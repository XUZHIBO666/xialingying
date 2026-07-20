package com.demo.demo.Service.memory;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ConversationMemoryStore {

    private static final int MAX_TURNS = 10;
    private static final Type STORE_TYPE = new TypeToken<Map<String, List<ConversationMessage>>>() { }.getType();

    private final Path memoryFile;
    private final Gson gson = new Gson();
    private final Map<String, List<ConversationMessage>> histories = new HashMap<>();

    @Autowired
    public ConversationMemoryStore(@Value("${ai.memory.file:./data/conversation-memory.json}") String file) {
        this(Path.of(file));
    }

    ConversationMemoryStore(Path memoryFile) {
        this.memoryFile = memoryFile;
        load();
    }

    public synchronized List<ConversationMessage> getHistory(String userId) {
        return List.copyOf(histories.getOrDefault(userId, List.of()));
    }

    public synchronized void appendTurn(String userId, String userMessage,
                                        String assistantMessage) throws IOException {
        List<ConversationMessage> history = histories.computeIfAbsent(userId,
                ignored -> new ArrayList<>());
        history.add(new ConversationMessage("user", userMessage));
        history.add(new ConversationMessage("assistant", assistantMessage));
        while (history.size() > MAX_TURNS * 2) {
            history.remove(0);
            history.remove(0);
        }
        persist();
    }

    public synchronized void clear(String userId) throws IOException {
        histories.remove(userId);
        persist();
    }

    private void load() {
        if (!Files.exists(memoryFile)) {
            return;
        }
        try {
            Map<String, List<ConversationMessage>> saved = gson.fromJson(
                    Files.readString(memoryFile, StandardCharsets.UTF_8), STORE_TYPE);
            if (saved != null) {
                saved.forEach((userId, history) ->
                        histories.put(userId, new ArrayList<>(history)));
            }
        } catch (Exception e) {
            histories.clear();
            log.warn("[AI记忆] 无法读取记忆文件 path={} error={}",
                    memoryFile.toAbsolutePath(), e.getMessage());
        }
    }

    private void persist() throws IOException {
        Path absolute = memoryFile.toAbsolutePath();
        Path parent = absolute.getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent,
                absolute.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, gson.toJson(histories), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, absolute,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
