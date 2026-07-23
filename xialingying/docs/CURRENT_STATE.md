# Claw 助手当前项目基线

> 基线日期：2026-07-18  
> 项目根目录：`E:\summer-projects\xialingying`  
> 判定优先级：当前源码 → 实际编译/测试结果 → 运行日志与探测产物 → 无法确认。  
> 安全约束：本文不记录任何真实 API Key、Token、Cookie、AES Key 或其他凭据。

## 1. 基本信息与启动方式

| 项目 | 当前基线 | 证据 |
| --- | --- | --- |
| 启动类 | `com.demo.demo.DemoApplication` | `pom.xml` 的 `spring-boot-maven-plugin.mainClass`；`src/main/java/com/demo/demo/DemoApplication.java` 的 `DemoApplication.main()` |
| Java | 21 | `pom.xml` 的 `java.version`；`mvn --version` 实测 Maven 使用 Oracle JDK 21.0.11 |
| Spring Boot | `4.0.8-SNAPSHOT` | `pom.xml` 的 parent |
| iLink SDK | `io.github.lith0924:wechat-ilink-sdk:1.0.1` | `pom.xml` |
| Web 端口 | 8080 | `src/main/resources/application.yml` |

IDEA 启动方式：运行 `DemoApplication.main()`。Maven 启动方式：

```powershell
mvn spring-boot:run
```

应用启动后访问 `http://localhost:8080/bot` 才会进入扫码登录。`BotController.botPage()` 在 Bot 尚未登录时调用 `BotService.startLogin()`；应用上下文启动本身不会自动申请二维码。

本机 `mvn` 为 3.9.11，可以构建项目。`.\mvnw.cmd` 当前需要下载 Maven 3.9.16，实际验证时因下载连接关闭而未能启动，这不是源码编译失败。终端直接运行 `java -version` 指向 JDK 24，而 Maven 使用 JDK 21，开发环境存在 Java 路径不一致。

## 2. 当前真实的 iLink 接入方式

`BotService.BotService()` 通过 `new ILinkClient()` 直接创建 SDK 客户端，没有 Spring 配置 Bean、SDK Listener 注解或单独的连接管理器。实际消息接收由项目自行维护长轮询线程。

当前项目依赖的权威版本是本机 Maven 依赖的 SDK 1.0.1。用户最初指定的 `E:\wechat-ilink-sdk-java-reference` 当前不存在；另一个 `E:\summer-projects\wechat-ilink-sdk-java-reference` 工作区当前是 SDK 2.3.3，只能作为辅助材料，不能覆盖 1.0.1 JAR 的事实。

相关文件和方法：

- `src/main/java/com/demo/demo/Service/BotService.java`
  - `BotService()`
  - `startLogin()`
  - `startListening()`
  - `sendReply()`
  - `sendImageReply()`
- SDK 1.0.1 实际 JAR：
  - `C:\Users\Lenovo\.m2\repository\io\github\lith0924\wechat-ilink-sdk\1.0.1\wechat-ilink-sdk-1.0.1.jar`

## 3. 登录、凭据、监听和发送调用链

### 3.1 扫码登录

```text
GET /bot
→ BotController.botPage()
→ BotService.startLogin()
→ ILinkClient.getBotQrCode()
→ ILinkClient.getQrCodeStatus()（最多 150 次，每次间隔 2 秒）
→ ILinkClient.createCredentials()
→ BotService.credentials.set(...)
→ BotService.startListening()
```

凭据保存在 `BotService.credentials` 的 `AtomicReference<LoginCredentials>` 中。SDK 1.0.1 的凭据对象包含 botToken、userId、apiBaseUrl 和 qrcode；项目没有将其写入 Redis、数据库或本地凭据文件。应用重启后是否必须重新扫码，依据源码判断为“是”，仍需按人工清单复测。

### 3.2 消息监听

```text
BotService.startListening()
→ 守护线程 while (loggedIn)
→ client.receiveMessages(credentials, cursor)
→ 更新 ReceiveMessagesResult.nextCursor
→ 过滤 dto.isUserMessage()
→ 遍历 dto.getItemList()
→ 按 isText()/isImage()/isVoice() 分支
```

