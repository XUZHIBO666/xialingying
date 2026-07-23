package com.demo.demo.Service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 语音服务：封装语音转文字（STT）和文字转语音（TTS）功能
 * 支持 SiliconFlow、百度语音、OpenAI 等兼容接口
 *
 * 配置方式（application.yml）：
 * ai.voice.api.key: 你的API_KEY
 * ai.voice.api.url: https://api.siliconflow.cn
 * ai.voice.stt.model: FunAudioLLM/SenseVoiceSmall
 * ai.voice.tts.model: CosyVoice2-0.5B
 * ai.voice.tts.voice: default
 */
@Slf4j
@Service
public class VoiceService {

    @Value("${ai.voice.api.key:}")
    private String apiKey;

    @Value("${ai.voice.api.url:https://api.siliconflow.cn}")
    private String apiUrl;

    @Value("${ai.voice.stt.model:FunAudioLLM/SenseVoiceSmall}")
    private String sttModel;

    @Value("${ai.voice.tts.model:FunAudioLLM/CosyVoice2-0.5B}")
    private String ttsModel;

    @Value("${ai.voice.tts.voice:alloy}")
    private String defaultVoice;

    @Value("${ai.voice.tts.speed:1.0}")
    private double speed;

    @Value("${ai.voice.tts.gain:0.0}")
    private double gain;

    @Value("${ai.voice.file.host.url:https://transfer.sh}")
    private String fileHostUrl;

    @Value("${qiniu.access-key:}")
    private String qiniuAk;
    @Value("${qiniu.secret-key:}")
    private String qiniuSk;
    @Value("${qiniu.bucket:voice-bot}")
    private String qiniuBucket;
    @Value("${qiniu.domain:}")
    private String qiniuDomain;

    // 本地音频存储（内存中临时保存最近生成的 MP3）
    private final java.util.concurrent.ConcurrentHashMap<String, byte[]> audioStore = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_STORED_AUDIO = 20;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    // ==================== STT（语音转文字）====================

    /**
     * 语音转文字（STT）
     * 调用 SiliconFlow 的音频转录接口：POST /v1/audio/transcriptions
     *
     * @param audioBytes 语音文件字节数据
     * @param format     语音格式（mp3/wav/pcm/silk 等）
     * @return 识别出的文字，失败返回 null
     */
    public String speechToText(byte[] audioBytes, String format) {
        if (!isConfigured()) {
            log.warn("[Voice] STT 跳过：API Key 未配置");
            return null;
        }
        if (audioBytes == null || audioBytes.length == 0) {
            log.warn("[Voice] STT 跳过：音频数据为空");
            return null;
        }

        try {
            // 根据格式设置 MIME 类型
            String mimeType = getMimeType(format);

            // 构建 multipart/form-data 请求
            RequestBody body = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("model", sttModel)
                    .addFormDataPart("file", "audio." + format,
                            RequestBody.create(audioBytes, MediaType.parse(mimeType)))
                    .build();

            Request request = new Request.Builder()
                    .url(trimTrailingSlash(apiUrl) + "/v1/audio/transcriptions")
                    .header("Authorization", "Bearer " + apiKey)
                    .post(body)
                    .build();

            log.info("[Voice] STT 请求 model={} format={} size={} bytes",
                    sttModel, format, audioBytes.length);

            try (Response response = client.newCall(request).execute()) {
                String json = response.body() != null ? response.body().string() : "";

                if (!response.isSuccessful()) {
                    log.error("[Voice] STT 失败 HTTP {} body={}", response.code(),
                            json.length() > 200 ? json.substring(0, 200) : json);
                    return null;
                }

                // 解析响应，提取文字
                JsonObject result = JsonParser.parseString(json).getAsJsonObject();
                String text = result.get("text").getAsString();

                log.info("[Voice] STT 成功 text={}",
                        text.length() > 100 ? text.substring(0, 100) + "..." : text);
                return text.trim();
            }
        } catch (Exception e) {
            log.error("[Voice] STT 异常: {} | 类型: {} | 堆栈: {}", e.getMessage(), e.getClass().getName(), e);
            return null;
        }
    }

