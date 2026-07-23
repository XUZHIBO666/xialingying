# Post-Claude Project Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 `feat/voice-mvp` 与稳定性/隐私修复整合为可安全合并、可确定性验证、真正发送微信 SILK 语音回复的版本。

**Architecture:** 以功能覆盖更完整的 `feat/voice-mvp` 为整合基线，逐项移植 `feat/bot-stability-privacy` 的线程生命周期和隐私语义，不直接自动合并两个严重冲突的 `BotService`。所有外部 URL 统一经过严格校验；消息只在接收入口限流一次；语音链路固定为 SILK → PCM 16 kHz → ASR → LLM → TTS PCM 16 kHz → SILK → iLink 原生语音消息。

**Tech Stack:** Java 21, Spring Boot, JUnit 5, Mockito, OkHttp, Gson, wechat-ilink-sdk 1.0.1, SiliconFlow ASR/TTS, qiniu-java-sdk 7.19.0。

## Global Constraints

- 保留 `E:/summer-projects/xialingying/.worktrees/bot-stability-privacy` 中现有未提交修改；整合前不得 reset、checkout 或覆盖。
- 不升级 Java、Spring Boot 或 iLink SDK；当前 iLink SDK 1.0.1 已包含 `sendVoiceMessage`。
- 不记录或返回消息正文、令牌、媒体参数、用户原始 ID 或外部响应正文。
- 所有缺陷先写失败测试，再做最小实现；每个任务独立提交。
- 完整验证命令统一为 `.\\mvnw.cmd -o clean test`；原生 SILK 集成测试需显式配置编码器/解码器路径。

---

## 审查基线

- `feat/voice-mvp`：相对 `c4ed67e` 有 24 个提交、63 个文件变化；干净工作树。
- `feat/bot-stability-privacy`：昨日 3 个提交，另有 8 个未提交文件。
- 两分支共同修改 `AIService`、`BotService`、`ImageRecognitionService`、`WeatherUtil`、`BotController` 等核心文件，`git merge-tree` 已确认存在实质冲突。
- 2026-07-21 验证：`voice-mvp` 158 tests / 0 failures / 1 skipped；`bot-stability-privacy` 130 tests / 0 failures / 0 skipped。
- 唯一跳过项是需要真实 SILK 编解码器的 `AudioConverterIntegrationTest`；当前通过结果不等于原生微信语音闭环已验证。

### Task 1: 建立可回滚的整合基线

**Files:**
- Inspect: `E:/summer-projects/xialingying/.worktrees/bot-stability-privacy`
- Modify: none

**Interfaces:**
- Consumes: `feat/voice-mvp`, `feat/bot-stability-privacy`, 未提交稳定性补丁。
- Produces: 一个从 `feat/voice-mvp` 创建的整合分支，以及可审计的稳定性补丁快照。

- [ ] **Step 1: 记录两个工作树的不可变基线**

Run:

```powershell
git -C E:/summer-projects/xialingying/.worktrees/voice-mvp rev-parse HEAD
git -C E:/summer-projects/xialingying/.worktrees/bot-stability-privacy rev-parse HEAD
git -C E:/summer-projects/xialingying/.worktrees/bot-stability-privacy diff --check
```

Expected: 两个 SHA 被记录；`diff --check` 无新的空白错误。

- [ ] **Step 2: 在 `voice-mvp` 基础上创建整合分支**

Run:

```powershell
git -C E:/summer-projects/xialingying/.worktrees/voice-mvp switch -c fix/post-claude-review
```

Expected: 当前分支为 `fix/post-claude-review`，原两个分支指针不变。

- [ ] **Step 3: 用三方差异清单手工移植，不执行整分支 merge**

Run:

```powershell
git -C E:/summer-projects/xialingying diff --name-only feat/voice-mvp...feat/bot-stability-privacy
git -C E:/summer-projects/xialingying/.worktrees/bot-stability-privacy diff --name-only
```

Expected: 将 `BotServiceShutdownTest`、`ServerLogPrivacyTest`、监听 session 校验和中断取消语义纳入后续任务；不覆盖 voice-mvp 的语音、限流、记忆和工具调用功能。

### Task 2: 封堵生成图片下载 SSRF 与响应正文泄漏

