# 周期性图文卡片推送 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在保留现有天气周期推送行为的前提下，支持用户通过自然语言创建自定义周期的主题图文卡片任务，并实现近期去重、图片加独立文案推送及分阶段幂等恢复。

**Architecture:** 将现有天气专用执行器改为公共执行器加 `ScheduledTaskHandlerRegistry`，天气和图文分别实现 Handler。调度任务使用受限结构化规则和通用 payload；图文任务使用独立无副作用内容 Agent、专属历史表和分阶段执行状态，底层复用 `ImageGenerationService` 与默认 Bot iLink 网关。

**Tech Stack:** Java 21、Spring Boot 3.4.5、Spring AI Alibaba 1.1.2.3、Spring AI 1.1.8、iLink SDK 1.0.1、Spring JDBC、SQLite、Jackson、JUnit 5、Mockito。

## Global Constraints

- 先阅读 `CLAUDE.md`、`docs/ARCHITECTURE.md`、`docs/TASKS.md` 和设计规格。
- 一次只执行一个 Task，不提前实现后续 Task。
- 使用 TDD：先写失败测试，确认失败原因，再写最小实现。
- 不升级 Java、Spring Boot、Spring AI Alibaba 或 iLink SDK。
- 不引入 JPA、消息队列、分布式锁、工作流引擎或原始 Cron 输入。
- MVP 只使用 `MultiBotManager.getDefaultBot()`。
- 模型不得提供或覆盖 owner、target ID、userId、contextToken。
- 图文任务最短执行间隔为 1 小时。
- 不把完整主题、文案、绘图 prompt、contextToken 或外部响应正文写入日志。
- 自动化测试必须 mock DashScope、文生图、iLink 和网络。
- 每个 Task 完成后运行聚焦测试、相关回归测试和 `git diff --check`。
- 不得自动执行 `git commit`；提交步骤只准备命令和推荐信息，必须等待用户授权。

---

## 文件结构

### 调度核心

- `Service/scheduling/execution/ScheduledTaskHandler.java`：任务类型执行接口。
- `Service/scheduling/execution/TaskHandlingResult.java`：成功、降级、失败结果。
- `Service/scheduling/execution/ScheduledTaskHandlerRegistry.java`：按任务类型选择 Handler。
- `Service/scheduling/execution/WeatherScheduledTaskHandler.java`：承接现有天气执行逻辑。

### 通用调度领域

- `Service/scheduling/domain/ScheduleKind.java`：受支持规则类型。
- `Service/scheduling/domain/ScheduleRule.java`：结构化调度规则。
- `Service/scheduling/domain/ScheduleRuleCodec.java`：规则 JSON 编解码。
- `Service/scheduling/domain/RecurrenceCalculator.java`：统一下一次时间计算。
- `Service/scheduling/domain/WeatherTaskPayload.java`：天气 payload。
- `Service/scheduling/domain/TaskPayloadCodec.java`：任务 payload JSON 编解码。

### 图文卡片

- `Service/scheduling/creative/CreateCreativeCardTaskCommand.java`
- `Service/scheduling/creative/CreativeCardTaskPayload.java`
- `Service/scheduling/creative/CreateCreativeCardTaskTool.java`
- `Service/scheduling/creative/CreativeCardDraft.java`
- `Service/scheduling/creative/CardCopyAgent.java`
- `Service/scheduling/creative/DashScopeCardCopyAgent.java`
- `Service/scheduling/creative/CreativeCardSimilarityService.java`
- `Service/scheduling/creative/CreativeCardRun.java`
- `Service/scheduling/creative/CreativeCardHistory.java`
- `Service/scheduling/creative/CreativeCardRepository.java`
- `Service/scheduling/creative/JdbcCreativeCardRepository.java`
- `Service/scheduling/creative/CreativeCardScheduledTaskHandler.java`

### 媒体推送

- 扩展 `MessagePushGateway` 和 `ILinkMessagePushGateway` 的图片方法。
- 为 `BotInstance` 增加可观测图片发送方法，保留现有 `sendImageReply(...)`。

---

### Task 1: 提取任务 Handler Registry，保持天气行为不变

**Files:**

- Create: `src/main/java/com/demo/demo/Service/scheduling/execution/ScheduledTaskHandler.java`
- Create: `src/main/java/com/demo/demo/Service/scheduling/execution/TaskHandlingResult.java`
- Create: `src/main/java/com/demo/demo/Service/scheduling/execution/ScheduledTaskHandlerRegistry.java`
- Create: `src/main/java/com/demo/demo/Service/scheduling/execution/WeatherScheduledTaskHandler.java`
- Modify: `src/main/java/com/demo/demo/Service/scheduling/execution/ScheduledTaskExecutionService.java:20`
- Modify: `src/main/java/com/demo/demo/Service/scheduling/domain/ExecutionStatus.java:16`
- Modify: `src/main/java/com/demo/demo/Service/scheduling/persistence/TaskExecutionRepository.java:15`
- Modify: `src/main/java/com/demo/demo/Service/scheduling/persistence/JdbcTaskExecutionRepository.java:18`
- Test: `src/test/java/com/demo/demo/Service/scheduling/execution/ScheduledTaskHandlerRegistryTest.java`
- Test: `src/test/java/com/demo/demo/Service/scheduling/execution/WeatherScheduledTaskHandlerTest.java`
- Modify test: `src/test/java/com/demo/demo/Service/scheduling/execution/ScheduledTaskExecutionServiceTest.java:20`
- Modify test: `src/test/java/com/demo/demo/Service/scheduling/persistence/JdbcTaskExecutionRepositoryTest.java:20`

**Interfaces:**

- Consumes: `ScheduledTask`, `TaskExecution`, `WeatherService`, `ScheduledContentAgent`, `WeatherMessageTemplateFormatter`, `MessagePushGateway`.
- Produces:

