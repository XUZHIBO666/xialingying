# WeChat Voice MVP Design

## Goal

Complete the existing inbound voice path with a native WeChat SILK voice reply while preserving text fallbacks. The implementation stays inside the current Spring application and reuses iLink SDK 1.0.1 and `AIService.chat()`.

## Data Flow

`BotService.startListening()` detects `MessageItemDto.isVoice()` and downloads/decrypts media through `ILinkClient.downloadMedia(...)`. `AudioCodecService` converts SILK to raw PCM S16LE, 16 kHz, mono. `SiliconFlowAsrService` wraps that PCM as WAV for the existing transcription endpoint. The recognized text is passed to `AIService.chat()`. `SiliconFlowTtsService` requests non-streaming 16 kHz PCM. `AudioCodecService` encodes PCM to SILK, after which `BotService` calls `uploadMedia(credentials, 4, toUserId, silk)` and `sendVoiceMessage(credentials, toUserId, contextToken, media, playtimeMs, 1)`.

## Components

- Keep `AsrService` and `SiliconFlowAsrService`; make their input the internal PCM representation with minimal changes.
- Add `TtsService` and one `SiliconFlowTtsService` implementation.
- Add `AudioCodecService`; adapt the existing command-based converter to decode and encode SILK using configured executables.
- Keep orchestration close to the existing `BotService` voice path. Add a small `VoiceMessageService` only if it reduces branching in `BotService`.
- Extend `VoiceProperties` and YAML placeholders for TTS and the SILK encoder path. No credentials are stored in source.

## Failure Behavior

- Download, decode, ASR failure, or blank transcript: send `没有听清，请重新发送一段语音。`
- `AIService.chat()` failure or blank response: send `当前服务暂时不可用，请稍后重试。`
- TTS failure: send the generated LLM text.
- SILK encoding, upload, or voice-send failure: send the generated LLM text.

## Verification

Use mocked HTTP and iLink calls for deterministic TTS, codec-command, orchestration, and fallback tests. Run focused tests first, then the full Maven test/package lifecycle. Real SiliconFlow and WeChat delivery remain manual checks requiring credentials, encoder/decoder executables, and a logged-in iLink session.
