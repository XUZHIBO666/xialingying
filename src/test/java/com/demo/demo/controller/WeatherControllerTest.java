package com.demo.demo.controller;

import com.demo.demo.Service.weather.*;
import com.demo.demo.controller.dto.WeatherBatchRequest;
import com.demo.demo.execption.GlobalExpectionHandler;
import com.demo.demo.execption.ResponseCodeEnum;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WeatherControllerTest {

    private MockMvc mockMvc;
    private WeatherService weatherService;
    private WeatherProperties weatherProperties;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        weatherService = mock(WeatherService.class);
        weatherProperties = new WeatherProperties();
        weatherProperties.setBatchLimit(10);
        objectMapper = new ObjectMapper();

        WeatherController controller = new WeatherController(weatherService, weatherProperties);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExpectionHandler())
                .build();
    }

    // ==================== Single query ====================

    @Test
    @DisplayName("GET /api/weather?city=杭州&date=明天 returns structured report")
    void getWeatherReturnsStructuredReport() throws Exception {
        WeatherReport report = currentReport();
        when(weatherService.query(any())).thenReturn(report);

        mockMvc.perform(get("/api/weather")
                        .param("city", "杭州")
                        .param("date", "明天"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.type").value("CURRENT"))
                .andExpect(jsonPath("$.data.location.name").value("Hangzhou"))
                .andExpect(jsonPath("$.data.targetDate").value("2026-07-23"))
                .andExpect(jsonPath("$.data.source").value("open-meteo"));

        verify(weatherService).query(new WeatherQuery("杭州", "明天"));
    }

    @Test
    @DisplayName("GET /api/weather/杭州 returns structured report")
    void getWeatherByPath() throws Exception {
        WeatherReport report = currentReport();
        when(weatherService.query(any())).thenReturn(report);

        mockMvc.perform(get("/api/weather/{city}", "杭州"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.location.name").value("Hangzhou"));

        verify(weatherService).query(new WeatherQuery("杭州", ""));
    }

    @Test
    @DisplayName("GET /api/weather without city is rejected")
    void missingCityRejected() throws Exception {
        mockMvc.perform(get("/api/weather"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40001"));
    }

    // ==================== Batch query ====================

    @Test
    @DisplayName("POST /api/weather/batch returns mixed success/failure")
    void batchQueryMixedResults() throws Exception {
        WeatherReport report = currentReport();
        when(weatherService.query(new WeatherQuery("杭州", ""))).thenReturn(report);
        when(weatherService.query(new WeatherQuery("北京", "")))
                .thenThrow(new WeatherException(WeatherError.LOCATION_NOT_FOUND, "未找到"));

        WeatherBatchRequest req = new WeatherBatchRequest(List.of("杭州", "北京"), null);
        mockMvc.perform(post("/api/weather/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.successCount").value(1))
                .andExpect(jsonPath("$.data.failureCount").value(1))
                .andExpect(jsonPath("$.data.items[0].success").value(true))
                .andExpect(jsonPath("$.data.items[1].success").value(false))
                .andExpect(jsonPath("$.data.items[1].errorCode").value("40004"));
    }

    @Test
    @DisplayName("POST batch with 11 cities is rejected")
    void batchSizeExceedsLimit() throws Exception {
        List<String> eleven = List.of("1","2","3","4","5","6","7","8","9","10","11");
        WeatherBatchRequest req = new WeatherBatchRequest(eleven, null);

        mockMvc.perform(post("/api/weather/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("40002"));
    }

    // ==================== Exception mapping ====================

    @Test
    @DisplayName("LOCATION_NOT_FOUND maps to CITY_NOT_FOUND")
    void locationNotFoundMapping() throws Exception {
        when(weatherService.query(any()))
                .thenThrow(new WeatherException(WeatherError.LOCATION_NOT_FOUND, "未找到"));

        mockMvc.perform(get("/api/weather").param("city", "火星"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("40004"));
    }

    @Test
    @DisplayName("INVALID_DATE maps to WEATHER_DATE_INVALID")
    void invalidDateMapping() throws Exception {
        when(weatherService.query(any()))
                .thenThrow(new WeatherException(WeatherError.INVALID_DATE, "超出范围"));

        mockMvc.perform(get("/api/weather").param("city", "杭州").param("date", "2026-08-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("40007"));
    }

    @Test
    @DisplayName("PROVIDER_TIMEOUT maps to THIRD_PARTY_TIMEOUT")
    void timeoutMapping() throws Exception {
        when(weatherService.query(any()))
                .thenThrow(new WeatherException(WeatherError.PROVIDER_TIMEOUT, "超时"));

        mockMvc.perform(get("/api/weather").param("city", "杭州"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("50301"));
    }

    // ==================== Helper ====================

    private static WeatherReport currentReport() {
        return new WeatherReport(
                WeatherReportType.CURRENT,
                new WeatherLocation("杭州", "Hangzhou", "浙江", "中国",
                        30.2741, 120.1551, ZoneId.of("Asia/Shanghai")),
                LocalDate.of(2026, 7, 23),
                Instant.now(),
                new CurrentConditions(33.0, 37.0, 60, 12.0, 135, 2),
                null,
                "open-meteo");
    }
}