```java
public interface ScheduledTaskHandler {
    String taskType();
    TaskHandlingResult handle(ScheduledTask task, TaskExecution execution);
}

public record TaskHandlingResult(Status status, String errorCode) {
    public enum Status { SUCCEEDED, DEGRADED, FAILED }
    public static TaskHandlingResult succeeded();
    public static TaskHandlingResult degraded(String errorCode);
    public static TaskHandlingResult failed(String errorCode);
}
```

- [ ] **Step 1: 写 Registry 失败测试**

```java
@Test
void shouldResolveHandlerByTaskType() {
    ScheduledTaskHandler weather = handler("DAILY_WEATHER");
    var registry = new ScheduledTaskHandlerRegistry(List.of(weather));
    assertSame(weather, registry.require("DAILY_WEATHER"));
}

@Test
void shouldRejectDuplicateTaskTypes() {
    assertThrows(IllegalStateException.class,
            () -> new ScheduledTaskHandlerRegistry(List.of(
                    handler("DAILY_WEATHER"), handler("DAILY_WEATHER"))));
}
```

- [ ] **Step 2: 运行测试并确认因类不存在而失败**

Run:

```powershell
mvnw.cmd -Dtest=ScheduledTaskHandlerRegistryTest test
```

Expected: 编译失败，缺少 `ScheduledTaskHandlerRegistry`。

- [ ] **Step 3: 实现接口、结果对象和 Registry**

Registry 构造时建立不可变 `Map<String, ScheduledTaskHandler>`；空类型和重复类型直接抛出 `IllegalStateException`，`require(String)` 对未知类型抛出 `SchedulingException("Unsupported task type: ...")`。

- [ ] **Step 4: 为天气 Handler 写现有行为测试**

覆盖：

```java
handleShouldQueryWeatherGenerateTextAndPush();
handleShouldUseTemplateWhenAgentReturnsEmpty();
handleShouldReturnFailureWhenWeatherFails();
handleShouldReturnFailureWhenPushFails();
```

- [ ] **Step 5: 把 `ScheduledTaskExecutionService` 第 4～6 阶段原样搬入天气 Handler**

`WeatherScheduledTaskHandler.taskType()` 返回 `ScheduledTask.TASK_TYPE_DAILY_WEATHER`。公共执行器只执行：

```java
ScheduledTaskHandler handler = handlerRegistry.require(task.taskType());
TaskHandlingResult result = handler.handle(task, execution);
switch (result.status()) {
    case SUCCEEDED -> execRepo.markSucceeded(executionId, now);
    case DEGRADED -> execRepo.markDegraded(executionId, result.errorCode(), now);
    case FAILED -> handleFailure(executionId, result.errorCode(), now, execution.attemptCount());
}
```

- [ ] **Step 6: 增加 `DEGRADED` 终态持久化**

```java
boolean markDegraded(String executionId, String errorCode, Instant finishedAt);
```

SQL 只允许 `RUNNING -> DEGRADED`。为非法状态转换增加失败断言。

- [ ] **Step 7: 运行聚焦和天气回归测试**

```powershell
mvnw.cmd -Dtest=ScheduledTaskHandlerRegistryTest,WeatherScheduledTaskHandlerTest,ScheduledTaskExecutionServiceTest,JdbcTaskExecutionRepositoryTest,ScheduledWeatherPushIntegrationTest test
git diff --check
```

Expected: 全部通过，天气任务发送文本的行为不变。

- [ ] **Step 8: 准备提交**

```powershell
git add src/main/java/com/demo/demo/Service/scheduling/execution src/main/java/com/demo/demo/Service/scheduling/domain/ExecutionStatus.java src/main/java/com/demo/demo/Service/scheduling/persistence/TaskExecutionRepository.java src/main/java/com/demo/demo/Service/scheduling/persistence/JdbcTaskExecutionRepository.java src/test/java/com/demo/demo/Service/scheduling
git commit -m "refactor: route scheduled tasks through handlers"
```

仅在用户授权后执行提交。

---

### Task 2: 建立受限结构化调度规则

**Files:**

- Create: `src/main/java/com/demo/demo/Service/scheduling/domain/ScheduleKind.java`
- Create: `src/main/java/com/demo/demo/Service/scheduling/domain/ScheduleRule.java`
- Create: `src/main/java/com/demo/demo/Service/scheduling/domain/ScheduleRuleCodec.java`
- Create: `src/main/java/com/demo/demo/Service/scheduling/domain/RecurrenceCalculator.java`
- Modify: `src/main/java/com/demo/demo/Service/scheduling/domain/NextRunCalculator.java:23`
- Test: `src/test/java/com/demo/demo/Service/scheduling/domain/ScheduleRuleTest.java`
- Test: `src/test/java/com/demo/demo/Service/scheduling/domain/ScheduleRuleCodecTest.java`
- Test: `src/test/java/com/demo/demo/Service/scheduling/domain/RecurrenceCalculatorTest.java`
- Modify test: `src/test/java/com/demo/demo/Service/scheduling/domain/NextRunCalculatorTest.java`

**Interfaces:**

- Consumes: Java Time、Jackson `ObjectMapper`。
- Produces:

```java
public enum ScheduleKind { ONCE, DAILY, WEEKLY, MONTHLY, INTERVAL }

public record ScheduleRule(
        ScheduleKind kind,
        LocalDateTime onceAt,
        LocalTime localTime,
        Set<DayOfWeek> daysOfWeek,
        Integer dayOfMonth,
        Integer intervalValue,
        String intervalUnit) {
    public static ScheduleRule once(LocalDateTime at);
    public static ScheduleRule daily(LocalTime time);
    public static ScheduleRule weekly(Set<DayOfWeek> days, LocalTime time);
    public static ScheduleRule monthly(int dayOfMonth, LocalTime time);
    public static ScheduleRule intervalHours(int hours);
    public static ScheduleRule intervalDays(int days);
}

public final class RecurrenceCalculator {
    public static Optional<Instant> next(
            ScheduleRule rule, ZoneId zoneId, Instant after);
}
```

