package com.demo.demo.Service.scheduling.domain;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.Optional;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class RecurrenceCalculatorTest {

    private static final Instant REF = Instant.parse("2026-07-28T04:00:00Z");
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final ZoneId NYC = ZoneId.of("America/New_York");

    @Test void onceShouldReturnWhenAfter() {
        var at = LocalDateTime.of(2026, 7, 28, 13, 0); // 13:00 SH = 05:00Z > 04:00Z REF
        Optional<Instant> r = RecurrenceCalculator.next(ScheduleRule.once(at), SHANGHAI, REF);
        assertTrue(r.isPresent());
        assertEquals(at.atZone(SHANGHAI).toInstant(), r.get());
    }
    @Test void onceShouldReturnEmptyAfterExecutionTime() {
        var at = LocalDateTime.of(2026, 7, 27, 12, 0);
        assertTrue(RecurrenceCalculator.next(ScheduleRule.once(at), SHANGHAI, REF).isEmpty());
    }
    @Test void dailyShouldUseRequestedZone() {
        var rule = ScheduleRule.daily(LocalTime.of(8, 0));
        Instant r = RecurrenceCalculator.next(rule, SHANGHAI, REF).orElseThrow();
        assertEquals(Instant.parse("2026-07-29T00:00:00Z"), r);
    }
    @Test void weeklyShouldChooseNextConfiguredWeekday() {
        var rule = ScheduleRule.weekly(Set.of(DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), LocalTime.of(8, 0));
        Instant r = RecurrenceCalculator.next(rule, SHANGHAI, REF).orElseThrow();
        ZonedDateTime zdt = r.atZone(SHANGHAI);
        assertEquals(LocalTime.of(8, 0), zdt.toLocalTime());
        assertTrue(zdt.getDayOfWeek() == DayOfWeek.WEDNESDAY || zdt.getDayOfWeek() == DayOfWeek.FRIDAY);
    }
    @Test void weeklyShouldSupportWeekdays() {
        var rule = ScheduleRule.weekly(
                Set.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
                LocalTime.of(8, 0));
        Instant r = RecurrenceCalculator.next(rule, SHANGHAI, REF).orElseThrow();
        ZonedDateTime zdt = r.atZone(SHANGHAI);
        assertTrue(zdt.getDayOfWeek().getValue() <= 5);
    }
    @Test void monthlyShouldSkipMonthWithoutRequestedDay() {
        var rule = ScheduleRule.monthly(31, LocalTime.of(8, 0));
        Instant r = RecurrenceCalculator.next(rule, SHANGHAI, REF).orElseThrow();
        ZonedDateTime zdt = r.atZone(SHANGHAI);
        assertEquals(31, zdt.getDayOfMonth());
    }
    @Test void intervalHoursShouldAdvanceFromPreviousSchedule() {
        var rule = ScheduleRule.intervalHours(6);
        Instant r = RecurrenceCalculator.next(rule, SHANGHAI, REF).orElseThrow();
        assertEquals(REF.plusSeconds(6 * 3600), r);
    }
    @Test void intervalDaysShouldAdvance() {
        var rule = ScheduleRule.intervalDays(1);
        Instant r = RecurrenceCalculator.next(rule, SHANGHAI, REF).orElseThrow();
        assertEquals(REF.plusSeconds(86400), r);
    }
    @Test void springDstGapShouldMoveToFirstValidLocalTime() {
        var ref = ZonedDateTime.of(2026, 3, 8, 1, 0, 0, 0, NYC).toInstant();
        var rule = ScheduleRule.daily(LocalTime.of(2, 30));
        Instant r = RecurrenceCalculator.next(rule, NYC, ref).orElseThrow();
        ZonedDateTime zdt = r.atZone(NYC);
        assertEquals(3, zdt.getHour());
    }
    @Test void autumnDstOverlapShouldChooseEarlierOffset() {
        var ref = ZonedDateTime.of(2026, 11, 1, 0, 30, 0, 0, NYC).toInstant();
        var rule = ScheduleRule.daily(LocalTime.of(1, 30));
        Instant r = RecurrenceCalculator.next(rule, NYC, ref).orElseThrow();
        ZonedDateTime zdt = r.atZone(NYC);
        assertEquals(1, zdt.getHour());
        assertTrue(zdt.getOffset().getId().startsWith("-04"),
                "should use earlier (summer) offset, got " + zdt.getOffset());
    }
}
