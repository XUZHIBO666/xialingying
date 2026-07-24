package com.demo.demo.controller;

import com.demo.demo.Service.AIService;
import com.demo.demo.Service.BotInstance;
import com.demo.demo.Service.BotService;
import com.demo.demo.Service.MultiBotManager;
import com.demo.demo.Service.memory.ConversationMemoryStore;
import com.demo.demo.config.VoiceProperties;
import jakarta.annotation.Resource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 健康检查与可观测性端点。
 *
 * /bot/health/live  — K8s liveness probe（仅存活检查）
 * /bot/health/ready — K8s readiness probe（核心依赖可用）
 * /bot/health       — 完整健康状态 + 可选检查 + 指标
 */
@RestController
@RequestMapping("/bot")
public class BotHealthController {

    private static final Instant STARTUP_TIME = Instant.now();

    @Resource
    private BotService botService;

    @Resource
    private MultiBotManager multiBotManager;

    @Resource
    private AIService aiService;

    @Resource
    private VoiceProperties voiceProperties;

    @Resource
    private ConversationMemoryStore memoryStore;
    @Value("${spring.ai.dashscope.api-key:}")
    private String dashScopeApiKey;
    @GetMapping("/health/live")
    @ResponseBody
    public Map<String, Object> liveness() {
        return Map.of(
                "status", "UP",
                "timestamp", System.currentTimeMillis()
        );
    }

    @GetMapping("/health/ready")
    @ResponseBody
    public Map<String, Object> readiness() {
        List<Map<String, Object>> checks = new ArrayList<>();
        checks.add(checkIlinkLogin());
        checks.add(checkReplyQueue());
        checks.add(checkMemoryStore());

        boolean allUp = checks.stream()
                .allMatch(c -> "UP".equals(c.get("status")));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", allUp ? "UP" : "DOWN");
        result.put("checks", checks);
        result.put("timestamp", System.currentTimeMillis());
        return result;
    }

    @GetMapping("/health")
    @ResponseBody
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.putAll(readiness());

        // 可选检查（不阻塞就绪）
        List<Map<String, Object>> optionals = new ArrayList<>();
        optionals.add(checkAiApi());
        optionals.add(checkAsrApi());
        optionals.add(checkTtsApi());
        result.put("optionals", optionals);

        result.put("uptime", formatUptime());
        result.put("version", "0.0.1-SNAPSHOT (feat/voice-mvp)");
        return result;
    }

    @GetMapping("/health/metrics")
    @ResponseBody
    public Map<String, Object> metrics() {
        Map<String, Object> m = new LinkedHashMap<>();

        BotInstance defaultBot = multiBotManager.getDefaultBot();

        m.put("replyQueue", Map.of(
                "size", defaultBot.getReplyQueueSize(),
                "capacity", 200,
                "usagePercent", (int) (100L * defaultBot.getReplyQueueSize() / 200)
        ));

        m.put("memory", Map.of(
                "userCount", memoryStore.getUserCount()
        ));

        Runtime rt = Runtime.getRuntime();
        m.put("jvm", Map.of(
                "maxMemoryMB", rt.maxMemory() / 1024 / 1024,
                "totalMemoryMB", rt.totalMemory() / 1024 / 1024,
                "freeMemoryMB", rt.freeMemory() / 1024 / 1024,
                "availableProcessors", rt.availableProcessors()
        ));

        m.put("uptimeMs", Duration.between(STARTUP_TIME, Instant.now()).toMillis());
        return m;
    }

    @GetMapping("/health/bots")
    @ResponseBody
    public Map<String, Object> allBotsHealth() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalBots", multiBotManager.getBotCount());
        result.put("loggedInBots", multiBotManager.getTotalLoggedInBots());

        List<Map<String, Object>> botHealthList = new ArrayList<>();
        for (BotInstance bot : multiBotManager.getAllBots()) {
            Map<String, Object> botInfo = new LinkedHashMap<>();
            botInfo.put("instanceId", bot.getInstanceId());
            botInfo.put("displayName", bot.getDisplayName());
            botInfo.put("loggedIn", bot.isLoggedIn());
            botInfo.put("status", bot.getStatusText());
            botInfo.put("replyQueueSize", bot.getReplyQueueSize());
            botHealthList.add(botInfo);
        }
        result.put("bots", botHealthList);
        result.put("timestamp", System.currentTimeMillis());

        return result;
    }


    // ==================== 检查项 ====================

    private Map<String, Object> checkIlinkLogin() {
        BotInstance defaultBot = multiBotManager.getDefaultBot();
        boolean loggedIn = defaultBot.isLoggedIn();
        return Map.of(
                "name", "ilinkLogin",
                "status", loggedIn ? "UP" : "DOWN",
                "details", loggedIn ? "已登录 (" + defaultBot.getInstanceId() + ")" : "未登录"
        );
    }

    private Map<String, Object> checkReplyQueue() {
        BotInstance defaultBot = multiBotManager.getDefaultBot();
        int size = defaultBot.getReplyQueueSize();
        int capacity = 200;
        boolean healthy = capacity > 0 && size < capacity * 0.9;
        return Map.of(
                "name", "replyQueue",
                "status", healthy ? "UP" : "DEGRADED",
                "details", Map.of(
                        "size", size,
                        "capacity", capacity,
                        "usagePercent", capacity > 0
                                ? (int) (100.0 * size / capacity) : 0
                )
        );
    }

    private Map<String, Object> checkMemoryStore() {
        return Map.of(
                "name", "memoryStore",
                "status", "UP",
                "details", Map.of("userCount", memoryStore.getUserCount())
        );
    }

    private Map<String, Object> checkAiApi() {
        if (!aiService.isConfigured()) {
            return Map.of("name", "aiApi", "status", "UNKNOWN",
                    "details", "AI API Key 未配置");
        }
        return Map.of("name", "aiApi", "status", "UP",
                "details", Map.of("configured", true));
    }

    private Map<String, Object> checkAsrApi() {
        String key = voiceProperties.getAsr().getApiKey();
        boolean configured = key != null && !key.isBlank();
        return Map.of("name", "asrApi", "status",
                configured ? "UP" : "UNKNOWN",
                "details", Map.of("configured", configured));
    }

//    private Map<String, Object> checkTtsApi() {
//        String key = voiceProperties.getTts().getApiKey();
//        boolean configured = key != null && !key.isBlank();
//        return Map.of("name", "ttsApi", "status",
//                configured ? "UP" : "UNKNOWN",
//                "details", Map.of("configured", configured));
//    }
private Map<String, Object> checkTtsApi() {
    boolean configured = dashScopeApiKey != null && !dashScopeApiKey.isBlank()
            && !"sk-placeholder".equals(dashScopeApiKey);
    return Map.of("name", "ttsApi", "status",
            configured ? "UP" : "UNKNOWN",
            "details", Map.of("configured", configured, "provider", "dashscope"));
}

    private String formatUptime() {
        Duration uptime = Duration.between(STARTUP_TIME, Instant.now());
        long hours = uptime.toHours();
        long minutes = uptime.toMinutesPart();
        long seconds = uptime.toSecondsPart();
        return String.format("%dh %dm %ds", hours, minutes, seconds);
    }
}
