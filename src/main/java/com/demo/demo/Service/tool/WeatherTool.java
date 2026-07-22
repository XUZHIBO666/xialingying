package com.demo.demo.Service.tool;

import com.demo.demo.Utils.WeatherUtil;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 天气查询工具 — 使用 Spring AI @Tool 注解，LLM 自动识别并调用。
 */
@Component
public class WeatherTool {

    @Tool(description = "查询指定城市的实时天气，返回温度、体感温度、天气状况、风向风速和湿度。" +
                        "当用户询问天气相关问题时使用此工具。")
    public String getWeather(
            @ToolParam(description = "城市名称，支持中文/拼音/英文") String city) {
        try {
            return WeatherUtil.getWeather(city);
        } catch (Exception e) {
            return "天气查询失败: " + e.getMessage();
        }
    }
}
