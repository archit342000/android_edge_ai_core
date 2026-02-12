# Edge AI Core 🚀

**Edge AI Core** is a high-performance Android system service that brings state-of-the-art AI inference directly to your device. Built on **LiteRT-LM (TFLite)**, it provides a secure, private, and extremely low-latency API for local AI processing.

---

## ✨ Features

- 🧠 **Multi-Modal Support**: Optimized for Text, Vision, and Audio inference.
- ⚡ **Hardware Acceleration**: Seamlessly switch between **CPU**, **GPU**, and **NPU** backends.
- 🔄 **Smart Session Reuse**: Persistent KV Cache for ultra-fast multi-turn conversations.
- 🛡️ **Hardened Security**: Robust API Token management with granular per-app permissions.
- 🌌 **32k Context**: Massive context window for processing large documents and long chats.
- ⏳ **Customizable TTL**: Clients can specify custom Time-To-Live for conversations via the API.
- 💾 **Crash Recovery**: Conversations are persisted to local storage and reloaded automatically after restarts.

---

## 📱 App Usage Guide

### 1. Initial Setup
1. **Grant Permissions**: Upon first launch, the app will request permissions for **Microphone** (audio inference), **Storage** (loading models), and **Battery Optimization** (to ensure the service stays alive in the background).
2. **Select a Model**: Go to the **Backend Settings** tab. Tap **Select Model** and choose your `.litertlm`, `.bin`, or `.tflite` model file.
3. **Choose Backend**: Select your preferred hardware accelerator (**CPU**, **GPU**, or **NPU**) depending on your device's capabilities.

### 2. Managing the Service
- **Switch On**: In the **Server** tab, toggle the **Enable AI Server** switch. The status will change to `Loading...` and then `Ready` once the model is initialized.
- **Diagnostics**: Use the **Test Inference**, **Multi-Turn Test**, **Health Check**, or **Get Load** buttons to verify the model and server status.

### 3. API Token Management
The service requires an API token for all external app requests.
- **Generate Token**: In the **Tokens** tab, tap **Generate New Token**.
- **App Approval**: When an external app requests access, you will see a notification in the **Pending Access Requests** section. You can **Approve** or **Deny** these requests manually.
- **Revoke Access**: At any time, you can view **Authorized Apps** and revoke their specific API tokens.
- **Backup/Restore**: Securely export your authorized tokens to a JSON file for backup or transfer between devices.

---

## 🛠️ Developer Integration Guide

This section is for developers who want to integrate Edge AI Core into their own apps.

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
    // Conversation Management
    // =====================
    /**
     * Starts a new stateful conversation. Returns conversation_id and TTL info.
     * @param ttlMs Custom TTL in ms. Pass 0 or less to use default (30 mins).
     */
    String startConversation(String apiToken, String systemInstruction, long ttlMs);

    /**
     * Closes a conversation, releasing hardware resources.
     */
    String closeConversation(String apiToken, String conversationId);

    /**
     * Gets information about an active conversation.
     */
    String getConversationInfo(String apiToken, String conversationId);

    // =====================
    // Inference
    // =====================
    /**
     * Generates a streaming response. Only NEW messages should be sent.
     */
    void generateConversationResponseAsync(String apiToken, String conversationId, String jsonRequest, IInferenceCallback callback);

    /**
     * Diagnostic check.
     * @param apiToken The API token for authentication
     * @return "pong"
     */
    String ping(String apiToken);

    /**
     * Health check to confirm the AI server is up and running.
     * @param apiToken The API token for authentication
     * @return "ok" if the service is responsive and model is loaded.
     */
    String health(String apiToken);

    /**
     * Returns the current load on the server (number of active requests).
     * @param apiToken The API token for authentication
     * @return Number of requests currently being processed or waiting in queue.
     *         Returns -1 for invalid token, -2 for model not loaded.
     */
    int getLoad(String apiToken);
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

---

## 2. Integration Workflow

### 1. Bind to Service
The service runs as a **Bound Foreground Service**.

```kotlin
val intent = Intent("com.aanand.edgeaicore.IInferenceService").apply {
    setPackage("com.aanand.edgeaicore")
}
bindService(intent, connection, Context.BIND_AUTO_CREATE)
```

### 2. Obtain an API Token
All calls require an API token. Tokens are permanent until revoked.

```kotlin
// Triggers a manual approval request in the Edge AI Core app
val result = aiService?.generateApiToken() 
```

### 3. Start a Conversation
Conversations maintain state and allow **32k tokens** of context.

```kotlin
val systemPrompt = "You are a helpful assistant."
val customTtlMs = 60000L // 1 minute
val convJson = aiService?.startConversation(apiToken, systemPrompt, customTtlMs)
val conversationId = JSONObject(convJson).getString("conversation_id")
```

