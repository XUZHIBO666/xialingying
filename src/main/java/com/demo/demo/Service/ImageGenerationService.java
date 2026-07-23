package com.demo.demo.Service;

import com.alibaba.cloud.ai.dashscope.api.DashScopeImageApi;
import com.alibaba.cloud.ai.dashscope.image.DashScopeImageModel;
import com.alibaba.cloud.ai.dashscope.image.DashScopeImageOptions;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.image.Image;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URL;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// ==================== 以下为旧版 OkHttp + Gson 的 import，已废弃，注释保留 ====================
// import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
// import com.google.gson.Gson;
// import com.google.gson.JsonArray;
// import com.google.gson.JsonObject;
// import com.google.gson.JsonParser;
// import okhttp3.MediaType;
// import okhttp3.OkHttpClient;
// import okhttp3.Request;
// import okhttp3.RequestBody;
// import okhttp3.Response;
// import okhttp3.ResponseBody;
// import org.springframework.beans.factory.annotation.Autowired;
// import java.net.*;
// import java.util.LinkedHashMap;
// import java.util.Locale;
// import java.util.Map;
// import java.util.concurrent.TimeUnit;

/**
 * 图片生成服务 —— 基于 Spring AI Alibaba DashScopeImageModel。
 *
 * <p>旧版手写 OkHttp + Gson 调 SiliconFlow，现已替换为百炼 DashScope 文生图。
 */
@Slf4j
@Service
public class ImageGenerationService {

    // ==================== 正则匹配（纯文本解析，和 API 无关，保留） ====================

