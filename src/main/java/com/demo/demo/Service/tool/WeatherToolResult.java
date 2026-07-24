package com.demo.demo.Service.tool;

import com.demo.demo.Service.weather.WeatherReport;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Structured result returned by {@link WeatherTool} to the LLM.
 *
 * <p>The {@link Status} enum provides a machine-readable contract so the
 * LLM can branch on failure categories without parsing natural-language
 * error strings. On success the raw {@link WeatherReport} data is included
 * for the LLM to construct a natural-language reply.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WeatherToolResult(Status status, String message, WeatherReport data) {

    public enum Status {
        SUCCESS,
        LOCATION_REQUIRED,
        LOCATION_AMBIGUOUS,
        LOCATION_NOT_FOUND,
        INVALID_DATE,
        PROVIDER_TIMEOUT,
        PROVIDER_UNAVAILABLE
    }

    public static WeatherToolResult success(WeatherReport data) {
        return new WeatherToolResult(Status.SUCCESS, "天气查询成功", data);
    }

    public static WeatherToolResult failure(Status status, String message) {
        return new WeatherToolResult(status, message, null);
    }
}
