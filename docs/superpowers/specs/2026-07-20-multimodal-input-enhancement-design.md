# 多模态输入增强 — 详细设计

> 关联：P3-26 | 前置：P3-23（持久化对话记忆）

## 目标

让语音消息和文本消息能够利用同一用户最近的多模态上下文（图片、语音转写），使 Bot 的回复更智能、更有上下文感知能力。

---

## 1. 现状分析

### 当前消息处理————孤立模式

```
时间线：
  用户 A 发送图片 → 图片识别 → "这是一只猫"
  用户 A 发送文字 "这是什么颜色？" → LLM 只看文字 → "请问你指的是什么？" ❌
```

各模态独立处理，互不感知。用户需要在一轮消息中完整描述所有信息。

### 目标————上下文感知

```
  用户 A 发送图片(猫) → 图片识别 ──→ "这是一只橘色的猫"
                        识别文本存入上下文
  用户 A 发送文字 "它是什么颜色？" → LLM 看到：
    system: ...
    context: [最近图片：这是一只橘色的猫]
    user: 它是什么颜色？
  → "图中的猫是橘色的" ✅
```

---

## 2. 上下文窗口设计

### 2.1 SensorialContext

为每个活跃用户维护一个会话级感知上下文窗口：

```java
package com.demo.demo.Service.context;

public class SensorialContext {

    /** 最近一次图片识别的文字描述（最多保留 1 条） */
    private String lastImageDescription;

    /** 最近一次图片识别的时间戳 */
    private long lastImageTime;

    /** 最近一次语音 ASR 转写的文字（最多保留 1 条） */
    private String lastVoiceTranscript;

    /** 最近一次语音的时间戳 */
    private long lastVoiceTime;

    /** 上下文有效期（超过此时间视为过期） */
    private static final long CONTEXT_TTL_MS = TimeUnit.MINUTES.toMillis(5);

    /** 记录一次图片识别结果 */
    public void recordImage(String description) {
        this.lastImageDescription = description;
        this.lastImageTime = System.currentTimeMillis();
    }

    /** 记录一次语音转写结果 */
    public void recordVoice(String transcript) {
        this.lastVoiceTranscript = transcript;
        this.lastVoiceTime = System.currentTimeMillis();
    }

    /** 构建注入 LLM 的上下文文本 */
    public String buildContextHint() {
        StringBuilder sb = new StringBuilder();
        long now = System.currentTimeMillis();

        if (lastImageDescription != null
                && now - lastImageTime < CONTEXT_TTL_MS) {
            sb.append("用户刚才发了一张图片，内容是：")
              .append(lastImageDescription).append("\n");
        }
        if (lastVoiceTranscript != null
                && now - lastVoiceTime < CONTEXT_TTL_MS) {
            sb.append("用户刚才发了一条语音，内容是：")
              .append(lastVoiceTranscript).append("\n");
        }

        return sb.toString();
    }

    /** 同一条消息不重复记录（图片识别文本 ≠ 用户输入文本） */
    public void clearExpired() {
        long now = System.currentTimeMillis();
        if (now - lastImageTime >= CONTEXT_TTL_MS) lastImageDescription = null;
        if (now - lastVoiceTime >= CONTEXT_TTL_MS) lastVoiceTranscript = null;
    }
}
```

### 2.2 ContextManager

```java
@Component
public class ContextManager {

    // 用户 → 感知上下文（ConcurrentHashMap + 过期清理）
    private final ConcurrentHashMap<String, SensorialContext> contexts
            = new ConcurrentHashMap<>();
    private static final int MAX_CONTEXTS = 200;

    public void recordImage(String userId, String description) {
        contextFor(userId).recordImage(description);
    }

    public void recordVoice(String userId, String transcript) {
        contextFor(userId).recordVoice(transcript);
    }

    public String buildEnhancedSystemMessage(String userId, String baseSystemPrompt) {
        SensorialContext ctx = contexts.get(userId);
        if (ctx == null) return baseSystemPrompt;

        String hint = ctx.buildContextHint();
        if (hint.isEmpty()) return baseSystemPrompt;

        return baseSystemPrompt + "\n\n[近期上下文]\n" + hint;
    }

    private SensorialContext contextFor(String userId) {
        return contexts.computeIfAbsent(userId, k -> {
            if (contexts.size() >= MAX_CONTEXTS) {
                // 清理过期 + 最旧的条目
                evictOldest();
            }
            return new SensorialContext();
        });
    }

    /** 定期清理过期上下文（由 BotService 或定时任务触发） */
    public void cleanExpired() {
        contexts.entrySet().removeIf(e -> {
            e.getValue().clearExpired();
            return e.getValue().isEmpty();
        });
    }
}
```