**Files:**
- Modify: `src/main/java/com/demo/demo/Service/ImageGenerationService.java`
- Modify: `src/main/java/com/demo/demo/controller/BotController.java`
- Test: `src/test/java/com/demo/demo/Service/ImageGenerationServiceTest.java`
- Test: `src/test/java/com/demo/demo/Service/ServerLogPrivacyTest.java`

**Interfaces:**
- Consumes: 供应商返回的 `data[0].url` 或 `images[0].url`。
- Produces: 只允许 HTTPS、公网地址、且重定向每一跳重新校验的下载流程。

- [ ] **Step 1: 写能重现 substring 信任绕过的失败测试**

```java
@Test
void rejectsLocalUrlEvenWhenPathContainsSiliconflow() throws Exception {
    startServer(exchange -> send(exchange, 200,
            "{\"images\":[{\"url\":\"http://127.0.0.1/siliconflow/image.png\"}]}"));

    IOException error = assertThrows(IOException.class,
            () -> service().generateImage("unsafe"));

    assertTrue(error.getMessage().contains("安全"));
}
```

- [ ] **Step 2: 验证测试先失败**

Run: `.\\mvnw.cmd -o -Dtest=ImageGenerationServiceTest#rejectsLocalUrlEvenWhenPathContainsSiliconflow test`

Expected: FAIL；当前 `url.contains("siliconflow")` 进入免校验下载路径。

- [ ] **Step 3: 删除 trusted URL 旁路，所有 URL 统一校验**

```java
if (first.has("url") && !first.get("url").isJsonNull()) {
    return downloadImage(first.get("url").getAsString());
}
```

删除 `isSiliconFlowUrl()` 和 `downloadFromTrustedUrl()`；保持 `followRedirects(false)`，如确需支持重定向，则显式读取 `Location`，每一跳调用 `validateDownloadUrl()`，最多 3 跳。

- [ ] **Step 4: 禁止日志与用户响应携带供应商正文**

将错误日志收敛为状态码、异常类型和 trace id（若响应头存在），例如：

```java
log.error("[图片生成] API 失败 httpStatus={} traceId={}",
        response.code(), response.header("x-siliconcloud-trace-id", "missing"));
throw new IOException("图片生成服务暂时不可用，HTTP " + response.code());
```

`BotController` 返回固定友好提示，不拼接 `e.getMessage()`。

- [ ] **Step 5: 运行安全与回归测试并提交**

Run: `.\\mvnw.cmd -o -Dtest=ImageGenerationServiceTest,ServerLogPrivacyTest test`

Expected: PASS，日志捕获中不含供应商响应正文。

Commit: `fix: secure generated image downloads`

### Task 3: 修正限流、排队与图片重复提交

**Files:**
- Modify: `src/main/java/com/demo/demo/Service/BotService.java`
- Test: `src/test/java/com/demo/demo/Service/ImageMessageAsyncTest.java`
- Test: `src/test/java/com/demo/demo/Service/UserMessageSerializationTest.java`
- Create: `src/test/java/com/demo/demo/Service/ReplyAdmissionTest.java`

**Interfaces:**
- Consumes: 每条入站消息。
- Produces: 每条消息只消耗一个令牌；队列满时快速拒绝，监听线程绝不执行耗时任务。

- [ ] **Step 1: 写图片消息只限流一次的失败测试**

```java
@Test
void oneImageConsumesOnePermitAndStillRunsRecognition() {
    botService.processImageItem("wx-user", "ctx", imageContent);

    verify(imageReplyHandler, timeout(1000)).onImage(eq("wx-user"), eq("ctx"), any());
    assertEquals(1, botService.getTotalRateLimitAccepted());
    assertEquals(0, botService.getTotalRateLimitRejected());
}
```

- [ ] **Step 2: 写队列饱和时监听线程不执行任务的失败测试**

```java
@Test
void saturatedQueueRejectsWithoutRunningTaskOnCaller() {
    String caller = Thread.currentThread().getName();
    saturateReplyExecutor();

    botService.processTextMessage("wx-user", "ctx", "hello");

    assertNotEquals(caller, executedThread.get());
    assertEquals(1, botService.getRejectedReplyTaskCount());
}
```

- [ ] **Step 3: 仅在入站入口执行 admission，内部阶段不重复 submit**

将图片下载与识别合并为同一个已接纳任务；`processImageMessage` 变成不再调用 `submitReplyTask` 的内部方法：

```java
submitReplyTask(fromUser, contextToken, () -> {
    byte[] imageBytes = client.downloadMedia(downloadParam, aesKey);
    processImageMessageInWorker(fromUser, contextToken, imageBytes);
});
```

