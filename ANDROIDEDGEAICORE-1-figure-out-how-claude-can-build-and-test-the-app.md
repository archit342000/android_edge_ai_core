---
id: ANDROIDEDGEAICORE-1
title: "Figure out how Claude can build and test the app"
status: done
created: 2026-11-09
updated: 2026-11-09
---

## Description
Work out a repeatable way for Claude to compile the app, install it, and exercise it on an emulator or device (start the service, load a model, call the REST API, check the UI and logs), so that features and fixes can be verified while they are being implemented.
Also work out how to build APKs the user can install on their own phone (debug and signed release, and whether a new build can update an existing install).
This ticket is investigation only: decide the approach and record it here. No app code changes, and no repo changes. The findings go into the repo docs later, in a follow-up.

Known facts (2026-11-09):
- Android SDK at `~/Android/Sdk`, JDK 21, `/dev/kvm` available.
- `./gradlew assembleDebug testDebugUnitTest lintDebug` works locally.
- AVDs: `Pixel_9_Pro_XL` (API 36, x86_64) and `Medium_Desktop`. System images for android-34, 36 and 36.1.
- A headless, read-only emulator boots in about 30 s and leaves the AVD unchanged:
  `emulator -avd Pixel_9_Pro_XL -read-only -no-snapshot -no-window -no-audio -no-boot-anim -port 5584`.
- `adb forward tcp:<host> tcp:8080` plus `curl` reaches the app's server. `am start-foreground-service -n com.aanand.edgeaicore/.InferenceService` starts it.
- Open questions: a model file for testing (size and licence, where to keep it, how to push it to the emulator), whether the GPU or NPU backends work on the emulator, driving the UI (adb input or UI Automator), and signed release builds.

