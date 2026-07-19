# Bot Stability and Log Privacy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make inbound image processing non-blocking, stop all Bot work within a bounded shutdown period, and remove sensitive content from server logs.

**Architecture:** Reuse the existing bounded reply executor and per-user lock for the complete image pipeline. Add an idempotent shutdown state and a shared five-second shutdown deadline. Keep the authenticated admin page unchanged while replacing sensitive server log arguments with event metadata or masked identifiers.

**Tech Stack:** Java 21, Spring Boot 4, JUnit 5, Mockito, Maven

## Global Constraints

- Implement in this order: image download async, shutdown lifecycle, server-log privacy.
- Do not add another executor, persistence layer, dependency, or configuration abstraction.
- Preserve per-user ordering and cross-user parallelism.
- Wait at most five seconds during shutdown, then interrupt remaining reply tasks.
- Keep admin-page message visibility unchanged.
- Do not stage or commit the existing `README.md` modification.

---

### Task 1: Move the complete inbound image pipeline to the reply executor

**Files:**
- Modify: `src/main/java/com/demo/demo/Service/BotService.java`
- Modify: `src/test/java/com/demo/demo/Service/ImageAutoReplyTest.java`
- Create: `src/test/java/com/demo/demo/Service/ImageMessageAsyncTest.java`

**Interfaces:**
- Consumes: `submitReplyTask(String fromUser, String contextToken, Runnable task)`
- Produces: `private void downloadAndHandleImage(String fromUser, String contextToken, ImageContent image)` and `private void handleImageMessage(String fromUser, String contextToken, byte[] imageBytes)`

- [ ] **Step 1: Write a failing non-blocking download test**

Create `ImageMessageAsyncTest` with a blocked SDK download. Invoke the private image entry through `ReflectionTestUtils`, and prove the entry returns before the download is released:

```java
@Test
void imageEntryReturnsWhileDownloadRunsInReplyExecutor() throws Exception {
    ILinkClient client = mock(ILinkClient.class);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    BotService service = loggedInBotService(client, executor);
    CountDownLatch downloadStarted = new CountDownLatch(1);
    CountDownLatch releaseDownload = new CountDownLatch(1);
    byte[] bytes = "image".getBytes(StandardCharsets.UTF_8);
    ImageContent image = imageContent();

    when(client.downloadMedia("encrypt-query", "aes-key")).thenAnswer(invocation -> {
        downloadStarted.countDown();
        assertTrue(releaseDownload.await(2, TimeUnit.SECONDS));
        return bytes;
    });
    service.setImageReply((user, context, input) -> "recognized");

    long start = System.nanoTime();
    ReflectionTestUtils.invokeMethod(service, "processImageItem", "user-a", "ctx-a", image);
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

    assertTrue(elapsedMs < 200);
    assertTrue(downloadStarted.await(1, TimeUnit.SECONDS));
    releaseDownload.countDown();
    verify(client, timeout(1000)).sendTextMessage(
            any(LoginCredentials.class), eq("user-a"), eq("ctx-a"), eq("recognized"));
    executor.shutdownNow();
}
```

Include local helpers that create `ImageContent("encrypt-query", "aes-key", ...)`, set `loggedIn`, and populate `credentials`, matching the existing `ImageAutoReplyTest` pattern.

- [ ] **Step 2: Run the focused test and verify the blocking behavior fails**

Run:

```powershell
mvn -Dtest=ImageMessageAsyncTest test
```

Expected: FAIL because `processImageItem()` blocks inside `client.downloadMedia(...)` and does not return within 200 ms.

- [ ] **Step 3: Write a failing same-user ordering test**

Add a second test that records handler order:

```java
@Test
void textFromSameUserWaitsForImagePipeline() throws Exception {
    CopyOnWriteArrayList<String> handled = new CopyOnWriteArrayList<>();
    CountDownLatch downloadStarted = new CountDownLatch(1);
    CountDownLatch releaseDownload = new CountDownLatch(1);
    CountDownLatch textHandled = new CountDownLatch(1);

    when(client.downloadMedia("encrypt-query", "aes-key")).thenAnswer(invocation -> {
        downloadStarted.countDown();
        assertTrue(releaseDownload.await(2, TimeUnit.SECONDS));
        return "image".getBytes(StandardCharsets.UTF_8);
    });
    service.setImageReply((user, context, bytes) -> {
        handled.add("image");
        return null;
    });
    service.setAutoReply((user, context, text) -> {
        handled.add("text");
        textHandled.countDown();
        return null;
    });

    ReflectionTestUtils.invokeMethod(service, "processImageItem", "user-a", "ctx-image", imageContent());
    assertTrue(downloadStarted.await(1, TimeUnit.SECONDS));
    service.processTextMessage("user-a", "ctx-text", "next");
    assertFalse(textHandled.await(150, TimeUnit.MILLISECONDS));

    releaseDownload.countDown();
    assertTrue(textHandled.await(1, TimeUnit.SECONDS));
    assertEquals(List.of("image", "text"), handled);
```

