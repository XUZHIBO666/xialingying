package com.demo.demo.Service.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 @Tool 注解的方法（Spring AI 方式，替代旧 Tool 接口）。
 */
class ToolRegistryTest {

    @Test
    void weatherToolHasToolAnnotation() throws Exception {
        WeatherTool tool = new WeatherTool();
        // 直接调用方法验证
        java.lang.reflect.Method method = WeatherTool.class.getMethod("getWeather", String.class);
        assertNotNull(method);

        // 验证 @Tool 注解存在
        var toolAnnotation = method.getAnnotation(org.springframework.ai.tool.annotation.Tool.class);
        assertNotNull(toolAnnotation);
        assertTrue(toolAnnotation.description().contains("天气"));
    }

    @Test
    void timeToolReturnsFormattedTime() {
        TimeTool tool = new TimeTool();
        String result = tool.getCurrentTime();
        assertNotNull(result);
        assertTrue(result.contains("年"));
        assertTrue(result.contains("月"));
        assertTrue(result.contains("日"));
    }

    @Test
    void timeToolHasToolAnnotation() throws Exception {
        java.lang.reflect.Method method = TimeTool.class.getMethod("getCurrentTime");
        var toolAnnotation = method.getAnnotation(org.springframework.ai.tool.annotation.Tool.class);
        assertNotNull(toolAnnotation);
        assertTrue(toolAnnotation.description().contains("时间"));
    }

    @Test
    void imageGenerationToolHasToolAnnotation() throws Exception {
        java.lang.reflect.Method method = ImageGenerationTool.class
                .getMethod("generateImage", String.class);
        var toolAnnotation = method.getAnnotation(org.springframework.ai.tool.annotation.Tool.class);
        assertNotNull(toolAnnotation);
        assertTrue(toolAnnotation.description().contains("图片"));
    }
}
