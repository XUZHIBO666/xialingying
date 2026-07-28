package com.demo.demo.Service.scheduling.execution;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * Determines whether and when to retry a failed execution.
 *
 * <p>Terminal errors abort immediately. Recoverable errors retry up to
 * {@code MAX_ATTEMPTS} with exponential backoff.
 */
public class RetryPolicy {

    static final int MAX_ATTEMPTS = 3;
    static final Duration BASE_DELAY = Duration.ofSeconds(30);

    private static final Set<String> RECOVERABLE_ERRORS = Set.of(
            "PROVIDER_TIMEOUT",      // weather API timeout
            "PROVIDER_UNAVAILABLE",   // weather API down
            "SDK_ERROR",              // iLink transient error
            "AGENT_FAILURE"           // content agent error
    );

    private static final Set<String> TERMINAL_ERRORS = Set.of(
            "BOT_OFFLINE",
            "SESSION_EXPIRED",
            "TARGET_NOT_FOUND"
    );

    private RetryPolicy() {
    }

    /**
     * Determine the next retry time, if any.
     *
     * @param attemptCount current attempt number (1-based, BEFORE the next retry)
     * @param errorCode    error from the last execution
     * @param now          current time
     * @return next attempt instant, or empty if no retry should occur
     */
    public static Optional<Instant> nextAttempt(int attemptCount, String errorCode, Instant now) {
        if (errorCode == null) return Optional.empty();
        if (TERMINAL_ERRORS.contains(errorCode)) return Optional.empty();
        if (attemptCount >= MAX_ATTEMPTS) return Optional.empty();
        if (!RECOVERABLE_ERRORS.contains(errorCode)) return Optional.empty();

        long delaySeconds = BASE_DELAY.getSeconds() * (1L << (attemptCount - 1)); // 30, 60, 120
        long capped = Math.min(delaySeconds, 600); // max 10 minutes
        return Optional.of(now.plusSeconds(capped));
    }
}
