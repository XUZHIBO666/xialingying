package com.demo.demo.Service.scheduling.execution;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RetryPolicyTest {

    private static final Instant NOW = Instant.parse("2026-07-28T04:00:00Z");

    @Test
    void shouldRetryRecoverableError() {
        Optional<Instant> next = RetryPolicy.nextAttempt(1, "SDK_ERROR", NOW);
        assertTrue(next.isPresent());
        assertEquals(NOW.plusSeconds(30), next.get());
    }

    @Test
    void shouldApplyExponentialBackoff() {
        Optional<Instant> r1 = RetryPolicy.nextAttempt(1, "SDK_ERROR", NOW);
        Optional<Instant> r2 = RetryPolicy.nextAttempt(2, "SDK_ERROR", NOW);

        assertEquals(NOW.plusSeconds(30), r1.get());
        assertEquals(NOW.plusSeconds(60), r2.get());
    }

    @Test
    void shouldCapAtMaxAttempts() {
        Optional<Instant> r3 = RetryPolicy.nextAttempt(3, "SDK_ERROR", NOW);
        assertTrue(r3.isEmpty(), "3rd attempt (index 4) should not retry");
    }

    @Test
    void shouldNotRetryTerminalErrors() {
        assertTrue(RetryPolicy.nextAttempt(1, "BOT_OFFLINE", NOW).isEmpty());
        assertTrue(RetryPolicy.nextAttempt(1, "SESSION_EXPIRED", NOW).isEmpty());
        assertTrue(RetryPolicy.nextAttempt(1, "TARGET_NOT_FOUND", NOW).isEmpty());
    }

    @Test
    void shouldNotRetryUnknownErrorCodes() {
        assertTrue(RetryPolicy.nextAttempt(1, "RANDOM_ERROR", NOW).isEmpty());
    }

    @Test
    void shouldNotRetryNullErrorCode() {
        assertTrue(RetryPolicy.nextAttempt(1, null, NOW).isEmpty());
    }

    @Test
    void shouldNotRetryBeyondMaxAttempts() {
        // 4th execution (attempt index 4) with remaining tries = 0
        assertTrue(RetryPolicy.nextAttempt(4, "SDK_ERROR", NOW).isEmpty());
    }

    @Test
    void shouldCapBackoffTo10Minutes() {
        // Attempt 2 → 30s * 2^1 = 60s, within cap
        Optional<Instant> r = RetryPolicy.nextAttempt(2, "SDK_ERROR", NOW);
        assertTrue(r.isPresent());
        long seconds = Duration.between(NOW, r.get()).getSeconds();
        assertEquals(60, seconds, "attempt 2 should yield 60s delay");
        assertTrue(seconds <= 600, "should cap at 10 minutes");
    }

    @Test
    void weatherTimeoutShouldBeRecoverable() {
        assertTrue(RetryPolicy.nextAttempt(1, "PROVIDER_TIMEOUT", NOW).isPresent());
    }

    @Test
    void weatherUnavailableShouldBeRecoverable() {
        assertTrue(RetryPolicy.nextAttempt(1, "PROVIDER_UNAVAILABLE", NOW).isPresent());
    }

    @Test
    void agentFailureShouldBeRecoverable() {
        assertTrue(RetryPolicy.nextAttempt(1, "AGENT_FAILURE", NOW).isPresent());
    }
}
