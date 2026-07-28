# 周期回复 MVP 实施计划

> **供执行代理使用：** 必须使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans`，逐项实施本计划。各步骤使用复选框（`- [ ]`）跟踪进度。

**目标：** 在只新增一个生产类的前提下，实现持久化的间隔、每日和每周微信周期回复，并同时支持固定内容和 Agent 动态生成内容。

**架构：** 单个 `PeriodicReplyService` 负责内嵌任务记录、Agent 工具、JSON 持久化、调度和触发回调。`AIService` 注册工具并传递可信的用户/上下文元数据；`MemoryAgentHook` 在向量记忆旁加入有效任务摘要；`BotController` 将现有 Agent 和默认 Bot 操作接入回调。

**技术栈：** Java 21、Spring Boot、Spring AI Alibaba ReactAgent、Spring AI `@Tool`、Jackson、`java.time`、`ScheduledExecutorService`、JUnit 5、Mockito。

## 全局约束

- 只新增一个生产类：`src/main/java/com/demo/demo/Service/PeriodicReplyService.java`。
- 保持 `MessagesModelHook trimHook` 不变。
- 使用 `Asia/Shanghai`，只支持 `INTERVAL`、`DAILY` 和 `WEEKLY`。
- 支持 `FIXED` 和 `AGENT` 两种任务模式。
- MVP 只绑定 `MultiBotManager.getDefaultBot()`。
- JSON 是任务事实源；不新增数据表、数据源、Quartz、cron 库、分布式锁或解析器类。
- 每个用户最多保留 20 个有效任务。
- 禁止记录 `contextToken`、任务/消息正文、媒体参数或外部响应正文。
- 测试中模拟 Agent、iLink、嵌入模型和网络活动。

## 文件改动表

- 新建 `src/main/java/com/demo/demo/Service/PeriodicReplyService.java`：内嵌任务模型、工具、持久化、时间计算、扫描循环、回调和摘要。
- 新建 `src/test/java/com/demo/demo/Service/PeriodicReplyServiceTest.java`：确定性的服务、持久化、调度、并发和隐私测试。
- 修改 `src/main/java/com/demo/demo/Service/AIService.java`：注入并注册服务、传递可信工具上下文、构造增强后的记忆 Hook。
- 修改 `src/main/java/com/demo/demo/Service/memory/MemoryAgentHook.java`：独立合并向量记忆和有效周期任务摘要。
- 新建 `src/test/java/com/demo/demo/Service/memory/MemoryAgentHookTest.java`：验证两个独立记忆来源。
- 修改 `src/main/java/com/demo/demo/controller/BotController.java`：把 `contextToken` 传给 AI 调用并连接回调。
- 修改 `src/main/resources/application.yml`：配置任务文件。
- 修改 `src/main/resources/application-local.example.yml`：记录本地路径。

---

### 任务 1：任务持久化 CRUD 与日历时间计算

**文件：**
- 新建：`src/main/java/com/demo/demo/Service/PeriodicReplyService.java`
- 新建：`src/test/java/com/demo/demo/Service/PeriodicReplyServiceTest.java`

**接口：**
- 产出：包级可见的测试构造器 `PeriodicReplyService(Path, ObjectMapper, Clock)`。
- 产出：内嵌的 `PeriodicTask(int id, String userId, String contextToken, String scheduleType, String scheduleValue, String mode, String content, Instant nextRunAt, boolean enabled)`。
- 产出：包级可见的 `create(...)`、`list(String userId)`、`cancel(String userId, Integer taskId)` 和 `nextRun(...)`。

- [ ] **步骤 1：为规范化的下次执行时间计算编写失败测试**

加入固定时钟 `2026-07-28T00:30:00Z`，对应上海时间 `08:30`：

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

- [ ] **步骤 2：运行聚焦测试并确认失败**

运行：

```powershell
.\mvnw.cmd -Dtest=PeriodicReplyServiceTest test
```

预期：编译失败，因为 `PeriodicReplyService` 尚不存在。

- [ ] **步骤 3：加入任务记录、常量、构造器和时间计算**

使用以下精确的核心声明创建类：

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

按以下规则实现 `nextRun(String scheduleType, String scheduleValue, Instant after)`：

- `INTERVAL` 使用 `Duration.parse(scheduleValue)`，拒绝零值和负值。
- `DAILY` 使用 `LocalTime.parse(scheduleValue)`；只有当天候选时间严格晚于 `after` 时才选择当天。
- `WEEKLY` 使用 `DayOfWeek.valueOf(...)` 和 `TemporalAdjusters.nextOrSame(...)`；若候选时间不严格晚于 `after`，则推进一周。
- 日历时间转换使用 `ZonedDateTime.of(..., ZONE).toInstant()`。

- [ ] **步骤 4：加入 CRUD、文件、重启、上限和取消行为的失败测试**

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

- [ ] **步骤 5：实现校验、CRUD 和 JSON 原子持久化**

在同一个类中使用带版本号的私有快照记录：

```java
private static final int FILE_VERSION = 1;
private record TaskSnapshot(int version, List<PeriodicTask> tasks) {}
```

必需行为：

- 校验 `userId`、`contextToken`、`content` 均为非空白字符串。
- 只接受全局约束中精确的大写枚举式字符串。
- 修改内存之前调用 `nextRun(...)`。
- 编号分配为 `max(该用户已有任务编号) + 1`。
- 在 `lock` 内构造替换列表，先持久化该列表，再替换 `tasks`；写入失败时，内存状态不得领先磁盘。
- 先保存到同目录临时文件，再使用带 `ATOMIC_MOVE, REPLACE_EXISTING` 的 `Files.move`；不支持原子移动时退化为 `REPLACE_EXISTING`。
- 文件缺失时加载为空。
- 文件无效时只记录路径和异常类型，不记录序列化内容，并加载为空。
- `list` 返回 `List.copyOf(...)`。
- `cancel(userId, null)` 只在恰好存在一个有效任务时执行取消。

- [ ] **步骤 6：运行聚焦测试类**

运行：

```powershell
.\mvnw.cmd -Dtest=PeriodicReplyServiceTest test
```

预期：任务 1 的全部测试通过。

- [ ] **步骤 7：提交持久化 CRUD 切片**

```powershell
git add src/main/java/com/demo/demo/Service/PeriodicReplyService.java src/test/java/com/demo/demo/Service/PeriodicReplyServiceTest.java
git commit -m "feat: 持久化周期回复任务"
```

---

### 任务 2：Agent 工具、扫描循环及固定/动态触发

**文件：**
- 修改：`src/main/java/com/demo/demo/Service/PeriodicReplyService.java`
- 修改：`src/test/java/com/demo/demo/Service/PeriodicReplyServiceTest.java`

**接口：**
- 使用：任务 1 的 CRUD 和 `PeriodicTask`。
- 产出：Agent 工具 `createPeriodicReply(...)`、`listPeriodicReplies(ToolContext)`、`cancelPeriodicReply(Integer, ToolContext)`。
- 产出：`configure(BooleanSupplier, Function<PeriodicTask, String>, TaskSender)`。
- 产出：包级可见的 `scanDueTasks()` 和公开的 `activeTaskSummary(String)`。

- [ ] **步骤 1：编写工具上下文和摘要的失败测试**

用可信值构造 `ToolContext`：

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

反射检查三个公开工具方法，断言每个方法都有 `@Tool`；同时断言 `ToolContext` 是最后一个参数，确保身份信息不是由模型提供。

- [ ] **步骤 2：运行聚焦测试并确认失败**

运行：

```powershell
.\mvnw.cmd -Dtest=PeriodicReplyServiceTest test
```

预期：编译失败，因为工具方法和摘要方法尚不存在。

- [ ] **步骤 3：实现三个工具和格式化摘要**

使用以下精确签名：

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

只能从 `toolContext.getContext()` 提取 `user_id` 和 `context_token`。不得把它们作为模型可见的字符串参数接收。返回简洁的中文确认信息和列表。用户没有有效任务时，`activeTaskSummary` 必须返回空字符串。

- [ ] **步骤 4：编写固定、动态、离线和逾期触发的失败测试**

使用回调捕获结果，不调用真实 Bot 或 Agent：

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

加入以下辅助重载和可变测试时钟：

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

加入 `overdueTaskFiresOnceAndAdvancesToFuture()`：创建每小时任务，将可变时钟推进五小时，调用一次 `scanDueTasks()`，断言只发送一次，并断言保存后的 `nextRunAt` 严格晚于可变时钟当前时间。

- [ ] **步骤 5：实现回调、至多一次扫描语义和生命周期**

定义一个内嵌函数式接口，不再新增生产文件：

```java
@FunctionalInterface
public interface TaskSender {
    void send(String userId, String contextToken, String text);
}
```

保存以下字段：

```java
private volatile BooleanSupplier botLoggedIn = () -> false;
private volatile Function<PeriodicTask, String> dynamicGenerator = task -> null;
private volatile TaskSender sender = (userId, contextToken, text) -> {};
private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(...);
```

使用 `Objects.requireNonNull` 实现 `configure(...)`。加入 `@PostConstruct start()`，调用 `scheduleWithFixedDelay(this::scanSafely, 30, 30, TimeUnit.SECONDS)`；加入 `@PreDestroy stop()`，调用 `shutdownNow()`。

`scanDueTasks()` 必须：

1. 当 `botLoggedIn.getAsBoolean()` 为 false 时直接返回，不修改状态。
2. 在 `lock` 内查找到期任务，为每个任务计算未来的下次时间，持久化完整替换列表，然后替换内存状态。
3. 释放 `lock`。
4. 对每个到期快照发送固定内容，或调用 `dynamicGenerator`。
5. 动态输出为 null/空白或生成过程抛出异常时，改用“本次周期任务生成失败，请稍后重试”。
6. 分别捕获每个任务的异常，只记录掩码用户 ID、任务编号和异常类型。

- [ ] **步骤 6：加入并发和日志隐私测试**

通过执行器为同一用户并发创建 20 个任务，等待全部 Future 完成，重新加载 JSON，并断言存在 20 个不同编号。挂载 Logback `ListAppender`，断言日志不包含 `secret-token` 或具有唯一性的任务正文。

- [ ] **步骤 7：运行聚焦测试**

运行：

```powershell
.\mvnw.cmd -Dtest=PeriodicReplyServiceTest test
```

预期：全部服务测试通过，且没有真实网络调用或休眠等待。

- [ ] **步骤 8：提交工具和调度器切片**

```powershell
git add src/main/java/com/demo/demo/Service/PeriodicReplyService.java src/test/java/com/demo/demo/Service/PeriodicReplyServiceTest.java
git commit -m "feat: 调度周期回复"
```

---

### 任务 3：将有效任务合并进 Agent 长期上下文

**文件：**
- 修改：`src/main/java/com/demo/demo/Service/memory/MemoryAgentHook.java`
- 新建：`src/test/java/com/demo/demo/Service/memory/MemoryAgentHookTest.java`

**接口：**
- 使用：`PeriodicReplyService.activeTaskSummary(String)`。
- 产出：`MemoryAgentHook(VectorMemoryStore, PeriodicReplyService)`。
- 保持：`MemoryAgentHook.MEMORY_CONTEXT_KEY`。
- 产出：供 Spring AI `ToolContext` 使用的可信 `user_id` 和可选 `context_token` 状态项。

- [ ] **步骤 1：为独立数据源编写失败测试**

模拟两个依赖，并按以下方式精确构造状态和配置：

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

- [ ] **步骤 2：编写可信上下文传播的失败测试**

```java
@Test
void exposesTrustedIdentityToModelAndToolContext() {
    Map<String, Object> result = hook.beforeAgent(state, config).join();

    assertEquals("u", result.get("user_id"));
    assertEquals("secret-token", result.get("context_token"));
}
```

- [ ] **步骤 3：运行 Hook 测试并确认失败**

运行：

```powershell
.\mvnw.cmd -Dtest=MemoryAgentHookTest test
```

预期：编译失败，因为构造器尚不接收 `PeriodicReplyService`。

- [ ] **步骤 4：实现独立检索与拼接**

将构造器改为：

```java
public MemoryAgentHook(
        VectorMemoryStore vectorMemoryStore,
        PeriodicReplyService periodicReplyService)
