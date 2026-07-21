package com.demo.demo.Service.memory;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ConversationMemoryStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void persistAndReload() throws IOException {
        Path file = tempDir.resolve("conversation-memory.json");
        ConversationMemoryStore first = new ConversationMemoryStore(file);
        first.appendTurn("user-a", "问题1", "回答1");

        ConversationMemoryStore restarted = new ConversationMemoryStore(file);
        assertEquals(
                List.of(
                        new ConversationMessage("user", "问题1"),
                        new ConversationMessage("assistant", "回答1")),
                restarted.getHistory("user-a"));
    }

    @Test
    void emptyForUnknownUser() throws IOException {
        Path file = tempDir.resolve("conversation-memory.json");
        ConversationMemoryStore store = new ConversationMemoryStore(file);
        store.appendTurn("user-a", "你好", "你好！");
        assertTrue(store.getHistory("user-b").isEmpty());
    }

    @Test
    void userIsolation() throws IOException {
        Path file = tempDir.resolve("conversation-memory.json");
        ConversationMemoryStore store = new ConversationMemoryStore(file);
        store.appendTurn("user-a", "a问题", "a回答");
        store.appendTurn("user-b", "b问题", "b回答");

        ConversationMemoryStore reloaded = new ConversationMemoryStore(file);
        assertEquals(2, reloaded.getHistory("user-a").size());
        assertEquals(2, reloaded.getHistory("user-b").size());
        assertEquals("a问题", reloaded.getHistory("user-a").get(0).content());
        assertEquals("b问题", reloaded.getHistory("user-b").get(0).content());
    }

    @Test
    void trimTo10Pairs() throws IOException {
        Path file = tempDir.resolve("conversation-memory.json");
        ConversationMemoryStore store = new ConversationMemoryStore(file);
        for (int i = 1; i <= 12; i++) {
            store.appendTurn("user-a", "问题" + i, "回答" + i);
        }
        List<ConversationMessage> history = store.getHistory("user-a");
        assertEquals(20, history.size(), "应裁剪为 10 对 = 20 条消息");
        assertEquals("问题3", history.get(0).content());
        assertEquals("回答12", history.get(history.size() - 1).content());
    }

    @Test
    void selectiveClear() throws IOException {
        Path file = tempDir.resolve("conversation-memory.json");
        ConversationMemoryStore store = new ConversationMemoryStore(file);
        store.appendTurn("user-a", "a问题", "a回答");
        store.appendTurn("user-b", "b问题", "b回答");
        store.clear("user-a");
        ConversationMemoryStore reloaded = new ConversationMemoryStore(file);
        assertTrue(reloaded.getHistory("user-a").isEmpty());
        assertEquals(2, reloaded.getHistory("user-b").size());
    }

    @Test
    void missingFileInitializesEmpty() {
        Path file = tempDir.resolve("nonexistent-dir").resolve("memory.json");
        ConversationMemoryStore store = new ConversationMemoryStore(file);
        assertTrue(store.getHistory("any-user").isEmpty());
        assertEquals(0, store.getUserCount());
    }

    @Test
    void malformedJsonInitializesEmpty() throws IOException {
        Path file = tempDir.resolve("conversation-memory.json");
        Files.writeString(file, "{broken", StandardCharsets.UTF_8);
        ConversationMemoryStore store = new ConversationMemoryStore(file);
        assertTrue(store.getHistory("any-user").isEmpty());
        assertEquals(0, store.getUserCount());
    }

    @Test
    void writeFailureThrows() throws IOException {
        Path notADir = tempDir.resolve("not-a-dir");
        Files.writeString(notADir, "block");
        Path file = notADir.resolve("memory.json");
        ConversationMemoryStore store = new ConversationMemoryStore(file);
        assertThrows(IOException.class, () ->
                store.appendTurn("user-a", "问题", "回答"));
    }

    @Test
    void memoryUpdatedEvenOnWriteFailure() throws IOException {
        Path file = tempDir.resolve("conversation-memory.json");
        ConversationMemoryStore store = new ConversationMemoryStore(file);
        store.appendTurn("user-a", "问题1", "回答1");
        assertEquals(2, store.getHistory("user-a").size());
        Files.deleteIfExists(file);
        Files.createDirectory(file);
        assertThrows(IOException.class, () ->
                store.appendTurn("user-a", "问题2", "回答2"));
        List<ConversationMessage> history = store.getHistory("user-a");
        assertEquals(4, history.size());
        assertEquals("问题2", history.get(2).content());
    }

    @Test
    void concurrentAppendsProducesValidJson() throws Exception {
        Path file = tempDir.resolve("conversation-memory.json");
        ConversationMemoryStore store = new ConversationMemoryStore(file);
        int userCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch latch = new CountDownLatch(userCount);
        for (int i = 0; i < userCount; i++) {
            String userId = "user-" + i;
            executor.submit(() -> {
                try {
                    store.appendTurn(userId, "q-" + userId, "a-" + userId);
                } catch (IOException e) {
                    fail("并发写入不应失败: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }
        boolean completed = latch.await(10, TimeUnit.SECONDS);
        assertTrue(completed);
        executor.shutdown();
        String json = Files.readString(file);
        assertDoesNotThrow(() -> JsonParser.parseString(json).getAsJsonObject());
        ConversationMemoryStore reloaded = new ConversationMemoryStore(file);
        assertEquals(userCount, reloaded.getUserCount());
    }

    @Test
    void concurrentAppendsPreservesData() throws Exception {
        Path file = tempDir.resolve("conversation-memory.json");
        ConversationMemoryStore store = new ConversationMemoryStore(file);
        int userCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch latch = new CountDownLatch(userCount);
        for (int i = 0; i < userCount; i++) {
            String userId = "user-" + i;
            executor.submit(() -> {
                try {
                    store.appendTurn(userId, "q-" + userId, "a-" + userId);
                } catch (IOException e) {
                    fail("写入不应失败: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();
        ConversationMemoryStore reloaded = new ConversationMemoryStore(file);
        for (int i = 0; i < userCount; i++) {
            String userId = "user-" + i;
            List<ConversationMessage> history = reloaded.getHistory(userId);
            assertEquals(2, history.size(), "用户 " + userId + " 应有 2 条消息");
            assertEquals("user", history.get(0).role());
            assertEquals("q-" + userId, history.get(0).content());
            assertEquals("assistant", history.get(1).role());
            assertEquals("a-" + userId, history.get(1).content());
        }
    }

    @Test
    void getHistoryReturnsDefensiveCopy() throws IOException {
        Path file = tempDir.resolve("conversation-memory.json");
        ConversationMemoryStore store = new ConversationMemoryStore(file);
        store.appendTurn("user-a", "问题", "回答");
        List<ConversationMessage> copy = store.getHistory("user-a");
        assertThrows(UnsupportedOperationException.class, () -> copy.clear());
        assertEquals(2, store.getHistory("user-a").size());
    }
}
