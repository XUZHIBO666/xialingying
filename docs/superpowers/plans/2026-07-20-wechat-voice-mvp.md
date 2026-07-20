# WeChat Voice MVP Implementation Plan

**Goal:** Reply to inbound WeChat SILK voice messages with an LLM-generated native SILK voice message and the four specified text fallbacks.

**Architecture:** Preserve `BotService` as the iLink boundary. Add narrow TTS and codec interfaces, keep SiliconFlow HTTP details in provider classes, and use a small orchestration service for ASR → LLM → TTS → encode so `BotService` only downloads and sends.

**Tech Stack:** Java 21, Spring Boot 4, OkHttp, Gson, iLink SDK 1.0.1, external SILK decoder/encoder, Maven, JUnit 5, Mockito.

---

## Task 1: SiliconFlow TTS

**Files:**
- Create: `src/main/java/com/demo/demo/Service/voice/TtsService.java`
- Create: `src/main/java/com/demo/demo/Service/voice/SiliconFlowTtsService.java`
- Modify: `src/main/java/com/demo/demo/config/VoiceProperties.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/resources/application-local.example.yml`
- Test: `src/test/java/com/demo/demo/Service/voice/SiliconFlowTtsServiceTest.java`

1. Write tests proving the request uses `/v1/audio/speech`, configured model/voice, `response_format=pcm`, `sample_rate=16000`, and returns non-empty binary PCM.
2. Run `mvn.cmd -Dtest=SiliconFlowTtsServiceTest test` and confirm RED.
3. Implement the minimal interface, configuration, and OkHttp provider.
4. Re-run the focused test and confirm GREEN.

## Task 2: PCM/SILK Codec

**Files:**
- Create: `src/main/java/com/demo/demo/Service/voice/AudioCodecService.java`
- Modify: `src/main/java/com/demo/demo/Service/voice/AudioConverter.java`
- Modify: `src/main/java/com/demo/demo/config/VoiceProperties.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/resources/application-local.example.yml`
- Modify: `src/test/java/com/demo/demo/Service/voice/AudioConverterTest.java`

1. Add tests for SILK → raw PCM and raw PCM → SILK command invocation, header validation, and output validation.
2. Run `mvn.cmd -Dtest=AudioConverterTest test` and confirm RED.
3. Implement only the two codec operations using configured decoder/encoder executables and 16 kHz mono S16LE PCM.
4. Re-run the focused test and confirm GREEN.

## Task 3: ASR Internal PCM Contract

**Files:**
- Modify: `src/main/java/com/demo/demo/Service/voice/AsrService.java`
- Modify: `src/main/java/com/demo/demo/Service/voice/SiliconFlowAsrService.java`
- Modify: `src/test/java/com/demo/demo/Service/voice/SiliconFlowAsrServiceTest.java`

1. Add a test proving raw 16 kHz PCM is wrapped as a valid mono PCM WAV upload.
2. Run the focused test and confirm RED.
3. Make the minimal provider change and retain the existing SiliconFlow endpoint/model.
4. Re-run the test and confirm GREEN.

## Task 4: End-to-End Voice Orchestration

**Files:**
- Create: `src/main/java/com/demo/demo/Service/voice/VoiceMessageService.java`
- Modify: `src/main/java/com/demo/demo/Service/voice/VoiceMessageHandler.java`
- Remove: `src/main/java/com/demo/demo/Service/voice/VoiceProcessingService.java`
- Create: `src/test/java/com/demo/demo/Service/voice/VoiceMessageServiceTest.java`
- Modify/remove obsolete voice processing tests as needed.

1. Test successful ASR → `AIService.chat()` → TTS → SILK and all ASR/LLM/TTS/encode fallback results.
2. Run the focused test and confirm RED.
3. Implement a small result object containing reply text, optional SILK bytes, and playtime.
4. Re-run the focused test and confirm GREEN.

## Task 5: iLink Upload and Voice Send

**Files:**
- Modify: `src/main/java/com/demo/demo/Service/BotService.java`
- Modify: `src/main/java/com/demo/demo/controller/BotController.java`
- Modify: `src/test/java/com/demo/demo/Service/VoiceMessageReplyTest.java`
- Modify: `src/test/java/com/demo/demo/Service/VoiceFallbackReplyTest.java`

1. Add tests proving `uploadMedia(credentials, 4, user, silk)` and `sendVoiceMessage(credentials, user, context, media, playtime, 1)` are called.
2. Add tests proving upload/send failure falls back to generated text and ASR/LLM failures use exact messages.
3. Run focused tests and confirm RED.
4. Wire the existing inbound download into the orchestration service and add the minimal voice send helper.
5. Re-run focused tests and confirm GREEN.

## Task 6: Documentation and Verification

**Files:**
- Modify: `README.md`

1. Document environment variables and external SILK tools without real keys.
2. Run focused voice tests.
3. Run `mvn.cmd test`.
4. Run `mvn.cmd clean package`.
5. Inspect `git diff --check`, `git diff --stat`, and `git status --short`.

## Task 7: Correct Outbound SILK Metadata

**Files:**
- Modify: `src/test/java/com/demo/demo/Service/VoiceMessageReplyTest.java`
- Modify: `src/main/java/com/demo/demo/Service/BotService.java`
- Modify: `docs/superpowers/specs/2026-07-20-wechat-voice-mvp-design.md`

1. Change the successful voice-reply test to require `sendVoiceMessage(..., playtimeMs, 6)` and run `mvn.cmd -Dtest=VoiceMessageReplyTest test`; expect failure because production currently sends encode type `1`.
2. Add a named SILK encode-type constant with value `6`, use it in `sendVoiceReply`, and log non-sensitive byte length and playtime after successful upload/send.
3. Re-run `mvn.cmd -Dtest=VoiceMessageReplyTest test`; expect both tests to pass.
4. Run `mvn.cmd test`, `mvn.cmd -DskipTests package`, `git diff --check`, and inspect `git status --short`.

## Task 8: Complete SDK 1.0.1 Voice Metadata

**Files:**
- Modify: `src/test/java/com/demo/demo/Service/VoiceMessageReplyTest.java`
- Modify: `src/main/java/com/demo/demo/Service/BotService.java`
- Modify: `docs/superpowers/specs/2026-07-20-wechat-voice-mvp-design.md`

1. Add a failing test requiring a complete SDK `SendMessageRequest` voice item and a send-failure text fallback.
2. Keep `uploadMedia(..., 4, ...)`, then build the SDK DTO with item type `3`, SILK encode type `6`, 16 bits, 16000 Hz, and millisecond playtime before calling `sendMessage(...)`.
3. Run the focused test, full test suite, package build, and Git diff checks.
4. Restart the application and manually verify that WeChat renders a playable native voice bubble.

## Task 9: Replace Unreliable Native Voice with MP3 Attachment

**Files:**
- Modify: `src/main/java/com/demo/demo/Service/voice/SiliconFlowTtsService.java`
- Modify: `src/main/java/com/demo/demo/Service/voice/VoiceMessageService.java`
- Modify: `src/main/java/com/demo/demo/Service/BotService.java`
- Modify: corresponding voice tests and documentation

1. Request MP3 directly from SiliconFlow instead of PCM for the outbound reply.
2. Return MP3 bytes from voice orchestration without outbound SILK encoding.
3. Upload with media type `3` and send through `sendFileMessage(...)` using a `.mp3` filename.
4. Send no duplicate text after success; fall back to the generated LLM text on TTS, upload, or file-send failure.
