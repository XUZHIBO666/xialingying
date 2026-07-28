package com.demo.demo.Service.scheduling.domain;

/**
 * Lifecycle status of a scheduled task.
 *
 * <p>MVP only uses the {@code DAILY_WEATHER} task type, so the status
 * transitions are: ACTIVE → PAUSED → ACTIVE, or ACTIVE → CANCELED.
 */
public enum ScheduledTaskStatus {
    ACTIVE,
    PAUSED,
    CANCELED,
    COMPLETED
}
