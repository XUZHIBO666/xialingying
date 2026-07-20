package com.demo.demo.Service;

import com.demo.demo.Service.voice.VoiceMessageHandler;
import com.demo.demo.Service.voice.VoiceMessageService;
import com.lth.wechat.ilink.ILinkClient;
import com.lth.wechat.ilink.LoginCredentials;
import com.lth.wechat.ilink.dto.message.VoiceContent;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VoiceMessageReplyTest {

    @Test
    void uploadsAndSendsRecognizedVoiceReply() throws Exception {
        ILinkClient client = mock(ILinkClient.class);
        BotService botService = loggedInBotService(client);
        VoiceMessageService voiceService = mock(VoiceMessageService.class);
        VoiceMessageHandler voiceHandler = new VoiceMessageHandler(voiceService);
        VoiceContent voice = new VoiceContent(
                "encrypt-query", "aes-key", 4, 1, 16000, 16, 1000, "官方文本");
        byte[] inputSilk = new byte[]{1};
        byte[] replySilk = new byte[]{2, 3};
        ILinkClient.MediaInfo media = new ILinkClient.MediaInfo("query", "key", replySilk.length);
        when(client.downloadMedia("encrypt-query", "aes-key")).thenReturn(inputSilk);
        when(voiceService.process("wx-user", inputSilk)).thenReturn(
                new VoiceMessageService.Result("这是LLM回复", replySilk, 1200));
        when(client.uploadMedia(any(LoginCredentials.class), eq(4), eq("wx-user"), eq(replySilk)))
                .thenReturn(media);
        botService.setVoiceMessageHandler(voiceHandler);

        ReflectionTestUtils.invokeMethod(botService, "processVoiceMessage", "wx-user", "ctx-token", voice);

        verify(client, timeout(2000)).downloadMedia("encrypt-query", "aes-key");
        verify(client, timeout(2000)).uploadMedia(any(LoginCredentials.class), eq(4), eq("wx-user"), eq(replySilk));
        verify(client, timeout(2000)).sendVoiceMessage(any(LoginCredentials.class), eq("wx-user"),
                eq("ctx-token"), eq(media), eq(1200), eq(1));
        verify(client, never()).sendTextMessage(any(LoginCredentials.class), anyString(), anyString(), anyString());
    }

    @Test
    void voiceUploadFailureFallsBackToLlmText() throws Exception {
        ILinkClient client = mock(ILinkClient.class);
        BotService botService = loggedInBotService(client);
        VoiceMessageHandler voiceHandler = mock(VoiceMessageHandler.class);
        VoiceContent voice = new VoiceContent(
                "encrypt-query", "aes-key", 4, 1, 16000, 16, 1000, "");
        byte[] replySilk = new byte[]{2, 3};
        when(voiceHandler.handle(eq("wx-user"), any())).thenReturn(
                new VoiceMessageService.Result("这是LLM回复", replySilk, 1200));
        when(client.uploadMedia(any(LoginCredentials.class), eq(4), eq("wx-user"), eq(replySilk)))
                .thenThrow(new RuntimeException("upload failed"));
        botService.setVoiceMessageHandler(voiceHandler);

        ReflectionTestUtils.invokeMethod(botService, "processVoiceMessage", "wx-user", "ctx-token", voice);

        verify(client, timeout(2000)).sendTextMessage(any(LoginCredentials.class), eq("wx-user"),
                eq("ctx-token"), eq("这是LLM回复"));
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
