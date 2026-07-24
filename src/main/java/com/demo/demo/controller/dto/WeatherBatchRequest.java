package com.demo.demo.controller.dto;

import java.util.List;

/**
 * Typed request body for batch weather queries.
 */
public record WeatherBatchRequest(List<String> cities, String date) {}
