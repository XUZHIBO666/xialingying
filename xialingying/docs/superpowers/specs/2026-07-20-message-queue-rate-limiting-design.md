# 消息队列与削峰 — 详细设计

> 关联：P3-27 | 前置：P1-7（优雅关闭）

## 目标

优化消息处理队列和并发策略，使 Bot 在高负载下能平稳处理消息而不拒绝请求，同时通过 per-user 速率限制确保公平性和防止滥用。

---

## 1. 现状分析

### 当前线程模型

```java
// BotService.createReplyExecutor()
ThreadPoolExecutor(
    2,                          // corePoolSize
    2,                          // maxPoolSize
    new ArrayBlockingQueue<>(20), // 队列容量 20
    AbortPolicy()               // 队列满时抛 RejectedExecutionException
);
```

| 指标 | 当前值 | 问题 |
|------|--------|------|
| 工作线程 | 2 | 只有 2 个用户能同时得到 LLM 回复，其他排队 |
| 队列容量 | 20 | 22 个任务后（2 执行中 + 20 排队），第 23 个被拒绝 |
| 拒绝策略 | AbortPolicy | 直接拒绝 + 发"任务较多"文本，用户体验差 |
| Per-user 串行 | `synchronized(userLock)` | 同一个用户的消息串行 ✅，但不同用户共享线程池 |

### 瓶颈分析

假设 LLM 平均回复延迟 3 秒：

- 吞吐量：2 线程 / 3 秒 ≈ **0.67 TPS**
- 队列容纳：20 个任务 = 约 30 秒的缓冲
- 超过 22 个并发请求 → 拒绝

适合低频的个人 Bot（当前场景），但不适合群聊或高活跃 Bot。

---

## 2. 目标架构

```
消息到达
  │
  ├─ Per-user Rate Limiter（令牌桶）
  │   ├─ 通过 → 继续
  │   └─ 拒绝 → "请稍后再发消息"（不发 LLM 请求）
  │
  ├─ User Serializer（用户锁，已有）
  │   └─ 同一用户串行处理
  │
  └─ Reply Executor（改进的线程池）
      ├─ 4 线程（可配置）
      ├─ LinkedBlockingQueue(200)（可配置）
      └─ CallerRunsPolicy（队列满时由监听线程兜底，形成自然背压）
```

---

## 3. 组件设计

### 3.1 RateLimiter（Per-User 令牌桶）

```java
package com.demo.demo.Service.throttle;

public class UserRateLimiter {

    private final ConcurrentHashMap<String, TokenBucket> buckets
            = new ConcurrentHashMap<>();

    private final double permitsPerSecond;
    private final int burstSize;

    /**
     * @param permitsPerSecond 每秒允许的请求数（如 0.5 = 每 2 秒 1 条）
     * @param burstSize       突发容量（如 2 = 允许连续发 2 条后限速）
     */
    public UserRateLimiter(double permitsPerSecond, int burstSize) {
        this.permitsPerSecond = permitsPerSecond;
        this.burstSize = burstSize;
    }

    /** 尝试获取一个许可。返回 true = 允许，false = 限速 */
    public boolean tryAcquire(String userId) {
        TokenBucket bucket = buckets.computeIfAbsent(userId,
                k -> new TokenBucket(burstSize, permitsPerSecond));
        cleanExpired();
        return bucket.tryConsume();
    }

    public void clear(String userId) {
        buckets.remove(userId);
    }

    /** 定期清理超过 10 分钟未使用的桶 */
    private void cleanExpired() {
        // 由 tryAcquire 大致触发，或单独定时任务
        long now = System.currentTimeMillis();
        buckets.entrySet().removeIf(e ->
                now - e.getValue().lastAccessTime > TimeUnit.MINUTES.toMillis(10)
                && buckets.size() > 200);
    }

    static class TokenBucket {
        private double tokens;
        private long lastRefillTime;
        final long lastAccessTime;

        private final double maxTokens;
        private final double refillRate; // tokens per ms

        TokenBucket(int maxTokens, double permitsPerSecond) {
            this.maxTokens = maxTokens;
            this.tokens = maxTokens;
            this.refillRate = permitsPerSecond / 1000.0;
            this.lastRefillTime = System.currentTimeMillis();
            this.lastAccessTime = lastRefillTime;
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
```

### 3.2 改进的 Reply Executor

```java
// BotService.createReplyExecutor() 改造

@Value("${bot.reply.threads:4}")
private int replyThreads;

@Value("${bot.reply.queue-capacity:200}")
private int replyQueueCapacity;

private static ExecutorService createReplyExecutor(int threads, int queueCapacity) {
    return new ThreadPoolExecutor(
            threads,
            threads,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(queueCapacity),
            runnable -> {
                Thread t = new Thread(runnable,
                        "bot-reply-" + REPLY_THREAD_SEQUENCE.incrementAndGet());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()  // 改为 CallerRuns
    );
}
```

**CallerRunsPolicy 的效果**：
- 所有线程 + 队列满时，新任务由 iLink 监听线程直接执行
- 监听线程在处理回复时无法接收新消息 → 自然的背压机制
- 不会丢失请求，不会收到"任务较多"提示