### 4. Execute Inference (Streaming)
**CRITICAL:** The service implements **Raw Message Appending**. This means it will explicitly append **every message** provided in the `jsonRequest` to the current engine state. 

It is the **client's sole responsibility** to ensure that old messages are not sent again. Sending the full history in every request will result in duplicated context strings and rapidly fill the context window.

```kotlin
// Example: Basic Text Request
val textRequest = """{
    "messages": [{"role": "user", "content": "Tell me a story."}],
    "temperature": 0.7
}"""

// Example: Multi-Modal Vision Request
val visionRequest = """{
    "messages": [{
        "role": "user",
        "content": [
            {"type": "text", "text": "What is in this image?"},
            {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,...base64_data..."}}
        ]
    }]
}"""

aiService?.generateConversationResponseAsync(apiToken, conversationId, textRequest, object : IInferenceCallback.Stub() {
    override fun onToken(token: String) {
        // Handle token-by-token streaming
    }
    override fun onComplete(fullResponse: String) {
        // Final response JSON
    }
    override fun onError(error: String) {
        // Handle errors
    }
})
```

---

## 3. Key Features

### 32k Context Window
Initial conversations are initialized with a 32,768 token context window, allowing for extremely long multi-turn interactions and large document processing.

### TTL & Auto-Cleanup
Each conversation has a **TTL (Time-To-Live)**. 
- **Customizable**: You can set a custom TTL (in ms) when starting a conversation via `startConversation(..., ttlMs)`.
- **Default behavior**: If `ttlMs` is 0 or less, the system defaults to **30 minutes**.
- **Reset on Access**: The TTL resets automatically every time a new request is sent to that conversation.
- **Cleanup**: If a conversation is inactive beyond the TTL, it is automatically closed to free up hardware resources.
- You can manually close a conversation using `closeConversation()`.

### Local Persistence & Recovery
Edge AI Core now automatically persists all active conversations to secure internal storage.
- **Crash Recovery**: If the service process is killed or the device restarts, all non-expired conversations are reloaded into memory upon service initialization.
- **Auto-Sync**: Any change to a conversation (new messages, sampling parameter updates, or last access time) is immediately mirrored to the local copy.
- **Expired Data Cleanup**: During the recovery process, conversations that have exceeded their TTL are permanently deleted from storage to protect privacy and free up disk space.

### System Instructions & Message Handling
- **System Preamble**: The `systemInstruction` string provided in `startConversation()` is used as the **System Prompt** for the entire session. It cannot be changed once the conversation starts.
- **In-Chat System Messages**: Any messages with role 'system' sent *during* the conversation via `generateConversationResponseAsync` are treated as **User** messages to ensure they are seen by the model.
- **Message Processing**: 
    - Historical messages (if any) are processed synchronously to update the context.
    - The **final** message in the request triggers the asynchronous response generation.

### Sampling Parameters
You can customize the model's creativity and diversity by including the following optional parameters in your JSON request:

- **`temperature`** (Defaults to 0.8): Controls randomness. Higher values (e.g., 1.0) make output more random, lower values (e.g., 0.2) make it more deterministic.
- **`top_p`** (Defaults to 0.95): Nucleus sampling. Limits the next token selection to a subset of tokens with a cumulative probability of `p`.
- **`top_k`** (Defaults to 40): Limits the next token selection to the top `k` most probable tokens.

These parameters are stored with the conversation state. If provided in a request, they update the conversation's settings for that and subsequent turns.



### Diagnostic & Health Endpoints
**Note:** All diagnostic endpoints now require a valid API token for authentication.

- **`ping(apiToken)`**: Simple connectivity test. Returns `"pong"`.
- **`health(apiToken)`**: Recommended for checking if the AI server is active and the model is loaded. Returns `"ok"`.
- **`getLoad(apiToken)`**: Returns an integer representing the total number of inference requests currently being processed. Returns `-2` if the model is not loaded.

---

## 4. Best Practices

1.  **State Management**: Store `conversationId` in your app. Do not start a new conversation for every turn.
2.  **History Management**: **DO NOT** send the full conversation history. The server simply appends what it receives. It is the client's job to only send the User/Assistant messages since the last turn.
3.  **Manual Cleanup**: Call `closeConversation()` when the user finishes a chat session to help the system manage limited NPU/GPU resources.
4.  **Error Handling**: Always implement `onError` to handle expired conversations or engine failures gracefully.

---

## 5. Troubleshooting
    
- **"Conversation not found or expired"**: The TTL has passed. Call `startConversation()` again.
- **"Invalid API token"**: Ensure your app has been approved in the Edge AI Core dashboard.
- **Service Visibility**: On Android 11+, ensure the `<queries>` tag is in your client's manifest (see section 9 in previous versions, now standard practice).
