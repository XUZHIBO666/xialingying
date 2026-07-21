package com.demo.demo.Service.voice;

import com.demo.demo.config.VoiceProperties;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

@Service
public class SiliconFlowAsrService implements AsrService {

    private static final MediaType WAV = MediaType.parse("audio/wav");
    private static final int MAX_AUDIO_BYTES = 50 * 1024 * 1024;

    private final VoiceProperties properties;
    private final OkHttpClient httpClient;

    @Autowired
    public SiliconFlowAsrService(VoiceProperties properties) {
        this(properties, new OkHttpClient());
    }

    SiliconFlowAsrService(VoiceProperties properties, OkHttpClient httpClient) {
        this.properties = properties;
        this.httpClient = httpClient.newBuilder()
                .callTimeout(properties.getAsr().getTimeout())
                .build();
    }

    @Override
    public boolean isConfigured() {
        return !properties.getAsr().getApiKey().isBlank()
                && !properties.getAsr().getApiUrl().isBlank()
                && !properties.getAsr().getModel().isBlank();
    }

    @Override
    public String transcribe(byte[] pcmAudio) throws IOException {
        if (!isConfigured()) {
            throw new IOException("ASR 未配置");
        }
        if (pcmAudio == null || pcmAudio.length == 0 || pcmAudio.length > MAX_AUDIO_BYTES
                || pcmAudio.length % 2 != 0) {
            throw new IOException("ASR 音频大小无效");
        }
        byte[] wavAudio = wrapPcmAsWav(pcmAudio);

        RequestBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", properties.getAsr().getModel())
                .addFormDataPart("file", "voice.wav", RequestBody.create(wavAudio, WAV))
                .build();
        Request request = new Request.Builder()
                .url(trimTrailingSlash(properties.getAsr().getApiUrl()) + "/v1/audio/transcriptions")
                .header("Authorization", "Bearer " + properties.getAsr().getApiKey())
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("语音识别失败，HTTP " + response.code());
            }
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            String text = root.has("text") && !root.get("text").isJsonNull()
                    ? root.get("text").getAsString().trim() : "";
            if (text.isEmpty()) {
                throw new IOException("语音识别结果为空");
            }
            return text;
        } catch (RuntimeException e) {
            throw new IOException("语音识别响应格式错误", e);
        }
    }

    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private byte[] wrapPcmAsWav(byte[] pcm) {
        ByteBuffer wav = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        wav.putInt(36 + pcm.length);
        wav.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        wav.putInt(16);
        wav.putShort((short) 1);
        wav.putShort((short) 1);
        wav.putInt(16000);
        wav.putInt(16000 * 2);
        wav.putShort((short) 2);
        wav.putShort((short) 16);
        wav.put("data".getBytes(StandardCharsets.US_ASCII));
        wav.putInt(pcm.length);
        wav.put(pcm);
        return wav.array();
    }
}
