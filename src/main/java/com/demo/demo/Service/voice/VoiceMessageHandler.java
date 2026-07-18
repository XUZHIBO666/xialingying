package com.demo.demo.Service.voice;

import com.lth.wechat.ilink.dto.message.VoiceContent;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.function.Supplier;

@Component
public class VoiceMessageHandler {

    private final VoiceProcessingService processingService;

    public VoiceMessageHandler(VoiceProcessingService processingService) {
        this.processingService = processingService;
    }

    public VoiceProcessingService.Result handle(VoiceContent voice, Supplier<byte[]> downloader)
            throws IOException {
        return processingService.recognize(voice, downloader);
    }
}
