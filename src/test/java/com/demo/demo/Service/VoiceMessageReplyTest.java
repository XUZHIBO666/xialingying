package com.demo.demo.Service;

import com.demo.demo.Service.voice.VoiceMessageHandler;
import com.demo.demo.Service.voice.VoiceProcessingService;
import com.lth.wechat.ilink.ILinkClient;
import com.lth.wechat.ilink.LoginCredentials;
import com.lth.wechat.ilink.dto.message.VoiceContent;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VoiceMessageReplyTest {

    @Test
    void recognizedVoiceUsesExistingTextReplyHandler() throws Exception {
        ILinkClient client = mock(ILinkClient.class);
        BotService botService = loggedInBotService(client);
        VoiceMessageHandler voiceHandler = mock(VoiceMessageHandler.class);
        VoiceContent voice = new VoiceContent(
                "encrypt-query", "aes-key", 4, 1, 16000, 16, 1000, "官方文本");
        when(voiceHandler.handle(eq(voice), any())).thenReturn(
                new VoiceProcessingService.Result("今天天气怎么样", "asr", 10, 20, 30));
        botService.setVoiceMessageHandler(voiceHandler);
        AtomicReference<String> receivedText = new AtomicReference<>();
        botService.setAutoReply((fromUser, contextToken, text) -> {
            receivedText.set(text);
            return "这是LLM回复";
        });

        ReflectionTestUtils.invokeMethod(botService, "processVoiceMessage", "wx-user", "ctx-token", voice);

        verify(client, timeout(2000)).sendTextMessage(any(LoginCredentials.class), eq("wx-user"),
                eq("ctx-token"), eq("这是LLM回复"));
        assertEquals("今天天气怎么样", receivedText.get());
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
}
