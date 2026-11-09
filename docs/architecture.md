# Architecture

Edge AI Core is a single-module Android app (`:app`, package `com.aanand.edgeaicore`) that runs an on-device LLM with
LiteRT-LM and exposes it to other apps on the device through an OpenAI-compatible REST server on port 8080.

## Build facts

| Item | Value (source) |
|------|----------------|
| Gradle / AGP / Kotlin | 9.3.1 (`gradle/wrapper/gradle-wrapper.properties`) / 9.0.0 / 2.4.21 (`build.gradle.kts`) |
| SDK levels | compileSdk and targetSdk 34, minSdk 26 (`app/build.gradle.kts`) |
| JVM target | 21 (`app/build.gradle.kts`); building needs JDK 21 |
| Key libraries | LiteRT-LM `litertlm-android`, Ktor server (Netty), Gson (`app/build.gradle.kts`) |

## Components

All sources are in `app/src/main/java/com/aanand/edgeaicore/`.

| Class | Role |
|-------|------|
| `MainActivity` | The whole UI: Server, Backend Settings and Tokens tabs, model picker, diagnostics buttons (test inference, multi-turn, audio, vision, health, load), token backup and restore. Talks to the server over HTTP like any client. |
| `InferenceService` | Foreground service (`dataSync`). Owns `AiEngineManager`, `TokenManager`, `ConversationManager` and `OpenAIServer`. Loads the model, runs a readiness ping, broadcasts status (`ACTION_STATUS_UPDATE`), and guards against crash loops during model load. |
| `AiEngineManager` | Wraps an `LlmEngine` (the LiteRT-LM `Engine` in production). Loads a model on CPU, GPU or NPU, holds one active engine `Conversation`, serialises inference with a `Mutex`, and streams tokens through callbacks. |
| `LlmEngine` | `LlmEngine` and `LlmConversation` interfaces over the LiteRT-LM `Engine` and `Conversation`, plus the thin adapters and `createLiteRtEngine`. The seam that lets `AiEngineManager` be tested with a fake engine. |
| `OpenAIServer` | Ktor `embeddedServer(Netty, port = 8080)` with the `/v1` routes and a root `/health`. Authenticates every request through an `isValidToken` function (`TokenManager.isValidToken`); `module()` installs JSON and the routes so route tests can host it. |
| `OpenAIModels` | Gson data classes for requests, responses and `ErrorResponse`. The streaming chunk and model-list classes live at the bottom of `OpenAIServer.kt`. |
| `TokenManager` | Singleton (`getInstance`). Single-active-token store in SharedPreferences `edge_ai_core_tokens_v2`, mirrored to `filesDir/auth_tokens_backup.json`. |
| `ConversationManager` | In-memory conversations with TTL and a cleanup task, persisted as files under `filesDir/conversations`. |

## Request flow

```
client app ──HTTP :8080──> OpenAIServer
   1. extractToken(Authorization) -> TokenManager.isValidToken      (401 if invalid)
   2. call.receive<ChatCompletionRequest>()                          (400 on parse error)
   3. SystemPromptSplitter: system/developer messages -> the system instruction (400 if no other message is left);
      new transient ConversationState(ttlMs = 0, random UUID, systemInstruction)
   4. AiEngineManager.generateConversationResponseAsync(state, messages, onToken, onComplete, onError)
   5. stream=true  -> SSE chunks "data: {...}\n\n" ... "data: [DONE]"
      stream=false -> one ChatCompletionResponse (JSON)
```

`OpenAIServer` does not use `ConversationManager`. Every REST request gets a fresh conversation id, so the engine
rebuilds its context from the `messages` array each time (see `docs/pitfalls.md`).

## Ownership and lifecycle

- `MainActivity` starts `InferenceService` with `EXTRA_MODEL_PATH` and `EXTRA_BACKEND`. Stop sends `ACTION_STOP`, which clears the service prefs, deletes all conversations and stops the server.
- `InferenceService.onCreate` builds `TokenManager`, `ConversationManager` (its removal callback closes the matching engine conversation) and `OpenAIServer`.
  `onStartCommand` starts the server, goes foreground, loads the model and returns `START_STICKY`.
  On a sticky restart it restores the model path from `inference_service_prefs`, and refuses to start if `crashed_during_init` is set.
- `InferenceService` alone owns `ConversationManager` (create, `deleteAllConversations`, `shutdown`).
- `onDestroy` cancels coroutines, shuts down conversations, closes the engine and stops the server. Tokens survive restarts.

## Storage

| Data | Location |
|------|----------|
| Copied model file | `filesDir/<name>`, path in SharedPreferences `litertlm_prefs` (`MainActivity`) |
| Service restart state | SharedPreferences `inference_service_prefs` |
| API tokens | SharedPreferences `edge_ai_core_tokens_v2` + `filesDir/auth_tokens_backup.json` |
| Conversations | `filesDir/conversations/` |
| What's New entries | `app/src/main/assets/changelog.json` |

## Boundaries

- Inference runs only on the device through LiteRT-LM. There is no cloud backend.
- The server is the only integration surface for other apps. The old AIDL interface was removed in 2.0.0.
