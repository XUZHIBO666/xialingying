# Periodic Reply MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add persistent interval, daily, and weekly WeChat replies with fixed or Agent-generated content while adding only one production class.

**Architecture:** A single `PeriodicReplyService` owns the nested task record, Agent tools, JSON persistence, scheduling, and trigger callbacks. `AIService` registers the tool and passes trusted user/context metadata; `MemoryAgentHook` adds active task summaries beside vector memories; `BotController` wires existing Agent and default-Bot operations into callbacks.

**Tech Stack:** Java 21, Spring Boot, Spring AI Alibaba ReactAgent, Spring AI `@Tool`, Jackson, `java.time`, `ScheduledExecutorService`, JUnit 5, Mockito.

## Global Constraints

- Add exactly one production class: `src/main/java/com/demo/demo/Service/PeriodicReplyService.java`.
- Keep `MessagesModelHook trimHook` unchanged.
- Use `Asia/Shanghai`; support only `INTERVAL`, `DAILY`, and `WEEKLY`.
- Support `FIXED` and `AGENT` task modes.
- Bind the MVP to `MultiBotManager.getDefaultBot()` only.
- JSON is the task source of truth; do not add a table, data source, Quartz, cron library, distributed lock, or parser class.
- Keep at most 20 active tasks per user.
- Never log `contextToken`, task/message bodies, media parameters, or external response bodies.
- Mock Agent, iLink, embedding, and network activity in tests.

## File Map

- Create `src/main/java/com/demo/demo/Service/PeriodicReplyService.java`: nested task model, tools, persistence, schedule calculation, scan loop, callbacks, summaries.
- Create `src/test/java/com/demo/demo/Service/PeriodicReplyServiceTest.java`: deterministic service, persistence, scheduling, concurrency, and privacy tests.
- Modify `src/main/java/com/demo/demo/Service/AIService.java`: inject/register service, pass trusted tool context, construct enhanced memory Hook.
- Modify `src/main/java/com/demo/demo/Service/memory/MemoryAgentHook.java`: merge vector memory and active periodic-task summary independently.
- Create `src/test/java/com/demo/demo/Service/memory/MemoryAgentHookTest.java`: verify independent memory sources.
- Modify `src/main/java/com/demo/demo/controller/BotController.java`: pass `contextToken` into AI calls and wire callbacks.
- Modify `src/main/resources/application.yml`: configure the task file.
- Modify `src/main/resources/application-local.example.yml`: document the local path.

---

### Task 1: Persistent task CRUD and calendar calculations

**Files:**
- Create: `src/main/java/com/demo/demo/Service/PeriodicReplyService.java`
- Create: `src/test/java/com/demo/demo/Service/PeriodicReplyServiceTest.java`

**Interfaces:**
- Produces: `PeriodicReplyService(Path, ObjectMapper, Clock)` package-private test constructor.
- Produces: nested `PeriodicTask(int id, String userId, String contextToken, String scheduleType, String scheduleValue, String mode, String content, Instant nextRunAt, boolean enabled)`.
- Produces: package-private `create(...)`, `list(String userId)`, `cancel(String userId, Integer taskId)`, and `nextRun(...)`.

- [ ] **Step 1: Write failing tests for normalized next-run calculations**

Add a fixed clock at `2026-07-28T00:30:00Z`, which is `08:30` in Shanghai:

```java
private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
private static final Instant NOW = Instant.parse("2026-07-28T00:30:00Z");

private PeriodicReplyService service(Path file) {
    return new PeriodicReplyService(
            file,
            new ObjectMapper().findAndRegisterModules(),
            Clock.fixed(NOW, ZoneOffset.UTC));
}

@Test
void calculatesIntervalDailyAndWeeklyRuns() {
    PeriodicReplyService service = service(tempDir.resolve("tasks.json"));

    assertEquals(Instant.parse("2026-07-28T02:30:00Z"),
            service.nextRun("INTERVAL", "PT2H", NOW));
    assertEquals(Instant.parse("2026-07-29T00:00:00Z"),
            service.nextRun("DAILY", "08:00", NOW));
    assertEquals(Instant.parse("2026-08-03T01:00:00Z"),
            service.nextRun("WEEKLY", "MONDAY@09:00", NOW));
}

@Test
void rejectsUnsupportedOrNonFutureRules() {
    assertThrows(IllegalArgumentException.class,
            () -> service(tempDir.resolve("tasks.json"))
                    .nextRun("MONTHLY", "1@08:00", NOW));
    assertThrows(IllegalArgumentException.class,
            () -> service(tempDir.resolve("tasks.json"))
                    .nextRun("INTERVAL", "PT0S", NOW));
}
```

- [ ] **Step 2: Run the focused tests and verify failure**

Run:

```powershell
.\mvnw.cmd -Dtest=PeriodicReplyServiceTest test
```

Expected: compilation fails because `PeriodicReplyService` does not exist.

- [ ] **Step 3: Add the task record, constants, constructor, and time calculation**

Create the class with these exact core declarations:

```java
@Slf4j
@Service
public class PeriodicReplyService {
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    static final int MAX_TASKS_PER_USER = 20;

    public record PeriodicTask(
            int id,
            String userId,
            String contextToken,
            String scheduleType,
            String scheduleValue,
            String mode,
            String content,
            Instant nextRunAt,
            boolean enabled) {}

    private final Path taskFile;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Object lock = new Object();
    private final List<PeriodicTask> tasks = new ArrayList<>();

    public PeriodicReplyService(
            @Value("${ai.periodic-reply.file:./data/periodic-replies.json}") String file,
            ObjectMapper objectMapper) {
        this(Path.of(file), objectMapper, Clock.systemUTC());
    }

    PeriodicReplyService(Path file, ObjectMapper objectMapper, Clock clock) {
        this.taskFile = file.toAbsolutePath();
        this.objectMapper = objectMapper;
        this.clock = clock;
        load();
    }
}
```

Implement `nextRun(String scheduleType, String scheduleValue, Instant after)` using:

- `Duration.parse(scheduleValue)` for `INTERVAL`, rejecting zero/negative values.
- `LocalTime.parse(scheduleValue)` for `DAILY`, choosing today only when strictly after `after`.
- `DayOfWeek.valueOf(...)` and `TemporalAdjusters.nextOrSame(...)` for `WEEKLY`, moving one week when the candidate is not strictly after `after`.
- `ZonedDateTime.of(..., ZONE).toInstant()` for calendar conversion.

- [ ] **Step 4: Add failing CRUD, file, restart, limit, and cancellation tests**

```java
@Test
void createsPersistsReloadsListsAndCancelsTasks() {
    Path file = tempDir.resolve("periodic-replies.json");
    PeriodicReplyService first = service(file);

    PeriodicTask created = first.create(
            "user-a", "secret-token", "DAILY", "08:00",
            "FIXED", "提醒吃药");

    assertEquals(1, created.id());
    assertEquals(1, first.list("user-a").size());
    assertFalse(Files.readString(file).isBlank());

    PeriodicReplyService restarted = service(file);
    assertEquals(created, restarted.list("user-a").getFirst());
    assertTrue(restarted.cancel("user-a", null).contains("已取消任务 1"));
    assertTrue(restarted.list("user-a").isEmpty());
}

@Test
void requiresIdWhenUserHasMultipleTasks() {
    PeriodicReplyService service = service(tempDir.resolve("tasks.json"));
    service.create("u", "t", "DAILY", "08:00", "FIXED", "a");
    service.create("u", "t", "DAILY", "09:00", "FIXED", "b");

    assertTrue(service.cancel("u", null).contains("请指定任务编号"));
    assertEquals(2, service.list("u").size());
}

@Test
void enforcesTwentyTaskLimitPerUser() {
    PeriodicReplyService service = service(tempDir.resolve("tasks.json"));
    for (int i = 0; i < 20; i++) {
        service.create("u", "t", "DAILY", "08:00", "FIXED", "x" + i);
    }
    assertThrows(IllegalStateException.class,
            () -> service.create("u", "t", "DAILY", "09:00", "FIXED", "overflow"));
}
```