- [ ] **Step 1: 写规则校验失败测试**

覆盖：

```java
weeklyShouldRequireAtLeastOneDay();
monthlyShouldRejectDayAbove31();
intervalShouldRejectLessThanOneHour();
dailyShouldRequireLocalTime();
```

- [ ] **Step 2: 运行并确认测试因类型不存在而失败**

```powershell
mvnw.cmd -Dtest=ScheduleRuleTest test
```

- [ ] **Step 3: 实现 `ScheduleRule` 工厂和构造校验**

`INTERVAL` 只接受：

```text
intervalUnit = HOURS，intervalValue >= 1
intervalUnit = DAYS，intervalValue >= 1
```

其他字段必须与 `kind` 匹配，多余字段拒绝而不是忽略。

- [ ] **Step 4: 写下一次时间计算测试**

至少覆盖：

```java
onceShouldReturnEmptyAfterExecutionTime();
dailyShouldUseRequestedZone();
weeklyShouldChooseNextConfiguredWeekday();
weeklyShouldSupportWeekdays();
monthlyShouldSkipMonthWithoutRequestedDay();
intervalHoursShouldAdvanceFromPreviousSchedule();
springDstGapShouldMoveToFirstValidLocalTime();
autumnDstOverlapShouldChooseEarlierOffset();
```

- [ ] **Step 5: 实现 `RecurrenceCalculator.next(...)`**

月度规则从 `after` 所在月份开始逐月寻找有效日期；不存在的日期跳过该月。`ONCE` 已经过期时返回 `Optional.empty()`，其余规则必须返回严格晚于 `after` 的 UTC 时间。

- [ ] **Step 6: 实现稳定 JSON 编解码**

```java
public final class ScheduleRuleCodec {
    public String write(ScheduleRule rule);
    public ScheduleRule read(String json);
}
```

未知字段、未知 `kind` 和非法组合抛出 `SchedulingException("Invalid schedule rule")`，异常消息不包含原始 JSON。

- [ ] **Step 7: 让旧每日计算委托新计算器**

`NextRunCalculator.nextDailyRun(...)` 保持签名，内部调用：

```java
return RecurrenceCalculator.next(
        ScheduleRule.daily(localTime), zoneId, after).orElseThrow();
```

- [ ] **Step 8: 运行领域测试**

```powershell
mvnw.cmd -Dtest=ScheduleRuleTest,ScheduleRuleCodecTest,RecurrenceCalculatorTest,NextRunCalculatorTest test
git diff --check
```

- [ ] **Step 9: 准备提交**

推荐提交信息：

```text
feat: add validated recurrence rules
```

仅在用户授权后执行提交。

---

### Task 3: 将天气专用任务表迁移为通用任务表

**Files:**

- Create: `src/main/java/com/demo/demo/Service/scheduling/domain/WeatherTaskPayload.java`
- Create: `src/main/java/com/demo/demo/Service/scheduling/domain/TaskPayloadCodec.java`
- Modify: `src/main/java/com/demo/demo/Service/scheduling/domain/ScheduledTask.java:18`
- Modify: `src/main/java/com/demo/demo/Service/scheduling/domain/ScheduledTaskStatus.java:9`
- Modify: `src/main/java/com/demo/demo/Service/scheduling/application/ScheduledTaskSummary.java:12`
- Modify: `src/main/java/com/demo/demo/Service/scheduling/application/ScheduledTaskService.java:22`
- Modify: `src/main/java/com/demo/demo/Service/scheduling/tool/ManageScheduledTaskTool.java:62`
- Modify: `src/main/java/com/demo/demo/Service/scheduling/execution/WeatherScheduledTaskHandler.java`
- Modify: `src/main/java/com/demo/demo/Service/scheduling/persistence/SchedulingSchemaInitializer.java:18`
- Modify: `src/main/java/com/demo/demo/Service/scheduling/persistence/JdbcScheduledTaskRepository.java:16`
- Modify: `src/main/java/com/demo/demo/Service/scheduling/runtime/ScheduledTaskScanner.java:30`
- Modify tests: `src/test/java/com/demo/demo/Service/scheduling/persistence/SchedulingSchemaInitializerTest.java`
- Modify tests: `src/test/java/com/demo/demo/Service/scheduling/persistence/JdbcScheduledTaskRepositoryTest.java`
- Modify tests: `src/test/java/com/demo/demo/Service/scheduling/application/ScheduledTaskServiceTest.java`
- Modify tests: `src/test/java/com/demo/demo/Service/scheduling/tool/ManageScheduledTaskToolTest.java`
- Modify tests: `src/test/java/com/demo/demo/Service/scheduling/runtime/ScheduledTaskScannerTest.java`
- Modify test: `src/test/java/com/demo/demo/integration/ScheduledWeatherPushIntegrationTest.java`

**Interfaces:**

- Consumes: `ScheduleRuleCodec`, `RecurrenceCalculator`。
- Produces:

```java
public record ScheduledTask(
        Long id,
        String taskId,
        String ownerTargetId,
        String taskType,
        ScheduledTaskStatus status,
        ScheduleKind scheduleKind,
        String scheduleExpression,
        String timeZone,
        String payload,
        Instant nextRunAt,
        int version,
        Instant createdAt,
        Instant updatedAt) {}

public record WeatherTaskPayload(String location) {}
```

- [ ] **Step 1: 写旧天气数据迁移测试**

测试先按旧 schema 插入：

