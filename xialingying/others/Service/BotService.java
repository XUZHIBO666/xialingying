package com.demo.demo.Service;

import com.lth.wechat.ilink.ILinkClient;
import com.lth.wechat.ilink.LoginCredentials;
import com.lth.wechat.ilink.dto.message.ImageContent;
import com.lth.wechat.ilink.dto.message.MessageItemDto;
import com.lth.wechat.ilink.dto.message.ReceiveMessagesResult;
import com.lth.wechat.ilink.dto.message.VoiceContent;
import com.lth.wechat.ilink.dto.message.WeixinMessageDto;
import com.lth.wechat.ilink.entity.login.LoginStatusResp;
import com.lth.wechat.ilink.entity.send.SendMessageRequest;
import com.lth.wechat.ilink.entity.message.MessageItem;
import com.lth.wechat.ilink.entity.message.FileItem;
import com.lth.wechat.ilink.entity.message.CDNMediaEntity;
import com.lth.wechat.ilink.entity.config.BaseInfo;
import com.lth.wechat.ilink.entity.media.GetUploadUrlReq;
import com.lth.wechat.ilink.entity.media.GetUploadUrlResp;
import com.lth.wechat.ilink.utils.MediaUtils;
import com.lth.wechat.ilink.entity.login.QrCodeResp;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.apache.tomcat.util.threads.ThreadPoolExecutor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bot 核心服务：管理 iLink 登录状态、二维码、消息收发
 *
 * iLink 通信流程：
 * 1. getBotQrCode() → 获取登录二维码
 * 2. getQrCodeStatus() → 轮询等待用户扫码确认
 * 3. createCredentials() → 生成登录凭证
 * 4. receiveMessages() → 轮询接收新消息
 * 5. sendTextMessage() → 发送文本回复
 * 6. uploadMedia() + sendImageMessage() → 发送图片回复
 */
@Slf4j
@Service
public class BotService {

    // 线程工厂
    private static final ThreadFactory namedThreadFactory = new BasicThreadFactory.Builder()
            .namingPattern("studentReg-pool-%d")
            .daemon(true)
            .build();

    // 等待队列
    private static final BlockingQueue<Runnable> workQueue = new LinkedBlockingQueue<Runnable>(1024);

    // 处理线程池（消息接收+AI处理）
    private static final ThreadPoolExecutor EXECUTOR_SERVICE = new ThreadPoolExecutor(
            20,
            200,
            30,
            TimeUnit.SECONDS,
            workQueue,
            namedThreadFactory,
            new ThreadPoolExecutor.AbortPolicy());

    // 发送线程池（专门负责发送回复）
    private final ExecutorService sendPool = Executors.newFixedThreadPool(2, namedThreadFactory);

    // 发送队列（生产者-消费者模式：处理线程放进去，发送线程取出来发）
    private final BlockingQueue<SendTask> sendQueue = new LinkedBlockingQueue<>(1024);

    private final ILinkClient client;

    // 状态（线程安全）
    private final AtomicReference<String> qrCodeBase64 = new AtomicReference<>();
    private final AtomicReference<String> qrCodeUrl = new AtomicReference<>();
    private final AtomicReference<String> statusText = new AtomicReference<>("未启动");
    private final AtomicReference<LoginCredentials> credentials = new AtomicReference<>();
    private volatile boolean loggedIn = false;

    // 消息和日志
    private final List<String> logs = new CopyOnWriteArrayList<>();
    private final List<Msg> messages = new CopyOnWriteArrayList<>();
    private String cursor = "";
    private Thread listenThread;
    private Thread loginThread;
    // 每次重新获取二维码都会递增，防止旧登录线程把新二维码状态覆盖掉。
    private final AtomicInteger loginSession = new AtomicInteger();

    // 自动回复处理器
    private volatile ReplyHandler autoReplyHandler;
    private volatile ImageReplyHandler imageReplyHandler;
    private volatile VoiceReplyHandler voiceReplyHandler;

    public BotService() {
        this(new ILinkClient());
    }

    BotService(ILinkClient client) {
        this.client = client;
        log.info("[iLink] BotService 初始化完成");
        // 启动发送消费者线程
        startSendConsumers();
    }

