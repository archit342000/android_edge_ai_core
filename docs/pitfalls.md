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
6. **No KV-cache reuse over REST.** Every request gets a new random `conversationId` and `ttlMs = 0`, so `AiEngineManager`'s reuse check
   (same id, one new message, same sampling parameters) never passes for REST clients. The README's "Smart Session Reuse", custom TTL and crash-recovery
   features belong to the conversation model that REST does not use.
7. *(Removed.)* `usage` now reports real token counts (`TokenUsage`). The number is kept because docs and tickets cite later entries.
8. **`system` messages become user turns.** `AiEngineManager.toLiteRTMessage` maps only `assistant` to a model turn, and the preamble is fixed
   to "You are a helpful assistant." because REST requests set no `systemInstruction`.

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

16. **Stale comment.** a comment in `AiEngineManager.generateConversationResponseAsync` mentions `ConversationManager.saveConversation`, but `AiEngineManager` never calls it.
    Only `ConversationManager` calls its own `saveConversation`.
17. **Two kinds of `ConversationState`.** `InferenceService.kt:142` builds its own state for the readiness ping, outside `ConversationManager`.
18. **One active engine conversation.** `AiEngineManager` keeps a single `activeEngineConversation` behind `inferenceMutex`.
    Requests are serialised, and switching conversations rebuilds the context.

## Models and runtime

19. **Only multimodal models load.** `AiEngineManager.loadModel` always sets `visionBackend = GPU` and `audioBackend = CPU`, so a text-only model fails with
    `TF_LITE_VISION_ENCODER not found in the model`.
20. **Vision is GPU-only.** Image input fails wherever GPU is unavailable (for example on the emulator: `Can not find OpenCL library`), even when the main backend is CPU.
21. **An unsupported model kills the process.** LiteRT-LM aborts natively (SIGABRT) on a model it cannot parse (for example Gemma 4 with `0.9.0-alpha02`:
    `Unknown model type: tf_lite_end_of_vision`; Gemma 4 E2B loads with `0.19.1`). The service's crash-loop guard then refuses the sticky restart until a new model path is sent.
22. **GPU "loads" before it fails.** With `BACKEND=GPU` and no OpenCL, "Model loaded successfully" is logged and the readiness ping fails afterwards.
23. **The UI misses status updates.** `MainActivity` receives status broadcasts only while it is resumed, so a service started from adb or while the app is
    in the background leaves the UI showing "Idle".
24. **First launch asks for battery-optimisation exemption.** The system dialog covers the UI until answered; `scripts/device.sh install` whitelists the app instead.
25. **Debug and release builds share the application id.** A debug APK cannot update a release install (different keys); uninstalling deletes tokens and models.
26. **Coroutines 1.11.0 breaks Ktor request bodies of about 1 MB or more.** Ktor 2.3.12 reads large bodies (image or audio parts) through
    `EventLoopKt.processNextEventInCurrentThread()`, which kotlinx-coroutines 1.11.0 removed. LiteRT-LM 0.19.1 asks for 1.11.0, so `app/build.gradle.kts` forces 1.10.2;
    without the pin such requests fail with `NoSuchMethodError`, reported as HTTP 400 "Failed to convert request body". Route tests do not show it (the Ktor test host
    reads bodies in memory); `CoroutinesPinTest` only guards the pin, so check large bodies on a device after touching Ktor, coroutines or LiteRT-LM versions. Remove the pin when Ktor is upgraded.