```

返回的更新 Map 首先放入来自 `RunnableConfig.metadata()` 的可信 `user_id`；只有 `context_token` 是非空白字符串时才复制。分别在独立的 `try/catch` 块中检索向量记忆和周期任务摘要。仅当至少一个记忆区段非空白时加入 `memory_context`。即使两个记忆源均为空，也要返回可信身份更新。日志不得包含内容正文。

在周期任务区段加入系统指引：

```text
这些任务是当前系统状态；不得虚构、重复创建或擅自取消。
```

- [ ] **步骤 5：运行 Hook 和现有记忆测试**

运行：

```powershell
.\mvnw.cmd -Dtest=MemoryAgentHookTest,AIServiceMemoryTest,ConversationMemoryStoreTest test
```

预期：全部选定测试通过。

- [ ] **步骤 6：提交记忆集成**

```powershell
git add src/main/java/com/demo/demo/Service/memory/MemoryAgentHook.java src/test/java/com/demo/demo/Service/memory/MemoryAgentHookTest.java
git commit -m "feat: 将周期任务注入 Agent 记忆"
```

---

### 任务 4：注册工具并连接可信微信上下文

**文件：**
- 修改：`src/main/java/com/demo/demo/Service/AIService.java`
- 修改：`src/main/java/com/demo/demo/controller/BotController.java`
- 修改：`src/test/java/com/demo/demo/Service/AIServiceMemoryTest.java`
- 修改：`src/test/java/com/demo/demo/Service/tool/ToolAnnotationTest.java`

**接口：**
- 使用：`PeriodicReplyService` 工具对象和增强后的 `MemoryAgentHook`。
- 产出：`AIService.chat(String userId, String contextToken, String message)`。
- 保持：`AIService.chat(String userId, String message)`，作为委托重载。

- [ ] **步骤 1：更新构造器测试，要求传入周期服务**

模拟 `PeriodicReplyService`，把它传给每一处直接构造的 `AIService`，并加入反射断言，确认三个周期工具方法均带有 `@Tool`。

加入重载边界测试：

```java
@Test
void legacyChatOverloadRemainsAvailable() throws Exception {
    assertNotNull(AIService.class.getMethod("chat", String.class, String.class));
    assertNotNull(AIService.class.getMethod(
            "chat", String.class, String.class, String.class));
}
```

- [ ] **步骤 2：运行选定测试并确认失败**

运行：

```powershell
.\mvnw.cmd -Dtest=AIServiceMemoryTest,ToolRegistryTest test
```

预期：测试失败，因为新构造器、方法签名和工具注册尚不存在。

- [ ] **步骤 3：注入并注册 `PeriodicReplyService`**

加入 final 字段和构造器参数，并按以下方式注册：

```java
.tools(ToolCallbacks.from(
        weatherTool, timeTool, imageGenerationTool, voiceReplyTool,
        webSearchTool, emailTool, periodicReplyService))
