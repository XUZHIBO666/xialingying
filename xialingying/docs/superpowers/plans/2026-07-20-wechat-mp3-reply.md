# WeChat MP3 Reply Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reply to an inbound WeChat voice message with one downloadable MP3 attachment, falling back to the generated LLM text only when TTS, upload, or file sending fails.

**Architecture:** Keep SILK decoding, ASR, and `AIService.chat()` unchanged. Request MP3 directly from SiliconFlow, carry the bytes through `VoiceMessageService.Result`, then use iLink SDK 1.0.1 file upload (`mediaType=3`) and `sendFileMessage(...)`; remove the unsuccessful outbound native-voice request path.

**Tech Stack:** Java 21, Spring Boot 4, OkHttp, Gson, iLink SDK 1.0.1, Maven, JUnit 5, Mockito.

## Global Constraints

- Do not upgrade Java, Spring Boot, or iLink SDK.
- Do not add FFmpeg, a local MP3 encoder, or a new dependency.
- Do not change inbound SILK → PCM → ASR processing.
- Send only MP3 on success; send LLM text only on failure.
- Do not overwrite unrelated README or AGENTS content.

---

### Task 1: Request MP3 from SiliconFlow

**Files:**
- Modify: `src/test/java/com/demo/demo/Service/voice/SiliconFlowTtsServiceTest.java`
- Modify: `src/main/java/com/demo/demo/Service/voice/SiliconFlowTtsService.java`

**Interfaces:**
- Consumes: `TtsService.synthesize(String text)`.
- Produces: non-empty MP3 bytes returned by `SiliconFlowTtsService.synthesize(String)`.

- [ ] **Step 1: Change the provider test to require MP3**

Use an ID3-prefixed response and assert `response_format` is `mp3`:

```java
byte[] expectedMp3 = new byte[]{'I', 'D', '3', 4, 0, 0};
byte[] actual = service.synthesize("你好");
assertArrayEquals(expectedMp3, actual);
assertEquals("mp3", requestBody.get().get("response_format").getAsString());
assertFalse(requestBody.get().has("sample_rate"));
```

- [ ] **Step 2: Run the focused test and verify RED**

Run: `mvn.cmd "-Dtest=SiliconFlowTtsServiceTest" test`

Expected: FAIL because the request still contains `response_format=pcm` and `sample_rate=16000`.

- [ ] **Step 3: Implement the minimal MP3 request and validation**

In `synthesize`, set:

```java
body.addProperty("response_format", "mp3");
body.addProperty("stream", false);
```

Remove `sample_rate`. Rename the size constant/result variable to MP3 terminology and validate only non-empty and maximum size; do not require an even byte length.

- [ ] **Step 4: Re-run the focused test and verify GREEN**

Run: `mvn.cmd "-Dtest=SiliconFlowTtsServiceTest" test`

Expected: 1 test, 0 failures.

### Task 2: Carry MP3 Through Voice Orchestration

**Files:**
- Modify: `src/test/java/com/demo/demo/Service/voice/VoiceMessageServiceTest.java`
- Modify: `src/main/java/com/demo/demo/Service/voice/VoiceMessageService.java`

**Interfaces:**
- Consumes: `byte[] TtsService.synthesize(String text)` containing MP3.
- Produces: `Result(String text, byte[] mp3Audio)` with `hasMp3()`.

- [ ] **Step 1: Change orchestration tests to require MP3 and no SILK encoding**

The success test must use:

```java
byte[] outputMp3 = new byte[]{'I', 'D', '3'};
when(tts.synthesize("LLM回答")).thenReturn(outputMp3);
VoiceMessageService.Result result = service.process("wx-user", inputSilk);
assertArrayEquals(outputMp3, result.mp3Audio());
assertTrue(result.hasMp3());
verify(codec, never()).pcmToSilk(any());
```

Update ASR, LLM, and TTS failure assertions to use `hasMp3()`. Remove the obsolete SILK-encoding-failure test because outbound SILK encoding no longer occurs.

- [ ] **Step 2: Run the focused test and verify RED**

Run: `mvn.cmd "-Dtest=VoiceMessageServiceTest" test`

Expected: test compilation fails because `mp3Audio()` and `hasMp3()` do not exist yet, proving the old result contract is still active.

- [ ] **Step 3: Implement the minimal result contract**

Replace outbound conversion with:

```java
byte[] replyMp3 = ttsService.synthesize(reply);
return new Result(reply, replyMp3);
```

Use:

```java
public record Result(String text, byte[] mp3Audio) {
    public static Result textOnly(String text) {
        return new Result(text, null);
    }

    public boolean hasMp3() {
        return mp3Audio != null && mp3Audio.length > 0;
    }
}
```

Change the warning to `TTS 失败，降级文字回复`.

- [ ] **Step 4: Re-run the focused test and verify GREEN**

Run: `mvn.cmd "-Dtest=VoiceMessageServiceTest" test`

Expected: all remaining orchestration tests pass.

### Task 3: Upload and Send an MP3 File Attachment

**Files:**
- Modify: `src/test/java/com/demo/demo/Service/VoiceMessageReplyTest.java`
- Modify: `src/main/java/com/demo/demo/Service/BotService.java`

**Interfaces:**
- Consumes: `VoiceMessageService.Result.mp3Audio()` and `hasMp3()`.
- Produces: iLink calls `uploadMedia(credentials, 3, toUserId, mp3)` and `sendFileMessage(credentials, toUserId, contextToken, media, filename, mp3.length)`.

- [ ] **Step 1: Change reply tests to require one MP3 attachment**

Capture the filename and verify:

```java
verify(client, timeout(2000)).uploadMedia(any(LoginCredentials.class), eq(3),
        eq("wx-user"), eq(replyMp3));
verify(client, timeout(2000)).sendFileMessage(any(LoginCredentials.class), eq("wx-user"),
        eq("ctx-token"), eq(media), matches("voice-reply-\\d+\\.mp3"), eq((long) replyMp3.length));
verify(client, never()).sendTextMessage(any(), anyString(), anyString(), anyString());
```

Keep separate tests for upload failure and `sendFileMessage` failure, each requiring the exact LLM text fallback.

- [ ] **Step 2: Run the focused test and verify RED**

Run: `mvn.cmd "-Dtest=VoiceMessageReplyTest" test`

Expected: FAIL because production still uploads voice media type `4` and sends a native voice request.

- [ ] **Step 3: Implement MP3 file sending**

Replace `sendVoiceReply` with:

```java
private boolean sendMp3Reply(String toUserId, String contextToken, byte[] mp3Audio) {
    if (!loggedIn || mp3Audio == null || mp3Audio.length == 0) return false;
    try {
        ILinkClient.MediaInfo media = client.uploadMedia(credentials.get(), 3, toUserId, mp3Audio);
        String fileName = "voice-reply-" + System.currentTimeMillis() + ".mp3";
        client.sendFileMessage(credentials.get(), toUserId, contextToken, media,
                fileName, mp3Audio.length);
        log.info("[iLink] MP3 文件发送成功 to={} mp3Bytes={} fileName={}",
                toUserId, mp3Audio.length, fileName);
        return true;
    } catch (Exception e) {
        log.error("[iLink] MP3 文件发送失败 to={} error={}", toUserId, e.getMessage(), e);
        return false;
    }
}
```

In `processVoiceMessage`, call it only when `result.hasMp3()` and send text only when it returns false. Remove voice-message DTO imports, metadata constants, and `buildVoiceMessageRequest`.

- [ ] **Step 4: Re-run the focused test and verify GREEN**

Run: `mvn.cmd "-Dtest=VoiceMessageReplyTest" test`

Expected: success, upload-failure fallback, and send-failure fallback all pass.

### Task 4: Documentation and Full Verification

**Files:**
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-07-20-wechat-voice-mvp-design.md`
- Modify: `docs/superpowers/plans/2026-07-20-wechat-voice-mvp.md`

**Interfaces:**
- Consumes: completed MP3 attachment behavior.
- Produces: accurate setup and limitation documentation.

- [ ] **Step 1: Correct outbound behavior documentation**

State that inbound audio remains SILK/PCM, while replies are MP3 file attachments because native iLink voice delivery is not reliable. Remove claims that the current path delivers a native SILK voice bubble. Keep existing credential and decoder setup unchanged.

- [ ] **Step 2: Run all verification commands**

Run:

```powershell
mvn.cmd test
mvn.cmd -DskipTests package
git diff --check
git status --short
```

Expected: Maven tests and package succeed; diff check reports no whitespace errors; status lists only intentional files.

- [ ] **Step 3: Manually verify in WeChat**

Restart `DemoApplication` from the worktree, send one WeChat voice message, and verify exactly one `.mp3` attachment arrives and plays after download. Confirm the log contains `MP3 文件发送成功`; on forced upload/send failure, confirm only the LLM text arrives.
