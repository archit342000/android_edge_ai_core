# Pitfalls

Known traps in the current code. Each is recorded, not yet fixed. Fix one only as its own change, and update this file when you do.

## Server and API

1. **The server listens on all interfaces.** `embeddedServer(Netty, port = 8080)` in `OpenAIServer.start` sets no `host`, so Netty binds `0.0.0.0`.
   The README says localhost, but other devices on the same network can reach the port. Only the token protects it.
2. **Error bodies leak exception text.** The 400 path returns `"Bad Request: ${e.message}"`, and the 500 path and SSE errors return `error.message`.
   New code should return a short fixed message and log the detail (`docs/standards/errors-and-logging.md`).
3. **"Model not loaded" on chat becomes 400.** `generateConversationResponseAsync` throws `IllegalStateException` when no engine is loaded.
   For a non-streaming request the outer `catch` turns it into `400 Bad Request`, not `503`.
4. **`/v1/health` and root `/health` duplicate the same handler.** Change both or neither.
5. **The raw token is accepted without `Bearer `.** `extractToken` strips the prefix if present and otherwise uses the whole header value.
6. *(Removed.)* REST requests now reuse the cached engine conversation when they extend the previous one (`ConversationCache`). The number is kept because docs and tickets cite later entries.
7. *(Removed.)* `usage` now reports real token counts (`TokenUsage`). The number is kept because docs and tickets cite later entries.
8. *(Removed.)* `system` messages are now the system instruction and there is no built-in prompt (`SystemPromptSplitter`). The number is kept because docs and tickets cite later entries.

## Security and logging

9. **Token prefixes are logged.** `ConversationManager.kt:139,151` and `TokenManager.kt:133,142` log `take(8)` of a token. Do not copy this.
10. **`InferenceService` is exported.** `android:exported="true"` in `AndroidManifest.xml` with no permission, so any app can start or bind it.

## Build

11. **Stale AIDL flag.** `buildFeatures { aidl = true }` remains in `app/build.gradle.kts` although `app/src/main/aidl/` was removed in 2.0.0.
12. **Missing ProGuard file.** `buildTypes.release` references `proguard-rules.pro`, which does not exist. Minify is off, so this matters only if someone enables it.
13. **No signing config.** `./gradlew assembleRelease` gives an unsigned APK under `app/build/`. Release APKs are signed in Android Studio (`docs/release.md`).
14. *(Removed.)* The JVM 1.8 target and `-Xskip-metadata-version-check` went away with the LiteRT-LM 0.19.1 upgrade. The number is kept because docs and tickets cite later entries.
15. **Existing lint errors are baselined.** `res/values/themes.xml:25` and `res/values-night/themes.xml:25` set `android:windowLightNavigationBar` (API 27) with `minSdk` 26.
    They are recorded in `app/lint-baseline.xml`; moving the item to `values-v27/` fixes them and lets the baseline shrink.

## Code

16. *(Removed.)* The stale comment in `AiEngineManager` was reworded when the reuse code was rewritten. The number is kept because docs and tickets cite later entries.
17. **Two kinds of `ConversationState`.** `AiEngineManager.verifyReady` builds its own state for the readiness ping, outside `ConversationManager`.
18. **Few cached engine conversations.** `AiEngineManager` keeps up to 2 idle conversations (`ConversationCache`, each with its own KV cache) behind `inferenceMutex`.
    Requests are serialised; a third client's conversation evicts the least recently used one, so interleaving more than two clients rebuilds the context each time.

## Models and runtime

19. *(Removed.)* Text-only models load: the vision and audio encoders are created only when the model has them (`ModelCapabilities`), and image or audio input to a model without that encoder is a fixed 400. The number is kept because docs and tickets cite later entries.
20. **Encoder backend support varies by model.** Each encoder runs on a backend the model lists for it (Backend Settings: Auto follows the main backend, or force CPU or GPU; an unsupported choice is ignored and the status says so).
    On the emulator (2026-11-09) Gemma 4's vision and audio encoders ran on CPU and answered; Gemma 3n lists only GPU for vision, and its image request still answered there (not explained: the main backend on GPU still fails at the first request, pitfall 22).
    NPU-only encoders are treated as absent. Phone GPU vision is untested.
21. **An unsupported or corrupt model must not reach the engine.** `AiEngineManager.loadModel` first reads the file with `ModelInspector` (LiteRT-LM `ModelInfo`) and rejects what it cannot read,
    what is not an LLM, or what has no text input with `UnsupportedModelException`; the service then shows "Error: Unsupported model file", clears the crash flag and the saved model path, and stays
    up without a model (health 503). A random-bytes file and a truncated real model were both rejected this way on `0.19.1` (the process survived). Native aborts inside `Engine.initialize()`
    for a file whose metadata reads fine are still possible (Gemma 4 aborted with `0.9.0-alpha02`: `Unknown model type: tf_lite_end_of_vision`; it loads with `0.19.1`). The crash-loop guard
    stays for those: it refuses the sticky restart until a new model path is sent.
22. **GPU "loads" before it fails.** With `BACKEND=GPU` and no OpenCL, `Engine.initialize()` succeeds and the first request fails (`Can not find OpenCL library`). The service now treats that as a GPU failure:
    the readiness ping gets 2 attempts on GPU, then the engine is closed and reloaded on CPU, and the status says `Model loaded successfully (CPU, GPU unavailable)`; `/v1/health` reports the backend in use.
    On the emulator the whole fallback took about 2 minutes (closing the failed GPU engine was the slow part). The saved backend stays GPU, so a restart tries GPU again. A GPU failure after the ping is not handled, and a native abort during the first GPU request is not covered by the crash-loop guard (the flag is cleared once the model has loaded).
23. **The UI misses status updates.** `MainActivity` receives status broadcasts only while it is resumed, so a service started from adb or while the app is
    in the background leaves the UI showing "Idle".
24. **First launch asks for battery-optimisation exemption.** The system dialog covers the UI until answered; `scripts/device.sh install` whitelists the app instead.
25. **Debug and release builds share the application id.** A debug APK cannot update a release install (different keys); uninstalling deletes tokens and models.
26. **Coroutines 1.11.0 breaks Ktor request bodies of about 1 MB or more.** Ktor 2.3.12 reads large bodies (image or audio parts) through
    `EventLoopKt.processNextEventInCurrentThread()`, which kotlinx-coroutines 1.11.0 removed. LiteRT-LM 0.19.1 asks for 1.11.0, so `app/build.gradle.kts` forces 1.10.2;
    without the pin such requests fail with `NoSuchMethodError`, reported as HTTP 400 "Failed to convert request body". Route tests do not show it (the Ktor test host
    reads bodies in memory); `CoroutinesPinTest` only guards the pin, so check large bodies on a device after touching Ktor, coroutines or LiteRT-LM versions. Remove the pin when Ktor is upgraded.
27. **A dropped stream is noticed only when a token write fails.** `OpenAIServer` cancels the generation (`cancelProcess()`) from the failed write, so a client that leaves during prompt
    processing is noticed at the first token. After a cancel `AiEngineManager` waits up to `CANCEL_WAIT_MS` (2 s) for the native terminal callback while holding `inferenceMutex`; if it
    never arrives it logs a warning, closes the conversation and releases the lock anyway, so native decoding could then overlap the next request. It was not seen on a device (about 50 ms).