    /** 匹配 "/image 提示词" 或 "/draw 提示词" 指令 */
    private static final Pattern COMMAND_IMAGE_REQUEST = Pattern.compile(
            "^\\s*(?:/image|/draw)\\s*[：:,，]?\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);

    /** 匹配 "生成/制作/画 一张..." 自然语言请求 */
    private static final Pattern PREFIX_IMAGE_REQUEST = Pattern.compile(
            "^\\s*(?:请|请帮我|帮我)?(?:生成|制作|画)(?:一张|一个|个|张)?\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);

    // ==================== 旧版常量，已废弃 ====================
    // private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    // private static final int MAX_IMAGE_BYTES = 20 * 1024 * 1024;
    // private static final int MAX_HTTP_ATTEMPTS = 3;
    // private static final long[] RETRY_DELAYS_MILLIS = {500L, 1000L};

    // ==================== 新版 DashScope 组件 ====================

    /** DashScope 图片生成模型（百炼文生图） */
    private DashScopeImageModel imageModel;

    /** 最近一次生成的图片（ThreadLocal，供 ImageGenerationTool 取用） */
    private final ThreadLocal<byte[]> lastGeneratedImage = new ThreadLocal<>();

    /** 从 application.yml 注入：百炼 API Key（和对话共用 spring.ai.dashscope.api-key） */
    @Value("${spring.ai.dashscope.api-key:}")
    private String apiKey;

    /** 图片生成模型名称，默认 wanx2.0-t2i-turbo（通义万相2.0） */
    @Value("${ai.image.model:wanx2.0-t2i-turbo}")
    private String model;

    /** 生成图片尺寸，默认 1024x1024 */
    @Value("${ai.image.size:1024x1024}")
    private String size;

    // ==================== 初始化 ====================

    /**
     * 在 @Value 注入完成后构建 DashScopeImageModel。
     * 不能用构造函数，因为 @Value 在构造之后才注入。
     */
    @PostConstruct
    public void init() {
        // 1. 创建 DashScope 图片 API 客户端
        DashScopeImageApi imageApi = DashScopeImageApi.builder()
                .apiKey(apiKey)
                .build();

        // 2. 配置图片生成参数：wanx 模型用 width/height，不支持 "1024x1024" 字符串格式
        DashScopeImageOptions options = DashScopeImageOptions.builder()
                .withModel(model)
                .withWidth(1024)
                .withHeight(1024)
                .withN(1)   // 每次生成 1 张图片
                .build();

        // 3. 组装完整的图片生成模型
        this.imageModel = DashScopeImageModel.builder()
                .dashScopeApi(imageApi)
                .defaultOptions(options)
                .build();

        log.info("[图片生成] 初始化完成 model={} size={}", model, size);
    }

    // ==================== 状态查询 ====================

    /** 是否已配置 API Key */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** 判断用户消息是否包含生图意图 */
    public boolean isImageRequest(String text) {
        return extractPrompt(text) != null;
    }

    // ==================== 提示词提取（纯文本逻辑，和 API 无关） ====================

    /**
     * 从用户消息中提取图片生成提示词。
     * 支持两种格式：
     *   1. 指令式：/image 一只小猫  或  /draw 一只小猫
     *   2. 自然语言：生成一张小猫的图片
     *
     * @return 提取到的提示词，未命中则返回 null
     */
    public String extractPrompt(String text) {
        if (text == null) return null;

        // 尝试匹配指令式
        Matcher commandMatcher = COMMAND_IMAGE_REQUEST.matcher(text);
        if (commandMatcher.matches()) {
            return cleanPrompt(commandMatcher.group(1));
        }

        // 尝试匹配自然语言式
        Matcher prefixMatcher = PREFIX_IMAGE_REQUEST.matcher(text);
        if (!prefixMatcher.matches()) return null;

        String prompt = prefixMatcher.group(1).trim();
        // 避免 "生成一段文案" 这类普通请求误触发，只把明确提到"图片"的当成生图请求
        if (prompt.startsWith("图片") || prompt.endsWith("图片")) {
            return cleanPrompt(prompt);
        }
        return null;
    }

    /** 清理提示词：去掉前缀修饰词、后缀"图片"等 */
    private String cleanPrompt(String value) {
        String prompt = value == null ? "" : value.trim();
        prompt = prompt.replaceFirst("^[：:,，\\s]+", "");
        prompt = prompt.replaceFirst("^图片[：:,，\\s]*", "");
        prompt = prompt.replaceFirst("^(一张|一个|个|张)", "");
        prompt = prompt.replaceFirst("的?图片$", "");
        prompt = prompt.trim();
        return prompt.isEmpty() ? null : prompt;
    }

    // ==================== 图片生成（核心方法） ====================

    /**
     * 调用百炼 DashScope 文生图 API 生成图片。
     * 优先使用 base64 直接返回，其次下载 URL。
     *
     * @param prompt 图片提示词（中文或英文）
     * @return 图片字节数组
     * @throws IOException API 调用失败或返回为空时抛出
     */
    public byte[] generateImage(String prompt) throws IOException {
        // 前置校验
        if (!isConfigured()) {
            throw new IOException("图片生成 API 未配置");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IOException("图片提示词不能为空");
        }

        try {
            // 调用 DashScope 文生图 API
            ImageResponse response = imageModel.call(new ImagePrompt(prompt));
            Image image = response.getResult().getOutput();

            // 优先取 base64（不需要二次下载，速度更快）
            if (image.getB64Json() != null) {
                byte[] bytes = Base64.getDecoder().decode(image.getB64Json());
                lastGeneratedImage.set(bytes);
                log.info("[图片生成] 成功（base64） size={}KB", bytes.length / 1024);
                return bytes;
            }

            // 没有 base64 则从 URL 下载
            if (image.getUrl() != null) {
                byte[] bytes = new URL(image.getUrl()).openStream().readAllBytes();
                lastGeneratedImage.set(bytes);
                log.info("[图片生成] 成功（URL下载） size={}KB", bytes.length / 1024);
                return bytes;
            }

            throw new IOException("图片生成返回为空");
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            log.error("[图片生成] 失败: {}", e.getMessage(), e);
            throw new IOException("图片生成失败: " + e.getMessage());
        }
    }

    // ==================== 线程安全取图 ====================

    /** 取出当前线程最近一次生成的图片，取后清空（供 ImageGenerationTool 调用） */
    public byte[] takeLastImage() {
        byte[] img = lastGeneratedImage.get();
        lastGeneratedImage.remove();
        return img;
    }

    // ==================== 以下为旧版 OkHttp + Gson 实现，已废弃，仅注释保留用于参考 ====================

    // /**
    //  * 旧版：从 JSON 响应中解析图片数据（b64_json 或 url）
    //  */
    // private byte[] readImageBytes(String body) throws IOException {
    //     JsonObject root = JsonParser.parseString(body).getAsJsonObject();
    //     JsonArray images = root.getAsJsonArray("data");
    //     if (images == null || images.isEmpty()) {
    //         images = root.getAsJsonArray("images");
    //     }
    //     if (images == null || images.size() == 0 || !images.get(0).isJsonObject()) {
    //         throw new IOException("图片生成响应为空");
    //     }
    //     JsonObject first = images.get(0).getAsJsonObject();
    //     if (first.has("b64_json") && !first.get("b64_json").isJsonNull()) {
    //         byte[] bytes = Base64.getDecoder().decode(first.get("b64_json").getAsString());
    //         checkImageSize(bytes);
    //         return bytes;
    //     }
    //     if (first.has("url") && !first.get("url").isJsonNull()) {
    //         return downloadImage(first.get("url").getAsString());
    //     }
    //     throw new IOException("图片生成响应缺少 b64_json/url");
    // }

    // /**
    //  * 旧版：从 URL 下载图片（含 SSRF 校验）
    //  */
    // private byte[] downloadImage(String url) throws IOException {
    //     validateDownloadUrl(url);
    //     Request request = new Request.Builder().url(url).build();
    //     try (Response response = executeDownloadRequest(request)) {
    //         if (!response.isSuccessful()) {
    //             throw new IOException("下载图片失败，HTTP " + response.code());
    //         }
    //         ResponseBody body = response.body();
    //         byte[] bytes = body == null ? new byte[0] : body.bytes();
    //         checkImageSize(bytes);
    //         return bytes;
    //     }
    // }

    // /**
    //  * 旧版：执行生成请求（含重试逻辑）
    //  */
    // private Response executeGenerationRequest(Request request) throws IOException {
    //     for (int attempt = 1; attempt <= MAX_HTTP_ATTEMPTS; attempt++) {
    //         Response response = httpClient.newCall(request).execute();
    //         if (!isRetryableGenerationStatus(response.code()) || attempt == MAX_HTTP_ATTEMPTS) {
    //             return response;
    //         }
    //         response.close();
    //         waitBeforeRetry(attempt);
    //     }
    //     throw new IOException("图片生成请求未完成");
    // }

    // /**
    //  * 旧版：执行下载请求（含重试逻辑）
    //  */
    // private Response executeDownloadRequest(Request request) throws IOException {
    //     for (int attempt = 1; attempt <= MAX_HTTP_ATTEMPTS; attempt++) {
    //         try {
    //             Response response = downloadHttpClient.newCall(request).execute();
    //             if (!isRetryableDownloadStatus(response.code()) || attempt == MAX_HTTP_ATTEMPTS) {
    //                 return response;
    //             }
    //             response.close();
    //         } catch (IOException e) {
    //             if (attempt == MAX_HTTP_ATTEMPTS) throw e;
    //         }
    //         waitBeforeRetry(attempt);
    //     }
    //     throw new IOException("图片下载请求未完成");
    // }

    // /**
    //  * 旧版：SSRF 防护 — 校验下载 URL 安全性
    //  */
    // private void validateDownloadUrl(String url) throws IOException { ... }
    // private boolean isUnsafeAddress(InetAddress address) { ... }
    // private boolean isRetryableGenerationStatus(int statusCode) { ... }
    // private boolean isRetryableDownloadStatus(int statusCode) { ... }
    // private void waitBeforeRetry(int completedAttempt) throws IOException { ... }
    // private void checkImageSize(byte[] bytes) throws IOException { ... }
    // private String trimTrailingSlash(String value) { ... }

    // /**
    //  * 旧版：带 OkHttp 客户端的构造函数
    //  */
    // ImageGenerationService(String apiKey, String apiUrl, String model, String size, OkHttpClient httpClient) {
    //     this.apiKey = apiKey;
    //     this.apiUrl = trimTrailingSlash(apiUrl);
    //     this.model = model;
    //     this.size = size;
    //     this.httpClient = httpClient;
    //     this.downloadHttpClient = httpClient.newBuilder()
    //             .followRedirects(false)
    //             .followSslRedirects(false)
    //             .build();
    // }
}
