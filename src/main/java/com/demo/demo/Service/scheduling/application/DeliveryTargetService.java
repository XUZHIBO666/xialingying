package com.demo.demo.Service.scheduling.application;

import com.demo.demo.Service.scheduling.domain.DeliveryTarget;
import com.demo.demo.Service.scheduling.persistence.DeliveryTargetRepository;
import com.demo.demo.Service.scheduling.security.ContextTokenCipher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Manages delivery targets: persists encrypted contextToken on each
 * inbound message, and decrypts on demand for outbound pushes.
 *
 * <p>Plaintext token exists only within the scope of {@code refresh} and
 * {@code resolve}; it is not logged.
 */
@Slf4j
@Service
public class DeliveryTargetService {

    private final DeliveryTargetRepository repo;
    private final ContextTokenCipher cipher;

    public DeliveryTargetService(DeliveryTargetRepository repo, ContextTokenCipher cipher) {
        this.repo = repo;
        this.cipher = cipher;
    }

    /**
     * Refresh the delivery target for a user on each trusted inbound message.
     *
     * @return the stable public target ID
     */
    public String refresh(DeliveryTargetRefreshCommand cmd) {
        String encrypted = cipher.encrypt(cmd.contextToken());
        DeliveryTarget target = repo.upsert(cmd.userId(), encrypted, cmd.now());
        log.debug("[DeliveryTarget] Refreshed target={} userId={}",
                target.targetId(), mask(cmd.userId()));
        return target.targetId();
    }

    /**
     * Resolve a target for outbound push: fetch and decrypt.
     *
     * @return resolved target with plaintext token
     * @throws SchedulingException if target not found
     */
    public DeliveryTargetResolved resolve(String targetId) {
        DeliveryTarget target = repo.findById(targetId)
                .orElseThrow(() -> new SchedulingException(
                        "Delivery target not found: " + targetId));
        String plainToken = cipher.decrypt(target.encryptedToken());
        log.debug("[DeliveryTarget] Resolved target={} userId={}",
                targetId, mask(target.userId()));
        return new DeliveryTargetResolved(target.userId(), plainToken);
    }

    private static String mask(String s) {
        if (s == null || s.length() < 9) return "***";
        return s.substring(0, 4) + "..." + s.substring(s.length() - 4);
    }
}
