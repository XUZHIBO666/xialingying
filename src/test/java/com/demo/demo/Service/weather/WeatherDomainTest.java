package com.demo.demo.Service.weather;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WeatherDomainTest {

    @Test
    void queryRejectsBlankLocation() {
        WeatherException error = assertThrows(
                WeatherException.class,
                () -> new WeatherQuery(" ", "明天"));
        assertEquals(WeatherError.LOCATION_REQUIRED, error.getError());
    }

    @Test
    void locationKeepsProviderTimeZone() {
        WeatherLocation location = new WeatherLocation(
                "杭州", "Hangzhou", "浙江", "中国",
                30.2741, 120.1551, ZoneId.of("Asia/Shanghai"));
        assertEquals("Asia/Shanghai", location.zoneId().getId());
    }

    @Test
    void dailyForecastKeepsTypedUnits() {
        DailyForecast forecast = new DailyForecast(
                LocalDate.of(2026, 7, 24), 31.5, 24.2, 61, 70);
        assertEquals(70, forecast.precipitationProbability());
    }
}
