---
id: ANDROIDEDGEAICORE-15
title: "Return a fixed 400 for request body parse failures"
status: done
created: 2026-11-09
updated: 2026-11-10
links:
  conflicts_with: [ANDROIDEDGEAICORE-17, ANDROIDEDGEAICORE-19, ANDROIDEDGEAICORE-22, ANDROIDEDGEAICORE-23]
  blocked_by: [ANDROIDEDGEAICORE-20]
  relates_to: [ANDROIDEDGEAICORE-10, ANDROIDEDGEAICORE-13, ANDROIDEDGEAICORE-24]
---

## Description
Found while implementing ANDROIDEDGEAICORE-10 and -13 (pitfall 2): any failure while parsing the request body goes through the outer `catch (e: Exception)` in `OpenAIServer` and returns `400 "Bad Request: <exception message>"`. That message comes from Gson/Ktor and can echo the input, which `docs/standards/security.md` forbids (fixed short message).
- A wrong-typed value (`"seed": 1.5`, `"max_tokens": "abc"`, an out-of-range int) gives a Gson `NumberFormatException` text containing the value.
- A request without `messages` leaves the non-null Kotlin property null, so the client sees `Bad Request: null`.
Also check that malformed JSON, an empty body and a wrong content type each give the same fixed 400 and release the load counter.

Background: found while running the ANDROIDEDGEAICORE-4 to -14 batch.

## Acceptance criteria
- [ ] Malformed JSON, wrong-typed fields and a missing `messages` return `400` with one fixed message that never echoes the input
- [ ] The detail is logged with `Log.w`/`Log.e` without request content
- [ ] Tests for each case on the stream and non-stream paths; `/v1/load` returns to 0
- [ ] `docs/standards/api.md` Errors table and pitfall 2 updated

## Plan
**Date:** 2026-11-09  **Status:** Validated

### Objective
Every chat request whose body cannot be read as a valid `ChatCompletionRequest` gets `400` with one fixed message that never echoes input or internal names, the detail is logged without request content, and the load counter is released.

