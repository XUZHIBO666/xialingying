package com.demo.demo.Service.scheduling.tool;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.interceptor.*;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Extracts the caller target ID from {@link RunnableConfig} metadata
 * and stores it in {@link TrustedToolContext} before each tool execution.
 *
 * <p>The target ID is set by the inbound message handler via
 * {@code RunnableConfig.addMetadata("caller_target_id", ...)} and is
 * NOT modifiable by the model.
 */
@Slf4j
@Component
public class TrustedToolContextInterceptor extends ToolInterceptor {

    public static final String CALLER_TARGET_ID_KEY = "caller_target_id";

    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        String targetId = extractTargetId(request);

        if (targetId != null) {
            TrustedToolContext.setTargetId(targetId);
            log.debug("[TrustedToolContext] Injecting targetId={}", mask(targetId));
        }

        try {
            return handler.call(request);
        } finally {
            TrustedToolContext.clear();
        }
    }

    @Override
    public String getName() {
        return "trusted_tool_context_interceptor";
    }

    private String extractTargetId(ToolCallRequest request) {
        return request.getExecutionContext()
                .map(ToolCallExecutionContext::config)
                .flatMap(config -> config.metadata(CALLER_TARGET_ID_KEY))
                .map(Object::toString)
                .orElse(null);
    }

    private static String mask(String s) {
        if (s == null || s.length() < 9) return "***";
        return s.substring(0, 4) + "..." + s.substring(s.length() - 4);
    }
}
