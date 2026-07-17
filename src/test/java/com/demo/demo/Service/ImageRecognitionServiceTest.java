package com.demo.demo.Service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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

        String reply = service.recognize("fake-image".getBytes(StandardCharsets.UTF_8));

        assertEquals("图片里是一只猫。", reply);
        assertEquals("Bearer test-key", authHeader.get());
        assertTrue(requestBody.get().contains("\"model\":\"vision-model\""));
        assertTrue(requestBody.get().contains("\"type\":\"image_url\""));
        assertTrue(requestBody.get().contains("data:image/jpeg;base64"));
        assertTrue(requestBody.get().contains("\"text\":\"描述图片\""));
    }

    @Test
    void includesProviderErrorMessageWhenVisionApiFails() throws Exception {
        startServer(exchange -> send(exchange, 403, "{\"code\":30003,\"message\":\"Model disabled.\",\"data\":null}"));

        ImageRecognitionService service = new ImageRecognitionService(
                "test-key",
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "disabled-model",
                "描述图片",
                new OkHttpClient());

        IOException exception = assertThrows(IOException.class,
                () -> service.recognize("fake-image".getBytes(StandardCharsets.UTF_8)));

        assertEquals("图片识别失败，HTTP 403：Model disabled.", exception.getMessage());
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
}
