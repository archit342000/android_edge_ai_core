---
id: ANDROIDEDGEAICORE-13
title: "Add tools, response_format and thinking to the API"
status: done
created: 2026-11-09
updated: 2026-11-09
links:
  blocked_by: [ANDROIDEDGEAICORE-3, ANDROIDEDGEAICORE-4]
  conflicts_with: [ANDROIDEDGEAICORE-10, ANDROIDEDGEAICORE-14]
  relates_to: [ANDROIDEDGEAICORE-15, ANDROIDEDGEAICORE-17, ANDROIDEDGEAICORE-20]
  split_from: [ANDROIDEDGEAICORE-2]
---

## Description
OpenAI-compatibility features LiteRT-LM supports but the API doesn't expose (gap #11): `tools` / `tool_calls` (manual tool calling, as the official server does), `response_format` JSON schema (constrained decoding with LLGuidance), and thinking or reasoning output.

Background: research and gap analysis in ANDROIDEDGEAICORE-2 (LiteRT-LM v0.19.1). Verify with `scripts/device.sh` (`docs/testing-on-device.md`).

## Acceptance criteria
- [x] `tools` requests return OpenAI-style `tool_calls`; tool results continue the conversation
- [x] `response_format` with a JSON schema yields valid JSON
- [x] Thinking can be enabled, and the reasoning is returned separately
- [x] `api.md` and README updated

## Plan
**Date:** 2026-11-09  **Status:** Validated

### Objective
Expose three OpenAI-compatible features LiteRT-LM 0.19.1 supports: function calling (`tools` in, `tool_calls` out, `tool` results continuing the conversation), constrained JSON output (`response_format`), and thinking (`reasoning_effort` in, reasoning returned separately as `reasoning_content`). Runs after ANDROIDEDGEAICORE-3 and -4.

### Context
- Current DTOs (`OpenAIModels.kt`): `ChatMessage(role, content: JsonElement)`, `ChatMessageResponse(role, content: String)`, `Choice(finish_reason: String)`; `ChunkDelta(content, role)` in `OpenAIServer.kt`. `AiEngineManager.toLiteRTMessage` maps `assistant` to `Message.model`, else `Message.user`; `extractText` keeps only `Content.Text`.
- 0.19.1: `ConversationConfig(tools: List<ToolProvider>, automaticToolCalling, channels, thinkingConfig, enableResponseFormat)`; `tool(openApiTool: OpenApiTool)` wraps an `OpenApiTool` (`getToolDescriptionJsonString()`, `execute(...)`); `Message.model(contents, toolCalls, channels)`, `Message.tool(contents)`, `Content.ToolResponse(name, response)`, `ToolCall(name, arguments: Map<String, Any?>)`, `Message.channels` (thinking text by channel name); `sendMessageAsync(..., thinkingConfig, responseFormat)`; `ResponseFormat.json(schema: String)` (requires `enableResponseFormat = true`, else `IllegalArgumentException`); `ThinkingConfig(enableThinking, thinkingTokenBudget)`.
- Official server (`serve/openai_common.py`, `openai_chat_completions.py`): `automatic_tool_calling=False`; OpenAI `tool_calls` with ids, `finish_reason: "tool_calls"`; tool results mapped by a `tool_call_id` to name map built from earlier assistant `tool_calls`; `reasoning_effort` `none` disables thinking, `minimal..xhigh` enable it with unlimited budget; `response_format` types `json_object`, `json_schema`, `text`.
- After ANDROIDEDGEAICORE-4, `CacheSettings` keys reuse by settings; tools, thinking and response-format enablement must join it.
- Rules: `docs/standards/api.md` (additive changes; snake_case; README and DTO tests), `docs/standards/kotlin.md` (Gson only; nullable absent fields, validate after parsing), `docs/standards/security.md` (validate and cap input; fixed 400 messages), `docs/standards/errors-and-logging.md` (never log content or tool arguments).

