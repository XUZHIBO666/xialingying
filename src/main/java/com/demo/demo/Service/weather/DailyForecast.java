package com.demo.demo.Service.weather;

import java.time.LocalDate;

/**
 * One daily forecast entry with typed numeric units.
 */
public record DailyForecast(
        LocalDate date,
        double maxTemperatureCelsius,
        double minTemperatureCelsius,
        int weatherCode,
        int precipitationProbability) {}
