# Testing on an emulator or device

How to build the app, install it and exercise it against a real model, so behaviour can be checked while a change is being implemented.
Unit tests and lint are in `docs/standards/testing.md`; this file covers runtime checks. Everything below is wrapped by `scripts/device.sh`.

## One-time setup

| Item | Setup |
|------|-------|
| SDK | `~/Android/Sdk` (or `ANDROID_HOME`) with `platform-tools`, `emulator` and `cmdline-tools/latest` (`sdkmanager`, `avdmanager`) |
| System image | `sdkmanager "system-images;android-34;google_apis;x86_64"` (API 34 matches `targetSdk`; no Play Store, lighter) |
| Test emulator | `avdmanager create avd -n claude_edgeaicore_api34 -k "system-images;android-34;google_apis;x86_64" -d pixel_7`, then set `hw.ramSize=8192` and `disk.dataPartition.size=16G` in `~/.android/avd/claude_edgeaicore_api34.avd/config.ini` |
| Test model | a `.litertlm` in `~/.cache/edgeaicore-test-models/` (see "Models"; Gemma 4 E2B for multimodal, Qwen3-0.6B for text only) |
| Host | KVM (`/dev/kvm`) for emulator speed |

Use only the dedicated `claude_edgeaicore_api34` emulator for automated runs; never the owner's own emulators or app data.
`scripts/device.sh boot` starts it with `-read-only -no-snapshot`, so nothing is written back and every boot starts clean.

## Quick start

```bash
MODEL=~/path/to/model.litertlm scripts/device.sh up      # boot, build, install, copy model, start on CPU, token, forward
scripts/device.sh api GET /v1/health                       # {"status": "ok", "backend": "CPU"} [HTTP 200]
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
| Start | `start [CPU\|GPU\|NPU]` | `am start-foreground-service` with `MODEL_PATH` and `BACKEND` extras (and `SPECULATIVE_DECODING` from `SPEC`, if set); waits for `Model loaded successfully (<backend in use>)` in logcat; `Ping failed` lines are retries, not errors |
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
| GPU | no OpenCL: the service falls back to CPU (`Model loaded successfully (CPU, GPU unavailable)`, health `backend: CPU`, 12 to 27 s) | works (Pixel 9 Pro XL, Gemma 4 E2B: engine ready in 12 to 49 s, no fallback, vision on GPU, audio on CPU) |
| NPU | fails unless the model is built for the device's NPU | Gemma 4 E2B (not NPU-built) fails with a handled "Failed to initialize"; the process survives and `crashed_during_init` stays false. No NPU-built model was available, so NPU success and NPU-only encoders are untested |
| Vision input | runs on the encoder backend the model lists (Auto follows the main backend; see the model table) | GPU vision answered (a red image gave "Red") |

Models: text-only and multimodal `.litertlm` models load (encoders only when the model has them, see `docs/pitfalls.md`). Known results with LiteRT-LM `0.19.1` (CPU, emulator):

| Model | Result |
|-------|--------|
| Gemma 3n E2B IT int4, image and audio | vision GPU (the model lists only GPU) and audio CPU: image answered "Red" and audio answered with a description of the tone (emulator, 2026-11-09), although the main backend on GPU fails at the first request |
| Gemma 3n E2B IT int4 (3.6 GB) | loads on CPU, chat and streaming work (retested on `0.19.1`, emulator, 2026-11-09) |
| `litert-community/Qwen3-0.6B` (text only, 614 MB) | loads on CPU, chat works; image and audio input give a 400 (emulator, 2026-11-09; failed with `TF_LITE_VISION_ENCODER not found` on `0.9.0-alpha02`) |
| `litert-community/gemma-4-E2B-it-litert-lm` (2.6 GB), image and audio | vision CPU and audio CPU: image (a generated red PNG) answered "Red", audio (a generated 440 Hz WAV) answered (emulator, 2026-11-09). Chat as below. |
| `litert-community/gemma-4-E2B-it-litert-lm` (2.6 GB) | loads on CPU, chat works (emulator `claude_edgeaicore_api34`, 2026-11-09; aborted natively with `0.9.0-alpha02`) |

A corrupt file (random bytes) or a truncated model is rejected before the engine is created: the log shows `ModelInspector: Model metadata could not be read`, the process survives, `/v1/health` is 503, and a valid model loads afterwards (checked on the emulator, 2026-11-09).

Speculative decoding (MTP, `SPEC=OFF|ON|ADAPTIVE scripts/device.sh start`; the app's setting is Adaptive by default): Gemma 4 E2B has a drafter, Gemma 3n does not
(the log says `Speculative decoding off (unsupported model)`). Decode speed on the emulator CPU (same prompt, temperature 0, 3 runs, 2026-11-09; rate = (completion tokens - 1) / (time of the last content chunk - the first), each run generated 891 tokens):

| Mode | tok/s | PSS |
|------|-------|-----|
| Off | 22.9 | 1.55 GB |
| On | 33.0 | 1.62 GB |
| Adaptive | 32.2 | 1.63 GB |

MTP decode speed on the phone was waived by the owner and is not measured.

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

## Verified behaviour

Checked on 2026-11-10 on the merged engine branches: the emulator (`claude_edgeaicore_api34`, Gemma 4 E2B and Gemma 3n on CPU) and a Pixel 9 Pro XL (Tensor G4, debug build, Gemma 4 E2B). Process kills use `run-as <package> kill -9`, which triggers the sticky restart.

| Check | Emulator | Phone |
|-------|----------|-------|
| Context size, MTP and encoder settings survive a process kill | restored (MTP off, vision CPU, context passed) | restored (`maxNumTokens=2048`, MTP off, vision CPU) |
| Sticky restart after a rejected model while a model runs | previous model restored, flag false, health ok | same |
| Cached tokens on a reused conversation | `cached_tokens` 320 then 339 (usage run) | 17 on turn 2 (GPU) |
| `enableBenchmark` latency cost | no measurable overhead (measured earlier) | not repeated |
| Non-streaming client disconnect | generation stopped in 46 to 88 ms, load 0 | stopped in 81 ms, load 0 |
| Client drops during a long prefill (5 times) | no cancel logged, load 0, next request 200 (the drop is noticed only at the first token write, pitfall 27) | same; the cancel wait expiry (2 s) was never reached |
| Memory, 2 cached conversations, MTP adaptive | 1.8 GB PSS (CPU) | 3.67 GB PSS (2.6 GB graphics), GPU |
| GPU to CPU fallback | triggered, 12 to 27 s | not reachable: the phone GPU works |
| Gemma 3n image request with vision on GPU, main CPU | answered; the log shows only the XNNPACK (CPU) delegate and no OpenCL error | not run |
| NPU | not applicable | fails cleanly with a non-NPU model (see the backend table) |
