package com.demo.demo.Service.memory;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.AgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 在 ReactAgent 开始时检索长期记忆（只执行一次）。
 *
 * <p>检索来源：{@link VectorMemoryStore} — SQLite 向量语义检索。
 * <p>结果存入 OverAllState，由 {@link MemoryContextInterceptor} 在每次模型调用时注入 system prompt。
 */
@Slf4j
@HookPositions({HookPosition.BEFORE_AGENT})
public class MemoryAgentHook extends AgentHook {

    public static final String MEMORY_CONTEXT_KEY = "memory_context";

    private final VectorMemoryStore vectorMemoryStore;

    public MemoryAgentHook(VectorMemoryStore vectorMemoryStore) {
        this.vectorMemoryStore = vectorMemoryStore;
    }

    @Override
    public String getName() {
        return "memory_agent_hook";
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeAgent(
            OverAllState state, RunnableConfig config) {

        // ---- 1. 取 userId ----
        String userId = config.metadata()
                .map(meta -> (String) meta.get("user_id"))
                .orElse(null);
        if (userId == null) {
            return CompletableFuture.completedFuture(Map.of());
        }

        // ---- 2. 从 state 提取最后一条用户消息作为查询 ----
        String userQuery = extractLastUserMessage(state);
        if (userQuery.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }

        // ---- 3. 向量语义检索 ----
        List<String> vectorMemories;
        try {
            vectorMemories = vectorMemoryStore.retrieveRelevant(userId, userQuery);
        } catch (Exception e) {
            log.warn("[MemoryHook] 向量记忆检索失败: {}", e.getMessage());
            return CompletableFuture.completedFuture(Map.of());
        }

        if (vectorMemories.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }

        // ---- 4. 拼接上下文 ----
        StringBuilder sb = new StringBuilder();
        sb.append("【语义相关记忆】\n");
        vectorMemories.forEach(m -> sb.append("- ").append(m).append("\n"));
        String context = sb.toString().trim();

        log.info("[MemoryHook] userId={} vector={}条",
                maskUserId(userId), vectorMemories.size());
        return CompletableFuture.completedFuture(
                Map.of(MEMORY_CONTEXT_KEY, context));
    }

    // ---- 内部 ----

    /** 从 state.messages 中提取最后一条 UserMessage */
    @SuppressWarnings("unchecked")
    private String extractLastUserMessage(OverAllState state) {
        Optional<Object> messagesOpt = state.value("messages");
        if (messagesOpt.isEmpty()) return "";

        List<Message> messages = (List<Message>) messagesOpt.get();

        return messages.stream()
                .filter(msg -> msg instanceof UserMessage)//获取用户的信息
                .map(msg -> ((UserMessage) msg).getText())//获取用户信息中的文本
                .reduce((first, second) -> second)//保留最后一条信息
                .orElse("");
    }

    private static String maskUserId(String userId) {
        if (userId == null || userId.length() < 9) return "***";
        return userId.substring(0, 4) + "..." + userId.substring(userId.length() - 4);
    }
}