- [ ] **Step 4: Implement the minimal single-task image pipeline**

Change `processImageItem()` to enqueue the complete pipeline:

```java
private void processImageItem(String fromUser, String contextToken, ImageContent image) {
    if (image == null) {
        displayLog(fromUser + ": [图片消息为空]");
        return;
    }
    submitReplyTask(fromUser, contextToken,
            () -> downloadAndHandleImage(fromUser, contextToken, image));
}
```

Move the current download try/catch into `downloadAndHandleImage()`. On success call `handleImageMessage()` directly. Keep the current download-failure reply text.

Refactor the existing direct-byte entry to avoid a nested queued task:

```java
void processImageMessage(String fromUser, String contextToken, byte[] imageBytes) {
    submitReplyTask(fromUser, contextToken,
            () -> handleImageMessage(fromUser, contextToken, imageBytes));
}

private void handleImageMessage(String fromUser, String contextToken, byte[] imageBytes) {
    messages.add(new Msg(fromUser, rememberReplyTarget(fromUser, contextToken), "[图片]"));
    displayLog(fromUser + ": [图片]");
    ImageReplyHandler handler = imageReplyHandler;
    if (handler == null) return;
    try {
        String reply = handler.onImage(fromUser, contextToken, imageBytes);
        if (reply != null && !reply.isEmpty()) sendReply(fromUser, contextToken, reply);
    } catch (Exception e) {
        log.error("[iLink] 图片自动回复处理异常: {}", e.getMessage(), e);
        sendReply(fromUser, contextToken, "收到图片了，但识别失败了：" + e.getMessage());
    }
}
```

- [ ] **Step 5: Update the existing asynchronous verification**

In `receivedImageDownloadsByEncryptQueryParamBeforeRecognition()`, replace immediate download verification with:

```java
verify(client, timeout(1000)).downloadMedia("encrypt-query-param", "aes-key");
```

Keep the assertion that the fallback URL is never downloaded.

- [ ] **Step 6: Run image tests**

Run:

```powershell
mvn -Dtest=ImageMessageAsyncTest,ImageAutoReplyTest test
```

Expected: all tests in both classes pass.

- [ ] **Step 7: Commit only Task 1 files**

```powershell
git add src/main/java/com/demo/demo/Service/BotService.java src/test/java/com/demo/demo/Service/ImageAutoReplyTest.java src/test/java/com/demo/demo/Service/ImageMessageAsyncTest.java
git commit -m "fix: move image downloads off ilink listener"
```

---

### Task 2: Add bounded and idempotent Bot shutdown

**Files:**
- Modify: `src/main/java/com/demo/demo/Service/BotService.java`
- Create: `src/test/java/com/demo/demo/Service/BotServiceShutdownTest.java`

**Interfaces:**
- Produces: `public void shutdown()`, `private boolean isShuttingDown()`, and a five-second shared deadline
- Changes: `startLogin(...)` and `submitReplyTask(...)` reject new work after shutdown begins

- [ ] **Step 1: Write failing graceful and forced shutdown tests**

Use a mocked `ExecutorService` so tests do not wait five real seconds:

```java
@Test
void shutdownWaitsForReplyExecutorWithoutForcingCompletedTasks() throws Exception {
    ExecutorService executor = mock(ExecutorService.class);
    when(executor.awaitTermination(anyLong(), eq(TimeUnit.NANOSECONDS))).thenReturn(true);
    BotService service = new BotService(mock(ILinkClient.class), executor);

    service.shutdown();
    service.shutdown();

    verify(executor, times(1)).shutdown();
    verify(executor, times(1)).awaitTermination(anyLong(), eq(TimeUnit.NANOSECONDS));
    verify(executor, never()).shutdownNow();
}

@Test
void shutdownInterruptsReplyTasksAfterDeadline() throws Exception {
    ExecutorService executor = mock(ExecutorService.class);
    when(executor.awaitTermination(anyLong(), eq(TimeUnit.NANOSECONDS))).thenReturn(false);
    BotService service = new BotService(mock(ILinkClient.class), executor);

    service.shutdown();

    verify(executor).shutdown();
    verify(executor).shutdownNow();
}
```

