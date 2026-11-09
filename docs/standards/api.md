# REST API contract

Implemented in `OpenAIServer.kt` (routes, chunk and model-list classes) and `OpenAIModels.kt` (request, response, `ErrorResponse`).
The README's developer guide is the public version of this contract; change both together.

## Server

- Ktor with Netty on port `8080`, no TLS. JSON through Gson (`ContentNegotiation`, pretty printing).
- Bind address: currently all interfaces (no `host` set, see `docs/pitfalls.md`). Documented intent is localhost only.

## Authentication

Every route needs the `Authorization` header. `extractToken` accepts `Bearer <token>` (case-insensitive prefix) and, for
compatibility, a bare token. Missing or unknown tokens get `401 {"error": "Invalid or missing API key"}`.

## Routes

| Method and path | Success | Errors |
|-----------------|---------|--------|
| `POST /v1/chat/completions` | `200` JSON `ChatCompletionResponse`, or an SSE stream when `stream: true` | `400`, `401`, `500` |
| `GET /v1/models` | `200 {"object": "list", "data": [{"id": "litertlm-model", "object": "model", "created": <s>, "owned_by": "user"}]}` | `401` |
| `GET /v1/health` | `200 {"status": "ok"}` | `401`, `503 {"error": "Model not loaded"}` |
| `GET /v1/load` | `200 {"load": N}` (active chat requests) | `401`, `503` |
| `GET /health` | same as `/v1/health` | same |

## Chat request (`ChatCompletionRequest`)

| Field | Type | Default |
|-------|------|---------|
| `model` | string, echoed back | `"litertlm-model"` |
| `messages` | list of `{role, content}`, required | (none) |
| `temperature` | number | `0.8` |
| `top_p` | number | `0.95` |
| `top_k` | integer | `40` |
| `stream` | boolean | `false` |

`content` is a string or an array of parts: `{"type": "text", "text": ...}`, `{"type": "image_url", "image_url": {"url": "data:image/...;base64,..."}}`,
`{"type": "audio_url", "audio_url": {"url": "data:audio/...;base64,..."}}`. Only base64 data URLs are read.
Role `assistant` maps to a model turn; every other role, including `system`, maps to a user turn. The system prompt is fixed to
"You are a helpful assistant." because REST requests carry no `systemInstruction`.

Each request is independent: the server builds a transient conversation from `messages`, so send the full history every time.

## Responses

Non-streaming: `ChatCompletionResponse` with `id` `chatcmpl-<uuid>`, `object` `chat.completion`, `created` (seconds), one `choices[0]`
with `message.role = "assistant"` and `finish_reason = "stop"`, and `usage` all zeros.

Streaming (`Content-Type: text/event-stream`): one event per token,

```
data: {"id":"chatcmpl-...","object":"chat.completion.chunk","created":...,"model":"...","choices":[{"index":0,"delta":{"content":"Hi"},"finish_reason":null}]}

data: [DONE]
```

On a generation error the stream sends `data: {"error": "..."}` and closes without `[DONE]`.
If the client disconnects mid-stream, the server cancels the generation (`cancelProcess()`), frees the inference slot and the `/v1/load` count drops.

## Errors

| Status | When |
|--------|------|
| `400` | body does not parse, or any other exception before or during a non-streaming generation (including "Model not loaded") |
| `401` | missing or invalid token |
| `500` | the engine reported an error, or returned no text (non-streaming) |
| `503` | model not loaded (`/health`, `/v1/health`, `/v1/load`) |

All error bodies are `ErrorResponse` (`{"error": "<message>"}`).

## Changing the contract

- Additive changes (new optional fields, new routes) are a minor version. Removing or renaming a field or route, or changing a status code, is a major version (`docs/release.md`).
- Keep OpenAI field names (snake_case) so standard clients work.
- Update this file, the README developer guide, `docs/architecture.md` if the flow changes, and add a unit test for the DTO change.
