package com.weathercli.command;

import com.weathercli.exception.CLIException;
import com.weathercli.service.ILinkService;

import java.util.Scanner;
import java.util.logging.Logger;

/**
 * ilink 命令 — 微信 iLink Bot 管理。
 *
 * 子命令:
 *   ilink login         扫码登录微信 Bot
 *   ilink logout        断开连接
 *   ilink status        显示连接状态
 *   ilink send <id> <msg>  发送消息给指定用户
 *   ilink listen        开始监听并显示收到的消息
 *   ilink info          显示 iLink 协议信息
 */
public class ILinkCommand implements Command {

    private static final Logger LOG = Logger.getLogger(ILinkCommand.class.getName());

    private final ILinkService ilinkService;

    public ILinkCommand(ILinkService ilinkService) {
        this.ilinkService = ilinkService;
    }

    @Override
    public String getName() {
        return "ilink";
    }

    @Override
    public String getDescription() {
        return "微信 iLink Bot 管理";
    }

    @Override
    public String getUsage() {
        return "ilink <login|logout|status|send|listen|info>";
    }

    @Override
    public void execute(String[] args) throws CLIException {
        if (args.length == 0) {
            showStatus();
            return;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "login":
                doLogin();
                break;
            case "logout":
                doLogout();
                break;
            case "status":
                showStatus();
                break;
            case "send":
                if (args.length < 3) {
                    throw new CLIException(CLIException.ErrorCode.MISSING_ARGUMENT,
                        "用法: ilink send <用户ID> <消息内容>\n"
                        + "示例: ilink send o9cq800kum_xxx@im.wechat 你好！");
                }
                String userId = args[1];
                String message = String.join(" ",
                    java.util.Arrays.copyOfRange(args, 2, args.length));
                doSend(userId, message);
                break;
            case "listen":
                doListen();
                break;
            case "info":
                showInfo();
                break;
            default:
                throw new CLIException(CLIException.ErrorCode.INVALID_COMMAND,
                    "未知子命令: " + subCommand + "\n"
                    + "可用: login, logout, status, send, listen, info");
        }
    }

    // ---- 登录 ----

    private void doLogin() throws CLIException {
        if (ilinkService.isConnected()) {
            System.out.println("⚠ 已登录 (Bot ID: " + ilinkService.getBotId() + ")");
            System.out.println("如需重新登录，请先执行 ilink logout");
            return;
        }

        System.out.println();
        System.out.println("┌──────────────────────────────────────────────┐");
        System.out.println("│       📱 iLink 微信 Bot 登录                 │");
        System.out.println("├──────────────────────────────────────────────┤");
        System.out.println("│  正在获取登录二维码...                        │");
        System.out.println("└──────────────────────────────────────────────┘");
        System.out.println();

        String qrContent = ilinkService.getLoginQrCode();

        // 在控制台显示二维码（ASCII QR）
        // 也可以生成一个 URL 让用户在浏览器打开
        System.out.println("📱 请使用微信扫描以下二维码登录:");
        // 显示二维码提示
        String shortQr = qrContent.length() > 50
            ? qrContent.substring(0, 50) + "..."
            : qrContent;

        System.out.println();
        System.out.println("┌──────────────────────────────────────────────┐");
        System.out.println("│  📱 二维码内容 (可复制到浏览器扫码):         │");
        System.out.println("│                                              │");
        System.out.println("│  " + shortQr);
        System.out.println("│                                              │");
        System.out.println("│  或访问在线生成:                              │");
        System.out.println("│  https://api.qrserver.com/v1/create-qr-code/ │");
        System.out.println("│  ?size=200x200&data=" + qrContent.substring(0, Math.min(30, qrContent.length())));
        System.out.println("│                                              │");
        System.out.println("└──────────────────────────────────────────────┘");
        System.out.println();

        // 等待扫码（60 秒超时）
        System.out.println("⏳ 等待扫码确认 (60秒超时)...");
        try {
            String botId = ilinkService.waitForScan(60);
            System.out.println();
            System.out.println("✅ 登录成功！Bot ID: " + botId);
            System.out.println();
            System.out.println("提示: 使用 'ilink listen' 开始监听消息");
            System.out.println();
        } catch (CLIException e) {
            System.out.println("❌ 登录失败: " + e.getMessage());
            throw e;
        }
    }

    // ---- 登出 ----

    private void doLogout() {
        ilinkService.disconnect();
        System.out.println("👋 已断开 iLink 连接");
    }

    // ---- 状态 ----

