---
id: ANDROIDEDGEAICORE-10
title: "Support context-size and generation limits"
status: done
created: 2026-11-09
updated: 2026-11-09
links:
  blocked_by: [ANDROIDEDGEAICORE-3]
  conflicts_with: [ANDROIDEDGEAICORE-4, ANDROIDEDGEAICORE-9, ANDROIDEDGEAICORE-13, ANDROIDEDGEAICORE-14]
  relates_to: [ANDROIDEDGEAICORE-15, ANDROIDEDGEAICORE-17, ANDROIDEDGEAICORE-18, ANDROIDEDGEAICORE-19, ANDROIDEDGEAICORE-20]
  split_from: [ANDROIDEDGEAICORE-2]
---

## Description
`maxNumTokens` (KV size) is never set, and the request's `max_tokens` and penalties are ignored (gap #8). Make `maxNumTokens` configurable, map `max_tokens` / `max_completion_tokens` to `maxOutputToken`, and map `presence_penalty`, `frequency_penalty` and `seed` to the LiteRT-LM configs. Validate the ranges (400 on bad input).

Background: research and gap analysis in ANDROIDEDGEAICORE-2 (LiteRT-LM v0.19.1). Verify with `scripts/device.sh` (`docs/testing-on-device.md`).

## Acceptance criteria
- [x] Context size configurable and applied at engine creation
- [x] `max_tokens` limits output length
- [x] Penalties and seed accepted and applied; invalid values give 400
- [x] `api.md` updated

## Plan
**Date:** 2026-11-09  **Status:** Validated

### Objective
Give control over memory and output length: a configurable context size (`EngineConfig.maxNumTokens`), and per-request `max_tokens` / `max_completion_tokens`, `presence_penalty`, `frequency_penalty` and `seed`, with range validation that returns 400. Runs after ANDROIDEDGEAICORE-3.

### Context
- Current code: `AiEngineManager.loadModel` sets no `maxNumTokens`; `ChatCompletionRequest` has only `model`, `messages`, `temperature`, `top_p`, `top_k`, `stream`; nothing validates them (`docs/standards/security.md` asks for range checks and a bounded `messages` list). Parse failures and every other pre-generation exception become `400 "Bad Request: ${e.message}"` (pitfall 2).
- 0.19.1: `EngineConfig.maxNumTokens` (KV size; `require(> 0)`), `SamplerConfig.seed` (per conversation), `Conversation.sendMessageAsync(..., repetitionPenaltyConfig, ..., maxOutputToken, ...)` per call; `RepetitionPenaltyConfig(repetitionPenalty, presencePenalty, frequencyPenalty, windowSize)` with `Float?` fields. `ModelInfo.maxContextTokens()` gives the model's maximum.
- Rules: `docs/standards/api.md` (additive fields; snake_case; README together; DTO test), `docs/standards/android.md` (extras as `companion` constants; sticky-restart state in `inference_service_prefs`; strings in `strings.xml`), `docs/standards/security.md` (fixed short 400 messages).

