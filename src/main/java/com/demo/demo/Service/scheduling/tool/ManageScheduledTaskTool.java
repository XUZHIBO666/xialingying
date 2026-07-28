package com.demo.demo.Service.scheduling.tool;

import com.demo.demo.Service.scheduling.application.ScheduledTaskService;
import com.demo.demo.Service.scheduling.application.ScheduledTaskSummary;
import com.demo.demo.Service.scheduling.application.SchedulingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Tool for managing existing scheduled tasks.
 *
 * <p>Supports list, pause, resume, and cancel actions. The owner is
 * determined from {@link TrustedToolContext}, NOT from model parameters.
 */
@Slf4j
@Component
public class ManageScheduledTaskTool {

    private final ScheduledTaskService service;

    public ManageScheduledTaskTool(ScheduledTaskService service) {
        this.service = service;
    }

    @Tool(description = """
            管理已创建的定时任务。支持的操作：
            - LIST: 列出当前所有定时任务
            - PAUSE: 暂停指定任务（需要 taskId）
            - RESUME: 恢复指定任务（需要 taskId）
            - CANCEL: 取消指定任务（需要 taskId）

            当用户说"我有哪些定时任务"、"查看定时任务"时使用 LIST。
            当用户说"暂停那个天气推送"、"取消杭州的定时"时使用 PAUSE 或 CANCEL。""")
    public ScheduledTaskToolResult manage(
            @ToolParam(description = "操作类型：LIST, PAUSE, RESUME, CANCEL") String action,
            @ToolParam(description = "任务ID（LIST 操作不需要，PAUSE/RESUME/CANCEL 必须提供）", required = false) String taskId) {

        String targetId = TrustedToolContext.getTargetId();
        if (targetId == null) {
            return ScheduledTaskToolResult.error("系统错误：无法识别用户身份，请重新发送消息。");
        }

        try {
            return switch (action.toUpperCase()) {
                case "LIST" -> handleList(targetId);
                case "PAUSE" -> handlePause(targetId, taskId);
                case "RESUME" -> handleResume(targetId, taskId);
                case "CANCEL" -> handleCancel(targetId, taskId);
                default -> ScheduledTaskToolResult.error(
                        "不支持的操作：" + action + "。支持的操作：LIST, PAUSE, RESUME, CANCEL。");
            };
        } catch (SchedulingException e) {
            return ScheduledTaskToolResult.error(e.getMessage());
        }
    }

    private ScheduledTaskToolResult handleList(String targetId) {
        List<ScheduledTaskSummary> tasks = service.listTasks(targetId);
        if (tasks.isEmpty()) {
            return ScheduledTaskToolResult.ok("你当前没有定时任务。对我说'每天早上8点发送杭州天气'来创建一个。");
        }
        String list = tasks.stream()
                .map(t -> String.format("- [%s] %s: 每天 %s 推送 %s 天气",
                        t.taskId(), t.status(), t.localTime(), t.location()))
                .collect(Collectors.joining("\n"));
        log.info("[ManageScheduledTaskTool] Listed {} tasks for target={}",
                tasks.size(), mask(targetId));
        return ScheduledTaskToolResult.ok("你的定时任务：\n" + list);
    }

    private ScheduledTaskToolResult handlePause(String targetId, String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return ScheduledTaskToolResult.error("PAUSE 操作需要提供任务ID。");
        }
        service.pause(targetId, taskId);
        log.info("[ManageScheduledTaskTool] Paused task={}", taskId);
        return ScheduledTaskToolResult.ok("任务 " + taskId + " 已暂停。");
    }

    private ScheduledTaskToolResult handleResume(String targetId, String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return ScheduledTaskToolResult.error("RESUME 操作需要提供任务ID。");
        }
        service.resume(targetId, taskId);
        return ScheduledTaskToolResult.ok("任务 " + taskId + " 已恢复。");
    }

    private ScheduledTaskToolResult handleCancel(String targetId, String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return ScheduledTaskToolResult.error("CANCEL 操作需要提供任务ID。");
        }
        service.cancel(targetId, taskId);
        return ScheduledTaskToolResult.ok("任务 " + taskId + " 已取消。");
    }

    private static String mask(String s) {
        if (s == null || s.length() < 9) return "***";
        return s.substring(0, 4) + "..." + s.substring(s.length() - 4);
    }
}
