package com.demo.demo.Service.weather;

import java.time.ZoneId;

/**
 * Normalized place with coordinates and time zone.
 * The provider resolves a user-supplied name into this canonical form.
 */
public record WeatherLocation(
        String requestedName,
        String name,
        String adminArea,
        String country,
        double latitude,
        double longitude,
        ZoneId zoneId) {}
