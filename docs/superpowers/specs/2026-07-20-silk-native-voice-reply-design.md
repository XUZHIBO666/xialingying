# SILK 原生语音回复 — 详细设计

> 关联：P3-25、P3-30 | 前置：P0-4（AIService 线程安全）、P1-10（语音处理超时）

## 目标

在现有 MP3 文件回复的基础上，尝试通过微信 iLink 原生 `sendVoiceMessage()` 发送 SILK 格式语音回复。原生语音消息在微信中可以直接播放（无需下载文件），体验显著优于 MP3 文件附件。

---

## 1. 现状分析

### 当前语音回复路径

```
用户 SILK → downloadMedia → silkToPcm → ASR → LLM → TTS(MP3)
  → uploadMedia(type=3, mp3Bytes) → sendFileMessage("voice-reply-xxx.mp3")
  → 用户收到：📎 文件消息，需点击下载播放
```

### 目标双路径

```
用户 SILK → downloadMedia → silkToPcm → ASR → LLM → TTS(MP3)
  ├─ [优先] TTS → PCM → pcmToSilk → uploadMedia(type=?, silkBytes)
  │           → sendVoiceMessage(durationMs)
  │           → 用户收到：🔊 语音消息，直接点击播放
  │
  └─ [降级] uploadMedia(type=3, mp3Bytes) → sendFileMessage("xxx.mp3")
            → 用户收到：📎 文件消息
```

### iLink SDK 1.0.1 相关 API

从 SDK JAR 分析获得：

```java
// 发送语音消息
public void sendVoiceMessage(
    LoginCredentials credentials,
    String toUserId,
    String contextToken,
    MediaInfo media,      // uploadMedia 返回的媒体信息
    int durationMs,       // 语音时长（毫秒）
    int format            // 音频格式（待确认：SILK 的 format 值）
);

// 上传媒体
public MediaInfo uploadMedia(
    LoginCredentials credentials,
    int mediaType,        // 1=图片, 3=文件, 语音的 mediaType 待确认
    String toUserId,
    byte[] data
);
```

---

## 2. TTS → SILK 转换链路

### 2.1 音频格式转换链

```
SiliconFlow TTS
  → MP3 字节（当前唯一输出，response_format=mp3）

需要新增：
TTS MP3 → 解码为 PCM S16LE 16kHz mono → pcmToSilk → SILK V3 + 0x02 前缀
```

**关键问题**：SiliconFlow TTS 直接输出 MP3。要将 MP3 转回 PCM 再编码为 SILK，需要增加一步 MP3 → PCM 解码。

### 2.2 方案比选

| 方案 | 描述 | 优点 | 缺点 |
|------|------|------|------|
| A: 外部 FFmpeg | MP3 → PCM via FFmpeg, then PCM → SILK | 成熟稳定 | 增加 FFmpeg 依赖和部署复杂度 |
| B: Java MP3 解码 | 使用 `javax.sound.sampled` 解码 MP3 | 零外部依赖 | Java SPI 需要 MP3 plugin（如 JLayer） |
| C: TTS 直接出 PCM | 请求 SiliconFlow TTS 时不指定 `response_format=mp3` | 跳过整个 MP3→PCM 步骤 | ⚠️ 需要验证 SiliconFlow 是否支持 PCM/WAV 输出 |

**推荐方案 C（优先验证）+ A（兜底）**：

1. 首先验证 SiliconFlow `/v1/audio/speech` 是否支持 `response_format=wav` 或 `response_format=pcm`
2. 如果支持，TTS 直接输出 WAV/PCM → 跳过 MP3 解码 → 直接 `pcmToSilk()`
3. 如果不支持，使用方案 A（FFmpeg 解码 MP3 → PCM）

### 2.3 推荐实现（方案 A）

```java
// AudioConverter 新增方法
public byte[] mp3ToPcm(byte[] mp3Audio) throws IOException {
    // 使用外部解码器：
    // ffmpeg -i input.mp3 -f s16le -acodec pcm_s16le -ar 16000 -ac 1 output.pcm
    validateMp3(mp3Audio);
    Path workDir = Files.createTempDirectory("claw-voice-mp3decode-");
    Path mp3File = workDir.resolve("input.mp3");
    Path pcmFile = workDir.resolve("output.pcm");
    try {
        Files.write(mp3File, mp3Audio);
        commandExecutor.execute(List.of(
            properties.getAudio().getFfmpegPath(),  // 新增配置
            "-i", mp3File.toString(),
            "-f", "s16le",
            "-acodec", "pcm_s16le",
            "-ar", "16000",
            "-ac", "1",
            pcmFile.toString(),
            "-y", "-loglevel", "error"
        ), properties.getAudio().getProcessTimeout());
        return readValidPcm(pcmFile);
    } finally { /* cleanup */ }
}
```

