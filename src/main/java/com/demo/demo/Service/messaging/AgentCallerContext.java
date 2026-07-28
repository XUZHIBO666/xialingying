package com.demo.demo.Service.messaging;

import java.util.Objects;

/**
 * Server-trusted context passed from the inbound message handler to the Agent.
 * Contains only the delivery target ID — the model never sees this,
 * and Tool parameters cannot override it.
 */
public record AgentCallerContext(String targetId) {
    public AgentCallerContext {
        Objects.requireNonNull(targetId, "targetId must not be null");
        if (targetId.isBlank()) {
            throw new IllegalArgumentException("targetId must not be blank");
        }
    }
}