### Approach
- **DTOs:** `ChatMessage` gains `tool_calls`, `tool_call_id`, `name`, all `= null` by default so existing positional constructions (`MainActivity`, the `InferenceService` ping) compile, and `content` becomes nullable (assistant tool-call turns send `null`). `ChatCompletionRequest` gains `tools`, `tool_choice` (`"auto"` or `"none"`; `"none"` means the tools are not passed to the conversation, so the model answers in text; anything else 400), `response_format`, `reasoning_effort`. `ChatMessageResponse` gains nullable `tool_calls`, `reasoning_content` and nullable `content`; `ChunkDelta` gains `tool_calls`, `reasoning_content`.
- **Mapping (pure, `OpenAIToolMapper`):** request tools to `OpenApiTool` descriptions (`execute` throws `IllegalStateException`, since tools are never run on the device); assistant `tool_calls` to `ToolCall` (arguments parsed from the JSON string; invalid JSON gives 400); `tool` messages to `Message.tool(Contents.of(Content.ToolResponse(name, content)))` with the name from the id map (unknown id gives 400); model `toolCalls` back to OpenAI `tool_calls` with ids `call_<uuid>` and `arguments` as a JSON string. Response format: `json_object` gives `ResponseFormat.json("{}")` (as the official server does), `json_schema` uses `json_schema.schema`, `text` or absent gives null. The schema is parsed with Gson `JsonParser` and size-checked in the mapper first, so an invalid schema gets the mapper's fixed 400 and never reaches `ResponseFormat.json` (whose message echoes the parser error). Thinking: `none` gives `ThinkingConfig(false, 0)`, the five efforts give `ThinkingConfig(true, -1)`, absent gives null (model default); unknown value 400.
- **Engine:** `createEngineConversation` takes tools (with `automaticToolCalling = false`), `thinkingConfig`, and `enableResponseFormat = (responseFormat != null)`; `sendMessageAsync` gets `responseFormat` and `thinkingConfig` per call. The completion callback carries a result object (text, reasoning text from `Message.channels`, tool calls). No `channels` are set in `ConversationConfig`, so the model's default channels apply (as in the official server); all channel entries are joined into `reasoning_content`, and the key names are logged at `Log.d` once per request instead of a bare string. `CacheSettings` gains the tool list JSON, thinking config and the response-format flag.
- **Server:** non-streaming: `finish_reason "tool_calls"` with `content: null` when tool calls are present; streaming: reasoning chunks as `delta.reasoning_content`, tool calls as one `delta.tool_calls` chunk, then a final chunk with the finish reason.
Rejected: running tools on the device (OpenAI clients run their own tools); `<think>` tags inside `content` (mixes channels).

### Out of scope
`tool_choice` forcing a named function, parallel-tool-call flags, `regex` response format, reasoning token budgets per effort level, `reasoning_tokens` in `usage` (follow-up once ANDROIDEDGEAICORE-9 has landed).

### Steps
#### Step 1: DTOs
- **Status:** todo
- **Depends on:** none
- **Files:** `app/src/main/java/com/aanand/edgeaicore/OpenAIModels.kt` (modify), `app/src/main/java/com/aanand/edgeaicore/OpenAIServer.kt` (modify: `ChunkDelta`), `AiEngineManager.toLiteRTMessage` (modify for nullability) and `AiEngineManager.extractTextFromChatMessage` (remove: it also reads `content` but has no callers)
- **Change:** as in Approach.
- **Verification:** `./gradlew assembleDebug`.

#### Step 2: Mapper
- **Status:** todo
- **Depends on:** 1
- **Files:** `app/src/main/java/com/aanand/edgeaicore/OpenAIToolMapper.kt` (create: tools, tool calls, tool results, response format, thinking; caps `MAX_TOOLS` 32 and schema size `MAX_SCHEMA_CHARS` 16 384 as `companion` constants)
- **Change:** as in Approach; each invalid input raises an `IllegalArgumentException` with a fixed message the server returns as 400.
- **Verification:** step 3 tests.

#### Step 3: Unit tests
- **Status:** todo
- **Depends on:** 2
- **Files:** `app/src/test/java/com/aanand/edgeaicore/OpenAIToolMapperTest.kt` (create), `app/src/test/java/com/aanand/edgeaicore/OpenAIModelsTest.kt` (modify: Gson round-trips of the new fields, `content: null`)
- **Change:** each mapping and each 400 case; tool-call id round trip; json_object and json_schema; every `reasoning_effort` value.
- **Verification:** `./gradlew testDebugUnitTest` passes.

