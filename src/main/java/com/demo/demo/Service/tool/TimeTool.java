package com.demo.demo.Service.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class TimeTool implements Tool {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss");

    @Override
    public String name() {
        return "get_current_time";
    }

    @Override
    public String description() {
        return "获取当前的精确日期和时间。当用户询问现在几点、今天几号、" +
               "当前时间、日期时使用此工具。";
    }

    @Override
    public JsonObject parameters() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        schema.add("required", new JsonArray());
        return schema;
    }

    @Override
    public String execute(JsonObject arguments) {
        return LocalDateTime.now().format(FORMATTER);
    }
}
