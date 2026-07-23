package com.demo.demo.Service.voice;

import com.demo.demo.Service.context.ContextManager;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VoiceMessageServiceTest {

    private final AudioCodecService codec = mock(AudioCodecService.class);
    private final AsrService asr = mock(AsrService.class);
    private final TtsService tts = mock(TtsService.class);
    private final VoiceMessageService service = new VoiceMessageService(
            codec, asr, mock(com.demo.demo.Service.AIService.class), tts,
            new ContextManager());

    // ==================== recognize() ====================

    @Test
    void recognizeReturnsTranscribedText() throws Exception {
        byte[] inputSilk = new byte[]{1};
        byte[] inputPcm = new byte[]{1, 0};
        when(codec.silkToPcm(inputSilk)).thenReturn(inputPcm);
        when(asr.transcribe(inputPcm)).thenReturn("你好世界");

        VoiceMessageService.Result result = service.recognize("wx-user", inputSilk);

        assertEquals("你好世界", result.text());
        assertFalse(result.hasMp3());
    }

    @Test
    void asrFailureReturnsFallbackText() throws Exception {
        when(codec.silkToPcm(new byte[]{1})).thenThrow(new IOException("bad silk"));

        VoiceMessageService.Result result = service.recognize("wx-user", new byte[]{1});

        assertEquals(VoiceMessageService.ASR_FAILURE_TEXT, result.text());
        assertFalse(result.hasMp3());
    }

    @Test
    void blankAsrResultReturnsFallbackText() throws Exception {
        when(codec.silkToPcm(new byte[]{1})).thenReturn(new byte[]{1, 0});
        when(asr.transcribe(new byte[]{1, 0})).thenReturn("  ");

        VoiceMessageService.Result result = service.recognize("wx-user", new byte[]{1});

        assertEquals(VoiceMessageService.ASR_FAILURE_TEXT, result.text());
    }

    // ==================== synthesizeReply() ====================

    @Test
    void synthesizeReplyReturnsMp3Bytes() throws Exception {
        byte[] outputMp3 = new byte[]{'I', 'D', '3'};
        when(tts.synthesize("你好")).thenReturn(outputMp3);

        byte[] result = service.synthesizeReply("你好");

        assertEquals(outputMp3, result);
    }

    @Test
    void synthesizeReplyBlankTextReturnsNull() {
        assertNull(service.synthesizeReply(""));
        assertNull(service.synthesizeReply(null));
    }

    @Test
    void synthesizeReplyTtsFailureReturnsNull() throws Exception {
        when(tts.synthesize("你好")).thenThrow(new IOException("tts failed"));

        byte[] result = service.synthesizeReply("你好");

        assertNull(result);
    }
}
