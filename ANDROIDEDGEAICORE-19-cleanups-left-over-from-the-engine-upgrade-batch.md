---
id: ANDROIDEDGEAICORE-19
title: "Cleanups left over from the engine upgrade batch"
status: done
created: 2026-11-09
updated: 2026-11-10
links:
  conflicts_with: [ANDROIDEDGEAICORE-15, ANDROIDEDGEAICORE-16, ANDROIDEDGEAICORE-17, ANDROIDEDGEAICORE-22, ANDROIDEDGEAICORE-23]
  blocked_by: [ANDROIDEDGEAICORE-20]
  relates_to: [ANDROIDEDGEAICORE-4, ANDROIDEDGEAICORE-8, ANDROIDEDGEAICORE-10, ANDROIDEDGEAICORE-11, ANDROIDEDGEAICORE-12, ANDROIDEDGEAICORE-24]
---

## Description
Small items noticed during the batch, none blocking:
- `ConversationManager.createConversation` logs the full system instruction text (`errors-and-logging.md`: never log prompts).
- `app/lint-baseline.xml` has a stale entry for a warning the GPU fallback change removes.
- Preference-key literals (`crashed_during_init`, `saved_model_path`, `saved_backend`, prefs file names) are repeated across `InferenceService` and `MainActivity`.
- Status broadcast texts are plain strings in Kotlin, against `android.md` (user-visible text in `strings.xml`).
- `InferenceService.onDestroy` calls `aiEngineManager.close()` outside `inferenceMutex`.
- After the cancel-on-disconnect PR lands, re-check that every request path releases the load counter exactly once (the old outer safety decrement double-released).
- `loadModel` closes the previous engine before inspecting a new file, so a rejected file leaves no model loaded; decide whether a rejected file should keep the running model.

Background: found while running the ANDROIDEDGEAICORE-4 to -14 batch.

## Acceptance criteria
- [ ] Each item fixed or consciously dropped
- [ ] Docs and pitfalls updated where behaviour changes

## Plan
**Date:** 2026-11-09  **Status:** Validated

### Objective
Fix or consciously drop each of the seven leftovers from the engine batch, with docs and pitfalls updated where behaviour changes.

