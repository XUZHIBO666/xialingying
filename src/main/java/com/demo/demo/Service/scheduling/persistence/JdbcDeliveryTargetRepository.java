package com.demo.demo.Service.scheduling.persistence;

import com.demo.demo.Service.scheduling.domain.DeliveryTarget;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcDeliveryTargetRepository implements DeliveryTargetRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<DeliveryTarget> ROW_MAPPER = (rs, rowNum) ->
            new DeliveryTarget(
                    rs.getLong("id"),
                    rs.getString("target_id"),
                    rs.getString("user_id"),
                    rs.getString("encrypted_token"),
                    Instant.ofEpochMilli(rs.getLong("created_at")),
                    Instant.ofEpochMilli(rs.getLong("updated_at")));

    public JdbcDeliveryTargetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public DeliveryTarget upsert(String userId, String encryptedToken, Instant now) {
        // Try to find existing target for this user
        DeliveryTarget existing = findOneByUserId(userId);
        if (existing != null) {
            jdbc.update(
                    "UPDATE wechat_delivery_target SET encrypted_token = ?, updated_at = ? WHERE user_id = ?",
                    encryptedToken, now.toEpochMilli(), userId);
            return existing.withToken(encryptedToken, now);
        }

        // Insert new target
        String targetId = UUID.randomUUID().toString();
        long epochNow = now.toEpochMilli();
        jdbc.update(
                "INSERT INTO wechat_delivery_target (target_id, user_id, encrypted_token, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                targetId, userId, encryptedToken, epochNow, epochNow);
        return DeliveryTarget.create(targetId, userId, encryptedToken, now);
    }

    @Override
    public Optional<DeliveryTarget> findById(String targetId) {
        return Optional.ofNullable(findOneByTargetId(targetId));
    }

    private DeliveryTarget findOneByUserId(String userId) {
        var list = jdbc.query(
                "SELECT id, target_id, user_id, encrypted_token, created_at, updated_at FROM wechat_delivery_target WHERE user_id = ?",
                ROW_MAPPER, userId);
        return list.isEmpty() ? null : list.get(0);
    }

    private DeliveryTarget findOneByTargetId(String targetId) {
        var list = jdbc.query(
                "SELECT id, target_id, user_id, encrypted_token, created_at, updated_at FROM wechat_delivery_target WHERE target_id = ?",
                ROW_MAPPER, targetId);
        return list.isEmpty() ? null : list.get(0);
    }
}
