package com.demo.demo.Service.scheduling.persistence;

import com.demo.demo.Service.scheduling.domain.TaskExecution;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Repository for {@link TaskExecution} persistence.
 *
 * <p>The {@code UNIQUE(task_id, scheduled_for)} constraint in SQLite
 * is the sole mechanism for preventing duplicate execution records.
 */
public interface TaskExecutionRepository {

    /**
     * Insert a new pending execution, relying on the SQLite
     * {@code UNIQUE(task_id, scheduled_for)} constraint for deduplication.
     *
     * @param taskId       the task to execute
     * @param scheduledFor the planned execution time
     * @param now          current timestamp
     * @return the created execution, or empty if a duplicate already exists
     */
    Optional<TaskExecution> insertUnique(String taskId, Instant scheduledFor, Instant now);

    /**
     * Find an execution by its public identifier.
     */
    Optional<TaskExecution> findById(String executionId);

    /**
     * Claim an execution for work: atomically set status to RUNNING
     * if current status is PENDING or RETRY, or lease has expired.
     */
    boolean claim(String executionId, Instant now, Duration leaseTimeout);

    /**
     * Mark an execution as successfully completed.
     */
    boolean markSucceeded(String executionId, Instant finishedAt);

    /**
     * Schedule a retry with backoff delay.
     */
    boolean scheduleRetry(String executionId, Instant nextAttempt, String errorCode, Instant now);

    /**
     * Mark an execution as degraded (partial success, e.g. text-only
     * when image generation failed).
     */
    boolean markDegraded(String executionId, String errorCode, Instant finishedAt);

    /**
     * Mark an execution as permanently failed.
     */
    boolean markFailed(String executionId, String errorCode, Instant finishedAt);

    /**
     * Reset expired RUNNING executions back to PENDING for recovery.
     *
     * @return number of recovered executions
     */
    int recoverExpiredRunning(Instant now, Duration leaseTimeout);
}