### Context
- Findings check (2026-11-09), item by item:
  1. Confirmed: `ConversationManager.createConversation` logs `SystemInstruction: ${systemInstruction ?: "None"}` (`develop` and every branch). Note: no app code calls `createConversation` today (REST builds transient states, pitfall 6), so nothing is logged in practice; the same line also logs `apiToken.take(8)` (pitfall 9).
  2. Confirmed: `app/lint-baseline.xml` has an `ApplySharedPref` entry for `InferenceService.kt` line 116 (`.commit()`); #40 replaces that call with `prefs.edit(commit = true)` in `markInitStarted`, so the entry is stale once #40 is merged.
  3. Corrected: `MainActivity` does not use these keys (it reads its own prefs through its `PREFS_NAME` constant). The repetition is inside `InferenceService`: on `develop`/#40, `"inference_service_prefs"` (2 places), `"saved_model_path"`, `"saved_backend"` and `"crashed_during_init"` (up to 6 places) are literals. #39/#46 already add `KEY_CRASHED_DURING_INIT`, `KEY_SAVED_MODEL_PATH`, `KEY_SAVED_VISION_BACKEND`, `KEY_SAVED_AUDIO_BACKEND`, and #42 `KEY_SAVED_MAX_NUM_TOKENS`, to the companion; after the merge the prefs file name and `saved_backend` remain literal, plus any literal the merge leaves.
  4. Confirmed: `sendStatusBroadcast` texts are Kotlin strings ("Service starting...", "Loading model from path: $modelPath", "Ping success ($i)", `STATUS_UNSUPPORTED_MODEL`, ...). `MainActivity.updateUIForStatus` classifies a status by English substrings (`loaded`, `Error`, `Initializing`, `Loading`, `Verifying`, `Restoring`, `Service starting`, `Idle`), and `scripts/device.sh start` greps logcat for `Model loaded successfully`.
  5. Confirmed: `InferenceService.onDestroy` cancels `serviceScope`, shuts down `ConversationManager`, calls `aiEngineManager.close()` without `inferenceMutex`, then stops the server, so an HTTP generation can still be using the engine while it is closed.
  6. Confirmed as a merge hazard, handled in ANDROIDEDGEAICORE-20 (Steps 5 and 6): #36's single `finally` release versus early decrements in #42/#45 and the outer "safety decrement" in the other branches. This ticket re-checks it on `develop` with tests.
  7. Confirmed: `AiEngineManager.loadModel` calls `close()` before `ModelInspector` inspects the new file (#39), and on `UnsupportedModelException` `InferenceService` removes `KEY_SAVED_MODEL_PATH`, so a rejected file leaves no model loaded.
- Depends on ANDROIDEDGEAICORE-20 (all items touch code from #36 to #46).
- Rules: AGENTS.md rules 3, 4, 7; `docs/standards/errors-and-logging.md`; `docs/standards/android.md` (strings in `strings.xml`, lifecycle: release native resources in `onDestroy`); `docs/standards/review.md` item 8 (no change to SharedPreferences names or keys); `docs/standards/testing.md`.

### Approach
1. Log only whether a system instruction is set and its length. The token prefix in the same line stays (pitfall 9 is its own change).
2. Delete the stale baseline entry by hand (not `updateLintBaseline`, which would also baseline new issues).
3. Move the remaining literals to `InferenceService` companion constants (`PREFS_NAME_SERVICE`, `KEY_SAVED_BACKEND`, and any left), same string values, so stored data is untouched.
4. Move every status text to `strings.xml` (`getString` with format args), keeping the English text identical so `updateUIForStatus` and `device.sh` keep working.
5. `onDestroy`: stop the server first (no new requests; Ktor cancels running calls, which cancels native decoding through #36), then close the engine under `inferenceMutex` with a bounded wait (`AiEngineManager.closeWhenIdle(timeoutMs)`, run with `runBlocking` on the main thread, 2 s, well below the service timeout); on timeout, `Log.w` and close anyway, since the process is ending.
6. Route tests that drive every exit of the chat handler and assert `/v1/load` is 0 afterwards; fix any path that still double-releases or leaks.
7. A rejected file keeps the running model: inspect before `close()`; on rejection the previous engine stays loaded, and `InferenceService` restores the previous `saved_model_path`, `saved_backend` and the other saved model settings written in the same prefs edit (encoder backends, context size, speculative decoding) instead of removing the path. `onStartCommand` overwrites them with the new values before the load starts, so it reads the previous values first (only when an engine is loaded) and passes them to the load coroutine. This changes what the stored path means after a rejection (the previous model, not none); key names and formats are unchanged (`review.md` item 8) and reports "Error: Unsupported model file (previous model still loaded)".

### Out of scope
- Removing the token-prefix logs (pitfall 9) and the unused `ConversationManager.createConversation`.
- Translating the status texts or replacing `updateUIForStatus`'s substring matching.

### Steps
#### Step 1: Stop logging the system instruction
- **Status:** todo
- **Depends on:** none
- **Files:** `app/src/main/java/com/aanand/edgeaicore/ConversationManager.kt` (`createConversation`)
- **Change:** log `systemInstruction != null` and its length instead of the text.
- **Verification:** `./gradlew testDebugUnitTest` (`ConversationManagerTest`, added by #42, still passes); `git grep -n "SystemInstruction: \\$" app/src` finds nothing.

#### Step 2: Drop the stale lint baseline entry
- **Status:** todo
- **Depends on:** none
- **Files:** `app/lint-baseline.xml`
- **Change:** delete the `ApplySharedPref` issue for `InferenceService.kt` `.commit()`.
- **Verification:** `./gradlew lintDebug` passes with no new issues and no "fixed issues still in baseline" note for it.

#### Step 3: Preference keys as constants
- **Status:** todo
- **Depends on:** none
- **Files:** `app/src/main/java/com/aanand/edgeaicore/InferenceService.kt` (companion, `onCreate`, `onStartCommand`)
- **Change:** as in Approach 3.
- **Verification:** `git grep -n -E '"(inference_service_prefs|saved_[a-z_]+|crashed_during_init)"' app/src/main` lists only the companion definitions; build passes.

#### Step 4: Status texts in `strings.xml`
- **Status:** todo
- **Depends on:** 3
- **Files:** `app/src/main/res/values/strings.xml` (new `status_*` strings), `InferenceService.kt` (every `sendStatusBroadcast` call; the `STATUS_*` constants removed)
- **Change:** as in Approach 4.
- **Verification:** `./gradlew lintDebug` (no `SetTextI18n`/hardcoded-text issues added); device check in Step 8 shows the same status texts and UI states.

#### Step 5: Close the engine under the lock in `onDestroy`
- **Status:** todo
- **Depends on:** none
- **Files:** `app/src/main/java/com/aanand/edgeaicore/AiEngineManager.kt` (new `closeWhenIdle(timeoutMs)`), `InferenceService.kt` (`onDestroy` order), `app/src/test/java/com/aanand/edgeaicore/AiEngineManagerTest.kt`
- **Change:** as in Approach 5.
- **Verification:** unit tests: `closeWhenIdle` waits for a running fake generation to finish, and closes after the timeout when it does not.

#### Step 6: Load counter audit
- **Status:** todo
- **Depends on:** none
- **Files:** `app/src/test/java/com/aanand/edgeaicore/OpenAIServerTest.kt`, `OpenAIServer.kt` only if a test fails
- **Change:** one test per exit: success (stream, non-stream), validation 400, tool-mapping 400, body-parse 400, engine error (500 and SSE error), empty response, model not loaded, client disconnect.
- **Verification:** all pass with `/v1/load` at 0 after each.

#### Step 7: Keep the running model when a new file is rejected
- **Status:** todo
- **Depends on:** 3, 4
- **Files:** `AiEngineManager.kt` (`loadModel`: inspect, then `close()`), `InferenceService.kt` (`onStartCommand` reads the previous saved path, backend and model settings before writing the new ones; the `UnsupportedModelException` branch restores them all; status text from `strings.xml`), `strings.xml`, `AiEngineManagerTest.kt`
- **Change:** as in Approach 7. A rejection with no previous model keeps today's behaviour (path removed).
- **Verification:** `AiEngineManagerTest`: a rejected file leaves the previous fake engine loaded and open; with none loaded it stays unloaded.

#### Step 8: Docs and device check
- **Status:** todo
- **Depends on:** 1, 2, 3, 4, 5, 6, 7
- **Files:** `docs/pitfalls.md` (21 as rewritten by #39: the clauses "clears the crash flag and the saved model path" and "stays up without a model (health 503)" become: clears the crash flag, keeps the previous model and its saved settings, or stays up without one when none was loaded), `docs/architecture.md` (the `onDestroy` line: stop the server, then close the engine under the lock); the README does not describe either and stays as is; evidence in `~/.claude/tickets/android_edge_ai_core/ANDROIDEDGEAICORE-19-evidence/`
- **Change:** update the docs that describe changed behaviour. On the emulator: load Gemma 4 E2B, start with `ANDROIDEDGEAICORE-11-evidence/bad.litertlm`, check the status and that chat still answers; stop the service during a streaming request and check logcat for a clean close (no native crash).
- **Verification:** `scripts/check_docs.sh` passes; device results recorded in Verification.

### Tests
`ConversationManagerTest` (unchanged, must pass), `AiEngineManagerTest` (Steps 5, 7), `OpenAIServerTest` (Step 6). `InferenceService` changes are covered by the device check (agreed exception). Commands: `./gradlew testDebugUnitTest lintDebug`, `scripts/check_docs.sh`.

### Risks and open questions
- Blocking the main thread up to 2 s in `onDestroy`; accepted (bounded, and only at service stop).
- Keeping the old model while reporting an error could confuse a user who expected the new one; the status says the previous model is still loaded.

### Success criteria
- [ ] Items 1 to 7 fixed as in Approach, none dropped
- [ ] Tests for Steps 5, 6, 7; checks pass
- [ ] Pitfalls, architecture and README updated where behaviour changed; device check recorded

### Owner decisions 2026-11-09
- Approved: a rejected model keeps the running model; `onDestroy` waits up to 2 s for a running generation.

### Reviewer brief
Objective: settle the seven batch leftovers; all are fixed, none dropped. Key decisions: log only presence and length of the system instruction (token prefix left for pitfall 9); delete the stale baseline entry by hand; remaining `inference_service_prefs` literals become `InferenceService` companion constants with unchanged values; status texts move to `strings.xml` with identical English text (UI and `device.sh` match on it); `onDestroy` stops the server, then closes the engine under `inferenceMutex` with a 2 s bounded wait; route tests cover every chat exit for `/v1/load` = 0; a rejected model file keeps the running model and its saved settings. Runs after ANDROIDEDGEAICORE-20. Out of scope: pitfall 9 token logs, removing unused `createConversation`, translations. Success: items fixed with tests for Steps 5 to 7, docs updated, device check recorded.
The decisions above are intended; report a problem with one only if it breaks a requirement or a documented rule.

### Validation log
- Round 1: validator FAIL. Judged FAIL. (1) BLOCKING previous saved path is overwritten before the load, so it cannot be restored: valid, read before the write. (2) MINOR Step 7 should depend on Step 4: valid. (3) MINOR pitfall 21 phrase not on `develop`: disproved for the target state (it is #39's text, merged by ANDROIDEDGEAICORE-20), anchor now says so. (4) MINOR `ConversationManagerTest` only on #42: valid, noted. (5) MINOR stored-data meaning change: valid, stated.
- Round 2: validator PASS. Judged PASS. Minors fixed inline: restore all saved model-setting keys, not just path and backend; pitfall 21 clauses named.

## Implementation notes
PR: https://github.com/archit342000/android_edge_ai_core/pull/52, branch bugfix/engine-batch-cleanups, base feature/cancel-nonstream-disconnect
Device check: evidence/out.txt. Formal review loop not run.

## Verification

## Review history

## Log
- 2026-11-09: created from the follow-ups of the engine upgrade batch
- 2026-11-09: findings checked (6 confirmed; item 3 corrected: the literals repeat inside InferenceService, not MainActivity; item 6 is a merge hazard handled in ANDROIDEDGEAICORE-20); plan validated, 2 rounds
- 2026-11-09: linked blocked_by ANDROIDEDGEAICORE-20 (its code lives on the unmerged batch PRs)
- 2026-11-09: linked conflicts_with ANDROIDEDGEAICORE-15 (OpenAIServer chat handler and OpenAIServerTest load-counter tests)
- 2026-11-09: linked conflicts_with ANDROIDEDGEAICORE-16 (InferenceService.onStartCommand crash flag and prefs)
- 2026-11-09: linked conflicts_with ANDROIDEDGEAICORE-17 (AiEngineManager.loadModel and InferenceService status texts)
- 2026-11-09: owner decisions recorded in the Plan
- 2026-11-09: linked conflicts_with ANDROIDEDGEAICORE-22 (OpenAIServer load-counter tests)
- 2026-11-09: linked conflicts_with ANDROIDEDGEAICORE-23 (OpenAIServer load-counter tests and onDestroy close)
- 2026-11-10 batch-autopilot: shipped PR #52 (stacked on #51)
- 2026-11-10: linked relates_to ANDROIDEDGEAICORE-24 (batch follow-ups)
- 2026-11-10: closed (merged in PR #52)
