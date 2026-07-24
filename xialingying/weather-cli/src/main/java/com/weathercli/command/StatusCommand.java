package com.weathercli.command;

import com.weathercli.exception.CLIException;
import com.weathercli.service.WeatherService;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * status 命令 — 显示程序运行状态和系统信息。
 */
public class StatusCommand implements Command {

    private final WeatherService weatherService;

    public StatusCommand(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    @Override
    public String getName() {
        return "status";
    }

    @Override
    public String getDescription() {
        return "显示程序运行状态";
    }

    @Override
    public String getUsage() {
        return "status";
    }

    @Override
    public void execute(String[] args) throws CLIException {
        Runtime runtime = Runtime.getRuntime();
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();

        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long maxMemory = runtime.maxMemory();
        int availableProcessors = runtime.availableProcessors();

        System.out.println();
        System.out.println("┌──────────────────────────────────────────────┐");
        System.out.println("│         程序运行状态                          │");
        System.out.println("├──────────────────────────────────────────────┤");

        // 时间
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        System.out.printf("│  当前时间:     %-30s │%n", now);

        // 版本
        System.out.printf("│  程序版本:     %-30s │%n", VersionCommand.VERSION);

        // 天气 API 状态
        String apiStatus = weatherService.isAvailable() ? "✓ 可用" : "✗ 不可用";
        System.out.printf("│  天气 API:     %-30s │%n", apiStatus);

        // 内存
        String memoryInfo = String.format("%d MB / %d MB",
            usedMemory / (1024 * 1024),
            maxMemory / (1024 * 1024));
        System.out.printf("│  内存使用:     %-30s │%n", memoryInfo);

        // CPU
        String cpuInfo = String.format("%.1f%% (核心数: %d)",
            osBean.getSystemLoadAverage() * 100, availableProcessors);
        System.out.printf("│  CPU 负载:     %-30s │%n", cpuInfo);

        // Java 版本
        System.out.printf("│  Java 版本:    %-30s │%n", VersionCommand.JAVA_VERSION);

        System.out.println("└──────────────────────────────────────────────┘");
        System.out.println();
    }
}
