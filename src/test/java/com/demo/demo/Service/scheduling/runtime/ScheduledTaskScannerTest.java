package com.demo.demo.Service.scheduling.runtime;

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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ScheduledTaskScannerTest {

    private ScheduledTaskScanner scanner;
    private ScheduledTaskRepository taskRepo;
    private TaskExecutionRepository execRepo;
    private AtomicInteger executionCount;
    private Path tempFile;
    private Clock fixedClock;

    @BeforeEach
    void setUp() throws Exception {
        tempFile = Files.createTempFile("scanner-test-", ".sqlite");
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempFile.toAbsolutePath());
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        new SchedulingSchemaInitializer(jdbc).init();

        taskRepo = new JdbcScheduledTaskRepository(jdbc);
        execRepo = new JdbcTaskExecutionRepository(jdbc);
        executionCount = new AtomicInteger(0);

        fixedClock = Clock.fixed(Instant.parse("2026-07-28T04:00:00Z"), ZoneOffset.UTC);

        ExecutorService workerPool = new ThreadPoolExecutor(
                1, 1, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(10),
                new ThreadPoolExecutor.CallerRunsPolicy());

        scanner = new ScheduledTaskScanner(
                taskRepo, execRepo,
                executionId -> executionCount.incrementAndGet(),
                workerPool, fixedClock);
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(tempFile);
    }

    // ==================== basic scanning ====================

    @Test
    void scanShouldExecuteDueTask() throws Exception {
        // Create task whose nextRunAt is in the past
        Instant past = fixedClock.instant().minusSeconds(60);
        insertDueTask("target-1", "杭州", past);

        scanner.scan();

        // Wait for async worker
        Thread.sleep(200);
        assertEquals(1, executionCount.get(), "due task should be executed");
    }

    @Test
    void scanShouldNotExecuteFutureTask() throws Exception {
        Instant future = fixedClock.instant().plusSeconds(3600);
        insertDueTask("target-1", "杭州", future);

        scanner.scan();

        Thread.sleep(100);
        assertEquals(0, executionCount.get(), "future task must not be executed");
    }

    @Test
    void scanShouldNotExecutePausedTask() throws Exception {
        Instant past = fixedClock.instant().minusSeconds(60);
        ScheduledTask task = insertDueTask("target-1", "杭州", past);
        taskRepo.updateStatusOwned(task.taskId(), "target-1",
                ScheduledTaskStatus.PAUSED, 0, past);

        scanner.scan();

        Thread.sleep(100);
        assertEquals(0, executionCount.get(), "PAUSED task must not be executed");
    }

    // ==================== deduplication ====================

    @Test
    void duplicateScanShouldNotCreateDuplicateExecution() throws Exception {
        Instant past = fixedClock.instant().minusSeconds(60);
        insertDueTask("target-1", "杭州", past);

        // Scan twice
        scanner.scan();
        scanner.scan();

        Thread.sleep(200);
        assertEquals(1, executionCount.get(),
                "duplicate scan must not create duplicate execution");
    }

    @Test
    void onlyOneExecutionRecordPerScheduledFor() throws Exception {
        Instant past = fixedClock.instant().minusSeconds(60);
        ScheduledTask task = insertDueTask("target-1", "杭州", past);

        scanner.scan();
        Thread.sleep(100);

        // Manually count executions for this (taskId, scheduledFor)
        Integer count = countExecutions(task.taskId(), task.nextRunAt());
        assertEquals(1, count, "exactly one execution record per (taskId, scheduledFor)");
    }

    // ==================== nextRunAt advancement ====================

    @Test
    void scanShouldAdvanceNextRunAt() throws Exception {
        Instant past = fixedClock.instant().minusSeconds(60);
        ScheduledTask task = insertDueTask("target-1", "杭州", past);

        Instant originalNextRun = task.nextRunAt();
        scanner.scan();
        Thread.sleep(100);

        ScheduledTask reloaded = taskRepo.findByTaskId(task.taskId()).orElseThrow();
        assertNotEquals(originalNextRun, reloaded.nextRunAt(),
                "nextRunAt must advance after scan");
        assertTrue(reloaded.nextRunAt().isAfter(fixedClock.instant()),
                "new nextRunAt must be in the future");
    }

    // ==================== batch limit ====================

    @Test
    void scanShouldRespectBatchLimit() throws Exception {
        Instant past = fixedClock.instant().minusSeconds(60);
        for (int i = 0; i < 25; i++) {
            insertDueTask("target-1", "city-" + i, past);
        }

        scanner.scan();
        Thread.sleep(500);

        // At most BATCH_SIZE (20), but all due tasks should have execution records
        // after scanning (they just get picked up in subsequent scans)
        int total = executionCount.get();
        assertTrue(total <= 20, "should not exceed batch size in single scan, got " + total);
    }

    // ==================== worker pool resilience ====================

    @Test
    void workerExceptionShouldNotCrashScanner() throws Exception {
        Instant past = fixedClock.instant().minusSeconds(60);
        insertDueTask("target-1", "杭州", past);
        insertDueTask("target-1", "上海", past.plusSeconds(10));

        // Create scanner with throwing executor for first call
        AtomicInteger callCount = new AtomicInteger(0);
        ScheduledTaskExecutor throwingExecutor = executionId -> {
            if (callCount.incrementAndGet() == 1) {
                throw new RuntimeException("simulated failure");
            }
            executionCount.incrementAndGet();
        };

        ExecutorService pool = new ThreadPoolExecutor(
                1, 1, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(10),
                new ThreadPoolExecutor.CallerRunsPolicy());

        ScheduledTaskScanner throwingScanner = new ScheduledTaskScanner(
                taskRepo, execRepo, throwingExecutor, pool, fixedClock);

        throwingScanner.scan();
        Thread.sleep(100);

        // Second task should still execute despite first throwing
        assertTrue(executionCount.get() >= 1,
                "second task should execute despite first throwing");
    }

    // ==================== recovery ====================

    @Test
    void scanShouldRecoverExpiredRunning() throws Exception {
        Instant past = fixedClock.instant().minusSeconds(60);
        ScheduledTask task = insertDueTask("target-1", "杭州", past);

        // Manually create an execution and set it to RUNNING with expired lease
        TaskExecution exec = execRepo.insertUnique(task.taskId(), task.nextRunAt(),
                fixedClock.instant().minusSeconds(600)).orElseThrow();
        new JdbcTemplate(new SQLiteDataSource() {{
            setUrl("jdbc:sqlite:" + tempFile.toAbsolutePath());
        }}).update(
                "UPDATE scheduled_task_execution SET status='RUNNING', lease_until=? WHERE execution_id=?",
                fixedClock.instant().minusSeconds(300).toEpochMilli(), exec.executionId());

        // Advance nextRunAt manually so task is no longer "due"
        taskRepo.advanceNextRun(task.taskId(), 0,
                fixedClock.instant().plusSeconds(3600), fixedClock.instant());

        // Scan should recover the stale execution (but not re-execute since task advanced)
        scanner.scan();
        Thread.sleep(100);

        // Verify recovery happened
        TaskExecution reloaded = execRepo.findById(exec.executionId()).orElseThrow();
        assertEquals(ExecutionStatus.PENDING, reloaded.status(),
                "expired RUNNING should be recovered to PENDING");
    }

    // ==================== helpers ====================

    private ScheduledTask insertDueTask(String targetId, String location, Instant nextRunAt) {
        return taskRepo.insert(ScheduledTask.createDailyWeather(
                targetId, location, LocalTime.of(8, 0),
                ZoneId.of("Asia/Shanghai"), nextRunAt,
                fixedClock.instant().minusSeconds(3600)));
    }

    private int countExecutions(String taskId, Instant scheduledFor) {
        Long count = new JdbcTemplate(new SQLiteDataSource() {{
            setUrl("jdbc:sqlite:" + tempFile.toAbsolutePath());
        }}).queryForObject(
                "SELECT COUNT(*) FROM scheduled_task_execution WHERE task_id = ? AND scheduled_for = ?",
                Long.class, taskId, scheduledFor.toEpochMilli());
        return count != null ? count.intValue() : 0;
    }
}
