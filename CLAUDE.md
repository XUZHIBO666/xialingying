# Claude Code 开发约定

本文件供后续 Claude Code + DeepSeek 在本仓库中执行开发任务时使用。本文只描述仓库根目录 Maven 工程 `E:\Summer-projects V3.1\xialingying`；不要把嵌套的 `xialingying/` 副本或 `others/` 旧代码当作当前主工程源码。

## 1. 项目基本信息

### 1.1 项目目标

当前源码实现的是一个微信 AI Bot：

- 使用 iLink SDK 登录微信、轮询接收用户消息并发送回复。
- 使用 Spring AI Alibaba `ReactAgent` 调用 DashScope 大模型。
- 通过 Spring AI `@Tool` 提供天气、时间、图片生成、语音合成、联网搜索和邮件发送能力。
- 支持文本、图片和语音输入处理。
- 支持单 Bot 旧实现 `BotService` 和多 Bot 实现 `MultiBotManager` + `BotInstance`。
- 使用会话 checkpoint 和向量记忆增强对话；仓库还定义了 JSON 文件记忆组件，但当前 `AIService` 不写入该组件。
- 提供 Bot 管理页面、健康检查和天气 REST 接口。

周期性消息推送尚未实现；其目标架构和开发任务分别记录在 `docs/ARCHITECTURE.md` 和 `docs/TASKS.md`。

### 1.2 当前技术栈

以下版本来自根目录 `pom.xml`：

| 技术 | 当前配置 |
|---|---|
| Java | 21 |
| Spring Boot | 3.4.5 |
| Spring AI Alibaba BOM | 1.1.2.3 |
| Spring AI BOM | 1.1.8 |
| Spring AI OpenAI | 显式依赖 1.1.2 |
| Spring AI Alibaba Agent Framework | 由 1.1.2.3 BOM 管理 |
| iLink SDK | `io.github.lith0924:wechat-ilink-sdk:1.0.1` |
| SQLite JDBC | 3.46.1.0 |
| Web | Spring MVC |
| 页面 | Thymeleaf |
| 数据访问 | Spring JDBC / `JdbcTemplate` |
| HTTP | OkHttp 4.12.0、Spring `RestClient` |
| 缓存 | Caffeine |
| JSON | Jackson、Gson 2.10.1 |
| 测试 | JUnit 5、Mockito、Spring Test、MockWebServer |

数据库存在仓库配置不一致：

- 当前工作树的 `pom.xml` 只包含 SQLite JDBC 驱动，不包含 MySQL Connector。
- 跟踪的 `src/main/resources/application.yml` 当前配置 `jdbc:mysql://...` 和 `com.mysql.cj.jdbc.Driver`。
- `VectorMemoryStore` 的代码注释把其 JDBC 存储描述为 SQLite。
- 实际本地运行配置可能由未提交的 `application-local.yml` 覆盖。

不得猜测最终数据源。用户已说明当前运行环境使用 SQLite，且当前 POM 只有 SQLite 驱动，但跟踪的默认配置仍是 MySQL；涉及数据库的 Task 必须先根据有效 profile、运行配置和数据库文件确认 SQLite 的实际连接方式，不得在周期功能中顺带改写全局 datasource。

### 1.3 项目模块

根目录工程是单 Maven 模块，不是 Maven 聚合多模块工程。主要包：

| 路径 | 当前职责 |
|---|---|
| `src/main/java/com/demo/demo/controller/` | Bot 管理、健康检查、天气和基础 HTTP 接口 |
| `src/main/java/com/demo/demo/Service/` | AI、Bot、图片、邮件、公众号和多 Bot 编排 |
| `src/main/java/com/demo/demo/Service/tool/` | ReactAgent Tool |
| `src/main/java/com/demo/demo/Service/weather/` | 天气领域、Provider、缓存和配置 |
| `src/main/java/com/demo/demo/Service/voice/` | SILK/PCM 转换、ASR、TTS 和语音处理 |
| `src/main/java/com/demo/demo/Service/memory/` | 文件记忆、向量记忆、Agent Hook 和 Interceptor |
| `src/main/java/com/demo/demo/Service/context/` | 感知上下文 |
| `src/main/java/com/demo/demo/Service/throttle/` | 用户限流 |
| `src/main/java/com/demo/demo/config/` | 配置属性 |
| `src/main/java/com/demo/demo/execption/` | 统一异常和响应码 |
| `src/main/resources/templates/` | Bot 管理页面 |
| `src/test/java/` | JUnit 5 测试 |

仓库中的 `weather-cli/` 有独立 `pom.xml`，但不属于根目录 Spring Boot 构建。嵌套 `xialingying/` 和 `others/` 存在重复或旧代码，除非 Task 明确要求，否则不得修改。

### 1.4 构建工具和命令

构建工具：Maven Wrapper。

Windows 命令：

```powershell
mvnw.cmd test
mvnw.cmd -Dtest=VoiceMessageServiceTest test
mvnw.cmd clean package
mvnw.cmd spring-boot:run
```

仅编译且不运行测试：

```powershell
mvnw.cmd -DskipTests compile
```

macOS/Linux 使用对应的 `./mvnw`。

### 1.5 主要配置文件

