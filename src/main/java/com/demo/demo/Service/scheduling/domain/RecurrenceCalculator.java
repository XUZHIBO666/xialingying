package com.demo.demo.Service.scheduling.domain;

import java.time.*;
import java.util.*;

/**
 * Calculates the next UTC execution instant for a validated {@link ScheduleRule}.
 *
 * <h3>DST handling</h3>
 * <ul>
 * <li><b>Gap (spring-forward):</b> non-existent local times move forward
 *     to the first valid time (ZonedDateTime default).</li>
 * <li><b>Overlap (fall-back):</b> times that occur twice use the earlier
 *     (summer-time) occurrence.</li>
 * </ul>
 *
 * <h3>Monthly edge cases</h3>
 * <p>If {@code dayOfMonth} (e.g. 31) does not exist in a month,
 * that month is skipped.
 */
public final class RecurrenceCalculator {

    private RecurrenceCalculator() {}

    /**
     * Compute the next UTC instant strictly after {@code after}.
     *
     * @return the next instant, or empty if the rule will never fire again
     *         (e.g. ONCE already passed)
     */
    public static Optional<Instant> next(ScheduleRule rule, ZoneId zoneId, Instant after) {
        return switch (rule.kind()) {
            case ONCE -> nextOnce(rule, zoneId, after);
            case DAILY -> nextDaily(rule, zoneId, after);
            case WEEKLY -> nextWeekly(rule, zoneId, after);
            case MONTHLY -> nextMonthly(rule, zoneId, after);
            case INTERVAL -> nextInterval(rule, after);
        };
    }

    // ---- ONCE ----

    private static Optional<Instant> nextOnce(ScheduleRule rule, ZoneId zoneId, Instant after) {
        ZonedDateTime at = rule.onceAt().atZone(zoneId);
        Instant instant = at.toInstant();
        return instant.isAfter(after) ? Optional.of(instant) : Optional.empty();
    }

    // ---- DAILY ----

    private static Optional<Instant> nextDaily(ScheduleRule rule, ZoneId zoneId, Instant after) {
        ZonedDateTime nowInZone = after.atZone(zoneId);
        ZonedDateTime candidate = nowInZone.with(rule.localTime());
        if (!candidate.toInstant().isAfter(after)) {
            candidate = candidate.plusDays(1);
        }
        return Optional.of(candidate.toInstant());
    }

    // ---- WEEKLY ----

    private static Optional<Instant> nextWeekly(ScheduleRule rule, ZoneId zoneId, Instant after) {
        ZonedDateTime nowInZone = after.atZone(zoneId);
        ZonedDateTime candidate = nowInZone.with(rule.localTime());

        // If candidate time today is not after 'after', start from tomorrow
        if (!candidate.toInstant().isAfter(after)) {
            candidate = candidate.plusDays(1);
        }

        // Walk forward until we hit a configured weekday
        for (int i = 0; i < 7; i++) {
            if (rule.daysOfWeek().contains(candidate.getDayOfWeek())) {
                return Optional.of(candidate.toInstant());
            }
            candidate = candidate.plusDays(1);
        }
        // Should never reach here — at least one day configured
        return Optional.empty();
    }

    // ---- MONTHLY ----

    private static Optional<Instant> nextMonthly(ScheduleRule rule, ZoneId zoneId, Instant after) {
        ZonedDateTime nowInZone = after.atZone(zoneId);
        int year = nowInZone.getYear();
        int month = nowInZone.getMonthValue();

        // Try current month first, then walk forward
        ZonedDateTime candidate = buildMonthlyCandidate(year, month, rule, zoneId);
        if (!candidate.toInstant().isAfter(after)) {
            // Move to next month
            month++;
            if (month > 12) { month = 1; year++; }
            candidate = buildMonthlyCandidate(year, month, rule, zoneId);
        }

        // If day doesn't exist this month, candidate was already adjusted
        return Optional.of(candidate.toInstant());
    }

    private static ZonedDateTime buildMonthlyCandidate(
            int year, int month, ScheduleRule rule, ZoneId zoneId) {
        int day = rule.dayOfMonth();
        int maxDay = YearMonth.of(year, month).lengthOfMonth();
        int actualDay = Math.min(day, maxDay);

        // If clamped, skip to next month
        if (actualDay != day) {
            month++;
            if (month > 12) { month = 1; year++; }
            actualDay = Math.min(day, YearMonth.of(year, month).lengthOfMonth());
        }

        return ZonedDateTime.of(year, month, actualDay,
                rule.localTime().getHour(), rule.localTime().getMinute(), 0, 0, zoneId);
    }

    // ---- INTERVAL ----

    private static Optional<Instant> nextInterval(ScheduleRule rule, Instant after) {
        long seconds = "HOURS".equals(rule.intervalUnit())
                ? rule.intervalValue() * 3600L
                : rule.intervalValue() * 86400L;
        return Optional.of(after.plusSeconds(seconds));
    }
}
