# 健康检查与可观测性 — 详细设计

> 关联：P3-28 | 前置：P1-7（优雅关闭）、P3-27（消息队列与削峰）

## 目标

为 xialingying Bot 增加生产级健康检查端点和可观测性能力，支撑容器化部署（Kubernetes liveness/readiness probe）和运维监控。

---

## 1. 健康检查端点

### 1.1 三层检查模型

```
GET /bot/health      — 聚合检查（K8s readiness probe）
GET /bot/health/live  — 存活检查（K8s liveness probe）
GET /bot/health/ready — 就绪检查（K8s readiness probe）
```

### 1.2 检查项定义

```java
package com.demo.demo.controller;

@RestController
@RequestMapping("/bot")
public class BotHealthController {

    private final BotService botService;
    private final AIService aiService;
    private final VoiceProperties voiceProperties;
    private final ImageGenerationService imageGenService;
    private final ImageRecognitionService imageRecService;
    private final ConversationMemoryStore memoryStore;  // 如果已实现

    // ... 构造注入

    @GetMapping("/health/live")
    @ResponseBody
    public Map<String, Object> liveness() {
        // 只检查进程是否存活——最轻量
        return Map.of(
            "status", "UP",
            "timestamp", System.currentTimeMillis()
        );
    }

    @GetMapping("/health/ready")
    @ResponseBody
    public Map<String, Object> readiness() {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> checks = new ArrayList<>();

        checks.add(checkIlinkLogin());
        checks.add(checkReplyQueue());
        checks.add(checkMemoryStore());

        boolean allUp = checks.stream()
                .allMatch(c -> "UP".equals(c.get("status")));
        result.put("status", allUp ? "UP" : "DOWN");
        result.put("checks", checks);
        result.put("timestamp", System.currentTimeMillis());
        return result;
    }

    @GetMapping("/health")
    @ResponseBody
    public Map<String, Object> health() {
        Map<String, Object> result = readiness();

        // 附加可选检查（不阻塞就绪）
        Map<String, Object> optionals = new LinkedHashMap<>();
        optionals.put("aiApi", checkAiApi());
        optionals.put("asrApi", checkAsrApi());
        optionals.put("imageGenApi", checkImageGenApi());
        result.put("optionals", optionals);

        // 附加指标
        result.put("uptime", getUptime());
        result.put("version", getVersion());
        return result;
    }

    // === 检查项实现 ===

    private Map<String, Object> checkIlinkLogin() {
        boolean loggedIn = botService.isLoggedIn();
        return Map.of(
            "name", "ilinkLogin",
            "status", loggedIn ? "UP" : "DOWN",
            "details", loggedIn ? "已登录" : "未登录"
        );
    }

    private Map<String, Object> checkReplyQueue() {
        int queueSize = botService.getReplyQueueSize();
        int queueCapacity = botService.getReplyQueueCapacity();
        boolean healthy = queueSize < queueCapacity * 0.9;  // 90% 阈值
        return Map.of(
            "name", "replyQueue",
            "status", healthy ? "UP" : "DEGRADED",
            "details", Map.of(
                "size", queueSize,
                "capacity", queueCapacity,
                "usagePercent", (int) (100.0 * queueSize / queueCapacity)
            )
        );
    }

    private Map<String, Object> checkMemoryStore() {
        // 只检查文件可访问性，不暴露内容
        return Map.of(
            "name", "memoryStore",
            "status", "UP",
            "details", Map.of("userCount", memoryStore.getUserCount())
        );
    }

    /**
     * AI API 连通性（可选的深度检查）
     * 发送最小化请求验证 API Key 有效
     */
    private Map<String, Object> checkAiApi() {
        if (!aiService.isConfigured()) {
            return Map.of("name", "aiApi",
                    "status", "UNKNOWN",
                    "details", "API Key 未配置");
        }
        try {
            // 发送极短请求验证连通性
            String result = aiService.probe();  // 需要新增方法
            return Map.of("name", "aiApi",
                    "status", "UP",
                    "details", Map.of("latencyMs", result));
        } catch (Exception e) {
            return Map.of("name", "aiApi",
                    "status", "DOWN",
                    "details", e.getMessage());
        }
    }

    // checkAsrApi()、checkImageGenApi() 模式相同
}
```