.hooks(trimHook, new MemoryAgentHook(vectorMemoryStore, periodicReplyService))
```

不得修改 `trimHook`。

- [ ] **步骤 4：加入感知上下文的 chat 重载**

保持兼容：

```java
public String chat(String userId, String message) {
    return chat(userId, null, message);
}

public String chat(String userId, String contextToken, String message) {
    // 保留当前按用户加锁和向量记忆保存行为
}
```

通过 `RunnableConfig` 元数据传递两个可信值。`MemoryAgentHook` 将它们复制到 Agent 状态，该状态会作为上下文暴露给 Spring AI 工具回调：

```java
var builder = RunnableConfig.builder()
        .threadId(userId)
        .addMetadata("user_id", userId)
        .addMetadata("system_prompt", enhancedSystem);
if (contextToken != null && !contextToken.isBlank()) {
    builder.addMetadata("context_token", contextToken);
}
```

不得记录原始 token。

- [ ] **步骤 5：连接控制器调用和周期回调**

在共享自动回复回调中，把普通消息和图片派生的 Agent 调用改为：

```java
aiService.chat(fromUser, contextToken, text)
```

在 `initAutoReply()` 末尾进行以下配置：

```java
periodicReplyService.configure(
        () -> multiBotManager.getDefaultBot().isLoggedIn(),
        task -> aiService.chat(task.userId(), task.contextToken(), task.content()),
        (userId, contextToken, content) ->
                multiBotManager.getDefaultBot().sendReply(userId, contextToken, content));