### 2.4 更优实现（方案 C — 如果 SiliconFlow 支持）

```java
// SiliconFlowTtsService.synthesize() 的重载版本
public byte[] synthesizePcm(String text) throws IOException {
    JsonObject body = new JsonObject();
    body.addProperty("model", properties.getTts().getModel());
    body.addProperty("voice", properties.getTts().getVoice());
    body.addProperty("input", text);
    body.addProperty("response_format", "wav");  // 或 "pcm"
    // ...

    // 解析 WAV 头，提取裸 PCM 数据
    // 或直接返回 PCM（如果 API 支持）
}
```

---

## 3. VoiceMessageService 改造

### 3.1 新的处理流程

```java
public Result process(String userId, byte[] silkAudio) {
    // 第 1-3 步不变：SILK → PCM → ASR → LLM
    String recognizedText = asr(silkToPcm(silkAudio));
    String reply = llm(userId, recognizedText);

    // 第 4 步：TTS 同时生成 MP3（用于降级）和 PCM（用于 SILK 编码）
    byte[] replyMp3 = ttsService.synthesize(reply);       // 总是生成（降级用）
    byte[] replyPcm = ttsService.synthesizePcm(reply);    // 尝试生成 PCM

    byte[] replySilk = null;
    if (replyPcm != null && replyPcm.length > 0) {
        try {
            replySilk = audioCodecService.pcmToSilk(replyPcm);
        } catch (IOException e) {
            log.warn("[语音] SILK 编码失败，降级到 MP3: {}", e.getMessage());
        }
    }

    return new Result(reply, replyMp3, replySilk,
            estimateSilkDuration(replySilk));
}
```

### 3.2 Result 扩展

```java
public record Result(
    String text,          // LLM 回复文本
    byte[] mp3Audio,      // MP3（降级路径）
    byte[] silkAudio,     // SILK（优先路径，可为 null）
    int durationMs        // SILK 语音时长（毫秒，用于 sendVoiceMessage）
) {
    public static Result textOnly(String text) {
        return new Result(text, null, null, 0);
    }

    public boolean hasSilk() {
        return silkAudio != null && silkAudio.length > 0;
    }

    public boolean hasMp3() {
        return mp3Audio != null && mp3Audio.length > 0;
    }
}
```

### 3.3 SILK 时长估算

```java
private int estimateSilkDuration(byte[] silkAudio) {
    if (silkAudio == null || silkAudio.length == 0) return 0;
    // SILK 16kHz 的码率约 24 kbps (3000 bytes/s)
    // durationMs = bytes / 3 (粗略估算)
    // 实际时长可通过解析 SILK 帧头获取，此处用估算
    // 微信不严格要求精确时长，±20% 误差可容忍
    return (int) (silkAudio.length * 1000L / 3000);
}
```

---

## 4. BotService 发送逻辑改造

### 4.1 三阶发送策略

```java
private boolean sendVoiceReply(String toUser, String contextToken,
                                VoiceMessageService.Result result) {

    // 第 1 阶：尝试 SILK 原生语音
    if (result.hasSilk()) {
        if (sendSilkVoiceMessage(toUser, contextToken,
                result.silkAudio(), result.durationMs())) {
            return true;  // 成功！不发送文本或 MP3
        }
        log.info("[语音] SILK 原生发送失败，尝试 MP3 降级");
    }

    // 第 2 阶：MP3 文件回复（现有路径）
    if (result.hasMp3()) {
        if (sendMp3Reply(toUser, contextToken, result.mp3Audio())) {
            return true;
        }
        log.info("[语音] MP3 发送失败，降级到文字");
    }

    // 第 3 阶：纯文本回复（最终降级）
    sendReply(toUser, contextToken, result.text());
    return false;
}
```

### 4.2 SILK 原生发送方法

```java
private boolean sendSilkVoiceMessage(String toUser, String contextToken,
                                      byte[] silkAudio, int durationMs) {
    if (!loggedIn || silkAudio == null || silkAudio.length == 0) {
        return false;
    }
    try {
        // mediaType 的值需要实测确认：图片=1, 文件=3, 语音可能=2 或 4
        ILinkClient.MediaInfo media = client.uploadMedia(
                credentials.get(), VOICE_MEDIA_TYPE, toUser, silkAudio);
        // format 参数：需要实测确认微信 SILK 的 format 值
        client.sendVoiceMessage(credentials.get(), toUser, contextToken,
                media, durationMs, SILK_VOICE_FORMAT);
        log.info("[iLink] SILK 语音发送成功 to={} durationMs={} silkBytes={}",
                toUser, durationMs, silkAudio.length);
        displayLog("语音回复 -> " + toUser + " (SILK " + durationMs + "ms)");
        return true;
    } catch (Exception e) {
        log.warn("[iLink] SILK 语音发送失败 to={} error={}",
                toUser, e.getMessage());
        return false;
    }
}
```

