# 持久化对话记忆 — 详细设计 v2

> 关联：P3-23 | 依赖：P0-4（AIService 线程安全修复）  
> 前身设计：`2026-07-20-persistent-conversation-memory-design.md`（已评审通过）

## 目标

将每个微信用户的最近 10 轮成功 LLM 对话持久化到本地 JSON 文件，应用重启后自动恢复上下文。无需 Redis、数据库或分布式组件。

---

## 1. 存储设计

### 1.1 文件布局

```
{工作目录}/
└── data/
    └── conversation-memory.json    # 由 AI_MEMORY_FILE 环境变量或 ai.memory.file 配置控制
```

`data/` 已在 `.gitignore` 中忽略，确保对话内容不会被提交。

### 1.2 JSON 结构

```json
{
  "v1": {
    "user_xxxxxxxxxxxx": [
      {"role": "user", "content": "今天天气怎么样"},
      {"role": "assistant", "content": "今天北京晴天，温度 25°C..."},
      {"role": "user", "content": "那明天呢"},
      {"role": "assistant", "content": "明天多云转阴..."}
    ],
    "user_yyyyyyyyyyyy": [
      {"role": "user", "content": "你好"},
      {"role": "assistant", "content": "你好！有什么可以帮你的？"}
    ]
  }
}
```

**设计决策**：
- 按用户 ID 分组的顶层结构——启动时一次加载全部，查找 O(1)
- 每用户最多 10 对（20 条消息）——超出时从最早开始裁剪
- 只存储 `user`/`assistant` 角色——System Prompt 从配置动态注入，不持久化
- 版本字段 `v1`——为未来结构升级预留
- **不存储**：时间戳、消息 ID、上下文 Token、模型名称

### 1.3 原子写入

```java
// 写 temp → 原子 rename → 清理 temp
Path absolute = memoryFile.toAbsolutePath();
Files.createDirectories(absolute.getParent());
Path temp = Files.createTempFile(absolute.getParent(),
        absolute.getFileName().toString(), ".tmp");
try {
    Files.writeString(temp, gson.toJson(snapshot), StandardCharsets.UTF_8);
    try {
        Files.move(temp, absolute,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
        Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
    }
} finally {
    Files.deleteIfExists(temp);
}
```

---

## 2. 组件设计

### 2.1 ConversationMessage（record）

```java
package com.demo.demo.Service.memory;

public record ConversationMessage(String role, String content) {
    // role ∈ {"user", "assistant"}
}
```

### 2.2 ConversationMemoryStore

```java
@Service
public class ConversationMemoryStore {

    // === 构造 ===
    @Autowired
    public ConversationMemoryStore(
            @Value("${ai.memory.file:./data/conversation-memory.json}") String filePath);

    // 测试用 package-private 构造
    ConversationMemoryStore(Path filePath);

    // === 查询 ===
    /** 返回指定用户的消息列表（防御性副本），不存在用户返回空列表 */
    public List<ConversationMessage> getHistory(String userId);

    /** 返回当前快照中的用户数 */
    public int getUserCount();

    // === 写入 ===
    /** 追加一对 user/assistant 消息并持久化。超过 10 对时裁剪最旧的 */
    public void appendTurn(String userId, String userMessage,
            String assistantMessage) throws IOException;

    /** 删除指定用户的所有历史并持久化 */
    public void clear(String userId) throws IOException;

    // === 内部 ===
    // ConcurrentHashMap<String, List<ConversationMessage>> memory;
    // 所有写操作 synchronized(memory) 保护
}
```

**线程安全**：
- 读：`getHistory` 返回 `List.copyOf()` 防御性副本
- 写：`appendTurn` 和 `clear` 在 `synchronized(memory)` 块中操作内存 + 写文件
- 不同用户的并发写入是安全的（同一把锁串行化文件写入）

**异常处理**：
- 文件不存在 → 初始化为空 Map，不报错
- JSON 格式损坏 → 日志警告（不含内容），初始化为空 Map
- `appendTurn` 写文件失败 → 抛出 IOException，**但内存快照已更新**（下次成功的写入会持久化）
- `clear` 写文件失败 → 抛出 IOException，内存快照已更新

---

### 2.3 AIService 改造

```java
@Service
public class AIService {

    // --- 移除 ---
    // private final Map<String, JsonArray> historyMap;  // 删除
    // private static final int MAX_HISTORY = 10;        // 删除

    // --- 新增 ---
    private final ConversationMemoryStore memoryStore;
    private final ConcurrentMap<String, Object> userLocks = new ConcurrentHashMap<>();

    public String chat(String userId, String message) {
        Object lock = userLocks.computeIfAbsent(userId, k -> new Object());
        synchronized (lock) {
            return chatInternal(userId, message);
        }
    }

    private String chatInternal(String userId, String message) {
        // 1. 构建消息列表
        JsonArray messages = new JsonArray();

        // system prompt
        JsonObject sys = new JsonObject();
        sys.addProperty("role", "system");
        sys.addProperty("content", systemPrompt);
        messages.add(sys);

        // 2. 加载持久化历史（最多 10 对 = 20 条）
        List<ConversationMessage> history = memoryStore.getHistory(userId);
        for (ConversationMessage cm : history) {
            JsonObject msg = new JsonObject();
            msg.addProperty("role", cm.role());
            msg.addProperty("content", cm.content());
            messages.add(msg);
        }

        // 3. 当前用户消息
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", message);
        messages.add(userMsg);

        // 4. 调用 LLM（保持不变，使用本地 JsonArray 无竞态）
        String reply = callLLM(messages);
        if (reply == null || reply.isBlank()) {
            return null;  // 失败不持久化
        }

        // 5. 持久化成功的一对
        try {
            memoryStore.appendTurn(userId, message, reply.trim());
        } catch (IOException e) {
            log.warn("[AI] 对话记忆写入失败 userId={}: {}",
                    maskUserId(userId), e.getMessage());
            // 内存快照已更新，下次成功写入会持久化
        }

        return reply.trim();
    }

    public void clearHistory(String userId) {
        try {
            memoryStore.clear(userId);
        } catch (IOException e) {
            log.warn("[AI] 清除对话记忆失败 userId={}: {}",
                    maskUserId(userId), e.getMessage());
        }
    }
}
```

