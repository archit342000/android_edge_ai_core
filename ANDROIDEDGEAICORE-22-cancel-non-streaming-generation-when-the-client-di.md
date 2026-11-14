---
id: ANDROIDEDGEAICORE-22
title: "Cancel non-streaming generation when the client disconnects"
status: done
created: 2026-11-09
updated: 2026-11-10
links:
  conflicts_with: [ANDROIDEDGEAICORE-15, ANDROIDEDGEAICORE-19, ANDROIDEDGEAICORE-21, ANDROIDEDGEAICORE-23]
  blocked_by: [ANDROIDEDGEAICORE-20]
  split_from: [ANDROIDEDGEAICORE-17]
  relates_to: [ANDROIDEDGEAICORE-24]
---

## Description
Streaming requests stop generating when the client goes away (ANDROIDEDGEAICORE-8), but a non-streaming request runs to the end and holds the inference lock even when nobody waits for the answer. Ktor 2.3.12 on Netty does not cancel an HTTP/1.1 call when the connection closes; the Netty channel is reachable through `RoutingApplicationCall.engineCall` (`NettyApplicationCall.context`), whose close can cancel the generation.

Background: split from ANDROIDEDGEAICORE-17 at the owner's request.

## Acceptance criteria
- [ ] A non-streaming client that disconnects mid-generation stops the native generation and frees the inference lock
- [ ] `/v1/load` returns to 0 and the next request works
- [ ] Route tests through a disconnect seam; device check with a real disconnect
- [ ] Docs and pitfalls updated

## Plan
**Date:** 2026-11-09  **Status:** Validated

### Objective
When a non-streaming client disconnects while its answer is being generated, stop native decoding, free the inference lock and release the load counter, as streaming requests already do.

