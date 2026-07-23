# Persistent Conversation Memory Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist the latest 10 complete LLM question/answer pairs per WeChat user in a local JSON file so context survives application restarts.

**Architecture:** Add one synchronized JSON snapshot store and inject it into `AIService`. `AIService` builds each request from the configured system prompt, persisted successful pairs, and the current user message; it saves only a successfully completed pair and serializes calls for the same user.

**Tech Stack:** Java 21, Spring Boot 4, Gson 2.10.1, OkHttp 4.12, JUnit 5, temporary directories, JDK `HttpServer`, Maven.

## Global Constraints

- Use one local JSON snapshot; do not add Redis, a database, or a new dependency.
- Keep at most 10 complete `user`/`assistant` pairs per user.
- Do not persist the system prompt or a failed/incomplete LLM turn.
- Do not log stored message bodies, API keys, JSON snapshots, or full user IDs added by this feature.
- Preserve existing text and voice callers of `AIService.chat(String userId, String message)`.
- Do not upgrade Java, Spring Boot, or iLink SDK and do not refactor unrelated services.

---

### Task 1: Atomic JSON Conversation Store

**Files:**
- Create: `src/main/java/com/demo/demo/Service/memory/ConversationMessage.java`
- Create: `src/main/java/com/demo/demo/Service/memory/ConversationMemoryStore.java`
- Create: `src/test/java/com/demo/demo/Service/memory/ConversationMemoryStoreTest.java`

**Interfaces:**
- Produces: `record ConversationMessage(String role, String content)`.
- Produces: `List<ConversationMessage> getHistory(String userId)`.
- Produces: `void appendTurn(String userId, String userMessage, String assistantMessage) throws IOException`.
- Produces: `void clear(String userId) throws IOException`.

- [ ] **Step 1: Write failing persistence, isolation, trimming, clear, corruption, and concurrency tests**

Use `@TempDir Path tempDir` and construct the wished-for store with a package-private `ConversationMemoryStore(Path file)` constructor. Include these concrete assertions:

```java
Path file = tempDir.resolve("conversation-memory.json");
ConversationMemoryStore first = new ConversationMemoryStore(file);
first.appendTurn("user-a", "问题1", "回答1");

ConversationMemoryStore restarted = new ConversationMemoryStore(file);
assertEquals(List.of(
        new ConversationMessage("user", "问题1"),
        new ConversationMessage("assistant", "回答1")),
        restarted.getHistory("user-a"));
assertTrue(restarted.getHistory("user-b").isEmpty());
```

For trimming, append 12 numbered pairs and assert there are 20 messages, starting at `问题3` and ending at `回答12`. For selective clearing, save two users, call `clear("user-a")`, reconstruct the store, and assert only `user-b` remains. For malformed JSON, write `{broken`, construct the store, and assert empty history without an exception. For write failure, use a target whose parent is a regular file, assert `appendTurn` throws `IOException`, then assert `getHistory` still contains the new pair. For concurrency, submit one append for 20 distinct users, await termination, reconstruct the store, and assert every user has exactly two messages and `JsonParser.parseString(Files.readString(file))` succeeds.

- [ ] **Step 2: Run the focused test and verify RED**

Run: `mvn.cmd "-Dtest=ConversationMemoryStoreTest" test`

Expected: test compilation fails because `ConversationMemoryStore` and `ConversationMessage` do not exist.

- [ ] **Step 3: Add the immutable message type**

Create:

```java
package com.demo.demo.Service.memory;

public record ConversationMessage(String role, String content) {
}
```

- [ ] **Step 4: Implement the minimal synchronized store**

The component owns `Map<String, List<ConversationMessage>> histories`, loads it once in its constructor with Gson `TypeToken`, and returns `List.copyOf(...)` from `getHistory`. `appendTurn` must update the in-memory list, remove the oldest two messages while `size > 20`, then persist the complete map.

Use the following write pattern so the temporary file is on the same filesystem:

```java
Path absolute = memoryFile.toAbsolutePath();
Files.createDirectories(absolute.getParent());
Path temporary = Files.createTempFile(absolute.getParent(), absolute.getFileName().toString(), ".tmp");
try {
    Files.writeString(temporary, gson.toJson(histories), StandardCharsets.UTF_8);
    try {
        Files.move(temporary, absolute,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
        Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
    }
} finally {
    Files.deleteIfExists(temporary);
}
```

Provide the Spring constructor:

```java
@Autowired
public ConversationMemoryStore(
        @Value("${ai.memory.file:./data/conversation-memory.json}") String file) {
    this(Path.of(file));
}
```

When the file is absent, leave the map empty. When loading fails, log only the path and exception type/message, clear the map, and do not throw.

- [ ] **Step 5: Re-run the focused test and verify GREEN**

Run: `mvn.cmd "-Dtest=ConversationMemoryStoreTest" test`

Expected: all store tests pass with no real project data file created.

- [ ] **Step 6: Commit the store**

```powershell
git add src/main/java/com/demo/demo/Service/memory src/test/java/com/demo/demo/Service/memory
git commit -m "feat: persist conversation history in json"
```

### Task 2: Use Persisted History in AIService

**Files:**
- Modify: `src/main/java/com/demo/demo/Service/AIService.java`
- Create: `src/test/java/com/demo/demo/Service/AIServiceMemoryTest.java`

**Interfaces:**
- Consumes: `ConversationMemoryStore.getHistory`, `appendTurn`, and `clear` from Task 1.
- Preserves: `String chat(String userId, String message)` and `void clearHistory(String userId)`.

- [ ] **Step 1: Write a failing restart-context request test**

Start a local `HttpServer` for `/v1/chat/completions`, capture each JSON body, and return:

```json
{"choices":[{"message":{"content":"第一条回答"}}]}
```

Configure `AIService` test instances with `ReflectionTestUtils` for `apiKey`, `apiUrl`, `model`, and `systemPrompt`. Call the first service with `我叫小明`, reconstruct both `ConversationMemoryStore` and `AIService` from the same temporary file, then call `我叫什么？`. Assert the second captured request roles and contents are exactly:

```text
system: 你是测试助手
user: 我叫小明
assistant: 第一条回答
user: 我叫什么？
```

- [ ] **Step 2: Add failing tests for unsuccessful calls and selective clearing**

Seed one successful pair, save the JSON text, make the HTTP server return status 500 for the next call, and assert the file text and `getHistory(userId)` remain unchanged. Then seed two users, call `aiService.clearHistory("user-a")`, reconstruct the store, and assert `user-a` is empty while `user-b` still has two messages.

- [ ] **Step 3: Add a failing same-user serialization test**

Start two calls for the same user with an executor. Make the first mock response block on a latch and record the number of requests received before release. Assert the second request has not reached the server until the first completes; after release, assert the second request includes the first completed pair before its current message.

- [ ] **Step 4: Run the focused AI test and verify RED**

Run: `mvn.cmd "-Dtest=AIServiceMemoryTest" test`

Expected: FAIL because `AIService` still uses its private in-memory `historyMap` and has no store constructor.

- [ ] **Step 5: Inject the store and build requests from defensive history copies**

Replace `historyMap` and `MAX_HISTORY` with:

```java
private final ConversationMemoryStore memoryStore;
private final ConcurrentMap<String, Object> userLocks = new ConcurrentHashMap<>();

public AIService(ConversationMemoryStore memoryStore) {
    this.memoryStore = memoryStore;
}
```

Make `chat` synchronize per user and delegate to a private method:

```java
public String chat(String userId, String message) {
    Object lock = userLocks.computeIfAbsent(userId, ignored -> new Object());
    synchronized (lock) {
        return chatInOrder(userId, message);
    }
}
```

Inside `chatInOrder`, create a new `JsonArray`, add the current system prompt, convert every stored `ConversationMessage` to a JSON role/content object, and finally add the current user message. Do not mutate the store before receiving a valid assistant reply.

- [ ] **Step 6: Persist only a valid complete response**

After parsing and trimming the reply, call:

```java
try {
    memoryStore.appendTurn(userId, message, reply);
} catch (IOException e) {
    log.warn("[AI] 对话记忆写入失败 userId={} error={}", maskUserId(userId), e.getMessage());
}
return reply;
```

HTTP and parsing failures return `null` without calling `appendTurn`. Change `clearHistory` to call `memoryStore.clear(userId)` and catch/log `IOException` without throwing to callers.

- [ ] **Step 7: Remove message bodies from AI logs**

Replace the current request/reply content logs with lengths and a masked identifier:

```java
log.info("[AI] 收到对话请求 userId={} messageLength={}",
        maskUserId(userId), message.length());
log.info("[AI] 回复成功 userId={} replyLength={}",
        maskUserId(userId), reply.length());
```

Use a private `maskUserId` helper that returns `***` for short IDs and otherwise keeps only the first four and last four characters.

- [ ] **Step 8: Re-run the focused AI and store tests and verify GREEN**

Run: `mvn.cmd "-Dtest=AIServiceMemoryTest,ConversationMemoryStoreTest" test`

Expected: restart context, failed-call immutability, selective clearing, same-user ordering, and store tests all pass.

- [ ] **Step 9: Commit AIService integration**

```powershell
git add src/main/java/com/demo/demo/Service/AIService.java src/test/java/com/demo/demo/Service/AIServiceMemoryTest.java
git commit -m "feat: restore llm context after restart"
```

### Task 3: Configuration and Repository Safety

**Files:**
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/resources/application-local.example.yml`
- Modify: `.gitignore`
- Modify: `README.md`
- Test: `src/test/java/com/demo/demo/DemoApplicationTests.java`

**Interfaces:**
- Produces: property `ai.memory.file` with environment override `AI_MEMORY_FILE`.
- Defaults to: `./data/conversation-memory.json`.

- [ ] **Step 1: Add the default configuration and ignore rule**

Under `ai`, add:

```yaml
  memory:
    file: ${AI_MEMORY_FILE:./data/conversation-memory.json}
```

Add `data/` to `.gitignore`. Add the non-secret example `memory.file: ./data/conversation-memory.json` to `application-local.example.yml`.

- [ ] **Step 2: Document behavior and operations**

In `README.md`, document:

```text
AI_MEMORY_FILE=./data/conversation-memory.json
```

State that each WeChat user keeps the latest 10 complete successful turns, restarts reload the file, deleting the file clears all memory, and `data/` must not be committed.

- [ ] **Step 3: Run the Spring context test**

Run: `mvn.cmd "-Dtest=DemoApplicationTests" test`

Expected: the application context starts with the default memory path and no Redis process.

- [ ] **Step 4: Commit configuration and documentation**

```powershell
git add .gitignore README.md src/main/resources/application.yml src/main/resources/application-local.example.yml
git commit -m "docs: configure persistent conversation memory"
```

### Task 4: Full Verification and Manual Restart Check

**Files:**
- Inspect only: all files changed in Tasks 1–3.

**Interfaces:**
- Verifies the complete text and voice callers still use the same persisted `AIService.chat(...)` path.

- [ ] **Step 1: Run focused memory tests**

Run: `mvn.cmd "-Dtest=ConversationMemoryStoreTest,AIServiceMemoryTest" test`

Expected: all memory tests pass.

- [ ] **Step 2: Run the complete suite and package build**

```powershell
mvn.cmd test
mvn.cmd -DskipTests package
```

Expected: both commands exit 0; report exact test/failure/error/skip counts from Surefire XML.

- [ ] **Step 3: Inspect repository changes**

```powershell
git diff --check
git status --short
git log --oneline -5
```

Expected: no whitespace errors, no generated `data/` file tracked, and only intentional changes/commits are present.

- [ ] **Step 4: Manually prove restart persistence**

Run the worktree application and send:

```text
我叫小明，请记住我的名字。
```

After receiving the reply, stop and restart the application, then send:

```text
我叫什么名字？
```

Expected: the answer uses `小明`; `data/conversation-memory.json` exists locally but `git status --short` does not list it.