### 1.3 检查响应格式（类 Kubernetes Health Check）

```json
{
  "status": "UP",
  "checks": [
    {
      "name": "ilinkLogin",
      "status": "UP",
      "details": "已登录"
    },
    {
      "name": "replyQueue",
      "status": "UP",
      "details": {
        "size": 3,
        "capacity": 200,
        "usagePercent": 1
      }
    },
    {
      "name": "memoryStore",
      "status": "UP",
      "details": {
        "userCount": 12
      }
    }
  ],
  "optionals": {
    "aiApi": {
      "name": "aiApi",
      "status": "UP",
      "details": { "latencyMs": 342 }
    }
  },
  "uptime": "2h 15m 33s",
  "version": "0.0.1-SNAPSHOT (feat/voice-mvp)",
  "timestamp": 1753001234567
}
```

---

## 2. 指标暴露

### 2.1 轻量指标端点

```java
@GetMapping("/health/metrics")
@ResponseBody
public Map<String, Object> metrics() {
    Map<String, Object> m = new LinkedHashMap<>();

    // === 消息指标 ===
    m.put("messages", Map.of(
        "totalReceived", botService.getTotalMessagesReceived(),
        "totalTextMessages", botService.getTotalTextMessages(),
        "totalImageMessages", botService.getTotalImageMessages(),
        "totalVoiceMessages", botService.getTotalVoiceMessages()
    ));

    // === 回复指标 ===
    m.put("replies", Map.of(
        "totalTextReplies", botService.getTotalTextReplies(),
        "totalImageReplies", botService.getTotalImageReplies(),
        "totalVoiceReplies", botService.getTotalVoiceReplies(),
        "totalVoiceFallbacks", botService.getTotalVoiceFallbacks()
    ));

    // === 线程池指标 ===
    ThreadPoolExecutor pool = (ThreadPoolExecutor) botService.getReplyExecutor();
    m.put("threadPool", Map.of(
        "activeThreads", pool.getActiveCount(),
        "poolSize", pool.getPoolSize(),
        "queueSize", pool.getQueue().size(),
        "queueRemaining", pool.getQueue().remainingCapacity(),
        "completedTasks", pool.getCompletedTaskCount()
    ));

    // === 速率限制指标 ===
    m.put("rateLimiter", Map.of(
        "activeBuckets", botService.getRateLimiterBucketCount(),
        "totalAccepted", botService.getTotalRateLimitAccepted(),
        "totalRejected", botService.getTotalRateLimitRejected()
    ));

    // === LLM 调用指标 ===
    m.put("llm", Map.of(
        "totalCalls", aiService.getTotalCalls(),
        "totalFailures", aiService.getTotalFailures(),
        "avgLatencyMs", aiService.getAverageLatencyMs(),
        "successRate", aiService.getSuccessRate()
    ));

    // === ASR 指标 ===
    m.put("asr", Map.of(
        "totalCalls", asrService.getTotalCalls(),
        "totalFailures", asrService.getTotalFailures(),
        "avgLatencyMs", asrService.getAverageLatencyMs()
    ));

    // === TTS 指标 ===
    m.put("tts", Map.of(
        "totalCalls", ttsService.getTotalCalls(),
        "totalFailures", ttsService.getTotalFailures(),
        "avgLatencyMs", ttsService.getAverageLatencyMs()
    ));

    // === JVM 指标 ===
    Runtime rt = Runtime.getRuntime();
    m.put("jvm", Map.of(
        "maxMemoryMB", rt.maxMemory() / 1024 / 1024,
        "totalMemoryMB", rt.totalMemory() / 1024 / 1024,
        "freeMemoryMB", rt.freeMemory() / 1024 / 1024,
        "availableProcessors", rt.availableProcessors()
    ));

    return m;
}
```

### 2.2 指标收集（MetricsCollector）

