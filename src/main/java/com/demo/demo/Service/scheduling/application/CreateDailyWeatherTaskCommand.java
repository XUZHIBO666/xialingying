package com.demo.demo.Service.scheduling.application;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;

/**
 * Command to create a daily weather scheduled task.
 * All fields come from the trusted service layer, NOT from model output directly.
 */
public record CreateDailyWeatherTaskCommand(
        String ownerTargetId,
        String location,
        LocalTime localTime,
        ZoneId zoneId) {

    public CreateDailyWeatherTaskCommand {
        Objects.requireNonNull(ownerTargetId, "ownerTargetId must not be null");
        Objects.requireNonNull(location, "location must not be null");
        Objects.requireNonNull(localTime, "localTime must not be null");
        Objects.requireNonNull(zoneId, "zoneId must not be null");
        if (ownerTargetId.isBlank()) {
            throw new IllegalArgumentException("ownerTargetId must not be blank");
        }
        if (location.isBlank()) {
            throw new IllegalArgumentException("location must not be blank");
        }
    }
}
