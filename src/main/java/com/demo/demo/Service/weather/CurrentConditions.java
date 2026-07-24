package com.demo.demo.Service.weather;

/**
 * Current weather measurements at the observation time.
 * All values use metric units (Celsius, km/h, percent).
 */
public record CurrentConditions(
        double temperatureCelsius,
        double apparentTemperatureCelsius,
        int relativeHumidityPercent,
        double windSpeedKmh,
        int windDirectionDegrees,
        int weatherCode) {}