```sql
INSERT INTO scheduled_task
(task_id, owner_target_id, task_type, status, location, local_time,
 time_zone, payload, next_run_at, version, created_at, updated_at)
VALUES ('legacy-1','target-1','DAILY_WEATHER','ACTIVE','杭州','08:00',
        'Asia/Shanghai','{}',1000,0,100,100);
```

执行初始化后断言：

- 行数仍为 1。
- `task_id/owner/status/version/next_run_at` 不变。
- `schedule_kind = DAILY`。
- `schedule_expression` 可解码为每日 08:00。
- payload 可解码为 `WeatherTaskPayload("杭州")`。
- 新表不存在 `location`、`local_time` 列。

- [ ] **Step 2: 运行迁移测试并确认因新列不存在而失败**

```powershell
mvnw.cmd -Dtest=SchedulingSchemaInitializerTest test
```

- [ ] **Step 3: 实现幂等 SQLite 表重建**

初始化器通过 `PRAGMA table_info(scheduled_task)` 判断旧结构；只在发现 `location` 或缺少 `schedule_kind` 时执行：

```text
BEGIN
CREATE TABLE scheduled_task_v2 (...)
INSERT ... SELECT ... FROM scheduled_task
核对 COUNT(*)
DROP TABLE scheduled_task
ALTER TABLE scheduled_task_v2 RENAME TO scheduled_task
重建索引
COMMIT
```

迁移 SQL 不输出 payload 或用户数据。迁移中断必须回滚，重复启动可再次安全执行。

- [ ] **Step 4: 修改领域对象和 JDBC 映射**

所有 SELECT 明确列出：

```text
id, task_id, owner_target_id, task_type, status,
schedule_kind, schedule_expression, time_zone, payload,
next_run_at, version, created_at, updated_at
```

不得使用 `SELECT *`。

- [ ] **Step 5: 修改天气创建、查重、恢复与执行**

- 创建天气任务时写入 `ScheduleRule.daily(cmd.localTime())` 和 `WeatherTaskPayload`。
- 重复判断比较解码后的 location、规则和时区。
- `resume(...)` 使用 `RecurrenceCalculator`。
- Scanner 使用任务的 `scheduleExpression` 计算下一次执行。
- `ONCE` 创建唯一执行记录后，把任务条件更新为新增终态 `COMPLETED`，不再被扫描。
- 公共执行器允许已经创建的 ONCE execution 执行或重试：任务必须为 `COMPLETED`、规则必须是 `ONCE`，且 `execution.scheduledFor()` 必须等于任务保存的 `nextRunAt`。
- 普通 `PAUSED`、`CANCELED`、`COMPLETED` 周期任务仍不得执行。
- Weather Handler 从 payload 读取 location。

- [ ] **Step 6: 修改公开任务摘要**

```java
public record ScheduledTaskSummary(
        String taskId,
        String taskType,
        ScheduledTaskStatus status,
        String scheduleDescription,
        String timeZone,
        Instant nextRunAt) {}
```

摘要不得返回原始 payload、内部 owner 或版本。

`ManageScheduledTaskTool.handleList(...)` 改为按通用摘要输出：

```java
String.format("- [%s] %s %s：%s（%s）",
        t.taskId(), t.status(), t.taskType(),
        t.scheduleDescription(), t.timeZone());
```

空列表提示改为同时举例天气任务和图文卡片任务，不再声称系统只有每日天气。

为一次性任务增加：

```java
public enum ScheduledTaskStatus {
    ACTIVE, PAUSED, CANCELED, COMPLETED
}
```

`COMPLETED` 不允许恢复为 ACTIVE；用户可以查询或取消清理，但不能再次调度同一个一次性任务。

- [ ] **Step 7: 运行迁移和天气全链路回归**

```powershell
mvnw.cmd -Dtest=SchedulingSchemaInitializerTest,JdbcScheduledTaskRepositoryTest,ScheduledTaskServiceTest,ManageScheduledTaskToolTest,ScheduledTaskScannerTest,WeatherScheduledTaskHandlerTest,ScheduledWeatherPushIntegrationTest test
git diff --check
```

- [ ] **Step 8: 准备提交**

推荐提交信息：

```text
refactor: generalize scheduled task persistence
```

仅在用户授权后执行提交。

---

### Task 4: 创建图文卡片任务 Tool 与应用服务

**Files:**

- Create: `src/main/java/com/demo/demo/Service/scheduling/creative/CreateCreativeCardTaskCommand.java`
- Create: `src/main/java/com/demo/demo/Service/scheduling/creative/CreativeCardTaskPayload.java`
- Create: `src/main/java/com/demo/demo/Service/scheduling/creative/CreateCreativeCardTaskTool.java`
- Create: `src/main/java/com/demo/demo/config/CreativeCardProperties.java`
- Modify: `src/main/java/com/demo/demo/Service/scheduling/application/ScheduledTaskService.java:22`
- Modify: `src/main/java/com/demo/demo/Service/scheduling/domain/ScheduledTask.java`
- Modify: `src/main/java/com/demo/demo/Service/AIService.java:38`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/resources/application-local.example.yml`
- Test: `src/test/java/com/demo/demo/Service/scheduling/creative/CreateCreativeCardTaskToolTest.java`
- Test: `src/test/java/com/demo/demo/Service/scheduling/creative/CreativeCardTaskPayloadTest.java`
- Modify test: `src/test/java/com/demo/demo/Service/scheduling/application/ScheduledTaskServiceTest.java`
- Modify test: `src/test/java/com/demo/demo/Service/AISchedulingToolRegistrationTest.java`

**Interfaces:**

```java
public record CreativeCardTaskPayload(
        String theme,
        String visualStyle,
        String audience,
        String language,
        String copyTone,
        String imageRatio,
        int historyWindow) {}

public record CreateCreativeCardTaskCommand(
        String ownerTargetId,
        CreativeCardTaskPayload payload,
        ScheduleRule scheduleRule,
        ZoneId zoneId) {}

