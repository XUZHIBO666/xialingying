package com.demo.demo.Service.scheduling.domain;

import com.demo.demo.Service.scheduling.application.SchedulingException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class TaskPayloadCodec {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private TaskPayloadCodec() {}

    public static String writeWeatherPayload(WeatherTaskPayload payload) {
        try { return MAPPER.writeValueAsString(payload); }
        catch (JsonProcessingException e) { throw new SchedulingException("encode failed"); }
    }

    public static WeatherTaskPayload readWeatherPayload(String json) {
        try { return MAPPER.readValue(json, WeatherTaskPayload.class); }
        catch (Exception e) { throw new SchedulingException("Invalid weather payload"); }
    }
}
