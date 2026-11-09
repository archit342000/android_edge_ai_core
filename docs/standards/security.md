# Security standards

## Threat model

The app serves an LLM over HTTP to other apps. The API token is the only access control, and the server is reachable
beyond the device while it binds all interfaces (`docs/pitfalls.md`). Treat every request as untrusted.

## Tokens

- `TokenManager` holds a single active token. `generateToken` clears all others; keep that rule.
- Tokens are UUIDs stored in SharedPreferences `edge_ai_core_tokens_v2` (private mode) and `filesDir/auth_tokens_backup.json`.
  The user's backup export is plain JSON; the UI must keep saying it is a secret.
- Never log a token or any part of it (existing `take(8)` logs are a known trap). Never show a token in a notification or broadcast.
- Every route checks `isValidToken(extractToken(...))` (wired to `TokenManager.isValidToken`) before doing work. New routes do the same; there are no unauthenticated routes.
  Compare tokens only through `TokenManager`.

## Network

- New server code must not widen exposure. A change to the bind address or port updates `docs/standards/api.md` and the README.
- `usesCleartextTraffic="true"` exists for the local HTTP server; do not add other cleartext endpoints.
- The app makes no outbound calls. Adding one needs the owner's agreement (`AGENTS.md` rule 6).

## Input and output

- Validate request bodies before use: required fields, sensible numeric ranges for `temperature`, `top_p`, `top_k`, and a bounded `messages` list.
  Return `400` with a fixed short message for bad input.
- Image and audio content in messages is base64 from the client; decode defensively and cap sizes.
- Error bodies use `ErrorResponse` with a short message. Do not return exception messages or stack traces (existing ones are a known trap).
- Do not log prompts, message content or model output, even at debug level.

## Files and storage

- Store app data only under `filesDir` or SharedPreferences in `MODE_PRIVATE`. No world-readable files.
- Names from a content picker (`getFileName`) are untrusted: strip path separators and `..` before building a `File(filesDir, name)`.
- Model files are copied into `filesDir`; never load a model from a path supplied over HTTP.

## Android components

- Export a component only when another app must reach it, and protect it with a permission when possible.
  `InferenceService` is currently exported with no permission (known trap); do not add more such components.
- Request only the runtime permissions a feature needs (`checkAndRequestPermissions` in `MainActivity`). New permissions need a stated reason in the PR.
- Broadcasts stay inside the app: `sendStatusBroadcast` sets `setPackage(packageName)`; keep that for any new broadcast.

## Secrets and build

- Keystores (`*.jks`, `*.keystore`) and `local.properties` are gitignored. Never commit signing keys or passwords.
- Dependencies are pinned (`dependencies.md`).
