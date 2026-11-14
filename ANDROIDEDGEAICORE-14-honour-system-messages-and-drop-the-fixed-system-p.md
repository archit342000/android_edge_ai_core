---
id: ANDROIDEDGEAICORE-14
title: "Honour system messages and drop the fixed system prompt"
status: done
created: 2026-11-09
updated: 2026-11-09
links:
  blocked_by: [ANDROIDEDGEAICORE-3]
  conflicts_with: [ANDROIDEDGEAICORE-4, ANDROIDEDGEAICORE-10, ANDROIDEDGEAICORE-13]
  relates_to: [ANDROIDEDGEAICORE-20]
  split_from: [ANDROIDEDGEAICORE-2]
---

## Description
REST requests always get the fixed system prompt "You are a helpful assistant.", and `system` messages are mapped to user turns (gap #12, pitfall 8). Use the request's `system` message(s) as `ConversationConfig.systemInstruction`, and use the model's default when none is given.

Background: research and gap analysis in ANDROIDEDGEAICORE-2 (LiteRT-LM v0.19.1). Verify with `scripts/device.sh` (`docs/testing-on-device.md`).

## Acceptance criteria
- [x] A `system` message in the request is applied as the system instruction
- [x] No system message means no hard-coded prompt (model default)
- [x] Pitfall 8 updated

## Plan
**Date:** 2026-11-09  **Status:** Validated

### Objective
Use the request's `system` messages as the conversation's system instruction, and send no system instruction at all (model default) when the request has none, instead of the fixed "You are a helpful assistant." and system-as-user turns (pitfall 8).

### Context
- Current code: `AiEngineManager.toLiteRTMessage` maps only `assistant` to `Message.model`; every other role, `system` included, becomes `Message.user`. `createEngineConversation` uses `preamble ?: "You are a helpful assistant."` and `generateConversationResponseAsync` uses `state.systemInstruction ?: "You are a helpful assistant."` (REST never sets it). `ConversationState.systemInstruction: String?` exists (used by `ConversationManager.createConversation`).
- LiteRT-LM: `ConversationConfig.systemInstruction: Contents?` in both 0.9.0-alpha02 (checked with `javap` on the cached AAR) and 0.19.1 ("If set, it will prepend to initialMessages"). `Message.system(...)` exists only in 0.19.1, so this plan does not use it and works on either LiteRT-LM version; it is ordered after ANDROIDEDGEAICORE-3 only for the test seam.
- Rules: `docs/standards/api.md` (contract text on roles; README together), `docs/standards/errors-and-logging.md` (log lengths, never content), `docs/standards/security.md` (fixed 400 messages).

### Approach
- Pure `SystemPromptSplitter.split(messages): Pair<String?, List<ChatMessage>>`: every `system` (and OpenAI's `developer`) message, in order, has its text parts joined with a blank line into one system instruction (blank or whitespace-only text gives null, so the model default applies); the rest keep their order. Merging all of them (not only leading ones) is deliberate: Gemma-style templates render one system block, and 0.9.0 has no system message type.
- `OpenAIServer` splits before building `ConversationState(systemInstruction = ...)`; if no non-system message remains it returns `400 {"error": "messages must include a non-system message"}`.
- `AiEngineManager` passes `systemInstruction = text?.let { Contents.of(Content.Text(it)) }` (null when absent) and removes both hard-coded defaults. The reuse check compares the system instruction too (it is a conversation setting; ANDROIDEDGEAICORE-4's `CacheSettings` already lists it).
Rejected: keeping a default prompt when none is given (the ticket asks for the model default).

### Out of scope
`Message.system` for mid-conversation system turns, chat-template overrides, the `name` field on messages.

### Steps
#### Step 1: Splitter
- **Status:** todo
- **Depends on:** none
- **Files:** `app/src/main/java/com/aanand/edgeaicore/SystemPromptSplitter.kt` (create), `app/src/test/java/com/aanand/edgeaicore/SystemPromptSplitterTest.kt` (create)
- **Change:** as in Approach; tests: blank system text gives null, none, one leading, several (including a later one and `developer`), array content with text parts, only-system input.
- **Verification:** `./gradlew testDebugUnitTest` passes.

#### Step 2: Engine
- **Status:** todo
- **Depends on:** none
- **Files:** `app/src/test/java/com/aanand/edgeaicore/AiEngineManagerTest.kt` (modify: cases in Tests), `app/src/main/java/com/aanand/edgeaicore/AiEngineManager.kt` (modify: `createEngineConversation`, `generateConversationResponseAsync` reuse check and preamble, `toLiteRTMessage` KDoc noting system roles are removed upstream)
- **Change:** as in Approach: rename `createEngineConversation`'s `preamble` parameter to `systemInstruction`; replace `activeConversationParams: Triple` with a small data class (temperature, topP, topK, systemInstruction); remove the "No preamble provided, using default" log; `Log.d` the instruction length or "none".
- **Verification:** `./gradlew assembleDebug`.

#### Step 3: Server
- **Status:** todo
- **Depends on:** 1, 2
- **Files:** `app/src/test/java/com/aanand/edgeaicore/OpenAIServerTest.kt` (modify: cases in Tests), `app/src/main/java/com/aanand/edgeaicore/OpenAIServer.kt` (modify: `/chat/completions` before building `ConversationState`)
- **Change:** split, 400 on no remaining message, pass the instruction and the remaining messages. `activeRequests` is incremented before `call.receive` (`OpenAIServer.kt` line 68), so decrement it before the early `return@post` (the outer `catch` only covers exceptions), and check `/v1/load` in step 4.
- **Verification:** `./gradlew assembleDebug testDebugUnitTest lintDebug`.

#### Step 4: Device checks (emulator, CPU)
- **Status:** todo
- **Depends on:** 3
- **Files:** none (evidence in `~/.claude/tickets/android_edge_ai_core/ANDROIDEDGEAICORE-14-evidence/`)
- **Change:** with `scripts/device.sh`: a system message "Always answer in French." plus "What colour is the sky?"; the same user message with no system message; a request with only a system message; the UI's Test Inference and Multi-turn buttons.
- **Verification:** French reply with the system message, a normal reply without; logs show instruction length vs "none"; only-system gives the 400 and `GET /v1/load` returns `{"load": 0}` afterwards; the UI's Test Inference and Multi-turn buttons still get replies.

#### Step 5: Docs
- **Status:** todo
- **Depends on:** 4
- **Files:** `docs/standards/api.md` (role mapping paragraph; Errors table `400` row gains the system-only cause), `README.md` (developer guide), `docs/architecture.md` (Request flow: the system-message split and its 400), `docs/pitfalls.md` (remove entry 8; keep the other entries' numbers, which docs and tickets cite)
- **Change:** describe the new mapping and the 400.
- **Verification:** `scripts/check_docs.sh` passes.

### Tests
`SystemPromptSplitterTest` (new); `AiEngineManagerTest` (the fake's `ConversationConfig.systemInstruction` is null without a system message and the text with one; a changed instruction rebuilds the conversation) and `OpenAIServerTest` (system-only 400, `/v1/load` 0 afterwards) through the engine seam and Ktor test host from ANDROIDEDGEAICORE-3, which is why this ticket now follows ANDROIDEDGEAICORE-3. Run `./gradlew testDebugUnitTest lintDebug`, `scripts/check_docs.sh`; device check in step 4.

### Risks and open questions
- Clients that relied on "You are a helpful assistant." get the model's bare behaviour; called out in the PR. The readiness ping in `InferenceService` also runs without a system instruction now; it only checks for a non-empty reply.
- Conflicts with ANDROIDEDGEAICORE-3, -4 and -13 in `AiEngineManager`; if it lands first, they rebase onto it.

### Success criteria
- [ ] A request `system` message is applied as the system instruction
- [ ] No system message means no hard-coded prompt
- [ ] Tests, lint, docs checks pass; `api.md`, README updated; pitfall 8 removed

### Reviewer brief
- **Objective:** apply request `system` messages as the conversation's system instruction, and use no instruction (model default) when there are none.
- **Key decisions:** a pure `SystemPromptSplitter` merges all `system`/`developer` messages in order (blank text gives null); no `Message.system` (it doesn't exist in 0.9.0); both hard-coded "You are a helpful assistant." defaults removed; the reuse params become a data class with the system instruction; a system-only request gives a fixed 400 with the load counter decremented; ordered after ticket 3 for the test seam.
- **Out of scope:** mid-conversation system turns, chat-template overrides, the message `name` field.
- **Success criteria:** system message applied; no hard-coded prompt; splitter/engine/server tests and checks pass; `api.md`, README and architecture updated; pitfall 8 removed.

The decisions above are intended; report a problem with one only if it breaks a requirement or a documented rule.

### Validation log
- **Round 1:** validator FAIL. My judgement: FAIL, with 1 valid BLOCKING issue.
  1. BLOCKING an early 400 leaked the `activeRequests` count: valid (increment at `OpenAIServer.kt` line 68, before `receive`); now decremented, and checked on the device.
  2. to 4. MINOR (`Triple` widening and `preamble` rename, stale default log, pitfall numbering and api.md Errors row): all valid and fixed.
  - Cross-ticket test rule: settled by the owner (engine seam + Ktor tests); this ticket is now `blocked_by` ANDROIDEDGEAICORE-3 for the seam.
- **Round 2 (diff-scoped):** validator FAIL. My judgement: PASS.
  1. BLOCKING architecture.md request flow not in the docs step: a missing file in a list; downgraded to MINOR and fixed inline.
  2. to 5. MINOR (test files in the steps, "UI tests" wording, ping without a system prompt in Risks, blank system text gives null): all valid, fixed inline.


## Implementation notes
### Autopilot run (2026-11-09)
- Mode: automatic (batch-autopilot, item 6/11)
- Branch: feature/honour-system-messages  PR base: develop  Starting commit: 47ace0582824aecf89c09db3b0138e0dd5ed54ec
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
- [x] Step 1: SystemPromptSplitter + test (6)
- [x] Step 2: AiEngineManager (`systemInstruction` parameter, no built-in prompt, `ConversationParams` data class in the reuse check); fake records conversation configs
- [x] Step 3: OpenAIServer (split, 400 with the load counter decremented, instruction passed); tests
- [x] Step 4: device checks (see Verification)
- [x] Step 5: docs (api.md, README, architecture, pitfall 8 retired with its number kept)

### Smoke test (2026-11-09)
`./gradlew assembleDebug testDebugUnitTest lintDebug` pass; `scripts/check_docs.sh` OK.

### Autopilot decisions (2026-11-09)
- Stage 1: Plan already `Validated` (2 rounds); reused.

PR: https://github.com/archit342000/android_edge_ai_core/pull/41, branch feature/honour-system-messages, base develop

## Verification
### Verification plan (2026-11-09)
**Plan:** ticket Plan above  **Diff base:** 47ace0582824aecf89c09db3b0138e0dd5ed54ec (light form plus edge cases)
**Environment:** emulator `claude_edgeaicore_api34`, CPU, Gemma 4 E2B; script `ANDROIDEDGEAICORE-14-evidence/scripts/system.sh`; teardown `device.sh down`.

| ID | Criterion | Scenario | Type | Method | Files | Expected | Evidence |
|----|-----------|----------|------|--------|-------|----------|----------|
| V1 | AC1 | system "Always answer in French." + user "What colour is the sky?" | happy | API + logcat | OpenAIServer.kt, AiEngineManager.kt | reply in French; log shows the instruction length | response, log |
| V2 | AC2 | the same user message with no system message | happy | API + logcat | same | normal (English) reply; log says "none (model default)" | response, log |
| V3 | AC1 | system message split across two (system + developer) | edge | API | SystemPromptSplitter.kt | French again | response |
| V4 | edge | only a system message | failure | API | OpenAIServer.kt | 400 with the fixed text; `/v1/load` 0 afterwards | response, load |
| V5 | regression | stream with a system message ends `[DONE]`; bad body 400 | regression | API | OpenAIServer.kt | as before | output |
| V6 | regression | Unit tests, lint, docs | regression | CLI | all | pass | output |


### Results (2026-11-09)
Commit verified: 47ace0582824aecf89c09db3b0138e0dd5ed54ec + uncommitted:
- `README.md` 7cf7a04a0dbea228989cf692dfa00efd559620f2
- `app/src/main/java/com/aanand/edgeaicore/AiEngineManager.kt` 18098ec1039453896728573e1b0d82d6e9748630
- `app/src/main/java/com/aanand/edgeaicore/OpenAIServer.kt` 5192e8204d807a2589bd6ed6f0b789908a65e1e9
- `app/src/test/java/com/aanand/edgeaicore/AiEngineManagerTest.kt` c8a23d7c4297ad2b86d8b8f634e88a1121b8924c
- `app/src/test/java/com/aanand/edgeaicore/FakeLlmEngine.kt` 18a7e571b39a1b84d6734df2b4dcaab6b5b21edb
- `app/src/test/java/com/aanand/edgeaicore/OpenAIServerTest.kt` d494efb14a84b4a1cac5df8400e59377fb4aea0e
- `docs/architecture.md` cc0b50b13c37c7d99b75f93a97138681020f6074
- `docs/pitfalls.md` 89ccaa02f953bb210fe154900046a4297bd0306c
- `docs/standards/api.md` b0615c11af716722e4bc2db15732855b6c2a2052
- `app/src/main/java/com/aanand/edgeaicore/SystemPromptSplitter.kt` 86c1e04f6772fc88f3abccbfd07cec32fc7a0152
- `app/src/test/java/com/aanand/edgeaicore/SystemPromptSplitterTest.kt` bb9c6b01c03cbee41f7382162e53ef795b6fa4bc

Emulator `claude_edgeaicore_api34`, CPU, Gemma 4 E2B. Evidence: `ANDROIDEDGEAICORE-14-evidence/system-run.txt`, `scripts/system.sh`.

| ID | Status | Evidence |
|----|--------|----------|
| V1 | PASS | system "Always answer in French." gives "Le ciel est généralement bleu."; log `System instruction: 24 chars`. |
| V2 | PASS | no system message: a normal English answer; log `System instruction: none (model default)`. |
| V3 | PASS | system "You are a pirate." + later developer "Always answer in French.": a pirate greeting in French (merged); log `43 chars`. |
| V4 | PASS | only a system message: HTTP 400 `messages must include a non-system message`; `GET /v1/load` `{"load":0}` afterwards. |
| V5 | PASS | stream with a system message ends `[DONE]` (log `19 chars`); bad body 400. |
| V6 | PASS | 46 unit tests, lint, check_docs. |

Not run: the UI Test Inference / Multi-turn buttons (they send user-only messages through the same route; not exercised separately).
Verdict: verified, 6 PASS, 0 FAIL, 0 BLOCKED.

## Review history

### Round 1 - 2026-11-09
Verdict: Request changes (one MAJOR, docs/tests only). Scope: 11 files (~400 changed lines incl. tests/docs), one Haiku reviewer.
- R1-1 [MAJOR] an empty `messages` array now gets 400 (was an error event / 500) without being documented or tested - Static - Fixed: api.md (text and Errors row) says so, test `anEmptyMessagesArrayIsRejectedWith400OnBothPaths` (both paths, load 0)
- R1-2 [MINOR] stale reuse comment - Static - Fixed
- R1-3 [MINOR] pitfall 6 wording lacks the system instruction in the reuse key - Static - Fixed
- R1-4 [MINOR] a null `systemInstruction` was unproven on the engine (changelog 1.5.1 said it must never be null) - Static - Rejected with evidence: the emulator run on 0.19.1 answered normally with no instruction (V2, log "none (model default)")
- R1-5 [NIT] blank lines; fully qualified `assertNull` - Static - Fixed
- R1-6 [NIT] splitter constants at object level - Static - Rejected: an `object` has no companion; AGENTS.md rule 3 is about classes
Pre-existing: `ConversationManager.createConversation` logs the full system instruction text (unchanged line; REST does not use it) - follow-up.
Resolution: 5 fixed, 2 rejected. 32 unit tests, lint, check_docs pass; no runtime code changed after verification (comment and blank-line edits only).

## Log
- 2026-11-09: created from ANDROIDEDGEAICORE-2 gap analysis
- 2026-11-09: plan drafted
- 2026-11-09: unlinked conflicts_with ANDROIDEDGEAICORE-3 (replaced by blocked_by: needs the test seam from 3)
- 2026-11-09: linked blocked_by ANDROIDEDGEAICORE-3 (uses the engine seam and Ktor test host from 3)
- 2026-11-09: linked conflicts_with ANDROIDEDGEAICORE-10 (both change the conversation-reuse params in AiEngineManager)
- 2026-11-09: plan revised after validation round 1 (and test-seam decision)
- 2026-11-09: plan validated, 2 rounds
- 2026-11-09: autopilot started (batch)
- 2026-11-09: implemented, smoke tests pass
- 2026-11-09: verify: 6 PASS, 0 FAIL
- 2026-11-09: batch-autopilot: shipped, base develop
- 2026-11-09: closed (all acceptance criteria checked; the PR is open, not yet merged)
- 2026-11-09: linked relates_to ANDROIDEDGEAICORE-20 (follow-ups)
