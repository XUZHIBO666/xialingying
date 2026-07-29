package com.demo.demo.Service.memory;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.Map;

public class UserStateInterceptor extends ModelInterceptor {

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        Map<String,Object> context = request.getContext();
        String userName = (String) context.get("user_name");
        Long lastUpdated = (Long) context.get("last_updated");
        // 没有数据 → 原样放行
        if (userName == null && lastUpdated == null) {
            return handler.call(request);
        }
        // 构建状态提示
        StringBuilder sb = new StringBuilder();
        sb.append("\n[用户状态]\n");
        if (userName != null) {
            sb.append("- 当前用户: ").append(userName).append("\n");
        }
        if (lastUpdated != null) {
            sb.append("- 状态更新时间: ").append(lastUpdated).append("\n");
        }

        // 拼到原有 system message 后面
        SystemMessage newSystemMessage;
        if (request.getSystemMessage() == null) {
            newSystemMessage = new SystemMessage(sb.toString());
        } else {
            newSystemMessage = new SystemMessage(
                    request.getSystemMessage().getText() + sb.toString());
        }

        ModelRequest enhanced = ModelRequest.builder(request)
                .systemMessage(newSystemMessage)
                .build();

        return handler.call(enhanced);

    }

    @Override
    public String getName() {
        return "user_state_interceptor";
    }
}
