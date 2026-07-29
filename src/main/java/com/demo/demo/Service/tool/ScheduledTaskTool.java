package com.demo.demo.Service.tool;

import com.demo.demo.Service.schedule.ScheduledTask;
import com.demo.demo.Service.schedule.ScheduledTaskService;
import jakarta.annotation.Resource;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
public class ScheduledTaskTool {

    @Resource
    private ScheduledTaskService scheduledTaskService;

    @Tool(description = "创建定时任务。当用户说'X点后提醒我...'、'Y点给我发天气'、" +
            "'定时发送...'、'过N分钟后...'等涉及未来时间执行的操作时使用此工具。" +
            "支持一次性定时任务。")
    public String createTask(
            @ToolParam(description = "要执行的动作描述，如'查询杭州天气并发送给我'") String action,
            @ToolParam(description = "执行时间，ISO格式如 2026-07-29T08:00:00") String executeAt,
            @ToolParam(description = "重复类型：once=一次性执行一次", required = false) String repeat) {

        try {
            LocalDateTime time = LocalDateTime.parse(executeAt,
                    DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            if (time.isBefore(LocalDateTime.now())) {
                return "创建失败：指定的时间已经过去了，请选择一个未来的时间。";
            }

            String taskId = scheduledTaskService.scheduleTask(
                    "pending-user",
                    "pending-token",
                    action,
                    time);

            return "定时任务已创建，任务ID: " + taskId
                    + "，将在 " + time.format(
                    DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm"))
                    + " 执行。请用简短的话告知用户任务已设置成功，并告知任务ID。";
        } catch (Exception e) {
            return "创建定时任务失败: " + e.getMessage()
                    + "。请检查时间格式是否正确（需要 yyyy-MM-ddTHH:mm:ss 格式）。";
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
}
