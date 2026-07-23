# Spring AI Alibaba 迁移文档

> 迁移日期：2026-07-21  
> 目标：将手写 OkHttp + Gson 的 AI 调用替换为 Spring AI Alibaba + Spring AI OpenAI

---

## 一、依赖变更（pom.xml）

### 1.1 Spring Boot 版本回退

| 变更前 | 变更后 | 原因 |
|--------|--------|------|
| `4.0.8-SNAPSHOT` | `3.4.5` | Spring AI Alibaba 不支持 Spring Boot 4.x（Spring Framework 7），`RestClientAutoConfiguration` 在新版已移除 |

### 1.2 新增依赖

```xml
<!-- Spring AI Alibaba：百炼平台集成（TTS / 文生图 / 多模态，后续使用） -->
<dependency>
    <groupId>com.alibaba.cloud.ai</groupId>
    <artifactId>spring-ai-alibaba-starter-dashscope</artifactId>
    <version>1.1.2.1</version>
</dependency>

<!-- Spring AI OpenAI：OpenAI 兼容协议，直连 DeepSeek -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
    <version>1.1.2</version>
</dependency>
```

### 1.3 移除 BOM

去掉了 `spring-ai-alibaba-bom:1.0.0.3`，改为直接声明版本号。两个依赖同属 `1.1.x` 大版本，内部 Spring AI 版本一致，不会冲突。

### 1.4 Spring Boot 3.x 适配

| 旧 Artifact（Spring Boot 4.x） | 新 Artifact（Spring Boot 3.x） |
|-------------------------------|-------------------------------|
| `spring-boot-starter-webmvc` | `spring-boot-starter-web` |
| `spring-boot-starter-webmvc-test` | （删除，已包含在 `spring-boot-starter-test` 中） |

---

## 二、配置变更（application.yml）

### 2.1 新增 Spring AI 配置

```yaml
spring:
  autoconfigure:
    exclude:
      # DashScopeChatModel 是百炼专用协议，DeepSeek 用 OpenAI 的 ChatModel
      - com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeChatAutoConfiguration
  ai:
    dashscope:
      api-key: ${AI_API_KEY:${ai.api.key:sk-placeholder}}
    openai:
      api-key: ${AI_API_KEY:${ai.api.key:sk-placeholder}}
      base-url: ${AI_BASE_URL:https://api.deepseek.com}
      chat:
        options:
          model: ${AI_MODEL:deepseek-chat}
```

### 2.2 配置优先级链

```
spring.ai.openai.api-key
  ├── 环境变量 AI_API_KEY（最高优先级）
  ├── application-local.yml 中的 ai.api.key（你原来的配置）
  └── sk-placeholder（兜底，不会报错但不可用）
```

你原来的 `application-local.yml` 不用改，`ai.api.key` 会被自动拾取。

### 2.3 base-url 注意事项

`OpenAiChatModel` 会自动在 `base-url` 后面追加 `/v1/chat/completions`，所以 **base-url 不要带 `/v1` 后缀**：

- ✅ `https://api.deepseek.com` → 实际请求 `https://api.deepseek.com/v1/chat/completions`
- ❌ `https://api.deepseek.com/v1` → 实际请求 `https://api.deepseek.com/v1/v1/chat/completions`（404）

---

## 三、核心代码变更

### 3.1 AIService.java（最重要）

**文件路径**：`src/main/java/com/demo/demo/Service/AIService.java`

| 对比 | 变更前 | 变更后 |
|------|--------|--------|
| 底层 HTTP | `OkHttp` + `Gson` 手动拼 JSON | `ChatClient` + `ChatModel`（Spring AI 框架） |
| 简单对话 | 构建 `JsonObject` → OkHttp POST → 解析 JSON → 重试逻辑（~60 行） | `chatClient.prompt().user(msg).call().content()`（1 行） |
| 工具调用 | 手动构建 `tools` JSON → 解析 `tool_calls` → 执行 → 第二轮请求（~80 行） | `chatClient.prompt().user(msg).tools(...).call().content()`（1 行，ChatClient 内置 tool loop） |
| 历史消息 | Gson `JsonArray` 手动拼接 | Spring AI `Message` 对象（`SystemMessage`/`UserMessage`/`AssistantMessage`） |
| 重试逻辑 | 手动 3 次重试 + 指数退避 | Spring AI 内置 `RetryTemplate` |
| 构造函数 | `AIService(store, contextManager, toolRegistry)` | `AIService(ChatModel, store, contextManager, weatherTool, timeTool, imageGenTool)` |
| 代码行数 | 345 行 | 200 行（减少 42%） |

**说明**：
- `ChatClient` 是 Spring AI 的高层 API，Fluent 风格链式调用，一行搞定对话+工具调用
- `ChatModel` 是底层抽象，由 `spring-ai-starter-model-openai` 自动创建 `OpenAiChatModel` Bean
- 对话记忆（`ConversationMemoryStore`）和上下文（`ContextManager`）完全保留，只是消息格式从 Gson → Spring AI `Message`

### 3.2 工具类（@Tool 注解）

**文件路径**：`src/main/java/com/demo/demo/Service/tool/*.java`

