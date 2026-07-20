package com.demo.demo.Service.voice;

import com.demo.demo.config.VoiceProperties;
import com.google.gson.JsonObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class SiliconFlowTtsService implements TtsService {

    private static final MediaType JSON = MediaType.parse("application/json");
    private static final int MAX_PCM_BYTES = 50 * 1024 * 1024;

    private final VoiceProperties properties;
    private final OkHttpClient httpClient;

    @Autowired
    public SiliconFlowTtsService(VoiceProperties properties) {
        this(properties, new OkHttpClient());
    }

    SiliconFlowTtsService(VoiceProperties properties, OkHttpClient httpClient) {
        this.properties = properties;
        this.httpClient = httpClient.newBuilder()
                .callTimeout(properties.getTts().getTimeout())
                .build();
    }

    @Override
    public boolean isConfigured() {
        return !properties.getTts().getApiKey().isBlank()
                && !properties.getTts().getApiUrl().isBlank()
                && !properties.getTts().getModel().isBlank()
                && !properties.getTts().getVoice().isBlank();
    }

    @Override
    public byte[] synthesize(String text) throws IOException {
        if (!isConfigured()) {
            throw new IOException("TTS 未配置");
        }
        if (text == null || text.isBlank()) {
            throw new IOException("TTS 文本为空");
        }

        JsonObject body = new JsonObject();
        body.addProperty("model", properties.getTts().getModel());
        body.addProperty("voice", properties.getTts().getVoice());
        body.addProperty("input", text);
        body.addProperty("response_format", "pcm");
        body.addProperty("sample_rate", 16000);
        body.addProperty("stream", false);

        Request request = new Request.Builder()
                .url(trimTrailingSlash(properties.getTts().getApiUrl()) + "/v1/audio/speech")
                .header("Authorization", "Bearer " + properties.getTts().getApiKey())
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("语音合成失败，HTTP " + response.code());
            }
            byte[] pcm = response.body() == null ? new byte[0] : response.body().bytes();
            if (pcm.length == 0 || pcm.length > MAX_PCM_BYTES || pcm.length % 2 != 0) {
                throw new IOException("语音合成结果大小无效");
            }
            return pcm;
        }
    }

    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
