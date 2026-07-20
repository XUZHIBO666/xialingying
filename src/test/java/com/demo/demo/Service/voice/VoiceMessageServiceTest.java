package com.demo.demo.Service.voice;

import com.demo.demo.Service.AIService;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VoiceMessageServiceTest {

    private final AudioCodecService codec = mock(AudioCodecService.class);
    private final AsrService asr = mock(AsrService.class);
    private final AIService ai = mock(AIService.class);
    private final TtsService tts = mock(TtsService.class);
    private final VoiceMessageService service = new VoiceMessageService(codec, asr, ai, tts);

    @Test
    void createsMp3ReplyFromRecognizedVoice() throws Exception {
        byte[] inputSilk = new byte[]{1};
        byte[] inputPcm = new byte[]{1, 0};
        byte[] outputMp3 = new byte[]{'I', 'D', '3'};
        when(codec.silkToPcm(inputSilk)).thenReturn(inputPcm);
        when(asr.transcribe(inputPcm)).thenReturn("用户问题");
        when(ai.chat("wx-user", "用户问题")).thenReturn("LLM回答");
        when(tts.synthesize("LLM回答")).thenReturn(outputMp3);

        VoiceMessageService.Result result = service.process("wx-user", inputSilk);

        assertEquals("LLM回答", result.text());
        assertArrayEquals(outputMp3, result.mp3Audio());
        assertTrue(result.hasMp3());
        verify(codec, never()).pcmToSilk(any());
    }

    @Test
    void asrFailureReturnsExactPrompt() throws Exception {
        when(codec.silkToPcm(new byte[]{1})).thenThrow(new IOException("bad silk"));

        VoiceMessageService.Result result = service.process("wx-user", new byte[]{1});

        assertEquals("没有听清，请重新发送一段语音。", result.text());
        assertFalse(result.hasMp3());
    }

    @Test
    void blankAsrResultReturnsExactPrompt() throws Exception {
        when(codec.silkToPcm(new byte[]{1})).thenReturn(new byte[]{1, 0});
        when(asr.transcribe(new byte[]{1, 0})).thenReturn("  ");

        VoiceMessageService.Result result = service.process("wx-user", new byte[]{1});

        assertEquals("没有听清，请重新发送一段语音。", result.text());
    }

    @Test
    void llmFailureReturnsExactUnavailableMessage() throws Exception {
        when(codec.silkToPcm(new byte[]{1})).thenReturn(new byte[]{1, 0});
        when(asr.transcribe(new byte[]{1, 0})).thenReturn("问题");
        when(ai.chat("wx-user", "问题")).thenReturn(null);

        VoiceMessageService.Result result = service.process("wx-user", new byte[]{1});

        assertEquals("当前服务暂时不可用，请稍后重试。", result.text());
        assertFalse(result.hasMp3());
    }

    @Test
    void ttsFailureReturnsLlmText() throws Exception {
        prepareThroughLlm();
        when(tts.synthesize("回答")).thenThrow(new IOException("tts failed"));

        VoiceMessageService.Result result = service.process("wx-user", new byte[]{1});

        assertEquals("回答", result.text());
        assertFalse(result.hasMp3());
    }

    private void prepareThroughLlm() throws Exception {
        when(codec.silkToPcm(new byte[]{1})).thenReturn(new byte[]{1, 0});
        when(asr.transcribe(new byte[]{1, 0})).thenReturn("问题");
        when(ai.chat("wx-user", "问题")).thenReturn("回答");
    }
}
