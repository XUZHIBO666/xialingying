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
 * <p>检索来源：
 * <ol>
 *   <li>{@link VectorMemoryStore} — MySQL 向量语义检索</li>
 *   <li>{@link ConversationMemoryStore} — JSON 文件最近对话历史</li>
 * </ol>
 * 结果存入 OverAllState，由 {@link MemoryContextInterceptor} 在每次模型调用时注入 system prompt。
 */
@Slf4j
@HookPositions({HookPosition.BEFORE_AGENT})
public class MemoryAgentHook extends AgentHook {

    public static final String MEMORY_CONTEXT_KEY = "memory_context";

    private final VectorMemoryStore vectorMemoryStore;
    private final ConversationMemoryStore conversationMemoryStore;
    private static final int HISTORY_MAX_PAIRS = 5;

    public MemoryAgentHook(VectorMemoryStore vectorMemoryStore,
                           ConversationMemoryStore conversationMemoryStore) {
        this.vectorMemoryStore = vectorMemoryStore;
        this.conversationMemoryStore = conversationMemoryStore;
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
        List<String> vectorMemories = vectorMemoryStore.retrieveRelevant(userId, userQuery);

        // ---- 4. 最近对话历史 ----
        List<ConversationMessage> history = conversationMemoryStore.getHistory(userId);
        List<String> historyLines = history.stream()
                .skip(Math.max(0, history.size() - HISTORY_MAX_PAIRS * 2L))
                .map(m -> "[" + m.role() + "]: " + m.content())
                .toList();

        // ---- 5. 拼接上下文 ----
        StringBuilder sb = new StringBuilder();
        if (!historyLines.isEmpty()) {
            sb.append("【最近对话】\n");
            historyLines.forEach(line -> sb.append(line).append("\n"));
        }
        if (!vectorMemories.isEmpty()) {
            sb.append("\n【语义相关记忆】\n");
            vectorMemories.forEach(m -> sb.append("- ").append(m).append("\n"));
        }

        String context = sb.toString().trim();
        if (!context.isEmpty()) {
            log.info("[MemoryHook] userId={} history={}条 vector={}条",
                    maskUserId(userId), historyLines.size(), vectorMemories.size());
            return CompletableFuture.completedFuture(
                    Map.of(MEMORY_CONTEXT_KEY, context));
        }

        return CompletableFuture.completedFuture(Map.of());
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