public String ScheduledTaskService.createCreativeCardTask(
        CreateCreativeCardTaskCommand command);

@Data
@Component
@ConfigurationProperties(prefix = "scheduling.creative-card")
public class CreativeCardProperties {
    private Duration minInterval = Duration.ofHours(1);
    private int maxActiveTasksPerUser = 10;
    private int maxRunsPerUserPerDay = 8;
    private int historyLimit = 30;
    private int copyMaxLength = 300;
}
```

- [ ] **Step 1: 写 payload 校验测试**

覆盖空主题、过长主题、非法图片比例、`historyWindow` 超界，以及默认值：

```java
var payload = CreativeCardTaskPayload.defaults("治愈系早安");
assertEquals("zh-CN", payload.language());
assertEquals(20, payload.historyWindow());
```

- [ ] **Step 2: 写 Tool 身份和规则测试**

覆盖：

- 缺少 `TrustedToolContext` 时拒绝。
- `WEEKLY + MONDAY,WEDNESDAY,FRIDAY + 08:00` 正确转换。
- `INTERVAL + 30 MINUTES` 拒绝。
- 非法时区拒绝。
- 模型参数中不存在 target ID、userId 或 contextToken。

- [ ] **Step 3: 实现命令、payload 和 Tool**

Tool 建议签名：

```java
@Tool
public ScheduledTaskToolResult createCreativeCardTask(
        String theme,
        String visualStyle,
        String copyTone,
        String scheduleKind,
        String localTime,
        String daysOfWeek,
        Integer dayOfMonth,
        Integer intervalValue,
        String intervalUnit,
        String timeZone);
```

`daysOfWeek` 只接受英文枚举逗号列表；空受众、语言、比例使用服务端默认值，不作为第一版 Tool 参数。

- [ ] **Step 4: 增加配置绑定并实现创建限制**

```yaml
scheduling:
  creative-card:
    min-interval: 1h
    max-active-tasks-per-user: 10
    max-runs-per-user-per-day: 8
    history-limit: 30
    copy-max-length: 300
```

`ScheduledTaskService.createCreativeCardTask(...)` 在 insert 前统计该 owner 的 ACTIVE 图文任务，达到上限时抛出稳定错误；`INTERVAL` 小于 `minInterval` 时拒绝。

- [ ] **Step 5: 实现 Service 创建和重复判断**

同一 owner 下，活动的 `CREATIVE_CARD` 任务若规范化 theme、schedule rule、zone 完全相同，则抛出 `SchedulingException`。不同风格允许共存。

- [ ] **Step 6: 注册 Tool**

向 `AIService` 构造器增加 `CreateCreativeCardTaskTool`，并加入现有 `ToolCallbacks.from(...)`。在 `scheduling.system-prompt-addition` 中明确：

```text
一次性生成图片使用 generateImage。
要求按周期生成主题图文卡片时使用 createCreativeCardTask。
模型只提取结构化调度字段，不生成 Cron。
```

- [ ] **Step 7: 运行 Tool、Service 和 Agent 注册测试**

```powershell
mvnw.cmd -Dtest=CreativeCardTaskPayloadTest,CreateCreativeCardTaskToolTest,ScheduledTaskServiceTest,AISchedulingToolRegistrationTest test
git diff --check
```

- [ ] **Step 8: 准备提交**

推荐提交信息：

```text
feat: add creative card scheduling tool
```

仅在用户授权后执行提交。

---

### Task 5: 持久化创作历史和可恢复执行阶段

**Files:**

- Create: `src/main/java/com/demo/demo/Service/scheduling/creative/CreativeCardHistory.java`
- Create: `src/main/java/com/demo/demo/Service/scheduling/creative/CreativeCardRun.java`
- Create: `src/main/java/com/demo/demo/Service/scheduling/creative/CreativeCardStage.java`
- Create: `src/main/java/com/demo/demo/Service/scheduling/creative/CreativeCardRepository.java`
- Create: `src/main/java/com/demo/demo/Service/scheduling/creative/JdbcCreativeCardRepository.java`
- Modify: `src/main/java/com/demo/demo/Service/scheduling/persistence/SchedulingSchemaInitializer.java`
- Test: `src/test/java/com/demo/demo/Service/scheduling/creative/JdbcCreativeCardRepositoryTest.java`
- Modify test: `src/test/java/com/demo/demo/Service/scheduling/persistence/SchedulingSchemaInitializerTest.java`

**Interfaces:**

```java
public enum CreativeCardStage {
    STARTED, CONTENT_GENERATED, IMAGE_GENERATED,
    IMAGE_SENT, TEXT_SENT, SUCCEEDED, DEGRADED, FAILED
}