**关键行为变化**：
- 原来：多用户可并发调用 LLM（共用 OkHttpClient），同一用户的并发由线程池的用户锁控制
- 现在：同一用户 `synchronized(lock)` 保证串行，不同用户仍可并发
- 原来：重启丢失历史
- 现在：重启后从 JSON 文件恢复

---

## 3. 配置

`application.yml`（已存在）：
```yaml
ai:
  memory:
    file: ${AI_MEMORY_FILE:./data/conversation-memory.json}
```

`application-local.example.yml` 补充：
```yaml
ai:
  memory:
    file: ./data/conversation-memory.json
```

无需新增环境变量。

---

## 4. 日志隐私

| 允许记录 | 禁止记录 |
|---------|---------|
| 用户数、消息对数 | 消息正文 |
| 文件路径 | JSON 文件内容 |
| 操作失败原因（异常类型） | 用户 ID（`maskUserId` 掩码） |
| 加载/持久化的字节数 | System Prompt |

---

## 5. 验证策略

### 5.1 单元测试（ConversationMemoryStoreTest）

使用 `@TempDir Path tempDir` 的 12 个测试场景：

| # | 测试方法 | 验证点 |
|---|---------|--------|
| 1 | `persistAndReload` | 写入后新 Store 实例可读取 |
| 2 | `emptyForUnknownUser` | 从未写入的用户返回空列表 |
| 3 | `userIsolation` | 两个用户的历史互不影响 |
| 4 | `trimTo10Pairs` | 写入 12 对后只剩最近 10 对（20 条） |
| 5 | `trimOrder` | 裁剪后最早的消息被移除 |
| 6 | `selectiveClear` | clear(user-a) 只影响 user-a |
| 7 | `missingFileInitializesEmpty` | 文件不存在时正常初始化 |
| 8 | `malformedJsonInitializesEmpty` | 写入 `{broken` 后启动不抛异常 |
| 9 | `writeFailureThrows` | 父目录是文件时 appendTurn 抛 IOException |
| 10 | `memoryUpdatedOnWriteFailure` | 写文件失败后 getHistory 仍返回新数据 |
| 11 | `concurrentAppendsProducesValidJson` | 20 个不同用户并发写入后 JSON 可解析 |
| 12 | `concurrentAppendsPreservesData` | 并发写入后每个用户都有 2 条消息 |

### 5.2 集成测试（AIServiceMemoryTest）

使用本地 `HttpServer` 模拟 LLM API 的 6 个场景：

| # | 测试方法 | 验证点 |
|---|---------|--------|
| 1 | `restartRestoresContext` | 先存一对，重建 Store 和 AIService 后，第二个请求包含完整历史 |
| 2 | `failedCallDoesNotPersist` | HTTP 500 时不调用 appendTurn |
| 3 | `emptyResponseDoesNotPersist` | LLM 返回空时不持久化 |
| 4 | `selectiveClearRemovesOnlyTarget` | clearHistory 只删除目标用户 |
| 5 | `sameUserSerialization` | 同一用户两个并发请求按顺序执行 |
| 6 | `requestMessageOrder` | 第二个请求的 messages 数组顺序为 system → history → current |

### 5.3 回归测试

- `mvn test` — 全部 79+ 现有测试继续通过
- `AIService` 的现有调用方（`BotController`、`VoiceMessageService`）不受影响
- `chat()` 方法签名不变

---

## 6. 部署注意事项

- 文件默认路径 `./data/conversation-memory.json` 相对于 JVM 工作目录
- 容器化部署时通过 `AI_MEMORY_FILE` 挂载到持久卷
- 不支持多实例（同一文件被多个进程写入会损坏 JSON）——这是设计约束，不是 Bug
- 删除文件即清除所有记忆，重启后从空开始
- 文件大小估算：10 对/用户 × 100 用户 × 平均 500 字/条 ≈ 1 MB

---

## 7. 与现有计划的关系

本文档是对 `2026-07-20-persistent-conversation-memory-design.md` 的补充细化，主要新增：
- JSON 版本字段
- 原子写入策略
- 线程安全方案细节
- 完整的测试场景表格
- 容量估算和部署注意事项

实施计划仍参考 `docs/superpowers/plans/2026-07-20-persistent-conversation-memory.md`。
