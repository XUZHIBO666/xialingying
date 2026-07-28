package com.demo.demo.Service.scheduling.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.*;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

class NextRunCalculatorTest {

    // Reference "now" for deterministic tests: 2026-07-28T03:00:00Z (11:00 AM in Asia/Shanghai)
    private static final Instant REFERENCE = Instant.parse("2026-07-28T03:00:00Z");

    // ==================== normal cases ====================

    @Test
    void shouldReturnLaterTodayWhenTimeNotYetReached() {
        // 08:00 Shanghai today = 2026-07-28T00:00:00Z, which is before REFERENCE 03:00Z
        // Actually 08:00 on 2026-07-28 Shanghai = 00:00Z, which IS before 03:00Z
        // Let me use 20:00 Shanghai = 12:00Z, which is AFTER 03:00Z
        Instant result = NextRunCalculator.nextDailyRun(
                LocalTime.of(20, 0),
                ZoneId.of("Asia/Shanghai"),
                REFERENCE);

        // 20:00 Shanghai on 2026-07-28 = 2026-07-28T12:00:00Z
        Instant expected = Instant.parse("2026-07-28T12:00:00Z");
        assertEquals(expected, result, "20:00 Shanghai should be later today");
    }

    @Test
    void shouldReturnTomorrowWhenTimeAlreadyPassed() {
        // 02:00 Shanghai = 2026-07-27T18:00Z, which is before REFERENCE 03:00Z
        Instant result = NextRunCalculator.nextDailyRun(
                LocalTime.of(2, 0),
                ZoneId.of("Asia/Shanghai"),
                REFERENCE);

        // 02:00 Shanghai tomorrow = 2026-07-29T02:00+08:00 = 2026-07-28T18:00:00Z
        Instant expected = Instant.parse("2026-07-28T18:00:00Z");
        assertEquals(expected, result, "02:00 Shanghai should be tomorrow");
    }

    @Test
    void shouldReturnTomorrowWhenExactSameMinute() {
        // REFERENCE = 2026-07-28T03:00:00Z = 11:00 Shanghai
        Instant afterAt11 = ZonedDateTime.of(2026, 7, 28, 11, 0, 0, 0, ZoneId.of("Asia/Shanghai"))
                .toInstant();
        Instant result = NextRunCalculator.nextDailyRun(
                LocalTime.of(11, 0),
                ZoneId.of("Asia/Shanghai"),
                afterAt11);

        // Should be 11:00 tomorrow (2026-07-29T03:00:00Z)
        Instant expected = Instant.parse("2026-07-29T03:00:00Z");
        assertEquals(expected, result);
    }

    // ==================== cross-midnight UTC ====================

    @Test
    void shouldComputeCorrectlyWhenLocalMidnightCrossesUtcDay() {
        // After 2026-07-28T16:00:00Z (midnight Jul 29 Shanghai)
        // Next 08:00 Shanghai = 2026-07-29T08:00+08 = 2026-07-29T00:00:00Z
        Instant afterMidnight = Instant.parse("2026-07-28T16:00:01Z");
        Instant result = NextRunCalculator.nextDailyRun(
                LocalTime.of(8, 0),
                ZoneId.of("Asia/Shanghai"),
                afterMidnight);

        Instant expected = Instant.parse("2026-07-29T00:00:00Z");
        assertEquals(expected, result);
    }

    // ==================== UTC (no DST) baseline ====================

    @Test
    void shouldWorkWithUtcZone() {
        // REFERENCE = 03:00Z, 08:00 UTC today is after → today
        Instant result = NextRunCalculator.nextDailyRun(
                LocalTime.of(8, 0),
                ZoneId.of("UTC"),
                REFERENCE);

        Instant expected = Instant.parse("2026-07-28T08:00:00Z");
        assertEquals(expected, result);
    }

    // ==================== DST gap: spring-forward ====================

