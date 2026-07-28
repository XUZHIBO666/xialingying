package com.demo.demo.integration;

import com.demo.demo.Service.scheduling.application.*;
import com.demo.demo.Service.scheduling.domain.*;
import com.demo.demo.Service.scheduling.execution.*;
import com.demo.demo.Service.scheduling.execution.*;
import com.demo.demo.Service.scheduling.persistence.*;
import com.demo.demo.Service.scheduling.runtime.*;
import com.demo.demo.Service.scheduling.security.ContextTokenCipher;
import com.demo.demo.Service.weather.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test: full push flow from task creation
 * through scanning, weather/content generation, to iLink push.
 * Uses temp SQLite, fixed Clock, and mocked external services.
 */
class ScheduledWeatherPushIntegrationTest {

    private Path tempFile;
    private JdbcTemplate jdbc;
    private DeliveryTargetService targetService;
    private ScheduledTaskService taskService;
    private ScheduledTaskScanner scanner;
    private ScheduledTaskRepository taskRepo;
    private TaskExecutionRepository execRepo;
    private AtomicReference<String> lastPushedText;
    private AtomicReference<String> lastPushedTarget;
    private Clock fixedClock;
    private ScheduledTaskExecutionService executionService;

    @BeforeEach
    void setUp() throws Exception {
        tempFile = Files.createTempFile("integration-", ".sqlite");
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempFile.toAbsolutePath());
        jdbc = new JdbcTemplate(ds);
        new SchedulingSchemaInitializer(jdbc).init();

        fixedClock = Clock.fixed(Instant.parse("2026-07-28T04:00:00Z"), ZoneOffset.UTC);
        Instant now = fixedClock.instant();

        // Repos
        taskRepo = new JdbcScheduledTaskRepository(jdbc);
        execRepo = new JdbcTaskExecutionRepository(jdbc);
        var targetRepo = new JdbcDeliveryTargetRepository(jdbc);

        // Cipher + target service
        ContextTokenCipher cipher = new ContextTokenCipher("");
        targetService = new DeliveryTargetService(targetRepo, cipher);

        // Task service
        taskService = new ScheduledTaskService(taskRepo);

        // Push gateway — capture pushed text/target
        lastPushedText = new AtomicReference<>();
        lastPushedTarget = new AtomicReference<>();
        MessagePushGateway gateway = req -> {
            lastPushedTarget.set(req.targetId());
            lastPushedText.set(req.text());
            return PushResult.ok();
        };

        // Content agent — simple text generation
        ScheduledContentAgent contentAgent = (task, report) ->
                report.location().name() + "今天天气：" +
                        (report.current() != null ?
                                String.format("%.0f°C", report.current().temperatureCelsius()) : "N/A");

        WeatherMessageTemplateFormatter formatter = new WeatherMessageTemplateFormatter();

        // Mock weather service with stub report
        WeatherService weatherSvc = new WeatherService(
                new WeatherProvider() {
                    @Override public WeatherLocation resolveLocation(String loc) {
                        return new WeatherLocation(loc, loc, null, null, 30, 120, ZoneId.of("Asia/Shanghai"));
                    }
                    @Override public WeatherSnapshot fetch(WeatherLocation loc) {
                        return new WeatherSnapshot(loc, now,
                                new CurrentConditions(22.5, 21, 65, 12, 180, 1),
                                List.of(new DailyForecast(LocalDate.now(ZoneOffset.UTC).plusDays(1), 25, 15, 1, 0)),
                                "test");
                    }
                }, fixedClock, new WeatherProperties());

        // Execution service
        WeatherScheduledTaskHandler weatherHandler = new WeatherScheduledTaskHandler(
                weatherSvc, contentAgent, formatter, gateway);
        ScheduledTaskHandlerRegistry registry = new ScheduledTaskHandlerRegistry(
                List.of(weatherHandler));
        executionService = new ScheduledTaskExecutionService(execRepo, taskRepo, registry);

