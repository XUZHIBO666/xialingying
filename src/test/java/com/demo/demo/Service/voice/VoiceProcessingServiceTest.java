package com.demo.demo.Service.voice;

import com.lth.wechat.ilink.dto.message.VoiceContent;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VoiceProcessingServiceTest {

    @Test
    void usesAsrWhenConversionAndAsrAreConfigured() throws Exception {
        AudioConverter converter = mock(AudioConverter.class);
        AsrService asr = mock(AsrService.class);
        when(converter.isConfigured()).thenReturn(true);
        when(asr.isConfigured()).thenReturn(true);
        when(converter.convertToWav(new byte[]{1})).thenReturn(new byte[]{2});
        when(asr.transcribe(new byte[]{2})).thenReturn("ASR文本");
        VoiceProcessingService service = new VoiceProcessingService(converter, asr);

        VoiceProcessingService.Result result = service.recognize(voice("官方文本"), () -> new byte[]{1});

        assertEquals("ASR文本", result.text());
        assertEquals("asr", result.source());
    }

    @Test
    void fallsBackToOfficialTextWhenAsrFails() throws Exception {
        AudioConverter converter = mock(AudioConverter.class);
        AsrService asr = mock(AsrService.class);
        when(converter.isConfigured()).thenReturn(true);
        when(asr.isConfigured()).thenReturn(true);
        when(converter.convertToWav(new byte[]{1})).thenThrow(new IOException("bad silk"));
        VoiceProcessingService service = new VoiceProcessingService(converter, asr);

        VoiceProcessingService.Result result = service.recognize(voice("官方文本"), () -> new byte[]{1});

        assertEquals("官方文本", result.text());
        assertEquals("official-fallback", result.source());
    }

    private VoiceContent voice(String text) {
        return new VoiceContent("query", "key", 4, 1, 16000, 16, 1000, text);
    }
}
