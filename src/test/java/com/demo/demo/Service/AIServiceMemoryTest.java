package com.demo.demo.Service;

import com.demo.demo.Service.memory.ConversationMemoryStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AIServiceMemoryTest {

    @TempDir
    Path tempDir;

    private HttpServer server;
    private ExecutorService serverExecutor;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
    }

    @Test
    void includesPersistedHistoryAfterRestart() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<JsonObject> secondRequest = new AtomicReference<>();
        startServer(exchange -> {
            int request = requests.incrementAndGet();
            JsonObject body = readBody(exchange);
            if (request == 2) {
                secondRequest.set(body);
            }
            sendJson(exchange, 200, completion(request == 1 ? "第一条回答" : "第二条回答"));
        });
        Path file = tempDir.resolve("memory.json");

        AIService first = configuredService(new ConversationMemoryStore(file.toString()));
        assertEquals("第一条回答", first.chat("user-a", "我叫小明"));
        AIService restarted = configuredService(new ConversationMemoryStore(file.toString()));
        assertEquals("第二条回答", restarted.chat("user-a", "我叫什么？"));

        JsonArray messages = secondRequest.get().getAsJsonArray("messages");
        assertEquals(List.of("system", "user", "assistant", "user"),
                messages.asList().stream()
                        .map(element -> element.getAsJsonObject().get("role").getAsString())
                        .toList());
        assertEquals("我叫小明", messages.get(1).getAsJsonObject().get("content").getAsString());
        assertEquals("第一条回答", messages.get(2).getAsJsonObject().get("content").getAsString());
        assertEquals("我叫什么？", messages.get(3).getAsJsonObject().get("content").getAsString());
    }

    @Test
    void httpFailureDoesNotSaveIncompleteTurn() throws Exception {
        startServer(exchange -> sendJson(exchange, 500, "{}"));
        Path file = tempDir.resolve("memory.json");
        ConversationMemoryStore store = new ConversationMemoryStore(file.toString());
        AIService service = configuredService(store);

        assertNull(service.chat("user-a", "失败消息"));

        assertTrue(store.getHistory("user-a").isEmpty());
        assertFalse(java.nio.file.Files.exists(file));
    }

    @Test
    void clearHistoryKeepsOtherUsers() throws Exception {
        ConversationMemoryStore store = new ConversationMemoryStore(
                tempDir.resolve("memory.json").toString());
        store.appendTurn("user-a", "a", "answer-a");
        store.appendTurn("user-b", "b", "answer-b");
        AIService service = new AIService(store);

        service.clearHistory("user-a");

        assertTrue(store.getHistory("user-a").isEmpty());
        assertEquals(2, store.getHistory("user-b").size());
    }

    @Test
    void serializesRequestsFromSameUser() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch firstReachedServer = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicReference<JsonObject> secondRequest = new AtomicReference<>();
        startServer(exchange -> {
            int request = requests.incrementAndGet();
            JsonObject body = readBody(exchange);
            if (request == 1) {
                firstReachedServer.countDown();
                await(releaseFirst);
            } else {
                secondRequest.set(body);
            }
            sendJson(exchange, 200, completion("回答" + request));
        });
        AIService service = configuredService(new ConversationMemoryStore(
                tempDir.resolve("memory.json").toString()));
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = callers.submit(() -> service.chat("user-a", "问题1"));
            assertTrue(firstReachedServer.await(2, TimeUnit.SECONDS));
            Future<String> second = callers.submit(() -> service.chat("user-a", "问题2"));

            Thread.sleep(200);
            assertEquals(1, requests.get());
            releaseFirst.countDown();

            assertEquals("回答1", first.get(2, TimeUnit.SECONDS));
            assertEquals("回答2", second.get(2, TimeUnit.SECONDS));
            JsonArray messages = secondRequest.get().getAsJsonArray("messages");
            assertEquals("问题1", messages.get(1).getAsJsonObject().get("content").getAsString());
            assertEquals("回答1", messages.get(2).getAsJsonObject().get("content").getAsString());
            assertEquals("问题2", messages.get(3).getAsJsonObject().get("content").getAsString());
        } finally {
            releaseFirst.countDown();
            callers.shutdownNow();
        }
    }

    private AIService configuredService(ConversationMemoryStore store) {
        AIService service = new AIService(store);
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
        ReflectionTestUtils.setField(service, "apiUrl",
                "http://localhost:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(service, "model", "test-model");
        ReflectionTestUtils.setField(service, "systemPrompt", "system prompt");
        return service;
    }

    private void startServer(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/chat/completions", handler);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.start();
    }

    private static JsonObject readBody(HttpExchange exchange) throws IOException {
        return JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static String completion(String reply) {
        JsonObject message = new JsonObject();
        message.addProperty("content", reply);
        JsonObject choice = new JsonObject();
        choice.add("message", message);
        JsonArray choices = new JsonArray();
        choices.add(choice);
        JsonObject response = new JsonObject();
        response.add("choices", choices);
        return response.toString();
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
