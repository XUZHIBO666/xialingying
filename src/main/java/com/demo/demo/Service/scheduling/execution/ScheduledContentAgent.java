package com.demo.demo.Service.scheduling.execution;

import com.demo.demo.Service.scheduling.domain.ScheduledTask;
import com.demo.demo.Service.weather.WeatherReport;

/**
 * Generates user-facing text from weather data for a scheduled task.
 *
 * <p>The real implementation (TASK-009) builds a dedicated, side-effect-free
 * ReactAgent — no MemorySaver, no vector memory, no tools. Tests use a mock.
 */
public interface ScheduledContentAgent {

    /**
     * Generate a short weather message.
     *
     * @param task   the scheduled task (contains location, time zone, etc.)
     * @param report the actual weather data
     * @return human-readable weather message, or null on failure
     */
    String generate(ScheduledTask task, WeatherReport report);
}
