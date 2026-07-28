package com.demo.demo.Service.scheduling.execution;

import com.demo.demo.Service.scheduling.domain.ScheduledTask;
import com.demo.demo.Service.scheduling.domain.TaskExecution;

/**
 * Handles the execution of a single scheduled task type.
 *
 * <p>Implementations contain the business logic for one {@code taskType}
 * (weather, creative card, etc.) and return a structured result that
 * the common execution service uses to update the execution record.
 */
public interface ScheduledTaskHandler {

    /** The task type this handler is responsible for (e.g. "DAILY_WEATHER"). */
    String taskType();

    /**
     * Execute the business logic for a scheduled task.
     *
     * @param task      the task to execute
     * @param execution the claimed execution record
     * @return structured result indicating success, degradation, or failure
     */
    TaskHandlingResult handle(ScheduledTask task, TaskExecution execution);
}
