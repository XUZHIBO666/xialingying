package com.demo.demo.Service.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 持久化对话记忆存储。
 *
 * 按用户 ID 分组，每用户最多保留 10 对（20 条）最近的 user/assistant 消息。
 * 存储为单个本地 JSON 快照，使用原子写入保证文件不损坏。
 * 线程安全：写操作串行化在 synchronized 块中，读操作返回防御性副本。
 */
@Slf4j
@Service
public class ConversationMemoryStore {

    private static final int MAX_PAIRS_PER_USER = 10;
    private static final int MAX_MESSAGES_PER_USER = MAX_PAIRS_PER_USER * 2;
    private static final String VERSION_KEY = "v1";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path memoryFile;
    private final Map<String, List<ConversationMessage>> memory;
    private final Object writeLock = new Object();

    /**
     * Spring 构造——从配置读取文件路径。
     */
    @Autowired
    public ConversationMemoryStore(
            @Value("${ai.memory.file:./data/conversation-memory.json}") String filePath) {
        this(Path.of(filePath));
    }

    /**
     * 测试用构造——直接指定文件路径。
     */
    public ConversationMemoryStore(Path memoryFile) {
        this.memoryFile = memoryFile.toAbsolutePath();
        this.memory = loadFromDisk();
    }

    // ==================== 查询 ====================

    /**
     * 返回指定用户的对话历史（防御性副本），不存在则返回空列表。
     */
    public List<ConversationMessage> getHistory(String userId) {
        List<ConversationMessage> userMessages = memory.get(userId);
        if (userMessages == null || userMessages.isEmpty()) {
            return List.of();
        }
        return List.copyOf(userMessages);
    }

    /**
     * 返回当前快照中的用户数。
     */
    public int getUserCount() {
        return memory.size();
    }

    // ==================== 写入 ====================

    /**
     * 追加一对 user/assistant 消息，裁剪到 10 对，并持久化到磁盘。
     * 写入失败时抛出 IOException，但内存快照已更新。
     */
    public void appendTurn(String userId, String userMessage, String assistantMessage)
            throws IOException {
        synchronized (writeLock) {
            List<ConversationMessage> messages = memory.computeIfAbsent(userId,
                    k -> new ArrayList<>());

            messages.add(new ConversationMessage("user", userMessage));
            messages.add(new ConversationMessage("assistant", assistantMessage));

            // 裁剪：保留最近 10 对
            while (messages.size() > MAX_MESSAGES_PER_USER) {
                messages.remove(0);
                messages.remove(0);  // 移除最早的一对
            }

            persistToDisk();
        }
    }

    /**
     * 删除指定用户的所有历史并持久化。
     */
    public void clear(String userId) throws IOException {
        synchronized (writeLock) {
            memory.remove(userId);
            persistToDisk();
        }
    }

    // ==================== 持久化 ====================

    private void persistToDisk() throws IOException {
        JsonObject root = buildJsonSnapshot();
        String json = GSON.toJson(root);

        Path parent = memoryFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path temp = Files.createTempFile(parent,
                memoryFile.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temp, json, StandardCharsets.UTF_8);
            try {
                Files.move(temp, memoryFile, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, memoryFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private JsonObject buildJsonSnapshot() {
        JsonObject root = new JsonObject();
        JsonObject versioned = new JsonObject();
        for (Map.Entry<String, List<ConversationMessage>> entry : memory.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            versioned.add(entry.getKey(),
                    GSON.toJsonTree(entry.getValue()));
        }
        root.add(VERSION_KEY, versioned);
        return root;
    }

    // ==================== 加载 ====================

    private Map<String, List<ConversationMessage>> loadFromDisk() {
        if (!Files.isRegularFile(memoryFile)) {
            log.debug("[记忆] 文件不存在，初始化为空: {}", memoryFile);
            return new ConcurrentHashMap<>();
        }

        try {
            String json = Files.readString(memoryFile);
            if (json.isBlank()) {
                return new ConcurrentHashMap<>();
            }

            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject versioned = root.getAsJsonObject(VERSION_KEY);
            if (versioned == null) {
                log.warn("[记忆] 文件缺少版本键，初始化为空");
                return new ConcurrentHashMap<>();
            }

            Map<String, List<ConversationMessage>> loaded = new ConcurrentHashMap<>();
            for (Map.Entry<String, JsonElement> entry : versioned.entrySet()) {
                List<ConversationMessage> messages = GSON.fromJson(
                        entry.getValue().getAsJsonArray(),
                        new TypeToken<List<ConversationMessage>>() {}.getType());
                if (messages != null && !messages.isEmpty()) {
                    // 加载时也执行裁剪，防止手动编辑文件造成超量
                    while (messages.size() > MAX_MESSAGES_PER_USER) {
                        messages.remove(0);
                        messages.remove(0);
                    }
                    loaded.put(entry.getKey(), Collections.synchronizedList(messages));
                }
            }

            log.debug("[记忆] 已加载 {} 个用户的对话历史", loaded.size());
            return loaded;
        } catch (Exception e) {
            log.warn("[记忆] 文件加载失败，初始化为空 path={} error={}: {}",
                    memoryFile, e.getClass().getSimpleName(), e.getMessage());
            return new ConcurrentHashMap<>();
        }
    }
}