`fromUserId` 来自 `WeixinMessageDto.getFromUserId()`，作为回复目标和 LLM 历史用户键。`contextToken` 来自 `getContextToken()`，发送文本、图片时原样传回 SDK。SDK DTO 还提供 messageId、sessionId 和 item msgId，但当前业务代码没有读取。

### 3.3 消息发送

- 文本：`BotService.sendReply()` → `ILinkClient.sendTextMessage(...)`
- 图片：`BotService.sendImageReply()` → `ILinkClient.uploadMedia(..., 1, ...)` → `sendImageMessage(...)`
- 语音：SDK 1.0.1 存在 `sendVoiceMessage(...)`，主项目没有调用，属于未实现的业务能力

## 4. 文本消息完整调用链

```text
BotService.startListening()
→ item.isText()
→ BotService.processTextMessage(fromUser, contextToken, text)
→ submitReplyTask()
→ 固定回复线程池
→ BotController.initAutoReply() 注册的 ReplyHandler
→ 图片生成路由 / 天气路由 / 时间路由 / 普通 LLM
→ AIService.chat(fromUser, prompt)
→ BotService.sendReply()
→ ILinkClient.sendTextMessage()
```

`src/test/java/com/demo/demo/Service/ImageAutoReplyTest.java` 的
`textMessageReturnsBeforeSlowReplyFinishes()` 验证了文本业务处理会异步提交，而不会等待慢处理器完成。该测试使用模拟 SDK，不等于真实微信文本端到端验证。

## 5. 图片接收、识别、生成和回复调用链

### 5.1 图片接收与识别

```text
BotService.startListening()
→ item.isImage()
→ BotService.processImageItem()
→ client.downloadMedia(image.encryptQueryParam, image.aesKey)
→ BotService.processImageMessage()
→ submitReplyTask()
→ ImageRecognitionService.recognize()
→ OpenAI 兼容 POST /v1/chat/completions
→ BotService.sendReply()
```

`ImageRecognitionService.recognize()` 支持 JPEG、PNG、WebP，输入上限 20 MB；JPEG/PNG 可进行 EXIF 方向修正和最长边 1600 像素缩放。WebP 只校验容器结构。HTTP 连接、读取、写入超时分别为 30、120、60 秒；网络异常、HTTP 429 和 5xx 最多尝试三次。

`ImageRecognitionServiceTest` 20 项自动测试通过，覆盖格式、缩放、EXIF、透明通道、错误响应和有限重试。`ImageAutoReplyTest` 验证了下载参数及识别后文本回复衔接。真实微信 CDN 和真实视觉平台本轮未人工验证。

### 5.2 文本生成图片

```text
文本 ReplyHandler
→ ImageGenerationService.isImageRequest()
→ extractPrompt()
→ generateImage()
→ OpenAI 兼容 POST /v1/images/generations
→ 解析 b64_json / data.url / SiliconFlow images.url
→ BotService.sendImageReply()
→ uploadMedia(mediaType=1)
→ sendImageMessage()
```

默认配置模型为 `gpt-image-1`；当前已跟踪的 IDEA 运行配置将图片生成指向 SiliconFlow 的 `Kwai-Kolors/Kolors`。Kolors 只注入图片生成服务，不用于文本或图片识别。图片生成连接、读取、写入超时为 30、180、60 秒，结果大小上限 20 MB。

`ImageGenerationServiceTest` 12 项和 `ImageAutoReplyTest` 的生成发送测试通过，但都使用本地 HTTP Server 或 Mockito；真实 Kolors 和真实微信图片发送仍需人工验证。

## 6. 语音下载、解码、转换、识别和回复调用链

当前正式运行链只到“下载和保存探测样本”：

```text
BotService.startListening()
→ item.isVoice()
→ BotService.processVoiceProbe()
→ Thread.ofVirtual()
→ client.downloadMedia(voice.encryptQueryParam, voice.aesKey)
→ target/voice-probe/*.bin
→ 保存官方 voice.text（新代码尚未通过重启后的真实样本验证）
```

历史人工探测链：

```text
微信语音字节
→ 识别文件头：微信前导字节 + #!SILK_V3
→ 外部专用 SILK decoder
→ 16 kHz、单声道、16-bit little-endian PCM
→ 外部 FFmpeg
→ WAV
```

证据：

