package com.demo.demo.Service.scheduling.application;

import com.demo.demo.Service.scheduling.domain.ScheduledTask;
import com.demo.demo.Service.scheduling.domain.ScheduledTaskStatus;

import java.time.Instant;

/**
 * Public-facing summary of a scheduled task. Does not expose
 * internal IDs, version numbers, or owner information.
 */
public record ScheduledTaskSummary(
        String taskId,
        String taskType,
        ScheduledTaskStatus status,
        String scheduleDescription,
        String timeZone,
        Instant nextRunAt) {

    public static ScheduledTaskSummary from(ScheduledTask task) {
        return new ScheduledTaskSummary(
                task.taskId(), task.taskType(), task.status(),
                describe(task), task.timeZone(), task.nextRunAt());
    }

    private static String describe(ScheduledTask task) {
        if (TASK_TYPE_DAILY_WEATHER.equals(task.taskType())) {
            try {
                WeatherTaskPayload wp = task.weatherPayload();
                return wp.location() + " 每日天气";
            } catch (Exception e) { /* ignore */ }
        }
        ScheduleRule rule = ScheduleRuleCodec.read(task.scheduleExpression());
        return rule.kind().name();
    }
}
