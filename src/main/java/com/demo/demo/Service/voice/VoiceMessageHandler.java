package com.demo.demo.Service.voice;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.function.Supplier;

@Component
public class VoiceMessageHandler {

    private final VoiceMessageService voiceMessageService;

    public VoiceMessageHandler(VoiceMessageService voiceMessageService) {
        this.voiceMessageService = voiceMessageService;
    }

    public VoiceMessageService.Result handle(String userId, Supplier<byte[]> downloader) throws IOException {
        return voiceMessageService.process(userId, downloader.get());
    }
}
