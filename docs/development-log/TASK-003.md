# TASK-003 开发记录

## 1. 任务目标

定义每日天气任务、推送目标和执行记录的最小领域模型，提供与 SQLite 兼容的幂等建表方式，以及基于时区的下一次执行时间计算。

## 2. 修改前调用链

N/A — 本 Task 所有类均为新增，不修改现有代码。

## 3. 设计决策

| 决策 | 选择 | 原因 |
|------|------|------|
| 领域对象风格 | Java `record` | 遵循项目先例 `WeatherQuery`；不可变对象，线程安全 |
| 建表机制 | `@Component` + `@PostConstruct` + `CREATE TABLE IF NOT EXISTS` | TASK-002 确认无现有建表入口，自建初始化组件 |
| 时间存储格式 | UTC epoch millis `INTEGER` | TASKS.md 要求，避免 SQLite 无 `DATETIME` 类型 |
| 业务时间格式 | `TEXT` (HH:mm + IANA ZoneId) | 保留原始用户意图，不受服务器时区影响 |
| DST gap 处理 | `ZonedDateTime.with(LocalTime)` 自动前移 | Spring-forward 日不存在的时间跳到第一个有效时刻 |
| DST overlap 处理 | 使用第一个（夏令时）出现 | `ZonedDateTime` 默认行为，最早触发 |
| 唯一约束位置 | SQL 层 `UNIQUE(task_id, scheduled_for)` | 不等同应用层校验，防止并发/崩溃后重复 |
| 乐观锁 | `version` 字段 + 条件更新 SQL | TASK-004 实现，领域对象已预留 |

## 4. 修改文件

| 文件 | 操作 | 说明 |
|------|------|------|
| `domain/ScheduledTaskStatus.java` | 新增 | ACTIVE/PAUSED/CANCELED 枚举 |
| `domain/ExecutionStatus.java` | 新增 | PENDING/RUNNING/SUCCEEDED/FAILED/RETRY 枚举 |
| `domain/DeliveryTarget.java` | 新增 | 推送目标记录，含 `withToken()` 刷新方法 |
| `domain/ScheduledTask.java` | 新增 | 任务领域对象，含状态转换 + `advanceNextRun()` |
| `domain/TaskExecution.java` | 新增 | 执行记录，含完整状态机（PENDING→RUNNING→SUCCEEDED/FAILED/RETRY） |
| `domain/NextRunCalculator.java` | 新增 | 基于 IANA 时区的下次执行时间计算 |
| `persistence/SchedulingSchemaInitializer.java` | 新增 | 三张表的幂等 DDL 初始化（含索引） |
| `test/.../domain/NextRunCalculatorTest.java` | 新增 | 16 个用例 |
| `test/.../persistence/SchedulingSchemaInitializerTest.java` | 新增 | 8 个用例 |
| `docs/TASKS.md` | 修改 | TASK-003 状态 + 记录 |
| `docs/development-log/TASK-003.md` | 新增 | 本文件 |

## 5. 修改类

无现有类被修改。

## 6. 修改方法

无现有方法被修改。所有均为新增。

### 新增方法说明

#### NextRunCalculator.nextDailyRun
- 签名：`public static Instant nextDailyRun(LocalTime localTime, ZoneId zoneId, Instant after)`
- 输入：用户指定的本地时间、IANA 时区、参考时刻（通常是 now）
- 返回值：UTC Instant
- 职责：计算下一次 `localTime` 在 `zoneId` 时区中的 UTC 时刻
- DST gap：不存在的时刻自动前移到第一个有效时刻
- DST overlap：使用第一个出现（夏令时）

#### SchedulingSchemaInitializer.init
- 签名：`public void init()`（@PostConstruct）
- 职责：幂等创建三张周期表及索引
- SQL 语法：仅 SQLite 兼容（INTEGER epoch millis, TEXT, 无 JSON/DATETIME/SKIP LOCKED）

## 7. 修改后调用链

```
SchedulingSchemaInitializer.init()                    ← 本 Task
→ JdbcTemplate.execute("CREATE TABLE IF NOT EXISTS wechat_delivery_target ...")
→ JdbcTemplate.execute("CREATE TABLE IF NOT EXISTS scheduled_task ...")
→ JdbcTemplate.execute("CREATE INDEX IF NOT EXISTS ...")
→ JdbcTemplate.execute("CREATE TABLE IF NOT EXISTS scheduled_task_execution ...")
→ JdbcTemplate.execute("CREATE INDEX IF NOT EXISTS ...")

ScheduledTaskService.createDailyWeatherTask(cmd)      ← TASK-005
→ NextRunCalculator.nextDailyRun(localTime, zoneId, now)  ← 本 Task
→ ScheduledTask.createDailyWeather(...)                ← 本 Task
→ ScheduledTaskRepository.insert(task)                 ← TASK-004
```

## 8. 测试结果

### 执行命令

```powershell
mvnw.cmd test -Dtest="com.demo.demo.Service.scheduling.**"
```

### 结果

```
Tests run: 24, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

| 测试类 | 用例数 | 覆盖 |
|--------|--------|------|
| `NextRunCalculatorTest` | 16 | 正常、跨天、同分钟、跨UTC午夜、UTC基线、DST gap、DST overlap、参数校验、6时区参数化 |
| `SchedulingSchemaInitializerTest` | 8 | 首次建表、幂等重复、三表字段校验、唯一约束、无MySQL语法 |

### 未测试场景

- 实际 datasource（TASK-002 待确认）上的集成测试
- DST 切换时刻的亚秒精度
- 极值时区（如 +14:00/-12:00）

## 9. 风险与遗留问题

| 级别 | 风险 | 说明 |
|------|------|------|
| **高** | 实际 datasource 未确认 | 若运行时非 SQLite，DDL 将失败 |
| **中** | `CREATE INDEX IF NOT EXISTS` 语法 | SQLite 3.3.0+ 支持，xerial 3.46.1.0 确认可用 |
| **低** | `AutoCloseable` 临时文件 | Schema 测试使用 `Files.createTempFile`，`@AfterEach` 清理 |
| **低** | xerial SQLite 驱动异常翻译 | `UncategorizedSQLException` 而非 `DuplicateKeyException`；TASK-004 Repository 需注意异常处理 |

## 10. Git 提交信息

```
feat: add SQLite scheduling domain and schema

Define 3 domain records (ScheduledTask, TaskExecution, DeliveryTarget),
2 status enums, NextRunCalculator with DST-aware time computation, and
idempotent SchedulingSchemaInitializer for SQLite.

- All timestamps stored as INTEGER (epoch millis)
- UNIQUE(task_id, scheduled_for) on execution table
- 3 tables: wechat_delivery_target, scheduled_task, scheduled_task_execution
- 24 tests pass (16 calculator + 8 schema)

Co-Authored-By: Claude <noreply@anthropic.com>
```
