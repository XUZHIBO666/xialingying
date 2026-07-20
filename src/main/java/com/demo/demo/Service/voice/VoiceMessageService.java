package com.demo.demo.Service.voice;

import com.demo.demo.Service.AIService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class VoiceMessageService {

    public static final String ASR_FAILURE_TEXT = "没有听清，请重新发送一段语音。";
    public static final String LLM_FAILURE_TEXT = "当前服务暂时不可用，请稍后重试。";

    private final AudioCodecService audioCodecService;
    private final AsrService asrService;
    private final AIService aiService;
    private final TtsService ttsService;

    public VoiceMessageService(AudioCodecService audioCodecService, AsrService asrService,
                               AIService aiService, TtsService ttsService) {
        this.audioCodecService = audioCodecService;
        this.asrService = asrService;
        this.aiService = aiService;
        this.ttsService = ttsService;
    }

    public Result process(String userId, byte[] silkAudio) {
        String recognizedText;
        try {
            byte[] pcm = audioCodecService.silkToPcm(silkAudio);
            recognizedText = asrService.transcribe(pcm);
            if (recognizedText == null || recognizedText.isBlank()) {
                return Result.textOnly(ASR_FAILURE_TEXT);
            }
            recognizedText = recognizedText.trim();
        } catch (Exception e) {
            log.warn("[语音处理] ASR 阶段失败: {}", e.getMessage());
            return Result.textOnly(ASR_FAILURE_TEXT);
        }

        String reply;
        try {
            reply = aiService.chat(userId, recognizedText);
            if (reply == null || reply.isBlank()) {
                return Result.textOnly(LLM_FAILURE_TEXT);
            }
            reply = reply.trim();
        } catch (Exception e) {
            log.warn("[语音处理] LLM 阶段失败: {}", e.getMessage());
            return Result.textOnly(LLM_FAILURE_TEXT);
        }

        try {
            byte[] replyPcm = ttsService.synthesize(reply);
            byte[] replySilk = audioCodecService.pcmToSilk(replyPcm);
            int playtimeMs = Math.max(1, (int) Math.min(Integer.MAX_VALUE,
                    replyPcm.length * 1000L / (16000 * 2)));
            return new Result(reply, replySilk, playtimeMs);
        } catch (Exception e) {
            log.warn("[语音处理] TTS 或 SILK 编码失败，降级文字回复: {}", e.getMessage());
            return Result.textOnly(reply);
        }
    }

    public record Result(String text, byte[] silkAudio, int playtimeMs) {
        public static Result textOnly(String text) {
            return new Result(text, null, 0);
        }

        public boolean hasVoice() {
            return silkAudio != null && silkAudio.length > 0;
        }
    }
}
