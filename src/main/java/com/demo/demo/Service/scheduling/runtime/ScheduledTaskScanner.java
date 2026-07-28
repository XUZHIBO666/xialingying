package com.demo.demo.Service.scheduling.runtime;

import com.demo.demo.Service.scheduling.domain.NextRunCalculator;
import com.demo.demo.Service.scheduling.domain.ScheduledTask;
import com.demo.demo.Service.scheduling.domain.TaskExecution;
import com.demo.demo.Service.scheduling.execution.ScheduledTaskExecutionService;
import com.demo.demo.Service.scheduling.persistence.ScheduledTaskRepository;
import com.demo.demo.Service.scheduling.persistence.TaskExecutionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * Periodically scans the {@code scheduled_task} table for due tasks,
 * creates unique execution records, advances {@code nextRunAt}, and
 * submits work to a bounded worker pool.
 *
 * <p>Deduplication relies solely on the SQLite
 * {@code UNIQUE(task_id, scheduled_for)} constraint — no in-process set.
 */
@Slf4j
@Component
public class ScheduledTaskScanner {

    private static final int BATCH_SIZE = 20;
    private static final Duration SCAN_INTERVAL = Duration.ofSeconds(15);

    private final ScheduledTaskRepository taskRepo;
    private final TaskExecutionRepository execRepo;
    private final ScheduledTaskExecutor taskExecutor;
    private final ExecutorService workerPool;
    private final Clock clock;

    public ScheduledTaskScanner(
            ScheduledTaskRepository taskRepo,
            TaskExecutionRepository execRepo,
            ScheduledTaskExecutor taskExecutor,
            @Qualifier("schedulingWorkerExecutor") ExecutorService workerPool,
            Clock clock) {
        this.taskRepo = taskRepo;
        this.execRepo = execRepo;
        this.taskExecutor = taskExecutor;
        this.workerPool = workerPool;
        this.clock = clock;
    }

    /**
     * Scan for due tasks on a fixed interval.
     */
    @Scheduled(fixedRateString = "${scheduling.scan-interval:15s}")
    public void scan() {
        Instant now = clock.instant();

        // Recover stale RUNNING executions from crashed workers
        int recovered = execRepo.recoverExpiredRunning(now,
                ScheduledTaskExecutionService.LEASE_TIMEOUT);
        if (recovered > 0) {
            log.info("[Scanner] Recovered {} expired RUNNING execution(s)", recovered);
        }

        log.debug("[Scanner] Scanning for due tasks at {}", now);

        List<ScheduledTask> dueTasks = taskRepo.findDue(now, BATCH_SIZE);
        if (dueTasks.isEmpty()) {
            return;
        }

        log.debug("[Scanner] Found {} due task(s)", dueTasks.size());
        int submitted = 0;
        for (ScheduledTask task : dueTasks) {
            if (tryClaimAndAdvance(task, now)) {
                submitted++;
            }
        }

        if (submitted > 0) {
            log.info("[Scanner] Submitted {} execution(s) out of {} due task(s)",
                    submitted, dueTasks.size());
        }
    }

    /**
     * Try to claim a task for this scan cycle: insert a unique execution
     * record, then advance nextRunAt. If the UNIQUE constraint fires,
     * another scanner (or a previous cycle) already claimed it.
     */
    private boolean tryClaimAndAdvance(ScheduledTask task, Instant now) {
        // 1. Insert unique execution record (SQLite UNIQUE constraint is the gate)
        Optional<TaskExecution> exec = execRepo.insertUnique(
                task.taskId(), task.nextRunAt(), now);
        if (exec.isEmpty()) {
            log.debug("[Scanner] Task {} already claimed for {}", task.taskId(), task.nextRunAt());
            return false;
        }

        // 2. Advance nextRunAt with optimistic version check
        ScheduleRule rule = ScheduleRuleCodec.read(task.scheduleExpression());
        ZoneId zone = ZoneId.of(task.timeZone());
        Instant newNextRun = RecurrenceCalculator.next(rule, zone, now).orElseThrow();
        boolean advanced = taskRepo.advanceNextRun(
                task.taskId(), task.version(), newNextRun, now);
        if (!advanced) {
            log.warn("[Scanner] Failed to advance nextRunAt for {} (version conflict)", task.taskId());
            // Execution record exists but task wasn't advanced — recovery in TASK-011
        }

        // 3. Submit to worker pool
        try {
            workerPool.submit(() -> {
                try {
                    taskExecutor.execute(exec.get().executionId());
                } catch (Exception e) {
                    log.error("[Scanner] Execution failed executionId={}: {}",
                            exec.get().executionId(), e.getMessage(), e);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            log.warn("[Scanner] Worker pool full — execution {} remains PENDING",
                    exec.get().executionId());
            return false;
        }
    }
}
