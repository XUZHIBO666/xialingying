package com.demo.demo.Service;

import com.demo.demo.Service.throttle.UserRateLimiter;
import com.demo.demo.Service.voice.VoiceMessageHandler;
import com.demo.demo.Service.voice.VoiceMessageService;
import com.lth.wechat.ilink.ILinkClient;
import com.lth.wechat.ilink.LoginCredentials;
import com.lth.wechat.ilink.dto.message.ImageContent;
import com.lth.wechat.ilink.dto.message.MessageItemDto;
import com.lth.wechat.ilink.dto.message.ReceiveMessagesResult;
import com.lth.wechat.ilink.dto.message.VoiceContent;
import com.lth.wechat.ilink.dto.message.WeixinMessageDto;
import com.lth.wechat.ilink.entity.login.LoginStatusResp;
import com.lth.wechat.ilink.entity.login.QrCodeResp;
import com.lth.wechat.ilink.exception.ILinkSessionExpiredException;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单个微信Bot实例 —— 封装独立的 iLink 登录状态、二维码、消息收发
 *
 * <p>每个实例拥有：
 * <ul>
 *   <li>独立的 ILinkClient 和登录凭证</li>
 *   <li>独立的二维码状态和登录线程</li>
 *   <li>独立的消息监听线程</li>
 *   <li>共享的业务处理器（AIService、VoiceHandler等）</li>
 * </ul>
 */
@Slf4j
public class BotInstance {

    // ==================== 实例标识 ====================

    /** Bot实例ID（用于前端区分） */
    @Getter
    private final String instanceId;

    /** Bot显示名称（可选，用于UI展示） */
    @Getter
    private final String displayName;

    // ==================== iLink 客户端与状态 ====================

    private final ILinkClient client;
    private final AtomicReference<LoginCredentials> credentials = new AtomicReference<>();

    @Getter
    private volatile boolean loggedIn = false;

    private final AtomicReference<String> qrCodeBase64 = new AtomicReference<>();
    private final AtomicReference<String> qrCodeUrl = new AtomicReference<>();
    private final AtomicReference<String> statusText = new AtomicReference<>("未启动");

    // ==================== 线程管理 ====================

    private final ExecutorService replyExecutor;
    private Thread loginThread;
    private Thread listenThread;
    private final AtomicInteger loginSession = new AtomicInteger();
    private volatile boolean shuttingDown = false;

    // ==================== 消息处理 ====================

    private final List<String> logs = new CopyOnWriteArrayList<>();
    private final List<Msg> messages = new CopyOnWriteArrayList<>();
    private final ConcurrentMap<String, ReplyTarget> replyTargets = new ConcurrentHashMap<>();
    private final List<String> replyTargetOrder = new CopyOnWriteArrayList<>();
    private final ConcurrentMap<String, Long> processedMessageIds = new ConcurrentHashMap<>();
    private final List<String> processedMessageOrder = new CopyOnWriteArrayList<>();
    private final ConcurrentMap<String, Object> userReplyLocks = new ConcurrentHashMap<>();
    private String cursor = "";

    // ==================== 业务处理器（共享） ====================

    private volatile BotService.ReplyHandler autoReplyHandler;
    private volatile BotService.ImageReplyHandler imageReplyHandler;
    private volatile VoiceMessageHandler voiceMessageHandler;

    // ==================== 速率限制 ====================

    private final UserRateLimiter rateLimiter;
    private final AtomicLong totalRateLimitAccepted = new AtomicLong();
    private final AtomicLong totalRateLimitRejected = new AtomicLong();

    // ==================== 常量配置 ====================

    private static final int REPLY_WORKER_COUNT = Integer.parseInt(
            System.getenv().getOrDefault("BOT_REPLY_THREADS", "4"));
    private static final int REPLY_QUEUE_CAPACITY = Integer.parseInt(
            System.getenv().getOrDefault("BOT_REPLY_QUEUE_CAPACITY", "200"));
    private static final int REPLY_TARGET_CAPACITY = 200;
    private static final int PROCESSED_MESSAGE_CAPACITY = 1000;
    private static final long PROCESSED_MESSAGE_TTL_MS = TimeUnit.MINUTES.toMillis(10);
    private static final AtomicInteger INSTANCE_SEQUENCE = new AtomicInteger();

