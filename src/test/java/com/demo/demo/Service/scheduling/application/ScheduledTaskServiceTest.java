package com.demo.demo.Service.scheduling.application;

import com.demo.demo.Service.scheduling.domain.*;
import com.demo.demo.Service.scheduling.persistence.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScheduledTaskServiceTest {

    private ScheduledTaskService service;
    private ScheduledTaskRepository taskRepo;
    private Path tempFile;

    @BeforeEach
    void setUp() throws Exception {
        tempFile = Files.createTempFile("scheduled-task-svc-", ".sqlite");
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempFile.toAbsolutePath());
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        new SchedulingSchemaInitializer(jdbc).init();
        taskRepo = new JdbcScheduledTaskRepository(jdbc);
        service = new ScheduledTaskService(taskRepo);
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(tempFile);
    }

    // ==================== create ====================

    @Test
    void createDailyWeatherTaskShouldReturnTaskKey() {
        String key = service.createDailyWeatherTask(
                new CreateDailyWeatherTaskCommand("target-1", "杭州",
                        LocalTime.of(8, 0), ZoneId.of("Asia/Shanghai")));

        assertNotNull(key);
        assertFalse(key.isBlank());
    }

    @Test
    void createShouldRejectDuplicate() {
        var cmd = new CreateDailyWeatherTaskCommand("target-1", "杭州",
                LocalTime.of(8, 0), ZoneId.of("Asia/Shanghai"));
        service.createDailyWeatherTask(cmd);

        assertThrows(SchedulingException.class, () ->
                service.createDailyWeatherTask(cmd));
    }

    @Test
    void createShouldAllowDifferentCity() {
        service.createDailyWeatherTask(new CreateDailyWeatherTaskCommand(
                "target-1", "杭州", LocalTime.of(8, 0), ZoneId.of("Asia/Shanghai")));
        // Different city should work
        String key = service.createDailyWeatherTask(new CreateDailyWeatherTaskCommand(
                "target-1", "上海", LocalTime.of(8, 0), ZoneId.of("Asia/Shanghai")));
        assertNotNull(key);
    }

    @Test
    void createShouldAllowDifferentTime() {
        service.createDailyWeatherTask(new CreateDailyWeatherTaskCommand(
                "target-1", "杭州", LocalTime.of(8, 0), ZoneId.of("Asia/Shanghai")));
        String key = service.createDailyWeatherTask(new CreateDailyWeatherTaskCommand(
                "target-1", "杭州", LocalTime.of(20, 0), ZoneId.of("Asia/Shanghai")));
        assertNotNull(key);
    }

    @Test
    void createShouldAllowDifferentOwner() {
        service.createDailyWeatherTask(new CreateDailyWeatherTaskCommand(
                "target-a", "杭州", LocalTime.of(8, 0), ZoneId.of("Asia/Shanghai")));
        String key = service.createDailyWeatherTask(new CreateDailyWeatherTaskCommand(
                "target-b", "杭州", LocalTime.of(8, 0), ZoneId.of("Asia/Shanghai")));
        assertNotNull(key, "different owners should be independent");
    }

    @Test
    void createShouldSetCorrectNextRunAt() {
        String key = service.createDailyWeatherTask(
                new CreateDailyWeatherTaskCommand("target-1", "北京",
                        LocalTime.of(8, 0), ZoneId.of("Asia/Shanghai")));

        ScheduledTask task = taskRepo.findByTaskId(key).orElseThrow();
        assertTrue(task.nextRunAt().isAfter(Instant.now().minusSeconds(60)),
                "nextRunAt should be in the future");
    }

    // ==================== listTasks ====================

    @Test
    void listTasksShouldReturnOwnedTasks() {
        service.createDailyWeatherTask(new CreateDailyWeatherTaskCommand(
                "target-1", "杭州", LocalTime.of(8, 0), ZoneId.of("Asia/Shanghai")));
        service.createDailyWeatherTask(new CreateDailyWeatherTaskCommand(
                "target-1", "上海", LocalTime.of(8, 0), ZoneId.of("Asia/Shanghai")));
        service.createDailyWeatherTask(new CreateDailyWeatherTaskCommand(
                "target-2", "北京", LocalTime.of(8, 0), ZoneId.of("Asia/Shanghai")));

        List<ScheduledTaskSummary> t1 = service.listTasks("target-1");
        assertEquals(2, t1.size());
        assertEquals(1, service.listTasks("target-2").size());
    }

    @Test
    void listTasksShouldNotExposeInternalIds() {
        service.createDailyWeatherTask(new CreateDailyWeatherTaskCommand(
                "target-1", "杭州", LocalTime.of(8, 0), ZoneId.of("Asia/Shanghai")));

        ScheduledTaskSummary summary = service.listTasks("target-1").get(0);
        // ScheduledTaskSummary has no ownerTargetId, version, or internal id
        assertNotNull(summary.taskId());
        assertNotNull(summary.location());
    }

    // ==================== pause ====================

    @Test
    void pauseShouldChangeStatusToPaused() {
        String key = service.createDailyWeatherTask(new CreateDailyWeatherTaskCommand(
                "target-1", "杭州", LocalTime.of(8, 0), ZoneId.of("Asia/Shanghai")));
        service.pause("target-1", key);

        ScheduledTask task = taskRepo.findByTaskId(key).orElseThrow();
        assertEquals(ScheduledTaskStatus.PAUSED, task.status());
    }

    @Test
    void pauseShouldFailForWrongOwner() {
        String key = service.createDailyWeatherTask(new CreateDailyWeatherTaskCommand(
                "target-1", "杭州", LocalTime.of(8, 0), ZoneId.of("Asia/Shanghai")));

        assertThrows(SchedulingException.class, () ->
                service.pause("target-other", key));
    }

    // ==================== resume ====================

    @Test
    void resumeShouldChangeStatusBackToActive() {
        String key = service.createDailyWeatherTask(new CreateDailyWeatherTaskCommand(
                "target-1", "杭州", LocalTime.of(8, 0), ZoneId.of("Asia/Shanghai")));
        service.pause("target-1", key);
        service.resume("target-1", key);

        ScheduledTask task = taskRepo.findByTaskId(key).orElseThrow();
        assertEquals(ScheduledTaskStatus.ACTIVE, task.status());
    }

    @Test
    void resumeShouldRecalculateNextRunAt() {
        String key = service.createDailyWeatherTask(new CreateDailyWeatherTaskCommand(
                "target-1", "杭州", LocalTime.of(8, 0), ZoneId.of("Asia/Shanghai")));

        service.pause("target-1", key);
        service.resume("target-1", key);

        ScheduledTask resumed = taskRepo.findByTaskId(key).orElseThrow();
        assertEquals(ScheduledTaskStatus.ACTIVE, resumed.status());
        // nextRunAt must be in the future after resume
        assertTrue(resumed.nextRunAt().isAfter(Instant.now().minusSeconds(10)),
                "resume should set nextRunAt in the future");
    }

    // ==================== cancel ====================

    @Test
    void cancelShouldChangeStatusToCanceled() {
        String key = service.createDailyWeatherTask(new CreateDailyWeatherTaskCommand(
                "target-1", "杭州", LocalTime.of(8, 0), ZoneId.of("Asia/Shanghai")));
        service.cancel("target-1", key);

        ScheduledTask task = taskRepo.findByTaskId(key).orElseThrow();
        assertEquals(ScheduledTaskStatus.CANCELED, task.status());
    }

    @Test
    void cancelShouldFailForWrongOwner() {
        String key = service.createDailyWeatherTask(new CreateDailyWeatherTaskCommand(
                "target-1", "杭州", LocalTime.of(8, 0), ZoneId.of("Asia/Shanghai")));

        assertThrows(SchedulingException.class, () ->
                service.cancel("target-other", key));
    }

    @Test
    void cancelShouldNotAllowDuplicateCancellation() {
        String key = service.createDailyWeatherTask(new CreateDailyWeatherTaskCommand(
                "target-1", "杭州", LocalTime.of(8, 0), ZoneId.of("Asia/Shanghai")));
        service.cancel("target-1", key);

        assertThrows(IllegalStateException.class, () ->
                service.cancel("target-1", key));
    }
}
