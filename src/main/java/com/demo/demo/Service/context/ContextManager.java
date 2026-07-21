package com.demo.demo.Service.context;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 管理所有用户的感知上下文。
 *
 * 在图片识别或语音转写完成后记录摘要，在 LLM 调用时
 * 将近期上下文注入 system prompt，实现跨模态感知。
 */
@Slf4j
@Component
public class ContextManager {

    private final ConcurrentMap<String, SensorialContext> contexts
            = new ConcurrentHashMap<>();
    private static final int MAX_CONTEXTS = 200;

    /** 记录一次图片识别结果。 */
    public void recordImage(String userId, String description) {
        if (description == null || description.isBlank()) return;
        contextFor(userId).recordImage(description.trim());
    }

    /** 记录一次语音转写结果。 */
    public void recordVoice(String userId, String transcript) {
        if (transcript == null || transcript.isBlank()) return;
        contextFor(userId).recordVoice(transcript.trim());
    }

    /**
     * 构建增强的 system prompt：原始 prompt + 近期上下文提示。
     * 无上下文时返回原始 prompt。
     */
    public String buildEnhancedSystemMessage(String userId, String baseSystemPrompt) {
        SensorialContext ctx = contexts.get(userId);
        if (ctx == null) return baseSystemPrompt;

        String hint = ctx.buildContextHint();
        if (hint.isEmpty()) return baseSystemPrompt;

        return baseSystemPrompt + "\n\n[近期上下文]\n" + hint;
    }

    /** 清除指定用户的上下文。 */
    public void clear(String userId) {
        contexts.remove(userId);
    }

    /** 清除所有过期上下文。 */
    public void cleanExpired() {
        contexts.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    /** 活跃用户数。 */
    public int getActiveCount() {
        cleanExpired();
        return contexts.size();
    }

    private SensorialContext contextFor(String userId) {
        return contexts.computeIfAbsent(userId, k -> {
            if (contexts.size() >= MAX_CONTEXTS) {
                cleanExpired();
                if (contexts.size() >= MAX_CONTEXTS) {
                    // 超过上限时清除一个旧条目
                    contexts.entrySet().stream().findFirst()
                            .ifPresent(e -> contexts.remove(e.getKey()));
                }
            }
            return new SensorialContext();
        });
    }
}