    public BotInstance(String displayName) {
        this.instanceId = "bot-" + String.format("%03d", INSTANCE_SEQUENCE.incrementAndGet());
        this.displayName = displayName != null ? displayName : this.instanceId;
        this.client = new ILinkClient();
        this.replyExecutor = createReplyExecutor();
        this.rateLimiter = new UserRateLimiter(
                Double.parseDouble(System.getenv().getOrDefault("BOT_RATE_LIMIT_PER_SECOND", "0.5")),
                Integer.parseInt(System.getenv().getOrDefault("BOT_RATE_LIMIT_BURST", "2")));

        log.info("[{}] Bot实例初始化完成", this.instanceId);
    }

    // ==================== 生命周期管理 ====================

    /** 启动登录流程（异步） */
    public synchronized void startLogin() {
        startLogin(false);
    }

    /** 强制重启登录 */
    public synchronized void restartLogin() {
        startLogin(true);
    }

    private synchronized void startLogin(boolean force) {
        if (shuttingDown) return;
        if (loggedIn && !force) return;
        if (!force && loginThread != null && loginThread.isAlive()) return;

        int session = loginSession.incrementAndGet();
        if (loginThread != null) loginThread.interrupt();

        if (force) {
            loggedIn = false;
            credentials.set(null);
            qrCodeBase64.set(null);
            qrCodeUrl.set(null);
            messages.clear();
            replyTargets.clear();
            replyTargetOrder.clear();
            processedMessageIds.clear();
            processedMessageOrder.clear();
            userReplyLocks.clear();
            logs.clear();
            cursor = "";
            if (listenThread != null) listenThread.interrupt();
        }

        statusText.set("正在获取二维码...");
        log.info("[{}] 开始获取登录二维码...", this.instanceId);

        loginThread = new Thread(() -> {
            try {
                QrCodeResp qr = client.getBotQrCode();
                if (!isCurrentLoginSession(session)) return;

                String content = qr.getQrcode();
                String imgData = qr.getQrcode_img_content();

                qrCodeUrl.set(content);
                log.info("[{}] 二维码已获取，长度 {}", this.instanceId, content == null ? 0 : content.length());

                String qrBase64 = buildQrCodeBase64(content, imgData);
                qrCodeBase64.set(qrBase64);

                displayLog("二维码已获取，请扫描");
                statusText.set("等待扫码...");

                for (int i = 0; i < 150; i++) {
                    if (!isCurrentLoginSession(session) || Thread.currentThread().isInterrupted()) return;
                    Thread.sleep(2000);
                    LoginStatusResp s;
                    try {
                        s = client.getQrCodeStatus(content);
                    } catch (Exception e) {
                        if (!isCurrentLoginSession(session)) return;
                        log.warn("[{}] 查询扫码状态失败: {}", this.instanceId, e.getMessage());
                        continue;
                    }

                    String code = s.getStatus();
                    if ("confirmed".equals(code)) {
                        credentials.set(ILinkClient.createCredentials(content, s));
                        loggedIn = true;
                        String botUserId = credentials.get().getUserId();
                        statusText.set("已登录 " + botUserId);
                        log.info("[{}] 登录成功！Bot ID: {}", this.instanceId, maskUserId(botUserId));
                        displayLog("登录成功！Bot ID: " + botUserId);
                        startListening();
                        return;
                    }
                    if ("expired".equals(code)) {
                        log.warn("[{}] 二维码已过期", this.instanceId);
                        statusText.set("二维码已过期，请刷新页面重试");
                        return;
                    }
                }
                log.warn("[{}] 登录超时", this.instanceId);
                statusText.set("登录超时，请刷新页面重试");

            } catch (Exception e) {
                if (isCurrentLoginSession(session)) {
                    log.error("[{}] 登录流程异常: {}", this.instanceId, e.getMessage(), e);
                    displayLog("错误: " + e.getMessage());
                    statusText.set("错误: " + e.getMessage());
                }
            }
        });
        loginThread.start();
    }

