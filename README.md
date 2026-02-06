# Edge AI Core - Developer Integration Guide

This application provides a system-wide AI inference service via **AIDL (Android Interface Definition Language)** and a local **OpenAI-compatible HTTP Server**. 

Starting from **v1.4.0**, Edge AI Core **only supports stateful, session-based inference**. This ensures high performance through KV cache reuse and robust resource management.

---

## 1. Project Configuration

### AIDL Definition
1. Create a directory in your project: `app/src/main/aidl/com/aanand/edgeaicore/`.
2. Create `IInferenceService.aidl` and `IInferenceCallback.aidl` inside that directory.

**IInferenceService.aidl**:
```aidl
package com.aanand.edgeaicore;

import com.aanand.edgeaicore.IInferenceCallback;

interface IInferenceService {
    // =====================
    // Token Management
    // =====================
    String generateApiToken();
    boolean revokeApiToken(String apiToken);

    // =====================
    // Session Management
    // =====================
    String startSession(String apiToken, long ttlMs);
    String closeSession(String apiToken, String sessionId);
    String getSessionInfo(String apiToken, String sessionId);

    // =====================
    // Inference (Session-Based)
    // =====================
    String generateResponseWithSession(String apiToken, String sessionId, String jsonRequest);
    void generateResponseAsyncWithSession(String apiToken, String sessionId, String jsonRequest, IInferenceCallback callback);
}
```

**IInferenceCallback.aidl**:
```aidl
package com.aanand.edgeaicore;

interface IInferenceCallback {
    void onToken(String token);
    void onComplete(String fullResponse);
    void onError(String error);
}
```

### Manifest Permissions
Ensure your client application has visibility into the Edge AI Core service (required for Android 11+). Add this to your `AndroidManifest.xml`:

```xml
<queries>
    <package android:name="com.aanand.edgeaicore" />
</queries>
```

---

## 2. Integration Workflow

### 1. Bind to Service
The service runs as a **Bound Service**. Establish a connection before sending requests.

```kotlin
val intent = Intent().apply {
    component = ComponentName("com.aanand.edgeaicore", "com.aanand.edgeaicore.InferenceService")
}
context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
```

### 2. Obtain an API Token
All inference calls require an API token. 

```kotlin
// This triggers a manual approval request in the Edge AI Core app
val result = aiService?.generateApiToken() 

if (result == "PENDING_USER_APPROVAL") {
    // Instruct the user to open Edge AI Core and approve the request
} else {
    // "result" is your permanent API Token
}
```

### 3. Start a Session
Sessions maintain the conversation state and optimize performance via KV cache reuse.

```kotlin
// ttlMs = 0 for default 30-minute timeout
val sessionJson = aiService?.startSession(apiToken, 0)
val sessionId = JSONObject(sessionJson).getString("session_id")
```

### 4. Execute Inference
You can choose between synchronous (blocking) or asynchronous (streaming) calls. Always call synchronous methods from a background thread.

#### Synchronous (with Coroutines)
```kotlin
val request = """{"messages": [{"role": "user", "content": "Hello!"}]}"""
val response = withContext(Dispatchers.IO) {
    aiService?.generateResponseWithSession(apiToken, sessionId, request)
}
```

#### Asynchronous (Streaming)
```kotlin
aiService?.generateResponseAsyncWithSession(apiToken, sessionId, request, object : IInferenceCallback.Stub() {
    override fun onToken(token: String) {
        // Handle streaming tokens (UI thread update required)
    }
    override fun onComplete(fullResponse: String) {
        // Final OpenAI-standard JSON response
    }
    override fun onError(error: String) {
        // Handle errors
    }
})
```

---

## 3. Communication Protocol (JSON)

### Request Structure
| Field | Type | Description |
| :--- | :--- | :--- |
| `model` | String | Identifier for the loaded model (e.g., "gemma"). |
| `messages` | Array | List of chat objects. |
| `messages[].role` | String | "user", "assistant", or **"system"**. |
| `messages[].content` | String/Obj | The text content or multimodal data. |
| `temperature` | Double | (Optional) Controls randomness (0.0 to 2.0). |
| `top_p` | Double | (Optional) Nucleus sampling probability threshold (0.0 to 1.0). |
| `top_k` | Integer | (Optional) Top-K sampling threshold. |