- [ ] **Step 4: 使用 AbortPolicy 并在拒绝时只做轻量记录**

```java
new ThreadPoolExecutor.AbortPolicy()
```

捕获 `RejectedExecutionException` 后递增计数并返回；不要从监听线程同步调用网络发送。

- [ ] **Step 5: 验证并提交**

Run: `.\\mvnw.cmd -o -Dtest=ReplyAdmissionTest,ImageMessageAsyncTest,UserMessageSerializationTest test`

Expected: PASS；同一用户 FIFO 不变，队列饱和不阻塞监听线程。

Commit: `fix: make reply admission non-blocking`

### Task 4: 移植稳定性分支的 session 与关闭语义

**Files:**
- Modify: `src/main/java/com/demo/demo/Service/BotService.java`
- Test: `src/test/java/com/demo/demo/Service/BotServiceShutdownTest.java`
- Test: `src/test/java/com/demo/demo/Service/ILinkSessionLifecycleTest.java`

**Interfaces:**
- Consumes: login/listener session id、在途 reply/voice task。
- Produces: 旧 session 结果不能覆盖新状态；关闭后没有新发送；语音任务可取消。

- [ ] **Step 1: 从稳定性工作树移植失败测试而非复制整个 BotService**

至少保留这些行为测试：旧 listener 丢弃 cursor、关闭后丢弃下载结果、关闭会取消语音任务、重复 shutdown 幂等。

- [ ] **Step 2: 在语音中断路径取消子任务**

```java
} catch (InterruptedException e) {
    task.cancel(true);
    Thread.currentThread().interrupt();
    return;
}
```

中断/关闭时不得再发送 ASR 失败提示。

- [ ] **Step 3: 给监听器增加递增 session 校验**

```java
private final AtomicInteger listenerSession = new AtomicInteger();

private boolean isCurrentListenerSession(int session) {
    return !shuttingDown && loggedIn && listenerSession.get() == session;
}
```

更新 cursor、处理消息和处理异常前都验证 session；restart/shutdown 递增 session。

- [ ] **Step 4: 验证并提交**

Run: `.\\mvnw.cmd -o -Dtest=BotServiceShutdownTest,ILinkSessionLifecycleTest test`

Expected: PASS，测试结束后不存在活跃 `voice-process-*` 线程。

Commit: `fix: preserve bot lifecycle invariants`

### Task 5: 让 readiness 通过 HTTP 状态表达真实结果

**Files:**
- Modify: `src/main/java/com/demo/demo/controller/BotHealthController.java`
- Test: `src/test/java/com/demo/demo/controller/BotHealthControllerTest.java`

**Interfaces:**
- Produces: ready 时 HTTP 200；未登录、队列饱和或关闭时 HTTP 503。

- [ ] **Step 1: 写 DOWN 返回 503 的失败测试**

```java
mockMvc.perform(get("/bot/health/ready"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.status").value("DOWN"));
```

- [ ] **Step 2: 返回 `ResponseEntity`**

```java
HttpStatus status = allUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
return ResponseEntity.status(status).body(result);
```

将关闭中的 executor 计为 DOWN；去掉未使用的 `ManagementFactory`、`ThreadPoolExecutor` import。

- [ ] **Step 3: 验证并提交**

Run: `.\\mvnw.cmd -o -Dtest=BotHealthControllerTest test`

Expected: ready=200，not-ready=503，live 始终保持轻量 200。

Commit: `fix: report readiness through http status`

### Task 6: 完成原生微信 SILK 语音回复

**Files:**
- Modify: `src/main/java/com/demo/demo/Service/voice/SiliconFlowTtsService.java`
- Modify: `src/main/java/com/demo/demo/Service/voice/VoiceMessageService.java`
- Modify: `src/main/java/com/demo/demo/Service/BotService.java`
- Modify: `src/test/java/com/demo/demo/Service/voice/SiliconFlowTtsServiceTest.java`
- Modify: `src/test/java/com/demo/demo/Service/voice/VoiceMessageServiceTest.java`
- Modify: `src/test/java/com/demo/demo/Service/VoiceMessageReplyTest.java`

**Interfaces:**
- Consumes: TTS 文本。
- Produces: PCM S16LE 16 kHz mono、Tencent SILK bytes、playtime ms、iLink encodeType 6。

