package com.demo.demo.Service.scheduling.domain;

import java.time.*;
import java.util.*;

/**
 * Validated, structured schedule rule. Only one {@code kind} is active;
 * fields that do not match the kind are rejected at construction.
 *
 * <p>Rule types and their required fields:
 * <ul>
 * <li>{@code ONCE} — {@code onceAt} (non-null)</li>
 * <li>{@code DAILY} — {@code localTime} (non-null)</li>
 * <li>{@code WEEKLY} — {@code daysOfWeek} (non-empty), {@code localTime}</li>
 * <li>{@code MONTHLY} — {@code dayOfMonth} (1-31), {@code localTime}</li>
 * <li>{@code INTERVAL} — {@code intervalValue} (≥1), {@code intervalUnit} (HOURS or DAYS)</li>
 * </ul>
 *
 * <p>This record cannot express arbitrary Cron. The model extracts structured
 * fields; this class validates them before persistence.
 */
public record ScheduleRule(
        ScheduleKind kind,
        LocalDateTime onceAt,
        LocalTime localTime,
        Set<DayOfWeek> daysOfWeek,
        Integer dayOfMonth,
        Integer intervalValue,
        String intervalUnit) {

    public ScheduleRule {
        Objects.requireNonNull(kind, "Schedule kind is required");
        switch (kind) {
            case ONCE -> Objects.requireNonNull(onceAt, "ONCE requires onceAt");
            case DAILY -> Objects.requireNonNull(localTime, "DAILY requires localTime");
            case WEEKLY -> {
                Objects.requireNonNull(localTime, "WEEKLY requires localTime");
                Objects.requireNonNull(daysOfWeek, "WEEKLY requires daysOfWeek");
                if (daysOfWeek.isEmpty())
                    throw new IllegalArgumentException("WEEKLY requires at least one day");
            }
            case MONTHLY -> {
                Objects.requireNonNull(localTime, "MONTHLY requires localTime");
                Objects.requireNonNull(dayOfMonth, "MONTHLY requires dayOfMonth");
                if (dayOfMonth < 1 || dayOfMonth > 31)
                    throw new IllegalArgumentException("MONTHLY dayOfMonth must be 1-31");
            }
            case INTERVAL -> {
                Objects.requireNonNull(intervalValue, "INTERVAL requires intervalValue");
                Objects.requireNonNull(intervalUnit, "INTERVAL requires intervalUnit");
                if (intervalValue < 1)
                    throw new IllegalArgumentException("INTERVAL requires intervalValue >= 1");
                if (!"HOURS".equals(intervalUnit) && !"DAYS".equals(intervalUnit))
                    throw new IllegalArgumentException("INTERVAL unit must be HOURS or DAYS");
            }
        }
    }

    // ---- static factories ----

    public static ScheduleRule once(LocalDateTime at) {
        return new ScheduleRule(ScheduleKind.ONCE, at, null, null, null, null, null);
    }

    public static ScheduleRule daily(LocalTime time) {
        return new ScheduleRule(ScheduleKind.DAILY, null, time, null, null, null, null);
    }

    public static ScheduleRule weekly(Set<DayOfWeek> days, LocalTime time) {
        return new ScheduleRule(ScheduleKind.WEEKLY, null, time,
                Set.copyOf(days), null, null, null);
    }

    public static ScheduleRule monthly(int dayOfMonth, LocalTime time) {
        return new ScheduleRule(ScheduleKind.MONTHLY, null, time,
                null, dayOfMonth, null, null);
    }

    public static ScheduleRule intervalHours(int hours) {
        return new ScheduleRule(ScheduleKind.INTERVAL, null, null,
                null, null, hours, "HOURS");
    }

    public static ScheduleRule intervalDays(int days) {
        return new ScheduleRule(ScheduleKind.INTERVAL, null, null,
                null, null, days, "DAYS");
    }
}
