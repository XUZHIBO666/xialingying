package com.weathercli.service;

import com.google.gson.*;
import com.weathercli.exception.CLIException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * 天气 API 服务 — 调用 Open-Meteo 免费天气 API。
 *
 * 使用的 API:
 *   - Open-Meteo (https://open-meteo.com) — 免费，无需 API Key
 *   - 地理编码用 Geocoding API 将城市名转为经纬度
 *   - 天气预报用 Forecast API 获取天气数据
 */
public class WeatherService {

    private static final Logger LOG = Logger.getLogger(WeatherService.class.getName());

    private static final String GEOCODING_API_URL =
        "https://geocoding-api.open-meteo.com/v1/search";
    private static final String FORECAST_API_URL =
        "https://api.open-meteo.com/v1/forecast";

    private final HttpClient httpClient;
    private final Gson gson;
    private volatile boolean available = true;

    public WeatherService() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }

    /**
     * 检查天气 API 是否可用。
     */
    public boolean isAvailable() {
        return available;
    }

    /**
     * 根据城市名查询天气。
     *
     * @param city 城市名（中文或英文）
     * @return 格式化后的天气信息字符串
     * @throws CLIException 查询失败时抛出
     */
    public WeatherResult queryWeather(String city) throws CLIException {
        LOG.info("开始查询天气: 城市=" + city);

        // 1. 地理编码：城市名 → 经纬度
        GeoLocation location = geocode(city);

        // 2. 获取天气预报
        WeatherData data = fetchForecast(location);

        // 3. 组装结果
        WeatherResult result = new WeatherResult(
            location.getName(), location.getCountry(),
            location.getLatitude(), location.getLongitude(),
            data
        );

        LOG.info("天气查询成功: " + result);
        return result;
    }

    /**
     * 地理编码：将城市名转换为经纬度坐标。
     */
    private GeoLocation geocode(String city) throws CLIException {
        String url = GEOCODING_API_URL + "?name=" + urlEncode(city)
            + "&count=5&language=zh&format=json";

        LOG.info("地理编码请求: " + url);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

            LOG.info("地理编码响应状态: " + response.statusCode());

            if (response.statusCode() != 200) {
                available = false;
                throw new CLIException(
                    CLIException.ErrorCode.API_ERROR,
                    "地理编码 API 返回错误状态码: " + response.statusCode()
                );
            }

            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonArray results = json.getAsJsonArray("results");

            if (results == null || results.isEmpty()) {
                throw new CLIException(
                    CLIException.ErrorCode.API_ERROR,
                    "未找到城市 \"" + city + "\"，请检查城市名拼写是否正确"
                );
            }

            // 取第一个结果（最匹配的）
            JsonObject first = results.get(0).getAsJsonObject();
            String name = getJsonString(first, "name");
            String country = getJsonString(first, "country");
            double lat = first.get("latitude").getAsDouble();
            double lon = first.get("longitude").getAsDouble();

            // 如果有中文名则使用
            if (first.has("admin1")) {
                String admin = getJsonString(first, "admin1");
            }

            LOG.info(String.format("地理编码结果: %s, %s (%.4f, %.4f)", name, country, lat, lon));

            return new GeoLocation(name, country, lat, lon);

        } catch (IOException e) {
            available = false;
            LOG.severe("地理编码网络错误: " + e.getMessage());
            throw new CLIException(
                CLIException.ErrorCode.NETWORK_ERROR,
                "网络连接失败，无法访问地理编码服务。请检查网络连接后重试。",
                e
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CLIException(
                CLIException.ErrorCode.NETWORK_ERROR,
                "请求被中断",
                e
            );
        } catch (JsonParseException e) {
            LOG.severe("地理编码数据解析失败: " + e.getMessage());
            throw new CLIException(
                CLIException.ErrorCode.PARSE_ERROR,
                "天气数据解析失败，请稍后重试。",
                e
            );
        }
    }

    /**
     * 根据经纬度获取天气预报。
     */
    private WeatherData fetchForecast(GeoLocation location) throws CLIException {
        String url = FORECAST_API_URL
            + "?latitude=" + location.getLatitude()
            + "&longitude=" + location.getLongitude()
            + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,"
            + "weather_code,wind_speed_10m,wind_direction_10m,pressure_msl"
            + "&daily=temperature_2m_max,temperature_2m_min,precipitation_sum,"
            + "weather_code,precipitation_probability_max"
            + "&timezone=auto"
            + "&forecast_days=3";

        LOG.info("天气预报请求: " + url);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

            LOG.info("天气预报响应状态: " + response.statusCode());

            if (response.statusCode() != 200) {
                available = false;
                throw new CLIException(
                    CLIException.ErrorCode.API_ERROR,
                    "天气预报 API 返回错误状态码: " + response.statusCode()
                );
            }

            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();

            // 解析当前天气
            JsonObject current = json.getAsJsonObject("current");
            double temperature = current.get("temperature_2m").getAsDouble();
            double apparentTemp = current.get("apparent_temperature").getAsDouble();
            int humidity = current.get("relative_humidity_2m").getAsInt();
            double windSpeed = current.get("wind_speed_10m").getAsDouble();
            int windDirection = current.get("wind_direction_10m").getAsInt();
            double pressure = current.get("pressure_msl").getAsDouble();
            int weatherCode = current.get("weather_code").getAsInt();

            // 解析未来天气预报
            JsonObject daily = json.getAsJsonObject("daily");
            JsonArray maxTemps = daily.getAsJsonArray("temperature_2m_max");
            JsonArray minTemps = daily.getAsJsonArray("temperature_2m_min");
            JsonArray precipSums = daily.getAsJsonArray("precipitation_sum");
            JsonArray weatherCodes = daily.getAsJsonArray("weather_code");
            JsonArray precipProbs = daily.getAsJsonArray("precipitation_probability_max");
            JsonArray dates = daily.getAsJsonArray("time");

            List<DailyForecast> dailyForecasts = new ArrayList<>();
            for (int i = 0; i < dates.size(); i++) {
                dailyForecasts.add(new DailyForecast(
                    dates.get(i).getAsString(),
                    maxTemps.get(i).getAsDouble(),
                    minTemps.get(i).getAsDouble(),
                    precipSums.get(i).getAsDouble(),
                    weatherCodes.get(i).getAsInt(),
                    precipProbs.get(i).getAsInt()
                ));
            }

            return new WeatherData(
                temperature, apparentTemp, humidity,
                windSpeed, windDirection, pressure,
                weatherCode, dailyForecasts
            );

        } catch (IOException e) {
            available = false;
            LOG.severe("天气预报网络错误: " + e.getMessage());
            throw new CLIException(
                CLIException.ErrorCode.NETWORK_ERROR,
                "网络连接失败，无法获取天气数据。请检查网络连接后重试。",
                e
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CLIException(
                CLIException.ErrorCode.NETWORK_ERROR,
                "请求被中断",
                e
            );
        } catch (JsonParseException | NullPointerException e) {
            LOG.severe("天气预报数据解析失败: " + e.getMessage());
            throw new CLIException(
                CLIException.ErrorCode.PARSE_ERROR,
                "天气数据解析失败，API 返回格式可能已变更。",
                e
            );
        }
    }

    // ---- 工具方法 ----

    /**
     * 获取 WMO 天气码对应的中文描述。
     */
    public static String getWeatherDescription(int code) {
        return switch (code) {
            case 0 -> "☀️ 晴天";
            case 1 -> "🌤️ 大部晴朗";
            case 2 -> "⛅ 多云";
            case 3 -> "☁️ 阴天";
            case 45, 48 -> "🌫️ 雾";
            case 51 -> "🌧️ 小毛毛雨";
            case 53 -> "🌧️ 中毛毛雨";
            case 55 -> "🌧️ 大毛毛雨";
            case 61 -> "🌧️ 小雨";
            case 63 -> "🌧️ 中雨";
            case 65 -> "🌧️ 大雨";
            case 71 -> "🌨️ 小雪";
            case 73 -> "🌨️ 中雪";
            case 75 -> "🌨️ 大雪";
            case 80 -> "🌦️ 小阵雨";
            case 81 -> "🌦️ 中阵雨";
            case 82 -> "🌦️ 大阵雨";
            case 85 -> "🌨️ 小阵雪";
            case 86 -> "🌨️ 大阵雪";
            case 95 -> "⛈️ 雷暴";
            case 96 -> "⛈️ 雷暴伴小冰雹";
            case 99 -> "⛈️ 雷暴伴大冰雹";
            default -> "❓ 未知";
        };
    }

    /**
     * 风速方向转为中文方向名。
     */
    public static String getWindDirectionName(int degrees) {
        String[] directions = {"北", "东北偏北", "东北", "东北偏东",
                               "东", "东南偏东", "东南", "东南偏南",
                               "南", "西南偏南", "西南", "西南偏西",
                               "西", "西北偏西", "西北", "西北偏北", "北"};
        int index = (int) Math.round(degrees / 22.5) % 16;
        return directions[index];
    }

    private String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private String getJsonString(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        return (el != null && !el.isJsonNull()) ? el.getAsString() : "";
    }

    // ---- 内部数据类 ----

    public static class GeoLocation {
        private final String name;
        private final String country;
        private final double latitude;
        private final double longitude;

        public GeoLocation(String name, String country, double latitude, double longitude) {
            this.name = name;
            this.country = country;
            this.latitude = latitude;
            this.longitude = longitude;
        }
        public String getName() { return name; }
        public String getCountry() { return country; }
        public double getLatitude() { return latitude; }
        public double getLongitude() { return longitude; }
    }

    public static class WeatherData {
        private final double temperature;
        private final double apparentTemp;
        private final int humidity;
        private final double windSpeed;
        private final int windDirection;
        private final double pressure;
        private final int weatherCode;
        private final List<DailyForecast> dailyForecasts;

        public WeatherData(double temperature, double apparentTemp, int humidity,
                           double windSpeed, int windDirection, double pressure,
                           int weatherCode, List<DailyForecast> dailyForecasts) {
            this.temperature = temperature;
            this.apparentTemp = apparentTemp;
            this.humidity = humidity;
            this.windSpeed = windSpeed;
            this.windDirection = windDirection;
            this.pressure = pressure;
            this.weatherCode = weatherCode;
            this.dailyForecasts = dailyForecasts;
        }
        public double getTemperature() { return temperature; }
        public double getApparentTemp() { return apparentTemp; }
        public int getHumidity() { return humidity; }
        public double getWindSpeed() { return windSpeed; }
        public int getWindDirection() { return windDirection; }
        public double getPressure() { return pressure; }
        public int getWeatherCode() { return weatherCode; }
        public List<DailyForecast> getDailyForecasts() { return dailyForecasts; }
    }

    public static class DailyForecast {
        private final String date;
        private final double maxTemp;
        private final double minTemp;
        private final double precipitation;
        private final int weatherCode;
        private final int precipitationProbability;

        public DailyForecast(String date, double maxTemp, double minTemp,
                             double precipitation, int weatherCode,
                             int precipitationProbability) {
            this.date = date;
            this.maxTemp = maxTemp;
            this.minTemp = minTemp;
            this.precipitation = precipitation;
            this.weatherCode = weatherCode;
            this.precipitationProbability = precipitationProbability;
        }
        public String getDate() { return date; }
        public double getMaxTemp() { return maxTemp; }
        public double getMinTemp() { return minTemp; }
        public double getPrecipitation() { return precipitation; }
        public int getWeatherCode() { return weatherCode; }
        public int getPrecipitationProbability() { return precipitationProbability; }
    }

    public static class WeatherResult {
        private final String city;
        private final String country;
        private final double latitude;
        private final double longitude;
        private final WeatherData data;

        public WeatherResult(String city, String country, double latitude,
                             double longitude, WeatherData data) {
            this.city = city;
            this.country = country;
            this.latitude = latitude;
            this.longitude = longitude;
            this.data = data;
        }
        public String getCity() { return city; }
        public String getCountry() { return country; }
        public double getLatitude() { return latitude; }
        public double getLongitude() { return longitude; }
        public WeatherData getData() { return data; }

        @Override
        public String toString() {
            return city + ", " + country + " [" + latitude + ", " + longitude + "] "
                + data.getTemperature() + "°C";
        }
    }
}
