package com.demo.demo.Service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ImageRecognitionService {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final int MAX_IMAGE_BYTES = 20 * 1024 * 1024;

    private final OkHttpClient httpClient;
    private final String apiKey;
    private final String apiUrl;
    private final String model;
    private final String prompt;

    @Autowired
    public ImageRecognitionService(
            @Value("${ai.vision.api.key:}") String apiKey,
            @Value("${ai.vision.api.url:https://api.siliconflow.cn}") String apiUrl,
            @Value("${ai.vision.model:Qwen/Qwen3-VL-8B-Instruct}") String model,
            @Value("${ai.vision.prompt:请用中文简洁描述这张图片的主要内容，如果图片中有文字也请读出来。}") String prompt) {
        this(apiKey, apiUrl, model, prompt, new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build());
    }

    ImageRecognitionService(String apiKey, String apiUrl, String model, String prompt, OkHttpClient httpClient) {
        this.apiKey = apiKey;
        this.apiUrl = trimTrailingSlash(apiUrl);
        this.model = model;
        this.prompt = prompt;
        this.httpClient = httpClient;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String recognize(byte[] imageBytes) throws IOException {
        if (!isConfigured()) {
            throw new IOException("图片识别 API 未配置");
        }
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IOException("图片内容为空");
        }
        if (imageBytes.length > MAX_IMAGE_BYTES) {
            throw new IOException("图片过大，超过 20MB");
        }

        // 视觉模型走 OpenAI 兼容的多模态 chat/completions，请求中直接内嵌 base64 图片。
        JsonObject imageUrl = new JsonObject();
        imageUrl.addProperty("url", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(imageBytes));
        imageUrl.addProperty("detail", "low");

        JsonObject imagePart = new JsonObject();
        imagePart.addProperty("type", "image_url");
        imagePart.add("image_url", imageUrl);

        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", prompt);

        JsonArray content = new JsonArray();
        content.add(imagePart);
        content.add(textPart);

        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.add("content", content);

        JsonArray messages = new JsonArray();
        messages.add(user);

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", messages);
        body.addProperty("max_tokens", 500);

        Request request = new Request.Builder()
                .url(apiUrl + "/v1/chat/completions")
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String json = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                log.error("[图片识别] API 调用失败 HTTP {} body={}", response.code(),
                        json.length() > 300 ? json.substring(0, 300) : json);
                throw new IOException("图片识别失败，HTTP " + response.code() + "：" + extractErrorMessage(json));
            }
            String reply = JsonParser.parseString(json).getAsJsonObject()
                    .getAsJsonArray("choices")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("message")
                    .get("content").getAsString();
            return reply == null ? "" : reply.trim();
        }
    }

    private String extractErrorMessage(String json) {
        if (json == null || json.isBlank()) {
            return "平台未返回错误详情";
        }
        try {
            // 兼容 SiliconFlow 顶层 message，以及 OpenAI 风格 error.message。
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (root.has("message") && !root.get("message").isJsonNull()) {
                return root.get("message").getAsString();
            }
            if (root.has("error") && root.get("error").isJsonObject()) {
                JsonObject error = root.getAsJsonObject("error");
                if (error.has("message") && !error.get("message").isJsonNull()) {
                    return error.get("message").getAsString();
                }
            }
        } catch (Exception ignored) {
            return json.length() > 120 ? json.substring(0, 120) : json;
        }
        return json.length() > 120 ? json.substring(0, 120) : json;
    }

    private String trimTrailingSlash(String value) {
        String result = value == null || value.isBlank() ? "https://api.siliconflow.cn" : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result.startsWith("http") ? result : "https://" + result;
    }
}
