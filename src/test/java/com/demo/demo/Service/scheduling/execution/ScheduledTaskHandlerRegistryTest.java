package com.demo.demo.Service.scheduling.execution;

import com.demo.demo.Service.scheduling.application.SchedulingException;
import com.demo.demo.Service.scheduling.domain.ScheduledTask;
import com.demo.demo.Service.scheduling.domain.TaskExecution;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScheduledTaskHandlerRegistryTest {

    private static ScheduledTaskHandler handler(String type) {
        return new ScheduledTaskHandler() {
            @Override public String taskType() { return type; }
            @Override public TaskHandlingResult handle(ScheduledTask t, TaskExecution e) {
                return TaskHandlingResult.succeeded();
            }
        };
    }

    @Test
    void shouldResolveHandlerByTaskType() {
        var weather = handler("DAILY_WEATHER");
        var registry = new ScheduledTaskHandlerRegistry(List.of(weather));
        assertSame(weather, registry.require("DAILY_WEATHER"));
    }

    @Test
    void shouldRejectDuplicateTaskTypes() {
        assertThrows(IllegalStateException.class,
                () -> new ScheduledTaskHandlerRegistry(List.of(
                        handler("DAILY_WEATHER"), handler("DAILY_WEATHER"))));
    }

    @Test
    void shouldRejectEmptyHandlerList() {
        assertThrows(IllegalStateException.class,
                () -> new ScheduledTaskHandlerRegistry(List.of()));
    }

    @Test
    void shouldRejectUnknownTaskType() {
        var registry = new ScheduledTaskHandlerRegistry(List.of(handler("DAILY_WEATHER")));
        assertThrows(SchedulingException.class,
                () -> registry.require("UNKNOWN_TYPE"));
    }
}
