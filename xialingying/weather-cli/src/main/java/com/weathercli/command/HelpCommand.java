package com.weathercli.command;

import com.weathercli.exception.CLIException;

import java.util.Map;

/**
 * help 命令 — 显示所有可用命令及帮助信息。
 */
public class HelpCommand implements Command {

    private final Map<String, Command> commands;

    public HelpCommand(Map<String, Command> commands) {
        this.commands = commands;
    }

    @Override
    public String getName() {
        return "help";
    }

    @Override
    public String getDescription() {
        return "显示帮助信息";
    }

    @Override
    public String getUsage() {
        return "help [command]";
    }

    @Override
    public void execute(String[] args) throws CLIException {
        if (args.length > 0) {
            // 显示特定命令的帮助
            String cmdName = args[0].toLowerCase();
            Command cmd = commands.get(cmdName);
            if (cmd == null) {
                throw new CLIException(
                    CLIException.ErrorCode.INVALID_COMMAND,
                    "未知命令: " + cmdName + "\n输入 'help' 查看所有可用命令"
                );
            }
            System.out.println("═══════ " + cmd.getName() + " ═══════");
            System.out.println("描述: " + cmd.getDescription());
            System.out.println("用法: " + cmd.getUsage());
        } else {
            // 显示所有命令
            System.out.println();
            System.out.println("╔══════════════════════════════════════════╗");
            System.out.println("║         Weather CLI  -  帮助信息        ║");
            System.out.println("╠══════════════════════════════════════════╣");
            System.out.println("║ 可用命令:                               ║");
            System.out.println("╠══════════════════════════════════════════╣");

            for (Command cmd : commands.values()) {
                System.out.printf("║  %-10s - %-26s ║%n", cmd.getName(), cmd.getDescription());
            }

            System.out.println("╠══════════════════════════════════════════╣");
            System.out.println("║  输入 'help <命令>' 查看详细用法        ║");
            System.out.println("╚══════════════════════════════════════════╝");
            System.out.println();
        }
    }
}
