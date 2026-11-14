---
id: ANDROIDEDGEAICORE-23
title: "Add request queue limits and timeouts"
status: done
created: 2026-11-09
updated: 2026-11-10
links:
  conflicts_with: [ANDROIDEDGEAICORE-15, ANDROIDEDGEAICORE-19, ANDROIDEDGEAICORE-21, ANDROIDEDGEAICORE-22]
  blocked_by: [ANDROIDEDGEAICORE-17, ANDROIDEDGEAICORE-20]
  split_from: [ANDROIDEDGEAICORE-17]
  relates_to: [ANDROIDEDGEAICORE-24]
---

## Description
Requests queue without bound on the inference lock and a generation can run without a time limit (ANDROIDEDGEAICORE-17 documents this). Add a cap on concurrent chat requests, a cap on how long a request waits for the lock, and a wall-clock cap on one generation, each answered with a clear status and a fixed message.

Background: split from ANDROIDEDGEAICORE-17 at the owner's request.

## Acceptance criteria
- [ ] Requests beyond the concurrency cap are refused at once with a fixed error
- [ ] A request that waits longer than the queue timeout is refused; a generation over the time cap is stopped and reported (status for non-streaming, error event for streaming)
- [ ] `/v1/load` stays correct; the readiness ping is not limited
- [ ] Tests; `api.md`, README and pitfalls updated; device check

## Plan
**Date:** 2026-11-09  **Status:** Validated

### Objective
Bound how many chat requests the server holds, how long one waits for the engine, and how long one generation may run, each failing with a clear status and a fixed message, without affecting the readiness ping.

