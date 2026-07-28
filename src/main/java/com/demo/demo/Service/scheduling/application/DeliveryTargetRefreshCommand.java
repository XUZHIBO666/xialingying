package com.demo.demo.Service.scheduling.application;

import java.time.Instant;
import java.util.Objects;

/**
 * Command to refresh a delivery target from an inbound WeChat message.
 * Contains only raw message fields — no Controller or iLink DTO dependency.
 */
public record DeliveryTargetRefreshCommand(
        String userId,
        String contextToken,
        Instant now) {

    public DeliveryTargetRefreshCommand {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(contextToken, "contextToken must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        if (contextToken.isBlank()) {
            throw new IllegalArgumentException("contextToken must not be blank");
        }
    }
}
