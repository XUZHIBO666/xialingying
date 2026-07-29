# 周期回复 MVP 设计

## 目标与范围

实现微信用户通过自然语言创建、查看和取消周期回复任务。第一版支持：

- 固定间隔，例如“每隔 2 小时”。
- 每日固定时间，例如“每天 8 点”。
- 每周固定时间，例如“每周一 9 点”。
- 固定内容发送。
- 每次触发时由现有 Agent 动态生成内容后发送。

第一版统一使用 `Asia/Shanghai` 时区，只绑定当前默认 `BotInstance`。不引入 Quartz、cron 解析库、新数据源、分布式锁或多 Bot 任务路由。

## 设计原则

周期任务是结构化业务状态，不是聊天消息，也不是可被淘汰的语义记忆。任务文件是唯一事实源；现有数据库中的向量记忆只保留用户命令及 Agent 回复的语义副本，即使每用户超过 40 条后被裁剪，也不影响任务执行。

不修改 `MessagesModelHook trimHook`。该 Hook 只负责控制本次模型调用的消息窗口，把周期任务混入其中会污染短期对话、产生重复消息，并把业务状态错误地绑定到消息裁剪生命周期。

## 架构与代码边界

只新增一个生产类 `PeriodicReplyService`，其内部嵌套 `PeriodicTask record`。该类集中承担周期回复 MVP 中紧密相关的职责：

1. 创建、查看和取消任务。
2. JSON 文件加载与原子持久化。
3. 单线程周期扫描。
4. 到期任务触发。
5. 为 `MemoryAgentHook` 提供当前用户的有效任务摘要。

不额外拆分 Store、Scheduler、Parser、Executor 或 DTO 类。测试可以独立建类，不为了减少文件数量而牺牲测试隔离。

`PeriodicReplyService` 作为 ReactAgent 工具暴露三个操作：

- 创建周期任务。
- 查看当前用户的周期任务。
- 取消周期任务。

自然语言理解由现有 ReactAgent 完成，工具只接收规范化参数，不再实现一套中文命令解析器。

固定模式到期后直接调用默认 Bot 的文本发送。动态模式先调用现有 `AIService.chat()`，再发送生成结果。为了避免 `AIService` 与 `PeriodicReplyService` 产生构造器循环依赖，`PeriodicReplyService` 保存两个在应用初始化阶段设置的函数回调：

- 动态内容生成器。
- 消息发送器。

`BotController` 在现有自动回复初始化位置设置这两个回调，复用当前 `AIService` 和默认 Bot 发送路径。

## 任务模型

内嵌 `PeriodicTask record` 包含以下字段：

| 字段 | 用途 |
| --- | --- |
| `id` | 用户范围内递增的短编号 |
| `userId` | 微信用户标识 |
| `contextToken` | iLink 发送所需上下文 |
| `scheduleType` | `INTERVAL`、`DAILY` 或 `WEEKLY` |
| `scheduleValue` | 规范化规则值 |
| `mode` | `FIXED` 或 `AGENT` |
| `content` | 固定文本或交给 Agent 的动态指令 |
| `nextRunAt` | 下一次执行时间 |
| `enabled` | 任务是否有效 |

规则值采用简单字符串，不新增规则类型层级：

- 每隔 2 小时：`INTERVAL` + `PT2H`。
- 每天 8 点：`DAILY` + `08:00`。
- 每周一 9 点：`WEEKLY` + `MONDAY@09:00`。

时间计算只使用 Java 21 自带的 `java.time` API。

## 创建、查看与取消

创建流程：

1. 用户发送周期回复请求。
2. ReactAgent 识别固定或动态模式并调用创建工具。
3. 工具校验用户、发送上下文、规则、模式和内容。
4. 为该用户分配下一个短编号并计算 `nextRunAt`。
5. 原子写入任务文件。
6. 工具返回规范化确认信息，Agent 回复用户。
7. 正常 `AIService.chat()` 成功后仍由现有 `VectorMemoryStore.saveTurn()` 保存用户命令和回复。

查看任务时按编号列出当前有效任务，不输出内部 `contextToken`。

取消规则：

- 用户只有一个有效任务时，“取消周期任务”可以省略编号。
- 用户有多个有效任务时，必须指定编号，例如“取消任务 2”。
- 模糊取消不得猜测目标，应返回任务列表并要求用户指定编号。
- 取消成功后从任务文件删除对应记录。

每个用户最多允许 20 个有效周期任务。

## 调度与触发

`PeriodicReplyService` 内部持有一个单线程 `ScheduledExecutorService`，每 30 秒扫描一次。无需在应用级启用 `@EnableScheduling`。

扫描流程：

1. 在锁内找出已到期且有效的任务。
2. 检查默认 Bot 是否已登录。未登录时不推进任务，留待下一次扫描重试。
3. 已登录时，先计算未来的 `nextRunAt` 并持久化。
4. 在锁外执行动态生成和消息发送，避免慢调用阻塞任务 CRUD。
5. 单个任务失败只记录不含敏感内容的告警，不终止后续任务扫描。

