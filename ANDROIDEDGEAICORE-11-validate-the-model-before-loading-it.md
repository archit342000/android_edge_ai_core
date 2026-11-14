---
id: ANDROIDEDGEAICORE-11
title: "Validate the model before loading it"
status: done
created: 2026-11-09
updated: 2026-11-09
links:
  blocks: [ANDROIDEDGEAICORE-5, ANDROIDEDGEAICORE-7]
  blocked_by: [ANDROIDEDGEAICORE-3]
  conflicts_with: [ANDROIDEDGEAICORE-12]
  relates_to: [ANDROIDEDGEAICORE-18, ANDROIDEDGEAICORE-19, ANDROIDEDGEAICORE-20]
  split_from: [ANDROIDEDGEAICORE-2]
---

## Description
An unsupported model aborts natively (SIGABRT) and the crash-loop guard then blocks restarts (gap #9, pitfall 21). Check the model with `ModelInfo` (or test whether 0.19.1 throws instead) before `Engine.initialize()`, and report a clear error instead of crashing.

Background: research and gap analysis in ANDROIDEDGEAICORE-2 (LiteRT-LM v0.19.1). Verify with `scripts/device.sh` (`docs/testing-on-device.md`).

## Acceptance criteria
- [x] Loading an unsupported or corrupt model gives an error status, not a crash
- [x] The service stays usable and a valid model can then be loaded
- [x] Pitfall 21 updated

## Plan
**Date:** 2026-11-09  **Status:** Validated

### Objective
Stop an unsupported or corrupt model from killing the process (SIGABRT, then the crash-loop guard blocks restarts). Inspect the file with LiteRT-LM `ModelInfo` before `Engine.initialize()`, reject what the app cannot serve with a clear error status, and leave the service able to load a valid model afterwards. Runs after ANDROIDEDGEAICORE-3 (0.19.1).

### Context
- Rules: `docs/standards/errors-and-logging.md` (specific exceptions, short user-facing messages, detail in `Log`), `docs/standards/android.md` "Lifecycle" (keep the crash-loop guard: set `crashed_during_init` before loading a native model, clear it after success or a handled failure), `docs/pitfalls.md` 21, `docs/standards/testing.md` (fake the LiteRT-LM boundary; no model files in unit tests).
- Current code: `AiEngineManager.loadModel` checks only `File.exists()` then builds `Engine(config)` and calls `initialize()`; failures are logged and rethrown (GPU is retried on CPU). `InferenceService.onStartCommand` sets `crashed_during_init=true` (commit) before `loadModel`, clears it after return or in the `catch`, which broadcasts `"Error loading model: ${e.message}"`. A sticky restart with the flag set stops the service with "Crash loop detected".
- 0.19.1 API (`ModelInfo.kt` at `v0.19.1`): `ModelInfo.from(modelPath)` throws `LiteRtLmJniException` when the native info cannot be created; returns `ModelInfo.Llm`, `ModelInfo.Embedding` or `ModelInfo.Unknown`; `inputModalities()` gives `SupportedModalities(text, vision, audio, video)`; `ModelInfo` is `AutoCloseable`. Its classes have internal constructors, so unit tests cannot fake it.
- Open question from ANDROIDEDGEAICORE-2: whether 0.19.1 throws or aborts on unknown sections. Unknown until measured.

### Approach
New `ModelInspector` reads `ModelInfo` into a plain `ModelCapabilities` data class (model type, text/vision/audio input), and a pure `validate(capabilities)` rejects non-LLM or no-text models with a new `UnsupportedModelException` (project exception, short message). `loadModel` calls it before building the engine and keeps the result in a read-only `modelCapabilities` property, which ANDROIDEDGEAICORE-5 and -7 reuse. `InferenceService` maps `UnsupportedModelException` to the status "Error: Unsupported model file" and any other load failure to "Error: Model failed to load" (detail only in `Log`), and keeps the server running with no model (health 503) so the next start intent with a valid model works. The inspection stays inside the crash-guarded region, since it is native too.
Rejected: loading in a separate `:probe` process (only needed if the measurement in step 5 shows native aborts that inspection cannot catch; then the owner decides, see Risks).

### Out of scope
Choosing encoders or backends from capabilities (ANDROIDEDGEAICORE-5), MTP (ANDROIDEDGEAICORE-7), GPU fallback (ANDROIDEDGEAICORE-12), `minRuntimeVersion` checks, changing the crash-loop guard itself.

### Steps
#### Step 1: ModelInspector and capabilities
- **Status:** todo
- **Depends on:** none
- **Files:** `app/src/main/java/com/aanand/edgeaicore/ModelInspector.kt` (create: `ModelInspector`, `ModelCapabilities`, `UnsupportedModelException`)
- **Change:** `ModelInspector(reader: (String) -> RawModelInfo = ::readModelInfo)`. `RawModelInfo` is a plain data class (model type, input modalities), and the default `readModelInfo` is the only code that touches LiteRT-LM: it opens `ModelInfo.from(path).use { }` and copies the values out. `inspect(path): ModelCapabilities` calls the reader, wraps `LiteRtLmJniException` in `UnsupportedModelException` (cause kept) with `Log.e(TAG, ..., e)`, and maps the raw values through the pure `toCapabilities(raw)`. `ModelInspector.validate(capabilities)` (pure) throws `UnsupportedModelException` unless type is LLM and text input is supported. KDoc states it is blocking native I/O (call on `Dispatchers.IO`).
- **Verification:** compiles; covered by step 2 tests.

#### Step 2: Unit tests
- **Status:** todo
- **Depends on:** 1
- **Files:** `app/src/test/java/com/aanand/edgeaicore/ModelInspectorTest.kt` (create)
- **Change:** test `validate` for LLM+text (passes), embedding, unknown and no-text (throw `UnsupportedModelException`); `inspect` with a fake reader: values map to `ModelCapabilities`, and a reader throwing `LiteRtLmJniException` gives `UnsupportedModelException` with that cause. (`LiteRtLmJniException` is a plain `RuntimeException(message)` in 0.19.1, so the fake throws it directly; `inspect` catches only that type.)
- **Verification:** `./gradlew testDebugUnitTest` passes.

#### Step 3: Validate before loading
- **Status:** todo
- **Depends on:** 1
- **Files:** `app/src/main/java/com/aanand/edgeaicore/AiEngineManager.kt` (modify: `loadModel`, new `modelCapabilities` property, `close`)
- **Change:** after the file check and before the `try` that holds the GPU-to-CPU retry, `loadModel` calls the injected inspector and then `ModelInspector.validate(capabilities)`, so an `UnsupportedModelException` propagates without a CPU retry. The engine creation and its GPU-to-CPU retry move into a private `loadEngine(...)` that the retry calls instead of `loadModel`, so the file is inspected only once; store the result; clear it in `close()`. Inspection goes through an injectable `modelInspector: (String) -> ModelCapabilities` (default `ModelInspector()::inspect`) next to the `engineFactory` seam from ANDROIDEDGEAICORE-3.
- **Verification:** `./gradlew assembleDebug testDebugUnitTest`; `AiEngineManagerTest` cases: an inspector that throws `UnsupportedModelException`, and one that returns a non-LLM or no-text capability set (rejected by `validate`), both make `loadModel` throw it with the fake engine factory never called (also with GPU requested); a GPU init failure retries on CPU with the inspector called once; a valid capability set loads and `modelCapabilities` is set, then cleared by `close()`.

#### Step 4: Service error handling
- **Status:** todo
- **Depends on:** 3
- **Files:** `app/src/main/java/com/aanand/edgeaicore/InferenceService.kt` (modify: the `serviceScope.launch` load block in `onStartCommand`)
- **Change:** catch `UnsupportedModelException` before the generic `catch`; broadcast the fixed short statuses above (both contain "Error" so `MainActivity.updateUIForStatus` resets the switch); log with the throwable; clear `crashed_during_init` in both handled paths; on `UnsupportedModelException` also remove `saved_model_path`, so a sticky restart does not retry a file known to be unusable; skip the readiness ping after a failure.
- **Verification:** `./gradlew assembleDebug testDebugUnitTest lintDebug`.

#### Step 5: Device checks (emulator, CPU)
- **Status:** todo
- **Depends on:** 4
- **Files:** `scripts/device.sh` (modify: `LOG_TAGS` gains `ModelInspector`); evidence in `~/.claude/tickets/android_edge_ai_core/ANDROIDEDGEAICORE-11-evidence/`
- **Change:** `scripts/device.sh up` with the valid model first (for the token and forward), then for each file F in turn (a 1 MB random-bytes `bad.litertlm`, a truncated copy `trunc.litertlm` of the first 200 MB of `~/Downloads/model.litertlm`, then the full valid model): `MODEL=F scripts/device.sh model` and `MODEL=F scripts/device.sh start CPU` (`start` takes the path from `MODEL`). For bad files `start` is expected to exit with "start failed: ..." (it stops on the first `Error` line); the checks below run after it, with `scripts/device.sh logs` (add the `ModelInspector` tag to `LOG_TAGS` in `scripts/device.sh`) and `api`.
- **Verification:** for each bad file the process survives (`adb shell pidof com.aanand.edgeaicore` unchanged), logs show the inspection error, status is the fixed error text, `/v1/health` is 503; the valid model then loads and chat returns 200. If any bad file still aborts natively, record the signal and log line and stop for the owner's decision (Risks).

#### Step 6: Docs
- **Status:** todo
- **Depends on:** 5
- **Files:** `docs/pitfalls.md` (21), `docs/architecture.md` (Components: `ModelInspector`), `docs/testing-on-device.md` ("Backends and models": bad-file behaviour, and the Gemma 4 row if it still says "native abort")
- **Change:** describe validation and what still aborts, if anything.
- **Verification:** `scripts/check_docs.sh` passes.

### Tests
`ModelInspectorTest` (new; `validate`, plus `inspect` through a fake reader; only the thin `readModelInfo` LiteRT-LM adapter is left to the step 5 device check, the same as the `LlmEngine` adapters in ANDROIDEDGEAICORE-3); `AiEngineManagerTest` extended through the engine seam and Ktor test host from ANDROIDEDGEAICORE-3 (step 3). `InferenceService` and `MainActivity` changes are covered by device checks (agreed exception in `docs/standards/testing.md`, ANDROIDEDGEAICORE-3). Run `./gradlew testDebugUnitTest lintDebug` and `scripts/check_docs.sh`; device check in step 5.

### Risks and open questions
- `ModelInfo.from` or `initialize()` may still abort natively for some files. Then the only full fix is loading in a separate process; that is a larger change for the owner to approve as a new ticket.
- A truncated file may pass inspection (header intact) and fail in `initialize()`; that path must surface as "Error: Model failed to load", not a crash.

### Success criteria
- [ ] Unsupported or corrupt model gives an error status, not a crash
- [ ] Service stays up and a valid model loads afterwards
- [ ] `ModelInspectorTest` passes; lint and `check_docs.sh` pass
- [ ] Pitfall 21 updated

### Reviewer brief
- **Objective:** inspect the model with LiteRT-LM `ModelInfo` before `Engine.initialize()`, reject unsupported or corrupt files with a clear error status instead of a native crash, and keep the service usable.
- **Key decisions:** `ModelInspector` with an injectable reader (`readModelInfo` is the only LiteRT-LM touchpoint, returning `RawModelInfo`), a pure `toCapabilities` and `validate`, wrapping only `LiteRtLmJniException` in `UnsupportedModelException`; `loadModel` inspects and validates once, before the GPU-to-CPU retry (moved into `loadEngine`); fixed short error statuses; the crash guard is kept around inspection; `saved_model_path` is cleared on an unsupported model; `device.sh` gets the `ModelInspector` log tag; `ModelCapabilities` is reused by tickets 5 and 7.
- **Out of scope:** encoder/backend choice, MTP, GPU fallback, `minRuntimeVersion`, a separate probe process (an owner decision if native aborts remain).
- **Success criteria:** bad files give an error status, not a crash; the service stays up and a valid model loads afterwards; tests and checks pass; pitfall 21 updated.

The decisions above are intended; report a problem with one only if it breaks a requirement or a documented rule.

### Validation log
- **Round 1:** validator FAIL. My judgement: FAIL, with 2 valid BLOCKING issues.
  1. BLOCKING no tests for the changed classes: valid. The owner chose engine seam + Ktor tests; `AiEngineManagerTest` cases were added.
  2. BLOCKING `device.sh start` takes the model from `MODEL`, not from `model`'s argument: valid (checked `cmd_start`); `MODEL=F` is used for both.
  3. MINOR `~/Downloads/model.litertlm` not in the repo: disproved as an issue; it is the documented Gemma 3n test copy from ticket 1.
  4. to 8. MINOR (inspection before the retry, `start` exits on Error, doc section names, `LiteRtLmJniException` package, sticky restart on a bad file): valid ones fixed. The package claim was disproved: `LiteRtLmJniException.kt` is in the same package.
- **Round 2 (diff-scoped):** validator FAIL. My judgement: FAIL, with 1 valid BLOCKING issue: `inspect`'s wrapping and mapping were untested. Fixed with an injectable reader and the pure `toCapabilities`. MINOR `Log.e` in `inspect` added.
- **Round 3 (diff-scoped):** validator FAIL. My judgement: PASS.
  1. BLOCKING contradictory catch type: resolved by fetching `LiteRtLmJniException.kt` (v0.19.1: `class LiteRtLmJniException(message: String) : RuntimeException(message)`); the JVM fallback sentence was deleted and `inspect` catches only that type. A mechanical fix.
  2. MINOR `validate` call site and test: valid, fixed inline.
  3. MINOR re-inspection on the CPU retry: valid, fixed inline (retry through `loadEngine`).


## Implementation notes
### Autopilot run (2026-11-09)
- Mode: automatic (batch-autopilot, item 4/11)
- Branch: feature/validate-model-before-load  PR base: feature/real-token-usage (stacked: ticket 7 is blocked by this and by the token-usage ticket, which are unrelated, so this one is chained on it to give ticket 7 a single base)  Starting commit: 1eb04431d8b8428d01bf2cd6d6c72b21f6b0e6e6
- Dirty before start: none
- Work files: none yet
- Counters: fix 0/10, review 2/5, re-verify 1
- Last completed stage: 3 verify (all PASS)  Next: done (shipped)
- Hard blocker: none
- WIP commit: none
- Repo: /home/archit-anand/docker-data/shared/Coding/android_edge_ai_core

### Baseline (2026-11-09)
All unit tests passed on the base branch (token usage, 33 tests).

### Implementation progress
- [x] Step 1: ModelInspector.kt (ModelInspector, RawModelInfo, ModelCapabilities, ModelKind, UnsupportedModelException)
- [x] Step 2: ModelInspectorTest (5)
- [x] Step 3: AiEngineManager (inspect + validate once, `loadEngine`, `modelCapabilities`, `modelInspector` internal var); tests use `newManager(...)` with a fake inspector; 5 new AiEngineManagerTest cases
- [x] Step 4: InferenceService (UnsupportedModelException -> fixed status, clears crash flag and saved path via KTX edit; generic failure -> fixed status)
- [x] Step 5: device checks (see Verification); `device.sh` gets the ModelInspector log tag
- [x] Step 6: docs (pitfall 21, architecture, testing-on-device)
- Deviation: `modelInspector` is an internal var (not a constructor parameter) because a trailing lambda in existing tests would otherwise bind to it.

### Smoke test (2026-11-09)
`./gradlew assembleDebug testDebugUnitTest lintDebug` pass; `scripts/check_docs.sh` OK.

### Autopilot decisions (2026-11-09)
- Stage 1: Plan already `Validated` (3 rounds); reused.
- Chain stacking: the PR targets the token-usage branch (PR 38), not develop.

PR: https://github.com/archit342000/android_edge_ai_core/pull/39, branch feature/validate-model-before-load, base feature/real-token-usage

## Verification
### Verification plan (2026-11-09)
**Plan:** ticket Plan above  **Diff base:** feature/real-token-usage at 1eb0443 (the PR base); light form plus failure cases.
**Environment:** emulator `claude_edgeaicore_api34`, CPU, `scripts/device.sh`; bad files created in the evidence folder: `bad.litertlm` (1 MB random bytes), `trunc.litertlm` (first 200 MB of the Gemma 3n model); valid model Gemma 4 E2B. Script: `ANDROIDEDGEAICORE-11-evidence/scripts/badmodels.sh`. Teardown `device.sh down`.

| ID | Criterion | Scenario | Type | Method | Files | Expected | Evidence |
|----|-----------|----------|------|--------|-------|----------|----------|
| V1 | AC1 | Load `bad.litertlm` (random bytes) | failure | adb/logcat | ModelInspector.kt, AiEngineManager.kt, InferenceService.kt | process survives (same pid), log shows the unsupported-model error, no native abort, `/v1/health` 503 | logs, pid |
| V2 | AC1 | Load `trunc.litertlm` (truncated real model) | failure | adb/logcat | same | process survives; either "Unsupported model file" or "Model failed to load"; if it aborts natively, record signal and stop | logs, pid |
| V3 | AC2 | Load the valid model after each bad one | happy | adb + API | same | loads, chat 200 | chat output |
| V4 | AC1 | Sticky restart after a bad file does not retry it | edge | logcat | InferenceService.kt | `saved_model_path` cleared (no crash-loop message) | prefs |
| V5 | regression | Unit tests, lint, docs | regression | CLI | all | pass | output |


### Results (2026-11-09)
Commit verified: feature/real-token-usage at 1eb0443 + uncommitted:
- `app/src/main/java/com/aanand/edgeaicore/AiEngineManager.kt` d35ef57e5cbc226e46b47fccd6758a898bcb389a
- `app/src/main/java/com/aanand/edgeaicore/InferenceService.kt` 69d3aa7b781e95ffad885b9639c8f51f0a0c8795
- `app/src/test/java/com/aanand/edgeaicore/AiEngineManagerTest.kt` 9db346b1d5b1fc03c492a3e90312561f5f5410a2
- `app/src/test/java/com/aanand/edgeaicore/FakeLlmEngine.kt` baf2c979549eb20c89dc1a585637992a68301107
- `app/src/test/java/com/aanand/edgeaicore/OpenAIServerTest.kt` 6c63c3d27a24d95a6ce53145187c963132848205
- `docs/architecture.md` e36e32cbe270732e4144c8d2b18421906518588d
- `docs/pitfalls.md` eb94ba943392635e45f2c7d285dd74d0f017007c
- `docs/testing-on-device.md` 82bbbb40b52097bfc1659fcf78392c9bb310614b
- `scripts/device.sh` 0edb7633f0ad37d1c1bcf5e481aad5f5b378d387
- `app/src/main/java/com/aanand/edgeaicore/ModelInspector.kt` e7beb270e121c19bf92bba0e325794722a88e278
- `app/src/test/java/com/aanand/edgeaicore/ModelInspectorTest.kt` 96e8b9f851986247c5a10ec1b11b62eb2efc7827

Emulator `claude_edgeaicore_api34`, CPU. Evidence: `ANDROIDEDGEAICORE-11-evidence/badmodels-run.txt`, `scripts/badmodels.sh`.

| ID | Status | Evidence |
|----|--------|----------|
| V1 | PASS | `bad.litertlm` (1 MB random): pid unchanged (4980), `ModelInspector: Model metadata could not be read` (`LiteRtLmJniException: Failed to open LiteRT-LM file`), `/v1/health` 503, persisted prefs: `crashed_during_init` false and `saved_model_path` removed. |
| V2 | PASS | `trunc.litertlm` (200 MB of a real model): pid unchanged, same inspection error, health 503. No native abort. (The prefs snapshot for this file was read before the asynchronous `apply()` flushed, so it still showed the old values; the bad-file case shows the same code path persisted.) |
| V3 | PASS | After both bad files the valid Gemma 4 model loaded ("ready on CPU in 7 s"), chat 200, health 200. |
| V4 | PASS (partly) | `saved_model_path` removed and the crash flag cleared for `bad.litertlm`; a real sticky restart was not exercised. |
| V5 | PASS | 51 unit tests, lint (no new issues), `check_docs.sh`. |

The open question from the research ticket is answered: on 0.19.1 `ModelInfo.from` throws `LiteRtLmJniException` for both a garbage and a truncated file; no native abort happened.
Verdict: verified, 5 PASS (V4 partly), 0 FAIL, 0 BLOCKED. The UI status text was not captured (the status broadcast is not logged); the fixed status strings are covered by code reading only.

### Results (re-run 1, after review fixes, 2026-11-09)
Commit verified: feature/real-token-usage at 1eb0443 + uncommitted:
- `app/src/main/java/com/aanand/edgeaicore/AiEngineManager.kt` 8d2b13dd3a9ab31bb3f18791f3b9aca95ecbec80
- `app/src/main/java/com/aanand/edgeaicore/InferenceService.kt` 41c737c9168d31129afe480f311abce44f82bd2e
- `app/src/test/java/com/aanand/edgeaicore/AiEngineManagerTest.kt` 63968b8e21d58ac6fca2abdc8f82d4f5dcb9b9ed
- `app/src/test/java/com/aanand/edgeaicore/FakeLlmEngine.kt` baf2c979549eb20c89dc1a585637992a68301107
- `app/src/test/java/com/aanand/edgeaicore/OpenAIServerTest.kt` 6c63c3d27a24d95a6ce53145187c963132848205
- `docs/architecture.md` 39bbfdb27b8247c53f808a21acb148df11be3b8e
- `docs/pitfalls.md` eb94ba943392635e45f2c7d285dd74d0f017007c
- `docs/testing-on-device.md` 82bbbb40b52097bfc1659fcf78392c9bb310614b
- `scripts/device.sh` 0edb7633f0ad37d1c1bcf5e481aad5f5b378d387
- `app/src/main/java/com/aanand/edgeaicore/ModelInspector.kt` fec88d6a7e13e4c04bc3dd94f730336c59c7c525
- `app/src/test/java/com/aanand/edgeaicore/ModelInspectorTest.kt` 96e8b9f851986247c5a10ec1b11b62eb2efc7827

Evidence: `badmodels-run.txt` (first run kept as `badmodels-run1.txt`). The script now waits 3 s before reading the preferences.

| ID | Status | Evidence |
|----|--------|----------|
| V1 | PASS | `bad.litertlm`: pid unchanged (5055), `ModelInspector` warning plus `InferenceService: Unsupported model file`, health 503, prefs: `crashed_during_init` false and `saved_model_path` gone. |
| V2 | PASS | `trunc.litertlm`: same result (the preference race of the first run is gone). |
| V3 | PASS | Valid model afterwards: ready in 6 s, chat 200, health 200. |
| V4 | PASS | Persisted preferences show the saved path removed and the flag cleared for both bad files, so a sticky restart does not retry them (a real restart was not exercised). |
| V5 | PASS | 52 unit tests, lint, check_docs. |

Verdict: verified, 5 PASS, 0 FAIL, 0 BLOCKED.

## Review history

### Round 1 - 2026-11-09
Verdict: Request changes. Scope: 11 files (~420 changed lines incl. tests/docs), one Haiku reviewer.
- R1-1 [MAJOR] the failure-path reset of `modelCapabilities` was untested - Static - Fixed: the reset was removed by publishing the capabilities only after the engine exists (see R1-2); tests assert null after a failed load, including a failed load after a successful one
- R1-2 [MINOR] `modelCapabilities` published before the engine exists - Static - Fixed: assigned after `loadEngine` returns
- R1-3 [MINOR] `aValidModelLoadsAfterAnUnsupportedOne` swallowed the first failure - Static - Fixed: `fail()` and an assertion
- R1-4 [MINOR] `"crashed_during_init"` / `"saved_model_path"` hard-coded again - Static - Fixed: `KEY_*` constants for the new block (older copies left; follow-up, not part of this change)
- R1-5 [MINOR] architecture.md sticky-restart text lacks the cleared path - Static - Fixed
- R1-6 [NIT] import placement - Static - Fixed (the mixed `edit` styles remain: the old line is baselined lint)
- R1-7 [NIT] fully qualified `assertNull` - Static - Fixed
Pre-existing: the repeated preference-key literals in `InferenceService` (older copies).
Resolution: 7 fixed. 52 unit tests, lint, check_docs pass. Runtime change: where `modelCapabilities` is assigned.

### Round 2 - 2026-11-09
Verdict: Approve with comments (MINOR/NIT only). Scope: same files, one Haiku reviewer.
- R2-1 [MINOR] `modelCapabilities` set after the engine, so a concurrent reader can see the engine without capabilities - Static - Fixed: assigned together with the engine inside `loadEngine`
- R2-2 [MINOR] literals for the two preference keys remain next to the new constants - Static - Fixed: all uses in `InferenceService` now use the constants
- R2-3 [NIT] `loadEngine` placed between public functions - Static - Fixed: moved below them
- R2-4 [NIT] same throwable logged twice - Static - Fixed: the inspector logs a one-line warning, the service logs the stack
Dismissed:
- Reviewer #1 [MINOR] `close()` runs before inspection so a rejected file evicts a working engine: rejected; replacing the loaded model is what `loadModel` for a new path does, and the plan and pitfall 21 define the state after a rejection as "up without a model" (health 503).
- Reviewer #4 [MINOR] no device check of a sticky restart after a rejection: rejected; the persisted prefs (flag false, saved path removed) are verified in the re-run, which is what prevents the retry.
- Reviewer #7 [NIT] several types in `ModelInspector.kt`: rejected; small related types (enum, data classes, exception) grouped by the file's subject.
Pre-existing: none.
Resolution: 4 fixed, 3 rejected. Unit tests, lint, check_docs pass; device re-run done (below).

## Log
- 2026-11-09: created from ANDROIDEDGEAICORE-2 gap analysis
- 2026-11-09: plan drafted
- 2026-11-09: linked blocks ANDROIDEDGEAICORE-5 (ModelInspector/ModelCapabilities created in 11 are reused)
- 2026-11-09: linked blocks ANDROIDEDGEAICORE-7 (ModelInspector/ModelCapabilities created in 11 are reused)
- 2026-11-09: plan revised after validation round 1 (and test-seam decision)
- 2026-11-09: plan revised after validation round 2
- 2026-11-09: plan validated, 3 rounds
- 2026-11-09: autopilot started (batch)
- 2026-11-09: implemented, smoke tests pass
- 2026-11-09: verify: 5 PASS, 0 FAIL
- 2026-11-09: batch-autopilot: shipped, base feature/real-token-usage (stacked)
- 2026-11-09: closed (all acceptance criteria checked; the PR is open, not yet merged)
- 2026-11-09: linked relates_to ANDROIDEDGEAICORE-18, ANDROIDEDGEAICORE-19, ANDROIDEDGEAICORE-20 (follow-ups)
