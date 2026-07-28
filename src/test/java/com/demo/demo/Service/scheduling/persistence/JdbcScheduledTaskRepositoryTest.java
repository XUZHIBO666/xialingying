package com.demo.demo.Service.scheduling.persistence;

import com.demo.demo.Service.scheduling.domain.*;

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

class JdbcScheduledTaskRepositoryTest {

    private JdbcScheduledTaskRepository repo;
    private Path tempFile;
    private Instant now;

    @BeforeEach
    void setUp() throws Exception {
        tempFile = Files.createTempFile("scheduled-task-test-", ".sqlite");
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempFile.toAbsolutePath());
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        new SchedulingSchemaInitializer(jdbc).init();
        repo = new JdbcScheduledTaskRepository(jdbc);
        now = Instant.now();
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(tempFile);
    }

    // ==================== insert ====================

    @Test
    void insertShouldPersistAndReturnWithId() {
        ScheduledTask task = newDailyWeather("target-1", "杭州", now.plusSeconds(86400));
        ScheduledTask saved = repo.insert(task);

        assertNotNull(saved.id(), "should have generated ID");
        assertEquals(task.taskId(), saved.taskId());
        assertEquals("杭州", saved.location());
        assertEquals("08:00", saved.localTime());
        assertEquals("Asia/Shanghai", saved.timeZone());
        assertEquals(ScheduledTaskStatus.ACTIVE, saved.status());
    }

    // ==================== findByOwner ====================

    @Test
    void findByOwnerShouldReturnOnlyOwnedTasks() {
        repo.insert(newDailyWeather("target-a", "杭州", now.plusSeconds(86400)));
        repo.insert(newDailyWeather("target-a", "上海", now.plusSeconds(86400)));
        repo.insert(newDailyWeather("target-b", "北京", now.plusSeconds(86400)));

        List<ScheduledTask> tasksA = repo.findByOwner("target-a");
        assertEquals(2, tasksA.size());

        List<ScheduledTask> tasksB = repo.findByOwner("target-b");
        assertEquals(1, tasksB.size());
        assertEquals("北京", tasksB.get(0).location());
    }

    @Test
    void findByOwnerShouldReturnEmptyForUnknownOwner() {
        List<ScheduledTask> tasks = repo.findByOwner("nonexistent");
        assertTrue(tasks.isEmpty());
    }

    // ==================== updateStatusOwned ====================

    @Test
    void updateStatusOwnedShouldSucceedForCorrectOwner() {
        ScheduledTask task = repo.insert(newDailyWeather("target-1", "杭州", now.plusSeconds(86400)));

        boolean updated = repo.updateStatusOwned(
                task.taskId(), "target-1", ScheduledTaskStatus.PAUSED, 0, now.plusSeconds(100));

        assertTrue(updated, "should update with correct owner and version");
        ScheduledTask reloaded = repo.findByTaskId(task.taskId()).orElseThrow();
        assertEquals(ScheduledTaskStatus.PAUSED, reloaded.status());
        assertEquals(1, reloaded.version(), "version should increment");
    }

    @Test
    void updateStatusOwnedShouldFailForWrongOwner() {
        ScheduledTask task = repo.insert(newDailyWeather("target-1", "杭州", now.plusSeconds(86400)));

        boolean updated = repo.updateStatusOwned(
                task.taskId(), "target-other", ScheduledTaskStatus.PAUSED, 0, now);

        assertFalse(updated, "must reject update from non-owner");

        // Verify task untouched
        ScheduledTask reloaded = repo.findByTaskId(task.taskId()).orElseThrow();
        assertEquals(ScheduledTaskStatus.ACTIVE, reloaded.status());
        assertEquals(0, reloaded.version());
    }

    @Test
    void updateStatusOwnedShouldFailForWrongVersion() {
        ScheduledTask task = repo.insert(newDailyWeather("target-1", "杭州", now.plusSeconds(86400)));

        boolean updated = repo.updateStatusOwned(
                task.taskId(), "target-1", ScheduledTaskStatus.PAUSED, 99, now);

        assertFalse(updated, "must reject update with stale version");
    }

    // ==================== findDue ====================

    @Test
    void findDueShouldReturnOnlyPastAndActive() {
        Instant past = now.minusSeconds(60);
        Instant future = now.plusSeconds(86400);

        // Due task (nextRunAt in the past, ACTIVE)
        repo.insert(newDailyWeather("target-1", "杭州", past));

        // Future task (not due yet)
        repo.insert(newDailyWeather("target-1", "上海", future));

        List<ScheduledTask> due = repo.findDue(now, 10);
        assertEquals(1, due.size(), "only past task should be due");
        assertEquals("杭州", due.get(0).location());
    }

    @Test
    void findDueShouldExcludeNonActiveTasks() {
        Instant past = now.minusSeconds(60);
        ScheduledTask paused = repo.insert(newDailyWeather("target-1", "杭州", past));
        repo.updateStatusOwned(paused.taskId(), "target-1", ScheduledTaskStatus.PAUSED, 0, now);

        List<ScheduledTask> due = repo.findDue(now, 10);
        assertTrue(due.isEmpty(), "PAUSED tasks should not appear as due");
    }

    @Test
    void findDueShouldRespectBatchSize() {
        Instant past = now.minusSeconds(60);
        for (int i = 0; i < 5; i++) {
            repo.insert(newDailyWeather("target-1", "city-" + i, past));
        }

        List<ScheduledTask> due = repo.findDue(now, 3);
        assertEquals(3, due.size(), "should respect batch size limit");
    }

    // ==================== advanceNextRun ====================

    @Test
    void advanceNextRunShouldUpdateTimeAndVersion() {
        ScheduledTask task = repo.insert(newDailyWeather("target-1", "杭州", now.plusSeconds(86400)));

        Instant newNext = now.plusSeconds(172800);
        boolean updated = repo.advanceNextRun(task.taskId(), 0, newNext, now);

        assertTrue(updated);
        ScheduledTask reloaded = repo.findByTaskId(task.taskId()).orElseThrow();
        assertEquals(newNext.getEpochSecond(), reloaded.nextRunAt().getEpochSecond());
        assertEquals(1, reloaded.version());
    }

    // ==================== helper ====================

    private ScheduledTask newDailyWeather(String targetId, String location, Instant nextRunAt) {
        return ScheduledTask.createDailyWeather(
                targetId, location, LocalTime.of(8, 0), ZoneId.of("Asia/Shanghai"), nextRunAt, now);
    }
}
