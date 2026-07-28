# TASK-004 开发记录

## 1. 任务目标

按 `VectorMemoryStore` 的 `JdbcTemplate` 模式实现三张周期表的最小数据访问，不引入 JPA。

## 2. 修改前调用链

参照的现有 JDBC 模式：

```
VectorMemoryStore.constructor(JdbcTemplate, EmbeddingModel)
→ saveTurn() → JdbcTemplate.update("INSERT INTO vector_memory ...")
→ retrieveRelevant() → JdbcTemplate.query("SELECT ...", rowMapper, ...)
```

## 3. 设计决策

| 决策 | 选择 | 原因 |
|------|------|------|
| 接口+实现分离 | `interface XxxRepository` + `@Repository class JdbcXxxRepository` | TASKS.md 指定，方便 Service 层 mock |
| RowMapper | 每个实现类内部 `private static final RowMapper` | 与 `VectorMemoryStore` 一致，显式字段映射 |
| `insertUnique` 去重 | 捕获 `UncategorizedSQLException` + 检查 `SQLITE_CONSTRAINT_UNIQUE` | SQLite UNIQUE 约束，不依赖进程内 set |
| `updateStatusOwned` | WHERE 子句同时检查 `owner_target_id` 和 `version` | 跨用户安全 + 乐观锁 |
| 事务边界 | Repository 自身不开启事务，由 Service 控制 | 避免 SQLite 写锁竞争 |
| nullable epoch millis | `rs.getLong()` + `rs.wasNull()` 检查 | SQLite INTEGER 允许 NULL |

## 4. 修改文件

| 文件 | 操作 | 说明 |
|------|------|------|
| `persistence/DeliveryTargetRepository.java` | 新增 | 接口：upsert + findById |
| `persistence/JdbcDeliveryTargetRepository.java` | 新增 | JdbcTemplate 实现 |
| `persistence/ScheduledTaskRepository.java` | 新增 | 接口：insert + findByOwner + updateStatusOwned + findDue + advanceNextRun |
| `persistence/JdbcScheduledTaskRepository.java` | 新增 | JdbcTemplate 实现，条件更新含 owner 校验 |
| `persistence/TaskExecutionRepository.java` | 新增 | 接口：insertUnique + findById |
| `persistence/JdbcTaskExecutionRepository.java` | 新增 | UNIQUE 冲突 → Optional.empty() |
| `test/.../JdbcDeliveryTargetRepositoryTest.java` | 新增 | 5 用例 |
| `test/.../JdbcScheduledTaskRepositoryTest.java` | 新增 | 10 用例 |
| `test/.../JdbcTaskExecutionRepositoryTest.java` | 新增 | 7 用例 |
| `docs/ARCHITECTURE.md` | 修改 | 新增 3.7 节 |
| `docs/TASKS.md` | 修改 | TASK-004 状态 |
| `docs/development-log/TASK-004.md` | 新增 | 本文件 |

## 5. 修改类

无现有类被修改。

## 6. 新增方法

| 类名 | 方法名 | 签名 | 职责 |
|------|--------|------|------|
| `JdbcDeliveryTargetRepository` | `upsert` | `(String userId, String encryptedToken, Instant now) → DeliveryTarget` | 创建或刷新推送目标 |
| `JdbcDeliveryTargetRepository` | `findById` | `(String targetId) → Optional<DeliveryTarget>` | 按 target_id 查询 |
| `JdbcScheduledTaskRepository` | `insert` | `(ScheduledTask) → ScheduledTask` | 插入任务并读回 id |
| `JdbcScheduledTaskRepository` | `findByOwner` | `(String targetId) → List<ScheduledTask>` | 按 owner 查询 |
| `JdbcScheduledTaskRepository` | `findByTaskId` | `(String taskId) → Optional<ScheduledTask>` | 按 task_id 查询 |
| `JdbcScheduledTaskRepository` | `updateStatusOwned` | `(String taskId, String ownerTargetId, ScheduledTaskStatus, int expectedVersion, Instant) → boolean` | 条件更新状态（owner+version） |
| `JdbcScheduledTaskRepository` | `advanceNextRun` | `(String taskId, int expectedVersion, Instant newNextRun, Instant) → boolean` | 推进下次执行时间 |
| `JdbcScheduledTaskRepository` | `findDue` | `(Instant now, int batchSize) → List<ScheduledTask>` | 到期任务扫描 |
| `JdbcTaskExecutionRepository` | `insertUnique` | `(String taskId, Instant scheduledFor, Instant) → Optional<TaskExecution>` | 唯一执行记录创建 |
| `JdbcTaskExecutionRepository` | `findById` | `(String executionId) → Optional<TaskExecution>` | 按 execution_id 查询 |

