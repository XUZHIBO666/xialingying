package com.demo.demo.Service.scheduling.persistence;

import com.demo.demo.Service.scheduling.domain.DeliveryTarget;

import java.time.Instant;
import java.util.Optional;

/**
 * Repository for {@link DeliveryTarget} persistence.
 */
public interface DeliveryTargetRepository {

    /**
     * Create or update a delivery target for the given user.
     * If the user already has a target, the encrypted token is refreshed.
     *
     * @param userId         WeChat user ID
     * @param encryptedToken AES-GCM encrypted contextToken
     * @param now            current timestamp
     * @return the persisted delivery target (with generated targetId if new)
     */
    DeliveryTarget upsert(String userId, String encryptedToken, Instant now);

    /**
     * Find a delivery target by its public identifier.
     *
     * @param targetId public target identifier
     * @return the target, or empty if not found
     */
    Optional<DeliveryTarget> findById(String targetId);
}
