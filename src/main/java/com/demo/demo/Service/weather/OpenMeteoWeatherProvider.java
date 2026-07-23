package com.demo.demo.Service.weather;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Open-Meteo weather provider — free, anonymous weather API.
 *
 * <p>Uses two endpoints:
 * <ul>
 *   <li>Geocoding API — resolves location names to coordinates and time zones.</li>
 *   <li>Forecast API — fetches current conditions and up to 3 daily forecasts.</li>
 * </ul>
 *
 * <p>This class owns all Open-Meteo HTTP and JSON details.
 * The rest of the application depends only on {@link WeatherProvider}.
 */
@Slf4j
@Component
public class OpenMeteoWeatherProvider implements WeatherProvider {

    private static final String SOURCE = "open-meteo";

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final WeatherProperties properties;

    public OpenMeteoWeatherProvider(
            @Qualifier("weatherHttpClient") OkHttpClient httpClient,
            ObjectMapper objectMapper,
            WeatherProperties properties) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    // ==================== Location resolution ====================

    @Override
    public WeatherLocation resolveLocation(String requestedLocation) {
        long start = System.currentTimeMillis();
        HttpUrl url = requireBaseUrl(properties.getGeocodingBaseUrl()).newBuilder()
                .addQueryParameter("name", requestedLocation)
                .addQueryParameter("count", "5")
                .addQueryParameter("language", "zh")
                .addQueryParameter("format", "json")
                .build();

        JsonNode root = executeGet(url, "geocoding");
        JsonNode results = root.get("results");

        if (results == null || !results.isArray() || results.isEmpty()) {
            log.warn("[Open-Meteo] Location not found, elapsed={}ms",
                    System.currentTimeMillis() - start);
            throw new WeatherException(WeatherError.LOCATION_NOT_FOUND,
                    "未找到城市「" + requestedLocation + "」，请检查城市名拼写");
        }

        JsonNode first = results.get(0);

        String name = stringField(first, "name");
        String adminArea = stringField(first, "admin1");
        String country = stringField(first, "country");
        double lat = doubleField(first, "latitude");
        double lon = doubleField(first, "longitude");
        String timezoneStr = stringField(first, "timezone");

        if (timezoneStr.isEmpty()) {
            throw new WeatherException(WeatherError.PROVIDER_RESPONSE_INVALID,
                    "天气数据缺少时区信息");
        }

        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(timezoneStr);
        } catch (Exception e) {
            throw new WeatherException(WeatherError.PROVIDER_RESPONSE_INVALID,
                    "天气数据时区无效: " + timezoneStr, e);
        }

        // Ambiguity check: multiple results with different admin1/country,
        // and the first result is not an exact case-insensitive name match
        if (results.size() > 1 && name != null && !name.equalsIgnoreCase(requestedLocation)) {
            String firstCountry = country;
            String firstAdmin = adminArea;
            boolean ambiguous = false;
            for (int i = 1; i < results.size(); i++) {
                JsonNode other = results.get(i);
                String otherAdmin = stringField(other, "admin1");
                String otherCountry = stringField(other, "country");
                if (!otherAdmin.equals(firstAdmin) || !otherCountry.equals(firstCountry)) {
                    ambiguous = true;
                    break;
                }
            }
            if (ambiguous) {
                log.warn("[Open-Meteo] Ambiguous location '{}', elapsed={}ms",
                        requestedLocation, System.currentTimeMillis() - start);
                throw new WeatherException(WeatherError.LOCATION_AMBIGUOUS,
                        "城市「" + requestedLocation + "」不明确，请补充省份或国家");
            }
        }

        log.debug("[Open-Meteo] Location resolved: {} -> {}/{}/{}",
                requestedLocation, name, adminArea, country);