- [ ] **Step 5: Implement validation, CRUD, and atomic JSON persistence**

Use a versioned private snapshot record inside the same class:

```java
private static final int FILE_VERSION = 1;
private record TaskSnapshot(int version, List<PeriodicTask> tasks) {}
```

Required behavior:

- Validate nonblank `userId`, `contextToken`, `content`.
- Accept only the exact uppercase enum-like strings in the global constraints.
- Call `nextRun(...)` before modifying memory.
- Allocate `max(existing user IDs) + 1`.
- Under `lock`, build a replacement list, persist that list, and only then replace `tasks`; a failed write must not leave memory ahead of disk.
- Save through a sibling temporary file and `Files.move` with `ATOMIC_MOVE, REPLACE_EXISTING`, falling back to `REPLACE_EXISTING`.
- Missing file loads empty.
- Invalid file logs only path and exception type, never serialized data, and loads empty.
- `list` returns `List.copyOf(...)`.
- `cancel(userId, null)` cancels only when exactly one active task exists.

- [ ] **Step 6: Run the focused test class**

Run:

```powershell
.\mvnw.cmd -Dtest=PeriodicReplyServiceTest test
```

Expected: all Task 1 tests pass.

- [ ] **Step 7: Commit the persistent CRUD slice**

```powershell
git add src/main/java/com/demo/demo/Service/PeriodicReplyService.java src/test/java/com/demo/demo/Service/PeriodicReplyServiceTest.java
git commit -m "feat: persist periodic reply tasks"
```

---

### Task 2: Agent tools, scan loop, and fixed/dynamic triggers

**Files:**
- Modify: `src/main/java/com/demo/demo/Service/PeriodicReplyService.java`
- Modify: `src/test/java/com/demo/demo/Service/PeriodicReplyServiceTest.java`

**Interfaces:**
- Consumes: Task 1 CRUD and `PeriodicTask`.
- Produces: `createPeriodicReply(...)`, `listPeriodicReplies(ToolContext)`, `cancelPeriodicReply(Integer, ToolContext)` Agent tools.
- Produces: `configure(BooleanSupplier, Function<PeriodicTask, String>, TaskSender)`.
- Produces: package-private `scanDueTasks()` and public `activeTaskSummary(String)`.

- [ ] **Step 1: Write failing tool-context and summary tests**

Construct `ToolContext` with trusted values:

```java
private ToolContext toolContext(String userId, String contextToken) {
    return new ToolContext(Map.of(
            "user_id", userId,
            "context_token", contextToken));
}

@Test
void toolUsesTrustedContextAndSummaryHidesToken() {
    PeriodicReplyService service = service(tempDir.resolve("tasks.json"));

    String result = service.createPeriodicReply(
            "DAILY", "08:00", "FIXED", "提醒吃药",
            toolContext("user-a", "secret-token"));

    assertTrue(result.contains("任务 1"));
    assertTrue(service.activeTaskSummary("user-a").contains("每天 08:00"));
    assertFalse(service.activeTaskSummary("user-a").contains("secret-token"));
}
```

Reflect over all three public tool methods and assert each has `@Tool`; assert `ToolContext` is the last parameter so identity is not supplied by the model.

- [ ] **Step 2: Run the focused tests and verify failure**

Run:

```powershell
.\mvnw.cmd -Dtest=PeriodicReplyServiceTest test
```

Expected: compilation fails because the tool and summary methods do not exist.

- [ ] **Step 3: Implement the three tools and formatted summaries**

Use exact signatures:

```java
@Tool(description = "创建周期回复任务。scheduleType 仅可为 INTERVAL、DAILY、WEEKLY；"
        + "scheduleValue 示例 PT2H、08:00、MONDAY@09:00；mode 仅可为 FIXED 或 AGENT。")
public String createPeriodicReply(
        String scheduleType,
        String scheduleValue,
        String mode,
        String content,
        ToolContext toolContext)

@Tool(description = "查看当前微信用户的所有有效周期回复任务。")
public String listPeriodicReplies(ToolContext toolContext)

@Tool(description = "取消当前微信用户的周期回复任务。只有一个任务时 taskId 可省略；多个任务时必须指定。")
public String cancelPeriodicReply(Integer taskId, ToolContext toolContext)
```

Extract `user_id` and `context_token` only from `toolContext.getContext()`. Never accept them as model-visible string arguments. Return concise Chinese confirmations and lists. `activeTaskSummary` must return an empty string when the user has no active tasks.

- [ ] **Step 4: Write failing fixed, dynamic, offline, and overdue trigger tests**

Use callback captures rather than real Bot or Agent:

```java
@Test
void triggersFixedAndDynamicTasksAndAdvancesBeforeSending() {
    MutableClock clock = new MutableClock(NOW);
    PeriodicReplyService service = service(tempDir.resolve("tasks.json"), clock);
    List<String> sent = new ArrayList<>();
    service.configure(
            () -> true,
            task -> "动态结果",
            (userId, contextToken, text) -> sent.add(userId + ":" + text));

    service.create("u", "t", "INTERVAL", "PT1H", "FIXED", "固定内容");
    service.create("u", "t", "INTERVAL", "PT1H", "AGENT", "生成内容");
    clock.advance(Duration.ofHours(1));
    service.scanDueTasks();

    assertEquals(List.of("u:固定内容", "u:动态结果"), sent);
    assertTrue(service.list("u").stream().allMatch(t -> t.nextRunAt().isAfter(clock.instant())));
}

@Test
void offlineBotLeavesTaskDueForRetry() {
    MutableClock clock = new MutableClock(NOW);
    PeriodicReplyService service = service(tempDir.resolve("tasks.json"), clock);
    service.configure(() -> false, task -> fail(), (u, t, text) -> fail());
    PeriodicTask created = service.create(
            "u", "t", "INTERVAL", "PT1H", "FIXED", "content");
    clock.advance(Duration.ofHours(1));

    service.scanDueTasks();

    assertEquals(created.nextRunAt(), service.list("u").getFirst().nextRunAt());
}
```

Add this helper overload and a mutable test clock:

```java
private PeriodicReplyService service(Path file, Clock clock) {
    return new PeriodicReplyService(
            file, new ObjectMapper().findAndRegisterModules(), clock);
}

private static final class MutableClock extends Clock {
    private Instant instant;

    private MutableClock(Instant instant) {
        this.instant = instant;
    }

    void advance(Duration duration) {
        instant = instant.plus(duration);
    }

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return instant; }
}
```

Add `overdueTaskFiresOnceAndAdvancesToFuture()`: create an hourly task, advance the mutable clock by five hours, call `scanDueTasks()` once, assert exactly one send, and assert the stored `nextRunAt` is strictly after the mutable clock's instant.

- [ ] **Step 5: Implement callbacks, at-most-once scan, and lifecycle**

Define one nested functional interface rather than another production file:

```java
@FunctionalInterface
public interface TaskSender {
    void send(String userId, String contextToken, String text);
}
```

Store:

```java
private volatile BooleanSupplier botLoggedIn = () -> false;
private volatile Function<PeriodicTask, String> dynamicGenerator = task -> null;
private volatile TaskSender sender = (userId, contextToken, text) -> {};
private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(...);
```

Implement `configure(...)` with `Objects.requireNonNull`. Add `@PostConstruct start()` using `scheduleWithFixedDelay(this::scanSafely, 30, 30, TimeUnit.SECONDS)` and `@PreDestroy stop()` using `shutdownNow()`.

