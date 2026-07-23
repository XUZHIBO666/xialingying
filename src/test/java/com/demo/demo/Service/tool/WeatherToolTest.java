package com.demo.demo.Service.tool;

import com.demo.demo.Service.weather.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WeatherToolTest {

    private WeatherService service;
    private WeatherTool tool;

    @BeforeEach
    void setUp() {
        service = mock(WeatherService.class);
        tool = new WeatherTool(service);
    }

    // ==================== Success ====================

    @Test
    @DisplayName("successful query returns SUCCESS with report data")
    void successfulQuery() {
        WeatherReport report = new WeatherReport(
                WeatherReportType.CURRENT,
                new WeatherLocation("杭州", "Hangzhou", "浙江", "中国",
                        30.0, 120.0, java.time.ZoneId.of("Asia/Shanghai")),
                LocalDate.of(2026, 7, 23),
                java.time.Instant.now(),
                new CurrentConditions(33.0, 37.0, 60, 12.0, 135, 2),
                null,
                "open-meteo");

        when(service.query(any())).thenReturn(report);

        WeatherToolResult result = tool.queryWeather("杭州", "明天");

        assertEquals(WeatherToolResult.Status.SUCCESS, result.status());
        assertSame(report, result.data());
        verify(service).query(new WeatherQuery("杭州", "明天"));
    }

    @Test
    @DisplayName("query without date delegates to service with empty date")
    void queryWithoutDate() {
        WeatherReport report = mock(WeatherReport.class);
        when(service.query(any())).thenReturn(report);

        tool.queryWeather("北京", null);

        verify(service).query(new WeatherQuery("北京", ""));
    }

    // ==================== Error mapping ====================

    static Stream<Arguments> exceptionMappingProvider() {
        return Stream.of(
                Arguments.of(WeatherError.LOCATION_REQUIRED,
                        WeatherToolResult.Status.LOCATION_REQUIRED),
                Arguments.of(WeatherError.LOCATION_AMBIGUOUS,
                        WeatherToolResult.Status.LOCATION_AMBIGUOUS),
                Arguments.of(WeatherError.LOCATION_NOT_FOUND,
                        WeatherToolResult.Status.LOCATION_NOT_FOUND),
                Arguments.of(WeatherError.INVALID_DATE,
                        WeatherToolResult.Status.INVALID_DATE),
                Arguments.of(WeatherError.PROVIDER_TIMEOUT,
                        WeatherToolResult.Status.PROVIDER_TIMEOUT),
                Arguments.of(WeatherError.PROVIDER_UNAVAILABLE,
                        WeatherToolResult.Status.PROVIDER_UNAVAILABLE),
                Arguments.of(WeatherError.PROVIDER_RESPONSE_INVALID,
                        WeatherToolResult.Status.PROVIDER_UNAVAILABLE)
        );
    }

    @ParameterizedTest
    @MethodSource("exceptionMappingProvider")
    @DisplayName("WeatherError maps to correct Tool status")
    void exceptionMapsToStatus(WeatherError error, WeatherToolResult.Status expectedStatus) {
        when(service.query(any())).thenThrow(new WeatherException(error, "test error"));

        WeatherToolResult result = tool.queryWeather("杭州", "");
        assertEquals(expectedStatus, result.status());
        assertNull(result.data());
    }

    // ==================== Tool annotation ====================

    @Test
    @DisplayName("queryWeather has @Tool annotation with correct parameters")
    void hasToolAnnotation() throws Exception {
        Method method = WeatherTool.class.getMethod("queryWeather", String.class, String.class);
        Tool toolAnnotation = method.getAnnotation(Tool.class);
        assertNotNull(toolAnnotation, "queryWeather must have @Tool annotation");
        assertTrue(toolAnnotation.description().contains("天气"),
                "Tool description must mention 天气");

        // Verify location parameter (required)
        ToolParam locationParam = method.getParameters()[0].getAnnotation(ToolParam.class);
        assertNotNull(locationParam, "location must have @ToolParam");

        // Verify date parameter (optional)
        ToolParam dateParam = method.getParameters()[1].getAnnotation(ToolParam.class);
        assertNotNull(dateParam, "date must have @ToolParam");
        assertFalse(dateParam.required(), "date must be optional");
    }
}
