package com.demo.demo.Service;

import com.demo.demo.Service.context.ContextManager;
import com.demo.demo.controller.BotController;
import com.lth.wechat.ilink.ILinkClient;
import com.lth.wechat.ilink.LoginCredentials;
import com.lth.wechat.ilink.dto.message.ImageContent;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ImageAutoReplyTest {

    @Test
    void repliesBusyWhenReplyExecutorRejectsTask() throws Exception {
        ILinkClient client = mock(ILinkClient.class);
        ExecutorService rejectingExecutor = mock(ExecutorService.class);
        doThrow(new RejectedExecutionException("queue full"))
                .when(rejectingExecutor).execute(any(Runnable.class));
        BotService botService = loggedInBotService(client, rejectingExecutor);
        botService.setAutoReply((fromUser, contextToken, text) -> "不会执行");

        botService.processTextMessage("wx-user", "ctx-token", "生成图片：星空");

        verify(client).sendTextMessage(any(LoginCredentials.class), eq("wx-user"), eq("ctx-token"),
                eq("服务正在维护中，请稍后再试。"));
    }

    @Test
    void textMessageReturnsBeforeSlowReplyFinishes() throws Exception {
        BotService botService = new BotService(mock(ILinkClient.class));
        CountDownLatch handlerStarted = new CountDownLatch(1);
        CountDownLatch allowHandlerToFinish = new CountDownLatch(1);
        botService.setAutoReply((fromUser, contextToken, text) -> {
            handlerStarted.countDown();
            try {
                allowHandlerToFinish.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        });

        ExecutorService caller = Executors.newSingleThreadExecutor();
        Future<?> processing = caller.submit(
                () -> botService.processTextMessage("wx-user", "ctx-token", "生成图片：星空"));
        try {
            assertTrue(handlerStarted.await(1, TimeUnit.SECONDS), "自动回复处理器没有启动");

            // 处理器仍被闩锁阻塞时，消息接收方法也应该已经返回。
            assertDoesNotThrow(() -> processing.get(200, TimeUnit.MILLISECONDS),
                    "耗时的自动回复阻塞了消息接收线程");
        } finally {
            allowHandlerToFinish.countDown();
            caller.shutdownNow();
        }
    }

    @Test
    void imageMessageReturnsBeforeSlowRecognitionFinishes() throws Exception {
        BotService botService = new BotService(mock(ILinkClient.class));
        CountDownLatch handlerStarted = new CountDownLatch(1);
        CountDownLatch allowHandlerToFinish = new CountDownLatch(1);
        botService.setImageReply((fromUser, contextToken, imageBytes) -> {
            handlerStarted.countDown();
            allowHandlerToFinish.await(2, TimeUnit.SECONDS);
            return null;
        });

        ExecutorService caller = Executors.newSingleThreadExecutor();
        Future<?> processing = caller.submit(
                () -> botService.processImageMessage("wx-user", "ctx-token", new byte[]{1, 2, 3}));
        try {
            assertTrue(handlerStarted.await(1, TimeUnit.SECONDS), "图片识别处理器没有启动");

            // 图片识别通常需要等待远程模型，不能让这段等待阻塞消息接收。
            assertDoesNotThrow(() -> processing.get(200, TimeUnit.MILLISECONDS),
                    "耗时的图片识别阻塞了消息接收线程");
        } finally {
            allowHandlerToFinish.countDown();
            caller.shutdownNow();
        }
    }

    @Test
    void imageRequestGeneratesAndSendsImageMessage() throws Exception {
        ILinkClient client = mock(ILinkClient.class);
        BotService botService = loggedInBotService(client);
        AIService aiService = mock(AIService.class);
        ImageGenerationService imageService = mock(ImageGenerationService.class);
        ImageRecognitionService recognitionService = mock(ImageRecognitionService.class);
        byte[] imageBytes = "image-bytes".getBytes();
        ILinkClient.MediaInfo media = new ILinkClient.MediaInfo("query", "aes-key", imageBytes.length);

        // 新 BotController 使用 AI 意图判断：AI 返回 YES|提示词
        when(aiService.isConfigured()).thenReturn(true);
        when(aiService.chat(contains("_img_intent"), anyString())).thenReturn("YES|星空");
        when(imageService.isConfigured()).thenReturn(true);
        when(imageService.generateImage("星空")).thenReturn(imageBytes);
        when(client.uploadMedia(any(LoginCredentials.class), eq(1), eq("wx-user"), eq(imageBytes))).thenReturn(media);

        BotController controller = controller(botService, aiService, imageService, recognitionService);
        controller.initAutoReply();

        botService.processTextMessage("wx-user", "ctx-token", "生成图片：星空");

        verify(client, timeout(1000)).uploadMedia(
                any(LoginCredentials.class), eq(1), eq("wx-user"), eq(imageBytes));
        verify(client, timeout(1000))
                .sendImageMessage(any(LoginCredentials.class), eq("wx-user"), eq("ctx-token"), eq(media));
        verify(client, never()).sendTextMessage(any(LoginCredentials.class), anyString(), anyString(), anyString());
    }

    @Test
    void unconfiguredImageServiceRepliesWithTextHint() throws Exception {
        ILinkClient client = mock(ILinkClient.class);
        BotService botService = loggedInBotService(client);
        AIService aiService = mock(AIService.class);
        ImageGenerationService imageService = mock(ImageGenerationService.class);
        ImageRecognitionService recognitionService = mock(ImageRecognitionService.class);

        // 新 BotController：图片生成未配置时跳过图片块，voice 和 AI 也未配置 → "AI 未配置"
        when(imageService.isConfigured()).thenReturn(false);
        when(aiService.isConfigured()).thenReturn(false);

        BotController controller = controller(botService, aiService, imageService, recognitionService);
        controller.initAutoReply();

        botService.processTextMessage("wx-user", "ctx-token", "画一张海边日落");

        verify(client, timeout(1000)).sendTextMessage(any(LoginCredentials.class), eq("wx-user"), eq("ctx-token"),
                eq("AI 未配置，请联系管理员"));
        verify(client, never()).uploadMedia(any(LoginCredentials.class), anyInt(), anyString(), any(byte[].class));
        verify(client, never()).sendImageMessage(any(LoginCredentials.class), anyString(), anyString(), any());
    }

    @Test
    void receivedImageIsRecognizedAndRepliedAsText() throws Exception {
        ILinkClient client = mock(ILinkClient.class);
        BotService botService = loggedInBotService(client);
        AIService aiService = mock(AIService.class);
        ImageGenerationService imageService = mock(ImageGenerationService.class);
        ImageRecognitionService recognitionService = mock(ImageRecognitionService.class);
        byte[] imageBytes = "received-image".getBytes();

        when(recognitionService.isConfigured()).thenReturn(true);
        when(recognitionService.recognize(imageBytes)).thenReturn("这张图片里是一只猫。");

        BotController controller = controller(botService, aiService, imageService, recognitionService);
        controller.initAutoReply();

        botService.processImageMessage("wx-user", "ctx-token", imageBytes);

        verify(recognitionService, timeout(1000)).recognize(imageBytes);
        verify(client, timeout(1000)).sendTextMessage(any(LoginCredentials.class), eq("wx-user"), eq("ctx-token"),
                eq("这张图片里是一只猫。"));
        verifyNoInteractions(aiService);
        verifyNoInteractions(imageService);
    }

    @Test
    void receivedImageDownloadsByEncryptQueryParamBeforeRecognition() throws Exception {
        ILinkClient client = mock(ILinkClient.class);
        BotService botService = loggedInBotService(client);
        AIService aiService = mock(AIService.class);
        ImageGenerationService imageService = mock(ImageGenerationService.class);
        ImageRecognitionService recognitionService = mock(ImageRecognitionService.class);
        byte[] imageBytes = "downloaded-image".getBytes();
        ImageContent image = new ImageContent(
                "encrypt-query-param", "aes-key", 1, "https://wrong-url.example/image.jpg",
                imageBytes.length, 0, 0, 0, 0);

        when(client.downloadMedia("encrypt-query-param", "aes-key")).thenReturn(imageBytes);
        when(recognitionService.isConfigured()).thenReturn(true);
        when(recognitionService.recognize(imageBytes)).thenReturn("图片识别成功。");

        BotController controller = controller(botService, aiService, imageService, recognitionService);
        controller.initAutoReply();

        ReflectionTestUtils.invokeMethod(botService, "processImageItem", "wx-user", "ctx-token", image);

        verify(client, timeout(1000)).downloadMedia("encrypt-query-param", "aes-key");
        verify(client, never()).downloadMedia(eq("https://wrong-url.example/image.jpg"), anyString());
        verify(client, timeout(1000)).sendTextMessage(any(LoginCredentials.class), eq("wx-user"), eq("ctx-token"),
                eq("图片识别成功。"));
    }

    @SuppressWarnings("unchecked")
    private BotService loggedInBotService(ILinkClient client) {
        return loggedInBotService(client, null);
    }

    @SuppressWarnings("unchecked")
    private BotService loggedInBotService(ILinkClient client, ExecutorService replyExecutor) {
        BotService botService = replyExecutor == null
                ? new BotService(client)
                : new BotService(client, replyExecutor);
        ReflectionTestUtils.setField(botService, "loggedIn", true);
        AtomicReference<LoginCredentials> credentials =
                (AtomicReference<LoginCredentials>) ReflectionTestUtils.getField(botService, "credentials");
        credentials.set(new LoginCredentials("token", "bot-user", "https://example.test"));
        return botService;
    }

    private BotController controller(BotService botService, AIService aiService,
                                     ImageGenerationService imageService,
                                     ImageRecognitionService recognitionService) {
        BotController controller = new BotController();
        ReflectionTestUtils.setField(controller, "botService", botService);
        ReflectionTestUtils.setField(controller, "aiService", aiService);
        ReflectionTestUtils.setField(controller, "imageGenerationService", imageService);
        ReflectionTestUtils.setField(controller, "imageRecognitionService", recognitionService);
        ReflectionTestUtils.setField(controller, "contextManager", new ContextManager());
        return controller;
    }
}