    private void showStatus() {
        System.out.println();
        System.out.println("┌──────────────────────────────────────────────┐");
        System.out.println("│       📡 iLink 连接状态                       │");
        System.out.println("├──────────────────────────────────────────────┤");
        System.out.printf("│  状态:     %-34s │%n",
            ilinkService.getState().getDescription());
        if (ilinkService.isConnected()) {
            System.out.printf("│  Bot ID:   %-34s │%n",
                ilinkService.getBotId());
        }
        System.out.println("│                                              │");
        System.out.println("│  可用子命令:                                  │");
        System.out.println("│    ilink login    - 扫码登录                  │");
        System.out.println("│    ilink logout   - 断开连接                  │");
        System.out.println("│    ilink send     - 发送消息                  │");
        System.out.println("│    ilink listen   - 监听消息                  │");
        System.out.println("│    ilink info     - 协议信息                  │");
        System.out.println("└──────────────────────────────────────────────┘");
        System.out.println();
    }

    // ---- 发送消息 ----

    private void doSend(String userId, String message) throws CLIException {
        LOG.info("ilink send → " + userId + ": " + message);
        ilinkService.sendText(userId, message);
        System.out.println("✅ 消息已发送 → " + userId);
    }

    // ---- 监听消息 ----

    private void doListen() throws CLIException {
        if (!ilinkService.isConnected()) {
            throw new CLIException(CLIException.ErrorCode.CONFIG_ERROR,
                "请先执行 'ilink login' 扫码登录");
        }

        System.out.println();
        System.out.println("┌──────────────────────────────────────────────┐");
        System.out.println("│  👂 开始监听 iLink 消息...                    │");
        System.out.println("│  输入 'stop' 停止监听                        │");
        System.out.println("│  收到消息时输入回复内容可立即回复            │");
        System.out.println("└──────────────────────────────────────────────┘");
        System.out.println();

        // 注册消息处理器
        ILinkService.MessageListener listener = (userId, text, contextToken) -> {
            System.out.println();
            System.out.println("📩 收到消息 [" + userId + "]:");
            System.out.println("   " + text);
            System.out.print("   回复 (回车跳过) > ");
        };

        ilinkService.addMessageListener(listener);

        // 开始后台轮询
        ilinkService.startPolling();

        // 交互式回复
        Scanner scanner = new Scanner(System.in, java.nio.charset.StandardCharsets.UTF_8);
        String lastUserId = null;

        while (true) {
            String input = scanner.nextLine().trim();

            if ("stop".equalsIgnoreCase(input)) {
                break;
            }

            if (input.isEmpty()) {
                continue;
            }

            // 如果有待回复的用户
            if (lastUserId != null && !input.isEmpty()) {
                try {
                    ilinkService.reply(lastUserId, input);
                    System.out.println("   ✅ 已回复");
                } catch (CLIException e) {
                    System.out.println("   ❌ 发送失败: " + e.getMessage());
                }
            }
        }

        ilinkService.removeMessageListener(listener);
        ilinkService.disconnect();
        System.out.println("👋 监听已停止");
    }

    // ---- 协议信息 ----

    private void showInfo() {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║        📖 iLink 协议 — 技术说明              ║");
        System.out.println("╠══════════════════════════════════════════════╣");
        System.out.println("║                                              ║");
        System.out.println("║  【协议概述】                                ║");
        System.out.println("║  iLink 是腾讯为微信个人号 1v1 助手场景      ║");
        System.out.println("║  提供的私有协议，基于 HTTPS + 长轮询。      ║");
        System.out.println("║                                              ║");
        System.out.println("║  【通信机制】                                ║");
        System.out.println("║  1. 扫码登录 → 获取 bot_token               ║");
        System.out.println("║  2. 长轮询 getupdates (最长35s挂起)        ║");
        System.out.println("║  3. context_token 关联对话上下文           ║");
        System.out.println("║  4. cursor 游标确保消息不丢失              ║");
        System.out.println("║                                              ║");
        System.out.println("║  【API 端点】                                ║");
        System.out.println("║  GET  /ilink/bot/get_bot_qrcode             ║");
        System.out.println("║  GET  /ilink/bot/get_qrcode_status           ║");
        System.out.println("║  POST /ilink/bot/getupdates                  ║");
        System.out.println("║  POST /ilink/bot/sendmessage                 ║");
        System.out.println("║  POST /ilink/bot/getconfig                   ║");
        System.out.println("║  POST /ilink/bot/sendtyping                  ║");
        System.out.println("║  POST /ilink/bot/getuploadurl                ║");
        System.out.println("║                                              ║");
        System.out.println("║  Base URL: ilinkai.weixin.qq.com            ║");
        System.out.println("║                                              ║");
        System.out.println("║  【限制】                                    ║");
        System.out.println("║  · 不支持群聊                                ║");
        System.out.println("║  · 用户先发消息，Bot 才能回复（24h 窗口）    ║");
        System.out.println("║  · 不能主动发起对话                          ║");
        System.out.println("║                                              ║");
        System.out.println("║  【参考文档】                                ║");
        System.out.println("║  docs.openclaw.ai/zh-CN/channels/wechat     ║");
        System.out.println("║  github.com/hao-ji-xing/openclaw-weixin     ║");
        System.out.println("╚══════════════════════════════════════════════╝");
        System.out.println();
    }
}
