package com.demo.demo.Service.scheduling.execution;

import com.demo.demo.Service.weather.*;
import org.springframework.stereotype.Component;

/**
 * Deterministic fallback formatter: produces human-readable weather text
 * directly from a {@link WeatherReport}, without involving an LLM.
 * Used when the content agent fails, times out, or returns empty output.
 */
@Component
public class WeatherMessageTemplateFormatter {

    public String format(WeatherReport report) {
        StringBuilder sb = new StringBuilder();
        String locationName = report.location().adminArea() != null
                ? report.location().name() + "," + report.location().adminArea()
                : report.location().name();
        sb.append(locationName).append("天气预报：\n");

        if (report.current() != null) {
            sb.append("当前")
                    .append(formatTemp(report.current().temperatureCelsius()))
                    .append("，").append(weatherDesc(report.current().weatherCode()))
                    .append("。\n");
        }

        if (report.forecast() != null) {
            var f = report.forecast();
            sb.append(f.date()).append("：")
                    .append(weatherDesc(f.weatherCode())).append("，")
                    .append(formatTemp(f.minTemperatureCelsius())).append(" ~ ")
                    .append(formatTemp(f.maxTemperatureCelsius()));
            if (f.precipitationProbability() > 0) {
                sb.append("，降水概率").append(f.precipitationProbability()).append("%");
            }
            sb.append("\n");
        }

        sb.append("（自动推送）");
        return sb.toString();
    }

    private static String formatTemp(double celsius) {
        return String.format("%.0f°C", celsius);
    }

    private static String weatherDesc(int code) {
        // WMO weather codes (simplified mapping for Open-Meteo)
        return switch (code) {
            case 0 -> "晴";
            case 1, 2, 3 -> "多云";
            case 45, 48 -> "雾";
            case 51, 53, 55 -> "小雨";
            case 56, 57 -> "冻雨";
            case 61, 63, 65 -> "雨";
            case 66, 67 -> "冻雨";
            case 71, 73, 75 -> "雪";
            case 77 -> "雪粒";
            case 80, 81, 82 -> "阵雨";
            case 85, 86 -> "阵雪";
            case 95 -> "雷暴";
            case 96, 99 -> "雷暴伴冰雹";
            default -> "天气代码" + code;
        };
    }
}
