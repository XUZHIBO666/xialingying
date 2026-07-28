package com.demo.demo.Service.scheduling.domain;

/**
 * Status of a single execution attempt.
 *
 * <p>Transitions:
 * <pre>
 * PENDING → RUNNING → SUCCEEDED
 * PENDING → RUNNING → DEGRADED (partial success)
 * PENDING → RUNNING → RETRY → PENDING (retry)
 * PENDING → RUNNING → FAILED  (terminal)
 * </pre>
 *
 * {@code RETRY} is set when a recoverable error occurs and another
 * attempt is scheduled via {@code nextAttemptAt}.
 */
public enum ExecutionStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    DEGRADED,
    FAILED,
    RETRY
}
