package com.demo.demo.Service.scheduling.domain;

/**
 * Supported schedule kinds for periodic tasks.
 *
 * <p>{@code ONCE} tasks execute once and then transition to COMPLETED.
 * {@code INTERVAL} uses {@code intervalValue} and {@code intervalUnit}
 * (HOURS or DAYS) to compute the next execution.
 */
public enum ScheduleKind {
    ONCE,
    DAILY,
    WEEKLY,
    MONTHLY,
    INTERVAL
}
