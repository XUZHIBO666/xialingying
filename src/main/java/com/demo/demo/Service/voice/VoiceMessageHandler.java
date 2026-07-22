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

    /** 语音识别：下载 → SILK解码 → ASR → 返回识别文字 */
    public VoiceMessageService.Result recognize(String userId, Supplier<byte[]> downloader) throws IOException {
        return voiceMessageService.recognize(userId, downloader.get());
    }

    /** TTS 合成 */
    public byte[] synthesize(String text) {
        return voiceMessageService.synthesizeReply(text);
    }
}