```java
@Component
public class MetricsCollector {

    // 使用 LongAdder 高性能累加
    private final LongAdder totalMessages = new LongAdder();
    private final LongAdder totalTextMessages = new LongAdder();
    private final LongAdder totalImageMessages = new LongAdder();
    private final LongAdder totalVoiceMessages = new LongAdder();
    // ...

    // LLM 延迟采样（滑动窗口）
    private final long[] latencySamples = new long[100];
    private final AtomicInteger sampleIndex = new AtomicInteger();

    public void recordLlmCall(long latencyMs) {
        int idx = sampleIndex.getAndUpdate(i -> (i + 1) % latencySamples.length);
        latencySamples[idx] = latencyMs;
    }

    public long getAverageLatencyMs() {
        long sum = 0;
        int count = 0;
        for (long sample : latencySamples) {
            if (sample > 0) { sum += sample; count++; }
        }
        return count == 0 ? 0 : sum / count;
    }
}
```

### 2.3 集成点

在各 Service 的方法入口/出口插入指标收集：

```java
// AIService.chat()
long start = System.currentTimeMillis();
try {
    String reply = callLLM(messages);
    metricsCollector.recordLlmCall(
            System.currentTimeMillis() - start);
    if (reply != null) metricsCollector.incrementLlmSuccess();
    else metricsCollector.incrementLlmFailure();
    return reply;
} catch (Exception e) {
    metricsCollector.incrementLlmFailure();
    throw e;
}

// BotService 消息统计
void processTextMessage(...) {
    metricsCollector.incrementTotalMessages();
    metricsCollector.incrementTextMessages();
    // ...
}
```

---

## 3. 安全

- 健康检查端点受 `BotAdminAuthConfig` 保护（现有拦截器覆盖 `/bot/**`）
- 可选：将 `/bot/health/live` 和 `/bot/health/ready` 排除在认证拦截之外（K8s probe 需要无认证访问）

```java
// BotAdminAuthConfig 中
@Override
public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(new BotAdminTokenInterceptor(...))
            .addPathPatterns("/bot", "/bot/**")
            .excludePathPatterns("/bot/health/live", "/bot/health/ready");
}
```

- 健康检查不暴露任何用户数据、Token、消息内容
- 指标端点仍需要认证

---

## 4. Docker / K8s 集成

### 4.1 Dockerfile 健康检查

```dockerfile
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8080/bot/health/live || exit 1
```

### 4.2 Kubernetes Probe 配置

```yaml
livenessProbe:
  httpGet:
    path: /bot/health/live
    port: 8080
  initialDelaySeconds: 30
  periodSeconds: 30

readinessProbe:
  httpGet:
    path: /bot/health/ready
    port: 8080
  initialDelaySeconds: 10
  periodSeconds: 15
```

---

## 5. 日志增强

### 5.1 关键事件结构化日志

```java
// 重要状态变化使用结构化格式
log.info("[lifecycle] event=startup version={} java={}", version, javaVersion);
log.info("[lifecycle] event=login_success userId={}", maskUserId(userId));
log.info("[lifecycle] event=session_expired reason={}", reason);
log.info("[lifecycle] event=shutdown pendingTasks={}", pendingTasks);

// 周期性心跳
@Scheduled(fixedRate = 300_000)  // 每 5 分钟
public void heartbeat() {
    log.info("[heartbeat] loggedIn={} queueSize={} memoryUsers={}",
            botService.isLoggedIn(), queueSize, memoryStore.getUserCount());
}
```

### 5.2 日志文件滚动

`application.yml` 中配置：

```yaml
logging:
  file:
    path: ./logs
  logback:
    rolling-policy:
      max-file-size: 10MB
      max-history: 30
      total-size-cap: 500MB
```

---

## 6. 测试策略

| # | 场景 | 验证点 |
|---|------|--------|
| 1 | liveness 始终 UP | 只要进程存活就返回 UP |
| 2 | readiness 未登录时 DOWN | iLink 未登录 → readiness status=DOWN |
| 3 | readiness 队列健康时 UP | 队列使用 < 90% → UP |
| 4 | readiness 队列满载时 DEGRADED | 队列使用 ≥ 90% → DEGRADED |
| 5 | /health 包含可选检查 | optionals 中有 aiApi/asrApi/imageGenApi |
| 6 | /metrics 返回数值 | 所有指标字段有值且 > 0（运行一段时间后） |
| 7 | 未认证访问被拒绝 | 无 Token 访问 /health 返回 401 |
| 8 | liveness/readiness 免认证 | 无 Token 访问 /health/live 返回 200 |