    /** 启动发送消费者：从 sendQueue 中取任务并发送 */
    private void startSendConsumers() {
        for (int i = 0; i < 2; i++) {
            sendPool.submit(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        SendTask task = sendQueue.take(); // 阻塞等待
                        if (task.imageBytes != null) {
                            doSendImageReply(task.toUserId, task.contextToken, task.imageBytes);
                        } else {
                            doSendReply(task.toUserId, task.contextToken, task.text);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        log.error("[iLink] 发送线程异常: {}", e.getMessage(), e);
                    }
                }
            });
        }
        log.info("[iLink] 发送消费者已启动（2个线程）");
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
        if (loggedIn && !force)
            return;
        if (!force && loginThread != null && loginThread.isAlive())
            return;

        int session = loginSession.incrementAndGet();
        if (loginThread != null)
            loginThread.interrupt();

        if (force) {
            loggedIn = false;
            credentials.set(null);
            qrCodeBase64.set(null);
            qrCodeUrl.set(null);
            messages.clear();
            logs.clear();
            cursor = "";
            if (listenThread != null)
                listenThread.interrupt();
        }
        statusText.set("正在获取二维码...");
        log.info("[iLink] 开始获取登录二维码...");

        loginThread = new Thread(() -> {
            try {
                // 1. 获取二维码
                QrCodeResp qr = client.getBotQrCode();
                if (!isCurrentLoginSession(session))
                    return;

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
                    if (!isCurrentLoginSession(session) || Thread.currentThread().isInterrupted())
                        return;
                    Thread.sleep(2000);
                    LoginStatusResp s;
                    try {
                        s = client.getQrCodeStatus(content);
                    } catch (Exception e) {
                        if (!isCurrentLoginSession(session))
                            return;
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
                        if (!dto.isUserMessage())
                            continue;
                        if (!dto.hasItems())
                            continue;
                        for (MessageItemDto item : dto.getItemList()) {
                            String fromUser = dto.getFromUserId();
                            String contextToken = dto.getContextToken();

                            if (item.isText()) {
                                String text = item.getText();
                                log.info("[iLink] 收到消息 from={} contextToken={} text={}",
                                        fromUser, contextToken, text);
                                EXECUTOR_SERVICE.submit(() -> {
                                    try {
                                        processTextMessage(fromUser, contextToken, text);
                                    } catch (Exception e) {
                                        log.error("[iLink] 消息处理异常: {}", e.getMessage(), e);
                                    }
                                });
                                continue;
                            }

                            if (item.isImage()) {
                                log.info("[iLink] 收到图片 from={} contextToken={}", fromUser, contextToken);
                                EXECUTOR_SERVICE.submit(() -> {
                                    try {
                                        processImageItem(fromUser, contextToken, item.getImage());
                                    } catch (Exception e) {
                                        log.error("[iLink] 图片处理异常: {}", e.getMessage(), e);
                                    }
                                });
                            }
                            if (item.isVoice()) {
                                log.info("[iLink] 收到语音 from={} contextToken={}", fromUser, contextToken);
                                EXECUTOR_SERVICE.submit(() -> {
                                    try {
                                        processVoiceItem(fromUser, contextToken, item.getVoice());
                                    } catch (Exception e) {
                                        log.error("[iLink] 语音处理异常: {}", e.getMessage(), e);
                                    }
                                });
                            }
                        }
                    }
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

    void processTextMessage(String fromUser, String contextToken, String text) {
        Msg msg = new Msg(fromUser, contextToken, text);
        messages.add(msg);
        displayLog(fromUser + ": " + text);

        // --- 自动回复 ---
        ReplyHandler handler = autoReplyHandler;
        if (handler != null) {
            try {
                String reply = handler.onMessage(fromUser, contextToken, text);
                if (reply != null && !reply.isEmpty()) {
                    // 投递到发送队列，由发送线程池负责发送
                    sendQueue.offer(new SendTask(fromUser, contextToken, reply));
                }
            } catch (Exception e) {
                log.error("[iLink] 自动回复处理异常: {}", e.getMessage(), e);
            }
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

    void processImageMessage(String fromUser, String contextToken, byte[] imageBytes) {
        messages.add(new Msg(fromUser, contextToken, "[图片]"));
        displayLog(fromUser + ": [图片]");

        // 图片识别和文本自动回复分开注册，避免普通文本 AI 误处理图片消息。
        ImageReplyHandler handler = imageReplyHandler;
        if (handler != null) {
            try {
                String reply = handler.onImage(fromUser, contextToken, imageBytes);
                if (reply != null && !reply.isEmpty()) {
                    // 投递到发送队列，由发送线程池负责发送
                    sendQueue.offer(new SendTask(fromUser, contextToken, reply));
                }
            } catch (Exception e) {
                log.error("[iLink] 图片自动回复处理异常: {}", e.getMessage(), e);
                sendQueue.offer(new SendTask(fromUser, contextToken, "收到图片了，但识别失败了：" + e.getMessage()));
            }
        }
    }

    private void processVoiceItem(String fromUser, String contextToken, VoiceContent voice) {
        if (voice == null) {
            displayLog(fromUser + ": [语音消息为空]");
            return;
        }
        // 先检查微信是否已自带语音识别结果
        String wxText = voice.getText();
        log.info("[iLink] 收到语音 from={} wxText={} encodeType={} sampleRate={}",
                fromUser,
                wxText != null && !wxText.isEmpty() ? wxText : "(空)",
                voice.getEncodeType(), voice.getSampleRate());

        if (wxText != null && !wxText.isEmpty()) {
            // 微信已识别，直接用
            processTextMessage(fromUser, contextToken, wxText);
            return;
        }

        // 微信没有识别结果，尝试 STT
        try {
            String mediaParam = voice.getEncryptQueryParam();
            byte[] voiceBytes = client.downloadMedia(mediaParam, voice.getAesKey());
            processVoiceMessage(fromUser, contextToken, voiceBytes, "wav");
        } catch (Exception e) {
            log.error("[iLink] 语音下载/识别失败 from={} error={}", fromUser, e.getMessage(), e);
            displayLog("语音下载失败: " + e.getMessage());
            sendReply(fromUser, contextToken, "收到语音了，但语音下载失败，暂时无法识别。");
        }
    }

    void processVoiceMessage(String fromUser, String contextToken, byte[] voiceBytes, String format) {
        messages.add(new Msg(fromUser, contextToken, "[语音]"));
        displayLog(fromUser + ": [语音]");

        VoiceReplyHandler handler = voiceReplyHandler;
        if (handler != null) {
            try {
                String reply = handler.onVoice(fromUser, contextToken, voiceBytes, format);
                if (reply != null && !reply.isEmpty()) {
                    sendQueue.offer(new SendTask(fromUser, contextToken, reply));
                }
            } catch (Exception e) {
                log.error("[iLink] 语音自动回复处理异常: {}", e.getMessage(), e);
                sendQueue.offer(new SendTask(fromUser, contextToken, "收到语音了，但识别失败了：" + e.getMessage()));
            }
        }
    }

    /**
     * 发送 MP3 文件
     * 使用 SDK 标准的 uploadMedia 方法
     */
    public boolean sendVoiceReply(String toUserId, String contextToken, byte[] voiceBytes) {
        if (!loggedIn) {
            log.warn("[iLink] 发送语音失败：未登录");
            return false;
        }
        if (voiceBytes == null || voiceBytes.length == 0) {
            log.warn("[iLink] 发送语音失败：语音为空");
            return false;
        }
        try {
            log.info("[iLink] 上传语音 to={} size={} bytes", toUserId, voiceBytes.length);

            // 使用 SDK 标准的 uploadMedia（mediaType=1 表示图片/文件）
            ILinkClient.MediaInfo media = client.uploadMedia(credentials.get(), 1, toUserId, voiceBytes);

            log.info("[iLink] 上传成功, 尝试发送语音气泡");

            // 先尝试发送语音气泡
            try {
                client.sendVoiceMessage(credentials.get(), toUserId, contextToken, media, 24000, 16);
                log.info("[iLink] 语音气泡发送成功 to={}", toUserId);
                displayLog("语音气泡回复 -> " + toUserId + " (" + voiceBytes.length + " bytes)");
                return true;
            } catch (Exception voiceEx) {
                // 语音气泡发送失败，尝试发送MP3文件
                log.warn("[iLink] 语音气泡发送失败，尝试发送MP3文件: {}", voiceEx.getMessage());
                client.sendFileMessage(credentials.get(), toUserId, contextToken, media, "语音回复.mp3", voiceBytes.length);
                log.info("[iLink] MP3文件发送成功 to={}", toUserId);
                displayLog("语音文件回复 -> " + toUserId + " (" + voiceBytes.length + " bytes)");
                return true;
            }
        } catch (Exception e) {
            log.error("[iLink] 语音发送失败 to={} error={}", toUserId, e.getMessage(), e);
            displayLog("发送语音失败: " + e.getMessage());
            return false;
        }
    }

    /** 发送文本回复（供外部直接调用） */
    public void sendReply(String toUserId, String contextToken, String text) {
        // 也投递到发送队列
        sendQueue.offer(new SendTask(toUserId, contextToken, text));
    }

    /** 发送图片回复（供外部直接调用） */
    public boolean sendImageReply(String toUserId, String contextToken, byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            log.warn("[iLink] 发送图片失败：图片为空");
            displayLog("图片为空，无法发送");
            return false;
        }
        // 投递到发送队列
        sendQueue.offer(new SendTask(toUserId, contextToken, imageBytes));
        return true;
    }

    /** 内部发送文本回复（由发送消费者线程调用） */
    private void doSendReply(String toUserId, String contextToken, String text) {
        if (!loggedIn) {
            log.warn("[iLink] 发送失败：未登录");
            displayLog("未登录，无法发送");
            return;
        }
        try {
            log.info("[iLink] 发送文本消息 to={} contextToken={} text={}",
                    toUserId, contextToken, text);
            client.sendTextMessage(credentials.get(), toUserId, contextToken, text);
            log.info("[iLink] 文本消息发送成功 to={}", toUserId);
            displayLog("回复 -> " + toUserId + ": " + text);
        } catch (Exception e) {
            log.error("[iLink] 文本消息发送失败 to={} error={}", toUserId, e.getMessage(), e);
            displayLog("发送失败: " + e.getMessage());
        }
    }

    /** 内部发送图片回复（由发送消费者线程调用） */
    private void doSendImageReply(String toUserId, String contextToken, byte[] imageBytes) {
        if (!loggedIn) {
            log.warn("[iLink] 发送图片失败：未登录");
            displayLog("未登录，无法发送图片");
            return;
        }
        try {
            log.info("[iLink] 上传图片 to={} size={} bytes", toUserId, imageBytes.length);
            ILinkClient.MediaInfo media = client.uploadMedia(credentials.get(), 1, toUserId, imageBytes);
            client.sendImageMessage(credentials.get(), toUserId, contextToken, media);
            log.info("[iLink] 图片消息发送成功 to={}", toUserId);
            displayLog("图片回复 -> " + toUserId + " (" + imageBytes.length + " bytes)");
        } catch (Exception e) {
            log.error("[iLink] 图片消息发送失败 to={} error={}", toUserId, e.getMessage(), e);
            displayLog("发送图片失败: " + e.getMessage());
        }
    }

    // ==================== 前端查询接口 ====================

    public String getQrCodeBase64() {
        return qrCodeBase64.get();
    }

    public String getQrCodeUrl() {
        return qrCodeUrl.get();
    }

    public String getStatusText() {
        return statusText.get();
    }

    public boolean isLoggedIn() {
        return loggedIn;
    }

    public List<String> getLogs() {
        return new ArrayList<>(logs);
    }

    public List<Msg> getMessages() {
        return new ArrayList<>(messages);
    }

    public List<Msg> pollMessages() {
        if (messages.isEmpty())
            return Collections.emptyList();
        List<Msg> result = new ArrayList<>(messages);
        messages.clear();
        return result;
    }

    private synchronized void displayLog(String msg) {
        logs.add(msg);
        while (logs.size() > 200) {
            logs.remove(0);
        }
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
                if (data == null || data.isEmpty())
                    data = content;
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
     * 
     * @param handler (发信人ID, contextToken, 消息文本) → 回复文本，返回 null/空则不回复
     */
    public void setAutoReply(ReplyHandler handler) {
        this.autoReplyHandler = handler;
        log.info("[iLink] 自动回复处理器已设置");
    }

    /** 获取自动回复处理器（供语音消息复用） */
    public ReplyHandler getAutoReplyHandler() {
        return autoReplyHandler;
    }

    public void setImageReply(ImageReplyHandler handler) {
        this.imageReplyHandler = handler;
        log.info("[iLink] 图片识别处理器已设置");
    }

    public void setVoiceReply(VoiceReplyHandler handler) {
        this.voiceReplyHandler = handler;
        log.info("[iLink] 语音识别处理器已设置");
    }

    @FunctionalInterface
    public interface ReplyHandler {
        String onMessage(String fromUserId, String contextToken, String text);
    }

    @FunctionalInterface
    public interface ImageReplyHandler {
        String onImage(String fromUserId, String contextToken, byte[] imageBytes) throws Exception;
    }

    @FunctionalInterface
    public interface VoiceReplyHandler {
        String onVoice(String fromUserId, String contextToken, byte[] voiceBytes, String format) throws Exception;
    }

    // ==================== DTO ====================

    /** 发送任务（用于发送队列） */
    private static class SendTask {
        final String toUserId;
        final String contextToken;
        final String text;
        final byte[] imageBytes;

        SendTask(String toUserId, String contextToken, String text) {
            this.toUserId = toUserId;
            this.contextToken = contextToken;
            this.text = text;
            this.imageBytes = null;
        }

        SendTask(String toUserId, String contextToken, byte[] imageBytes) {
            this.toUserId = toUserId;
            this.contextToken = contextToken;
            this.text = null;
            this.imageBytes = imageBytes;
        }
    }

    public static class Msg {
        public String fromUser;
        public String contextToken;
        public String content;
        public long time = System.currentTimeMillis();

        public Msg(String f, String c, String t) {
            fromUser = f;
            contextToken = c;
            content = t;
        }
    }
}
