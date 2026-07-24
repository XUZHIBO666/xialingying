# feat/voice-mvp 功能变更日志

> 基线：2026-07-20，从 `132 tests` → `158 tests`，全程零回归

---

## 1. 持久化对话记忆（P3-23）

**提交**：`ed1d356` + `3300d47`

**新增文件**：
- `Service/memory/ConversationMessage.java` — 不可变 record（role + content）
- `Service/memory/ConversationMemoryStore.java` — JSON 快照存储
- `Service/memory/ConversationMemoryStoreTest.java` — 12 项测试
- `AIServiceMemoryTest.java` — 6 项集成测试

**修改文件**：
- `AIService.java` — 替换内存 `ConcurrentHashMap` 为持久化存储

**能力**：
- 每用户最多 10 对（20 条）对话自动持久化到 `./data/conversation-memory.json`
- 原子写入（临时文件 + rename），JSON 损坏自动恢复
- 线程安全：写操作 `synchronized`，读操作 `List.copyOf()` 防御性副本
- 重启后自动恢复对话上下文
- LLM 失败/空回复不持久化
- 用户 ID 掩码记录，消息正文不入日志

**验证**：
| 测试类 | 数量 | 覆盖场景 |
|--------|------|---------|
| `ConversationMemoryStoreTest` | 12 | 持久化/重载、用户隔离、10对裁剪、选择性清除、文件不存在/损坏、写入失败不丢内存数据、20用户并发JSON可解析、并发数据完整、防御性副本 |
| `AIServiceMemoryTest` | 6 | 重启恢复上下文、HTTP500不持久化、空回复不持久化、选择性清除、消息顺序验证、同用户串行 |

---

## 2. 消息队列削峰与限速（P3-27）

**提交**：`94156f1`

**新增文件**：
- `Service/throttle/UserRateLimiter.java` — Per-user 令牌桶
- `Service/throttle/UserRateLimiterTest.java` — 6 项测试

**修改文件**：
- `BotService.java` — 线程池改造 + 限速接入

**能力**：
- 回复线程池：4 线程 + 200 容量 `LinkedBlockingQueue` + `CallerRunsPolicy`
- Per-user 速率限制：默认每秒 0.5 次，突发 2 条
- 配置化：`BOT_REPLY_THREADS`、`BOT_REPLY_QUEUE_CAPACITY`、`BOT_RATE_LIMIT_PER_SECOND`、`BOT_RATE_LIMIT_BURST`
- 限速触发 → 友好提示"你的消息太快了，请稍后再发"

**验证**：
| 测试类 | 数量 | 覆盖场景 |
|--------|------|---------|
| `UserRateLimiterTest` | 6 | 正常速率、突发限速、令牌恢复、用户隔离、清除桶、桶计数 |

---

## 3. 优雅关闭（P1-7）

**提交**：`a2ceebc`

**修改文件**：
- `BotService.java` — `@PreDestroy shutdown()` 完整重写

**能力**：
- 关闭标志 `volatile boolean shuttingDown` 防止新工作
- 有序拆卸：登录线程 → 监听线程 → 回复线程池
- 等待已提交任务完成（上限 5 秒）
- 超时后强制取消剩余任务
- 幂等：重复调用 `shutdown()` 无副作用
- `startLogin()` / `startListening()` 检查关闭标志

---

## 4. 健康检查与可观测性（P3-28）

**提交**：`b439d4b`

**新增文件**：
- `controller/BotHealthController.java` — 健康检查端点
- `controller/BotHealthControllerTest.java` — 5 项 MockMvc 测试

**修改文件**：
- `controller/BotAdminAuthConfig.java` — 排除健康端点认证

**端点**：
| 端点 | 用途 | 认证 |
|------|------|------|
| `GET /bot/health/live` | K8s liveness probe | 免认证 |
| `GET /bot/health/ready` | K8s readiness probe | 免认证 |
| `GET /bot/health` | 完整状态 + AI/ASR/TTS 可选检查 | 需认证 |
| `GET /bot/health/metrics` | 队列、限速、JVM、记忆存储指标 | 需认证 |

**检查项**：iLink 登录状态、回复队列使用率、记忆存储用户数、AI/ASR/TTS API 配置状态

**指标**：队列容量/使用率、限速器活跃桶数/累计接受/拒绝、JVM 内存、运行时间

---

## 5. 图片下载异步化（P1-8）

**提交**：`0e58080`

**修改文件**：
- `BotService.java` — `processImageItem()` 重构

**能力**：
- CDN 图片下载从监听线程移到回复线程池异步执行
- 监听线程只做参数提取（同步，< 1ms），立即返回
- 下载 + 识别 + 回复在回复池中按用户串行
- 下载失败仍然发送文字降级提示

---

## 6. 多模态上下文感知（P3-26）

**提交**：`6b24fb2`

**新增文件**：
- `Service/context/SensorialContext.java` — 单用户感知上下文
- `Service/context/ContextManager.java` — 全局上下文管理器
- `Service/context/ContextManagerTest.java` — 8 项测试

**修改文件**：
- `AIService.java` — system prompt 注入近期上下文
- `BotController.java` — 图片识别后记录描述
- `VoiceMessageService.java` — 语音转写后记录文本

