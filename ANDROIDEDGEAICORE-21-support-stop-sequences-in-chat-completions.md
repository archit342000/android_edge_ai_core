---
id: ANDROIDEDGEAICORE-21
title: "Support stop sequences in chat completions"
status: done
created: 2026-11-09
updated: 2026-11-10
links:
  conflicts_with: [ANDROIDEDGEAICORE-22, ANDROIDEDGEAICORE-23]
  blocked_by: [ANDROIDEDGEAICORE-17, ANDROIDEDGEAICORE-20]
  split_from: [ANDROIDEDGEAICORE-17]
  relates_to: [ANDROIDEDGEAICORE-24]
---

## Description
The `stop` request field (a string or up to 4 strings) is ignored today (ANDROIDEDGEAICORE-17 documents it as unsupported). LiteRT-LM 0.19.1 has no stop-sequence parameter, so the server must match the generated text itself, hold back streamed text that could be the start of a stop sequence, end the generation when one matches (`cancelProcess`), and return the text before the match with `finish_reason: "stop"`.

Background: split from ANDROIDEDGEAICORE-17 at the owner's request.

## Acceptance criteria
- [ ] `stop` (string or list of up to 4 non-empty strings) is accepted; anything else is a fixed 400
- [ ] Output, streamed or not, ends before the first stop sequence and never contains it; `finish_reason` is `stop`
- [ ] Generation ends promptly after a match and the next request works
- [ ] Tests for the matcher (split across chunks, overlaps, no match) and the routes; `api.md` and README updated; device check

## Plan
**Date:** 2026-11-09  **Status:** Validated

### Objective
Honour OpenAI's `stop` field: end the answer before the first stop sequence, never send the sequence (or a partial match) to the client, stop native decoding promptly, and report `finish_reason: "stop"`.