- [ ] **Step 2: Run the shutdown test and verify it fails**

Run:

```powershell
mvn -Dtest=BotServiceShutdownTest test
```

Expected: FAIL because `shutdown()` and graceful `ExecutorService.shutdown()` do not exist.

- [ ] **Step 3: Add state-clearing and post-shutdown rejection tests**

Populate `credentials`, QR fields, `cursor`, and `loggedIn` with `ReflectionTestUtils`; call `shutdown()` and assert:

```java
assertFalse(service.isLoggedIn());
assertNull(service.getQrCodeBase64());
assertNull(service.getQrCodeUrl());
assertNull(credentials.get());
assertEquals("", ReflectionTestUtils.getField(service, "cursor"));
```

Then call `service.startLogin()` and verify:

```java
verifyNoInteractions(client);
```

Use a real single-thread executor, call `shutdown()`, then call `processTextMessage()` and assert its handler latch is not triggered.

- [ ] **Step 4: Implement the five-second shared-deadline shutdown**

Add:

```java
private static final long SHUTDOWN_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5);
private volatile boolean shuttingDown;
```

Replace `shutdownReplyExecutor()` with:

```java
@PreDestroy
public void shutdown() {
    Thread login;
    Thread listener;
    synchronized (this) {
        if (shuttingDown) return;
        shuttingDown = true;
        loggedIn = false;
        loginSession.incrementAndGet();
        login = loginThread;
        listener = listenThread;
        credentials.set(null);
        qrCodeBase64.set(null);
        qrCodeUrl.set(null);
        cursor = "";
        statusText.set("已停止");
    }

    long deadline = System.nanoTime() + SHUTDOWN_TIMEOUT_NANOS;
    if (login != null) login.interrupt();
    if (listener != null) listener.interrupt();
    replyExecutor.shutdown();
    joinUntil(login, deadline);
    joinUntil(listener, deadline);
    if (!awaitReplyTermination(deadline)) replyExecutor.shutdownNow();
}
```

Implement `joinUntil` with `Thread.join(millis, nanos)` using only positive remaining time. Implement `awaitReplyTermination` with `replyExecutor.awaitTermination(remaining, TimeUnit.NANOSECONDS)`. On `InterruptedException`, restore the interrupt flag and return `false`.

- [ ] **Step 5: Reject new work after shutdown**

At the top of `startLogin(boolean force)`:

```java
if (shuttingDown) return;
```

At the top of `submitReplyTask(...)`:

```java
if (shuttingDown) return;
```

Do not send the queue-full message when rejection is caused by shutdown.

- [ ] **Step 6: Run lifecycle and concurrency tests**

Run:

```powershell
mvn -Dtest=BotServiceShutdownTest,ILinkSessionLifecycleTest,UserMessageSerializationTest test
```

Expected: all selected tests pass.

- [ ] **Step 7: Commit only Task 2 files**

```powershell
git add src/main/java/com/demo/demo/Service/BotService.java src/test/java/com/demo/demo/Service/BotServiceShutdownTest.java
git commit -m "fix: stop bot workers gracefully on shutdown"
```

---

### Task 3: Remove sensitive values from server logs

**Files:**
- Modify: `src/main/java/com/demo/demo/Service/BotService.java`
- Modify: `src/main/java/com/demo/demo/Service/AIService.java`
- Modify: `src/main/java/com/demo/demo/Service/ImageRecognitionService.java`
- Modify: `src/main/java/com/demo/demo/Utils/WeatherUtil.java`
- Modify: `src/main/java/com/demo/demo/controller/BotController.java`
- Create: `src/test/java/com/demo/demo/Service/ServerLogPrivacyTest.java`

**Interfaces:**
- Consumes: existing `BotService.maskToken(String)`
- Produces: server logs containing event type, masked user identity, status, sizes, timing, and exception class only

- [ ] **Step 1: Write a failing source-level privacy regression test**

Create a test that reads the five source files with `Files.readString(Path.of(...))` and rejects exact risky server-log fragments:

```java
@Test
void serverLogsDoNotIncludeSensitivePayloadArguments() throws Exception {
    String sources = String.join("\n",
            source("Service/BotService.java"),
            source("Service/AIService.java"),
            source("Service/ImageRecognitionService.java"),
            source("Utils/WeatherUtil.java"),
            source("controller/BotController.java"));

    assertFalse(sources.contains("二维码已获取: {}"));
    assertFalse(sources.contains("更新游标: {}"));
    assertFalse(sources.contains("contextToken={}"));
    assertFalse(sources.contains(" text={}"));
    assertFalse(sources.contains("message={}"));
    assertFalse(sources.contains("reply={}"));
    assertFalse(sources.contains("body={}"));
    assertFalse(sources.contains("API 返回数据: {}"));
    assertFalse(sources.contains("请求 URL: {}"));
}

private String source(String relative) throws IOException {
    return Files.readString(Path.of("src/main/java/com/demo/demo").resolve(relative));
}
```

- [ ] **Step 2: Run the privacy test and verify it fails**

Run:

```powershell
mvn -Dtest=ServerLogPrivacyTest test
```

Expected: FAIL on the current QR, cursor, message, reply, response-body, and weather JSON log formats.

- [ ] **Step 3: Redact Bot and controller logs**

Apply these exact rules:

- `二维码已获取: {}` → `二维码已获取`
- `更新游标: {}` → `游标已更新`
- inbound text/image/voice logs contain only event type and `maskToken(fromUser)`
- image download log contains only `aesKeyPresent` and `urlPresent`
- outbound text logs contain masked target only, with no context token or text
- upload/send/error logs use `maskToken(toUserId)`
- `BotController` automatic reply logs contain masked sender and message type only
- weather routing logs contain only `进入天气查询` or the business error code
- manual-send logs contain `replyId` only, not message text

Keep `displayLog(...)`, `messages`, and admin-page behavior unchanged.

- [ ] **Step 4: Redact AI, vision, and weather logs**

In `AIService`:

```java
log.info("[AI] 收到对话请求 user={}", maskIdentifier(userId));
log.info("[AI] 回复成功 user={}", maskIdentifier(userId));
log.error("[AI] API 调用失败 HTTP {}", response.code());
```

Add a private identifier masker equivalent to the Bot masker without creating a shared utility.

In `ImageRecognitionService`, log only the HTTP status:

```java
log.error("[图片识别] API 调用失败 HTTP {}", response.code());
```

In `WeatherUtil`, keep only phase logs:

```java
log.info("[天气查询] 开始查询");
log.warn("[天气查询] 城市名包含非法字符");
log.error("[天气查询] 请求超时", e);
log.error("[天气查询] 网络请求失败", e);
log.error("[天气查询] JSON 解析失败", e);
log.warn("[天气查询] 城市未找到");
log.info("[天气查询] 查询成功");
```

Delete logs for the complete URL, JSON, result text, and untranslated weather string.

- [ ] **Step 5: Run privacy and affected service tests**

Run:

```powershell
mvn -Dtest=ServerLogPrivacyTest,ImageRecognitionServiceTest,WeatherUtilTest,WeatherStabilityTest test
```

Expected: all selected tests pass and test output no longer prints complete weather JSON.

- [ ] **Step 6: Commit only Task 3 files**

```powershell
git add src/main/java/com/demo/demo/Service/BotService.java src/main/java/com/demo/demo/Service/AIService.java src/main/java/com/demo/demo/Service/ImageRecognitionService.java src/main/java/com/demo/demo/Utils/WeatherUtil.java src/main/java/com/demo/demo/controller/BotController.java src/test/java/com/demo/demo/Service/ServerLogPrivacyTest.java
git commit -m "fix: remove sensitive values from server logs"
```

---

### Task 4: Verify the complete branch

**Files:**
- Verify only; do not modify unrelated files

**Interfaces:**
- Consumes: Tasks 1–3
- Produces: test, source-scan, and Git-state evidence

- [ ] **Step 1: Run the complete test suite**

```powershell
mvn test
```

Expected: `BUILD SUCCESS`, zero failures, and zero errors.

- [ ] **Step 2: Run source and diff checks**

```powershell
rg -n "二维码已获取:|更新游标:|contextToken=| text=|message=|reply=|body=|API 返回数据:|请求 URL:" src/main/java/com/demo/demo
git diff --check
git status --short
```

Expected: the source scan has no server-log matches for the forbidden formats; `git diff --check` exits zero; `README.md` remains the only pre-existing uncommitted file.

- [ ] **Step 3: Review commits and preserve manual-test limitations**

```powershell
git log --oneline -4
```

Expected: design, image async, shutdown, and log privacy commits are present. Do not claim real WeChat image or shutdown end-to-end verification because login is intentionally deferred.
