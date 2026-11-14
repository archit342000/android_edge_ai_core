---
id: ANDROIDEDGEAICORE-6
title: "Set the engine cache directory"
status: done
created: 2026-11-09
updated: 2026-11-09
links:
  blocked_by: [ANDROIDEDGEAICORE-3]
  conflicts_with: [ANDROIDEDGEAICORE-5, ANDROIDEDGEAICORE-7, ANDROIDEDGEAICORE-12]
  relates_to: [ANDROIDEDGEAICORE-20]
  split_from: [ANDROIDEDGEAICORE-2]
---

## Description
Set `EngineConfig.cacheDir` to the app's cache dir so compiled artefacts are reused and the second load is faster (gap #4).

Background: research and gap analysis in ANDROIDEDGEAICORE-2 (LiteRT-LM v0.19.1). Verify with `scripts/device.sh` (`docs/testing-on-device.md`).

## Acceptance criteria
- [x] `cacheDir` set to `context.cacheDir`
- [x] Second model load is measurably faster than the first on the emulator (times recorded)

## Plan
**Date:** 2026-11-09  **Status:** Validated

### Objective
Point LiteRT-LM's compiled-artefact cache at the app's cache directory (`EngineConfig.cacheDir`) so repeated loads reuse it and the cache lives where Android can reclaim it, and record first vs second load times. Runs after ANDROIDEDGEAICORE-3.

### Context
- `EngineConfig.cacheDir` (0.19.1 `Config.kt`): "If not set, it uses the directory of the modelPath"; `":nocache"` disables caching. Today `AiEngineManager.loadModel` sets no `cacheDir`, so cache files (if any) land next to the model in `filesDir`.
- `AiEngineManager` has no `Context`; `InferenceService` creates it at field init (`private val aiEngineManager = AiEngineManager()`), before a context is attached, and calls `loadModel(modelPath, finalBackend)`.
- Rules: `docs/standards/security.md` (app data only in private storage; `cacheDir` is private), `docs/standards/coding.md` (constants in `companion object`), `docs/standards/errors-and-logging.md` (`Log.i` for model loaded).

