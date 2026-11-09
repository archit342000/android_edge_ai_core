# Kotlin standards

## Language

- Kotlin 2.4, JVM target 21 (`app/build.gradle.kts`), required by LiteRT-LM 0.19.1. `minSdk` is 26, so Java 9+ library APIs still need core library desugaring; do not use them without it.
- Prefer `val` and immutable collections. Use `data class` for DTOs and state holders.
- Null safety: avoid `!!`. Use `?.`, `?:`, `requireNotNull` or an early return with a clear error. Existing `!!` is not a pattern to copy.
- Use `when` with explicit branches for string options such as the backend (`"CPU"`, `"GPU"`, `"NPU"`); prefer an `enum class` for new option sets.
- Scope functions (`apply`, `let`, `also`) only when they make the code shorter and clearer.

## Naming and layout

- Classes `PascalCase`, functions and properties `camelCase`, constants `UPPER_SNAKE_CASE` in the `companion object`.
- One top-level class per file, named after the file. Small related DTOs may share a file (`OpenAIModels.kt`).
- JSON DTO properties use the wire name (`top_p`, `finish_reason`) so Gson maps them without annotations. Keep that convention.
- Order inside a class: properties, init, public functions, private functions, `companion object` last.

## Coroutines and threading

- Never block the main thread. File, model and network work runs on `Dispatchers.IO`; UI updates switch to `Dispatchers.Main`.
- Use a scope tied to a lifecycle, such as `InferenceService.serviceScope` (`SupervisorJob`, cancelled in `onDestroy`).
  `MainActivity` currently launches on ad-hoc `CoroutineScope(Dispatchers.IO)`; do not add more of those without an owner that cancels them.
- Model inference is serialised by `AiEngineManager.inferenceMutex`; do not call the LiteRT-LM `Engine` or `Conversation` outside it.
- Shared mutable state is guarded (`synchronized(dataLock)` in `TokenManager`, `@Volatile` and the mutex in `AiEngineManager`). Keep new state behind the same guard.
- Bridge callback APIs with `suspendCancellableCoroutine` and handle cancellation (see `generateConversationResponseAsync`).

## Serialisation

- Gson is the only JSON library (Ktor `gson {}` and direct `Gson`/`GsonBuilder`). Do not add kotlinx.serialization or Moshi.
- Fields that may be absent must be nullable or have defaults; Gson does not run Kotlin default constructors reliably, so validate after parsing.
- Reuse one `Gson` instance where a class serialises repeatedly instead of building one per call.

## Formatting

- Official Kotlin style (4-space indent). Keep lines readable (about 140 characters).
- Comments explain why, not what. Remove stale comments when you change the code they describe.
