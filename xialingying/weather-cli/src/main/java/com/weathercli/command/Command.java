package com.weathercli.command;

import com.weathercli.exception.CLIException;

/**
 * 命令接口 — 所有 CLI 命令需实现此接口。
 */
public interface Command {

    /**
     * 命令名称（用户输入的关键字）。
     */
    String getName();

    /**
     * 命令描述。
     */
    String getDescription();

    /**
     * 命令用法示例。
     */
    String getUsage();

    /**
     * 执行命令。
     *
     * @param args 命令参数（不含命令名本身）
     * @throws CLIException 当命令执行出错时抛出
     */
    void execute(String[] args) throws CLIException;
}