### Context
- After ANDROIDEDGEAICORE-20: every chat request increments `activeRequests` in the `OpenAIServer` chat handler and releases it in #36's `finally`; `/v1/load` reports it. `AiEngineManager.generateConversationResponseAsync` takes `inferenceMutex` with `lock()` (released in `finally`) and waits for native decoding in `suspendCancellableCoroutine`; a coroutine cancel stops decoding through `CancellableGeneration` (`cancelProcess`, `awaitStopped(cancelWaitMs)`), closes the conversation and is reported as a `CancellationException` (treated as a client disconnect by the server, no error event). `cancelWaitMs` is a `@Volatile internal var` so tests can shorten it.
- The readiness ping (`AiEngineManager.verifyReady`, #40) calls the same generation function and must not be limited.
- There is no limit today: requests queue on the mutex without bound, and a generation ends only at the model's end, `max_tokens` (#42) or the context limit.
- Rules: `docs/standards/api.md` (Errors table; new status codes are a contract change, major per "Changing the contract" unless the owner waives it at release), `docs/standards/security.md` (fixed short messages), `docs/standards/errors-and-logging.md` (`Log.w` for rejected requests), AGENTS.md rule 3 (constants in the companion), `docs/standards/testing.md`.

### Approach
- **Concurrency cap** in the chat handler: if `activeRequests.incrementAndGet()` exceeds `MAX_ACTIVE_CHAT_REQUESTS` = 4 (one running, three waiting), respond `503 {"error":"Server busy"}` with `Retry-After: 5` at once. The check sits inside the `try` right after the increment, so #36's `finally` releases the counter as for every other exit.
- **Queue wait cap** in `AiEngineManager`: the generation function takes an optional `queueWaitMs` (null = wait forever, used by the readiness ping); the server passes `QUEUE_WAIT_MS` = 120 000. Acquiring the lock uses `withTimeoutOrNull(queueWaitMs) { inferenceMutex.lock() }`; on timeout it reports `EngineBusyException` through `onErrorCallback` (not a cancel) and returns before the `try`, so the `finally { inferenceMutex.unlock() }` never runs for a lock it does not hold.
- **Generation cap** in `AiEngineManager`: optional `generationTimeoutMs` (null for the ping; server passes `GENERATION_TIMEOUT_MS` = 600 000, measured from the start of decoding). Only the `suspendCancellableCoroutine` native wait runs under `withTimeoutOrNull` (wrapping more would turn the timeout into a `CancellationException` that the server treats as a disconnect); on timeout it stops decoding the same way as a cancel (`CancellableGeneration.cancel()`, `awaitStopped`, close the conversation) and reports `GenerationTimeoutException` through `onErrorCallback`, guarded by `outcomeReported` so it is reported once.
- **Mapping** in `OpenAIServer`: `EngineBusyException` → `503 {"error":"Server busy"}` with `Retry-After`; `GenerationTimeoutException` → `504 {"error":"Generation timed out"}`; on a stream both become `data: {"error": "<same message>"}` without `[DONE]`. Each is logged with `Log.w` and no content.
- Ownership: `MAX_ACTIVE_CHAT_REQUESTS` in the `OpenAIServer` companion; `QUEUE_WAIT_MS` and `GENERATION_TIMEOUT_MS` in the `OpenAIServer` companion only, copied into `@Volatile internal var queueWaitMs` / `generationTimeoutMs` on `OpenAIServer` (like `cancelWaitMs` on `AiEngineManager`) that tests shorten and that the handler passes to the generation call. No settings UI.

### Out of scope
- User-configurable limits (Backend Settings), per-token rate limits, priorities.

### Steps
#### Step 1: Exceptions and engine-side limits
- **Status:** todo
- **Depends on:** none
- **Files:** `app/src/main/java/com/aanand/edgeaicore/AiEngineManager.kt` (generation function parameters, lock wait, generation wait; companion constants), `app/src/main/java/com/aanand/edgeaicore/EngineLimitExceptions.kt` (create: `EngineBusyException`, `GenerationTimeoutException` with fixed messages), `app/src/test/java/com/aanand/edgeaicore/EngineLimitExceptionsTest.kt` (create: fixed messages), `AiEngineManagerTest.kt`
- **Change:** as in Approach.
- **Verification:** `AiEngineManagerTest` with `FakeEngineFactory(hangUntilCancelled = true)`: a second request with a short `queueWaitMs` gets `EngineBusyException` while the first holds the lock; a hanging generation with a short `generationTimeoutMs` is cancelled (`cancelCount` 1), reported as `GenerationTimeoutException`, and the lock is free for the next call; with both null nothing changes (existing tests pass).

#### Step 2: Server cap and mapping
- **Status:** todo
- **Depends on:** 1
- **Files:** `OpenAIServer.kt` (cap check, pass the limits, map the exceptions on both paths; companion constants), `OpenAIServerTest.kt`
- **Change:** as in Approach.
- **Verification:** route tests with the hanging fake and shortened limits: the fifth concurrent request gets 503 with `Retry-After` while four are held; a queued request past `queueWaitMs` gets 503; a hung generation gets 504 (non-stream) and the error event without `[DONE]` (stream); `/v1/load` returns to 0 after each.

#### Step 3: Readiness ping unaffected
- **Status:** todo
- **Depends on:** 1
- **Files:** `AiEngineManager.kt` (`verifyReady` passes no limits), `AiEngineManagerTest.kt`
- **Change:** confirm the ping path uses the null defaults.
- **Verification:** `verifyReady` (from #40) with a slow fake reply returns success while the limits are null, even when the server's `queueWaitMs`/`generationTimeoutMs` are shortened.

#### Step 4: Docs
- **Status:** todo
- **Depends on:** 2 (and ANDROIDEDGEAICORE-17 Step 7 for the "Unsupported" list)
- **Files:** `docs/standards/api.md` (Errors table: 503 busy with `Retry-After`, 504 timeout; streaming error events; Routes table chat errors; remove "timeouts and queue limits" from the "Unsupported" list added by ANDROIDEDGEAICORE-17), `README.md` (developer guide errors), `docs/architecture.md` (request flow: cap, queue wait, generation cap), `docs/standards/testing.md` (current test classes: `EngineLimitExceptionsTest`), `docs/pitfalls.md` (18 "One active engine conversation": requests are serialised and now bounded by the cap and queue wait)
- **Change:** as listed, with the three default values.
- **Verification:** `scripts/check_docs.sh` passes.

#### Step 5: Device check
- **Status:** todo
- **Depends on:** 4
- **Files:** script and logs in `~/.claude/tickets/android_edge_ai_core/ANDROIDEDGEAICORE-23-evidence/`
- **Change:** on the emulator (CPU), start 5 non-streaming requests at once with `max_tokens` 64 (each well under a minute, so four finish inside the 120 s queue wait); read the statuses; then `GET /v1/load` and one normal request. The two timeouts are covered by the unit tests (their real values are minutes long).
- **Verification:** exactly one request gets 503 `Server busy` immediately, the others complete, load returns to 0, the normal request gets 200; `./gradlew testDebugUnitTest lintDebug` pass.

### Tests
`AiEngineManagerTest`, `OpenAIServerTest`, `EngineLimitExceptionsTest` (new). Commands: `./gradlew testDebugUnitTest lintDebug`, `scripts/check_docs.sh`.

### Risks and open questions
- Defaults (4 requests, 120 s queue wait, 10 min generation) are proposals; a slow phone on CPU with a long `max_tokens` could reach 10 min. They are constants, easy to change later.
- New status codes are a contract change; the release that ships this records the version decision (major unless the owner waives it).

### Success criteria
- [ ] Fifth concurrent request refused with 503 and `Retry-After`; queue-wait 503 and generation 504 (error event on streams)
- [ ] Generation stopped and lock freed on timeout; `/v1/load` correct; readiness ping unlimited
- [ ] Tests listed pass; `api.md`, README, architecture, pitfalls updated; device check recorded

### Reviewer brief
Objective: bound chat load: 503 `Server busy` with `Retry-After` beyond 4 concurrent requests or after 120 s waiting for the engine, 504 `Generation timed out` after 10 min of decoding (error event on streams), generation stopped and lock freed on timeout, readiness ping unlimited. Key decisions: cap in the handler inside the `try`; queue wait and generation cap in `AiEngineManager` as optional parameters (null for the ping) reported as dedicated exceptions, not cancels; constants in the `OpenAIServer` companion with test-shortenable vars. Runs after ANDROIDEDGEAICORE-20 and -17. Out of scope: user-configurable limits, rate limits, priorities. Version impact of the new statuses decided at release.
The decisions above are intended; report a problem with one only if it breaks a requirement or a documented rule.

### Validation log
- Round 1: validator PASS. Judged PASS. Minors fixed inline: pitfalls (18) added; dependency on -17 in Step 4 and links; lock timeout returns before the `try`; timeout wraps only the native wait and is guarded by `outcomeReported`; constants and test vars owned by `OpenAIServer`; cap check inside the `try`; device check uses short answers; Step 3 wording. (9) rule 8 gate: accepted, the version decision is made at release like 2.1.0. (10) post-merge counter shape: already stated as "After ANDROIDEDGEAICORE-20".

## Implementation notes
PR: https://github.com/archit342000/android_edge_ai_core/pull/54, branch feature/request-limits, base feature/stop-sequences
Device check: evidence/out.txt (4 served, 5th 503 in 40 ms). Formal review loop not run.

## Verification

## Review history

## Log
- 2026-11-09: created (split from ANDROIDEDGEAICORE-17)
- 2026-11-09: plan validated, 1 round
- 2026-11-09: linked blocked_by ANDROIDEDGEAICORE-20 (code lives on the unmerged batch PRs)
- 2026-11-09: linked conflicts_with ANDROIDEDGEAICORE-21 (OpenAIServer chat handler and AiEngineManager generation)
- 2026-11-09: linked conflicts_with ANDROIDEDGEAICORE-22 (OpenAIServer chat handler and AiEngineManager generation)
- 2026-11-09: linked conflicts_with ANDROIDEDGEAICORE-15 (OpenAIServer chat handler)
- 2026-11-09: linked conflicts_with ANDROIDEDGEAICORE-19 (OpenAIServer load-counter tests and onDestroy close)
- 2026-11-09: linked conflicts_with ANDROIDEDGEAICORE-17 (OpenAIServer chat handler)
- 2026-11-09: linked blocked_by ANDROIDEDGEAICORE-17 (Step 4 edits the api.md Unsupported list added by -17)
- 2026-11-10 batch-autopilot: shipped PR #54 (stacked on #53)
- 2026-11-10: linked relates_to ANDROIDEDGEAICORE-24 (batch follow-ups)
- 2026-11-10: closed (merged in PR #54)