```

按照仓库现有风格把 `PeriodicReplyService` 注入 `BotController`。

- [ ] **步骤 6：运行选定的集成测试**

运行：

```powershell
.\mvnw.cmd -Dtest=AIServiceMemoryTest,ToolRegistryTest,UserMessageSerializationTest,VoiceMessageReplyTest,ImageAutoReplyTest test
```

预期：全部选定测试通过，且不发生外部调用。

- [ ] **步骤 7：提交 Agent 和控制器连接改动**

```powershell
git add src/main/java/com/demo/demo/Service/AIService.java src/main/java/com/demo/demo/controller/BotController.java src/test/java/com/demo/demo/Service/AIServiceMemoryTest.java src/test/java/com/demo/demo/Service/tool/ToolAnnotationTest.java
git commit -m "feat: 接入周期回复工具"
```

---

### 任务 5：配置、回归验证和人工交接

**文件：**
- 修改：`src/main/resources/application.yml`
- 修改：`src/main/resources/application-local.example.yml`
- 修改：`docs/wechat-test-guide.md`

**接口：**
- 使用：配置项 `ai.periodic-reply.file`。
- 产出：环境变量覆盖项 `PERIODIC_REPLY_FILE`。

- [ ] **步骤 1：加入配置**

在 `application.yml` 的 `ai:` 下加入：

```yaml
  periodic-reply:
    file: ${PERIODIC_REPLY_FILE:./data/periodic-replies.json}
