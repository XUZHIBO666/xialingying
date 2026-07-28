package com.demo.demo.Service.scheduling.application;

/**
 * Resolved delivery target with decrypted contextToken, ready for iLink push.
 */
public record DeliveryTargetResolved(String userId, String contextToken) {
}