`scanDueTasks()` must:

1. Return without mutation when `botLoggedIn.getAsBoolean()` is false.
2. Under `lock`, find due tasks, calculate each next future run, persist the full replacement list, then replace memory.
3. Release `lock`.
4. For each due snapshot, send fixed content or call `dynamicGenerator`.
5. Substitute `本次周期任务生成失败，请稍后重试` when dynamic output is null/blank or generation throws.
6. Catch each task's exception separately and log only masked user ID, task ID, and exception type.

- [ ] **Step 6: Add concurrency and logging-privacy tests**

Create 20 concurrent tasks for one user through an executor, await all futures, reload the JSON, and assert 20 distinct IDs. Attach a Logback `ListAppender` and assert logs do not contain `secret-token` or a unique task body.

- [ ] **Step 7: Run the focused tests**

Run:

```powershell
.\mvnw.cmd -Dtest=PeriodicReplyServiceTest test
```

Expected: all service tests pass without real network calls or sleeping.

- [ ] **Step 8: Commit the tool and scheduler slice**

```powershell
git add src/main/java/com/demo/demo/Service/PeriodicReplyService.java src/test/java/com/demo/demo/Service/PeriodicReplyServiceTest.java
git commit -m "feat: schedule periodic replies"
```

---

### Task 3: Merge active tasks into long-term Agent context

**Files:**
- Modify: `src/main/java/com/demo/demo/Service/memory/MemoryAgentHook.java`
- Create: `src/test/java/com/demo/demo/Service/memory/MemoryAgentHookTest.java`

**Interfaces:**
- Consumes: `PeriodicReplyService.activeTaskSummary(String)`.
- Produces: `MemoryAgentHook(VectorMemoryStore, PeriodicReplyService)`.
- Preserves: `MemoryAgentHook.MEMORY_CONTEXT_KEY`.
- Produces: trusted `user_id` and optional `context_token` state entries for Spring AI `ToolContext`.

- [ ] **Step 1: Write failing independent-source tests**

Mock both dependencies. Build the state and config exactly as follows:

```java
OverAllState state = new OverAllState(Map.of(
        "messages", List.of(new UserMessage("hello"))));
RunnableConfig config = RunnableConfig.builder()
        .addMetadata("user_id", "u")
        .addMetadata("context_token", "secret-token")
        .build();

@Test
void includesPeriodicTasksWhenVectorLookupFails() {
    when(vectorMemoryStore.retrieveRelevant("u", "hello"))
            .thenThrow(new RuntimeException("embedding unavailable"));
    when(periodicReplyService.activeTaskSummary("u"))
            .thenReturn("【当前有效周期任务】\n- 任务1：每天 08:00");

    Map<String, Object> result = hook.beforeAgent(state, config).join();

    assertTrue(result.get(MEMORY_CONTEXT_KEY).toString().contains("任务1"));
}

@Test
void includesVectorMemoryWhenPeriodicLookupFails() {
    when(vectorMemoryStore.retrieveRelevant("u", "hello"))
            .thenReturn(List.of("用户喜欢简洁回复"));
    when(periodicReplyService.activeTaskSummary("u"))
            .thenThrow(new RuntimeException("file unavailable"));

    Map<String, Object> result = hook.beforeAgent(state, config).join();

    assertTrue(result.get(MEMORY_CONTEXT_KEY).toString().contains("用户喜欢简洁回复"));
}
```

- [ ] **Step 2: Write a failing trusted-context propagation test**

```java
@Test
void exposesTrustedIdentityToModelAndToolContext() {
    Map<String, Object> result = hook.beforeAgent(state, config).join();

    assertEquals("u", result.get("user_id"));
    assertEquals("secret-token", result.get("context_token"));
}
```

- [ ] **Step 3: Run the Hook test and verify failure**

Run:

```powershell
.\mvnw.cmd -Dtest=MemoryAgentHookTest test
```

Expected: compilation fails because the constructor does not accept `PeriodicReplyService`.

