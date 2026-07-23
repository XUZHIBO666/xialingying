package com.demo.demo.Service.weather;

import lombok.Getter;

/**
 * Typed application/provider exception for weather operations.
 * Carries a stable {@link WeatherError} category for safe routing and logging.
 */
@Getter
public final class WeatherException extends RuntimeException {
    private final WeatherError error;

    public WeatherException(WeatherError error, String message) {
        super(message);
        this.error = error;
    }

    public WeatherException(WeatherError error, String message, Throwable cause) {
        super(message, cause);
        this.error = error;
    }
}
