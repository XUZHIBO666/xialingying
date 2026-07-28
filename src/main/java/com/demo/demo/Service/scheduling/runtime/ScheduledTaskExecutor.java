package com.demo.demo.Service.scheduling.runtime;

/**
 * Executes a single scheduled task execution.
 *
 * <p>In TASK-008 this is a placeholder. TASK-009 will implement
 * weather fetching, content generation, and message pushing.
 */
public interface ScheduledTaskExecutor {

    /**
     * Execute the work for a previously-claimed execution record.
     *
     * @param executionId the unique execution identifier
     */
    void execute(String executionId);
}
