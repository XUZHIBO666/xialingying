package com.demo.demo.Service.scheduling.execution;

import com.demo.demo.Service.weather.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

class WeatherMessageTemplateFormatterTest {

    private final WeatherMessageTemplateFormatter formatter = new WeatherMessageTemplateFormatter();
    private static final ZoneId UTC8 = ZoneId.of("Asia/Shanghai");

    @Test
    void shouldFormatCurrentWeatherWithLocation() {
        WeatherReport report = new WeatherReport(
                WeatherReportType.CURRENT,
                new WeatherLocation("杭州", "杭州", "浙江", "中国", 30.25, 120.16, UTC8),
                LocalDate.now(),
                Instant.now(),
                new CurrentConditions(22.5, 21.0, 65, 12.0, 180, 1),
                null, "test");

        String result = formatter.format(report);
        assertTrue(result.contains("杭州"), "should contain city name");
        assertTrue(result.contains("23°C"), "should contain formatted temperature");
        assertTrue(result.contains("自动推送"), "should contain auto-push marker");
    }

    @Test
    void shouldFormatForecastWithMinMax() {
        WeatherReport report = new WeatherReport(
                WeatherReportType.FORECAST,
                new WeatherLocation("北京", "北京", "北京", "中国", 39.9, 116.4, UTC8),
                LocalDate.now().plusDays(1),
                Instant.now(),
                null,
                new DailyForecast(LocalDate.now().plusDays(1), 18.0, 5.0, 61, 30),
                "test");

        String result = formatter.format(report);
        assertTrue(result.contains("北京"));
        assertTrue(result.contains("5°C") && result.contains("18°C"), "should show min~max range");
        assertTrue(result.contains("降水概率30%"), "should show precipitation probability");
    }

    @Test
    void shouldHandleZeroPrecipitation() {
        WeatherReport report = new WeatherReport(
                WeatherReportType.FORECAST,
                new WeatherLocation("上海", "上海", null, "中国", 31.2, 121.5, UTC8),
                LocalDate.now().plusDays(1),
                Instant.now(),
                null,
                new DailyForecast(LocalDate.now().plusDays(1), 25.0, 15.0, 0, 0),
                "test");

        String result = formatter.format(report);
        assertFalse(result.contains("降水概率"), "0% precipitation should be omitted");
    }

    @Test
    void shouldMapWeatherCodesCorrectly() {
        WeatherReport report = new WeatherReport(
                WeatherReportType.FORECAST,
                new WeatherLocation("test", "test", null, null, 0, 0, UTC8),
                LocalDate.now(),
                Instant.now(),
                null,
                new DailyForecast(LocalDate.now(), 20.0, 10.0, 61, 0),
                "test");

        String result = formatter.format(report);
        assertTrue(result.contains("雨"), "WMO code 61 should map to 雨");
    }

    @Test
    void shouldProduceDeterministicOutput() {
        WeatherReport report = new WeatherReport(
                WeatherReportType.CURRENT,
                new WeatherLocation("杭州", "杭州", null, null, 30.25, 120.16, UTC8),
                LocalDate.now(),
                Instant.now(),
                new CurrentConditions(20.0, 19.0, 50, 5.0, 90, 0),
                null, "test");

        String r1 = formatter.format(report);
        String r2 = formatter.format(report);
        assertEquals(r1, r2, "template output must be deterministic");
    }
}