- [ ] **Step 1: 改写测试，要求 PCM 而非 MP3**

```java
assertEquals("pcm", requestBody.get().get("response_format").getAsString());
assertEquals(16000, requestBody.get().get("sample_rate").getAsInt());
verify(codec).pcmToSilk(ttsPcm);
```

- [ ] **Step 2: TTS 请求固定输出 16 kHz PCM**

```java
body.addProperty("response_format", "pcm");
body.addProperty("sample_rate", 16000);
body.addProperty("stream", false);
```

将 `MAX_MP3_BYTES`/局部变量重命名为 PCM 语义，并校验非空、偶数字节数和上限。

- [ ] **Step 3: VoiceMessageService 编码 SILK 并携带时长**

```java
byte[] pcm = ttsService.synthesize(reply);
byte[] silk = audioCodecService.pcmToSilk(pcm);
int playtimeMs = Math.max(1, (int) (pcm.length * 1000L / (16000 * 2)));
return new Result(reply, silk, playtimeMs);
```

- [ ] **Step 4: BotService 使用 SDK 原生语音接口**

```java
ILinkClient.MediaInfo media = client.uploadMedia(credentials.get(), 3, toUserId, silkAudio);
client.sendVoiceMessage(credentials.get(), toUserId, contextToken, media, playtimeMs, 6);
```

发送失败继续降级文字；删除 MP3 文件发送代码和相关命名。

- [ ] **Step 5: 运行单元与真实编解码集成验证**

Run:

```powershell
.\\mvnw.cmd -o -Dtest=SiliconFlowTtsServiceTest,VoiceMessageServiceTest,VoiceMessageReplyTest test
$env:VOICE_SILK_ENCODER_PATH='<encoder.exe>'
$env:VOICE_SILK_DECODER_PATH='<decoder.exe>'
.\\mvnw.cmd -o -Dtest=AudioConverterIntegrationTest test
```

Expected: 单元测试全部 PASS；集成测试 1 test / 0 skipped；人工微信检查显示为可播放语音气泡而非 MP3 文件。

Commit: `feat: send native silk voice replies`

### Task 6B: 增加默认关闭的七牛 MP3 临时链接备选通道

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/java/com/demo/demo/Service/voice/TtsService.java`
- Modify: `src/main/java/com/demo/demo/Service/voice/SiliconFlowTtsService.java`
- Create: `src/main/java/com/demo/demo/Service/voice/VoiceLinkFallbackService.java`
- Create: `src/main/java/com/demo/demo/Service/voice/QiniuVoiceObjectStore.java`
- Create: `src/main/java/com/demo/demo/config/VoiceLinkFallbackProperties.java`
- Modify: `src/main/java/com/demo/demo/Service/BotService.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/resources/application-local.example.yml`
- Create: `src/test/java/com/demo/demo/Service/voice/VoiceLinkFallbackServiceTest.java`
- Create: `src/test/java/com/demo/demo/Service/voice/QiniuVoiceObjectStoreTest.java`
- Modify: `src/test/java/com/demo/demo/Service/VoiceMessageReplyTest.java`
- Modify: `src/test/java/com/demo/demo/Service/ServerLogPrivacyTest.java`
- Modify: `docs/MANUAL_TEST_CHECKLIST.md`

**Interfaces:**
- Consumes: Task 6 已生成的最终回复文本，以及原生 SILK 编码/上传/发送失败信号。
- Produces: `Optional<URI> createTemporaryLink(String text)`；成功时为私有 bucket 的短期签名 HTTPS URL，失败时为空。
- Produces: `URI uploadTemporaryMp3(byte[] mp3Bytes)`；只接受已校验的 MP3，返回签名 URI。
- Produces: package-private `void deliverVoiceResult(String fromUser, String contextToken, VoiceMessageService.Result result)`，集中执行“原生语音 → 七牛链接 → 纯文字”顺序，供行为测试直接验证。
- Constraint: 只有 `VOICE_LINK_FALLBACK_ENABLED=true` 且原生语音失败时执行；不得调用 `AIService` 或创建 `_voice_intent` 会话。

- [ ] **Step 1: 添加七牛官方 SDK 依赖**

在 `pom.xml` 增加：

```xml
<dependency>
    <groupId>com.qiniu</groupId>
    <artifactId>qiniu-java-sdk</artifactId>
    <version>7.19.0</version>
