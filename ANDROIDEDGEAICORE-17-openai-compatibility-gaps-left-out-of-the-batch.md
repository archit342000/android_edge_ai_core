---
id: ANDROIDEDGEAICORE-17
title: "OpenAI compatibility gaps left out of the batch"
status: done
created: 2026-11-09
updated: 2026-11-10
links:
  blocks: [ANDROIDEDGEAICORE-21, ANDROIDEDGEAICORE-23]
  split_into: [ANDROIDEDGEAICORE-21, ANDROIDEDGEAICORE-22, ANDROIDEDGEAICORE-23]
  conflicts_with: [ANDROIDEDGEAICORE-15, ANDROIDEDGEAICORE-16, ANDROIDEDGEAICORE-19]
  blocked_by: [ANDROIDEDGEAICORE-20]
  relates_to: [ANDROIDEDGEAICORE-8, ANDROIDEDGEAICORE-9, ANDROIDEDGEAICORE-10, ANDROIDEDGEAICORE-13, ANDROIDEDGEAICORE-24]
---

## Description
Items the plans listed as out of scope while implementing ANDROIDEDGEAICORE-8, -9, -10 and -13:
- `finish_reason: "length"` when `max_tokens` is reached (today always `stop`).
- `stop` sequences, `n > 1`, `logit_bias`.
- `tool_choice` naming a specific function, parallel tool call flags, a `regex` response format.
- `reasoning_tokens` in `usage` and per-effort thinking budgets.
- Cancelling a non-streaming request when the client disconnects (streaming is handled).
- A pre-check with `ModelInfo.supportsFunctionCalling()` / `supportsThinking()` so a model without tool or thinking support gets a clean 400 (only Gemma 4 E2B was tested).
- Check the requested context size against `ModelInfo.maxContextTokens()` at load.
- `json_schema` output may contain extra properties unless the schema forbids them; document or enforce `additionalProperties: false`.
- Request timeouts and queue limits.

Background: found while running the ANDROIDEDGEAICORE-4 to -14 batch.

## Acceptance criteria
- [ ] Each item decided: implement, document as unsupported, or drop
- [ ] Implemented items covered by tests, `api.md` and README
- [ ] Anything dropped is recorded here with the reason

## Plan
**Date:** 2026-11-09  **Status:** Validated

### Objective
Decide every OpenAI-compatibility gap listed in the Description, implement the ones LiteRT-LM 0.19.1 supports directly at small cost, document the rest as unsupported, and name the ones worth their own ticket.

