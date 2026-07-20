package com.demo.demo.Service;

import com.demo.demo.Service.context.ContextManager;
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

    private static final int MAX_RETRIES = 3;
    private static final long[] RETRY_DELAYS_MS = {500L, 1000L, 2000L};

    private final ConversationMemoryStore memoryStore;
    private final ContextManager contextManager;
    private final ConcurrentMap<String, Object> userLocks = new ConcurrentHashMap<>();
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    public AIService(ConversationMemoryStore memoryStore, ContextManager contextManager) {
        this.memoryStore = memoryStore;
        this.contextManager = contextManager;
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

            String json = executeWithRetries(request, userId);
            if (json == null) {
                return null;  // 所有重试均已失败
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
        } catch (Exception e) {
            log.error("[AI] 调用异常 userId={} type={} error={}",
                    maskUserId(userId), e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    /**
     * 带重试的 HTTP 调用。
     * 429（限流）和 5xx（服务端错误）自动重试，最多 {@value #MAX_RETRIES} 次。
     * 401/403（认证错误）不重试，立即返回 null。
     * IO 异常（网络错误）也会重试。
     */
    private String executeWithRetries(Request request, String userId) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                Response response = client.newCall(request).execute();
                String json = response.body() != null ? response.body().string() : "";
                int code = response.code();
                response.close();

                if (response.isSuccessful()) {
                    return json;
                }

                if (!isRetryable(code)) {
                    log.error("[AI] 不可重试错误 userId={} httpStatus={}",
                            maskUserId(userId), code);
                    return null;
                }

                if (attempt < MAX_RETRIES) {
                    log.warn("[AI] 可重试错误 userId={} httpStatus={} 第{}次/共{}次",
                            maskUserId(userId), code, attempt, MAX_RETRIES);
                    sleepBeforeRetry(attempt);
                } else {
                    log.error("[AI] 重试耗尽 userId={} httpStatus={}",
                            maskUserId(userId), code);
                }
            } catch (IOException e) {
                if (attempt < MAX_RETRIES) {
                    log.warn("[AI] 网络异常 userId={} 第{}次/共{}次: {}",
                            maskUserId(userId), attempt, MAX_RETRIES, e.getMessage());
                    sleepBeforeRetry(attempt);
                } else {
                    log.error("[AI] 网络异常重试耗尽 userId={}: {}",
                            maskUserId(userId), e.getMessage());
                    return null;
                }
            }
        }
        return null;
    }

    private boolean isRetryable(int httpStatus) {
        return httpStatus == 429
                || httpStatus == 500
                || httpStatus == 502
                || httpStatus == 503
                || httpStatus == 504;
    }

    private void sleepBeforeRetry(int completedAttempt) {
        try {
            Thread.sleep(RETRY_DELAYS_MS[completedAttempt - 1]);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private JsonArray buildMessages(String userId, String currentMessage) {
        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content",
                contextManager.buildEnhancedSystemMessage(userId, systemPrompt));
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
