package com.demo.demo.Service.scheduling.domain;

import java.time.Instant;

/**
 * A delivery target represents a WeChat user reachable via encrypted
 * {@code contextToken} within the scope of the default Bot.
 *
 * <p>Every trusted inbound message refreshes this record so the
 * encrypted token stays current.
 */
public record DeliveryTarget(
        Long id,
        String targetId,
        String userId,
        String encryptedToken,
        Instant createdAt,
        Instant updatedAt) {

    public static DeliveryTarget create(
            String targetId, String userId, String encryptedToken, Instant now) {
        return new DeliveryTarget(null, targetId, userId, encryptedToken, now, now);
    }

    public DeliveryTarget withToken(String newEncryptedToken, Instant now) {
        return new DeliveryTarget(id, targetId, userId, newEncryptedToken, createdAt, now);
    }
}
