package com.demo.demo.Service.throttle;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Per-user 令牌桶速率限制器。
 *
 * 每个用户独立持有令牌桶：容量为 burstSize 个令牌，以 permitsPerSecond
 * 速率补充。超过 10 分钟未活动的用户桶自动清理。
 */
public class UserRateLimiter {

    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final double permitsPerSecond;
    private final int burstSize;

    /**
     * @param permitsPerSecond 每秒补充的令牌数（如 0.5 = 每 2 秒 1 次）
     * @param burstSize        桶容量（允许的突发请求数）
     */
    public UserRateLimiter(double permitsPerSecond, int burstSize) {
        if (permitsPerSecond <= 0) throw new IllegalArgumentException("permitsPerSecond must be > 0");
        if (burstSize <= 0) throw new IllegalArgumentException("burstSize must be > 0");
        this.permitsPerSecond = permitsPerSecond;
        this.burstSize = burstSize;
    }

    /**
     * 尝试消费一个令牌。成功返回 true，被限速返回 false。
     */
    public boolean tryAcquire(String userId) {
        TokenBucket bucket = buckets.computeIfAbsent(userId,
                k -> new TokenBucket(burstSize, permitsPerSecond));
        bucket.lastAccessTime = System.currentTimeMillis();
        cleanExpired();
        return bucket.tryConsume();
    }

    /** 当前活跃桶数。 */
    public int getBucketCount() {
        cleanExpired();
        return buckets.size();
    }

    /** 清除指定用户的桶。 */
    public void clear(String userId) {
        buckets.remove(userId);
    }

    private void cleanExpired() {
        if (buckets.size() < 200) return;
        long now = System.currentTimeMillis();
        long expireThreshold = TimeUnit.MINUTES.toMillis(10);
        buckets.entrySet().removeIf(e ->
                now - e.getValue().lastAccessTime > expireThreshold);
    }

    // ==================== TokenBucket ====================

    static class TokenBucket {
        private double tokens;
        private long lastRefillTime;
        volatile long lastAccessTime;
        private final double maxTokens;
        private final double refillRate; // tokens per millisecond

        TokenBucket(int maxTokens, double permitsPerSecond) {
            this.maxTokens = maxTokens;
            this.tokens = maxTokens;
            this.refillRate = permitsPerSecond / 1000.0;
            long now = System.currentTimeMillis();
            this.lastRefillTime = now;
            this.lastAccessTime = now;
        }

        synchronized boolean tryConsume() {
            refill();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }

        private void refill() {
            long now = System.currentTimeMillis();
            double elapsed = now - lastRefillTime;
            tokens = Math.min(maxTokens, tokens + elapsed * refillRate);
            lastRefillTime = now;
        }
    }
}
