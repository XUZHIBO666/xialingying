package com.weathercli.service;

import com.google.gson.*;
import com.weathercli.exception.CLIException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * iLink 微信 Bot 服务 — 基于腾讯 iLink 协议实现个人微信消息收发。
 *
 * ## 通信机制（长轮询）
 *
 * iLink 使用 HTTP 长轮询（Long Polling）机制：
 * - 客户端调用 getupdates 接口，服务端挂起连接最多 35 秒
 * - 有新消息时立即返回，无消息则超时后返回空
 * - 客户端处理后立即发起下一次轮询
 * - cursor 游标由 SDK/服务端管理，确保不丢消息
 *
 * ## 消息流程
 * 1. 扫码登录 → 获取 bot_token
 * 2. 长轮询 getupdates → 接收消息（含 context_token）
 * 3. 用 context_token 调用 sendmessage → 回复消息
 *
 * ## 限制
 * - 不支持群聊
 * - 用户需先发消息，Bot 才能回复（24h 窗口）
 * - 不能主动发起对话
 *
 * 参考文档: https://docs.openclaw.ai/zh-CN/channels/wechat
 * GitHub:   https://github.com/hao-ji-xing/openclaw-weixin
 */
public class ILinkService {

    private static final Logger LOG = Logger.getLogger(ILinkService.class.getName());

    // ---- iLink API 端点 ----
    private static final String BASE_URL = "https://ilinkai.weixin.qq.com";
    private static final String GET_QRCODE = "/ilink/bot/get_bot_qrcode?bot_type=3";
    private static final String GET_QRCODE_STATUS = "/ilink/bot/get_qrcode_status";
    private static final String GET_UPDATES = "/ilink/bot/getupdates";
    private static final String SEND_MESSAGE = "/ilink/bot/sendmessage";
    private static final String GET_CONFIG = "/ilink/bot/getconfig";
    private static final String SEND_TYPING = "/ilink/bot/sendtyping";

    // ---- 连接状态 ----
    private volatile ILinkState state = ILinkState.DISCONNECTED;
    private volatile String botToken;
    private volatile String botId;
    private volatile String qrCodeContent;
    private String updatesCursor = "";       // 游标，用于长轮询
    private final Map<String, String> contextTokenCache = new ConcurrentHashMap<>(); // userId → contextToken

    private final HttpClient httpClient;
    private final Gson gson;
    private ScheduledExecutorService pollExecutor;
    private final List<MessageListener> listeners = new CopyOnWriteArrayList<>();
    private ScheduledExecutorService heartbeatExecutor;

