package com.demo.demo.Service.scheduling.domain;

import java.time.*;
import java.util.Objects;
import java.util.UUID;

/**
 * A scheduled task owned by a delivery target.
 *
 * <p>Schedule rules are stored in {@code scheduleKind} + {@code scheduleExpression}
 * (a validated JSON {@link ScheduleRule}). Task-type-specific data lives in {@code payload}.
 */
public record ScheduledTask(
        Long id,
        String taskId,
        String ownerTargetId,
        String taskType,
        ScheduledTaskStatus status,
        ScheduleKind scheduleKind,
        String scheduleExpression,
        String timeZone,
        String payload,
        Instant nextRunAt,
        int version,
        Instant createdAt,
        Instant updatedAt) {

    public static final String TASK_TYPE_DAILY_WEATHER = "DAILY_WEATHER";

    public static ScheduledTask createDailyWeather(
            String ownerTargetId, String location,
            LocalTime localTime, ZoneId zoneId, Instant nextRunAt, Instant now) {
        String taskId = UUID.randomUUID().toString();
        ScheduleRule rule = ScheduleRule.daily(localTime);
        return new ScheduledTask(null, taskId,
                Objects.requireNonNull(ownerTargetId),
                TASK_TYPE_DAILY_WEATHER, ScheduledTaskStatus.ACTIVE,
                rule.kind(), ScheduleRuleCodec.write(rule), zoneId.getId(),
                TaskPayloadCodec.writeWeatherPayload(new WeatherTaskPayload(location)),
                Objects.requireNonNull(nextRunAt), 0, now, now);
    }

    // ---- state transitions ----

    public ScheduledTask pause(Instant now) {
        if (status != ScheduledTaskStatus.ACTIVE)
            throw new IllegalStateException("Only ACTIVE can be paused");
        return new ScheduledTask(id, taskId, ownerTargetId, taskType,
                ScheduledTaskStatus.PAUSED,
                scheduleKind, scheduleExpression, timeZone, payload,
                nextRunAt, version, createdAt, now);
    }

    public ScheduledTask resume(Instant now) {
        if (status != ScheduledTaskStatus.PAUSED)
            throw new IllegalStateException("Only PAUSED can be resumed");
        ScheduleRule rule = ScheduleRuleCodec.read(scheduleExpression);
        ZoneId zoneId = ZoneId.of(timeZone);
        Instant newNextRun = RecurrenceCalculator.next(rule, zoneId, now).orElseThrow();
        return new ScheduledTask(id, taskId, ownerTargetId, taskType,
                ScheduledTaskStatus.ACTIVE,
                scheduleKind, scheduleExpression, timeZone, payload,
                newNextRun, version, createdAt, now);
    }

    public ScheduledTask cancel(Instant now) {
        if (status == ScheduledTaskStatus.CANCELED)
            throw new IllegalStateException("Already canceled");
        return new ScheduledTask(id, taskId, ownerTargetId, taskType,
                ScheduledTaskStatus.CANCELED,
                scheduleKind, scheduleExpression, timeZone, payload,
                nextRunAt, version, createdAt, now);
    }

    public ScheduledTask advanceNextRun(Instant newNextRun, int expectedVersion, Instant now) {
        if (this.version != expectedVersion)
            throw new IllegalStateException("Version conflict");
        return new ScheduledTask(id, taskId, ownerTargetId, taskType, status,
                scheduleKind, scheduleExpression, timeZone, payload,
                newNextRun, version + 1, createdAt, now);
    }

    /** Convenience: extract weather payload or throw. */
    public WeatherTaskPayload weatherPayload() {
        return TaskPayloadCodec.readWeatherPayload(payload);
    }
}