## Acceptance criteria
- [x] The build, install and run steps that work for Claude are documented (commands, prerequisites, timings).
- [x] There is a decision on a test model (which one, where it lives, how it gets onto the device) and on which backends can be tested on an emulator.
- [x] There is a decision on how UI and REST behaviour are exercised, and how evidence (logcat, responses, screenshots) is captured.
- [x] The isolation approach is agreed, so the user's AVDs, devices and app data are never modified.
- [x] There is a documented way to build installable APKs (command, output path, signing, and how they get onto the user's phone), including whether they update the existing install.
- [x] A proposed place in the repo docs for the outcome (for example `docs/standards/testing.md` or a new `docs/` file), to be written in a follow-up.

## Plan

## Implementation notes
### 2026-11-09: environment survey and APK builds
- Host: 62 GB RAM, 194 GB free disk, KVM. build-tools 34 to 36.1 (`apksigner` in `~/Android/Sdk/build-tools/36.1.0/`), `keytool` at `/usr/bin`.
- AVD `Pixel_9_Pro_XL`: API 36 `google_apis_playstore` x86_64, 16 GB RAM, 32 GB data partition, `hw.gpu.mode=auto`.
- Debug build: `./gradlew assembleDebug` takes 8 s from clean and 2 s when nothing changed. Output is `app/build/outputs/apk/debug/app-debug.apk` (29 MB), signed with `~/.android/debug.keystore` (CN=Android Debug).
- Signatures:
  - The current release APK (`app/release/app-release.apk`) is signed by CN=Archit Anand, SHA-256 `15efcd61…c9c4`.
  - A debug APK cannot update an install of a release APK (the signatures differ), so the release must be uninstalled first, which deletes the app's data.
- Keystores in `~/.keystore/`: `edgeaicore.jks` (probably the release key; not opened, since the password is the user's) and `whattodo.jks`.
- Release signing from the CLI works without editing `build.gradle.kts`, using Gradle's injected signing properties (the same mechanism Android Studio uses).
  Tested with a throwaway keystore in `/tmp`, which was then deleted:
  `./gradlew assembleRelease -Pandroid.injected.signing.store.file=<jks> -Pandroid.injected.signing.store.password=<pw> -Pandroid.injected.signing.key.alias=<alias> -Pandroid.injected.signing.key.password=<pw>`
  It took 23 s, wrote `app/build/outputs/apk/release/app-release.apk`, and `apksigner verify` passed (v2 scheme). The test APK was deleted afterwards.
  To be decided: how the user provides the real password without it ending up in shell history or logs (for example a `~/.gradle/gradle.properties` entry or env vars they set themselves).
- Test models:
  - Local: `~/Downloads/model.litertlm` (3.6 GB, model unknown) and `~/Downloads/gemma-3n-E4B-it-int4.litertlm` (4.9 GB).
  - Hugging Face: the `litert-community` Gemma models are gated (licence acceptance and login needed). `litert-community/Qwen3-0.6B` is ungated and Apache-2.0: `Qwen3-0.6B.litertlm` is 614 MB and `Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm` is 343 MB.
    Downloaded `Qwen3-0.6B.litertlm` to `~/.cache/edgeaicore-test-models/` (since deleted).

### 2026-11-09: emulator end-to-end run
Evidence is in `ANDROIDEDGEAICORE-1-evidence/`. The emulator was `Pixel_9_Pro_XL` with `-read-only -no-snapshot -no-window -no-audio -no-boot-anim -port 5584`; it booted in about 30 s.
1. **Install:** `adb -s emulator-5584 install -r app/build/outputs/apk/debug/app-debug.apk`, then `pm grant` for POST_NOTIFICATIONS, RECORD_AUDIO and READ_MEDIA_* (skips the permission dialogs).
2. **Model into app storage** (debug builds only, because it needs `run-as`):
   `adb push <model> /data/local/tmp/`, then `adb shell run-as com.aanand.edgeaicore sh -c 'cat /data/local/tmp/<m> > files/<m>'`, then delete the tmp copy. 3.6 GB took 25 s.
3. **Only multimodal models work.** `AiEngineManager.loadModel` always sets `visionBackend = GPU` and `audioBackend = CPU`, so text-only Qwen3-0.6B fails:
   `NOT_FOUND: TF_LITE_VISION_ENCODER not found in the model`. `~/Downloads/model.litertlm` (3.6 GB, probably Gemma 3n E2B) works. This is a pitfall to add to `docs/pitfalls.md` later; it is not fixed here.
4. **Start without the UI:** `am start-foreground-service -n com.aanand.edgeaicore/.InferenceService --es MODEL_PATH /data/user/0/com.aanand.edgeaicore/files/model.litertlm --es BACKEND CPU`.
   It is ready, with the readiness ping passing, in 24 to 40 s. Stop it with `am start-foreground-service -n .../.InferenceService -a STOP`.
5. **Token:** tap Generate in the API Tokens tab (`adb shell input tap`), then read it with `run-as ... cat shared_prefs/edge_ai_core_tokens_v2.xml`. Keep it in a mode-600 temp file and never print it.
6. **REST over `adb forward tcp:18080 tcp:8080` + curl** (`rest-cpu.log`):
   - `/v1/health` returns 200 `{"status":"ok"}` with a token and 401 without one; `/v1/load` returns 200.
   - A non-streamed chat returns "pong" in 4.2 s.
   - A streamed chat sends 38 SSE events and ends with `[DONE]`, in 7.8 s.
7. **Backends on the emulator** (`backends.log`): CPU works.
   - GPU: "Model loaded successfully" is logged, then the ping fails with `Can not find OpenCL library` and chat returns 500.
   - NPU: `Main backend constraint mismatch. Model requires one of [cpu,gpu]`.
   - Vision (`vision-cpu.log`): 500 `vision_litert_compiled_model_executor ... Failed to invoke`, because vision is hard-wired to GPU.
   - So GPU, NPU, vision (and probably audio) can only be tested on a real phone.
8. **UI driving:** `uiautomator dump` plus `ui_dump.py` (prints text, resource-id and bounds), `input tap`, and `screencap`.
   - A full UI flow worked: seed `shared_prefs/litertlm_prefs.xml` `model_path` with `run-as`, choose CPU in the Backend tab, turn on Enable Server, and the status reads "Model loaded successfully (CPU)" after 37 s (`ui-test-inference.png`).
   - The activity only sees status broadcasts while it is on screen, so a service started from adb leaves the UI showing "Idle".
   - The system file picker was not automated; seeding the prefs replaces it.
   - On first boot "System UI isn't responding" appeared and had to be dismissed (Wait).
9. **Isolation caveat:**
   - `-read-only` should keep AVD changes out of its disk images, but the AVD still carries its earlier state (the app's data dir dated 2026-02-13).
   - `userdata-qemu.img.qcow2` has mtime 2026-11-09 02:21. That is the first read-only boot of the day (the #31 verification), and its contents can't be confirmed unchanged.
   - Recommendation: a dedicated AVD that only Claude uses.

### 2026-11-09: user decisions and follow-through
Decisions:
1. Dedicated AVD: yes.
2. Test model: Gemma 4 E2B IT.
3. Release keystore password is forgotten.
4. Helper script: yes, location left to Claude.
5. `applicationIdSuffix ".debug"`: yes (a follow-up code change, not done here).

**1. Dedicated AVD, done.**
- Installed Android cmdline-tools `16111833` into `~/Android/Sdk/cmdline-tools/latest` (`sdkmanager`, `avdmanager`), plus the system image `system-images;android-34;google_apis;x86_64` (r14).
- Created AVD `claude_edgeaicore_api34` (`pixel_7`, 8 GB RAM, 16 GB data). The user's `Pixel_9_Pro_XL` is no longer used.
- It boots read-only and headless in 30 to 34 s.

**2. Gemma 4 E2B doesn't load in the current app.**
- Got `litert-community/gemma-4-E2B-it-litert-lm` / `gemma-4-E2B-it.litertlm` (2.6 GB, ungated, Apache-2.0) and stored it in `~/.cache/edgeaicore-test-models/`.
- On load, LiteRT-LM `0.9.0-alpha02` aborts natively: `litert_lm_loader.h:92 Check failed: Initialize() is OK (INVALID_ARGUMENT: Unknown model type: tf_lite_end_of_vision)`, then SIGABRT.
  The service's crash-loop guard then blocks the sticky restart ("Detected crash loop").
- Using Gemma 4 needs a newer `com.google.ai.edge.litertlm:litertlm-android`. That is a dependency upgrade, so a follow-up; not done.
- Until then, verification uses `~/Downloads/model.litertlm` (Gemma 3n, 3.6 GB) via `MODEL=...`.
- Also a pitfall for the docs: an unsupported model kills the process instead of raising an error.

**3. Forgotten keystore password.**
- Without it, no new APK can be signed with the release key, so no build can update the installed 2.0.0 over the top. Key rotation (APK Signature Scheme v3) also needs the old key.
- Options for the user:
  - (a) Look for the password in a password manager, an old `gradle.properties`, or Android Studio's saved credentials.
  - (b) Create a new release keystore. Every install must then be uninstalled once (losing tokens and models), and later updates work normally.
- Nothing was done. The keystore file was not opened and no password guessing was attempted.
- For local testing on the phone this doesn't matter: debug builds are signed with the debug key.

**4. Helper script, done** (local branch `feature/device-test-script`, commit `11855f3`, not pushed).
- Lives at `scripts/device.sh`, next to `scripts/check_docs.sh`, so the docs can point to it and it is versioned with the app.
- Commands: `up`, `down`, `boot`, `build`, `install`, `model`, `start`, `stop-service`, `token`, `forward`, `api`, `logs`, `ui`, `tap`, `screenshot`, `help`.
- Defaults: AVD `claude_edgeaicore_api34`, port 5584, model `~/.cache/edgeaicore-test-models/gemma-4-E2B-it.litertlm`, host port 18080, state in `~/.cache/edgeaicore-device` (mode 700, token file mode 600, never printed). `SERIAL=<phone>` targets a real phone.
- `install` also runs `dumpsys deviceidle whitelist +pkg`, because otherwise MainActivity's "run in background?" dialog covers the UI on first launch. `model` creates `files/` and `shared_prefs/` on a fresh install and seeds `litertlm_prefs.model_path`.
- Clean run with `MODEL=~/Downloads/model.litertlm scripts/device.sh up` takes 153 s in total:
  - boot 30 s, then build, install, and a 67 s model push;
  - ready on CPU 33 s later;
  - then the token and the port forward.
- Checks after `up`:
  - `/v1/health` and `/v1/load` return 200 with the token, 401 without.
  - Chat returns "pong" (200).
  - Streaming gives 22 `data:` events ending in `data: [DONE]`.
- Utility commands: `ui`, `tap`, `screenshot` (`device-sh-server.png`) and `logs` work. A missing UI id and an unknown command exit 1 with a message. `down` stops the emulator, removes the forward and deletes the token.

**5. `applicationIdSuffix ".debug"`: approved, not done.** It's a code change in `app/build.gradle.kts` for a follow-up PR. Effect: the debug app (`com.aanand.edgeaicore.debug`) installs alongside the release app.

## Proposal (decisions received 2026-11-09, see above)
**A. Verification while implementing (emulator, CPU):**
1. A dedicated AVD, for example `claude_edgeaicore_api34`: API 34 `google_apis` x86_64 (matches targetSdk 34, no Play Store, lighter), 8 GB RAM, about 16 GB data. Created once with `avdmanager` and used only by Claude, always with `-read-only -no-window`. Your AVDs are never touched.
2. Test model: keep a copy of a multimodal `.litertlm` in a cache outside the repo, for example `~/.cache/edgeaicore-test-models/` (`~/Downloads/model.litertlm` works today). Gemma 3n E2B on Hugging Face is gated, so it needs your licence acceptance and login if a fresh download is wanted.
3. Flow per change: `assembleDebug`, boot, install, `pm grant`, push the model with `run-as`, start the service (adb or the UI flow), take a token from the UI, check REST with curl, check the UI with uiautomator and screencap, then `logcat -d -s <TAGs>`. Evidence goes in the ticket's evidence folder.
4. Optional: a small helper script (not in the repo, or a `scripts/` file if you want it shared) wrapping steps 3 and 4. It's for you to decide, and would be its own change.
5. GPU, NPU, vision and audio need your real phone: USB or `adb pair` wireless debugging; the commands are the same with `-s <serial>`.
   A debug build installs over a release install only after uninstalling, which wipes tokens and models. To run both side by side would need `applicationIdSuffix ".debug"` in `build.gradle.kts`, a code change for a follow-up if you want it.

**B. Installable APKs for your phone:**
1. Quick test build: `./gradlew assembleDebug` gives `app/build/outputs/apk/debug/app-debug.apk` (debug-signed). It cannot update the release install.
2. Real release: `./gradlew assembleRelease` with the `android.injected.signing.*` properties pointing at `~/.keystore/edgeaicore.jks` gives a signed `app/build/outputs/apk/release/app-release.apk` that updates the existing install, if that keystore is the one behind the current release (CN=Archit Anand), which still needs confirming.
   The passwords should come from your `~/.gradle/gradle.properties` (outside the repo), or env vars you set, never typed into a shell I run.
3. Getting it to the phone: `adb install -r <apk>` over USB or wireless debugging, or copy the file to the phone and open it.
4. `docs/release.md` currently says to use the Android Studio wizard; the CLI route can replace or sit next to it.

**C. Where the docs go (follow-up):**
- A new `docs/testing-on-device.md` for the emulator and phone flows.
- `docs/standards/testing.md` links to it.
- The CLI signing route goes in `docs/release.md`.
- The multimodal-only, GPU-vision and UI-broadcast findings go in `docs/pitfalls.md`.

**Decisions needed from you:**
1. OK to create the dedicated AVD?
2. Which test model to standardise on?
3. Is `~/.keystore/edgeaicore.jks` the release key, and how will you supply its password?
4. Helper script: yes or no, and where?
5. Do you want `applicationIdSuffix ".debug"` (a follow-up code change)?

### 2026-11-09: script shipped
- PR #33 `chore: add device test helper script` (base `develop`) merged as `7c3ec00`. Local checks passed (`bash -n`, `check_docs.sh`, `testDebugUnitTest lintDebug`). The worktree was removed and local `develop` synced.

### 2026-11-09: docs shipped
- PR #34 `docs: add device testing guide, CLI signing and runtime pitfalls` (base `develop`) merged as `7d26fd9`:
  - new `docs/testing-on-device.md`;
  - `AGENTS.md` index row;
  - device-check rows in `docs/standards/testing.md` and `review.md`;
  - CLI signing and a "Signing key" section in `docs/release.md`;
  - pitfalls 19 to 25.
- `scripts/check_docs.sh` passes.

## Verification

## Review history

## Log
- 2026-11-09: created
- 2026-11-09: scope extended to installable APK builds; status set to in-progress
- 2026-11-09: survey done; CLI release signing proven with a throwaway key
- 2026-11-09: emulator end-to-end run done (CPU inference over REST and through the UI); findings and proposal recorded; waiting on the user's decisions
- 2026-11-09: decisions received; AVD created; Gemma 4 E2B fails with LiteRT-LM 0.9.0-alpha02; `scripts/device.sh` written and tested (local branch, not pushed)
- 2026-11-09: PR #33 (scripts/device.sh) merged into develop
- 2026-11-09: PR #34 (device testing docs) merged into develop
- 2026-11-09: closed; all acceptance criteria met