- [ ] **Step 4: Implement independent retrieval and concatenation**

Change the constructor to:

```java
public MemoryAgentHook(
        VectorMemoryStore vectorMemoryStore,
        PeriodicReplyService periodicReplyService)
```

Start the returned update with the trusted `user_id` from `RunnableConfig.metadata()` and copy `context_token` only when it is a nonblank string. Retrieve vector memories and periodic summary in separate `try/catch` blocks. Add `memory_context` only when at least one memory section is nonblank. Return the trusted identity update even when both memory sources are empty. Keep logs content-free.

Add system guidance to the periodic section:

```text
这些任务是当前系统状态；不得虚构、重复创建或擅自取消。
```

- [ ] **Step 5: Run the Hook and existing memory tests**

Run:

```powershell
.\mvnw.cmd -Dtest=MemoryAgentHookTest,AIServiceMemoryTest,ConversationMemoryStoreTest test
```

Expected: all selected tests pass.

- [ ] **Step 6: Commit the memory integration**

```powershell
git add src/main/java/com/demo/demo/Service/memory/MemoryAgentHook.java src/test/java/com/demo/demo/Service/memory/MemoryAgentHookTest.java
git commit -m "feat: inject periodic tasks into agent memory"
```

---

### Task 4: Register tools and wire trusted WeChat context

**Files:**
- Modify: `src/main/java/com/demo/demo/Service/AIService.java`
- Modify: `src/main/java/com/demo/demo/controller/BotController.java`
- Modify: `src/test/java/com/demo/demo/Service/AIServiceMemoryTest.java`
- Modify: `src/test/java/com/demo/demo/Service/tool/ToolAnnotationTest.java`

**Interfaces:**
- Consumes: `PeriodicReplyService` tool object and enhanced `MemoryAgentHook`.
- Produces: `AIService.chat(String userId, String contextToken, String message)`.
- Preserves: `AIService.chat(String userId, String message)` as a delegating overload.

- [ ] **Step 1: Update constructor tests to require the periodic service**

Mock `PeriodicReplyService`, pass it to every direct `AIService` construction, and add a reflection assertion that all three periodic tool methods carry `@Tool`.

Add a test around the overload boundary:

```java
@Test
void legacyChatOverloadRemainsAvailable() throws Exception {
    assertNotNull(AIService.class.getMethod("chat", String.class, String.class));
    assertNotNull(AIService.class.getMethod(
            "chat", String.class, String.class, String.class));
}
```

- [ ] **Step 2: Run selected tests and verify failure**

Run:

```powershell
.\mvnw.cmd -Dtest=AIServiceMemoryTest,ToolRegistryTest test
```

Expected: failure because the new constructor/signature/tool registration is absent.

- [ ] **Step 3: Inject and register `PeriodicReplyService`**

Add a final field and constructor parameter. Register it with:

```java
.tools(ToolCallbacks.from(
        weatherTool, timeTool, imageGenerationTool, voiceReplyTool,
        webSearchTool, emailTool, periodicReplyService))
.hooks(trimHook, new MemoryAgentHook(vectorMemoryStore, periodicReplyService))
```

Do not change `trimHook`.

- [ ] **Step 4: Add the context-aware chat overload**

Keep compatibility:

```java
public String chat(String userId, String message) {
    return chat(userId, null, message);
}

public String chat(String userId, String contextToken, String message) {
    // retain current per-user locking and vector save behavior
}
```

Pass both trusted values through `RunnableConfig` metadata. `MemoryAgentHook` copies these values into Agent state, which is the context exposed to Spring AI tool callbacks:

```java
var builder = RunnableConfig.builder()
        .threadId(userId)
        .addMetadata("user_id", userId)
        .addMetadata("system_prompt", enhancedSystem);
if (contextToken != null && !contextToken.isBlank()) {
    builder.addMetadata("context_token", contextToken);
}
```

Never log the raw token.

