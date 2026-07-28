# Periodic Weather Push Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow a WeChat user to say “每天8点发送杭州天气”, have the existing ReactAgent create a persistent daily weather task, and later generate and deliver the weather message through the correct iLink Bot instance.

**Architecture:** The conversational ReactAgent calls a task-management Tool, which persists a daily recurrence and the caller’s encrypted iLink delivery target in SQLite. A lightweight Spring scheduler creates durable execution rows for due tasks; a bounded worker queries the existing weather domain service, asks a dedicated side-effect-free ReactAgent to format the message, and sends it through an iLink push gateway. SQLite uniqueness constraints and conditional state transitions provide local idempotency without Quartz, a message queue, or distributed locks.

**Tech Stack:** Java 21, Spring Boot 3.4.5, Spring JDBC, SQLite JDBC, Spring Scheduling, Spring AI Alibaba ReactAgent, iLink SDK, JUnit 5, Mockito.

## Global Constraints

- Keep the project on Java 21 and Spring Boot 3.4.5.
- SQLite is the only database assumed by this plan.
- Reuse the existing reception, ReactAgent, weather, and iLink sending code.
- MVP recurrence is `DAILY` at one local minute; do not accept arbitrary cron expressions.
- MVP task type is `WEATHER`; do not add generic user-authored prompts.
- Default time zone is `Asia/Shanghai`; persist each task’s explicit IANA zone.
- Persist instants as UTC epoch milliseconds in SQLite `INTEGER` columns.
- Persist structured payloads as validated JSON strings in SQLite `TEXT` columns.
- Never log or return context tokens, credentials, API keys, message bodies, media parameters, or provider response bodies.
- Encrypt persisted iLink context tokens with AES-GCM and an environment-provided key.
- Do not introduce Quartz, a message queue, distributed locks, microservices, WebSocket, WebRTC, or streaming AI.
- Scheduled executions must not enter the user’s ordinary conversation memory or vector memory.
- Weather failure must never cause the model to invent weather data.
- LLM formatting failure falls back to deterministic text formatting.
- Every production change follows a failing-test, minimal-implementation, passing-test cycle.
- Do not change or stage unrelated user files, including `.idea/material_theme_project_new.xml` and `docs/weather-module-analysis.md`.

---

## Scope and Acceptance Criteria

The implementation is complete when all of the following observable behaviors pass:

1. “每天早上8点发送杭州天气” reaches ReactAgent and calls the create-task Tool.
2. The Tool derives its delivery-target ownership from trusted `ToolContext`, not model arguments; the context token never enters Agent configuration.
3. SQLite contains one active daily weather task with location `杭州`, local time `08:00`, and zone `Asia/Shanghai`.
4. An equivalent active task is not duplicated.
5. A due task creates at most one execution row for one scheduled instant.
6. Execution obtains a real `WeatherReport`, generates a concise message, and sends through the Bot that originally owned the task.
7. A formatting-model failure uses deterministic weather text.
8. A weather-provider failure does not send fabricated content and follows the bounded retry policy.
9. A logged-out Bot or transient iLink failure follows the bounded retry policy.
10. An invalid delivery target is disabled and is not retried indefinitely.
11. Users can list, pause, resume, and delete only their own tasks.
12. Application restart preserves tasks and recovers eligible execution rows.
13. No sensitive token or full scheduled message body appears in server logs or execution audit rows.

## Chosen Runtime Semantics

- Scheduler scan interval: 30 seconds.
- Scheduler batch limit: 50 due tasks.
- Worker pool: 2 threads with a queue capacity of 100.
- Execution lease timeout: 10 minutes.
- Maximum delivery attempts: 3.
- Retry delays: 1 minute after attempt 1 and 5 minutes after attempt 2.
- A missed daily task is generated if it is no more than 30 minutes late; older missed occurrences are marked `SKIPPED` and the next daily occurrence remains scheduled.
- `next_run_at` is advanced when the durable execution row is created, not after delivery.
- One execution is identified by `(task_id, scheduled_for)`.
- LLM formatting errors do not consume a delivery retry because deterministic formatting is used immediately.
- Weather and iLink transient failures consume delivery attempts.
- First release supports the stable Bot key `default`; the data model retains `bot_key` so multi-Bot routing can be added without schema replacement.

## File Map

### Configuration and SQLite schema

- `src/main/java/com/demo/demo/config/SchedulingProperties.java` — typed scheduling, retry, encryption, and default-zone settings.
- `src/main/java/com/demo/demo/config/SchedulingConfiguration.java` — enables scheduling and exposes the bounded execution pool.
- `src/main/java/com/demo/demo/config/SQLiteDataSourceConfiguration.java` — creates the shared SQLite datasource with per-connection foreign-key, WAL, and busy-timeout settings.
- `src/main/resources/schema-scheduling.sql` — idempotent SQLite DDL for targets, tasks, and executions.
- `src/main/java/com/demo/demo/Service/scheduling/persistence/SchedulingSchemaInitializer.java` — executes only the idempotent scheduling DDL at startup.

### Trusted inbound context

- `src/main/java/com/demo/demo/Service/messaging/InboundMessageContext.java` — immutable `botKey`, `userId`, and `contextToken`.
- `src/main/java/com/demo/demo/Service/messaging/AgentCallerContext.java` — immutable non-sensitive `deliveryTargetId` and `botKey` passed into one Agent invocation.
- `src/main/java/com/demo/demo/Service/messaging/TextMessageHandler.java` — context-aware auto-reply boundary.
- `src/main/java/com/demo/demo/Service/messaging/TrustedToolContextInterceptor.java` — transfers `AgentCallerContext` from `RunnableConfig` metadata into Spring AI `ToolContext`.

### Domain and application services

