package com.demo.demo.Service;

import com.demo.demo.Service.context.ContextManager;
import com.demo.demo.Service.memory.ConversationMemoryStore;
import com.demo.demo.Service.memory.ConversationMessage;
import com.demo.demo.Service.tool.ImageGenerationTool;
import com.demo.demo.Service.tool.TimeTool;
import com.demo.demo.Service.tool.WeatherTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * AI 大模型服务 —— 基于 Spring AI Alibaba（DashScope / 百炼）。
 *
 * <p>相比旧版手写 OkHttp + Gson 的方式，ChatClient 只需一行链式调用：
 * {@code chatClient.prompt().user(msg).call().content()}
 *
 * <p>工具调用（Function Calling）由 ChatClient 内置处理，
 * 无需手动构建 JSON tools、解析 tool_calls、发第二轮请求。
 */
@Slf4j
@Service
public class AIService {

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;

    @Value("${ai.system-prompt}")
    private String systemPrompt;

    private final ChatClient chatClient;
    private final ConversationMemoryStore memoryStore;
    private final ContextManager contextManager;

    // @Tool 注解的 Bean，传给 ChatClient 即可自动识别和调用
    private final WeatherTool weatherTool;
    private final TimeTool timeTool;
    private final ImageGenerationTool imageGenerationTool;

    /** 用户级锁：保证同一用户的对话历史不会被并发修改 */
    private final ConcurrentMap<String, Object> userLocks = new ConcurrentHashMap<>();

    public AIService(ChatModel chatModel,
                     ConversationMemoryStore memoryStore,
                     ContextManager contextManager,
                     WeatherTool weatherTool,
                     TimeTool timeTool,
                     ImageGenerationTool imageGenerationTool) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.memoryStore = memoryStore;
        this.contextManager = contextManager;
        this.weatherTool = weatherTool;
        this.timeTool = timeTool;
        this.imageGenerationTool = imageGenerationTool;
    }

    // ==================== 简单对话（无工具） ====================

    /** 调用 AI 生成回复，失败时返回 null。 */
    public String chat(String userId, String message) {
        if (!isConfigured()) {
            log.debug("[AI] API Key 未配置，跳过调用");
            return null;
        }

        Object lock = userLocks.computeIfAbsent(userId, ignored -> new Object());
        synchronized (lock) {
            return doChat(userId, message);
        }
    }

    private String doChat(String userId, String message) {
        log.info("[AI] 收到对话请求 userId={} messageLength={}",
                maskUserId(userId), message == null ? 0 : message.length());

        String enhancedSystem = contextManager.buildEnhancedSystemMessage(userId, systemPrompt);

        try {
            String reply = chatClient.prompt()
                    .system(enhancedSystem)
                    .messages(toHistoryMessages(userId))
                    .user(message)
                    .call()
                    .content();

            if (reply == null || reply.isBlank()) {
                log.warn("[AI] 回复为空 userId={}", maskUserId(userId));
                return null;
            }

            memoryStore.appendTurn(userId, message, reply.trim());
            log.info("[AI] 回复成功 userId={} replyLength={}",
                    maskUserId(userId), reply.length());
            return reply.trim();
        } catch (Exception e) {
            log.error("[AI] 调用异常 userId={}: {}", maskUserId(userId), e.getMessage());
            return null;
        }
    }

    // ==================== Function Calling 对话 ====================

    /**
     * 带 Function Calling 能力的聊天——LLM 可自动调用 {@link WeatherTool}、
     * {@link TimeTool}、{@link ImageGenerationTool}。
     * <p>ChatClient 内置工具调用循环，无需手动处理 tool_calls 和重试。
     */
    public String chatWithTools(String userId, String message) {
        if (!isConfigured()) {
            log.debug("[AI] API Key 未配置，跳过 FC 调用");
            return null;
        }

        Object lock = userLocks.computeIfAbsent(userId, ignored -> new Object());
        synchronized (lock) {
            return doChatWithTools(userId, message);
        }
    }

    private String doChatWithTools(String userId, String message) {
        log.info("[AI] Function Calling 请求 userId={} messageLength={}",
                maskUserId(userId), message == null ? 0 : message.length());

        String enhancedSystem = contextManager.buildEnhancedSystemMessage(userId, systemPrompt);

        try {
            String reply = chatClient.prompt()
                    .system(enhancedSystem)
                    .messages(toHistoryMessages(userId))
                    .user(message)
                    .tools(weatherTool, timeTool, imageGenerationTool)
                    .call()
                    .content();

            if (reply == null || reply.isBlank()) {
                log.warn("[AI] FC回复为空 userId={}", maskUserId(userId));
                return null;
            }

            memoryStore.appendTurn(userId, message, reply.trim());
            log.info("[AI] FC回复成功 userId={} replyLength={}",
                    maskUserId(userId), reply.length());
            return reply.trim();
        } catch (Exception e) {
            log.error("[AI] FC调用异常 userId={} type={} error={}",
                    maskUserId(userId), e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    // ==================== 对话记忆 ====================

    /** 清除指定用户的持久化对话历史。 */
    public void clearHistory(String userId) {
        Object lock = userLocks.computeIfAbsent(userId, ignored -> new Object());
        synchronized (lock) {
            try {
                memoryStore.clear(userId);
                log.info("[AI] 清除对话历史 userId={}", maskUserId(userId));
            } catch (Exception e) {
                log.error("[AI] 清除对话历史失败 userId={} error={}",
                        maskUserId(userId), e.getMessage());
            }
        }
    }

    /** 是否已经配置 API Key。排除占位符和默认假值。 */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank()
                && !"sk-placeholder".equals(apiKey)
                && !"你的API_KEY".equals(apiKey);
    }

    // ==================== 内部方法 ====================

    /** 将持久化的对话历史转为 Spring AI Message 数组。 */
    private Message[] toHistoryMessages(String userId) {
        return memoryStore.getHistory(userId).stream()
                .map(m -> {
                    if ("user".equals(m.role())) {
                        return (Message) new UserMessage(m.content());
                    } else if ("assistant".equals(m.role())) {
                        return new AssistantMessage(m.content());
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .toArray(Message[]::new);
    }

    private String maskUserId(String userId) {
        if (userId == null || userId.length() < 9) {
            return "***";
        }
        return userId.substring(0, 4) + "..." + userId.substring(userId.length() - 4);
    }
}