### Approach
- **Context size:** a number field "Context size (tokens)" on the Backend Settings page, empty meaning the model default; stored in `litertlm_prefs`, sent as `EXTRA_MAX_NUM_TOKENS`, saved in `inference_service_prefs`, passed to `loadModel` and into `EngineConfig.maxNumTokens`. `loadModel`'s early return (same path, engine loaded) also compares the loaded `maxNumTokens`, so a changed context size reloads the engine; the recursive GPU-to-CPU retry passes it on.
- **Request fields:** add `max_tokens`, `max_completion_tokens`, `presence_penalty`, `frequency_penalty`, `seed` (nullable) to `ChatCompletionRequest`. `max_completion_tokens` wins over `max_tokens` (OpenAI's newer name).
- **Validation:** pure `ChatRequestValidator.validate(request)` returns null or a fixed message; ranges: `messages` 1..`MAX_MESSAGES` (256), `temperature` 0..2, `top_p` (0, 1], `top_k` >= 1, penalties -2..2, max tokens >= 1, `seed` any int. `OpenAIServer` returns `400 ErrorResponse(<fixed message>)` before generation.
- **Mapping:** max tokens to `maxOutputToken` (the SDK describes it as "per decode step"; the step 6 device check confirms the whole reply is capped, and if it is not, the owner is told before merging); penalties to one `RepetitionPenaltyConfig` (only when either is set); both per call. `seed` goes into `SamplerConfig` (per conversation), carried on `ConversationState` like `temperature`. Each REST request currently builds its own conversation, so the seed applies to every request; it is also part of the reuse settings: `activeConversationParams` changes from `Triple` to a small data class including `seed` (or the field is added to `CacheSettings` if ANDROIDEDGEAICORE-4 has landed; whichever lands second rebases). The readiness ping keeps the defaults (seed 0, model context size from the setting).
Rejected: a context-size REST field (engine-level, would force a reload per request).

### Out of scope
Checking the context size against the model's `maxContextTokens()` (the engine reports an invalid size at load), `finish_reason: "length"`, `stop` sequences, `logit_bias`, `n > 1`, fixing the other 400 paths from pitfalls 2 and 3.

### Steps
#### Step 1: Request DTO and validator
- **Status:** todo
- **Depends on:** none
- **Files:** `app/src/main/java/com/aanand/edgeaicore/OpenAIModels.kt` (modify: `ChatCompletionRequest`), `app/src/main/java/com/aanand/edgeaicore/ChatRequestValidator.kt` (create; limits as `companion` constants)
- **Change:** as in Approach.
- **Verification:** step 2 tests.

#### Step 2: Unit tests
- **Status:** todo
- **Depends on:** 1
- **Files:** `app/src/test/java/com/aanand/edgeaicore/ChatRequestValidatorTest.kt` (create), `app/src/test/java/com/aanand/edgeaicore/OpenAIModelsTest.kt` (modify: Gson parsing of the new fields)
- **Change:** each bound accepted at the edge and rejected just outside; empty and oversized `messages`; `max_completion_tokens` precedence.
- **Verification:** `./gradlew testDebugUnitTest` passes.

#### Step 3: Engine mapping
- **Status:** todo
- **Depends on:** 1
- **Files:** `app/src/main/java/com/aanand/edgeaicore/ConversationManager.kt` (modify: `ConversationState` gains `seed: Int = 0`), `app/src/test/java/com/aanand/edgeaicore/ConversationStateTest.kt` (create), `app/src/test/java/com/aanand/edgeaicore/AiEngineManagerTest.kt` (modify), `app/src/main/java/com/aanand/edgeaicore/LlmEngine.kt` (modify: `LlmConversation.sendMessageAsync` gains the per-call options; the adapter forwards them to `Conversation.sendMessageAsync`), `app/src/main/java/com/aanand/edgeaicore/AiEngineManager.kt` (modify: `loadModel` takes `maxNumTokens: Int?`; `createEngineConversation` passes `seed`; reuse comparison includes `seed`; `generateConversationResponseAsync` takes per-call `GenerationOptions(maxOutputTokens, presencePenalty, frequencyPenalty)` and passes them to `sendMessageAsync`)
- **Change:** as in Approach. `ConversationState` is also Gson-persisted by `ConversationManager`; Gson leaves a missing `Int` at 0 (the default), so old files load unchanged and no migration is needed (`review.md` item 8); a test proves it.
- **Verification:** `./gradlew assembleDebug testDebugUnitTest`; new `ConversationStateTest` parses a persisted-format JSON without `seed` and gets 0; `AiEngineManagerTest` (engine seam, ANDROIDEDGEAICORE-3) checks: `maxNumTokens` reaches the fake factory's `EngineConfig` and survives the CPU retry; a changed value reloads with the same path; per-call options reach the fake conversation; a changed seed rebuilds the conversation.

#### Step 4: Server wiring
- **Status:** todo
- **Depends on:** 3
- **Files:** `app/src/main/java/com/aanand/edgeaicore/OpenAIServer.kt` (modify: `/chat/completions`)
- **Files:** also `app/src/test/java/com/aanand/edgeaicore/OpenAIServerTest.kt` (modify).
- **Change:** validate after `receive`, return the 400 (decrementing `activeRequests`, which is incremented before `receive`), then build `ConversationState` with `seed` and pass `GenerationOptions`. `OpenAIServerTest` cases: each invalid field gives its fixed 400; `/v1/load` is 0 afterwards; a valid request passes the options to the fake.
- **Verification:** `./gradlew assembleDebug testDebugUnitTest lintDebug`.

#### Step 5: Context-size setting
- **Status:** todo
- **Depends on:** 3
- **Files:** `app/src/main/res/layout/activity_main.xml` (modify: `page_backend`, a `TextInputEditText` `et_context_size`, numeric), `app/src/main/res/values/strings.xml` (modify), `app/src/main/java/com/aanand/edgeaicore/MainActivity.kt` (modify: `onCreate` restore, `startInferenceService`; pref key constant), `app/src/main/java/com/aanand/edgeaicore/InferenceService.kt` (modify: `EXTRA_MAX_NUM_TOKENS`, save/restore, `loadModel` call)
- **Change:** as in Approach; non-positive input is rejected in the UI with a short message.
- **Verification:** `scripts/device.sh ui` shows the field; value survives an app restart.

#### Step 6: Device checks (emulator, CPU)
- **Status:** todo
- **Depends on:** 4, 5
- **Files:** none (evidence in `~/.claude/tickets/android_edge_ai_core/ANDROIDEDGEAICORE-10-evidence/`)
- **Change:** set context size 1024 and start; check the engine log shows it (and `meminfo` vs default). Requests: `max_tokens: 5` on a long prompt; the same with `stream: true`; `seed: 42`, `temperature: 1` twice (same output expected); `presence_penalty: 1.5`; invalid values (`temperature: 5`, `max_tokens: 0`, `top_p: 0`, empty `messages`).
- **Verification:** short reply for `max_tokens` (and only a few chunks streamed); identical replies for the same seed; the penalty request returns 200; each invalid request returns 400 with its fixed message.

#### Step 7: Docs
- **Status:** todo
- **Depends on:** 6
- **Files:** `docs/standards/api.md` (Chat request table, Errors), `README.md` (developer guide; app guide: context size)
- **Change:** document fields, ranges and the setting.
- **Verification:** `scripts/check_docs.sh` passes.

### Tests
`ChatRequestValidatorTest`, `ConversationStateTest` (new), `OpenAIModelsTest`, `AiEngineManagerTest`, `OpenAIServerTest` (extended through the engine seam and Ktor test host from ANDROIDEDGEAICORE-3). `InferenceService` and `MainActivity` changes are covered by device checks (agreed exception in `docs/standards/testing.md`, ANDROIDEDGEAICORE-3). Run `./gradlew testDebugUnitTest lintDebug`, `scripts/check_docs.sh`; device check in step 6.

### Risks and open questions
- Seeded sampling may not be bit-identical across runs on every backend; if the two replies differ, record it and keep `seed` accepted (documented as best effort).
- `MAX_MESSAGES` = 256 is a new limit chosen here (`security.md` asks for a bound but names none).
- Rejecting requests that today succeed (for example `temperature: 3`) is a behaviour change for existing clients; it is what `security.md` asks for, and the PR must call it out.

### Success criteria
- [ ] Context size configurable and applied at engine creation
- [ ] `max_tokens` / `max_completion_tokens` limit output
- [ ] Penalties and seed accepted and applied; invalid values give 400
- [ ] Tests, lint, docs checks pass; `api.md` and README updated

### Reviewer brief
- **Objective:** configurable context size (`maxNumTokens`) and per-request `max_tokens`/`max_completion_tokens`, `presence_penalty`, `frequency_penalty`, `seed`, with range validation giving fixed 400s.
- **Key decisions:** context size in Backend Settings → extra → `inference_service_prefs` → `EngineConfig`; reload when it changes; passed on in the CPU retry; a pure `ChatRequestValidator` (ranges listed, `MAX_MESSAGES` 256); per-call `maxOutputToken` and `RepetitionPenaltyConfig` through `LlmConversation`; `seed` in `SamplerConfig` and the reuse settings; `ConversationState.seed` defaults to 0 for old files.
- **Out of scope:** checking against the model's max context, `finish_reason: "length"`, `stop`, `logit_bias`, `n > 1`, other 400 paths.
- **Success criteria:** context size applied at engine creation; output capped by max tokens (confirmed on device); penalties/seed applied; invalid values give 400; tests and checks pass; `api.md`/README updated.

The decisions above are intended; report a problem with one only if it breaks a requirement or a documented rule.

### Validation log
- **Round 1:** validator FAIL. My judgement: FAIL, with 2 valid BLOCKING issues.
  1. BLOCKING a changed `maxNumTokens` was ignored by `loadModel`'s early return: valid (`AiEngineManager.kt` early return compares only the path); now compared.
  2. BLOCKING the recursive CPU retry dropped `maxNumTokens`: valid; it is now passed on.
  3. to 9. MINOR (model-max check moved to out of scope, `Triple` → data class with `seed`, tests, persisted-state proof, seed per request, ping defaults, `MAX_MESSAGES` as a decision): all valid and fixed.
- **Round 2 (diff-scoped):** validator FAIL. My judgement: PASS.
  1. BLOCKING `LlmEngine.kt` missing from the step Files lists for the per-call options: the interface change follows ticket 3's rule ("later tickets add interface methods"), so only the file list was incomplete. Downgraded to MINOR and fixed inline.
  2. MINOR `maxOutputToken` is "per decode step" in the SDK: valid; the device check now confirms the whole reply is capped.


## Implementation notes
### Autopilot run (2026-11-09)
- Mode: automatic (batch-autopilot, item 7/11)
- Branch: feature/generation-limits  PR base: develop  Starting commit: 47ace0582824aecf89c09db3b0138e0dd5ed54ec
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
- [x] Steps 1-2: request fields, `ChatRequestValidator`, tests (validator, models)
- [x] Step 3: engine mapping (`maxNumTokens` incl. reload on change and the CPU retry, `GenerationOptions` per call, `ConversationParams` with seed, `ConversationState.seed`); tests incl. persisted-state test
- [x] Step 4: server wiring (400 with the load counter released); tests
- [x] Step 5: Context size field (layout, strings, MainActivity, InferenceService extra and saved pref)
- [x] Step 6: device checks (see Verification)
- [x] Step 7: docs (api.md, README)

### Smoke test (2026-11-09)
`./gradlew assembleDebug testDebugUnitTest lintDebug` pass; `scripts/check_docs.sh` OK.

### Autopilot decisions (2026-11-09)
- Stage 1: Plan already `Validated` (2 rounds); reused.

PR: https://github.com/archit342000/android_edge_ai_core/pull/42, branch feature/generation-limits, base develop

## Verification
### Verification plan (2026-11-09)
**Plan:** ticket Plan above  **Diff base:** 47ace0582824aecf89c09db3b0138e0dd5ed54ec (full form for the UI/service part; light for the API)
**Environment:** emulator `claude_edgeaicore_api34`, CPU, Gemma 4 E2B; script `ANDROIDEDGEAICORE-10-evidence/scripts/limits.sh`; teardown `device.sh down`.

| ID | Criterion | Scenario | Type | Method | Files | Expected | Evidence |
|----|-----------|----------|------|--------|-------|----------|----------|
| V1 | AC1 | Type 1024 in the Context size field (Backend Settings), enable the server | happy | UI (adb) + logcat | MainActivity.kt, InferenceService.kt, AiEngineManager.kt | engine config in logcat shows `maxNumTokens=1024`; the field keeps the value | logcat, ui dump |
| V2 | AC2 | `max_tokens: 5` on a request that asks for a long text, non-stream and stream | happy | API | OpenAIServer.kt, LlmEngine.kt | reply is cut to a few tokens (usage-free: compare length); stream sends only a few chunks | responses |
| V3 | AC2 | `max_completion_tokens` wins over `max_tokens` | edge | API | OpenAIModels.kt | `max_tokens: 100, max_completion_tokens: 3` gives a short reply | response |
| V4 | AC3 | `seed: 42`, `temperature: 1` twice | happy | API | AiEngineManager.kt | same reply twice (best effort) | responses |
| V5 | AC3 | `presence_penalty` 1.5 and `frequency_penalty` -1 | happy | API | LlmEngine.kt | 200 with a reply | response |
| V6 | AC3 | invalid: `temperature: 5`, `max_tokens: 0`, `top_p: 0`, `messages: []` | failure | API | ChatRequestValidator.kt | each 400 with its fixed message; `/v1/load` 0 after | responses |
| V7 | regression | plain request and stream still work; bad body 400 | regression | API | OpenAIServer.kt | 200 / `[DONE]` / 400 | output |
| V8 | regression | Unit tests, lint, docs | regression | CLI | all | pass | output |


### Results (2026-11-09)
Commit verified: 47ace0582824aecf89c09db3b0138e0dd5ed54ec + uncommitted:
- `README.md` 08fb513f40544c476257e93644f8622bc57fce34
- `app/src/main/java/com/aanand/edgeaicore/AiEngineManager.kt` dbafefa11e28969e050884d856d904f856affe11
- `app/src/main/java/com/aanand/edgeaicore/ConversationManager.kt` 110140b032ff486ec2f7b7adcbdd6154187dbf35
- `app/src/main/java/com/aanand/edgeaicore/InferenceService.kt` 6299f503ab07dab21b2ac49cc67c3ee3a309c875
- `app/src/main/java/com/aanand/edgeaicore/LlmEngine.kt` 2019962071d4ed04f4c08e1137177c5e8b28d1c2
- `app/src/main/java/com/aanand/edgeaicore/MainActivity.kt` 52d521e21567c56e4457c5f355cfb848a92981f8
- `app/src/main/java/com/aanand/edgeaicore/OpenAIModels.kt` b2848f24281b39e5340092d290e50aa09fc235c7
- `app/src/main/java/com/aanand/edgeaicore/OpenAIServer.kt` 9d900d2f22a5296c6944f46f8977ec36dd855a69
- `app/src/main/res/layout/activity_main.xml` 8f77ad568fa6f4705821f123d96e8243bf938809
- `app/src/main/res/values/strings.xml` 5f1852a3111a2fd67f68e0be35707773e6bb97b4
- `app/src/test/java/com/aanand/edgeaicore/AiEngineManagerTest.kt` 0a5b2b2b28284437bd95b4331b126e85962b2092
- `app/src/test/java/com/aanand/edgeaicore/FakeLlmEngine.kt` d453f5631335b0d75bc9a6fd118d0c17311ef6a8
- `app/src/test/java/com/aanand/edgeaicore/OpenAIModelsTest.kt` 7bcf686be96dda9964c928fabf4fcea0bbd18b7c
- `app/src/test/java/com/aanand/edgeaicore/OpenAIServerTest.kt` 48d5d7b72cc400ed9ec44de4e9577cbc57926c1c
- `docs/standards/api.md` 54b10e2073c9f3d1e0b5556eddb211088dbbe4cd
- `app/src/main/java/com/aanand/edgeaicore/ChatRequestValidator.kt` 7a8e64474ba52c4e62ca8c70c58c9b96cfdfee5e
- `app/src/test/java/com/aanand/edgeaicore/ChatRequestValidatorTest.kt` 45b6116ae51abc28d0e618aa95e2ce5c13304e01
- `app/src/test/java/com/aanand/edgeaicore/ConversationStateTest.kt` ff1f53a95cf13308d923b217b4ef58ac0420f694

Emulator `claude_edgeaicore_api34`, CPU, Gemma 4 E2B. Evidence: `ANDROIDEDGEAICORE-10-evidence/limits-run.txt` (`limits-run1.txt`: first run, where the UI's default GPU backend failed on the emulator with `Can not find OpenCL library`; the script then selected CPU through the UI), `scripts/limits.sh`.

| ID | Status | Evidence |
|----|--------|----------|
| V1 | PASS | Typed 1024 into the Context size field via adb and enabled the server: engine config shows `maxNumTokens=1024`; `litertlm_prefs` `context_size` 1024 and `inference_service_prefs` `saved_max_num_tokens` 1024. (A restart of the app to see the field restored was not run.) |
| V2 | PASS | `max_tokens: 5` on a long-story prompt: reply `## The Obsidian Heart of`; streamed: exactly 5 content chunks (the whole reply is capped; with `max_tokens: 60` the reply was 299 chars). |
| V3 | PASS | `max_tokens: 100, max_completion_tokens: 3`: reply `## The Obsidian` (the smaller completion limit won). |
| V4 | PASS | `seed: 42, temperature: 1` twice: identical replies; `seed: 7`: a different reply. |
| V5 | PASS | `presence_penalty: 1.5, frequency_penalty: -1`: HTTP 200. (Their effect on the text was not measured.) |
| V6 | PASS | `temperature: 5`, `max_tokens: 0`, `top_p: 0`, `messages: []`: HTTP 400 each with its fixed message; `GET /v1/load` `{"load":0}` after. |
| V7 | PASS | plain request 200, stream `[DONE]`, bad body 400. |
| V8 | PASS | 38 unit tests, lint (no new issues), check_docs. |

Verdict: verified, 8 PASS, 0 FAIL, 0 BLOCKED.

## Review history

### Round 1 - 2026-11-09
Verdict: Request changes (one MAJOR, rejected below as out of scope per the plan). Scope: 21 files (~430 changed lines incl. tests/docs), one Haiku reviewer.
- R1-1 [MAJOR] a wrong-typed value (`seed: 1.5`, `max_tokens: "abc"`) fails in `call.receive` and gets `400 "Bad Request: <Gson message>"`, which echoes the input - Static - Rejected as out of scope: that is the existing parse-failure path of pitfall 2, and the plan lists "fixing the other 400 paths from pitfalls 2 and 3" under Out of scope. Still a 400 (the ticket's requirement). Recorded as a follow-up for the owner (a fixed message for body parse failures).
- R1-2 [MINOR] a request with no `messages` NPEs in the validator and gets `400 "Bad Request: null"` - Static - Rejected: same pre-existing path (before, the missing list failed the same way and also gave a 400).
- R1-3 [MINOR] `maxOutputToken` is documented as "per decode step" in the SDK, so a total cap is unproven - Static - Rejected with evidence: on the emulator `max_tokens: 5` gave exactly 5 streamed chunks and a 5-token reply (V2), and `max_completion_tokens: 3` a 2-3 token reply (V3).
- R1-4 [MINOR] the persisted-state test does not guard the `seed` default - Static - Fixed: renamed to a compatibility check (and the class to `ConversationManagerTest`, matching its source file, R1-8); the default itself is covered by `seedRoundTripsThroughGson`
- R1-5 [NIT] import order / `EditText` import - Static - Fixed; `String.format` kept (replacing it with `toString()` triggers the lint warning SetTextI18n)
- R1-6 [NIT] fully qualified `assertNull` - Static - Fixed
- R1-7 [NIT] lines over 140 characters in the validator - Static - Fixed (`inRange` helper)
- R1-8 [NIT] test class name - Static - Fixed
Pre-existing: the parse-failure 400 text (pitfall 2) echoes Gson's message.
Resolution: 5 fixed, 3 rejected. 38 unit tests, lint, check_docs pass. Runtime code unchanged apart from a helper refactor and an import; no re-verify needed.

## Log
- 2026-11-09: created from ANDROIDEDGEAICORE-2 gap analysis
- 2026-11-09: plan drafted
- 2026-11-09: linked conflicts_with ANDROIDEDGEAICORE-14 (both change the conversation-reuse params in AiEngineManager)
- 2026-11-09: plan revised after validation round 1 (and test-seam decision)
- 2026-11-09: plan validated, 2 rounds
- 2026-11-09: autopilot started (batch)
- 2026-11-09: implemented, smoke tests pass
- 2026-11-09: verify: 8 PASS, 0 FAIL
- 2026-11-09: batch-autopilot: shipped, base develop
- 2026-11-09: closed (all acceptance criteria checked; the PR is open, not yet merged)
- 2026-11-09: linked relates_to ANDROIDEDGEAICORE-15, ANDROIDEDGEAICORE-17, ANDROIDEDGEAICORE-18, ANDROIDEDGEAICORE-19, ANDROIDEDGEAICORE-20 (follow-ups)
