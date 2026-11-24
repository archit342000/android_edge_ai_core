# Android standards

## Components

| Component | Rules |
|-----------|-------|
| `MainActivity` | UI only. Starts and stops `InferenceService` through intents and talks to the server over HTTP. No inference or token logic beyond calling `TokenManager`. |
| `InferenceService` | Foreground service of type `dataSync` (manifest and `startForeground`). Owns the engine, server, tokens and conversations. Returns `START_STICKY`. |

- Extras and actions are the constants in `InferenceService.companion` (`EXTRA_MODEL_PATH`, `EXTRA_BACKEND`, `ACTION_STOP`, `ACTION_STATUS_UPDATE`, `EXTRA_STATUS`). Add new ones there.
- The service reports status to `ServerStatusStore`, the one owner of the server status (`sendStatusBroadcast`), and then tells the activity by an app-private broadcast (`broadcastStatus` with `setPackage`); the activity reads the kind and detail from the store, never from the broadcast text. `MainActivity` registers its receiver `RECEIVER_NOT_EXPORTED` in `onResume` and unregisters it in `onPause`; follow that pairing.

## Lifecycle

- The service must survive sticky restarts: anything needed to resume goes in `inference_service_prefs` before the work starts.
- Keep the crash-loop guard: set `crashed_during_init` before loading a native model, clear it after success or a handled failure.
- Release native resources (`AiEngineManager.close`, `ConversationManager.shutdown`, `OpenAIServer.stop`) in `onDestroy`.
- Start the service with `startForegroundService` on API 26+ and call `startForeground` promptly in `onStartCommand`.

## Permissions

- Declared in `AndroidManifest.xml`. Runtime ones are asked at the point of use, each after an explain card with "Not now" as an equal choice:
  notifications (API 33+) and the battery exemption once the server is ready (`ExplainCardPolicy`), the microphone from the audio test.
  Files come through document pickers, so the media and storage permissions are not requested.
- Add a permission only for a feature that needs it, request it at the point of use, and handle denial without crashing.
- Battery optimisation exemption is requested so the service stays alive; do not add other special-access requests without the owner.

## UI

- View-based UI with Material Components and `ConstraintLayout`, laid out in `app/src/main/res/layout/`. Do not mix in Jetpack Compose without the owner's agreement.
- The UI follows the Console at Rest design system (the `console-at-rest` repo: rule book, `tokens.json`, reference page). Shared values live in
  `values/tokens_*.xml` and `colors.xml` (checked against a `tokens.json` snapshot by `ConsoleTokensTest`); a piece's own numbers live in
  `pieces.xml` with the reference selector they come from. No raw colour, size, corner, shadow or duration anywhere else.
- Three width frames share three page layouts: `layout/` (bottom bar), `layout-w600dp/` (rail), `layout-w1024dp/` (navigation panel).
- Control state is decided by `ControlsPolicy` and applied only in `MainActivity.render()`; the server status is read from `ServerStatusStore`.
- Tabs are pages whose `visibility` is toggled in `MainActivity`; follow that pattern for a new page.
- All user-visible text is in `res/values/strings.xml` (use `getString`). No hardcoded UI strings in Kotlin or layouts.
- Night mode uses `values-night/`. Theme attributes that need a higher API than `minSdk` 26 go in a `values-v<API>/` folder (lint `NewApi`); system bar icon colours are set in code.
- Work started from the UI that touches files or the network runs off the main thread and reports back on `Dispatchers.Main`.

## SDK levels and build

- `minSdk` 26, `targetSdk` and `compileSdk` 34. Guard newer APIs with `Build.VERSION.SDK_INT` checks.
- Native libraries: `jniLibs.useLegacyPackaging = true` and `android:extractNativeLibs="true"` are needed by LiteRT-LM. Keep the
  optional `uses-native-library` entries (`libOpenCL.so`, `libvndksupport.so`) for GPU support.
- Raising `targetSdk` changes foreground-service and permission rules; treat it as its own change with device testing.