#### Step 4: Engine
- **Status:** todo
- **Depends on:** 2
- **Files:** `app/src/main/java/com/aanand/edgeaicore/AiEngineManager.kt` (modify: `toLiteRTMessage` handles `tool` and assistant `tool_calls`; `createEngineConversation`; `generateConversationResponseAsync` options and result; `MessageCallback.onMessage` collects channels and tool calls), `app/src/main/java/com/aanand/edgeaicore/ConversationCache.kt` (modify: `CacheSettings`), `app/src/main/java/com/aanand/edgeaicore/InferenceService.kt` (modify: ping completion lambda)
- **Change:** as in Approach; `LlmConversation` (engine seam from ANDROIDEDGEAICORE-3) passes the new per-call options.
- **Verification:** `./gradlew assembleDebug testDebugUnitTest`; `ConversationCacheTest` extended for the new settings fields; `AiEngineManagerTest` cases: tools reach the fake's `ConversationConfig` with `automaticToolCalling = false`; a fake reply with tool calls and channels reaches the result object; `tool` history maps to a tool message.

#### Step 5: Server
- **Status:** todo
- **Depends on:** 4
- **Files:** `app/src/main/java/com/aanand/edgeaicore/OpenAIServer.kt` (modify: `/chat/completions` both branches)
- **Change:** map request options, catch the mapper's `IllegalArgumentException` for a fixed 400 (decrementing `activeRequests`), build responses and chunks as in Approach. `OpenAIServerTest` cases with a fake engine: `finish_reason: "tool_calls"` and `content: null`; streamed `delta.tool_calls` and `delta.reasoning_content`; invalid `tool_choice`, unknown `tool_call_id`, bad schema and bad `reasoning_effort` give their fixed 400s.
- **Verification:** `./gradlew assembleDebug testDebugUnitTest lintDebug`.

