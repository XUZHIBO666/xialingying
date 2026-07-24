package com.demo.demo.Service.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that Spring AI @Tool annotations are correctly present on tool methods.
 */
class ToolRegistryTest {

    @Test
    void weatherToolHasToolAnnotation() throws Exception {
        java.lang.reflect.Method method = WeatherTool.class
                .getMethod("queryWeather", String.class, String.class);
        assertNotNull(method);

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
