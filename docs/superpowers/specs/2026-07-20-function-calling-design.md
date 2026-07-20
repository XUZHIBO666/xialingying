# Function Calling 智能路由 — 详细设计

> 关联：P3-24 | 前置：无（可与现有关键词路由并存）

## 目标

利用 LLM 的 Function Calling / Tool Use 能力自动判断用户意图并调用对应工具，替代当前基于正则表达式和关键词的手动路由。保留关键词路由作为快速路径。

---

## 1. 现状分析

### 当前路由方式（`BotController.initAutoReply()`）

```java
if (imageGenerationService.isImageRequest(text))     // 正则 → 生图
else if (text.contains("天气"))                       // 关键词 → 查天气
else if (text.contains("几点") || text.contains("时间")) // 关键词 → 查时间
else                                                   // 兜底 → LLM 闲聊
```

**痛点**：
- "今天热不热" → 不触发天气（不含"天气"关键词）
- "现在什么时候" → 不触发时间（不含"几点/时间"）
- "帮我画一只猫" → 可能匹配/可能不匹配（取决于自然语言前缀）
- 新工具需要修改 `BotController` 代码，不符合开闭原则

### 目标路由方式

```
用户消息 → AIService.chatWithTools(message, tools)
  → LLM 判断是否需要调用工具
    ├─ tool_calls 非空 → 执行工具 → 结果回传 LLM → 生成最终回复
    └─ tool_calls 为空 → 直接返回 LLM 回复
```

DeepSeek API 兼容 OpenAI 的 Function Calling 协议，无需升级模型或 API URL。

---

## 2. 工具定义

### 2.1 Tool 接口

```java
package com.demo.demo.Service.tool;

public interface Tool {

    /** 工具名称（用于 LLM tool_choice 匹配） */
    String name();

    /** 工具描述（LLM 用此判断何时调用） */
    String description();

    /** JSON Schema 格式的参数定义 */
    JsonObject parameters();

    /** 执行工具，返回结果文本（会被注入 LLM 上下文） */
    String execute(JsonObject arguments) throws Exception;
}
```

### 2.2 内置工具清单

#### 2.2.1 WeatherTool

```java
@Component
public class WeatherTool implements Tool {

    @Override
    public String name() { return "get_weather"; }

    @Override
    public String description() {
        return "查询指定城市的实时天气，包括温度、体感温度、天气状况、" +
               "风向风速和湿度。当用户询问天气相关问题时使用此工具。";
    }

    @Override
    public JsonObject parameters() {
        // JSON Schema:
        // {
        //   "type": "object",
        //   "properties": {
        //     "city": {
        //       "type": "string",
        //       "description": "城市名称，支持中文/拼音/英文"
        //     }
        //   },
        //   "required": ["city"]
        // }
    }

    @Override
    public String execute(JsonObject args) {
        String city = args.get("city").getAsString();
        return WeatherUtil.getWeather(city);
    }
}
```

#### 2.2.2 TimeTool

```java
@Component
public class TimeTool implements Tool {

    @Override
    public String name() { return "get_current_time"; }

    @Override
    public String description() {
        return "获取当前的精确日期和时间。" +
               "当用户询问现在几点、今天几号、当前时间时使用此工具。";
    }

    @Override
    public JsonObject parameters() {
        // { "type": "object", "properties": {} }  — 无参数
    }

    @Override
    public String execute(JsonObject args) {
        return LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss"));
    }
}
```

#### 2.2.3 ImageGenTool

```java
@Component
public class ImageGenTool implements Tool {

    @Override
    public String name() { return "generate_image"; }

    @Override
    public String description() {
        return "根据文字描述生成一张图片。当用户要求画图、生成图片、" +
               "制作图片时使用此工具。不要在普通聊天中调用。";
    }

    @Override
    public JsonObject parameters() {
        // {
        //   "type": "object",
        //   "properties": {
        //     "prompt": {
        //       "type": "string",
        //       "description": "图片的文字描述，语言与用户输入一致"
        //     }
        //   },
        //   "required": ["prompt"]
        // }
    }

    @Override
    public String execute(JsonObject args) {
        // 此工具的真正执行在 ToolCallingService 中特殊处理（需要发图片而非文本）
        // execute() 返回提示词，由外层判断后调用 ImageGenerationService
        return args.get("prompt").getAsString();
    }
}
```

