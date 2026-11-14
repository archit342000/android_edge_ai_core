---
id: ANDROIDEDGEAICORE-12
title: "Fall back from GPU to CPU when GPU is unusable"
status: done
created: 2026-11-09
updated: 2026-11-09
links:
  blocked_by: [ANDROIDEDGEAICORE-3]
  conflicts_with: [ANDROIDEDGEAICORE-5, ANDROIDEDGEAICORE-6, ANDROIDEDGEAICORE-11]
  relates_to: [ANDROIDEDGEAICORE-16, ANDROIDEDGEAICORE-18, ANDROIDEDGEAICORE-19, ANDROIDEDGEAICORE-20]
  split_from: [ANDROIDEDGEAICORE-2]
---

## Description
With `BACKEND=GPU` and no OpenCL, load logs success and the readiness ping fails, so chat returns 500 (gap #10, pitfall 22). Detect GPU init or first-inference failure and retry on CPU, reporting the backend actually in use.

Background: research and gap analysis in ANDROIDEDGEAICORE-2 (LiteRT-LM v0.19.1). Verify with `scripts/device.sh` (`docs/testing-on-device.md`).

## Acceptance criteria
- [x] On the emulator, GPU selection falls back to CPU and chat works
- [x] Status, logs and `/v1/health` show the backend in use
- [x] Pitfall 22 updated

## Plan
**Date:** 2026-11-09  **Status:** Validated

### Objective
When GPU is selected but unusable (no OpenCL, or a GPU engine that initialises and then fails its first inference), fall back to CPU, keep chat working, and report the backend actually in use in the status, logs and `/v1/health`. Runs after ANDROIDEDGEAICORE-3.

### Context
- Current code: `AiEngineManager.loadModel` already retries on CPU when `Engine.initialize()` throws on GPU (`Log.w "Retrying with CPU backend..."`), but nothing records it. On the emulator with 0.9.0-alpha02 GPU initialisation succeeds and the readiness ping in `InferenceService.onStartCommand` then fails 10 times with `Can not find OpenCL library`, ending in "Error: Model verification failed" (pitfall 22). The success status is `"Model loaded successfully ($finalBackend)"`, i.e. the requested backend. `/v1/health` returns `{"status": "ok"}`.
- Rules: `AGENTS.md` rule 7 and `docs/standards/coding.md` 3 (no silent fallbacks: the fallback must be visible), `docs/standards/api.md` (additive field = minor; README together), `docs/standards/android.md` (keep the crash-loop guard), `docs/standards/errors-and-logging.md`.
- 0.19.1 may now throw from `initialize()` instead of failing later; the plan handles both.

### Approach
- `AiEngineManager` gains `activeBackend: String?` (set on successful load, cleared in `close()`), and a `fellBackFromGpu` flag set by the existing init-time retry.
- Readiness: move the ping into a single `verifyReady()` call in `AiEngineManager` that the service calls; if it fails with GPU active, the service closes the engine, reloads with CPU (same crash-guard handling), runs the ping again, and sets `fellBackFromGpu`. Fewer ping attempts on GPU (`GPU_PING_ATTEMPTS` = 2) so the fallback does not wait 20 s.
- Reporting: status `"Model loaded successfully (CPU, GPU unavailable)"` when it fell back, else `"(<backend>)"`; `Log.w` with the GPU failure; `/v1/health` and root `/health` return `{"status": "ok", "backend": "<CPU|GPU|NPU>"}` (additive). `inference_service_prefs` keeps the user's requested backend, so a restart tries GPU again.
- Pure `BackendFallbackPolicy.next(requested, failedStage)` decides whether to retry on CPU (only from GPU; NPU and CPU failures are reported, not retried, as today).
Rejected: probing for `libOpenCL.so` before load (does not catch drivers that load but fail at run time).

### Out of scope
NPU fallback, encoder backend fallback (ANDROIDEDGEAICORE-5), changing the default backend in the UI, the duplicated health handler itself (pitfall 4: both are changed together).

### Steps
#### Step 1: Fallback policy
- **Status:** todo
- **Depends on:** none
- **Files:** `app/src/main/java/com/aanand/edgeaicore/BackendFallbackPolicy.kt` (create), `app/src/test/java/com/aanand/edgeaicore/BackendFallbackPolicyTest.kt` (create)
- **Change:** GPU init or first-inference failure gives "retry CPU"; CPU or NPU failure gives "fail".
- **Verification:** `./gradlew testDebugUnitTest` passes.

#### Step 2: Engine state and readiness
- **Status:** todo
- **Depends on:** 1
- **Files:** `app/src/main/java/com/aanand/edgeaicore/AiEngineManager.kt` (modify: `loadModel` retry uses the policy and records `fellBackFromGpu`; new `activeBackend`, `verifyReady(attempts)`; `close`)
- **Change:** `verifyReady` runs the "Hello" ping through `generateConversationResponseAsync` with a dedicated state and clears it after, returning success or the last error (moved from `InferenceService`). `InferenceService` keeps logging the ping results under its own tag, as today.
- **Verification:** `./gradlew assembleDebug testDebugUnitTest`; `AiEngineManagerTest` (engine seam, ANDROIDEDGEAICORE-3) cases: GPU init failure loads CPU and sets `fellBackFromGpu` and `activeBackend = CPU`; `verifyReady` returns success for a replying fake and the error for a failing one; `close()` clears `activeBackend`.

#### Step 3: Service flow
- **Status:** todo
- **Depends on:** 2
- **Files:** `app/src/main/java/com/aanand/edgeaicore/InferenceService.kt` (modify: load block in `onStartCommand`; `GPU_PING_ATTEMPTS` constant)
- **Change:** load, `verifyReady`, on GPU failure reload on CPU and verify again, then broadcast the status described above and log it with `Log.i(TAG, "Model loaded successfully (...)")`; a GPU ping failure that leads to a fallback is logged as `Log.w(TAG, "GPU readiness failed, falling back to CPU")`, not as "Ping failed" or "Error". The crash flag is set before each native load and cleared after each handled outcome.
- **Verification:** `./gradlew assembleDebug testDebugUnitTest lintDebug`.

#### Step 4: Health response
- **Status:** todo
- **Depends on:** 2
- **Files:** `app/src/main/java/com/aanand/edgeaicore/OpenAIServer.kt` (modify: `/v1/health` and root `/health`)
- **Change:** replace the inline `mapOf("status" to "ok")` with a `HealthResponse(status, backend)` DTO in `OpenAIModels.kt`, `backend` from `activeBackend`; the 503 path keeps `ErrorResponse("Model not loaded")` unchanged.
- **Verification:** `./gradlew assembleDebug testDebugUnitTest`; `OpenAIModelsTest` serialises `HealthResponse`; `OpenAIServerTest` checks both health routes return `backend` after a fake load and 503 before.

#### Step 5: device.sh readiness
- **Status:** todo
- **Depends on:** 3
- **Files:** `scripts/device.sh` (modify: `cmd_start`)
- **Change:** wait for `Model loaded successfully` from `InferenceService` as the ready signal (printing the status, which names the backend), and fail on `Error` status lines or "crash loop"; `Ping failed` lines are no longer fatal on their own.
- **Verification:** `bash -n scripts/device.sh`; used by step 6.

#### Step 6: Device checks
- **Status:** todo
- **Depends on:** 4, 5
- **Files:** none (evidence in `~/.claude/tickets/android_edge_ai_core/ANDROIDEDGEAICORE-12-evidence/`)
- **Change:** emulator: `scripts/device.sh up` then `stop-service`, `start GPU` (Gemma 3n); chat; `api GET /v1/health`; the UI status via `ui`. Repeat with `start CPU` to confirm no change there. If the owner has the phone connected, `start GPU` there to show `backend: GPU` without fallback.
- **Verification:** emulator GPU start ends "Model loaded successfully (CPU, GPU unavailable)", chat 200, health `{"status":"ok","backend":"CPU"}`, logcat has the GPU failure at `W`; CPU start unchanged; phone (if available) reports GPU.

#### Step 7: Docs
- **Status:** todo
- **Depends on:** 6
- **Files:** `docs/pitfalls.md` (22; 17 moves to `AiEngineManager.verifyReady`), `docs/standards/api.md` (health body), `README.md` (Health Check section), `docs/testing-on-device.md` (Backends table: GPU on emulator falls back; "What each step does" `start` row: ready signal), `docs/architecture.md` (Components: `InferenceService` no longer runs the ping itself, `AiEngineManager` gains readiness and the active backend)
- **Change:** describe the fallback and the field.
- **Verification:** `scripts/check_docs.sh` passes.

### Tests
`BackendFallbackPolicyTest` (new); `AiEngineManagerTest`, `OpenAIServerTest`, `OpenAIModelsTest` extended through the engine seam and Ktor test host from ANDROIDEDGEAICORE-3. `InferenceService` and `MainActivity` changes are covered by device checks (agreed exception in `docs/standards/testing.md`, ANDROIDEDGEAICORE-3). Run `./gradlew testDebugUnitTest lintDebug`, `scripts/check_docs.sh`; device check in step 5.

### Risks and open questions
- A GPU failure after the ping (mid-session) is not handled; it still surfaces as a 500. Out of scope unless seen in practice.
- `MainActivity.updateUIForStatus` matches on "loaded"; the new status still contains it.
- Conflicts with ANDROIDEDGEAICORE-5, -6 and -11 in `loadModel`; land in ticket order and rebase.

### Success criteria
- [ ] GPU selection on the emulator falls back to CPU and chat works
- [ ] Status, logs and `/v1/health` show the backend in use
- [ ] Tests, lint, docs checks pass; pitfall 22 updated

### Reviewer brief
- **Objective:** when GPU is selected but unusable, fall back to CPU and report the backend actually in use (status, logs, `/v1/health`).
- **Key decisions:** pure `BackendFallbackPolicy` (only GPU falls back); readiness ping moved into `AiEngineManager.verifyReady`, with `GPU_PING_ATTEMPTS` = 2; the fallback is visible (status "CPU, GPU unavailable", `Log.w`); `HealthResponse(status, backend)` is an additive field; `device.sh start` waits for "Model loaded successfully"; the requested backend stays saved for restarts.
- **Out of scope:** NPU fallback, encoder fallback, mid-session GPU failures, default backend changes.
- **Success criteria:** emulator GPU start falls back and chat works; health/status/logs show the backend; tests (`BackendFallbackPolicyTest`, `AiEngineManagerTest`, `OpenAIServerTest`, `OpenAIModelsTest`) and checks pass; pitfall 22 updated.

The decisions above are intended; report a problem with one only if it breaks a requirement or a documented rule.

### Validation log
- **Round 1:** validator FAIL. My judgement: FAIL, with 3 valid BLOCKING issues.
  1. BLOCKING missing tests for the changed classes: valid. The owner chose engine seam + Ktor tests (ticket 3); `AiEngineManagerTest`, `OpenAIServerTest` and `OpenAIModelsTest` cases were added, and InferenceService is an agreed exception.
  2. BLOCKING architecture.md not updated for the ping move: valid, added to the docs step.
  3. BLOCKING `device.sh start` dies on "Ping failed" and filters only the InferenceService tag: valid (checked `cmd_start`). New step 5 changes the ready signal, and the service logs the fallback without "Ping failed".
  4. MINOR "fixing pitfall 17" claim: valid, wording dropped; pitfall 17 is updated instead.
  5. MINOR 0.19.1 "may throw" claim: accepted as a hypothesis, since the plan handles both cases.
  6. MINOR health DTO test and 503 body: valid, now a `HealthResponse` DTO with a test; the 503 body is unchanged.
- **Round 2 (diff-scoped):** validator PASS, no issues. My judgement: PASS; step order and dependencies spot-checked.


## Implementation notes
### Autopilot run (2026-11-09)
- Mode: automatic (batch-autopilot, item 5/11)
- Branch: feature/gpu-cpu-fallback  PR base: develop  Starting commit: 47ace0582824aecf89c09db3b0138e0dd5ed54ec
- Dirty before start: none
- Work files: none yet
- Counters: fix 0/10, review 2/5, re-verify 1
- Last completed stage: 3 verify (all PASS)  Next: done (shipped)
- Hard blocker: none
- WIP commit: none
- Repo: /home/archit-anand/docker-data/shared/Coding/android_edge_ai_core

### Baseline (2026-11-09)
All unit tests passed on develop at 47ace05.

### Implementation progress
- [x] Step 1: BackendFallbackPolicy + test
- [x] Step 2: AiEngineManager (`activeBackend`, `fellBackFromGpu`, `verifyReady`, `fallBackToCpu`); tests
- [x] Step 3: InferenceService flow (GPU_PING_ATTEMPTS 2, fallback reload, status/log); the crash-guard write moved into `markInitStarted` (KTX `edit(commit = true)`) so lint stays clean
- [x] Step 4: `HealthResponse(status, backend)` on both health routes; tests
- [x] Step 5: `device.sh start` waits for "Model loaded successfully"
- [x] Step 6: device checks (see Verification)
- [x] Step 7: docs (pitfalls 17 and 22, api.md, README, testing-on-device, architecture)

### Smoke test (2026-11-09)
`./gradlew assembleDebug testDebugUnitTest lintDebug` pass; `scripts/check_docs.sh` OK.

### Autopilot decisions (2026-11-09)
- Stage 1: Plan already `Validated` (2 rounds); reused.

PR: https://github.com/archit342000/android_edge_ai_core/pull/40, branch feature/gpu-cpu-fallback, base develop

## Verification
### Verification plan (2026-11-09)
**Plan:** ticket Plan above  **Diff base:** 47ace0582824aecf89c09db3b0138e0dd5ed54ec (light form plus edge cases)
**Environment:** emulator `claude_edgeaicore_api34` (no OpenCL, so GPU is unusable), `scripts/device.sh`, Gemma 4 E2B; script `ANDROIDEDGEAICORE-12-evidence/scripts/gpu.sh`; teardown `device.sh down`. A phone was not available.

| ID | Criterion | Scenario | Type | Method | Files | Expected | Evidence |
|----|-----------|----------|------|--------|-------|----------|----------|
| V1 | AC1 | `start GPU` on the emulator, then chat (non-stream and stream) | happy | adb + API | AiEngineManager.kt, InferenceService.kt | falls back to CPU, chat 200 and `[DONE]` | logcat, responses |
| V2 | AC2 | Status/logs/health after V1 | happy | logcat + API | InferenceService.kt, OpenAIServer.kt | `Model loaded successfully (CPU, GPU unavailable)` logged; `GPU readiness failed, falling back to CPU` at W; `/v1/health` and `/health` say `"backend":"CPU"` | logcat, health body |
| V3 | regression | `start CPU` afterwards | regression | adb + API | same | `Model loaded successfully (CPU)`, health `CPU`, no fallback line | logcat |
| V4 | regression | health before a model is loaded | edge | API | OpenAIServer.kt | 503 (covered by unit test; the service is up before the first start) | response |
| V5 | regression | Unit tests, lint, docs | regression | CLI | all | pass | output |


### Results (2026-11-09)
Commit verified: 47ace0582824aecf89c09db3b0138e0dd5ed54ec + uncommitted:
- `README.md` 043251a834522c4873c42d7a9e94e13a2f17a28d
- `app/src/main/java/com/aanand/edgeaicore/AiEngineManager.kt` a3c7816253a48c7599bde9f6c5a8bf0641a7a64c
- `app/src/main/java/com/aanand/edgeaicore/InferenceService.kt` ec8173dee3887b123553b9616044a8db24578084
- `app/src/main/java/com/aanand/edgeaicore/OpenAIModels.kt` 5fb46b5fadeab141f89c9f235b9689b041167221
- `app/src/main/java/com/aanand/edgeaicore/OpenAIServer.kt` 72f970a182bbfffee1a7c078077c8ee6380d1452
- `app/src/test/java/com/aanand/edgeaicore/AiEngineManagerTest.kt` 678e74c28f1b34de73c5427a22db2b1cb0742c89
- `app/src/test/java/com/aanand/edgeaicore/OpenAIModelsTest.kt` 8a7c7865635e0fb9e5688f2b3190c903c2b80697
- `app/src/test/java/com/aanand/edgeaicore/OpenAIServerTest.kt` 639b4c84d11d402ada833ac75c1471921ec4ea29
- `docs/architecture.md` 99228539ce78d197fa07fa22842b458123d631a8
- `docs/pitfalls.md` bc9c84875d0626ea884ff8d99a4a0cf6d24ecf79
- `docs/standards/api.md` 7e3d888e9b0da881d04271780e51a5be3b360776
- `docs/testing-on-device.md` 8c3cffb3b64dc5afd2a07e6506091d047564e226
- `scripts/device.sh` a9c19d47ac9e450de4f9a6fe8c062b9e08ca6bf9
- `app/src/main/java/com/aanand/edgeaicore/BackendFallbackPolicy.kt` 1a5af1fd045e50df0009041dea2741aad11074b6
- `app/src/test/java/com/aanand/edgeaicore/BackendFallbackPolicyTest.kt` e285ca9d0dbfc349df68d769b4cd7579bdad4ede

Emulator `claude_edgeaicore_api34`, Gemma 4 E2B. Evidence: `ANDROIDEDGEAICORE-12-evidence/gpu-run.txt`, `scripts/gpu.sh`.

| ID | Status | Evidence |
|----|--------|----------|
| V1 | PASS | `start GPU`: the engine initialised on GPU, then `Ping failed (1)` and `(2)`: `Can not find OpenCL library`; the service reloaded on CPU. Chat after: non-stream 200, stream ends `[DONE]`. |
| V2 | PASS | Status and log: `Model loaded successfully (CPU, GPU unavailable)` (I); `GPU readiness failed, falling back to CPU: ...` at W; `/v1/health` and `/health` both `{"backend":"CPU","status":"ok"}`. |
| V3 | PASS | `start CPU` afterwards: `Model loaded successfully (CPU)`, health `CPU`, no fallback line. |
| V4 | PASS (unit test) | 503 before a model is loaded is covered by `OpenAIServerTest`; the device baseline health after `up` was `{"backend":"CPU","status":"ok"}`. |
| V5 | PASS | 62 unit tests, lint (no new issues), check_docs. |

Observation: the fallback took 116 s end to end on the emulator (`start GPU` to ready): about 17 s GPU init, 85 s between "loading on CPU" and the CPU engine's init (closing the failed GPU engine), then the CPU load. Not measured on a phone; no phone was available.
Verdict: verified on the emulator, 5 PASS, 0 FAIL, 0 BLOCKED. Phone GPU (`backend: GPU` without fallback) not checked.

### Results (re-run 1, after review fixes, 2026-11-09)
Commit verified: 47ace0582824aecf89c09db3b0138e0dd5ed54ec + uncommitted:
- `README.md` 043251a834522c4873c42d7a9e94e13a2f17a28d
- `app/src/main/java/com/aanand/edgeaicore/AiEngineManager.kt` 3513918531d9a4be1e0ac0e203ffb32ab239fadb
- `app/src/main/java/com/aanand/edgeaicore/InferenceService.kt` dc260b4e181dfaca2d380aa448d4dd8c0264669f
- `app/src/main/java/com/aanand/edgeaicore/OpenAIModels.kt` 5fb46b5fadeab141f89c9f235b9689b041167221
- `app/src/main/java/com/aanand/edgeaicore/OpenAIServer.kt` 72f970a182bbfffee1a7c078077c8ee6380d1452
- `app/src/test/java/com/aanand/edgeaicore/AiEngineManagerTest.kt` 49e9751f102d4fc8a0a32f08b54823a4a492a9c5
- `app/src/test/java/com/aanand/edgeaicore/OpenAIModelsTest.kt` 8a7c7865635e0fb9e5688f2b3190c903c2b80697
- `app/src/test/java/com/aanand/edgeaicore/OpenAIServerTest.kt` bba66e40016e5deb1f823526e36c8f4df2608a19
- `docs/architecture.md` 99228539ce78d197fa07fa22842b458123d631a8
- `docs/pitfalls.md` 23684347f6b60b040a29b3aa05e7daa9914e0c9b
- `docs/standards/api.md` 7e3d888e9b0da881d04271780e51a5be3b360776
- `docs/testing-on-device.md` a14cb44f0d130a27fc20f20bd36b02e98c134ccc
- `scripts/device.sh` 1e60e755aed3b7573f5982719dd5e1489d27042d
- `app/src/main/java/com/aanand/edgeaicore/BackendFallbackPolicy.kt` 1a5af1fd045e50df0009041dea2741aad11074b6
- `app/src/test/java/com/aanand/edgeaicore/BackendFallbackPolicyTest.kt` e285ca9d0dbfc349df68d769b4cd7579bdad4ede

Evidence: `gpu-run.txt` (first run kept as `gpu-run1.txt`). V1 PASS (GPU init, 2 failed pings, reload on CPU, chat 200 and `[DONE]`), V2 PASS (`Model loaded successfully (CPU, GPU unavailable)`, W line `GPU readiness failed, falling back to CPU`, `/v1/health` and `/health` both `{"backend":"CPU","status":"ok"}`), V3 PASS (`start CPU`: `(CPU)`, health CPU), V5 PASS (31 unit tests, lint, check_docs). End to end 115 s on the emulator (about 85 s of it between the fallback decision and the CPU engine init).
Verdict: verified on the emulator, 4 PASS, 0 FAIL. Phone GPU not checked.

## Review history

### Round 1 - 2026-11-09
Verdict: Request changes. Scope: 15 files (~550 changed lines incl. tests/docs), one Haiku reviewer.
- R1-1 [MAJOR] `fallBackToCpu` closed the GPU engine outside `inferenceMutex` - Static - Fixed: close and reload run under the lock
- R1-2 [MAJOR] `verifyReady` swallowed `CancellationException` - Static - Fixed: rethrown
- R1-3 [MINOR] `activeBackend` reported the raw requested name although an unknown name runs GPU - Static - Fixed: the backend is resolved once and reported; test added
- R1-4 [MINOR] `fallBackToCpu` was unconditional - Static - Fixed: `require(activeBackend == GPU)`; test added
- R1-5 [MINOR] fallback decision hard-coded in the service; "CPU"/"GPU" literals repeated - Static - Fixed: the service asks `BackendFallbackPolicy.next(activeBackend)`; constants used in the manager
- R1-6 [MINOR] stale line references in pitfalls 16/17 - Static - Fixed (cite the methods; same wording as the equivalent pitfall 16 edit on other branches)
- R1-7 [MINOR] missing failure-path tests (CPU reload throws, empty ping reply, ping conversation closed, unknown backend) - Static - Fixed: 4 tests
- R1-8 [NIT] import order / blank line; start message printed the requested backend first - Static - Fixed (`ready in N s (requested X): <status>`)
Dismissed: pitfalls header says "not yet fixed" while 22 describes the fix (rejected: the file's convention records behaviour and removed entries, like 14 and 21; the "about 2 minutes" figure is backed by the device run: 116 s).
Pre-existing: none.
Resolution: 8 fixed, 1 rejected. 66 unit tests, lint, check_docs pass. Runtime files changed (AiEngineManager.kt, InferenceService.kt): another review iteration and a device re-verify follow.

### Round 2 - 2026-11-09
Verdict: Approve with comments (MINOR/NIT only). Scope: same files, one Haiku reviewer.
- R2-1 [MINOR] the fallback decision and a log used the raw requested name (an unknown name runs GPU) - Static - Fixed: resolved name hoisted and used; test added
- R2-2 [MINOR] `Log.e` without the throwable for the verification failure - Static - Fixed
- R2-3 [MINOR] bare `Exception("Empty response")` - Static - Fixed: `IllegalStateException`
- R2-4 [MINOR] the crash-loop flag is cleared before the first GPU request, so a native abort there bypasses the guard - Static - Rejected as a behaviour change out of scope (the same ordering existed before: the flag was cleared right after `loadModel`); recorded as an accepted gap in pitfall 22
- R2-5 [MINOR] `activeBackend` published after the engine (`/v1/health` could omit `backend`) - Static - Fixed: set before the engine
- R2-6 [MINOR] `/v1/health` backend not asserted - Static - Fixed
- R2-7 [MINOR] stale health example in testing-on-device.md - Static - Fixed
- R2-8 [MINOR] `verifyReady` could return a cancel as an ordinary error on the last attempt - Static - Fixed: `ensureActive()` after each attempt
- R2-9 [NIT] help text; fully qualified `assertNull` - Static - Fixed
Pre-existing: `generateConversationResponseAsync` reports a `CancellationException` through `onErrorCallback` (callee behaviour; worked around in `verifyReady`).
Resolution: 8 fixed, 1 rejected. 31 unit tests, lint, check_docs pass; device re-run follows.

## Log
- 2026-11-09: created from ANDROIDEDGEAICORE-2 gap analysis
- 2026-11-09: plan drafted
- 2026-11-09: plan revised after validation round 1 (and test-seam decision)
- 2026-11-09: plan validated, 2 rounds
- 2026-11-09: autopilot started (batch)
- 2026-11-09: implemented, smoke tests pass
- 2026-11-09: verify: 5 PASS, 0 FAIL
- 2026-11-09: batch-autopilot: shipped, base develop
- 2026-11-09: closed (all acceptance criteria checked; the PR is open, not yet merged)
- 2026-11-09: linked relates_to ANDROIDEDGEAICORE-16, ANDROIDEDGEAICORE-18, ANDROIDEDGEAICORE-19, ANDROIDEDGEAICORE-20 (follow-ups)
