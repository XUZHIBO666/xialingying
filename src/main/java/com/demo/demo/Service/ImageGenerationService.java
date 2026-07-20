package com.demo.demo.Service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
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
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class ImageGenerationService {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final Pattern COMMAND_IMAGE_REQUEST = Pattern.compile("^\\s*(?:/image|/draw)\\s*[：:,，]?\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PREFIX_IMAGE_REQUEST = Pattern.compile(
            "^\\s*(?:请|请帮我|帮我)?(?:生成|制作|画)(?:一张|一个|个|张)?\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);
    private static final int MAX_IMAGE_BYTES = 20 * 1024 * 1024;
    private static final int MAX_HTTP_ATTEMPTS = 3;
    private static final long[] RETRY_DELAYS_MILLIS = {500L, 1000L};

    private final OkHttpClient httpClient;
    private final OkHttpClient downloadHttpClient;
    private final Gson gson = new Gson();
    private final String apiKey;
    private final String apiUrl;
    private final String model;
    private final String size;

    @Autowired
    public ImageGenerationService(
            @Value("${ai.image.api.key:}") String apiKey,
            @Value("${ai.image.api.url:https://api.siliconflow.cn}") String apiUrl,
            @Value("${ai.image.model:Kwai-Kolors/Kolors}") String model,
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
        this.downloadHttpClient = httpClient.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
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

        try (Response response = executeGenerationRequest(request)) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                String errMsg = extractErrorMessage(body);
                log.error("[图片生成] API 失败 httpStatus={} bodyPreview={}",
                        response.code(),
                        body == null ? "null" : body.substring(0, Math.min(200, body.length())));
                throw new IOException("图片生成失败，HTTP " + response.code()
                        + (errMsg.isEmpty() ? "" : "：" + errMsg));
            }
            return readImageBytes(body);
        } catch (java.net.ConnectException e) {
            throw new IOException("无法连接图片生成服务（" + apiUrl + "），请检查 IMAGE_API_URL 配置和网络");
        } catch (java.net.SocketTimeoutException e) {
            throw new IOException("图片生成服务响应超时，请稍后重试");
        } catch (IOException e) {
            // 重新抛出（不包装已包装过的异常）
            throw e;
        }
    }

    private byte[] readImageBytes(String body) throws IOException {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonArray images = root.getAsJsonArray("data");
        // SiliconFlow 返回 images，其他 OpenAI 兼容平台通常返回 data。
        if (images == null || images.isEmpty()) {
            images = root.getAsJsonArray("images");
        }
        if (images == null || images.size() == 0 || !images.get(0).isJsonObject()) {
            log.error("[图片生成] 响应格式异常 bodyPreview={}",
                    body.substring(0, Math.min(300, body.length())));
            throw new IOException("图片生成响应为空");
        }

        JsonObject first = images.get(0).getAsJsonObject();

        // 优先 b64_json（不需要额外下载，且通常质量更高）
        if (first.has("b64_json") && !first.get("b64_json").isJsonNull()) {
            byte[] bytes = Base64.getDecoder().decode(first.get("b64_json").getAsString());
            checkImageSize(bytes);
            return bytes;
        }

        // url 字段——下载前放宽校验（SiliconFlow CDN 可能使用 HTTP）
        if (first.has("url") && !first.get("url").isJsonNull()) {
            String imageUrl = first.get("url").getAsString();
            if (isSiliconFlowUrl(imageUrl)) {
                return downloadFromTrustedUrl(imageUrl);
            }
            return downloadImage(imageUrl);
        }

        log.error("[图片生成] 响应缺少 b64_json/url bodyPreview={}",
                body.substring(0, Math.min(300, body.length())));
        throw new IOException("图片生成响应缺少 b64_json/url");
    }

    private boolean isSiliconFlowUrl(String url) {
        return url != null && (url.contains("siliconflow") || url.contains("sf-maas"));
    }

    /** 从可信来源下载图片（放宽 SSRF 校验，允许 HTTP）。 */
    private byte[] downloadFromTrustedUrl(String url) throws IOException {
        Request request = new Request.Builder().url(url).build();
        try (Response response = executeDownloadRequest(request)) {
            if (!response.isSuccessful()) {
                throw new IOException("下载图片失败，HTTP " + response.code());
            }
            ResponseBody respBody = response.body();
            byte[] bytes = respBody == null ? new byte[0] : respBody.bytes();
            checkImageSize(bytes);
            return bytes;
        }
    }

    private byte[] downloadImage(String url) throws IOException {
        validateDownloadUrl(url);
        Request request = new Request.Builder().url(url).build();
        try (Response response = executeDownloadRequest(request)) {
            if (!response.isSuccessful()) {
                throw new IOException("下载图片失败，HTTP " + response.code());
            }
            ResponseBody body = response.body();
            byte[] bytes = body == null ? new byte[0] : body.bytes();
            checkImageSize(bytes);
            return bytes;
        }
    }

    private Response executeGenerationRequest(Request request) throws IOException {
        for (int attempt = 1; attempt <= MAX_HTTP_ATTEMPTS; attempt++) {
            // 生图 POST 的网络异常可能表示平台已经开始计费，保守策略下不自动重试 IOException。
            Response response = httpClient.newCall(request).execute();
            if (!isRetryableGenerationStatus(response.code()) || attempt == MAX_HTTP_ATTEMPTS) {
                return response;
            }

            log.warn("[图片生成] HTTP {}，准备第 {} 次请求", response.code(), attempt + 1);
            response.close();
            waitBeforeRetry(attempt);
        }
        throw new IOException("图片生成请求未完成");
    }

    private Response executeDownloadRequest(Request request) throws IOException {
        for (int attempt = 1; attempt <= MAX_HTTP_ATTEMPTS; attempt++) {
            try {
                Response response = downloadHttpClient.newCall(request).execute();
                if (!isRetryableDownloadStatus(response.code()) || attempt == MAX_HTTP_ATTEMPTS) {
                    return response;
                }

                log.warn("[图片下载] HTTP {}，准备第 {} 次请求", response.code(), attempt + 1);
                response.close();
            } catch (IOException e) {
                if (attempt == MAX_HTTP_ATTEMPTS) {
                    throw e;
                }
                log.warn("[图片下载] 网络异常，准备第 {} 次请求: {}", attempt + 1, e.getMessage());
            }
            waitBeforeRetry(attempt);
        }
        throw new IOException("图片下载请求未完成");
    }

    private void validateDownloadUrl(String url) throws IOException {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IOException("图片下载 URL 不合法", e);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("图片下载 URL 必须使用 HTTPS");
        }
        if (uri.getUserInfo() != null) {
            throw new IOException("图片下载 URL 不安全");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IOException("图片下载 URL 缺少主机");
        }
        for (InetAddress address : InetAddress.getAllByName(host)) {
            if (isUnsafeAddress(address)) {
                throw new IOException("图片下载 URL 不安全");
            }
        }
    }

    private boolean isUnsafeAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }

        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address && bytes.length == 4) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            return first == 0
                    || first == 10
                    || first == 127
                    || (first == 100 && second >= 64 && second <= 127)
                    || (first == 169 && second == 254)
                    || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && second == 168);
        }
        if (address instanceof Inet6Address && bytes.length == 16) {
            int first = bytes[0] & 0xff;
            return (first & 0xfe) == 0xfc;
        }
        return false;
    }

    private boolean isRetryableGenerationStatus(int statusCode) {
        return statusCode == 429
                || statusCode == 500
                || statusCode == 502
                || statusCode == 503
                || statusCode == 504;
    }

    private boolean isRetryableDownloadStatus(int statusCode) {
        return statusCode == 408 || isRetryableGenerationStatus(statusCode);
    }

    private void waitBeforeRetry(int completedAttempt) throws IOException {
        try {
            Thread.sleep(RETRY_DELAYS_MILLIS[completedAttempt - 1]);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("等待重试时被中断", e);
        }
    }

    private String extractErrorMessage(String json) {
        if (json == null || json.isBlank()) {
            return "平台未返回错误详情";
        }
        try {
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
