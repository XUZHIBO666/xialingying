package com.demo.demo.Service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ImageGenerationService {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final Pattern COMMAND_IMAGE_REQUEST = Pattern.compile("^\\s*(?:/image|/draw)\\s*[：:,，]?\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PREFIX_IMAGE_REQUEST = Pattern.compile(
            "^\\s*(?:请|请帮我|帮我)?(?:生成|制作|画)(?:一张|一个|个|张)?\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);
    private static final int MAX_IMAGE_BYTES = 20 * 1024 * 1024;

    private final OkHttpClient httpClient;
    private final Gson gson = new Gson();
    private final String apiKey;
    private final String apiUrl;
    private final String model;
    private final String size;

    @Autowired
    public ImageGenerationService(
            @Value("${ai.image.api.key:}") String apiKey,
            @Value("${ai.image.api.url:https://api.openai.com}") String apiUrl,
            @Value("${ai.image.model:gpt-image-1}") String model,
            @Value("${ai.image.size:1024x1024}") String size) {
        this(apiKey, apiUrl, model, size, new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build());
    }

    ImageGenerationService(String apiKey, String apiUrl, String model, String size, OkHttpClient httpClient) {
        this.apiKey = apiKey;
        this.apiUrl = trimTrailingSlash(apiUrl);
        this.model = model;
        this.size = size;
        this.httpClient = httpClient;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public boolean isImageRequest(String text) {
        return extractPrompt(text) != null;
    }

    public String extractPrompt(String text) {
        if (text == null) return null;
        Matcher commandMatcher = COMMAND_IMAGE_REQUEST.matcher(text);
        if (commandMatcher.matches()) {
            return cleanPrompt(commandMatcher.group(1));
        }

        Matcher prefixMatcher = PREFIX_IMAGE_REQUEST.matcher(text);
        if (!prefixMatcher.matches()) return null;

        String prompt = prefixMatcher.group(1).trim();
        // 避免“生成一段文案”这类普通请求误触发，只把明确提到图片的句子当成生图请求。
        if (prompt.startsWith("图片") || prompt.endsWith("图片")) {
            return cleanPrompt(prompt);
        }
        return null;
    }

    private String cleanPrompt(String value) {
        String prompt = value == null ? "" : value.trim();
        prompt = prompt.replaceFirst("^[：:,，\\s]+", "");
        prompt = prompt.replaceFirst("^图片[：:,，\\s]*", "");
        prompt = prompt.replaceFirst("^(一张|一个|个|张)", "");
        prompt = prompt.replaceFirst("的?图片$", "");
        prompt = prompt.trim();
        return prompt.isEmpty() ? null : prompt;
    }

    public byte[] generateImage(String prompt) throws IOException {
        if (!isConfigured()) {
            throw new IOException("图片生成 API 未配置");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IOException("图片提示词不能为空");
        }

        Map<String, Object> requestBodyMap = new LinkedHashMap<>();
        requestBodyMap.put("model", model);
        requestBodyMap.put("prompt", prompt);
        requestBodyMap.put("size", size);
        // SiliconFlow 的生图接口使用 image_size/batch_size；保留 size 兼容 OpenAI 风格接口。
        if (apiUrl.contains("siliconflow.cn")) {
            requestBodyMap.put("image_size", size);
            requestBodyMap.put("batch_size", 1);
        }

        String bodyJson = gson.toJson(requestBodyMap);
        Request request = new Request.Builder()
                .url(apiUrl + "/v1/images/generations")
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(bodyJson, JSON))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("图片生成失败，HTTP " + response.code());
            }
            return readImageBytes(body);
        }
    }

    private byte[] readImageBytes(String body) throws IOException {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonArray data = root.getAsJsonArray("data");
        if (data == null || data.size() == 0 || !data.get(0).isJsonObject()) {
            throw new IOException("图片生成响应为空");
        }

        JsonObject first = data.get(0).getAsJsonObject();
        if (first.has("b64_json") && !first.get("b64_json").isJsonNull()) {
            byte[] bytes = Base64.getDecoder().decode(first.get("b64_json").getAsString());
            checkImageSize(bytes);
            return bytes;
        }
        if (first.has("url") && !first.get("url").isJsonNull()) {
            return downloadImage(first.get("url").getAsString());
        }
        throw new IOException("图片生成响应缺少 b64_json/url");
    }

    private byte[] downloadImage(String url) throws IOException {
        Request request = new Request.Builder().url(url).build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("下载图片失败，HTTP " + response.code());
            }
            ResponseBody body = response.body();
            byte[] bytes = body == null ? new byte[0] : body.bytes();
            checkImageSize(bytes);
            return bytes;
        }
    }

    private void checkImageSize(byte[] bytes) throws IOException {
        if (bytes.length == 0) {
            throw new IOException("图片内容为空");
        }
        if (bytes.length > MAX_IMAGE_BYTES) {
            throw new IOException("图片过大，超过 20MB");
        }
    }

    private String trimTrailingSlash(String value) {
        String result = value == null || value.isBlank() ? "https://api.openai.com" : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result.toLowerCase(Locale.ROOT).startsWith("http") ? result : "https://" + result;
    }
}