---

## 3. 集成点

### 3.1 图片识别后记录上下文

```java
// BotController.initAutoReply() 中修改 ImageReplyHandler
botService.setImageReply((fromUser, contextToken, imageBytes) -> {
    String description = imageRecognitionService.recognize(imageBytes);
    if (description != null && !description.isBlank()) {
        // 记录到上下文窗口
        contextManager.recordImage(fromUser, description.trim());
    }
    return description;
});
```

### 3.2 语音识别后记录上下文

```java
// VoiceMessageService.process() 中，ASR 成功后
try {
    recognizedText = asrService.transcribe(pcm);
    if (recognizedText != null && !recognizedText.isBlank()) {
        recognizedText = recognizedText.trim();
        contextManager.recordVoice(userId, recognizedText);  // 记录
    }
} catch (...) { ... }
```

### 3.3 LLM 调用时注入上下文

```java
// AIService.chat() 中，构建 messages 前
String enhancedSystemPrompt = contextManager
        .buildEnhancedSystemMessage(userId, systemPrompt);

// 用 enhancedSystemPrompt 替代 systemPrompt 作为第一条消息
```

### 3.4 上下文接口

```java
// BotController 中暴露手动清除上下文的能力
@PostMapping("/context/clear")
@ResponseBody
public Map<String, Object> clearContext(@RequestParam String userId) {
    contextManager.clear(userId);
    return Map.of("ok", true);
}
```

---

## 4. 增强场景

### 4.1 图片 → 文字追问

```
用户: [图片：一份菜单]
Bot: 这是一份中餐菜单，有宫保鸡丁、麻婆豆腐...
用户: "第一个菜多少钱"
Bot: (能看到上次图片识别结果) "宫保鸡丁的价格在菜单上显示为..."
```

### 4.2 语音 → 文字追问

```
用户: [语音] "帮我查一下今天北京的天气"
Bot: 今天北京晴天，25°C...
用户: "那明天呢"
Bot: (能看到上次语音转写提到"北京") 查询北京明天天气...
```

### 4.3 图片 → 语音回复

```
用户: [图片：一只受伤的鸟]
Bot: 识图："这是一只翅膀受伤的麻雀"
用户: [语音] "怎么救它"
Bot: (上下文有图片描述 + ASR 转写)
  → LLM 知道用户在问鸟 → 生成救助建议 → TTS → MP3 回复
```

---

## 5. 隐私考量

- 上下文保存在 JVM 内存中，不持久化到文件（与 `ConversationMemoryStore` 分开）
- 上下文有过期时间（5 分钟），过期后自动清除
- 上下文只包含 AI 生成的摘要文本，不包含原始图片/语音二进制数据
- `ContextManager.cleanExpired()` 可由定时任务调用，限制内存占用

---

## 6. 测试策略

| # | 场景 | 验证点 |
|---|------|--------|
| 1 | 图片后立即追问 | LLM 收到的 system prompt 包含图片上下文 |
| 2 | 上下文过期（> 5 分钟） | system prompt 不含过期的图片上下文 |
| 3 | 语音后文字追问 | system prompt 包含语音转写上下文 |
| 4 | 无上下文的普通消息 | system prompt 保持不变 |
| 5 | 上下文容量限制 | 超过 200 用户时 evict 最旧的 |
| 6 | 手动清除上下文 | `/bot/context/clear` 后上下文为空 |
| 7 | 多用户隔离 | 用户 A 的上下文不影响用户 B |

---

## 7. 未来扩展

- **跨模态引用解析**："上一张图片"、"刚刚那条语音" —— LLM 根据上下文字段理解引用
- **上下文优先级**：图片上下文 > 语音上下文 > 纯文本历史
- **上下文持久化**：跨重启恢复上下文（需要引入 Redis 或本地文件）
