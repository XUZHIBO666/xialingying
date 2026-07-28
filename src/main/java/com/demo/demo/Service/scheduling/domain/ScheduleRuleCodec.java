package com.demo.demo.Service.scheduling.domain;

import com.demo.demo.Service.scheduling.application.SchedulingException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * JSON codec for {@link ScheduleRule}. Stable format —
 * does not expose internal class names or raw user input.
 */
public final class ScheduleRuleCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ScheduleRuleCodec() {}

    public static String write(ScheduleRule rule) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("kind", rule.kind().name());

        switch (rule.kind()) {
            case ONCE -> map.put("onceAt", rule.onceAt().toString());
            case DAILY -> map.put("localTime", rule.localTime().toString());
            case WEEKLY -> {
                map.put("localTime", rule.localTime().toString());
                map.put("daysOfWeek", rule.daysOfWeek().stream()
                        .sorted().map(DayOfWeek::name).toList());
            }
            case MONTHLY -> {
                map.put("localTime", rule.localTime().toString());
                map.put("dayOfMonth", rule.dayOfMonth());
            }
            case INTERVAL -> {
                map.put("intervalValue", rule.intervalValue());
                map.put("intervalUnit", rule.intervalUnit());
            }
        }
        try {
            return MAPPER.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new SchedulingException("Failed to encode schedule rule");
        }
    }

    public static ScheduleRule read(String json) {
        Map<String, Object> map;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = MAPPER.readValue(json, Map.class);
            map = raw;
        } catch (Exception e) {
            throw new SchedulingException("Invalid schedule rule JSON");
        }
        try {
            ScheduleKind kind = ScheduleKind.valueOf((String) map.get("kind"));
            return switch (kind) {
                case ONCE -> ScheduleRule.once(LocalDateTime.parse((String) map.get("onceAt")));
                case DAILY -> ScheduleRule.daily(LocalTime.parse((String) map.get("localTime")));
                case WEEKLY -> {
                    @SuppressWarnings("unchecked")
                    var days = (List<String>) map.get("daysOfWeek");
                    yield ScheduleRule.weekly(
                            days.stream().map(DayOfWeek::valueOf).collect(Collectors.toSet()),
                            LocalTime.parse((String) map.get("localTime")));
                }
                case MONTHLY -> ScheduleRule.monthly(
                        (Integer) map.get("dayOfMonth"),
                        LocalTime.parse((String) map.get("localTime")));
                case INTERVAL -> {
                    String unit = (String) map.get("intervalUnit");
                    int val = (Integer) map.get("intervalValue");
                    yield "HOURS".equals(unit)
                            ? ScheduleRule.intervalHours(val)
                            : ScheduleRule.intervalDays(val);
                }
            };
        } catch (Exception e) {
            throw new SchedulingException("Invalid schedule rule: " + e.getMessage());
        }
    }
}