## 7. 修改后调用链

```
DeliveryTargetService.refresh(cmd)          ← TASK-005
→ DeliveryTargetRepository.upsert(userId, encryptedToken, now)
  → JdbcDeliveryTargetRepository: SELECT by user_id → UPDATE or INSERT

ScheduledTaskService.create(task)           ← TASK-005
→ ScheduledTaskRepository.insert(task)
  → JdbcScheduledTaskRepository: INSERT + SELECT back

ScheduledTaskScanner.scan()                 ← TASK-008
→ ScheduledTaskRepository.findDue(now, 10)
→ TaskExecutionRepository.insertUnique(taskId, scheduledFor, now)
  → JdbcTaskExecutionRepository: INSERT → catch SQLITE_CONSTRAINT_UNIQUE → empty

管理 Tool 查询/取消                            ← TASK-006
→ ScheduledTaskRepository.findByOwner(targetId)
→ ScheduledTaskRepository.updateStatusOwned(taskId, targetId, CANCELED, version, now)
  → JdbcScheduledTaskRepository: UPDATE WHERE task_id=? AND owner_target_id=? AND version=?
```

## 8. 测试结果

```
mvnw.cmd test -Dtest="JdbcDeliveryTargetRepositoryTest,JdbcScheduledTaskRepositoryTest,JdbcTaskExecutionRepositoryTest"
Tests run: 22, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

| 测试类 | 用例数 | 关键覆盖 |
|--------|--------|----------|
| `JdbcDeliveryTargetRepositoryTest` | 5 | 新建、更新 token、不同用户独立、findById 命中/未命中 |
| `JdbcScheduledTaskRepositoryTest` | 10 | insert、findByOwner 过滤、updateStatusOwned 正确 owner/错误 owner/错误 version、findDue 过去/未来/非ACTIVE/批量限制、advanceNextRun |
| `JdbcTaskExecutionRepositoryTest` | 7 | 首次成功、重复去重、不同 scheduledFor、不同 task 同时间、唯一约束消息检测、findById |

## 9. 风险与遗留问题

| 级别 | 风险 |
|------|------|
| **中** | xerial SQLite 异常翻译为 `UncategorizedSQLException`，生产代码需精确匹配 `SQLITE_CONSTRAINT_UNIQUE` 字符串 |
| **低** | `upsert` 使用 SELECT + UPDATE/INSERT（非原子），并发下同一 userId 可能创建两个 target；Service 层可用数据库 UNIQUE 约束兜底 |
| **低** | 未覆盖大规模数据的性能测试 |
| **低** | 5 个已有测试编译错误（非本 Task 引入） |

## 10. Git 提交信息

```
feat: add JDBC scheduling repositories

Implement DeliveryTargetRepository, ScheduledTaskRepository and
TaskExecutionRepository with JdbcTemplate. Owner verification baked
into UPDATE SQL; insertUnique uses SQLite UNIQUE constraint for
deduplication. 22 tests pass on temp SQLite.

Co-Authored-By: Claude <noreply@anthropic.com>
```
