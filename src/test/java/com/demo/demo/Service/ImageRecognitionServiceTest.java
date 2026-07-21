package com.demo.demo.Service;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ImageRecognitionServiceTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void recognizesImageWithVisionChatApi() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authHeader = new AtomicReference<>();
        startServer(exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            send(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"图片里是一只猫。\"}}]}");
        });

        ImageRecognitionService service = new ImageRecognitionService(
                "test-key",
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "vision-model",
                "描述图片",
                new OkHttpClient());

        String reply = service.recognize(jpegImage());

        assertEquals("图片里是一只猫。", reply);
        assertEquals("Bearer test-key", authHeader.get());
        assertTrue(requestBody.get().contains("\"model\":\"vision-model\""));
        assertTrue(requestBody.get().contains("\"type\":\"image_url\""));
        assertTrue(requestBody.get().contains("data:image/jpeg;base64"));
        assertTrue(requestBody.get().contains("\"text\":\"描述图片\""));
    }

    @Test
    void usesOcrPromptWhenVisionModeIsOcr() throws Exception {
        String prompt = recognizeAndReadPrompt("ocr");

        assertEquals("请准确识别图片中的所有文字，尽量保持原有顺序和换行；不要补充图片中不存在的内容。", prompt);
    }

    @Test
    void usesDetailedPromptWhenVisionModeIsDetailed() throws Exception {
        String prompt = recognizeAndReadPrompt("detailed");

        assertEquals("请用中文详细分析这张图片，包括场景、人物或物体、文字、颜色、位置关系和可能表达的信息。", prompt);
    }

    @Test
    void fallsBackToGeneralPromptWhenVisionModeIsUnknown() throws Exception {
        String prompt = recognizeAndReadPrompt("unknown");

        assertEquals("通用识图提示词", prompt);
    }

    @Test
    void resizesLandscapeJpegToMaximumLongSide() throws Exception {
        byte[] originalBytes = createImage("jpg", 2400, 1200, BufferedImage.TYPE_INT_RGB);

        UploadedImage uploaded = recognizeAndReadUploadedImage(originalBytes);

        assertEquals(1600, uploaded.image().getWidth());
        assertEquals(800, uploaded.image().getHeight());
        assertTrue(uploaded.bytes().length < originalBytes.length,
                "缩小后的 JPEG 应小于原始图片");
    }

    @Test
    void resizesPortraitJpegToMaximumLongSide() throws Exception {
        byte[] originalBytes = createImage("jpg", 1200, 2400, BufferedImage.TYPE_INT_RGB);

        UploadedImage uploaded = recognizeAndReadUploadedImage(originalBytes);

        assertEquals(800, uploaded.image().getWidth());
        assertEquals(1600, uploaded.image().getHeight());
    }

    @Test
    void correctsExifOrientationBeforeResizingLargeJpeg() throws Exception {
        byte[] portraitPixels = createImage("jpg", 1200, 2400, BufferedImage.TYPE_INT_RGB);
        byte[] orientedImage = withExifOrientation(portraitPixels, 6);

        UploadedImage uploaded = recognizeAndReadUploadedImage(orientedImage);

        assertEquals(1600, uploaded.image().getWidth());
        assertEquals(800, uploaded.image().getHeight());
    }

    @Test
    void correctsExifOrientationForImageWithinSizeLimit() throws Exception {
        byte[] portraitPixels = createImage("jpg", 600, 1000, BufferedImage.TYPE_INT_RGB);
        byte[] orientedImage = withExifOrientation(portraitPixels, 6);

        UploadedImage uploaded = recognizeAndReadUploadedImage(orientedImage);

        assertEquals(1000, uploaded.image().getWidth());
        assertEquals(600, uploaded.image().getHeight());
    }

    @Test
    void doesNotReencodeImageWithinSizeLimit() throws Exception {
        byte[] originalBytes = createImage("jpg", 800, 600, BufferedImage.TYPE_INT_RGB);

        UploadedImage uploaded = recognizeAndReadUploadedImage(originalBytes);

        assertArrayEquals(originalBytes, uploaded.bytes());
        assertEquals(800, uploaded.image().getWidth());
        assertEquals(600, uploaded.image().getHeight());
    }

    @Test
    void keepsTransparencyWhenResizingPng() throws Exception {
        byte[] originalBytes = createImage("png", 2000, 1000, BufferedImage.TYPE_INT_ARGB);

        UploadedImage uploaded = recognizeAndReadUploadedImage(originalBytes);

        assertEquals(1600, uploaded.image().getWidth());
        assertEquals(800, uploaded.image().getHeight());
        assertTrue(uploaded.image().getColorModel().hasAlpha(), "PNG 透明通道不应丢失");
    }

    @Test
    void usesPngMimeTypeForPngImage() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        startServer(exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            send(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"识别成功\"}}]}");
        });

        service().recognize(pngImage());

        assertTrue(requestBody.get().contains("data:image/png;base64"));
    }

    @Test
    void usesWebpMimeTypeForWebpImage() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        startServer(exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            send(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"识别成功\"}}]}");
        });

        service().recognize(webpImage());

        assertTrue(requestBody.get().contains("data:image/webp;base64"));
    }

    @Test
    void rejectsTruncatedWebpBeforeCallingProvider() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        startServer(exchange -> {
            attempts.incrementAndGet();
            send(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"不应该调用平台\"}}]}");
        });

        byte[] truncatedWebp = {
                'R', 'I', 'F', 'F',
                0, 0, 0, 0,
                'W', 'E', 'B', 'P'
        };

        IOException exception = assertThrows(IOException.class,
                () -> service().recognize(truncatedWebp));

        assertEquals("图片内容无法解析", exception.getMessage());
        assertEquals(0, attempts.get());
    }

    @Test
    void rejectsWebpWithoutImageChunkBeforeCallingProvider() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        startServer(exchange -> {
            attempts.incrementAndGet();
            send(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"不应该调用平台\"}}]}");
        });

        byte[] webpWithOnlyMetadata = {
                'R', 'I', 'F', 'F',
                12, 0, 0, 0,
                'W', 'E', 'B', 'P',
                'E', 'X', 'I', 'F',
                0, 0, 0, 0
        };

        IOException exception = assertThrows(IOException.class,
                () -> service().recognize(webpWithOnlyMetadata));

        assertEquals("图片内容无法解析", exception.getMessage());
        assertEquals(0, attempts.get());
    }

    @Test
    void rejectsUnknownImageFormatBeforeCallingProvider() throws Exception {
        startServer(exchange -> send(exchange, 200,
                "{\"choices\":[{\"message\":{\"content\":\"不应该调用平台\"}}]}"));

        IOException exception = assertThrows(IOException.class,
                () -> service().recognize("not-an-image".getBytes(StandardCharsets.UTF_8)));

        assertEquals("不支持的图片格式，仅支持 JPEG、PNG、WebP", exception.getMessage());
    }

    @Test
    void includesProviderErrorMessageWhenVisionApiFails() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        startServer(exchange -> {
            attempts.incrementAndGet();
            send(exchange, 403, "{\"code\":30003,\"message\":\"Model disabled.\",\"data\":null}");
        });

        ImageRecognitionService service = new ImageRecognitionService(
                "test-key",
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "disabled-model",
                "描述图片",
                new OkHttpClient());

        IOException exception = assertThrows(IOException.class,
                () -> service.recognize(jpegImage()));

        assertEquals("图片识别失败，HTTP 403：Model disabled.", exception.getMessage());
        assertEquals(1, attempts.get());
    }

    @Test
    void retriesVisionWhenProviderReturns429() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        startServer(exchange -> {
            if (attempts.incrementAndGet() == 1) {
                send(exchange, 429, "{\"message\":\"Rate limited.\"}");
                return;
            }
            send(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"重试成功\"}}]}");
        });

        String reply = service().recognize(jpegImage());

        assertEquals("重试成功", reply);
        assertEquals(2, attempts.get());
    }

    @Test
    void retriesVisionWhenProviderReturns503() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        startServer(exchange -> {
            if (attempts.incrementAndGet() == 1) {
                send(exchange, 503, "{\"message\":\"Service unavailable.\"}");
                return;
            }
            send(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"服务恢复\"}}]}");
        });

        String reply = service().recognize(jpegImage());

        assertEquals("服务恢复", reply);
        assertEquals(2, attempts.get());
    }

    @Test
    void retriesVisionWhenNetworkFails() throws Exception {
        startServer(exchange ->
                send(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"网络恢复\"}}]}"));
        AtomicInteger attempts = new AtomicInteger();
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw new IOException("network interrupted");
                    }
                    return chain.proceed(chain.request());
                })
                .build();
        ImageRecognitionService service = new ImageRecognitionService(
                "test-key",
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "vision-model",
                "描述图片",
                client);

        String reply = service.recognize(jpegImage());

        assertEquals("网络恢复", reply);
        assertEquals(2, attempts.get());
    }

    @Test
    void stopsVisionRetryAfterThreeAttempts() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        startServer(exchange -> {
            attempts.incrementAndGet();
            send(exchange, 503, "{\"message\":\"Service unavailable.\"}");
        });

        assertThrows(IOException.class, () -> service().recognize(jpegImage()));

        assertEquals(3, attempts.get());
    }

    private ImageRecognitionService service() {
        return new ImageRecognitionService(
                "test-key",
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "vision-model",
                "描述图片",
                new OkHttpClient());
    }

    private String recognizeAndReadPrompt(String mode) throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        startServer(exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            send(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"识别成功\"}}]}");
        });
        ImageRecognitionService service = new ImageRecognitionService(
                "test-key",
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "vision-model",
                "通用识图提示词",
                mode,
                new OkHttpClient());

        service.recognize(jpegImage());

        return JsonParser.parseString(requestBody.get()).getAsJsonObject()
                .getAsJsonArray("messages").get(0).getAsJsonObject()
                .getAsJsonArray("content").get(1).getAsJsonObject()
                .get("text").getAsString();
    }

    private UploadedImage recognizeAndReadUploadedImage(byte[] originalBytes) throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        startServer(exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            send(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"识别成功\"}}]}");
        });

        service().recognize(originalBytes);

        String dataUrl = JsonParser.parseString(requestBody.get()).getAsJsonObject()
                .getAsJsonArray("messages").get(0).getAsJsonObject()
                .getAsJsonArray("content").get(0).getAsJsonObject()
                .getAsJsonObject("image_url").get("url").getAsString();
        byte[] uploadedBytes = Base64.getDecoder().decode(dataUrl.substring(dataUrl.indexOf(',') + 1));
        BufferedImage uploadedImage = ImageIO.read(new ByteArrayInputStream(uploadedBytes));
        assertNotNull(uploadedImage, "上传内容应该可以解码为图片");
        return new UploadedImage(uploadedBytes, uploadedImage);
    }

    private byte[] jpegImage() throws IOException {
        return createImage("jpg", 2, 2, BufferedImage.TYPE_INT_RGB);
    }

    private byte[] pngImage() throws IOException {
        return createImage("png", 2, 2, BufferedImage.TYPE_INT_ARGB);
    }

    private byte[] createImage(String format, int width, int height, int imageType) throws IOException {
        BufferedImage image = new BufferedImage(width, height, imageType);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, format, output), "测试图片编码失败");
        return output.toByteArray();
    }

    private byte[] withExifOrientation(byte[] jpegBytes, int orientation) throws IOException {
        // 构造最小 EXIF APP1 段，只写入 Orientation 标签，避免测试依赖外部图片文件。
        byte[] exif = new byte[]{
                'E', 'x', 'i', 'f', 0, 0,
                'I', 'I', 0x2A, 0, 8, 0, 0, 0,
                1, 0,
                0x12, 0x01, 3, 0, 1, 0, 0, 0,
                (byte) orientation, 0, 0, 0,
                0, 0, 0, 0
        };
        int segmentLength = exif.length + 2;

        int insertOffset = 2;
        if ((jpegBytes[2] & 0xFF) == 0xFF && (jpegBytes[3] & 0xFF) == 0xE0) {
            int app0Length = ((jpegBytes[4] & 0xFF) << 8) | (jpegBytes[5] & 0xFF);
            insertOffset = 4 + app0Length;
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(jpegBytes, 0, insertOffset);
        output.write(0xFF);
        output.write(0xE1);
        output.write((segmentLength >>> 8) & 0xFF);
        output.write(segmentLength & 0xFF);
        output.write(exif);
        output.write(jpegBytes, insertOffset, jpegBytes.length - insertOffset);
        return output.toByteArray();
    }

    private byte[] webpImage() {
        return new byte[]{
                'R', 'I', 'F', 'F',
                18, 0, 0, 0,
                'W', 'E', 'B', 'P',
                'V', 'P', '8', 'L',
                5, 0, 0, 0,
                0x2F, 0, 0, 0, 0,
                0
        };
    }

    private void startServer(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler::handle);
        server.start();
    }

    private void send(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private record UploadedImage(byte[] bytes, BufferedImage image) {
    }
}
