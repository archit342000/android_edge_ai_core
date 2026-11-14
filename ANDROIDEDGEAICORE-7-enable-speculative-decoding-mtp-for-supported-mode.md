---
id: ANDROIDEDGEAICORE-7
title: "Enable speculative decoding (MTP) for supported models"
status: done
created: 2026-11-09
updated: 2026-11-09
links:
  blocked_by: [ANDROIDEDGEAICORE-3, ANDROIDEDGEAICORE-9, ANDROIDEDGEAICORE-11]
  conflicts_with: [ANDROIDEDGEAICORE-5, ANDROIDEDGEAICORE-6]
  relates_to: [ANDROIDEDGEAICORE-18, ANDROIDEDGEAICORE-20]
  split_from: [ANDROIDEDGEAICORE-2]
---

## Description
Gemma 4 ships an MTP drafter. `SpeculativeDecodingConfig` (OFF, ON or ADAPTIVE) gives 1.3–1.8× decode speed on GPU (gap #5). Enable it for models that have a drafter, defaulting to ADAPTIVE, and make it configurable.

Background: research and gap analysis in ANDROIDEDGEAICORE-2 (LiteRT-LM v0.19.1). Verify with `scripts/device.sh` (`docs/testing-on-device.md`).

## Acceptance criteria
- [x] Speculative decoding enabled (ADAPTIVE by default) when the model supports it
- [ ] Decode tokens per second compared with and without it on the phone's GPU and on CPU (phone part waived by the owner 2026-11-09; emulator CPU comparison recorded in Verification)
- [x] Setting exposed in the backend settings

## Plan
**Date:** 2026-11-09  **Status:** Validated

### Objective
Turn on speculative decoding (MTP) for models that ship a drafter, defaulting to ADAPTIVE, with a user setting (Off, On, Adaptive), and measure decode speed with and without it. Runs after ANDROIDEDGEAICORE-3, -9 (token counts for the measurement) and -11 (`ModelInspector`).

### Context
- 0.19.1: `EngineConfig.speculativeDecodingConfig = SpeculativeDecodingConfig(mode)` with `SpeculativeDecodingMode.OFF/ON/ADAPTIVE` ("ON and ADAPTIVE load the drafter when the engine is initialized"; runtime default OFF); `ModelInfo.Llm.hasSpeculativeDecodingSupport()`. `ExperimentalFlags.enableSpeculativeDecoding` is deprecated.
- Research (ANDROIDEDGEAICORE-2): Gemma 4 E2B decode goes 52 to 66-92 tok/s on GPU, 41 to 36-48 on CPU (can be slower on CPU; ADAPTIVE limits that).
- Current code: `AiEngineManager.loadModel(modelPath, backendType)`; `MainActivity.startInferenceService` sends `EXTRA_MODEL_PATH`, `EXTRA_BACKEND`; Backend Settings page has `rg_backend`. After ANDROIDEDGEAICORE-11, `ModelInspector` returns `ModelCapabilities`; after -9, responses carry `usage.completion_tokens`.
- Rules: `docs/standards/android.md` (extras in `InferenceService.companion`; sticky state in `inference_service_prefs`; strings in `strings.xml`; existing tab pattern), `docs/standards/kotlin.md` (enum for option sets).

### Approach
- `ModelCapabilities` gains `speculativeDecoding: Boolean` (from `hasSpeculativeDecodingSupport()` for `Llm`, false otherwise), read in ANDROIDEDGEAICORE-11's `readModelInfo` inside its `ModelInfo.use { }` block before it closes.
- New `enum class SpeculativeDecodingOption { OFF, ON, ADAPTIVE }` (default ADAPTIVE), chosen in a new RadioGroup `rg_speculative` on the Backend Settings page, stored in `litertlm_prefs`, sent as `EXTRA_SPECULATIVE_DECODING`, saved in `inference_service_prefs`.
- `loadModel` sets `speculativeDecodingConfig` only when the model supports it and the option is not OFF; otherwise null, with `Log.i` "speculative decoding off (unsupported model|user setting)". The status text adds "MTP <mode>" when active.
- If initialisation with the drafter throws, report the error (no silent retry without MTP); the user can switch the setting to Off.
Rejected: per-request control (engine-level drafter load; per-conversation override adds a request field for no clear client need).

### Out of scope
Per-request or per-conversation speculative settings, other drafter types, changing default backends.

### Steps
#### Step 1: Capability and option
- **Status:** todo
- **Depends on:** none
- **Files:** `app/src/main/java/com/aanand/edgeaicore/ModelInspector.kt` (modify: `ModelCapabilities`, `inspect`), `app/src/main/java/com/aanand/edgeaicore/SpeculativeDecodingOption.kt` (create: enum, pure `fromStored(value: String?)` (null or unknown gives ADAPTIVE, unknown flagged so the caller logs `Log.w`), and pure `toConfigMode(option, supported)` returning the LiteRT-LM mode or null)
- **Change:** as in Approach. `RawModelInfo` (ANDROIDEDGEAICORE-11) gains `speculativeSupport`, read in `readModelInfo` inside `use { }`; the pure `toCapabilities(raw)` maps it, so the new field is unit-tested through `inspect` with a fake reader.
- **Verification:** step 2 tests.

#### Step 2: Unit tests
- **Status:** todo
- **Depends on:** 1
- **Files:** `app/src/test/java/com/aanand/edgeaicore/SpeculativeDecodingOptionTest.kt` (create), `app/src/test/java/com/aanand/edgeaicore/ModelInspectorTest.kt` (modify: through a fake reader, `speculativeDecoding` is true only for an LLM with drafter support, false for an LLM without it and for non-LLM types)
- **Change:** unsupported model gives null for every option; OFF gives null; ON and ADAPTIVE map to their modes; parsing a stored string, including an unknown value (falls back to ADAPTIVE with a logged warning in the caller).
- **Verification:** `./gradlew testDebugUnitTest` passes.

#### Step 3: Engine config
- **Status:** todo
- **Depends on:** 1
- **Files:** `app/src/main/java/com/aanand/edgeaicore/AiEngineManager.kt` (modify: `loadModel` signature and `EngineConfig`)
- **Change:** as in Approach; pass the option through the recursive CPU retry call.
- **Verification:** `./gradlew assembleDebug`; `AiEngineManagerTest` (engine seam from ANDROIDEDGEAICORE-3) checks the fake factory receives the mode for a supporting model, null for an unsupported one or OFF, and keeps it on the CPU retry. The native `hasSpeculativeDecodingSupport()` read inside `readModelInfo` is covered by the step 5 device check (load logs show the drafter mode for Gemma 4 and "unsupported model" for Gemma 3n).

#### Step 4: Service and UI
- **Status:** todo
- **Depends on:** 3
- **Files:** `app/src/main/java/com/aanand/edgeaicore/InferenceService.kt` (modify: constant, save/restore, `loadModel` call, status text), `scripts/device.sh` (modify: `cmd_start`, help), `app/src/main/res/layout/activity_main.xml` (modify: `page_backend` card with `rg_speculative`, `rb_spec_off`, `rb_spec_on`, `rb_spec_adaptive`), `app/src/main/res/values/strings.xml` (modify), `app/src/main/java/com/aanand/edgeaicore/MainActivity.kt` (modify: `onCreate` restore, `startInferenceService`, pref key constant)
- **Change:** follow the `rg_backend` card style. A missing extra (adb start, older intents) means ADAPTIVE; the value is saved to and restored from `inference_service_prefs` with the other restart state. Extend `scripts/device.sh start` to pass `--es SPECULATIVE_DECODING "$SPEC"` when the `SPEC` environment variable is set, and list `SPEC` in its help text.
- **Verification:** `./gradlew assembleDebug testDebugUnitTest lintDebug`; `scripts/device.sh ui` lists the buttons.

#### Step 5: Measurement
- **Status:** todo
- **Depends on:** 4
- **Files:** none (script and results in `~/.claude/tickets/android_edge_ai_core/ANDROIDEDGEAICORE-7-evidence/`)
- **Change:** a Python script sends the same streamed prompt ("Count from 1 to 200, comma separated") with `temperature: 0` and `stream_options.include_usage`, 3 runs each, and computes decode tok/s as `(completion_tokens - 1) / (t_last_chunk - t_first_chunk)`. Emulator CPU with Gemma 4 E2B: `SPEC=OFF|ON|ADAPTIVE MODEL=~/.cache/edgeaicore-test-models/gemma-4-E2B-it.litertlm scripts/device.sh start CPU` for each mode (this ticket needs ANDROIDEDGEAICORE-3's Gemma 4 result to be a successful load; if Gemma 4 still fails there, this ticket is blocked and says so). Phone with the owner: GPU and CPU, each Off and Adaptive. Also Gemma 3n (no drafter) with Adaptive to confirm it is skipped.
- **Verification:** table of tok/s per mode and backend recorded; logs show the drafter mode at load; Gemma 3n logs "unsupported model".

#### Step 6: Docs
- **Status:** todo
- **Depends on:** 5
- **Files:** `README.md` (app guide: the setting), `docs/architecture.md` (Components: `SpeculativeDecodingOption`), `docs/testing-on-device.md` ("Backends and models": MTP results; "What each step does" `start` row: `SPEC`)
- **Change:** document the setting and measured effect.
- **Verification:** `scripts/check_docs.sh` passes.

### Tests
`SpeculativeDecodingOptionTest` (new); `AiEngineManagerTest` and `ModelInspectorTest` extended (step 3). `InferenceService` and `MainActivity` changes are covered by device checks (agreed exception in `docs/standards/testing.md`, ANDROIDEDGEAICORE-3). Run `./gradlew testDebugUnitTest lintDebug`, `scripts/check_docs.sh`; measurement in step 5.

### Risks and open questions
- The phone measurements need the owner. The PR may merge with emulator CPU numbers, but the ticket closes only after the phone GPU and CPU runs, unless the owner explicitly accepts closing without them.
- ADAPTIVE on CPU may still be slower than Off for short replies. The default stays ADAPTIVE as the acceptance criterion says; changing it for CPU needs the owner's decision first.
- Drafter memory adds to the footprint; record `meminfo` with and without.

### Success criteria
- [x] Speculative decoding on (ADAPTIVE by default) when the model supports it
- [ ] Decode tok/s compared with and without it on CPU, and on the phone GPU with the owner
- [x] Setting in Backend Settings
- [x] Tests, lint, docs checks pass

### Reviewer brief
- **Objective:** enable MTP speculative decoding for models with a drafter, ADAPTIVE by default, with an Off/On/Adaptive setting, and measure decode speed with and without it.
- **Key decisions:** `RawModelInfo.speculativeSupport` (read in ticket 11's `readModelInfo`) mapped by the pure `toCapabilities`; the `SpeculativeDecodingOption` enum with `fromStored`/`toConfigMode`; `EngineConfig.speculativeDecodingConfig` set only when supported and not OFF; no silent retry without MTP; a Backend Settings radio group, an extra and restart state; `device.sh start` honours `SPEC`; measurement from `usage` (ticket 9) on emulator CPU and the phone's GPU/CPU.
- **Out of scope:** per-request/per-conversation settings, other drafters, changing default backends.
- **Success criteria:** ADAPTIVE by default when supported; tok/s comparison recorded (the phone runs are needed to close unless the owner waives them); the setting exists; tests and checks pass.

The decisions above are intended; report a problem with one only if it breaks a requirement or a documented rule.

### Validation log
- **Round 1:** validator FAIL. My judgement: FAIL, with 2 valid BLOCKING issues.
  1. BLOCKING no tests for the changed classes: valid. The owner chose engine seam + Ktor tests; `AiEngineManagerTest` cases were added.
  2. BLOCKING no way to pass the mode through `device.sh start`: valid (checked `cmd_start`); `SPEC` env added.
  3. to 10. MINOR (`fromStored` parse function, close condition for the phone runs, which CPU, Gemma 4 dependency, reading inside `use`, owner decision before a default change): all valid and fixed.
- **Round 2 (diff-scoped):** validator FAIL. My judgement: FAIL, with 1 valid BLOCKING issue: the new field read in `inspect` had no unit test. Fixed with ticket 11's injectable reader and the pure `toCapabilities`, tested through a fake reader.
- **Round 3 (diff-scoped):** validator PASS with 1 MINOR issue (stale "inside inspect" wording). Valid, fixed inline to `readModelInfo`.


## Implementation notes
### Autopilot run (2026-11-09)
- Mode: automatic (batch-autopilot, item 9/11)
- Branch: feature/speculative-decoding  PR base: feature/validate-model-before-load (stacked: chain 9 -> 11 -> 7)  Starting commit: 8122ff2f437dfe0124e41ff7063b9eee952fb9c7
- Dirty before start: none
- Work files: none yet
- Counters: fix 0/10, review 1/5, re-verify 1
- Last completed stage: 3 verify (phone runs BLOCKED)  Next: done (shipped; ticket stays open for the phone GPU/CPU runs)
- Hard blocker: none
- WIP commit: none
- Repo: /home/archit-anand/docker-data/shared/Coding/android_edge_ai_core

### Baseline (2026-11-09)
All unit tests passed on the base branch.

### Implementation progress
- [x] Steps 1-2: `ModelCapabilities.speculativeDecoding` (read in `readModelInfo`), `SpeculativeDecodingOption` (+ tests)
- [x] Step 3: `AiEngineManager.loadModel(..., speculativeDecoding)` sets `speculativeDecodingConfig` only for supporting models and not OFF, also on the CPU retry; logs the decision (+ tests)
- [x] Step 4: InferenceService (extra, saved state, status text), `device.sh` (`SPEC`), layout/strings/MainActivity (radio group, stored in `litertlm_prefs`)
- [x] Step 5: emulator CPU measurement (phone runs pending)
- [x] Step 6: docs (README, architecture, on-device guide)

### Smoke test (2026-11-09)
`./gradlew assembleDebug testDebugUnitTest lintDebug` pass; `scripts/check_docs.sh` OK.

### Autopilot decisions (2026-11-09)
- Stage 1: Plan already `Validated` (3 rounds); reused.
- No phone is connected, so the phone GPU/CPU runs of the acceptance criteria cannot be done in this run; the PR carries the emulator CPU numbers and the ticket stays open for the phone runs (the plan allows this).

PR: https://github.com/archit342000/android_edge_ai_core/pull/44, branch feature/speculative-decoding, base feature/validate-model-before-load

## Verification
### Verification plan (2026-11-09)
**Plan:** ticket Plan above  **Diff base:** feature/validate-model-before-load at 8122ff2 (the PR base; the diff also contains nothing from tickets 9/11, which are in that base)
**Environment:** emulator `claude_edgeaicore_api34`, CPU only (no phone is connected: the phone GPU/CPU runs of the acceptance criteria are NOT done in this run); Gemma 4 E2B (may have a drafter) and Gemma 3n (no drafter); scripts `ANDROIDEDGEAICORE-7-evidence/scripts/{decode.py,mtp.sh}`; teardown `device.sh down`.

| ID | Criterion | Scenario | Type | Method | Files | Expected | Evidence |
|----|-----------|----------|------|--------|-------|----------|----------|
| V1 | AC1 | Gemma 4 with no setting given (ADAPTIVE default) | happy | logcat | AiEngineManager.kt, InferenceService.kt | log says speculative decoding mode ADAPTIVE if the model has a drafter, else "unsupported model" | log |
| V2 | AC2 | decode tok/s for OFF, ON, ADAPTIVE (3 runs each, temperature 0) on emulator CPU | happy | API timing + usage | same | table of tok/s | table |
| V3 | AC1 | Gemma 3n (no drafter) with ADAPTIVE | edge | logcat | same | "unsupported model", loads and answers | log |
| V4 | AC3 | the setting in Backend Settings | happy | UI dump | MainActivity.kt, layout | `rg_speculative` with 3 buttons, default Adaptive | ui dump |
| V5 | regression | unit tests, lint, docs | regression | CLI | all | pass | output |
| V6 | AC2 (phone) | phone GPU and CPU, Off vs Adaptive | happy | phone | - | table | BLOCKED: no phone connected |


### Results (2026-11-09)
Commit verified: feature/validate-model-before-load at 8122ff2 + uncommitted:
- `README.md` 421182a724924c33447af5f759988fa55d0b2982
- `app/src/main/java/com/aanand/edgeaicore/AiEngineManager.kt` 0a7d78b4cc150262ec82ef9842f9c3abeea08d1e
- `app/src/main/java/com/aanand/edgeaicore/InferenceService.kt` d03fe072a496e17fa1d3ec1e77cfe43bf1d4d32f
- `app/src/main/java/com/aanand/edgeaicore/MainActivity.kt` 5f54928b135df6d00314d76ed9156ece08c466d5
- `app/src/main/java/com/aanand/edgeaicore/ModelInspector.kt` 6c8e0140be904cf7e5f869bf5395e3352aa4803d
- `app/src/main/res/layout/activity_main.xml` a167107124e4db8c1efdcfa351e9321a6055277b
- `app/src/main/res/values/strings.xml` b3081a6e21ad433b8d9608afbda1aa076d88330e
- `app/src/test/java/com/aanand/edgeaicore/AiEngineManagerTest.kt` a340bc43564c50bcd587301a26a559ee2b635f76
- `app/src/test/java/com/aanand/edgeaicore/ModelInspectorTest.kt` 0b79686e8adf90ee23a49162579caa7089559877
- `docs/architecture.md` 25c4033e5759cf5f006ec74f6c141f78aea5b1d9
- `docs/testing-on-device.md` 39f6abde2379407339c95da9c0e83713a2076469
- `scripts/device.sh` 4665a26abf1effb53e9ed6538f64fb28e8f59c73
- `app/src/main/java/com/aanand/edgeaicore/SpeculativeDecodingOption.kt` dc4c1d8320355cc1d44a2ba2db7e7d7d07877f52
- `app/src/test/java/com/aanand/edgeaicore/SpeculativeDecodingOptionTest.kt` fc2d6a1050e9589ffc820dd3dc84237627d21542

Emulator `claude_edgeaicore_api34`, CPU only. Evidence: `ANDROIDEDGEAICORE-7-evidence/mtp-run.txt`, `scripts/{mtp.sh,decode.py}`. Decode rate = (completion tokens - 1) / (last chunk time - first chunk time), temperature 0, 891 tokens, 3 runs each.

| Mode (Gemma 4 E2B, CPU) | run 1 | run 2 | run 3 | mean tok/s | PSS |
|---|---|---|---|---|---|
| Off | 22.8 | 22.7 | 23.1 | **22.9** | 1.55 GB |
| On | 33.3 | 33.3 | 32.5 | **33.0** (+44%) | 1.62 GB |
| Adaptive | 32.9 | 30.5 | 33.1 | **32.2** (+41%) | 1.63 GB |

| ID | Status | Evidence |
|----|--------|----------|
| V1 | PASS | no setting given: log `Speculative decoding (MTP): ADAPTIVE` for Gemma 4. |
| V2 | PASS | table above (the research expected CPU might be slower; here it is faster). |
| V3 | PASS | Gemma 3n: `Speculative decoding off (unsupported model)`, loads, chat 200. |
| V4 | PASS | Backend Settings lists `rb_spec_off`, `rb_spec_on`, `rb_spec_adaptive`; Adaptive is the default (restoring a stored choice after an app restart not exercised). |
| V5 | PASS | 54 unit tests, lint, check_docs. |
| V6 | BLOCKED | the phone GPU and CPU runs need the owner's phone (none connected); the ticket stays open for them (the plan allows merging with the emulator numbers). |

Verdict: partly verified: 5 PASS, 0 FAIL, 1 BLOCKED (phone).

## Review history

### Round 1 - 2026-11-09
Verdict: Approve with comments (MINOR/NIT only). Scope: 14 files (~480 changed lines incl. tests/docs), one Haiku reviewer.
- R1-1 [MINOR] the status text used the requested MTP mode, and a start while running with a changed setting kept the old engine - Static - Fixed: `AiEngineManager.activeSpeculativeMode` (set with the engine, cleared by `close()`) feeds the status; `loadModel` reloads when the option changes; tests added; re-checked on the emulator (`status-run.txt`: start while running with OFF then ON each reloads and logs the new mode)
- R1-2 [MINOR] MTP decision duplicated in the service; "MTP" literal in the status - Static - Fixed (single source); the literal stays: broadcast status strings are plain text throughout (existing pattern)
- R1-3 [MINOR] `device.sh` header wording; `SPEC` not validated - Static - Fixed (`SPEC must be OFF, ON or ADAPTIVE`, verified)
- R1-4 [MINOR] on-device guide Start row lacks `SPECULATIVE_DECODING` - Static - Fixed
- R1-5 [MINOR] measurement method not stated in the doc - Static - Fixed
- R1-6 [NIT] fully qualified enum, helper placement in the test - Static - Fixed
Pre-existing: none.
Resolution: 6 fixed. 55 unit tests, lint, check_docs pass.

## Log
- 2026-11-09: created from ANDROIDEDGEAICORE-2 gap analysis
- 2026-11-09: plan drafted
- 2026-11-09: linked blocked_by ANDROIDEDGEAICORE-11 (reuses ModelInspector/ModelCapabilities from 11)
- 2026-11-09: linked blocked_by ANDROIDEDGEAICORE-9 (MTP measurement uses usage token counts from 9)
- 2026-11-09: plan revised after validation round 1 (and test-seam decision)
- 2026-11-09: plan revised after validation round 2
- 2026-11-09: plan validated, 3 rounds
- 2026-11-09: autopilot started (batch)
- 2026-11-09: implemented, smoke tests pass
- 2026-11-09: verify: 5 PASS, 1 BLOCKED (phone)
- 2026-11-09: batch-autopilot: shipped, base feature/validate-model-before-load (stacked); phone runs pending
- 2026-11-09: linked relates_to ANDROIDEDGEAICORE-18, ANDROIDEDGEAICORE-20 (follow-ups)
- 2026-11-09: plan success criteria ticked from V1, V3, V4, V5; only the phone GPU/CPU comparison (V6) remains, planned in ANDROIDEDGEAICORE-18 Step 8
- 2026-11-09: owner cannot connect the phone now; the phone GPU/CPU comparison waits for ANDROIDEDGEAICORE-18 Step 8
- 2026-11-09: owner waived the phone speed comparison; closed (criterion 2 met on emulator CPU only; PR #44 open, merges with ANDROIDEDGEAICORE-20)
