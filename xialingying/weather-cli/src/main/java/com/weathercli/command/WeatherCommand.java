package com.weathercli.command;

import com.weathercli.exception.CLIException;
import com.weathercli.service.WeatherService;
import com.weathercli.service.WeatherService.*;

import java.util.logging.Logger;

/**
 * weather 命令 — 查询指定城市的天气。
 *
 * 用法: weather <城市名>
 * 示例: weather 北京
 *       weather Tokyo
 *       weather "New York"
 */
public class WeatherCommand implements Command {

    private static final Logger LOG = Logger.getLogger(WeatherCommand.class.getName());

    private final WeatherService weatherService;

    public WeatherCommand(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    @Override
    public String getName() {
        return "weather";
    }

    @Override
    public String getDescription() {
        return "查询城市天气";
    }

    @Override
    public String getUsage() {
        return "weather <城市名>";
    }

    @Override
    public void execute(String[] args) throws CLIException {
        // ===== 参数验证 =====
        if (args.length == 0) {
            LOG.warning("weather 命令缺少城市名参数");
            throw new CLIException(
                CLIException.ErrorCode.MISSING_ARGUMENT,
                "❌ 缺少城市名称！\n\n"
                + "用法: weather <城市名>\n"
                + "示例:\n"
                + "  weather 北京\n"
                + "  weather 上海\n"
                + "  weather Tokyo\n"
                + "  weather \"New York\"\n"
                + "  weather 伦敦"
            );
        }

        String city = String.join(" ", args).trim();

        if (city.isEmpty() || city.isBlank()) {
            LOG.warning("weather 命令城市名为空白");
            throw new CLIException(
                CLIException.ErrorCode.MISSING_ARGUMENT,
                "❌ 城市名不能为空！请输入有效的城市名称。"
            );
        }

        // ===== 查询天气 =====
        LOG.info("执行 weather 命令: city=" + city);
        System.out.println();
        System.out.println("🔍 正在查询 " + city + " 的天气...");

        WeatherResult result = weatherService.queryWeather(city);
        WeatherData data = result.getData();

        // ===== 格式化输出 =====
        System.out.println();
        System.out.println("┌──────────────────────────────────────────────────┐");
        System.out.printf("│  📍 %-15s, %-4s                      │%n",
            result.getCity(), result.getCountry());
        System.out.printf("│  坐标: (%.4f, %.4f)                          │%n",
            result.getLatitude(), result.getLongitude());
        System.out.println("├──────────────────────────────────────────────────┤");
        System.out.println("│                   🌤  当前天气                   │");
        System.out.println("├──────────────────────────────────────────────────┤");
        System.out.printf("│  天气状况:   %-30s       │%n",
            WeatherService.getWeatherDescription(data.getWeatherCode()));
        System.out.printf("│  🌡  实际温度:  %-6.1f °C                        │%n",
            data.getTemperature());
        System.out.printf("│  🥵 体感温度:   %-6.1f °C                        │%n",
            data.getApparentTemp());
        System.out.printf("│  💧 湿度:       %-6d %%                          │%n",
            data.getHumidity());
        System.out.printf("│  🌬  风速:       %-6.1f km/h  (%s)             │%n",
            data.getWindSpeed(),
            WeatherService.getWindDirectionName(data.getWindDirection()));
        System.out.printf("│  📊 气压:       %-6.1f hPa                       │%n",
            data.getPressure());
        System.out.println("├──────────────────────────────────────────────────┤");
        System.out.println("│                 📅  未来天气预报                 │");
        System.out.println("├──────────────────────────────────────────────────┤");

        for (DailyForecast df : data.getDailyForecasts()) {
            System.out.printf("│  %s                                      │%n", df.getDate());
            System.out.printf("│    %s  🌡 %.0f°C ~ %.0f°C  💧 %d%%  🌧 %.1fmm   │%n",
                WeatherService.getWeatherDescription(df.getWeatherCode()),
                df.getMinTemp(), df.getMaxTemp(),
                df.getPrecipitationProbability(),
                df.getPrecipitation());
        }

        System.out.println("└──────────────────────────────────────────────────┘");
        System.out.println();

        LOG.info("天气查询结果显示完成: " + city);
    }
}
