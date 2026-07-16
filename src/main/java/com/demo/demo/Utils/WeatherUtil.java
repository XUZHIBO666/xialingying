package com.demo.demo.Utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 天气查询工具类
 * 使用 wttr.in 免费开放 API，一个请求完成城市定位+天气查询
 * 无需注册，无需 API Key
 */
public class WeatherUtil {

    // wttr.in 免费天气 API
    // 文档: https://github.com/chubin/wttr.in
    private static final String WTTR_API = "https://wttr.in";

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    // wttr.in 返回英文天气描述 → 中文展示（本地化翻译，非天气代码映射）
    private static final Map<String, String> ZH = Map.ofEntries(
            Map.entry("Sunny", "晴"),
            Map.entry("Clear", "晴"),
            Map.entry("Partly cloudy", "多云"),
            Map.entry("Partly Cloudy", "多云"),
            Map.entry("Cloudy", "阴"),
            Map.entry("Overcast", "阴"),
            Map.entry("Mist", "薄雾"),
            Map.entry("Fog", "雾"),
            Map.entry("Freezing fog", "冻雾"),
            Map.entry("Patchy rain possible", "局部阵雨"),
            Map.entry("Patchy rain nearby", "局部阵雨"),
            Map.entry("Light drizzle", "毛毛雨"),
            Map.entry("Patchy light drizzle", "局部毛毛雨"),
            Map.entry("Light rain", "小雨"),
            Map.entry("Light rain shower", "小阵雨"),
            Map.entry("Moderate rain", "中雨"),
            Map.entry("Moderate rain at times", "间歇中雨"),
            Map.entry("Heavy rain", "大雨"),
            Map.entry("Heavy rain at times", "间歇大雨"),
            Map.entry("Torrential rain shower", "暴雨"),
            Map.entry("Patchy light rain", "局部小雨"),
            Map.entry("Patchy moderate rain", "局部中雨"),
            Map.entry("Patchy heavy rain", "局部大雨"),
            Map.entry("Light sleet", "小雨夹雪"),
            Map.entry("Moderate sleet", "雨夹雪"),
            Map.entry("Light snow", "小雪"),
            Map.entry("Moderate snow", "中雪"),
            Map.entry("Heavy snow", "大雪"),
            Map.entry("Patchy light snow", "局部小雪"),
            Map.entry("Patchy moderate snow", "局部中雪"),
            Map.entry("Blizzard", "暴风雪"),
            Map.entry("Blowing snow", "吹雪"),
            Map.entry("Light snow showers", "小阵雪"),
            Map.entry("Moderate or heavy snow showers", "中到大阵雪"),
            Map.entry("Thunderstorm", "雷阵雨"),
            Map.entry("Thunderstorm nearby", "附近雷阵雨"),
            Map.entry("Thundery outbreaks possible", "可能有雷暴"),
            Map.entry("Ice pellets", "冰粒"),
            Map.entry("Freezing rain", "冻雨"),
            Map.entry("Moderate or heavy freezing rain", "中到强冻雨"),
            Map.entry("Moderate or heavy rain shower", "中到大阵雨"),
            Map.entry("Freezing drizzle", "冻毛毛雨"),
            Map.entry("Heavy freezing drizzle", "强冻毛毛雨")
    );

    // 风向英文缩写 → 中文
    private static final Map<String, String> WIND_ZH = Map.ofEntries(
            Map.entry("N", "北"), Map.entry("NNE", "北东北"), Map.entry("NE", "东北"),
            Map.entry("ENE", "东东北"), Map.entry("E", "东"), Map.entry("ESE", "东东南"),
            Map.entry("SE", "东南"), Map.entry("SSE", "南东南"), Map.entry("S", "南"),
            Map.entry("SSW", "南西南"), Map.entry("SW", "西南"), Map.entry("WSW", "西西南"),
            Map.entry("W", "西"), Map.entry("WNW", "西西北"), Map.entry("NW", "西北"),
            Map.entry("NNW", "北西北")
    );