</dependency>
```

Run: `.\\mvnw.cmd -DskipTests dependency:tree -Dincludes=com.qiniu:qiniu-java-sdk`

Expected: 输出 `com.qiniu:qiniu-java-sdk:jar:7.19.0:compile`。该版本和坐标以 Maven Central 与七牛 Java SDK 文档为准。

- [ ] **Step 2: 写默认关闭和严格触发条件的失败测试**

```java
@Test
void nativeVoiceSuccessNeverCallsLinkFallback() {
    botService.deliverVoiceResult("wx-user", "ctx", nativeVoiceResult);

    verify(client).sendVoiceMessage(any(), eq("wx-user"), eq("ctx"), any(), anyInt(), eq(6));
    verifyNoInteractions(voiceLinkFallbackService);
}

@Test
void nativeVoiceFailureUsesLinkOnlyWhenEnabled() {
    doThrow(new RuntimeException("send failed"))
            .when(client).sendVoiceMessage(any(), anyString(), anyString(), any(), anyInt(), eq(6));
    when(voiceLinkFallbackService.createTemporaryLink("最终回复"))
            .thenReturn(Optional.of(URI.create("https://voice.example.test/signed")));

    botService.deliverVoiceResult("wx-user", "ctx", nativeVoiceResult);

    verify(voiceLinkFallbackService).createTemporaryLink("最终回复");
    verify(client).sendTextMessage(any(), eq("wx-user"), eq("ctx"),
            contains("https://voice.example.test/signed"));
}
```

- [ ] **Step 3: 运行测试确认当前实现缺少备选通道**

Run: `.\\mvnw.cmd -o -Dtest=VoiceMessageReplyTest test`

Expected: FAIL，`VoiceLinkFallbackService` 尚不存在，原生发送失败只降级纯文字。

- [ ] **Step 4: 增加类型安全配置并验证边界**

`VoiceLinkFallbackProperties` 使用 `@ConfigurationProperties(prefix = "ai.voice.link-fallback")`，默认值如下：

```java
private boolean enabled = false;
private int maxTextLength = 1000;
private int maxAudioBytes = 20 * 1024 * 1024;
private Duration urlTtl = Duration.ofMinutes(15);
private final Qiniu qiniu = new Qiniu();
```

`Qiniu` 包含 `accessKey`、`secretKey`、`bucket`、`domain`。构造 `QiniuVoiceObjectStore` 时拒绝空值、非 HTTPS domain，以及超过 60 分钟的 TTL。

在配置文件增加：

```yaml
ai:
  voice:
    link-fallback:
      enabled: ${VOICE_LINK_FALLBACK_ENABLED:false}
      max-text-length: ${VOICE_LINK_FALLBACK_MAX_TEXT_LENGTH:1000}
      max-audio-bytes: ${VOICE_LINK_FALLBACK_MAX_AUDIO_BYTES:20971520}
      url-ttl: ${VOICE_LINK_FALLBACK_URL_TTL:15m}
      qiniu:
        access-key: ${QINIU_ACCESS_KEY:}
        secret-key: ${QINIU_SECRET_KEY:}
        bucket: ${QINIU_BUCKET:}
        domain: ${QINIU_DOMAIN:}
```

- [ ] **Step 5: 扩展同一 TTS provider 的 MP3 输出，不增加第二个 LLM 或 TTS provider**

在 `TtsService` 增加：

```java
byte[] synthesizeMp3(String text) throws IOException;
```

`SiliconFlowTtsService` 复用私有请求方法：

```java
@Override
public byte[] synthesizeMp3(String text) throws IOException {
    return synthesize(text, "mp3", null);
}

private byte[] synthesize(String text, String format, Integer sampleRate) throws IOException {
    JsonObject body = baseRequest(text);
    body.addProperty("response_format", format);
    if (sampleRate != null) body.addProperty("sample_rate", sampleRate);
    return execute(body);
}
```

PCM 主路径继续传 `pcm` 和 `16000`；MP3 仅由备选服务在原生发送失败后调用。

- [ ] **Step 6: 使用官方 SDK 上传私有对象并生成短期签名 URL**

`QiniuVoiceObjectStore` 的核心实现：

```java
String key = "voice/" + UUID.randomUUID().toString().replace("-", "") + ".mp3";
Auth auth = Auth.create(accessKey, secretKey);
String uploadToken = auth.uploadToken(bucket, key);
uploader.put(mp3Bytes, key, uploadToken);

