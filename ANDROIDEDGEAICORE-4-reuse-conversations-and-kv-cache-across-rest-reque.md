---
id: ANDROIDEDGEAICORE-4
title: "Reuse conversations and KV cache across REST requests"
status: done
created: 2026-11-09
updated: 2026-11-09
links:
  blocks: [ANDROIDEDGEAICORE-13]
  blocked_by: [ANDROIDEDGEAICORE-3]
  conflicts_with: [ANDROIDEDGEAICORE-8, ANDROIDEDGEAICORE-9, ANDROIDEDGEAICORE-10, ANDROIDEDGEAICORE-14]
  relates_to: [ANDROIDEDGEAICORE-18, ANDROIDEDGEAICORE-19, ANDROIDEDGEAICORE-20]
  split_from: [ANDROIDEDGEAICORE-2]
---

## Description
Every `/v1/chat/completions` call currently creates a transient conversation with a random id and re-prefills the whole history (gap #1, pitfall 6). Cache conversations keyed by the prior messages and settings (sampler, tools, thinking, response format), following the official LiteRT-LM server's `get_or_create_server_conversation`. When a request's `messages[:-1]` equal the cached history plus the last assistant reply, reuse the conversation and prefill only the new message; otherwise build a new conversation from `messages[:-1]`. Consider `clone()` for shared prefixes and a cap on cached conversations.

Background: research and gap analysis in ANDROIDEDGEAICORE-2 (LiteRT-LM v0.19.1). Verify with `scripts/device.sh` (`docs/testing-on-device.md`).

## Acceptance criteria
- [x] A follow-up request that extends the previous turn reuses the cached conversation: only the new message is prefilled, shown by logs or token counts
- [x] Changed history or settings create a fresh conversation, and the reply is correct
- [x] Time to first token on turn N no longer grows with history length (measured on the emulator)
- [x] Memory bounded: a cap on cached conversations, with eviction or TTL
- [x] Docs updated (`architecture.md`, `api.md`; pitfall 6 removed)

## Plan
**Date:** 2026-11-09  **Status:** Validated

### Objective
Make stateless `/v1/chat/completions` efficient: keep the engine conversation (and its KV cache) from the previous request and, when the next request only extends it, prefill just the new message instead of rebuilding the whole history (pitfall 6). Runs after ANDROIDEDGEAICORE-3.

### Context
- Current code: `OpenAIServer` builds a `ConversationState` with a random `conversationId` and `ttlMs = 0` per request. `AiEngineManager.generateConversationResponseAsync` reuses `activeEngineConversation` only when `activeConversationId == state.conversationId`, `messages.size == 1` and the sampling `Triple` matches, so REST never reuses. Otherwise `createEngineConversation` closes the old conversation and builds one from `initialMessages = history.dropLast(1)`, then `sendMessageAsync(last)`. The readiness ping in `InferenceService` and `ConversationManager`'s removal callback call `closeConversation(id)`.
- Official LiteRT-LM server (`python/litert_lm_cli/commands/serve/util.py` `get_or_create_server_conversation`, v0.19.1): one cached conversation keyed by `(context_messages, tools, sampler_config, thinking_config, constrained_decoding_config)`; after a turn the key becomes `[*context, prompt, assistant_reply]`; a hit sends only the last message; a miss closes and recreates from `messages[:-1]`.
- 0.19.1: `Conversation.clone()` copies history and KV state; `getTokenCount()` returns tokens in the KV cache; `cancelProcess()` cannot roll state back (b/450903294 in `Conversation.kt`).
- Rules: `docs/standards/kotlin.md` (all `Engine`/`Conversation` calls inside `inferenceMutex`; shared state behind the same guard), `docs/standards/errors-and-logging.md` (`Log.d` for reuse decisions; never log message content), `docs/standards/testing.md` (keep logic in plain Kotlin and fake the LiteRT-LM boundary), `docs/standards/review.md` item 8 (no change to persisted conversation files).

### Approach
- New `ConversationCache<C>` (plain Kotlin, closing entries through the injected `close` function described below, generic so tests use a fake): holds up to `MAX_CACHED_CONVERSATIONS` entries `(key: CacheKey, conversation: C, lastUsed)`. `CacheKey` = settings (temperature, top_p, top_k, system instruction) + the exact message list the conversation already holds (`List<ChatMessage>`, compared by Gson `JsonElement` equality). `take(settings, context)` removes and returns a hit; `put(...)` inserts and closes the least recently used entry beyond the cap; `clear()` closes all. `ConversationCache<C>` takes a `close: (C) -> Unit` function instead of an `AutoCloseable` bound. LiteRT-LM conversations are closed only inside `inferenceMutex` (`kotlin.md`): `AiEngineManager.clearConversationCache()` is a `suspend` function that clears under `inferenceMutex.withLock`; `loadModel` (suspend) clears the cache, closes the old engine and sets `engine = null` and `currentModelPath = null` in one `inferenceMutex.withLock` block, so a request that runs while the new engine initialises gets "Model not loaded" (as today), never the closed engine; the readiness ping cleanup calls the suspend function from its coroutine; the `ConversationManager` removal callback is dropped (REST does not use `ConversationManager`). The one call left outside the mutex is `close()` from `InferenceService.onDestroy`, which already runs after `serviceScope.cancel()` today and is unchanged (see Risks). A conversation being generated has been taken out of the cache, so `clear()` never closes it mid-generation. The settings part is a data class so ANDROIDEDGEAICORE-10/13/14 add fields to it (seed, tools, thinking, response format) without touching the matching logic.
- `generateConversationResponseAsync` uses the cache instead of `activeEngineConversation`/`activeConversationId`/`activeConversationParams`: hit when settings match and `messages.dropLast(1)` equals the cached history; on a hit send only the last message; on a miss build from `messages.dropLast(1)` as today. After success with a non-empty reply, re-insert with history `messages + assistant(reply)`; an empty reply, an error or a cancellation closes the conversation instead (KV state unknown or not matchable). Closing happens in one place only, in a `finally` after the generation call (still inside the mutex), guarded by `isAlive`; a `CancellationException` is rethrown after that close, and the outer `catch (e: Exception)` gets a preceding `catch (e: CancellationException) { throw e }` so a cancel is never reported through `onErrorCallback` there; the `conversation.close()` in today's `invokeOnCancellation` is removed so there is no double close (`Conversation.close()` throws on a second call).
- Cap default 2 (one client plus the readiness ping or a second client), with an idle TTL `CACHE_IDLE_TTL_MS` (10 min), checked lazily on each request (an idle server keeps its entries until the next request, the service stop or a model change); both constants in `AiEngineManager.companion`.
- Reply matching is exact text; a client that trims or edits the reply gets a miss (correct, just slower). Logged as `Log.d` "cache hit/miss, context size=N".
- `closeConversation(id)` and the ping cleanup become `clearConversationCache()` calls; REST no longer depends on `conversationId`.
Rejected: `clone()` of a warmed system-prompt conversation (saves only the preface, costs a second KV cache); keying by a client-supplied conversation id (not OpenAI-compatible).

### Out of scope
Using `ConversationManager` for REST, changing persisted conversation files, tools/thinking/response format keys (ANDROIDEDGEAICORE-13), token usage (ANDROIDEDGEAICORE-9), cancellation on disconnect (ANDROIDEDGEAICORE-8), concurrent decoding.

### Steps
#### Step 1: ConversationCache
- **Status:** todo
- **Depends on:** none
- **Files:** `app/src/main/java/com/aanand/edgeaicore/ConversationCache.kt` (create: `ConversationCache`, `CacheSettings`, `CacheKey`)
- **Change:** as in Approach, with an injectable clock for TTL; KDoc states the internal lock and that `take`/`put` are called inside `inferenceMutex`.
- **Verification:** step 2 tests.

#### Step 2: Unit tests
- **Status:** todo
- **Depends on:** 1
- **Files:** `app/src/test/java/com/aanand/edgeaicore/ConversationCacheTest.kt` (create)
- **Change:** hit on exact extension; miss on changed earlier message, changed settings, changed reply text, multimodal part change; LRU eviction closes the evicted fake; TTL expiry closes; `clear()` closes all; a taken entry is no longer in the cache and is not closed by `clear()`.
- **Verification:** `./gradlew testDebugUnitTest` passes.

#### Step 3: Use the cache in AiEngineManager
- **Status:** todo
- **Depends on:** 1
- **Files:** `app/src/main/java/com/aanand/edgeaicore/AiEngineManager.kt` (modify: remove `activeEngineConversation`, `activeConversationId`, `activeConversationParams`; rewrite the reuse branch of `generateConversationResponseAsync`; `createEngineConversation` no longer closes others; `loadModel` clears the cache, closes the old engine and sets `engine = null` and `currentModelPath = null` inside one `inferenceMutex.withLock` block that covers only those actions (the mutex is not reentrant, and the GPU-to-CPU retry calls `loadModel` again); `closeAllConversations` is removed and its callers use the cache clear; `loadModel` no longer calls `close()` (the locked block replaces it), and `close()` (for `onDestroy` only) clears the cache through its non-suspend internal clear and then closes the engine; the `engine` read in `generateConversationResponseAsync` moves inside `inferenceMutex.withLock` so a queued request cannot use an engine closed by `loadModel`; `close()` (non-suspend) is kept only for `InferenceService.onDestroy`; replace `closeConversation(id)` with the suspend `clearConversationCache()`)
- **Change:** as in Approach; keep the `onComplete`/`onErrorCallback` exactly-once contract and the `inferenceMutex` scope. Remove the stale comment flagged by pitfall 16 since the code it describes changes. In `AiEngineManagerTest`, replace ticket 3's "same id, one new message, same params does not rebuild" case with the content-based cases below, since the id rule is removed. Add a cancellation case: cancelling the calling coroutine mid-generation closes the fake conversation once, does not cache it, and calls neither `onComplete` nor `onErrorCallback` from the outer catch. Add a case: `clearConversationCache()` closes idle cached fakes and not one that is in flight; `loadModel` with a new path closes the cached fakes and the old fake engine.
- **Verification:** `./gradlew assembleDebug`; `AiEngineManagerTest` (from ANDROIDEDGEAICORE-3, fake engine) gains cases: a second request extending the first sends only the last message to the same fake conversation; a changed history creates a new conversation; an error closes the conversation once and does not cache it; an empty reply is not cached.

#### Step 4: Callers
- **Status:** todo
- **Depends on:** 3
- **Files:** `app/src/main/java/com/aanand/edgeaicore/InferenceService.kt` (modify: `ConversationManager` construction and ping cleanup), `app/src/main/java/com/aanand/edgeaicore/ConversationManager.kt` (modify: remove `onConversationRemoved`)
- **Change:** call `clearConversationCache()` after the readiness ping; drop the `ConversationManager` removal callback: `InferenceService` stops passing it, and `ConversationManager.kt` loses the `onConversationRemoved` constructor parameter and both its invokes (`removeConversation` and `deleteAllConversations`).
- **Verification:** `./gradlew assembleDebug testDebugUnitTest lintDebug`.

#### Step 5: Device checks and measurement (emulator, CPU)
- **Status:** todo
- **Depends on:** 4
- **Files:** none (script and results in `~/.claude/tickets/android_edge_ai_core/ANDROIDEDGEAICORE-4-evidence/`)
- **Change:** after `MODEL=~/Downloads/model.litertlm scripts/device.sh up`, a small Python script that uses the saved token (`~/.cache/edgeaicore-device/token`) and the forward (`http://127.0.0.1:18080`) runs a 10-turn streamed conversation, sending the full history plus the previous reply each turn, and records time to first content chunk per turn; run it with Gemma 3n on the build before and after the change. Then: change an earlier message, change temperature, and interleave two different conversations.
- **Verification:** logs show "cache hit" for turns 2..10 and "miss" for the changed-history and changed-settings requests; replies are coherent in every case; time to first token stays roughly flat over turns after the change while it grows before it; `adb shell dumpsys meminfo com.aanand.edgeaicore` recorded with 2 cached conversations.

#### Step 6: Docs
- **Status:** todo
- **Depends on:** 5
- **Files:** `docs/architecture.md` (Request flow, the paragraph on fresh conversation ids, Components: `AiEngineManager` row "holds one active engine Conversation" and new `ConversationCache` row, Ownership: the `ConversationManager` removal-callback sentence), `docs/standards/api.md` ("Each request is independent" paragraph: still stateless, now with prefix reuse), `docs/pitfalls.md` (remove 6 and 16; update 18; keep the other entries' numbers, which docs and tickets cite), `README.md` ("Smart Session Reuse" feature line and Best Practices: send the reply back unchanged to benefit)
- **Change:** describe the reuse rule and its limits.
- **Verification:** `scripts/check_docs.sh` passes.

### Tests
`ConversationCacheTest` (new); `AiEngineManagerTest` extended through the engine seam from ANDROIDEDGEAICORE-3 (step 3). `InferenceService` changes are covered by device checks (agreed exception in `docs/standards/testing.md`, ANDROIDEDGEAICORE-3). Run `./gradlew testDebugUnitTest lintDebug`, `scripts/check_docs.sh`; device measurement in step 5.

### Risks and open questions
- `InferenceService.onDestroy` still closes the engine (and so the cache) outside the mutex, as today. REST generations run in `OpenAIServer`'s own scope (and the non-streaming handler), not in `serviceScope`, so one may still be in flight then; this race exists today and is not changed here.
- Until ANDROIDEDGEAICORE-8 lands, a cancelled generation closes its conversation when the call returns, which may be before native decoding stops; ticket 8's cancel-and-wait fixes that timing.
- Memory: up to three live conversations (two cached plus one in flight on a miss) each hold a KV cache; if `meminfo` shows pressure on the phone, lower the cap to 1 (a constant change).
- Chat-template rendering could differ between a prefilled history and an incrementally built one; step 5's coherence checks cover this.
- ANDROIDEDGEAICORE-8 must close (not cache) a cancelled conversation; the error path here already does that.

### Success criteria
- [ ] An extending request reuses the cached conversation and prefills only the new message (logs)
- [ ] Changed history or settings build a fresh conversation with a correct reply
- [ ] Time to first token no longer grows with history (measured)
- [ ] Cache bounded by a cap and a lazily checked idle TTL
- [ ] Tests, lint, docs checks pass; architecture, api, README updated; pitfall 6 removed

### Reviewer brief
- **Objective:** reuse the cached engine conversation (and its KV cache) when a stateless chat request only extends the previous one, prefilling just the new message.
- **Key decisions:** a generic `ConversationCache<C>` with an injected close function, keyed by settings + exact prior messages; reply matched exactly; cap 2 with a lazily checked 10-minute idle TTL; entries taken out while generating; all LiteRT-LM closes inside `inferenceMutex` (except the existing `onDestroy` path); the conversation is closed and not cached on error, cancellation or an empty reply; the `ConversationManager` removal callback is dropped; `clone()` and client conversation ids are rejected.
- **Out of scope:** `ConversationManager` for REST, persisted files, tools/thinking keys, usage, disconnect cancellation, concurrent decoding.
- **Success criteria:** reuse shown in logs; changed history/settings rebuild correctly; flat time to first token over turns; bounded cache; tests and checks pass; docs updated; pitfall 6 removed.

The decisions above are intended; report a problem with one only if it breaks a requirement or a documented rule.

### Validation log
- **Round 1:** validator FAIL. My judgement: FAIL, with 3 valid BLOCKING issues: (a) no tests for changed classes (settled by the owner: engine seam + Ktor tests); (b) cache clear outside `inferenceMutex`; (c) double close via `invokeOnCancellation` plus the error path (checked `Conversation.close()`, which throws on a second call). All fixed. MINOR (docs rows, device.sh usage, lazy TTL, empty reply, pitfall 16 and numbering) fixed.
- **Round 2 (diff-scoped):** validator FAIL. My judgement: FAIL, with 2 valid BLOCKING issues: (a) `Conversation.close()` still outside the mutex via the cache's own lock; (b) ticket 3's id-based reuse test would fail. Fixed (suspend `clearConversationCache` under the mutex; test replaced). MINOR (close function instead of an `AutoCloseable` bound, cancel timing, peak 3 live, wording) fixed.
- **Round 3 (diff-scoped):** validator FAIL. My judgement: FAIL, with 1 valid BLOCKING issue: `close()` via `loadModel` still ran outside the mutex. Fixed (one locked clear-and-close block in `loadModel`). MINOR (removal callback, close in `finally` with rethrow, `clearConversationCache` test) fixed.
- **Round 4 (diff-scoped):** validator FAIL. My judgement: FAIL, with 1 valid BLOCKING issue: no test for the cancellation path. Fixed (test case added). MINOR (`onDestroy` wording, `ConversationManager.kt` edit, `closeAllConversations` fate, lock scope with the recursive retry) fixed.
- **Round 5 (diff-scoped, last allowed):** validator FAIL. My judgement: FAIL, with 1 valid BLOCKING issue: `onConversationRemoved` is also invoked in `deleteAllConversations` (checked `ConversationManager.kt` line 208), so removing the parameter as written would not compile. MINOR: valid issues 2 (what `close()` does; `loadModel` no longer calling it) and 3 (engine read before the lock) were fixed; issue 4 (`onDestroy` outside the mutex) was already disclosed in Risks.
- **After round 5:** the fixes for issues 1 to 3 were applied (both invokes named; `close()` and the engine read specified). The 5-round cap was reached; the owner then asked for more rounds (2026-11-09).
- **Round 6 (diff-scoped, owner-requested):** validator FAIL. My judgement: FAIL, with 1 valid BLOCKING issue: the locked block in `loadModel` left `engine` pointing at the closed engine until the new one was assigned (checked `AiEngineManager.kt`: `engine = null` only in `close()`). Fixed: the block also sets `engine = null` and `currentModelPath = null`. The round 5 BLOCKING issue was confirmed resolved.
- **Round 7 (diff-scoped):** validator PASS with 1 MINOR issue (the Approach wording omitted `currentModelPath = null`). Valid, fixed inline.


## Implementation notes
### Autopilot run (2026-11-09)
- Mode: automatic (batch-autopilot, item 8/11)
- Branch: feature/reuse-conversations  PR base: develop  Starting commit: 47ace0582824aecf89c09db3b0138e0dd5ed54ec
- Dirty before start: none
- Work files: none yet
- Counters: fix 0/10, review 1/5, re-verify 0
- Last completed stage: 3 verify (all PASS)  Next: done (shipped)
- Hard blocker: none
- WIP commit: none
- Repo: /home/archit-anand/docker-data/shared/Coding/android_edge_ai_core

### Baseline (2026-11-09)
All unit tests passed on develop at 47ace05.

### Implementation progress
- [x] Steps 1-2: ConversationCache + ConversationCacheTest (5)
- [x] Step 3: AiEngineManager uses the cache (generate path rewritten, close in `finally`, `clearConversationCache`, `loadModel` replaces the engine under the lock, `engine` read under the lock); tests (7 new, 1 updated)
- [x] Step 4: InferenceService (ping cleanup, no removal callback), ConversationManager (`onConversationRemoved` removed)
- [x] Step 5: device measurement (see Verification)
- [x] Step 6: docs (architecture, api.md, README, pitfalls 6/16 retired with numbers kept, 18 updated)
- Deviation: the cancel hard-stop (`conversation.close()` in `invokeOnCancellation`) is gone, as planned; the conversation is closed when the call returns.

### Smoke test (2026-11-09)
`./gradlew assembleDebug testDebugUnitTest lintDebug` pass; `scripts/check_docs.sh` OK.

### Autopilot decisions (2026-11-09)
- Stage 1: Plan already `Validated` (7 rounds); reused.

PR: https://github.com/archit342000/android_edge_ai_core/pull/43, branch feature/reuse-conversations, base develop

## Verification
### Verification plan (2026-11-09)
**Plan:** ticket Plan above  **Diff base:** 47ace0582824aecf89c09db3b0138e0dd5ed54ec
**Environment:** emulator `claude_edgeaicore_api34`, CPU, Gemma 4 E2B (the plan names Gemma 3n; Gemma 4 is the standard test model and loads faster); build "before" = develop at 47ace05 in a temporary worktree, "after" = this branch; scripts `ANDROIDEDGEAICORE-4-evidence/scripts/{convo.py,reuse.sh}`; teardown `device.sh down`, temporary worktree removed.

| ID | Criterion | Scenario | Type | Method | Files | Expected | Evidence |
|----|-----------|----------|------|--------|-------|----------|----------|
| V1 | AC1 | 10-turn streamed conversation, full history re-sent each turn with the reply unchanged | happy | API + logcat | AiEngineManager.kt, ConversationCache.kt | logcat `Conversation cache hit` for turns 2..10 | log excerpt |
| V2 | AC3 | time to first content chunk per turn, before vs after | happy | API timing | same | flat after the change; grows before | table |
| V3 | AC2 | change an earlier message; change temperature; interleave two conversations | edge | API + logcat | same | `cache miss` each time, coherent replies | log, replies |
| V4 | AC4 | `dumpsys meminfo` with 2 cached conversations; log shows the cap/eviction | edge | adb | ConversationCache.kt | recorded; at most 2 cached | meminfo |
| V5 | regression | plain, stream, bad body; readiness ping still passes | regression | API | InferenceService.kt | 200 / `[DONE]` / 400; `Model loaded successfully` | output |
| V6 | regression | unit tests, lint, docs | regression | CLI | all | pass | output |


### Results (2026-11-09)
Commit verified: 47ace0582824aecf89c09db3b0138e0dd5ed54ec + uncommitted:
- `README.md` cf67a31512929cfc238c8652750e4571fcc88c18
- `app/src/main/java/com/aanand/edgeaicore/AiEngineManager.kt` 31777b4b08abbe80346ac7e7f0a50e1c517deeb6
- `app/src/main/java/com/aanand/edgeaicore/ConversationManager.kt` 236b8736d15e8424391dd57e2d89f2f533430aad
- `app/src/main/java/com/aanand/edgeaicore/InferenceService.kt` 8f7b24685933558e981265776b7f9f137af93296
- `app/src/test/java/com/aanand/edgeaicore/AiEngineManagerTest.kt` ae00616fe1f222b104db50a983fe8dc0a6b0bc10
- `app/src/test/java/com/aanand/edgeaicore/FakeLlmEngine.kt` 1bcdc0638152d44083a21a656792da191a8e5b5e
- `docs/architecture.md` d8ace3a7682539ae6321339a8f70e0a90bab049c
- `docs/pitfalls.md` 924f5bdba6c2c2892a2832ab03da4d8320974166
- `docs/standards/api.md` b962e20cf339d8084efd7496025cb4bd28c9335e
- `app/src/main/java/com/aanand/edgeaicore/ConversationCache.kt` a81f2ff6ba43487fea5682178a4d03fabce984ab
- `app/src/test/java/com/aanand/edgeaicore/ConversationCacheTest.kt` 8f8fe2edf974ef42cb438789d10fba85d90cdfb5

Emulator `claude_edgeaicore_api34`, CPU, Gemma 4 E2B. "Before" = develop at 47ace05 built in a temporary worktree (removed afterwards). Evidence: `ANDROIDEDGEAICORE-4-evidence/` (`reuse-before.txt`, `reuse-after.txt`, the short-history runs `*-short.txt`, `scripts/convo.py`, `scripts/reuse.sh`).

Time to the first content chunk per turn (streamed, full history re-sent with the reply unchanged; the first message is about 700 tokens long, then one-word answers):

| Turn | 1 | 2 | 3 | 4 | 5 | 6 |
|------|---|---|---|---|---|---|
| Before (s) | 8.29 | 6.56 | 5.14 | 5.14 | 5.06 | 6.55 |
| After (s) | 6.45 | 0.77 | 0.75 | 0.73 | 0.72 | 0.78 |

With short messages (10 turns, 19 messages) the old build took 0.7 to 1.4 s per turn and the new one 0.71 to 0.74 s after turn 1 (`*-short.txt`); the long first message makes the difference visible.

| ID | Status | Evidence |
|----|--------|----------|
| V1 | PASS | logcat: `cache miss, context size=0` on turn 1, then `cache hit, context size=2,4,6,8,10` (and up to 18 in the 10-turn run). |
| V2 | PASS | table above: flat 0.72-0.78 s after turn 1 with the cache; 5 to 8 s per turn without it. |
| V3 | PASS | A turn 2 hit; B turn 1 miss; A turn 3 after B: **hit** (both conversations cached, the interleave case); A with an edited earlier message: miss; A with temperature 0.2: miss; every reply coherent (one-word answers as asked). |
| V4 | PASS (partly) | `dumpsys meminfo` after the run with 2 cached conversations: TOTAL PSS 1.75 GB (native heap 664 MB; the model is memory-mapped). No comparison run without the cache; the cap of 2 and the idle limit are unit-tested (`ConversationCacheTest`). |
| V5 | PASS | plain request 200, bad body 400, readiness ping passed (server answered). |
| V6 | PASS | 32 unit tests, lint, check_docs. |

Verdict: verified, 6 PASS (V4 partly), 0 FAIL, 0 BLOCKED.

## Review history

### Round 1 - 2026-11-09
Verdict: Request changes (two MAJORs, docs only). Scope: 12 files (~560 changed lines incl. tests/docs), one Haiku reviewer.
- R1-1 [MAJOR] README told clients they may send "just the new turn" - Static - Fixed: full history every time, cache is automatic
- R1-2 [MAJOR] pitfall 16 marked removed while the stale comment was still in the code - Static - Fixed: the comment is reworded; pitfall note says so
- R1-3 [MINOR] stale KDoc ("recreates the conversation on every request") - Static - Fixed
- R1-4 [MINOR] dead `lastDummyId` in `InferenceService` - Static - Fixed: removed
- R1-5 [MINOR] README "Customizable TTL" no longer qualified now that pitfall 6 is removed - Static - Fixed: noted as not used by the REST endpoint
- R1-6 [MINOR] settings changes beyond temperature, and `clearConversationCache()` waiting for a generation, untested - Static - Fixed: two tests
- R1-7 [NIT] cache KDoc omits the `onDestroy` exception - Static - Fixed
- R1-8 [NIT] redundant `also` before `takeIf` - Static - Fixed
Pre-existing: none.
Resolution: 8 fixed. 34 unit tests, lint, check_docs pass. Runtime behaviour unchanged since verification (comments, a dead variable and a no-op `also`).

## Log
- 2026-11-09: created from ANDROIDEDGEAICORE-2 gap analysis
- 2026-11-09: plan drafted
- 2026-11-09: plan revised after validation round 1 (and test-seam decision)
- 2026-11-09: plan revised after validation round 2
- 2026-11-09: plan revised after validation round 3
- 2026-11-09: plan revised after validation round 4
- 2026-11-09: plan drafted, unresolved issues (5 rounds; post-round-5 fixes not re-validated)
- 2026-11-09: plan revised after validation round 6 (owner asked for more rounds)
- 2026-11-09: plan validated, 7 rounds (rounds 6-7 at the owner's request)
- 2026-11-09: autopilot started (batch)
- 2026-11-09: implemented, smoke tests pass
- 2026-11-09: verify: 6 PASS, 0 FAIL
- 2026-11-09: batch-autopilot: shipped, base develop
- 2026-11-09: closed (all acceptance criteria checked; the PR is open, not yet merged)
- 2026-11-09: linked relates_to ANDROIDEDGEAICORE-18, ANDROIDEDGEAICORE-19, ANDROIDEDGEAICORE-20 (follow-ups)
