package com.demo.demo.Service.weather;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class OpenMeteoWeatherProviderTest {

    private MockWebServer geocodingServer;
    private MockWebServer forecastServer;
    private OpenMeteoWeatherProvider provider;
    private WeatherProperties properties;

    @BeforeEach
    void setUp() throws IOException {
        geocodingServer = new MockWebServer();
        geocodingServer.start();
        forecastServer = new MockWebServer();
        forecastServer.start();

        properties = new WeatherProperties();
        properties.setGeocodingBaseUrl(geocodingServer.url("/").toString());
        properties.setForecastBaseUrl(forecastServer.url("/").toString());
        properties.setConnectTimeout(Duration.ofSeconds(5));
        properties.setReadTimeout(Duration.ofSeconds(5));

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(5))
                .build();

        provider = new OpenMeteoWeatherProvider(client, new ObjectMapper(), properties);
    }

    @AfterEach
    void tearDown() throws IOException {
        geocodingServer.shutdown();
        forecastServer.shutdown();
    }

    // ==================== Happy path ====================

    @Test
    @DisplayName("resolveLocation returns canonical location from geocoding result")
    void resolveLocationFromGeocoding() {
        geocodingServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"results\":[{\"name\":\"Hangzhou\",\"admin1\":\"Zhejiang\"," +
                        "\"country\":\"China\",\"latitude\":30.2741,\"longitude\":120.1551," +
                        "\"timezone\":\"Asia/Shanghai\"}]}"));

        WeatherLocation location = provider.resolveLocation("杭州");

        assertEquals("杭州", location.requestedName());
        assertEquals("Hangzhou", location.name());
        assertEquals("Zhejiang", location.adminArea());
        assertEquals("China", location.country());
        assertEquals(30.2741, location.latitude());
        assertEquals(120.1551, location.longitude());
        assertEquals(ZoneId.of("Asia/Shanghai"), location.zoneId());
    }

    @Test
    @DisplayName("fetch returns snapshot with current conditions and daily forecasts")
    void fetchForecastSnapshot() {
        geocodingServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"results\":[{\"name\":\"Hangzhou\",\"admin1\":\"Zhejiang\"," +
                        "\"country\":\"China\",\"latitude\":30.2741,\"longitude\":120.1551," +
                        "\"timezone\":\"Asia/Shanghai\"}]}"));
        forecastServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"timezone\":\"Asia/Shanghai\"," +
                        "\"current\":{\"time\":\"2026-07-23T14:00\"," +
                        "\"temperature_2m\":33.1,\"relative_humidity_2m\":58," +
                        "\"apparent_temperature\":37.0,\"weather_code\":2," +
                        "\"wind_speed_10m\":12.4,\"wind_direction_10m\":135}," +
                        "\"daily\":{\"time\":[\"2026-07-23\",\"2026-07-24\",\"2026-07-25\"]," +
                        "\"weather_code\":[2,61,3]," +
                        "\"temperature_2m_max\":[35.0,32.0,31.0]," +
                        "\"temperature_2m_min\":[27.0,25.0,24.0]," +
                        "\"precipitation_probability_max\":[20,70,30]}}"));

        WeatherLocation location = provider.resolveLocation("杭州");
        WeatherSnapshot snapshot = provider.fetch(location);

        // Current conditions
        assertEquals(33.1, snapshot.current().temperatureCelsius());
        assertEquals(37.0, snapshot.current().apparentTemperatureCelsius());
        assertEquals(58, snapshot.current().relativeHumidityPercent());
        assertEquals(12.4, snapshot.current().windSpeedKmh());
        assertEquals(135, snapshot.current().windDirectionDegrees());
        assertEquals(2, snapshot.current().weatherCode());

        // Daily forecasts
        assertEquals(3, snapshot.dailyForecasts().size());
        assertEquals(70, snapshot.dailyForecasts().get(1).precipitationProbability());

        // Metadata
        assertEquals("open-meteo", snapshot.source());
        assertNotNull(snapshot.observedAt());
    }

    // ==================== Failure: location not found ====================

    @Test
    @DisplayName("empty geocoding results throw LOCATION_NOT_FOUND")
    void emptyResultsLocationNotFound() {
        geocodingServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"results\":[]}"));

        WeatherException ex = assertThrows(WeatherException.class,
                () -> provider.resolveLocation("NonExistentCityXYZ"));
        assertEquals(WeatherError.LOCATION_NOT_FOUND, ex.getError());
        assertTrue(ex.getMessage().contains("NonExistentCityXYZ"));
    }

    // ==================== Failure: malformed JSON ====================

    @Test
    @DisplayName("malformed geocoding JSON throws PROVIDER_RESPONSE_INVALID")
    void malformedJsonResponseInvalid() {
        geocodingServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("this is not json"));

        WeatherException ex = assertThrows(WeatherException.class,
                () -> provider.resolveLocation("杭州"));
        assertEquals(WeatherError.PROVIDER_RESPONSE_INVALID, ex.getError());
    }

    // ==================== Failure: provider unavailable ====================

    @Test
    @DisplayName("HTTP 429 throws PROVIDER_UNAVAILABLE")
    void http429ProviderUnavailable() {
        geocodingServer.enqueue(new MockResponse()
                .setResponseCode(429)
                .setBody("Rate limited"));

        WeatherException ex = assertThrows(WeatherException.class,
                () -> provider.resolveLocation("杭州"));
        assertEquals(WeatherError.PROVIDER_UNAVAILABLE, ex.getError());
    }

    @Test
    @DisplayName("HTTP 503 throws PROVIDER_UNAVAILABLE")
    void http503ProviderUnavailable() {
        geocodingServer.enqueue(new MockResponse()
                .setResponseCode(503)
                .setBody("Service Unavailable"));

        WeatherException ex = assertThrows(WeatherException.class,
                () -> provider.resolveLocation("杭州"));
        assertEquals(WeatherError.PROVIDER_UNAVAILABLE, ex.getError());
    }

    // ==================== Failure: timeout ====================

    @Test
    @DisplayName("connection timeout throws PROVIDER_TIMEOUT")
    void connectionTimeout() throws IOException {
        // Use a dedicated server for the timeout test so teardown is clean
        MockWebServer timeoutServer = new MockWebServer();
        try {
            timeoutServer.start();

            MockResponse delayed = new MockResponse()
                    .setResponseCode(200)
                    .setBody("{\"results\":[]}")
                    .setBodyDelay(3, TimeUnit.SECONDS);

            timeoutServer.enqueue(delayed);
            // The enqueued response will be consumed by the first request; enqueue
            // another one so shutdown doesn't hit an empty-queue error
            timeoutServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setBody("{\"results\":[]}"));

            WeatherProperties shortTimeoutProps = new WeatherProperties();
            shortTimeoutProps.setGeocodingBaseUrl(timeoutServer.url("/").toString());
            shortTimeoutProps.setForecastBaseUrl(forecastServer.url("/").toString());
            shortTimeoutProps.setConnectTimeout(Duration.ofSeconds(5));
            shortTimeoutProps.setReadTimeout(Duration.ofMillis(50));

            OkHttpClient shortTimeoutClient = new OkHttpClient.Builder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .readTimeout(Duration.ofMillis(50))
                    .build();

            OpenMeteoWeatherProvider shortTimeoutProvider =
                    new OpenMeteoWeatherProvider(shortTimeoutClient, new ObjectMapper(), shortTimeoutProps);

            WeatherException ex = assertThrows(WeatherException.class,
                    () -> shortTimeoutProvider.resolveLocation("杭州"));
            assertEquals(WeatherError.PROVIDER_TIMEOUT, ex.getError());
        } finally {
            timeoutServer.shutdown();
        }
    }
}
