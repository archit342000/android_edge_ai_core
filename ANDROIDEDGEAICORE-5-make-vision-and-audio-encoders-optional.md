---
id: ANDROIDEDGEAICORE-5
title: "Make vision and audio encoders optional"
status: done
created: 2026-11-09
updated: 2026-11-09
links:
  blocked_by: [ANDROIDEDGEAICORE-3, ANDROIDEDGEAICORE-11]
  conflicts_with: [ANDROIDEDGEAICORE-6, ANDROIDEDGEAICORE-7, ANDROIDEDGEAICORE-12]
  relates_to: [ANDROIDEDGEAICORE-18, ANDROIDEDGEAICORE-20]
  split_from: [ANDROIDEDGEAICORE-2]
---

## Description
`AiEngineManager.loadModel` always sets `visionBackend = GPU` and `audioBackend = CPU` (gap #3, pitfalls 19 and 20). Text-only models fail, and vision only works where GPU is available. In 0.19.1, `null` skips an encoder. Enable the encoders only when the model has them (model sections or a `ModelInfo` check) and let the vision and audio backends be chosen.

Background: research and gap analysis in ANDROIDEDGEAICORE-2 (LiteRT-LM v0.19.1). Verify with `scripts/device.sh` (`docs/testing-on-device.md`).

## Acceptance criteria
- [x] Text-only models (for example Qwen3-0.6B) load and chat
- [x] Multimodal models still accept image and audio input where their backend is available
- [x] Vision on CPU works on the emulator, or a clear error is returned
- [x] Pitfalls 19 and 20 updated

## Plan
**Date:** 2026-11-09  **Status:** Validated

### Objective
Initialise the vision and audio encoders only when the model has them, on a backend the encoder supports, with an optional user override, so text-only models load and vision works without GPU where the model allows it. Runs after ANDROIDEDGEAICORE-3 and -11 (it reuses `ModelInspector` / `ModelCapabilities`).

### Context
- Current code: `AiEngineManager.loadModel` always sets `visionBackend = Backend.GPU`, `audioBackend = Backend.CPU` (pitfalls 19, 20). `toLiteRTMessage` adds `Content.ImageBytes` / `Content.AudioBytes` for `image_url` / `audio_url` parts. `MainActivity.startInferenceService` sends `EXTRA_MODEL_PATH` and `EXTRA_BACKEND`; `InferenceService` saves them to `inference_service_prefs` for sticky restarts.
- 0.19.1: `EngineConfig.visionBackend` / `audioBackend` are nullable, "If null, vision executor will not be initialized". `ModelInfo.inputModalities()` and `ModelInfo.supportedBackends(Modality)` (returns `List<BackendType>`) describe the encoders. The official server picks per-modality backends from the model's sections.
- Rules: `docs/standards/android.md` (extras as `InferenceService.companion` constants; resume state in `inference_service_prefs`; UI strings in `strings.xml`; tabs as pages), `docs/standards/api.md` + README together for contract changes, `docs/standards/security.md` (fixed short 400 messages), `docs/standards/kotlin.md` (prefer `enum class` for new option sets).

### Approach
- Extend `ModelCapabilities` (from ANDROIDEDGEAICORE-11) with the supported backends per modality, read with `supportedBackends(Modality.VISION/AUDIO)`. Definitions: whether the encoder exists comes only from `inputModalities()` (`vision`, `audio`); the backend list is `null` when the encoder is absent, and an empty list from the model (no backend constraint recorded) is normalised to `[CPU, GPU]` with a `Log.d`. Encoder backends are limited to CPU and GPU: the empty-list rule is applied to the raw list first, then NPU entries are dropped, so an NPU-only list leaves no usable backend and the encoder is treated as off (the caller logs it at `Log.w`). The lists are read in ANDROIDEDGEAICORE-11's `readModelInfo` (into `RawModelInfo`) and mapped in the pure `toCapabilities`.
- New pure `EncoderBackendSelector.select(supported, mainBackend, override)`: null when `supported` is null (no encoder); the override when the encoder supports it; otherwise the main backend if it is CPU or GPU and supported (an NPU main backend is treated as CPU), else CPU if supported, else GPU. An unsupported override is reported in the selector's result and logged at `Log.w` by the caller (`AiEngineManager`), the automatic choice is used, and the status says so ("vision override GPU ignored, using CPU"), so the fallback is visible.
- New `enum class EncoderBackendOption { AUTO, CPU, GPU }`, chosen in two new RadioGroups ("Vision encoder", "Audio encoder") on the Backend Settings page, persisted in `litertlm_prefs`, sent as new extras `EXTRA_VISION_BACKEND` / `EXTRA_AUDIO_BACKEND`, and saved in `inference_service_prefs` for sticky restarts. Default AUTO.
- `OpenAIServer` rejects a request with an `image_url` part when vision is off, or `audio_url` when audio is off, with `400 {"error": "Model does not support image input"}` / `"... audio input"` before generation (additive status use; documented).
Rejected: a single global "multimodal on/off" switch (cannot fix vision-on-CPU).

### Out of scope
NPU encoder backends, `maxNumImages`, changing the main backend choice, GPU fallback for encoders (ANDROIDEDGEAICORE-12), image size caps.

### Steps
#### Step 1: Capabilities per modality
- **Status:** todo
- **Depends on:** none
- **Files:** `app/src/main/java/com/aanand/edgeaicore/ModelInspector.kt` (modify: `ModelCapabilities`, `inspect`)
- **Change:** add `visionBackends` and `audioBackends` per the definitions in Approach (null when absent; empty normalised to `[CPU, GPU]`; NPU dropped).
- **Verification:** `./gradlew assembleDebug`.

#### Step 2: Selector and option enum
- **Status:** todo
- **Depends on:** 1
- **Files:** `app/src/main/java/com/aanand/edgeaicore/EncoderBackendSelector.kt` (create: `EncoderBackendSelector`, `EncoderBackendOption`)
- **Change:** the pure selection rule above, returning a small result (chosen backend type or none, plus whether the override was ignored).
- **Verification:** step 3 tests.

#### Step 3: Unit tests
- **Status:** todo
- **Depends on:** 2
- **Files:** `app/src/test/java/com/aanand/edgeaicore/EncoderBackendSelectorTest.kt` (create), `app/src/test/java/com/aanand/edgeaicore/ModelInspectorTest.kt` (modify if `validate` inputs change)
- **Change:** no encoder (null) gives null; AUTO prefers the main backend, then CPU; NPU main backend gives CPU; supported override wins; unsupported override falls back and is flagged; a normalised `[CPU, GPU]` list behaves like an explicit one.
- **Verification:** `./gradlew testDebugUnitTest` passes.

#### Step 4: Engine config
- **Status:** todo
- **Depends on:** 1, 2
- **Files:** `app/src/test/java/com/aanand/edgeaicore/AiEngineManagerTest.kt` (modify: the cases in Tests), `app/src/main/java/com/aanand/edgeaicore/AiEngineManager.kt` (modify: `loadModel` signature gains the two options; `EngineConfig` vision/audio from the selector; `Log.d` of the chosen encoder backends)
- **Change:** map the selected `BackendType` (CPU or GPU only) to `Backend.CPU()` / `Backend.GPU()`; keep the existing GPU-to-CPU retry for the main backend, and pass the two encoder options through its recursive `loadModel` call.
- **Verification:** `./gradlew assembleDebug`; the `AiEngineManagerTest` cases above pass.

#### Step 5: Service extras and restart state
- **Status:** todo
- **Depends on:** 4
- **Files:** `app/src/main/java/com/aanand/edgeaicore/InferenceService.kt` (modify: `companion object` constants, `onStartCommand` save/restore, `loadModel` call; status text includes the encoder backends)
- **Change:** read the two extras (default AUTO), store and restore them alongside `saved_backend`.
- **Verification:** `./gradlew assembleDebug`.

#### Step 6: Backend Settings UI
- **Status:** todo
- **Depends on:** 5
- **Files:** `app/src/main/res/layout/activity_main.xml` (modify: `page_backend`, two RadioGroups `rg_vision_backend`, `rg_audio_backend`), `app/src/main/res/values/strings.xml` (modify), `app/src/main/java/com/aanand/edgeaicore/MainActivity.kt` (modify: `onCreate` view lookup and restore, `startInferenceService`; new `litertlm_prefs` keys as `companion` constants)
- **Change:** follow the existing `rg_backend` card style; persist on change.
- **Verification:** `scripts/device.sh ui` lists the new radio buttons; selection survives an app restart.

#### Step 7: Request rejection for missing encoders
- **Status:** todo
- **Depends on:** 4
- **Files:** `app/src/test/java/com/aanand/edgeaicore/OpenAIServerTest.kt` (modify: image and audio 400s), `app/src/main/java/com/aanand/edgeaicore/OpenAIServer.kt` (modify: `/chat/completions` before generation), `app/src/main/java/com/aanand/edgeaicore/AiEngineManager.kt` (expose `modelCapabilities` plus the chosen encoders)
- **Change:** scan request parts with a small pure helper (`MessageParts.hasImage/hasAudio`, placed in `OpenAIModels.kt`) and return the 400s above (a new 400 cause, added to the `api.md` Errors table in step 9).
- **Verification:** unit test for the helper in `OpenAIModelsTest`; the `OpenAIServerTest` 400 cases; `./gradlew testDebugUnitTest`.

#### Step 8: Device checks (emulator, CPU)
- **Status:** todo
- **Depends on:** 6, 7
- **Files:** none (evidence in `~/.claude/tickets/android_edge_ai_core/ANDROIDEDGEAICORE-5-evidence/`)
- **Change:** download `litert-community/Qwen3-0.6B` `Qwen3-0.6B.litertlm` (ungated, Apache-2.0) to `~/.cache/edgeaicore-test-models/`; load it on CPU and chat; send it an image (expect 400). Load Gemma 3n (and Gemma 4 E2B) on CPU with vision AUTO and CPU: send a small PNG data URL and a short WAV.
- **Verification:** Qwen chat 200, image 400 with the fixed message; Gemma image and audio replies 200, or the exact failure with the chosen backend in the status; logs show the chosen encoder backends. Also start with main backend GPU and vision AUTO (the UI default): record the chosen vision backend and the image result; a GPU main-backend failure on the emulator is ANDROIDEDGEAICORE-12's case and is recorded, not fixed, here.

#### Step 9: Docs
- **Status:** todo
- **Depends on:** 8
- **Files:** `docs/pitfalls.md` (19, 20), `docs/standards/api.md` (400 for unsupported input), `README.md` (developer guide: same; app guide: encoder settings), `docs/testing-on-device.md` (backends and models tables), `docs/architecture.md` (Components: `EncoderBackendSelector`)
- **Change:** describe the new behaviour.
- **Verification:** `scripts/check_docs.sh` passes.

### Tests
`EncoderBackendSelectorTest` (new), `OpenAIModelsTest` (part helper), `ModelInspectorTest` (if touched). Through the engine seam and Ktor test host from ANDROIDEDGEAICORE-3: `AiEngineManagerTest` checks the `EngineConfig` the fake factory receives (null encoders for a text-only capability set, the chosen backends otherwise, options kept on the GPU-to-CPU retry); `OpenAIServerTest` checks the image and audio 400s. `InferenceService` and `MainActivity` changes are covered by device checks (agreed exception in `docs/standards/testing.md`, ANDROIDEDGEAICORE-3). Run `./gradlew testDebugUnitTest lintDebug`, `scripts/check_docs.sh`. GPU vision on the phone is optional for this ticket; if the owner has the phone, run step 8's Gemma image with vision GPU there.

### Risks and open questions
- A model's vision encoder may list only GPU; then vision on the emulator correctly gives a clear failure status, which meets the acceptance criterion's "or a clear error".
- `supportedBackends` may be empty for older `.litertlm` files without backend constraints; treat empty as "CPU and GPU" and log it.

### Success criteria
- [ ] Text-only Qwen3-0.6B loads and chats
- [ ] Multimodal models accept image and audio where their encoder backend is available
- [ ] Vision on CPU works on the emulator, or a clear error is returned
- [ ] Image/audio to a model without that encoder gives a fixed 400
- [ ] Tests, lint, docs checks pass; pitfalls 19 and 20 updated

### Reviewer brief
- **Objective:** initialise vision/audio encoders only when the model has them, on a backend the encoder supports, with an optional override, so text-only models load and vision can run without GPU.
- **Key decisions:** encoder presence from `inputModalities()`; per-modality backend lists from `supportedBackends` (empty means `[CPU, GPU]`; NPU dropped; NPU-only means off); a pure `EncoderBackendSelector` (main backend, then CPU, then GPU; an NPU main backend counts as CPU; an unsupported override is reported in the status); `EncoderBackendOption` AUTO/CPU/GPU in two Backend Settings radio groups, extras saved for sticky restarts; fixed 400 for image or audio input to a model without that encoder.
- **Out of scope:** NPU encoders, `maxNumImages`, encoder GPU fallback, image size caps.
- **Success criteria:** Qwen3-0.6B loads and chats; multimodal input works where the encoder backend exists; vision on CPU works on the emulator or gives a clear error; fixed 400s; tests and checks pass; pitfalls 19/20 updated.

The decisions above are intended; report a problem with one only if it breaks a requirement or a documented rule.

### Validation log
- **Round 1:** validator FAIL. My judgement: FAIL, with 2 valid BLOCKING issues.
  1. BLOCKING an empty backend list meant both "no encoder" and "unknown": valid. Encoder presence now comes from `inputModalities()`; empty is normalised to `[CPU, GPU]`.
  2. BLOCKING no NPU mapping for the encoder backend: valid. Encoders are CPU/GPU only, and an NPU main backend counts as CPU.
  3. to 8. MINOR (step 4 dependency, retry forwarding, tests, override visibility, api.md 400 row, GPU-main emulator check): all valid and fixed.
  - Cross-ticket: the testing.md rule 1 gap was settled by the owner (engine seam + Ktor tests); `AiEngineManagerTest` and `OpenAIServerTest` cases were added.
- **Round 2 (diff-scoped):** validator FAIL. My judgement: PASS.
  1. BLOCKING test files missing from the step Files lists: the Tests section and step verifications already scheduled the cases, so only the file lists were incomplete. Downgraded to MINOR and fixed inline (the test files are now in steps 4 and 7).
  2. MINOR NPU-only list order: valid, fixed (empty rule first, then NPU dropped; NPU-only means off).
  3. MINOR the pure selector logging: valid, fixed (the caller logs).


## Implementation notes
### Autopilot run (2026-11-09)
- Mode: automatic (batch-autopilot, item 11/11)
- Branch: feature/optional-encoders  PR base: feature/validate-model-before-load (stacked: blocked by the model-validation ticket)  Starting commit: 8122ff2f437dfe0124e41ff7063b9eee952fb9c7
- Dirty before start: none
- Work files: none yet
- Counters: fix 0/10, review 1/5, re-verify 0
- Last completed stage: 3 verify (all PASS)  Next: done (shipped)
- Hard blocker: none
- WIP commit: none
- Repo: /home/archit-anand/docker-data/shared/Coding/android_edge_ai_core

### Baseline (2026-11-09)
All unit tests passed on the base branch.

### Implementation progress
- [x] Steps 1-3: `ModelCapabilities.visionBackends/audioBackends` (read in `readModelInfo`, normalised by `EncoderBackendSelector.usableBackends`), `EncoderBackendSelector`, `EncoderBackendOption`, `EncoderSummary`; tests (selector 9, inspector)
- [x] Step 4: `AiEngineManager.loadModel(..., visionOption, audioOption)` and `activeEncoders`; tests (5)
- [x] Step 5: InferenceService extras, saved state, status text with the encoders
- [x] Step 6: Backend Settings radio groups (layout, strings, MainActivity)
- [x] Step 7: `MessageParts` and the fixed 400s in `OpenAIServer`; tests
- [x] Step 8: device checks (see Verification)
- [x] Step 9: docs (pitfalls 19 retired and 20 rewritten, api.md, README, on-device guide, architecture)

### Smoke test (2026-11-09)
`./gradlew assembleDebug testDebugUnitTest lintDebug` pass; `scripts/check_docs.sh` OK.

### Autopilot decisions (2026-11-09)
- Stage 1: Plan already `Validated` (2 rounds); reused.

PR: https://github.com/archit342000/android_edge_ai_core/pull/46, branch feature/optional-encoders, base feature/validate-model-before-load

## Verification
### Verification plan (2026-11-09)
**Plan:** ticket Plan above  **Diff base:** feature/validate-model-before-load at 8122ff2 (the PR base)
**Environment:** emulator `claude_edgeaicore_api34`, CPU main backend (and one GPU main-backend start); models `litert-community/Qwen3-0.6B` (text only, downloaded to `~/.cache/edgeaicore-test-models/`), Gemma 4 E2B and Gemma 3n (multimodal); scripts `ANDROIDEDGEAICORE-5-evidence/scripts/{media.py,encoders.sh}`; teardown `device.sh down`.

| ID | Criterion | Scenario | Type | Method | Files | Expected | Evidence |
|----|-----------|----------|------|--------|-------|----------|----------|
| V1 | AC1 | Qwen3-0.6B loads on CPU and chats | happy | adb + API | AiEngineManager.kt, ModelInspector.kt | log `Encoders: vision=none, audio=none`; chat 200 | log, reply |
| V2 | AC1 | an image to Qwen | failure | API | OpenAIServer.kt | 400 `Model does not support image input`; audio too; load 0 | responses |
| V3 | AC2 | Gemma 4 and Gemma 3n on CPU, a small PNG and a short WAV | happy | API | same | 200 with a reply, or the exact failure; log shows the encoder backends | responses |
| V4 | AC3 | vision on CPU on the emulator (AUTO with a CPU main backend) | happy | API | same | works or a clear error | response, log |
| V5 | edge | main backend GPU with vision AUTO (UI default) on the emulator | edge | adb | same | log shows vision=GPU; the GPU result is ticket 12's case, recorded | log |
| V6 | UI | the two encoder radio groups exist, default Auto | happy | UI dump | MainActivity.kt, layout | `rb_vision_auto/cpu/gpu`, `rb_audio_auto/cpu/gpu` | ui dump |
| V7 | regression | unit tests, lint, docs | regression | CLI | all | pass | output |


### Results (2026-11-09)
Commit verified: feature/validate-model-before-load at 8122ff2 + uncommitted:
- `README.md` dba8e979df48eb3119d1cb61518b7be4fb5b33a5
- `app/src/main/java/com/aanand/edgeaicore/AiEngineManager.kt` 4c0ac84e513ffd90b662a1fa2eeb96f20180edf4
- `app/src/main/java/com/aanand/edgeaicore/InferenceService.kt` 4c42f47113426e1b3cdb3d72e2ea1720209ddf8f
- `app/src/main/java/com/aanand/edgeaicore/MainActivity.kt` 7d60f2063ff22944b5a05676e8ed992f59c2383e
- `app/src/main/java/com/aanand/edgeaicore/ModelInspector.kt` 7f1c8bd01855f543beb2b59b3341e0682ce46a82
- `app/src/main/java/com/aanand/edgeaicore/OpenAIModels.kt` 0ec3f0e6785215709778958b8e230355f0eed4bb
- `app/src/main/java/com/aanand/edgeaicore/OpenAIServer.kt` 377064c4e777ddde8d1e3cc9e6a25571fcc133ec
- `app/src/main/res/layout/activity_main.xml` 234911de34b27d663c087284b96d300f9ef165c8
- `app/src/main/res/values/strings.xml` 467d54c36316df5b0db89387d83c33db081561f2
- `app/src/test/java/com/aanand/edgeaicore/AiEngineManagerTest.kt` 8c58b4ce4da8485a04a1a1746a36f120eb015cba
- `app/src/test/java/com/aanand/edgeaicore/FakeLlmEngine.kt` af4f6542e001ad9d8b2aac9269fe33e113655f49
- `app/src/test/java/com/aanand/edgeaicore/ModelInspectorTest.kt` c1c95b37c4f49a1333437533a31bdd8c0f39dd39
- `app/src/test/java/com/aanand/edgeaicore/OpenAIModelsTest.kt` 540609dd4007b2dd8bed3ba8d5702d430cc0907d
- `app/src/test/java/com/aanand/edgeaicore/OpenAIServerTest.kt` a839cf0d5af38ed4f5d9c7087bc95079e4a71c23
- `docs/architecture.md` aee2d3ac84c1133e83c71ea50b60ab0d96093f5d
- `docs/pitfalls.md` 21c5a287114dadeb08a8b8b9e3f0036173ca619a
- `docs/standards/api.md` 817d03de1f60ad7760398608e7ab4ea646e032a9
- `docs/testing-on-device.md` 68d53bc22127c5a37d4697aa7a82f63ba322f857
- `app/src/main/java/com/aanand/edgeaicore/EncoderBackendSelector.kt` 615cf33f606ed86c00d1efd3a50c550fffc42ff9
- `app/src/test/java/com/aanand/edgeaicore/EncoderBackendSelectorTest.kt` ad28899a20ace4314bfd875895db41c9b5e5ec38

Emulator `claude_edgeaicore_api34`. Evidence: `ANDROIDEDGEAICORE-5-evidence/encoders-run.txt`, `scripts/{media.py,encoders.sh}`.

| ID | Status | Evidence |
|----|--------|----------|
| V1 | PASS | Qwen3-0.6B (text only, 614 MB, downloaded from `litert-community`): log `Encoders: vision=none, audio=none`, model loads on CPU, chat 200 "Hey!". |
| V2 | PASS | image to Qwen: 400 `Model does not support image input`; audio: 400 `... audio input`; `/v1/load` 0. |
| V3 | PASS | Gemma 4 E2B CPU: `Encoders: vision=CPU, audio=CPU`; text 200; image 200 "Red" (generated red PNG); audio 200 (a reply about the sound). Gemma 3n CPU: `vision=GPU, audio=CPU`; text 200; image 200 "Red"; audio 200 "The sound is a soft, melodic chime ...". |
| V4 | PASS | vision on CPU works on the emulator for Gemma 4. Gemma 3n lists only GPU for vision, so Auto chose GPU and the image request still answered on the emulator. |
| V5 | PASS (recorded) | Gemma 3n with main backend GPU, vision Auto: `Encoders: vision=GPU, audio=CPU`; the first request then fails with `Can not find OpenCL library` (the GPU-fallback case, a separate change), recorded not fixed. |
| V6 | PASS | `rb_vision_auto/cpu/gpu` and `rb_audio_auto/cpu/gpu` listed in Backend Settings; (default Auto; restoring a stored choice after an app restart not exercised). |
| V7 | PASS | 61 unit tests, lint, check_docs. |

Verdict: verified, 7 PASS, 0 FAIL, 0 BLOCKED. Not run: GPU vision on the phone (optional per the plan).

## Review history

### Round 1 - 2026-11-09
Verdict: Request changes (two MAJORs, fixed). Scope: 20 files (~700 changed lines incl. tests/docs), one Haiku reviewer.
- R1-1 [MAJOR] `EncoderBackendSelector.kt` held four top-level types - Static - Fixed: `EncoderBackendOption` has its own file (the two DTOs stay with the selector)
- R1-2 [MAJOR] emulator evidence in the docs was contradictory / unrecorded - Static - Fixed: result rows for Gemma 4 and Gemma 3n image and audio in the on-device guide, and pitfall 20 states the unexplained Gemma 3n GPU-vision result as such
- R1-3 [MINOR] 400 path could release the load counter twice if `respond` throws - Static - Rejected: same pre-existing outer safety decrement as the other 400 paths (follow-up noted in the cancel/limits PRs)
- R1-4 [MINOR] an unknown backend string was GPU for the engine but CPU for the encoder selector - Static - Fixed: one resolved main backend
- R1-5 [MINOR] `MessageParts` failure paths untested - Static - Fixed: `MessagePartsTest` (null content, object content, missing/object `type`, non-object parts)
- R1-6 [MINOR] `MessageParts` lived in the DTO file - Static - Fixed: own file and test class
- R1-7 [MINOR] status text built in Kotlin - Static - Rejected: the existing status strings are plain text broadcasts throughout
- R1-8 [NIT] duplicate strings; pitfall 20 wording - Static - Fixed
Pre-existing: none new.
Resolution: 6 fixed, 2 rejected. 62 unit tests, lint, check_docs pass. Runtime change: one resolved main-backend value and file moves; not re-run on the device.

## Log
- 2026-11-09: created from ANDROIDEDGEAICORE-2 gap analysis
- 2026-11-09: plan drafted
- 2026-11-09: linked blocked_by ANDROIDEDGEAICORE-11 (reuses ModelInspector/ModelCapabilities from 11)
- 2026-11-09: plan revised after validation round 1 (and test-seam decision)
- 2026-11-09: plan validated, 2 rounds
- 2026-11-09: autopilot started (batch)
- 2026-11-09: implemented, smoke tests pass
- 2026-11-09: verify: 7 PASS, 0 FAIL
- 2026-11-09: batch-autopilot: shipped, base feature/validate-model-before-load (stacked)
- 2026-11-09: closed (all acceptance criteria checked; the PR is open, not yet merged)
- 2026-11-09: linked relates_to ANDROIDEDGEAICORE-18, ANDROIDEDGEAICORE-20 (follow-ups)
