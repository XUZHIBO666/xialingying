package com.demo.demo.Service.memory;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.Map;

/**
 * 每次模型调用前，把 {@link MemoryAgentHook} 检索到的记忆上下文注入 system prompt。
 *
 * <p>数据流：AgentHook → OverAllState → ModelRequest.getContext() → 本类读取。
 */
public class MemoryContextInterceptor extends ModelInterceptor {

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {

        // 从上下文取 Hook 存入的记忆
        Map<String, Object> context = request.getContext();
        String memoryContext = (String) context.get(MemoryAgentHook.MEMORY_CONTEXT_KEY);

        // 没有记忆 → 原样放行
        if (memoryContext == null || memoryContext.isEmpty()) {
            return handler.call(request);
        }

        // 构建记忆增强 prompt
        String memoryPrompt = String.format("""

                [系统指令·长期记忆]
                以下是和当前用户的历史交互记忆，请参考这些信息理解用户的上下文：

                %s

                使用规则：
                - 如果记忆中有用户的名字、偏好、之前讨论的话题，在回复中自然地体现
                - 如果用户当前问题和记忆无关，忽略记忆正常回复
                - 不要生硬地复述记忆内容
                """, memoryContext);

        // 拼到原有 system message 后面
        SystemMessage newSystemMessage;
        if (request.getSystemMessage() == null) {
            newSystemMessage = new SystemMessage(memoryPrompt);
        } else {
            newSystemMessage = new SystemMessage(
                    request.getSystemMessage().getText() + "\n" + memoryPrompt);
        }

        ModelRequest enhanced = ModelRequest.builder(request)
                .systemMessage(newSystemMessage)
                .build();

        return handler.call(enhanced);
    }

    @Override
    public String getName() {
        return "memory_context_interceptor";
    }
}
