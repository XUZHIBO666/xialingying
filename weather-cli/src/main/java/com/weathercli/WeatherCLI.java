package com.weathercli;

import com.weathercli.command.*;
import com.weathercli.exception.CLIException;
import com.weathercli.service.*;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.logging.*;

/**
 * Weather CLI — 主入口类。
 *
 * 一个命令行工具，支持:
 *   - help     显示帮助信息
 *   - version  显示版本信息
 *   - status   显示运行状态
 *   - weather  查询城市天气 (调用 Open-Meteo 免费 API)
 *   - models   查看可用的 AI 模型及 API Key 申请指引
 *   - ask      向 AI 大模型提问 (需配置 API Key)
 *   - ilink    微信 iLink Bot 管理 (扫码登录/消息收发)
 *   - exit     退出程序
 */
public class WeatherCLI {

    private static final Logger LOG = Logger.getLogger(WeatherCLI.class.getName());

    private final Map<String, Command> commands = new LinkedHashMap<>();
    private final WeatherService weatherService;
    private final ModelService modelService;
    private final AIService aiService;
    private final ILinkService ilinkService;
    private boolean running = true;

    public WeatherCLI() {
        this.weatherService = new WeatherService();
        this.modelService = new ModelService();
        this.aiService = new AIService();
        this.ilinkService = new ILinkService();

        // 注册所有命令
        registerCommand(new VersionCommand());
        registerCommand(new StatusCommand(weatherService));
        registerCommand(new WeatherCommand(weatherService));
        registerCommand(new ModelsCommand(modelService));
        registerCommand(new AskCommand(aiService));
        registerCommand(new ILinkCommand(ilinkService));
        // HelpCommand 需要 commands map 引用，在注册完成后设置
    }

    private void registerCommand(Command cmd) {
        commands.put(cmd.getName(), cmd);
    }