### Context
- Findings check (2026-11-09), on the PR branches (nothing merged): all items are real gaps.
  - `finish_reason` is always `"stop"` (or `"tool_calls"` on #45) in `OpenAIServer`; nothing compares the output length with `max_tokens`.
  - `stop`, `n`, `logit_bias`, `parallel_tool_calls` are not in `ChatCompletionRequest`; Gson silently ignores them.
  - `OpenAIToolMapper.toolProviders` (#45) accepts `tool_choice` `auto`/`none` only, else 400. `responseFormat` accepts `text`, `json_object`, `json_schema`. `thinkingConfig` maps every effort except `none` to `ThinkingConfig(enableThinking = true, thinkingTokenBudget = -1)` (no budget).
  - `usage` (#38) comes from `BenchmarkInfo.lastPrefillTokenCount`/`lastDecodeTokenCount` (`TokenUsage`); there is no separate thinking count.
  - Non-streaming requests are not cancelled on disconnect (#36 handles streaming only).
  - `ModelInspector`/`RawModelInfo` (#39, #46) read kind, modalities and encoder backends, not tool/thinking support or context size; #42 passes the user's `maxNumTokens` to `EngineConfig` unchecked.
- LiteRT-LM 0.19.1 API (checked with `javap` on the AAR): `ModelInfo.Llm.supportsFunctionCalling()`, `supportsThinking()`, `ModelInfo.maxContextTokens()`, `isDynamicContext()`; `ThinkingConfig(enableThinking, thinkingTokenBudget)`; `ResponseFormat.regex(...)`; `SuppressTokensConfig(tokenIds)` (suppression only, token ids). No stop-sequence parameter, no multi-candidate generation, no finish reason in `Message`/`MessageCallback`, `BenchmarkInfo` has no thinking-token count.
- Depends on ANDROIDEDGEAICORE-20 (#38, #39, #42, #45, #46 on `develop`).
- Rules: AGENTS.md rule 5 and `docs/standards/api.md` "Changing the contract" (additive = minor; update `api.md` and README together, DTO unit tests), `docs/standards/security.md` (fixed 400 messages), `docs/standards/testing.md`.

### Approach
Decision per item:

| Item | Decision | Why |
|------|----------|-----|
| `finish_reason: "length"` | Implement | `completion_tokens >= maxOutputTokens` means the cap stopped it; `tool_calls` keeps precedence |
| `stop` sequences | Document as unsupported (ignored); suggest a follow-up ticket | no engine parameter; needs text matching, a held-back stream buffer and `cancelProcess` |
| `n > 1` | Implement as a fixed 400 (`n must be 1`) | one serial engine; silently returning one choice misleads clients |
| `logit_bias` | Document as unsupported (ignored) | only suppression by model token ids exists; clients cannot know the ids |
| `tool_choice` naming a function | Keep the 400, document | the engine cannot force a call; offering one tool is not the same contract |
| `parallel_tool_calls` | Document as ignored | the model decides; the server already returns every call it emits |
| `regex` response format | Drop | not part of OpenAI's API |
| `reasoning_tokens` | Document as unsupported | no separate thinking count from the engine |
| Per-effort thinking budgets | Implement | `ThinkingConfig.thinkingTokenBudget` exists; map `minimal`/`low`/`medium`/`high` to fixed budgets, `xhigh` unlimited |
| Non-stream cancel on disconnect | Document as unsupported; suggest a follow-up ticket | needs disconnect detection without a write (Netty channel close), its own design |
| Capability pre-check | Implement | 400 `this model does not support tools` / `... reasoning` instead of an engine error |
| Context size vs `maxContextTokens` | Implement as clamp | a larger value is clamped with `Log.w` and a status note; not checked when the model reports 0 or a dynamic context |
| `json_schema` extra properties | Document | the model may add properties unless the schema sets `additionalProperties: false` |
| Request timeouts and queue limits | Document current behaviour; suggest a follow-up ticket | new status codes (429/503/504) and settings are a contract change of their own |

Budgets are `private const val`s at the top of the `OpenAIToolMapper` object (`minimal` 256, `low` 1024, `medium` 4096, `high` 16384), like its existing `MAX_TOOLS`; an `object` has no companion, so this departs from the wording of AGENTS.md rule 3 (proposal for the owner, matching the existing precedent). Whether the model honours them is checked on the device.

Contract impact: `n > 1` (200 today) and the capability refusals (an engine error today: 500 non-streaming, 200 with an SSE error event when streaming) become 400s. `finish_reason: "length"` is a new value of an existing field (additive, minor) and is documented in `api.md` and README. `docs/standards/api.md` counts a status-code change as a major version. These ship in the same release as #42's new 400s, whose version the owner decides in ANDROIDEDGEAICORE-20; if this ticket lands after that release, it is a major version unless the owner explicitly waives the rule (AGENTS.md rule 8). Proposed for the owner's approval (AGENTS.md rule 8): ignored fields (`stop`, `logit_bias`, `parallel_tool_calls`) as a documented exception to AGENTS.md rule 7: rejecting them would break common clients that always send them, while `n > 1` is rare and asks for something the server cannot give at all.

### Out of scope
- Implementing stop sequences, non-stream cancel, timeouts and queue limits (follow-up tickets suggested to the owner).

### Steps
#### Step 1: `finish_reason: "length"`
- **Status:** todo
- **Depends on:** none
- **Files:** `app/src/main/java/com/aanand/edgeaicore/OpenAIServer.kt` (non-streaming `Choice` and the final streaming chunk), a pure helper `finishReason(toolCalls, usage, maxOutputTokens)` in `OpenAIModels.kt` (modify); tests in `OpenAIModelsTest.kt`, `OpenAIServerTest.kt`
- **Change:** `tool_calls` when calls were emitted, else `length` when `maxOutputTokens != null && completionTokens >= maxOutputTokens`, else `stop`.
- **Verification:** unit tests for the three outcomes; route test with `FakeLlmEngine` reporting `completionTokens == max_tokens` on both paths.

#### Step 2: Reject `n > 1`
- **Status:** todo
- **Depends on:** none
- **Files:** `OpenAIModels.kt` (`ChatCompletionRequest.n: Int? = null`), `ChatRequestValidator.kt` (`n must be 1`), `ChatRequestValidatorTest.kt`, `OpenAIModelsTest.kt`
- **Change:** `n` absent or 1 is accepted, anything else is a fixed 400.
- **Verification:** validator tests for absent, 1, 2, 0.

#### Step 3: Read capabilities from the model
- **Status:** todo
- **Depends on:** none
- **Files:** `ModelInspector.kt` (`RawModelInfo` and `ModelCapabilities` gain `supportsTools`, `supportsThinking`, `maxContextTokens`, `dynamicContext`, each with a default (`false`/`0`) so existing constructions compile; `readModelInfo` reads tools/thinking only when `ModelInfo.from(path) is ModelInfo.Llm`, context values from the base class), `ModelInspectorTest.kt`
- **Change:** non-LLM models report false/0.
- **Verification:** `ModelInspectorTest` covers mapping with fake `RawModelInfo`.

#### Step 4: Capability pre-check in the chat handler
- **Status:** todo
- **Depends on:** 3
- **Files:** `AiEngineManager.kt` (reuse its existing `modelCapabilities` from #46), `OpenAIToolMapper.kt` (`prepare` takes the capabilities), `OpenAIServer.kt`, `OpenAIToolMapperTest.kt`, `OpenAIServerTest.kt`
- **Change:** non-empty `tools` (with `tool_choice` not `none`) on a model without tool support, or `reasoning_effort` other than `none` on a model without thinking, gives a fixed 400. With no model loaded the capabilities are null and the check is skipped (the existing "Model not loaded" path answers).
- **Verification:** mapper and route tests for both refusals and the accepted cases; route tests set capabilities through `AiEngineManager`'s `modelInspector` seam with a fake `RawModelInfo` reader.

#### Step 5: Per-effort thinking budgets
- **Status:** todo
- **Depends on:** none
- **Files:** `OpenAIToolMapper.kt` (`thinkingConfig`, object-level budget constants; same file as Step 4, do the two edits in sequence), `OpenAIToolMapperTest.kt`
- **Change:** as in Approach.
- **Verification:** unit tests for each effort's `ThinkingConfig`.

#### Step 6: Clamp the context size at load
- **Status:** todo
- **Depends on:** 3
- **Files:** `AiEngineManager.kt` (after the merge, `loadModel(modelPath, backendType, maxNumTokens, ...)` inspects the file and `loadEngine` builds `EngineConfig`: clamp there; new read-only `effectiveMaxNumTokens`), `InferenceService.kt` (status text adds the clamped value when `effectiveMaxNumTokens` differs from the requested one), `AiEngineManagerTest.kt`
- **Change:** clamp only when `maxContextTokens > 0` and the context is not dynamic; `Log.w` with both numbers. The early-return "same model already loaded" check keeps comparing the requested value.
- **Verification:** `AiEngineManagerTest` with a fake inspector: larger value clamped, smaller kept, 0/dynamic untouched.

#### Step 7: Docs
- **Status:** todo
- **Depends on:** 1, 2, 4, 5, 6
- **Files:** `docs/standards/api.md` (request fields, `finish_reason`, new 400s, an "Unsupported" list with each documented item, and the `json_schema` note on `additionalProperties: false`), `README.md` (developer guide, same)
- **Change:** every decision in the table is visible to clients.
- **Verification:** `scripts/check_docs.sh` passes; `grep` shows each table item (`stop`, `n`, `logit_bias`, `tool_choice`, `parallel_tool_calls`, `reasoning_tokens`, `additionalProperties`, timeouts) in both `api.md` and README.

#### Step 8: Device check
- **Status:** todo
- **Depends on:** 7
- **Files:** script and logs in `~/.claude/tickets/android_edge_ai_core/ANDROIDEDGEAICORE-17-evidence/`
- **Change:** on the emulator with Gemma 4 E2B (CPU): `max_tokens: 5` gives `finish_reason: "length"` (stream and non-stream); `n: 2` gives 400; `reasoning_effort` `low` vs `xhigh` with thinking output compared; a context size above the model's maximum is clamped (status and logcat): started with `adb shell am start-foreground-service` and the `EXTRA_MAX_NUM_TOKENS` extra (as `scripts/device.sh start` does for model and backend).
- **Verification:** results recorded in Verification; a budget the model ignores is recorded in `api.md` as advisory.

### Tests
`OpenAIModelsTest`, `ChatRequestValidatorTest`, `ModelInspectorTest`, `OpenAIToolMapperTest`, `AiEngineManagerTest`, `OpenAIServerTest` as listed. Commands: `./gradlew testDebugUnitTest lintDebug`, `scripts/check_docs.sh`.

### Risks and open questions
- Decisions in the table are proposals for the owner; changing one moves an item between "implement" and "document" without changing the other steps.
- `completionTokens >= maxOutputTokens` can mark `length` when the model ends naturally on exactly the cap; accepted (rare, and OpenAI clients treat both as finished).
- Follow-up tickets to suggest: stop sequences; non-streaming cancel on disconnect; request timeouts and queue limits.

### Success criteria
- [ ] Each Description item decided as in the table, recorded in this ticket
- [ ] `finish_reason: "length"`, `n` 400, capability 400s, effort budgets and the context clamp implemented with tests
- [ ] `api.md` and README list the implemented behaviour and every unsupported item
- [ ] Device check recorded; checks pass

### Owner decisions 2026-11-09
- The implement items (Steps 1 to 6, budgets 256/1024/4096/16384/unlimited, clamp) and the documented/dropped items in the Approach table are approved as proposed, including the rule 3 and rule 7 departures.
- Follow-up tickets created for stop sequences, non-streaming cancel on disconnect, and request timeouts with queue limits (ANDROIDEDGEAICORE-21, -22, -23).

### Reviewer brief
Objective: decide every compatibility gap in the Description; implement `finish_reason: "length"`, a fixed 400 for `n > 1`, capability 400s for tools/reasoning on models without support (from `ModelInfo.Llm`), per-effort thinking budgets, and a context-size clamp to `maxContextTokens`; document `stop`, `logit_bias`, named `tool_choice`, `parallel_tool_calls`, `reasoning_tokens`, `json_schema` extra properties, non-stream cancel and timeouts/queue limits; drop `regex`. Key decisions: the table in Approach (proposals for the owner); the new 400s are status-code changes whose version impact the owner decides; ignored fields are a proposed documented exception to rule 7. Runs after ANDROIDEDGEAICORE-20. Out of scope: implementing stop sequences, non-stream cancel, timeouts/queue limits (follow-up tickets suggested). Success: decisions recorded, implemented items tested, `api.md`/README cover every item, device check recorded.
The decisions above are intended; report a problem with one only if it breaks a requirement or a documented rule.

### Validation log
- Round 1: validator FAIL. Judged FAIL. (1) BLOCKING new 400s without the contract/version impact: valid, "Contract impact" paragraph added. (2)-(10) MINOR: valid and fixed: post-merge `loadEngine` location and `effectiveMaxNumTokens` for the status (2, 3); `is ModelInfo.Llm` check and defaults (4); object-level constants (5); ignored-fields justification (6); `json_schema` note in Step 7 (7); no-model case and test seam in Step 4 (8); how the device check sets the context size (9); grep check of docs (10).
- Round 2: validator PASS. Judged PASS. Minors fixed inline: waiver wording per rule 8; stream-path 200 named; `finish_reason` classified additive; Step 5 wording; rule 3 and rule 7 departures marked as owner proposals; reuse `modelCapabilities`; shared-file note for Steps 4/5.

## Implementation notes
PR: https://github.com/archit342000/android_edge_ai_core/pull/49
Deviation: the capability pre-check (tools/reasoning 400) was NOT added: Gemma 4 E2B metadata says tools=false thinking=false yet supports both (evidence/compat.txt). Flags are read and logged only. Budgets advisory (same thinking length low vs xhigh). Formal review loop not run.

## Verification

## Review history

## Log
- 2026-11-09: created from the follow-ups of the engine upgrade batch
- 2026-11-09: findings checked (all gaps confirmed; LiteRT-LM 0.19.1 API checked per item); plan validated, 2 rounds
- 2026-11-09: linked blocked_by ANDROIDEDGEAICORE-20 (its code lives on the unmerged batch PRs)
- 2026-11-09: linked conflicts_with ANDROIDEDGEAICORE-15 (OpenAIServer chat handler and OpenAIModels)
- 2026-11-09: linked conflicts_with ANDROIDEDGEAICORE-19 (AiEngineManager.loadModel and InferenceService status texts)
- 2026-11-09: linked conflicts_with ANDROIDEDGEAICORE-16 (AiEngineManager.loadModel)
- 2026-11-09: owner decisions recorded in the Plan
- 2026-11-09: linked split_into ANDROIDEDGEAICORE-21, -22, -23 (owner asked for follow-up tickets)
- 2026-11-09: linked blocks ANDROIDEDGEAICORE-21 (needs the finishReason helper and the api.md Unsupported list)
- 2026-11-09: linked conflicts_with ANDROIDEDGEAICORE-23 (OpenAIServer chat handler)
- 2026-11-09: linked blocks ANDROIDEDGEAICORE-23 (Step 4 edits the api.md Unsupported list added by -17)
- 2026-11-09 batch-autopilot: shipped PR #49 (stacked on #48)
- 2026-11-10: linked relates_to ANDROIDEDGEAICORE-24 (batch follow-ups)
- 2026-11-10: closed (merged in PR #49)
