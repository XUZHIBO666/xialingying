package com.demo.demo.Service.weather;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Weather module configuration bound from {@code application.yml} under the
 * {@code weather} prefix. All values have safe defaults suitable for Open-Meteo.
 * No credentials are needed because Open-Meteo is a free, anonymous API.
 */
@Data
@Component
@ConfigurationProperties(prefix = "weather")
public class WeatherProperties {

    /** Open-Meteo Geocoding API base URL. */
    private String geocodingBaseUrl = "https://geocoding-api.open-meteo.com/v1/search";

    /** Open-Meteo Forecast API base URL. */
    private String forecastBaseUrl = "https://api.open-meteo.com/v1/forecast";

    /** HTTP connect timeout for weather HTTP calls. */
    private Duration connectTimeout = Duration.ofSeconds(3);

    /** HTTP read timeout for weather HTTP calls. */
    private Duration readTimeout = Duration.ofSeconds(5);

    /** How long a resolved location stays in cache. */
    private Duration locationCacheTtl = Duration.ofHours(24);

    /** How long a current-weather report stays in cache. */
    private Duration currentCacheTtl = Duration.ofMinutes(10);

    /** How long a daily-forecast report stays in cache. */
    private Duration forecastCacheTtl = Duration.ofMinutes(60);

    /** Maximum number of entries across all weather caches. */
    private int maxCacheEntries = 256;

    /** Maximum number of cities accepted in a single batch REST request. */
    private int batchLimit = 10;
}
