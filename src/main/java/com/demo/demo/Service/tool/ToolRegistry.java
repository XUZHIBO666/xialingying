package com.demo.demo.Service.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册中心——通过 Spring 自动发现所有 {@link Tool} Bean。
 */
@Component
public class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    /** Spring 注入所有 Tool 实现。 */
    public ToolRegistry(List<Tool> toolBeans) {
        for (Tool tool : toolBeans) {
            tools.put(tool.name(), tool);
        }
    }

    /** 所有已注册工具的不可变列表。 */
    public List<Tool> getAll() {
        return List.copyOf(tools.values());
    }

    /** 按名称查找工具。 */
    public Tool get(String name) {
        return tools.get(name);
    }

    /** 是否为空（没有注册任何工具）。 */
    public boolean isEmpty() {
        return tools.isEmpty();
    }

    /**
     * 构建发送给 LLM 的 tools 数组（OpenAI 兼容格式）。
     */
    public JsonArray toOpenAiTools() {
        JsonArray array = new JsonArray();
        for (Tool tool : tools.values()) {
            JsonObject wrapper = new JsonObject();
            wrapper.addProperty("type", "function");

            JsonObject function = new JsonObject();
            function.addProperty("name", tool.name());
            function.addProperty("description", tool.description());
            function.add("parameters", tool.parameters());
            wrapper.add("function", function);

            array.add(wrapper);
        }
        return array;
    }

    /**
     * 执行一轮工具调用，返回结果文本数组（供继续对话使用）。
     */
    public JsonArray executeTools(JsonArray toolCalls) {
        JsonArray results = new JsonArray();
        for (int i = 0; i < toolCalls.size(); i++) {
            JsonObject call = toolCalls.get(i).getAsJsonObject();
            String callId = call.has("id") ? call.get("id").getAsString() : String.valueOf(i);
            String name = call.getAsJsonObject("function").get("name").getAsString();
            JsonObject args = JsonParser.parseString(
                    call.getAsJsonObject("function").get("arguments").getAsString())
                    .getAsJsonObject();

            Tool tool = tools.get(name);
            String result;
            if (tool == null) {
                result = "未知工具: " + name;
            } else {
                try {
                    result = tool.execute(args);
                } catch (Exception e) {
                    result = "工具执行失败: " + e.getMessage();
                }
            }

            JsonObject toolResult = new JsonObject();
            toolResult.addProperty("role", "tool");
            toolResult.addProperty("tool_call_id", callId);
            toolResult.addProperty("content", result);
            results.add(toolResult);
        }
        return results;
    }
}
