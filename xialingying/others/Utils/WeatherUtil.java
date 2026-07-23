package com.demo.demo.Utils;

import com.demo.demo.execption.BizException;
import com.demo.demo.execption.ResponseCodeEnum;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 天气查询工具类
 * 使用 wttr.in 免费开放 API，一个请求完成城市定位+天气查询
 * 无需注册，无需 API Key
 *
 * 文档: https://github.com/chubin/wttr.in
 */
@Slf4j
public class WeatherUtil {

    private static final String WTTR_API = "https://wttr.in";

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    // 天气描述英文 → 中文翻译表
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
     * @throws BizException 参数为空、城市不存在、网络异常等
     */
    public static String getWeather(String cityName) {
        // 日志：记录请求参数
        log.info("[天气查询] 开始查询，城市: {}", cityName);

        // 边界校验：空城市名
        if (cityName == null || cityName.trim().isEmpty()) {
            log.warn("[天气查询] 城市名为空");
            throw new BizException(ResponseCodeEnum.CITY_NAME_EMPTY);
        }

        String trimmedCity = cityName.trim();

        // 边界校验：城市名包含非法字符（只允许中文、英文、空格、连字符）
        if (!trimmedCity.matches("[\\u4e00-\\u9fa5a-zA-Z\\s\\-]+")) {
            log.warn("[天气查询] 城市名包含非法字符: {}", trimmedCity);
            throw new BizException(ResponseCodeEnum.CITY_NAME_INVALID,
                    "城市名「" + trimmedCity + "」包含无效字符，请使用中文/拼音/英文");
        }

        // 调用 wttr.in JSON API
        String url = WTTR_API + "/" + trimmedCity + "?format=j1";
        log.info("[天气查询] 请求 URL: {}", url);

        String json;
        try {
            json = httpGet(url);
        } catch (BizException e) {
            throw e; // 直接抛出，不包装
        } catch (SocketTimeoutException e) {
            log.error("[天气查询] 请求超时，城市: {}", trimmedCity, e);
            throw new BizException(ResponseCodeEnum.THIRD_PARTY_TIMEOUT,
                    "天气服务响应超时，请稍后重试", e);
        } catch (Exception e) {
            log.error("[天气查询] 网络请求失败，城市: {}", trimmedCity, e);
            throw new BizException(ResponseCodeEnum.WEATHER_API_ERROR,
                    "天气服务暂时不可用，请稍后重试", e);
        }

        // 解析 JSON
        JsonObject root;
        try {
            root = parseJson(json);
        } catch (Exception e) {
            log.error("[天气查询] JSON 解析失败，城市: {}", trimmedCity, e);
            throw new BizException(ResponseCodeEnum.WEATHER_PARSE_ERROR,
                    "天气数据格式异常", e);
        }

        log.debug("[天气查询] API 返回数据: {}", json);

        // 解析当前天气
        JsonArray conditions = root.getAsJsonArray("current_condition");
        if (conditions == null || conditions.isEmpty()) {
            log.warn("[天气查询] 城市未找到: {}", trimmedCity);
            throw new BizException(ResponseCodeEnum.CITY_NOT_FOUND,
                    "❌ 未找到城市「" + trimmedCity + "」\n"
                    + "  请检查：\n"
                    + "  1. 城市名拼写是否正确（支持中文/拼音/英文）\n"
                    + "  2. 是否输入了不存在的城市名");
        }
        JsonObject current = conditions.get(0).getAsJsonObject();

        // 提取各项数据
        String weatherEn = current.getAsJsonArray("weatherDesc")
                .get(0).getAsJsonObject()
                .get("value").getAsString().trim();
        String weatherDesc = translateWeather(weatherEn);

        String tempC = current.get("temp_C").getAsString();
        String feelsLikeC = current.get("FeelsLikeC").getAsString();
        String humidity = current.get("humidity").getAsString();
        String windSpeed = current.get("windspeedKmph").getAsString();
        String windDirEn = current.get("winddir16Point").getAsString();
        String windDir = WIND_ZH.getOrDefault(windDirEn, windDirEn);

        // 解析城市信息
        JsonArray areas = root.getAsJsonArray("nearest_area");
        String country = "";
        String areaName = "";
        if (areas != null && !areas.isEmpty()) {
            JsonObject area = areas.get(0).getAsJsonObject();
            country = area.getAsJsonArray("country")
                    .get(0).getAsJsonObject()
                    .get("value").getAsString();
            areaName = area.getAsJsonArray("areaName")
                    .get(0).getAsJsonObject()
                    .get("value").getAsString();
        }

        String result = String.format(
                "城市: %s (%s, %s) | 温度: %s°C (体感: %s°C) | 天气: %s | %s风 %s km/h | 湿度: %s%%",
                trimmedCity, areaName, country,
                tempC, feelsLikeC,
                weatherDesc,
                windDir, windSpeed,
                humidity
        );

        // 日志：记录响应结果
        log.info("[天气查询] 查询成功，结果: {}", result);

        return result;
    }

    // ==============================================================
    //  工具方法
    // ==============================================================

    private static String httpGet(String url) throws Exception {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "curl/8.0")
                .build();
        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String body = response.body() != null ? response.body().string() : "(空)";
                if (body.contains("not found") || body.contains("Unknown location")) {
                    String city = URLDecoder.decode(
                            response.request().url().toString(), StandardCharsets.UTF_8)
                            .replaceAll(".*/", "")
                            .replaceAll("\\?.*", "");
                    throw new BizException(ResponseCodeEnum.CITY_NOT_FOUND,
                            "❌ 未找到城市「" + city + "」\n"
                            + "  请检查城市名拼写是否正确");
                }
                throw new BizException(ResponseCodeEnum.WEATHER_API_ERROR,
                        "天气 API 返回错误 HTTP " + response.code());
            }
            if (response.body() == null) {
                throw new BizException(ResponseCodeEnum.WEATHER_API_ERROR, "响应体为空");
            }
            return response.body().string();
        }
    }

    private static JsonObject parseJson(String json) {
        try {
            return JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            throw new BizException(ResponseCodeEnum.WEATHER_PARSE_ERROR,
                    "天气数据解析失败: " + e.getMessage(), e);
        }
    }

    private static String translateWeather(String english) {
        String zh = ZH.get(english);
        if (zh != null) return zh;
        for (Map.Entry<String, String> e : ZH.entrySet()) {
            if (e.getKey().equalsIgnoreCase(english)) {
                return e.getValue();
            }
        }
        log.debug("[天气查询] 未找到天气翻译: {}", english);
        return english;
    }
}
