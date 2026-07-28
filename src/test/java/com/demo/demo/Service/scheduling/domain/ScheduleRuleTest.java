package com.demo.demo.Service.scheduling.domain;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ScheduleRuleTest {

    @Test void weeklyShouldRequireAtLeastOneDay() {
        assertThrows(IllegalArgumentException.class,
                () -> ScheduleRule.weekly(Set.of(), LocalTime.of(8, 0)));
    }
    @Test void monthlyShouldRejectDayAbove31() {
        assertThrows(IllegalArgumentException.class,
                () -> ScheduleRule.monthly(32, LocalTime.of(8, 0)));
    }
    @Test void monthlyShouldRejectDayBelow1() {
        assertThrows(IllegalArgumentException.class,
                () -> ScheduleRule.monthly(0, LocalTime.of(8, 0)));
    }
    @Test void intervalShouldRejectZero() {
        assertThrows(IllegalArgumentException.class, () -> ScheduleRule.intervalHours(0));
        assertThrows(IllegalArgumentException.class, () -> ScheduleRule.intervalDays(0));
    }
    @Test void intervalShouldRejectNegative() {
        assertThrows(IllegalArgumentException.class, () -> ScheduleRule.intervalHours(-1));
    }
    @Test void intervalShouldRejectBadUnit() {
        assertThrows(IllegalArgumentException.class, () ->
                new ScheduleRule(ScheduleKind.INTERVAL, null, null, null, null, 1, "MINUTES"));
    }
    @Test void dailyShouldRequireLocalTime() {
        assertThrows(NullPointerException.class,
                () -> ScheduleRule.daily(null));
    }
    @Test void factoriesShouldProduceCorrectKind() {
        assertEquals(ScheduleKind.ONCE, ScheduleRule.once(LocalDateTime.now()).kind());
        assertEquals(ScheduleKind.DAILY, ScheduleRule.daily(LocalTime.NOON).kind());
        assertEquals(ScheduleKind.WEEKLY,
                ScheduleRule.weekly(Set.of(DayOfWeek.MONDAY), LocalTime.NOON).kind());
        assertEquals(ScheduleKind.MONTHLY, ScheduleRule.monthly(15, LocalTime.NOON).kind());
        assertEquals(ScheduleKind.INTERVAL, ScheduleRule.intervalHours(2).kind());
    }
}
