package com.demo.demo.Service.scheduling.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A single execution attempt for a scheduled task.
 *
 * <p>Uniqueness is enforced by the {@code UNIQUE(task_id, scheduled_for)}
 * constraint in the database. The scanner creates a {@code PENDING} row
 * to claim the slot, then the executor transitions it through
 * {@code RUNNING → SUCCEEDED/FAILED/RETRY}.
 */
public record TaskExecution(
        Long id,
        String executionId,
        String taskId,
        Instant scheduledFor,
        ExecutionStatus status,
        int attemptCount,
        Instant nextAttemptAt,
        String errorCode,
        Instant leaseUntil,
        Instant createdAt,
        Instant updatedAt) {

    /** Create a new pending execution, ready to be claimed. */
    public static TaskExecution createPending(
            String taskId, Instant scheduledFor, Instant now) {
        return new TaskExecution(
                null,
                UUID.randomUUID().toString(),
                Objects.requireNonNull(taskId, "taskId"),
                Objects.requireNonNull(scheduledFor, "scheduledFor"),
                ExecutionStatus.PENDING,
                0,
                null,
                null,
                null,
                now,
                now);
    }

    // ---- state transitions ----

    public TaskExecution markRunning(Instant now, Instant leaseUntil) {
        if (status != ExecutionStatus.PENDING && status != ExecutionStatus.RETRY) {
            throw new IllegalStateException(
                    "Only PENDING or RETRY can transition to RUNNING, current=" + status);
        }
        return new TaskExecution(
                id, executionId, taskId, scheduledFor,
                ExecutionStatus.RUNNING,
                attemptCount + 1,
                null, errorCode,
                leaseUntil,
                createdAt, now);
    }

    public TaskExecution markSucceeded(Instant now) {
        if (status != ExecutionStatus.RUNNING) {
            throw new IllegalStateException(
                    "Only RUNNING can transition to SUCCEEDED, current=" + status);
        }
        return new TaskExecution(
                id, executionId, taskId, scheduledFor,
                ExecutionStatus.SUCCEEDED,
                attemptCount,
                null, null, null,
                createdAt, now);
    }

    public TaskExecution markFailed(String errorCode, Instant now) {
        if (status != ExecutionStatus.RUNNING) {
            throw new IllegalStateException(
                    "Only RUNNING can transition to FAILED, current=" + status);
        }
        return new TaskExecution(
                id, executionId, taskId, scheduledFor,
                ExecutionStatus.FAILED,
                attemptCount,
                null, errorCode, null,
                createdAt, now);
    }

    public TaskExecution scheduleRetry(String errorCode, Instant nextAttempt, Instant now) {
        if (status != ExecutionStatus.RUNNING) {
            throw new IllegalStateException(
                    "Only RUNNING can schedule a retry, current=" + status);
        }
        return new TaskExecution(
                id, executionId, taskId, scheduledFor,
                ExecutionStatus.RETRY,
                attemptCount,
                nextAttempt, errorCode, null,
                createdAt, now);
    }
}