### 2.3 ToolRegistry

```java
@Component
public class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolRegistry(List<Tool> toolBeans) {
        for (Tool tool : toolBeans) {
            tools.put(tool.name(), tool);
        }
    }

    public List<Tool> getAll() { return List.copyOf(tools.values()); }

    public Tool get(String name) { return tools.get(name); }

    /** 构建发送给 LLM 的 tools 数组 */
    public JsonArray toOpenAiTools() {
        JsonArray array = new JsonArray();
        for (Tool tool : tools.values()) {
            JsonObject t = new JsonObject();
            t.addProperty("type", "function");

            JsonObject function = new JsonObject();
            function.addProperty("name", tool.name());
            function.addProperty("description", tool.description());
            function.add("parameters", tool.parameters());
            t.add("function", function);

            array.add(t);
        }
        return array;
    }
}
```

---

## 3. 核心流程

### 3.1 ToolCallingService

```java
@Service
public class ToolCallingService {

    private final AIService aiService;
    private final ToolRegistry toolRegistry;
    private final ImageGenerationService imageGenService;

    /** 带工具调用的聊天（替代简单 chat） */
    public ToolChatResult chat(String userId, String message) {

        // === 第 1 步：调用 LLM（带 tools 定义） ===
        LLMResponse firstResponse = aiService.chatWithTools(
                userId, message, toolRegistry.toOpenAiTools());

        // 没有 tool_calls → 直接返回文本
        if (!firstResponse.hasToolCalls()) {
            return ToolChatResult.text(firstResponse.getContent());
        }

        // === 第 2 步：执行工具并将结果回传 ===
        JsonArray toolResults = new JsonArray();
        for (ToolCall call : firstResponse.getToolCalls()) {
            Tool tool = toolRegistry.get(call.name());
            if (tool == null) continue;

            String result;
            try {
                if (tool instanceof ImageGenTool) {
                    // 特殊处理：生图工具
                    String prompt = tool.execute(call.arguments());
                    byte[] imageBytes = imageGenService.generateImage(prompt);
                    result = "图片已成功生成，请告知用户并描述图片内容";
                    // 实际图片 bytes 通过 ToolChatResult 传出
                } else {
                    result = tool.execute(call.arguments());
                }
            } catch (Exception e) {
                result = "工具执行失败：" + e.getMessage();
            }

            JsonObject tr = new JsonObject();
            tr.addProperty("tool_call_id", call.id());
            tr.addProperty("role", "tool");
            tr.addProperty("content", result);
            toolResults.add(tr);
        }

        // === 第 3 步：回传工具结果给 LLM 生成最终回复 ===
        String finalReply = aiService.continueWithToolResults(
                userId, toolResults);
        return ToolChatResult.text(finalReply);
    }
}
```

### 3.2 ToolChatResult

```java
public class ToolChatResult {
    private final String text;
    private final byte[] generatedImage;  // 非 null 时需要发图片

    public static ToolChatResult text(String text) { ... }
    public static ToolChatResult image(String text, byte[] imageBytes) { ... }

    public boolean hasImage() { return generatedImage != null && generatedImage.length > 0; }
}
```

### 3.3 AIService 新增方法

```java
// AIService 新增两个方法

/** 带 tools 定义的聊天请求 */
LLMResponse chatWithTools(String userId, String message, JsonArray tools) {
    // 构建请求时添加 "tools": tools, "tool_choice": "auto"
    // 解析响应中的 tool_calls（如有）
    // 注意：工具调用消息不持久化到 ConversationMemoryStore
}

/** 回传工具执行结果，获取最终回复 */
String continueWithToolResults(String userId, JsonArray toolResults) {
    // 将 tool 结果消息追加到对话中
    // 调用 LLM 生成最终自然语言回复
    // 此轮对话不持久化（不是用户→助手的一对一对话）
}
```

