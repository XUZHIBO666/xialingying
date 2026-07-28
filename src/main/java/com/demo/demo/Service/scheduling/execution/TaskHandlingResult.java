package com.demo.demo.Service.scheduling.execution;

/**
 * Result returned by a {@link ScheduledTaskHandler}.
 *
 * <ul>
 * <li>{@code SUCCEEDED} — the handler completed normally.</li>
 * <li>{@code DEGRADED} — the handler produced a partial result
 *     (e.g. text-only when an image could not be generated).</li>
 * <li>{@code FAILED} — the handler could not complete; the caller
 *     determines retry or terminal failure based on {@code errorCode}.</li>
 * </ul>
 */
public record TaskHandlingResult(Status status, String errorCode) {

    public enum Status {
        SUCCEEDED, DEGRADED, FAILED
    }

    public static TaskHandlingResult succeeded() {
        return new TaskHandlingResult(Status.SUCCEEDED, null);
    }

    public static TaskHandlingResult degraded(String errorCode) {
        return new TaskHandlingResult(Status.DEGRADED, errorCode);
    }

    public static TaskHandlingResult failed(String errorCode) {
        return new TaskHandlingResult(Status.FAILED, errorCode);
    }
}
