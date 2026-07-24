package com.demo.demo.Service;

import com.demo.demo.Service.context.ContextManager;
import com.demo.demo.controller.BotController;
import com.lth.wechat.ilink.ILinkClient;
import com.lth.wechat.ilink.LoginCredentials;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Asserts that all WeChat weather queries go through ReactAgent Tool Calling
 * and never through a keyword shortcut or direct WeatherUtil call.
 */
class WeatherAgentRoutingTest {

    @Test
    @DisplayName("tomorrow rain query reaches ReactAgent with original text")
    void weatherQueryReachesAgentUnchanged() {
        ILinkClient client = mock(ILinkClient.class);
        BotService botService = loggedInBotService(client);
        AIService aiService = mock(AIService.class);
        ImageGenerationService imageService = mock(ImageGenerationService.class);
        ImageRecognitionService recognitionService = mock(ImageRecognitionService.class);

        when(imageService.isConfigured()).thenReturn(false);
        when(aiService.isConfigured()).thenReturn(true);
        when(aiService.chat("wx-user", "明天会下雨吗"))
                .thenReturn("请告诉我想查询的城市。");

        BotController controller = controller(botService, aiService, imageService, recognitionService);
        controller.initAutoReply();

        botService.processTextMessage("wx-user", "ctx-token", "明天会下雨吗");

        verify(aiService, timeout(1000)).chat("wx-user", "明天会下雨吗");
        verify(aiService, never()).chat(eq("wx-user"), contains("以下是实时天气数据"));
        verify(client, timeout(1000)).sendTextMessage(
                any(LoginCredentials.class), eq("wx-user"), eq("ctx-token"),
                eq("请告诉我想查询的城市。"));
    }

    @Test
    @DisplayName("hot day query reaches ReactAgent with original text")
    void hotDayQueryReachesAgent() {
        ILinkClient client = mock(ILinkClient.class);
        BotService botService = loggedInBotService(client);
        AIService aiService = mock(AIService.class);
        ImageGenerationService imageService = mock(ImageGenerationService.class);
        ImageRecognitionService recognitionService = mock(ImageRecognitionService.class);

        when(imageService.isConfigured()).thenReturn(false);
        when(aiService.isConfigured()).thenReturn(true);
        when(aiService.chat("wx-user", "今天热不热"))
                .thenReturn("你想查询哪个城市呢？");

        BotController controller = controller(botService, aiService, imageService, recognitionService);
        controller.initAutoReply();

        botService.processTextMessage("wx-user", "ctx-token", "今天热不热");

        verify(aiService, timeout(1000)).chat("wx-user", "今天热不热");
        verify(client, timeout(1000)).sendTextMessage(
                any(LoginCredentials.class), eq("wx-user"), eq("ctx-token"),
                eq("你想查询哪个城市呢？"));
    }

    @Test
    @DisplayName("follow-up query reaches Agent unchanged")
    void followUpQueryReachesAgent() {
        ILinkClient client = mock(ILinkClient.class);
        BotService botService = loggedInBotService(client);
        AIService aiService = mock(AIService.class);
        ImageGenerationService imageService = mock(ImageGenerationService.class);
        ImageRecognitionService recognitionService = mock(ImageRecognitionService.class);

        when(imageService.isConfigured()).thenReturn(false);
        when(aiService.isConfigured()).thenReturn(true);
        when(aiService.chat("wx-user", "那后天呢？"))
                .thenReturn("后天北京多云，气温24°C到31°C。");

        BotController controller = controller(botService, aiService, imageService, recognitionService);
        controller.initAutoReply();

        botService.processTextMessage("wx-user", "ctx-token", "那后天呢？");

        verify(aiService, timeout(1000)).chat("wx-user", "那后天呢？");
        verify(client, timeout(1000)).sendTextMessage(
                any(LoginCredentials.class), eq("wx-user"), eq("ctx-token"),
                eq("后天北京多云，气温24°C到31°C。"));
    }

    // ==================== Helpers ====================

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
        ReflectionTestUtils.setField(controller, "contextManager", new ContextManager());
        return controller;
    }
}
