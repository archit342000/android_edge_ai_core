# Edge AI Core 🚀

**Edge AI Core** is a high-performance Android system service that brings state-of-the-art AI inference directly to your device. Built on **LiteRT-LM (TFLite)**, it provides a secure, private, and extremely low-latency API for local AI processing.

---

## 📥 Download

> **[Download Latest APK (v2.0.0)](app/release/app-release.apk)**


## ✨ Features

- 🧠 **Multi-Modal Support**: Optimized for Text, Vision, and Audio inference.
- ⚡ **Hardware Acceleration**: Seamlessly switch between **CPU**, **GPU**, and **NPU** backends.
- 🌐 **OpenAI Compatible REST API**: Standard `/v1/chat/completions` server running locally on port 8080.
- 🌊 **Streaming Support**: Real-time token generation via Server-Sent Events (SSE).
- 🔄 **Smart Session Reuse**: the KV cache of the previous turn is reused when a request extends it, so multi-turn chats only process the new message.
- 🛡️ **Hardened Security**: Robust API Token management with single-active-token policy.
- 🌌 **32k Context**: Massive context window for processing large documents and long chats.
- ⏳ **Customizable TTL**: Clients can specify custom Time-To-Live for conversations (the stateless REST chat endpoint does not use it).
- 💾 **Crash Recovery**: Conversations are persisted to local storage and reloaded automatically after restarts.

---

## 📱 App Usage Guide

### 1. Initial Setup
1. **Grant Permissions**: Upon first launch, the app will request permissions for **Microphone** (audio inference), **Storage** (loading models), and **Battery Optimization** (to ensure the service stays alive in the background).
2. **Select a Model**: Go to the **Backend Settings** tab. Tap **Select Model** and choose your `.litertlm`, `.bin`, or `.tflite` model file.
3. **Choose Backend**: Select your preferred hardware accelerator (**CPU**, **GPU**, or **NPU**) depending on your device's capabilities.
   Optionally set the **Context size (tokens)** below it (empty = the model's default); smaller values use less memory. It applies the next time the server starts.

### 2. Managing the Service
- **Switch On**: In the **Server** tab, toggle the **Enable AI Server** switch. The status will change to `Loading...` and then `Ready (http://localhost:8080)` once the model is initialized.
- **Diagnostics**: Use the **Test Inference**, **Multi-Turn Test**, **Health Check**, or **Get Load** buttons to verify the model and server status.

### 3. API Token Management
The service requires an API token for all requests.
- **Generate Token**: In the **Tokens** tab, tap **Generate New Token**.
- **Single Active Token**: Generating a new token automatically revokes any previous tokens.
- **Revoke Access**: You can revoke the current token at any time.
- **Backup/Restore**: Securely export your token to a JSON file.

---

## 🛠️ Developer Integration Guide

This section is for developers who want to integrate Edge AI Core into their own apps.

## 1. Connection

The service runs a local HTTP server at `http://localhost:8080`.
All endpoints require the `Authorization` header: `Authorization: Bearer <YOUR_API_TOKEN>`.

## 2. API Endpoints

### Chat Completions (Streaming & Non-Streaming)
**POST** `/v1/chat/completions`

Mimics the OpenAI API.

**Request Body:**
```json
{
  "model": "gemma",
  "messages": [
    {"role": "user", "content": "Hello!"}
  ],
  "stream": true,
  "temperature": 0.8,
  "max_tokens": 256
}
```

Also supported: `max_tokens` / `max_completion_tokens`, `top_p`, `top_k`, `presence_penalty`, `frequency_penalty` and `seed`. Values out of range are rejected with `400` (see `docs/standards/api.md`).

**Tool calling, JSON output and thinking** (supported by models such as Gemma 4):
```json
{
  "messages": [{"role": "user", "content": "Weather in Paris?"}],
  "tools": [{"type": "function", "function": {"name": "get_weather", "description": "Current weather",
             "parameters": {"type": "object", "properties": {"city": {"type": "string"}}, "required": ["city"]}}}],
  "reasoning_effort": "high"
}
```
The reply has `finish_reason: "tool_calls"` and `message.tool_calls`; run the tool yourself and send the call and a `{"role": "tool", "tool_call_id": ..., "content": ...}` message back.
`response_format` (`json_object` or `json_schema`) makes the reply valid JSON; thinking text comes back as `reasoning_content`.

**Response (Streaming):**
Returns a stream of Server-Sent Events (SSE).
```
data: {"id":"...","object":"chat.completion.chunk","choices":[{"delta":{"content":"Hello"}}]}

data: {"id":"...","object":"chat.completion.chunk","choices":[{"delta":{"content":"!"}}]}

data: [DONE]
```

### Health Check
**GET** `/v1/health`
Returns `{"status": "ok", "backend": "CPU"}` if the model is loaded and ready; `backend` is the backend the engine actually runs on (`CPU`, `GPU` or `NPU`). If GPU was selected but unusable, the service falls back to CPU and reports `CPU`.

### Server Load
**GET** `/v1/load`
Returns `{"load": N}` where N is the number of currently active requests.

---

## 3. Integration Examples

### Kotlin (OkHttp)

```kotlin
val client = OkHttpClient()
val request = Request.Builder()
    .url("http://localhost:8080/v1/chat/completions")
    .addHeader("Authorization", "Bearer $apiToken")
    .post(requestBody)
    .build()

val response = client.newCall(request).execute()
// Read stream from response.body?.byteStream()
```

### Python

```python
import requests

url = "http://localhost:8080/v1/chat/completions"
headers = {"Authorization": f"Bearer {api_token}"}
data = {
    "model": "gemma",
    "messages": [{"role": "user", "content": "Tell me a joke."}],
    "stream": True
}

response = requests.post(url, headers=headers, json=data, stream=True)
for line in response.iter_lines():
    if line:
        print(line.decode('utf-8'))
```

---

## 4. Best Practices

1.  **State Management**: The server uses a transient conversation ID for each request to maintain context within the engine, but the REST API itself is stateless. Send the full conversation history in the `messages` array every time; the server reuses its cache automatically when the history extends the previous request.
2.  **System prompt**: put instructions in a `system` message; with none, the model's own default applies (no built-in prompt). A request with only system messages is rejected with 400.
3.  **Reuse**: send the assistant's reply back exactly as received (unchanged text); an edited or trimmed reply, a changed earlier message or different settings make the server rebuild the context (correct, just slower).
4.  **Streaming**: Use `stream: true` for the best user experience, as Large Language Models can take time to generate full responses. Closing a streaming connection stops generation as soon as the next token is written (not during the initial prompt processing), so the next request normally does not wait for it.
5.  **Token usage**: responses carry real `usage` (prompt, completion, total and cached prompt tokens). For streams, send `"stream_options": {"include_usage": true}` to get a final usage chunk before `data: [DONE]`.
6.  **Error Handling**: Check for HTTP status codes (401 for Unauthorized, 503 for Model Not Loaded).

---

## 5. Troubleshooting
    
- **"Model not loaded"**: Ensure the service is started in the Edge AI Core app.
- **"Invalid API token"**: Generate a new token in the app and update your client.
- **Connection Refused**: Ensure your app has internet permissions (though localhost usually works without strictly needing it, `android:usesCleartextTraffic="true"` is required in your manifest if not using HTTPS).