- `src/main/java/com/demo/demo/Service/scheduling/domain/*` — task, recurrence, payload, execution, and status types.
- `src/main/java/com/demo/demo/Service/scheduling/application/NextRunCalculator.java` — local-time/zone to UTC instant calculation.
- `src/main/java/com/demo/demo/Service/scheduling/application/DeliveryTargetService.java` — encrypts, refreshes, resolves, and invalidates delivery targets.
- `src/main/java/com/demo/demo/Service/scheduling/application/ScheduledTaskService.java` — owner-scoped task commands and queries.
- `src/main/java/com/demo/demo/Service/scheduling/application/ScheduledTaskExecutionService.java` — durable dispatch, content generation, delivery, retry, and terminal states.

### Persistence

- `src/main/java/com/demo/demo/Service/scheduling/persistence/DeliveryTargetRepository.java`
- `src/main/java/com/demo/demo/Service/scheduling/persistence/JdbcDeliveryTargetRepository.java`
- `src/main/java/com/demo/demo/Service/scheduling/persistence/ScheduledTaskRepository.java`
- `src/main/java/com/demo/demo/Service/scheduling/persistence/JdbcScheduledTaskRepository.java`
- `src/main/java/com/demo/demo/Service/scheduling/persistence/TaskExecutionRepository.java`
- `src/main/java/com/demo/demo/Service/scheduling/persistence/JdbcTaskExecutionRepository.java`

### Agent Tools and scheduled content

- `src/main/java/com/demo/demo/Service/scheduling/tool/CreateScheduledTaskTool.java`
- `src/main/java/com/demo/demo/Service/scheduling/tool/ManageScheduledTaskTool.java`
- `src/main/java/com/demo/demo/Service/scheduling/agent/ScheduledContentAgent.java`
- `src/main/java/com/demo/demo/Service/scheduling/agent/ScheduledContentPromptFactory.java`
- `src/main/java/com/demo/demo/Service/scheduling/agent/WeatherMessageTemplateFormatter.java`

### Scheduling and iLink delivery

- `src/main/java/com/demo/demo/Service/scheduling/scheduler/ScheduledTaskScanner.java`
- `src/main/java/com/demo/demo/Service/scheduling/push/MessagePushGateway.java`
- `src/main/java/com/demo/demo/Service/scheduling/push/ILinkMessagePushGateway.java`
- `src/main/java/com/demo/demo/Service/scheduling/push/PushRequest.java`
- `src/main/java/com/demo/demo/Service/scheduling/push/PushResult.java`
- `src/main/java/com/demo/demo/Service/scheduling/push/ContextTokenCipher.java`

---

