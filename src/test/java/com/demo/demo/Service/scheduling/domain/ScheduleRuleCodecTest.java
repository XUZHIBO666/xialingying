package com.demo.demo.Service.scheduling.domain;

import com.demo.demo.Service.scheduling.application.SchedulingException;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ScheduleRuleCodecTest {

    @Test void dailyRoundTrip() {
        var rule = ScheduleRule.daily(LocalTime.of(8, 0));
        String json = ScheduleRuleCodec.write(rule);
        assertEquals(rule, ScheduleRuleCodec.read(json));
    }
    @Test void weeklyRoundTrip() {
        var rule = ScheduleRule.weekly(Set.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), LocalTime.of(8, 0));
        String json = ScheduleRuleCodec.write(rule);
        assertEquals(rule, ScheduleRuleCodec.read(json));
    }
    @Test void monthlyRoundTrip() {
        var rule = ScheduleRule.monthly(15, LocalTime.of(9, 30));
        String json = ScheduleRuleCodec.write(rule);
        assertEquals(rule, ScheduleRuleCodec.read(json));
    }
    @Test void intervalRoundTrip() {
        var rule = ScheduleRule.intervalHours(6);
        String json = ScheduleRuleCodec.write(rule);
        assertEquals(rule, ScheduleRuleCodec.read(json));
    }
    @Test void onceRoundTrip() {
        var rule = ScheduleRule.once(LocalDateTime.of(2026, 7, 28, 10, 0));
        String json = ScheduleRuleCodec.write(rule);
        assertEquals(rule, ScheduleRuleCodec.read(json));
    }
    @Test void shouldRejectInvalidJson() {
        assertThrows(SchedulingException.class, () -> ScheduleRuleCodec.read("not json"));
    }
    @Test void shouldRejectUnknownKind() {
        assertThrows(SchedulingException.class,
                () -> ScheduleRuleCodec.read("{\"kind\":\"CRON\"}"));
    }
}