    private boolean isCurrentLoginSession(int session) {
        return loginSession.get() == session;
    }

    private void startListening() {
        if (shuttingDown) return;
        if (listenThread != null && listenThread.isAlive()) {
            log.info("[{}] 消息监听线程已在运行", this.instanceId);
            return;
        }

        log.info("[{}] 启动消息监听线程...", this.instanceId);
        listenThread = new Thread(() -> {
            log.info("[{}] 消息监听已启动，等待新消息...", this.instanceId);
            while (loggedIn) {
                try {
                    ReceiveMessagesResult result = client.receiveMessages(credentials.get(), cursor);
                    if (result == null || !result.hasMessages()) {
                        Thread.sleep(1000);
                        continue;
                    }

                    String nextCursor = result.getNextCursor();
                    if (nextCursor != null && !nextCursor.isEmpty()) {
                        cursor = nextCursor;
                    }

                    for (WeixinMessageDto dto : result.getMessages()) {
                        if (!dto.isUserMessage() || !dto.hasItems()) continue;

                        for (MessageItemDto item : dto.getItemList()) {
                            String fromUser = dto.getFromUserId();
                            String contextToken = dto.getContextToken();
                            String messageId = String.valueOf(dto.getMessageId());
                            String itemMsgId = String.valueOf(item.getMsgId());

                            if (!markInboundMessageIfNew(messageId, itemMsgId)) {
                                continue;
                            }

                            if (item.isText()) {
                                processTextMessage(fromUser, contextToken, item.getText());
                            } else if (item.isImage()) {
                                processImageItem(fromUser, contextToken, item.getImage());
                            } else if (item.isVoice()) {
                                processVoiceMessage(fromUser, contextToken, item.getVoice());
                            }
                        }
                    }
                } catch (ILinkSessionExpiredException e) {
                    markSessionExpired(e);
                    break;
                } catch (Exception e) {
                    if (loggedIn) {
                        log.error("[{}] 消息监听异常: {}", this.instanceId, e.getMessage(), e);
                    }
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            log.info("[{}] 消息监听已停止", this.instanceId);
        });
        listenThread.setDaemon(true);
        listenThread.start();
    }

    @PreDestroy
    public synchronized void shutdown() {
        if (shuttingDown) return;
        shuttingDown = true;
        log.info("[{}] 开始优雅关闭...", this.instanceId);

        loggedIn = false;

        if (loginThread != null && loginThread.isAlive()) {
            loginThread.interrupt();
        }
        if (listenThread != null && listenThread.isAlive()) {
            listenThread.interrupt();
        }

        replyExecutor.shutdown();
        try {
            if (!replyExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                replyExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            replyExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        credentials.set(null);
        log.info("[{}] 优雅关闭完成", this.instanceId);
    }

    // ==================== 消息处理（委托给共享处理器） ====================

    void processTextMessage(String fromUser, String contextToken, String text) {
        Msg msg = new Msg(fromUser, rememberReplyTarget(fromUser, contextToken), text);
        messages.add(msg);
        displayLog(fromUser + ": " + text);

        if (autoReplyHandler != null) {
            submitReplyTask(fromUser, contextToken, () -> runAutoReply(fromUser, contextToken, text));
        }
    }

    private void processImageItem(String fromUser, String contextToken, ImageContent image) {
        if (image == null) {
            displayLog(fromUser + ": [图片消息为空]");
            return;
        }

        String mediaParam = image.getEncryptQueryParam();
        if (mediaParam == null || mediaParam.isBlank()) {
            mediaParam = image.getUrl();
        }
        final String aesKey = image.getAesKey();
        final String downloadParam = mediaParam;

        submitReplyTask(fromUser, contextToken, () -> {
            try {
                byte[] imageBytes = client.downloadMedia(downloadParam, aesKey);
                processImageMessage(fromUser, contextToken, imageBytes);
            } catch (Exception e) {
                log.error("[{}] 图片下载失败: {}", this.instanceId, e.getMessage(), e);
                sendReply(fromUser, contextToken, "收到图片了，但图片下载失败，暂时无法识别。");
            }
        });
    }

    private void processVoiceMessage(String fromUser, String contextToken, VoiceContent voice) {
        if (voice == null) {
            sendReply(fromUser, contextToken, "语音消息解析失败");
            return;
        }

        String wxText = voice.getText();
        if (wxText != null && !wxText.isBlank()) {
            log.info("[{}] 微信已识别语音 from={} wxText={}", this.instanceId, maskUserId(fromUser), wxText);
            processTextMessage(fromUser, contextToken, wxText.trim());
            return;
        }

        VoiceMessageHandler voiceHandler = voiceMessageHandler;
        if (voiceHandler == null) {
            sendReply(fromUser, contextToken, "语音识别服务未配置");
            return;
        }

        submitReplyTask(fromUser, contextToken, () -> {
            try {
                VoiceMessageService.Result asrResult = voiceHandler.recognize(fromUser,
                        () -> client.downloadMedia(voice.getEncryptQueryParam(), voice.getAesKey()));

                String recognizedText = asrResult.text();
                if (recognizedText == null || recognizedText.isBlank()) {
                    sendReply(fromUser, contextToken, VoiceMessageService.ASR_FAILURE_TEXT);
                    return;
                }

                messages.add(new Msg(fromUser, rememberReplyTarget(fromUser, contextToken),
                        "[语音] " + recognizedText));
                displayLog(fromUser + ": [语音] " + recognizedText);

                if (autoReplyHandler != null) {
                    String reply = autoReplyHandler.onMessage(fromUser, contextToken, recognizedText);
                    if (reply != null && !reply.isEmpty()) {
                        sendReply(fromUser, contextToken, reply);
                    }
                }
            } catch (Exception e) {
                log.error("[{}] 语音处理失败: {}", this.instanceId, e.getMessage(), e);
                sendReply(fromUser, contextToken, VoiceMessageService.ASR_FAILURE_TEXT);
            }
        });
    }

    private void processImageMessage(String fromUser, String contextToken, byte[] imageBytes) {
        messages.add(new Msg(fromUser, rememberReplyTarget(fromUser, contextToken), "[图片]"));
        displayLog(fromUser + ": [图片]");

        if (imageReplyHandler != null) {
            submitReplyTask(fromUser, contextToken, () -> {
                try {
                    String reply = imageReplyHandler.onImage(fromUser, contextToken, imageBytes);
                    if (reply != null && !reply.isEmpty()) {
                        sendReply(fromUser, contextToken, reply);
                    }
                } catch (Exception e) {
                    log.error("[{}] 图片处理异常: {}", this.instanceId, e.getMessage(), e);
                    sendReply(fromUser, contextToken, "收到图片了，但识别失败了：" + e.getMessage());
                }
            });
        }
    }

    private void runAutoReply(String fromUser, String contextToken, String text) {
        if (autoReplyHandler == null) return;

        try {
            String reply = autoReplyHandler.onMessage(fromUser, contextToken, text);
            if (reply != null && !reply.isEmpty()) {
                sendReply(fromUser, contextToken, reply);
            }
        } catch (Exception e) {
            log.error("[{}] 自动回复异常: {}", this.instanceId, e.getMessage(), e);
            sendReply(fromUser, contextToken, "抱歉，处理消息时遇到问题，请稍后再试。");
        }
    }

    // ==================== 发送消息 ====================

    public void sendReply(String toUserId, String contextToken, String text) {
        if (!loggedIn) {
            log.warn("[{}] 发送失败：未登录", this.instanceId);
            return;
        }
        try {
            client.sendTextMessage(credentials.get(), toUserId, contextToken, text);
            log.info("[{}] 文本消息发送成功 to={}", this.instanceId, maskUserId(toUserId));
            displayLog("回复 -> " + toUserId + ": " + text);
        } catch (Exception e) {
            log.error("[{}] 文本消息发送失败: {}", this.instanceId, e.getMessage(), e);
            displayLog("发送失败: " + e.getMessage());
        }
    }

    public boolean sendImageReply(String toUserId, String contextToken, byte[] imageBytes) {
        if (!loggedIn || imageBytes == null || imageBytes.length == 0) {
            return false;
        }
        try {
            ILinkClient.MediaInfo media = client.uploadMedia(credentials.get(), 1, toUserId, imageBytes);
            client.sendImageMessage(credentials.get(), toUserId, contextToken, media);
            log.info("[{}] 图片消息发送成功 to={}", this.instanceId, maskUserId(toUserId));
            displayLog("图片回复 -> " + toUserId + " (" + imageBytes.length + " bytes)");
            return true;
        } catch (Exception e) {
            log.error("[{}] 图片消息发送失败: {}", this.instanceId, e.getMessage(), e);
            return false;
        }
    }

    public boolean sendVoiceReply(String toUserId, String contextToken, byte[] mp3Audio) {
        if (!loggedIn || mp3Audio == null || mp3Audio.length == 0) {
            return false;
        }
        try {
            ILinkClient.MediaInfo media = client.uploadMedia(credentials.get(), 3, toUserId, mp3Audio);
            String fileName = "voice-reply-" + System.currentTimeMillis() + ".mp3";
            client.sendFileMessage(credentials.get(), toUserId, contextToken, media, fileName, mp3Audio.length);
            log.info("[{}] MP3语音发送成功 to={} mp3Bytes={}", this.instanceId, maskUserId(toUserId), mp3Audio.length);
            displayLog("语音回复 -> " + toUserId + " (" + mp3Audio.length + " bytes)");
            return true;
        } catch (Exception e) {
            log.error("[{}] MP3语音发送失败: {}", this.instanceId, e.getMessage(), e);
            return false;
        }
    }

    // ==================== 公开查询接口 ====================

    public String getQrCodeBase64() { return qrCodeBase64.get(); }
    public String getQrCodeUrl() { return qrCodeUrl.get(); }
    public String getStatusText() { return statusText.get(); }
    public List<String> getLogs() { return new ArrayList<>(logs); }
    public List<Msg> getMessages() { return new ArrayList<>(messages); }

    public List<Msg> pollMessages() {
        if (messages.isEmpty()) return Collections.emptyList();
        List<Msg> result = new ArrayList<>(messages);
        messages.clear();
        return result;
    }

    public int getReplyQueueSize() {
        return replyExecutor instanceof ThreadPoolExecutor tp ? tp.getQueue().size() : 0;
    }

    public ExecutorService getReplyExecutor() { return replyExecutor; }

    // ==================== 设置业务处理器 ====================

    public void setAutoReply(BotService.ReplyHandler handler) {
        this.autoReplyHandler = handler;
        log.info("[{}] 自动回复处理器已设置", this.instanceId);
    }

    public void setImageReply(BotService.ImageReplyHandler handler) {
        this.imageReplyHandler = handler;
        log.info("[{}] 图片识别处理器已设置", this.instanceId);
    }

    public void setVoiceMessageHandler(VoiceMessageHandler handler) {
        this.voiceMessageHandler = handler;
        log.info("[{}] 语音处理器已设置", this.instanceId);
    }

    // ==================== 内部工具方法 ====================

    private static ExecutorService createReplyExecutor() {
        return new ThreadPoolExecutor(
                REPLY_WORKER_COUNT, REPLY_WORKER_COUNT, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(REPLY_QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "bot-reply-" + INSTANCE_SEQUENCE.get() + "-" + System.nanoTime());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    private void submitReplyTask(String fromUser, String contextToken, Runnable task) {
        if (!rateLimiter.tryAcquire(fromUser)) {
            totalRateLimitRejected.incrementAndGet();
            sendReply(fromUser, contextToken, "你的消息太快了，请稍后再发。");
            return;
        }
        totalRateLimitAccepted.incrementAndGet();

        try {
            Object userLock = userReplyLocks.computeIfAbsent(fromUser, ignored -> new Object());
            replyExecutor.execute(() -> {
                synchronized (userLock) {
                    task.run();
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("[{}] 任务被拒绝", this.instanceId);
            sendReply(fromUser, contextToken, "服务正在维护中，请稍后再试。");
        }
    }

    private synchronized void markSessionExpired(Exception e) {
        loggedIn = false;
        credentials.set(null);
        cursor = "";
        statusText.set("登录已失效，请重新扫码");
        displayLog("登录已失效，请重新扫码");
        log.warn("[{}] 会话已失效: {}", this.instanceId, e.getMessage());
    }

    private synchronized String rememberReplyTarget(String fromUser, String contextToken) {
        String replyId = UUID.randomUUID().toString();
        replyTargets.put(replyId, new ReplyTarget(fromUser, contextToken));
        replyTargetOrder.add(replyId);
        while (replyTargetOrder.size() > REPLY_TARGET_CAPACITY) {
            String expiredId = replyTargetOrder.remove(0);
            replyTargets.remove(expiredId);
        }
        return replyId;
    }

    synchronized boolean markInboundMessageIfNew(String messageId, String itemMsgId) {
        String key = dedupKey(messageId, itemMsgId);
        if (key == null) return true;

        long now = System.currentTimeMillis();
        evictProcessedMessages(now);
        Long firstSeenAt = processedMessageIds.get(key);
        if (firstSeenAt != null && now - firstSeenAt <= PROCESSED_MESSAGE_TTL_MS) {
            return false;
        }

        processedMessageIds.put(key, now);
        processedMessageOrder.add(key);
        return true;
    }

    private String dedupKey(String messageId, String itemMsgId) {
        if (hasMessageId(itemMsgId)) return "item:" + itemMsgId;
        if (hasMessageId(messageId)) return "message:" + messageId;
        return null;
    }

    private boolean hasMessageId(String id) {
        return id != null && !id.isBlank() && !"0".equals(id);
    }

    private void evictProcessedMessages(long now) {
        while (!processedMessageOrder.isEmpty()) {
            String oldestKey = processedMessageOrder.get(0);
            Long firstSeenAt = processedMessageIds.get(oldestKey);
            boolean expired = firstSeenAt == null || now - firstSeenAt > PROCESSED_MESSAGE_TTL_MS;
            boolean overCapacity = processedMessageIds.size() > PROCESSED_MESSAGE_CAPACITY;
            if (!expired && !overCapacity) return;
            processedMessageOrder.remove(0);
            if (firstSeenAt != null && (expired || overCapacity)) {
                processedMessageIds.remove(oldestKey, firstSeenAt);
            }
        }
    }

    private void displayLog(String msg) {
        logs.add(msg);
        if (logs.size() > 200) logs.remove(0);
    }

    private static String maskUserId(String userId) {
        if (userId == null || userId.length() < 9) return "***";
        return userId.substring(0, 4) + "..." + userId.substring(userId.length() - 4);
    }

    private String buildQrCodeBase64(String content, String imgData) {
        try {
            byte[] imageBytes = null;

            if (imgData != null && !imgData.isEmpty()) {
                if (imgData.contains(",")) {
                    imageBytes = Base64.getDecoder().decode(imgData.substring(imgData.indexOf(",") + 1));
                } else if (!imgData.startsWith("http")) {
                    try {
                        imageBytes = Base64.getDecoder().decode(imgData);
                    } catch (IllegalArgumentException ignored) {
                        // ignore
                    }
                }
            }

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
            log.error("[{}] 生成二维码失败: {}", this.instanceId, e.getMessage(), e);
        }
        return "";
    }

    public boolean sendManualReply(String replyId, String text) {
        ReplyTarget target = replyTargets.get(replyId);
        if (target == null) {
            log.warn("[{}] 手动发送失败：replyId 无效", this.instanceId);
            return false;
        }
        sendReply(target.toUserId(), target.contextToken(), text);
        return true;
    }

    // ==================== DTO ====================

    public static class Msg {
        public String fromUser;
        public String replyId;
        public String content;
        public long time = System.currentTimeMillis();

        public Msg(String f, String r, String t) {
            fromUser = f;
            replyId = r;
            content = t;
        }
    }

    private record ReplyTarget(String toUserId, String contextToken) {}
}
