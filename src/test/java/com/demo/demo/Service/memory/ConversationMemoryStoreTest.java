package com.demo.demo.Service.memory;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationMemoryStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void reloadsHistoryAfterRestart() throws IOException {
        Path file = tempDir.resolve("memory.json");
        ConversationMemoryStore first = new ConversationMemoryStore(file);
        first.appendTurn("user-a", "question", "answer");

        ConversationMemoryStore restarted = new ConversationMemoryStore(file);

        assertEquals(List.of(
                new ConversationMessage("user", "question"),
                new ConversationMessage("assistant", "answer")
        ), restarted.getHistory("user-a"));
    }

    @Test
    void isolatesUsersAndClearsOnlySelectedUser() throws IOException {
        Path file = tempDir.resolve("memory.json");
        ConversationMemoryStore store = new ConversationMemoryStore(file);
        store.appendTurn("user-a", "a-question", "a-answer");
        store.appendTurn("user-b", "b-question", "b-answer");

        store.clear("user-a");

        assertTrue(store.getHistory("user-a").isEmpty());
        assertEquals("b-question", store.getHistory("user-b").get(0).content());
        assertEquals("b-question", new ConversationMemoryStore(file)
                .getHistory("user-b").get(0).content());
    }

    @Test
    void keepsLastTenCompleteTurns() throws IOException {
        ConversationMemoryStore store = new ConversationMemoryStore(tempDir.resolve("memory.json"));

        for (int i = 1; i <= 12; i++) {
            store.appendTurn("user-a", "question" + i, "answer" + i);
        }

        List<ConversationMessage> history = store.getHistory("user-a");
        assertEquals(20, history.size());
        assertEquals("question3", history.get(0).content());
        assertEquals("answer12", history.get(19).content());
    }

    @Test
    void malformedJsonStartsWithEmptyHistory() throws IOException {
        Path file = tempDir.resolve("memory.json");
        Files.writeString(file, "not-json");

        ConversationMemoryStore store = new ConversationMemoryStore(file);

        assertTrue(store.getHistory("user-a").isEmpty());
    }

    @Test
    void failedWriteKeepsNewTurnInMemory() throws IOException {
        Path parentFile = tempDir.resolve("not-a-directory");
        Files.writeString(parentFile, "occupied");
        ConversationMemoryStore store = new ConversationMemoryStore(parentFile.resolve("memory.json"));

        assertThrows(IOException.class,
                () -> store.appendTurn("user-a", "question", "answer"));

        assertEquals(2, store.getHistory("user-a").size());
    }

    @Test
    void concurrentWritesProduceValidJson() throws Exception {
        Path file = tempDir.resolve("memory.json");
        ConversationMemoryStore store = new ConversationMemoryStore(file);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 20; i++) {
                int user = i;
                futures.add(executor.submit(() -> {
                    start.await();
                    store.appendTurn("user-" + user, "question", "answer");
                    return null;
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        ConversationMemoryStore restarted = new ConversationMemoryStore(file);
        for (int i = 0; i < 20; i++) {
            assertEquals(2, restarted.getHistory("user-" + i).size());
        }
    }
}