    // ==================== TTS（文字转语音）====================

    /**
     * 文字转语音（TTS）
     * 调用 SiliconFlow 的语音合成接口：POST /v1/audio/speech
     *
     * @param text  要转换的文字
     * @param voice 声音类型（可选，null 则使用默认声音）
     * @return 语音文件字节数据（MP3 格式），失败返回 null
     */
    public byte[] textToSpeech(String text, String voice) {
        if (!isConfigured()) {
            log.warn("[Voice] TTS 跳过：API Key 未配置");
            return null;
        }
        if (text == null || text.isBlank()) {
            log.warn("[Voice] TTS 跳过：文字为空");
            return null;
        }

        try {
            // 限制文字长度，避免超时
            if (text.length() > 2000) {
                text = text.substring(0, 2000);
                log.warn("[Voice] TTS 文字过长，已截断到 2000 字符");
            }

            // 构建请求体
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", ttsModel);
            requestBody.addProperty("input", text);
            requestBody.addProperty("voice", voice != null ? voice : defaultVoice);
            requestBody.addProperty("response_format", "mp3");
            requestBody.addProperty("speed", speed);
            requestBody.addProperty("gain", gain);

            RequestBody body = RequestBody.create(
                    requestBody.toString(), MediaType.parse("application/json"));

            Request request = new Request.Builder()
                    .url(trimTrailingSlash(apiUrl) + "/v1/audio/speech")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .post(body)
                    .build();

            log.info("[Voice] TTS 请求 model={} voice={} textLength={}",
                    ttsModel, voice != null ? voice : defaultVoice, text.length());

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errBody = response.body() != null ? response.body().string() : "";
                    log.error("[Voice] TTS 失败 HTTP {} body={}", response.code(),
                            errBody.length() > 500 ? errBody.substring(0, 500) : errBody);
                    return null;
                }

                byte[] audioBytes = response.body().bytes();
                log.info("[Voice] TTS 成功 size={} bytes", audioBytes.length);
                return audioBytes;
            }
        } catch (Exception e) {
            log.error("[Voice] TTS 异常: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 文字转语音（OPUS 格式，微信内部使用的音频编码）
     */
    public byte[] textToSpeechOpus(String text) {
        if (!isConfigured()) return null;
        if (text == null || text.isBlank()) return null;
        try {
            if (text.length() > 500) text = text.substring(0, 500);
            JsonObject body = new JsonObject();
            body.addProperty("model", ttsModel);
            body.addProperty("input", text);
            body.addProperty("voice", defaultVoice);
            body.addProperty("response_format", "opus");
            body.addProperty("sample_rate", 48000);
            body.addProperty("speed", speed);
            body.addProperty("gain", gain);
            Request request = new Request.Builder()
                    .url(trimTrailingSlash(apiUrl) + "/v1/audio/speech")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                    .build();
            log.info("[Voice] TTS-OPUS 请求 textLength={}", text.length());
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("[Voice] TTS-OPUS 失败 HTTP {} body={}",
                            response.code(), response.body() != null ? response.body().string() : "");
                    return null;
                }
                byte[] audio = response.body().bytes();
                log.info("[Voice] TTS-OPUS 成功 size={} bytes", audio.length);
                return audio;
            }
        } catch (Exception e) {
            log.error("[Voice] TTS-OPUS 异常: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 文字转语音（PCM 格式，专用于微信语音气泡）
     * 微信语音消息底层期望 PCM 原始音频数据
     */
    public byte[] textToSpeechPcm(String text) {
        if (!isConfigured()) {
            log.warn("[Voice] TTS-PCM 跳过：API Key 未配置");
            return null;
        }
        if (text == null || text.isBlank()) {
            log.warn("[Voice] TTS-PCM 跳过：文字为空");
            return null;
        }
        try {
            if (text.length() > 500) {
                text = text.substring(0, 500);
            }

            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", ttsModel);
            requestBody.addProperty("input", text);
            requestBody.addProperty("voice", defaultVoice);
            requestBody.addProperty("response_format", "pcm");
            requestBody.addProperty("sample_rate", 24000);
            requestBody.addProperty("speed", speed);
            requestBody.addProperty("gain", gain);

            RequestBody body = RequestBody.create(
                    requestBody.toString(), MediaType.parse("application/json"));

            Request request = new Request.Builder()
                    .url(trimTrailingSlash(apiUrl) + "/v1/audio/speech")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .post(body)
                    .build();

            log.info("[Voice] TTS-PCM 请求 textLength={}", text.length());

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errBody = response.body() != null ? response.body().string() : "";
                    log.error("[Voice] TTS-PCM 失败 HTTP {} body={}", response.code(),
                            errBody.length() > 500 ? errBody.substring(0, 500) : errBody);
                    return null;
                }
                byte[] audioBytes = response.body().bytes();
                log.info("[Voice] TTS-PCM 成功 size={} bytes", audioBytes.length);
                return audioBytes;
            }
        } catch (Exception e) {
            log.error("[Voice] TTS-PCM 异常: {}", e.getMessage(), e);
            return null;
        }
    }

    // ==================== 辅助方法 ====================

    /** 是否已配置 API Key */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** 根据音频格式返回 MIME 类型 */
    private String getMimeType(String format) {
        if (format == null)
            return "audio/mpeg";
        switch (format.toLowerCase(Locale.ROOT)) {
            case "mp3":
                return "audio/mpeg";
            case "wav":
                return "audio/wav";
            case "pcm":
                return "audio/pcm";
            case "silk":
                return "audio/silk";
            case "amr":
                return "audio/amr";
            case "ogg":
                return "audio/ogg";
            case "flac":
                return "audio/flac";
            default:
                return "audio/mpeg";
        }
    }

    /** 去掉 URL 末尾多余的斜杠 */
    private String trimTrailingSlash(String value) {
        String result = value == null || value.isBlank()
                ? "https://api.siliconflow.cn"
                : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result.toLowerCase(Locale.ROOT).startsWith("http")
                ? result
                : "https://" + result;
    }

    // ==================== 外部文件托管 ====================

    /**
     * 上传 MP3 到外部文件托管服务，返回公网可访问的下载链接
     * 支持两种模式：
     *   - PUT 模式（transfer.sh）：PUT /文件名，返回纯文本 URL
     *   - POST multipart 模式（tmpfiles.org）：POST /api/v1/upload，返回 JSON
     *
     * @param audioBytes MP3 字节数据
     * @param fileName   文件名
     * @return 下载链接，失败返回 null
     */
    public String uploadToFileHost(byte[] audioBytes, String fileName) {
        try {
            String host = trimTrailingSlash(fileHostUrl);

            Request request;
            if (host.contains("tmpfiles.org")) {
                // tmpfiles.org: POST multipart
                RequestBody multipartBody = new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("file", fileName,
                                RequestBody.create(audioBytes, MediaType.parse("audio/mpeg")))
                        .build();
                request = new Request.Builder()
                        .url(host + "/api/v1/upload")
                        .post(multipartBody)
                        .build();
            } else {
                // transfer.sh 等: PUT
                String encodedName = java.net.URLEncoder.encode(fileName, "UTF-8");
                RequestBody putBody = RequestBody.create(audioBytes,
                        MediaType.parse("audio/mpeg"));
                request = new Request.Builder()
                        .url(host + "/" + encodedName)
                        .put(putBody)
                        .header("Max-Days", "1")
                        .build();
            }

            log.info("[Voice] 上传到文件托管 host={} size={} bytes", host, audioBytes.length);

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errBody = response.body() != null
                            ? response.body().string()
                            : "";
                    log.error("[Voice] 文件托管上传失败 HTTP {} body={}",
                            response.code(),
                            errBody.length() > 300 ? errBody.substring(0, 300) : errBody);
                    return null;
                }
                String respText = response.body() != null
                        ? response.body().string().trim()
                        : null;
                log.info("[Voice] 文件托管上传成功 response={}",
                        respText != null && respText.length() > 200
                                ? respText.substring(0, 200) + "..."
                                : respText);

                // 解析不同服务的响应
                if (host.contains("tmpfiles.org")) {
                    // JSON: {"status":"success","data":{"url":"...","deleteUrl":"..."}}
                    JsonObject json = JsonParser.parseString(respText).getAsJsonObject();
                    String downloadUrl = json.getAsJsonObject("data")
                            .get("url").getAsString();
                    log.info("[Voice] 文件托管下载链接={}", downloadUrl);
                    return downloadUrl;
                }
                // transfer.sh 等：响应体直接就是 URL
                return respText;
            }
        } catch (Exception e) {
            log.error("[Voice] 文件托管上传异常: {}", e.getMessage(), e);
            return null;
        }
    }

    // ==================== 本地音频存储 ====================

    /** 存储 MP3 到本地内存，返回唯一 token */
    public String storeAudio(byte[] audioBytes) {
        String token = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        audioStore.put(token, audioBytes);
        // 超过上限则删除最旧的
        if (audioStore.size() > MAX_STORED_AUDIO) {
            String oldest = audioStore.keys().nextElement();
            audioStore.remove(oldest);
        }
        log.info("[Voice] 本地存储音频 token={} size={} bytes totalStored={}",
                token, audioBytes.length, audioStore.size());
        return token;
    }

    /** 根据 token 获取存储的音频 */
    public byte[] getAudio(String token) {
        return audioStore.get(token);
    }

    // ==================== 七牛云上传 ====================

    /**
     * 上传 MP3 到七牛云，使用官方 SDK 生成 token，返回公网下载链接（国内可访问）
     */
    public String uploadToQiniu(byte[] mp3Bytes) {
        String ak = qiniuAk != null ? qiniuAk.trim() : "";
        String sk = qiniuSk != null ? qiniuSk.trim() : "";
        String bucket = qiniuBucket.trim();
        if (ak.isBlank() || sk.isBlank()) return null;
        try {
            String fileKey = "voice/" + java.util.UUID.randomUUID().toString().replace("-", "")
                    + ".mp3";

            // 使用 Qiniu 官方 SDK 生成 token
            com.qiniu.util.Auth auth = com.qiniu.util.Auth.create(ak, sk);

            String token = auth.uploadToken(bucket, fileKey);

            okhttp3.RequestBody fileBody = okhttp3.RequestBody.create(mp3Bytes,
                    okhttp3.MediaType.parse("audio/mpeg"));
            okhttp3.MultipartBody multipart = new okhttp3.MultipartBody.Builder()
                    .setType(okhttp3.MultipartBody.FORM)
                    .addFormDataPart("token", token)
                    .addFormDataPart("key", fileKey)
                    .addFormDataPart("file", fileKey, fileBody)
                    .build();

            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url("https://up.qiniup.com")
                    .post(multipart)
                    .build();

            log.info("[七牛云] 上传 key={} size={}", fileKey, mp3Bytes.length);
            try (okhttp3.Response response = client.newCall(request).execute()) {
                String json = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    log.error("[七牛云] 上传失败 HTTP {} body={}", response.code(), json);
                    return null;
                }
                JsonObject resp = JsonParser.parseString(json).getAsJsonObject();
                String uploadedKey = resp.get("key").getAsString();
                String url = qiniuDomain + "/" + uploadedKey;
                log.info("[七牛云] ✅ 上传成功 url={}", url);
                return url;
            }
        } catch (Exception e) {
            log.error("[七牛云] 上传异常: {}", e.getMessage(), e);
            return null;
        }
    }
}
