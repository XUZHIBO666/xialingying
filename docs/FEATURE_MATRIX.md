# Claw 助手功能矩阵

> 状态仅使用约定枚举。自动测试结果以 2026-07-18 实际执行的 `mvn test` 为准：79 项通过，0 failure，0 error。  
> “人工验证”只记录已有证据；没有日志或产物支撑的项目标记为待验证。

| 功能 | 状态 | 入口文件 | 入口方法 | 依赖组件 | 自动测试 | 人工验证 | 已知问题 | 下一步 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Maven 增量编译 | 已完整验证 | `pom.xml` | Maven `compile` 生命周期 | Maven 3.9.11、JDK 21.0.11 | `mvn compile`：BUILD SUCCESS | 不需要 | 未执行 clean；不是干净构建 | 在允许保留诊断产物的前提下设计独立干净构建环境 |
| Spring 应用上下文 | 已完整验证 | `src/main/java/com/demo/demo/DemoApplication.java` | `main()` | Spring Boot 4.0.8-SNAPSHOT | 两个 contextLoads 通过 | 未启动真实端口 | 只验证 MOCK 上下文；使用 SNAPSHOT | 增加受控 Web 启动检查 |
| Bot 管理页面 | 自动测试通过但未人工验证 | `src/main/java/com/demo/demo/controller/BotController.java` | `botPage()` | Spring MVC、Thymeleaf | 上下文可创建 Controller | 待验证 | 无认证；前端 innerHTML XSS | 按人工清单验证并先处理安全边界 |
| iLink 客户端初始化 | 自动测试通过但未人工验证 | `src/main/java/com/demo/demo/Service/BotService.java` | `BotService()` | iLink SDK 1.0.1 | Mockito 测试可注入客户端 | 历史语音产物间接证明曾工作 | 客户端直接 new，生命周期分散 | 增加受控客户端生命周期测试 |
| iLink 扫码登录 | 人工验证通过但缺少自动测试 | `src/main/java/com/demo/demo/Service/BotService.java` | `startLogin()` | iLink SDK、ZXing | 无 | 历史真实语音样本说明曾完成登录和收消息 | 当前轮未扫码；凭据不持久化 | 执行扫码、过期、重启清单 |
| iLink 消息长轮询 | 人工验证通过但缺少自动测试 | `src/main/java/com/demo/demo/Service/BotService.java` | `startListening()` | iLink SDK 1.0.1 | 无真实 SDK 测试 | 三个真实语音样本已收到 | 会话失效不重登；固定 5 秒重试 | 验证断网恢复和凭据过期 |
| 微信文本接收 | 自动测试通过但未人工验证 | `src/main/java/com/demo/demo/Service/BotService.java` | `processTextMessage()` | iLink SDK、回复线程池 | `ImageAutoReplyTest` 异步测试通过 | 当前轮未执行 | 无 messageId 去重；无用户级顺序 | 执行单条及连续 20 条验证 |
| 微信文本发送 | 自动测试通过但未人工验证 | `src/main/java/com/demo/demo/Service/BotService.java` | `sendReply()` | iLink SDK | Mockito 验证 sendTextMessage | 当前轮未执行 | contextToken 未校验绑定；日志记录内容 | 执行真实文本回复 |
| 文本 LLM | 部分实现 | `src/main/java/com/demo/demo/Service/AIService.java` | `chat()` | DeepSeek 兼容 API、OkHttp | 无 `AIServiceTest` | 既有会话反馈不能替代本轮验证 | 无重试；错误未分类；空响应返回 null | 增加本地 HTTP 模拟测试和真实受控验收 |
| 多轮上下文 | 部分实现 | `src/main/java/com/demo/demo/Service/AIService.java` | `chat()`、`clearHistory()` | ConcurrentHashMap、Gson JsonArray | 无 | 待验证 | JsonArray 非线程安全；重启丢失；约 5 轮 | 增加并发、截断、清理测试 |
| 多用户历史隔离 | 部分实现 | `src/main/java/com/demo/demo/Service/AIService.java` | `chat()` | fromUserId Map Key | 无 | 待验证 | 不同用户隔离但共享线程池；同用户有竞态 | 两个用户同时对话验证 |
| 图片消息下载 | 自动测试通过但未人工验证 | `src/main/java/com/demo/demo/Service/BotService.java` | `processImageItem()` | iLink CDN、SDK downloadMedia | 下载参数 Mockito 测试通过 | 当前轮未执行 | 在监听线程同步下载；下载前无业务大小限制 | 真实微信图片下载验证 |
| 图片识别 | 自动测试通过但未人工验证 | `src/main/java/com/demo/demo/Service/ImageRecognitionService.java` | `recognize()` | Qwen/Qwen3-VL-8B-Instruct、OkHttp、Thumbnailator | 20 项组件测试通过 | 当前轮未调用真实平台 | 真实模型权限、延迟无法确认 | 执行真实图片识别清单 |
| 图片识别文字回复 | 自动测试通过但未人工验证 | `src/main/java/com/demo/demo/Service/BotService.java` | `processImageMessage()` | 回复线程池、iLink SDK | `ImageAutoReplyTest` 通过 | 当前轮未执行 | 异常消息可能包含供应商原始信息 | 验证成功、403、超时场景 |
| 文本触发图片生成 | 自动测试通过但未人工验证 | `src/main/java/com/demo/demo/Service/ImageGenerationService.java` | `isImageRequest()`、`extractPrompt()` | 正则路由 | 触发词测试通过 | 当前轮未执行 | 自然语言覆盖范围有限 | 扩充人工触发语料 |
| 图片生成 | 自动测试通过但未人工验证 | `src/main/java/com/demo/demo/Service/ImageGenerationService.java` | `generateImage()` | Kwai-Kolors/Kolors 或默认 gpt-image-1 | 12 项组件测试通过 | 当前轮未调用真实平台 | 供应商 URL 直接下载存在 SSRF；真实权限未知 | 执行真实生图并限制下载目标 |
| 生成图片发送 | 自动测试通过但未人工验证 | `src/main/java/com/demo/demo/Service/BotService.java` | `sendImageReply()` | iLink uploadMedia、sendImageMessage | Mockito 链路测试通过 | 当前轮未执行 | 仅检查非空，未校验发送格式 | 验证微信收到并可打开 |
| 语音消息下载 | 人工验证通过但缺少自动测试 | `src/main/java/com/demo/demo/Service/BotService.java` | `processVoiceProbe()` | iLink SDK、虚拟线程 | 无 | 三份真实 `.bin` 样本 | 探测代码写入 target；无用户回复 | 再发送已知内容样本并保存转写 |
| SILK 格式识别 | 已完整验证 | `docs/voice-feature-investigation.md` | 人工文件头检查 | 文件头、ffprobe | 无 Java 测试 | 三份样本均为微信前导字节 + `#!SILK_V3` | 仅覆盖当前三份中文短语音 | 增加长语音和异常样本 |
| SILK 解码为 PCM | 人工验证通过但缺少自动测试 | `docs/voice-feature-investigation.md` | 外部 decoder 命令 | 专用 SILK decoder | 无 | 三份 `.pcm` 产物存在 | 未封装为 Java AudioConverter；路径在项目外 | 实现受控转换器后加测试 |
| PCM 封装为 WAV | 人工验证通过但缺少自动测试 | `docs/voice-feature-investigation.md` | 外部 FFmpeg 命令 | FFmpeg 8.1.2 | 无 | 三份 `.wav` 产物存在 | 未接入主链；部署依赖未配置化 | 实现转换和 ffprobe 自动验证 |
| 官方语音转写字段 | 部分实现 | `src/main/java/com/demo/demo/Service/BotService.java` | `processVoiceProbe()` | SDK VoiceContent.text | 无 | 三条样本字段非空，但原文未留存 | 准确率和延迟无法确认 | 重启后发送三条已知内容样本 |
| ASR 语音识别 | 未实现 | 无 | 无 | 待选 ASR 供应商 | 无 | 无 | 没有 AsrService、配置或调用 | 完成官方文本准确率决策后实现 |
| 语音进入 LLM | 未实现 | 无 | 无 | ASR、AIService | 无 | 无 | 语音分支没有 contextToken 和回复编排 | 实现 VoiceProcessingService |
| TTS/语音回复 | 未实现 | 无 | 无 | TTS、SDK sendVoiceMessage | 无 | 无 | 主项目未调用 sendVoiceMessage | 一期文字回复验收后再做 |
| 天气模块（完整） | 已完整重构并验证 | `Service/weather/`（12 文件）、`Service/tool/WeatherTool.java`、`controller/WeatherController.java` | `queryWeather()`、REST API | Open-Meteo、Caffeine、ReactAgent Tool Calling | 43 项确定性单元测试通过，零公网依赖 | Tool/REST/Agent 路由全覆盖 | 真实 Open-Meteo 手动验证待完成 | 执行手动天气清单 |
| 时间查询 | 部分实现 | `src/main/java/com/demo/demo/controller/BotController.java` | `initAutoReply()` | LocalDateTime、AIService | 无 | 待验证 | 必须依赖 LLM；关键词有限 | 增加路由测试 |
| messageId 去重 | 未实现 | `src/main/java/com/demo/demo/Service/BotService.java` | `startListening()` | SDK DTO 已提供字段 | 无 | 无 | 重复投递会重复调用和回复 | 增加幂等存储及重复测试 |
| 同用户顺序处理 | 未实现 | `src/main/java/com/demo/demo/Service/BotService.java` | `submitReplyTask()` | 共享两线程池 | 无 | 无 | 后发先回、历史竞态 | 引入用户级串行调度 |
| 临时断网恢复 | 部分实现 | `src/main/java/com/demo/demo/Service/BotService.java` | `startListening()` | 异常捕获、5 秒等待 | 无 | 待验证 | 不区分错误类型；固定退避 | 执行断网恢复清单 |
| 会话过期自动重登 | 未实现 | `src/main/java/com/demo/demo/Service/BotService.java` | `startListening()` | iLink SDK | 无 | 无 | 持续使用旧凭据重试 | 识别会话过期并转登录状态 |
| 程序优雅关闭 | 部分实现 | `src/main/java/com/demo/demo/Service/BotService.java` | `shutdownReplyExecutor()` | @PreDestroy | 无 | 待验证 | 只关闭回复池，未明确停止 iLink 线程 | 执行关闭清单并补生命周期测试 |
| Git 跟踪的模型凭据 | 部分实现 | `.idea/workspace.xml`、`.gitignore` | Git 索引与脱敏扫描命令 | Git、供应商凭据控制台 | 修复前稳定复现；修复后验证 tracked=false、本地文件保留、忽略规则命中、跟踪文件脱敏扫描通过 | 新凭据的文本、生图和识图真实调用均返回 HTTP 200；旧凭据撤销仍待人工确认 | Git 历史仍含旧值；供应商侧撤销状态无法从仓库确认 | 在供应商控制台确认旧凭据已撤销，再通过 IDEA 启动项目执行微信端到端验证 |
| Agent/Tool/Function Calling | 已实现（天气 Tool） | `Service/tool/WeatherTool.java` | `queryWeather()` | Spring AI @Tool、ReactAgent | 10 项 Tool 测试 + 3 项路由测试 | WeatherToolResult 含 7 种机器可读状态 | 时间 Tool 也已注册到 ReactAgent | 扩展更多工具 |
| 独立 weather-cli | 归档项目 | `weather-cli/` | 独立 CLI 工具 | Open-Meteo、Java 17 | 不属于主 Maven 模块 | 主项目不测试；weather-cli 源码保留供参考 | 与主项目不共享代码 | 如需保留可明确独立维护 |