### Context
- Today `stop` is not in `ChatCompletionRequest` and Gson drops it; ANDROIDEDGEAICORE-17 documents it as ignored.
- LiteRT-LM 0.19.1 has no stop parameter (`ConversationConfig`, `SessionConfig`, `Conversation.sendMessageAsync` checked with `javap`); `Conversation.cancelProcess()` stops decoding.
- After ANDROIDEDGEAICORE-20, `AiEngineManager.generateConversationResponseAsync` streams text from `MessageCallback.onMessage` (content via `extractText`, reasoning via `message.channels` to `onReasoning`, tool calls to `onToolCalls`), wraps native decoding in `CancellableGeneration` (`cancel()` calls `cancelProcess`, `awaitStopped(cancelWaitMs)`), treats a coroutine cancel as a client disconnect (closes the conversation, reports via `onErrorCallback`), and caches a conversation only when it holds exactly history + reply (#43).
- ANDROIDEDGEAICORE-17 Step 1 adds the `finishReason(...)` helper in `OpenAIModels.kt` (`tool_calls` > `length` > `stop`); this ticket extends it, so it is blocked by -17.
- Rules: `docs/standards/api.md` (contract changes, additive = minor), `docs/standards/security.md` (fixed 400 messages, bounded input), `docs/standards/testing.md`, `docs/standards/errors-and-logging.md` (no output in logs).

### Approach
- Validate `stop` in `ChatRequestValidator`: absent/null, a non-empty string, or a list of 1 to 4 non-empty strings, each at most 64 characters (bounded input, `docs/standards/security.md`) (`MAX_STOP_SEQUENCES`, `MAX_STOP_LENGTH` as object-level `const val`s in `ChatRequestValidator`, like its `MAX_MESSAGES`; the same object-level form the owner approved for ANDROIDEDGEAICORE-17, proposed here for `ChatRequestValidator` too); otherwise a fixed 400 (`stop must be a string or up to 4 strings of 1 to 64 characters`). The DTO field is `stop: JsonElement?`, normalised into `ChatOptions.stopSequences: List<String>`.
- New pure class `StopSequenceMatcher(sequences)`: `feed(chunk)` returns the text that is safe to emit and whether a sequence matched; it holds back the longest suffix that is a prefix of some sequence (at most `max length - 1` characters); `flush()` returns the held-back text at a normal end. Matching applies to answer content only, not reasoning or tool calls.
- `AiEngineManager`: when `stopSequences` is non-empty, each content chunk goes through the matcher before `onToken`/`lastResponseText`. At a normal `onDone` the matcher's `flush()` remainder is emitted through `onToken` and added to `lastResponseText`. On a match: set a local `stoppedBySequence`, drop any later chunks, and stop decoding with the same guarded call as #36's `stopNative` (`isAlive` check, exception logged), not `CancellableGeneration.cancel()`, which marks a client cancel. The native callback that follows, `onDone` or `onError`, then completes the generation: it claims `outcomeReported`, calls `onComplete(textBeforeMatch, stoppedBySequence = true)` and resumes normally (an `onError` after a match is not an error). The conversation is closed, not cached (its KV state holds tokens past the match): on a match the `conversationCache.put` step is skipped, so the existing `finally` closes it. `onComplete` becomes `(String, Boolean) -> Unit`; every caller is updated (callers that do not care write `{ text, _ -> ... }`). `CancellableGeneration` is not changed.
- `finishReason(...)` gains a `stoppedBySequence` flag: `tool_calls` > `stop` (sequence) > `length` > `stop`. Usage keeps the engine's counts (they may include a few tokens past the match; documented).

### Out of scope
- Stop sequences inside reasoning or tool-call arguments.
- Token-level stop (`stop` as token ids).

### Steps
#### Step 1: `StopSequenceMatcher`
- **Status:** todo
- **Depends on:** none
- **Files:** `app/src/main/java/com/aanand/edgeaicore/StopSequenceMatcher.kt` (create), `app/src/test/java/com/aanand/edgeaicore/StopSequenceMatcherTest.kt` (create)
- **Change:** as in Approach.
- **Verification:** tests: match inside one chunk, split across chunks, overlapping sequences (`"ab"`/`"bc"`), a held-back prefix that then does not match (released), no match with `flush()`, match at the very start (empty output), several sequences with the earliest match winning.

#### Step 2: Request field and validation
- **Status:** todo
- **Depends on:** none
- **Files:** `OpenAIModels.kt` (`ChatCompletionRequest.stop: JsonElement? = null`), `ChatRequestValidator.kt`, `ChatOptions.kt` (`stopSequences`), `OpenAIToolMapper.kt` (`prepare` normalises `stop` into `ChatOptions.stopSequences`; not part of the conversation-reuse settings), tests `ChatRequestValidatorTest.kt`, `OpenAIModelsTest.kt`
- **Change:** as in Approach.
- **Verification:** validator tests for string, list, empty string, 5 items, 65 characters, a number, JSON null.

#### Step 3: Stop in the generation
- **Status:** todo
- **Depends on:** 1, 2
- **Files:** `AiEngineManager.kt` (`generateConversationResponseAsync`, `onComplete` signature), `FakeLlmEngine.kt` (`FakeConversation` stops emitting after `cancelProcess` and then calls `onDone`, or `onError` in a second mode), `AiEngineManagerTest.kt`
- **Change:** as in Approach.
- **Verification:** with the fake: output ends before the match, `onComplete` is called once with `stoppedBySequence = true` (no `onErrorCallback`), for both the `onDone` and the `onError` ending; `cancelProcess` was called; chunks after the match are dropped; the conversation is not cached (the next request extending it is a cache miss and rebuilds); the next request runs; a reply that ends in a held prefix with no match keeps its tail (via `flush()`).

#### Step 4: Server and `finish_reason`
- **Status:** todo
- **Depends on:** 3 (and ANDROIDEDGEAICORE-17 Step 1, which adds `finishReason`)
- **Files:** `OpenAIModels.kt` (`finishReason` from -17), `OpenAIServer.kt` (pass the stop result to both paths; streaming sends `[DONE]` after the final chunk), `OpenAIModelsTest.kt`, `OpenAIServerTest.kt`
- **Change:** as in Approach.
- **Verification:** route tests, stream and non-stream: content without the sequence, `finish_reason: "stop"`, `[DONE]` sent, `/v1/load` back to 0.

#### Step 5: Docs and device check
- **Status:** todo
- **Depends on:** 4
- **Files:** `docs/standards/api.md` (request table: `stop`; remove it from the "Unsupported" list added by ANDROIDEDGEAICORE-17 Step 7; Streaming section: the final chunk carries `finish_reason`), `README.md` (developer guide), evidence in `~/.claude/tickets/android_edge_ai_core/ANDROIDEDGEAICORE-21-evidence/`
- **Change:** document the field, the limits and that usage may count a few tokens past the match. On the emulator: "Count from 1 to 20" with `stop: ["7"]`, stream and non-stream; then a normal request.
- **Verification:** answers end at "6" (or before "7"), `finish_reason: "stop"`, logcat shows the generation stopped; `scripts/check_docs.sh`, `./gradlew testDebugUnitTest lintDebug` pass.

### Tests
`StopSequenceMatcherTest` (new), `ChatRequestValidatorTest`, `OpenAIModelsTest`, `AiEngineManagerTest`, `OpenAIServerTest`. Commands: `./gradlew testDebugUnitTest lintDebug`, `scripts/check_docs.sh`.

### Risks and open questions
- Held-back text delays streamed output by up to 63 characters near a possible match; accepted.
- `cancelProcess` may leave a few decoded tokens; they are discarded and the conversation is not reused, so the next turn rebuilds it (one extra prefill).
- `stop` turns a previously ignored field into a validated one: a client sending an invalid `stop` gets a 400 instead of a 200. This lands after the 2.1.0 release, so by `docs/standards/api.md` it is a major-version change unless the owner waives the rule at release time, as for 2.1.0; the release that ships it records the decision.

### Success criteria
- [ ] `stop` validated and applied on both paths; output never contains the sequence
- [ ] `finish_reason: "stop"`; generation stops promptly; next request works
- [ ] Tests listed pass; `api.md` and README updated; device check recorded

### Reviewer brief
Objective: honour `stop` (a string or up to 4 strings, 1 to 64 characters; else a fixed 400): output ends before the first match on both paths, never contains it, decoding stops promptly, `finish_reason: "stop"`. Key decisions: a pure `StopSequenceMatcher` holds back possible prefixes and flushes them at a normal end; on a match `AiEngineManager` stops decoding with the guarded `cancelProcess`, reports a normal completion with `stoppedBySequence`, and never caches the conversation; content only (not reasoning or tool calls); usage may count a few tokens past the match. Runs after ANDROIDEDGEAICORE-20 and -17. Out of scope: stop inside reasoning/tool arguments, token-id stops. Version impact of the new 400 decided at release.
The decisions above are intended; report a problem with one only if it breaks a requirement or a documented rule.

### Validation log
- Round 1: validator FAIL. Judged FAIL. (1) BLOCKING `flush()` never called: valid, emitted at `onDone` with a test. (2) BLOCKING version impact of the new 400 unstated: valid, stated in Risks. Minors valid and fixed: stop result shape (`onComplete` flag), constants placement, dependencies on -17/-20 (front matter and Step 4), completion mechanism through `outcomeReported` and the fake, `api.md` streaming section, `CancellableGeneration` left unchanged. (4) 64-character cap not requested: kept, justified by `security.md` bounded input.
- Round 2: validator PASS. Judged PASS. Minors fixed inline: `onComplete` becomes two-argument at every caller; reuse #36's guarded stop; skip `conversationCache.put` so the conversation is closed. (4) `ChatRequestValidator` not on any branch: disproved, it is on `feature/generation-limits` (#42) with `MAX_MESSAGES`; placement worded as a proposal.

## Implementation notes
PR: https://github.com/archit342000/android_edge_ai_core/pull/53, branch feature/stop-sequences, base bugfix/engine-batch-cleanups
Device check: evidence/out.txt. Formal review loop not run.

## Verification

## Review history

## Log
- 2026-11-09: created (split from ANDROIDEDGEAICORE-17)
- 2026-11-09: plan validated, 2 rounds
- 2026-11-09: linked blocked_by ANDROIDEDGEAICORE-20 (code lives on the unmerged batch PRs)
- 2026-11-09: linked blocked_by ANDROIDEDGEAICORE-17 (needs the finishReason helper and the api.md Unsupported list)
- 2026-11-09: linked conflicts_with ANDROIDEDGEAICORE-22 (OpenAIServer chat handler and AiEngineManager generation)
- 2026-11-09: linked conflicts_with ANDROIDEDGEAICORE-23 (OpenAIServer chat handler and AiEngineManager generation)
- 2026-11-10 batch-autopilot: shipped PR #53 (stacked on #52)
- 2026-11-10: linked relates_to ANDROIDEDGEAICORE-24 (batch follow-ups)
- 2026-11-10: closed (merged in PR #53)
