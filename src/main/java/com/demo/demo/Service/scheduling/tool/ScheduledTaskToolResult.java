package com.demo.demo.Service.scheduling.tool;

/**
 * Structured result returned by scheduling tools to the LLM.
 * The model branches on {@code status} and reads {@code message}
 * for user-facing text.
 */
public record ScheduledTaskToolResult(String status, String message) {

    public static ScheduledTaskToolResult ok(String message) {
        return new ScheduledTaskToolResult("OK", message);
    }

    public static ScheduledTaskToolResult error(String message) {
        return new ScheduledTaskToolResult("ERROR", message);
    }
}
