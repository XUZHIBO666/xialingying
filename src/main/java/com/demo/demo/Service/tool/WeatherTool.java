package com.demo.demo.Service.tool;

import com.demo.demo.Utils.WeatherUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.springframework.stereotype.Component;

@Component
public class WeatherTool implements Tool {

    @Override
    public String name() {
        return "get_weather";
    }

    @Override
    public String description() {
        return "查询指定城市的实时天气，返回温度、体感温度、天气状况、风向风速和湿度。" +
               "当用户询问天气相关问题时使用此工具。";
    }

    @Override
    public JsonObject parameters() {
        JsonObject props = new JsonObject();
        JsonObject city = new JsonObject();
        city.addProperty("type", "string");
        city.addProperty("description", "城市名称，支持中文/拼音/英文");
        props.add("city", city);

        JsonArray required = new JsonArray();
        required.add("city");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", required);
        return schema;
    }

    @Override
    public String execute(JsonObject arguments) throws Exception {
        String city = arguments.get("city").getAsString();
        return WeatherUtil.getWeather(city);
    }
}
