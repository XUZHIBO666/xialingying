package com.demo.demo.Service.weather;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Selected current or forecast result returned to adapters (Tool / REST).
 * Exactly one of {@code current} or {@code forecast} is populated,
 * determined by {@link #type}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WeatherReport(
        WeatherReportType type,
        WeatherLocation location,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate targetDate,
        Instant observedAt,
        CurrentConditions current,
        DailyForecast forecast,
        String source) {}
