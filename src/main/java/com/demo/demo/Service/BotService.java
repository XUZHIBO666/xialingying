package com.demo.demo.Service;

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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bot 核心服务：管理 iLink 登录状态、二维码、消息收发
 *
 * iLink 通信流程：
 *   1. getBotQrCode() → 获取登录二维码
 *   2. getQrCodeStatus() → 轮询等待用户扫码确认
 *   3. createCredentials() → 生成登录凭证
 *   4. receiveMessages() → 轮询接收新消息
 *   5. sendTextMessage() → 发送文本回复
 *   6. uploadMedia() + sendImageMessage() → 发送图片回复
 */
@Slf4j
@Service
public class BotService {

    private static final int REPLY_WORKER_COUNT = 2;
    private static final int REPLY_QUEUE_CAPACITY = 20;
    private static final int REPLY_TARGET_CAPACITY = 200;
    private static final int PROCESSED_MESSAGE_CAPACITY = 1000;
    private static final long PROCESSED_MESSAGE_TTL_MS = TimeUnit.MINUTES.toMillis(10);
    private static final AtomicInteger REPLY_THREAD_SEQUENCE = new AtomicInteger();

    private final ILinkClient client;
    private final ExecutorService replyExecutor;

    // 状态（线程安全）
    private final AtomicReference<String> qrCodeBase64 = new AtomicReference<>();
    private final AtomicReference<String> qrCodeUrl = new AtomicReference<>();
    private final AtomicReference<String> statusText = new AtomicReference<>("未启动");
    private final AtomicReference<LoginCredentials> credentials = new AtomicReference<>();
    private volatile boolean loggedIn = false;

    // 消息和日志
    private final List<String> logs = new CopyOnWriteArrayList<>();
    private final List<Msg> messages = new CopyOnWriteArrayList<>();
    private final ConcurrentMap<String, ReplyTarget> replyTargets = new ConcurrentHashMap<>();
    private final List<String> replyTargetOrder = new CopyOnWriteArrayList<>();
    private final ConcurrentMap<String, Long> processedMessageIds = new ConcurrentHashMap<>();
    private final List<String> processedMessageOrder = new CopyOnWriteArrayList<>();
    private final ConcurrentMap<String, Object> userReplyLocks = new ConcurrentHashMap<>();
    private String cursor = "";
    private Thread listenThread;
    private Thread loginThread;
    // 每次重新获取二维码都会递增，防止旧登录线程把新二维码状态覆盖掉。
    private final AtomicInteger loginSession = new AtomicInteger();

    // 自动回复处理器
    private volatile ReplyHandler autoReplyHandler;
    private volatile ImageReplyHandler imageReplyHandler;

    public BotService() {
        this(new ILinkClient(), createReplyExecutor());
    }

    BotService(ILinkClient client) {
        this(client, createReplyExecutor());
    }

    BotService(ILinkClient client, ExecutorService replyExecutor) {
        this.client = client;
        this.replyExecutor = replyExecutor;
        log.info("[iLink] BotService 初始化完成");
    }