**注意**：带有 Tool 调用的对话轮次不计入 `ConversationMemoryStore`。只持久化最终的"用户问题 → 助手最终回复"对。

---

## 4. 路由策略：关键词 + Function Calling 混合

```
用户消息到达
  ├─ 关键词快速路径（不改动）
  │   ├─ 正则匹配生图？→ 调 ImageGenerationService（省一次 LLM 调用）
  │   └─ 不是
  │
  └─ Function Calling 路径
      ├─ 调用 ToolCallingService.chat()
      ├─ LLM 自行判断是否调 Tool
      └─ 返回最终结果
```

**优势**：
- 正则生图快速路径节省延迟和费用（生图提示词提取原本就依赖正则）
- Function Calling 处理天气、时间和其他未来工具——自然语言理解更准确
- 两个路径并行不悖，可渐进迁移

**配置开关**：

```yaml
ai:
  function-calling:
    enabled: ${AI_FUNCTION_CALLING_ENABLED:true}  # 可关闭回退到纯关键词路由
```

---

## 5. 错误处理

| 场景 | 行为 |
|------|------|
| LLM 返回的 tool name 不在注册表中 | 跳过该调用，返回错误给 LLM 让其重试 |
| 工具执行抛异常 | 结果文本为"工具执行失败：{msg}"，LLM 据此生成用户友好回复 |
| LLM 不支持 Function Calling | `chatWithTools` 检测到 API 报错后删除 tools 参数重试 |
| 工具超时（单次 > 30s） | 通过 Future + timeout 包装，超时返回错误 |
| 生图工具但 imageGenService 未配置 | 返回配置提示文本给 LLM |
| 最大工具调用轮次（防死循环） | 最多 3 轮工具调用 |

---

## 6. 与现有代码的兼容

- `BotController.initAutoReply()` 中的 `ReplyHandler` Lambda 改为调用 `ToolCallingService`
- `AIService.chat(userId, message)` 签名不变——普通闲聊仍走此路径
- `ImageGenerationService.isImageRequest()` / `extractPrompt()` 保留——作为快速路径
- `WeatherUtil` 保留——WeatherTool 复用
- 天气的 `extractCity()` 不再需要——LLM 从用户消息中提取城市名

---

## 7. 测试策略

### 组件测试（ToolCallingServiceTest）

使用本地 `HttpServer` 模拟 LLM API，返回预定义 JSON：

| # | 场景 | 验证点 |
|---|------|--------|
| 1 | 普通闲聊，无 tool_calls | 返回 LLM 文本，不调任何工具 |
| 2 | "今天北京天气" → tool_call: get_weather | WeatherUtil 被调用，结果回传 LLM |
| 3 | "现在几点了" → tool_call: get_current_time | 返回格式化时间 |
| 4 | "画一只猫" → tool_call: generate_image | ImageGenService 被调用 |
| 5 | LLM 返回未知 tool name | 工具结果返回错误，LLM 收到后回复 |
| 6 | 工具执行失败 | 错误信息注入上下文，LLM 友好回复 |
| 7 | 最大轮次保护 | 第 4 次 tool_call 时停止并返回 |

### 单元测试（ToolRegistryTest）

| # | 场景 |
|---|------|
| 1 | 三个工具自动注入 |
| 2 | `toOpenAiTools()` 生成正确的 JSON Schema |
| 3 | `get(name)` 命中/未命中 |

---

## 8. 回滚方案

- 设置 `AI_FUNCTION_CALLING_ENABLED=false` 恢复关键词路由
- `ToolCallingService` 不注册时，`BotController` 使用原有逻辑
- 不删除任何现有路由代码，仅添加新路径
