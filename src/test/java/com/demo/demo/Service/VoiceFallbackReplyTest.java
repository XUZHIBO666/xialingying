package com.demo.demo.Service;

import com.lth.wechat.ilink.ILinkClient;
import com.lth.wechat.ilink.LoginCredentials;
import com.lth.wechat.ilink.dto.message.VoiceContent;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

class VoiceFallbackReplyTest {

    @Test
    void voiceMessageSendsTextFallbackWhenHandlerIsUnavailable() throws Exception {
        ILinkClient client = mock(ILinkClient.class);
        BotService botService = loggedInBotService(client);
        VoiceContent voice = new VoiceContent(
                "encrypt-query", "aes-key", 4, 1, 16000, 16, 1000, "");
        ReflectionTestUtils.invokeMethod(botService, "processVoiceMessage", "wx-user", "ctx-token", voice);

        verify(client, timeout(1000)).sendTextMessage(any(LoginCredentials.class), eq("wx-user"),
                eq("ctx-token"), eq("没有听清，请重新发送一段语音。"));
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