        // Scanner
        ExecutorService worker = Executors.newSingleThreadExecutor();
        scanner = new ScheduledTaskScanner(taskRepo, execRepo, executionService, worker, fixedClock);
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(tempFile);
    }

    // ==================== full push flow ====================

    @Test
    void shouldCreateTaskScanAndPush() throws Exception {
        // 1. Register delivery target
        String targetId = targetService.refresh(
                new DeliveryTargetRefreshCommand("user-1", "token-1", fixedClock.instant()));

        // 2. Create task with nextRunAt in the PAST (so scanner picks it up)
        Instant past = fixedClock.instant().minusSeconds(60);
        ScheduledTask task = taskRepo.insert(ScheduledTask.createDailyWeather(
                targetId, "杭州", LocalTime.of(8, 0), ZoneId.of("Asia/Shanghai"),
                past, fixedClock.instant().minusSeconds(3600)));

        // 3. Scan
        scanner.scan();
        Thread.sleep(200);

        // 4. Verify push happened
        assertNotNull(lastPushedText.get(), "should have pushed a message");
        assertTrue(lastPushedText.get().contains("杭州"));
        assertEquals(targetId, lastPushedTarget.get());

        // 5. Verify nextRunAt advanced
        ScheduledTask reloaded = taskRepo.findByTaskId(task.taskId()).orElseThrow();
        assertTrue(reloaded.nextRunAt().isAfter(past), "nextRunAt should advance");
    }

    // ==================== lifecycle ====================

    @Test
    void shouldSupportPauseResumeCancelLifecycle() {
        String targetId = targetService.refresh(
                new DeliveryTargetRefreshCommand("user-1", "t", fixedClock.instant()));

        // Create
        String key = taskService.createDailyWeatherTask(new CreateDailyWeatherTaskCommand(
                targetId, "上海", LocalTime.of(8, 0), ZoneId.of("Asia/Shanghai")));
        assertEquals(1, taskService.listTasks(targetId).size());

        // Pause
        taskService.pause(targetId, key);
        ScheduledTaskSummary s = taskService.listTasks(targetId).get(0);
        assertEquals(ScheduledTaskStatus.PAUSED, s.status());

        // Resume
        taskService.resume(targetId, key);
        s = taskService.listTasks(targetId).get(0);
        assertEquals(ScheduledTaskStatus.ACTIVE, s.status());

        // Cancel
        taskService.cancel(targetId, key);
        s = taskService.listTasks(targetId).get(0);
        assertEquals(ScheduledTaskStatus.CANCELED, s.status());
    }

    // ==================== isolation ====================

    @Test
    void shouldIsolateUsers() {
        String t1 = targetService.refresh(
                new DeliveryTargetRefreshCommand("user-a", "t", fixedClock.instant()));
        String t2 = targetService.refresh(
                new DeliveryTargetRefreshCommand("user-b", "t", fixedClock.instant()));

        var cmd = new CreateDailyWeatherTaskCommand(t1, "杭州", LocalTime.of(8, 0), ZoneId.of("Asia/Shanghai"));
        String key = taskService.createDailyWeatherTask(cmd);

        // user-b cannot pause user-a's task
        assertThrows(SchedulingException.class, () -> taskService.pause(t2, key));
        // user-a can still access their own task
        assertEquals(1, taskService.listTasks(t1).size());
        assertEquals(0, taskService.listTasks(t2).size());
    }

    // ==================== dedup ====================

    @Test
    void duplicateScanShouldNotCauseDoublePush() throws Exception {
        String targetId = targetService.refresh(
                new DeliveryTargetRefreshCommand("user-1", "t", fixedClock.instant()));

        Instant past = fixedClock.instant().minusSeconds(60);
        taskRepo.insert(ScheduledTask.createDailyWeather(
                targetId, "杭州", LocalTime.of(8, 0), ZoneId.of("Asia/Shanghai"),
                past, fixedClock.instant().minusSeconds(3600)));

        scanner.scan();
        scanner.scan(); // second scan — should not create duplicate execution
        Thread.sleep(200);

        // Only one execution record per (taskId, scheduledFor)
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM scheduled_task_execution", Long.class);
        assertEquals(1L, count, "duplicate scan must not create duplicate execution");
    }

    // ==================== template fallback ====================

    @Test
    void shouldFallbackToTemplateWhenAgentReturnsEmpty() throws Exception {
        String targetId = targetService.refresh(
                new DeliveryTargetRefreshCommand("user-1", "t", fixedClock.instant()));

        Instant past = fixedClock.instant().minusSeconds(60);
        ScheduledTask task = taskRepo.insert(ScheduledTask.createDailyWeather(
                targetId, "杭州", LocalTime.of(8, 0), ZoneId.of("Asia/Shanghai"),
                past, fixedClock.instant().minusSeconds(3600)));

        // Create execution service with empty agent
        ScheduledContentAgent emptyAgent = (t, r) -> "";
        WeatherMessageTemplateFormatter formatter = new WeatherMessageTemplateFormatter();
        WeatherService weatherSvc2 = new WeatherService(new WeatherProvider() {
                    @Override public WeatherLocation resolveLocation(String loc) {
                        return new WeatherLocation(loc, loc, null, null, 30, 120, ZoneId.of("Asia/Shanghai"));
                    }
                    @Override public WeatherSnapshot fetch(WeatherLocation loc) {
                        return new WeatherSnapshot(loc, fixedClock.instant(),
                                new CurrentConditions(20, 19, 50, 5, 90, 0),
                                List.of(), "test");
                    }
                }, fixedClock, new WeatherProperties());

        WeatherScheduledTaskHandler wh2 = new WeatherScheduledTaskHandler(
                weatherSvc2, emptyAgent, formatter,
                req -> { lastPushedText.set(req.text()); return PushResult.ok(); });
        ScheduledTaskHandlerRegistry reg2 = new ScheduledTaskHandlerRegistry(List.of(wh2));
        ScheduledTaskExecutionService svc = new ScheduledTaskExecutionService(execRepo, taskRepo, reg2);

        ExecutorService worker = Executors.newSingleThreadExecutor();
        ScheduledTaskScanner s = new ScheduledTaskScanner(taskRepo, execRepo, svc, worker, fixedClock);
        s.scan();
        Thread.sleep(200);

        assertNotNull(lastPushedText.get());
        assertTrue(lastPushedText.get().contains("自动推送"), "should use template fallback");
    }

    // ==================== delivery target refresh ====================

    @Test
    void refreshShouldReuseTargetIdAndUpdateToken() {
        String t1 = targetService.refresh(
                new DeliveryTargetRefreshCommand("user-1", "token-v1", fixedClock.instant()));
        String t2 = targetService.refresh(
                new DeliveryTargetRefreshCommand("user-1", "token-v2",
                        fixedClock.instant().plusSeconds(60)));

        assertEquals(t1, t2, "same user should reuse target ID");

        // Resolved token should be the latest
        DeliveryTargetResolved r = targetService.resolve(t2);
        assertEquals("token-v2", r.contextToken());
    }
}
