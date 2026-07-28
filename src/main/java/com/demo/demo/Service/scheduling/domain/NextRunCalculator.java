package com.demo.demo.Service.scheduling.domain;

import java.time.*;

/**
 * Calculates the next daily run instant given a wall-clock time and time zone.
 *
 * <h3>DST handling</h3>
 * <ul>
 *   <li><b>Gap (spring-forward):</b> when {@code localTime} falls in a
 *       non-existent clock hour, {@link ZonedDateTime#with(LocalTime)}
 *       automatically moves forward to the first valid time.
 *       Example: {@code 02:30 America/New_York} on spring-forward day
 *       resolves to {@code 03:00}.</li>
 *   <li><b>Overlap (fall-back):</b> when {@code localTime} occurs twice,
 *       {@code ZonedDateTime} uses the earlier (summer-time) occurrence
 *       by default. Example: {@code 01:30 America/New_York} on fall-back
 *       day resolves to the first {@code 01:30 EDT}.</li>
 * </ul>
 *
 * <p>This class cannot be instantiated.
 */
public final class NextRunCalculator {

    private NextRunCalculator() {
    }

    /**
     * Compute the next instant at or after {@code after} when the wall clock
     * in {@code zoneId} reads {@code localTime}.
     *
     * <p>If {@code localTime} today (in the given zone) is strictly after
     * {@code after}, the result is today; otherwise it is tomorrow.
     *
     * @param localTime wall-clock time (e.g. {@code 08:00})
     * @param zoneId    IANA time zone (e.g. {@code Asia/Shanghai})
     * @param after     reference instant (typically "now")
     * @return the next UTC instant matching the schedule
     */
    /**
     * @deprecated use {@link RecurrenceCalculator#next(ScheduleRule, ZoneId, Instant)}
     *             with {@link ScheduleRule#daily(LocalTime)} instead.
     */
    @Deprecated
    public static Instant nextDailyRun(LocalTime localTime, ZoneId zoneId, Instant after) {
        return RecurrenceCalculator.next(
                ScheduleRule.daily(localTime), zoneId, after).orElseThrow();
    }
}