    private static ExecutorService createReplyExecutor() {
        return new ThreadPoolExecutor(
                REPLY_WORKER_COUNT,
                REPLY_WORKER_COUNT,
                0L,
                TimeUnit.MILLISECONDS,
                // 队列必须有上限，避免图片平台变慢时任务无限堆积并耗尽内存。
                new ArrayBlockingQueue<>(REPLY_QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable,
                            "bot-reply-" + REPLY_THREAD_SEQUENCE.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @PreDestroy
    public void shutdownReplyExecutor() {
        loggedIn = false;
        if (loginThread != null) loginThread.interrupt();
        if (listenThread != null) listenThread.interrupt();
        replyExecutor.shutdownNow();
    }

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
        log.info("[iLink] 开始获取登录二维码...");

        loginThread = new Thread(() -> {
            try {
                // 1. 获取二维码
                QrCodeResp qr = client.getBotQrCode();
                if (!isCurrentLoginSession(session)) return;

                String content = qr.getQrcode();
                String imgData = qr.getQrcode_img_content();

                qrCodeUrl.set(content);
                log.info("[iLink] 二维码已获取: {}", content);

                // 2. 处理二维码图片
                String qrBase64 = buildQrCodeBase64(content, imgData);
                qrCodeBase64.set(qrBase64);

                displayLog("二维码已获取，请扫描");
                statusText.set("等待扫码...");

                // 3. 轮询等待扫码确认
                for (int i = 0; i < 150; i++) {
                    if (!isCurrentLoginSession(session) || Thread.currentThread().isInterrupted()) return;
                    Thread.sleep(2000);
                    LoginStatusResp s;
                    try {
                        s = client.getQrCodeStatus(content);
                    } catch (Exception e) {
                        if (!isCurrentLoginSession(session)) return;
                        log.warn("[iLink] 查询扫码状态失败，继续重试: {}", e.getMessage());
                        displayLog("查询扫码状态失败，继续重试: " + e.getMessage());
                        statusText.set("查询扫码状态失败，正在重试...");
                        continue;
                    }

                    String code = s.getStatus();
                    log.debug("[iLink] 扫码状态检查 #{}: {}", i + 1, code);

                    if ("confirmed".equals(code)) {
                        credentials.set(ILinkClient.createCredentials(content, s));
                        loggedIn = true;
                        statusText.set("已登录 " + credentials.get().getUserId());
                        log.info("[iLink] 登录成功！Bot ID: {}", credentials.get().getUserId());
                        displayLog("登录成功！Bot ID: " + credentials.get().getUserId());
                        startListening();
                        return;
                    }
                    if ("expired".equals(code)) {
                        log.warn("[iLink] 二维码已过期");
                        statusText.set("二维码已过期，请刷新页面重试");
                        return;
                    }
                }
                log.warn("[iLink] 登录超时（300秒）");
                statusText.set("登录超时，请刷新页面重试");

            } catch (Exception e) {
                if (isCurrentLoginSession(session)) {
                    log.error("[iLink] 登录流程异常: {}", e.getMessage(), e);
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

    /** 启动消息监听（iLink 消息接收轮询） */
    private void startListening() {
        if (listenThread != null && listenThread.isAlive()) {
            log.info("[iLink] 消息监听线程已在运行，跳过重复启动");
            return;
        }
        log.info("[iLink] 启动消息监听线程...");
        listenThread = new Thread(() -> {
            log.info("[iLink] 消息监听已启动，等待新消息...");
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
                        log.debug("[iLink] 更新游标: {}", cursor);
                    }

                    for (WeixinMessageDto dto : result.getMessages()) {
                        if (!dto.isUserMessage()) continue;
                        if (!dto.hasItems()) continue;
                        for (MessageItemDto item : dto.getItemList()) {
                            String fromUser = dto.getFromUserId();
                            String contextToken = dto.getContextToken();
                            String messageId = String.valueOf(dto.getMessageId());
                            String itemMsgId = String.valueOf(item.getMsgId());
                            if (!markInboundMessageIfNew(messageId, itemMsgId)) {
                                log.info("[iLink] 忽略重复消息 messageId={} msgId={}",
                                        maskToken(messageId), maskToken(itemMsgId));
                                continue;
                            }

                            if (item.isText()) {
                                String text = item.getText();
                                log.info("[iLink] 收到消息 from={} contextToken={} text={}",
                                        fromUser, maskToken(contextToken), text);
                                processTextMessage(fromUser, contextToken, text);
                                continue;
                            }

                            if (item.isImage()) {
                                log.info("[iLink] 收到图片 from={} contextToken={}",
                                        fromUser, maskToken(contextToken));
                                processImageItem(fromUser, contextToken, item.getImage());
                                continue;
                            }

                            if (item.isVoice()) {
                                log.info("[iLink] 收到语音探测样本 from={}", fromUser);
                                processVoiceProbe(fromUser, item.getVoice());
                            }
                        }
                    }
                } catch (ILinkSessionExpiredException e) {
                    markSessionExpired(e);
                    break;
                } catch (Exception e) {
                    if (loggedIn) {
                        log.error("[iLink] 消息监听异常: {}", e.getMessage(), e);
                        displayLog("监听异常: " + e.getMessage());
                    }
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            log.info("[iLink] 消息监听已停止");
        });
        listenThread.setDaemon(true);
        listenThread.start();
    }

    private synchronized void markSessionExpired(Exception e) {
        loggedIn = false;
        credentials.set(null);
        cursor = "";
        qrCodeBase64.set(null);
        qrCodeUrl.set(null);
        statusText.set("登录已失效，请重新扫码");
        displayLog("登录已失效，请重新扫码");
        log.warn("[iLink] 会话已失效，需要重新扫码: {}", e.getMessage());
    }

    void processTextMessage(String fromUser, String contextToken, String text) {
        Msg msg = new Msg(fromUser, rememberReplyTarget(fromUser, contextToken), text);
        messages.add(msg);
        displayLog(fromUser + ": " + text);

        // --- 自动回复 ---
        ReplyHandler handler = autoReplyHandler;
        if (handler != null) {
            submitReplyTask(fromUser, contextToken, () -> {
                try {
                    String reply = handler.onMessage(fromUser, contextToken, text);
                    if (reply != null && !reply.isEmpty()) {
                        sendReply(fromUser, contextToken, reply);
                    }
                } catch (Exception e) {
                    log.error("[iLink] 自动回复处理异常: {}", e.getMessage(), e);
                }
            });
        }
    }

    private void processImageItem(String fromUser, String contextToken, ImageContent image) {
        if (image == null) {
            displayLog(fromUser + ": [图片消息为空]");
            return;
        }
        try {
            // iLink SDK 的 downloadMedia 第一个参数实际需要 CDN 的 encryptQueryParam；
            // url 只作为少数兼容场景的兜底，否则会出现“下载媒体失败”。
            String mediaParam = image.getEncryptQueryParam();
            if (mediaParam == null || mediaParam.isBlank()) {
                mediaParam = image.getUrl();
            }
            log.info("[iLink] 下载图片 mediaParam={} aesKeyPresent={} urlPresent={}",
                    mediaParam == null ? "null" : mediaParam.substring(0, Math.min(mediaParam.length(), 24)) + "...",
                    image.getAesKey() != null && !image.getAesKey().isBlank(),
                    image.getUrl() != null && !image.getUrl().isBlank());
            byte[] imageBytes = client.downloadMedia(mediaParam, image.getAesKey());
            processImageMessage(fromUser, contextToken, imageBytes);
        } catch (Exception e) {
            log.error("[iLink] 图片下载失败 from={} error={}", fromUser, e.getMessage(), e);
            displayLog("图片下载失败: " + e.getMessage());
            sendReply(fromUser, contextToken, "收到图片了，但图片下载失败，暂时无法识别。");
        }
    }

    private void processVoiceProbe(String fromUser, VoiceContent voice) {
        Thread.ofVirtual().name("voice-probe").start(() -> {
            if (voice == null) {
                log.warn("[语音探测] 语音内容为空 from={}", fromUser);
                return;
            }
            long startedAt = System.nanoTime();
            try {
                byte[] voiceBytes = client.downloadMedia(
                        voice.getEncryptQueryParam(),
                        voice.getAesKey());
                long downloadMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
                Path probeDirectory = Path.of("target", "voice-probe");
                Files.createDirectories(probeDirectory);
                String sampleName = "voice-" + System.currentTimeMillis();
                Path sampleFile = probeDirectory.resolve(sampleName + ".bin");
                Files.write(sampleFile, voiceBytes);
                Path transcriptFile = probeDirectory.resolve(sampleName + ".transcript.txt");
                Files.writeString(transcriptFile, voice.getText() == null ? "" : voice.getText());

                int headerLength = Math.min(16, voiceBytes.length);
                String headerHex = HexFormat.of().formatHex(voiceBytes, 0, headerLength);
                long bitrate = voice.getPlaytime() > 0
                        ? voiceBytes.length * 8_000L / voice.getPlaytime()
                        : 0;
                log.info("[语音探测] from={} file={} encodeType={} sampleRate={} bitsPerSample={} "
                                + "playtimeMs={} bytes={} estimatedBitrate={}bps headerHex={} "
                                + "officialTextPresent={} officialTextLength={} downloadMs={}",
                        fromUser,
                        sampleFile.toAbsolutePath(),
                        voice.getEncodeType(),
                        voice.getSampleRate(),
                        voice.getBitsPerSample(),
                        voice.getPlaytime(),
                        voiceBytes.length,
                        bitrate,
                        headerHex,
                        voice.getText() != null && !voice.getText().isBlank(),
                        voice.getText() == null ? 0 : voice.getText().length(),
                        downloadMs);
            } catch (Exception e) {
                log.error("[语音探测] 下载或保存失败 from={} error={}",
                        fromUser, e.getMessage(), e);
            }
        });
    }

    void processImageMessage(String fromUser, String contextToken, byte[] imageBytes) {
        messages.add(new Msg(fromUser, rememberReplyTarget(fromUser, contextToken), "[图片]"));
        displayLog(fromUser + ": [图片]");

        // 图片识别和文本自动回复分开注册，避免普通文本 AI 误处理图片消息。
        ImageReplyHandler handler = imageReplyHandler;
        if (handler != null) {
            submitReplyTask(fromUser, contextToken, () -> {
                try {
                    String reply = handler.onImage(fromUser, contextToken, imageBytes);
                    if (reply != null && !reply.isEmpty()) {
                        sendReply(fromUser, contextToken, reply);
                    }
                } catch (Exception e) {
                    log.error("[iLink] 图片自动回复处理异常: {}", e.getMessage(), e);
                    sendReply(fromUser, contextToken, "收到图片了，但识别失败了：" + e.getMessage());
                }
            });
        }
    }

    private void submitReplyTask(String fromUser, String contextToken, Runnable task) {
        try {
            Object userLock = userReplyLocks.computeIfAbsent(fromUser, ignored -> new Object());
            replyExecutor.execute(() -> {
                synchronized (userLock) {
                    task.run();
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("[iLink] 自动回复任务队列已满 from={}", fromUser);
            displayLog("自动回复任务较多，已拒绝新任务");
            sendReply(fromUser, contextToken, "当前任务较多，请稍后再试");
        }
    }

    /** 发送文本回复（iLink 消息发送） */
    public void sendReply(String toUserId, String contextToken, String text) {
        if (!loggedIn) {
            log.warn("[iLink] 发送失败：未登录");
            displayLog("未登录，无法发送");
            return;
        }
        try {
            log.info("[iLink] 发送文本消息 to={} contextToken={} text={}",
                    toUserId, maskToken(contextToken), text);
            client.sendTextMessage(credentials.get(), toUserId, contextToken, text);
            log.info("[iLink] 文本消息发送成功 to={}", toUserId);
            displayLog("回复 -> " + toUserId + ": " + text);
        } catch (Exception e) {
            log.error("[iLink] 文本消息发送失败 to={} error={}", toUserId, e.getMessage(), e);
            displayLog("发送失败: " + e.getMessage());
        }
    }

    public boolean sendManualReply(String replyId, String text) {
        ReplyTarget target = replyTargets.get(replyId);
        if (target == null) {
            log.warn("[iLink] 手动发送失败：replyId 无效");
            displayLog("手动发送失败：回复对象已失效");
            return false;
        }
        sendReply(target.toUserId(), target.contextToken(), text);
        return true;
    }

    /** 发送图片回复 */
    public boolean sendImageReply(String toUserId, String contextToken, byte[] imageBytes) {
        if (!loggedIn) {
            log.warn("[iLink] 发送图片失败：未登录");
            displayLog("未登录，无法发送图片");
            return false;
        }
        if (imageBytes == null || imageBytes.length == 0) {
            log.warn("[iLink] 发送图片失败：图片为空");
            displayLog("图片为空，无法发送");
            return false;
        }
        try {
            log.info("[iLink] 上传图片 to={} size={} bytes", toUserId, imageBytes.length);
            ILinkClient.MediaInfo media = client.uploadMedia(credentials.get(), 1, toUserId, imageBytes);
            client.sendImageMessage(credentials.get(), toUserId, contextToken, media);
            log.info("[iLink] 图片消息发送成功 to={}", toUserId);
            displayLog("图片回复 -> " + toUserId + " (" + imageBytes.length + " bytes)");
            return true;
        } catch (Exception e) {
            log.error("[iLink] 图片消息发送失败 to={} error={}", toUserId, e.getMessage(), e);
            displayLog("发送图片失败: " + e.getMessage());
            return false;
        }
    }

    // ==================== 前端查询接口 ====================

    public String getQrCodeBase64()  { return qrCodeBase64.get(); }
    public String getQrCodeUrl()     { return qrCodeUrl.get(); }
    public String getStatusText()    { return statusText.get(); }
    public boolean isLoggedIn()      { return loggedIn; }
    public List<String> getLogs()    { return new ArrayList<>(logs); }
    public List<Msg> getMessages()   { return new ArrayList<>(messages); }

    public List<Msg> pollMessages() {
        if (messages.isEmpty()) return Collections.emptyList();
        List<Msg> result = new ArrayList<>(messages);
        messages.clear();
        return result;
    }

    private void displayLog(String msg) {
        logs.add(msg);
        if (logs.size() > 200) logs.remove(0);
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
        evictProcessedMessages(now);
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

    private static String maskToken(String token) {
        if (token == null || token.isBlank()) return "null";
        if (token.length() <= 8) return "***";
        return token.substring(0, 4) + "..." + token.substring(token.length() - 4);
    }

    /** 把 SDK 返回的各种格式统一转成纯 base64（前端 img 标签直接用） */
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
                        log.debug("[iLink] imgData 不是 base64 格式，尝试作为 URL 处理");
                    }
                }
            }

            if (imageBytes == null) {
                String data = (imgData != null && imgData.startsWith("http")) ? imgData : content;
                if (data == null || data.isEmpty()) data = content;
                String api = "https://api.qrserver.com/v1/create-qr-code/?size=300x300&data="
                        + java.net.URLEncoder.encode(data, "UTF-8");
                log.debug("[iLink] 使用 qrserver API 生成二维码");
                imageBytes = new java.net.URI(api).toURL().openStream().readAllBytes();
            }

            if (imageBytes != null) {
                return Base64.getEncoder().encodeToString(imageBytes);
            }
        } catch (Exception e) {
            log.error("[iLink] 生成二维码图片失败: {}", e.getMessage(), e);
        }
        return "";
    }

    // ==================== 自动回复 ====================

    /**
     * 设置自动回复规则
     * @param handler (发信人ID, contextToken, 消息文本) → 回复文本，返回 null/空则不回复
     */
    public void setAutoReply(ReplyHandler handler) {
        this.autoReplyHandler = handler;
        log.info("[iLink] 自动回复处理器已设置");
    }

    public void setImageReply(ImageReplyHandler handler) {
        this.imageReplyHandler = handler;
        log.info("[iLink] 图片识别处理器已设置");
    }

    @FunctionalInterface
    public interface ReplyHandler {
        String onMessage(String fromUserId, String contextToken, String text);
    }

    @FunctionalInterface
    public interface ImageReplyHandler {
        String onImage(String fromUserId, String contextToken, byte[] imageBytes) throws Exception;
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

    private record ReplyTarget(String toUserId, String contextToken) {
    }
}