### 3.3 接入 BotService

```java
@Service
public class BotService {

    private final UserRateLimiter rateLimiter =
            new UserRateLimiter(0.5, 2);  // 每 2 秒 1 条，突发 2 条

    // 配置化
    @Value("${bot.rate-limit.permits-per-second:0.5}")
    private double rateLimitPerSecond;

    @Value("${bot.rate-limit.burst:2}")
    private int rateLimitBurst;

    private void submitReplyTask(String fromUser, String contextToken,
                                  Runnable task) {
        // 1. 速率检查
        if (!rateLimiter.tryAcquire(fromUser)) {
            log.info("[限速] from={} 消息被限速", maskUserId(fromUser));
            sendReply(fromUser, contextToken,
                    "你的消息太快了，请稍后再发。");
            return;
        }

        // 2. 提交任务
        try {
            Object userLock = userReplyLocks.computeIfAbsent(
                    fromUser, ignored -> new Object());
            replyExecutor.execute(() -> {
                synchronized (userLock) {
                    task.run();
                }
            });
        } catch (RejectedExecutionException e) {
            // CallerRunsPolicy 下此异常只会在 shutdown 后抛出
            log.warn("[iLink] 回复任务被拒绝（可能在关闭中）from={}",
                    maskUserId(fromUser));
            sendReply(fromUser, contextToken,
                    "服务正在维护中，请稍后再试。");
        }
    }
}
```

---

## 4. 配置

### 4.1 application.yml

```yaml
bot:
  reply:
    threads: ${BOT_REPLY_THREADS:4}              # 工作线程数
    queue-capacity: ${BOT_REPLY_QUEUE_CAPACITY:200}  # 队列容量
  rate-limit:
    permits-per-second: ${BOT_RATE_LIMIT_PER_SECOND:0.5}  # 每用户每秒允许请求数
    burst: ${BOT_RATE_LIMIT_BURST:2}                       # 突发容量
```

### 4.2 容量规划

| 场景 | threads | queue | 最大并发处理中+排队 | 3s LLM 延迟下的 TPS |
|------|---------|-------|-------------------|-------------------|
| 当前 | 2 | 20 | 22 | 0.67 |
| 推荐 | 4 | 200 | 204 | 1.33 |
| 高负载 | 8 | 500 | 508 | 2.67 |

---

## 5. 可观测性

### 5.1 状态端点扩展

```java
// BotController 新增
@GetMapping("/stats")
@ResponseBody
public Map<String, Object> stats() {
    ThreadPoolExecutor pool = (ThreadPoolExecutor) replyExecutor;
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("activeThreads", pool.getActiveCount());
    s.put("poolSize", pool.getPoolSize());
    s.put("queueSize", pool.getQueue().size());
    s.put("queueCapacity", pool.getQueue().remainingCapacity()
            + pool.getQueue().size());
    s.put("completedTasks", pool.getCompletedTaskCount());
    s.put("rateLimiterBuckets", rateLimiter.getBucketCount());
    return s;
}
```

### 5.2 日志指标

每分钟/每百条消息输出一次聚合指标：

```java
// 在 submitReplyTask 中累计
private final AtomicLong totalSubmitted = new AtomicLong();
private final AtomicLong totalRejected = new AtomicLong();
private final AtomicLong totalRateLimited = new AtomicLong();

// 定期 dump
private void dumpMetrics() {
    long submitted = totalSubmitted.getAndSet(0);
    long rejected = totalRejected.getAndSet(0);
    long limited = totalRateLimited.getAndSet(0);
    if (submitted > 0 || rejected > 0 || limited > 0) {
        log.info("[指标] submitted={} rejected={} rateLimited={}",
                submitted, rejected, limited);
    }
}
```

---

## 6. 测试策略

### UserRateLimiter 测试

| # | 场景 | 验证点 |
|---|------|--------|
| 1 | 正常速率 | 每秒 1 次通过 |
| 2 | 突发 | 初始 2 次连续通过 |
| 3 | 限速触发 | 消耗完令牌后第 3 次被拒绝 |
| 4 | 令牌恢复 | 等待 2 秒后再次通过 |
| 5 | 多用户独立 | 用户 A 被限速不影响用户 B |
| 6 | 清理过期桶 | 10 分钟未活动的桶被移除 |

### ReplyExecutor 测试

| # | 场景 | 验证点 |
|---|------|--------|
| 1 | 正常提交 | 任务在线程池中执行 |
| 2 | 队列满 | CallerRunsPolicy 让调用线程执行 |
| 3 | 限速触发 | 不提交任务，发送限速提示文本 |
| 4 | 关闭中提交 | 任务被拒绝，发送维护提示文本 |

---

## 7. 回滚

- 设置 `BOT_REPLY_THREADS=2` 和 `BOT_REPLY_QUEUE_CAPACITY=20` 恢复当前模型
- 设置 `BOT_RATE_LIMIT_PER_SECOND=999`（极大值）禁用限速
- 旧 `AbortPolicy` 可通过新增配置项 `BOT_REPLY_REJECTION_POLICY=abort` 恢复