```

加入不含秘密信息的本地示例：

```yaml
  periodic-reply:
    file: ./data/periodic-replies.json
```

- [ ] **步骤 2：加入简洁的人工测试用例**

在 `docs/wechat-test-guide.md` 中记录以下精确检查：

1. “每天 8 点提醒我吃药”创建固定任务并返回任务编号。
2. “每周一 9 点总结本周计划并发给我”创建 Agent 动态任务。
3. “查看周期任务”列出两个任务，但不包含内部 token。
4. “取消任务 1”只删除任务 1。
5. 重启后重新加载剩余任务。
6. 用户未登录时到期任务保持等待；登录后只发送一次。
7. 人工验证长周期延迟发送，因为单元测试无法证明 iLink `contextToken` 的生命周期。

- [ ] **步骤 3：运行聚焦测试**

运行：

```powershell
.\mvnw.cmd -Dtest=PeriodicReplyServiceTest,MemoryAgentHookTest,AIServiceMemoryTest,ToolRegistryTest test
```

预期：全部聚焦测试通过。

- [ ] **步骤 4：运行完整测试套件**

运行：

```powershell
.\mvnw.cmd test
```

预期：Maven 以代码 0 退出，且没有失败或错误。

- [ ] **步骤 5：构建应用**

运行：

```powershell
.\mvnw.cmd clean package
```

预期：输出 `BUILD SUCCESS`。

- [ ] **步骤 6：检查范围和空白字符**

运行：

```powershell
git diff --check
git status --short
git diff --stat
git diff --name-only
```

预期：

- 没有空白字符错误。
- 恰好只新增一个生产 Java 类。
- 没有数据库迁移、SQLite 配置、Quartz/cron 依赖或无关重构。
- `MessagesModelHook trimHook` 方法体保持不变。

- [ ] **步骤 7：提交配置和文档**

```powershell
git add src/main/resources/application.yml src/main/resources/application-local.example.yml docs/wechat-test-guide.md
git commit -m "docs: 配置周期回复"
```

- [ ] **步骤 8：记录延期的人工验证**

在实施交接中明确说明：必须使用真实已登录微信账号，验证旧 iLink `contextToken` 在计划支持的最长提醒间隔后是否仍可使用。在该检查成功之前，不得声称已证明长延迟主动发送可用。