    /**
     * DST gap (spring-forward): on 2026-03-08, America/New_York springs forward
     * from 02:00 EST to 03:00 EDT. The wall-clock time 02:30 does not exist.
     * {@code ZonedDateTime.with(LocalTime)} will move forward to 03:30 EDT.
     */
    @Test
    void shouldJumpForwardAcrossDstGap() {
        // 2026-03-08T01:00:00-05:00 = 2026-03-08T06:00:00Z (before the gap)
        Instant beforeGap = ZonedDateTime.of(2026, 3, 8, 1, 0, 0, 0, ZoneId.of("America/New_York"))
                .toInstant();

        Instant result = NextRunCalculator.nextDailyRun(
                LocalTime.of(2, 30),
                ZoneId.of("America/New_York"),
                beforeGap);

        // 02:30 doesn't exist → becomes 03:30 EDT = 2026-03-08T07:30:00Z
        Instant expected = Instant.parse("2026-03-08T07:30:00Z");
        assertEquals(expected, result,
                "02:30 during spring-forward gap should resolve to 03:30");
    }

    // ==================== DST overlap: fall-back ====================

    /**
     * DST overlap (fall-back): on 2026-11-01, America/New_York falls back
     * from 02:00 EDT to 01:00 EST. The wall-clock time 01:30 occurs twice.
     * {@code ZonedDateTime} uses the first (summer-time EDT) occurrence.
     */
    @Test
    void shouldUseFirstOccurrenceDuringDstOverlap() {
        // 2026-11-01T01:00:00-04:00 = 2026-11-01T05:00:00Z (first 01:00, EDT)
        Instant beforeOverlap = ZonedDateTime.of(2026, 11, 1, 0, 30, 0, 0, ZoneId.of("America/New_York"))
                .toInstant();

        Instant result = NextRunCalculator.nextDailyRun(
                LocalTime.of(1, 30),
                ZoneId.of("America/New_York"),
                beforeOverlap);

        // First 01:30 = EDT (UTC-4) = 2026-11-01T05:30:00Z
        Instant firstOccurrence = Instant.parse("2026-11-01T05:30:00Z");
        assertEquals(firstOccurrence, result,
                "01:30 during fall-back overlap should use the first (summer-time) occurrence");
    }

    // ==================== parameter validation ====================

    @Test
    void shouldRejectNullLocalTime() {
        assertThrows(RuntimeException.class, () ->
                NextRunCalculator.nextDailyRun(null, ZoneId.of("UTC"), REFERENCE));
    }

    @Test
    void shouldRejectNullZoneId() {
        assertThrows(RuntimeException.class, () ->
                NextRunCalculator.nextDailyRun(LocalTime.of(8, 0), null, REFERENCE));
    }

    @Test
    void shouldRejectNullAfter() {
        assertThrows(RuntimeException.class, () ->
                NextRunCalculator.nextDailyRun(LocalTime.of(8, 0), ZoneId.of("UTC"), null));
    }

    // ==================== parameterized: zone-daylight cross-check ====================

    @ParameterizedTest
    @CsvSource({
            // REFERENCE = 2026-07-28T03:00:00Z. 08:00 local in these zones:
            "Asia/Shanghai,      2026-07-29T00:00:00Z",  // today 08:00=00:00Z < REF → tomorrow
            "Asia/Tokyo,         2026-07-28T23:00:00Z",  // today 08:00=23:00Z(-1d) < REF → tomorrow
            "Europe/London,      2026-07-28T07:00:00Z",  // today 08:00 BST=07:00Z > REF → today
            "America/New_York,   2026-07-28T12:00:00Z",  // today 08:00 EDT=12:00Z > REF → today
            "America/Los_Angeles,2026-07-28T15:00:00Z",  // today 08:00 PDT=15:00Z > REF → today
            "UTC,                2026-07-28T08:00:00Z",  // today 08:00=08:00Z > REF → today
    })
    void shouldProduceCorrectUtcInstant(String zoneStr, String expectedStr) {
        Instant result = NextRunCalculator.nextDailyRun(
                LocalTime.of(8, 0),
                ZoneId.of(zoneStr),
                REFERENCE);

        Instant expected = Instant.parse(expectedStr);
        long diffMinutes = Math.abs(ChronoUnit.MINUTES.between(expected, result));
        assertTrue(diffMinutes <= 60,
                () -> zoneStr + ": expected ~" + expected + " but got " + result);
    }
}
