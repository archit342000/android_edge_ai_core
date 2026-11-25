# Pitfalls

Known traps in the current code. Each is recorded, not yet fixed. Fix one only as its own change, and update this file when you do.

## Server and API

1. **The server listens on all interfaces.** `embeddedServer(Netty, port = 8080)` in `OpenAIServer.start` sets no `host`, so Netty binds `0.0.0.0`.
   The README says localhost, but other devices on the same network can reach the port. Only the token protects it.
2. *(Removed.)* Error bodies and SSE error events carry only fixed messages: request checks (`ChatRequestValidator`, `OpenAIToolMapper`) throw fixed, documented texts, and for anything else `OpenAIServer.clientMessage` gives the fixed message of a known exception or a fixed fallback (`Bad request`, `Inference failed`); the detail goes to the log. The number is kept because docs and tickets cite later entries.
3. **"Model not loaded" on chat becomes 400.** `generateConversationResponseAsync` throws `ModelNotLoadedException` (an `IllegalStateException`) when no engine is loaded.
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
15. *(Removed.)* The themes no longer set `windowLightNavigationBar`; the bar icon colours are set in code for every API level. The number is kept because docs and tickets cite later entries.

## Code

16. *(Removed.)* The stale comment in `AiEngineManager` was reworded when the reuse code was rewritten. The number is kept because docs and tickets cite later entries.
17. **Two kinds of `ConversationState`.** `AiEngineManager.verifyReady` builds its own state for the readiness ping, outside `ConversationManager`.
18. **Few cached engine conversations.** `AiEngineManager` keeps up to 2 idle conversations (`ConversationCache`, each with its own KV cache) behind `inferenceMutex`.
    Requests are serialised (at most 4 are held, each waits at most 120 s for the engine, a generation is stopped after 10 min); a third client's conversation evicts the least recently used one, so interleaving more than two clients rebuilds the context each time.

## Models and runtime

19. *(Removed.)* Text-only models load: the vision and audio encoders are created only when the model has them (`ModelCapabilities`), and image or audio input to a model without that encoder is a fixed 400. The number is kept because docs and tickets cite later entries.
20. **Encoder backend support varies by model.** Each encoder runs on a backend the model lists for it (Backend tab: Auto follows the main backend, or force CPU or GPU; an unsupported choice is ignored and the status says so).
    On the emulator (2026-11-09) Gemma 4's vision and audio encoders ran on CPU and answered; Gemma 3n lists only GPU for vision, and its image request still answered on the emulator; the log of that request shows only the XNNPACK (CPU) delegate and no OpenCL error, so the "GPU" vision encoder most likely ran on CPU there (not proven). On the Pixel the GPU vision encoder ran and answered.
    NPU-only encoders are treated as absent. Phone GPU vision is untested.
21. **An unsupported or corrupt model must not reach the engine.** `AiEngineManager.loadModel` first reads the file with `ModelInspector` (LiteRT-LM `ModelInfo`) and rejects what it cannot read,
    what is not an LLM, or what has no text input with `UnsupportedModelException`; the service then shows "The app can't run this file" (an error), clears the crash flag, and keeps the running model with its saved settings
    (status ready, "... The previous model is still running"); with no model loaded it drops the saved path and stays up without one (health 503). The running model is closed only after the new file passed inspection. A random-bytes file and a truncated real model were both rejected this way on `0.19.1` (the process survived). Native aborts inside `Engine.initialize()`
    for a file whose metadata reads fine are still possible (Gemma 4 aborted with `0.9.0-alpha02`: `Unknown model type: tf_lite_end_of_vision`; it loads with `0.19.1`). The crash-loop guard
    stays for those: it refuses the sticky restart until a new model path is sent.
