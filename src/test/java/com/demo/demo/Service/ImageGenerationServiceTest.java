package com.demo.demo.Service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
                String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/generated.png";
                send(exchange, 200, "{\"data\":[{\"url\":\"" + url + "\"}]}");
                return;
            }
            send(exchange, 200, expected);
        });

        byte[] actual = service().generateImage("山谷里的小屋");

        assertArrayEquals(expected, actual);
    }

    @Test
    void failsWhenResponseHasNoImageData() throws Exception {
        startServer(exchange -> send(exchange, 200, "{\"data\":[{}]}"));

        IOException exception = assertThrows(IOException.class, () -> service().generateImage("空响应"));

        assertTrue(exception.getMessage().contains("b64_json/url"));
    }

    private ImageGenerationService service() {
        return new ImageGenerationService(
                "test-key",
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-image",
                "256x256",
                new OkHttpClient());
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
