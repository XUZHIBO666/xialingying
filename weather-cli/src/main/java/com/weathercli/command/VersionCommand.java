package com.weathercli.command;

import com.weathercli.exception.CLIException;

/**
 * version 命令 — 显示程序版本信息。
 */
public class VersionCommand implements Command {

    public static final String VERSION = "1.0.0";
    public static final String BUILD_DATE = "2026-07-16";
    public static final String JAVA_VERSION = System.getProperty("java.version");
    public static final String OS_NAME = System.getProperty("os.name");
    public static final String OS_ARCH = System.getProperty("os.arch");

    @Override
    public String getName() {
        return "version";
    }

    @Override
    public String getDescription() {
        return "显示版本信息";
    }

    @Override
    public String getUsage() {
        return "version";
    }

    @Override
    public void execute(String[] args) throws CLIException {
        System.out.println();
        System.out.println("┌─────────────────────────────────────────┐");
        System.out.println("│         Weather CLI  版本信息           │");
        System.out.println("├─────────────────────────────────────────┤");
        System.out.printf("│  版本号:     %-27s │%n", VERSION);
        System.out.printf("│  发布日期:   %-27s │%n", BUILD_DATE);
        System.out.printf("│  Java 版本:  %-27s │%n", JAVA_VERSION);
        System.out.printf("│  操作系统:   %-27s │%n", OS_NAME);
        System.out.printf("│  系统架构:   %-27s │%n", OS_ARCH);
        System.out.println("└─────────────────────────────────────────┘");
        System.out.println();
    }
}
