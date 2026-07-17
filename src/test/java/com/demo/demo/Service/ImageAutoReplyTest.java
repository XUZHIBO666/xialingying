package com.demo.demo.Service;

import com.demo.demo.controller.BotController;
import com.lth.wechat.ilink.ILinkClient;
import com.lth.wechat.ilink.LoginCredentials;
import com.lth.wechat.ilink.dto.message.ImageContent;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ImageAutoReplyTest {

    @Test
    void imageRequestGeneratesAndSendsImageMessage() throws Exception {
        ILinkClient client = mock(ILinkClient.class);
        BotService botService = loggedInBotService(client);
        AIService aiService = mock(AIService.class);
        ImageGenerationService imageService = mock(ImageGenerationService.class);
        ImageRecognitionService recognitionService = mock(ImageRecognitionService.class);
        byte[] imageBytes = "image-bytes".getBytes();
        ILinkClient.MediaInfo media = new ILinkClient.MediaInfo("query", "aes-key", imageBytes.length);

        when(imageService.isImageRequest("生成图片：星空")).thenReturn(true);
        when(imageService.extractPrompt("生成图片：星空")).thenReturn("星空");
        when(imageService.isConfigured()).thenReturn(true);
        when(imageService.generateImage("星空")).thenReturn(imageBytes);
        when(client.uploadMedia(any(LoginCredentials.class), eq(1), eq("wx-user"), eq(imageBytes))).thenReturn(media);

        BotController controller = controller(botService, aiService, imageService, recognitionService);
        controller.initAutoReply();

        botService.processTextMessage("wx-user", "ctx-token", "生成图片：星空");

        verify(client).uploadMedia(any(LoginCredentials.class), eq(1), eq("wx-user"), eq(imageBytes));
        verify(client).sendImageMessage(any(LoginCredentials.class), eq("wx-user"), eq("ctx-token"), eq(media));
        verify(client, never()).sendTextMessage(any(LoginCredentials.class), anyString(), anyString(), anyString());
        verifyNoInteractions(aiService);
    }

    @Test
    void unconfiguredImageServiceRepliesWithTextHint() throws Exception {
        ILinkClient client = mock(ILinkClient.class);
        BotService botService = loggedInBotService(client);
        AIService aiService = mock(AIService.class);
        ImageGenerationService imageService = mock(ImageGenerationService.class);
        ImageRecognitionService recognitionService = mock(ImageRecognitionService.class);

        when(imageService.isImageRequest("画一张海边日落")).thenReturn(true);
        when(imageService.extractPrompt("画一张海边日落")).thenReturn("海边日落");
        when(imageService.isConfigured()).thenReturn(false);

        BotController controller = controller(botService, aiService, imageService, recognitionService);
        controller.initAutoReply();

        botService.processTextMessage("wx-user", "ctx-token", "画一张海边日落");

        verify(client).sendTextMessage(any(LoginCredentials.class), eq("wx-user"), eq("ctx-token"),
                eq("图片生成未配置，请管理员设置 IMAGE_API_KEY"));
        verify(client, never()).uploadMedia(any(LoginCredentials.class), anyInt(), anyString(), any(byte[].class));
        verify(client, never()).sendImageMessage(any(LoginCredentials.class), anyString(), anyString(), any());
        verifyNoInteractions(aiService);
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

        verify(recognitionService).recognize(imageBytes);
        verify(client).sendTextMessage(any(LoginCredentials.class), eq("wx-user"), eq("ctx-token"),
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

        verify(client).downloadMedia("encrypt-query-param", "aes-key");
        verify(client, never()).downloadMedia(eq("https://wrong-url.example/image.jpg"), anyString());
        verify(client).sendTextMessage(any(LoginCredentials.class), eq("wx-user"), eq("ctx-token"),
                eq("图片识别成功。"));
    }

    @SuppressWarnings("unchecked")
    private BotService loggedInBotService(ILinkClient client) {
        BotService botService = new BotService(client);
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
        return controller;
    }
}