先推进并保存再发送，选择“至多一次”语义，优先避免进程崩溃造成重复提醒。底层发送调用已发起但最终失败时，本周期不自动重试；下一周期仍正常执行。

固定模式直接发送 `content`。动态模式调用生成器处理 `content`，生成成功后发送结果；生成失败时发送简短提示“本次周期任务生成失败，请稍后重试”，任务本身保持有效。

应用关闭时停止调度线程。

## 重启与错过任务

启动时加载任务文件。已经过期的任务最多补触发一次，然后把 `nextRunAt` 推进到未来：

- 固定间隔任务持续增加间隔，直到下一次时间在未来。
- 每日任务推进到下一天对应时间。
- 每周任务推进到下一周对应星期和时间。

不逐次补发停机期间错过的所有周期，避免应用离线较久后集中轰炸用户。

## 持久化

默认文件为 `./data/periodic-replies.json`，可通过 `PERIODIC_REPLY_FILE` 覆盖。顶层包含版本号和任务数组。复用 `ConversationMemoryStore` 的写入策略：

1. 写入目标文件同目录的临时文件。
2. 优先原子替换目标文件。
3. 文件系统不支持原子移动时退化为普通替换。

缺失文件视为空任务集。损坏或无法读取的文件记录不含内容的警告并以空任务集启动，不能阻止应用启动。

所有内存任务读写和文件替换由同一把锁串行化。读取操作返回不可变副本。

`contextToken` 和任务正文属于敏感数据，只保存在本地任务文件，不写日志。任务目录继续由 `.gitignore` 排除。

## Agent 上下文

`MemoryAgentHook` 增加 `PeriodicReplyService` 依赖。每次 Agent 启动时，同时读取：

- `VectorMemoryStore` 返回的语义相关记忆。
- `PeriodicReplyService` 返回的当前用户有效任务摘要。

两部分合并写入现有 `memory_context`，继续由 `MemoryContextInterceptor` 注入 system prompt。即使向量检索失败，有效周期任务仍应被注入；反之亦然。

摘要示例：

```text
【当前有效周期任务】
- 任务1：每天 08:00，固定发送“提醒吃药”
- 任务2：每周一 09:00，由 Agent 执行“总结本周计划”
```

注入规则明确说明：这些内容是当前系统状态，Agent 不得虚构任务、重复创建任务或擅自取消任务。

## 配置与改动范围

生产代码变化控制为：

- 新增 `Service/PeriodicReplyService.java`。
- 修改 `AIService.java`：注册周期任务工具，并向 Hook 注入服务。
- 修改 `MemoryAgentHook.java`：合并有效任务摘要。
- 修改 `BotController.java`：设置生成与发送回调。
- 修改 `application.yml` 和 `application-local.example.yml`：增加任务文件配置。

不修改 `MessagesModelHook trimHook`，不新增数据库表，不增加 SQLite 数据源配置。

## 错误处理

- 参数无效：工具返回明确原因，不创建任务。
- 超过每用户 20 个任务：拒绝创建并提示先取消旧任务。
- 文件保存失败：创建、取消或推进操作视为失败，不仅修改内存状态。
- 默认 Bot 未登录：保留到期任务，下次扫描重试。
- Agent 动态生成失败：发送简短失败提示，保持任务有效。
- 单个任务异常：隔离处理，继续扫描其他任务。
- 无效或过期 `contextToken`：发送失败按当前周期结束处理，日志不得输出 token。

## 测试与验收

新增 `PeriodicReplyServiceTest`，并按需扩展现有 `MemoryAgentHook`/AI 相关测试。确定性测试使用临时时钟、临时目录和函数回调，不调用真实 Agent、iLink 或网络。

覆盖以下行为：

1. 固定间隔、每日和每周规则正确计算下一次时间。
2. 创建后生成有效 JSON，重启后恢复任务。
3. 查看任务按用户隔离并隐藏 `contextToken`。
4. 指定编号取消成功。
5. 单任务允许省略编号，多任务拒绝模糊取消。
6. 固定模式直接调用发送回调。
7. 动态模式先调用生成回调，再调用发送回调。
8. Bot 未登录时不推进任务。
9. 过期任务启动后只补触发一次。
10. 并发创建不丢任务，文件始终是有效 JSON。
11. 向量检索失败时仍注入周期任务，任务读取失败时仍保留向量记忆。
12. 日志不包含 `contextToken`、任务正文、消息正文或外部响应正文。

实现完成后的验证命令：

```powershell
.\mvnw.cmd -Dtest=PeriodicReplyServiceTest test
.\mvnw.cmd test
.\mvnw.cmd clean package
git diff --check
git status --short
```

需要延期的手工检查：真实微信登录后验证 iLink `contextToken` 能否跨较长周期继续用于主动发送；如果 SDK 或服务端限制该 token 生命周期，再单独设计主动消息凭据刷新，不在本 MVP 中推测实现。
