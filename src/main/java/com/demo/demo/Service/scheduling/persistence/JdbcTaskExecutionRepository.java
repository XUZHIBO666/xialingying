package com.demo.demo.Service.scheduling.persistence;

import com.demo.demo.Service.scheduling.domain.ExecutionStatus;
import com.demo.demo.Service.scheduling.domain.TaskExecution;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcTaskExecutionRepository implements TaskExecutionRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<TaskExecution> ROW_MAPPER = (rs, rowNum) ->
            new TaskExecution(
                    rs.getLong("id"),
                    rs.getString("execution_id"),
                    rs.getString("task_id"),
                    Instant.ofEpochMilli(rs.getLong("scheduled_for")),
                    ExecutionStatus.valueOf(rs.getString("status")),
                    rs.getInt("attempt_count"),
                    nullableEpoch(rs, "next_attempt_at"),
                    rs.getString("error_code"),
                    nullableEpoch(rs, "lease_until"),
                    Instant.ofEpochMilli(rs.getLong("created_at")),
                    Instant.ofEpochMilli(rs.getLong("updated_at")));

    public JdbcTaskExecutionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<TaskExecution> insertUnique(String taskId, Instant scheduledFor, Instant now) {
        TaskExecution execution = TaskExecution.createPending(taskId, scheduledFor, now);
        long nowEpoch = now.toEpochMilli();
        try {
            jdbc.update(
                    "INSERT INTO scheduled_task_execution (execution_id, task_id, scheduled_for, status, attempt_count, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    execution.executionId(),
                    taskId,
                    scheduledFor.toEpochMilli(),
                    ExecutionStatus.PENDING.name(),
                    0,
                    nowEpoch,
                    nowEpoch);
            return findById(execution.executionId());
        } catch (UncategorizedSQLException e) {
            // SQLite UNIQUE constraint violation: (task_id, scheduled_for) already exists
            if (e.getMessage() != null && e.getMessage().contains("SQLITE_CONSTRAINT_UNIQUE")) {
                return Optional.empty();
            }
            throw e; // Other errors propagate
        }
    }

    @Override
    public Optional<TaskExecution> findById(String executionId) {
        var list = jdbc.query(
                "SELECT id, execution_id, task_id, scheduled_for, status, attempt_count, next_attempt_at, error_code, lease_until, created_at, updated_at FROM scheduled_task_execution WHERE execution_id = ?",
                ROW_MAPPER, executionId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public boolean claim(String executionId, Instant now, Duration leaseTimeout) {
        Instant leaseUntil = now.plus(leaseTimeout);
        int rows = jdbc.update(
                "UPDATE scheduled_task_execution SET status = 'RUNNING', lease_until = ?, updated_at = ?, attempt_count = attempt_count + 1 WHERE execution_id = ? AND status IN ('PENDING', 'RETRY') AND (lease_until IS NULL OR lease_until < ?)",
                leaseUntil.toEpochMilli(), now.toEpochMilli(), executionId, now.toEpochMilli());
        return rows == 1;
    }

    @Override
    public boolean markSucceeded(String executionId, Instant finishedAt) {
        int rows = jdbc.update(
                "UPDATE scheduled_task_execution SET status = 'SUCCEEDED', updated_at = ? WHERE execution_id = ? AND status = 'RUNNING'",
                finishedAt.toEpochMilli(), executionId);
        return rows == 1;
    }

    @Override
    public boolean scheduleRetry(String executionId, Instant nextAttempt, String errorCode, Instant now) {
        int rows = jdbc.update(
                "UPDATE scheduled_task_execution SET status = 'RETRY', next_attempt_at = ?, error_code = ?, updated_at = ? WHERE execution_id = ? AND status = 'RUNNING'",
                nextAttempt.toEpochMilli(), errorCode, now.toEpochMilli(), executionId);
        return rows == 1;
    }

    @Override
    public boolean markDegraded(String executionId, String errorCode, Instant finishedAt) {
        int rows = jdbc.update(
                "UPDATE scheduled_task_execution SET status = 'DEGRADED', error_code = ?, updated_at = ? WHERE execution_id = ? AND status = 'RUNNING'",
                errorCode, finishedAt.toEpochMilli(), executionId);
        return rows == 1;
    }

    @Override
    public boolean markFailed(String executionId, String errorCode, Instant finishedAt) {
        int rows = jdbc.update(
                "UPDATE scheduled_task_execution SET status = 'FAILED', error_code = ?, updated_at = ? WHERE execution_id = ? AND status = 'RUNNING'",
                errorCode, finishedAt.toEpochMilli(), executionId);
        return rows == 1;
    }

    @Override
    public int recoverExpiredRunning(Instant now, Duration leaseTimeout) {
        return jdbc.update(
                "UPDATE scheduled_task_execution SET status = 'PENDING', lease_until = NULL, error_code = 'LEASE_EXPIRED', updated_at = ? WHERE status = 'RUNNING' AND lease_until IS NOT NULL AND lease_until < ?",
                now.toEpochMilli(), now.toEpochMilli());
    }

    private static Instant nullableEpoch(ResultSet rs, String column) throws SQLException {
        long val = rs.getLong(column);
        if (rs.wasNull()) {
            return null;
        }
        return Instant.ofEpochMilli(val);
    }
}
