package com.demo.demo.Service.memory;
// 在 Hook 中更新状态
import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class UpdateStateHook extends ModelHook {

    @Override
    public String getName() {
        return "update_state";
    }

    @Override
    public HookPosition[] getHookPositions() {
        return new HookPosition[]{HookPosition.AFTER_MODEL};
    }

    @Override
    public CompletableFuture<Map<String, Object>> afterModel(OverAllState state, RunnableConfig config) {
// 更新状态
       String currentUserId = config.metadata()
               .map(meta -> (String) meta.get("user_id"))
               .orElse("unknown");

        return CompletableFuture.completedFuture(Map.of(
                "user_name", currentUserId,
                "last_updated", System.currentTimeMillis()
        ));
    }
}
