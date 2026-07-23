package com.demo.demo.Service;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import com.alibaba.cloud.ai.graph.agent.hook.messages.UpdatePolicy;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.demo.demo.Service.context.ContextManager;
import com.demo.demo.Service.tool.EmailTool;
import com.demo.demo.Service.tool.ImageGenerationTool;
import com.demo.demo.Service.tool.TimeTool;
import com.demo.demo.Service.tool.WeatherTool;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * AI 大模型服务 —— 基于 Spring AI Alibaba（DashScope / 百炼）。
 *
 * <p>使用 ReactAgent 替代旧版 ChatClient，内置 ReAct 循环和工具调用。
 * <p>对话记忆由 {@link MemorySaver} 自动管理，按 threadId（userId）隔离。
 */
@Slf4j
@Service
public class AIService {
    @Value("${spring.ai.dashscope.api-key:}")
    private String apiKey;
    @Value("${ai.system-prompt}")
    private String systemPrompt;
    private ReactAgent agent;
    private final MemorySaver memorySaver;
    private final ContextManager contextManager;
    private final WeatherTool weatherTool;
    private final TimeTool timeTool;
    private final ImageGenerationTool imageGenerationTool;
    private final EmailTool emailTool;
    /** 用户级锁：保证同一用户的对话历史不会被并发修改 */
    private final ConcurrentMap<String, Object> userLocks = new ConcurrentHashMap<>();

    public AIService(
                     ContextManager contextManager,
                     WeatherTool weatherTool,
                     TimeTool timeTool,
                     ImageGenerationTool imageGenerationTool,
                     EmailTool emailTool) {
        this.memorySaver = new MemorySaver();
        this.contextManager = contextManager;
        this.weatherTool = weatherTool;
        this.timeTool = timeTool;
        this.imageGenerationTool = imageGenerationTool;
        this.emailTool = emailTool;
    }

    @PostConstruct
    public void init() {
        DashScopeApi dashScopeApi = DashScopeApi.builder()
                .apiKey(apiKey)
                .build();
        DashScopeChatModel chatModel = DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel("qwen-max")
                        .withTemperature(0.7)
                        .withMaxToken(1000)
                        .enableThinking(true)
                        .topP(0.9)
                        .build())
                .build();

        MessagesModelHook trimHook = new MessagesModelHook() {
            @Override
            public String getName() {
                return "trimHook";
            }

            @Override
            public AgentCommand beforeModel(List<Message> messages, RunnableConfig config) {
                int maxMessages = 20;
                if (messages.size() > maxMessages) {
                    List<Message> trimmed = new ArrayList<>();
                    trimmed.addAll(messages.subList(0, 2));
                    trimmed.addAll(messages.subList(messages.size() - (maxMessages - 2), messages.size()));
                    return new AgentCommand(trimmed, UpdatePolicy.REPLACE);
                }
                return null;
            }
        };

        this.agent = ReactAgent.builder()
                .name("wechat_agent")
                .model(chatModel)
                .systemPrompt(systemPrompt)
                .saver(memorySaver)
                .tools(ToolCallbacks.from(weatherTool, timeTool, imageGenerationTool, emailTool))
                .hooks(trimHook)
                .build();
    }

    // ==================== 对话（ReactAgent 内置工具调用） ====================

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
            RunnableConfig runnableConfig = RunnableConfig.builder()
                    .threadId(userId)
                    .addMetadata("user_id", userId)
                    .addMetadata("system_prompt", enhancedSystem)
                    .build();
            AssistantMessage response = agent.call(message, runnableConfig);
            String reply = response.getText();
            if (reply == null || reply.isBlank()) {
                log.warn("[AI] 回复为空 userId={}", maskUserId(userId));
                return null;
            }

            log.info("[AI] 回复成功 userId={} replyLength={}",
                    maskUserId(userId), reply.length());
            return reply.trim();
        } catch (Exception e) {
            log.error("[AI] 调用异常 userId={}: {}", maskUserId(userId), e.getMessage());
            return null;
        }
    }

    /**
     * 带 Function Calling 能力的聊天，直接复用 chat()。
     * ReactAgent 已在构造时注册了 {@link WeatherTool}、{@link TimeTool}、
     * {@link ImageGenerationTool}、{@link EmailTool}，调用时 LLM 自动决定是否触发工具。
     */
    public String chatWithTools(String userId, String message) {
        return chat(userId, message);
    }

    // ==================== 对话记忆 ====================

    /** 是否已经配置 API Key。排除占位符和默认假值。 */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank()
                && !"sk-placeholder".equals(apiKey)
                && !"你的API_KEY".equals(apiKey);
    }

    // ==================== 内部方法 ====================

    private String maskUserId(String userId) {
        if (userId == null || userId.length() < 9) {
            return "***";
        }
        return userId.substring(0, 4) + "..." + userId.substring(userId.length() - 4);
    }
}
