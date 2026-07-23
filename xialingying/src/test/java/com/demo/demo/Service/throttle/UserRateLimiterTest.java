package com.demo.demo.Service.throttle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UserRateLimiterTest {

    @Test
    void normalRateAllowsRequests() {
        // 每秒 10 次，burst 10 — 连续 5 次都应通过
        UserRateLimiter limiter = new UserRateLimiter(10.0, 10);
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire("user-a"), "第 " + (i + 1) + " 次应通过");
        }
    }

    @Test
    void burstAllowsInitialRequestsThenBlocks() {
        // 每秒 0.5，burst 2 — 最多连续 2 次通过
        UserRateLimiter limiter = new UserRateLimiter(0.5, 2);
        assertTrue(limiter.tryAcquire("user-a"), "第 1 次应通过（突发）");
        assertTrue(limiter.tryAcquire("user-a"), "第 2 次应通过（突发）");
        assertFalse(limiter.tryAcquire("user-a"), "第 3 次应被限速");
    }

    @Test
    void tokensRefillOverTime() throws InterruptedException {
        // 每秒 10 次，burst 1
        UserRateLimiter limiter = new UserRateLimiter(10.0, 1);
        assertTrue(limiter.tryAcquire("user-a"));
        assertFalse(limiter.tryAcquire("user-a"), "桶空，应被限速");

        // 等待 150ms（10 tokens/s → 1 token/100ms，足够补充 1 个）
        Thread.sleep(150);
        assertTrue(limiter.tryAcquire("user-a"), "补充后应通过");
    }

    @Test
    void usersAreIsolated() {
        UserRateLimiter limiter = new UserRateLimiter(0.5, 1);
        // user-a 消耗唯一的令牌
        assertTrue(limiter.tryAcquire("user-a"));
        assertFalse(limiter.tryAcquire("user-a"));

        // user-b 有自己独立的桶
        assertTrue(limiter.tryAcquire("user-b"), "user-b 不受 user-a 影响");
    }

    @Test
    void clearRemovesBucket() {
        UserRateLimiter limiter = new UserRateLimiter(0.5, 1);
        limiter.tryAcquire("user-a"); // 消耗令牌
        limiter.clear("user-a");

        // 清除后应从头开始
        assertTrue(limiter.tryAcquire("user-a"), "清除后应重新获得令牌");
    }

    @Test
    void bucketCountTracksActiveUsers() {
        UserRateLimiter limiter = new UserRateLimiter(1.0, 1);
        assertEquals(0, limiter.getBucketCount());

        limiter.tryAcquire("user-a");
        limiter.tryAcquire("user-b");
        limiter.tryAcquire("user-c");
        assertEquals(3, limiter.getBucketCount());
    }
}