    /**
     * 根据城市名查询实时天气
     *
     * @param cityName 城市名（中文/拼音/英文均可）
     * @return 格式化天气信息
     * @throws CityNotFoundException 城市不存在
     * @throws Exception             网络异常或 API 错误
     */
    public static String getWeather(String cityName) throws Exception {
        System.out.println("========== 查询天气 ==========");
        System.out.println("目标城市: " + cityName);

        // 调用 wttr.in JSON API，一次请求完成城市解析 + 天气查询
        String url = WTTR_API + "/" + cityName + "?format=j1";
        System.out.println("🌐 请求: " + url);

        String json = httpGet(url);
        JsonObject root = parseJson(json);

        // ── 解析当前天气 ──
        JsonArray conditions = root.getAsJsonArray("current_condition");
        if (conditions == null || conditions.isEmpty()) {
            throw new CityNotFoundException(cityName);
        }
        JsonObject current = conditions.get(0).getAsJsonObject();

        // 直接从 API 读取天气描述，trim 去除多余空格
        String weatherEn = current.getAsJsonArray("weatherDesc")
                .get(0).getAsJsonObject()
                .get("value").getAsString().trim();
        // 本地化翻译（大小写不敏感匹配）
        String weatherDesc = translateWeather(weatherEn);

        String tempC      = current.get("temp_C").getAsString();
        String feelsLikeC = current.get("FeelsLikeC").getAsString();
        String humidity   = current.get("humidity").getAsString();
        String windSpeed  = current.get("windspeedKmph").getAsString();
        String windDirEn  = current.get("winddir16Point").getAsString();
        String windDir    = WIND_ZH.getOrDefault(windDirEn, windDirEn);

        // ── 解析城市信息 ──
        JsonArray areas = root.getAsJsonArray("nearest_area");
        String country = "";
        if (areas != null && !areas.isEmpty()) {
            JsonObject area = areas.get(0).getAsJsonObject();
            country = area.getAsJsonArray("country")
                    .get(0).getAsJsonObject()
                    .get("value").getAsString();
        }

        System.out.println("📍 匹配结果: " + cityName + ", " + country);
        System.out.println("==============================");

        return String.format(
                "城市: %s (%s) | 温度: %s°C (体感: %s°C) | 天气: %s | %s风 %s km/h | 湿度: %s%%",
                cityName, country,       // ← 使用用户输入的城市名
                tempC, feelsLikeC,
                weatherDesc,             // ← API 返回的天气描述（已翻译）
                windDir, windSpeed,
                humidity
        );
    }

    // ==============================================================
    //  工具方法
    // ==============================================================

    private static String httpGet(String url) throws Exception {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "curl/8.0")  // wttr.in 建议加 UA
                .build();
        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String body = response.body() != null ? response.body().string() : "(空)";
                // wttr.in 常见错误格式："location not found: ..."
                if (body.contains("not found") || body.contains("Unknown location")) {
                    throw new CityNotFoundException(
                            java.net.URLDecoder.decode(
                                    response.request().url().toString(), "UTF-8"
                            ).replaceAll(".*/", "").replaceAll("\\?.*", "")
                    );
                }
                throw new Exception("HTTP " + response.code() + ": " + body);
            }
            if (response.body() == null) {
                throw new Exception("响应体为空");
            }
            return response.body().string();
        }
    }

    private static JsonObject parseJson(String json) throws Exception {
        try {
            return JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            throw new Exception("JSON 解析失败: " + e.getMessage());
        }
    }

    private static String translateWeather(String english) {
        // 精确匹配
        String zh = ZH.get(english);
        if (zh != null) return zh;
        // 大小写不敏感匹配
        for (Map.Entry<String, String> e : ZH.entrySet()) {
            if (e.getKey().equalsIgnoreCase(english)) {
                return e.getValue();
            }
        }
        // 无匹配则保留 API 原文
        return english;
    }

    // ==============================================================
    //  异常类
    // ==============================================================

    /**
     * 城市未找到异常
     */
    public static class CityNotFoundException extends Exception {
        public CityNotFoundException(String cityName) {
            super("❌ 未找到城市「" + cityName + "」\n"
                    + "  请检查：\n"
                    + "  1. 城市名拼写是否正确（支持中文/拼音/英文）\n"
                    + "  2. 是否输入了不存在的城市名");
        }
    }
}
