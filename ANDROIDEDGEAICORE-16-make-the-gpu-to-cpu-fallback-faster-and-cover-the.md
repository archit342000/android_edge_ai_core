---
id: ANDROIDEDGEAICORE-16
title: "Make the GPU to CPU fallback faster and cover the first GPU request in the crash guard"
status: done
created: 2026-11-09
updated: 2026-11-10
links:
  conflicts_with: [ANDROIDEDGEAICORE-17, ANDROIDEDGEAICORE-19]
  blocked_by: [ANDROIDEDGEAICORE-20]
  relates_to: [ANDROIDEDGEAICORE-12, ANDROIDEDGEAICORE-24]
---

## Description
Found while implementing ANDROIDEDGEAICORE-12.
- On the emulator the fallback took about 115 s end to end: about 17 s GPU init, about 85 s between "falling back" and the CPU engine init (closing the failed GPU engine), then the CPU load. Find out why the close is slow and whether a failed GPU engine can be dropped faster, or whether the GPU can be probed before the full model load.
- The crash-loop flag (`crashed_during_init`) is cleared once `loadModel` returns, before the first GPU request (the readiness ping). A native abort during that first GPU request is not covered by the guard, and a sticky restart would try GPU again (documented in pitfall 22). Decide whether to keep the flag set until the readiness check passes.
- A GPU failure after the ping (mid-session) is not handled and still surfaces as a 500.

Background: found while running the ANDROIDEDGEAICORE-4 to -14 batch.

## Acceptance criteria
- [ ] Fallback time on the emulator measured and reduced, or the cause documented
- [ ] Decision recorded on keeping the crash flag set until the first request succeeds, implemented if chosen
- [ ] Mid-session GPU failure behaviour decided (document or handle)
- [ ] Pitfall 22 updated

## Plan
**Date:** 2026-11-09  **Status:** Validated

### Objective
Find where the 85 s of the emulator GPU-to-CPU fallback goes and remove it if it is in app code, keep the crash-loop guard armed until the first GPU request has succeeded, and record the mid-session GPU failure decision.

