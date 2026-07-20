# xialingying 功能优化方案

> 基线：2026-07-20，`feat/voice-mvp` 分支，79 项测试通过  
> 本文档基于对项目源码、设计文档、功能矩阵和问题基线的全面分析，按优先级和类别列出所有优化项。

---

## 目录

- [P0 — 安全与数据正确性](#p0-安全与数据正确性)
- [P1 — 稳定性与可运维性](#p1-稳定性与可运维性)
- [P2 — 代码质量与架构](#p2-代码质量与架构)
- [P3 — 功能增强](#p3-功能增强)
- [实施路线建议](#实施路线建议)

---

## P0 — 安全与数据正确性

### 1. Bot 管理页面 XSS 防护

**现状问题**：`templates/bot.html` 使用未转义的 `innerHTML` 插入微信用户 ID、消息内容、日志和 `contextToken`。攻击者可通过微信发送恶意脚本，当管理员打开 `/bot` 页面时触发 XSS，窃取 `contextToken` 或伪造操作。

**优化方案**：
- 所有用户内容使用 `textContent` 替代 `innerHTML`
- 或引入 DOMPurify 对内容做 HTML 转义后再插入
- 对 `contextToken`、`replyId` 等敏感字段做掩码展示

**涉及文件**：`src/main/resources/templates/bot.html`

---

### 2. Bot 管理接口认证加固

**现状问题**：`BotAdminAuthConfig` 已实现了基于 `X-Bot-Admin-Token` 的拦截器认证，但 `FEATURE_MATRIX.md` 标记该功能"已完整验证"，实际 Reviewer 发现拦截器仅校验请求头/参数中的 Token 与 `BOT_ADMIN_TOKEN` 环境变量一致。该机制已覆盖所有 `/bot/**` 路径。

**优化方案**（硬化工夫）：
- 增加登录失败延迟（防暴力破解）
- Token 支持从文件读取（方便 K8s Secret 挂载）
- 添加请求频率限制（防暴力枚举）

**涉及文件**：`src/main/java/com/demo/demo/controller/BotAdminAuthConfig.java`

---

### 3. 消息业务去重

**现状问题**：`BotService.markInboundMessageIfNew()` 已实现基于 `messageId` / `itemMsgId` 的 TTL 去重（10 分钟），但 `CURRENT_STATE.md` 和 `FEATURE_MATRIX.md` 中标记该功能为"未实现"——说明基线文档未及时更新。当前实现：
- 使用 `ConcurrentHashMap<String, Long>` + 有序列表驱逐
- 容量上限 1000 条，TTL 10 分钟
- 对 `"0"` 和空的 messageId 不记录（无法去重）

**优化方案**：
- 补充 `InboundMessageDeduplicationTest` 覆盖：非法 ID、容量逐出、并发插入
- 对无法去重的消息（`messageId="0"` 或为空）增加日志警告
- 考虑将去重窗口改为可配置

**涉及文件**：`src/main/java/com/demo/demo/Service/BotService.java`、`src/test/java/com/demo/demo/Service/InboundMessageDeduplicationTest.java`

---

### 4. AIService 线程安全修复

**现状问题**：`AIService` 使用 Gson 的 `JsonArray` 存储对话历史，该对象**不是线程安全的**。同一用户的两条消息如果几乎同时到达，两个回复线程可能并发修改同一个 `JsonArray`，造成交叉追加、错误截断或 `ConcurrentModificationException`。

已有一个设计计划（`2026-07-20-persistent-conversation-memory.md`）通过引入 `ConversationMemoryStore` + 用户级 `synchronized` 锁来解决此问题。

**优化方案**：
- 按设计计划实现 `ConversationMemoryStore`（本地 JSON 文件持久化 + 内存缓存）
- `chat()` 方法对同一用户加 `synchronized` 保证串行
- 替换 `JsonArray` 为线程安全的集合

**涉及文件**：`src/main/java/com/demo/demo/Service/AIService.java`（计划新建 `Service/memory/` 包）

---

### 5. 会话过期自动重登

**现状问题**：当 iLink 凭据过期（`ILinkSessionExpiredException`），`BotService` 只清空状态并显示"登录已失效，请重新扫码"，**不会自动发起重登**。消息循环随后持续使用空凭据失败，直到管理员手动扫码。

**优化方案**：
- `markSessionExpired()` 中自动调用 `startLogin()` 发起重登
- 增加退避策略：第一次立即重试，后续指数退避（上限 5 分钟）
- 连续重登失败 N 次后停止自动重试，等待人工介入
- 通过 `/bot/status` 暴露重登次数和下次重试时间

**涉及文件**：`src/main/java/com/demo/demo/Service/BotService.java`

---

### 6. 日志隐私全面整改

**现状问题**：
- `BotService.displayLog()` 记录完整用户消息、回复内容、contextToken
- `BotService.buildQrCodeBase64()` 可能暴露二维码链接
- `WeatherUtil` 记录完整 JSON 响应和请求 URL
- `AIService.chat()` 记录完整用户消息和 AI 回复
- `ImageGenerationService` / `ImageRecognitionService` 记录图片 bytes 大小但未泄露内容（基本合规）

**优化方案**：
- 审计所有 `log.info`/`log.debug`/`displayLog` 调用点
- 用户消息只记录长度或前 N 个字符的哈希
- contextToken 统一使用 `maskToken()` 掩码
- 天气 JSON 只记录 HTTP 状态码和关键字段
- 引入日志分级开关：开发模式保留详细日志，生产模式只记录元数据
- `displayLog()`（前端日志）与 SLF4J 日志分离：前端可查看内容，服务端只记录脱敏信息

**涉及文件**：`BotService.java`、`AIService.java`、`WeatherUtil.java`、`BotController.java`

---

## P1 — 稳定性与可运维性

### 7. 优雅关闭（Graceful Shutdown）

**现状问题**：`@PreDestroy shutdownReplyExecutor()` 只关闭回复线程池，不停止登录线程和监听线程。应用关闭时可能出现：
- 登录轮询线程继续运行
- 消息监听线程继续尝试接收消息
- 正在执行的回复任务被强制中断
- 凭据未清空

**优化方案**：
- 新增 `volatile boolean shuttingDown` 标志
- 关闭顺序：登录线程 → 监听线程 → 回复线程池
- 回复线程池等待已提交任务完成（超时 5 秒后强制中断）
- 清空凭据、二维码、游标
- 可重复调用（幂等）

**涉及文件**：`src/main/java/com/demo/demo/Service/BotService.java`

---

### 8. 图片下载异步化

**现状问题**：`BotService.processImageItem()` 中的 `client.downloadMedia()` **在监听线程同步执行**。如果图片较大或 CDN 慢，会阻塞整个消息轮询循环，后续文本消息也无法处理。

**优化方案**：
- `processImageItem()` 只做基本校验（非空、参数有效性）后提交到回复线程池
- 下载、识别、回复在回复线程池中异步完成
- 同一用户的图片任务与后续文本任务通过已有的用户锁保证顺序

**涉及文件**：`src/main/java/com/demo/demo/Service/BotService.java`

---

### 9. LLM 调用增强错误分类与重试

**现状问题**：`AIService.chat()` 对所有 HTTP 非 2xx 和异常统一返回 `null`，不区分认证失败（401/403）、限流（429）、服务端错误（5xx）和超时。调用方无法针对性处理。

**优化方案**：
- 定义 `AIException` 异常层次：`AIAuthException`（401/403）、`AIRateLimitException`（429）、`AIServerException`（5xx）、`AITimeoutException`
- 对 429 和 5xx 自动重试（3 次，退避 500ms/1s/2s）
- 401/403 不重试，直接返回
- `BotController` 和 `VoiceMessageService` 根据异常类型给出不同降级文案

**涉及文件**：`src/main/java/com/demo/demo/Service/AIService.java`

---

### 10. 语音处理超时与取消

**现状问题**：语音处理使用虚拟线程 + `FutureTask.get()`（阻塞等待）。如果 ASR 或 TTS 下游服务长时间无响应，虚拟线程会一直阻塞。没有整体超时控制。

**优化方案**：
- `VoiceMessageService.process()` 增加整体超时（如 60 秒）
- 超时后取消 FutureTask，发送降级文本
- `AudioConverter` 的外部命令已有超时（30 秒可配置），确认生效

**涉及文件**：`src/main/java/com/demo/demo/Service/voice/VoiceMessageService.java`、`BotService.java`

---

### 11. 语音消息大小与时长校验

**现状问题**：
- `SiliconFlowAsrService` 有 50MB 上限，但上游传入的 SILK 原始数据没有业务级大小校验
- 微信可能发送超长语音（如 5 分钟），ASR API 可能按音频时长计费
- `AudioConverter` 不校验解码后的 PCM 时长

**优化方案**：
- `BotService.processVoiceMessage()` 中校验 `downloadMedia` 返回的字节数上限（如 5MB，约 5 分钟 SILK）
- `VoiceMessageService.process()` 增加 PCM 时长估算校验（PCM S16LE 16kHz mono = 32000 bytes/s）
- 超过时长上限（如 60 秒）的语音拒绝处理并提示用户

**涉及文件**：`BotService.java`、`VoiceMessageService.java`

---

### 12. 重连退避策略优化

**现状问题**：`BotService.startListening()` 异常捕获后固定等待 5 秒重试，不区分错误类型。如果是网络瞬断，5 秒过短；如果是凭据过期，重试无意义。

**优化方案**：
- 区分临时错误（网络异常、超时）和永久错误（凭据过期）
- 临时错误使用指数退避：1s → 2s → 4s → 8s → 16s → 32s（上限）
- 连续成功恢复后重置退避计数器
- 永久错误触发 `markSessionExpired()` 并启动重登

**涉及文件**：`src/main/java/com/demo/demo/Service/BotService.java`

---

### 13. Redis 依赖梳理

**现状问题**：`pom.xml` 依赖了 `spring-boot-starter-data-redis` 和 `spring-session-core`，但项目代码中未发现 Redis 使用。启动时可能因 Redis 不可用而失败，除非显式禁用。

**优化方案**：
- 确认 Redis 和 Spring Session 是否必要
- 如果不需要：从 pom.xml 移除两个依赖
- 如果需要：增加 Redis 连接配置和健康检查，启动失败时给出明确提示

**涉及文件**：`pom.xml`、`application.yml`

---

### 14. Spring Boot 版本固定

**现状问题**：使用 `4.0.8-SNAPSHOT` 快照版本，存在以下风险：
- SNAPSHOT 可能在远程仓库被覆盖，导致构建不可复现
- 快照版本通常未经过完整测试
- CI/CD 环境中可能下载到不同的快照构建

**优化方案**：
- 升级到最近的 Spring Boot 4.0.x RELEASE 版本（或降级到 3.4.x 稳定版）
- 如果必须在 SNAPSHOT 上开发，将本地 Maven 仓库提交到版本控制（使用 `mvn dependency:go-offline`）

**涉及文件**：`pom.xml`

---

## P2 — 代码质量与架构

### 15. BotService 职责拆分

**现状问题**：`BotService.java` 约 686 行，承担了过多职责：
- iLink 客户端管理（登录、凭据）
- 消息接收轮询
- 消息路由（文本/图片/语音分发）
- 消息去重
- 回复线程池管理
- 文本/图片/MP3 发送
- 二维码生成
- 状态查询 API 的数据提供

**优化方案**：
```
BotService (核心编排，保留 ~300 行)
├── ILinkSessionManager (登录、凭据、重登、关闭)
├── MessagePoller (消息轮询、cursor 管理、去重)
├── MessageSender (sendText, sendImage, sendMp3)
└── ReplyCoordinator (线程池、用户锁、任务提交)
```

**涉及文件**：`src/main/java/com/demo/demo/Service/BotService.java`（新建多个 Service 类）

---

### 16. 包名规范化

**现状问题**：
- 基础包为 `com.demo.demo`（无意义）
- Maven groupId 为 `com.xzb`，artifactId 为 `xialingying`，但代码包为 `com.demo.demo`
- `Utils` 包名首字母大写（Java 约定为小写 `utils`）
- `execption` 包名为拼写错误（应为 `exception`）
- `Xu.java` 在 `src/main/java/` 根目录
- `controller/zhoujiale.java` 风格不统一
- 存在重复测试类：`DemoApplicationTests` 和 `XialingyingApplicationTests` 都做 Context Load

**优化方案**：
- 统一包名为 `com.xzb.xialingying`
- 修正目录名：`Utils` → `utils`，`execption` → `exception`，`Service` → `service`
- 移除 `Xu.java` 和 `zhoujiale.java` 等临时/个人文件
- 合并或删除重复的 Application Context 测试

**涉及文件**：几乎所有 Java 文件（建议分步骤、分 PR 进行）

---

### 17. Controller 逻辑瘦身

**现状问题**：`BotController.initAutoReply()` 方法约 100 行，包含了：
- 图片生成请求检测 → 生成 → 回复
- 天气路由 → `extractCity()` → 天气查询 → LLM 润色
- 时间路由 → 格式化 → LLM 润色
- 普通聊天 → LLM
- 图片识别处理

所有这些逻辑以 Lambda 形式注册到 `BotService`，不利于测试和复用。

**优化方案**：
- 提取 `MessageRouter` 服务，集中管理路由规则
- 每个路由规则实现统一接口 `MessageHandler`
- 采用责任链模式：`ImageGenHandler → WeatherHandler → TimeHandler → ChatHandler`
- `BotController` 只负责注册处理器链

**涉及文件**：`src/main/java/com/demo/demo/controller/BotController.java`（新建 `Service/router/` 包）

---

### 18. 配置管理优化

**现状问题**：
- `application.yml` 中配置项分散在 `ai.*` 前缀下，层级不一致（`ai.image.api.key` 与 `ai.voice.asr.api-key` 风格混用）
- ASR/TTS API Key 使用 kebab-case（`api-key`），其他用 camelCase（`api.key`）
- `application-local.example.yml` 存在但项目未激活 local profile
- 未使用 `@Validated` 校验配置有效性

**优化方案**：
- 统一为 kebab-case（Spring Boot 推荐风格）：`ai.voice.asr.api-key`
- 增加 `@ConfigurationProperties` 的 JSR-303 校验注解
- 在 `DemoApplication.onReady()` 中校验关键配置非空并给出明确错误提示
- 增加 `application-local.example.yml` 到 README 的配置说明

**涉及文件**：`application.yml`、`VoiceProperties.java`、`DemoApplication.java`

---

### 19. 统一异常处理覆盖

**现状问题**：`GlobalExpectionHandler` 只处理 Controller 层的异常（`@RestControllerAdvice`）。`AIService`、`VoiceMessageService`、`ImageGenerationService` 等 Service 层的异常依赖各自的 try-catch 处理，缺乏统一性。

**优化方案**：
- 不改变 Service 层异常处理模式（Service 不应抛出异常到 Controller）
- 在 Controller 层增加对 AI/语音/图片相关异常的映射
- 补充 `HttpMessageNotReadableException`、`HttpRequestMethodNotSupportedException` 等 Spring MVC 标准异常的处理

**涉及文件**：`src/main/java/com/demo/demo/execption/GlobalExpectionHandler.java`

---

### 20. 前端重构

**现状问题**：`bot.html` 是一个约 300+ 行的单文件 Thymeleaf 模板，内嵌 CSS 和 JavaScript，没有模块化。JS 使用全局变量和 `innerHTML`。

**优化方案**：
- 将 CSS 提取为独立 `bot.css` 文件
- 将 JS 提取为独立 `bot.js` 文件（使用 ES6 模块或 IIFE）
- 消息列表使用 DocumentFragment 批量更新 DOM
- 使用 `textContent` 替代 `innerHTML`（配合 P0-1 XSS 修复）

**涉及文件**：`src/main/resources/templates/bot.html`（新建 `static/css/bot.css`、`static/js/bot.js`）

---

### 21. 测试覆盖率提升

**现状问题**：以下核心模块缺乏自动测试：
- `AIService` — 0 个测试（LLM 调用、历史管理、错误处理）
- `BotController` — 只有 auth 测试，没有消息处理链路测试
- `VoiceMessageService` — 缺少端到端编排测试
- `AudioConverter` — 缺少 SILK 头部校验边界测试
- `BotService` 的登录/监听/重连 — 缺少真实场景模拟测试
- 天气功能 — WeatherController 缺少 MockMvc 测试

**优化方案**：

| 测试目标 | 测试类型 | 新增测试数（估计） |
|---------|---------|-----------------|
| `AIService` | 本地 HTTP Server 组件测试 | 10-12 |
| `VoiceMessageService` 编排 | Mock 注入测试 | 8-10 |
| `AudioConverter` 边界 | 参数化单元测试 | 6-8 |
| `BotController` 消息链路 | MockMvc + Mockito | 8-10 |
| `BotService` 重连/关闭 | Mockito 测试 | 6-8 |
| `WeatherController` | MockMvc 测试 | 4-6 |

**涉及文件**：`src/test/java/com/demo/demo/` 下新增多个测试类

---

### 22. 清理孤立代码

**现状问题**：
- `weather-cli/` — 独立 Maven 项目，不属于主项目但混在同一仓库
- `Xu.java` — 根目录下孤立文件
- `controller/zhoujiale.java` — 风格不统一
- `HelloController.java` — 演示用 Controller
- Spring Session / Redis 依赖可能未使用
- `ILinkSessionLifecycleTest.java` — 测试类名覆盖范围与内容可能不符

**优化方案**：
- 将 `weather-cli/` 移到独立仓库或归档到 `archive/` 目录
- 删除 `Xu.java`、`HelloController.java`、`zhoujiale.java`
- 确认并移除未使用的 Maven 依赖
- 审查测试类命名和覆盖范围的对应关系

**涉及文件**：如上所述

---

## P3 — 功能增强

### 23. 对话记忆持久化

**现状问题**：`AIService` 使用内存 `ConcurrentHashMap` 保存对话历史，应用重启后全部丢失。

已有完整的设计计划和实施步骤（`docs/superpowers/plans/2026-07-20-persistent-conversation-memory.md`）。

**优化方案**（按计划实施）：
1. 新建 `ConversationMessage` record
2. 新建 `ConversationMemoryStore`（JSON 文件存储 + 原子写入）
3. 修改 `AIService` 注入 Store，替代内存 Map
4. 每用户最多保留 10 轮完整对话
5. 可配置存储路径，默认 `./data/conversation-memory.json`

**涉及文件**：新建 `Service/memory/` 包，修改 `AIService.java`

---

### 24. 智能路由升级 — Function Calling

**现状问题**：天气和时间的触发依赖关键词匹配（`text.contains("天气")`、`text.contains("几点")`），存在以下局限：
- "今天热不热" 不会触发天气查询
- "现在什么时候" 不会触发时间查询
- 生图识别也不够自然："帮我画个猫" 可能匹配也可能不匹配

**优化方案**：
- 利用 DeepSeek 的 Function Calling 能力（或 OpenAI 兼容的 Tool Use）
- 定义 4 个 Tool：`get_weather`、`get_time`、`generate_image`、`search`
- LLM 自行判断是否调用 Tool 及参数
- 图片生成和天气查询不再依赖正则路由

**注意事项**：
- Function Calling 增加一次 API 调用（Tool 选择 → 执行 → 回复，共 2 次 LLM 调用）
- 保留关键词路由作为快速路径（省一次 API 调用的延迟和费用）
- 将 Function Calling 设为可配置开关

**涉及文件**：`AIService.java`、`BotController.java`（新建 `Service/tool/` 包）

---

### 25. 语音回复原生支持（SILK 直发）

**现状问题**：当前使用 `uploadMedia(type=3, mp3) + sendFileMessage` 发送 MP3 文件。MP3 文件在微信中显示为文件消息，用户需要点击下载播放。体验不如原生语音消息。

微信 iLink SDK 1.0.1 存在 `sendVoiceMessage()`，但根据设计文档，原生语音回复通过 iLink 不可靠。

**优化方案**：
- 保留 MP3 文件回复作为主路径（已稳定）
- 实现 SILK 编码路径：TTS PCM → `AudioConverter.pcmToSilk()` → iLink `sendVoiceMessage()`
- `sendVoiceMessage()` 成功后不发 MP3；失败则 fallback 到 MP3
- 在 `VoiceMessageService.Result` 中增加 `silkAudio` 字段
- 优先级：SILK 原生 > MP3 文件 > 纯文本

**涉及文件**：`VoiceMessageService.java`、`BotService.java`、`AudioConverter.java`

---

### 26. 多模态输入增强

**现状问题**：语音消息目前只提取转写文字送入 LLM。如果用户发送"这张图片里的文字是什么"的同时发了一张图片，系统只处理图片识别（因为 `isImage()` 优先于 `isText()`）。

**优化方案**：
- 支持图文混合消息：同时收到图片和文本时，将文本作为识别 prompt 的补充信息
- 语音结合历史：将 ASR 文本与对话历史合并送入 LLM
- 语音 → 图片联动：用户在语音中说"帮我生成一张..."，识别后走图片生成流程

**涉及文件**：`BotService.java`、`VoiceMessageService.java`、`ImageRecognitionService.java`

---

### 27. 消息队列与削峰

**现状问题**：回复线程池固定 2 线程 + 20 容量队列，`AbortPolicy` 拒绝后发"当前任务较多"文本。在高并发场景下（如群聊 Bot），大多数消息会被拒绝。

**优化方案**：
- 队列扩容到 100-200，或使用 `LinkedBlockingQueue`
- 拒绝策略改为 `CallerRunsPolicy`（由监听线程兜底执行，产生背压）
- 增加队列深度监控（通过 `/bot/status` 暴露）
- 增加 per-user 速率限制（每用户每秒最多 1 条处理中的消息）

**涉及文件**：`BotService.java`

---

### 28. 健康检查与可观测性

**现状问题**：项目缺少健康检查端点，不利于容器化部署和监控。没有指标暴露（处理延迟、队列深度、API 调用成功率）。

**优化方案**：
- 新增 `GET /bot/health` 端点，检查：
  - iLink 登录状态
  - AI API 连通性（可选，避免每次健康检查都调 API）
  - 语音 ASR/TTS 服务连通性（可选）
  - 回复队列深度
- 引入 Micrometer + Prometheus 指标（Spring Boot 原生支持）
- 关键指标：消息处理量、平均/最大延迟、ASR/TTS/LLM 调用成功率和延迟

**涉及文件**：`BotController.java`、`pom.xml`（如需 Micrometer 依赖）

---

### 29. Admin 控制台增强

**现状问题**：Bot 管理页面功能基础，只能查看消息和手动回复。

**优化方案**：
- 增加消息搜索/过滤功能
- 显示语音处理状态（ASR → LLM → TTS 各阶段耗时）
- 增加对话历史查看（从持久化存储中加载）
- 增加一键清除某用户对话历史的按钮
- 增加服务状态面板：登录状态、运行时间、消息统计
- 移动端适配（响应式布局）

**涉及文件**：`templates/bot.html`、`BotController.java`

---

### 30. 本地 SILK Encoder 集成

**现状问题**：`AudioConverter.pcmToSilk()` 已实现但仅在 TTS → SILK 原生回复路径中使用。当前 MP3 路径不需要 SILK encoder。但 `VoiceProperties` 中包含 `silkEncoderPath` 配置。

**优化方案**：
- 保持 `pcmToSilk()` 的实现（已有完整实现和验证逻辑）
- 如需支持原生语音回复（见 #25），则可直接使用
- 如果不打算支持原生语音，移除 `pcmToSilk()` 和 `silkEncoderPath` 配置以减少维护负担

**涉及文件**：`AudioConverter.java`、`VoiceProperties.java`

---

## 实施路线建议

### 第一阶段（1-2 周）：安全修复 + 核心稳定性

| 优先级 | 编号 | 优化项 |
|--------|------|--------|
| P0 | 4 | AIService 线程安全修复（对话记忆持久化） |
| P0 | 1 | Bot 管理页面 XSS 防护 |
| P0 | 6 | 日志隐私全面整改 |
| P1 | 7 | 优雅关闭 |
| P1 | 8 | 图片下载异步化 |
| P1 | 9 | LLM 调用错误分类与重试 |

**里程碑**：核心安全风险消除，应用可安全对外暴露。

---

### 第二阶段（2-3 周）：可观测性 + 代码质量

| 优先级 | 编号 | 优化项 |
|--------|------|--------|
| P1 | 13 | Redis 依赖梳理 |
| P2 | 15 | BotService 职责拆分（先拆分 MessageSender） |
| P2 | 18 | 配置管理优化 |
| P2 | 21 | 测试覆盖率提升（优先 AIService、VoiceMessageService） |
| P2 | 22 | 清理孤立代码 |
| P1 | 12 | 重连退避策略优化 |
| P0 | 5 | 会话过期自动重登 |

**里程碑**：代码结构清晰，核心模块有测试覆盖。

---

### 第三阶段（3-4 周）：包名规范 + 架构优化

| 优先级 | 编号 | 优化项 |
|--------|------|--------|
| P2 | 16 | 包名规范化 |
| P2 | 17 | Controller 逻辑瘦身（MessageRouter 责任链） |
| P2 | 19 | 统一异常处理覆盖 |
| P2 | 20 | 前端重构 |
| P3 | 23 | 对话记忆持久化（如第一阶段未完成） |

**里程碑**：代码达到生产级标准，包结构清晰。

---

### 第四阶段（长期）：功能增强

| 优先级 | 编号 | 优化项 |
|--------|------|--------|
| P3 | 24 | 智能路由升级 — Function Calling |
| P3 | 25 | 语音回复原生支持（SILK 直发） |
| P3 | 27 | 消息队列与削峰 |
| P3 | 28 | 健康检查与可观测性 |
| P3 | 29 | Admin 控制台增强 |
| P3 | 26 | 多模态输入增强 |

**里程碑**：功能完整，用户体验优秀。

---

## 附录 A：技术债务清单速查

| 项 | 严重度 | 影响范围 | 工作量估计 |
|----|--------|---------|-----------|
| `com.demo.demo` 包名 | 低 | 全项目 | 2-3h |
| `Utils/` 首字母大写 | 低 | 3 文件 | 0.5h |
| `execption/` 拼写错误 | 低 | 4 文件 | 0.5h |
| `Xu.java` 孤立文件 | 低 | 1 文件 | 0.1h |
| `weather-cli/` 孤立项目 | 低 | 独立目录 | 0.5h |
| 重复 Context Test | 低 | 2 文件 | 0.2h |
| Spring Boot SNAPSHOT | 中 | 构建 | 1h |
| Redis 未使用依赖 | 中 | 启动/构建 | 1h |
| JsonArray 非线程安全 | 高 | 多用户并发 | 已纳入 P0-4 |
| BotService 686 行 | 中 | 可维护性 | 4-6h |

---

## 附录 B：新增文件预估

| 文件 | 所属优化项 |
|------|-----------|
| `Service/memory/ConversationMessage.java` | #23 |
| `Service/memory/ConversationMemoryStore.java` | #23 |
| `Service/messaging/MessageSender.java` | #15 |
| `Service/messaging/ReplyCoordinator.java` | #15 |
| `Service/router/MessageRouter.java` | #17 |
| `Service/router/ImageGenHandler.java` | #17 |
| `Service/router/WeatherHandler.java` | #17 |
| `Service/router/TimeHandler.java` | #17 |
| `Service/router/ChatHandler.java` | #17 |
| `Service/tool/ToolRegistry.java` | #24 |
| `Service/tool/WeatherTool.java` | #24 |
| `Service/tool/TimeTool.java` | #24 |
| `Service/tool/ImageGenTool.java` | #24 |
| `static/css/bot.css` | #20 |
| `static/js/bot.js` | #20 |
| 10+ 新增测试类 | #21 |
