package com.demo.demo.Service.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolRegistryTest {

    @Test
    void discoversAllToolBeans() {
        ToolRegistry registry = new ToolRegistry(
                List.of(new WeatherTool(), new TimeTool()));

        assertEquals(2, registry.getAll().size());
        assertFalse(registry.isEmpty());
    }

    @Test
    void getByNameReturnsCorrectTool() {
        ToolRegistry registry = new ToolRegistry(List.of(new WeatherTool()));

        Tool tool = registry.get("get_weather");
        assertNotNull(tool);
        assertEquals("get_weather", tool.name());
    }

    @Test
    void emptyRegistryIsEmpty() {
        ToolRegistry registry = new ToolRegistry(List.of());
        assertTrue(registry.isEmpty());
    }

    @Test
    void toOpenAiToolsGeneratesCorrectFormat() {
        ToolRegistry registry = new ToolRegistry(List.of(new TimeTool()));

        JsonArray tools = registry.toOpenAiTools();
        assertEquals(1, tools.size());

        JsonObject first = tools.get(0).getAsJsonObject();
        assertEquals("function", first.get("type").getAsString());

        JsonObject func = first.getAsJsonObject("function");
        assertEquals("get_current_time", func.get("name").getAsString());
        assertTrue(func.get("description").getAsString().length() > 0);
    }

    @Test
    void executeTimeToolReturnsFormattedTime() throws Exception {
        ToolRegistry registry = new ToolRegistry(List.of(new TimeTool()));
        String result = registry.get("get_current_time").execute(new JsonObject());
        assertNotNull(result);
        assertTrue(result.contains("年"));
        assertTrue(result.contains("月"));
        assertTrue(result.contains("日"));
    }
}
