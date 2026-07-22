package com.demo.demo.Service;

import com.demo.demo.Service.voice.VoiceMessageHandler;
import com.lth.wechat.ilink.ILinkClient;
import com.lth.wechat.ilink.LoginCredentials;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VoiceMessageReplyTest {

    @Test
    void uploadsAndSendsMp3FileReply() throws Exception {
        ILinkClient client = mock(ILinkClient.class);
        BotService botService = loggedInBotService(client);
        VoiceMessageHandler voiceHandler = mock(VoiceMessageHandler.class);
        byte[] replyMp3 = new byte[]{'I', 'D', '3'};
        ILinkClient.MediaInfo media = new ILinkClient.MediaInfo("query", "key", replyMp3.length);

        when(voiceHandler.synthesize("这是LLM回复")).thenReturn(replyMp3);
        when(client.uploadMedia(any(LoginCredentials.class), eq(3), eq("wx-user"), eq(replyMp3)))
                .thenReturn(media);
        botService.setVoiceMessageHandler(voiceHandler);
        botService.setAutoReply((fromUser, ctxToken, text) -> "这是LLM回复");

        // 输入含"语音"关键词 → isVoiceRequest=true → TTS → MP3
        ReflectionTestUtils.invokeMethod(botService, "processTextMessage",
                "wx-user", "ctx-token", "用语音回复我");

        verify(client, timeout(2000)).uploadMedia(any(LoginCredentials.class), eq(3),
                eq("wx-user"), eq(replyMp3));
        verify(client, timeout(2000)).sendFileMessage(any(LoginCredentials.class), eq("wx-user"),
                eq("ctx-token"), eq(media), matches("voice-reply-\\d+\\.mp3"), anyLong());
    }

    @Test
    void ttsFailureFallsBackToText() throws Exception {
        ILinkClient client = mock(ILinkClient.class);
        BotService botService = loggedInBotService(client);
        VoiceMessageHandler voiceHandler = mock(VoiceMessageHandler.class);

        when(voiceHandler.synthesize("这是LLM回复")).thenReturn(null); // TTS 失败
        botService.setVoiceMessageHandler(voiceHandler);
        botService.setAutoReply((fromUser, ctxToken, text) -> "这是LLM回复");

        ReflectionTestUtils.invokeMethod(botService, "processTextMessage",
                "wx-user", "ctx-token", "用语音说");

        // TTS 失败 → 降级发文字
        verify(client, timeout(2000)).sendTextMessage(any(LoginCredentials.class), eq("wx-user"),
                eq("ctx-token"), eq("这是LLM回复"));
        verify(client, never()).sendFileMessage(any(LoginCredentials.class), anyString(),
                anyString(), any(), anyString(), anyLong());
    }

    @Test
    void noVoiceKeywordSendsTextDirectly() throws Exception {
        ILinkClient client = mock(ILinkClient.class);
        BotService botService = loggedInBotService(client);
        VoiceMessageHandler voiceHandler = mock(VoiceMessageHandler.class);
        botService.setVoiceMessageHandler(voiceHandler);
        botService.setAutoReply((fromUser, ctxToken, text) -> "普通回复");

        ReflectionTestUtils.invokeMethod(botService, "processTextMessage",
                "wx-user", "ctx-token", "今天天气怎么样");

        // 没有语音关键词 → 直接发文字，不调 TTS
        verify(client, timeout(2000)).sendTextMessage(any(LoginCredentials.class), eq("wx-user"),
                eq("ctx-token"), eq("普通回复"));
        verify(voiceHandler, never()).synthesize(anyString());
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
