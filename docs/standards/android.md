# Android standards

## Components

| Component | Rules |
|-----------|-------|
| `MainActivity` | UI only. Starts and stops `InferenceService` through intents and talks to the server over HTTP. No inference or token logic beyond calling `TokenManager`. |
| `InferenceService` | Foreground service of type `dataSync` (manifest and `startForeground`). Owns the engine, server, tokens and conversations. Returns `START_STICKY`. |

- Extras and actions are the constants in `InferenceService.companion` (`EXTRA_MODEL_PATH`, `EXTRA_BACKEND`, `ACTION_STOP`, `ACTION_STATUS_UPDATE`, `EXTRA_STATUS`). Add new ones there.
- Status goes from the service to the activity by an app-private broadcast (`sendStatusBroadcast` with `setPackage`). `MainActivity` registers its receiver `RECEIVER_NOT_EXPORTED` in `onResume` and unregisters it in `onPause`; follow that pairing.

## Lifecycle

- The service must survive sticky restarts: anything needed to resume goes in `inference_service_prefs` before the work starts.
- Keep the crash-loop guard: set `crashed_during_init` before loading a native model, clear it after success or a handled failure.
- Release native resources (`AiEngineManager.close`, `ConversationManager.shutdown`, `OpenAIServer.stop`) in `onDestroy`.
- Start the service with `startForegroundService` on API 26+ and call `startForeground` promptly in `onStartCommand`.

## Permissions

- Declared in `AndroidManifest.xml`; runtime ones are requested in `MainActivity.checkAndRequestPermissions` with API-level branches
  (`READ_MEDIA_*` and `POST_NOTIFICATIONS` on 33+, `READ_EXTERNAL_STORAGE` below).
- Add a permission only for a feature that needs it, request it at the point of use, and handle denial without crashing.
- Battery optimisation exemption is requested so the service stays alive; do not add other special-access requests without the owner.

## UI

- View-based UI with Material Components and `ConstraintLayout`, laid out in `app/src/main/res/layout/`. Do not mix in Jetpack Compose without the owner's agreement.
- Tabs are pages whose `visibility` is toggled in `MainActivity`; follow that pattern for a new page.
- All user-visible text is in `res/values/strings.xml` (use `getString`). No hardcoded UI strings in Kotlin or layouts.
- Night mode uses `values-night/`. Theme attributes that need a higher API than `minSdk` 26 go in a `values-v<API>/` folder (`docs/pitfalls.md`, lint `NewApi`).
- Work started from the UI that touches files or the network runs off the main thread and reports back on `Dispatchers.Main`.

## SDK levels and build

- `minSdk` 26, `targetSdk` and `compileSdk` 34. Guard newer APIs with `Build.VERSION.SDK_INT` checks.
- Native libraries: `jniLibs.useLegacyPackaging = true` and `android:extractNativeLibs="true"` are needed by LiteRT-LM. Keep the
  optional `uses-native-library` entries (`libOpenCL.so`, `libvndksupport.so`) for GPU support.
- Raising `targetSdk` changes foreground-service and permission rules; treat it as its own change with device testing.