| 文件 | 变更前 | 变更后 |
|------|--------|--------|
| `WeatherTool.java` | 实现 `Tool` 接口，手动写 JSON Schema（45 行） | `@Tool` 注解 + `@ToolParam`（25 行） |
| `TimeTool.java` | 实现 `Tool` 接口（41 行） | `@Tool` 注解（22 行） |
| `ImageGenerationTool.java` | 实现 `Tool` 接口（68 行） | `@Tool` 注解（43 行），保留 `ThreadLocal<byte[]>` |

**对比示例（WeatherTool）**：

```java
// 变更前：手动写 JSON Schema
@Override
public JsonObject parameters() {
    JsonObject props = new JsonObject();
    JsonObject city = new JsonObject();
    city.addProperty("type", "string");
    city.addProperty("description", "城市名称");
    props.add("city", city);
    // ... 更多样板代码
}

// 变更后：一行注解搞定
@Tool(description = "查询指定城市的实时天气")
public String getWeather(@ToolParam(description = "城市名称") String city) {
    return WeatherUtil.getWeather(city);
}
```

**说明**：Spring AI 的 `@Tool` 注解通过反射自动生成 JSON Schema，ChatClient 传入工具对象即可自动注册。旧的 `Tool` 接口和 `ToolRegistry` 已不再使用（文件保留，未删除）。

### 3.3 BotController.java

**文件路径**：`src/main/java/com/demo/demo/controller/BotController.java`

变更内容：参照 `others/BotController.java` 的实现，在 handler 中增加了 **AI 语音意图检测**：

```
图片生成检测
  ↓
语音生成检测（新增）  ← AI 判断用户是否想要语音回复
  ↓
AI 配置检查
  ↓
天气查询
  ↓
时间查询
  ↓
chatWithTools（兜底）
```

语音检测增加 `aiService.isConfigured()` 前置守卫，避免未配置时产生无意义的 AI 调用。

### 3.4 BotService.java

**文件路径**：`src/main/java/com/demo/demo/Service/BotService.java`

| 变更 | 说明 |
|------|------|
| `sendMp3Reply()` → `public sendVoiceReply()` | 私有改公开，重命名，供 Controller 调用 |
| `runAutoReply()` 增加语音检测 | handler 返回文字后，检查 `isVoiceRequest(text)`，命中则 TTS → 发 MP3 |
| `isVoiceRequest()` 扩展关键词 | 从 12 个扩展到 26 个（"发语音""说给我听""念给我听"等） |
| `processVoiceMessage()` 调用更新 | `sendMp3Reply` → `sendVoiceReply` |

### 3.5 移除文件

| 文件 | 原因 |
|------|------|
| `config/AiConfig.java` | 曾手动创建 `OpenAiChatModel`，现由 `spring-ai-starter-model-openai` 自动装配 |

---

## 四、架构对比

### 变更前

```
AIService
  └── OkHttp → POST api.deepseek.com/v1/chat/completions
       ├── 手动构建 JSON body（Gson）
       ├── 手动 3 次重试
       └── 手动解析 JSON → 提取 content

ToolRegistry
  └── Tool 接口（name/description/parameters/execute）
       └── 手动拼 OpenAI tools JSON Schema
```

### 变更后

```
AIService
  └── ChatClient（Spring AI 高层 API）
       └── ChatModel（接口）
            └── OpenAiChatModel（spring-ai-starter-model-openai 自动装配）
                 └── RestClient → POST api.deepseek.com/v1/chat/completions

@Tool 注解（Spring AI 声明式工具）
  └── ChatClient 自动扫描 → 生成 JSON Schema → 处理 tool loop
```

---

## 五、为什么需要两个依赖

| 依赖 | 作用 | 当前状态 |
|------|------|---------|
| `spring-ai-alibaba-starter-dashscope:1.1.2.1` | 引入 Spring AI 框架核心 + DashScope（百炼）平台支持 | **Chat 部分被排除**（因为用 DeepSeek），TTS/文生图/多模态后续启用 |
| `spring-ai-starter-model-openai:1.1.2` | 自动装配 `OpenAiChatModel`，纯 OpenAI 协议 | **Chat 的主力**，直连 DeepSeek |

**排除 DashScopeChatModel 的原因**：`DashScopeChatModel` 是百炼专用协议，发往 `api.deepseek.com` 会 401（百炼和 DeepSeek 的认证方式不同）。排除后只保留 `OpenAiChatModel`，走标准 OpenAI 协议，完美兼容 DeepSeek。

---

## 六、后续可做的事情

1. **TTS 切换到百炼 CosyVoice**：注入 `DashScopeAudioSpeechModel`，替换 `SiliconFlowTtsService`
2. **文生图切换到百炼 WANX**：注入 `ImageModel`，替换 `SiliconFlow` 的 Kolors
3. **图片识别切换到 qwen-vl-max**：用 `ChatClient` 多模态 API
4. **对话记忆改用 Spring AI ChatMemory**：替换 `ConversationMemoryStore`
5. **Tool 迁移清理**：删除旧的 `Tool.java` 接口和 `ToolRegistry.java`
6. **升级到 Spring Boot 4.x**：等 Spring AI Alibaba 放出支持 Spring Boot 4 的版本