**待实测确认的常量**：

| 常量 | 猜测值 | 含义 | 确认方式 |
|------|--------|------|---------|
| `VOICE_MEDIA_TYPE` | `2` 或 `4` | uploadMedia 的媒体类型 | 查看 SDK 源码或通过试错确认 |
| `SILK_VOICE_FORMAT` | `0` 或 `1` | sendVoiceMessage 的音频格式 | 查看 SDK 源码或通过试错确认 |

---

## 5. 配置扩展

### 5.1 VoiceProperties 新增

```java
public static class Tts {
    // 现有字段保持不变
    private String responseFormat = "mp3";  // 新增：默认 mp3，可选 "wav" / "pcm"
}

public static class Audio {
    // 现有字段
    private String silkDecoderPath = "";
    private String silkEncoderPath = "";
    private String ffmpegPath = "";         // 新增：FFmpeg 可执行文件路径
}
```

### 5.2 application.yml 新增

```yaml
ai:
  voice:
    tts:
      response-format: ${VOICE_TTS_RESPONSE_FORMAT:mp3}
    audio:
      ffmpeg-path: ${VOICE_FFMPEG_PATH:}
    silk:
      native-reply-enabled: ${VOICE_SILK_NATIVE_REPLY_ENABLED:false}  # 功能开关
```

**默认关闭**：`VOICE_SILK_NATIVE_REPLY_ENABLED=false`，直到 mediaType 和 format 参数在真实微信环境中确认后，再设为 `true`。

---

## 6. 降级矩阵

| 阶段 | 失败点 | 降级路径 |
|------|--------|---------|
| TTS PCM 合成 | API 不支持 wav/pcm 格式 | 尝试 FFmpeg 解码 MP3 → PCM |
| FFmpeg 解码 | FFmpeg 未安装/命令失败 | 跳转到第 2 阶（MP3） |
| PCM → SILK 编码 | encoder 未配置/编码失败 | 跳转到第 2 阶（MP3） |
| uploadMedia (SILK) | mediaType 参数错误/SDK 拒绝 | 跳转到第 2 阶（MP3） |
| sendVoiceMessage | format 参数错误/SDK 异常 | 跳转到第 2 阶（MP3） |
| sendFileMessage (MP3) | 上传/发送失败 | 跳转到第 3 阶（文字） |
| sendTextMessage | 发送失败 | 静默失败（已记录日志） |

---

## 7. 测试策略

### 单元测试

| # | 场景 | 验证点 |
|---|------|--------|
| 1 | TTS 返回 wav → 提取 PCM 成功 | synthesizePcm() 返回有效 PCM |
| 2 | TTS 不支持 wav → 返回 null | synthesizePcm() 返回 null，不抛异常 |
| 3 | PCM → SILK 编码成功 | pcmToSilk() 返回 0x02 前缀的 SILK V3 |
| 4 | SILK 编码失败 | 抛 IOException，Result 中 silkAudio=null |
| 5 | voice.Result 三态 | textOnly / hasMp3 / hasSilk 状态正确 |
| 6 | SILK 时长估算 | 已知 bytes → durationMs 在合理范围 |

### 集成测试（VoiceMessageReplyTest 扩展）

| # | 场景 | 验证点 |
|---|------|--------|
| 1 | SILK 发送成功 | sendVoiceMessage 被调用，不调 sendFileMessage |
| 2 | SILK 发送失败 → MP3 成功 | sendVoiceMessage 返回 false，MP3 被发送 |
| 3 | SILK + MP3 都失败 → 文字 | 纯文本被发送 |
| 4 | 功能开关关闭 | 不走 SILK 路径，直接 MP3 |

### 手工验证

需要在真实微信环境中完成：
1. 发送语音，确认收到的语音消息可直接播放
2. 语音时长显示正确
3. 音质可接受（与原始语音对比）
4. 连续多条语音稳定性

---

## 8. 实施前置条件

在开始编码前，必须先完成：

1. **SiliconFlow TTS 格式验证**：调用 `/v1/audio/speech` 测试 `response_format=wav` 是否返回有效 WAV
2. **mediaType 参数确认**：通过反编译 SDK 或试错确认 `uploadMedia` 语音的 `mediaType`
3. **format 参数确认**：确认 `sendVoiceMessage` 的 `format` 参数值
4. **FFmpeg 可用性**（如方案 A）：确认目标部署环境有 FFmpeg

这些前置条件标记在实施计划中为"阻塞项"。