    public ILinkService() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }

    // ============================================
    // 登录流程
    // ============================================

    /**
     * 第一步: 获取登录二维码。
     * 返回二维码内容（可在终端或浏览器中渲染）。
     */
    public String getLoginQrCode() throws CLIException {
        LOG.info("获取 iLink 登录二维码...");

        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + GET_QRCODE))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

            LOG.info("二维码接口响应: " + response.statusCode());

            if (response.statusCode() != 200) {
                throw new CLIException(CLIException.ErrorCode.API_ERROR,
                    "获取登录二维码失败: HTTP " + response.statusCode());
            }

            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            qrCodeContent = getJsonString(json, "qrcode");
            String qrImgContent = getJsonString(json, "qrcode_img_content");

            state = ILinkState.WAITING_FOR_SCAN;
            LOG.info("二维码获取成功，等待扫码...");

            return qrImgContent != null ? qrImgContent : qrCodeContent;

        } catch (IOException e) {
            LOG.severe("获取二维码网络错误: " + e.getMessage());
            throw new CLIException(CLIException.ErrorCode.NETWORK_ERROR,
                "无法连接 iLink 服务，请检查网络。", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CLIException(CLIException.ErrorCode.NETWORK_ERROR,
                "请求被中断。", e);
        }
    }

    /**
     * 第二步: 轮询等待扫码确认。
     * 阻塞直到用户扫码确认，或超时。
     *
     * @param timeoutSeconds 超时秒数
     * @return 登录成功后的 bot_id
     */
    public String waitForScan(int timeoutSeconds) throws CLIException {
        if (qrCodeContent == null) {
            throw new CLIException(CLIException.ErrorCode.CONFIG_ERROR,
                "请先调用 getLoginQrCode() 获取二维码。");
        }

        LOG.info("等待扫码确认 (超时: " + timeoutSeconds + "秒)...");

        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;

        while (System.currentTimeMillis() < deadline) {
            try {
                String url = BASE_URL + GET_QRCODE_STATUS + "?qrcode="
                    + java.net.URLEncoder.encode(qrCodeContent,
                        java.nio.charset.StandardCharsets.UTF_8);

                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

                HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    Thread.sleep(2000);
                    continue;
                }

                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                String status = getJsonString(json, "status");

                LOG.info("扫码状态: " + status);

                switch (status) {
                    case "confirmed":
                        botToken = getJsonString(json, "bot_token");
                        state = ILinkState.CONNECTED;
                        LOG.info("登录成功! bot_token="
                            + (botToken.length() > 20
                                ? botToken.substring(0, 20) + "..."
                                : botToken));

                        // 获取 bot 信息
                        fetchBotConfig();
                        return botToken;

                    case "pending":
                        // 继续等待
                        break;

                    case "expired":
                        state = ILinkState.DISCONNECTED;
                        throw new CLIException(CLIException.ErrorCode.API_ERROR,
                            "二维码已过期，请重新获取。");

                    default:
                        LOG.warning("未知状态: " + status);
                        break;
                }

                Thread.sleep(2000); // 2秒轮询间隔

            } catch (IOException e) {
                LOG.warning("轮询网络异常: " + e.getMessage());
                try { Thread.sleep(3000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt(); break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CLIException(CLIException.ErrorCode.NETWORK_ERROR,
                    "扫码等待被中断。");
            }
        }

        state = ILinkState.DISCONNECTED;
        throw new CLIException(CLIException.ErrorCode.API_ERROR,
            "扫码超时（" + timeoutSeconds + "秒），请重新登录。");
    }

    /**
     * 获取 Bot 配置信息（包含 bot_id）。
     */
    private void fetchBotConfig() throws CLIException {
        try {
            JsonObject body = new JsonObject();
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + GET_CONFIG))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("AuthorizationType", "ilink_bot_token")
                .header("Authorization", "Bearer " + botToken)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

            HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            botId = getJsonString(json, "bot_id");
            LOG.info("Bot ID: " + botId);
        } catch (Exception e) {
            LOG.warning("获取 Bot 配置失败: " + e.getMessage());
        }
    }

    // ============================================
    // 消息接收（长轮询）
    // ============================================

    /**
     * 开始长轮询接收消息（非阻塞，后台线程运行）。
     */
    public void startPolling() {
        if (state != ILinkState.CONNECTED) {
            LOG.warning("未登录，无法开始轮询");
            return;
        }
        if (pollExecutor != null && !pollExecutor.isShutdown()) {
            LOG.warning("轮询已在运行中");
            return;
        }

        LOG.info("开始 iLink 消息轮询...");
        pollExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ilink-poll");
            t.setDaemon(true);
            return t;
        });

        pollExecutor.submit(this::pollLoop);
    }

    /**
     * 长轮询循环。
     */
    private void pollLoop() {
        while (state == ILinkState.CONNECTED && !Thread.currentThread().isInterrupted()) {
            try {
                JsonObject requestBody = new JsonObject();
                requestBody.addProperty("get_updates_buf", updatesCursor);
                JsonObject baseInfo = new JsonObject();
                baseInfo.addProperty("channel_version", "1.0.2");
                requestBody.add("base_info", baseInfo);

                String randomUin = Base64.getEncoder().encodeToString(
                    String.valueOf(new Random().nextInt(999999999)).getBytes());

                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + GET_UPDATES))
                    .timeout(Duration.ofSeconds(40))
                    .header("Content-Type", "application/json")
                    .header("AuthorizationType", "ilink_bot_token")
                    .header("X-WECHAT-UIN", randomUin)
                    .header("Authorization", "Bearer " + botToken)
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(requestBody)))
                    .build();

                HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    LOG.warning("轮询返回非 200: " + response.statusCode());
                    Thread.sleep(3000);
                    continue;
                }

                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();

                // 更新游标
                if (json.has("get_updates_buf")) {
                    updatesCursor = getJsonString(json, "get_updates_buf");
                }

                // 处理消息
                JsonArray msgs = json.getAsJsonArray("msgs");
                if (msgs != null && !msgs.isEmpty()) {
                    LOG.info("收到 " + msgs.size() + " 条消息");
                    for (JsonElement el : msgs) {
                        processMessage(el.getAsJsonObject());
                    }
                }

            } catch (IOException e) {
                LOG.warning("轮询网络异常: " + e.getMessage());
                try { Thread.sleep(3000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt(); break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOG.warning("轮询异常: " + e.getMessage());
                try { Thread.sleep(3000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt(); break;
                }
            }
        }
        LOG.info("iLink 轮询已停止");
    }

    /**
     * 处理收到的消息。
     */
    private void processMessage(JsonObject msg) {
        try {
            String fromUserId = getJsonString(msg, "from_user_id");
            String contextToken = getJsonString(msg, "context_token");

            // 缓存 context_token（按用户）
            if (contextToken != null && !contextToken.isEmpty()) {
                contextTokenCache.put(fromUserId, contextToken);
            }

            // 提取文本内容
            JsonArray items = msg.getAsJsonArray("item_list");
            if (items != null) {
                for (JsonElement el : items) {
                    JsonObject item = el.getAsJsonObject();
                    int itemType = item.get("type").getAsInt();
                    if (itemType == 1) { // 文本类型
                        String text = item.getAsJsonObject("text_item")
                            .get("text").getAsString();
                        LOG.info("收到消息 [" + fromUserId + "]: " + text);

                        // 通知所有监听器
                        for (MessageListener listener : listeners) {
                            listener.onMessage(fromUserId, text, contextToken);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.warning("处理消息异常: " + e.getMessage());
        }
    }

    // ============================================
    // 消息发送
    // ============================================

    /**
     * 发送文本消息给指定用户。
     *
     * @param toUserId 目标用户 ID (格式: xxx@im.wechat)
     * @param text     文本内容
     */
    public void sendText(String toUserId, String text) throws CLIException {
        if (state != ILinkState.CONNECTED) {
            throw new CLIException(CLIException.ErrorCode.CONFIG_ERROR,
                "未登录 iLink，请先执行 ilink login 扫码登录。");
        }

        String contextToken = contextTokenCache.get(toUserId);
        if (contextToken == null || contextToken.isEmpty()) {
            LOG.warning("未找到用户 " + toUserId + " 的 context_token");
        }

        LOG.info("发送消息 → [" + toUserId + "]: " + text);

        try {
            // 构建消息体
            JsonObject textItem = new JsonObject();
            textItem.addProperty("type", 1);
            JsonObject textContent = new JsonObject();
            textContent.addProperty("text", text);
            textItem.add("text_item", textContent);

            JsonArray itemList = new JsonArray();
            itemList.add(textItem);

            JsonObject msg = new JsonObject();
            msg.addProperty("to_user_id", toUserId);
            msg.addProperty("message_type", 2);
            msg.addProperty("message_state", 2);
            if (contextToken != null) {
                msg.addProperty("context_token", contextToken);
            }
            msg.add("item_list", itemList);

            JsonObject requestBody = new JsonObject();
            requestBody.add("msg", msg);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + SEND_MESSAGE))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("AuthorizationType", "ilink_bot_token")
                .header("Authorization", "Bearer " + botToken)
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(requestBody)))
                .build();

            HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

            LOG.info("发送消息响应: " + response.statusCode());

            if (response.statusCode() != 200) {
                LOG.severe("发送失败: " + response.body());
                throw new CLIException(CLIException.ErrorCode.API_ERROR,
                    "消息发送失败 (HTTP " + response.statusCode() + ")");
            }

        } catch (IOException e) {
            throw new CLIException(CLIException.ErrorCode.NETWORK_ERROR,
                "消息发送网络失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CLIException(CLIException.ErrorCode.NETWORK_ERROR,
                "消息发送被中断。", e);
        }
    }

    /**
     * 给收到消息的用户自动回复。
     */
    public void reply(String toUserId, String text) throws CLIException {
        sendText(toUserId, text);
    }

    // ============================================
    // 连接管理
    // ============================================

    /**
     * 断开连接。
     */
    public void disconnect() {
        LOG.info("断开 iLink 连接...");
        state = ILinkState.DISCONNECTED;

        if (pollExecutor != null) {
            pollExecutor.shutdownNow();
            pollExecutor = null;
        }
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
            heartbeatExecutor = null;
        }

        botToken = null;
        botId = null;
        qrCodeContent = null;
        updatesCursor = "";
        contextTokenCache.clear();

        LOG.info("iLink 已断开");
    }

    /**
     * 注册消息监听器。
     */
    public void addMessageListener(MessageListener listener) {
        listeners.add(listener);
    }

    /**
     * 移除消息监听器。
     */
    public void removeMessageListener(MessageListener listener) {
        listeners.remove(listener);
    }

    // ---- Getters ----

    public ILinkState getState() { return state; }
    public String getBotId() { return botId; }
    public String getQrCodeContent() { return qrCodeContent; }
    public boolean isConnected() { return state == ILinkState.CONNECTED; }

    // ---- 工具方法 ----

    private String getJsonString(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        return (el != null && !el.isJsonNull()) ? el.getAsString() : null;
    }

    // ============================================
    // 内部类型
    // ============================================

    /** iLink 连接状态 */
    public enum ILinkState {
        DISCONNECTED("未连接"),
        WAITING_FOR_SCAN("等待扫码"),
        CONNECTED("已连接"),
        ERROR("错误");

        private final String description;
        ILinkState(String desc) { this.description = desc; }
        public String getDescription() { return description; }
    }

    /** 消息监听器接口 */
    @FunctionalInterface
    public interface MessageListener {
        void onMessage(String userId, String text, String contextToken);
    }
}
