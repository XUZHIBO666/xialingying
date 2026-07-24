package com.demo.demo.Service.weather;

/**
 * Stable failure categories for weather operations.
 * Each corresponds to a specific user-visible or machine-readable failure mode.
 */
public enum WeatherError {
    LOCATION_REQUIRED,
    LOCATION_AMBIGUOUS,
    LOCATION_NOT_FOUND,
    INVALID_DATE,
    PROVIDER_TIMEOUT,
    PROVIDER_UNAVAILABLE,
    PROVIDER_RESPONSE_INVALID
}