### Task 1: Establish the SQLite scheduling baseline

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/resources/application-local.example.yml`
- Create: `src/main/resources/schema-scheduling.sql`
- Create: `src/main/java/com/demo/demo/config/SchedulingProperties.java`
- Create: `src/main/java/com/demo/demo/config/SQLiteDataSourceConfiguration.java`
- Create: `src/main/java/com/demo/demo/Service/scheduling/persistence/SchedulingSchemaInitializer.java`
- Test: `src/test/java/com/demo/demo/Service/scheduling/persistence/SchedulingSchemaInitializerTest.java`
- Test: `src/test/java/com/demo/demo/config/SchedulingPropertiesTest.java`
- Test: `src/test/java/com/demo/demo/config/SQLiteDataSourceConfigurationTest.java`

**Interfaces:**
- Produces `SchedulingProperties` with `enabled`, `defaultZone`, `scanInterval`, `batchSize`, `leaseTimeout`, `maxAttempts`, `retryDelays`, `lateGracePeriod`, `workerThreads`, `workerQueueCapacity`, and `tokenEncryptionKey`.
- Produces one shared SQLite datasource used by existing JDBC memory and the scheduling repositories; each created connection enforces foreign keys, WAL journal mode, and the configured busy timeout.
- Produces the SQLite tables `wechat_delivery_target`, `scheduled_task`, and `scheduled_task_execution`.
- Produces indexes for due-task and retry scans and unique keys for target ownership and execution idempotency.

- [ ] **Step 1: Write failing SQLite datasource and schema tests**

  Verify against a temporary SQLite file that every separately opened datasource connection has foreign keys enabled and the configured busy timeout, WAL is active, startup creates all three tables, startup is idempotent, `payload_json` and encrypted tokens use `TEXT`, instant columns use `INTEGER`, and `(task_id, scheduled_for)` rejects a duplicate.

- [ ] **Step 2: Run the focused schema tests**

  Run: `mvnw.cmd -Dtest=SchedulingSchemaInitializerTest,SchedulingPropertiesTest,SQLiteDataSourceConfigurationTest test`

  Expected: FAIL because the initializer, properties class, and schema do not exist.

- [ ] **Step 3: Define the exact SQLite schema**

  Use `INTEGER PRIMARY KEY AUTOINCREMENT`, `TEXT NOT NULL`, integer status timestamps, foreign keys, and `CHECK` constraints for known statuses and recurrence values. Store task deletion as status `DELETED`; do not physically cascade-delete audit history.

- [ ] **Step 4: Configure every SQLite connection for this workload**

  Create the datasource with Xerial `SQLiteConfig`/`SQLiteDataSource`: enforce foreign keys, select WAL journal mode, and set the configurable busy timeout at connection creation. Do not rely on a one-time initializer `PRAGMA`, because foreign keys and busy timeout are connection-scoped. Keep schema transactions short and do not hold a database transaction during weather, model, or iLink calls.

- [ ] **Step 5: Bind scheduling configuration**

  Add the `app.scheduling` configuration tree with defaults matching “Chosen Runtime Semantics”. The encryption key has no committed default; when scheduling is enabled and the key is absent or invalid length, startup must fail with a configuration error.

- [ ] **Step 6: Make SQLite the explicit application datasource**

  Align the main and local-example datasource with `jdbc:sqlite:./data/xialingying.db`, remove the MySQL connector and MySQL driver configuration, and keep the existing SQLite JDBC version unless compilation requires a change. Confirm `VectorMemoryStore` and scheduling repositories use the same injected `JdbcTemplate`.

- [ ] **Step 7: Run focused and full tests**

  Run: `mvnw.cmd -Dtest=SchedulingSchemaInitializerTest,SchedulingPropertiesTest,SQLiteDataSourceConfigurationTest test`

  Expected: PASS.

  Run: `mvnw.cmd test`

  Expected: all pre-existing and new tests pass.

- [ ] **Step 8: Commit this independently testable baseline**

  Commit message: `feat: add SQLite scheduling schema`

---

### Task 2: Define daily task and execution domain behavior

**Files:**
- Create: `src/main/java/com/demo/demo/Service/scheduling/domain/ScheduledTask.java`
- Create: `src/main/java/com/demo/demo/Service/scheduling/domain/ScheduledTaskStatus.java`
- Create: `src/main/java/com/demo/demo/Service/scheduling/domain/ScheduledTaskType.java`
- Create: `src/main/java/com/demo/demo/Service/scheduling/domain/RecurrenceType.java`
- Create: `src/main/java/com/demo/demo/Service/scheduling/domain/WeatherTaskPayload.java`
- Create: `src/main/java/com/demo/demo/Service/scheduling/domain/TaskExecution.java`
- Create: `src/main/java/com/demo/demo/Service/scheduling/domain/TaskExecutionStatus.java`
- Create: `src/main/java/com/demo/demo/Service/scheduling/application/NextRunCalculator.java`
- Test: `src/test/java/com/demo/demo/Service/scheduling/domain/ScheduledTaskDomainTest.java`
- Test: `src/test/java/com/demo/demo/Service/scheduling/application/NextRunCalculatorTest.java`

**Interfaces:**
- `NextRunCalculator.nextDailyRun(LocalTime localTime, ZoneId zoneId, Instant now)` returns the first future `Instant`.
- `NextRunCalculator.followingDailyRun(LocalTime localTime, ZoneId zoneId, Instant scheduledFor)` returns the next occurrence after the supplied occurrence.
- `WeatherTaskPayload` contains normalized `location`, `reportDate=TODAY`, and `style=BRIEF`.
- `ScheduledTask` carries internal ID, public UUID key, target ID, type, recurrence, local time, zone, payload, status, next/last run instants, version, and audit instants.

- [ ] **Step 1: Write failing domain validation tests**

  Cover blank location, unsupported task type, unsupported recurrence, invalid zone, missing next run, and illegal status transition from `DELETED` back to `ACTIVE`.

- [ ] **Step 2: Write failing time-calculation tests**

  Use a fixed `Clock` and cover: creation before 08:00 runs today; creation at or after 08:00 runs tomorrow; UTC conversion for Shanghai; month/year boundary; and a DST zone such as `America/New_York` to prove use of `ZoneId` rather than a fixed offset.

- [ ] **Step 3: Run the focused tests**

  Run: `mvnw.cmd -Dtest=ScheduledTaskDomainTest,NextRunCalculatorTest test`

  Expected: FAIL because domain types and calculator do not exist.

- [ ] **Step 4: Implement immutable domain types**

  Keep validation in constructors or named factories. Do not put JDBC, Agent, iLink, or Spring annotations in domain classes.

- [ ] **Step 5: Implement deterministic recurrence calculation**

  Use Java time types only. Treat equality with the requested minute as already reached, so a task created exactly at 08:00 schedules tomorrow and cannot accidentally fire twice.

- [ ] **Step 6: Run focused and full tests**

  Run: `mvnw.cmd -Dtest=ScheduledTaskDomainTest,NextRunCalculatorTest test`

  Expected: PASS.

  Run: `mvnw.cmd test`

  Expected: PASS.

- [ ] **Step 7: Commit the domain slice**

  Commit message: `feat: model daily scheduled tasks`

---

### Task 3: Implement SQLite repositories and durable execution claims

**Files:**
- Create: `src/main/java/com/demo/demo/Service/scheduling/persistence/DeliveryTargetRepository.java`
- Create: `src/main/java/com/demo/demo/Service/scheduling/persistence/JdbcDeliveryTargetRepository.java`
- Create: `src/main/java/com/demo/demo/Service/scheduling/persistence/ScheduledTaskRepository.java`
- Create: `src/main/java/com/demo/demo/Service/scheduling/persistence/JdbcScheduledTaskRepository.java`
- Create: `src/main/java/com/demo/demo/Service/scheduling/persistence/TaskExecutionRepository.java`
- Create: `src/main/java/com/demo/demo/Service/scheduling/persistence/JdbcTaskExecutionRepository.java`
- Test: `src/test/java/com/demo/demo/Service/scheduling/persistence/JdbcDeliveryTargetRepositoryTest.java`
- Test: `src/test/java/com/demo/demo/Service/scheduling/persistence/JdbcScheduledTaskRepositoryTest.java`
- Test: `src/test/java/com/demo/demo/Service/scheduling/persistence/JdbcTaskExecutionRepositoryTest.java`

**Interfaces:**
- Target repository upserts by `(bot_key, user_id)` and returns a stable target ID.
- Task repository creates a task, lists owner tasks, finds an owner task by public key, finds equivalent active weather tasks, scans due tasks, advances `next_run_at` with `version` comparison, and changes owner-scoped status.
- Execution repository inserts one row per `(task_id, scheduled_for)`, claims `PENDING` or due `RETRY` rows through a conditional update, completes rows, schedules retries, and recovers expired `RUNNING` leases.

- [ ] **Step 1: Write failing repository contract tests**

  Use a fresh temporary SQLite file per test class. Cover round-trip mapping, owner isolation, due ordering, batch limit, optimistic-version failure, target upsert, target invalidation, duplicate execution rejection, single-winner claim, retry eligibility, and stale lease recovery.

- [ ] **Step 2: Run focused repository tests**

  Run: `mvnw.cmd -Dtest=JdbcDeliveryTargetRepositoryTest,JdbcScheduledTaskRepositoryTest,JdbcTaskExecutionRepositoryTest test`

  Expected: FAIL because repositories do not exist.

- [ ] **Step 3: Implement explicit row mapping**

  Convert UTC epoch milliseconds to `Instant`, `HH:mm` text to `LocalTime`, zone text to `ZoneId`, and payload JSON to `WeatherTaskPayload`. Reject malformed stored payloads with a repository exception; do not send partially parsed tasks.

- [ ] **Step 4: Implement SQLite-safe conditional state changes**

  Claim with one conditional `UPDATE` using execution ID, expected status, due time, and attempt limit. Treat update count `1` as acquired and `0` as lost. Do not use `SELECT FOR UPDATE`, `SKIP LOCKED`, or a transaction spanning remote calls.

- [ ] **Step 5: Implement durable due materialization**

  Within one short transaction, insert the execution row and advance the task to its following daily occurrence. If the unique execution insert reports an existing row, do not create a second worker action.

- [ ] **Step 6: Run focused and full tests**

  Run: `mvnw.cmd -Dtest=JdbcDeliveryTargetRepositoryTest,JdbcScheduledTaskRepositoryTest,JdbcTaskExecutionRepositoryTest test`

  Expected: PASS.

  Run: `mvnw.cmd test`

  Expected: PASS.

- [ ] **Step 7: Commit persistence**

  Commit message: `feat: persist scheduled task executions`

---

### Task 4: Introduce trusted inbound context and encrypted delivery targets

**Files:**
- Create: `src/main/java/com/demo/demo/Service/messaging/InboundMessageContext.java`
- Create: `src/main/java/com/demo/demo/Service/messaging/AgentCallerContext.java`
- Create: `src/main/java/com/demo/demo/Service/messaging/TextMessageHandler.java`
- Create: `src/main/java/com/demo/demo/Service/messaging/TrustedToolContextInterceptor.java`
- Create: `src/main/java/com/demo/demo/Service/scheduling/push/ContextTokenCipher.java`
- Create: `src/main/java/com/demo/demo/Service/scheduling/application/DeliveryTargetService.java`
- Modify: `src/main/java/com/demo/demo/Service/BotInstance.java`
- Modify: `src/main/java/com/demo/demo/Service/MultiBotManager.java`
- Modify: `src/main/java/com/demo/demo/controller/BotController.java`
- Test: `src/test/java/com/demo/demo/Service/messaging/TrustedToolContextInterceptorTest.java`
- Test: `src/test/java/com/demo/demo/Service/scheduling/push/ContextTokenCipherTest.java`
- Test: `src/test/java/com/demo/demo/Service/scheduling/application/DeliveryTargetServiceTest.java`
- Modify test: `src/test/java/com/demo/demo/Service/ServerLogPrivacyTest.java`
- Modify affected tests: `src/test/java/com/demo/demo/Service/WeatherAgentRoutingTest.java`
- Modify affected tests: `src/test/java/com/demo/demo/Service/ImageAutoReplyTest.java`

**Interfaces:**
- `InboundMessageContext` exposes stable `botKey`, `userId`, and `contextToken`.
- `DeliveryTargetService.refresh(context)` persists the encrypted token and returns `AgentCallerContext(deliveryTargetId, botKey)`.
- `AIService.chat(userId, message, callerContext)` stores only `AgentCallerContext` in the invocation’s `RunnableConfig` metadata.
- `TrustedToolContextInterceptor` reads `AgentCallerContext` from `ToolCallExecutionContext.config()` and copies it into the intercepted `ToolCallRequest` context.
- Task-management `@Tool` methods accept Spring AI `ToolContext` as a framework-supplied parameter and fail closed when the trusted caller key is absent.
- `ContextTokenCipher.encrypt(plaintext)` and `decrypt(ciphertext)` use AES-GCM with a fresh nonce.
- `DeliveryTargetService.resolve(targetId)` returns decrypted routing only to the push gateway.

- [ ] **Step 1: Write failing trusted Tool-context tests**

  Build two `RunnableConfig` instances with different `AgentCallerContext` values and invoke the interceptor from separate executor threads. Prove each `ToolContext` receives only its own caller, a missing caller fails closed, the model-visible Tool schema excludes caller fields, and the context token is never present in config metadata or Tool context.

- [ ] **Step 2: Write failing encryption tests**

  Prove round trip, different ciphertext for the same plaintext due to fresh nonces, tamper rejection, and absence of plaintext inside stored ciphertext.

- [ ] **Step 3: Write failing delivery-target tests**

  Prove repeat messages update the same target, update `last_seen_at`, replace the encrypted token, preserve masked logs, and prevent resolution after invalidation.

- [ ] **Step 4: Run focused tests**

  Run: `mvnw.cmd -Dtest=TrustedToolContextInterceptorTest,ContextTokenCipherTest,DeliveryTargetServiceTest test`

  Expected: FAIL because the new context, interceptor, encryption, and target boundaries do not exist.

- [ ] **Step 5: Pass Bot identity through the message handler**

  Replace the multi-Bot shared text callback’s three loose arguments with `InboundMessageContext` plus text. Set the first release’s persistent Bot key to `default`; keep the existing transient instance ID for UI status only.

- [ ] **Step 6: Refresh the target before entering ReactAgent**

  In the automatic-reply application path, persist the latest context token before the Agent call, receive an `AgentCallerContext`, and pass it to the `AIService.chat` overload. Store only target ID and Bot key in `RunnableConfig` metadata; do not store the raw user ID or context token there.

- [ ] **Step 7: Register the Tool interceptor and verify async propagation**

  Register `TrustedToolContextInterceptor` on the conversational ReactAgent. The interceptor must use `ToolCallExecutionContext.config()` rather than a thread-local so it remains correct if ReactAgent executes Tools in a parallel executor.

- [ ] **Step 8: Correct media routing while the context is available**

  Replace `multiBotManager.getDefaultBot()` media sends with lookup by the inbound context’s Bot key. This is required so task creation and existing image/voice side effects share correct multi-Bot ownership.

- [ ] **Step 9: Extend privacy assertions**

  Add scheduling and messaging sources to `ServerLogPrivacyTest`. Assert that log statements contain neither raw `contextToken` variables nor decrypted target fields.

- [ ] **Step 10: Run affected and full tests**

  Run: `mvnw.cmd -Dtest=TrustedToolContextInterceptorTest,ContextTokenCipherTest,DeliveryTargetServiceTest,WeatherAgentRoutingTest,ImageAutoReplyTest,ServerLogPrivacyTest test`

  Expected: PASS.

  Run: `mvnw.cmd test`

  Expected: PASS.

- [ ] **Step 11: Commit trusted routing**

  Commit message: `feat: persist trusted iLink delivery targets`

---

### Task 5: Add owner-scoped task services and ReactAgent Tools

**Files:**
- Create: `src/main/java/com/demo/demo/Service/scheduling/application/CreateDailyWeatherTaskCommand.java`
- Create: `src/main/java/com/demo/demo/Service/scheduling/application/ScheduledTaskService.java`
- Create: `src/main/java/com/demo/demo/Service/scheduling/tool/CreateScheduledTaskTool.java`
- Create: `src/main/java/com/demo/demo/Service/scheduling/tool/ManageScheduledTaskTool.java`
- Create: `src/main/java/com/demo/demo/Service/scheduling/tool/ScheduledTaskToolResult.java`
- Modify: `src/main/java/com/demo/demo/Service/AIService.java`
- Modify: `src/main/resources/application.yml`
- Modify test: `src/test/java/com/demo/demo/Service/tool/ToolAnnotationTest.java`
- Create test: `src/test/java/com/demo/demo/Service/scheduling/application/ScheduledTaskServiceTest.java`
- Create test: `src/test/java/com/demo/demo/Service/scheduling/tool/CreateScheduledTaskToolTest.java`
- Create test: `src/test/java/com/demo/demo/Service/scheduling/tool/ManageScheduledTaskToolTest.java`
- Create test: `src/test/java/com/demo/demo/Service/ScheduledTaskAgentRoutingTest.java`

**Interfaces:**
- Create Tool accepts model-visible `location`, `localTime`, and optional `timeZone`, plus framework-supplied Spring AI `ToolContext`.
- Manage Tool accepts model-visible action `LIST`, `PAUSE`, `RESUME`, or `DELETE`, an optional public task key, and framework-supplied `ToolContext`.
- `ScheduledTaskService.createDailyWeather(command)` validates location through the existing weather domain boundary, deduplicates equivalent active tasks, calculates next run, and returns a safe public view.
- Every query and mutation is scoped by the trusted delivery-target ID obtained from `ToolContext`.

- [ ] **Step 1: Write failing service tests**

  Cover successful creation, default zone, invalid zone, invalid `HH:mm`, blank location, maximum five active tasks per owner, equivalent-task deduplication, owner-scoped list, pause, resume with recalculated next run, delete, and cross-user denial.

- [ ] **Step 2: Write failing Tool tests**

  Assert Spring AI `@Tool` annotations and descriptions, that the generated model schema exposes no target/identity/token field, fail-closed behavior without trusted `ToolContext`, structured success/failure output, and exact delegation to `ScheduledTaskService`.

- [ ] **Step 3: Write failing Agent routing tests**

  Verify that an explicit recurring request is passed unchanged to ReactAgent with the scheduling Tools registered, while “明天8点杭州天气怎么样” remains an ordinary weather query and creates no task.

- [ ] **Step 4: Run focused tests**

  Run: `mvnw.cmd -Dtest=ScheduledTaskServiceTest,CreateScheduledTaskToolTest,ManageScheduledTaskToolTest,ScheduledTaskAgentRoutingTest,ToolAnnotationTest test`

  Expected: FAIL because services and Tools do not exist or are not registered.

- [ ] **Step 5: Implement owner-safe task commands**

  Normalize `location` by trimming only; let `WeatherService`/provider resolve it rather than inventing aliases. Return public UUID task keys and human-readable summaries, never internal IDs or routing tokens.

- [ ] **Step 6: Register Tools in the existing ReactAgent**

  Add both task Tools to `ToolCallbacks.from(...)`. Update the system prompt so creation requires explicit recurring intent and confirmation echoes frequency, local time, zone, and location.

- [ ] **Step 7: Run focused and full tests**

  Run: `mvnw.cmd -Dtest=ScheduledTaskServiceTest,CreateScheduledTaskToolTest,ManageScheduledTaskToolTest,ScheduledTaskAgentRoutingTest,ToolAnnotationTest test`

  Expected: PASS.

  Run: `mvnw.cmd test`

  Expected: PASS.

- [ ] **Step 8: Commit Agent task management**

  Commit message: `feat: let ReactAgent manage weather schedules`

---

### Task 6: Build the side-effect-free scheduled content Agent

**Files:**
- Create: `src/main/java/com/demo/demo/config/AgentConfiguration.java`
- Modify: `src/main/java/com/demo/demo/Service/AIService.java`
- Create: `src/main/java/com/demo/demo/Service/scheduling/agent/ScheduledContentAgent.java`
- Create: `src/main/java/com/demo/demo/Service/scheduling/agent/ScheduledContentPromptFactory.java`
- Create: `src/main/java/com/demo/demo/Service/scheduling/agent/WeatherMessageTemplateFormatter.java`
- Test: `src/test/java/com/demo/demo/Service/scheduling/agent/ScheduledContentPromptFactoryTest.java`
- Test: `src/test/java/com/demo/demo/Service/scheduling/agent/ScheduledContentAgentTest.java`
- Test: `src/test/java/com/demo/demo/Service/scheduling/agent/WeatherMessageTemplateFormatterTest.java`
- Modify test: `src/test/java/com/demo/demo/Service/AIServiceMemoryTest.java`

**Interfaces:**
- `AgentConfiguration` exposes one reusable DashScope chat model configured from existing properties.
- `ScheduledContentAgent.generate(task, WeatherReport)` returns concise text and does not expose Tool callbacks with side effects.
- `ScheduledContentPromptFactory` includes only validated task fields and structured weather values.
- `WeatherMessageTemplateFormatter.format(WeatherReport)` produces deterministic Chinese text without a model call.

- [ ] **Step 1: Write failing prompt and formatter tests**

  Verify location, date, condition, temperatures, precipitation/umbrella guidance, source date, and a maximum target length. Assert the prompt contains no user context token, credentials, conversation history, arbitrary stored prompt, or external response body.

- [ ] **Step 2: Write failing scheduled-Agent tests**

  Mock the model boundary. Cover successful concise output, blank model output falling back to the template, model exception falling back to the template, and no call to `VectorMemoryStore.saveTurn`.

- [ ] **Step 3: Run focused tests**

  Run: `mvnw.cmd -Dtest=ScheduledContentPromptFactoryTest,ScheduledContentAgentTest,WeatherMessageTemplateFormatterTest,AIServiceMemoryTest test`

  Expected: FAIL because the scheduled content components do not exist.

- [ ] **Step 4: Extract reusable chat-model construction**

  Move DashScope API/model construction from `AIService` into `AgentConfiguration` without changing existing model name, temperature, maximum token, thinking, or top-p behavior unless a test proves an incompatibility.

- [ ] **Step 5: Build a dedicated ReactAgent**

  Give it a fixed weather-notification system prompt, no `MemorySaver`, no vector-memory hook, and no image, voice, email, search, task-management, or other side-effect Tools. Use an execution-specific thread identifier only for tracing, not conversation continuity.

- [ ] **Step 6: Implement immediate deterministic fallback**

  If the dedicated Agent throws, returns blank text, or exceeds the allowed message length after normalization, format the already obtained `WeatherReport` using the template. Never ask the Agent to invent missing values.

- [ ] **Step 7: Run focused and full tests**

  Run: `mvnw.cmd -Dtest=ScheduledContentPromptFactoryTest,ScheduledContentAgentTest,WeatherMessageTemplateFormatterTest,AIServiceMemoryTest test`

  Expected: PASS.

  Run: `mvnw.cmd test`

  Expected: PASS.

- [ ] **Step 8: Commit scheduled content generation**

  Commit message: `feat: generate scheduled weather messages`

---

### Task 7: Add the iLink push gateway with observable outcomes

**Files:**
- Create: `src/main/java/com/demo/demo/Service/scheduling/push/MessagePushGateway.java`
- Create: `src/main/java/com/demo/demo/Service/scheduling/push/PushRequest.java`
- Create: `src/main/java/com/demo/demo/Service/scheduling/push/PushResult.java`
- Create: `src/main/java/com/demo/demo/Service/scheduling/push/PushFailureCode.java`
- Create: `src/main/java/com/demo/demo/Service/scheduling/push/ILinkMessagePushGateway.java`
- Modify: `src/main/java/com/demo/demo/Service/BotInstance.java`
- Modify: `src/main/java/com/demo/demo/Service/MultiBotManager.java`
- Test: `src/test/java/com/demo/demo/Service/scheduling/push/ILinkMessagePushGatewayTest.java`
- Modify test: `src/test/java/com/demo/demo/Service/ILinkSessionLifecycleTest.java`
- Modify test: `src/test/java/com/demo/demo/Service/ServerLogPrivacyTest.java`

**Interfaces:**
- `MessagePushGateway.pushText(PushRequest)` returns `PushResult`.
- `PushRequest` contains target ID, task key, execution ID, and text; it does not expose a context token.
- `PushResult` distinguishes `SUCCESS`, `BOT_OFFLINE`, `TARGET_INVALID`, `RATE_LIMITED`, and `SDK_ERROR`, and marks failures transient or terminal.
- `MultiBotManager.findByBotKey(botKey)` resolves the persistent Bot route.
- `BotInstance` exposes a text-send operation that reports success or a normalized failure without logging the message or token.

- [ ] **Step 1: Write failing gateway tests**

  Cover correct Bot selection, successful send, missing Bot, logged-out Bot, invalid target, SDK exception, masked logs, and ensuring the target’s token is decrypted only immediately before iLink invocation.

- [ ] **Step 2: Run focused tests**

  Run: `mvnw.cmd -Dtest=ILinkMessagePushGatewayTest,ILinkSessionLifecycleTest,ServerLogPrivacyTest test`

  Expected: FAIL because the gateway and observable Bot send result do not exist.

- [ ] **Step 3: Add a stable Bot lookup**

  Map `default` to the primary Bot instance. Do not use the transient `bot-001` UI ID as the persisted routing key.

- [ ] **Step 4: Add an observable send path**

  Preserve the existing immediate-reply API for compatibility, but implement it through a lower-level operation that returns a normalized result. The gateway uses that result to decide retryability.

- [ ] **Step 5: Keep SDK details inside the gateway/Bot boundary**

  Do not expose `LoginCredentials`, decrypted context tokens, or `ILinkClient` to scheduler or application services.

- [ ] **Step 6: Run focused and full tests**

  Run: `mvnw.cmd -Dtest=ILinkMessagePushGatewayTest,ILinkSessionLifecycleTest,ServerLogPrivacyTest test`

  Expected: PASS.

  Run: `mvnw.cmd test`

  Expected: PASS.

- [ ] **Step 7: Commit push routing**

  Commit message: `feat: route scheduled pushes through iLink`

---

### Task 8: Implement durable scanning, execution, retry, and recovery

**Files:**
- Create: `src/main/java/com/demo/demo/config/SchedulingConfiguration.java`
- Create: `src/main/java/com/demo/demo/Service/scheduling/scheduler/ScheduledTaskScanner.java`
- Create: `src/main/java/com/demo/demo/Service/scheduling/application/ScheduledTaskExecutionService.java`
- Test: `src/test/java/com/demo/demo/Service/scheduling/scheduler/ScheduledTaskScannerTest.java`
- Test: `src/test/java/com/demo/demo/Service/scheduling/application/ScheduledTaskExecutionServiceTest.java`

**Interfaces:**
- `ScheduledTaskScanner.scan()` materializes due executions, dispatches eligible pending/retry rows, and recovers expired leases.
- `ScheduledTaskExecutionService.execute(executionId)` performs claim, weather query, content generation, push, and final transition.
- The scanner uses the configured bounded `ThreadPoolTaskExecutor`; no remote call runs on the scheduler thread.

- [ ] **Step 1: Write failing scanner tests**

  Cover disabled scheduling, due batch limit, one execution per scheduled instant, next-run advancement, 30-minute late grace, old occurrence skip, retry eligibility, bounded executor rejection, and stale `RUNNING` recovery.

- [ ] **Step 2: Write failing execution tests**

  Cover successful weather/text push, formatting fallback, weather transient retry, Bot-offline retry, SDK transient retry, invalid target terminal failure, maximum attempts, one task failure not blocking another, and lost claim causing no side effect.

- [ ] **Step 3: Run focused tests**

  Run: `mvnw.cmd -Dtest=ScheduledTaskScannerTest,ScheduledTaskExecutionServiceTest test`

  Expected: FAIL because scanner and execution service do not exist.

- [ ] **Step 4: Configure bounded execution**

  Enable Spring scheduling in `SchedulingConfiguration`, name the worker threads, set the exact worker and queue limits from properties, and define rejection behavior that leaves the durable execution eligible for a later scan.

- [ ] **Step 5: Implement scan ordering**

  On each scan: recover expired leases; materialize due task executions; fetch eligible `PENDING`/`RETRY` rows; submit IDs only. Do not load decrypted tokens or message content on the scheduler thread.

- [ ] **Step 6: Implement execution ordering**

  Claim first; load task/target; query `WeatherService` using `WeatherQuery(location, "今天")`; generate content; push; then mark success. Map failures to retry or terminal state using typed error categories.

- [ ] **Step 7: Implement crash-safe state rules**

  A crash after execution materialization leaves a `PENDING` row. A crash after claim leaves `RUNNING`, which becomes retryable after the lease timeout. A completed unique execution is never recreated for the same scheduled instant.

- [ ] **Step 8: Run focused and full tests**

  Run: `mvnw.cmd -Dtest=ScheduledTaskScannerTest,ScheduledTaskExecutionServiceTest test`

  Expected: PASS.

  Run: `mvnw.cmd test`

  Expected: PASS.

- [ ] **Step 9: Commit scheduling execution**

  Commit message: `feat: execute durable weather schedules`

---

### Task 9: Add safe task visibility and operational health

**Files:**
- Modify: `src/main/java/com/demo/demo/controller/BotHealthController.java`
- Create: `src/main/java/com/demo/demo/controller/dto/SchedulingHealthResponse.java`
- Create: `src/main/java/com/demo/demo/Service/scheduling/application/SchedulingHealthService.java`
- Modify: `src/main/java/com/demo/demo/controller/BotAdminAuthConfig.java`
- Test: `src/test/java/com/demo/demo/controller/SchedulingHealthControllerTest.java`
- Test: `src/test/java/com/demo/demo/Service/scheduling/application/SchedulingHealthServiceTest.java`
- Modify test: `src/test/java/com/demo/demo/controller/BotAdminAuthTest.java`
- Modify test: `src/test/java/com/demo/demo/Service/ServerLogPrivacyTest.java`

**Interfaces:**
- Task and execution repositories provide aggregate count, oldest eligible execution instant, and next due task instant queries without returning payload or target rows.
- `SchedulingHealthService.snapshot()` combines repository aggregates with the worker queue depth.
- Admin health exposes enabled state, counts by task/execution status, oldest pending age, next due instant, and worker queue depth.
- Admin output never exposes `userId`, context token, payload JSON, generated message, provider response, or credentials.
- User task management remains available through Agent Tools; no unauthenticated REST mutation endpoint is added.

- [ ] **Step 1: Write failing health and authorization tests**

  Verify safe aggregate fields, empty database behavior, protected endpoint behavior, and absence of sensitive identifiers or message content.

- [ ] **Step 2: Run focused tests**

  Run: `mvnw.cmd -Dtest=SchedulingHealthServiceTest,SchedulingHealthControllerTest,BotAdminAuthTest,ServerLogPrivacyTest test`

  Expected: FAIL because scheduling health output does not exist.

- [ ] **Step 3: Add aggregate-only health reporting**

  Add aggregate repository queries and combine them in `SchedulingHealthService`; the Controller only maps the returned snapshot. Query counts and ages without selecting encrypted tokens or payload bodies. Reuse existing admin authentication rules and response conventions.

- [ ] **Step 4: Run focused and full tests**

  Run: `mvnw.cmd -Dtest=SchedulingHealthServiceTest,SchedulingHealthControllerTest,BotAdminAuthTest,ServerLogPrivacyTest test`

  Expected: PASS.

  Run: `mvnw.cmd test`

  Expected: PASS.

- [ ] **Step 5: Commit operational visibility**

  Commit message: `feat: expose scheduling health`

---

### Task 10: Verify end-to-end behavior and document the iLink gate

**Files:**
- Create: `src/test/java/com/demo/demo/Service/scheduling/ScheduledWeatherPushIntegrationTest.java`
- Modify: `src/main/resources/application-local.example.yml`
- Create: `docs/scheduled-message-manual-test-guide.md`
- Modify: `docs/FEATURE_MATRIX.md`
- Modify: `docs/CURRENT_STATE.md`

**Interfaces:**
- Integration test owns a temporary SQLite file, mocked weather provider/model/iLink boundary, fixed clock, and synchronous test executor.
- Manual guide records the required real-device iLink checks and the decision outcome for context-token longevity.

- [ ] **Step 1: Write the failing end-to-end integration test**

  Drive the trusted inbound handler with “每天8点发送杭州天气”, simulate the Tool call through the registered Agent boundary, assert the SQLite task, advance the fixed clock, scan, execute, and verify one send through the correct Bot and token without checking token plaintext in logs.

- [ ] **Step 2: Add restart and duplicate scenarios**

  Close and recreate repositories against the same temporary SQLite file; verify the task survives. Run the same scheduled instant twice and verify one execution/send. Simulate a stale running row and verify recovery.

- [ ] **Step 3: Run the focused integration test**

  Run: `mvnw.cmd -Dtest=ScheduledWeatherPushIntegrationTest test`

  Expected before final wiring: FAIL at the first missing or incorrectly connected boundary.

- [ ] **Step 4: Complete only missing wiring exposed by the test**

  Limit changes to dependency injection and lifecycle wiring. Do not add new features or broaden recurrence/task types.

- [ ] **Step 5: Write the manual iLink test matrix**

  Include: immediate push; push after one hour; push after crossing midnight; push after Bot relogin; push after application restart; old-token failure followed by user message and refreshed-token success; paused task; deleted task; offline Bot retry; and confirmation that WeChat platform rules permit the chosen proactive send.

- [ ] **Step 6: Run full automated verification**

  Run: `mvnw.cmd test`

  Expected: PASS.

  Run: `mvnw.cmd clean package`

  Expected: BUILD SUCCESS with all tests passing.

- [ ] **Step 7: Run the real-device acceptance checks**

  Record each check as pass/fail with timestamp and environment, without copying tokens, message bodies, media parameters, or provider responses into the document.

- [ ] **Step 8: Enforce the delivery-channel decision gate**

  If a context token cannot support next-day proactive sends, do not mark the feature production-ready. Keep the scheduling/domain/application layers and replace only `ILinkMessagePushGateway` with a platform-supported delivery adapter in a separately approved plan.

- [ ] **Step 9: Update project documentation**

  Mark automated features separately from deferred real-WeChat validation. Document SQLite file location, backup implications, scheduling configuration, retry behavior, and the single-instance/single-default-Bot limit.

- [ ] **Step 10: Commit the verified MVP**

  Commit message: `docs: verify scheduled weather push`

---

## Test Matrix

| Area | Required behaviors |
|---|---|
| Intent routing | Explicit recurrence creates; one-off future weather query does not |
| Authorization | Target ownership is server-derived through ToolContext; cross-user management is denied |
| Time | Before/after execution minute, UTC conversion, zone validation, boundary dates |
| SQLite | Idempotent schema, WAL, unique execution, conditional claim, restart persistence |
| Security | AES-GCM token storage, tamper rejection, no plaintext/log leakage |
| Task lifecycle | Create, deduplicate, list, pause, resume, delete, active-task limit |
| Content | Real weather input, concise Agent output, deterministic LLM fallback |
| Delivery | Correct stable Bot, offline/transient/terminal outcomes, target refresh |
| Reliability | Retry delays, max attempts, stale lease recovery, late-run grace |
| Isolation | Scheduled work does not modify chat MemorySaver or VectorMemoryStore |
| End to end | Natural language to Tool to SQLite to scheduler to Agent to iLink |

## Manual Release Gate

Production enablement requires explicit evidence that the iLink SDK and WeChat account permit the scheduled proactive send:

1. The stored context token remains usable at the next day’s scheduled time.
2. The token remains correctly associated with the stable Bot login.
3. A Bot relogin has defined behavior for existing targets.
4. The account is permitted to initiate the message under WeChat platform rules.
5. A send timeout’s duplicate-delivery behavior is understood and accepted.

Until these checks pass, ship scheduling disabled by default and label the feature “automated tests complete; real WeChat delivery pending”.

## Plan Self-Review

- Spec coverage: creation, persistence, trigger, Agent content, iLink delivery, management, security, retries, restart recovery, and manual platform verification are each assigned to a task.
- Scope: one task type, one daily recurrence, text delivery, one application instance, and one stable default Bot.
- SQLite consistency: all instants are integer UTC milliseconds, payloads/tokens are text, uniqueness and conditional updates replace server-database locking.
- Type consistency: the same `InboundMessageContext`, target ID, public task key, execution ID, `WeatherReport`, `PushRequest`, and `PushResult` boundaries are used throughout.
- Security consistency: target ownership never comes from model parameters; `ToolContext` carries only a target ID and Bot key; decrypted tokens exist only at the gateway boundary.
- No implementation is authorized by this document alone; execution begins only after the user selects an execution workflow.