- `src/main/java/com/demo/demo/Service/BotService.java`
  - `processVoiceProbe()`
- `docs/voice-feature-investigation.md`
- `target/voice-probe` 中三份 `.bin`、三份 `.pcm`、三份 `.wav`

当前不存在 `AudioConverter`、`AsrService`、`VoiceProcessingService`、`VoiceMessageHandler` 或 TTS 实现。没有语音→ASR→LLM→文字回复链，也没有语音失败时的用户提示。SDK 官方转写字段在三条历史样本中均非空，但没有保存可核对的完整内容，准确率无法确认；“官方字段、自建 ASR、两者保留”的决策尚未完成。

## 7. 天气功能

天气已经接入微信文本消息链：

```text
BotController.initAutoReply()
→ text.contains("天气")
→ BotController.extractCity()
→ WeatherUtil.getWeather()
→ AIService.chat() 润色
→ BotService.sendReply()
```

它是关键词路由，不是 Agent、Tool Registry 或 Function Calling。由于 `AIService.isConfigured()` 检查位于天气分支之前，没有文本 LLM 配置时，微信天气也不会执行。

天气还提供 REST 入口：

- `WeatherController.getWeather()`
- `WeatherController.getWeatherByPath()`
- `WeatherController.batchQuery()`

`WeatherUtilTest` 18 项和 `WeatherStabilityTest` 7 项均通过，并实际访问 `wttr.in`。这验证了底层查询和解析，不等于 WeatherController HTTP 或微信天气端到端验证。

## 8. 模型基线

| 能力 | 当前准确名称 | 配置/实现 |
| --- | --- | --- |
| 文本 LLM | `deepseek-chat` | `application.yml`；`AIService.chat()` |
| 图片生成默认值 | `gpt-image-1` | `application.yml`；`ImageGenerationService.generateImage()` |
| 当前 IDEA 图片生成配置 | `Kwai-Kolors/Kolors` | `.idea/workspace.xml` 的非凭据运行配置 |
| 图片识别 | `Qwen/Qwen3-VL-8B-Instruct` | `application.yml`；`ImageRecognitionService.recognize()` |

文本和识图调用 OpenAI 兼容的 `/v1/chat/completions`，生图调用 `/v1/images/generations`。本文不记录任何凭据值。

## 9. 会话上下文与多用户隔离

`AIService` 使用：

```java
ConcurrentHashMap<String, JsonArray> historyMap
```

Map Key 是微信 `fromUserId`，不同用户正常情况下使用不同 `JsonArray`。System Prompt 只在该用户历史首次创建时加入。`MAX_HISTORY=10` 实际限制的是十个非 System 消息对象，约五轮完整问答。历史只存在 JVM 内存，重启后不保留；`clearHistory()` 存在，但没有业务入口调用。

隔离风险：`ConcurrentHashMap` 本身线程安全，但 Gson `JsonArray` 不是线程安全对象。同一用户两条消息被两个工作线程并发处理时，可能交叉追加、错误删除或产生后发先回。不同用户不直接共享历史，但共享回复线程池。

## 10. 异步、顺序与去重

| 操作 | 当前线程模型 |
| --- | --- |
| 扫码登录 | 普通守护线程 |
| iLink 长轮询 | 普通守护线程 |
| 文本 LLM、图片生成、图片识别 | 2 个线程的固定池，队列容量 20 |
| 图片 CDN 下载 | 监听线程同步执行 |
| 语音探测 | JDK 21 虚拟线程 |

线程池由 `BotService.createReplyExecutor()` 创建，拒绝策略为 `AbortPolicy`；`submitReplyTask()` 捕获拒绝并同步发送“当前任务较多”。图片 CDN 下载仍会阻塞监听线程。

当前没有：

- messageId 或 msgId 去重
- 用户级串行队列
- 相同会话顺序保证
- 幂等处理记录

`cursor` 可降低正常轮询中的重复概率，但不能替代业务去重。重复消息若再次到达，会再次调用 LLM/图片服务并再次回复。

## 11. 重连、超时、重试和异常

### iLink

- 登录状态最多轮询 150 次，每次间隔 2 秒。
- SDK 拉取和发送内部最多重试三次。
- `BotService.startListening()` 捕获异常后等待 5 秒继续轮询。
- 临时网络故障可能恢复；凭据过期时不会自动刷新或重新扫码，可能持续失败。
- `@PreDestroy BotService.shutdownReplyExecutor()` 只关闭回复线程池，没有明确停止登录线程、监听线程或清空凭据。