public interface CreativeCardRepository {
    CreativeCardRun createOrGetRun(String executionId, String taskId, Instant now);
    Optional<CreativeCardRun> findRun(String executionId);
    boolean saveDraft(String executionId, String draftJson, Instant now);
    boolean saveImage(String executionId, byte[] imageBytes, Instant now);
    boolean advanceStage(String executionId, CreativeCardStage expected,
                         CreativeCardStage next, Instant now);
    void completeHistory(CreativeCardHistory history);
    List<CreativeCardHistory> findRecent(String taskId, int limit);
    int countRunsForOwnerSince(String ownerTargetId, Instant since);
    void pruneHistory(String taskId, int keep);
}
```

- [ ] **Step 1: 写 schema 和 Repository 失败测试**

覆盖：

- 同一 execution ID 只能有一条 run。
- 不同 task ID 历史隔离。
- 阶段必须按 expected 条件更新。
- 已发送图片后重新读取仍是 `IMAGE_SENT`。
- terminal 后清空暂存图片 BLOB。
- 每任务历史裁剪到指定数量。
- 按 owner 和 UTC 时间下界统计执行次数，不混入其他用户。

- [ ] **Step 2: 运行并确认表不存在**

```powershell
mvnw.cmd -Dtest=JdbcCreativeCardRepositoryTest test
```

- [ ] **Step 3: 新增两张表**

`creative_card_run` 保存恢复所需的 `draft_json`、临时 `image_bytes` 和阶段；`creative_card_history` 保存 title、fingerprint、keywords 和时间。

关键约束：

```sql
UNIQUE(execution_id)
UNIQUE(task_id, execution_id)
```

图片 BLOB 只在未完成 run 中保留；`SUCCEEDED/DEGRADED/FAILED` 后清空。

- [ ] **Step 4: 实现条件阶段更新**

SQL 形式：

```sql
UPDATE creative_card_run
SET stage = ?, updated_at = ?
WHERE execution_id = ? AND stage = ?
```

返回值不是 1 时调用方必须重新读取状态，不可假设成功。

- [ ] **Step 5: 实现近期历史查询和裁剪**

按 `created_at DESC LIMIT ?` 查询；裁剪使用 task ID 范围，不能删除其他任务历史。

- [ ] **Step 6: 运行持久化测试**

```powershell
mvnw.cmd -Dtest=SchedulingSchemaInitializerTest,JdbcCreativeCardRepositoryTest test
git diff --check
```

- [ ] **Step 7: 准备提交**

推荐提交信息：

```text
feat: persist creative card execution stages
```

仅在用户授权后执行提交。

---

### Task 6: 实现结构化创作 Agent 和近期去重

**Files:**

- Create: `src/main/java/com/demo/demo/Service/scheduling/creative/CreativeCardDraft.java`
- Create: `src/main/java/com/demo/demo/Service/scheduling/creative/CardCopyAgent.java`
- Create: `src/main/java/com/demo/demo/Service/scheduling/creative/DashScopeCardCopyAgent.java`
- Create: `src/main/java/com/demo/demo/Service/scheduling/creative/CreativeCardSimilarityService.java`
- Create: `src/main/java/com/demo/demo/Service/scheduling/creative/CreativeCardContentPolicy.java`
- Test: `src/test/java/com/demo/demo/Service/scheduling/creative/CreativeCardDraftTest.java`
- Test: `src/test/java/com/demo/demo/Service/scheduling/creative/DashScopeCardCopyAgentTest.java`
- Test: `src/test/java/com/demo/demo/Service/scheduling/creative/CreativeCardSimilarityServiceTest.java`
- Test: `src/test/java/com/demo/demo/Service/scheduling/creative/CreativeCardContentPolicyTest.java`

**Interfaces:**

```java
public record CreativeCardDraft(
        String title,
        String copy,
        String imagePrompt,
        Set<String> keywords,
        String contentSafetyNote) {}

public interface CardCopyAgent {
    CreativeCardDraft generate(
            CreativeCardTaskPayload payload,
            List<CreativeCardHistory> recent,
            Set<String> rejectedTitles);
}

public record SimilarityDecision(boolean duplicate, String reason) {}

public interface CreativeCardContentPolicy {
    void validateInput(CreativeCardTaskPayload payload);
    void validateDraft(CreativeCardDraft draft);
}
```

- [ ] **Step 1: 写草稿与内容边界校验测试**

空标题、空文案、空 prompt、超长文案、超长 prompt、控制字符和空关键词必须拒绝。限制值集中为常量并在测试中断言。`CreativeCardContentPolicy` 不把主题、文案或 prompt 写入异常消息。

- [ ] **Step 2: 写相似度测试**

覆盖：

```java
exactNormalizedTitleShouldBeDuplicate();
samePromptFingerprintShouldBeDuplicate();
keywordJaccardAboveThresholdShouldBeDuplicate();
differentThemeShouldNotBeDuplicate();
historyFromAnotherTaskMustNotBeProvidedByRepository();
```

- [ ] **Step 3: 实现确定性相似度**

- 标题：Unicode 归一化、去标点、转小写后精确比较。
- prompt：SHA-256 指纹只判定完全重复。
- 关键词：`intersection / union >= 0.70` 判定重复。
- 不调用 embedding，不使用 `VectorMemoryStore`。

- [ ] **Step 4: 写 Agent 输出解析测试**

给 mock `ChatModel` 返回：

```json
{"title":"晨光","copy":"愿今天温柔展开。","imagePrompt":"soft watercolor sunrise","keywords":["晨光","治愈"],"contentSafetyNote":"safe"}
```

断言解析成功；Markdown 围栏、缺字段、额外说明和超长字段应返回受控异常。

- [ ] **Step 5: 实现无 Tool、无记忆的 DashScope Agent**

沿用 `DashScopeScheduledContentAgent` 的独立 `DashScopeChatModel` 模式，但通过可注入构造器允许测试传入 mock `ChatModel`。Prompt 只包含主题配置、近期标题/关键词和拒绝列表，不包含 userId、target ID 或 token。

- [ ] **Step 6: 在模型调用前后执行内容边界校验**

调用顺序固定为：

```text
validateInput(payload)
→ Agent generate
→ parse CreativeCardDraft
→ validateDraft(draft)
→ similarity check
```

模型或图片 Provider 明确返回内容审核拒绝时统一映射为 `CONTENT_REJECTED`，不得把外部响应正文写入日志。

- [ ] **Step 7: 实现最多两次去重重写的调用服务**

初次生成后检测相似；重复时把冲突标题加入 `rejectedTitles` 再调用，最多两次重写。第三次仍重复时抛出错误码 `CONTENT_DUPLICATE`。

- [ ] **Step 8: 运行创作测试**

```powershell
mvnw.cmd -Dtest=CreativeCardDraftTest,CreativeCardSimilarityServiceTest,DashScopeCardCopyAgentTest,CreativeCardContentPolicyTest test
git diff --check
```

- [ ] **Step 9: 准备提交**

推荐提交信息：

```text
feat: generate deduplicated card drafts
```

仅在用户授权后执行提交。

---

### Task 7: 扩展可观测图片推送 Gateway

**Files:**

- Create: `src/main/java/com/demo/demo/Service/scheduling/execution/ImagePushRequest.java`
- Modify: `src/main/java/com/demo/demo/Service/scheduling/execution/MessagePushGateway.java:7`
- Modify: `src/main/java/com/demo/demo/Service/scheduling/adapter/ILinkMessagePushGateway.java:23`
- Modify: `src/main/java/com/demo/demo/Service/BotInstance.java:447`
- Modify test: `src/test/java/com/demo/demo/Service/scheduling/adapter/ILinkMessagePushGatewayTest.java:16`
- Modify test: `src/test/java/com/demo/demo/Service/BotInstanceScheduledSendTest.java:15`

**Interfaces:**

```java
public record ImagePushRequest(String targetId, byte[] imageBytes) {}