### Response Structure
Standard OpenAI-compatible JSON:
```json
{
  "choices": [{
    "message": { "role": "assistant", "content": "..." },
    "finish_reason": "stop"
  }],
  "usage": { "total_tokens": 37 }
}
```

---

## 4. Multimodal Inference

Send images or audio as base64-encoded strings within the `content` array of a message.

```json
{
  "messages": [
    {
      "role": "user",
      "content": [
        { "type": "text", "text": "Describe this!" },
        { "type": "image_url", "image_url": { "url": "data:image/jpeg;base64,..." } }
      ]
    }
  ]
}
```

---

## 5. Assistant Message Continuation (Prefilling)

You can guide or constrain the model's response by "prefilling" the assistant's turn. If the last message in your `messages` array has the role `assistant`, the model will treat this text as the beginning of its response and continue generating from there.

**Example: Forcing a specific format**
```json
{
  "messages": [
    { "role": "user", "content": "Spell the word 'apple'." },
    { "role": "assistant", "content": "A-P-" }
  ]
}
```
**Response:**
```json
{
  "choices": [{
    "message": { "role": "assistant", "content": "P-L-E" }
  }]
}
```

**Example: Reasoning Enforcement (CoT)**
```json
{
  "messages": [
    { "role": "user", "content": "How many 'r's are in 'strawberry'?" },
    { "role": "assistant", "content": "<think>\n" }
  ]
}
```
*The model will continue its reasoning inside the `<think>` block.*

This is useful for:
*   Forcing JSON output (start with `{`).
*   Reasoning enforcement (start with `<think>`).
*   Continuing a sentence or interrupted thought.
*   Guiding the model's tone or style.

---

## 6. Advanced Multi-Session Management

Edge AI Core supports **true multi-session concurrency**. Multiple independent client applications can maintain their own conversation states simultaneously.

### How it Works:
1.  **Independent Contexts**: Each `sessionId` maps to a distinct `Session` instance in the LiteRT-LM engine.
2.  **KV Cache Persistence**: The hardware KV cache is preserved for each session. 
    *   **Full History Support**: You can safely send the full conversation history in every request (standard OpenAI behavior). The engine intelligently detects the session state and only processes the new messages, avoiding redundant computation.
3.  **Hardware Awareness**:
    *   **GPU Backend**: Typically supports many parallel sessions.
    *   **NPU/CPU Backend**: If hardware limits are reached, the engine closes the oldest active session (LRU) to make room.
    *   **Lock Serialization**: Inference execution is serialized for stability.

---

## 7. Best Practices & Security

1.  **Session Hygiene**: Always call `closeSession()` when a conversation ends to free up NPU memory.
2.  **KV Cache Reuse**: The engine intelligently avoids re-processing old messages. For best performance, send the **full conversation history** in every request. The service will automatically detect the overlapping messages and only process the new tokens.
3.  **Local Server**: For testing, a local OpenAI-compatible server runs at `localhost:8080`.
4.  **Token Security**: Keep backup files of your API tokens safe. Tokens are mapped to your app's package name for security.

---

## 8. Troubleshooting
    
- **"PENDING_USER_APPROVAL"**: The user hasn't approved your app in the Edge AI Core UI yet.
- **Service Not Found**: On Android 11+, ensure your client app declares the `<queries>` tag in its `AndroidManifest.xml`.
- **"Invalid API token"**: The token has been revoked or was never approved.
- **"Session expired"**: The session timed out due to inactivity. Create a new session.
- **ANR in Client**: Ensure you are not calling synchronous AIDL methods on the main thread.

---

## 9. Integration Guide: Binding to the Service

On Android 11 (API 30) and above, package visibility restrictions prevent apps from seeing each other by default. To bind to the Edge AI Core service, your client app **must** include the following in its `AndroidManifest.xml`:

```xml
<queries>
    <package android:name="com.aanand.edgeaicore" />
    <intent>
        <action android:name="com.aanand.edgeaicore.IInferenceService" />
    </intent>
</queries>
```

### Binding Example (Kotlin)
```kotlin
val intent = Intent("com.aanand.edgeaicore.IInferenceService").apply {
    setPackage("com.aanand.edgeaicore")
}
bindService(intent, connection, Context.BIND_AUTO_CREATE)
```

### Diagnostic Check
You can use the `ping()` method to verify your connection and that the service is responsive, even without an API token:

```kotlin
// Returns "pong" if the service is alive
val status = aiService?.ping()
Log.d("ClientApp", "Service status: $status")
```
