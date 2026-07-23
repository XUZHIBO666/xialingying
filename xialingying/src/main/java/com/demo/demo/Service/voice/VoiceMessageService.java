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

    /**
     * 语音识别：SILK 解码 + ASR 转文字。
     * 只做识别，不处理 AI 对话、不生成图片、不做 TTS。
     */
    public Result recognize(String userId, byte[] silkAudio) {
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

        log.info("[语音处理] 识别结果: {}", recognizedText);
        return Result.textOnly(recognizedText);
    }

    /** 文字转语音（供外部调用） */
    public byte[] synthesizeReply(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return ttsService.synthesize(text);
        } catch (Exception e) {
            log.warn("[语音处理] TTS 失败: {}", e.getMessage());
            return null;
        }
    }

    public record Result(String text, byte[] mp3Audio, byte[] imageBytes) {
        public Result(String text, byte[] mp3Audio) {
            this(text, mp3Audio, null);
        }

        public static Result textOnly(String text) {
            return new Result(text, null, null);
        }

        public boolean hasMp3() {
            return mp3Audio != null && mp3Audio.length > 0;
        }

        public boolean hasImage() {
            return imageBytes != null && imageBytes.length > 0;
        }
    }
}
