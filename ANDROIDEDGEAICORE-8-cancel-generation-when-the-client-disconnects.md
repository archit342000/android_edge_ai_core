---
id: ANDROIDEDGEAICORE-8
title: "Cancel generation when the client disconnects"
status: done
created: 2026-11-09
updated: 2026-11-09
links:
  blocked_by: [ANDROIDEDGEAICORE-3]
  conflicts_with: [ANDROIDEDGEAICORE-4, ANDROIDEDGEAICORE-9]
  relates_to: [ANDROIDEDGEAICORE-17, ANDROIDEDGEAICORE-18, ANDROIDEDGEAICORE-19, ANDROIDEDGEAICORE-20]
  split_from: [ANDROIDEDGEAICORE-2]
---

## Description
A dropped streaming client keeps decoding and holds `inferenceMutex`, blocking other requests (gap #6). Detect the disconnect and call `cancelProcess()`, then free the slot.

Background: research and gap analysis in ANDROIDEDGEAICORE-2 (LiteRT-LM v0.19.1). Verify with `scripts/device.sh` (`docs/testing-on-device.md`).

## Acceptance criteria
- [x] Closing a streaming request mid-generation stops decoding within about 1 s (logs show the cancel)
- [x] The next request runs immediately afterwards
- [x] `/v1/load` returns to 0

## Plan
**Date:** 2026-11-09  **Status:** Validated

### Objective
When a streaming client disconnects, stop decoding with `cancelProcess()` within about a second, release `inferenceMutex`, and decrement the load counter, so the next request runs at once. Runs after ANDROIDEDGEAICORE-3.

### Context
- Current code (`OpenAIServer` `/chat/completions`, `stream == true`): inside `respondTextWriter`, inference runs in a detached `CoroutineScope(Dispatchers.IO).launch` that pushes chunks into a `Channel`; the writer loops `write(msg); flush()`. A write to a closed socket throws in the writer, but nothing cancels the detached job, so decoding continues while holding `inferenceMutex`; `activeRequests` is decremented only from `onComplete`/`onErrorCallback`.
- `AiEngineManager.generateConversationResponseAsync`: `suspendCancellableCoroutine` with `invokeOnCancellation { conversation.close() }` (closing during native generation), resumed by `MessageCallback.onDone`/`onError`.
- 0.19.1 `Conversation.cancelProcess()`: "Cancels any ongoing inference process"; async errors arrive in `onError`, with `CancellationException` on cancel; internal state is not rolled back.
- Rules: `docs/standards/kotlin.md` (no new ad-hoc `CoroutineScope(Dispatchers.IO)`; bridge callbacks with `suspendCancellableCoroutine` and handle cancellation; engine calls inside the mutex), `docs/standards/errors-and-logging.md` (do not swallow `CancellationException`; exactly one of `onComplete`/`onErrorCallback`).

### Approach
- Streaming: replace the detached scope with `coroutineScope { val job = launch { generate... } ; try { for (msg in channel) { write; flush } } catch (e: IOException) { Log.w; job.cancel() } }` inside `respondTextWriter`, so the job is a child of the call and is cancelled when the write fails or the call is cancelled.
- Load counter: one `try { ... } finally { activeRequests.decrementAndGet() }` wraps the whole handler body after `incrementAndGet()`, for both paths; every other decrement (the SSE callbacks, the streaming launch `catch`, the non-streaming `finally` and the outer "safety decrement") is removed, so each request decrements exactly once.
- Exactly-once reporting: the outer `catch (e: Exception)` in `generateConversationResponseAsync` gains a preceding `catch (e: CancellationException) { throw e }`, so a cancel that was already reported is not reported again.
- In the launched child, a `catch (e: CancellationException) { throw e }` comes before its `catch (e: Exception)`, so a disconnect is not logged or sent as an error. Errors before the first token (for example "Model not loaded", thrown outside the inner `try`) are caught inside the launched child, logged with `Log.e`, sent as the usual `data: {"error": ...}` event and the stream closed, as `api.md` describes.
- Engine: `invokeOnCancellation` calls `conversation.cancelProcess()` (not `close()`), guarded by `isAlive` (`cancelProcess` throws `IllegalStateException` on a closed conversation). The `suspendCancellableCoroutine` resumes at once on cancellation, so the wait is a separate step: in the `catch (e: CancellationException)` after it, still inside `inferenceMutex.withLock`, `withContext(NonCancellable)` awaits a `CompletableDeferred` completed by the native `onDone`/`onError`, bounded by `CANCEL_WAIT_MS` (2 s). The mutex is released only after native decoding has stopped. Report the cancel through `onErrorCallback(CancellationException)` once, then rethrow. Close the conversation afterwards through `closeAllConversations()` (which also clears `activeEngineConversation`/`activeConversationId`), since its KV state holds a partial reply; if ANDROIDEDGEAICORE-4 has landed, that means "do not return it to the cache".
- Log `Log.i(TAG, "Generation cancelled (client disconnected)")` in the server and the elapsed cancel time in the engine.
Rejected: polling the socket during generation (Ktor exposes no cheap check; the next write fails within one token).

### Out of scope
Non-streaming disconnects (Ktor 2 with Netty does not reliably cancel the handler while it waits; noted under Risks), request timeouts, queue limits.

### Steps
#### Step 1: Cancellation in the engine
- **Status:** todo
- **Depends on:** none
- **Files:** `app/src/main/java/com/aanand/edgeaicore/CancellableGeneration.kt` (create: the cancel-then-await helper, taking `cancel: () -> Unit` and the terminal `CompletableDeferred`), `app/src/main/java/com/aanand/edgeaicore/AiEngineManager.kt` (modify: the `suspendCancellableCoroutine` block in `generateConversationResponseAsync` uses the helper; `CANCEL_WAIT_MS` in `companion object`), `app/src/main/java/com/aanand/edgeaicore/LlmEngine.kt` (modify: add `cancelProcess()` to `LlmConversation` and its adapter)
- **Change:** as in Approach.
- **Verification:** `./gradlew assembleDebug`.

#### Step 2: Streaming handler
- **Status:** todo
- **Depends on:** 1
- **Files:** `app/src/main/java/com/aanand/edgeaicore/OpenAIServer.kt` (modify: the `stream == true` branch, including the launched child's `CancellationException` rethrow, plus the counter changes from Approach: the single `try/finally` around the handler and removal of the non-streaming `finally` decrement and the outer "safety decrement")
- **Change:** as in Approach. Do not refactor the per-call `GsonBuilder().create()` here.
- **Verification:** `./gradlew assembleDebug testDebugUnitTest lintDebug`.

#### Step 3: Unit tests
- **Status:** todo
- **Depends on:** 1, 2
- **Files:** `app/src/test/java/com/aanand/edgeaicore/CancellableGenerationTest.kt` (create), `app/src/test/java/com/aanand/edgeaicore/AiEngineManagerTest.kt` (modify), `app/src/test/java/com/aanand/edgeaicore/OpenAIServerTest.kt` (modify)
- **Change:** `CancellableGenerationTest` with a fake callback source: cancelling calls `cancel` once, waits for the terminal callback, times out after the bound, and never calls both completion paths (`runTest`, virtual time; `kotlinx-coroutines-test` is added by ANDROIDEDGEAICORE-3). `AiEngineManagerTest`: cancelling the calling coroutine calls the fake's `cancelProcess` once (not `close` during generation), skips it on a closed fake, and frees the mutex so a second generation runs. `AiEngineManagerTest` also checks that a cancel calls `onErrorCallback` exactly once. `OpenAIServerTest`: after a streamed request and an unparsable-body 400, `/v1/load` returns 0; a stream with no model loaded sends the error event without `[DONE]`. A client disconnect cannot be simulated reliably in `testApplication`; that path is covered by the step 4 device check.
- **Verification:** `./gradlew testDebugUnitTest` passes.

#### Step 4: Device check (emulator, CPU)
- **Status:** todo
- **Depends on:** 2, 3
- **Files:** none (evidence in `~/.claude/tickets/android_edge_ai_core/ANDROIDEDGEAICORE-8-evidence/`)
- **Change:** start a long streamed request ("write 500 words ...") with `curl -N` through the forward, kill curl after a few chunks, immediately send a short chat and poll `GET /v1/load`.
- **Verification:** logcat shows the cancel within about 1 s of the kill; the next request returns 200 without waiting for the long one; `/v1/load` returns `{"load": 0}` afterwards; a normal streamed request still ends with `data: [DONE]`.

#### Step 5: Docs
- **Status:** todo
- **Depends on:** 4
- **Files:** `docs/standards/api.md` (Responses section, streaming paragraph: client disconnect stops generation), `README.md` (Best Practices: same)
- **Change:** one sentence each.
- **Verification:** `scripts/check_docs.sh` passes.

### Tests
`CancellableGenerationTest` (new); `AiEngineManagerTest` and `OpenAIServerTest` extended through the engine seam and Ktor test host from ANDROIDEDGEAICORE-3. Run `./gradlew testDebugUnitTest lintDebug`, `scripts/check_docs.sh`; device check in step 4.

### Risks and open questions
- If `cancelProcess()` does not stop decoding promptly on CPU, the wait bound expires and the mutex is held up to 2 s longer; record the measured time.
- A disconnect is noticed only at the next write, so a client that drops during prefill (before the first token) is detected when the first token is written.
- Non-streaming requests from a dropped client still run to completion; a follow-up ticket if it matters.
- Conflicts with ANDROIDEDGEAICORE-4 and -9 in the same function; land in ticket order and rebase.

### Success criteria
- [ ] Closing a stream mid-generation stops decoding within about 1 s (logs)
- [ ] The next request runs immediately
- [ ] `/v1/load` returns 0
- [ ] Tests, lint, docs checks pass

### Reviewer brief
- **Objective:** when a streaming client disconnects, stop decoding with `cancelProcess()` within about 1 s, free `inferenceMutex`, and bring `/v1/load` back to 0.
- **Key decisions:** the generation job is a child of the call (`coroutineScope` + `launch`); a failed write cancels it; one `try/finally` decrement per request; `invokeOnCancellation` calls `cancelProcess()` (guarded by `isAlive`), then waits via `CancellableGeneration` (`NonCancellable`, `CANCEL_WAIT_MS` 2 s) inside the mutex for the native terminal callback; `CancellationException` is rethrown ahead of every `catch (Exception)` so a cancel is reported exactly once; the cancelled conversation is closed through `closeAllConversations()` (not cached once ticket 4 lands).
- **Out of scope:** non-streaming disconnects, request timeouts, queue limits, the Gson refactor.
- **Success criteria:** cancel within ~1 s in the logs; the next request runs immediately; `/v1/load` is 0; tests (`CancellableGenerationTest`, `AiEngineManagerTest`, `OpenAIServerTest`) and checks pass.

The decisions above are intended; report a problem with one only if it breaks a requirement or a documented rule.

### Validation log
- **Round 1:** validator FAIL. My judgement: FAIL, with 1 valid BLOCKING issue: no tests for the changed classes. The owner chose engine seam + Ktor tests; cases were added. MINOR (helper location, await placement and the `isAlive` guard, counter decrements, api.md section name, Gson tidy-up dropped, prefill gap): all valid and fixed.
- **Round 2 (diff-scoped):** validator FAIL. My judgement: FAIL, with 1 valid BLOCKING issue: the outer `catch (Exception)` would report a cancel twice (checked `AiEngineManager.kt`). Rethrow added. MINOR (Gson wording, pre-token errors, `closeAllConversations`, step 3 dependency, untestable disconnect stated): fixed.
- **Round 3 (diff-scoped):** validator FAIL. My judgement: FAIL, with 1 valid BLOCKING issue: the launched child's `catch (Exception)` at `OpenAIServer.kt` line 122 would swallow `CancellationException`. Rethrow added. MINOR (step 2 file list for the counter changes): fixed.
- **Round 4 (diff-scoped):** validator PASS with 1 MINOR issue (name the child rethrow in step 2 Files). Valid, fixed inline.


## Implementation notes
### Autopilot run (2026-11-09)
- Mode: automatic (batch-autopilot, item 1/11)
- Branch: feature/cancel-on-client-disconnect  PR base: develop  Starting commit: 47ace0582824aecf89c09db3b0138e0dd5ed54ec
- Dirty before start: none
- Work files: app/src/main/java/com/aanand/edgeaicore/{CancellableGeneration(new),AiEngineManager,LlmEngine,OpenAIServer}.kt; app/src/test/.../{CancellableGenerationTest(new),AiEngineManagerTest,OpenAIServerTest,FakeLlmEngine}.kt; docs/standards/api.md; README.md
- Counters: fix 1/10, review 5/5, re-verify 1
- Last completed stage: 5 review iteration 1 resolved  Next: done (shipped)
- Hard blocker: none
- WIP commit: none
- Repo: /home/archit-anand/docker-data/shared/Coding/android_edge_ai_core

### Baseline (2026-11-09)
`./gradlew testDebugUnitTest`: all pass (before changes).

### Implementation progress
- [x] Step 1: Cancellation in the engine - CancellableGeneration.kt (new), LlmEngine.kt (cancelProcess), AiEngineManager.kt (cancel + await inside the mutex, CANCEL_WAIT_MS 2 s)
- [x] Step 2: Streaming handler - OpenAIServer.kt (child job in coroutineScope, IOException cancels it, single finally decrement)
- [x] Step 3: Unit tests - CancellableGenerationTest (5), AiEngineManagerTest (+2), OpenAIServerTest (+2), FakeLlmEngine (hangUntilCancelled)
- [ ] Step 4: Device check - done in verify
- [x] Step 5: Docs - api.md, README.md
- Deviation: late tokens/onDone/onError after a cancel are dropped in AiEngineManager so a cancel is reported exactly once.

### Smoke test (2026-11-09)
`./gradlew testDebugUnitTest lintDebug` pass (lint: no new issues); `scripts/check_docs.sh` OK.

### Fix loop
- Round 1 (V7, product defect): Ktor rethrows the failed write from `respondTextWriter`, so the outer `catch (Exception)` logged ERROR and tried a 400; and the engine's cancel log measured from request start. Fix: `catch (IOException)` around `respondTextWriter` in `OpenAIServer.kt`; `CancellableGeneration.msSinceCancel()` + test `msSinceCancelMeasuresFromTheFirstCancel`. Re-verified on the emulator: 0 ERROR lines.

### Autopilot decisions (2026-11-09)
- Stage 1: Plan already `Validated` (4 rounds); reused without re-planning.

PR: https://github.com/archit342000/android_edge_ai_core/pull/36, branch feature/cancel-on-client-disconnect, base develop

## Verification
### Verification plan (2026-11-09)
**Plan:** ticket Plan above  **Diff base:** 47ace0582824aecf89c09db3b0138e0dd5ed54ec (light form: ~120 changed source lines, 4 files)

**Scope:** streaming disconnect cancels decoding (`OpenAIServer.kt`, `AiEngineManager.kt`, `CancellableGeneration.kt`, `LlmEngine.kt`); load counter now decremented once in a `finally`.
**Environment and isolation:** dedicated emulator `claude_edgeaicore_api34` (read-only, no snapshot) via `scripts/device.sh up CPU`, host port 18080, Gemma 4 E2B on CPU. Token stays in `~/.cache/edgeaicore-device/token`, never printed. Teardown with `scripts/device.sh down`. Never touches the owner's AVDs or app data. Driver script: `ANDROIDEDGEAICORE-8-evidence/scripts/disconnect.sh` (curl through the forward; arguments: host port, token file).

| ID | Criterion | Scenario | Type | Method | Files | Expected | Evidence |
|----|-----------|----------|------|--------|-------|----------|----------|
| V1 | AC1 | Start a long streamed request, kill curl after a few chunks; read logcat | happy | API + logcat | OpenAIServer.kt, AiEngineManager.kt | "Generation cancelled" logged within about 1 s of the kill; no more tokens decoded | curl output, logcat with timestamps |
| V2 | AC2 | Send a short chat immediately after V1 | happy | API | AiEngineManager.kt | 200 without waiting for the long generation; time to answer is short | response, timing |
| V3 | AC3 | `GET /v1/load` after V1/V2 | happy | API | OpenAIServer.kt | `{"load":0}` | response |
| V4 | regression | Normal streamed request to the end; non-streaming chat | regression | API | OpenAIServer.kt | stream ends with `data: [DONE]`; non-stream 200 with text; load 0 | outputs |
| V5 | edge | Unparsable body (400), then load; three rapid disconnects in a row, then a chat | failure/edge | API | OpenAIServer.kt | 400; load 0 after each; chat still 200 | outputs |
| V6 | regression | `./gradlew testDebugUnitTest lintDebug`, `scripts/check_docs.sh` | regression | CLI | all | pass | output |
| V7 | quality | After a disconnect the log has no spurious "Error processing request" stack trace and the cancel log reports time since the cancel | failure | logcat | OpenAIServer.kt, AiEngineManager.kt | one INFO "Generation cancelled" line, no ERROR | logcat (added after V1 showed it) |

**Coverage:** AC1 V1; AC2 V2; AC3 V3, V5; changed files covered by V1-V6.


### Results (2026-11-09)
Commit verified: 47ace0582824aecf89c09db3b0138e0dd5ed54ec + uncommitted:
-` `README.md 6ec3ce3650d15e7d50db9296420395b0e5f9bda5
-` `app/src/main/java/com/aanand/edgeaicore/AiEngineManager.kt 15e18dcbfc04eef80eaa8f0de16acea97e43104c
-` `app/src/main/java/com/aanand/edgeaicore/LlmEngine.kt 6c3f05fb27cb6b7f25613f3024470bd707b0e8a4
-` `app/src/main/java/com/aanand/edgeaicore/OpenAIServer.kt 0adc9169a19fb1a202d6508eaec80b9abd58d824
-` `app/src/test/java/com/aanand/edgeaicore/AiEngineManagerTest.kt 979b864184064e43ff96506064ba13943ff538ea
-` `app/src/test/java/com/aanand/edgeaicore/FakeLlmEngine.kt 7d7ef68c44bfba28d12ef0d82ec8cd08b4f9dc6f
-` `app/src/test/java/com/aanand/edgeaicore/OpenAIServerTest.kt e6833001c89c7d5026954090fc3d3f35982ca9e3
-` `docs/standards/api.md c0c46fe873796ae29585b1dbf3db6a6863827540
-` `app/src/main/java/com/aanand/edgeaicore/CancellableGeneration.kt 0ded1eb97c6d938d4193b6a66afad3e91347ece5
-` `app/src/test/java/com/aanand/edgeaicore/CancellableGenerationTest.kt 53e1790bf63ae91693ee6c55f315746eb310d512

Emulator `claude_edgeaicore_api34`, CPU, Gemma 4 E2B. Evidence: `ANDROIDEDGEAICORE-8-evidence/` (`v1-run.txt`, `v1-logcat-full.txt`, `v4-v5-run.txt`, `v5-logcat.txt`, `v6-run.txt`, `scripts/disconnect.sh`).

| ID | Status | Evidence |
|----|--------|----------|
| V1 | PASS | After curl was killed at 5 chunks, logcat shows `OpenAIServer: Generation cancelled (client disconnected)` then `AiEngineManager: Generation cancelled ... (stopped=true)` 50 ms later. (Host and emulator clocks differ by about 0.1 s, so the kill-to-cancel gap is only known as under ~1 s: the next request returned within 0.9 s.) |
| V2 | PASS | Next chat returned HTTP 200 in 0.91 s while the old generation was cancelled. |
| V3 | PASS | `/v1/load` was 1 during the stream and 0 afterwards. |
| V4 | PASS | Stream ended with `data: [DONE]`; non-stream chat returned 200 "Hi!"; load 0. |
| V5 | PASS | Bad body gave 400 and load 0. Three disconnects in a row: chat afterwards 200 in 0.75 s, final load 0. (Load read straight after a disconnect still showed 1, because the cancel takes effect at the next token; it was 0 once the next request had run.) |
| V6 | PASS | `testDebugUnitTest lintDebug` pass, `check_docs` OK. |
| V7 | **FAIL** | Each disconnect logs `E OpenAIServer: Error processing request` with a `ChannelWriteException` stack trace (Ktor rethrows the failed write from `respondTextWriter` into the outer `catch (Exception)`, which then tries to answer 400 on a dead channel). The engine's "Generation cancelled after 1754 ms" measures from request start, not from the cancel. Repro: start a stream, kill curl, read logcat. |

Verdict: AC1-AC3 verified (PASS); 6 PASS, 1 FAIL (V7), 0 BLOCKED. Teardown: `device.sh down` run; forward removed.

### Results (re-run 1, 2026-11-09)
Commit verified: 47ace0582824aecf89c09db3b0138e0dd5ed54ec + uncommitted:
- `README.md` 6ec3ce3650d15e7d50db9296420395b0e5f9bda5
- `app/src/main/java/com/aanand/edgeaicore/AiEngineManager.kt` e9ca7b1946cf916eb098d7aa99d22d65aba4beb7
- `app/src/main/java/com/aanand/edgeaicore/LlmEngine.kt` 6c3f05fb27cb6b7f25613f3024470bd707b0e8a4
- `app/src/main/java/com/aanand/edgeaicore/OpenAIServer.kt` 971a1232d04fd1a10bacdb0f1179fd965ca815c0
- `app/src/test/java/com/aanand/edgeaicore/AiEngineManagerTest.kt` 979b864184064e43ff96506064ba13943ff538ea
- `app/src/test/java/com/aanand/edgeaicore/FakeLlmEngine.kt` 7d7ef68c44bfba28d12ef0d82ec8cd08b4f9dc6f
- `app/src/test/java/com/aanand/edgeaicore/OpenAIServerTest.kt` e6833001c89c7d5026954090fc3d3f35982ca9e3
- `docs/standards/api.md` c0c46fe873796ae29585b1dbf3db6a6863827540
- `app/src/main/java/com/aanand/edgeaicore/CancellableGeneration.kt` da4c8c52dfeac757a4900491fdda447585d2f474
- `app/src/test/java/com/aanand/edgeaicore/CancellableGenerationTest.kt` 3f603d2c22b02f17138e801a3e30d3eb0042f048

Fix for V7: `OpenAIServer.kt` catches the `IOException` Ktor rethrows from `respondTextWriter` (logged at DEBUG); `CancellableGeneration.msSinceCancel()` (new, unit-tested) gives the cancel-to-stop time. Evidence: `rerun1-run.txt`, `rerun1-logcat.txt`, `rerun1/`.

| ID | Status | Evidence |
|----|--------|----------|
| V1 | PASS | Four disconnects: `Generation cancelled (client disconnected)` then `Generation stopped 47-48 ms after cancel (stopped=true)`. |
| V2 | PASS | Next chat 200 in 0.87 s. |
| V3 | PASS | load 1 during the stream, 0 after; 0 one second after each of three more disconnects. |
| V4 | PASS | Stream still ends with `data: [DONE]` (carried forward; re-checked in this run). |
| V5 | PASS (carried: 400 on bad body unchanged; disconnect loads re-checked: 0) | `rerun1-run.txt` |
| V6 | PASS | `testDebugUnitTest lintDebug` and `check_docs` pass after the fix. |
| V7 | PASS | 0 ERROR lines in logcat for the whole run; one INFO cancel line per disconnect. |

Verdict: verified, 7 PASS, 0 FAIL, 0 BLOCKED. Teardown: `device.sh down` run.

### Results (re-run 2, after review fixes, 2026-11-09)
Commit verified: 47ace0582824aecf89c09db3b0138e0dd5ed54ec + uncommitted:
- `README.md` 606eee05a44b63939e7d74ab5655e7a228fc0697
- `app/src/main/java/com/aanand/edgeaicore/AiEngineManager.kt` ecb1025866eb1efa05266d3ba31c53bf370ccdec
- `app/src/main/java/com/aanand/edgeaicore/LlmEngine.kt` 279624836df27c59e3bac957cbd2452ebdaf2992
- `app/src/main/java/com/aanand/edgeaicore/OpenAIServer.kt` c4a1977c95e5f6fbed7eb48c1b92afc3083b9c66
- `app/src/test/java/com/aanand/edgeaicore/AiEngineManagerTest.kt` d0076a68a82512c8f3466f5dbb55c3de8f469249
- `app/src/test/java/com/aanand/edgeaicore/FakeLlmEngine.kt` b90d361369090e1b8c08272630a07e89c01aa94d
- `app/src/test/java/com/aanand/edgeaicore/OpenAIServerTest.kt` c65aa0ee7a50508182b72bd241ae47dbb00b90be
- `docs/architecture.md` a15b599344b1ece5a38c7ab0516ceefaf396a021
- `docs/pitfalls.md` abb12de433ef98b2a39d1b5a641630e3a4b2c3cb
- `docs/standards/api.md` c0c46fe873796ae29585b1dbf3db6a6863827540
- `docs/standards/kotlin.md` d7fcb9de3a3daff77fb12a82ed4a192c67af8233
- `docs/standards/testing.md` 41e04baf57ff0128dee49104e10e1a47d0eff3eb
- `app/src/main/java/com/aanand/edgeaicore/CancellableGeneration.kt` 233257423a661fe705f6c9b4abcb300cfab4c4c4
- `app/src/test/java/com/aanand/edgeaicore/CancellableGenerationTest.kt` 3f603d2c22b02f17138e801a3e30d3eb0042f048

Evidence: `rerun2-run.txt`, `rerun2-logcat.txt`, `rerun2/`, `scripts/rerun2.sh`. Fresh emulator, CPU, Gemma 4 E2B.

| ID | Status | Evidence |
|----|--------|----------|
| V1 | PASS | 4 disconnects: `Generation cancelled (client disconnected)` then `Generation stopped 46-52 ms after cancel (stopped=true)`. |
| V2 | PASS | Next chat 200 in 1.0 s. |
| V3 | PASS | load 1 during the stream, 0 after, and 0 one second after each of three more disconnects. |
| V4 | PASS | Stream ends with `data: [DONE]`; non-stream 200. |
| V5 | PASS | Bad body 400; final load 0. |
| V6 | PASS | 35 unit tests, lint and `check_docs` pass. |
| V7 | PASS | The only `E` entry is the bad-body request (`Error processing request`, expected); disconnects log INFO plus one DEBUG line. |

Verdict: verified, 7 PASS, 0 FAIL, 0 BLOCKED. Teardown: `device.sh down` run.

## Review history

### Round 1 - 2026-11-09
Verdict: Request changes. Scope: diff base 47ace05 vs working tree, 10 files (~450 changed lines), one Haiku reviewer; requirement = Description + AC + Reviewer brief.
- R1-1 [MAJOR] AiEngineManager.kt:161 - a cancel while queued for `inferenceMutex` throws out of `withLock` before the `try`, so `onErrorCallback` is never called (breaks "exactly one callback") - Static (code read) - Open
- R1-2 [MINOR] CancellableGeneration.kt:34 / AiEngineManager.kt:226 - `cancelAction()` unguarded: `cancelProcess()` can throw `IllegalStateException` (race with `closeAllConversations()` outside the mutex); an exception in `invokeOnCancellation` goes to the uncaught handler and the mutex then stalls 2 s silently - Static - Fixed - `AiEngineManager.kt`: cancelProcess guarded with try/catch + Log.w
- R1-3 [MINOR] AiEngineManager.kt:279 - `stopped=false` (terminal callback missing after 2 s) logged at `Log.i`; should be `Log.w` - Static - Fixed - `AiEngineManager.kt`: Log.w when stopped=false
- R1-4 [MINOR] CancellableGeneration.kt:32 - `cancelRequested` set before `cancelledAtMs`, so `msSinceCancel()` can read 0 in between - Static - Fixed - `CancellableGeneration.kt`: msSinceCancel returns 0 until the timestamp is set
- R1-5 [MINOR] OpenAIServerTest.kt:125 - `/v1/load` asserted right after the response without polling; the third response body is never read; can be flaky - Static - Fixed - `OpenAIServerTest.kt`: every body read, bounded poll `awaitLoadZero()`
- R1-6 [MINOR] AiEngineManagerTest.kt:222,258 / FakeLlmEngine.kt - cross-thread `sent`/`pending` not `@Volatile`; unbounded polling loops - Static - Fixed - `FakeLlmEngine.kt` @Volatile, `AiEngineManagerTest.kt` withTimeout(5 s) on the waits
- R1-7 [MINOR] docs/standards/kotlin.md:22 - still says `OpenAIServer` launches on an ad-hoc `CoroutineScope(Dispatchers.IO)` - Static - Fixed - `docs/standards/kotlin.md` names only MainActivity
- R1-8 [NIT] README.md:134 - "the next request does not wait for it" overstates (up to `CANCEL_WAIT_MS`) - Static - Fixed - README wording ('normally')
- R1-9 [NIT] AiEngineManager.kt:279 - `?: 0` / `?: true` fallbacks for a null `generation` - Static - Rejected - `generation` is a nullable var assigned mid-try, so the fallbacks keep the catch total; making it non-null needs a restructure the finding does not justify
Dismissed:
- Reviewer #1 [MAJOR] no unit test for the server disconnect path: the plan states a disconnect cannot be simulated reliably in `testApplication`; the path is covered by the device check (V1/V7: `Generation cancelled (client disconnected)` in logcat, load 0) and the engine side by `AiEngineManagerTest`.
- Reviewer #5 [MINOR] cancel recognised by exception type: intended by the plan; only our `cancelProcess()` produces the native cancelled status.
- Reviewer #10 [MINOR] unverified Ktor exception type: verified on the emulator, the `IOException` branch runs (logcat shows the "client disconnected" line, 0 ERROR lines).
Pre-existing: none.
Resolution: 8 fixed, 1 rejected, 0 deferred. `testDebugUnitTest lintDebug` and `check_docs` pass. Not shown: the new queued-cancel test failing without the fix (no revert done); before the fix `onErrorCallback` was not reached on that path. Runtime files changed: AiEngineManager.kt, CancellableGeneration.kt (re-verify needed).

### Round 2 - 2026-11-09
Verdict: Request changes. Scope: same 10 files + docs (kotlin.md, architecture.md, pitfalls.md), one Haiku reviewer.
- R2-1 [MAJOR] AiEngineManager.kt:241 - a cancel landing after the lock but before `sendMessageAsync` runs the cancel handler at once (no-op) and the send still starts a full generation that outlives the lock - Static - Fixed: skip the send when `!cont.isActive` after registering the handler; test `cancelDuringConversationCreationNeverStartsDecodingAndFreesTheLock`
- R2-2 [MAJOR] OpenAIServer.kt:149 - Ktor's `ClosedWriteChannelException` is a `CancellationException`, not an `IOException`, so that write failure skips `job.cancel()` and the log - Static - Fixed: also catch `CancellationException` in the write loop (cancel the job, log, rethrow). The `IOException` path is device-verified.
- R2-3 [MINOR] AiEngineManager.kt:256 - completion and cancel could both report in a narrow race - Static - Fixed: one `AtomicBoolean` claim shared by `onDone` and the cancel catch
- R2-4 [MINOR] AiEngineManager.kt:285 - comment says the next request never starts on a busy engine, but the lock is released after the 2 s timeout - Static - Fixed: comment now says so (warning is logged)
- R2-5 [MINOR] README.md:134 - overstated: detection happens when a token is written - Static - Fixed: wording qualified
- R2-6 [MINOR] docs/architecture.md:38 - request flow lacks the disconnect cancel - Static - Fixed: one line added
- R2-7 [NIT] docs/pitfalls.md:38 - stale line reference `AiEngineManager.kt:167` - Static - Fixed: reference by method name
- R2-8 [NIT] AiEngineManager.kt:398 - `CANCEL_WAIT_MS` `internal` but only used in the class - Static - Fixed: `private`
Dismissed:
- Reviewer #3 [MAJOR] no unit test for the disconnect path: repeat of R1 (rejected; cannot be simulated in `testApplication`; device-verified V1/V7).
- Reviewer #7 [MINOR] 100 ms delay in the queued-cancel test: rejected; the test cannot observe the lock queue without a production hook, the delay is only a scheduling margin, and its failure mode is a false failure, never a false pass. The closed-conversation test's 2 s wait is the bounded timeout it exercises.
Pre-existing: none.
Resolution: 8 fixed, 2 rejected. `testDebugUnitTest lintDebug` and `check_docs` pass. Runtime files changed: AiEngineManager.kt, OpenAIServer.kt (re-verify needed; another review iteration since a MAJOR fix touched runtime code).

### Round 3 - 2026-11-09
Verdict: Request changes. Scope: same files + docs/architecture.md, pitfalls.md, one Haiku reviewer.
- R3-1 [MAJOR] AiEngineManager.kt:249 - a cancel landing between the `isActive` check and `sendMessageAsync` leaves decoding running after the lock is released - Static - Fixed: `stopNative()` is called again after the send when `!cont.isActive`; test `cancelRightAfterTheSendStopsTheDecodingThatJustStarted`
- R3-2 [MAJOR] OpenAIServer.kt:144 - the disconnect-to-`job.cancel()` mapping has no automated test - Static - Fixed (design now testable): write loop extracted to `internal suspend fun forwardChunks(channel, job, write)`; tests for IOException, CancellationException (rethrown) and healthy forwarding. (Rounds 1-2 had rejected this as untestable.)
- R3-3 [MINOR] docs/standards/testing.md:6 - test class list lacks `CancellableGenerationTest` - Static - Fixed
- R3-4 [MINOR] awaitLoadZero wall-clock deadline; closed-conversation test waits the real 2 s - Static - Fixed: poll bounded by attempt count; `AiEngineManager.cancelWaitMs` is an internal var, the test sets 50 ms
Dismissed:
- Reviewer #4 close-on-timeout risk: repeat of the R1 decision (timeout path logged at WARN; not observed on device, `stopped=true` every time).
- Reviewer #6 [NIT] comments state SDK behaviour as fact: both behaviours are observed on device (V1: `cancelProcess` then terminal callback, `stopped=true`).
Pre-existing: none.
Resolution: 4 fixed, 2 rejected. 35 unit tests, lint and `check_docs` pass. Runtime files changed: AiEngineManager.kt, OpenAIServer.kt.

### Round 4 - 2026-11-09
Verdict: Request changes. Scope: same files + docs/standards/testing.md, one Haiku reviewer.
- R4-1 [MAJOR] OpenAIServer.kt:299 - `forwardChunks` logged with a string-literal tag (errors-and-logging.md, AGENTS.md rule 4) - Static - Fixed: moved into `OpenAIServer`'s companion object, logs with `TAG`; tests call `OpenAIServer.forwardChunks`
- R4-2 [MAJOR] OpenAIServerTest.kt - no test ties a dropped stream to the engine cancel and a free lock - Static - Fixed: `aDroppedStreamCancelsTheEngineAndFreesTheLock` (real `forwardChunks` + `AiEngineManager` + hanging fake: `cancelCount == 1`, conversation closed, next request completes). `/v1/load` itself is route-level and device-verified.
- R4-3 [MINOR] AiEngineManager.kt:296 - post-send `if (!cont.isActive) stopNative()` also fires after a normal completion - Static - Fixed: `cont.isCancelled`
- R4-4 [NIT] OpenAIServer.kt:153 - the outer IOException log duplicates/misstates the disconnect - Static - Fixed: message reworded ("closed after an I/O error")
Dismissed:
- Reviewer #4 [MINOR] cancel decided by exception type: repeat of R1 (intended; only our `cancelProcess()` produces the native cancelled status).
- Reviewer #5 [MINOR] 100 ms delay / unbounded joins: repeat of R2; `runTest` bounds the joins.
Pre-existing: none.
Resolution: 4 fixed, 2 rejected. Unit tests, lint, `check_docs` pass. Runtime files changed: OpenAIServer.kt (log tag, function moved), AiEngineManager.kt (`isCancelled`).

### Round 5 - 2026-11-09 (last allowed iteration)
Verdict: Request changes (as reported; all kept items resolved below). Scope: same files, one Haiku reviewer.
- R5-1 [MAJOR] AiEngineManager.kt:302 - after the 2 s wait times out the lock is released and the conversation closed while native decoding may still run - Static - Fixed in docs only: pitfall 27 records the timeout exception. Code unchanged: this is the planned bounded wait (repeat of R1/R3/R4 decisions); the timeout was never hit on the device (about 50 ms).
- R5-2 [MINOR] OpenAIServer.kt:293 - detection depends on token cadence - Static - Rejected: documented in README and pitfall 27; measured on device (stop 47-48 ms after detection).
- R5-3 [MINOR] OpenAIServer.kt:152 - outer `catch (IOException)` swallows at DEBUG - Static - Rejected: rethrowing would reach the outer `catch (Exception)` and bring back the V7 spurious ERROR/400 on a dead channel; the failed write is logged by `forwardChunks`.
- R5-4 [MINOR] OpenAIServerTest.kt - no route-level dropped-HTTP-stream test - Static - Rejected (repeat): Ktor's test client cannot abort mid-stream; the engine glue is tested (`aDroppedStreamCancelsTheEngineAndFreesTheLock`), the route is device-verified (V1, V7, `/v1/load` 0).
- R5-5 [NIT] OpenAIServer.kt:289 - `forwardChunks` indented one level short - Static - Fixed
- R5-6 [NIT] LlmEngine.kt:21 - `cancelProcess` KDoc lacks `@throws IllegalStateException` - Static - Fixed
Pre-existing: none.
Resolution: 3 fixed, 3 rejected. Unit tests, lint, `check_docs` pass. No finding left open. Review iterations used: 5 of 5.

## Log
- 2026-11-09: created from ANDROIDEDGEAICORE-2 gap analysis
- 2026-11-09: plan drafted
- 2026-11-09: plan revised after validation round 1 (and test-seam decision)
- 2026-11-09: plan revised after validation round 2
- 2026-11-09: plan revised after validation round 3
- 2026-11-09: plan validated, 4 rounds
- 2026-11-09: autopilot started (batch)
- 2026-11-09: implemented, smoke tests pass
- 2026-11-09: verify: 6 PASS, 1 FAIL (V7), 0 BLOCKED
- 2026-11-09: fix round 1 (V7); re-verify 7 PASS
- 2026-11-09: review round 1: Request changes; resolved 8 fixed, 1 rejected
- 2026-11-09: review rounds 2-5: all findings fixed or rejected with evidence (5/5 used)
- 2026-11-09: re-verify after review fixes: 7 PASS
- 2026-11-09: batch-autopilot: shipped, base develop
- 2026-11-09: closed (all acceptance criteria checked; the PR is open, not yet merged)
- 2026-11-09: linked relates_to ANDROIDEDGEAICORE-17, ANDROIDEDGEAICORE-18, ANDROIDEDGEAICORE-19, ANDROIDEDGEAICORE-20 (follow-ups)