| 文件 | 当前用途 |
|---|---|
| `pom.xml` | 依赖、Java 版本、Spring Boot 主类 |
| `src/main/resources/application.yml` | 端口、日志、数据源、DashScope、邮件、图片、语音和搜索配置 |
| `src/main/resources/application-local.example.yml` | 本地配置模板 |
| `src/main/java/com/demo/demo/config/VoiceProperties.java` | `ai.voice` 属性绑定 |
| `src/main/java/com/demo/demo/config/MailProperties.java` | `spring.mail` 属性绑定 |
| `src/main/java/com/demo/demo/Service/weather/WeatherProperties.java` | `weather` 属性绑定 |
| `src/main/java/com/demo/demo/Service/weather/WeatherConfiguration.java` | 天气 `Clock` 和专用 `OkHttpClient` Bean |
| `src/main/java/com/demo/demo/controller/BotAdminAuthConfig.java` | Bot 管理接口鉴权 |

不得读取、输出或提交真实 API Key、数据库密码、邮件凭据、contextToken 或本地私密配置。

## 2. 必读文档

每次执行开发 Task 前必须完整阅读：

1. `CLAUDE.md`
2. `docs/ARCHITECTURE.md`
3. `docs/TASKS.md`
4. 当前 Task 列出的现有源码
5. 当前 Task 列出的已有测试
6. 当前 Task 将修改方法的直接调用方和被调用方

如果文档与源码冲突，以源码和有效配置为证据，并先更新方法级分析；不得静默按文档猜测。

## 3. 开发约束

- 一次只执行 `docs/TASKS.md` 中一个 Task。
- 不得提前实现后续 Task，即使后续接口看起来容易顺手完成。
- 不得进行与当前 Task 无关的重构、格式化、依赖升级或目录清理。
- 不得随意修改公共接口；必须先列出全部调用方、兼容影响和测试影响。
- 不得自动执行 `git commit`、`git push`、创建分支或创建 PR。
- 修改前必须搜索并阅读相关代码、直接调用方、被调用方和测试。
- 修改后必须至少运行相关测试或编译；高风险公共链路必须运行完整测试。
- 修改后必须执行 `git diff --check` 和 `git diff -- <本Task文件>`。
- 不得猜测类、方法、字段、Bean、调用关系、配置键或数据库表。
- 不确定时必须先使用 `rg` 搜索代码确认。
- 新功能测试必须 mock ASR、TTS、天气、iLink、邮件和网络调用，保证 CI 可重复。
- Bug 修复必须先增加能复现问题的失败测试。
- 生产日志不得输出消息正文、contextToken、媒体参数、凭据或外部响应正文。
- 保持 Java 21、四空格缩进、`PascalCase` 类名和 `camelCase` 成员。
- 只修改当前 Task 明确列出的文件；新增文件必须与当前 Task 直接相关。
- 不得修改 `target/`、`logs/`、嵌套 `xialingying/` 副本或 `others/`，除非 Task 明确授权。

## 4. 方法级变更审计规则

每个被新增、修改或删除的方法都必须记录以下内容：

| 字段 | 必填内容 |
|---|---|
| 修改文件路径 | 仓库相对路径 |
| 修改类名 | 完整类名 |
| 修改方法名 | 方法名；构造器写类名 |
| 方法签名 | 可见性、返回类型、方法名、参数类型 |
| 修改前职责 | 修改前的真实行为；新增方法写“不存在” |
| 修改后职责 | 修改后的单一职责 |
| 修改原因 | 对应 Task 目标或失败测试 |
| 调用方 | 真实类名和方法名 |
| 被调用方 | 真实类名和方法名 |
| 参数来源 | HTTP、iLink DTO、Tool 参数、配置、数据库或内部计算 |
| 返回值去向 | Controller、Agent、iLink、Repository、调用方或忽略 |
| 异常处理 | 捕获、转换、降级、重试或向上抛出 |
| 测试结果 | 测试类、测试方法、执行命令和结果 |

审计模板：

```markdown
### 方法变更：完整类名.方法名

- 文件：
- 签名：
- 修改前职责：
- 修改后职责：
- 修改原因：
- 调用方：
- 被调用方：
- 参数来源：
- 返回值去向：
- 异常处理：
- 测试：
```

公共接口变更还必须增加：

- 兼容方案
- 受影响调用方数量
- 是否需要迁移适配层
- 是否改变线程、事务或安全边界

## 5. 每个 Task 的标准执行流程

1. 阅读 `docs/TASKS.md` 中当前 Task 的全部内容。
2. 检查 Task 涉及的源码、测试、配置和直接依赖。
3. 输出修改前调用链，必须使用真实类名和方法名。
4. 列出计划修改的文件、类、方法和方法签名。
5. 先写或调整失败测试，再执行最小代码修改。
6. 运行 Task 指定的聚焦测试；必要时运行 `mvnw.cmd test`。
7. 执行 `git diff --check`，再逐文件检查 `git diff`。
8. 生成方法级变更报告。
9. 更新 `docs/TASKS.md` 中当前 Task 的状态和开发记录；不得改动后续 Task 的范围。

如果修改前发现 Task 与当前源码不一致：

1. 停止编码。
2. 使用 `rg` 和测试确认真实情况。
3. 记录差异、影响和建议。
4. 获得用户确认后再调整 Task。

## 6. Task 完成输出格式

每次完成 Task 必须按以下结构输出：

```markdown
## 完成内容

## 修改文件

## 修改类

## 修改方法

## 修改后调用链

## 测试结果

## 风险与未验证项

## 推荐 Git 提交信息
```

“修改方法”部分必须附带第 4 节要求的方法级审计。“测试结果”必须包含实际命令和实际结果，不能只写“已测试”。推荐提交信息只提供文本，不得自动提交。