String publicUrl = domainWithoutTrailingSlash + "/" + key;
String signedUrl = auth.privateDownloadUrl(publicUrl, urlTtl.toSeconds());
URI result = URI.create(signedUrl);
if (!"https".equalsIgnoreCase(result.getScheme())) {
    throw new IOException("七牛下载链接必须使用 HTTPS");
}
return result;
```

不得使用硬编码 `https://up.qiniup.com` 的手写 multipart 上传，也不得读取或记录完整响应正文。

为避免测试访问网络，提供 package-private 构造函数注入最小上传适配器：

```java
@FunctionalInterface
interface QiniuUploader {
    void put(byte[] bytes, String key, String uploadToken) throws IOException;
}

QiniuVoiceObjectStore(VoiceLinkFallbackProperties properties, QiniuUploader uploader) {
    this.properties = properties;
    this.uploader = uploader;
}
```

生产构造函数用 `UploadManager` 实现该适配器；测试传入记录参数或抛出异常的 lambda。

```java
UploadManager uploadManager = new UploadManager(Configuration.create(Region.autoRegion()));
this.uploader = (bytes, key, token) -> {
    try (Response response = uploadManager.put(bytes, key, token)) {
        if (!response.isOK()) {
            throw new IOException("七牛上传失败，HTTP " + response.statusCode);
        }
    }
};
```

- [ ] **Step 7: 实现备选服务的开关、输入限制和单次尝试语义**

```java
public Optional<URI> createTemporaryLink(String text) {
    if (!properties.isEnabled() || text == null || text.isBlank()
            || text.length() > properties.getMaxTextLength()
            || Thread.currentThread().isInterrupted()) {
        return Optional.empty();
    }
    try {
        byte[] mp3 = ttsService.synthesizeMp3(text);
        if (mp3.length == 0 || mp3.length > properties.getMaxAudioBytes()) {
            return Optional.empty();
        }
        return Optional.of(objectStore.uploadTemporaryMp3(mp3));
    } catch (Exception e) {
        log.warn("[语音备选] 生成临时链接失败 type={}", e.getClass().getSimpleName());
        return Optional.empty();
    }
}
```

该类不得依赖 `AIService`。不要在内部重试 TTS 或上传，避免重复计费和孤立对象。

- [ ] **Step 8: 只在原生语音发送失败后调用备选服务**

`BotService` 的交付顺序固定为：

```java
if (sendSilkReply(toUserId, contextToken, result.silkAudio(), result.playtimeMs())) {
    return;
}

Optional<URI> link = voiceLinkFallbackService.createTemporaryLink(result.text());
if (link.isPresent()) {
    sendReply(toUserId, contextToken, result.text()
            + "\n\n原生语音发送失败，可在 15 分钟内通过以下链接播放：\n"
            + link.get());
} else {
    sendReply(toUserId, contextToken, result.text());
}
```

若线程已中断或 `shuttingDown=true`，直接返回，不上传、不发送降级消息。链接文案中的有效期应从配置格式化，不能硬编码。

- [ ] **Step 9: 验证日志、异常和隐私边界**

测试必须断言日志不包含：AK、SK、upload token、供应商响应正文、完整 key、完整 URL、最终回复正文。允许记录 HTTP 状态码、字节数、耗时、异常类型和 key 后 6 位。

Run:

```powershell
.\\mvnw.cmd -o -Dtest=VoiceLinkFallbackServiceTest,QiniuVoiceObjectStoreTest,VoiceMessageReplyTest,ServerLogPrivacyTest test
```

Expected: PASS；测试使用 mock SDK adapter，不访问真实七牛、TTS 或微信。

- [ ] **Step 10: 人工验收并提交**

人工验收矩阵：

1. fallback=false + 原生失败 → 仅文字；
2. fallback=true + 原生成功 → 仅微信语音气泡，七牛无新对象；
3. fallback=true + 原生失败 + 七牛成功 → 文字和 15 分钟签名 HTTPS 链接；
4. fallback=true + 七牛失败 → 仅文字；
5. 应用关闭中 → 不产生新七牛对象；
6. 私有 bucket 匿名 URL 无法访问，签名 URL 到期后无法访问；
7. `voice/` 生命周期规则在 24 小时内删除对象。

Commit: `feat: add secure voice link fallback`

### Task 7: 收紧记忆、锁与多模态上下文的生命周期