public interface MessagePushGateway {
    PushResult pushText(PushRequest request);
    PushResult pushImage(ImagePushRequest request);
}

public BotInstance.PushResult sendImageWithResult(
        String toUserId, String contextToken, byte[] imageBytes);
```

- [ ] **Step 1: 写 Bot 图片结果测试**

通过反射或可注入 seam 验证：

- 离线返回 `BOT_OFFLINE`。
- 空图片返回 `INVALID_IMAGE`。
- session 过期返回 `SESSION_EXPIRED`。
- SDK 异常返回 `SDK_ERROR`。
- 成功返回 `PushResult.ok()`。

- [ ] **Step 2: 运行并确认缺少 `sendImageWithResult`**

```powershell
mvnw.cmd -Dtest=BotInstanceScheduledSendTest test
```

- [ ] **Step 3: 实现可观测图片方法并保持兼容**

`sendImageReply(...)` 保留原签名；其内部可以委托 `sendImageWithResult(...).success()`。新方法不得记录图片内容、contextToken 或外部响应正文。

- [ ] **Step 4: 写 Gateway 图片测试**

覆盖目标不存在、默认 Bot 离线、空图片、成功和 SDK 失败。断言 `DeliveryTargetService.resolve(...)` 只在 gateway 边界调用。

- [ ] **Step 5: 实现 `pushImage(...)`**

流程与 `pushText(...)` 一致：

```text
resolve target
→ getDefaultBot
→ sendImageWithResult
→ 映射 PushResult
```

- [ ] **Step 6: 运行发送回归**

```powershell
mvnw.cmd -Dtest=BotInstanceScheduledSendTest,ILinkMessagePushGatewayTest test
git diff --check
```

- [ ] **Step 7: 准备提交**

推荐提交信息：

```text
feat: add observable scheduled image push
```

仅在用户授权后执行提交。

---

### Task 8: 实现图文卡片 Handler、阶段恢复和降级

**Files:**

- Create: `src/main/java/com/demo/demo/Service/scheduling/creative/CreativeCardScheduledTaskHandler.java`
- Create: `src/main/java/com/demo/demo/Service/scheduling/creative/CreativeCardDeliveryService.java`
- Modify: `src/main/java/com/demo/demo/Service/scheduling/execution/RetryPolicy.java:12`
- Test: `src/test/java/com/demo/demo/Service/scheduling/creative/CreativeCardScheduledTaskHandlerTest.java`
- Test: `src/test/java/com/demo/demo/Service/scheduling/creative/CreativeCardDeliveryServiceTest.java`
- Modify test: `src/test/java/com/demo/demo/Service/scheduling/execution/RetryPolicyTest.java`

**Interfaces:**

```java
public final class CreativeCardDeliveryService {
    public TaskHandlingResult deliver(
            ScheduledTask task, TaskExecution execution);
}

public final class CreativeCardScheduledTaskHandler
        implements ScheduledTaskHandler {
    public String taskType(); // CREATIVE_CARD
    public TaskHandlingResult handle(
            ScheduledTask task, TaskExecution execution);
}
```

- [ ] **Step 1: 写成功链路测试**

用 fake Agent、fake `ImageGenerationService`、内存/临时 SQLite Repository 和 fake Gateway 断言调用顺序：

```text
generate draft
save draft
generate image
save image
push image
mark IMAGE_SENT
push text
mark TEXT_SENT
save history
clear image BLOB
return SUCCEEDED
```

- [ ] **Step 2: 写阶段恢复测试**

分别从 `CONTENT_GENERATED`、`IMAGE_GENERATED`、`IMAGE_SENT`、`TEXT_SENT` 恢复。核心断言：

```java
verify(imageGenerator, never()).generateImage(anyString()); // IMAGE_SENT 恢复
verify(gateway, never()).pushImage(any());                  // IMAGE_SENT 恢复
verify(gateway).pushText(any());                            // 只补文案
```

- [ ] **Step 3: 写失败与降级测试**

覆盖：

- Agent 超时 → `AGENT_TIMEOUT`，可重试。
- 连续重复 → `CONTENT_DUPLICATE`，终止。
- 图片生成临时失败 → `IMAGE_PROVIDER_UNAVAILABLE`，可重试。
- 图片最终失败 → 只发送一次文案并返回 `DEGRADED`。
- 图片发送失败 → 不发送文案。
- 文案发送失败且图片已发送 → 重试时不重复图片。
- payload 非法 → `INVALID_TASK_PAYLOAD`，终止。

- [ ] **Step 4: 实现阶段驱动编排**

每完成一步先持久化产物，再条件推进阶段。开始执行时调用 `createOrGetRun(executionId, taskId, now)`；已有 run 必须从数据库阶段继续，不能重新生成所有内容。

- [ ] **Step 5: 直接调用底层图片 Service**

```java
byte[] imageBytes = imageGenerationService.generateImage(draft.imagePrompt());
```

不得调用 `ImageGenerationTool`，不得依赖其 `ThreadLocal`。

- [ ] **Step 6: 扩展重试策略**

可重试：

```text
AGENT_TIMEOUT
IMAGE_PROVIDER_UNAVAILABLE
BOT_OFFLINE
SDK_ERROR
```

永久失败：

```text
INVALID_TASK_PAYLOAD
CONTENT_DUPLICATE
CONTENT_REJECTED
DAILY_LIMIT_REACHED
TARGET_NOT_FOUND
SESSION_EXPIRED
INVALID_IMAGE
```

图片生成达到重试上限后，Handler 必须使用已持久化文案执行一次降级，而不是由通用 RetryPolicy 自动标记 FAILED。

生成前调用：

```java
int used = creativeCardRepository.countRunsForOwnerSince(
        task.ownerTargetId(), startOfLocalDayUtc);
