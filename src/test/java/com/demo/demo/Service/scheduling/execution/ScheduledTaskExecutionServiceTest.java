package com.demo.demo.Service.scheduling.execution;

import com.demo.demo.Service.scheduling.domain.*;
import com.demo.demo.Service.scheduling.persistence.*;
import com.demo.demo.Service.weather.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScheduledTaskExecutionServiceTest {

    private ScheduledTaskExecutionService service;
    private ScheduledTaskRepository taskRepo;
    private TaskExecutionRepository execRepo;
    private Path tempFile;
    private AtomicReference<String> lastPushedText;
    private WeatherReport stubReport;
    private WeatherService mockWeatherService;

    @BeforeEach
    void setUp() throws Exception {
        tempFile = Files.createTempFile("execution-svc-", ".sqlite");
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempFile.toAbsolutePath());
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        new SchedulingSchemaInitializer(jdbc).init();
        taskRepo = new JdbcScheduledTaskRepository(jdbc);
        execRepo = new JdbcTaskExecutionRepository(jdbc);
        lastPushedText = new AtomicReference<>();

        stubReport = new WeatherReport(
                WeatherReportType.CURRENT,
                new WeatherLocation("杭州", "杭州", "浙江", "中国", 30.25, 120.16, ZoneId.of("Asia/Shanghai")),
                LocalDate.now(),
                Instant.now(),
                new CurrentConditions(22.5, 21.0, 65, 12.0, 180, 1),
                null, "Open-Meteo");

        mockWeatherService = mock(WeatherService.class);
        when(mockWeatherService.query(any())).thenReturn(stubReport);

        MessagePushGateway testGateway = req -> {
            lastPushedText.set(req.text());
            return PushResult.ok();
        };

        ScheduledContentAgent testAgent = (task, report) ->
                "杭州今天多云，22°C，适合出行。";

        WeatherMessageTemplateFormatter formatter = new WeatherMessageTemplateFormatter();

        WeatherScheduledTaskHandler weatherHandler = new WeatherScheduledTaskHandler(
                mockWeatherService, testAgent, formatter, testGateway);

        ScheduledTaskHandlerRegistry registry = new ScheduledTaskHandlerRegistry(
                List.of(weatherHandler));

        service = new ScheduledTaskExecutionService(execRepo, taskRepo, registry);
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(tempFile);
    }

    @Test
    void executeShouldPushContentOnSuccess() {
        ScheduledTask task = taskRepo.insert(ScheduledTask.createDailyWeather(
                "target-1", "杭州", LocalTime.of(8, 0), ZoneId.of("Asia/Shanghai"),
                Instant.now().plusSeconds(3600), Instant.now()));
        TaskExecution exec = execRepo.insertUnique(
                task.taskId(), task.nextRunAt(), Instant.now()).orElseThrow();

        service.execute(exec.executionId());

        assertNotNull(lastPushedText.get(), "should push text");
        assertTrue(lastPushedText.get().contains("杭州"));
    }

    @Test
    void executeShouldFallbackToTemplateWhenAgentFails() {
        ScheduledContentAgent failingAgent = (task, report) -> {
            throw new RuntimeException("simulated agent failure");
        };
        WeatherMessageTemplateFormatter formatter = new WeatherMessageTemplateFormatter();

        WeatherScheduledTaskHandler weatherHandler = new WeatherScheduledTaskHandler(
                mockWeatherService, failingAgent, formatter,
                req -> { lastPushedText.set(req.text()); return PushResult.ok(); });

        ScheduledTaskHandlerRegistry registry = new ScheduledTaskHandlerRegistry(
                List.of(weatherHandler));
        service = new ScheduledTaskExecutionService(execRepo, taskRepo, registry);

        ScheduledTask task = taskRepo.insert(ScheduledTask.createDailyWeather(
                "target-1", "杭州", LocalTime.of(8, 0), ZoneId.of("Asia/Shanghai"),
                Instant.now().plusSeconds(3600), Instant.now()));
        TaskExecution exec = execRepo.insertUnique(
                task.taskId(), task.nextRunAt(), Instant.now()).orElseThrow();

        service.execute(exec.executionId());

        assertNotNull(lastPushedText.get(), "should fallback to template");
    }

    @Test
    void executeShouldFallbackWhenAgentReturnsEmpty() {
        ScheduledContentAgent emptyAgent = (task, report) -> "";
        WeatherMessageTemplateFormatter formatter = new WeatherMessageTemplateFormatter();

        WeatherScheduledTaskHandler weatherHandler = new WeatherScheduledTaskHandler(
                mockWeatherService, emptyAgent, formatter,
                req -> { lastPushedText.set(req.text()); return PushResult.ok(); });

        ScheduledTaskHandlerRegistry registry = new ScheduledTaskHandlerRegistry(
                List.of(weatherHandler));
        service = new ScheduledTaskExecutionService(execRepo, taskRepo, registry);

        ScheduledTask task = taskRepo.insert(ScheduledTask.createDailyWeather(
                "target-1", "杭州", LocalTime.of(8, 0), ZoneId.of("Asia/Shanghai"),
                Instant.now().plusSeconds(3600), Instant.now()));
        TaskExecution exec = execRepo.insertUnique(
                task.taskId(), task.nextRunAt(), Instant.now()).orElseThrow();

        service.execute(exec.executionId());

        assertNotNull(lastPushedText.get(), "template fallback should produce text");
    }

    @Test
    void executeShouldNotPushOnWeatherFailure() {
        WeatherService failingWeather = mock(WeatherService.class);
        when(failingWeather.query(any()))
                .thenThrow(new WeatherException(WeatherError.PROVIDER_UNAVAILABLE, "down"));

        WeatherScheduledTaskHandler weatherHandler = new WeatherScheduledTaskHandler(
                failingWeather, (t, r) -> "should not reach",
                new WeatherMessageTemplateFormatter(),
                req -> { lastPushedText.set(req.text()); return PushResult.ok(); });

        ScheduledTaskHandlerRegistry registry = new ScheduledTaskHandlerRegistry(
                List.of(weatherHandler));
        service = new ScheduledTaskExecutionService(execRepo, taskRepo, registry);

        ScheduledTask task = taskRepo.insert(ScheduledTask.createDailyWeather(
                "target-1", "杭州", LocalTime.of(8, 0), ZoneId.of("Asia/Shanghai"),
                Instant.now().plusSeconds(3600), Instant.now()));
        TaskExecution exec = execRepo.insertUnique(
                task.taskId(), task.nextRunAt(), Instant.now()).orElseThrow();

        service.execute(exec.executionId());

        assertNull(lastPushedText.get(), "must NOT push when weather fails");
    }
}
