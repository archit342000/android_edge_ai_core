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
| `GET /v1/health` | `200 {"status": "ok", "backend": "<CPU|GPU|NPU>"}` | `401`, `503 {"error": "Model not loaded"}` |
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
| `max_tokens` | integer >= 1, caps the reply length in tokens | model default |
| `max_completion_tokens` | integer >= 1; the newer name, wins over `max_tokens` | model default |
| `presence_penalty` | number, -2 to 2 | none |
| `frequency_penalty` | number, -2 to 2 | none |
| `seed` | integer; the same seed with the same request gives the same reply (best effort, per backend) | `0` |
| `tools` | list of `{type: "function", function: {name, description, parameters}}`, at most 32; `parameters` is a JSON schema | none |
| `tool_choice` | `"auto"` (offer the tools) or `"none"` (answer in text); anything else is a 400 | `"auto"` |
| `response_format` | `{type: "text"}`, `{type: "json_object"}` or `{type: "json_schema", json_schema: {name, schema}}` (schema object up to 16 KB); constrained decoding | none |
| `reasoning_effort` | `none` turns thinking off; `minimal`, `low`, `medium`, `high`, `xhigh` turn it on with a thinking-token budget of 256, 1024, 4096, 16384 or unlimited (advisory: Gemma 4 E2B on CPU produced the same amount of thinking for `low` and `xhigh`) | the model's default |
| `n` | must be `1` (or absent); anything else is a 400 | `1` |
| `stream_options` | `{"include_usage": boolean}`; with `stream: true`, `include_usage: true` adds a final usage chunk | none |

Ranges are checked before generation: `messages` has 1 to 256 items, `temperature` is 0 to 2, `top_p` is above 0 and at most 1, `top_k` is at least 1, the
penalties are -2 to 2 and the token limits at least 1. Anything outside gives `400 {"error": "<field> must be ..."}` (a fixed message naming the field, never the value).
The context size (KV cache, input plus output tokens) is not a request field: it is set in the app (Backend Settings, "Context size (tokens)", empty = model default)
and applied when the server starts.

`content` is a string or an array of parts: `{"type": "text", "text": ...}`, `{"type": "image_url", "image_url": {"url": "data:image/...;base64,..."}}`,
`{"type": "audio_url", "audio_url": {"url": "data:audio/...;base64,..."}}`. Only base64 data URLs are read.
Role `assistant` maps to a model turn (its `content` may be `null` when it only carries `tool_calls`); role `tool` is a tool result and needs the `tool_call_id` of an
earlier assistant `tool_calls` entry (an unknown id is a 400). `system` (and OpenAI's `developer`) messages are not turns: all of them, wherever they appear, are merged in order
(blank line between) into the conversation's system instruction. Every other role maps to a user turn. With no system message the model's default is used (no built-in
prompt). A request with no message left after the system messages (only system messages, or an empty `messages` array) is rejected with `400 {"error": "messages must include a non-system message"}`.

**Tools** are run by the client, never on the device: when the model decides to call one, the response has `finish_reason: "tool_calls"` and
`message.tool_calls[]` (`id` `call_<uuid>`, `type` `function`, `function.name`, `function.arguments` as a JSON string). Send the result back as an assistant message
with those `tool_calls` plus a `tool` message with the same `tool_call_id`. Tool, response-format and reasoning settings are part of the conversation-reuse key.
`tools` and `response_format` cannot be combined (the engine would constrain the tool-call turn to the schema): that is a 400. Tool-call arguments and tool results are limited to 16 KB each.
Invalid tools, tool calls, schemas or `reasoning_effort` values give a 400 with a fixed message. Support depends on the model (tested with Gemma 4 E2B); the app does not refuse `tools` or `reasoning_effort` up front because a model's metadata can say it supports neither while it handles both (Gemma 4 E2B does), so a model that cannot do them fails like any engine error.

Each request is independent: send the full history every time. The server reuses its cached engine conversation when your `messages` extend the previous
request exactly (same settings, the earlier messages and the assistant reply unchanged, as text), so only the new message is prefilled; otherwise it rebuilds
the context from `messages`. The response never depends on whether the cache was used.

## Responses

Non-streaming: `ChatCompletionResponse` with `id` `chatcmpl-<uuid>`, `object` `chat.completion`, `created` (seconds), one `choices[0]`
with `message.role = "assistant"` and `finish_reason = "stop"` (`"length"` when the reply reached `max_tokens`/`max_completion_tokens`; `"tool_calls"` when the model called a tool, then `content` is omitted when empty and
`message.tool_calls` is set), `message.reasoning_content` when thinking produced text (separate from `content`), and `usage`: `prompt_tokens`, `completion_tokens`, `total_tokens`
and `prompt_tokens_details.cached_tokens` (prompt tokens already in the conversation's KV cache; they are included in `prompt_tokens`).
The counts come from the engine (LiteRT-LM benchmark info), not from re-tokenising text. If the engine cannot report them the response is `500 {"error": "Token usage unavailable"}`.

Streaming (`Content-Type: text/event-stream`): one event per token,

```
data: {"id":"chatcmpl-...","object":"chat.completion.chunk","created":...,"model":"...","choices":[{"index":0,"delta":{"content":"Hi"},"finish_reason":null}]}

data: [DONE]
```

Streams end with a chunk with an empty `delta` and `finish_reason` `"stop"` (or `"tool_calls"`) before the optional usage chunk and `[DONE]`. Thinking text arrives as `delta.reasoning_content`
chunks, and tool calls as one `delta.tool_calls` chunk (each with `index`, `id`, `function`).

With `stream_options.include_usage: true` the last event before `[DONE]` is a chunk with `"choices": []` and the `usage` object; without it no
chunk carries `usage`.

On a generation error the stream sends `data: {"error": "..."}` and closes without `[DONE]`.
If the client disconnects mid-stream, the server cancels the generation (`cancelProcess()`), frees the inference slot and the `/v1/load` count drops.

## Unsupported or ignored

Accepted but ignored (clients such as the OpenAI SDKs send them by default): `stop` (no stop sequences), `logit_bias`, `parallel_tool_calls` (the server returns every call the model emits).
Not supported: `n` other than 1 (400), `tool_choice` naming a function (400: the engine cannot force a call), `regex` response formats, `reasoning_tokens` in `usage` (the engine gives no thinking count),
cancelling a non-streaming request when the client disconnects, request timeouts and queue limits (requests wait for the inference lock without a limit).
`json_schema` output may contain properties the schema does not list unless the schema sets `additionalProperties: false`.
The context size set in the app is clamped to the model's maximum when the model reports a fixed one (the status line says so).

## Errors

| Status | When |
|--------|------|
| `400` | body does not parse, a field is out of range (see Chat request), an `image_url` part for a model without a vision encoder or an `audio_url` part for a model without an audio encoder (`Model does not support image input` / `... audio input`), the request has no non-system message (only `system`/`developer` messages, or an empty `messages` array), or any other exception before or during a non-streaming generation (including "Model not loaded") |
| `401` | missing or invalid token |
| `500` | the engine reported an error, returned no text (non-streaming), or its token usage could not be read (`Token usage unavailable`) |
| `503` | model not loaded (`/health`, `/v1/health`, `/v1/load`) |

All error bodies are `ErrorResponse` (`{"error": "<message>"}`).

## Changing the contract

- Additive changes (new optional fields, new routes) are a minor version. Removing or renaming a field or route, or changing a status code, is a major version (`docs/release.md`).
- Keep OpenAI field names (snake_case) so standard clients work.
- Update this file, the README developer guide, `docs/architecture.md` if the flow changes, and add a unit test for the DTO change.
