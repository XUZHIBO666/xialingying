package com.demo.demo.Service.voice;

import com.demo.demo.config.VoiceProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;

/**
 * TTS 文字转语音服务 —— OkHttp 直调 SiliconFlow API。
 *
 * <p>{@code POST https://api.siliconflow.cn/v1/audio/speech}，JSON 请求体，
 * 返回 MP3 二进制。</p>
 */
@Slf4j
@Service
public class SiliconFlowTtsService implements TtsService {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    @Autowired
    private VoiceProperties voiceProperties;

    private OkHttpClient httpClient;
    private String apiKey;
    private String apiUrl;
    private String model;
    private String voice;

    @PostConstruct
    public void init() {
        VoiceProperties.Tts cfg = voiceProperties.getTts();
        // TTS key 降级到 ASR key
        this.apiKey = !cfg.getApiKey().isBlank() ? cfg.getApiKey() : voiceProperties.getAsr().getApiKey();
        this.apiUrl = cfg.getApiUrl();
        this.model = cfg.getModel();
        this.voice = cfg.getVoice();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(cfg.getTimeout())
                .writeTimeout(Duration.ofSeconds(30))
                .build();

        if (isConfigured()) {
            log.info("[TTS] 初始化完成（OkHttp 直调） apiUrl={} model={} voice={}",
                    apiUrl, model, voice);
        } else {
            log.warn("[TTS] 未配置 API Key，语音合成不可用。");
        }
    }

    @Override
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public byte[] synthesize(String text) throws IOException {
        if (!isConfigured()) {
            throw new IOException("TTS 未配置，请设置 VOICE_ASR_API_KEY 或 VOICE_TTS_API_KEY");
        }
        if (text == null || text.isBlank()) {
            throw new IOException("TTS 文本为空");
        }

        // ---- 1. 构造 JSON 请求体 ----
        String json = String.format(
                "{\"model\":\"%s\",\"input\":\"%s\",\"voice\":\"%s\",\"response_format\":\"mp3\"}",
                model, escapeJson(text), voice);

        RequestBody body = RequestBody.create(json, JSON);

        // ---- 2. 发送请求（自动去除 apiUrl 末尾 /v1 避免双写） ----
        String base = apiUrl.endsWith("/v1") ? apiUrl.substring(0, apiUrl.length() - 3) : apiUrl;
        String url = base + "/v1/audio/speech";
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(body)
                .build();

        log.info("[TTS] POST {} voice={} textLength={}", url, voice, text.length());

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errBody = response.body() != null ? response.body().string() : "";
                log.error("[TTS] API 返回错误 status={} body={}", response.code(), errBody);
                throw new IOException("TTS API 错误 " + response.code() + ": " + errBody);
            }

            byte[] mp3 = response.body() != null ? response.body().bytes() : new byte[0];
            log.info("[TTS] 合成成功 mp3Bytes={}", mp3.length);
            return mp3;
        }
    }

    // ---- JSON 转义 ----

    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:   sb.append(c);
            }
        }
        return sb.toString();
    }
}