**能力**：
- 图片识别后自动记录描述到用户上下文（5 分钟 TTL）
- 语音 ASR 后自动记录转写文本到用户上下文
- LLM 调用时自动注入近期上下文到 system prompt
- 支持跨模态追问："这是什么颜色？"（引用刚才发的图片）
- 过期自动清理，最多 200 个活跃用户

**验证**：
| 测试类 | 数量 | 覆盖场景 |
|--------|------|---------|
| `ContextManagerTest` | 8 | 图片上下文注入、语音上下文注入、两者共存、无上下文原样返回、用户隔离、空记录忽略、过期清理、TTL过期不注入 |

---

## 7. 日志隐私整改（P0-6）

**提交**：`c9bef7f`

**修改文件**：
- `BotService.java` — 消息正文/ID/Token 脱敏
- `BotController.java` — 消息正文脱敏
- `WeatherUtil.java` — 天气 JSON/URL 脱敏
- `ImageRecognitionService.java` — 错误响应体脱敏

**整改清单**：
| 类别 | 修改前 | 修改后 |
|------|--------|--------|
| 消息正文 | `text={完整消息}` | `textLength={长度}` |
| 用户 ID | `from={rawUserId}` | `from={掩码}` |
| 二维码 URL | `二维码: {完整URL}` | `二维码，长度 {n}` |
| 游标 | `更新游标: {cursor}` | `更新游标` |
| 天气 JSON | `API 返回数据: {完整JSON}` | `API 返回数据长度 {n}` |
| 天气结果 | `结果: {完整天气字符串}` | `查询成功，城市: {city}` |
| 识别错误 | `body={300字符子串}` | `responseLength={n}` |
| MP3 文件名 | `fileName={文件名}` | 移除 |

**未修改**：`displayLog()` — 管理页面日志，受 admin token 保护

---

## 8. LLM 错误分类与自动重试（P1-9）

**提交**：`f61d135`

**修改文件**：
- `AIService.java` — 新增 `executeWithRetries()` 方法

**能力**：
- 可重试错误：429（限流）、500/502/503/504（服务端）、IOException（网络）
- 不可重试错误：401/403（认证）→ 立即返回 null
- 最多 3 次重试，退避：500ms → 1s → 2s
- 日志区分可重试/不可重试/重试耗尽

---

## 9. Function Calling 智能路由（P3-24）

**提交**：`3324521`

**新增文件**：
- `Service/tool/Tool.java` — 工具接口
- `Service/tool/ToolRegistry.java` — Spring 自动发现 + OpenAI tools 格式转换
- `Service/tool/WeatherTool.java` — `get_weather(city)` 工具
- `Service/tool/TimeTool.java` — `get_current_time()` 工具
- `Service/tool/ToolRegistryTest.java` — 5 项测试

**修改文件**：
- `AIService.java` — 新增 `chatWithTools()` 方法（两轮 FC 流程）
- `BotController.java` — 兜底路由使用 `chatWithTools`

**能力**：
- LLM 自动判断用户意图并调用对应工具
- 两轮 FC 流程：请求（带 tools）→ 工具执行 → 回传结果 → 最终回复
- 无工具时行为与 `chat()` 完全一致
- 关键词路由保留作为快速路径（生图正则 + 天气/时间关键词）
- ToolRegistry 通过 Spring 自动发现所有 `@Component Tool` Bean

**验证**：
| 测试类 | 数量 | 覆盖场景 |
|--------|------|---------|
| `ToolRegistryTest` | 5 | Bean发现、按名查找、空注册表、OpenAI格式生成、TimeTool执行 |
| `AIServiceMemoryTest`（新增） | 2 | FC无工具行为一致、FC有工具但LLM选择不用 |

---

## 总览

| 维度 | 数据 |
|------|------|
| 新增文件 | 16 个（10 源码 + 6 测试） |
| 修改文件 | 7 个 |
| 新增测试 | 26 个 |
| 当前测试总数 | **158** |
| 失败 | **0** |
| 提交数 | **11**（含 3 个 docs 提交） |
| 覆盖优先级 | P0 ×1, P1 ×3, P3 ×5 |

### 系统架构现状

```
Controller 层
├── BotController        ← 混合路由（关键词 + FC 兜底）
├── BotHealthController  ← 健康检查 + 指标
└── BotAdminAuthConfig   ← 管理认证（健康端点免认证）

Service 层
├── BotService           ← 4线程200队列 + 限速 + 优雅关闭 + 异步图片下载
├── AIService            ← 持久化记忆 + 重试 + FC + 多模态上下文
├── ImageGenerationService
├── ImageRecognitionService
├── memory/
│   └── ConversationMemoryStore ← JSON原子写入
├── context/
│   └── ContextManager   ← 跨模态感知上下文
├── throttle/
│   └── UserRateLimiter  ← Per-user令牌桶
├── tool/
│   ├── ToolRegistry     ← 工具自动发现
│   ├── WeatherTool      ← get_weather
│   └── TimeTool         ← get_current_time
└── voice/
    ├── VoiceMessageService ← ASR→LLM→TTS 编排
    ├── SiliconFlowAsrService
    ├── SiliconFlowTtsService
    └── AudioConverter
```
