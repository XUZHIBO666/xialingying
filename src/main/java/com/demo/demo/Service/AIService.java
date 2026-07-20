package com.demo.demo.Service;

import com.demo.demo.Service.memory.ConversationMemoryStore;
import com.demo.demo.Service.memory.ConversationMessage;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/** 调用兼容 OpenAI Chat Completions 接口的 LLM 服务。 */
@Slf4j
@Service
public class
AIService {

    @Value("${ai.api.key:}")
    private String apiKey;

    @Value("${ai.api.url}")
    private String apiUrl;

    @Value("${ai.model}")
    private String model;

    @Value("${ai.system-prompt}")
    private String systemPrompt;

    private final ConversationMemoryStore memoryStore;
    private final ConcurrentMap<String, Object> userLocks = new ConcurrentHashMap<>();
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    public AIService(ConversationMemoryStore memoryStore) {
        this.memoryStore = memoryStore;
    }

    /** 调用 AI 生成回复，失败时返回 null。 */
    public String chat(String userId, String message) {
        if (!isConfigured()) {
            log.debug("[AI] API Key 未配置，跳过调用");
            return null;
        }

        Object lock = userLocks.computeIfAbsent(userId, ignored -> new Object());
        synchronized (lock) {
            return chatInOrder(userId, message);
        }
    }

    private String chatInOrder(String userId, String message) {
        log.info("[AI] 收到对话请求 userId={} messageLength={}",
                maskUserId(userId), message == null ? 0 : message.length());

        try {
            JsonArray messages = buildMessages(userId, message);
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
                    log.error("[AI] API 调用失败 userId={} httpStatus={}",
                            maskUserId(userId), response.code());
                    return null;
                }

                String reply = JsonParser.parseString(json).getAsJsonObject()
                        .getAsJsonArray("choices")
                        .get(0).getAsJsonObject()
                        .getAsJsonObject("message")
                        .get("content").getAsString().trim();
                if (reply.isEmpty()) {
                    log.warn("[AI] 回复为空 userId={}", maskUserId(userId));
                    return null;
                }

                try {
                    memoryStore.appendTurn(userId, message, reply);
                } catch (IOException e) {
                    log.error("[AI] 记忆保存失败 userId={} error={}",
                            maskUserId(userId), e.getMessage());
                }

                log.info("[AI] 回复成功 userId={} replyLength={}",
                        maskUserId(userId), reply.length());
                return reply;
            }
        } catch (Exception e) {
            log.error("[AI] 调用异常 userId={} type={} error={}",
                    maskUserId(userId), e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    private JsonArray buildMessages(String userId, String currentMessage) {
        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", systemPrompt);
        messages.add(system);

        for (ConversationMessage saved : memoryStore.getHistory(userId)) {
            JsonObject historyMessage = new JsonObject();
            historyMessage.addProperty("role", saved.role());
            historyMessage.addProperty("content", saved.content());
            messages.add(historyMessage);
        }

        JsonObject current = new JsonObject();
        current.addProperty("role", "user");
        current.addProperty("content", currentMessage);
        messages.add(current);
        return messages;
    }

    /** 清除指定用户的持久化对话历史。 */
    public void clearHistory(String userId) {
        Object lock = userLocks.computeIfAbsent(userId, ignored -> new Object());
        synchronized (lock) {
            try {
                memoryStore.clear(userId);
                log.info("[AI] 清除对话历史 userId={}", maskUserId(userId));
            } catch (IOException e) {
                log.error("[AI] 清除对话历史失败 userId={} error={}",
                        maskUserId(userId), e.getMessage());
            }
        }
    }

    /** 是否已经配置 API Key。 */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isEmpty() && !"你的API_KEY".equals(apiKey);
    }

    private String maskUserId(String userId) {
        if (userId == null || userId.length() < 9) {
            return "***";
        }
        return userId.substring(0, 4) + "..." + userId.substring(userId.length() - 4);
    }
}
