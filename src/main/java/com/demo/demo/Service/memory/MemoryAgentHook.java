package com.demo.demo.Service.memory;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.AgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.demo.demo.Service.PeriodicReplyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.LinkedHashMap;
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
    private final PeriodicReplyService periodicReplyService;

    public MemoryAgentHook(
            VectorMemoryStore vectorMemoryStore,
            PeriodicReplyService periodicReplyService) {
        this.vectorMemoryStore = vectorMemoryStore;
        this.periodicReplyService = periodicReplyService;
    }

    @Override
    public String getName() {
        return "memory_agent_hook";
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeAgent(
            OverAllState state, RunnableConfig config) {

        Map<String, Object> metadata = config.metadata().orElse(Map.of());
        Object userValue = metadata.get("user_id");
        if (!(userValue instanceof String userId) || userId.isBlank()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        Map<String, Object> update = new LinkedHashMap<>();
        update.put("user_id", userId);
        Object tokenValue = metadata.get("context_token");
        if (tokenValue instanceof String token && !token.isBlank()) {
            update.put("context_token", token);
        }

        String userQuery = extractLastUserMessage(state);

        List<String> vectorMemories = List.of();
        if (!userQuery.isEmpty()) {
            try {
                vectorMemories = vectorMemoryStore.retrieveRelevant(userId, userQuery);
            } catch (Exception e) {
                log.warn("[MemoryHook] 向量记忆检索失败 error={}",
                        e.getClass().getSimpleName());
            }
        }

        String periodicSummary = "";
        try {
            periodicSummary = periodicReplyService.activeTaskSummary(userId);
        } catch (Exception e) {
            log.warn("[MemoryHook] 周期任务读取失败 error={}",
                    e.getClass().getSimpleName());
        }

        StringBuilder sb = new StringBuilder();
        if (!vectorMemories.isEmpty()) {
            sb.append("【语义相关记忆】\n");
            vectorMemories.forEach(memory ->
                    sb.append("- ").append(memory).append('\n'));
        }
        if (periodicSummary != null && !periodicSummary.isBlank()) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(periodicSummary);
        }
        if (!sb.isEmpty()) {
            update.put(MEMORY_CONTEXT_KEY, sb.toString().trim());
        }

        log.info("[MemoryHook] userId={} vector={} periodic={}",
                maskUserId(userId), vectorMemories.size(),
                periodicSummary != null && !periodicSummary.isBlank());
        return CompletableFuture.completedFuture(Map.copyOf(update));
    }

    // ---- 内部 ----

    /** 从 state.messages 中提取最后一条 UserMessage */
    @SuppressWarnings("unchecked")
    private String extractLastUserMessage(OverAllState state) {
        Optional<Object> messagesOpt = state.value("messages");
        if (messagesOpt.isEmpty()) return "";

        List<Message> messages = (List<Message>) messagesOpt.get();

        return messages.stream()
                .filter(msg -> msg instanceof UserMessage)
                .map(msg -> ((UserMessage) msg).getText())
                .reduce((first, second) -> second)
                .orElse("");
    }

    private static String maskUserId(String userId) {
        if (userId == null || userId.length() < 9) return "***";
        return userId.substring(0, 4) + "..." + userId.substring(userId.length() - 4);
    }
}
