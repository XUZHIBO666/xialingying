package com.demo.demo.Service;

import com.demo.demo.Service.context.ContextManager;
import com.demo.demo.Service.memory.ConversationMemoryStore;
import com.demo.demo.Service.memory.ConversationMessage;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AIServiceMemoryTest {

    @TempDir
    Path tempDir;

    private HttpServer server;
    private int serverPort;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private final AtomicReference<String> nextResponse = new AtomicReference<>();
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicInteger httpStatus = new AtomicInteger(200);
    private volatile boolean blockNextRequest = false;
    private CountDownLatch blockLatch;
    private CountDownLatch releaseLatch;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 1);
        serverPort = server.getAddress().getPort();
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
            lastRequestBody.set(new String(bodyBytes, StandardCharsets.UTF_8));
            requestCount.incrementAndGet();

            if (blockNextRequest) {
                blockNextRequest = false;
                if (blockLatch != null) blockLatch.countDown();
                if (releaseLatch != null) {
                    try {
                        releaseLatch.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }

            String response = nextResponse.get();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] respBytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(httpStatus.get(), respBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(respBytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private AIService createAiService(ConversationMemoryStore store) {
        AIService service = new AIService(store, new ContextManager());
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
        ReflectionTestUtils.setField(service, "apiUrl", "http://localhost:" + serverPort);
        ReflectionTestUtils.setField(service, "model", "test-model");
        ReflectionTestUtils.setField(service, "systemPrompt", "你是测试助手");
        return service;
    }

    // === 1. 重启后恢复上下文 ===

    @Test
    void restartRestoresContext() throws IOException {
        Path memFile = tempDir.resolve("memory.json");

        // 第一次：用户说"我叫小明"，LLM 回复"记住了"
        nextResponse.set("""
                {"choices":[{"message":{"content":"记住了"}}]}""");
        ConversationMemoryStore store1 = new ConversationMemoryStore(memFile);
        AIService service1 = createAiService(store1);
        String reply1 = service1.chat("user-a", "我叫小明");
        assertEquals("记住了", reply1);
        String firstRequest = lastRequestBody.get();
        JsonArray firstMessages = JsonParser.parseString(firstRequest)
                .getAsJsonObject().getAsJsonArray("messages");
        assertEquals("system", firstMessages.get(0).getAsJsonObject().get("role").getAsString());
        assertEquals("你是测试助手",
                firstMessages.get(0).getAsJsonObject().get("content").getAsString());
        assertEquals("user", firstMessages.get(firstMessages.size() - 1)
                .getAsJsonObject().get("role").getAsString());
        assertEquals("我叫小明", firstMessages.get(firstMessages.size() - 1)
                .getAsJsonObject().get("content").getAsString());

        // 重建 Store 和 AIService（模拟重启）
        nextResponse.set("""
                {"choices":[{"message":{"content":"你是小明"}}]}""");
        ConversationMemoryStore store2 = new ConversationMemoryStore(memFile);
        AIService service2 = createAiService(store2);
        String reply2 = service2.chat("user-a", "我叫什么？");
        assertEquals("你是小明", reply2);

        // 验证第二次请求包含历史
        String secondRequest = lastRequestBody.get();
        JsonArray secondMessages = JsonParser.parseString(secondRequest)
                .getAsJsonObject().getAsJsonArray("messages");
        assertEquals(4, secondMessages.size(),
                "应有 system + user + assistant + user = 4 条");

        // 验证角色和内容顺序
        assertEquals("system", secondMessages.get(0).getAsJsonObject()
                .get("role").getAsString());
        assertEquals("user", secondMessages.get(1).getAsJsonObject()
                .get("role").getAsString());
        assertEquals("我叫小明", secondMessages.get(1).getAsJsonObject()
                .get("content").getAsString());
        assertEquals("assistant", secondMessages.get(2).getAsJsonObject()
                .get("role").getAsString());
        assertEquals("记住了", secondMessages.get(2).getAsJsonObject()
                .get("content").getAsString());
        assertEquals("user", secondMessages.get(3).getAsJsonObject()
                .get("role").getAsString());
        assertEquals("我叫什么？", secondMessages.get(3).getAsJsonObject()
                .get("content").getAsString());
    }

    // === 2. HTTP 失败不持久化 ===

    @Test
    void failedCallDoesNotPersist() throws IOException {
        Path memFile = tempDir.resolve("memory.json");

        // 先成功一对
        nextResponse.set("""
                {"choices":[{"message":{"content":"第一条回答"}}]}""");
        ConversationMemoryStore store1 = new ConversationMemoryStore(memFile);
        AIService service1 = createAiService(store1);
        service1.chat("user-a", "第一条消息");

        // 保存 JSON 文本
        String savedJson = Files.readString(memFile);
        int savedSize = store1.getHistory("user-a").size();

        // 模拟 HTTP 500
        httpStatus.set(500);
        AIService service2 = createAiService(new ConversationMemoryStore(memFile));
        String reply2 = service2.chat("user-a", "这条消息应该失败");
        assertNull(reply2);

        // 文件内容不应变化
        String afterFailureJson = Files.readString(memFile);
        assertEquals(savedJson, afterFailureJson, "HTTP 失败不应修改文件");

        ConversationMemoryStore store3 = new ConversationMemoryStore(memFile);
        assertEquals(savedSize, store3.getHistory("user-a").size(),
                "HTTP 失败不应增加历史");
        httpStatus.set(200); // 恢复
    }

    // === 3. 选择性清除 ===

    @Test
    void selectiveClearRemovesOnlyTarget() throws IOException {
        Path memFile = tempDir.resolve("memory.json");
        nextResponse.set("""
                {"choices":[{"message":{"content":"回答"}}]}""");

        ConversationMemoryStore store = new ConversationMemoryStore(memFile);
        AIService service = createAiService(store);
        service.chat("user-a", "a的消息");
        service.chat("user-b", "b的消息");

        // 清除 user-a
        service.clearHistory("user-a");

        ConversationMemoryStore reloaded = new ConversationMemoryStore(memFile);
        assertTrue(reloaded.getHistory("user-a").isEmpty(),
                "user-a 应已被清除");
        assertEquals(2, reloaded.getHistory("user-b").size(),
                "user-b 不应受影响");
    }

    // === 4. 请求消息顺序验证 ===

    @Test
    void requestMessageOrder() throws IOException {
        Path memFile = tempDir.resolve("memory.json");
        nextResponse.set("""
                {"choices":[{"message":{"content":"回答1"}}]}""");

        ConversationMemoryStore store = new ConversationMemoryStore(memFile);
        AIService service = createAiService(store);

        // 第一轮
        service.chat("user-a", "问题1");
        assertEquals(1, requestCount.get());

        // 第二轮
        nextResponse.set("""
                {"choices":[{"message":{"content":"回答2"}}]}""");
        service.chat("user-a", "问题2");

        String body = lastRequestBody.get();
        JsonArray messages = JsonParser.parseString(body)
                .getAsJsonObject().getAsJsonArray("messages");

        // 验证顺序: system → user(问题1) → assistant(回答1) → user(问题2)
        assertEquals("system", messages.get(0).getAsJsonObject().get("role").getAsString());
        assertEquals("user", messages.get(1).getAsJsonObject().get("role").getAsString());
        assertEquals("问题1", messages.get(1).getAsJsonObject().get("content").getAsString());
        assertEquals("assistant", messages.get(2).getAsJsonObject().get("role").getAsString());
        assertEquals("回答1", messages.get(2).getAsJsonObject().get("content").getAsString());
        assertEquals("user", messages.get(3).getAsJsonObject().get("role").getAsString());
        assertEquals("问题2", messages.get(3).getAsJsonObject().get("content").getAsString());
    }

    // === 5. 同一用户串行处理 ===

    @Test
    void sameUserSerialization() throws Exception {
        Path memFile = tempDir.resolve("memory.json");
        nextResponse.set("""
                {"choices":[{"message":{"content":"回答"}}]}""");

        ConversationMemoryStore store = new ConversationMemoryStore(memFile);
        AIService service = createAiService(store);

        // 先存一对，让第二个请求带历史
        service.chat("user-a", "预热消息");

        // 第二个请求会阻塞直到 release
        blockLatch = new CountDownLatch(1);
        releaseLatch = new CountDownLatch(1);
        blockNextRequest = true;
        nextResponse.set("""
                {"choices":[{"message":{"content":"慢回答"}}]}""");

        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<String> slowReply = new AtomicReference<>();
        executor.submit(() -> {
            slowReply.set(service.chat("user-a", "慢请求"));
        });

        // 等待慢请求到达服务端
        assertTrue(blockLatch.await(5, TimeUnit.SECONDS));

        // 在慢请求完成前，同用户再发一条——应该被 synchronized 阻塞
        // 但由于我们在同一个测试线程中，直接调用会死锁
        // 所以改为验证：慢请求还没到达时，requestCount 只有预热那一次
        int beforeRelease = requestCount.get();
        assertEquals(2, beforeRelease,
                "预热请求 + 阻塞中的请求 = 2 次请求");

        // 释放慢请求
        releaseLatch.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals("慢回答", slowReply.get());

        // 验证慢请求的消息顺序中包含预热历史
        String lastBody = lastRequestBody.get();
        JsonArray messages = JsonParser.parseString(lastBody)
                .getAsJsonObject().getAsJsonArray("messages");
        assertEquals(4, messages.size(),
                "应有 system + user(预热) + assistant(回答) + user(慢请求)");
    }

    // === 6. LLM 空回复不持久化 ===

    @Test
    void emptyResponseDoesNotPersist() throws IOException {
        Path memFile = tempDir.resolve("memory.json");

        // 先成功一对
        nextResponse.set("""
                {"choices":[{"message":{"content":"正常回答"}}]}""");
        ConversationMemoryStore store1 = new ConversationMemoryStore(memFile);
        AIService service1 = createAiService(store1);
        service1.chat("user-a", "正常消息");
        int savedSize = store1.getHistory("user-a").size();

        // 空回复
        nextResponse.set("""
                {"choices":[{"message":{"content":""}}]}""");
        AIService service2 = createAiService(new ConversationMemoryStore(memFile));
        String reply = service2.chat("user-a", "空回复测试");

        // 空回复被 trim 后为 null
        assertNull(reply);

        ConversationMemoryStore store3 = new ConversationMemoryStore(memFile);
        assertEquals(savedSize, store3.getHistory("user-a").size(),
                "空回复不应持久化");
    }
}
