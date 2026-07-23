package com.demo.demo.Service.weather;

import java.time.Instant;
import java.util.List;

/**
 * Complete provider result (current + up to 3 daily forecasts) before
 * the application service selects a date-specific subset.
 */
public record WeatherSnapshot(
        WeatherLocation location,
        Instant observedAt,
        CurrentConditions current,
        List<DailyForecast> dailyForecasts,
        String source) {
    public WeatherSnapshot {
        dailyForecasts = List.copyOf(dailyForecasts);
    }
}
