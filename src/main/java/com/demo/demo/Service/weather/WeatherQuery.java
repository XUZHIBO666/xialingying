package com.demo.demo.Service.weather;

/**
 * Raw location and optional date expression from the caller (Tool or REST).
 * Validation happens at construction time so invalid queries fail fast.
 */
public record WeatherQuery(String location, String dateExpression) {
    public WeatherQuery {
        if (location == null || location.isBlank()) {
            throw new WeatherException(WeatherError.LOCATION_REQUIRED, "请提供要查询的城市");
        }
        location = location.trim();
        dateExpression = dateExpression == null ? "" : dateExpression.trim();
    }
}
