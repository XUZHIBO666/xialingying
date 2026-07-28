package com.demo.demo.Service.scheduling.tool;

import com.demo.demo.Service.scheduling.application.CreateDailyWeatherTaskCommand;
import com.demo.demo.Service.scheduling.application.ScheduledTaskService;
import com.demo.demo.Service.scheduling.application.SchedulingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * Tool for creating daily weather scheduled tasks.
 *
 * <p>The model provides only business parameters (location, time, timezone).
 * The owner (targetId) comes from {@link TrustedToolContext}, injected by
 * {@link TrustedToolContextInterceptor} — it CANNOT be set by the model.
 */
@Slf4j
@Component
public class CreateScheduledTaskTool {

    private final ScheduledTaskService service;

    public CreateScheduledTaskTool(ScheduledTaskService service) {
        this.service = service;
    }

    @Tool(description = """
            创建每日天气推送任务。当用户说"每天早上8点发送杭州天气"、"每天定时推送北京天气"、
            "订阅上海天气"等需要定时推送天气的意图时使用。
            注意：一次性查询天气（"今天杭州天气怎么样"）请使用 queryWeather 工具，不要使用本工具。""")
    public ScheduledTaskToolResult createDailyWeatherTask(
            @ToolParam(description = "城市或地区名称，例如 杭州、北京、上海") String location,
            @ToolParam(description = "每天发送的时间，格式 HH:mm 如 08:00、20:30") String localTimeStr,
            @ToolParam(description = "时区，例如 Asia/Shanghai、America/New_York、Europe/London。用户不指定时默认 Asia/Shanghai") String timeZoneStr) {

        String targetId = TrustedToolContext.getTargetId();
        if (targetId == null) {
            log.warn("[CreateScheduledTaskTool] Missing caller target ID — rejecting");
            return ScheduledTaskToolResult.error("系统错误：无法识别用户身份，请重新发送消息。");
        }

        // Validate time
        LocalTime localTime;
        try {
            localTime = LocalTime.parse(localTimeStr);
        } catch (DateTimeParseException e) {
            return ScheduledTaskToolResult.error("时间格式不正确，请使用 HH:mm 格式，例如 08:00。");
        }

        // Validate timezone
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(timeZoneStr);
        } catch (Exception e) {
            return ScheduledTaskToolResult.error("时区 '" + timeZoneStr + "' 无效，请使用 IANA 时区名称，例如 Asia/Shanghai。");
        }

        if (location == null || location.isBlank()) {
            return ScheduledTaskToolResult.error("请提供要推送天气的城市名称。");
        }

        try {
            var cmd = new CreateDailyWeatherTaskCommand(targetId, location.trim(), localTime, zoneId);
            String taskId = service.createDailyWeatherTask(cmd);
            log.info("[CreateScheduledTaskTool] Created task={} for target={}",
                    taskId, mask(targetId));
            return ScheduledTaskToolResult.ok(
                    "已创建每日天气推送任务：每天 " + localTimeStr + " 推送 " + location.trim()
                            + " 天气（时区：" + zoneId + "）。任务ID：" + taskId);
        } catch (SchedulingException e) {
            return ScheduledTaskToolResult.error(e.getMessage());
        }
    }

    private static String mask(String s) {
        if (s == null || s.length() < 9) return "***";
        return s.substring(0, 4) + "..." + s.substring(s.length() - 4);
    }
}
