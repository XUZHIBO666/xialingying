package com.demo.demo.Service.scheduling.execution;

import com.demo.demo.Service.scheduling.application.SchedulingException;

import java.util.*;

/**
 * Registry that maps {@code taskType} strings to their
 * corresponding {@link ScheduledTaskHandler}.
 *
 * <p>Built once at construction time with an immutable mapping.
 */
public class ScheduledTaskHandlerRegistry {

    private final Map<String, ScheduledTaskHandler> handlers;

    public ScheduledTaskHandlerRegistry(List<ScheduledTaskHandler> handlerList) {
        if (handlerList == null || handlerList.isEmpty()) {
            throw new IllegalStateException("At least one ScheduledTaskHandler is required");
        }
        Map<String, ScheduledTaskHandler> map = new LinkedHashMap<>();
        for (ScheduledTaskHandler h : handlerList) {
            if (map.containsKey(h.taskType())) {
                throw new IllegalStateException(
                        "Duplicate task type: " + h.taskType());
            }
            map.put(h.taskType(), h);
        }
        this.handlers = Collections.unmodifiableMap(map);
    }

    /**
     * Get the handler for a task type.
     *
     * @throws SchedulingException if no handler is registered for the type
     */
    public ScheduledTaskHandler require(String taskType) {
        ScheduledTaskHandler handler = handlers.get(taskType);
        if (handler == null) {
            throw new SchedulingException("Unsupported task type: " + taskType);
        }
        return handler;
    }
}