```

达到 `CreativeCardProperties.getMaxRunsPerUserPerDay()` 时不调用 Agent、图片或 iLink，并以永久错误 `DAILY_LIMIT_REACHED` 结束本次执行。

- [ ] **Step 7: 运行图文执行测试及天气回归**

```powershell
mvnw.cmd -Dtest=CreativeCardScheduledTaskHandlerTest,CreativeCardDeliveryServiceTest,RetryPolicyTest,WeatherScheduledTaskHandlerTest,ScheduledTaskExecutionServiceTest test
git diff --check
```

- [ ] **Step 8: 准备提交**

推荐提交信息：

```text
feat: execute creative card scheduled tasks
```

仅在用户授权后执行提交。

---

### Task 9: 端到端集成、限制配置和文档验收

**Files:**

- Create: `src/test/java/com/demo/demo/integration/ScheduledCreativeCardPushIntegrationTest.java`
- Create: `src/test/java/com/demo/demo/config/CreativeCardPropertiesTest.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/resources/application-local.example.yml`
- Modify: `CLAUDE.md`
- Modify: `docs/ARCHITECTURE.md`
- Modify: `docs/TASKS.md`
- Modify: `docs/superpowers/specs/2026-07-28-creative-card-scheduling-design.md`

**Interfaces:**

- Consumes: Tasks 1～8 的所有公共接口。
- Produces: 可自动验证的完整图文周期链路和最终准确文档。

- [ ] **Step 1: 写端到端成功测试**

测试使用临时 SQLite、固定 `Clock`、mock ChatModel、mock 图片 Service 和 mock Gateway：

```text
可信 target
→ CreateCreativeCardTaskTool
→ scheduled_task
→ scanner
→ handler registry
→ creative handler
→ IMAGE_SENT
→ TEXT_SENT
→ SUCCEEDED
→ creative_card_history
```

断言图片一次、文案一次、历史一条、执行状态成功。

- [ ] **Step 2: 写隔离、暂停和恢复测试**

覆盖：

- 用户 A 不能列出、暂停或取消用户 B 的任务。
- 暂停任务不被 scanner 执行。
- 恢复后按结构化规则重新计算 `nextRunAt`。
- 同一 `execution_id` 重跑不重复图片。

- [ ] **Step 3: 写配置绑定与资源限制集成测试**

加载 `application.yml`，断言 `CreativeCardProperties` 的五个值与 Task 4 的配置一致。测试超过活动任务数、每日次数和最短间隔时返回稳定业务错误，不调用 Agent 或图片服务。

- [ ] **Step 4: 运行全量测试和打包**

```powershell
mvnw.cmd test
mvnw.cmd clean package
git diff --check
```

Expected: 全量测试通过，JAR 构建成功，无格式错误。

- [ ] **Step 5: 执行日志与敏感信息审计**

```powershell
rg -n "contextToken|encrypted_token|imagePrompt|draftJson|copy_text" src/main/java/com/demo/demo/Service/scheduling src/main/java/com/demo/demo/Service/BotInstance.java
```

逐条确认命中仅为字段、参数或安全注释，任何日志语句不得输出值。

- [ ] **Step 6: 更新架构和任务文档**

文档必须使用最终真实类名和方法签名，更新：

- Handler Registry 调用链。
- 通用调度规则和数据库 schema。
- 图文 Agent、历史去重和分阶段发送链。
- 已完成 Task 状态、测试命令和仍需真机验证的风险。

- [ ] **Step 7: 真机发布门禁**

使用测试微信账号和默认 Bot：

1. 创建每小时一次的低风险测试任务。
2. 验证图片先于文案到达。
3. 暂停、恢复、取消。
4. 制造一次 Bot 离线并验证恢复不重复图片。
5. 删除测试任务和测试历史。

真机验证不得写入自动化 CI，结果只记录脱敏的成功/错误码和时间。

- [ ] **Step 8: 准备提交**

推荐提交信息：

```text
test: verify scheduled creative card flow
```

仅在用户授权后执行提交。

---

## 最终验收清单

- [ ] 现有天气任务通过 Handler 执行且行为不变。
- [ ] 旧 SQLite 天气任务完整迁移，无虚假字段。
- [ ] 自然语言只转换为受限结构化规则，不保存原始 Cron。
- [ ] 一次、每日、每周、每月、间隔和工作日规则通过测试。
- [ ] 图文任务 owner 只来自可信上下文。
- [ ] 创作历史严格按 task ID 隔离并有限保留。
- [ ] 相似内容最多重写两次。
- [ ] 后台图片生成直接使用 `ImageGenerationService`。
- [ ] 图片和文案分阶段发送，恢复时不重复已成功阶段。
- [ ] 图片最终失败只降级发送一次文案。
- [ ] 自动化测试不访问真实外部服务。
- [ ] 全量测试、打包和 `git diff --check` 通过。
- [ ] 文档与最终源码一致。
- [ ] 未经用户授权没有 Git 提交。
