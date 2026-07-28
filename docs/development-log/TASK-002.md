# TASK-002 开发记录

## 1. 任务目标

在写功能代码前确认两项运行事实：有效 datasource 是否是 SQLite、iLink `contextToken` 能否用于跨日主动推送。同步确认 MVP 是否接受"仅默认 Bot"边界。

## 2. 修改前调用链

本 Task 不改代码，涉及以下待验证链路：

**SQLite 持久化（待运行时确认）**：
```
VectorMemoryStore.saveTurn(userId,message,reply)
→ JdbcTemplate.update("INSERT INTO vector_memory ...")
→ 未知 DataSource → 未知数据库文件
→ 未知建表入口
```

**iLink 推送门禁（待运行时确认）**：
```
MultiBotManager.getDefaultBot()
→ BotInstance.sendReply(toUserId,contextToken,text)
→ ILinkClient.sendTextMessage(credentials,toUserId,contextToken,text)
→ contextToken 跨日有效性：待验证
→ contextToken 重登后有效性：待验证
```

## 3. 设计决策

1. **datasource**：根据 CLAUDE.md 记录"用户已说明当前运行环境使用 SQLite"和 POM 仅含 sqlite-jdbc 的事实，TASK-003 的 schema 初始化将基于 SQLite 语法（`INTEGER` 时间戳、`TEXT` JSON、无 `SKIP LOCKED`、无 `DATETIME`）。

2. **建表入口**：由于没有现成的 schema.sql、DataSourceInitializer 或 @PostConstruct 建表机制，TASK-003 将创建独立的 `SchedulingSchemaInitializer` 组件，使用 `CREATE TABLE IF NOT EXISTS` 模式。

3. **调度基础设施**：无 `@EnableScheduling`，TASK-008 需要新增。

4. **iLink 推送**：`BotInstance.sendReply()` 返回 void 并吞异常，TASK-010 需新增可观测发送方法。

## 4. 修改文件

| 文件 | 操作 | 说明 |
|------|------|------|
| `docs/ARCHITECTURE.md` | 修改 | 新增第 10 节：TASK-002 审计确认清单 |
| `docs/TASKS.md` | 修改 | TASK-002 状态更新 + 开发记录 |
| `docs/development-log/TASK-002.md` | 新增 | 本文件 |

## 5. 修改类

无。本 Task 不改生产代码。

## 6. 修改方法

无。本 Task 不改生产代码。

## 7. 关键审计发现

### 7.1 Datasource 三重冲突

| 位置 | 内容 |
|------|------|
| pom.xml | 仅 sqlite-jdbc 3.46.1.0 |
| application.yml（跟踪） | jdbc:mysql://... + com.mysql.cj.jdbc.Driver |
| application-local.yml（gitignored） | jdbc:mysql://localhost:3306/xialingying |

### 7.2 vector_memory 建表入口缺失

- 0 个 *.sql 文件
- 0 处 CREATE TABLE
- 无 DataSourceInitializer
- VectorMemoryStore 无 @PostConstruct 建表

### 7.3 调度基础设施空白

- 无 @EnableScheduling
- 无调度器/执行器
- 无调度任务表

### 7.4 BotInstance.sendReply() 不可观测

- 返回 void
- 异常被吞掉（catch log only）
- 无法区分"发送成功"/"Bot 离线"/"目标失效"/"SDK 异常"

## 8. 修改后调用链

N/A（本 Task 未修改代码）

## 9. 测试结果

### 自动化测试

```powershell
mvnw.cmd -DskipTests compile
```

**结果**：BUILD SUCCESS

```bash
git diff --check
```

**结果**：干净，无空白问题

### 待手动验证

以下 5 项需要通过运行环境手动验证：

| 门禁 | 内容 | 状态 |
|------|------|------|
| A | 启动应用，确认实际 JDBC URL 和驱动 | 待用户确认 |
| B | 确认 vector_memory 表由谁创建 | 待用户确认 |
| C | contextToken 跨日有效性测试 | 待用户测试 |
| D | contextToken 重登后有效性测试 | 待用户测试 |
| E | MVP 默认 Bot 边界决策 | 待用户确认 |

## 10. 风险与遗留问题

| 级别 | 风险 | 影响 |
|------|------|------|
| **严重** | 实际 datasource 未确认为 SQLite | TASK-003 DDL 语法、TASK-004 JDBC 操作可能不兼容 |
| **严重** | contextToken 跨日/重登失效 | 周期推送方案可能需要重新设计 |
| **高** | 建表入口未确认 | TASK-003 需自行设计初始化机制，可能与现有手动建表流程冲突 |
| **中** | 默认 Bot 边界未获人工确认 | 若后续要求多 Bot，需停止并新立路由 Task |
| **低** | IDE 数据源 (`my_data.sqlite`) 与 Spring 运行时 datasource 可能不一致 | 仅为 IDE 工具配置，不影响运行时 |

## 11. Git 提交信息

```
docs: confirm SQLite and iLink scheduling constraints

TASK-002 automated audit: compile pass, no schema.sql, no
@EnableScheduling, no table creation mechanism. POM has only
sqlite-jdbc but yml configs still show MySQL — runtime confirmation
still needed for actual datasource.

Co-Authored-By: Claude <noreply@anthropic.com>
```
