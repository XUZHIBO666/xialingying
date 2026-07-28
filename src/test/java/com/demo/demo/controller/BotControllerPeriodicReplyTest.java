package com.demo.demo.controller;

import com.demo.demo.Service.AIService;
import com.demo.demo.Service.BotService;
import com.demo.demo.Service.ImageGenerationService;
import com.demo.demo.Service.ImageRecognitionService;
import com.demo.demo.Service.MultiBotManager;
import com.demo.demo.Service.PeriodicReplyService;
import com.demo.demo.Service.context.ContextManager;
import com.demo.demo.Service.tool.ImageGenerationTool;
import com.demo.demo.Service.tool.VoiceReplyTool;
import com.demo.demo.Service.voice.VoiceMessageHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class BotControllerPeriodicReplyTest {

    @Test
    void initializationConfiguresPeriodicReplyCallbacks() {
        BotController controller = new BotController();
        PeriodicReplyService periodicReplyService = mock(PeriodicReplyService.class);
        ReflectionTestUtils.setField(
                controller, "periodicReplyService", periodicReplyService);
        ReflectionTestUtils.setField(
                controller, "multiBotManager", mock(MultiBotManager.class));
        ReflectionTestUtils.setField(controller, "aiService", mock(AIService.class));
        ReflectionTestUtils.setField(
                controller, "imageGenerationTool", mock(ImageGenerationTool.class));
        ReflectionTestUtils.setField(
                controller, "voiceReplyTool", mock(VoiceReplyTool.class));
        ReflectionTestUtils.setField(
                controller, "botService", mock(BotService.class));
        ReflectionTestUtils.setField(
                controller, "imageGenerationService", mock(ImageGenerationService.class));
        ReflectionTestUtils.setField(
                controller, "imageRecognitionService", mock(ImageRecognitionService.class));
        ReflectionTestUtils.setField(
                controller, "voiceMessageHandler", mock(VoiceMessageHandler.class));
        ReflectionTestUtils.setField(
                controller, "contextManager", mock(ContextManager.class));

        controller.initAutoReply();

        verify(periodicReplyService).configure(any(), any(), any());
    }
}