**Files:**
- Modify: `src/main/java/com/demo/demo/Service/AIService.java`
- Modify: `src/main/java/com/demo/demo/Service/memory/ConversationMemoryStore.java`
- Modify: `src/main/java/com/demo/demo/Service/context/SensorialContext.java`
- Test: `src/test/java/com/demo/demo/Service/AIServiceMemoryTest.java`
- Test: `src/test/java/com/demo/demo/Service/memory/ConversationMemoryStoreTest.java`
- Test: `src/test/java/com/demo/demo/Service/context/ContextManagerTest.java`

**Interfaces:**
- Produces: 每用户串行不变；读写线程安全；锁和持久化用户数有上限或可淘汰。

- [ ] **Step 1: 写并发读写与容量失败测试**

```java
@Test
void concurrentReadAndAppendReturnsCompleteTurns() throws Exception {
    runConcurrentReadsAndAppends(store, "u1", 100);
    assertEquals(0, store.getHistory("u1").size() % 2);
}
```

另测超过配置用户上限后淘汰最久未活跃用户，并验证磁盘快照同步更新。

- [ ] **Step 2: 所有 list 访问都在同一锁内并返回副本**

```java
public List<ConversationMessage> getHistory(String userId) {
    synchronized (writeLock) {
        List<ConversationMessage> messages = memory.get(userId);
        return messages == null ? List.of() : List.copyOf(messages);
    }
}
```

- [ ] **Step 3: 清理永久增长的 `userLocks`**

完成同一用户操作后使用带值条件删除：

```java
finally {
    userLocks.remove(userId, lock);
}
```

如果需要保证等待线程安全，改为带引用计数的最小 lock holder，并用并发测试证明不会出现同用户并行调用。

- [ ] **Step 4: 验证并提交**

Run: `.\\mvnw.cmd -o -Dtest=AIServiceMemoryTest,ConversationMemoryStoreTest,ContextManagerTest test`

Expected: PASS，无 `ConcurrentModificationException`，map 大小受控。

Commit: `fix: bound conversation state lifecycle`

### Task 8: 固定模型、依赖与测试可重复性

**Files:**
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/resources/application-local.example.yml`
- Modify: `src/test/java/com/demo/demo/WeatherUtilTest.java`
- Modify: `src/test/java/com/demo/demo/WeatherStabilityTest.java`
- Modify: `README.md`

**Interfaces:**
- Produces: 可配置且受支持的 DeepSeek 模型；默认测试不访问公网；人工/集成测试独立运行。

- [ ] **Step 1: 将模型改为环境变量并切换到受支持名称**

```yaml
model: ${AI_MODEL:deepseek-v4-flash}
```

在 README 说明切换日期和回滚变量；上线前用真实账号确认模型可用。

- [ ] **Step 2: 抽出天气 HTTP 依赖并在单元测试中 mock**

测试固定响应：

```java
when(weatherClient.fetch("Hangzhou")).thenReturn(FIXTURE_JSON);
assertThat(weatherService.getWeather("Hangzhou")).contains("Hangzhou");
```

真实网络测试改名为 `*IT` 并用 profile/环境变量显式启用，默认 `test` 不运行。

- [ ] **Step 3: 完整验证**

Run:

```powershell
.\\mvnw.cmd -o clean test
.\\mvnw.cmd -o clean package
git diff --check
```

Expected: 0 failures、0 errors；除显式 native codec IT 外 0 skipped；断网环境下默认测试仍通过；`git diff --check` 无输出。

- [ ] **Step 4: 人工微信验收并提交文档**

验收：文本 FIFO、图片只处理一次、天气/时间工具调用、重启后记忆恢复、ASR 失败提示、TTS/编码/发送任一失败降级文字、正常语音为语音气泡、队列饱和不阻塞新消息拉取。

Commit: `chore: make release verification deterministic`

---

## 推荐执行顺序与发布门槛

1. P0：Task 1–2，完成前不得部署公网环境。
2. P1：Task 3–6，完成前不得将 `feat/voice-mvp` 合并为主线。
3. P2：Task 6B、Task 7–8；Task 6B 是默认关闭的可选能力，不阻塞原生 SILK 主路径发布。

发布门槛：两个旧工作树均保留可回滚 SHA；完整测试通过；原生 codec IT 不跳过；SSRF/隐私/关闭/队列/503 回归测试存在；真实微信中回复为语音气泡；没有未解释的 merge 冲突或未提交生产代码。