22. **GPU "loads" before it fails.** With `BACKEND=GPU` and no OpenCL, `Engine.initialize()` can fail (the loader then retries on CPU at once) or succeed and fail on the first request (`Can not find OpenCL library`). The service treats both as a GPU failure:
    the readiness ping gets 2 attempts on GPU, then the engine is closed and reloaded on CPU, the log says `Model loaded successfully (CPU, GPU unavailable)` and the status line `Running on CPU, GPU unavailable`; `/v1/health` reports the backend in use.
    Measured on the emulator with LiteRT-LM 0.19.1 (2026-11-09, two runs): the whole fallback took 12 to 27 s and almost all of it is the failing GPU `Engine.initialize()` (10 to 24 s); closing the failed engine took 0 ms, the model inspection 16 to 27 ms and the CPU load under 1 s, so there is no app-side wait to remove (the 115 s once seen on an older build did not reproduce).
    `crashed_during_init` now stays set until the readiness check (and a CPU fallback) has finished, so a native abort during the first request is caught by the crash-loop guard like an abort during the load (checked with `kill -6` in the ping window: the sticky restart was refused, a manual start reset the flag). A non-crash kill in that window (a few seconds) also refuses the next sticky restart; start the service again.
    The saved backend stays GPU, so a restart tries GPU again. A GPU failure after the ping (mid-session) is not handled: it reaches the chat handler as a 500, with no automatic fallback.
23. *(Removed.)* The service keeps its status in `ServerStatusStore` and the activity reads it in `onResume`, so a status reached in the background is shown. The number is kept because docs and tickets cite later entries.
24. *(Removed.)* The battery exemption is asked from an explain card on the Server page once the server is ready, not by a system dialog at
    launch. The number is kept because docs and tickets cite later entries. `scripts/device.sh install` whitelists the app and grants the
    runtime permissions, so automated runs see no explain card.
25. **Debug and release builds share the application id.** A debug APK cannot update a release install (different keys); uninstalling deletes tokens and models.
26. **Coroutines 1.11.0 breaks Ktor request bodies of about 1 MB or more.** Ktor 2.3.12 reads large bodies (image or audio parts) through
    `EventLoopKt.processNextEventInCurrentThread()`, which kotlinx-coroutines 1.11.0 removed. LiteRT-LM 0.19.1 asks for 1.11.0, so `app/build.gradle.kts` forces 1.10.2;
    without the pin such requests fail with `NoSuchMethodError`, reported as HTTP 400 `Invalid request body` (the `NoSuchMethodError` is only in logcat). Route tests do not show it (the Ktor test host
    reads bodies in memory); `CoroutinesPinTest` only guards the pin, so check large bodies on a device after touching Ktor, coroutines or LiteRT-LM versions. Remove the pin when Ktor is upgraded.
27. **A cancelled generation may outlive the inference lock.** A client that disconnects is noticed through the closed connection (a Netty `closeFuture` listener, `NettyDisconnect`) for streaming and non-streaming requests alike, even before the first token, and a stream also through a failed write; either way the generation is cancelled (`cancelProcess()`). After a cancel `AiEngineManager` waits up to `CANCEL_WAIT_MS` (2 s) for the native terminal callback while holding `inferenceMutex`; if it
    never arrives it logs a warning, closes the conversation and releases the lock anyway, so native decoding could then overlap the next request. The 2 s expiry was not seen on a device for a cancel during decoding (it completed in about 50 to 90 ms). A cancel during prompt processing (prefill) is not honoured until prefill ends: with LiteRT-LM 0.19.1 (desktop JVM build, CPU, Gemma 4 E2B, 2026-11-25) the 2 s wait expired every time and logged `stopped=false`, but closing the conversation then waited for the native prefill to finish, so the lock stayed held until the engine was free and the next request did not overlap it. The engine then generated nothing: a dropped request whose full, uncapped reply takes 52.7 s freed the slot after 8.2 s, at the end of prefill. Not yet checked on a phone.
    A stream whose client leaves after the engine finished but before the reply's end was handed to the server is cancelled like any other, so that conversation is closed instead of cached.
