package com.demo.demo.Service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
 * 使用前在 application.properties 配置：
 *   ai.api.key=你的API_KEY
 *   ai.api.url=https://api.deepseek.com  (可选，默认 DeepSeek)
 */
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

    // 简单记忆：每个用户保留最近 10 轮对话
    private final Map<String, JsonArray> historyMap = new ConcurrentHashMap<>();
    private static final int MAX_HISTORY = 10;

    /**
     * 调用 AI 生成回复
     * @param userId  用户 ID（用于区分不同人的对话上下文）
     * @param message 用户发的消息
     * @return AI 回复文本，失败返回 null
     */
    public String chat(String userId, String message) {
        if (apiKey == null || apiKey.isEmpty() || "你的API_KEY".equals(apiKey)) {
            return null; // 没配 Key，不回复
        }

        try {
            // 构建消息列表
            JsonArray messages = historyMap.computeIfAbsent(userId, k -> new JsonArray());

            // 首次对话加入系统提示
            if (messages.isEmpty()) {
                JsonObject sys = new JsonObject();
                sys.addProperty("role", "system");
                sys.addProperty("content", systemPrompt);
                messages.add(sys);
            }

            // 加入用户消息
            JsonObject userMsg = new JsonObject();
            userMsg.addProperty("role", "user");
            userMsg.addProperty("content", message);
            messages.add(userMsg);

            // 限制历史长度
            while (messages.size() > MAX_HISTORY + 1) { // +1 保留 system prompt
                messages.remove(1); // 删掉最早的非 system 消息
            }

            // 构建请求体
            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            body.add("messages", messages);
            body.addProperty("max_tokens", 500);
            body.addProperty("temperature", 0.7);

            // 发请求
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
                    System.err.println("[AI] HTTP " + response.code() + ": " + json);
                    // 如果出错，移除刚加的用户消息避免堆积
                    if (messages.size() > 0) messages.remove(messages.size() - 1);
                    return null;
                }

                JsonObject result = JsonParser.parseString(json).getAsJsonObject();
                String reply = result.getAsJsonArray("choices")
                        .get(0).getAsJsonObject()
                        .getAsJsonObject("message")
                        .get("content").getAsString();

                // 把 AI 回复也加入历史
                JsonObject aiMsg = new JsonObject();
                aiMsg.addProperty("role", "assistant");
                aiMsg.addProperty("content", reply);
                messages.add(aiMsg);

                return reply.trim();
            }
        } catch (Exception e) {
            System.err.println("[AI] 调用失败: " + e.getMessage());
            return null;
        }
    }

    /** 清除指定用户的对话历史 */
    public void clearHistory(String userId) {
        historyMap.remove(userId);
    }

    /** 是否已配置 API Key */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isEmpty()
                && !"你的API_KEY".equals(apiKey);
    }
}
