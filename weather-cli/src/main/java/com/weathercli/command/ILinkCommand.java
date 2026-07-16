package com.weathercli.command;

import com.weathercli.exception.CLIException;
import com.weathercli.service.AIService;
import com.weathercli.service.ILinkService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicReference;
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
    private final AIService aiService;

    // 自动回复状态
    private enum AutoReplyMode { OFF, FIXED, AI }
    private volatile AutoReplyMode autoReplyMode = AutoReplyMode.OFF;
    private volatile String autoReplyMessage = null;

    public ILinkCommand(ILinkService ilinkService, AIService aiService) {
        this.ilinkService = ilinkService;
        this.aiService = aiService;
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
        return "ilink <login|logout|status|send|reply|listen|info>";
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
            case "reply":
                if (args.length < 3) {
                    throw new CLIException(CLIException.ErrorCode.MISSING_ARGUMENT,
                        "用法: ilink reply <用户ID> <消息内容>\n"
                        + "示例: ilink reply o9cq800kum_xxx@im.wechat 你好！");
                }
                String replyUserId = args[1];
                String replyMsg = String.join(" ",
                    java.util.Arrays.copyOfRange(args, 2, args.length));
                doSend(replyUserId, replyMsg);
                break;
            case "info":
                showInfo();
                break;
            case "autoreply":
                if (args.length < 2) {
                    throw new CLIException(CLIException.ErrorCode.MISSING_ARGUMENT,
                        "用法: ilink autoreply <消息|ai|off|status>\n"
                        + "  ilink autoreply 你好，我现在忙，稍后回复  设置固定回复\n"
                        + "  ilink autoreply ai                         开启 AI 智能回复\n"
                        + "  ilink autoreply off                        关闭自动回复\n"
                        + "  ilink autoreply status                     查看当前状态");
                }
                doAutoReply(args);
                break;
            default:
                throw new CLIException(CLIException.ErrorCode.INVALID_COMMAND,
                    "未知子命令: " + subCommand + "\n"
                    + "可用: login, logout, status, send, reply, listen, info, autoreply");
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

        // qrData: 二维码中编码的数据，通常是登录确认 URL
        // qrCodeContent: 用于轮询扫码状态的 session token
        String qrData = ilinkService.getLoginQrCode();
        String qrCodeContent = ilinkService.getQrCodeContent();

        LOG.info("qrData (二维码编码内容): "
            + (qrData != null ? qrData.substring(0, Math.min(200, qrData.length())) : "null"));
        LOG.info("qrCodeContent (轮询token): " + qrCodeContent);

        // 判断 qrData 是 URL 还是 base64 图片
        boolean qrDataIsUrl = qrData != null
            && (qrData.startsWith("http://") || qrData.startsWith("https://"));

        File qrImageFile = null;

        // 仅当 qrData 看起来像 base64 图片时才尝试保存（>500 字符且非 URL）
        if (qrData != null && qrData.length() > 500 && !qrDataIsUrl) {
            qrImageFile = saveQrCodeImage(qrData);
        }

        // ---- 始终显示在线二维码作为主要方式 ----
        System.out.println("┌──────────────────────────────────────────────┐");
        System.out.println("│  📱 微信扫码登录                             │");
        System.out.println("│  复制以下链接到浏览器打开即可看到二维码:      │");
        System.out.println("│                                              │");

        String encodedData;
        try {
            encodedData = URLEncoder.encode(qrData, StandardCharsets.UTF_8);
        } catch (Exception e) {
            encodedData = qrData;
        }
        String qrUrl = "https://api.qrserver.com/v1/create-qr-code/?size=250x250&data=" + encodedData;

        System.out.println("│  " + qrUrl);
        System.out.println("│                                              │");
        System.out.println("└──────────────────────────────────────────────┘");
        System.out.println();

        if (qrDataIsUrl) {
            System.out.println("💡 也可以在微信中直接打开此链接:");
            System.out.println("   " + qrData);
            System.out.println();
        }

        // 本地图片文件作为备用（仅当 base64 图片保存成功时显示）
        if (qrImageFile != null) {
            System.out.println("┌──────────────────────────────────────────────┐");
            System.out.println("│  📱 备用：本地二维码图片                     │");
            System.out.println("│  文件: " + qrImageFile.getAbsolutePath());
            System.out.println("│  如在线二维码无法使用，请打开此文件扫码       │");
            System.out.println("└──────────────────────────────────────────────┘");
            System.out.println();
        }

        // 等待扫码（60 秒超时）
        System.out.println("⏳ 等待扫码确认 (60秒超时)...");
        try {
            String botId = ilinkService.waitForScan(60);
            System.out.println();
            System.out.println("✅ 登录成功！Bot ID: " + botId);
            System.out.println();
            System.out.println("提示: 使用 'ilink listen' 开始监听消息");
            System.out.println();

            // 清理临时文件
            if (qrImageFile != null && qrImageFile.exists()) {
                qrImageFile.delete();
            }
        } catch (CLIException e) {
            // 清理临时文件
            if (qrImageFile != null && qrImageFile.exists()) {
                qrImageFile.delete();
            }
            System.out.println("❌ 登录失败: " + e.getMessage());
            throw e;
        }
    }

    /**
     * 将 base64 编码的二维码图片保存为临时文件。
     * 自动检测图片格式（PNG/JPEG/GIF 等），支持标准 base64 和 URL-safe base64。
     *
     * @param base64Data base64 图片数据（可能带 data URI 前缀）
     * @return 保存的临时文件，失败返回 null
     */
    private File saveQrCodeImage(String base64Data) {
        try {
            String pureBase64 = base64Data;
            String extension = "png"; // 默认扩展名

            // 处理 data URI 前缀: data:image/png;base64,xxxx
            if (base64Data.startsWith("data:image/")) {
                int typeEnd = base64Data.indexOf(';');
                if (typeEnd > 0) {
                    String mimeType = base64Data.substring(0, typeEnd);
                    if (mimeType.contains("jpeg") || mimeType.contains("jpg")) {
                        extension = "jpg";
                    } else if (mimeType.contains("gif")) {
                        extension = "gif";
                    } else if (mimeType.contains("bmp")) {
                        extension = "bmp";
                    } else if (mimeType.contains("webp")) {
                        extension = "webp";
                    }
                }
                int commaIdx = base64Data.indexOf(',');
                if (commaIdx > 0) {
                    pureBase64 = base64Data.substring(commaIdx + 1);
                }
            }

            // 去除空白字符（换行、空格等）
            pureBase64 = pureBase64.replaceAll("\\s+", "");

            // 尝试标准 base64 解码，失败则尝试 URL-safe base64
            byte[] imageBytes;
            try {
                imageBytes = Base64.getDecoder().decode(pureBase64);
            } catch (IllegalArgumentException e) {
                LOG.info("标准 Base64 解码失败，尝试 URL-safe Base64...");
                imageBytes = Base64.getUrlDecoder().decode(pureBase64);
            }

            LOG.info("二维码图片解码成功，大小: " + imageBytes.length + " 字节");

            // 根据实际文件头检测图片格式
            String detectedExt = detectImageFormat(imageBytes);
            if (detectedExt != null) {
                extension = detectedExt;
            }

            // 保存到临时目录
            Path tempDir = Files.createTempDirectory("weather-cli-qr");
            File qrFile = tempDir.resolve("ilink_qrcode." + extension).toFile();
            qrFile.deleteOnExit();

            try (FileOutputStream fos = new FileOutputStream(qrFile)) {
                fos.write(imageBytes);
            }

            LOG.info("二维码图片已保存: " + qrFile.getAbsolutePath()
                + " (格式: " + extension + ", 大小: " + imageBytes.length + " 字节)");

            return qrFile;
        } catch (Exception e) {
            LOG.warning("保存二维码图片失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 根据文件头魔数检测图片格式。
     */
    private String detectImageFormat(byte[] bytes) {
        if (bytes == null || bytes.length < 4) return null;

        // PNG: 89 50 4E 47
        if (bytes[0] == (byte)0x89 && bytes[1] == (byte)0x50
            && bytes[2] == (byte)0x4E && bytes[3] == (byte)0x47) {
            return "png";
        }
        // JPEG: FF D8 FF
        if (bytes[0] == (byte)0xFF && bytes[1] == (byte)0xD8 && bytes[2] == (byte)0xFF) {
            return "jpg";
        }
        // GIF: 47 49 46
        if (bytes[0] == (byte)0x47 && bytes[1] == (byte)0x49 && bytes[2] == (byte)0x46) {
            return "gif";
        }
        // BMP: 42 4D
        if (bytes[0] == (byte)0x42 && bytes[1] == (byte)0x4D) {
            return "bmp";
        }
        // WebP: 52 49 46 46 ... 57 45 42 50
        if (bytes.length >= 12 && bytes[0] == (byte)0x52 && bytes[1] == (byte)0x49
            && bytes[2] == (byte)0x46 && bytes[3] == (byte)0x46
            && bytes[8] == (byte)0x57 && bytes[9] == (byte)0x45
            && bytes[10] == (byte)0x42 && bytes[11] == (byte)0x50) {
            return "webp";
        }
        return null;
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
        System.out.println("│    ilink reply    - 回复消息                  │");
        System.out.println("│    ilink listen   - 监听消息                  │");
        System.out.println("│    ilink autoreply - 自动回复                 │");
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

    // ---- 自动回复 ----

    private void doAutoReply(String[] args) throws CLIException {
        String mode = args[1].toLowerCase();

        switch (mode) {
            case "off":
                autoReplyMode = AutoReplyMode.OFF;
                autoReplyMessage = null;
                System.out.println("🔕 自动回复已关闭");
                break;

            case "ai":
                if (!aiService.isAvailable()) {
                    throw new CLIException(CLIException.ErrorCode.CONFIG_ERROR,
                        "AI 自动回复需要先配置 API Key！\n"
                        + "请编辑 src/main/resources/config.properties 设置:\n"
                        + "  deepseek.api.key=sk-xxx\n"
                        + "或设置环境变量: DEEPSEEK_API_KEY=sk-xxx");
                }
                autoReplyMode = AutoReplyMode.AI;
                autoReplyMessage = null;
                System.out.println("🤖 AI 智能自动回复已开启");
                System.out.println("   使用 'ilink listen' 开始监听，收到消息会自动调用 AI 回复");
                break;

            case "status":
                System.out.println();
                System.out.println("┌──────────────────────────────────────────────┐");
                System.out.println("│  📡 自动回复状态                              │");
                System.out.println("├──────────────────────────────────────────────┤");
                switch (autoReplyMode) {
                    case OFF:
                        System.out.println("│  状态:   已关闭                               │");
                        break;
                    case FIXED:
                        System.out.println("│  模式:   固定消息                             │");
                        System.out.println("│  消息:   " + autoReplyMessage);
                        break;
                    case AI:
                        System.out.println("│  模式:   AI 智能回复                          │");
                        System.out.println("│  模型:   " + (aiService.isAvailable() ? "已配置" : "未配置"));
                        break;
                }
                System.out.println("└──────────────────────────────────────────────┘");
                System.out.println();
                break;

            default:
                // 当作固定回复消息
                String msg = String.join(" ", args);
                // 去掉 "autoreply" 前缀
                if (msg.toLowerCase().startsWith("autoreply ")) {
                    msg = msg.substring("autoreply ".length());
                }
                autoReplyMode = AutoReplyMode.FIXED;
                autoReplyMessage = msg;
                System.out.println("📝 自动回复已设置:");
                System.out.println("   \"" + autoReplyMessage + "\"");
                System.out.println();
                System.out.println("   使用 'ilink listen' 开始监听，收到消息会自动回复");
                break;
        }
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

        // 线程安全的 userId 持有者，用于在监听回调线程和主线程之间传递
        AtomicReference<String> lastUserId = new AtomicReference<>();

        // 注册消息处理器（含自动回复）
        ILinkService.MessageListener listener = (userId, text, contextToken) -> {
            lastUserId.set(userId);  // 记住最后发消息的用户
            System.out.println();
            System.out.println("📩 收到消息 [" + userId + "]:");
            System.out.println("   " + text);

            // ---- 自动回复 ----
            if (autoReplyMode == AutoReplyMode.FIXED && autoReplyMessage != null) {
                try {
                    ilinkService.reply(userId, autoReplyMessage);
                    System.out.println("   🤖 已自动回复: \"" + autoReplyMessage + "\"");
                    lastUserId.set(null); // 自动回复后清除，避免重复
                } catch (CLIException e) {
                    System.out.println("   ❌ 自动回复失败: " + e.getMessage());
                }
            } else if (autoReplyMode == AutoReplyMode.AI) {
                System.out.println("   🤖 AI 思考中，请稍候...");
                try {
                    String aiReply = aiService.chat(text);
                    ilinkService.reply(userId, aiReply);
                    System.out.println("   🤖 AI 已自动回复: \""
                        + (aiReply.length() > 50 ? aiReply.substring(0, 50) + "..." : aiReply) + "\"");
                    lastUserId.set(null); // 自动回复后清除
                } catch (CLIException e) {
                    System.out.println("   ❌ AI 自动回复失败: " + e.getMessage());
                }
            }

            // 如果自动回复已处理，不再提示手动回复
            if (autoReplyMode != AutoReplyMode.OFF && lastUserId.get() == null) {
                System.out.println();
            } else {
                System.out.print("💬 输入回复内容 (回车跳过) > ");
            }
        };

        ilinkService.addMessageListener(listener);

        // 开始后台轮询
        ilinkService.startPolling();

        // 交互式回复
        Scanner scanner = new Scanner(System.in, java.nio.charset.StandardCharsets.UTF_8);

        while (true) {
            String input = scanner.nextLine().trim();

            if ("stop".equalsIgnoreCase(input)) {
                break;
            }

            if (input.isEmpty()) {
                continue;
            }

            // 发送回复
            String replyTo = lastUserId.getAndSet(null);
            if (replyTo != null) {
                try {
                    ilinkService.reply(replyTo, input);
                    System.out.println("   ✅ 已回复 → [" + replyTo + "]");
                } catch (CLIException e) {
                    System.out.println("   ❌ 发送失败: " + e.getMessage());
                }
            } else {
                System.out.println("   ⚠ 暂无待回复消息，使用 'ilink send <用户ID> " + input + "' 发送");
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
