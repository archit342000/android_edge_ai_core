# Testing on an emulator or device

How to build the app, install it and exercise it against a real model, so behaviour can be checked while a change is being implemented.
Unit tests and lint are in `docs/standards/testing.md`; this file covers runtime checks. Everything below is wrapped by `scripts/device.sh`.

## One-time setup

| Item | Setup |
|------|-------|
| SDK | `~/Android/Sdk` (or `ANDROID_HOME`) with `platform-tools`, `emulator` and `cmdline-tools/latest` (`sdkmanager`, `avdmanager`) |
| System image | `sdkmanager "system-images;android-34;google_apis;x86_64"` (API 34 matches `targetSdk`; no Play Store, lighter) |
| Test emulator | `avdmanager create avd -n claude_edgeaicore_api34 -k "system-images;android-34;google_apis;x86_64" -d pixel_7`, then set `hw.ramSize=8192` and `disk.dataPartition.size=16G` in `~/.android/avd/claude_edgeaicore_api34.avd/config.ini` |
| Test model | a multimodal `.litertlm` in `~/.cache/edgeaicore-test-models/` (see "Models") |
| Host | KVM (`/dev/kvm`) for emulator speed |

Use only the dedicated `claude_edgeaicore_api34` emulator for automated runs; never the owner's own emulators or app data.
`scripts/device.sh boot` starts it with `-read-only -no-snapshot`, so nothing is written back and every boot starts clean.

## Quick start

```bash
MODEL=~/path/to/model.litertlm scripts/device.sh up      # boot, build, install, copy model, start on CPU, token, forward
scripts/device.sh api GET /v1/health                       # {"status": "ok"} [HTTP 200]
scripts/device.sh api POST /v1/chat/completions '{"messages":[{"role":"user","content":"Say hi"}],"stream":true}'
scripts/device.sh logs > logcat.txt; scripts/device.sh screenshot shot.png
scripts/device.sh down                                     # remove forward, stop emulator, delete token
```

`scripts/device.sh help` lists every command and environment variable. A clean `up` takes about 2.5 minutes:
boot about 30 s, build and install a few seconds, model copy about 70 s for 3.6 GB, model ready about 33 s.

## What each step does

| Step | Command | Notes |
|------|---------|-------|
| Boot | `boot` | Headless, read-only, console port 5584 (`emulator-5584`); animations turned off |
| Build | `build` | `./gradlew assembleDebug` gives `app/build/outputs/apk/debug/app-debug.apk` |
| Install | `install` | `adb install -r`, grants runtime permissions, whitelists the app from battery optimisation so MainActivity's "run in background?" dialog does not cover the UI |
| Model | `model [FILE]` | `adb push` to `/data/local/tmp`, then `run-as` copies it into the app's `files/` (debug builds only), and seeds `litertlm_prefs` `model_path` so the UI flow needs no file picker |
| Start | `start [CPU\|GPU\|NPU]` | `am start-foreground-service` with `MODEL_PATH` and `BACKEND` extras; waits for the readiness ping in logcat |
| Token | `token` | Taps Generate in the API Tokens tab, reads the token with `run-as` into `~/.cache/edgeaicore-device/token` (mode 600). Never print it. |
| Forward | `forward` | `adb forward tcp:18080 tcp:8080`; the API is then `http://127.0.0.1:18080` |
| Stop | `stop-service` | Sends `ACTION_STOP` |

## Exercising behaviour

- **REST:** `api METHOD PATH [JSON]` sends the saved token and prints the body plus `[HTTP nnn]`. Check failure paths too (no token gives 401).
- **UI:** `ui` lists on-screen text, ids and bounds; `tap ID` taps by resource id (`nav_server`, `nav_backend`, `nav_tokens`, `switch_enable_server`, `btn_test_inference`, ...).
  For a full UI flow: `model`, then `tap nav_backend`, `tap rb_cpu`, `tap nav_server`, `tap switch_enable_server`, and poll `ui` until `tv_status` shows "Model loaded".
- **Evidence:** `logs` (app tags only), `screenshot FILE`, and the `api` output. Put them with the plan or ticket for the change, not in the repo.

## Backends and models

| Backend | Emulator | Phone |
|---------|----------|-------|
| CPU | works | works |
| GPU | fails: `Can not find OpenCL library` | test on the phone |
| NPU | fails unless the model is built for the device's NPU | test on the phone |
| Vision input | fails (vision is hard-wired to GPU) | test on the phone |

Models: the app loads only **multimodal** `.litertlm` models (see `docs/pitfalls.md`). Known results with LiteRT-LM `0.19.1` (CPU, emulator):

| Model | Result |
|-------|--------|
| Gemma 3n E2B IT int4 (3.6 GB) | loads on CPU, chat and streaming work (retested on `0.19.1`, emulator, 2026-11-09) |
| `litert-community/Qwen3-0.6B` (text only) | `TF_LITE_VISION_ENCODER not found` (result with `0.9.0-alpha02`; not retested with `0.19.1`) |
| `litert-community/gemma-4-E2B-it-litert-lm` (2.6 GB) | loads on CPU, chat works (emulator `claude_edgeaicore_api34`, 2026-11-09; aborted natively with `0.9.0-alpha02`) |

A corrupt file (random bytes) or a truncated model is rejected before the engine is created: the log shows `ModelInspector: Model metadata could not be read`, the process survives, `/v1/health` is 503, and a valid model loads afterwards (checked on the emulator, 2026-11-09).

`litert-community` Gemma 3 and 3n repositories on Hugging Face are gated (licence acceptance and login); Gemma 4 and Qwen are not.

## Using a phone

Connect over USB, or pair with `adb pair` for wireless debugging, find the serial with `adb devices`, then run the same commands with `SERIAL=<serial>`
(skip `boot` and `down`). `model` and `token` use `run-as`, so they need the debug build. A debug build cannot update a release install (different signing keys);
uninstall the release first, which deletes its tokens and models.

## Installable APKs

| APK | Command | Output | Installs over the release app? |
|-----|---------|--------|-------------------------------|
| Debug | `./gradlew assembleDebug` | `app/build/outputs/apk/debug/app-debug.apk` | no (debug key) |
| Release | see `docs/release.md` (Android Studio or CLI signing) | `app/release/` or `app/build/outputs/apk/release/` | yes, if signed with the release key |

Install with `adb install -r <apk>` (USB or wireless debugging), or copy the APK to the phone and open it.
