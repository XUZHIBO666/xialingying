package com.demo.demo.Service.tool;

import com.demo.demo.Service.weather.WeatherException;
import com.demo.demo.Service.weather.WeatherQuery;
import com.demo.demo.Service.weather.WeatherReport;
import com.demo.demo.Service.weather.WeatherService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Spring AI Tool adapter for weather queries.
 *
 * <p>This is a thin adapter: it converts Tool-call parameters into a
 * {@link WeatherQuery}, delegates to {@link WeatherService}, and maps
 * the result (or failure) to a structured {@link WeatherToolResult}
 * that the LLM can branch on.
 */
@Component
public class WeatherTool {

    private final WeatherService weatherService;

    public WeatherTool(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    @Tool(description = "查询城市或地区的当前天气及未来三天预报。用户询问温度、冷热、降雨、是否带伞、" +
                        "今天、明天或后天天气时必须使用。缺少地点时不要猜测，应先询问用户。")
    public WeatherToolResult queryWeather(
            @ToolParam(description = "城市或地区名称，例如杭州、浙江杭州、London") String location,
            @ToolParam(description = "可选日期：今天、明天、后天或 yyyy-MM-dd；不填表示当前天气",
                       required = false) String date) {

        String dateExpr = date == null ? "" : date;

        try {
            WeatherReport report = weatherService.query(new WeatherQuery(location, dateExpr));
            return WeatherToolResult.success(report);
        } catch (WeatherException e) {
            WeatherToolResult.Status status = switch (e.getError()) {
                case LOCATION_REQUIRED -> WeatherToolResult.Status.LOCATION_REQUIRED;
                case LOCATION_AMBIGUOUS -> WeatherToolResult.Status.LOCATION_AMBIGUOUS;
                case LOCATION_NOT_FOUND -> WeatherToolResult.Status.LOCATION_NOT_FOUND;
                case INVALID_DATE -> WeatherToolResult.Status.INVALID_DATE;
                case PROVIDER_TIMEOUT -> WeatherToolResult.Status.PROVIDER_TIMEOUT;
                case PROVIDER_UNAVAILABLE,
                     PROVIDER_RESPONSE_INVALID -> WeatherToolResult.Status.PROVIDER_UNAVAILABLE;
            };
            return WeatherToolResult.failure(status, e.getMessage());
        }
    }
    
}
