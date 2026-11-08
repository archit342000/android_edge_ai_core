# Errors and logging

## Errors

- Throw specific exceptions (`IllegalArgumentException`, `IllegalStateException`, `FileNotFoundException`, or a project exception). Avoid bare `Exception` in new code.
- Catch only what you can handle at that layer. Otherwise log with context and rethrow. Never an empty `catch`, and never return `null`, `false` or an empty list to hide a failure.
- Do not catch `CancellationException` in coroutines without rethrowing it.
- Inference callbacks: `AiEngineManager.generateConversationResponseAsync` reports failures through `onErrorCallback` and success through `onComplete`.
  Call exactly one of them, once. Callers must handle both.
- HTTP failures map to status codes and an `ErrorResponse` body as listed in `docs/standards/api.md`. Streams send `data: {"error": ...}` and end.
- User-facing messages (toasts, status text, broadcasts) are short and actionable. Internal detail goes to `Log`.

## Logging

Use `android.util.Log` with the class `TAG` constant (`private const val TAG = "<ClassName>"` in the `companion object`).
Do not pass a string literal as the tag (`MainActivity.kt:414,443` do; fix when touched).

| Level | Use for |
|-------|---------|
| `Log.e` | Failures, always with the throwable: `Log.e(TAG, "Inference failed", e)` |
| `Log.w` | Recoverable problems, rejected requests, expired conversations |
| `Log.i` | Lifecycle and state changes: server start and stop, model loaded, conversations loaded |
| `Log.d` | Developer diagnostics: backend chosen, reuse decisions, history sizes |

Conventions:

- Never log tokens or token prefixes, prompts, message content, model output or base64 payloads. Log sizes and counts instead (`length=`, `size=`).
- Conversation ids may be logged truncated (`take(8)`).
- Log at boundaries (request start and end, model load, each failure), not per streamed token.
- The in-app log view (`appendLog` in `MainActivity`) is user-visible; the same rules apply to it.
