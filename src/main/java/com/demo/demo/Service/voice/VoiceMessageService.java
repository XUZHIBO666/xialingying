package com.demo.demo.Service.voice;

import com.demo.demo.Service.AIService;
import com.demo.demo.Service.context.ContextManager;
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
    private final ContextManager contextManager;

    public VoiceMessageService(AudioCodecService audioCodecService, AsrService asrService,
                               AIService aiService, TtsService ttsService,
                               ContextManager contextManager) {
        this.audioCodecService = audioCodecService;
        this.asrService = asrService;
        this.aiService = aiService;
        this.ttsService = ttsService;
        this.contextManager = contextManager;
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
            contextManager.recordVoice(userId, recognizedText);
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
            byte[] replyMp3 = ttsService.synthesize(reply);
            return new Result(reply, replyMp3);
        } catch (Exception e) {
            log.warn("[语音处理] TTS 失败，降级文字回复: {}", e.getMessage());
            return Result.textOnly(reply);
        }
    }

    public record Result(String text, byte[] mp3Audio) {
        public static Result textOnly(String text) {
            return new Result(text, null);
        }

        public boolean hasMp3() {
            return mp3Audio != null && mp3Audio.length > 0;
        }
    }
}
