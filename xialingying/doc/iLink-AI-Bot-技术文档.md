
# iLink AI Bot 源码解析文档

## 目录

1. [整体架构概览](#一整体架构概览)
2. [BotService - iLink核心服务](#二botservice---ilink核心服务)
3. [AIService - AI对话服务](#三aiservice---ai对话服务)
4. [BotController - 控制器](#四botcontroller---控制器)
5. [WeatherUtil - 天气查询工具](#五weatherutil---天气查询工具)
6. [AI查询天气完整调用链](#六ai查询天气完整调用链)
7. [配置说明](#七配置说明)

---

## 一、整体架构概览

```
┌─────────────────────────────────────────────────────────────────┐
│ 前端页面 (bot.html) ── REST API ── BotController               │
│                                    ├─ BotService (iLink通信)    │
│                                    └─ AIService (LLM调用)       │
│                                             └─ WeatherUtil      │
└─────────────────────────────────────────────────────────────────┘
```

---

## 二、BotService - iLink核心服务

**文件**：`src/main/java/com/demo/demo/Service/BotService.java`

### 2.1 类定义与核心字段

```java
@Service
public class BotService {
    private final ILinkClient client = new ILinkClient();//所有微信API调用的入口
    
    private final AtomicReference<String> qrCodeBase64 = new AtomicReference<>();//二维码图片的Base64编码，给前端展示
    private final AtomicReference<String> qrCodeUrl    = new AtomicReference<>();//二维码原始链接
    private final AtomicReference<String> statusText   = new AtomicReference<>("未启动");//当前登录状态
    private final AtomicReference<LoginCredentials> credentials = new AtomicReference<>();//登录凭证
    private volatile boolean loggedIn = false;//是否登录标志
    
    private final List<String> logs     = new CopyOnWriteArrayList<>();//日志列表，线程安全
    private final List<Msg> messages    = new CopyOnWriteArrayList<>();//收到的消息列表,线程安全
    private String cursor = "";//消息分页游标，避免重复拉取消息
    private Thread listenThread;//消息监听线程
    private volatile ReplyHandler autoReplyHandler;//自动回复处理器
}
```

**逐字段解释**：

| 字段 | 类型 | 作用 |
|------|------|------|
| `client` | `ILinkClient` | iLink SDK 核心客户端，所有微信 API 调用的入口 |
| `qrCodeBase64` | `AtomicReference<String>` | 二维码图片的 base64 编码，供前端展示 |
| `qrCodeUrl` | `AtomicReference<String>` | 二维码原始链接 |
| `statusText` | `AtomicReference<String>` | 当前登录状态描述（未启动/等待扫码/已登录等） |
| `credentials` | `AtomicReference<LoginCredentials>` | 登录凭证，扫码成功后存储 |
| `loggedIn` | `volatile boolean` | 是否已登录的标志 |
| `logs` | `CopyOnWriteArrayList<String>` | 日志列表，线程安全 |
| `messages` | `CopyOnWriteArrayList<Msg>` | 收到的消息列表，线程安全 |
| `cursor` | `String` | 消息分页游标，避免重复拉取消息 |
| `listenThread` | `Thread` | 消息监听线程 |
| `autoReplyHandler` | `ReplyHandler` | 自动回复处理器（策略模式） |

**为什么用 `AtomicReference` 和 `volatile`？**

因为登录流程（异步线程）和消息监听（另一个异步线程）会并发访问这些状态字段，使用 `AtomicReference` 保证单个操作的原子性，`volatile` 保证可见性，`CopyOnWriteArrayList` 保证列表操作的线程安全。

---

### 2.2 登录方法

```java
public synchronized void startLogin() {
    startLogin(false);
}

public synchronized void restartLogin() {
    startLogin(true);
}

private synchronized void startLogin(boolean force) {
    if (loggedIn && !force) return;
    if (force) {
        loggedIn = false;
        credentials.set(null);
        // ... 清空所有状态
        if (listenThread != null) listenThread.interrupt();
    }
    
    new Thread(() -> {
        // 1. 获取二维码
        QrCodeResp qr = client.getBotQrCode();
        String content = qr.getQrcode();
        String imgData = qr.getQrcode_img_content();
        
        // 2. 处理二维码图片
        String qrBase64 = buildQrCodeBase64(content, imgData);
        qrCodeBase64.set(qrBase64);
        
        // 3. 轮询等待扫码（最多等300秒，每2秒检查一次）
        for (int i = 0; i < 150; i++) {
            Thread.sleep(2000);
            LoginStatusResp s = client.getQrCodeStatus(content);
            
            if ("confirmed".equals(code)) {
                credentials.set(ILinkClient.createCredentials(content, s));
                loggedIn = true;
                startListening();  // 登录成功，开始监听消息
                return;
            }
            if ("expired".equals(code)) {
                statusText.set("二维码已过期，请刷新页面重试");
                return;
            }
        }
    }).start();
}
```

**执行流程**：

1. **检查状态**：如果已登录且不是强制重启，直接返回
2. **强制重启时**：清空所有状态，中断监听线程
3. **获取二维码**：调用 `client.getBotQrCode()` 获取二维码内容和图片
4. **处理图片**：调用 `buildQrCodeBase64()` 转成 base64
5. **轮询等待**：每 2 秒检查一次扫码状态，最多等 300 秒
6. **扫码成功**：创建凭证，设置 `loggedIn = true`，调用 `startListening()`
7. **二维码过期**：更新状态文本，退出

---

### 2.3 消息监听

```java
private void startListening() {
    listenThread = new Thread(() -> {
        while (loggedIn) {
            try {
                ReceiveMessagesResult result = client.receiveMessages(credentials.get(), cursor);
                
                if (result == null || !result.hasMessages()) {
                    Thread.sleep(1000);
                    continue;
                }
                
                cursor = result.getNextCursor();  // 更新游标，避免重复拉取
                
                for (WeixinMessageDto dto : result.getMessages()) {
                    if (!dto.hasItems()) continue;
                    for (MessageItemDto item : dto.getItemList()) {
                        if (!item.isText()) continue;  // 只处理文本消息
                        
                        String text = item.getText();
                        String fromUser = dto.getFromUserId();
                        String clientId = dto.getClientId();
                        
                        messages.add(new Msg(fromUser, clientId, text));
                        
                        // 触发自动回复
                        ReplyHandler handler = autoReplyHandler;
                        if (handler != null) {
                            String reply = handler.onMessage(fromUser, text);
                            if (reply != null && !reply.isEmpty()) {
                                sendReply(fromUser, clientId, reply);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                if (loggedIn) log("监听异常: " + e.getMessage());
                Thread.sleep(5000);  // 异常后等待5秒再重试
            }
        }
    });
    listenThread.setDaemon(true);  // 守护线程，随主线程退出
    listenThread.start();
}
```

**核心逻辑**：

1. **循环监听**：`while (loggedIn)` 持续运行，直到登出
2. **拉取消息**：使用 `cursor` 分页拉取，`cursor` 记录上次拉取到的位置
3. **遍历消息**：外层遍历 `WeixinMessageDto`（消息包），内层遍历 `MessageItemDto`（消息项）
4. **过滤文本**：只处理文本消息，忽略图片、语音等
5. **触发回复**：调用 `autoReplyHandler.onMessage()` 获取回复内容，如果有回复则调用 `sendReply()`

---

### 2.4 消息发送

```java
public void sendReply(String toUserId, String clientId, String text) {
    if (!loggedIn) { log("未登录，无法发送"); return; }
    try {
        client.sendTextMessage(credentials.get(), toUserId, clientId, text);
        log("回复 -> " + toUserId + ": " + text);
    } catch (Exception e) {
        log("发送失败: " + e.getMessage());
    }
}
```

**参数说明**：
- `toUserId`：接收者用户 ID
- `clientId`：客户端 ID（区分同一用户的不同设备）
- `text`：要发送的文本内容

---

### 2.5 自动回复机制

```java
public void setAutoReply(ReplyHandler handler) {
    this.autoReplyHandler = handler;
}

@FunctionalInterface
public interface ReplyHandler {
    String onMessage(String fromUserId, String text);
}
```

**设计模式**：策略模式。`ReplyHandler` 是一个函数式接口，外部可以注入任意回复逻辑。这样消息监听和回复逻辑就解耦了，想换回复策略只需换一个 handler 即可。

---

### 2.6 内部 DTO

```java
public static class Msg {
    public String fromUser;   // 发信人ID
    public String clientId;   // 客户端ID
    public String content;    // 消息内容
    public long time = System.currentTimeMillis();  // 接收时间
    
    public Msg(String f, String c, String t) { fromUser = f; clientId = c; content = t; }
}
```

---

## 三、AIService - AI对话服务

**文件**：`src/main/java/com/demo/demo/Service/AIService.java`

### 3.1 类定义与配置

```java
@Service
public class AIService {
    @Value("${ai.api.key:}")
    private String apiKey;
    
    @Value("${ai.api.url}")
    private String apiUrl;
    
    @Value("${ai.model}")
    private String model;
    
    @Value("${ai.system-prompt}")
    private String systemPrompt;
    
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();
    
    private final Map<String, JsonArray> historyMap = new ConcurrentHashMap<>();
    private static final int MAX_HISTORY = 10;
}
```

**配置说明**：

| 配置项 | 来源 | 默认值 | 说明 |
|--------|------|--------|------|
| `apiKey` | `${ai.api.key}` | 空 | LLM API 密钥 |
| `apiUrl` | `${ai.api.url}` | 无（必填） | API 地址，如 `https://api.deepseek.com` |
| `model` | `${ai.model}` | 无（必填） | 模型名称，如 `deepseek-chat` |
| `systemPrompt` | `${ai.system-prompt}` | 无（必填） | 系统提示词，引导模型行为 |

**`historyMap`**：`ConcurrentHashMap<String, JsonArray>`，key 是用户 ID，value 是该用户的对话历史（JSON 数组）。每个用户最多保留 10 轮对话。

---

### 3.2 核心方法 - chat()

```java
public String chat(String userId, String message) {
    // 步骤1：检查API Key是否配置
    if (apiKey == null || apiKey.isEmpty() || "你的API_KEY".equals(apiKey)) {
        return null;
    }

    try {
        // 步骤2：获取用户对话历史，不存在则创建
        JsonArray messages = historyMap.computeIfAbsent(userId, k -> new JsonArray());

        // 步骤3：首次对话，加入系统提示
        if (messages.isEmpty()) {
            JsonObject sys = new JsonObject();
            sys.addProperty("role", "system");
            sys.addProperty("content", systemPrompt);
            messages.add(sys);
        }

        // 步骤4：加入用户消息
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", message);
        messages.add(userMsg);

        // 步骤5：限制历史长度（最多10轮 + 系统提示）
        while (messages.size() > MAX_HISTORY + 1) {
            messages.remove(1);  // 删除最早的非系统消息
        }

        // 步骤6：构建请求体
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", messages);
        body.addProperty("max_tokens", 500);
        body.addProperty("temperature", 0.7);

        // 步骤7：发送HTTP请求
        Request request = new Request.Builder()
                .url(apiUrl + "/v1/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                .build();

        try (Response response = client.newCall(request).execute()) {
            String json = response.body() != null ? response.body().string() : "";
            
            if (!response.isSuccessful()) {
                // 出错时移除刚加的用户消息，避免历史堆积
                if (messages.size() > 0) messages.remove(messages.size() - 1);
                return null;
            }

            // 步骤8：解析响应
            JsonObject result = JsonParser.parseString(json).getAsJsonObject();
            String reply = result.getAsJsonArray("choices")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("message")
                    .get("content").getAsString();

            // 步骤9：把AI回复加入历史
            JsonObject aiMsg = new JsonObject();
            aiMsg.addProperty("role", "assistant");
            aiMsg.addProperty("content", reply);
            messages.add(aiMsg);

            return reply.trim();
        }
    } catch (Exception e) {
        System.err.println("[AI] 调用失败: " + e.getMessage());
        return null;
    }
}
```

**逐步骤解释**：

1. **检查 API Key**：如果没配置或仍是默认值，直接返回 null
2. **获取对话历史**：用 `computeIfAbsent` 保证线程安全，不存在则创建空数组
3. **系统提示**：首次对话时添加 system 角色的消息，设定 AI 的行为模式
4. **用户消息**：添加 user 角色的消息
5. **历史长度限制**：`MAX_HISTORY = 10`，所以最多保留 10 轮对话 + 1 条系统提示，超过则删除最早的非系统消息
6. **请求体构建**：
   - `model`：模型名称
   - `messages`：消息列表（包含历史）
   - `max_tokens`：最大回复长度，500 个 token
   - `temperature`：温度系数，0.7 表示中等随机性
7. **发送请求**：POST 到 `/v1/chat/completions`，这是 OpenAI 兼容的标准接口
8. **解析响应**：从 JSON 中提取 `choices[0].message.content`
9. **保存回复**：把 AI 回复（assistant 角色）加入历史，下次对话能记住上下文

---

### 3.3 辅助方法

```java
public void clearHistory(String userId) {
    historyMap.remove(userId);  // 清除指定用户的对话历史
}

public boolean isConfigured() {
    return apiKey != null && !apiKey.isEmpty() && !"你的API_KEY".equals(apiKey);
}
```

---

## 四、BotController - 控制器

**文件**：`src/main/java/com/demo/demo/controller/BotController.java`

### 4.1 自动回复配置（核心业务逻辑）

```java
@PostConstruct
public void initAutoReply() {
    botService.setAutoReply((fromUser, text) -> {
        // 检查AI是否配置
        if (!aiService.isConfigured()) {
            return "AI 未配置，请联系管理员";
        }

        // 工具1：查天气
        if (text.contains("天气")) {
            String city = extractCity(text);
            if (city != null) {
                try {
                    String weather = WeatherUtil.getWeather(city);
                    String prompt = "用户问: \"" + text + "\"\n"
                            + "以下是实时天气数据: " + weather + "\n"
                            + "请用自然的中文把这天气数据告诉用户，两句话以内。";
                    String reply = aiService.chat(fromUser, prompt);
                    if (reply != null) return reply;
                } catch (Exception e) {
                    return "抱歉，查询「" + city + "」的天气失败了：" + e.getMessage();
                }
            }
        }

        // 工具2：查时间
        if (text.contains("几点") || text.contains("时间") || text.contains("日期")) {
            String now = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss"));
            String prompt = "用户问: \"" + text + "\"\n"
                    + "当前精确时间是: " + now + "\n"
                    + "请用一句话告诉用户现在的时间。";
            String reply = aiService.chat(fromUser, prompt);
            if (reply != null) return reply;
        }

        // 普通对话，直接丢给AI
        String aiReply = aiService.chat(fromUser, text);
        if (aiReply != null) return aiReply;

        return "（AI 暂时无响应，请稍后再试）";
    });
}
```

**业务逻辑流程**：

1. **AI未配置检查**：如果 API Key 没配，直接返回提示
2. **天气查询**：
   - 检测消息中是否包含"天气"关键词
   - 调用 `extractCity()` 提取城市名
   - 调用 `WeatherUtil.getWeather()` 获取天气数据
   - 构建带天气数据的 Prompt，调用 AI 生成自然语言回复
3. **时间查询**：
   - 检测消息中是否包含"几点"、"时间"、"日期"关键词
   - 获取当前时间，构建带时间信息的 Prompt，调用 AI
4. **普通对话**：不匹配任何工具，直接把消息发给 AI
5. **兜底回复**：AI 无响应时返回提示

---

### 4.2 城市提取方法

```java
private String extractCity(String text) {
    int idx = text.indexOf("天气");
    if (idx <= 0) return null;

    String before = text.substring(0, idx);

    // 去掉常见前缀：查一下、帮我查、我想知道 等
    before = before.replaceAll("(?s).*?(查一下|帮我查|我想知道|我想了解|给我查|请问)", "");
    // 去掉时间词
    before = before.replaceAll("(?s).*?(今天|明天|后天|昨天|现在|这周|下周|本周)", "");
    // 去掉介词/动词前缀
    before = before.replaceAll("(?s).*?(在|的|了|呢|吗|啊|呀|吧|一下|下)", "");
    // 去掉标点
    before = before.replaceAll("[，。！？、,.\\s]", "");
    before = before.trim();

    // 如果剩下的文本太长（>6字），可能没清理干净，只取后2-3字
    if (before.length() > 6) {
        before = before.substring(before.length() - 3);
    }

    return before.isEmpty() ? null : before;
}
```

**正则清洗步骤**（以"帮我查一下今天北京的天气怎么样"为例）：

| 步骤 | 正则 | 结果 |
|------|------|------|
| 原始 | - | "帮我查一下今天北京的天气怎么样" |
| 取"天气"前 | - | "帮我查一下今天北京的" |
| 去前缀 | `(?s).*?(查一下\|帮我查\|...)` | "今天北京的" |
| 去时间词 | `(?s).*?(今天\|明天\|...)` | "北京的" |
| 去介词 | `(?s).*?(在\|的\|了\|...)` | "北京" |
| 去标点 | `[，。！？、,.\s]` | "北京" |

---

### 4.3 REST API

```java
// 主页面，访问时自动触发登录
@GetMapping
public String botPage() {
    if (!botService.isLoggedIn()) {
        botService.startLogin();
    }
    return "bot";
}

// 获取登录状态和二维码
@GetMapping("/status")
@ResponseBody
public Map<String, Object> status() {
    Map<String, Object> map = new HashMap<>();
    map.put("loggedIn", botService.isLoggedIn());
    map.put("status", botService.getStatusText());
    map.put("qrCodeBase64", botService.getQrCodeBase64());
    map.put("qrCodeUrl", botService.getQrCodeUrl());
    return map;
}

// 拉取新消息和日志
@GetMapping("/messages")
@ResponseBody
public Map<String, Object> messages() {
    Map<String, Object> map = new HashMap<>();
    map.put("messages", botService.pollMessages());  // 拉取后清空
    map.put("logs", botService.getLogs());
    return map;
}

// 手动发送回复
@PostMapping("/send")
@ResponseBody
public Map<String, Object> send(@RequestParam String toUserId,
                                 @RequestParam String clientId,
                                 @RequestParam String text) {
    botService.sendReply(toUserId, clientId, text);
    Map<String, Object> map = new HashMap<>();
    map.put("ok", true);
    return map;
}

// 重新获取二维码
@PostMapping("/restart")
@ResponseBody
public Map<String, Object> restart() {
    botService.restartLogin();
    Map<String, Object> map = new HashMap<>();
    map.put("ok", true);
    return map;
}
```

---

## 五、WeatherUtil - 天气查询工具

**文件**：`src/main/java/com/demo/demo/Utils/WeatherUtil.java`

### 5.1 核心方法 - getWeather()

```java
public static String getWeather(String cityName) throws Exception {
    // 1. 构建请求URL
    String url = WTTR_API + "/" + cityName + "?format=j1";
    
    // 2. 发送HTTP请求
    String json = httpGet(url);
    JsonObject root = parseJson(json);

    // 3. 解析当前天气
    JsonArray conditions = root.getAsJsonArray("current_condition");
    if (conditions == null || conditions.isEmpty()) {
        throw new CityNotFoundException(cityName);
    }
    JsonObject current = conditions.get(0).getAsJsonObject();

    // 4. 获取天气描述并翻译
    String weatherEn = current.getAsJsonArray("weatherDesc")
            .get(0).getAsJsonObject()
            .get("value").getAsString().trim();
    String weatherDesc = translateWeather(weatherEn);

    // 5. 提取其他天气数据
    String tempC      = current.get("temp_C").getAsString();
    String feelsLikeC = current.get("FeelsLikeC").getAsString();
    String humidity   = current.get("humidity").getAsString();
    String windSpeed  = current.get("windspeedKmph").getAsString();
    String windDirEn  = current.get("winddir16Point").getAsString();
    String windDir    = WIND_ZH.getOrDefault(windDirEn, windDirEn);

    // 6. 提取城市国家信息
    JsonArray areas = root.getAsJsonArray("nearest_area");
    String country = "";
    if (areas != null && !areas.isEmpty()) {
        JsonObject area = areas.get(0).getAsJsonObject();
        country = area.getAsJsonArray("country")
                .get(0).getAsJsonObject()
                .get("value").getAsString();
    }

    // 7. 格式化返回
    return String.format(
            "城市: %s (%s) | 温度: %s°C (体感: %s°C) | 天气: %s | %s风 %s km/h | 湿度: %s%%",
            cityName, country, tempC, feelsLikeC, weatherDesc, windDir, windSpeed, humidity
    );
}
```

**执行步骤**：

1. **构建 URL**：`https://wttr.in/杭州?format=j1`（`format=j1` 表示返回 JSON 格式）
2. **发送请求**：调用 `httpGet()` 获取 JSON 响应
3. **解析天气数组**：从 `current_condition` 数组中取第一个元素
4. **翻译天气描述**：调用 `translateWeather()` 将英文翻译成中文
5. **提取数据**：温度、体感温度、湿度、风速、风向
6. **提取城市信息**：从 `nearest_area` 数组中获取国家信息
7. **格式化**：组装成可读的字符串

---

### 5.2 HTTP 请求方法

```java
private static String httpGet(String url) throws Exception {
    Request request = new Request.Builder()
            .url(url)
            .header("User-Agent", "curl/8.0")  // wttr.in 建议加 UA
            .build();
            
    try (Response response = CLIENT.newCall(request).execute()) {
        if (!response.isSuccessful()) {
            String body = response.body() != null ? response.body().string() : "(空)";
            if (body.contains("not found") || body.contains("Unknown location")) {
                throw new CityNotFoundException(...);
            }
            throw new Exception("HTTP " + response.code() + ": " + body);
        }
        return response.body().string();
    }
}
```

---

### 5.3 天气翻译方法

```java
private static String translateWeather(String english) {
    // 精确匹配
    String zh = ZH.get(english);
    if (zh != null) return zh;
    
    // 大小写不敏感匹配
    for (Map.Entry<String, String> e : ZH.entrySet()) {
        if (e.getKey().equalsIgnoreCase(english)) {
            return e.getValue();
        }
    }
    
    // 无匹配则保留原文
    return english;
}
```

**翻译映射表**（部分）：

| 英文 | 中文 |
|------|------|
| "Sunny" | "晴" |
| "Partly cloudy" | "多云" |
| "Cloudy" | "阴" |
| "Light rain" | "小雨" |
| "Heavy rain" | "大雨" |

---

## 六、AI查询天气完整调用链

以用户发送"杭州天气怎么样"为例，完整调用链如下：

### 第1步：消息接收（BotService）

```java
// BotService.java 第115-158行
listenThread = new Thread(() -> {
    while (loggedIn) {
        ReceiveMessagesResult result = client.receiveMessages(credentials.get(), cursor);
        // ...
        for (WeixinMessageDto dto : result.getMessages()) {
            for (MessageItemDto item : dto.getItemList()) {
                String text = item.getText();           // "杭州天气怎么样"
                String fromUser = dto.getFromUserId();  // 用户ID
                
                // 触发自动回复
                String reply = handler.onMessage(fromUser, text);
                if (reply != null) sendReply(fromUser, clientId, reply);
            }
        }
    }
});
```

### 第2步：意图识别（BotController）

```java
// BotController.java 第36-50行
if (text.contains("天气")) {           // "杭州天气怎么样".contains("天气") → true
    String city = extractCity(text);   // 提取城市 → "杭州"
    if (city != null) {
        String weather = WeatherUtil.getWeather(city);  // 查天气
        String prompt = "用户问: \"" + text + "\"\n"
                + "以下是实时天气数据: " + weather + "\n"
                + "请用自然的中文把这天气数据告诉用户，两句话以内。";
        String reply = aiService.chat(fromUser, prompt);  // 调用AI
        if (reply != null) return reply;
    }
}
```

### 第3步：城市提取（BotController）

```java
// BotController.java 第131-154行
private String extractCity(String text) {
    int idx = text.indexOf("天气");       // idx = 4
    String before = text.substring(0, 4); // "杭州"
    
    // 正则清洗后得到 "杭州"
    return before;
}
```

### 第4步：查询天气（WeatherUtil）

```java
// WeatherUtil.java 第94-147行
public static String getWeather(String cityName) throws Exception {
    String url = "https://wttr.in/杭州?format=j1";
    String json = httpGet(url);
    
    // 解析JSON，提取天气信息
    // 返回: "城市: 杭州 (China) | 温度: 28°C (体感: 32°C) | 天气: 晴 | 东风 15 km/h | 湿度: 65%"
}
```

### 第5步：构建Prompt并调用AI（AIService）

```java
// AIService.java 第52-118行
public String chat(String userId, String message) {
    // message = "用户问: \"杭州天气怎么样\"\n以下是实时天气数据: 城市: 杭州...\n请用自然的中文..."
    
    // 构建请求体
    JsonObject body = new JsonObject();
    body.addProperty("model", "deepseek-chat");
    body.add("messages", messages);  // 包含系统提示 + 用户消息
    
    // 发送请求到 https://api.deepseek.com/v1/chat/completions
    Request request = new Request.Builder()
            .url(apiUrl + "/v1/chat/completions")
            .header("Authorization", "Bearer " + apiKey)
            .post(...)
            .build();
    
    // 解析响应，返回AI回复
    // 例如: "杭州今天天气晴朗，气温28度，体感温度32度，东风15公里/小时，湿度65%。"
}
```

### 第6步：发送回复（BotService）

```java
// BotService.java 第161-169行
public void sendReply(String toUserId, String clientId, String text) {
    client.sendTextMessage(credentials.get(), toUserId, clientId, text);
    // 回复发送到微信
}
```

---

## 七、配置说明

**文件**：`src/main/resources/application.yml`

```yaml
server:
  port: 8080

spring:
  application:
    name: demo

ai:
  api:
    key: ${AI_API_KEY:}              # 从环境变量读取，或留空
    url: https://api.deepseek.com    # 默认DeepSeek API
  model: deepseek-chat               # 使用的模型
  system-prompt: 你是一个友好的微信助手，用简洁的中文回复，不要超过200字。
```

### 支持的 LLM API

只需修改 `ai.api.url`，即可切换到其他兼容 OpenAI 接口的模型：

| 服务 | API URL |
|------|---------|
| DeepSeek | `https://api.deepseek.com` |
| OpenAI | `https://api.openai.com` |
| 智谱 AI | `https://open.bigmodel.cn/api/paas/v4` |

### 运行方式

```bash
# 设置API Key
export AI_API_KEY=你的API_KEY

# 启动应用
java -jar demo-1.0.0.jar

# 访问
http://localhost:8080/bot
```

---

## 附录：文件清单

| 文件 | 路径 | 作用 |
|------|------|------|
| BotService.java | `src/main/java/com/demo/demo/Service/BotService.java` | iLink登录、消息监听、发送 |
| AIService.java | `src/main/java/com/demo/demo/Service/AIService.java` | LLM API调用、对话历史管理 |
| BotController.java | `src/main/java/com/demo/demo/controller/BotController.java` | REST API、自动回复逻辑 |
| WeatherUtil.java | `src/main/java/com/demo/demo/Utils/WeatherUtil.java` | 天气查询工具 |
| application.yml | `src/main/resources/application.yml` | 配置文件 |
| bot.html | `src/main/resources/templates/bot.html` | 前端页面 |