- [ ] **Step 5: Wire controller calls and periodic callbacks**

In the shared auto-reply callback, change normal and image-derived Agent calls to:

```java
aiService.chat(fromUser, contextToken, text)
```

At the end of `initAutoReply()`, configure:

```java
periodicReplyService.configure(
        () -> multiBotManager.getDefaultBot().isLoggedIn(),
        task -> aiService.chat(task.userId(), task.contextToken(), task.content()),
        (userId, contextToken, content) ->
                multiBotManager.getDefaultBot().sendReply(userId, contextToken, content));
```

Inject `PeriodicReplyService` into `BotController` using the existing repository style.

- [ ] **Step 6: Run selected integration tests**

Run:

```powershell
.\mvnw.cmd -Dtest=AIServiceMemoryTest,ToolRegistryTest,UserMessageSerializationTest,VoiceMessageReplyTest,ImageAutoReplyTest test
```

Expected: all selected tests pass and no external calls occur.

- [ ] **Step 7: Commit Agent and controller wiring**

```powershell
git add src/main/java/com/demo/demo/Service/AIService.java src/main/java/com/demo/demo/controller/BotController.java src/test/java/com/demo/demo/Service/AIServiceMemoryTest.java src/test/java/com/demo/demo/Service/tool/ToolAnnotationTest.java
git commit -m "feat: wire periodic reply tools"
```

---

### Task 5: Configuration, regression verification, and manual handoff

**Files:**
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/resources/application-local.example.yml`
- Modify: `docs/wechat-test-guide.md`

**Interfaces:**
- Consumes: property `ai.periodic-reply.file`.
- Produces: environment override `PERIODIC_REPLY_FILE`.

- [ ] **Step 1: Add configuration**

Under `ai:` in `application.yml` add:

```yaml
  periodic-reply:
    file: ${PERIODIC_REPLY_FILE:./data/periodic-replies.json}
```

Add the non-secret local example:

```yaml
  periodic-reply:
    file: ./data/periodic-replies.json
```

- [ ] **Step 2: Add concise manual test cases**

Document these exact checks in `docs/wechat-test-guide.md`:

1. “每天 8 点提醒我吃药” creates a fixed task and returns its number.
2. “每周一 9 点总结本周计划并发给我” creates an Agent task.
3. “查看周期任务” lists both without internal tokens.
4. “取消任务 1” removes only task 1.
5. Restart reloads remaining tasks.
6. A due task while logged out waits; after login it sends once.
7. Verify long-delay sending manually because iLink `contextToken` lifetime is not proven by unit tests.

- [ ] **Step 3: Run focused tests**

Run:

```powershell
.\mvnw.cmd -Dtest=PeriodicReplyServiceTest,MemoryAgentHookTest,AIServiceMemoryTest,ToolRegistryTest test
```

Expected: all focused tests pass.

- [ ] **Step 4: Run the complete test suite**

Run:

```powershell
.\mvnw.cmd test
```

Expected: Maven exits with code 0 and reports no failures or errors.

- [ ] **Step 5: Build the application**

Run:

```powershell
.\mvnw.cmd clean package
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Inspect scope and whitespace**

Run:

```powershell
git diff --check
git status --short
git diff --stat
git diff --name-only
```

Expected:

- No whitespace errors.
- Exactly one new production Java class.
- No database migration, SQLite configuration, Quartz/cron dependency, or unrelated refactor.
- `MessagesModelHook trimHook` body remains unchanged.

- [ ] **Step 7: Commit configuration and documentation**

```powershell
git add src/main/resources/application.yml src/main/resources/application-local.example.yml docs/wechat-test-guide.md
git commit -m "docs: configure periodic replies"
```

- [ ] **Step 8: Record deferred manual verification**

In the implementation handoff, explicitly state that a real logged-in WeChat account must verify whether an old iLink `contextToken` remains usable across the longest intended reminder interval. Do not claim proactive long-delay delivery is proven until that check succeeds.
