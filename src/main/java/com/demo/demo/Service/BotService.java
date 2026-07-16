package com.demo.demo.Service;

import com.lth.wechat.ilink.ILinkClient;
import com.lth.wechat.ilink.LoginCredentials;
import com.lth.wechat.ilink.dto.message.MessageItemDto;
import com.lth.wechat.ilink.dto.message.ReceiveMessagesResult;
import com.lth.wechat.ilink.dto.message.WeixinMessageDto;
import com.lth.wechat.ilink.entity.login.LoginStatusResp;
import com.lth.wechat.ilink.entity.login.QrCodeResp;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bot 核心服务：管理登录状态、二维码、消息收发
 */
@Service
public class BotService {

    private final ILinkClient client = new ILinkClient();

    // 状态（线程安全）
    private final AtomicReference<String> qrCodeBase64 = new AtomicReference<>(); // 二维码 base64
    private final AtomicReference<String> qrCodeUrl    = new AtomicReference<>(); // 二维码链接
    private final AtomicReference<String> statusText   = new AtomicReference<>("未启动");
    private final AtomicReference<LoginCredentials> credentials = new AtomicReference<>();
    private volatile boolean loggedIn = false;

    // 消息和日志
    private final List<String> logs     = new CopyOnWriteArrayList<>();
    private final List<Msg> messages    = new CopyOnWriteArrayList<>();
    private String cursor = "";
    private Thread listenThread;

    // 自动回复处理器（设置后，收到消息会自动回复）
    private volatile ReplyHandler autoReplyHandler;

    // ==================== 公开方法 ====================

    /** 启动登录流程（异步） */
    public synchronized void startLogin() {
        startLogin(false);
    }

    /** 强制重启登录 */
    public synchronized void restartLogin() {
        startLogin(true);
    }

    private synchronized void startLogin(boolean force) {
        if (loggedIn && !force) return;
        if (force) {
            loggedIn = false;
            credentials.set(null);
            qrCodeBase64.set(null);
            qrCodeUrl.set(null);
            messages.clear();
            logs.clear();
            cursor = "";
            if (listenThread != null) listenThread.interrupt();
        }
        statusText.set("正在获取二维码...");

        new Thread(() -> {
            try {
                // 1. 获取二维码
                QrCodeResp qr = client.getBotQrCode();
                String content = qr.getQrcode();
                String imgData = qr.getQrcode_img_content();

                qrCodeUrl.set(content);

                // 2. 处理二维码图片
                String qrBase64 = buildQrCodeBase64(content, imgData);
                qrCodeBase64.set(qrBase64);

                log("二维码已获取，请扫描");
                statusText.set("等待扫码...");

                // 3. 轮询等待扫码
                for (int i = 0; i < 150; i++) { // 最多等 300 秒
                    Thread.sleep(2000);
                    LoginStatusResp s = client.getQrCodeStatus(content);
                    String code = s.getStatus();
                    log("状态: " + code);

                    if ("confirmed".equals(code)) {
                        credentials.set(ILinkClient.createCredentials(content, s));
                        loggedIn = true;
                        statusText.set("已登录 " + credentials.get().getUserId());
                        log("登录成功！Bot ID: " + credentials.get().getUserId());
                        startListening();  // 开始监听消息
                        return;
                    }
                    if ("expired".equals(code)) {
                        statusText.set("二维码已过期，请刷新页面重试");
                        return;
                    }
                }
                statusText.set("登录超时，请刷新页面重试");

            } catch (Exception e) {
                log("错误: " + e.getMessage());
                statusText.set("错误: " + e.getMessage());
            }
        }).start();
    }

