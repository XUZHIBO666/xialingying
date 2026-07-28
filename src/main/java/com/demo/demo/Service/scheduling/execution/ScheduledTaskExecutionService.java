package com.demo.demo.Service.scheduling.execution;

import com.demo.demo.Service.scheduling.domain.*;
import com.demo.demo.Service.scheduling.persistence.*;
import com.demo.demo.Service.scheduling.runtime.ScheduledTaskExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Common scheduled execution orchestrator: claim, load, route to handler,
 * and persist the result. Task-type-specific logic lives in
 * {@link ScheduledTaskHandler} implementations.
 */
@Slf4j
@Service
public class ScheduledTaskExecutionService implements ScheduledTaskExecutor {

    public static final Duration LEASE_TIMEOUT = Duration.ofMinutes(5);

    private final TaskExecutionRepository execRepo;
    private final ScheduledTaskRepository taskRepo;
    private final ScheduledTaskHandlerRegistry handlerRegistry;

    public ScheduledTaskExecutionService(
            TaskExecutionRepository execRepo,
            ScheduledTaskRepository taskRepo,
            ScheduledTaskHandlerRegistry handlerRegistry) {
        this.execRepo = execRepo;
        this.taskRepo = taskRepo;
        this.handlerRegistry = handlerRegistry;
    }

    @Override
    public void execute(String executionId) {
        Instant now = Instant.now();

        // 1. Claim the execution (PENDING/RETRY → RUNNING with lease)
        if (!execRepo.claim(executionId, now, LEASE_TIMEOUT)) {
            log.debug("[Execution] Could not claim {} — already claimed", executionId);
            return;
        }

        TaskExecution execution = execRepo.findById(executionId).orElse(null);
        if (execution == null) return;

        // 2. Load task
        ScheduledTask task = taskRepo.findByTaskId(execution.taskId()).orElse(null);
        if (task == null) {
            handleFailure(executionId, "TASK_NOT_FOUND", now, execution.attemptCount());
            return;
        }

        // 3. Only execute ACTIVE tasks
        if (task.status() != ScheduledTaskStatus.ACTIVE) {
            log.info("[Execution] Task {} is {}, skipping", task.taskId(), task.status());
            execRepo.markSucceeded(executionId, now);
            return;
        }

        // 4. Route to type-specific handler
        ScheduledTaskHandler handler;
        try {
            handler = handlerRegistry.require(task.taskType());
        } catch (Exception e) {
            log.error("[Execution] No handler for type {}: {}", task.taskType(), e.getMessage());
            handleFailure(executionId, "UNSUPPORTED_TASK_TYPE", now, execution.attemptCount());
            return;
        }

        // 5. Execute handler
        TaskHandlingResult result = handler.handle(task, execution);

        // 6. Persist result
        switch (result.status()) {
            case SUCCEEDED -> {
                execRepo.markSucceeded(executionId, now);
                log.info("[Execution] Succeeded {}", executionId);
            }
            case DEGRADED -> {
                execRepo.markDegraded(executionId, result.errorCode(), now);
                log.info("[Execution] Degraded {} error={}", executionId, result.errorCode());
            }
            case FAILED -> handleFailure(executionId, result.errorCode(), now,
                    execution.attemptCount());
        }
    }

    void handleFailure(String executionId, String errorCode, Instant now, int attemptCount) {
        Optional<Instant> nextAttempt = RetryPolicy.nextAttempt(attemptCount, errorCode, now);
        if (nextAttempt.isPresent()) {
            execRepo.scheduleRetry(executionId, nextAttempt.get(), errorCode, now);
            log.info("[Execution] Retry scheduled {} attempt={} next={}",
                    executionId, attemptCount + 1, nextAttempt.get());
        } else {
            execRepo.markFailed(executionId, errorCode, now);
            log.warn("[Execution] Terminal failure {} attempt={} error={}",
                    executionId, attemptCount, errorCode);
        }
    }
}
