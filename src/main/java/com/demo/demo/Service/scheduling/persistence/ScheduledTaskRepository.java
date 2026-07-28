package com.demo.demo.Service.scheduling.persistence;

import com.demo.demo.Service.scheduling.domain.ScheduledTask;
import com.demo.demo.Service.scheduling.domain.ScheduledTaskStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link ScheduledTask} persistence.
 */
public interface ScheduledTaskRepository {

    /**
     * Insert a new scheduled task.
     *
     * @param task the task to insert (id will be null)
     * @return the persisted task with generated id
     */
    ScheduledTask insert(ScheduledTask task);

    /**
     * Find all tasks owned by a delivery target.
     */
    List<ScheduledTask> findByOwner(String targetId);

    /**
     * Find a single task by its public task ID.
     */
    Optional<ScheduledTask> findByTaskId(String taskId);

    /**
     * Update the status of a task, verifying ownership and optimistic version.
     *
     * @return true if exactly one row was updated
     */
    boolean updateStatusOwned(String taskId, String ownerTargetId,
                              ScheduledTaskStatus newStatus, int expectedVersion, Instant now);

    /**
     * Advance {@code nextRunAt} with optimistic version check.
     *
     * @return true if exactly one row was updated
     */
    boolean advanceNextRun(String taskId, int expectedVersion, Instant newNextRun, Instant now);

    /**
     * Find tasks that are due for execution.
     *
     * @param now       current timestamp
     * @param batchSize maximum number of tasks to return
     */
    List<ScheduledTask> findDue(Instant now, int batchSize);
}
