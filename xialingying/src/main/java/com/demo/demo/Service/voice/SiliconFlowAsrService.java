package com.demo.demo.Service.voice;

import com.demo.demo.config.VoiceProperties;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * ASR 语音识别服务 —— OkHttp 直调 SiliconFlow API。
 *
 * <p>绕过 Spring AI，直接用 OkHttp 构造 multipart/form-data 请求
 * 调用 {@code POST https://api.siliconflow.cn/v1/audio/transcriptions}。</p>
 *
 * <p>链路：PCM S16LE 16kHz mono → 标准 WAV → OkHttp multipart → SiliconFlow → JSON(text)。</p>
 */
@Slf4j
@Service
public class SiliconFlowAsrService implements AsrService {

    private static final MediaType MEDIA_TYPE_WAV = MediaType.parse("audio/wav");

    @Autowired
    private VoiceProperties voiceProperties;

    private OkHttpClient httpClient;
    private String apiKey;
    private String apiUrl;
    private String model;

    @PostConstruct
    public void init() {
        VoiceProperties.Asr cfg = voiceProperties.getAsr();
        this.apiKey = cfg.getApiKey();
        this.apiUrl = cfg.getApiUrl();
        this.model = cfg.getModel();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(cfg.getTimeout())
                .writeTimeout(Duration.ofSeconds(30))
                .build();

        if (isConfigured()) {
            log.info("[ASR] 初始化完成（OkHttp 直调） apiUrl={} model={}", apiUrl, model);
        } else {
            log.warn("[ASR] 未配置 API Key（ai.voice.asr.api-key），语音识别不可用。" +
                    "请设置环境变量 VOICE_ASR_API_KEY 或在 yaml 中配置。");
        }
    }

    @Override
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * {@inheritDoc}
     *
     * <p>PCM S16LE 16kHz mono → WAV → OkHttp multipart → SiliconFlow ASR。</p>
     */
    @Override
    public String transcribe(byte[] pcmAudio) throws IOException {
        if (!isConfigured()) {
            throw new IOException("ASR 未配置，请设置 VOICE_ASR_API_KEY");
        }
        if (pcmAudio == null || pcmAudio.length == 0) {
            throw new IOException("ASR 音频为空");
        }

        // ---- 1. PCM → WAV ----
        byte[] wavAudio = pcmToWav(pcmAudio, 16000, 16, 1);
        log.info("[ASR] 音频已转 WAV pcmSize={} wavSize={}", pcmAudio.length, wavAudio.length);

        // ---- 2. 构造 multipart/form-data ----
        RequestBody fileBody = RequestBody.create(wavAudio, MEDIA_TYPE_WAV);
        MultipartBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "audio.wav", fileBody)
                .addFormDataPart("model", model)
                .build();

        // ---- 3. 发送请求（自动去除 apiUrl 末尾 /v1 避免双写） ----
        String base = apiUrl.endsWith("/v1") ? apiUrl.substring(0, apiUrl.length() - 3) : apiUrl;
        String url = base + "/v1/audio/transcriptions";
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + apiKey)
                .post(body)
                .build();

        log.info("[ASR] POST {} model={}", url, model);

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null
                    ? response.body().string() : "";

            if (!response.isSuccessful()) {
                log.error("[ASR] API 返回错误 status={} body={}", response.code(), responseBody);
                throw new IOException("ASR API 错误 " + response.code() + ": " + responseBody);
            }

            // ---- 4. 解析 JSON: {"text": "..."} ----
            JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
            String text = json.has("text") ? json.get("text").getAsString() : "";

            log.info("[ASR] 识别成功 textLength={}", text.length());
            return text.trim();
        } catch (IOException e) {
            log.error("[ASR] 网络请求失败: {}", e.getMessage(), e);
            throw e;
        }
    }

    // ==================== PCM → WAV 转换 ====================

    static byte[] pcmToWav(byte[] pcm, int sampleRate, int bitsPerSample, int channels) {
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        int dataSize = pcm.length;
        int totalSize = dataSize + 36;

        byte[] wav = new byte[44 + dataSize];

        // RIFF header
        writeString(wav, 0, "RIFF");
        writeInt32LE(wav, 4, totalSize);
        writeString(wav, 8, "WAVE");

        // fmt sub-chunk
        writeString(wav, 12, "fmt ");
        writeInt32LE(wav, 16, 16);
        writeInt16LE(wav, 20, 1);
        writeInt16LE(wav, 22, channels);
        writeInt32LE(wav, 24, sampleRate);
        writeInt32LE(wav, 28, byteRate);
        writeInt16LE(wav, 32, blockAlign);
        writeInt16LE(wav, 34, bitsPerSample);

        // data sub-chunk
        writeString(wav, 36, "data");
        writeInt32LE(wav, 40, dataSize);

        System.arraycopy(pcm, 0, wav, 44, dataSize);
        return wav;
    }

    private static void writeString(byte[] buf, int offset, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, buf, offset, bytes.length);
    }

    private static void writeInt16LE(byte[] buf, int offset, int value) {
        buf[offset] = (byte) value;
        buf[offset + 1] = (byte) (value >> 8);
    }

    private static void writeInt32LE(byte[] buf, int offset, int value) {
        buf[offset] = (byte) value;
        buf[offset + 1] = (byte) (value >> 8);
        buf[offset + 2] = (byte) (value >> 16);
        buf[offset + 3] = (byte) (value >> 24);
    }
}