### Context
- Findings check (2026-11-09): confirmed on `develop` and every PR branch: the chat handler's outer `catch (e: Exception)` in `OpenAIServer` responds `ErrorResponse("Bad Request: ${e.message}")`. Missing `messages`: Gson leaves the non-null Kotlin property null, and the first use (`ChatRequestValidator.validate` on #42, `request.messages.isEmpty()`) throws an NPE that reaches the same catch; the Description reports the body as `Bad Request: null` (on ART the NPE text may instead name the method, which is also not a fixed message). Correction (checked in the bytecode of `ktor-server-content-negotiation-jvm-2.3.12.jar`, `RequestConverterKt`): for a wrong-typed value (`"seed": 1.5`, `"max_tokens": "abc"`) Ktor 2.3.12 wraps Gson's error as `BadRequestException("Failed to convert request body to <type>", cause)` (`RequestConverterKt`), so the client sees `Bad Request: Failed to convert request body to class com.aanand.edgeaicore.ChatCompletionRequest`: an internal class name, not a fixed message, but not the input value (Gson's text, which has the value, is only in the cause). Either way it breaks `docs/standards/security.md` ("Return 400 with a fixed short message") and pitfall 2.
- Depends on ANDROIDEDGEAICORE-20: the handler is rewritten by #36 (single `finally` release of `activeRequests`), #42 (`ChatRequestValidator`, early 400 with fixed messages) and #45 (`OpenAIToolMapper.prepare` 400). This plan targets `develop` after those merges.
- The fixed messages of `ChatRequestValidator` and `OpenAIToolMapper` (for example "messages must contain 1 to 256 items") contain no input and stay as they are.
- Rules: `docs/standards/security.md` (Input and output), `docs/standards/errors-and-logging.md` (Log.w for rejected requests, no message content), `docs/standards/api.md` (Errors table), `docs/pitfalls.md` 2, `docs/standards/testing.md` (route tests via `testApplication`, `OpenAIServerTest`).

### Approach
- Read the body in one place: a new private suspend helper in `OpenAIServer` wraps `call.receive<ChatCompletionRequest>()`, catches any non-cancellation exception from it, and then checks the required fields Gson can leave null (`messages`, and each message's `role` and `content`). The `receive` call and the field checks run inside the same try, and the checks are null-safe (a `null` list element counts as missing). Any failure throws a private nested `InvalidRequestBodyException` in `OpenAIServer` (project exception, `errors-and-logging.md`; nested, so no new source class and `OpenAIServerTest` covers it) carrying only the exception class name. The DTO fields stay non-null: `messages`, `role` and `content` are required, and `docs/standards/kotlin.md` (Serialisation) asks for nullable types only for fields that may legitimately be absent, and to validate after parsing, which this helper does.
- The chat handler catches `InvalidRequestBodyException` and responds `400 {"error": "Invalid request body"}` (constant `INVALID_BODY_MESSAGE` in the `OpenAIServer` companion), with `Log.w(TAG, "Rejected request body: <exception class>")`, never `e.message`. The counter is released by #36's `finally`.
- Catching everything from `receive` (instead of listing Ktor/Gson types) covers malformed JSON, wrong types, out-of-range ints, an empty body and an unsupported content type, whatever exception Ktor picks.
- The outer `catch (e: Exception)` for non-parse failures (for example "Model not loaded", pitfall 3) is not changed here; pitfall 2 is reworded to say parse failures are fixed.

### Out of scope
- The 500 path and SSE error events that send `error.message`, and the outer 400 for non-parse failures (rest of pitfalls 2 and 3).
- New range checks (already in #42).

### Steps
#### Step 1: Failing route tests
- **Status:** todo
- **Depends on:** none
- **Files:** `app/src/test/java/com/aanand/edgeaicore/OpenAIServerTest.kt` (modify)
- **Change:** cases for malformed JSON, `"seed": 1.5`, `"max_tokens": "abc"`, an int beyond `Int.MAX_VALUE`, no `messages`, `"messages": [null]`, a message without `role`, a message without `content`, an empty body, and `Content-Type: text/plain`; each with `"stream": true` and `false` where the body can carry it. Each asserts `400`, body exactly `{"error":"Invalid request body"}`, and `/v1/load` back to 0 (`awaitLoadZero`, added to `OpenAIServerTest` by #36).
- **Verification:** `./gradlew testDebugUnitTest --tests '*OpenAIServerTest*'` fails on the new cases before Step 2.

#### Step 2: Fixed 400 for body failures
- **Status:** todo
- **Depends on:** 1
- **Files:** `app/src/main/java/com/aanand/edgeaicore/OpenAIServer.kt` (modify: chat handler, new receive helper, private nested `InvalidRequestBodyException`, companion `INVALID_BODY_MESSAGE`)
- **Change:** as in Approach; `CancellationException` is rethrown.
- **Verification:** Step 1 tests pass; full `./gradlew testDebugUnitTest lintDebug`.

#### Step 3: Docs
- **Status:** todo
- **Depends on:** 2
- **Files:** `docs/standards/api.md` (Errors table: `400` for an unreadable body has the fixed message `Invalid request body`), `docs/pitfalls.md` (pitfall 2 reworded: parse failures fixed, other paths remain; pitfall 26: the large-body failure now shows as `400 "Invalid request body"`, with the `NoSuchMethodError` only in logcat)
- **Change:** as listed. Status code and `ErrorResponse` shape are unchanged, only the message text, so under AGENTS.md rule 5 only `api.md` changes; the README names no 400 texts and stays as is.
- **Verification:** `scripts/check_docs.sh` passes.

#### Step 4: Device check
- **Status:** todo
- **Depends on:** 3
- **Files:** none in the repo; evidence in `~/.claude/tickets/android_edge_ai_core/ANDROIDEDGEAICORE-15-evidence/`
- **Change:** on the test emulator, send three bad bodies with `scripts/device.sh api` (malformed JSON, `"seed": 1.5`, no `messages`), then `GET /v1/load`; capture `logs` and check the `Log.w` line has no request content.
- **Verification:** three `400 {"error":"Invalid request body"}`, load 0, logcat shows the class name only.

### Tests
`OpenAIServerTest` cases from Step 1. Commands: `./gradlew testDebugUnitTest lintDebug`, `scripts/check_docs.sh`.

### Risks and open questions
- Gson may accept some wrong types silently (for example a number for `role`, coerced to a string); those are not parse failures and stay accepted.

### Success criteria
- [ ] Malformed JSON, wrong-typed fields, missing `messages`, an empty body and a wrong content type all return `400 {"error":"Invalid request body"}`, on the stream and non-stream paths
- [ ] The rejection is logged with `Log.w` and the exception class only
- [ ] `/v1/load` returns to 0 after each case
- [ ] `api.md` Errors table and pitfall 2 updated; checks pass

### Amendment 2026-11-09 (after validation)
- `content` is not a required field: on #45 `ChatMessage.content` is `JsonElement? = null` (an assistant turn may carry only `tool_calls`). The required-field check covers `messages`, `null` list elements and `role` only; the Step 1 case "a message without `content`" is replaced by "an assistant message with `tool_calls` and no `content` is still accepted (200)". Overrides the Approach, Step 1 and Reviewer brief wording on `content`.

### Reviewer brief
Objective: a fixed `400 {"error":"Invalid request body"}` for any unreadable chat body (malformed JSON, wrong types, out-of-range ints, missing `messages`/`role`/`content`, `[null]` messages, empty body, wrong content type), logged with `Log.w` and the exception class only, load counter released by #36's `finally`. Key decisions: one receive helper catches everything from `call.receive` plus null-safe required-field checks in the same try; a private nested exception in `OpenAIServer`; DTO fields stay non-null; the outer catch for non-parse failures is unchanged. Runs on `develop` after ANDROIDEDGEAICORE-20. Out of scope: 500/SSE error texts and the other pitfall 2/3 paths. Success: route tests for each case on both paths with `/v1/load` back to 0, `api.md` and pitfalls 2 and 26 updated, checks pass, emulator check recorded.
The decisions above are intended; report a problem with one only if it breaks a requirement or a documented rule.

### Validation log
- Round 1: validator FAIL. Judged FAIL. (1) BLOCKING new `InvalidRequestBodyException.kt` without a test class: valid, made a private nested class in `OpenAIServer`. (2) MINOR no missing-`content` case: valid, added. (3) MINOR `[null]` element could NPE outside the wrapper: valid, checks run in the same try, null-safe, case added. (4) MINOR `awaitLoadZero` only on #36: valid, cited. (5) MINOR pitfall 26 would go stale: valid, added to Step 3. (6) MINOR README/rule 5: valid, reasoning stated. (7) MINOR unsourced claims: valid, Ktor claim cited to the jar bytecode, NPE text attributed to the Description.
- Round 2: validator FAIL. Judged PASS. (1) BLOCKING `kotlin.md` requires nullable DTO fields: disproved, the rule covers fields that may be absent (optional) and says "validate after parsing", which the plan does for required fields; a clarifying sentence added to Approach. Previous BLOCKING confirmed resolved.

## Implementation notes
PR: https://github.com/archit342000/android_edge_ai_core/pull/50, branch bugfix/fixed-400-body-parse, base feature/openai-compat-gaps
Emulator check in evidence/out.txt. Formal review loop not run.

## Verification

## Review history

## Log
- 2026-11-09: created from the follow-ups of the engine upgrade batch
- 2026-11-09: findings checked (confirmed; correction: the wrong-type 400 leaks the internal class name, not the input value); plan validated, 2 rounds
- 2026-11-09: plan amended (content is optional since #45)
- 2026-11-09: linked blocked_by ANDROIDEDGEAICORE-20 (its code lives on the unmerged batch PRs)
- 2026-11-09: linked conflicts_with ANDROIDEDGEAICORE-17 (OpenAIServer chat handler and OpenAIModels)
- 2026-11-09: linked conflicts_with ANDROIDEDGEAICORE-19 (OpenAIServer chat handler and OpenAIServerTest load-counter tests)
- 2026-11-09: linked conflicts_with ANDROIDEDGEAICORE-22 (OpenAIServer chat handler)
- 2026-11-09: linked conflicts_with ANDROIDEDGEAICORE-23 (OpenAIServer chat handler)
- 2026-11-10 batch-autopilot: shipped PR #50 (stacked on #49)
- 2026-11-10: linked relates_to ANDROIDEDGEAICORE-24 (batch follow-ups)
- 2026-11-10: closed (merged in PR #50)
