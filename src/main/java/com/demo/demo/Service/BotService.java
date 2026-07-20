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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.UUID;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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

    // 回复线程池配置（可通过环境变量覆盖）
    private static final int REPLY_WORKER_COUNT = Integer.parseInt(
            System.getenv().getOrDefault("BOT_REPLY_THREADS", "4"));
    private static final int REPLY_QUEUE_CAPACITY = Integer.parseInt(
            System.getenv().getOrDefault("BOT_REPLY_QUEUE_CAPACITY", "200"));
    private static final int REPLY_TARGET_CAPACITY = 200;
    private static final int PROCESSED_MESSAGE_CAPACITY = 1000;
    private static final long PROCESSED_MESSAGE_TTL_MS = TimeUnit.MINUTES.toMillis(10);
    private static final AtomicInteger REPLY_THREAD_SEQUENCE = new AtomicInteger();
    private static final AtomicInteger VOICE_THREAD_SEQUENCE = new AtomicInteger();

    // 速率限制（可通过环境变量覆盖）
    private final UserRateLimiter rateLimiter = new UserRateLimiter(
            Double.parseDouble(System.getenv().getOrDefault("BOT_RATE_LIMIT_PER_SECOND", "0.5")),
            Integer.parseInt(System.getenv().getOrDefault("BOT_RATE_LIMIT_BURST", "2")));

    // 指标计数器
    private final AtomicLong totalRateLimitAccepted = new AtomicLong();
    private final AtomicLong totalRateLimitRejected = new AtomicLong();

    private final ILinkClient client;
    private final ExecutorService replyExecutor;

    // 状态（线程安全）
    private volatile boolean shuttingDown = false;
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
    private volatile VoiceMessageHandler voiceMessageHandler;

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
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(REPLY_QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable,
                            "bot-reply-" + REPLY_THREAD_SEQUENCE.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @PreDestroy
    public synchronized void shutdown() {
        if (shuttingDown) return;
        shuttingDown = true;
        log.info("[iLink] 开始优雅关闭...");

        // 1. 标记离线，阻止新的登录和消息处理
        loggedIn = false;

        // 2. 中断登录线程
        if (loginThread != null && loginThread.isAlive()) {
            loginThread.interrupt();
            log.info("[iLink] 已请求停止登录线程");
        }

        // 3. 中断消息监听线程
        if (listenThread != null && listenThread.isAlive()) {
            listenThread.interrupt();
            log.info("[iLink] 已请求停止监听线程");
        }

        // 4. 停止接收新回复任务
        replyExecutor.shutdown();

        // 5. 等待已提交任务完成（上限 5 秒）
        try {
            if (!replyExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                int pending = replyExecutor instanceof ThreadPoolExecutor tp
                        ? tp.getActiveCount() + tp.getQueue().size() : 0;
                log.warn("[iLink] 关闭超时，强制取消 {} 个待处理任务", pending);
                replyExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            replyExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // 6. 清理状态
        credentials.set(null);
        qrCodeBase64.set(null);
        qrCodeUrl.set(null);
        cursor = "";
        log.info("[iLink] 优雅关闭完成");
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
        if (shuttingDown) return;
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
                                log.info("[iLink] 收到语音消息 from={}", fromUser);
                                processVoiceMessage(fromUser, contextToken, item.getVoice());
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

        if (autoReplyHandler != null) {
            submitReplyTask(fromUser, contextToken, () -> runAutoReply(fromUser, contextToken, text));
        }
    }

    private void processImageItem(String fromUser, String contextToken, ImageContent image) {
        if (image == null) {
            displayLog(fromUser + ": [图片消息为空]");
            return;
        }

        // 提取下载参数（在监听线程中同步完成，不阻塞）
        String mediaParam = image.getEncryptQueryParam();
        if (mediaParam == null || mediaParam.isBlank()) {
            mediaParam = image.getUrl();
        }
        final String aesKey = image.getAesKey();
        final String downloadParam = mediaParam;

        // 提交到回复线程池异步下载和识别，避免 CDN 下载阻塞消息监听线程。
        submitReplyTask(fromUser, contextToken, () -> {
            try {
                log.info("[iLink] 下载图片 param={} aesKeyPresent={}",
                        downloadParam == null ? "null"
                                : downloadParam.substring(0,
                                        Math.min(downloadParam.length(), 24)) + "...",
                        aesKey != null && !aesKey.isBlank());
                byte[] imageBytes = client.downloadMedia(downloadParam, aesKey);
                processImageMessage(fromUser, contextToken, imageBytes);
            } catch (Exception e) {
                log.error("[iLink] 图片下载失败 from={} error={}", fromUser, e.getMessage(), e);
                displayLog("图片下载失败: " + e.getMessage());
                sendReply(fromUser, contextToken, "收到图片了，但图片下载失败，暂时无法识别。");
            }
        });
    }

    private void processVoiceMessage(String fromUser, String contextToken, VoiceContent voice) {
        VoiceMessageHandler handler = voiceMessageHandler;
        if (voice == null || handler == null) {
            sendReply(fromUser, contextToken, VoiceMessageService.ASR_FAILURE_TEXT);
            return;
        }

        submitReplyTask(fromUser, contextToken, () -> {
            long totalStart = System.nanoTime();
            FutureTask<VoiceMessageService.Result> task = new FutureTask<>(
                    () -> handler.handle(fromUser, () -> client.downloadMedia(
                            voice.getEncryptQueryParam(), voice.getAesKey())));
            Thread.ofVirtual()
                    .name("voice-process-" + VOICE_THREAD_SEQUENCE.incrementAndGet())
                    .start(task);
            try {
                VoiceMessageService.Result result = task.get();
                messages.add(new Msg(fromUser, rememberReplyTarget(fromUser, contextToken),
                        "[语音] " + result.text()));
                displayLog(fromUser + ": [语音]");
                boolean mp3ReplySent = result.hasMp3() && sendMp3Reply(fromUser, contextToken,
                        result.mp3Audio());
                if (!mp3ReplySent) {
                    sendReply(fromUser, contextToken, result.text());
                }
                log.info("[语音处理] from={} mp3Reply={} totalMs={}", fromUser, mp3ReplySent,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - totalStart));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[语音处理] 任务被中断 from={}", fromUser);
                sendReply(fromUser, contextToken, VoiceMessageService.ASR_FAILURE_TEXT);
            } catch (Exception e) {
                log.error("[语音处理] 处理失败 from={} error={}", fromUser, e.getMessage(), e);
                sendReply(fromUser, contextToken, VoiceMessageService.ASR_FAILURE_TEXT);
            }
        });
    }

    private void runAutoReply(String fromUser, String contextToken, String text) {
        ReplyHandler handler = autoReplyHandler;
        if (handler == null) {
            return;
        }
        try {
            String reply = handler.onMessage(fromUser, contextToken, text);
            if (reply != null && !reply.isEmpty()) {
                sendReply(fromUser, contextToken, reply);
            }
        } catch (Exception e) {
            log.error("[iLink] 自动回复处理异常: {}", e.getMessage(), e);
            sendReply(fromUser, contextToken, "抱歉，处理消息时遇到问题，请稍后再试。");
        }
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
        // 速率限制检查
        if (!rateLimiter.tryAcquire(fromUser)) {
            totalRateLimitRejected.incrementAndGet();
            log.info("[限速] from={} 消息被限速", maskUserId(fromUser));
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
            // CallerRunsPolicy 下几乎不会到达此处，仅在 shutdown 后触发
            log.warn("[iLink] 自动回复任务被拒绝（可能在关闭中） from={}", maskUserId(fromUser));
            sendReply(fromUser, contextToken, "服务正在维护中，请稍后再试。");
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

    private boolean sendMp3Reply(String toUserId, String contextToken, byte[] mp3Audio) {
        if (!loggedIn || mp3Audio == null || mp3Audio.length == 0) {
            return false;
        }
        try {
            ILinkClient.MediaInfo media = client.uploadMedia(credentials.get(), 3, toUserId, mp3Audio);
            String fileName = "voice-reply-" + System.currentTimeMillis() + ".mp3";
            client.sendFileMessage(credentials.get(), toUserId, contextToken, media,
                    fileName, mp3Audio.length);
            log.info("[iLink] MP3 文件发送成功 to={} mp3Bytes={} fileName={}",
                    toUserId, mp3Audio.length, fileName);
            displayLog("MP3 回复 -> " + toUserId + " (" + mp3Audio.length + " bytes)");
            return true;
        } catch (Exception e) {
            log.error("[iLink] MP3 文件发送失败 to={} error={}", toUserId, e.getMessage(), e);
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

    /** 回复队列当前大小。 */
    public int getReplyQueueSize() {
        return replyExecutor instanceof ThreadPoolExecutor tp
                ? tp.getQueue().size() : 0;
    }

    /** 回复队列容量。 */
    public int getReplyQueueCapacity() {
        return REPLY_QUEUE_CAPACITY;
    }

    /** 回复线程池（供健康检查端点使用）。 */
    public ExecutorService getReplyExecutor() {
        return replyExecutor;
    }

    /** 限速器活跃桶数。 */
    public int getRateLimiterBucketCount() {
        return rateLimiter.getBucketCount();
    }

    /** 限速累计接受数。 */
    public long getTotalRateLimitAccepted() {
        return totalRateLimitAccepted.get();
    }

    /** 限速累计拒绝数。 */
    public long getTotalRateLimitRejected() {
        return totalRateLimitRejected.get();
    }

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

    private static String maskUserId(String userId) {
        if (userId == null || userId.length() < 9) return "***";
        return userId.substring(0, 4) + "..." + userId.substring(userId.length() - 4);
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

    public void setVoiceMessageHandler(VoiceMessageHandler handler) {
        this.voiceMessageHandler = handler;
        log.info("[iLink] 语音处理器已设置");
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
