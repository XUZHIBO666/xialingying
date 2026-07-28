package com.demo.demo.Service.scheduling.execution;

import com.demo.demo.Service.scheduling.domain.*;
import com.demo.demo.Service.weather.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WeatherScheduledTaskHandlerTest {

    private WeatherService mockWeather;
    private ScheduledContentAgent mockAgent;
    private WeatherMessageTemplateFormatter formatter;
    private AtomicReference<String> pushedText;
    private MessagePushGateway gateway;
    private WeatherScheduledTaskHandler handler;
    private WeatherReport stubReport;

    @BeforeEach
    void setUp() {
        mockWeather = mock(WeatherService.class);
        mockAgent = mock(ScheduledContentAgent.class);
        formatter = new WeatherMessageTemplateFormatter();
        pushedText = new AtomicReference<>();
        gateway = req -> { pushedText.set(req.text()); return PushResult.ok(); };
        handler = new WeatherScheduledTaskHandler(mockWeather, mockAgent, formatter, gateway);

        stubReport = new WeatherReport(
                WeatherReportType.CURRENT,
                new WeatherLocation("杭州", "杭州", "浙江", "中国", 30.25, 120.16, ZoneId.of("Asia/Shanghai")),
                LocalDate.now(), Instant.now(),
                new CurrentConditions(22.5, 21.0, 65, 12.0, 180, 1),
                null, "test");
    }

    @Test
    void handleShouldQueryWeatherGenerateTextAndPush() {
        when(mockWeather.query(any())).thenReturn(stubReport);
        when(mockAgent.generate(any(), any())).thenReturn("杭州晴，22°C");

        ScheduledTask task = createWeatherTask("target-1", "杭州");
        TaskExecution exec = createExecution("task-1");

        TaskHandlingResult result = handler.handle(task, exec);

        assertEquals(TaskHandlingResult.Status.SUCCEEDED, result.status());
        assertNotNull(pushedText.get());
        assertTrue(pushedText.get().contains("杭州"));
    }

    @Test
    void handleShouldUseTemplateWhenAgentReturnsEmpty() {
        when(mockWeather.query(any())).thenReturn(stubReport);
        when(mockAgent.generate(any(), any())).thenReturn("");

        TaskHandlingResult result = handler.handle(createWeatherTask("t", "杭州"), createExecution("e"));

        assertEquals(TaskHandlingResult.Status.SUCCEEDED, result.status());
        assertNotNull(pushedText.get());
        assertTrue(pushedText.get().contains("自动推送"));
    }

    @Test
    void handleShouldReturnFailureWhenWeatherFails() {
        when(mockWeather.query(any()))
                .thenThrow(new WeatherException(WeatherError.PROVIDER_UNAVAILABLE, "down"));

        TaskHandlingResult result = handler.handle(createWeatherTask("t", "杭州"), createExecution("e"));

        assertEquals(TaskHandlingResult.Status.FAILED, result.status());
        assertEquals("PROVIDER_UNAVAILABLE", result.errorCode());
        assertNull(pushedText.get());
    }

    @Test
    void handleShouldReturnFailureWhenPushFails() {
        when(mockWeather.query(any())).thenReturn(stubReport);
        when(mockAgent.generate(any(), any())).thenReturn("text");

        // gateway that fails
        handler = new WeatherScheduledTaskHandler(mockWeather, mockAgent, formatter,
                req -> PushResult.failed("SDK_ERROR"));

        TaskHandlingResult result = handler.handle(createWeatherTask("t", "杭州"), createExecution("e"));

        assertEquals(TaskHandlingResult.Status.FAILED, result.status());
        assertEquals("SDK_ERROR", result.errorCode());
    }

    @Test
    void taskTypeShouldBeDailyWeather() {
        assertEquals("DAILY_WEATHER", handler.taskType());
    }

    private ScheduledTask createWeatherTask(String targetId, String location) {
        return ScheduledTask.createDailyWeather(
                targetId, location, LocalTime.of(8, 0), ZoneId.of("Asia/Shanghai"),
                Instant.now().plusSeconds(3600), Instant.now());
    }

    private TaskExecution createExecution(String taskId) {
        return TaskExecution.createPending(taskId, Instant.now(), Instant.now());
    }
}
