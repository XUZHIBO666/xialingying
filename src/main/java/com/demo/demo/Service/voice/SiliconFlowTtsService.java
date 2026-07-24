package com.demo.demo.Service.voice;

import com.alibaba.cloud.ai.dashscope.api.DashScopeAudioSpeechApi;
import com.alibaba.cloud.ai.dashscope.audio.tts.DashScopeAudioSpeechModel;
import com.alibaba.cloud.ai.dashscope.audio.tts.DashScopeAudioSpeechOptions;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.audio.tts.TextToSpeechPrompt;
import org.springframework.ai.audio.tts.TextToSpeechResponse;
import org.springframework.ai.model.SimpleApiKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.List;

/**
 * TTS 文字转语音服务 —— 基于 Spring AI Alibaba（DashScope / 百炼）。
 *
 * <p>使用 {@link DashScopeAudioSpeechModel} 调用百炼 CosyVoice 语音合成，
 * 通过 stream() 收集所有音频分片后拼接为完整 MP3 字节数组。</p>
 */
@Slf4j
@Service
public class SiliconFlowTtsService implements TtsService {

    @Value("${spring.ai.dashscope.api-key:}")
    private String apiKey;

    @Value("${ai.voice.tts.model:cosyvoice-v3-flash}")
    private String model;

    @Value("${ai.voice.tts.voice:longanyang}")
    private String voice;

    private DashScopeAudioSpeechModel speechModel;
    private DashScopeAudioSpeechOptions options;

    @PostConstruct
    public void init() {
        DashScopeAudioSpeechApi speechApi = DashScopeAudioSpeechApi.builder()
                .apiKey(new SimpleApiKey(apiKey))
                .baseUrl("https://dashscope.aliyuncs.com/api/v1")
                .build();
        this.options = DashScopeAudioSpeechOptions.builder()
                .model(model)
                .voice(voice)
                .format("mp3")
                .sampleRate(16000)
                .speed(1.0)
                .volume(50)
                .build();
        this.speechModel = DashScopeAudioSpeechModel.builder()
                .audioSpeechApi(speechApi)
                .defaultOptions(options)
                .build();
        log.info("[TTS] 初始化完成（DashScope 百炼） model={} voice={}", model, voice);
    }

    @Override
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank()
                && !"sk-placeholder".equals(apiKey);
    }

    @Override
    public byte[] synthesize(String text) throws IOException {
        if (!isConfigured()) {
            throw new IOException("TTS 未配置");
        }
        if (text == null || text.isBlank()) {
            throw new IOException("TTS 文本为空");
        }

        try {
            Flux<TextToSpeechResponse> stream = speechModel.stream(new TextToSpeechPrompt(text, options));
            byte[] audio = collectStreamBytes(stream);
            log.info("[TTS] 合成成功 audioBytes={}", audio.length);
            return audio;
        } catch (Exception e) {
            log.error("[TTS] 合成失败: {}", e.getMessage(), e);
            throw new IOException("TTS 合成失败: " + e.getMessage());
        }
    }

    private byte[] collectStreamBytes(Flux<TextToSpeechResponse> stream) {
        List<byte[]> chunks = stream
                .filter(r -> r != null && r.getResult() != null
                        && r.getResult().getOutput() != null)
                .map(r -> r.getResult().getOutput())
                .collectList()
                .block();

        if (chunks == null || chunks.isEmpty()) {
            return new byte[0];
        }

        int total = chunks.stream().mapToInt(b -> b.length).sum();
        byte[] result = new byte[total];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, result, offset, chunk.length);
            offset += chunk.length;
        }
        return result;
    }
}
