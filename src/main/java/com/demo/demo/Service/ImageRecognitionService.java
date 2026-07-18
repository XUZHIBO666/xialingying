package com.demo.demo.Service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import net.coobird.thumbnailator.util.exif.ExifUtils;
import net.coobird.thumbnailator.util.exif.Orientation;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ImageRecognitionService {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final int MAX_IMAGE_BYTES = 20 * 1024 * 1024;
    private static final int MAX_IMAGE_LONG_SIDE = 1600;
    private static final double JPEG_OUTPUT_QUALITY = 0.85;
    private static final int MAX_HTTP_ATTEMPTS = 3;
    private static final long[] RETRY_DELAYS_MILLIS = {500L, 1000L};
    private static final String OCR_PROMPT =
            "请准确识别图片中的所有文字，尽量保持原有顺序和换行；不要补充图片中不存在的内容。";
    private static final String DETAILED_PROMPT =
            "请用中文详细分析这张图片，包括场景、人物或物体、文字、颜色、位置关系和可能表达的信息。";

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
            @Value("${ai.vision.prompt:请用中文简洁描述这张图片的主要内容，如果图片中有文字也请读出来。}") String prompt,
            @Value("${ai.vision.mode:general}") String mode) {
        this(apiKey, apiUrl, model, prompt, mode, new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build());
    }

    ImageRecognitionService(String apiKey, String apiUrl, String model, String prompt, OkHttpClient httpClient) {
        this(apiKey, apiUrl, model, prompt, "general", httpClient);
    }

    ImageRecognitionService(
            String apiKey,
            String apiUrl,
            String model,
            String prompt,
            String mode,
            OkHttpClient httpClient) {
        this.apiKey = apiKey;
        this.apiUrl = trimTrailingSlash(apiUrl);
        this.model = model;
        this.prompt = selectPrompt(mode, prompt);
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

        String mimeType = detectImageMimeType(imageBytes);
        byte[] requestImageBytes = preprocessImage(imageBytes, mimeType);

        // 视觉模型走 OpenAI 兼容的多模态 chat/completions，请求中直接内嵌 base64 图片。
        JsonObject imageUrl = new JsonObject();
        imageUrl.addProperty("url",
                "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(requestImageBytes));
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

        try (Response response = executeRecognitionRequest(request)) {
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

    private Response executeRecognitionRequest(Request request) throws IOException {
        for (int attempt = 1; attempt <= MAX_HTTP_ATTEMPTS; attempt++) {
            try {
                Response response = httpClient.newCall(request).execute();
                if (!isRetryableStatus(response.code()) || attempt == MAX_HTTP_ATTEMPTS) {
                    return response;
                }

                log.warn("[图片识别] HTTP {}，准备第 {} 次请求", response.code(), attempt + 1);
                response.close();
            } catch (IOException e) {
                if (attempt == MAX_HTTP_ATTEMPTS) {
                    throw e;
                }
                log.warn("[图片识别] 网络异常，准备第 {} 次请求: {}", attempt + 1, e.getMessage());
            }
            waitBeforeRetry(attempt);
        }
        throw new IOException("图片识别请求未完成");
    }

    private boolean isRetryableStatus(int statusCode) {
        return statusCode == 408
                || statusCode == 429
                || statusCode == 500
                || statusCode == 502
                || statusCode == 503
                || statusCode == 504;
    }

    private void waitBeforeRetry(int completedAttempt) throws IOException {
        try {
            Thread.sleep(RETRY_DELAYS_MILLIS[completedAttempt - 1]);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("等待图片识别重试时被中断", e);
        }
    }

    private byte[] preprocessImage(byte[] imageBytes, String mimeType) throws IOException {
        // ImageIO 不能可靠解码 WebP，因此上传前至少校验容器边界，避免把伪造文件头发送给平台。
        if ("image/webp".equals(mimeType)) {
            validateWebpStructure(imageBytes);
            return imageBytes;
        }

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
        if (image == null) {
            throw new IOException("图片内容无法解码");
        }
        boolean needsOrientationCorrection = "image/jpeg".equals(mimeType)
                && needsExifOrientationCorrection(imageBytes);
        boolean exceedsSizeLimit = image.getWidth() > MAX_IMAGE_LONG_SIDE
                || image.getHeight() > MAX_IMAGE_LONG_SIDE;
        if (!exceedsSizeLimit && !needsOrientationCorrection) {
            return imageBytes;
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        var thumbnail = Thumbnails.of(new ByteArrayInputStream(imageBytes));
        if (exceedsSizeLimit) {
            thumbnail.size(MAX_IMAGE_LONG_SIDE, MAX_IMAGE_LONG_SIDE)
                    .keepAspectRatio(true);
        } else {
            // 仅需纠正方向的小图保持原尺寸，避免 size(1600, 1600) 把它放大。
            thumbnail.scale(1.0);
        }
        thumbnail
                // 手机照片可能只在 EXIF 中记录显示方向，上传前需要把方向真正应用到像素。
                .useExifOrientation(true)
                .outputFormat("image/jpeg".equals(mimeType) ? "jpg" : "png");
        if ("image/jpeg".equals(mimeType)) {
            thumbnail.outputQuality(JPEG_OUTPUT_QUALITY);
        }
        thumbnail.toOutputStream(output);
        return output.toByteArray();
    }

    private void validateWebpStructure(byte[] imageBytes) throws IOException {
        long declaredFileSize = readUnsignedLittleEndianInt(imageBytes, 4);
        if (declaredFileSize != imageBytes.length - 8L) {
            throw new IOException("图片内容无法解析");
        }

        boolean hasImageChunk = false;
        int offset = 12;
        while (offset < imageBytes.length) {
            if (imageBytes.length - offset < 8) {
                throw new IOException("图片内容无法解析");
            }

            long chunkSize = readUnsignedLittleEndianInt(imageBytes, offset + 4);
            long nextOffset = offset + 8L + chunkSize + (chunkSize & 1L);
            if (nextOffset > imageBytes.length) {
                throw new IOException("图片内容无法解析");
            }

            if (isWebpImageChunk(imageBytes, offset)) {
                hasImageChunk = true;
            }
            offset = (int) nextOffset;
        }

        if (!hasImageChunk) {
            throw new IOException("图片内容无法解析");
        }
    }

    private boolean isWebpImageChunk(byte[] imageBytes, int offset) {
        return imageBytes[offset] == 'V'
                && imageBytes[offset + 1] == 'P'
                && imageBytes[offset + 2] == '8'
                && (imageBytes[offset + 3] == ' '
                || imageBytes[offset + 3] == 'L'
                || imageBytes[offset + 3] == 'X');
    }

    private long readUnsignedLittleEndianInt(byte[] bytes, int offset) {
        return ((long) bytes[offset] & 0xFF)
                | (((long) bytes[offset + 1] & 0xFF) << 8)
                | (((long) bytes[offset + 2] & 0xFF) << 16)
                | (((long) bytes[offset + 3] & 0xFF) << 24);
    }

    private boolean needsExifOrientationCorrection(byte[] imageBytes) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(imageBytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return false;
            }

            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, false);
                Orientation orientation = ExifUtils.getExifOrientation(reader, 0);
                return orientation != null && orientation != Orientation.TOP_LEFT;
            } finally {
                reader.dispose();
            }
        }
    }

    private String detectImageMimeType(byte[] imageBytes) throws IOException {
        // 微信下载结果没有可信扩展名，因此根据格式固定文件头判断，避免 MIME 类型与实际内容不一致。
        if (imageBytes.length >= 3
                && (imageBytes[0] & 0xFF) == 0xFF
                && (imageBytes[1] & 0xFF) == 0xD8
                && (imageBytes[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (imageBytes.length >= 8
                && (imageBytes[0] & 0xFF) == 0x89
                && imageBytes[1] == 0x50
                && imageBytes[2] == 0x4E
                && imageBytes[3] == 0x47
                && imageBytes[4] == 0x0D
                && imageBytes[5] == 0x0A
                && imageBytes[6] == 0x1A
                && imageBytes[7] == 0x0A) {
            return "image/png";
        }
        if (imageBytes.length >= 12
                && imageBytes[0] == 'R'
                && imageBytes[1] == 'I'
                && imageBytes[2] == 'F'
                && imageBytes[3] == 'F'
                && imageBytes[8] == 'W'
                && imageBytes[9] == 'E'
                && imageBytes[10] == 'B'
                && imageBytes[11] == 'P') {
            return "image/webp";
        }
        throw new IOException("不支持的图片格式，仅支持 JPEG、PNG、WebP");
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

    private String selectPrompt(String mode, String generalPrompt) {
        String normalizedMode = mode == null ? "" : mode.trim();
        if ("ocr".equalsIgnoreCase(normalizedMode)) {
            return OCR_PROMPT;
        }
        if ("detailed".equalsIgnoreCase(normalizedMode)) {
            return DETAILED_PROMPT;
        }
        // 配置拼写错误时继续使用通用模式，避免应用能启动但所有图片都无法识别。
        return generalPrompt;
    }

    private String trimTrailingSlash(String value) {
        String result = value == null || value.isBlank() ? "https://api.siliconflow.cn" : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result.startsWith("http") ? result : "https://" + result;
    }
}
