package com.demo.demo.Service.tool;

import com.demo.demo.Service.schedule.ScheduledTask;
import com.demo.demo.Service.schedule.ScheduledTaskService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class ScheduledTaskTool {

    @Resource
    private ScheduledTaskService scheduledTaskService;

    @Tool(description = "创建定时任务。当用户说'X分钟后提醒我...'、'过N分钟后...'等涉及未来时间执行的操作时使用此工具。" +
            "优先使用 delayMinutes 参数指定延迟分钟数（服务端计算绝对时间，避免日期错误）。" +
            "例如 delayMinutes=5 表示5分钟后执行。只有在无法用分钟数表达时（如'明天下午3点'）才用 executeAt。")
    public String createTask(
            @ToolParam(description = "要执行的动作描述，如'查询杭州天气并发送给我'") String action,
            @ToolParam(description = "延迟分钟数，如 5 表示5分钟后执行。优先使用此参数，避免手动算日期出错。", required = false) Integer delayMinutes,
            @ToolParam(description = "执行时间（仅当 delayMinutes 无法表达时使用），务必确认年份和日期正确。ISO格式如 2026-07-29T14:30:00。", required = false) String executeAt,
            @ToolParam(description = "重复类型：once=一次性执行一次", required = false) String repeat,
            ToolContext toolContext) {

        try {
            // 从 ToolContext 获取真实用户标识和会话令牌
            String userId = contextValue(toolContext, "user_id");
            String contextToken = contextValue(toolContext, "context_token");

            // 更新 latestContextTokens，确保执行时使用最新 token
            scheduledTaskService.updateContextToken(userId, contextToken);

            LocalDateTime time;
            if (delayMinutes != null && delayMinutes > 0) {
                // 服务端计算时间，避免 AI 日期幻觉
                time = LocalDateTime.now().plusMinutes(delayMinutes);
                log.info("[定时任务] 收到创建请求 userId={} action={} delayMinutes={} → executeAt={}",
                        maskUserId(userId), action, delayMinutes, time);
            } else if (executeAt != null && !executeAt.isBlank()) {
                time = LocalDateTime.parse(executeAt,
                        DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                log.info("[定时任务] 收到创建请求 userId={} action={} executeAt={}",
                        maskUserId(userId), action, executeAt);
                // 年份校验：防止 AI 幻觉到错误年份
                int currentYear = LocalDateTime.now().getYear();
                if (time.getYear() < currentYear) {
                    return "创建失败：指定的时间是 " + time.getYear() + " 年，当前是 "
                            + currentYear + " 年，年份错误。请使用 delayMinutes 参数代替，"
                            + "或重新确认正确的年份后重试。";
                }
            } else {
                return "创建失败：必须指定 delayMinutes（延迟分钟数）或 executeAt（执行时间）。"
                        + "建议使用 delayMinutes，如 delayMinutes=" + 5 + " 表示5分钟后执行。";
            }

            if (time.isBefore(LocalDateTime.now())) {
                return "创建失败：指定的时间已经过去了，请选择一个未来的时间。";
            }

            String taskId = scheduledTaskService.scheduleTask(
                    userId,
                    contextToken,
                    action,
                    time);

            return "定时任务已创建，任务ID: " + taskId
                    + "，将在 " + time.format(
                    DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm"))
                    + " 执行。请用简短的话告知用户任务已设置成功，并告知任务ID。";
        } catch (IllegalArgumentException e) {
            return "创建定时任务失败：" + e.getMessage();
        } catch (Exception e) {
            return "创建定时任务失败: " + e.getMessage()
                    + "。请使用 delayMinutes 参数指定延迟分钟数重试。";
        }
    }

    @Tool(description = "取消已创建的定时任务。当用户说'取消定时任务'、'删除提醒'等时使用。")
    public String cancelTask(
            @ToolParam(description = "要取消的任务ID") String taskId) {
        boolean cancelled = scheduledTaskService.cancelTask(taskId);
        if (cancelled) {
            return "任务 " + taskId + " 已取消。请简短告知用户任务已取消。";
        }
        return "未找到任务 " + taskId + " 或该任务已完成/已取消。";
    }

    @Tool(description = "查询当前所有活跃的定时任务列表。当用户说'我的定时任务'、" +
            "'我有哪些提醒'、'查看定时'等时使用。")
    public String listTasks() {
        List<ScheduledTask> tasks = scheduledTaskService.listActiveTasks();
        if (tasks.isEmpty()) {
            return "当前没有活跃的定时任务。";
        }
        StringBuilder sb = new StringBuilder("当前定时任务列表（共 ").append(tasks.size()).append(" 个）：\n");
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM月dd日 HH:mm");
        for (ScheduledTask t : tasks) {
            sb.append("- [").append(t.getId()).append("] ")
                    .append(LocalDateTime.parse(t.getExecuteAt()).format(fmt))
                    .append(" → ").append(t.getAction()).append("\n");
        }
        return sb.toString();
    }

    // ==================== 工具方法 ====================

    private static String contextValue(ToolContext toolContext, String key) {
        if (toolContext == null) {
            throw new IllegalArgumentException("缺少可信工具上下文");
        }
        Map<String, Object> ctx = toolContext.getContext();
        log.info("[定时任务] ToolContext 可用 keySet={}", ctx.keySet());
        Object value = ctx.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("缺少 " + key + "，当前上下文 keys=" + ctx.keySet());
        }
        return text;
    }

    private static String maskUserId(String userId) {
        if (userId == null || userId.length() < 9) return "***";
        return userId.substring(0, 4) + "..." + userId.substring(userId.length() - 4);
    }
}