### 外部业务服务

| 服务 | 超时 | 重试 | 降级 |
| --- | --- | --- | --- |
| 文本 LLM | connect 30s / read 60s | 无 | 非成功、空响应、解析异常统一返回 null |
| 图片生成 | 30s / 180s / 60s | 429、5xx 最多三次；生成 POST 网络失败不重试 | 返回文字错误提示 |
| 图片识别 | 30s / 120s / 60s | 网络、429、5xx 最多三次 | 返回识别失败文字 |
| 天气 | connect 10s / read 10s | 无 | 转换为业务异常 |
| 语音 | 由 SDK 下载超时控制 | 无业务重试 | 当前没有用户降级回复 |

文本业务 Handler 的顶层异常当前只记录日志，不保证回复用户。图片分支提供下载失败和识别失败提示。

## 12. 安全基线

1. 修复前，`.idea/workspace.xml` 被 Git 跟踪，当前文件和 Git HEAD 均检测到非空的 API Key 环境变量值。当前工作树已将该文件从 Git 索引移除，本地文件仍保留并命中 `.gitignore` 的 `.idea/` 规则；历史中的旧值仍应按已暴露凭据处理，供应商侧撤销状态无法从仓库确认。本文不显示任何凭据值。
2. `application.yml` 被 Git 跟踪，但使用环境变量表达式，没有发现直接写入的真实 Key。
3. `BotController` 的 `/bot/status`、`/bot/messages`、`/bot/send`、`/bot/restart` 未发现认证保护。
4. `templates/bot.html` 使用 `innerHTML` 插入微信用户 ID、消息、日志和 contextToken，存在 DOM XSS 及上下文令牌泄漏风险。
5. 日志会记录二维码链接、contextToken、用户消息、回复、cursor 和部分外部错误响应。
6. `ImageGenerationService.downloadImage()` 直接请求供应商返回的 URL，没有目标主机白名单，存在 SSRF 风险。
7. 主项目未发现打印完整 Authorization Header、API Key 或 iLink botToken 的代码。

### 12.1 P0 凭据跟踪回归检查

本问题不用 JUnit 复现：它属于 Git 索引和仓库内容安全问题，不经过 Java 业务方法。使用以下脱敏检查作为回归测试，检查过程只能输出是否跟踪、命中数量和环境变量名，禁止输出具体值：

```powershell
git ls-files --error-unmatch .idea/workspace.xml
git check-ignore -v .idea/workspace.xml
```

并对当前文件和 `HEAD:.idea/workspace.xml` 中名称包含 `KEY`、`TOKEN`、`COOKIE`、`SECRET` 或 `PASSWORD` 的非空 `<env>` 字段计数。

修复前实测基线：

```text
TEST_TRACKED_WORKSPACE=True
TEST_LOCAL_NONEMPTY_CREDENTIAL_FIELDS=3
TEST_HEAD_NONEMPTY_CREDENTIAL_FIELDS=3
REPRO_TEST=FAIL_EXPECTED_BEFORE_FIX
```

涉及的配置名称为 `AI_API_KEY`、`IMAGE_API_KEY`、`VISION_API_KEY`；本文不记录对应值。修复验收要求：

1. `git ls-files .idea/workspace.xml` 无输出；
2. `git check-ignore -v .idea/workspace.xml` 命中 `.gitignore` 的 `.idea/`；
3. 当前待提交树不再包含该工作区文件及其中的凭据；
4. 供应商侧旧凭据已撤销，该项只能在供应商控制台人工确认。

修复后实测：

```text
TEST_TRACKED_WORKSPACE=False
TEST_LOCAL_FILE_EXISTS=True
TEST_IGNORE_RULE_MATCHED=True
TRACKED_IDEA_ENV_SECRET_SCAN=PASS
REPRO_TEST=PASS
```

