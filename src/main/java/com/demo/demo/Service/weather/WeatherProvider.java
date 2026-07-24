package com.demo.demo.Service.weather;

/**
 * Outbound port for weather data.
 * Implementations own the HTTP protocol and JSON mapping for a specific
 * external weather API. The application service depends only on this
 * interface, never on a concrete provider.
 */
public interface WeatherProvider {
    WeatherLocation resolveLocation(String requestedLocation);
    WeatherSnapshot fetch(WeatherLocation location);
}
