package com.demo.demo.Service.scheduling.tool;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.interceptor.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TrustedToolContextInterceptorTest {

    private final TrustedToolContextInterceptor interceptor = new TrustedToolContextInterceptor();

    @AfterEach
    void tearDown() {
        TrustedToolContext.clear();
    }

    @Test
    void shouldExtractTargetIdFromExecutionContext() {
        RunnableConfig config = RunnableConfig.builder()
                .addMetadata(TrustedToolContextInterceptor.CALLER_TARGET_ID_KEY, "target-123")
                .build();
        ToolCallExecutionContext execCtx = new ToolCallExecutionContext(config, new OverAllState());

        ToolCallRequest request = ToolCallRequest.builder()
                .toolName("testTool")
                .arguments("{}")
                .toolCallId("call-1")
                .executionContext(execCtx)
                .build();

        ToolCallResponse response = interceptor.interceptToolCall(request, req -> {
            // Verify TrustedToolContext has the targetId
            assertEquals("target-123", TrustedToolContext.getTargetId(),
                    "interceptor must inject targetId into TrustedToolContext");
            return ToolCallResponse.of("result", "testTool", "call-1");
        });

        assertNotNull(response);
        assertFalse(response.isError());
    }

    @Test
    void shouldClearContextAfterCall() {
        RunnableConfig config = RunnableConfig.builder()
                .addMetadata(TrustedToolContextInterceptor.CALLER_TARGET_ID_KEY, "target-456")
                .build();
        ToolCallRequest request = ToolCallRequest.builder()
                .toolName("t")
                .arguments("{}")
                .toolCallId("c")
                .executionContext(new ToolCallExecutionContext(config, new OverAllState()))
                .build();

        interceptor.interceptToolCall(request, req ->
                ToolCallResponse.of("ok", "t", "c"));

        assertNull(TrustedToolContext.getTargetId(),
                "TrustedToolContext must be cleared after call");
    }

    @Test
    void shouldHandleNullTargetId() {
        RunnableConfig config = RunnableConfig.builder().build(); // no target ID
        ToolCallRequest request = ToolCallRequest.builder()
                .toolName("t")
                .arguments("{}")
                .toolCallId("c")
                .executionContext(new ToolCallExecutionContext(config, new OverAllState()))
                .build();

        interceptor.interceptToolCall(request, req -> {
            assertNull(TrustedToolContext.getTargetId(),
                    "no target ID should result in null context");
            return ToolCallResponse.of("ok", "t", "c");
        });
    }

    @Test
    void shouldHandleMissingExecutionContext() {
        ToolCallRequest request = ToolCallRequest.builder()
                .toolName("t")
                .arguments("{}")
                .toolCallId("c")
                .build(); // no executionContext

        interceptor.interceptToolCall(request, req -> {
            assertNull(TrustedToolContext.getTargetId());
            return ToolCallResponse.of("ok", "t", "c");
        });
    }

    @Test
    void shouldReturnInterceptorName() {
        assertEquals("trusted_tool_context_interceptor", interceptor.getName());
    }
}
