package com.demo.demo.controller.dto;

import com.demo.demo.Service.weather.WeatherReport;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Per-city result within a batch weather response.
 * On success {@code data} is populated; on failure {@code errorCode}
 * and {@code errorMessage} are populated.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WeatherBatchItem(
        String city,
        boolean success,
        WeatherReport data,
        String errorCode,
        String errorMessage) {}