### Approach
Add a `cacheDir: String?` parameter to `loadModel`, passed by `InferenceService` as `cacheDir.absolutePath` (the app's `context.cacheDir`, as the acceptance criterion states). Log the `initialize()` duration at `Log.i` so load times can be compared. Rejected: giving `AiEngineManager` a `Context` (wider change than needed).

### Out of scope
Deleting cache files the old default may have left in `filesDir` (noted under Risks), cache size limits, other `EngineConfig` fields.

### Steps
#### Step 1: Wire cacheDir
- **Status:** todo
- **Depends on:** none
- **Files:** `app/src/main/java/com/aanand/edgeaicore/AiEngineManager.kt` (modify: `loadModel` signature and `EngineConfig`, including the recursive CPU retry call, which must pass `cacheDir` on), `app/src/main/java/com/aanand/edgeaicore/InferenceService.kt` (modify: `loadModel` call), `app/src/test/java/com/aanand/edgeaicore/AiEngineManagerTest.kt` (modify)
- **Change:** as in Approach; keep `cacheDir` nullable with default `null` so the readiness flow and other callers compile unchanged. Test through the engine seam (ANDROIDEDGEAICORE-3): the fake factory receives the given `cacheDir`, also on the GPU-to-CPU retry.
- **Verification:** `./gradlew assembleDebug testDebugUnitTest lintDebug`.

#### Step 2: Load-time logging
- **Status:** todo
- **Depends on:** 1
- **Files:** `app/src/main/java/com/aanand/edgeaicore/AiEngineManager.kt` (modify: `loadModel`)
- **Change:** measure `initialize()` with `SystemClock.elapsedRealtime()` and log `Log.i(TAG, "Engine initialized in <ms> ms (backend=..)")`.
- **Verification:** log line visible in `scripts/device.sh logs`.

#### Step 3: Measure on the emulator
- **Status:** todo
- **Depends on:** 2
- **Files:** none (create `~/.claude/tickets/android_edge_ai_core/ANDROIDEDGEAICORE-6-evidence/` and write the times and listings to `load-times.md` there)
- **Change:** `scripts/device.sh down`, then `MODEL=~/Downloads/model.litertlm scripts/device.sh up` (the read-only AVD boots clean, so the app's cache is empty); note the init time; `stop-service`, `MODEL=~/Downloads/model.litertlm scripts/device.sh start CPU` again; note it; list `adb shell run-as com.aanand.edgeaicore ls -la cache files`. Repeat the whole sequence (`down`, `up`, note, `stop-service`, `start CPU`, note, list) with `MODEL=~/.cache/edgeaicore-test-models/gemma-4-E2B-it.litertlm`.
- **Verification:** new cache files appear under `cache/` and not in `files/`; the second init time is lower than the first (both numbers recorded). If CPU shows no cache files or no gain, record that and run the same check on the phone's GPU with the owner, since GPU kernels are the main cached artefact.

#### Step 4: Docs
- **Status:** todo
- **Depends on:** 3
- **Files:** `docs/architecture.md` (Storage table: engine cache row)
- **Change:** add the cache location.
- **Verification:** `scripts/check_docs.sh` passes.

### Tests
`AiEngineManagerTest` extended (step 1) through the engine seam and Ktor test host from ANDROIDEDGEAICORE-3; `InferenceService` and `MainActivity` changes are covered by device checks (agreed exception in `docs/standards/testing.md`, ANDROIDEDGEAICORE-3). The load-time gain itself is shown by the device measurement. Run `./gradlew testDebugUnitTest lintDebug`, `scripts/check_docs.sh`.

### Risks and open questions
- On CPU there may be little or nothing to cache (XNNPack weight cache); the benefit is expected mainly on GPU.
- Earlier builds may have left cache files beside the model in `filesDir`; they stay until the model is deleted. Cleaning them is a separate change if they turn out to exist.
- `loadModel` also changes in ANDROIDEDGEAICORE-5, -7 and -12 (`conflicts_with`); land in ticket order and rebase the later ones.
- Android may clear `cacheDir` under storage pressure; the next load just rebuilds the cache.

### Success criteria
- [ ] `cacheDir` set to `context.cacheDir`
- [ ] Second load measurably faster than the first on the emulator (or on the phone GPU if CPU shows no cache), times recorded
- [ ] Checks pass; architecture Storage table updated

### Reviewer brief
- **Objective:** set `EngineConfig.cacheDir` to `context.cacheDir` so compiled artefacts are reused, and record first vs second load times.
- **Key decisions:** `loadModel` gains a nullable `cacheDir` (passed on in the CPU retry) supplied by `InferenceService`; `initialize()` duration logged at `Log.i`; clean-boot measurement on the read-only emulator, on the phone GPU if CPU shows no gain.
- **Out of scope:** cleaning old cache files in `filesDir`, cache size limits, other `EngineConfig` fields.
- **Success criteria:** `cacheDir` set; second load measurably faster (times recorded); `AiEngineManagerTest` case and checks pass; architecture Storage table updated.

The decisions above are intended; report a problem with one only if it breaks a requirement or a documented rule.

### Validation log
- **Round 1:** validator FAIL. My judgement: FAIL, with 1 valid BLOCKING issue.
  1. BLOCKING no test for the changed `AiEngineManager`: valid. The owner chose engine seam + Ktor tests; an `AiEngineManagerTest` case (cacheDir reaches the factory, also on the retry) was added.
  2. MINOR the AC says `context.cacheDir`: valid, switched from a subdirectory to `context.cacheDir`.
  3. MINOR `MODEL` not set, and "fresh" not guaranteed: valid, `down` then `MODEL=... up`.
  4. MINOR conflicts not mentioned: valid, added to Risks.
- **Round 2 (diff-scoped):** validator PASS with 2 MINOR issues (evidence file name, full repeat sequence). Both valid, fixed inline.


## Implementation notes
### Autopilot run (2026-11-09)
- Mode: automatic (batch-autopilot, item 2/11)
- Branch: feature/engine-cache-dir  PR base: develop  Starting commit: 47ace0582824aecf89c09db3b0138e0dd5ed54ec
- Dirty before start: none
- Work files: none yet
- Counters: fix 0/10, review 1/5, re-verify 0
- Last completed stage: 5 review (1 round, resolved)  Next: done (shipped)
- Hard blocker: none
- WIP commit: none
- Repo: /home/archit-anand/docker-data/shared/Coding/android_edge_ai_core

### Baseline (2026-11-09)
All unit tests passed on develop at 47ace05 (ran for the previous item).

### Implementation progress
- [x] Step 1: Wire cacheDir - AiEngineManager.kt (loadModel param, passed on in the CPU retry), InferenceService.kt, AiEngineManagerTest.kt (2 tests)
- [x] Step 2: Load-time logging - `Engine initialized in N ms` (System.nanoTime, no Android dependency)
- [x] Step 3: Measured on the emulator (see Verification)
- [x] Step 4: Docs - architecture.md Storage table

### Smoke test (2026-11-09)
`./gradlew assembleDebug testDebugUnitTest lintDebug` pass; `scripts/check_docs.sh` OK.

### Autopilot decisions (2026-11-09)
- Stage 1: Plan already `Validated` (2 rounds); reused.

PR: https://github.com/archit342000/android_edge_ai_core/pull/37, branch feature/engine-cache-dir, base develop

## Verification
### Verification plan (2026-11-09)
**Plan:** ticket Plan above  **Diff base:** 47ace0582824aecf89c09db3b0138e0dd5ed54ec (light form: ~15 changed source lines)
**Environment:** emulator `claude_edgeaicore_api34` (read-only boot, so the app cache starts empty), CPU, via `scripts/device.sh`; models `~/Downloads/model.litertlm` (Gemma 3n E2B int4) and `~/.cache/edgeaicore-test-models/gemma-4-E2B-it.litertlm`. Script: `ANDROIDEDGEAICORE-6-evidence/scripts/measure.sh`. Teardown `device.sh down`.

| ID | Criterion | Scenario | Type | Method | Files | Expected | Evidence |
|----|-----------|----------|------|--------|-------|----------|----------|
| V1 | AC1 | After the first load, list `cache/` and `files/` of the app | happy | adb run-as | InferenceService.kt, AiEngineManager.kt | cache artefacts (if the CPU backend writes any) appear under `cache/`, none next to the model | listings |
| V2 | AC2 | Load, stop the service, load again; compare the logged `Engine initialized in N ms` for each model | happy | logcat | AiEngineManager.kt | second load faster (times recorded); if no gain on CPU, record it | log lines |
| V3 | regression | Chat (non-stream and stream) after the second load | regression | API | AiEngineManager.kt | 200 and `[DONE]` | responses |
| V4 | regression | `testDebugUnitTest lintDebug`, `check_docs.sh` | regression | CLI | all | pass | output |


### Results (2026-11-09)
Commit verified: 47ace0582824aecf89c09db3b0138e0dd5ed54ec + uncommitted:
- `app/src/main/java/com/aanand/edgeaicore/AiEngineManager.kt` 062dd82013921e758f1d00b4f715182bf9096413
- `app/src/main/java/com/aanand/edgeaicore/InferenceService.kt` 9579132a1c4a554b3b543815aa1b017930972e72
- `app/src/test/java/com/aanand/edgeaicore/AiEngineManagerTest.kt` 8dd4e0394b2c168db72bbc163c7368dc30de0c3b
- `docs/architecture.md` 54fb8f1f0877b9343e61f3eaf07a6e277abeeef9

Emulator `claude_edgeaicore_api34`, CPU, clean boot per model. Evidence: `ANDROIDEDGEAICORE-6-evidence/` (`gemma4.txt`, `gemma3n.txt`, `scripts/measure.sh`).

| Model | First `Engine initialized` | Second | Cache files |
|-------|---------------------------|--------|-------------|
| Gemma 4 E2B (2.6 GB) | 4039 ms | 1904 ms | 6 files, about 1.0 GB, all under `cache/` |
| Gemma 3n E2B int4 (3.7 GB) | 2472 ms | 865 ms | 6 files, about 2.3 GB, all under `cache/` |

| ID | Status | Evidence |
|----|--------|----------|
| V1 | PASS | After the first load the XNNPack weight caches and vision-encoder caches are in `cache/`; `files/` holds only the model, tokens and conversations. |
| V2 | PASS | Second load faster: 4039 to 1904 ms and 2472 to 865 ms. |
| V3 | PASS | Non-stream chat 200 and stream ending with `data: [DONE]` after the second load, both models. |
| V4 | PASS | `testDebugUnitTest lintDebug`, `check_docs.sh` pass (28 + 2 new tests). |

Verdict: verified, 4 PASS, 0 FAIL, 0 BLOCKED. Note: the cache is large (up to 2.3 GB for a 3.7 GB model); Android may clear `cacheDir` under storage pressure. The first attempt of the Gemma 3n run failed (emulator still shutting down after `down`); the script now waits, the run was repeated.

## Review history

### Round 1 - 2026-11-09
Verdict: Approve with comments (the one MAJOR was a visibility gap, rejected below). Scope: 4 files (~45 changed lines), one Haiku reviewer.
- R1-1 [MAJOR] load times not recorded in the change - Static - Rejected: the plan records them in the ticket evidence and the PR Testing section (Gemma 4 4039 to 1904 ms, Gemma 3n 2472 to 865 ms); the reviewer had no access to them. No repo doc change is called for.
- R1-2 [MINOR] `cacheDir` defaults to null - Static - Rejected: intended by the plan so other callers compile unchanged; the only caller passes it.
- R1-3 [MINOR] timing covers only the successful attempt - Static - Fixed: start time taken before the try; the failure log includes the elapsed time
- R1-4 [MINOR] architecture.md row asserts unverified cache behaviour - Static - Fixed: reworded to what is observed
- R1-5 [NIT] `assertNull` - Static - Fixed
Pre-existing: none.
Resolution: 3 fixed, 2 rejected. Tests, lint, check_docs pass. Runtime change: a log message and where the timer starts only (no behaviour change).

## Log
- 2026-11-09: created from ANDROIDEDGEAICORE-2 gap analysis
- 2026-11-09: plan drafted
- 2026-11-09: plan revised after validation round 1 (and test-seam decision)
- 2026-11-09: plan validated, 2 rounds
- 2026-11-09: autopilot started (batch)
- 2026-11-09: implemented, smoke tests pass
- 2026-11-09: verify: 4 PASS, 0 FAIL
- 2026-11-09: batch-autopilot: shipped, base develop
- 2026-11-09: closed (all acceptance criteria checked; the PR is open, not yet merged)
- 2026-11-09: linked relates_to ANDROIDEDGEAICORE-20 (follow-ups)
