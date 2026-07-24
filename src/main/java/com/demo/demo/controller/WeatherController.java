package com.demo.demo.controller;

import com.demo.demo.Service.weather.WeatherException;
import com.demo.demo.Service.weather.WeatherProperties;
import com.demo.demo.Service.weather.WeatherQuery;
import com.demo.demo.Service.weather.WeatherReport;
import com.demo.demo.Service.weather.WeatherService;
import com.demo.demo.Utils.Response;
import com.demo.demo.controller.dto.WeatherBatchItem;
import com.demo.demo.controller.dto.WeatherBatchRequest;
import com.demo.demo.controller.dto.WeatherBatchResponse;
import com.demo.demo.execption.BizException;
import com.demo.demo.execption.ResponseCodeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Weather REST API backed by {@link WeatherService}.
 * Supports single-city queries (GET) and batch queries (POST).
 */
@Slf4j
@RestController
@RequestMapping("/api/weather")
public class WeatherController {

    private final WeatherService weatherService;
    private final WeatherProperties weatherProperties;

    public WeatherController(WeatherService weatherService, WeatherProperties weatherProperties) {
        this.weatherService = weatherService;
        this.weatherProperties = weatherProperties;
    }

    /**
     * Single-city weather query.
     *
     * GET /api/weather?city=杭州
     * GET /api/weather?city=杭州&date=明天
     */
    @GetMapping
    public Response<WeatherReport> getWeather(
            @RequestParam String city,
            @RequestParam(required = false, defaultValue = "") String date) {
        log.info("[WeatherController] Single query: city={}", city);
        WeatherReport report = weatherService.query(new WeatherQuery(city, date));
        return Response.success(report);
    }

    /**
     * Path-parameter style single-city query.
     *
     * GET /api/weather/杭州?date=明天
     */
    @GetMapping("/{city}")
    public Response<WeatherReport> getWeatherByPath(
            @PathVariable String city,
            @RequestParam(required = false, defaultValue = "") String date) {
        log.info("[WeatherController] Path query: city={}", city);
        return getWeather(city, date);
    }

    /**
     * Batch multi-city weather query.
     *
     * POST /api/weather/batch
     * Body: {"cities": ["杭州", "北京"], "date": "明天"}
     */
    @PostMapping("/batch")
    public Response<WeatherBatchResponse> batchQuery(@RequestBody WeatherBatchRequest request) {
        List<String> cities = request.cities();
        if (cities == null || cities.isEmpty()) {
            throw new BizException(ResponseCodeEnum.PARAM_EMPTY, "城市列表不能为空");
        }
        if (cities.size() > weatherProperties.getBatchLimit()) {
            throw new BizException(ResponseCodeEnum.PARAM_INVALID,
                    "批量查询最多支持" + weatherProperties.getBatchLimit() + "个城市");
        }

        String date = request.date() != null ? request.date() : "";
        long start = System.currentTimeMillis();
        log.info("[WeatherController] Batch query: count={}", cities.size());

        List<WeatherBatchItem> items = new ArrayList<>();
        for (String city : cities) {
            String trimmed = city != null ? city.trim() : "";
            if (trimmed.isEmpty()) {
                items.add(new WeatherBatchItem(city, false, null,
                        ResponseCodeEnum.CITY_NAME_EMPTY.getCode(),
                        "城市名不能为空"));
                continue;
            }
            try {
                WeatherReport report = weatherService.query(new WeatherQuery(trimmed, date));
                items.add(new WeatherBatchItem(trimmed, true, report, null, null));
            } catch (WeatherException e) {
                log.warn("[WeatherController] Batch item failed: city={} error={}",
                        trimmed, e.getError());
                items.add(new WeatherBatchItem(trimmed, false, null,
                        weatherErrorToCode(e.getError()), e.getMessage()));
            }
        }

        long successCount = items.stream().filter(WeatherBatchItem::success).count();
        long failureCount = items.size() - successCount;

        log.info("[WeatherController] Batch complete: success={} failure={} elapsed={}ms",
                successCount, failureCount, System.currentTimeMillis() - start);

        WeatherBatchResponse response = new WeatherBatchResponse(
                cities.size(), (int) successCount, (int) failureCount, items);
        return Response.success(response);
    }

    private static String weatherErrorToCode(com.demo.demo.Service.weather.WeatherError error) {
        return switch (error) {
            case LOCATION_REQUIRED -> ResponseCodeEnum.CITY_NAME_EMPTY.getCode();
            case LOCATION_AMBIGUOUS -> ResponseCodeEnum.CITY_AMBIGUOUS.getCode();
            case LOCATION_NOT_FOUND -> ResponseCodeEnum.CITY_NOT_FOUND.getCode();
            case INVALID_DATE -> ResponseCodeEnum.WEATHER_DATE_INVALID.getCode();
            case PROVIDER_TIMEOUT -> ResponseCodeEnum.THIRD_PARTY_TIMEOUT.getCode();
            case PROVIDER_UNAVAILABLE -> ResponseCodeEnum.THIRD_PARTY_UNAVAILABLE.getCode();
            case PROVIDER_RESPONSE_INVALID -> ResponseCodeEnum.WEATHER_PARSE_ERROR.getCode();
        };
    }
}