#### Step 6: Device checks (emulator, CPU, Gemma 4 E2B)
- **Status:** todo
- **Depends on:** 5
- **Files:** none (evidence in `~/.claude/tickets/android_edge_ai_core/ANDROIDEDGEAICORE-13-evidence/`)
- **Change:** with `MODEL=~/.cache/edgeaicore-test-models/gemma-4-E2B-it.litertlm scripts/device.sh up` (this needs ANDROIDEDGEAICORE-3's Gemma 4 check to have passed; if Gemma 4 does not load, record it and stop here, since no ungated test model supports tools and thinking): a `get_weather(city)` tool request ("weather in Paris?"); then the follow-up with the assistant `tool_calls` message and a `tool` result; the same flow streamed; `response_format` `json_schema` for `{name: string, age: integer}` (parse the reply with `python -m json.tool`); `json_object`; `reasoning_effort: "high"` and `"none"`, streamed and not; invalid inputs (unknown `tool_choice`, unknown `tool_call_id`, bad `reasoning_effort`).
- **Verification:** first call returns `finish_reason: "tool_calls"` with a parsable `arguments` string; the follow-up answers using the tool result; schema output parses and matches; `reasoning_content` present with "high" and absent with "none"; invalid inputs return 400 with fixed messages; a plain chat is unchanged.

#### Step 7: Docs
- **Status:** todo
- **Depends on:** 6
- **Files:** `docs/standards/api.md` (request fields, message roles, response and chunk fields, errors), `README.md` (developer guide with a tool-calling example), `docs/architecture.md` (Components: `OpenAIToolMapper`)
- **Change:** document the features and limits.
- **Verification:** `scripts/check_docs.sh` passes.

### Tests
`OpenAIToolMapperTest` (new), `OpenAIModelsTest`, `ConversationCacheTest`, `AiEngineManagerTest` and `OpenAIServerTest` (extended through the engine seam and Ktor test host from ANDROIDEDGEAICORE-3). `InferenceService` and `MainActivity` changes are covered by device checks (agreed exception in `docs/standards/testing.md`, ANDROIDEDGEAICORE-3). Run `./gradlew testDebugUnitTest lintDebug`, `scripts/check_docs.sh`; device check in step 6.

### Risks and open questions
- Gemma 3n may not support function calling or thinking; the device check uses Gemma 4 E2B. A pre-check with `ModelInfo.supportsFunctionCalling()` / `supportsThinking()` (via ANDROIDEDGEAICORE-11's `ModelInspector`) would give clean 400s; left as a follow-up unless the engine's own error proves unclear.
- `enableResponseFormat` initialises LLGuidance per conversation; only enabled when a request asks for it.
- Size: three features in one ticket. If implementation runs long, the owner may split thinking and response format into their own tickets; the steps already separate them inside steps 2 to 6.

### Success criteria
- [ ] `tools` requests return OpenAI-style `tool_calls`; tool results continue the conversation
- [ ] `response_format` with a JSON schema gives valid JSON
- [ ] Thinking can be enabled; reasoning returned separately in `reasoning_content`
- [ ] Tests, lint, docs checks pass; `api.md` and README updated

### Reviewer brief
- **Objective:** expose function calling (`tools`/`tool_calls`/`tool` results), constrained JSON (`response_format`) and thinking (`reasoning_effort` in, `reasoning_content` out), following the official LiteRT-LM server.
- **Key decisions:** manual tool calling (`automaticToolCalling = false`; tools never run on the device) via `OpenApiTool`; `tool_choice` `auto`/`none` only; a pure `OpenAIToolMapper` with fixed-message 400s, size caps and schema pre-validation; `json_object` gives `ResponseFormat.json("{}")`; `enableResponseFormat` only when requested; all channels joined into `reasoning_content`; tools/thinking/response format part of `CacheSettings`; new DTO fields default to null.
- **Out of scope:** forced named tool choice, parallel-tool flags, regex format, per-effort budgets, `reasoning_tokens` in usage.
- **Success criteria:** tool calls returned and continued; schema output valid JSON; reasoning returned separately; tests (mapper, models, cache, engine, server) and checks pass; `api.md` and README updated.

The decisions above are intended; report a problem with one only if it breaks a requirement or a documented rule.

### Validation log
- **Round 1:** validator FAIL. My judgement: FAIL, with 2 valid BLOCKING issues.
  1. BLOCKING no tests for the changed engine and server: valid. The owner chose engine seam + Ktor tests; `AiEngineManagerTest` and `OpenAIServerTest` cases were added.
  2. BLOCKING an invalid `json_schema` reached `ResponseFormat.json`, whose message is echoed in the 400: valid (checked `ResponseFormat.kt`). The mapper now pre-parses the schema with Gson.
  3. to 6. MINOR (`tool_choice` "none" behaviour, null defaults and the real readers of `content`, channel key, Gemma 4 dependency): all valid and fixed.
- **Round 2 (diff-scoped):** validator PASS with 1 MINOR issue: `extractTextFromChatMessage` also reads `content`. Valid (it has no callers, checked with grep), so step 1 now removes it.


## Implementation notes
### Autopilot run (2026-11-09)
- Mode: automatic (batch-autopilot, item 10/11)
- Branch: feature/tools-response-format-thinking  PR base: feature/reuse-conversations (stacked: blocked by the conversation-reuse ticket)  Starting commit: ad7c36227bac24f7ee248c99548183ae3d47d5e3
- Dirty before start: none
- Work files: none yet
- Counters: fix 0/10, review 1/5, re-verify 1
- Last completed stage: 3 verify (all PASS)  Next: done (shipped)
- Hard blocker: none
- WIP commit: none
- Repo: /home/archit-anand/docker-data/shared/Coding/android_edge_ai_core

### Baseline (2026-11-09)
All unit tests passed on the base branch (conversation reuse, 34 tests).

### Implementation progress
- [x] Step 1: DTOs (`ChatMessage` tool fields and nullable content, tools, `tool_choice`, `response_format`, `reasoning_effort`, response and chunk fields); `extractTextFromChatMessage` removed
- [x] Steps 2-3: `OpenAIToolMapper` (+ 10 tests), `ChatOptions`
- [x] Step 4: engine (`createEngineConversation` tools/thinking/response format with manual tool calling; `toLiteRTMessage` for tool messages and assistant tool calls; callbacks `onReasoning`, `onToolCalls`; `CacheSettings` fields); tests
- [x] Step 5: server (fixed 400 via `prepare`, tool_calls/reasoning in responses and chunks, final finish chunk); tests
- [x] Step 6: device checks (see Verification)
- [x] Step 7: docs (api.md, README example, architecture)
- Deviations: new optional callbacks `onReasoning` and `onToolCalls` on `generateConversationResponseAsync` instead of a result object (keeps `onComplete(String)` unchanged); Gson parses `"content": null` as `JsonNull`, which is treated as absent.

### Smoke test (2026-11-09)
`./gradlew assembleDebug testDebugUnitTest lintDebug` pass; `scripts/check_docs.sh` OK.

### Autopilot decisions (2026-11-09)
- Stage 1: Plan already `Validated` (2 rounds); reused.

PR: https://github.com/archit342000/android_edge_ai_core/pull/45, branch feature/tools-response-format-thinking, base feature/reuse-conversations

## Verification
### Verification plan (2026-11-09)
**Plan:** ticket Plan above  **Diff base:** feature/reuse-conversations at ad7c362 (the PR base)
**Environment:** emulator `claude_edgeaicore_api34`, CPU, Gemma 4 E2B; script `ANDROIDEDGEAICORE-13-evidence/scripts/features.py` driven by `features.sh`; teardown `device.sh down`.

| ID | Criterion | Scenario | Type | Method | Files | Expected | Evidence |
|----|-----------|----------|------|--------|-------|----------|----------|
| V1 | AC1 | `get_weather(city)` tool request "weather in Paris?" | happy | API | OpenAIToolMapper.kt, OpenAIServer.kt | `finish_reason: "tool_calls"`, `tool_calls[0].function.arguments` parses as JSON with a city | response |
| V2 | AC1 | follow-up with the assistant `tool_calls` message and a `tool` result | happy | API | AiEngineManager.kt | a normal answer that uses the result | response |
| V3 | AC1 | the same tool request streamed | happy | API | OpenAIServer.kt | `delta.tool_calls` chunk, finish chunk `tool_calls`, `[DONE]` | stream |
| V4 | AC2 | `response_format` json_schema `{name: string, age: integer}`; json_object | happy | API | OpenAIToolMapper.kt, AiEngineManager.kt | reply parses as JSON and has the keys / is valid JSON | reply |
| V5 | AC3 | `reasoning_effort` high and none, streamed and not | happy | API | same | `reasoning_content` present with high and absent with none; streamed `delta.reasoning_content` | responses |
| V6 | failure | unknown `tool_choice`, unknown `tool_call_id`, bad `reasoning_effort`, bad schema | failure | API | OpenAIToolMapper.kt | 400 with fixed messages; `/v1/load` 0 | responses |
| V7 | regression | plain chat and plain stream | regression | API | OpenAIServer.kt | 200 / `[DONE]` | output |
| V8 | regression | unit tests, lint, docs | regression | CLI | all | pass | output |


### Results (2026-11-09)
Commit verified: feature/reuse-conversations at ad7c362 + uncommitted:
- `README.md` bd9ada3b817b2eb13e96bdf980a8af5aa425cfc3
- `app/src/main/java/com/aanand/edgeaicore/AiEngineManager.kt` 060c636487a8aa25a05f1899b2f4560a29497851
- `app/src/main/java/com/aanand/edgeaicore/ConversationCache.kt` a37e32a0fce787101ed777b3a3a1dba3d53a5ea3
- `app/src/main/java/com/aanand/edgeaicore/LlmEngine.kt` 7ded695edef2217b349deedddcb155960ab3f78f
- `app/src/main/java/com/aanand/edgeaicore/OpenAIModels.kt` c77899bcbcc5fe18c097ce785f746d2efd8f7ad3
- `app/src/main/java/com/aanand/edgeaicore/OpenAIServer.kt` 4e1d67440e60c207363226547101859fee049262
- `app/src/test/java/com/aanand/edgeaicore/AiEngineManagerTest.kt` a69e6d4d0f310db12f55450d3433662175335e9b
- `app/src/test/java/com/aanand/edgeaicore/ConversationCacheTest.kt` 7a0c93b2a4a27d827831fc7667f14c7bd1e049e3
- `app/src/test/java/com/aanand/edgeaicore/FakeLlmEngine.kt` 526b2307729515aff3b488badf32c0e16867ec0e
- `app/src/test/java/com/aanand/edgeaicore/OpenAIModelsTest.kt` d3c1eab37c3d1e8de76bc82d4a49fc27a768479c
- `app/src/test/java/com/aanand/edgeaicore/OpenAIServerTest.kt` 764c3358fe8d05c84ab649b6a97f364bc634ff26
- `docs/architecture.md` 4711f108861a71754b796cc2e159ed1a539362b1
- `docs/standards/api.md` 754cfdf1b5c2d913684aacb2c0601385f442a8e7
- `app/src/main/java/com/aanand/edgeaicore/OpenAIToolMapper.kt` 9717f9d94e82bd5d4fb7725a7d87de94be75b4af
- `app/src/test/java/com/aanand/edgeaicore/OpenAIToolMapperTest.kt` 535ba8917e14e6900291a97cc525ab790ef8042d

Emulator `claude_edgeaicore_api34`, CPU, Gemma 4 E2B. Evidence: `ANDROIDEDGEAICORE-13-evidence/features-run.txt`, `scripts/{features.py,features.sh}`.

| ID | Status | Evidence |
|----|--------|----------|
| V1 | PASS | tool request: HTTP 200, `finish_reason: "tool_calls"`, `content: null`, `tool_calls[0]` with `id call_...`, `function.name get_weather`, `arguments "{\"city\":\"Paris\"}"` (parses). |
| V2 | PASS | follow-up with the assistant `tool_calls` message and a `tool` result: "The weather in Paris is cloudy with a temperature of 18.0°C." |
| V3 | PASS | streamed: one `tool_calls` delta chunk, then a finish chunk `tool_calls`, then `[DONE]`. |
| V4 | PASS | `json_schema` (name, age): the reply parses, has `name` and `age` (an integer); it also added other keys, because the schema did not forbid them; `json_object`: `{"colors":["blue","green"]}` parses. |
| V5 | PASS | `reasoning_effort: "high"`: `reasoning_content` "Thinking Process: ..." separate from `content` "51"; `none`: no reasoning, `content` "51"; streamed high: 169 reasoning chunks and 2 content chunks. |
| V6 | PASS | invalid `tool_choice`, `tool_call_id`, `reasoning_effort`, schema: 400 each with its fixed message; `/v1/load` 0 afterwards. |
| V7 | PASS | plain chat 200 with no `reasoning_content`. |
| V8 | PASS | 59 unit tests, lint (no new issues), check_docs. |

Verdict: verified, 8 PASS, 0 FAIL, 0 BLOCKED.

## Review history

### Round 1 - 2026-11-09
Verdict: Request changes (one MAJOR, rejected with evidence; the MINORs fixed). Scope: 13 files (~900 changed lines incl. tests/docs), one Haiku reviewer.
- R1-1 [MAJOR] criteria 2 and 3 only tested against a fake, no device record - Static - Rejected with evidence: the device run is in Verification (V4 `json_schema` and `json_object` replies parse; V5 `reasoning_content` present with high, absent with none, 169 streamed reasoning chunks), on Gemma 4 E2B
- R1-2 [MINOR] non-streaming tool calls carried `index` - Static - Fixed: omitted (test, and checked on the device)
- R1-3 [MINOR] `"tool_choice": null` rejected - Static - Fixed (JsonNull = absent; test)
- R1-4 [MINOR] whole numbers in tool-call arguments replayed as decimals (`3.0`) - Static - Fixed: whole numbers stay `Long`; checked on the device (integer argument survives the follow-up turn)
- R1-5 [MINOR] no size cap on tool-call arguments and tool results - Static - Fixed: 16 KB each, fixed 400 (tests)
- R1-6 [MINOR] `streamedToolCalls` unsynchronised - Static - Fixed: `AtomicInteger`
- R1-7 [MINOR] missing tests (streaming 400 and load counter, `toToolMessage`, null entries, null `tool_choice`) - Static - Fixed; null entries in `tools`/`messages` now give fixed 400s
- R1-8 [MINOR] the non-streaming error path decrements the load counter twice (inner `finally` plus the outer safety decrement) - Static - Pre-existing (unchanged lines; the cancel-on-disconnect work rewrites that block): follow-up
- R1-9 [MINOR] with manual tool calling the response format constrains every decode, including the tool-call turn - Static - Fixed: `tools` with `response_format` is a fixed 400, documented, tested, checked on the device
- R1-10 [NIT] `ChatOptions` shared a file; `!!`; shadowed `tool` name - Static - Fixed
Pre-existing: R1-8.
Resolution: 9 fixed, 1 rejected, 1 pre-existing. 66 unit tests, lint, check_docs pass; device re-check done (`ints-run.txt`).

## Log
- 2026-11-09: created from ANDROIDEDGEAICORE-2 gap analysis
- 2026-11-09: plan drafted
- 2026-11-09: plan revised after validation round 1 (and test-seam decision)
- 2026-11-09: plan validated, 2 rounds
- 2026-11-09: autopilot started (batch)
- 2026-11-09: implemented, smoke tests pass
- 2026-11-09: verify: 8 PASS, 0 FAIL
- 2026-11-09: batch-autopilot: shipped, base feature/reuse-conversations (stacked)
- 2026-11-09: closed (all acceptance criteria checked; the PR is open, not yet merged)
- 2026-11-09: linked relates_to ANDROIDEDGEAICORE-15, ANDROIDEDGEAICORE-17, ANDROIDEDGEAICORE-20 (follow-ups)
