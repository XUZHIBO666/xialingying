package com.demo.demo.Service.weather;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;

/**
 * One daily forecast entry with typed numeric units.
 */
public record DailyForecast(
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate date,
        double maxTemperatureCelsius,
        double minTemperatureCelsius,
        int weatherCode,
        int precipitationProbability) {}
