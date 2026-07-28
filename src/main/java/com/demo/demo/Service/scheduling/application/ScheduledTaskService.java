package com.demo.demo.Service.scheduling.application;

import com.demo.demo.Service.scheduling.domain.*;
import com.demo.demo.Service.scheduling.persistence.ScheduledTaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Application service for scheduled task lifecycle.
 *
 * <p>All state-mutating operations verify ownership via {@code targetId}
 * before touching the repository. Duplicate detection is based on
 * (owner, location, localTime, timeZone, ACTIVE status).
 */
@Slf4j
@Service
public class ScheduledTaskService {

    private final ScheduledTaskRepository taskRepo;

    public ScheduledTaskService(ScheduledTaskRepository taskRepo) {
        this.taskRepo = taskRepo;
    }

    /**
     * Create a new daily weather task.
     *
     * @return the public task key (UUID)
     * @throws SchedulingException if a duplicate ACTIVE task exists
     */
    public String createDailyWeatherTask(CreateDailyWeatherTaskCommand cmd) {
        // Duplicate detection: same owner + location + time + zone + ACTIVE
        List<ScheduledTask> existing = taskRepo.findByOwner(cmd.ownerTargetId());
        ScheduleRule newRule = ScheduleRule.daily(cmd.localTime());
        for (ScheduledTask t : existing) {
            if (t.status() != ScheduledTaskStatus.ACTIVE) continue;
            if (!ScheduledTask.TASK_TYPE_DAILY_WEATHER.equals(t.taskType())) continue;
            ScheduleRule existingRule = ScheduleRuleCodec.read(t.scheduleExpression());
            WeatherTaskPayload existingPayload = t.weatherPayload();
            if (cmd.location().equals(existingPayload.location())
                    && newRule.equals(existingRule)
                    && cmd.zoneId().getId().equals(t.timeZone())) {
                throw new SchedulingException(
                        "Duplicate task: daily weather for " + cmd.location()
                                + " at " + cmd.localTime() + " " + cmd.zoneId());
            }
        }

        Instant now = Instant.now();
        Instant nextRunAt = RecurrenceCalculator.next(newRule, cmd.zoneId(), now).orElseThrow();

        ScheduledTask task = ScheduledTask.createDailyWeather(
                cmd.ownerTargetId(),
                cmd.location(),
                cmd.localTime(),
                cmd.zoneId(),
                nextRunAt,
                now);

        ScheduledTask saved = taskRepo.insert(task);
        log.info("[ScheduledTask] Created task={} location={} time={} zone={} nextRun={}",
                saved.taskId(), saved.location(), saved.localTime(), saved.timeZone(),
                saved.nextRunAt());
        return saved.taskId();
    }

    /**
     * List all tasks belonging to a delivery target.
     */
    public List<ScheduledTaskSummary> listTasks(String targetId) {
        return taskRepo.findByOwner(targetId).stream()
                .map(ScheduledTaskSummary::from)
                .toList();
    }

    /**
     * Pause an ACTIVE task. Verifies ownership.
     */
    public void pause(String targetId, String taskId) {
        ScheduledTask task = findOwned(targetId, taskId);
        ScheduledTask paused = task.pause(Instant.now());
        taskRepo.updateStatusOwned(
                taskId, targetId, ScheduledTaskStatus.PAUSED,
                task.version(), paused.updatedAt());
        log.info("[ScheduledTask] Paused task={}", taskId);
    }

    /**
     * Resume a PAUSED task. Recalculates {@code nextRunAt}. Verifies ownership.
     */
    public void resume(String targetId, String taskId) {
        ScheduledTask task = findOwned(targetId, taskId);
        ScheduledTask resumed = task.resume(Instant.now());
        taskRepo.updateStatusOwned(
                taskId, targetId, ScheduledTaskStatus.ACTIVE,
                task.version(), resumed.updatedAt());
        taskRepo.advanceNextRun(
                taskId, task.version() + 1,
                resumed.nextRunAt(), resumed.updatedAt());
        log.info("[ScheduledTask] Resumed task={} nextRun={}", taskId, resumed.nextRunAt());
    }

    /**
     * Cancel a task. Verifies ownership.
     */
    public void cancel(String targetId, String taskId) {
        ScheduledTask task = findOwned(targetId, taskId);
        ScheduledTask canceled = task.cancel(Instant.now());
        taskRepo.updateStatusOwned(
                taskId, targetId, ScheduledTaskStatus.CANCELED,
                task.version(), canceled.updatedAt());
        log.info("[ScheduledTask] Canceled task={}", taskId);
    }

    private ScheduledTask findOwned(String targetId, String taskId) {
        ScheduledTask task = taskRepo.findByTaskId(taskId)
                .orElseThrow(() -> new SchedulingException(
                        "Task not found: " + taskId));
        if (!task.ownerTargetId().equals(targetId)) {
            throw new SchedulingException(
                    "Task " + taskId + " does not belong to target " + targetId);
        }
        return task;
    }
}