说明：Git 索引侧修复已经完成。2026-07-18 使用本地新配置进行了脱敏真实调用验证：DeepSeek 文本接口返回 HTTP 200 且回复非空；SiliconFlow `Kwai-Kolors/Kolors` 生图接口返回 HTTP 200，并成功下载 1,238,860 字节图片；该图片随后交给 SiliconFlow `Qwen/Qwen3-VL-8B-Instruct`，识图接口返回 HTTP 200 且回复非空。验证过程未输出或保存凭据值。旧凭据是否已在供应商控制台撤销仍无法从仓库确认。

## 13. 编译和测试基线

实际执行：

```powershell
mvn compile
mvn test
```

结果：

- `mvn compile`：`BUILD SUCCESS`。Maven判断 class 已是最新，本轮是增量编译，不是空 `target` 干净构建。
- `mvn test`：79 tests，0 failures，0 errors，0 skipped。
- Surefire 报告：`target/surefire-reports`
- 31 个 `.dumpstream` 是 Windows 跨盘绝对 classpath 警告；没有崩溃 `.dump`。
- 未执行 `clean`，九个语音探测文件保持存在。

测试构成：

| 测试类 | 数量 | 性质 |
| --- | ---: | --- |
| `DemoApplicationTests` | 1 | Spring 上下文 |
| `XialingyingApplicationTests` | 1 | 重复的 Spring 上下文 |
| `GlobalExceptionHandlerTest` | 13 | 单元测试 |
| `ImageAutoReplyTest` | 7 | Mockito 消息链路测试 |
| `ImageGenerationServiceTest` | 12 | 本地 HTTP Server 组件测试 |
| `ImageRecognitionServiceTest` | 20 | 本地 HTTP Server 组件测试 |
| `WeatherStabilityTest` | 7 | 真实网络/并发测试 |
| `WeatherUtilTest` | 18 | 真实天气服务集成测试 |

没有自动覆盖：`AIService`、真实 iLink 扫码/收发、会话过期、去重、同用户顺序、语音正式链、WeatherController HTTP、微信天气端到端、Bot 页面安全。

## 14. 当前问题优先级

### P0：影响核心闭环、安全或数据正确性

1. `.idea/workspace.xml` 的 Git 跟踪已在当前工作树中移除，新凭据的三类真实模型调用已通过；但历史中的旧凭据仍需在供应商侧撤销，撤销完成前，凭据泄漏风险未完全关闭。
2. Bot 管理接口无认证，可查看消息、二维码、contextToken 并发起发送或重登。
3. `bot.html` 对用户内容使用未转义 `innerHTML`，存在 XSS。
4. 没有 messageId 去重，重复消息会重复调用模型、重复计费和重复回复。
5. 同一用户没有顺序控制，且会并发修改非线程安全历史数组。
6. 会话过期不能自动重登，核心消息循环可能持续使用失效凭据。
7. 语音消息没有进入 LLM，也没有向用户回复或降级提示。
8. 图片结果 URL 直接下载，存在 SSRF 风险。

### P1：影响稳定性和正常开发

1. 图片 CDN 下载同步阻塞 iLink 监听线程。
2. `AIService`、真实 iLink、重连和关闭缺少自动测试。
3. 文本 LLM 错误未区分认证、限流、超时和空响应。
4. 真实图片平台与微信发送没有端到端测试。
5. WeatherController 和微信天气链路没有端到端测试。
6. 天气路由被文本 LLM 配置检查前置限制。
7. Maven Wrapper 下载失败，干净环境构建未验证。
8. Spring Boot 使用 `SNAPSHOT`。
9. `application-local.yml` 使用说明没有配套激活 local Profile；测试日志确认当前只使用 default Profile。
10. 日志记录较多用户内容、contextToken、二维码链接和完整天气 JSON。
11. `@PreDestroy` 没有完整终止 iLink 相关线程。
12. SDK 参考源码位置和版本与实际依赖不一致。

### P2：体验增强和扩展

1. TTS 和语音回复。
2. 天气/时间升级为受控 Tool 或 Function Calling。
3. 历史持久化或用户可调用的历史清理。
4. 包名、类名、目录命名规范化。
5. 清理空类、演示 Controller 和重复上下文测试。
6. 明确 `weather-cli` 的独立项目定位。
7. 确认或移除当前未使用的 Redis、Spring Session 依赖。
8. 增加处理耗时、队列深度、失败率等指标。
9. 统一 IDEA、Maven 和终端 JDK。
