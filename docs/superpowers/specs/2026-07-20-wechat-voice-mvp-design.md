# WeChat Voice MVP Design

## Goal

Complete the existing inbound voice path with a downloadable MP3 file reply while preserving text fallbacks. Native iLink voice requests are accepted but not reliably delivered, so the implementation uses the supported file-message path in iLink SDK 1.0.1 and reuses `AIService.chat()`.

## Data Flow

`BotService.startListening()` detects `MessageItemDto.isVoice()` and downloads/decrypts media through `ILinkClient.downloadMedia(...)`. `AudioCodecService` converts SILK to raw PCM S16LE, 16 kHz, mono. `SiliconFlowAsrService` wraps that PCM as WAV for the transcription endpoint. The recognized text is passed to `AIService.chat()`. `SiliconFlowTtsService` requests non-streaming MP3. `BotService` uploads the MP3 with `uploadMedia(credentials, 3, toUserId, mp3)` and sends it with `sendFileMessage(...)` using a `.mp3` filename.

## Components

- Keep `AsrService` and `SiliconFlowAsrService`; make their input the internal PCM representation with minimal changes.
- Add `TtsService` and one `SiliconFlowTtsService` implementation.
- Add `AudioCodecService`; use the command-based converter to decode inbound SILK for ASR.
- Keep orchestration close to the existing `BotService` voice path. Add a small `VoiceMessageService` only if it reduces branching in `BotService`.
- Extend `VoiceProperties` and YAML placeholders for TTS and the SILK decoder path. No credentials are stored in source.

## Failure Behavior

- Download, decode, ASR failure, or blank transcript: send `没有听清，请重新发送一段语音。`
- `AIService.chat()` failure or blank response: send `当前服务暂时不可用，请稍后重试。`
- TTS failure: send the generated LLM text.
- MP3 upload or file-send failure: send the generated LLM text.

## Verification

Use mocked HTTP and iLink calls for deterministic TTS, codec-command, orchestration, and fallback tests. Run focused tests first, then the full Maven test/package lifecycle. Real SiliconFlow and WeChat delivery remain manual checks requiring credentials, a decoder executable, and a logged-in iLink session.
