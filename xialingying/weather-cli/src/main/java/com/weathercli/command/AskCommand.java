package com.weathercli.command;

import com.weathercli.exception.CLIException;
import com.weathercli.service.AIService;

import java.util.logging.Logger;

/**
 * ask 命令 — 向 AI 大模型提问。
 *
 * 用法:
 *   ask <问题>           使用默认模型提问
 *   ask --model qwen <问题>  指定使用通义千问
 *   ask --model deepseek <问题>  指定使用 DeepSeek
 *
 * 前置条件: 需配置 API Key（环境变量或 config.properties）
 */
public class AskCommand implements Command {

    private static final Logger LOG = Logger.getLogger(AskCommand.class.getName());

    private final AIService aiService;

    public AskCommand(AIService aiService) {
        this.aiService = aiService;
    }

    @Override
    public String getName() {
        return "ask";
    }

    @Override
    public String getDescription() {
        return "向 AI 大模型提问";
    }

    @Override
    public String getUsage() {
        return "ask <问题>";
    }

    @Override
    public void execute(String[] args) throws CLIException {
        // 参数解析
        if (args.length == 0) {
            throw new CLIException(
                CLIException.ErrorCode.MISSING_ARGUMENT,
                "❌ 请输入要提问的问题！\n\n"
                + "用法: ask <问题>\n"
                + "示例:\n"
                + "  ask 今天天气怎么样\n"
                + "  ask 用Java写一个快速排序\n"
                + "  ask --model qwen 解释什么是RESTful API"
            );
        }

        String provider = null;
        int questionStart = 0;

        // 解析 --model 参数
        if (args.length >= 2 && "--model".equals(args[0])) {
            provider = args[1];
            questionStart = 2;
        }

        if (questionStart >= args.length) {
            throw new CLIException(
                CLIException.ErrorCode.MISSING_ARGUMENT,
                "❌ 缺少问题内容！\n用法: ask [--model <模型>] <问题>"
            );
        }

        String question = String.join(" ",
            java.util.Arrays.copyOfRange(args, questionStart, args.length));

        LOG.info("执行 ask 命令: provider=" + provider + ", question=" + question);

        // 检查 API Key 配置状态
        if (!aiService.isAvailable()) {
            System.out.println();
            System.out.println("┌──────────────────────────────────────────────┐");
            System.out.println("│  ⚠  API Key 未配置                           │");
            System.out.println("├──────────────────────────────────────────────┤");
            System.out.println("│  请先配置 AI 模型的 API Key:                  │");
            System.out.println("│                                              │");
            System.out.println("│  方式1 - 环境变量:                            │");
            System.out.println("│    export DEEPSEEK_API_KEY=sk-xxx             │");
            System.out.println("│                                              │");
            System.out.println("│  方式2 - 配置文件:                            │");
            System.out.println("│    编辑 src/main/resources/config.properties   │");
            System.out.println("│                                              │");
            System.out.println("│  DeepSeek 申请:                               │");
            System.out.println("│    https://platform.deepseek.com/api_keys     │");
            System.out.println("│  (注册即送 500万 tokens 免费额度)            │");
            System.out.println("└──────────────────────────────────────────────┘");
            System.out.println();
            return;
        }

        // 显示提问信息
        System.out.println();
        System.out.println("🤖 正在向 AI 提问...");
        System.out.println("   Q: " + question);
        System.out.println();

        // 调用 AI
        String answer;
        if (provider != null) {
            answer = aiService.chat(question, provider);
        } else {
            answer = aiService.chat(question);
        }

        // 显示结果
        System.out.println("┌──────────────────────────────────────────────┐");
        System.out.println("│  🤖 AI 回复                                   │");
        System.out.println("├──────────────────────────────────────────────┤");
        // 自动换行显示
        for (String line : wrapText(answer, 44)) {
            System.out.println("│  " + padRight(line, 42) + "│");
        }
        System.out.println("└──────────────────────────────────────────────┘");
        System.out.println();

        LOG.info("ask 命令完成");
    }

    /**
     * 简单文本换行。
     */
    private String[] wrapText(String text, int width) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (String paragraph : text.split("\n")) {
            while (paragraph.length() > width) {
                int breakPoint = paragraph.lastIndexOf(' ', width);
                if (breakPoint <= 0) breakPoint = width;
                lines.add(paragraph.substring(0, breakPoint));
                paragraph = paragraph.substring(breakPoint).trim();
            }
            lines.add(paragraph);
        }
        return lines.toArray(new String[0]);
    }

    private String padRight(String s, int n) {
        int len = 0;
        for (char c : s.toCharArray()) len += (c > 127) ? 2 : 1;
        int padding = n - len;
        return s + (padding > 0 ? " ".repeat(padding) : "");
    }
}
