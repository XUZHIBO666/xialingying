# WeChat MP3 Reply Design

## Goal

Replace the silently dropped native WeChat voice reply with a downloadable and playable MP3 file attachment. A successful MP3 reply must not also send text. TTS, upload, or file-send failure falls back to the generated LLM text.

## Data Flow

Keep the inbound path unchanged: WeChat SILK is downloaded and decrypted, decoded to PCM S16LE 16 kHz mono, transcribed by SiliconFlow ASR, and passed to `AIService.chat()`.

Change only the outbound path: `SiliconFlowTtsService` requests non-streaming MP3 from `/v1/audio/speech`; `VoiceMessageService` returns the LLM text plus optional MP3 bytes; `BotService` uploads those bytes with iLink media type `3` (file) and sends them with `sendFileMessage(...)` using a `.mp3` filename.

## Components

- Keep `TtsService` as the provider boundary and make its synthesized output MP3 for this MVP.
- Keep `AudioCodecService.silkToPcm(...)` for inbound ASR. Do not call `pcmToSilk(...)` for outbound replies.
- Rename the voice result fields and predicates to describe an MP3 attachment instead of SILK voice metadata.
- Add one small `BotService` helper for MP3 upload and file-message sending. Do not add a new orchestration layer.
- Add no FFmpeg dependency, executable path, or new credential.

## Failure Behavior

- ASR failure or blank transcript: send `没有听清，请重新发送一段语音。`
- LLM failure or blank response: send `当前服务暂时不可用，请稍后重试。`
- TTS failure or empty/oversized MP3: send the generated LLM text.
- MP3 upload or file-send failure: send the generated LLM text.
- MP3 send success: send no duplicate text.

## Verification

Tests will first fail while requiring `response_format=mp3`, an MP3 result from orchestration, `uploadMedia(..., 3, ...)`, and `sendFileMessage(...)`. Focused tests will then verify successful attachment delivery and text fallback on upload/send failure. Finally, run the complete Maven test suite, package build, `git diff --check`, and inspect Git status. Real WeChat download/playback remains a manual acceptance check.
