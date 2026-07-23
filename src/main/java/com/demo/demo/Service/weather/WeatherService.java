package com.demo.demo.Service.weather;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * Application service for weather queries.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Resolve a human date expression into a relative or absolute target date
 *       using the resolved location's time zone.</li>
 *   <li>Enforce the three-day range (today, tomorrow, day-after-tomorrow).</li>
 *   <li>Cache resolved locations, current-condition reports, and daily-forecast
 *       reports with bounded size and configurable TTLs.</li>
 *   <li>Orchestrate the provider call — one three-day snapshot and date selection.</li>
 * </ul>
 *
 * <p>This service depends only on {@link WeatherProvider}, never on a concrete
 * provider implementation.
 */
@Slf4j
@Service
public class WeatherService {

    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final WeatherProvider provider;
    private final Clock clock;
    private final WeatherProperties properties;

    private final Cache<String, WeatherLocation> locationCache;
    private final Cache<String, WeatherReport> currentCache;
    private final Cache<String, WeatherReport> forecastCache;

    public WeatherService(WeatherProvider provider, Clock clock, WeatherProperties properties) {
        this.provider = provider;
        this.clock = clock;
        this.properties = properties;

        this.locationCache = Caffeine.newBuilder()
                .maximumSize(properties.getMaxCacheEntries())
                .expireAfterWrite(properties.getLocationCacheTtl())
                .build();
        this.currentCache = Caffeine.newBuilder()
                .maximumSize(properties.getMaxCacheEntries())
                .expireAfterWrite(properties.getCurrentCacheTtl())
                .build();
        this.forecastCache = Caffeine.newBuilder()
                .maximumSize(properties.getMaxCacheEntries())
                .expireAfterWrite(properties.getForecastCacheTtl())
                .build();
    }

    /**
     * Query weather for a given location and optional date expression.
     *
     * @param query the raw query from a Tool or REST adapter
     * @return a structured report with either current conditions or a daily forecast
     */
    public WeatherReport query(WeatherQuery query) {
        // 1. Resolve (or cached) location
        String locationKey = normalizeLocationKey(query.location());
        WeatherLocation location = locationCache.get(locationKey, k -> {
            log.debug("[WeatherService] Resolving location");
            return provider.resolveLocation(query.location());
        });

        // 2. Resolve target date in the location's time zone
        LocalDate targetDate = resolveDate(query.dateExpression(), location.zoneId());

        // 3. Determine report type and build cache key
        LocalDate today = LocalDate.now(clock.withZone(location.zoneId()));
        boolean isCurrent = !targetDate.isAfter(today);

        String cacheKey = buildCacheKey(locationKey, targetDate);

        if (isCurrent) {
            // 4a. Check current cache
            WeatherReport cached = currentCache.getIfPresent(cacheKey);
            if (cached != null) {
                log.debug("[WeatherService] Current cache hit: {}", cacheKey);
                return cached;
            }

            // 5. Fetch one snapshot (contains current + 3-day forecast)
            WeatherSnapshot snapshot = provider.fetch(location);

            // 6. Build current report
            WeatherReport report = new WeatherReport(
                    WeatherReportType.CURRENT,
                    location,
                    targetDate,
                    snapshot.observedAt(),
                    snapshot.current(),
                    null,
                    snapshot.source());

            // 7. Also populate forecast cache entries from the same snapshot
            // so a follow-up "那明天呢？" hits the cache
            for (DailyForecast df : snapshot.dailyForecasts()) {
                if (df.date().isAfter(today)) {
                    String fcKey = buildCacheKey(locationKey, df.date());
                    WeatherReport fcReport = new WeatherReport(
                            WeatherReportType.FORECAST,
                            location,
                            df.date(),
                            snapshot.observedAt(),
                            null,
                            df,
                            snapshot.source());
                    forecastCache.put(fcKey, fcReport);
                }
            }

            currentCache.put(cacheKey, report);
            return report;
        } else {
            // 4b. Check forecast cache
            WeatherReport cached = forecastCache.getIfPresent(cacheKey);
            if (cached != null) {
                log.debug("[WeatherService] Forecast cache hit: {}", cacheKey);
                return cached;
            }

            // 5. Fetch one snapshot
            WeatherSnapshot snapshot = provider.fetch(location);

            // 6. Select matching daily forecast
            DailyForecast matching = null;
            for (DailyForecast df : snapshot.dailyForecasts()) {
                if (df.date().equals(targetDate)) {
                    matching = df;
                    break;
                }
            }

            if (matching == null) {
                throw new WeatherException(WeatherError.PROVIDER_RESPONSE_INVALID,
                        "天气数据中未找到" + targetDate + "的预报");
            }

            // 7. Build forecast report
            WeatherReport report = new WeatherReport(
                    WeatherReportType.FORECAST,
                    location,
                    targetDate,
                    snapshot.observedAt(),
                    null,
                    matching,
                    snapshot.source());

            // Also populate current cache and other forecast entries
            WeatherReport currentReport = new WeatherReport(
                    WeatherReportType.CURRENT,
                    location,
                    today,
                    snapshot.observedAt(),
                    snapshot.current(),
                    null,
                    snapshot.source());
            currentCache.put(buildCacheKey(locationKey, today), currentReport);

            for (DailyForecast df : snapshot.dailyForecasts()) {
                if (!df.date().equals(targetDate)) {
                    String fcKey = buildCacheKey(locationKey, df.date());
                    WeatherReport fcReport = new WeatherReport(
                            WeatherReportType.FORECAST,
                            location,
                            df.date(),
                            snapshot.observedAt(),
                            null,
                            df,
                            snapshot.source());
                    forecastCache.put(fcKey, fcReport);
                }
            }

            forecastCache.put(cacheKey, report);
            return report;
        }
    }

    // ==================== Date resolution ====================

    /**
     * Resolve a user-supplied date expression to a concrete {@link LocalDate}
     * using the target location's time zone.
     *
     * <p>Supported expressions: empty, 今天, 明天, 后天, yyyy-MM-dd.
     * Dates before today or more than 2 days in the future are rejected.
     */
    LocalDate resolveDate(String expression, ZoneId zoneId) {
        LocalDate today = LocalDate.now(clock.withZone(zoneId));

        if (expression == null || expression.isBlank() || "今天".equals(expression)) {
            return today;
        }

        LocalDate resolved = switch (expression) {
            case "明天" -> today.plusDays(1);
            case "后天" -> today.plusDays(2);
            default -> {
                try {
                    yield LocalDate.parse(expression, ISO_DATE);
                } catch (DateTimeParseException e) {
                    throw new WeatherException(WeatherError.INVALID_DATE,
                            "日期格式无效: " + expression + "，请使用 yyyy-MM-dd 或 [今天/明天/后天]");
                }
            }
        };

        // Reject past dates
        if (resolved.isBefore(today)) {
            throw new WeatherException(WeatherError.INVALID_DATE,
                    "不能查询过去的日期: " + resolved);
        }

        // Reject dates beyond day-after-tomorrow
        if (resolved.isAfter(today.plusDays(2))) {
            throw new WeatherException(WeatherError.INVALID_DATE,
                    "仅支持查询今天至后天的天气");
        }

        return resolved;
    }

    // ==================== Cache helpers ====================

    private String normalizeLocationKey(String location) {
        return location.trim().toLowerCase(Locale.ROOT);
    }

    private String buildCacheKey(String normalizedLocation, LocalDate date) {
        return normalizedLocation + "|" + date.format(ISO_DATE);
    }
}
