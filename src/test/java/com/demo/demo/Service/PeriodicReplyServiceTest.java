package com.demo.demo.Service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeriodicReplyServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-28T00:30:00Z");

    @TempDir
    Path tempDir;

    @Test
    void calculatesIntervalDailyAndWeeklyRuns() {
        PeriodicReplyService service = service(tempDir.resolve("tasks.json"));

        assertEquals(Instant.parse("2026-07-28T02:30:00Z"),
                service.nextRun("INTERVAL", "PT2H", NOW));
        assertEquals(Instant.parse("2026-07-29T00:00:00Z"),
                service.nextRun("DAILY", "08:00", NOW));
        assertEquals(Instant.parse("2026-08-03T01:00:00Z"),
                service.nextRun("WEEKLY", "MONDAY@09:00", NOW));
    }

    @Test
    void rejectsUnsupportedOrNonFutureRules() {
        PeriodicReplyService service = service(tempDir.resolve("tasks.json"));

        assertThrows(IllegalArgumentException.class,
                () -> service.nextRun("MONTHLY", "1@08:00", NOW));
        assertThrows(IllegalArgumentException.class,
                () -> service.nextRun("INTERVAL", "PT0S", NOW));
    }

    @Test
    void createsPersistsReloadsListsAndCancelsTasks() throws Exception {
        Path file = tempDir.resolve("periodic-replies.json");
        PeriodicReplyService first = service(file);

        PeriodicReplyService.PeriodicTask created = first.create(
                "user-a", "secret-token", "DAILY", "08:00",
                "FIXED", "提醒吃药");

        assertEquals(1, created.id());
        assertEquals(1, first.list("user-a").size());
        assertFalse(Files.readString(file).isBlank());

        PeriodicReplyService restarted = service(file);
        assertEquals(created, restarted.list("user-a").getFirst());
        assertTrue(restarted.cancel("user-a", null).contains("已取消任务 1"));
        assertTrue(restarted.list("user-a").isEmpty());
    }

    @Test
    void requiresIdWhenUserHasMultipleTasks() {
        PeriodicReplyService service = service(tempDir.resolve("tasks.json"));
        service.create("u", "t", "DAILY", "08:00", "FIXED", "a");
        service.create("u", "t", "DAILY", "09:00", "FIXED", "b");

        assertTrue(service.cancel("u", null).contains("请指定任务编号"));
        assertEquals(2, service.list("u").size());
    }

    @Test
    void enforcesTwentyTaskLimitPerUser() {
        PeriodicReplyService service = service(tempDir.resolve("tasks.json"));
        for (int i = 0; i < 20; i++) {
            service.create("u", "t", "DAILY", "08:00", "FIXED", "x" + i);
        }

        assertThrows(IllegalStateException.class,
                () -> service.create(
                        "u", "t", "DAILY", "09:00", "FIXED", "overflow"));
    }

    @Test
    void toolsUseTrustedContextAndSummaryHidesToken() throws Exception {
        PeriodicReplyService service = service(tempDir.resolve("tasks.json"));

        String result = service.createPeriodicReply(
                "DAILY", "08:00", "FIXED", "提醒吃药",
                toolContext("user-a", "secret-token"));

        assertTrue(result.contains("任务 1"));
        assertTrue(service.activeTaskSummary("user-a").contains("每天 08:00"));
        assertFalse(service.activeTaskSummary("user-a").contains("secret-token"));
        assertNotNull(PeriodicReplyService.class
                .getMethod("createPeriodicReply", String.class, String.class,
                        String.class, String.class, ToolContext.class)
                .getAnnotation(Tool.class));
        assertNotNull(PeriodicReplyService.class
                .getMethod("listPeriodicReplies", ToolContext.class)
                .getAnnotation(Tool.class));
        assertNotNull(PeriodicReplyService.class
                .getMethod("cancelPeriodicReply", Integer.class, ToolContext.class)
                .getAnnotation(Tool.class));
    }

    @Test
    void intervalSummaryPreservesMinutes() {
        PeriodicReplyService service = service(tempDir.resolve("tasks.json"));
        service.create(
                "user-a", "token", "INTERVAL", "PT30M",
                "FIXED", "休息一下");

        assertTrue(service.activeTaskSummary("user-a").contains("每隔 30 分钟"));
    }

    @Test
    void aiServiceKeepsLegacyAndContextAwareChatOverloads() throws Exception {
        assertNotNull(AIService.class.getMethod(
                "chat", String.class, String.class));
        assertNotNull(AIService.class.getMethod(
                "chat", String.class, String.class, String.class));
    }

    @Test
    void triggersFixedAndDynamicTasksAndAdvancesBeforeSending() {
        MutableClock clock = new MutableClock(NOW);
        PeriodicReplyService service = service(tempDir.resolve("tasks.json"), clock);
        List<String> sent = new ArrayList<>();
        service.configure(
                () -> true,
                task -> "动态结果",
                (userId, contextToken, text) -> sent.add(userId + ":" + text));

        service.create("u", "t", "INTERVAL", "PT1H", "FIXED", "固定内容");
        service.create("u", "t", "INTERVAL", "PT1H", "AGENT", "生成内容");
        clock.advance(Duration.ofHours(1));
        service.scanDueTasks();

        assertEquals(List.of("u:固定内容", "u:动态结果"), sent);
        assertTrue(service.list("u").stream()
                .allMatch(task -> task.nextRunAt().isAfter(clock.instant())));
    }

    @Test
    void offlineBotLeavesTaskDueForRetry() {
        MutableClock clock = new MutableClock(NOW);
        PeriodicReplyService service = service(tempDir.resolve("tasks.json"), clock);
        List<String> sent = new ArrayList<>();
        service.configure(
                () -> false,
                task -> "不应生成",
                (userId, contextToken, text) -> sent.add(text));
        PeriodicReplyService.PeriodicTask created = service.create(
                "u", "t", "INTERVAL", "PT1H", "FIXED", "content");
        clock.advance(Duration.ofHours(1));

        service.scanDueTasks();

        assertTrue(sent.isEmpty());
        assertEquals(created.nextRunAt(), service.list("u").getFirst().nextRunAt());
    }

    @Test
    void overdueTaskFiresOnceAndAdvancesToFuture() {
        MutableClock clock = new MutableClock(NOW);
        PeriodicReplyService service = service(tempDir.resolve("tasks.json"), clock);
        List<String> sent = new ArrayList<>();
        service.configure(
                () -> true,
                task -> "unused",
                (userId, contextToken, text) -> sent.add(text));
        service.create("u", "t", "INTERVAL", "PT1H", "FIXED", "一次");
        clock.advance(Duration.ofHours(5));

        service.scanDueTasks();

        assertEquals(List.of("一次"), sent);
        assertTrue(service.list("u").getFirst().nextRunAt().isAfter(clock.instant()));
    }

    @Test
    void concurrentCreatesKeepDistinctIdsAndValidJson() throws Exception {
        Path file = tempDir.resolve("tasks.json");
        PeriodicReplyService service = service(file);
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<PeriodicReplyService.PeriodicTask>> calls =
                    IntStream.range(0, 20)
                            .mapToObj(index -> (Callable<PeriodicReplyService.PeriodicTask>)
                                    () -> service.create(
                                            "u", "t", "DAILY", "08:00",
                                            "FIXED", "任务" + index))
                            .toList();
            var futures = executor.invokeAll(calls);
            Set<Integer> ids = futures.stream()
                    .map(future -> {
                        try {
                            return future.get().id();
                        } catch (Exception e) {
                            throw new AssertionError(e);
                        }
                    })
                    .collect(Collectors.toSet());

            assertEquals(20, ids.size());
            assertEquals(20, service(file).list("u").size());
            assertTrue(Files.readString(file).contains("\"version\""));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void dynamicGenerationFailureFallsBackWithoutRemovingTask() {
        MutableClock clock = new MutableClock(NOW);
        PeriodicReplyService service = service(tempDir.resolve("tasks.json"), clock);
        List<String> sent = new ArrayList<>();
        service.configure(
                () -> true,
                task -> {
                    throw new IllegalStateException("provider unavailable");
                },
                (userId, contextToken, text) -> sent.add(text));
        service.create("u", "t", "INTERVAL", "PT1H", "AGENT", "生成摘要");
        clock.advance(Duration.ofHours(1));

        service.scanDueTasks();

        assertEquals(List.of("本次周期任务生成失败，请稍后重试"), sent);
        assertEquals(1, service.list("u").size());
    }

    @Test
    void executionFailureLogsNeitherTokenNorTaskContent() {
        Logger logger = (Logger) LoggerFactory.getLogger(PeriodicReplyService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            MutableClock clock = new MutableClock(NOW);
            PeriodicReplyService service = service(
                    tempDir.resolve("tasks.json"), clock);
            service.configure(
                    () -> true,
                    task -> "unused",
                    (userId, contextToken, text) -> {
                        throw new IllegalStateException("send failed");
                    });
            service.create(
                    "user-secret", "secret-token", "INTERVAL", "PT1H",
                    "FIXED", "unique-secret-content");
            clock.advance(Duration.ofHours(1));

            service.scanDueTasks();

            String logs = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));
            assertFalse(logs.contains("secret-token"));
            assertFalse(logs.contains("unique-secret-content"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void failedPersistenceDoesNotChangeInMemoryTasks() throws Exception {
        Path nonDirectory = tempDir.resolve("not-a-directory");
        Files.writeString(nonDirectory, "x");
        PeriodicReplyService service = service(nonDirectory.resolve("tasks.json"));

        assertThrows(IllegalStateException.class,
                () -> service.create(
                        "u", "t", "DAILY", "08:00", "FIXED", "内容"));
        assertTrue(service.list("u").isEmpty());
    }

    private PeriodicReplyService service(Path file) {
        return service(file, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private PeriodicReplyService service(Path file, Clock clock) {
        return new PeriodicReplyService(
                file,
                new ObjectMapper().findAndRegisterModules(),
                clock);
    }

    private ToolContext toolContext(String userId, String contextToken) {
        return new ToolContext(Map.of(
                "user_id", userId,
                "context_token", contextToken));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
