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
7. **`usage` is always zero.** Non-streaming responses send `Usage(0, 0, 0)`.
8. **`system` messages become user turns.** `AiEngineManager.toLiteRTMessage` maps only `assistant` to a model turn, and the preamble is fixed
   to "You are a helpful assistant." because REST requests set no `systemInstruction`.

## Security and logging

9. **Token prefixes are logged.** `ConversationManager.kt:139,151` and `TokenManager.kt:133,142` log `take(8)` of a token. Do not copy this.
10. **`InferenceService` is exported.** `android:exported="true"` in `AndroidManifest.xml` with no permission, so any app can start or bind it.

## Build

11. **Stale AIDL flag.** `buildFeatures { aidl = true }` remains in `app/build.gradle.kts` although `app/src/main/aidl/` was removed in 2.0.0.
12. **ProGuard rules are untested.** `app/proguard-rules.pro` keeps Ktor, Netty, coroutines, Gson and all `com.aanand.edgeaicore` classes, but minify is off.
    Test a minified release build on a device before enabling `isMinifyEnabled`.
13. **No signing config.** `./gradlew assembleRelease` gives an unsigned APK under `app/build/`. Release APKs are signed in Android Studio (`docs/release.md`).
14. **JVM 1.8 and `-Xskip-metadata-version-check`.** The app targets Java 8 bytecode and skips the Kotlin metadata version check (the reason is not recorded; most likely the LiteRT-LM alpha).
    Keep both unless a dependency upgrade is tested on a device.
15. **Existing lint errors are baselined.** `res/values/themes.xml:25` and `res/values-night/themes.xml:25` set `android:windowLightNavigationBar` (API 27) with `minSdk` 26.
    They are recorded in `app/lint-baseline.xml`; moving the item to `values-v27/` fixes them and lets the baseline shrink.

## Code

16. **Stale comment.** `AiEngineManager.kt:171` mentions `ConversationManager.saveConversation`, but `AiEngineManager` never calls it.
    Only `ConversationManager` calls its own `saveConversation`.
17. **Two kinds of `ConversationState`.** `InferenceService.kt:142` builds its own state for the readiness ping, outside `ConversationManager`.
18. **One active engine conversation.** `AiEngineManager` keeps a single `activeEngineConversation` behind `inferenceMutex`.
    Requests are serialised, and switching conversations rebuilds the context.
