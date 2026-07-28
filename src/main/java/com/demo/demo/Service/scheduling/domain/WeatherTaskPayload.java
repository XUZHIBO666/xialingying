package com.demo.demo.Service.scheduling.domain;

public record WeatherTaskPayload(String location) {
    public WeatherTaskPayload {
        if (location == null || location.isBlank())
            throw new IllegalArgumentException("location required");
    }
}
