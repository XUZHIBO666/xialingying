# Repository Guidelines

## Project Structure & Module Organization

- `src/main/java/com/demo/demo/` contains the Spring Boot application: bot orchestration in `Service/`, voice code in `Service/voice/`, endpoints in `controller/`, and configuration in `config/`.
- `src/main/resources/` holds configuration and `templates/bot.html`. Use `application-local.example.yml` for local overrides.
- Tests mirror production packages under `src/test/java/`. Do not commit `target/` or `logs/`.

## Build, Test, and Development Commands

- `mvnw.cmd spring-boot:run` — start the application locally.
- `mvnw.cmd test` — run the complete JUnit suite.
- `mvnw.cmd -Dtest=VoiceMessageServiceTest test` — run one focused test class.
- `mvnw.cmd clean package` — compile, test, and build the JAR.

Use `./mvnw` instead on macOS or Linux.

## Current Development Goal

Implement only the WeChat voice-assistant MVP:

`WeChat SILK → download/decrypt → PCM S16LE 16 kHz mono → ASR → existing LLM → TTS PCM → SILK → WeChat voice reply`

Reuse existing reception, LLM, and sending code. Use one ASR and one TTS provider. Internal audio is PCM S16LE, 16000 Hz, mono; WeChat input/output is SILK. TTS failure falls back to text; ASR failure asks the user to resend.

Do not implement WebSocket, WebRTC, Opus, streaming ASR/TTS, complex monitoring, distributed locks, idempotency infrastructure, or microservice decomposition. Do not upgrade Java, Spring Boot, or the iLink SDK unless the current version blocks the core flow.

## Coding Style & Agent Workflow

Use Java 21, four-space indentation, `PascalCase` classes, and `camelCase` members. Match existing style; avoid unrelated refactors and speculative abstractions.

State assumptions and clarify material ambiguity or contradictions before coding. Prefer the simplest viable implementation. Modify only task-related code, remove dead imports you introduce, and only report pre-existing dead code. Convert goals into observable tests, plan multi-step work briefly, and verify every change.

## Testing Guidelines

Tests use JUnit 5, Mockito, and Spring test utilities. Name classes `*Test` and methods by behavior, such as `ttsFailureFallsBackToText()`. Bug fixes need a failing regression test. Mock ASR, TTS, iLink, and network calls for deterministic CI.

## Commit & Pull Request Guidelines

Use `feat:`, `fix:`, `docs:`, or `chore:` with an imperative summary. Keep commits focused. Pull requests should state the voice stage changed, configuration, fallback behavior, tests run, and deferred manual WeChat checks. Link issues; add screenshots only for UI changes.

## Security & Configuration

Read API keys from environment variables or configuration placeholders; never hard-code or log secrets, tokens, message bodies, media parameters, or external response bodies.