    /** 启动消息监听 */
    private void startListening() {
        listenThread = new Thread(() -> {
            while (loggedIn) {
                try {
                    ReceiveMessagesResult result = client.receiveMessages(credentials.get(), cursor);
                    if (result == null || !result.hasMessages()) {
                        Thread.sleep(1000);
                        continue;
                    }
                    String nextCursor = result.getNextCursor();
                    if (nextCursor != null && !nextCursor.isEmpty()) cursor = nextCursor;

                    for (WeixinMessageDto dto : result.getMessages()) {
                        if (!dto.hasItems()) continue;
                        for (MessageItemDto item : dto.getItemList()) {
                            if (!item.isText()) continue;

                            String text = item.getText();
                            String fromUser = dto.getFromUserId();
                            String clientId = dto.getClientId();

                            Msg msg = new Msg(fromUser, clientId, text);
                            messages.add(msg);
                            log(fromUser + ": " + text);

                            // --- 自动回复 ---
                            ReplyHandler handler = autoReplyHandler;
                            if (handler != null) {
                                String reply = handler.onMessage(fromUser, text);
                                if (reply != null && !reply.isEmpty()) {
                                    sendReply(fromUser, clientId, reply);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    if (loggedIn) log("监听异常: " + e.getMessage());
                    try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
                }
            }
        });
        listenThread.setDaemon(true);
        listenThread.start();
    }

    /** 发送文本回复 */
    public void sendReply(String toUserId, String clientId, String text) {
        if (!loggedIn) { log("未登录，无法发送"); return; }
        try {
            client.sendTextMessage(credentials.get(), toUserId, clientId, text);
            log("回复 -> " + toUserId + ": " + text);
        } catch (Exception e) {
            log("发送失败: " + e.getMessage());
        }
    }

    // ==================== 前端查询接口 ====================

    public String getQrCodeBase64()  { return qrCodeBase64.get(); }
    public String getQrCodeUrl()     { return qrCodeUrl.get(); }
    public String getStatusText()    { return statusText.get(); }
    public boolean isLoggedIn()      { return loggedIn; }
    public List<String> getLogs()    { return new ArrayList<>(logs); }
    public List<Msg> getMessages()   { return new ArrayList<>(messages); }

    /** 拉取新消息（调用后清空） */
    public List<Msg> pollMessages() {
        if (messages.isEmpty()) return Collections.emptyList();
        List<Msg> result = new ArrayList<>(messages);
        messages.clear();
        return result;
    }

    private void log(String msg) {
        System.out.println("[Bot] " + msg);
        logs.add(msg);
        if (logs.size() > 200) logs.remove(0); // 保留最近 200 条
    }

    /** 把 SDK 返回的各种格式统一转成纯 base64（前端 img 标签直接用） */
    private String buildQrCodeBase64(String content, String imgData) {
        try {
            byte[] imageBytes = null;

            // 优先处理：如果是纯 base64（含 data URI 前缀）
            if (imgData != null && !imgData.isEmpty()) {
                if (imgData.contains(",")) {
                    // 带 data URI 前缀: data:image/png;base64,xxxx
                    imageBytes = Base64.getDecoder().decode(imgData.substring(imgData.indexOf(",") + 1));
                } else if (!imgData.startsWith("http")) {
                    // 可能是纯 base64
                    try {
                        imageBytes = Base64.getDecoder().decode(imgData);
                    } catch (IllegalArgumentException ignored) {}
                }
            }

            // SDK 返回的是 URL/Token，用 qrserver API 生成真正的二维码图片
            if (imageBytes == null) {
                String data = (imgData != null && imgData.startsWith("http")) ? imgData : content;
                if (data == null || data.isEmpty()) data = content;
                String api = "https://api.qrserver.com/v1/create-qr-code/?size=300x300&data="
                        + java.net.URLEncoder.encode(data, "UTF-8");
                imageBytes = new java.net.URI(api).toURL().openStream().readAllBytes();
            }

            if (imageBytes != null) {
                return Base64.getEncoder().encodeToString(imageBytes);
            }
        } catch (Exception e) {
            log("生成二维码图片失败: " + e.getMessage());
        }
        return "";
    }

    // ==================== 自动回复 ====================

    /**
     * 设置自动回复规则
     * 入参：(发信人ID, 消息文本) -> 返回要回复的文本，返回 null 或空字符串则不回复
     */
    public void setAutoReply(ReplyHandler handler) {
        this.autoReplyHandler = handler;
    }

    /** 自动回复处理器接口 */
    @FunctionalInterface
    public interface ReplyHandler {
        String onMessage(String fromUserId, String text);
    }

    // ==================== DTO ====================

    public static class Msg {
        public String fromUser;
        public String clientId;
        public String content;
        public long time = System.currentTimeMillis();

        public Msg(String f, String c, String t) { fromUser = f; clientId = c; content = t; }
    }
}
