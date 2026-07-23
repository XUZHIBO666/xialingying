package com.demo.demo.Service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * AI 大模型服务：调用 LLM API 生成智能回复
 * 支持 DeepSeek、OpenAI 及所有兼容 /v1/chat/completions 的 API
 *
 * 配置方式（application.yml）：
 * ai.api.key: 你的API_KEY
 * ai.api.url: https://api.deepseek.com
 * ai.model: deepseek-chat
 */
@Slf4j
@Service
public class AIService {

    @Value("${ai.api.key:}")
    private String apiKey;

    @Value("${ai.api.url}")
    private String apiUrl;

    @Value("${ai.model}")
    private String model;

    @Value("${ai.system-prompt}")
    private String systemPrompt;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    // 简单记忆：每个用户保留最近 10 轮对话，这些对话存储在磁盘里面，但是我后面有定量删除对话
    private final Map<String, JsonArray> historyMap = new ConcurrentHashMap<>();
    private static final int MAX_HISTORY = 10;

    // 用户级锁：保证同一用户的对话历史不会被并发修改
    private final ConcurrentHashMap<String, Object> userLocks = new ConcurrentHashMap<>();

    /**
     * 调用 AI 生成回复
     *
     * @param userId  用户 ID（用于区分不同人的对话上下文）
     * @param message 用户发的消息
     * @return AI 回复文本，失败返回 null
     */
    public String chat(String userId, String message) {
        if (apiKey == null || apiKey.isEmpty() || "你的API_KEY".equals(apiKey)) {
            log.debug("[AI] API Key 未配置，跳过调用");
            return null;
        }

        log.info("[AI] 收到对话请求 userId={} message={}", userId,
                message.length() > 100 ? message.substring(0, 100) + "..." : message);

        // 获取该用户的锁，保证同一用户的对话历史不会被并发修改
        Object lock = userLocks.computeIfAbsent(userId, k -> new Object());
        synchronized (lock) {
            return doChat(userId, message);
        }
    }

    /** 内部聊天实现（在用户锁保护下调用） */
    private String doChat(String userId, String message) {
        try {
            // 构建消息列表
            JsonArray messages = historyMap.computeIfAbsent(userId, k -> new JsonArray());

            if (messages.isEmpty()) {
                JsonObject sys = new JsonObject();
                sys.addProperty("role", "system");
                sys.addProperty("content", systemPrompt);
                messages.add(sys);
                log.debug("[AI] 为用户 {} 初始化系统提示", userId);
            }

            JsonObject userMsg = new JsonObject();
            userMsg.addProperty("role", "user");
            userMsg.addProperty("content", message);
            messages.add(userMsg);

            // 限制历史长度
            while (messages.size() > MAX_HISTORY + 1) {
                messages.remove(1);
            }

            // 构建请求体
            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            body.add("messages", messages);
            body.addProperty("max_tokens", 500);
            body.addProperty("temperature", 0.7);

            log.debug("[AI] 请求参数 model={} historySize={}", model, messages.size());

            Request request = new Request.Builder()
                    .url(apiUrl + "/v1/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(body.toString(),
                            MediaType.parse("application/json")))
                    .build();

            try (Response response = client.newCall(request).execute()) {
                String json = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    log.error("[AI] API 调用失败 HTTP {} body={}", response.code(),
                            json.length() > 200 ? json.substring(0, 200) : json);
                    if (messages.size() > 0)
                        messages.remove(messages.size() - 1);
                    return null;
                }

                JsonObject result = JsonParser.parseString(json).getAsJsonObject();
                String reply = result.getAsJsonArray("choices")
                        .get(0).getAsJsonObject()
                        .getAsJsonObject("message")
                        .get("content").getAsString();

                // 记录 AI 回复到历史
                JsonObject aiMsg = new JsonObject();
                aiMsg.addProperty("role", "assistant");
                aiMsg.addProperty("content", reply);
                messages.add(aiMsg);

                log.info("[AI] 回复成功 userId={} reply={}",
                        userId,
                        reply.length() > 100 ? reply.substring(0, 100) + "..." : reply);

                return reply.trim();
            }
        } catch (Exception e) {
            log.error("[AI] 调用异常 userId={} error={}", userId, e.getMessage(), e);
            return null;
        }
    }

    /** 清除指定用户的对话历史 */
    public void clearHistory(String userId) {
        log.info("[AI] 清除对话历史 userId={}", userId);
        historyMap.remove(userId);
    }

    /** 是否已配置 API Key */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isEmpty()
                && !"你的API_KEY".equals(apiKey);
    }
}