### Context
- After ANDROIDEDGEAICORE-20 the non-streaming branch of the `OpenAIServer` chat handler calls `aiEngineManager.generateConversationResponseAsync(...)` directly in the call's coroutine and responds when it returns; the counter is released in #36's `finally`.
- `AiEngineManager` (#36) already handles a coroutine cancel: `invokeOnCancellation` calls `cancelProcess()` through `CancellableGeneration`, waits up to `CANCEL_WAIT_MS`, closes the conversation and reports the `CancellationException` through `onErrorCallback`. So cancelling the coroutine that runs the generation is enough on the engine side.
- Ktor 2.3.12 on Netty does not cancel an HTTP/1.1 call when the connection closes (checked in `ktor-server-netty-jvm-2.3.12.jar`: `NettyHttp1Handler.channelInactive` only removes a handler and fires the event; `NettyApplicationCallHandler` cancels the call job only from `exceptionCaught` and has no `closeFuture` use). The channel is reachable: `RoutingApplicationCall.getEngineCall()` returns the engine call, and `NettyApplicationCall.getContext()` returns the Netty `ChannelHandlerContext`, whose `channel().closeFuture()` completes when the connection closes.
- Whether Netty notices a client's FIN while the handler is busy depends on the channel still reading (auto-read); this is confirmed on the device before building on it (Step 1).
- Streaming: pitfall 27 (#36) says a dropped stream is noticed only when a token write fails. Not changed here.
- Rules: `docs/standards/testing.md` (server seam `OpenAIServer(aiEngineManager, isValidToken)`, route tests with `testApplication`, no network or fixed port in tests), `docs/standards/errors-and-logging.md` (no `CancellationException` swallowed), `docs/standards/kotlin.md`, `docs/pitfalls.md`.

### Approach
- A disconnect seam: `OpenAIServer` gains a constructor parameter `onClientGone: (ApplicationCall, () -> Unit) -> DisposableHandle?`, placed before `isValidToken` so existing trailing-lambda calls (`OpenAIServer(manager) { ... }` in tests, `InferenceService`) still bind to `isValidToken`, with a production default `NettyDisconnect.watch` that unwraps `RoutingApplicationCall.engineCall` to `NettyApplicationCall`, adds a `closeFuture` listener that runs the callback, and returns a handle that removes the listener; it returns null for non-Netty calls (the Ktor test host), so behaviour there is unchanged.
- Non-streaming branch: run the generation in a child `launch` inside `coroutineScope`, register the watcher to cancel that job, `join()`, and dispose of the handle in `finally`. The watcher sets a `clientGone` flag before cancelling. If the flag is set (whether the job was cancelled or had just finished), log at `Log.i` "Generation cancelled (client disconnected)" like the streaming path and skip `call.respond`; the `finally` releases the counter. A cancel of the call itself still propagates.
- Step 1 checks on the device that `closeFuture` fires for a non-streaming disconnect; if it does not (auto-read off), the plan stops after Step 1, the ticket records why, and the documented limitation stays.

### Out of scope
- Noticing a dropped stream before its first token write (pitfall 27).
- Ktor or Netty upgrades.

### Steps
#### Step 1: Confirm Netty reports the disconnect
- **Status:** todo
- **Depends on:** none
- **Files:** a throwaway local branch (not pushed) that adds a `closeFuture` listener logging `Log.d` in the non-streaming branch; evidence in `~/.claude/tickets/android_edge_ai_core/ANDROIDEDGEAICORE-22-evidence/`
- **Change:** on the emulator, after ANDROIDEDGEAICORE-20 (so `max_tokens` exists), send a non-streaming request asking for a long answer with `max_tokens` 400 using `curl --max-time 3`; read logcat.
- **Verification:** the listener's line appears within about a second of curl giving up. If not, record it and stop (Approach).

#### Step 2: Disconnect seam
- **Status:** todo
- **Depends on:** 1
- **Files:** `app/src/main/java/com/aanand/edgeaicore/NettyDisconnect.kt` (create: `watch`), `app/src/test/java/com/aanand/edgeaicore/NettyDisconnectTest.kt` (create), `OpenAIServer.kt` (constructor parameter with the default)
- **Change:** as in Approach.
- **Verification:** `NettyDisconnectTest`: a non-Netty `ApplicationCall` (the test host) returns null; build passes.

#### Step 3: Cancel the non-streaming generation
- **Status:** todo
- **Depends on:** 2
- **Files:** `OpenAIServer.kt` (non-streaming branch), `OpenAIServerTest.kt` (reuses #36's `FakeEngineFactory(hangUntilCancelled = true)` and `FakeConversation.cancelCount`)
- **Change:** as in Approach.
- **Verification:** route test with a fake `onClientGone` that fires once generation has started: the fake engine's `cancelProcess` was called, the route logs no error and does not call `respond` (the test client sees Ktor's unhandled-call result, not a 400/500 from the handler), `/v1/load` returns to 0, and a following request succeeds; a normal request still returns 200.

#### Step 4: Docs and device check
- **Status:** todo
- **Depends on:** 3
- **Files:** `docs/standards/testing.md` (server seam sentence names the new parameter; `NettyDisconnectTest` added to the current test classes), `docs/standards/api.md` (the disconnect sentence covers non-streaming requests), `docs/pitfalls.md` (27: non-streaming disconnects are noticed through the connection close), `docs/architecture.md` (request-flow line "a streaming client that disconnects cancels the generation" extended to non-streaming); the README does not mention disconnects and stays as is; evidence folder
- **Change:** as listed. On the emulator: the Step 1 curl, then `GET /v1/load` and a new request.
- **Verification:** logcat shows the generation stopped soon after the disconnect, load 0, next request 200; `./gradlew testDebugUnitTest lintDebug` and `scripts/check_docs.sh` pass.

### Tests
`NettyDisconnectTest` (new), `OpenAIServerTest`. Commands: `./gradlew testDebugUnitTest lintDebug`, `scripts/check_docs.sh`.

### Risks and open questions
- Netty may not see the close while busy (Step 1 decides).
- The same watcher could later let streaming notice a disconnect during prefill (pitfall 27); a possible follow-up, not done here.

### Success criteria
- [ ] A non-streaming disconnect stops native decoding and frees the lock (or Step 1 shows it cannot be detected, recorded)
- [ ] `/v1/load` back to 0; next request works
- [ ] Route tests through the seam; device check recorded; docs updated

### Reviewer brief
Objective: a non-streaming client that disconnects mid-generation stops native decoding, frees the lock and releases `/v1/load`. Key decisions: Ktor 2.3.12/Netty does not cancel HTTP/1.1 calls on close, so a seam `onClientGone` (default: a Netty `closeFuture` listener via `RoutingApplicationCall.engineCall` → `NettyApplicationCall.context`) cancels a child job that runs the generation; #36's engine cancel path does the rest; a `clientGone` flag skips the response; a device spike (Step 1) confirms Netty sees the close before anything is built, else the limitation is recorded. Runs after ANDROIDEDGEAICORE-20. Out of scope: earlier detection for streams (pitfall 27), Ktor upgrades.
The decisions above are intended; report a problem with one only if it breaks a requirement or a documented rule.

### Validation log
- Round 1: validator PASS. Judged PASS. Minors fixed inline: `max_tokens` exists after the merge; parameter placed before `isValidToken`; reuse #36's hanging fake; `api.md` and the test-class list added; expected outcome of the cancelled route; `clientGone` flag for the finish/close race; `Log.i` like the streaming path; Netty evidence wording. (10) constants/top-level declaration: accepted for implement (standard rules apply).

## Implementation notes
PR: https://github.com/archit342000/android_edge_ai_core/pull/51, branch feature/cancel-nonstream-disconnect, base bugfix/fixed-400-body-parse
Step 1 spike merged into the implementation: Netty closeFuture fires (emulator evidence/out.txt, 46-88 ms stop). Formal review loop not run.

## Verification

## Review history

## Log
- 2026-11-09: created (split from ANDROIDEDGEAICORE-17)
- 2026-11-09: plan validated, 1 round
- 2026-11-09: linked blocked_by ANDROIDEDGEAICORE-20 (code lives on the unmerged batch PRs)
- 2026-11-09: linked conflicts_with ANDROIDEDGEAICORE-21 (OpenAIServer chat handler and AiEngineManager generation)
- 2026-11-09: linked conflicts_with ANDROIDEDGEAICORE-23 (OpenAIServer chat handler and AiEngineManager generation)
- 2026-11-09: linked conflicts_with ANDROIDEDGEAICORE-15 (OpenAIServer chat handler)
- 2026-11-09: linked conflicts_with ANDROIDEDGEAICORE-19 (OpenAIServer load-counter tests)
- 2026-11-10 batch-autopilot: shipped PR #51 (stacked on #50)
- 2026-11-10: linked relates_to ANDROIDEDGEAICORE-24 (batch follow-ups)
- 2026-11-10: closed (merged in PR #51)
