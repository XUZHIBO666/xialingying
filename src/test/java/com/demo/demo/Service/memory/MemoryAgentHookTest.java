package com.demo.demo.Service.memory;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.demo.demo.Service.PeriodicReplyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;

import static com.demo.demo.Service.memory.MemoryAgentHook.MEMORY_CONTEXT_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemoryAgentHookTest {

    private VectorMemoryStore vectorMemoryStore;
    private PeriodicReplyService periodicReplyService;
    private MemoryAgentHook hook;
    private OverAllState state;
    private RunnableConfig config;

    @BeforeEach
    void setUp() {
        vectorMemoryStore = mock(VectorMemoryStore.class);
        periodicReplyService = mock(PeriodicReplyService.class);
        hook = new MemoryAgentHook(vectorMemoryStore, periodicReplyService);
        state = new OverAllState(Map.of(
                "messages", List.of(new UserMessage("hello"))));
        config = RunnableConfig.builder()
                .addMetadata("user_id", "u")
                .addMetadata("context_token", "secret-token")
                .build();
    }

    @Test
    void includesPeriodicTasksWhenVectorLookupFails() {
        when(vectorMemoryStore.retrieveRelevant("u", "hello"))
                .thenThrow(new RuntimeException("embedding unavailable"));
        when(periodicReplyService.activeTaskSummary("u"))
                .thenReturn("【当前有效周期任务】\n- 任务1：每天 08:00");

        Map<String, Object> result = hook.beforeAgent(state, config).join();

        assertTrue(result.get(MEMORY_CONTEXT_KEY).toString().contains("任务1"));
    }

    @Test
    void includesVectorMemoryWhenPeriodicLookupFails() {
        when(vectorMemoryStore.retrieveRelevant("u", "hello"))
                .thenReturn(List.of("用户喜欢简洁回复"));
        when(periodicReplyService.activeTaskSummary("u"))
                .thenThrow(new RuntimeException("file unavailable"));

        Map<String, Object> result = hook.beforeAgent(state, config).join();

        assertTrue(result.get(MEMORY_CONTEXT_KEY).toString()
                .contains("用户喜欢简洁回复"));
    }

    @Test
    void exposesTrustedIdentityToModelAndToolContext() {
        when(vectorMemoryStore.retrieveRelevant("u", "hello")).thenReturn(List.of());
        when(periodicReplyService.activeTaskSummary("u")).thenReturn("");

        Map<String, Object> result = hook.beforeAgent(state, config).join();

        assertEquals("u", result.get("user_id"));
        assertEquals("secret-token", result.get("context_token"));
    }
}
