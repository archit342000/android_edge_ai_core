---
id: ANDROIDEDGEAICORE-9
title: "Report real token usage"
status: done
created: 2026-11-09
updated: 2026-11-10
links:
  blocks: [ANDROIDEDGEAICORE-7]
  blocked_by: [ANDROIDEDGEAICORE-3]
  conflicts_with: [ANDROIDEDGEAICORE-4, ANDROIDEDGEAICORE-8, ANDROIDEDGEAICORE-10]
  relates_to: [ANDROIDEDGEAICORE-17, ANDROIDEDGEAICORE-18, ANDROIDEDGEAICORE-20]
  split_from: [ANDROIDEDGEAICORE-2]
---

## Description
Responses always send `usage` 0 (gap #7). Compute prompt, completion and total tokens as the official server does: `BenchmarkInfo.lastPrefillTokenCount` and `lastDecodeTokenCount` plus `getTokenCount()` for cached tokens (needs `ExperimentalFlags.enableBenchmark`). Support `stream_options.include_usage` for streams.

Background: research and gap analysis in ANDROIDEDGEAICORE-2 (LiteRT-LM v0.19.1). Verify with `scripts/device.sh` (`docs/testing-on-device.md`).

## Acceptance criteria
- [x] Non-streaming responses carry accurate `usage`
- [x] Streams emit a usage chunk before `[DONE]` when `include_usage` is set
- [x] Cached prompt tokens are counted when a conversation is reused
- [x] `api.md` updated; pitfall 7 removed

## Plan
**Date:** 2026-11-09  **Status:** Validated

### Objective
Return real `usage` (prompt, completion, total tokens, and cached prompt tokens) on chat responses, and a usage chunk before `[DONE]` when a stream asks for it with `stream_options.include_usage`. Runs after ANDROIDEDGEAICORE-3.

### Context
- Current code: `OpenAIServer` sends `usage = Usage(0, 0, 0)` (pitfall 7); streams send no usage. `ChatCompletionRequest` has no `stream_options`; `ChatCompletionChunk` has no `usage`.
- 0.19.1: `ExperimentalFlags.enableBenchmark` is read only when an `Engine` is created; `Conversation.getBenchmarkInfo()` returns `BenchmarkInfo(lastPrefillTokenCount, lastDecodeTokenCount, ...)` and throws `LiteRtLmJniException` if benchmark is off; `getTokenCount()` is the KV-cache size (prefill + decode).
- Official server `openai_common.compute_token_usage`: `completion = lastDecodeTokenCount`, `cached = max(0, tokenCount - prefill - completion)`, `prompt = cached + prefill`, plus `prompt_tokens_details.cached_tokens`; streams with `include_usage` add `"usage": null` to normal chunks and a final chunk with `choices: []` and the usage.
- Rules: `docs/standards/api.md` (additive fields are a minor version; keep OpenAI snake_case; update README and add a DTO test), `docs/standards/kotlin.md` (DTO wire names; nullable absent fields), `docs/standards/errors-and-logging.md`.

### Approach
- Set `ExperimentalFlags.enableBenchmark = true` in `AiEngineManager.loadModel` before the engine is created. The flag and `getBenchmarkInfo()` are `@ExperimentalApi` (`RequiresOptIn` at error level), so the code that touches them uses `@OptIn(ExperimentalApi::class)`.
- After `onDone`, and before the reply is appended to `state.history`, read benchmark info and token count inside the mutex and pass a `TokenUsage` to the completion callback (`onComplete: (String, TokenUsage) -> Unit`). If the read fails (it should not, since benchmark is always on), that is a failure, not a zero: `Log.e` with the throwable, the continuation is resumed with a new `UsageUnavailableException` (no history append), and the outer handler calls `onErrorCallback` once. The server maps that type to the fixed message (never `error.message`), so non-streaming returns `500 {"error": "Token usage unavailable"}` and a stream sends its error event and ends without `[DONE]`, as other stream errors do.
- Pure `TokenUsage.from(prefill, decode, tokenCount)` implements the official formula.
- DTOs: `Usage` gains `prompt_tokens_details: PromptTokensDetails? = null` with `cached_tokens: Int? = null`; `ChatCompletionRequest` gains `stream_options: StreamOptions? = null` with `include_usage: Boolean? = null`; `ChatCompletionChunk` gains nullable `usage` (Gson omits nulls, so the normal chunks stay unchanged when `include_usage` is off; the `"usage": null` per chunk from the official server is not copied, since Gson drops nulls and OpenAI clients accept its absence).
Rejected: counting tokens by re-tokenising text (no tokenizer API in Kotlin; would disagree with the engine).

### Out of scope
`reasoning_tokens` (ANDROIDEDGEAICORE-13), `finish_reason: "length"` (needs ANDROIDEDGEAICORE-10's limit), metrics endpoints.

### Steps
#### Step 1: DTOs and usage math
- **Status:** todo
- **Depends on:** none
- **Files:** `app/src/main/java/com/aanand/edgeaicore/OpenAIModels.kt` (modify: `Usage`, `ChatCompletionRequest`; new `PromptTokensDetails`, `StreamOptions`, `TokenUsage`), `app/src/main/java/com/aanand/edgeaicore/OpenAIServer.kt` (modify: `ChatCompletionChunk` gains `usage`)
- **Change:** as in Approach.
- **Verification:** step 2 tests.

#### Step 2: Unit tests
- **Status:** todo
- **Depends on:** 1
- **Files:** `app/src/test/java/com/aanand/edgeaicore/OpenAIModelsTest.kt` (modify)
- **Change:** `TokenUsage.from` for a fresh conversation (cached 0), a reused one (cached > 0) and a negative difference (clamped to 0); Gson round-trip of `stream_options.include_usage`; `usage` serialises with `prompt_tokens_details.cached_tokens`; a chunk without usage has no `usage` key.
- **Verification:** `./gradlew testDebugUnitTest` passes.

#### Step 3: Engine
- **Status:** todo
- **Depends on:** 1
- **Files:** `app/src/main/java/com/aanand/edgeaicore/AiEngineManager.kt` (modify: `loadModel` sets the flag; `generateConversationResponseAsync` `onComplete` signature and the `onDone` path), `app/src/main/java/com/aanand/edgeaicore/LlmEngine.kt` (modify: `getTokenCount()` and `getBenchmarkInfo()` on `LlmConversation` and its adapter), `app/src/main/java/com/aanand/edgeaicore/InferenceService.kt` (modify: ping `onComplete` lambda), `app/src/test/java/com/aanand/edgeaicore/AiEngineManagerTest.kt` (modify)
- **Change:** as in Approach; read counts before the conversation can be closed or reused. Tests with the fake engine: the fake's counts reach `onComplete`; a throwing `getBenchmarkInfo` gives `onErrorCallback` once and no `onComplete`.
- **Verification:** `./gradlew assembleDebug testDebugUnitTest`.

#### Step 4: Server
- **Status:** todo
- **Depends on:** 3
- **Files:** `app/src/main/java/com/aanand/edgeaicore/OpenAIServer.kt` (modify: non-streaming response `usage`; streaming `onComplete` sends a final chunk `choices = []`, `usage = ...` before `data: [DONE]` when `request.stream_options?.include_usage == true`)
- **Files:** also `app/src/test/java/com/aanand/edgeaicore/OpenAIServerTest.kt` (modify).
- **Change:** as above. `OpenAIServerTest` cases: non-streaming `usage` from the fake counts; a stream with `include_usage` ends with the usage chunk then `[DONE]`; without it there is no usage chunk; a usage failure gives the fixed 500.
- **Verification:** `./gradlew assembleDebug testDebugUnitTest lintDebug`.

#### Step 5: Device checks (emulator, CPU)
- **Status:** todo
- **Depends on:** 4
- **Files:** none (evidence in `~/.claude/tickets/android_edge_ai_core/ANDROIDEDGEAICORE-9-evidence/`)
- **Change:** non-streaming chat; streamed chat with and without `"stream_options":{"include_usage":true}`; if ANDROIDEDGEAICORE-4 has landed, a second turn that extends the first.
- **Verification:** non-zero `prompt_tokens` and `completion_tokens`, `total = prompt + completion`; the usage chunk is the last before `[DONE]` only when requested; on the reused turn `cached_tokens > 0` and `prompt_tokens` includes them. If ANDROIDEDGEAICORE-4 is not merged yet, the PR may merge with the cached case pending, but this ticket stays open until that case passes on a build with ANDROIDEDGEAICORE-4.

#### Step 6: Docs
- **Status:** todo
- **Depends on:** 5
- **Files:** `docs/standards/api.md` (Chat request: `stream_options`; Responses: usage fields and the usage chunk), `README.md` (developer guide), `docs/pitfalls.md` (remove 7; keep the other entries' numbers)
- **Change:** document the fields. No version or `changelog.json` change here; that is part of the release (`docs/release.md`).
- **Verification:** `scripts/check_docs.sh` passes.

### Tests
`OpenAIModelsTest`, `AiEngineManagerTest` and `OpenAIServerTest` extended through the engine seam and Ktor test host from ANDROIDEDGEAICORE-3. `InferenceService` and `MainActivity` changes are covered by device checks (agreed exception in `docs/standards/testing.md`, ANDROIDEDGEAICORE-3). Run `./gradlew testDebugUnitTest lintDebug`, `scripts/check_docs.sh`; device check in step 5.

### Risks and open questions
- `enableBenchmark` may add a small per-request overhead; compare a request's latency with and without it once and record it.
- The cached-token criterion depends on ANDROIDEDGEAICORE-4 for a real reused conversation.

### Success criteria
- [x] Non-streaming responses carry accurate `usage`
- [x] Streams emit a usage chunk before `[DONE]` when `include_usage` is set
- [x] Cached prompt tokens counted on a reused conversation
- [x] Tests, lint, docs checks pass; `api.md` and README updated; pitfall 7 removed

### Reviewer brief
- **Objective:** return real `usage` (prompt, completion, total, cached prompt tokens), and a usage chunk before `[DONE]` when `stream_options.include_usage` is set.
- **Key decisions:** `ExperimentalFlags.enableBenchmark = true` at engine creation with `@OptIn(ExperimentalApi::class)`; the official formula (completion = last decode count, prompt = token count − decode, cached = prompt − prefill, clamped ≥ 0); a failed usage read is an error (`UsageUnavailableException` → fixed 500 / stream error event), never zeros; nullable additive DTO fields; no per-chunk `"usage": null`.
- **Out of scope:** `reasoning_tokens`, `finish_reason: "length"`, metrics endpoints, the version bump.
- **Success criteria:** accurate non-streaming usage; usage chunk only when requested; cached tokens counted on reuse (the ticket stays open until checked with ticket 4); tests and checks pass; `api.md`/README updated; pitfall 7 removed.

The decisions above are intended; report a problem with one only if it breaks a requirement or a documented rule.

### Validation log
- **Round 1:** validator FAIL. My judgement: FAIL, with 2 valid BLOCKING issues.
  1. BLOCKING `@ExperimentalApi` opt-in missing: valid (checked `ExperimentalFlags.kt`, `Conversation.kt`); OptIn added.
  2. BLOCKING failed usage read sent as zeros (masking): valid; it is now an error.
  3. to 7. MINOR (closing with the cached criterion open, pitfall numbering, nullable DTO fields, stream error path, no version bump): all valid and fixed.
  - Cross-ticket test rule: settled by the owner (engine seam + Ktor tests); `AiEngineManagerTest`/`OpenAIServerTest` cases added.
- **Round 2 (diff-scoped):** validator PASS with 2 MINOR issues (read usage before the history append and resume with the error; OpenAIServerTest file and a fixed message tied to the exception type). Both valid, fixed inline.


## Implementation notes
### Autopilot run (2026-11-09)
- Mode: automatic (batch-autopilot, item 3/11)
- Branch: feature/real-token-usage  PR base: develop  Starting commit: 47ace0582824aecf89c09db3b0138e0dd5ed54ec
- Dirty before start: none
- Work files: none yet
- Counters: fix 0/10, review 3/5, re-verify 1
- Last completed stage: 3 verify (V7 BLOCKED on ticket 4)  Next: done (shipped; ticket stays open for the cached-token case V7)
- Hard blocker: none
- WIP commit: none
- Repo: /home/archit-anand/docker-data/shared/Coding/android_edge_ai_core

### Baseline (2026-11-09)
All unit tests passed on develop at 47ace05.

### Implementation progress
- [x] Step 1: DTOs and usage math - OpenAIModels.kt (`TokenUsage.from`, `StreamOptions`, `PromptTokensDetails`, `UsageUnavailableException`), OpenAIServer.kt (`ChatCompletionChunk.usage`)
- [x] Step 2: Unit tests - OpenAIModelsTest (6)
- [x] Step 3: Engine - LlmEngine.kt (getTokenCount/getBenchmarkInfo), AiEngineManager.kt (`enableBenchmark`, `readUsage`, `onComplete(text, usage)`), InferenceService.kt, AiEngineManagerTest (2), FakeLlmEngine
- [x] Step 4: Server - usage in the response, usage chunk on `include_usage`, fixed message for `UsageUnavailableException`, OpenAIServerTest (5)
- [x] Step 5: Device check (see Verification)
- [x] Step 6: Docs - api.md, README, pitfall 7 removed

### Smoke test (2026-11-09)
`./gradlew assembleDebug testDebugUnitTest lintDebug` pass; `scripts/check_docs.sh` OK.

### Autopilot decisions (2026-11-09)
- Stage 1: Plan already `Validated` (2 rounds); reused.
- The cached-token criterion needs ticket 4 (conversation reuse), which runs later in this batch; the plan allows the PR to ship with that case pending, so this ticket stays open until it is checked.

PR: https://github.com/archit342000/android_edge_ai_core/pull/38, branch feature/real-token-usage, base develop

## Verification
### Verification plan (2026-11-09)
**Plan:** ticket Plan above  **Diff base:** 47ace0582824aecf89c09db3b0138e0dd5ed54ec (full form not needed: ~150 changed source lines, 5 files; light form plus edge cases)
**Environment:** emulator `claude_edgeaicore_api34`, CPU, Gemma 4 E2B, `scripts/device.sh`; script `ANDROIDEDGEAICORE-9-evidence/scripts/usage.sh`; teardown `device.sh down`.

| ID | Criterion | Scenario | Type | Method | Files | Expected | Evidence |
|----|-----------|----------|------|--------|-------|----------|----------|
| V1 | AC1 | Non-streaming chat | happy | API | OpenAIServer.kt, AiEngineManager.kt | non-zero prompt/completion tokens, total = prompt + completion, `cached_tokens` 0 | response |
| V2 | AC2 | Stream with `stream_options.include_usage` | happy | API | OpenAIServer.kt | last event before `[DONE]` has `choices: []` and non-zero usage | stream |
| V3 | AC2 | Stream without it | edge | API | OpenAIServer.kt | no `usage` anywhere, ends `[DONE]` | stream |
| V4 | AC1 | Longer prompt gives larger `prompt_tokens`; usage of a stream equals the usage of the same non-stream request | edge | API | AiEngineManager.kt | prompt tokens grow with the prompt; completion tokens about the number of content chunks | responses |
| V5 | regression | Bad body | failure | API | OpenAIServer.kt | 400 | response |
| V6 | regression | Unit tests, lint, docs | regression | CLI | all | pass | output |
| V7 | AC3 | Cached tokens on a reused conversation | happy | API | AiEngineManager.kt | `cached_tokens > 0` | BLOCKED until the conversation-reuse change (ticket 4) is on the build |


### Results (2026-11-09)
Commit verified: 47ace0582824aecf89c09db3b0138e0dd5ed54ec + uncommitted:
- `README.md` b3b9eb2836e9cd9926057f9e5895745667d1f52f
- `app/src/main/java/com/aanand/edgeaicore/AiEngineManager.kt` 3917150006851ba9daf043b6b4eff5d81147e351
- `app/src/main/java/com/aanand/edgeaicore/InferenceService.kt` f32b4987c3b50efc9559414f069b501fccceac92
- `app/src/main/java/com/aanand/edgeaicore/LlmEngine.kt` 45ac8f990659a31380de99e9f3dd53c1444bb2bf
- `app/src/main/java/com/aanand/edgeaicore/OpenAIModels.kt` f4705fc41dae43031c9a389b46dbfe245cc218c8
- `app/src/main/java/com/aanand/edgeaicore/OpenAIServer.kt` bb59d2ffeead3b6234a4835e62b8e0728dfedda9
- `app/src/test/java/com/aanand/edgeaicore/AiEngineManagerTest.kt` 74f51a362cc7416b4bc4ad23ac5a1c804c3b7bbb
- `app/src/test/java/com/aanand/edgeaicore/FakeLlmEngine.kt` d8395100ea43b5840119620c9227fb6afed40317
- `app/src/test/java/com/aanand/edgeaicore/OpenAIModelsTest.kt` 17de01218a86c536b4926cdf4616902cf502b6f6
- `app/src/test/java/com/aanand/edgeaicore/OpenAIServerTest.kt` f339c154973854489b386004c1a2cbb1ec5065c2
- `docs/pitfalls.md` 06cddee7de5381b2826a49014fb407bcf8c13d44
- `docs/standards/api.md` dd75d6c05036094e5faf25dae77c516f8f447df2

Emulator `claude_edgeaicore_api34`, CPU, Gemma 4 E2B. Evidence: `ANDROIDEDGEAICORE-9-evidence/usage-run.txt`, `scripts/usage.sh`.

| ID | Status | Evidence |
|----|--------|----------|
| V1 | PASS | Non-stream: `prompt_tokens` 24, `completion_tokens` 18, `total_tokens` 42 (= 24 + 18), `cached_tokens` 0; HTTP 200 in 2.06 s. |
| V2 | PASS | Stream with `include_usage`: the last event before `[DONE]` has `"choices":[]` and the same usage (24/18/42). |
| V3 | PASS | Stream without it: 0 mentions of `usage`, ends `[DONE]`. |
| V4 | PASS | Short prompt: 23 prompt tokens; a prompt of about 600 words-tokens: 624. The streamed request reports `completion_tokens` 18 against 17 content chunks (the 18th is the end token). Stream and non-stream usage match (24/18). |
| V5 | PASS | Bad body 400. |
| V6 | PASS | `testDebugUnitTest lintDebug` (43 tests), `check_docs.sh` pass. |
| V7 | BLOCKED | Cached tokens on a reused conversation need the conversation-reuse change (not on this branch); `TokenUsage.from` covers the formula in unit tests (cached 20 of 30). |

Verdict: partly verified: 6 PASS, 0 FAIL, 1 BLOCKED (V7, dependency). Not measured: the latency cost of `enableBenchmark` (one request took 2.06 s for 18 tokens; no with/without comparison). Teardown done.

### Results (re-run 1, after review fixes, 2026-11-09)
Commit verified: 47ace0582824aecf89c09db3b0138e0dd5ed54ec + uncommitted:
- `README.md` b3b9eb2836e9cd9926057f9e5895745667d1f52f
- `app/src/main/java/com/aanand/edgeaicore/AiEngineManager.kt` 689fe7f3295855be17479aef5231f3fbaef596ea
- `app/src/main/java/com/aanand/edgeaicore/InferenceService.kt` f32b4987c3b50efc9559414f069b501fccceac92
- `app/src/main/java/com/aanand/edgeaicore/LlmEngine.kt` 45ac8f990659a31380de99e9f3dd53c1444bb2bf
- `app/src/main/java/com/aanand/edgeaicore/OpenAIModels.kt` cb485caf1a4d1fc942c64496a500665c134b5aa6
- `app/src/main/java/com/aanand/edgeaicore/OpenAIServer.kt` 8747670d021c52b9182e14d4997a7c02ebd7f3c7
- `app/src/test/java/com/aanand/edgeaicore/AiEngineManagerTest.kt` d7d0dbb93b67cb7e5b8c0f83de3abf171d69a2b7
- `app/src/test/java/com/aanand/edgeaicore/FakeLlmEngine.kt` d63488fd6ed0060d17191a65bb7f362ba73e66ec
- `app/src/test/java/com/aanand/edgeaicore/OpenAIModelsTest.kt` 5e10f63e7b2eb12786dc5d6427bd286f43689406
- `app/src/test/java/com/aanand/edgeaicore/OpenAIServerTest.kt` f339c154973854489b386004c1a2cbb1ec5065c2
- `docs/pitfalls.md` a3b8dfc7d5a47ccbea0e2dc43da62013fe7a6fe2
- `docs/standards/api.md` dd75d6c05036094e5faf25dae77c516f8f447df2

Evidence: `usage2-run.txt`, `scripts/usage2.sh`.

| ID | Status | Evidence |
|----|--------|----------|
| V1 | PASS | Non-stream: 24 prompt, 18 completion, `cached_tokens` 0. |
| V2 | PASS | Last event before `[DONE]`: `choices: []` with usage 24/18/42. |
| V3 | PASS | No `usage` in a stream without `include_usage`. |
| V5 | PASS | Bad body 400. |
| V6 | PASS | 33 unit tests, lint, check_docs. |
| V7 | BLOCKED | Needs the conversation-reuse change; formula covered by unit tests. |
| V8 | PASS | Two-turn REST request: `prompt_tokens` 50, `cached_tokens` 0 (a rebuilt history is prefill, not cache); the same final question alone: 25. |

Verdict: partly verified (7 PASS, 0 FAIL, 1 BLOCKED = V7 by dependency).

### Results (re-run 2, V7 and benchmark cost, 2026-11-09)
Build: a local, unpushed branch from `feature/reuse-conversations` (#43) with `feature/real-token-usage` (#38) merged in. Production code merged without textual conflicts but did not compile; two local fixes made it build (`readUsage(active)` instead of the captured `conversation`, and the `closeAllConversations()` call removed from the usage-failure path, since #43 closes an uncached conversation in its `finally`). Recorded for ANDROIDEDGEAICORE-20. Test and doc conflicts took #43's side (device check only). Branch and worktree deleted afterwards. Emulator `claude_edgeaicore_api34`, CPU, Gemma 4 E2B, temperature 0. Evidence: `cached-run.txt`, `bench-on.txt`, `bench-off.txt`, `scripts/{cached.sh,bench.sh}`.

| ID | Status | Evidence |
|----|--------|----------|
| V7 | PASS | Turn 1 (long first message) 330 prompt / 2 completion, `cached_tokens` 0, cache miss. Turn 2 extending it: 350 prompt, `cached_tokens` 332 (= turn 1 total), cache hit. Turn 3 streaming with `include_usage`: 370 prompt, `cached_tokens` 352 (= turn 2 total), cache hit. A fresh conversation: 30 prompt, `cached_tokens` 0, cache miss. |
| Risk: `enableBenchmark` cost | measured | Same prompt, 10 streaming + 10 non-streaming each. On: TTFT median 0.818 s, non-stream total median 7.021 s. Off (throwaway build, flag false, no usage read): TTFT 0.975 s, total 7.281 s. No measurable overhead (differences within run-to-run noise). |

Verdict: all acceptance criteria verified (V7 on the local merge; to be re-confirmed on `develop` by ANDROIDEDGEAICORE-18 Step 4 after ANDROIDEDGEAICORE-20).

## Review history

### Round 1 - 2026-11-09
Verdict: Request changes. Scope: 12 files (~330 changed lines incl. tests/docs), one Haiku reviewer.
- R1-1 [MAJOR] no test that `enableBenchmark` is set (deleting it kept all tests green) - Static - Fixed: `FakeEngineFactory.benchmarkFlagAtCreation` records the flag at engine creation; test `benchmarkIsEnabledWhenTheEngineIsCreatedAlsoOnTheCpuRetry`
- R1-2 [MAJOR] "Token usage unavailable" hard-coded twice; `clientMessage` redundant - Static - Fixed: `UsageUnavailableException.MESSAGE`; `clientMessage` removed (the exception's message is the fixed text); leak tests kept
- R1-3 [MINOR] after a usage failure the engine holds a reply the history lacks, and the next same-id request would reuse it - Static - Fixed: `closeAllConversations()` before resuming with the error; the test checks the next request rebuilds
- R1-4 [MINOR] pitfall 7 removed without keeping its number (file convention) - Static - Fixed: `7. *(Removed.)*`
- R1-5 [MINOR] `finalUsage ?: TokenUsage(0,0,0)` zero fallback - Static - Fixed: a missing usage is "Empty response from model"; `!!` after the check
Pre-existing: none.
Resolution: 5 fixed. 45 unit tests, lint, check_docs pass. Runtime files changed (AiEngineManager.kt, OpenAIServer.kt, OpenAIModels.kt): another review iteration and a device re-verify follow.

### Round 2 - 2026-11-09
Verdict: Request changes. Scope: same 12 files, one Haiku reviewer.
- R2-1 [MAJOR] OpenAIServer.kt:119 - `"litertlm-model"` hard-coded a third time - Static - Fixed: `DEFAULT_MODEL_ID` constant used by all four places
- R2-2 [MINOR] new `!!` on the non-streaming path - Static - Fixed: locals after the null check
- R2-3 [MINOR] pitfall 16 cites `AiEngineManager.kt:167` (line shifted) - Static - Fixed: cite the method (same wording as the equivalent edit on the cancel-on-disconnect branch, so the two merge cleanly)
- R2-4 [MINOR] `cached_tokens` may report a rebuilt REST history as cached - Static - Rejected after checking on the emulator: a 2-turn REST request reports `prompt_tokens` 50, `cached_tokens` 0 (the rebuilt history counts as prefill); the doc wording ("already in the KV cache") is accurate
- R2-5 [NIT] `@OptIn` on `readUsage` unneeded - Static - Fixed: removed
- R2-6 [NIT] test style (`assertTrue`, long lines) - Static - Fixed; per-chunk `GsonBuilder().create()` left as is (existing pattern in the file)
Dismissed: usage failure logged at three layers (R2-7 NIT): each layer adds its own context and the origin keeps the cause; rejected.
Pre-existing: none.
Resolution: 5 fixed, 2 rejected. 33 unit tests, lint, check_docs pass.

### Round 3 - 2026-11-09
Verdict: Request changes (one MAJOR, test-only). Scope: same 12 files, one Haiku reviewer.
- R3-1 [MAJOR] the `enableBenchmark` test could pass from a flag set by an earlier test (process-wide) - Static - Fixed: the test resets the flag to false first
- R3-2 [MINOR] architecture.md flow lacks the `(text, usage)` callback and usage failure - Static - Fixed
- R3-3 [MINOR] api.md Errors table 500 row lacks the usage failure - Static - Fixed
- R3-4 [NIT] indentation in the edited block of `OpenAIServer.kt` - Static - Fixed
- R3-5 [NIT] missing blank line in `FakeLlmEngine.kt` - Static - Rejected: the blank line is present
Pre-existing: none.
Resolution: 4 fixed, 1 rejected. Only tests, docs and whitespace changed; unit tests, lint, check_docs pass. Loop ends clean (3 of 5 iterations).

## Log
- 2026-11-09: created from ANDROIDEDGEAICORE-2 gap analysis
- 2026-11-09: plan drafted
- 2026-11-09: linked blocks ANDROIDEDGEAICORE-7 (MTP measurement uses usage token counts from 9)
- 2026-11-09: plan revised after validation round 1 (and test-seam decision)
- 2026-11-09: plan validated, 2 rounds
- 2026-11-09: autopilot started (batch)
- 2026-11-09: implemented, smoke tests pass
- 2026-11-09: verify: 6 PASS, 1 BLOCKED (V7, needs ticket 4)
- 2026-11-09: review rounds 1-3 resolved; re-verify 7 PASS, 1 BLOCKED
- 2026-11-09: batch-autopilot: shipped, base develop (V7 cached-token case pending until conversation reuse lands)
- 2026-11-09: linked relates_to ANDROIDEDGEAICORE-17, ANDROIDEDGEAICORE-18, ANDROIDEDGEAICORE-20 (follow-ups)
- 2026-11-09: verify re-run 2: V7 PASS on a local #38+#43 merge (cached 332 and 352 on reuse, 0 fresh); enableBenchmark cost measured (none measurable); all criteria checked, ready to close
- 2026-11-09: closed (all acceptance criteria checked; PR #38 open, merges with ANDROIDEDGEAICORE-20)
- 2026-11-10: cached tokens on reuse verified on the merged branch (emulator 320/339, phone 17); ready to close
- 2026-11-10: closed (cached tokens verified; closed with the batch)
