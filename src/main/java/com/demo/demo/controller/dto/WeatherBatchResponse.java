package com.demo.demo.controller.dto;

import java.util.List;

/**
 * Typed response for batch weather queries.
 */
public record WeatherBatchResponse(
        int total,
        int successCount,
        int failureCount,
        List<WeatherBatchItem> items) {
    public WeatherBatchResponse {
        items = List.copyOf(items);
    }
}