    /**
     * 程序入口。
     */
    public static void main(String[] args) {
        // 配置日志
        configureLogging();

        LOG.info("═══════════════════════════════════════");
        LOG.info("Weather CLI 启动");
        LOG.info("═══════════════════════════════════════");

        WeatherCLI cli = new WeatherCLI();
        // 在所有命令注册完成后设置 HelpCommand
        cli.commands.put("help", new HelpCommand(cli.commands));

        // 注册 JVM 关闭钩子，确保 iLink 连接正常释放
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            cli.ilinkService.disconnect();
            LOG.info("JVM 关闭钩子: 资源已释放");
        }));

        // 打印启动横幅
        cli.printBanner();

        // 如果命令行直接带参数，执行单次命令
        if (args.length > 0) {
            cli.executeCommand(args);
        } else {
            // 交互模式
            cli.interactiveMode();
        }

        LOG.info("Weather CLI 退出");
    }

    /**
     * 配置日志系统。
     */
    private static void configureLogging() {
        try {
            // 尝试加载配置文件
            InputStream configFile = WeatherCLI.class.getClassLoader()
                .getResourceAsStream("logging.properties");
            if (configFile != null) {
                LogManager.getLogManager().readConfiguration(configFile);
            }
        } catch (IOException e) {
            // 配置加载失败，使用默认配置
            System.err.println("⚠ 日志配置文件加载失败，使用默认配置");
        }

        // 设置自定义格式化器
        Logger rootLogger = Logger.getLogger("");
        for (Handler handler : rootLogger.getHandlers()) {
            handler.setFormatter(new LogFormatter());
        }
        // 也设置全局 logger
        Logger globalLogger = Logger.getLogger(WeatherCLI.class.getPackageName());
        globalLogger.setUseParentHandlers(true);
    }

    /**
     * 打印启动横幅。
     */
    private void printBanner() {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║         🌤  Weather CLI  v1.0.0              ║");
        System.out.println("║         基于 Open-Meteo 免费天气 API         ║");
        System.out.println("╠══════════════════════════════════════════════╣");
        System.out.println("║  输入 'help' 查看所有命令                    ║");
        System.out.println("║  输入 'exit' 退出程序                        ║");
        System.out.println("╚══════════════════════════════════════════════╝");
        System.out.println();
    }

    /**
     * 交互模式 — 循环等待用户输入。
     */
    private void interactiveMode() {
        Scanner scanner = new Scanner(System.in, java.nio.charset.StandardCharsets.UTF_8);

        while (running) {
            System.out.print("weather-cli> ");
            String line = scanner.nextLine().trim();

            if (line.isEmpty()) {
                continue;
            }

            String[] parts = parseCommandLine(line);
            String cmdName = parts[0].toLowerCase();

            // 处理 exit 命令
            if ("exit".equals(cmdName) || "quit".equals(cmdName) || "q".equals(cmdName)) {
                System.out.println("👋 再见！");
                running = false;
                continue;
            }

            // 提取参数
            String[] cmdArgs = new String[parts.length - 1];
            System.arraycopy(parts, 1, cmdArgs, 0, cmdArgs.length);

            // 执行命令
            executeSingleCommand(cmdName, cmdArgs);
        }

        scanner.close();
    }

    /**
     * 单次命令模式 — 执行后退出。
     */
    private void executeCommand(String[] args) {
        String cmdName = args[0].toLowerCase();
        String[] cmdArgs = new String[args.length - 1];
        System.arraycopy(args, 1, cmdArgs, 0, cmdArgs.length);

        executeSingleCommand(cmdName, cmdArgs);
    }

    /**
     * 执行单个命令并统一处理异常。
     */
    private void executeSingleCommand(String cmdName, String[] args) {
        try {
            Command cmd = commands.get(cmdName);

            if (cmd == null) {
                throw new CLIException(
                    CLIException.ErrorCode.INVALID_COMMAND,
                    "未知命令: " + cmdName + "\n输入 'help' 查看所有可用命令"
                );
            }

            LOG.info("执行命令: " + cmdName);
            cmd.execute(args);

        } catch (CLIException e) {
            // 简洁日志（控制台 + 文件），堆栈不对外暴露
            LOG.warning("[" + e.getErrorCode() + "] " + e.getMessage());
            System.err.println();
            System.err.println("╔══════════════════════════════════════════════╗");
            System.err.println("║  ⚠  错误 [" + e.getErrorCode().getCode() + "] "
                + padRight(e.getErrorCode().getDescription(), 28) + "║");
            System.err.println("╠══════════════════════════════════════════════╣");
            // 多行错误消息
            for (String line : e.getMessage().split("\n")) {
                System.err.println("║  " + padRight(line, 42) + "║");
            }
            System.err.println("╚══════════════════════════════════════════════╝");
            System.err.println();

        } catch (Exception e) {
            // 堆栈详情仅写入日志文件，控制台不显示
            LOG.log(Level.SEVERE, "未预期的异常: " + e, e);
            System.err.println();
            System.err.println("┌──────────────────────────────────────────────┐");
            System.err.println("│  ❌ 发生未预期的错误                          │");
            System.err.println("├──────────────────────────────────────────────┤");
            System.err.println("│  " + padRight(e.getClass().getSimpleName() + ": " + e.getMessage(), 42) + "│");
            System.err.println("│                                              │");
            System.err.println("│  如需调试，请查看日志文件 weather-cli.log    │");
            System.err.println("└──────────────────────────────────────────────┘");
            System.err.println();
        }
    }

    /**
     * 解析命令行输入（处理引号内的空格）。
     */
    private String[] parseCommandLine(String line) {
        // 简单实现：按空格分割，但保留引号内容
        java.util.List<String> tokens = new java.util.ArrayList<>();
        boolean inQuotes = false;
        StringBuilder current = new StringBuilder();

        for (char c : line.toCharArray()) {
            if (c == '"' || c == '\'') {
                inQuotes = !inQuotes;
            } else if (c == ' ' && !inQuotes) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }

        if (tokens.isEmpty()) {
            return new String[]{""};
        }

        return tokens.toArray(new String[0]);
    }

    private static String padRight(String s, int n) {
        // 处理中文字符宽度（粗略算为 2 个英文字符）
        int len = 0;
        for (char c : s.toCharArray()) {
            len += (c > 127) ? 2 : 1;
        }
        int padding = n - len;
        return s + (padding > 0 ? " ".repeat(padding) : "");
    }

    // ---- 日志格式化器 ----

    /**
     * 自定义日志格式化器，带时间戳和级别颜色标记。
     */
    public static class LogFormatter extends Formatter {

        private static final DateTimeFormatter DT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

        @Override
        public String format(LogRecord record) {
            StringBuilder sb = new StringBuilder();

            // 时间戳
            sb.append("[")
                .append(LocalDateTime.now().format(DT_FORMAT))
                .append("] ");

            // 日志级别
            sb.append("[")
                .append(record.getLevel().getLocalizedName())
                .append("] ");

            // 类名和方法
            String source = record.getSourceClassName();
            if (source != null) {
                sb.append(source.substring(source.lastIndexOf('.') + 1));
            }
            if (record.getSourceMethodName() != null) {
                sb.append(".").append(record.getSourceMethodName());
            }
            sb.append(" - ");

            // 消息
            sb.append(formatMessage(record));

            // 异常
            if (record.getThrown() != null) {
                sb.append(System.lineSeparator());
                sb.append("  Exception: ").append(record.getThrown().toString());
                for (StackTraceElement el : record.getThrown().getStackTrace()) {
                    sb.append(System.lineSeparator());
                    sb.append("    at ").append(el.toString());
                }
            }

            sb.append(System.lineSeparator());
            return sb.toString();
        }
    }
}
