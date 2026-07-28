package com.demo.demo.Service.scheduling.persistence;

import com.demo.demo.Service.scheduling.domain.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcScheduledTaskRepository implements ScheduledTaskRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<ScheduledTask> ROW_MAPPER = (rs, rowNum) ->
            new ScheduledTask(
                    rs.getLong("id"),
                    rs.getString("task_id"),
                    rs.getString("owner_target_id"),
                    rs.getString("task_type"),
                    ScheduledTaskStatus.valueOf(rs.getString("status")),
                    rs.getString("location"),
                    rs.getString("local_time"),
                    rs.getString("time_zone"),
                    rs.getString("payload"),
                    Instant.ofEpochMilli(rs.getLong("next_run_at")),
                    rs.getInt("version"),
                    Instant.ofEpochMilli(rs.getLong("created_at")),
                    Instant.ofEpochMilli(rs.getLong("updated_at")));

    public JdbcScheduledTaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ScheduledTask insert(ScheduledTask task) {
        long nowEpoch = task.updatedAt().toEpochMilli();
        jdbc.update(
                "INSERT INTO scheduled_task (task_id, owner_target_id, task_type, status, location, local_time, time_zone, payload, next_run_at, version, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                task.taskId(),
                task.ownerTargetId(),
                task.taskType(),
                task.status().name(),
                task.location(),
                task.localTime(),
                task.timeZone(),
                task.payload(),
                task.nextRunAt().toEpochMilli(),
                task.version(),
                task.createdAt().toEpochMilli(),
                nowEpoch);

        // Re-read to get the auto-generated id
        return findByTaskId(task.taskId()).orElseThrow(
                () -> new IllegalStateException("Failed to read back inserted task " + task.taskId()));
    }

    @Override
    public List<ScheduledTask> findByOwner(String targetId) {
        return jdbc.query(
                "SELECT id, task_id, owner_target_id, task_type, status, location, local_time, time_zone, payload, next_run_at, version, created_at, updated_at FROM scheduled_task WHERE owner_target_id = ? ORDER BY created_at DESC",
                ROW_MAPPER, targetId);
    }

    @Override
    public Optional<ScheduledTask> findByTaskId(String taskId) {
        var list = jdbc.query(
                "SELECT id, task_id, owner_target_id, task_type, status, location, local_time, time_zone, payload, next_run_at, version, created_at, updated_at FROM scheduled_task WHERE task_id = ?",
                ROW_MAPPER, taskId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public boolean updateStatusOwned(String taskId, String ownerTargetId,
                                     ScheduledTaskStatus newStatus, int expectedVersion, Instant now) {
        int rows = jdbc.update(
                "UPDATE scheduled_task SET status = ?, version = version + 1, updated_at = ? WHERE task_id = ? AND owner_target_id = ? AND version = ?",
                newStatus.name(), now.toEpochMilli(), taskId, ownerTargetId, expectedVersion);
        return rows == 1;
    }

    @Override
    public boolean advanceNextRun(String taskId, int expectedVersion, Instant newNextRun, Instant now) {
        int rows = jdbc.update(
                "UPDATE scheduled_task SET next_run_at = ?, version = version + 1, updated_at = ? WHERE task_id = ? AND version = ? AND status = 'ACTIVE'",
                newNextRun.toEpochMilli(), now.toEpochMilli(), taskId, expectedVersion);
        return rows == 1;
    }

    @Override
    public List<ScheduledTask> findDue(Instant now, int batchSize) {
        return jdbc.query(
                "SELECT id, task_id, owner_target_id, task_type, status, location, local_time, time_zone, payload, next_run_at, version, created_at, updated_at FROM scheduled_task WHERE next_run_at <= ? AND status = 'ACTIVE' ORDER BY next_run_at ASC LIMIT ?",
                ROW_MAPPER, now.toEpochMilli(), batchSize);
    }
}
