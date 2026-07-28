package com.demo.demo.Service.scheduling.persistence;

import com.demo.demo.Service.scheduling.domain.ExecutionStatus;
import com.demo.demo.Service.scheduling.domain.TaskExecution;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class JdbcTaskExecutionRepositoryTest {

    private JdbcTaskExecutionRepository repo;
    private Path tempFile;
    private Instant now;

    @BeforeEach
    void setUp() throws Exception {
        tempFile = Files.createTempFile("task-execution-test-", ".sqlite");
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempFile.toAbsolutePath());
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        new SchedulingSchemaInitializer(jdbc).init();
        repo = new JdbcTaskExecutionRepository(jdbc);
        now = Instant.now();
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(tempFile);
    }

    // ==================== insertUnique ====================

    @Test
    void insertUniqueShouldCreateExecutionOnFirstAttempt() {
        Instant scheduledFor = now.plusSeconds(3600);
        Optional<TaskExecution> result = repo.insertUnique("task-1", scheduledFor, now);

        assertTrue(result.isPresent(), "first insert should succeed");
        TaskExecution exec = result.get();
        assertNotNull(exec.executionId());
        assertEquals("task-1", exec.taskId());
        assertEquals(scheduledFor.getEpochSecond(), exec.scheduledFor().getEpochSecond());
        assertEquals(ExecutionStatus.PENDING, exec.status());
    }

    @Test
    void insertUniqueShouldReturnEmptyOnDuplicate() {
        Instant scheduledFor = now.plusSeconds(3600);

        // First insert — OK
        Optional<TaskExecution> first = repo.insertUnique("task-1", scheduledFor, now);
        assertTrue(first.isPresent());

        // Second insert with same (taskId, scheduledFor) — must fail gracefully
        Optional<TaskExecution> second = repo.insertUnique("task-1", scheduledFor, now.plusSeconds(100));
        assertTrue(second.isEmpty(), "duplicate must return empty");
    }

    @Test
    void insertUniqueShouldAllowDifferentScheduledFor() {
        // Same task, different scheduled time → different rows
        Optional<TaskExecution> first = repo.insertUnique("task-1", now.plusSeconds(3600), now);
        assertTrue(first.isPresent());

        Optional<TaskExecution> second = repo.insertUnique("task-1", now.plusSeconds(7200), now);
        assertTrue(second.isPresent(), "different scheduled_for should be allowed");
        assertNotEquals(first.get().executionId(), second.get().executionId());
    }

    @Test
    void insertUniqueShouldAllowDifferentTasksSameTime() {
        // Different tasks, same scheduled time → different rows
        Optional<TaskExecution> t1 = repo.insertUnique("task-a", now.plusSeconds(3600), now);
        Optional<TaskExecution> t2 = repo.insertUnique("task-b", now.plusSeconds(3600), now);

        assertTrue(t1.isPresent());
        assertTrue(t2.isPresent(), "different tasks at same time should be allowed");
        assertNotEquals(t1.get().executionId(), t2.get().executionId());
    }

    @Test
    void insertUniqueShouldDetectUniqueConstraintByMessage() {
        Instant scheduledFor = now.plusSeconds(3600);

        // First insert succeeds
        Optional<TaskExecution> first = repo.insertUnique("task-1", scheduledFor, now);
        assertTrue(first.isPresent());

        // Second insert with same (taskId, scheduledFor) fails due to UNIQUE constraint
        Optional<TaskExecution> second = repo.insertUnique("task-1", scheduledFor, now);
        assertTrue(second.isEmpty(),
                "must return empty on UNIQUE constraint, not throw or return garbage");
    }

    // ==================== findById ====================

    @Test
    void findByIdShouldReturnExecution() {
        Optional<TaskExecution> created = repo.insertUnique("task-1", now.plusSeconds(3600), now);
        assertTrue(created.isPresent());

        Optional<TaskExecution> found = repo.findById(created.get().executionId());
        assertTrue(found.isPresent());
        assertEquals(created.get().executionId(), found.get().executionId());
        assertEquals("task-1", found.get().taskId());
    }

    @Test
    void findByIdShouldReturnEmptyForUnknownId() {
        Optional<TaskExecution> found = repo.findById("nonexistent-exec-id");
        assertTrue(found.isEmpty());
    }

    // ==================== claim ====================

    @Test
    void claimShouldTransitionPendingToRunning() {
        Optional<TaskExecution> exec = repo.insertUnique("task-1", now.plusSeconds(3600), now);
        assertTrue(exec.isPresent());

        boolean claimed = repo.claim(exec.get().executionId(), now, Duration.ofMinutes(5));
        assertTrue(claimed);

        TaskExecution reloaded = repo.findById(exec.get().executionId()).orElseThrow();
        assertEquals(ExecutionStatus.RUNNING, reloaded.status());
        assertEquals(1, reloaded.attemptCount());
    }

    @Test
    void claimShouldFailForAlreadyRunning() {
        Optional<TaskExecution> exec = repo.insertUnique("task-1", now.plusSeconds(3600), now);
        repo.claim(exec.get().executionId(), now, Duration.ofMinutes(5));

        boolean secondClaim = repo.claim(exec.get().executionId(), now.plusSeconds(1), Duration.ofMinutes(5));
        assertFalse(secondClaim, "cannot claim an already RUNNING execution");
    }

    // ==================== markSucceeded ====================

    @Test
    void markSucceededShouldTransitionFromRunning() {
        Optional<TaskExecution> exec = repo.insertUnique("task-1", now.plusSeconds(3600), now);
        repo.claim(exec.get().executionId(), now, Duration.ofMinutes(5));

        boolean marked = repo.markSucceeded(exec.get().executionId(), now.plusSeconds(10));
        assertTrue(marked);

        TaskExecution reloaded = repo.findById(exec.get().executionId()).orElseThrow();
        assertEquals(ExecutionStatus.SUCCEEDED, reloaded.status());
    }

    // ==================== markFailed ====================

    @Test
    void markFailedShouldSetTerminalStatus() {
        Optional<TaskExecution> exec = repo.insertUnique("task-1", now.plusSeconds(3600), now);
        repo.claim(exec.get().executionId(), now, Duration.ofMinutes(5));

        repo.markFailed(exec.get().executionId(), "SDK_ERROR", now.plusSeconds(10));

        TaskExecution reloaded = repo.findById(exec.get().executionId()).orElseThrow();
        assertEquals(ExecutionStatus.FAILED, reloaded.status());
        assertEquals("SDK_ERROR", reloaded.errorCode());
    }

    // ==================== recoverExpiredRunning ====================

    @Test
    void recoverExpiredRunningShouldResetToPending() {
        Optional<TaskExecution> exec = repo.insertUnique("task-1", now.plusSeconds(3600), now);
        repo.claim(exec.get().executionId(), now.minusSeconds(600), Duration.ofMinutes(5));

        int recovered = repo.recoverExpiredRunning(now, Duration.ofMinutes(5));
        assertEquals(1, recovered);

        TaskExecution reloaded = repo.findById(exec.get().executionId()).orElseThrow();
        assertEquals(ExecutionStatus.PENDING, reloaded.status());
    }

    // ==================== markDegraded ====================

    @Test
    void markDegradedShouldTransitionFromRunning() {
        Optional<TaskExecution> exec = repo.insertUnique("task-1", now.plusSeconds(3600), now);
        repo.claim(exec.get().executionId(), now, Duration.ofMinutes(5));

        boolean marked = repo.markDegraded(exec.get().executionId(), "IMAGE_FAILED", now.plusSeconds(10));
        assertTrue(marked);

        TaskExecution reloaded = repo.findById(exec.get().executionId()).orElseThrow();
        assertEquals(ExecutionStatus.DEGRADED, reloaded.status());
        assertEquals("IMAGE_FAILED", reloaded.errorCode());
    }

    @Test
    void markDegradedShouldFailForNonRunningStatus() {
        Optional<TaskExecution> exec = repo.insertUnique("task-1", now.plusSeconds(3600), now);

        boolean marked = repo.markDegraded(exec.get().executionId(), "X", now);
        assertFalse(marked, "cannot markDegraded a PENDING execution");
    }
}
