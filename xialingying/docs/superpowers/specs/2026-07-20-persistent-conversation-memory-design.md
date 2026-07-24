# Persistent Conversation Memory Design

## Goal

Persist the most recent 10 complete LLM conversation turns per WeChat user so context survives application restarts. Keep the implementation local and single-instance: no Redis, database, distributed locking, or long-term profile extraction.

## Storage

Store all users in one JSON snapshot at `./data/conversation-memory.json` by default. Allow overriding the path with `AI_MEMORY_FILE`. Add `data/` to `.gitignore` so conversation content is never committed.

The file contains only ordered `user` and `assistant` messages grouped by user ID. The system prompt remains configuration and is added dynamically when building each LLM request. Each user retains at most 10 complete pairs (20 messages).

Write a complete snapshot to a sibling temporary file and replace the target atomically. If atomic moves are unavailable, fall back to a normal replace. A missing file initializes empty memory. Malformed or unreadable JSON logs a content-free warning and initializes empty memory without blocking application startup.

## Components and Flow

Add a small `ConversationMemoryStore` responsible for loading the snapshot, returning a defensive copy of one user's messages, saving a complete successful turn, and clearing one user. It keeps the loaded snapshot in memory and serializes file mutations so concurrent writes cannot corrupt JSON.

`AIService.chat(userId, message)` obtains a per-user lock, loads that user's persisted history, and builds the request in this order:

1. Current configured system prompt.
2. Up to 10 previously successful user/assistant pairs.
3. Current user message.

Only after a valid LLM reply is parsed does `AIService` append the complete user/assistant pair to the store. An HTTP, parsing, or model failure leaves persisted history unchanged. `clearHistory(userId)` removes that user's history from both the in-memory snapshot and JSON file while retaining other users.

Different users can call the LLM concurrently; calls for the same user are serialized to preserve conversational order. If persistence fails after a successful LLM response, return the response and retain the updated snapshot in memory so a later successful save can persist it.

## Privacy and Logging

Do not log stored message bodies, the JSON snapshot, API keys, or full user identifiers added by this feature. Logs may state the number of users or turns and report file operation failures without conversation content.

## Verification

Add deterministic tests proving restart persistence, user isolation, 10-pair trimming, ordered LLM request construction, unchanged persistence after LLM failure, selective clearing, malformed-file startup, and valid JSON after concurrent writes for different users. Use temporary directories and a local mock HTTP server; do not depend on the developer's real memory file or LLM credentials. Run focused tests, the complete Maven test suite, package build, `git diff --check`, and Git status inspection.
