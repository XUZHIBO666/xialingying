package com.weathercli.service;

import com.google.gson.*;
import com.weathercli.exception.CLIException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * AI 模型服务 — 调用 DeepSeek / 通义千问等大模型 API。
 *
 * API Key 配置方式（优先级从高到低）:
 *   1. 环境变量: DEEPSEEK_API_KEY / QWEN_API_KEY
 *   2. config.properties: deepseek.api.key / qwen.api.key
 */
public class AIService {

    private static final Logger LOG = Logger.getLogger(AIService.class.getName());

    private final HttpClient httpClient;
    private final Gson gson;
    private final ConfigService config;

    public AIService() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        this.gson = new Gson();
        this.config = ConfigService.getInstance();
    }

    /**
     * 向 AI 模型发送对话请求。
     *
     * @param userMessage 用户消息
     * @return AI 回复内容
     */
    public String chat(String userMessage) throws CLIException {
        return chat(userMessage, config.get("ai.default.provider", "deepseek"));
    }

    /**
     * 向指定 AI 模型发送对话请求。
     *
     * @param userMessage 用户消息
     * @param provider    模型提供商 (deepseek / qwen)
     * @return AI 回复内容
     */
    public String chat(String userMessage, String provider) throws CLIException {
        LOG.info("AI 对话请求: provider=" + provider + ", message=" + userMessage);

        String apiKey;
        String apiUrl;
        String model;

        switch (provider.toLowerCase()) {
            case "qwen":
            case "tongyi":
            case "通义千问":
                apiKey = config.get("qwen.api.key");
                apiUrl = config.get("qwen.api.url");
                model = config.get("qwen.model", "qwen-plus");
                break;
            case "deepseek":
            default:
                apiKey = config.get("deepseek.api.key");
                apiUrl = config.get("deepseek.api.url");
                model = config.get("deepseek.model", "deepseek-chat");
                break;
        }

        // 检查 API Key
        if (apiKey == null || apiKey.isBlank() || apiKey.startsWith("your-")) {
            throw new CLIException(
                CLIException.ErrorCode.CONFIG_ERROR,
                "❌ AI 模型未配置 API Key！\n\n"
                + "请通过以下方式配置:\n"
                + "  1. 环境变量: export " + provider.toUpperCase() + "_API_KEY=sk-xxx\n"
                + "  2. 配置文件: 编辑 src/main/resources/config.properties\n"
                + "\nAPI Key 申请地址:\n"
                + "  DeepSeek: https://platform.deepseek.com/api_keys\n"
                + "  通义千问: https://dashscope.console.aliyun.com/apiKey"
            );
        }

        LOG.info("调用 AI API: " + apiUrl + " (model=" + model + ")");

        try {
            // 构建请求体
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", model);

            JsonArray messages = new JsonArray();
            JsonObject systemMsg = new JsonObject();
            systemMsg.addProperty("role", "system");
            systemMsg.addProperty("content", "你是一个有帮助的AI助手，请用简洁清晰的中文回答。");
            messages.add(systemMsg);

            JsonObject userMsg = new JsonObject();
            userMsg.addProperty("role", "user");
            userMsg.addProperty("content", userMessage);
            messages.add(userMsg);

            requestBody.add("messages", messages);
            requestBody.addProperty("temperature", 0.7);
            requestBody.addProperty("max_tokens", 2000);

            String jsonBody = gson.toJson(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

            LOG.info("AI API 请求已发送, 等待响应...");
            HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

            LOG.info("AI API 响应状态: " + response.statusCode());

            if (response.statusCode() != 200) {
                LOG.severe("AI API 错误响应: " + response.body());
                throw new CLIException(
                    CLIException.ErrorCode.API_ERROR,
                    "AI API 调用失败 (HTTP " + response.statusCode() + ")\n"
                    + "请检查 API Key 是否正确，或稍后重试。"
                );
            }

            // 解析响应
            JsonObject result = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonArray choices = result.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                throw new CLIException(
                    CLIException.ErrorCode.PARSE_ERROR,
                    "AI 返回数据为空，请重试。"
                );
            }

            String content = choices.get(0).getAsJsonObject()
                .getAsJsonObject("message")
                .get("content").getAsString();

            // 记录 token 使用量
            if (result.has("usage")) {
                JsonObject usage = result.getAsJsonObject("usage");
                LOG.info("Token 用量: " + usage.toString());
            }

            LOG.info("AI 对话完成: " + (content.length() > 50
                ? content.substring(0, 50) + "..." : content));

            return content.trim();

        } catch (IOException e) {
            LOG.severe("AI API 网络错误: " + e.getMessage());
            throw new CLIException(
                CLIException.ErrorCode.NETWORK_ERROR,
                "无法连接 AI 服务，请检查网络。",
                e
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CLIException(
                CLIException.ErrorCode.NETWORK_ERROR,
                "AI 请求被中断。",
                e
            );
        } catch (JsonParseException e) {
            LOG.severe("AI API 响应解析失败: " + e.getMessage());
            throw new CLIException(
                CLIException.ErrorCode.PARSE_ERROR,
                "AI 返回数据解析失败。",
                e
            );
        }
    }

    /**
     * 检查 AI 服务是否可用（API Key 是否已配置）。
     */
    public boolean isAvailable() {
        return config.isApiKeyConfigured("deepseek.api.key")
            || config.isApiKeyConfigured("qwen.api.key");
    }
}
