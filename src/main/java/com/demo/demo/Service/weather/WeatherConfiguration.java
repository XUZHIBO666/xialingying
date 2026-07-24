package com.demo.demo.Service.weather;

import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Spring configuration for weather module infrastructure beans.
 * Provides a test-friendly {@link Clock} and a dedicated {@link OkHttpClient}
 * for weather HTTP calls with configurable timeouts.
 */
@Configuration
public class WeatherConfiguration {

    /**
     * A UTC clock injectable into {@link WeatherService} for deterministic
     * date resolution in tests.
     */
    @Bean
    Clock weatherClock() {
        return Clock.systemUTC();
    }

    /**
     * A dedicated OkHttpClient for weather provider HTTP calls.
     * Timeouts are bound from {@link WeatherProperties}.
     */
    @Bean
    @Qualifier("weatherHttpClient")
    OkHttpClient weatherHttpClient(WeatherProperties properties) {
        return new OkHttpClient.Builder()
                .connectTimeout(properties.getConnectTimeout())
                .readTimeout(properties.getReadTimeout())
                .build();
    }
}