        return new WeatherLocation(requestedLocation, name, adminArea, country,
                lat, lon, zoneId);
    }

    // ==================== Forecast fetching ====================

    @Override
    public WeatherSnapshot fetch(WeatherLocation location) {
        long start = System.currentTimeMillis();

        HttpUrl url = requireBaseUrl(properties.getForecastBaseUrl()).newBuilder()
                .addQueryParameter("latitude", String.valueOf(location.latitude()))
                .addQueryParameter("longitude", String.valueOf(location.longitude()))
                .addQueryParameter("current", "temperature_2m,relative_humidity_2m,"
                        + "apparent_temperature,weather_code,wind_speed_10m,wind_direction_10m")
                .addQueryParameter("daily", "weather_code,temperature_2m_max,"
                        + "temperature_2m_min,precipitation_probability_max")
                .addQueryParameter("timezone", location.zoneId().getId())
                .addQueryParameter("forecast_days", "3")
                .addQueryParameter("temperature_unit", "celsius")
                .addQueryParameter("wind_speed_unit", "kmh")
                .build();

        JsonNode root = executeGet(url, "forecast");

        // --- Parse current conditions ---
        JsonNode current = root.get("current");
        if (current == null) {
            throw new WeatherException(WeatherError.PROVIDER_RESPONSE_INVALID,
                    "天气数据缺少当前实况");
        }

        double temperature = doubleField(current, "temperature_2m");
        double apparentTemp = doubleField(current, "apparent_temperature");
        int humidity = intField(current, "relative_humidity_2m");
        double windSpeed = doubleField(current, "wind_speed_10m");
        int windDir = intField(current, "wind_direction_10m");
        int weatherCode = intField(current, "weather_code");

        // Parse observation time in the location's time zone and convert to UTC instant
        String currentTimeStr = stringField(current, "time");
        Instant observedAt;
        try {
            ZonedDateTime zdt = ZonedDateTime.parse(currentTimeStr,
                    java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            observedAt = zdt.toInstant();
        } catch (Exception e) {
            // Fallback: parse as local date-time in the location's zone
            try {
                ZonedDateTime zdt = LocalDate.parse(currentTimeStr)
                        .atStartOfDay(location.zoneId())
                        .withZoneSameInstant(java.time.ZoneOffset.UTC)
                        .toInstant()
                        .atZone(location.zoneId());
                observedAt = zdt.toInstant();
            } catch (Exception e2) {
                observedAt = Instant.now();
            }
        }

        CurrentConditions conditions = new CurrentConditions(
                temperature, apparentTemp, humidity, windSpeed, windDir, weatherCode);

        // --- Parse daily forecasts ---
        JsonNode daily = root.get("daily");
        if (daily == null) {
            throw new WeatherException(WeatherError.PROVIDER_RESPONSE_INVALID,
                    "天气数据缺少每日预报");
        }

        JsonNode timeArray = daily.get("time");
        JsonNode weatherCodeArray = daily.get("weather_code");
        JsonNode maxTempArray = daily.get("temperature_2m_max");
        JsonNode minTempArray = daily.get("temperature_2m_min");
        JsonNode precipProbArray = daily.get("precipitation_probability_max");

        if (timeArray == null || !timeArray.isArray() || timeArray.isEmpty()) {
            throw new WeatherException(WeatherError.PROVIDER_RESPONSE_INVALID,
                    "每日预报缺少日期");
        }

        int count = timeArray.size();
        // Validate all arrays have the same length
        if (weatherCodeArray == null || weatherCodeArray.size() != count
                || maxTempArray == null || maxTempArray.size() != count
                || minTempArray == null || minTempArray.size() != count
                || precipProbArray == null || precipProbArray.size() != count) {
            throw new WeatherException(WeatherError.PROVIDER_RESPONSE_INVALID,
                    "每日预报数据不完整");
        }

        DateTimeFormatter dateFormat = DateTimeFormatter.ISO_LOCAL_DATE;
        List<DailyForecast> forecasts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            LocalDate date = LocalDate.parse(timeArray.get(i).asText(), dateFormat);
            double maxTemp = maxTempArray.get(i).asDouble();
            double minTemp = minTempArray.get(i).asDouble();
            int wc = weatherCodeArray.get(i).asInt();
            int precipProb = precipProbArray.get(i).asInt();
            forecasts.add(new DailyForecast(date, maxTemp, minTemp, wc, precipProb));
        }

        log.debug("[Open-Meteo] Forecast fetched for {}, elapsed={}ms",
                location.name(), System.currentTimeMillis() - start);

        return new WeatherSnapshot(location, observedAt, conditions, forecasts, SOURCE);
    }

    // ==================== HTTP helper ====================

    private JsonNode executeGet(HttpUrl url, String operation) {
        long start = System.currentTimeMillis();
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "xialingying/1.0")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            long elapsed = System.currentTimeMillis() - start;

            if (!response.isSuccessful()) {
                log.warn("[Open-Meteo] {} returned HTTP {}, elapsed={}ms",
                        operation, response.code(), elapsed);
                if (response.code() == 429 || response.code() >= 500) {
                    throw new WeatherException(WeatherError.PROVIDER_UNAVAILABLE,
                            "天气服务暂时不可用");
                }
                throw new WeatherException(WeatherError.PROVIDER_UNAVAILABLE,
                        "天气服务返回错误");
            }

            String body = response.body() != null ? response.body().string() : "";
            log.debug("[Open-Meteo] {} succeeded, elapsed={}ms, bodySize={}",
                    operation, elapsed, body.length());

            return objectMapper.readTree(body);
        } catch (SocketTimeoutException e) {
            log.warn("[Open-Meteo] {} timed out, elapsed={}ms",
                    operation, System.currentTimeMillis() - start);
            throw new WeatherException(WeatherError.PROVIDER_TIMEOUT,
                    "天气服务响应超时", e);
        } catch (WeatherException e) {
            throw e;
        } catch (JsonProcessingException e) {
            log.warn("[Open-Meteo] {} parse error, elapsed={}ms: {}",
                    operation, System.currentTimeMillis() - start, e.getMessage());
            throw new WeatherException(WeatherError.PROVIDER_RESPONSE_INVALID,
                    "天气数据格式异常", e);
        } catch (IOException e) {
            log.warn("[Open-Meteo] {} network error, elapsed={}ms: {}",
                    operation, System.currentTimeMillis() - start, e.getMessage());
            throw new WeatherException(WeatherError.PROVIDER_UNAVAILABLE,
                    "天气服务暂时不可用", e);
        }
    }

    // ==================== JSON helpers ====================

    private String stringField(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value != null && !value.isNull()) ? value.asText() : "";
    }

    private double doubleField(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new WeatherException(WeatherError.PROVIDER_RESPONSE_INVALID,
                    "缺少字段: " + field);
        }
        return value.asDouble();
    }

    private int intField(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new WeatherException(WeatherError.PROVIDER_RESPONSE_INVALID,
                    "缺少字段: " + field);
        }
        return value.asInt();
    }

    private HttpUrl requireBaseUrl(String raw) {
        HttpUrl url = HttpUrl.parse(raw);
        if (url == null) {
            throw new IllegalStateException("Invalid weather base URL: " + raw);
        }
        return url;
    }
}