### Context
- Findings check (2026-11-09) against `origin/feature/gpu-cpu-fallback` (#40):
  - Timing: confirmed from `ANDROIDEDGEAICORE-12-evidence/gpu-run.txt`: GPU init 17 s (17:15:55 to 17:16:12), two failed pings to 17:16:20.536 ("falling back to CPU"), CPU `Initializing Engine` at 17:17:45.157 (85 s later), CPU loaded 0.6 s after, ping 4.5 s. Correction: the log does not show that closing the GPU engine is the slow part; nothing is logged between the fallback decision and the CPU engine init, which covers `AiEngineManager.fallBackToCpu` (acquire `inferenceMutex`, `close()` = `closeAllConversations()` + `engine.close()`) and the start of `loadModel` (a second `close()`, file check). The missing CPU `Loading model from` line is only the `gpu.sh` grep filter. After ANDROIDEDGEAICORE-20, `loadModel` also runs `ModelInspector` (#39) before the engine init, so the candidates are: the lock wait, `closeAllConversations()`, the native `engine.close()`, `loadModel`'s second `close()` and file check, and the model inspection (only after #39 is merged; it is not on `feature/gpu-cpu-fallback`, where the 85 s was measured).
  - Crash flag: confirmed: `InferenceService.onStartCommand` clears `crashed_during_init` right after `aiEngineManager.loadModel` returns, before `verifyEngineReady()`; the fallback re-arms it (`markInitStarted`) only around `fallBackToCpu`. A native abort during the GPU readiness ping is not guarded, and a sticky restart reads `saved_backend` = GPU and tries GPU again.
  - Mid-session: confirmed: no handling after the ping; a later GPU error reaches the chat handler's 500 path.
- LiteRT-LM 0.19.1 has no GPU probe: `Engine` exposes only `initialize`, `close`, `createConversation`, `createSession`; `ModelInfo.supportedBackends(Modality)` lists the model's backends, not the device's (checked with `javap` on the AAR).
- Depends on ANDROIDEDGEAICORE-20 (#40 and the later `loadModel`/`onStartCommand` changes from #39, #42, #44, #46 must be on `develop`).
- Rules: `docs/standards/android.md` Lifecycle ("set `crashed_during_init` before loading a native model, clear it after success or a handled failure"), `docs/standards/errors-and-logging.md` (`Log.d` for diagnostics), `docs/standards/testing.md` (`InferenceService` has no test class; device checks), `docs/pitfalls.md` 22.

### Approach
- Measure first: `Log.d` timings (elapsed ms, no content) at the fallback boundaries: mutex acquired, `closeAllConversations` done, `engine.close()` done, inspection done, engine init done. Run `gpu.sh` on the emulator.
- Act on the measurement: if the time is in app code (lock wait, conversation-close waits, inspection), fix that part with a unit test in `AiEngineManagerTest`. If it is inside the native `engine.close()` of the failed GPU engine, do not work around it (closing later would hold two engines in memory); record the cause and numbers in pitfall 22.
- Crash guard: keep `crashed_during_init` set until the readiness check has finished: clear it after the ping succeeds or after a handled readiness failure, not when `loadModel` returns. A native abort during the first GPU request then leaves the flag set and the sticky restart is refused like any init crash.
- Mid-session GPU failure: document only (pitfall 22, `docs/standards/api.md` 500 row unchanged): no GPU failure after a successful ping has been observed (the emulator never passes the GPU ping, and phone GPU runs are pending in ANDROIDEDGEAICORE-18), and switching backends under live requests needs its own design.

### Out of scope
- Restarting on CPU automatically after a guarded crash (the guard stays a refusal, as today).
- A GPU probe before the model load (no LiteRT-LM API, see Context).
- Phone measurements (ANDROIDEDGEAICORE-18).

### Steps
#### Step 1: Timing logs on the fallback path
- **Status:** todo
- **Depends on:** none
- **Files:** `app/src/main/java/com/aanand/edgeaicore/AiEngineManager.kt` (modify: `fallBackToCpu`, `close`, `loadModel`)
- **Change:** `Log.d` with elapsed ms at the boundaries in Approach, using the class `TAG`.
- **Verification:** `./gradlew testDebugUnitTest lintDebug` pass.

#### Step 2: Measure on the emulator
- **Status:** todo
- **Depends on:** 1
- **Files:** none in the repo; `~/.claude/tickets/android_edge_ai_core/ANDROIDEDGEAICORE-16-evidence/` (copy of `ANDROIDEDGEAICORE-12-evidence/scripts/gpu.sh` with the grep widened to `AiEngineManager`)
- **Change:** set the copy's logcat filter to `grep -E "AiEngineManager|InferenceService"` so the Step 1 timing lines and `Initializing Engine` are kept; run it twice; tabulate time per boundary.
- **Verification:** the run logs are saved and the slow segment is named in Implementation notes.

#### Step 3: Remove app-side delay (only if Step 2 finds one)
- **Status:** todo
- **Depends on:** 2
- **Files:** `AiEngineManager.kt` (the segment found), `app/src/test/java/com/aanand/edgeaicore/AiEngineManagerTest.kt` (modify)
- **Change:** remove the app-side wait found (for example avoid re-inspecting a file already inspected for the GPU load, or a redundant second `close()`). If Step 2 shows the time is in native `engine.close()`, this step records "not applicable" and the cause goes to Step 6.
- **Verification:** a unit test with `FakeLlmEngine` proves the removed wait; a rerun of Step 2 shows the shorter fallback.

#### Step 4: Keep the crash flag set through the readiness check
- **Status:** todo
- **Depends on:** none
- **Files:** `app/src/main/java/com/aanand/edgeaicore/InferenceService.kt` (modify: `onStartCommand` load block)
- **Change:** remove the two clears that follow `aiEngineManager.loadModel(...)` and `aiEngineManager.fallBackToCpu(...)`; add one clear after the final `verifyEngineReady()` call (the CPU one when the fallback ran, otherwise the first), whatever its result; keep the clear in the existing `catch`.
- **Verification:** `./gradlew testDebugUnitTest lintDebug` pass; Step 5.

#### Step 5: Device check of the guard
- **Status:** todo
- **Depends on:** 4
- **Files:** a script in `ANDROIDEDGEAICORE-16-evidence/scripts/`
- **Change:** on the emulator, run `scripts/device.sh start GPU` in the background (it blocks until the load ends); from a second shell, poll logcat until the `Log.d` line `loadModel returned. Starting readiness verification...` appears, then `adb shell kill -6 <app pid>`; let the system's sticky restart run. Also rerun a normal GPU start (fallback succeeds) and a CPU start.
- **Verification:** after the kill, logcat shows "Detected crash loop during previous initialization" and no new GPU load; the normal starts still end in `Model loaded successfully`, and a later manual start loads normally (flag reset).

#### Step 6: Docs
- **Status:** todo
- **Depends on:** 3, 5
- **Files:** `docs/pitfalls.md` (22), `docs/architecture.md` (the request-flow sentence on `crashed_during_init`: the flag now spans load and readiness check); the README states no fallback duration and stays as is
- **Change:** pitfall 22: measured breakdown and cause, the guard now covers the first request, mid-session GPU failure documented as a 500 with no fallback.
- **Verification:** `scripts/check_docs.sh` passes.

### Tests
`AiEngineManagerTest` for any Step 3 fix. `InferenceService` changes are covered by the Step 5 device check (agreed exception, `testing.md`). Commands: `./gradlew testDebugUnitTest lintDebug`, `scripts/check_docs.sh`.

### Risks and open questions
- Killing the process during the ping is timing-sensitive (about 8 s window on the emulator); repeat if the kill lands before or after.
- Keeping the flag set longer means a crash in the readiness ping on CPU also blocks the sticky restart; that is the guard's intended behaviour for init crashes.
- A non-crash kill (low-memory killer, system) during the readiness ping (a few seconds) also leaves the flag set, so that sticky restart is refused too and the user starts the service again. Accepted: the window is short and the same already applies to a kill during the model load.

### Success criteria
- [ ] Fallback time broken down on the emulator; reduced if the cause is app-side, otherwise the cause recorded in pitfall 22
- [ ] `crashed_during_init` stays set until the readiness check finishes; abort during the first GPU request shown to block the sticky restart
- [ ] Mid-session GPU failure decision (document, 500) recorded in pitfall 22
- [ ] Checks pass

### Owner decisions 2026-11-09
- Approved: the crash guard also refuses a sticky restart after a non-crash kill during the readiness check.

### Reviewer brief
Objective: explain and, if app-side, shorten the 85 s GPU-to-CPU fallback gap; keep `crashed_during_init` set through the readiness check; record the mid-session GPU failure decision. Key decisions: measure with `Log.d` timings before changing anything; do not defer closing the failed GPU engine (two engines in memory); if the cost is in native `engine.close()`, document it in pitfall 22; clear the crash flag once, after the final readiness check (and in the existing `catch`); mid-session GPU failure stays a 500 and is documented. Runs after ANDROIDEDGEAICORE-20. Out of scope: automatic CPU restart after a guarded crash, a pre-load GPU probe (no API), phone runs. Success: breakdown recorded, guard shown to block the sticky restart after an abort in the first GPU request, pitfall 22 and architecture updated, checks pass.
The decisions above are intended; report a problem with one only if it breaks a requirement or a documented rule.

### Validation log
- Round 1: validator FAIL. Judged FAIL. (1) BLOCKING Step 5 gated on an unlogged status text and ran the kill inline with the blocking start: valid, now background start + gate on the `Log.d` line. (2) MINOR `closeConversation` does not wait on `CANCEL_WAIT_MS`: valid (checked #36: the wait is only in the generation cancel path), candidate list corrected. (3) MINOR non-crash kill during the ping: valid, added to Risks. (4) MINOR which clears move: valid, made explicit. (5) MINOR mid-session evidence overstated: valid, reworded. (6) MINOR grep pattern unstated: valid, given.
- Round 2: validator FAIL. Judged PASS. (1) BLOCKING `device.sh start GPU` dies on the first "Ping failed": disproved, on `feature/gpu-cpu-fallback` `cmd_start` greps only `Model loaded successfully|Model verification failed|Failed to initialize|crash loop` ("Ping failed lines are retries"), and `gpu-run.txt` shows `ready in 115 s ... (CPU, GPU unavailable)`. (2) MINOR same root cause: disproved likewise. (3) MINOR `ModelInspector` not on the measured branch: valid, noted in Context.

## Implementation notes
PR: https://github.com/archit342000/android_edge_ai_core/pull/48, branch bugfix/gpu-fallback-crash-guard, base feature/land-engine-upgrade
Timings (evidence/timing.txt): fallback 12-27 s, all in failing GPU Engine.initialize; close 0 ms; inspect 16-27 ms; CPU load <1 s. Guard check (evidence/guard.txt): kill -6 in ping window -> sticky restart refused; manual start resets. Formal review/resolve loop not run.

## Verification

## Review history

## Log
- 2026-11-09: created from the follow-ups of the engine upgrade batch
- 2026-11-09: findings checked (timing and crash-flag gap confirmed; correction: the log does not prove the GPU engine close is the slow part); plan validated, 2 rounds
- 2026-11-09: linked blocked_by ANDROIDEDGEAICORE-20 (its code lives on the unmerged batch PRs)
- 2026-11-09: linked conflicts_with ANDROIDEDGEAICORE-19 (InferenceService.onStartCommand crash flag and prefs)
- 2026-11-09: linked conflicts_with ANDROIDEDGEAICORE-17 (AiEngineManager.loadModel)
- 2026-11-09: owner decisions recorded in the Plan
- 2026-11-09 batch-autopilot: shipped PR #48 (stacked on #47)
- 2026-11-10: linked relates_to ANDROIDEDGEAICORE-24 (batch follow-ups)
- 2026-11-10: closed (merged in PR #48)
