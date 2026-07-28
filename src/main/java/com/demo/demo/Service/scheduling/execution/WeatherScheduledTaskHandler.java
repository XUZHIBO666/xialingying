package com.demo.demo.Service.scheduling.execution;

import com.demo.demo.Service.scheduling.domain.*;
import com.demo.demo.Service.weather.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Handler for {@code DAILY_WEATHER} tasks.
 *
 * <p>Extracted from {@link ScheduledTaskExecutionService} to keep the
 * common executor free of task-type-specific logic.
 */
@Slf4j
@Component
public class WeatherScheduledTaskHandler implements ScheduledTaskHandler {

    private final WeatherService weatherService;
    private final ScheduledContentAgent contentAgent;
    private final WeatherMessageTemplateFormatter fallbackFormatter;
    private final MessagePushGateway pushGateway;

    public WeatherScheduledTaskHandler(
            WeatherService weatherService,
            ScheduledContentAgent contentAgent,
            WeatherMessageTemplateFormatter fallbackFormatter,
            MessagePushGateway pushGateway) {
        this.weatherService = weatherService;
        this.contentAgent = contentAgent;
        this.fallbackFormatter = fallbackFormatter;
        this.pushGateway = pushGateway;
    }

    @Override
    public String taskType() {
        return ScheduledTask.TASK_TYPE_DAILY_WEATHER;
    }

    @Override
    public TaskHandlingResult handle(ScheduledTask task, TaskExecution execution) {
        // Fetch weather
        WeatherReport report;
        try {
            report = weatherService.query(new WeatherQuery(task.weatherPayload().location(), "今天"));
        } catch (Exception e) {
            log.error("[WeatherHandler] Weather failed task={}: {}", task.taskId(), e.getMessage());
            return TaskHandlingResult.failed(mapWeatherError(e));
        }

        // Generate content (agent → template fallback)
        String text = generateContent(task, report);
        if (text == null || text.isBlank()) {
            return TaskHandlingResult.failed("AGENT_FAILURE");
        }

        // Push
        PushResult result = pushGateway.pushText(new PushRequest(task.ownerTargetId(), text));
        if (result.success()) {
            log.info("[WeatherHandler] Push OK task={} chars={}", task.taskId(), text.length());
            return TaskHandlingResult.succeeded();
        }
        log.warn("[WeatherHandler] Push failed task={}: {}", task.taskId(), result.errorCode());
        return TaskHandlingResult.failed(result.errorCode());
    }

    private String generateContent(ScheduledTask task, WeatherReport report) {
        try {
            String text = contentAgent.generate(task, report);
            if (text != null && !text.isBlank()) return text;
            log.warn("[WeatherHandler] Agent empty, using template");
        } catch (Exception e) {
            log.error("[WeatherHandler] Agent failed: {}", e.getMessage());
        }
        return fallbackFormatter.format(report);
    }

    private static String mapWeatherError(Exception e) {
        if (e instanceof WeatherException we) {
            return switch (we.getError()) {
                case PROVIDER_TIMEOUT -> "PROVIDER_TIMEOUT";
                case PROVIDER_UNAVAILABLE, PROVIDER_RESPONSE_INVALID -> "PROVIDER_UNAVAILABLE";
                default -> "WEATHER_ERROR";
            };
        }
        return "WEATHER_ERROR";
    }
}
