package com.demo.demo.Service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ImageGenerationServiceTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void detectsAndExtractsImagePrompt() {
        ImageGenerationService service = new ImageGenerationService(
                "key", "http://localhost", "test-image", "256x256", new OkHttpClient());

        assertTrue(service.isImageRequest("生成图片：赛博朋克城市夜景"));
        assertTrue(service.isImageRequest("生成一张穿宇航服的猫的图片"));
        assertTrue(service.isImageRequest("帮我画个海边日落图片"));
        assertTrue(service.isImageRequest("/draw a small robot"));
        assertFalse(service.isImageRequest("今天天气怎么样"));
        assertEquals("赛博朋克城市夜景", service.extractPrompt("生成图片：赛博朋克城市夜景"));
        assertEquals("穿宇航服的猫", service.extractPrompt("生成一张穿宇航服的猫的图片"));
        assertEquals("海边日落", service.extractPrompt("帮我画个海边日落图片"));
        assertNull(service.extractPrompt("生成图片"));
    }

    @Test
    void generatesImageFromBase64Response() throws Exception {
        byte[] expected = "fake-png".getBytes(StandardCharsets.UTF_8);
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authHeader = new AtomicReference<>();
        startServer(exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String json = "{\"data\":[{\"b64_json\":\""
                    + Base64.getEncoder().encodeToString(expected) + "\"}]}";
            send(exchange, 200, json);
        });

        ImageGenerationService service = service();
        byte[] actual = service.generateImage("一只绿色机器人");

        assertArrayEquals(expected, actual);
        assertEquals("Bearer test-key", authHeader.get());
        assertTrue(requestBody.get().contains("\"prompt\":\"一只绿色机器人\""));
        assertTrue(requestBody.get().contains("\"model\":\"test-image\""));
        assertTrue(requestBody.get().contains("\"size\":\"256x256\""));
    }

    @Test
    void generatesImageFromUrlResponse() throws Exception {
        byte[] expected = "downloaded-image".getBytes(StandardCharsets.UTF_8);
        startServer(exchange -> {
            if ("/v1/images/generations".equals(exchange.getRequestURI().getPath())) {
                String url = "https://93.184.216.34/downloaded-image";
                send(exchange, 200, "{\"data\":[{\"url\":\"" + url + "\"}]}");
                return;
            }
            send(exchange, 200, expected);
        });

        byte[] actual = service().generateImage("山谷里的小屋");

        assertArrayEquals(expected, actual);
    }

    @Test
    void generatesImageFromSiliconFlowImagesResponse() throws Exception {
        byte[] expected = "siliconflow-image".getBytes(StandardCharsets.UTF_8);
        startServer(exchange -> {
            if ("/v1/images/generations".equals(exchange.getRequestURI().getPath())) {
                String url = "https://93.184.216.34/siliconflow-image";
                send(exchange, 200, "{\"images\":[{\"url\":\"" + url + "\"}]}");
                return;
            }
            send(exchange, 200, expected);
        });

        byte[] actual = service().generateImage("一只在月球上的橘猫");

        assertArrayEquals(expected, actual);
    }

    @Test
    void rejectsGeneratedImageUrlThatUsesHttp() throws Exception {
        startServer(exchange ->
                send(exchange, 200, "{\"data\":[{\"url\":\"http://93.184.216.34/generated.png\"}]}"));

        IOException exception = assertThrows(IOException.class,
                () -> service().generateImage("unsafe-http-url"));

        assertTrue(exception.getMessage().contains("HTTPS"));
    }

    @Test
    void rejectsGeneratedImageUrlThatTargetsLocalhost() throws Exception {
        startServer(exchange ->
                send(exchange, 200, "{\"data\":[{\"url\":\"https://127.0.0.1/generated.png\"}]}"));

        IOException exception = assertThrows(IOException.class,
                () -> service().generateImage("unsafe-local-url"));

        assertTrue(exception.getMessage().contains("不安全"));
    }

    @Test
    void retriesGenerationWhenProviderReturns503() throws Exception {
        byte[] expected = "image-after-retry".getBytes(StandardCharsets.UTF_8);
        AtomicInteger attempts = new AtomicInteger();
        startServer(exchange -> {
            if (attempts.incrementAndGet() == 1) {
                send(exchange, 503, "{\"error\":{\"message\":\"Service unavailable\"}}");
                return;
            }
            String json = "{\"data\":[{\"b64_json\":\""
                    + Base64.getEncoder().encodeToString(expected) + "\"}]}";
            send(exchange, 200, json);
        });

        byte[] actual = service().generateImage("重试生成");

        assertArrayEquals(expected, actual);
        assertEquals(2, attempts.get());
    }

    @Test
    void stopsGenerationRetryAfterThreeAttempts() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        startServer(exchange -> {
            attempts.incrementAndGet();
            send(exchange, 503, "{\"error\":{\"message\":\"Service unavailable\"}}");
        });

        assertThrows(IOException.class, () -> service().generateImage("持续故障"));

        assertEquals(3, attempts.get());
    }

    @Test
    void doesNotRetryGenerationWhenProviderReturns403() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        startServer(exchange -> {
            attempts.incrementAndGet();
            send(exchange, 403, "{\"error\":{\"message\":\"Model disabled.\"}}");
        });

        assertThrows(IOException.class, () -> service().generateImage("无权限模型"));

        assertEquals(1, attempts.get());
    }

    @Test
    void generationFailureDoesNotLeakProviderBodyToCaller() throws Exception {
        startServer(exchange ->
                send(exchange, 403, "{\"error\":{\"message\":\"Model disabled.\"}}"));

        IOException exception = assertThrows(IOException.class,
                () -> service().generateImage("无权限模型"));

        assertEquals("图片生成服务暂时不可用，HTTP 403", exception.getMessage());
    }

    @Test
    void doesNotRetryGenerationPostWhenNetworkFails() {
        AtomicInteger attempts = new AtomicInteger();
        OkHttpClient failingClient = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    attempts.incrementAndGet();
                    throw new IOException("network interrupted");
                })
                .build();
        ImageGenerationService service = new ImageGenerationService(
                "test-key", "http://127.0.0.1", "test-image", "256x256", failingClient);

        assertThrows(IOException.class, () -> service.generateImage("网络异常"));

        assertEquals(1, attempts.get());
    }

    @Test
    void retriesGeneratedImageDownloadWhenServerReturns503() throws Exception {
        byte[] expected = "downloaded-after-retry".getBytes(StandardCharsets.UTF_8);
        AtomicInteger downloadAttempts = new AtomicInteger();
        startServer(exchange -> {
            if ("/v1/images/generations".equals(exchange.getRequestURI().getPath())) {
                String url = "https://93.184.216.34/generated.png";
                send(exchange, 200, "{\"data\":[{\"url\":\"" + url + "\"}]}");
            }
        });
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    if (!"/generated.png".equals(chain.request().url().encodedPath())) {
                        return chain.proceed(chain.request());
                    }
                    if (downloadAttempts.incrementAndGet() == 1) {
                        return response(chain.request(), 503, "Service Unavailable", "temporarily unavailable");
                    }
                    return response(chain.request(), 200, "OK", expected);
                })
                .build();

        byte[] actual = service(client).generateImage("下载重试");

        assertArrayEquals(expected, actual);
        assertEquals(2, downloadAttempts.get());
    }

    @Test
    void retriesGeneratedImageDownloadWhenNetworkFails() throws Exception {
        byte[] expected = "downloaded-after-network-retry".getBytes(StandardCharsets.UTF_8);
        AtomicInteger downloadAttempts = new AtomicInteger();
        startServer(exchange -> {
            if ("/v1/images/generations".equals(exchange.getRequestURI().getPath())) {
                String url = "https://93.184.216.34/generated.png";
                send(exchange, 200, "{\"data\":[{\"url\":\"" + url + "\"}]}");
                return;
            }
            send(exchange, 200, expected);
        });
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    if ("/generated.png".equals(chain.request().url().encodedPath())) {
                        if (downloadAttempts.incrementAndGet() == 1) {
                            throw new IOException("download interrupted");
                        }
                        return response(chain.request(), 200, "OK", expected);
                    }
                    return chain.proceed(chain.request());
                })
                .build();
        ImageGenerationService service = service(client);

        byte[] actual = service.generateImage("下载网络重试");

        assertArrayEquals(expected, actual);
        assertEquals(2, downloadAttempts.get());
    }

    @Test
    void failsWhenResponseHasNoImageData() throws Exception {
        startServer(exchange -> send(exchange, 200, "{\"data\":[{}]}"));

        IOException exception = assertThrows(IOException.class, () -> service().generateImage("空响应"));

        assertTrue(exception.getMessage().contains("b64_json/url"));
    }

    @Test
    void rejectsLocalUrlEvenWhenPathContainsSiliconflow() throws Exception {
        startServer(exchange ->
                send(exchange, 200,
                        "{\"images\":[{\"url\":\"http://127.0.0.1/siliconflow/image.png\"}]}"));

        IOException error = assertThrows(IOException.class,
                () -> service().generateImage("unsafe"));

        assertTrue(error.getMessage().contains("不安全") || error.getMessage().contains("HTTPS"));
    }

    @Test
    void rejectsLocalUrlEvenWhenHostContainsSfMaas() throws Exception {
        startServer(exchange ->
                send(exchange, 200,
                        "{\"data\":[{\"url\":\"https://127.0.0.1/sf-maas/image.png\"}]}"));

        IOException error = assertThrows(IOException.class,
                () -> service().generateImage("unsafe-sf-maas"));

        assertTrue(error.getMessage().contains("不安全"));
    }

    private ImageGenerationService service() {
        return service(new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    if ("93.184.216.34".equals(chain.request().url().host())) {
                        String body = chain.request().url().encodedPath().replaceFirst("^/", "");
                        return response(chain.request(), 200, "OK", body);
                    }
                    return chain.proceed(chain.request());
                })
                .build());
    }

    private ImageGenerationService service(OkHttpClient client) {
        return new ImageGenerationService(
                "test-key",
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-image",
                "256x256",
                client);
    }

    private Response response(Request request, int code, String message, String body) {
        return response(request, code, message, body.getBytes(StandardCharsets.UTF_8));
    }

    private Response response(Request request, int code, String message, byte[] body) {
        return new Response.Builder()
                .request(request)
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(code)
                .message(message)
                .body(ResponseBody.create(body, null))
                .build();
    }

    private void startServer(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler::handle);
        server.start();
    }

    private void send(HttpExchange exchange, int code, String body) throws IOException {
        send(exchange, code, body.getBytes(StandardCharsets.UTF_8));
    }

    private void send(HttpExchange exchange, int code, byte[] body) throws IOException {
        exchange.sendResponseHeaders(code, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    @FunctionalInterface
    interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
