# Edge AI Core - Developer Integration Guide

This application provides a system-wide AI inference service via **AIDL (Android Interface Definition Language)**. 

Starting from **v1.5.0**, Edge AI Core introduces a **stateful conversation architecture**. This ensures high performance through KV cache reuse, supports a **32k context window**, and simplifies multi-turn interactions.

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
    // Conversation Management
    // =====================
    /**
     * Starts a new stateful conversation. Returns conversation_id and TTL info.
     */
    String startConversation(String apiToken);

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
     */
    String ping();
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
val convJson = aiService?.startConversation(apiToken)
val conversationId = JSONObject(convJson).getString("conversation_id")
```

### 4. Execute Inference (Streaming)
**CRITICAL:** The service implements **Raw Message Appending**. This means it will explicitly append **every message** provided in the `jsonRequest` to the current engine state. 

It is the **client's sole responsibility** to ensure that old messages are not sent again. Sending the full history in every request will result in duplicated context strings and rapidly fill the context window.

```kotlin
val request = """{
    "messages": [{"role": "user", "content": "Tell me a story."}]
}"""

aiService?.generateConversationResponseAsync(apiToken, conversationId, request, object : IInferenceCallback.Stub() {
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
- The TTL resets automatically every time a new request is sent to that conversation.
- If a conversation is inactive beyond the TTL, it is automatically closed to free up hardware resources.
- You can manually close a conversation using `closeConversation()`.

### System Instructions & Message Handling
- **System Preamble**: The **first** system message provided when starting a *new* conversation is extracted and set as the engine's `systemInstruction`. This sets the behavior for the entire session.
- **Subsequent System Messages**: Any system messages sent *after* the initial turn (or multiple system messages in the first turn) are treated as **User** messages to ensure they are injected into the context stream.
- **Message Processing**: 
    - Historical messages (if any) are processed synchronously to update the context.
    - The **final** message in the request triggers the asynchronous response generation.

### Assistant Message Continuation (Continuation Mode)
You can guide the model by prefilling its response. If the last message in your request has the role `assistant`, the model will continue generating from that point.

**Example:**
```json
{
  "messages": [
    { "role": "user", "content": "Write a JSON object for a user named Alice." },
    { "role": "assistant", "content": "{\n  \"name\": \"Alice\"," }
  ]
}
```
*The service will continue generating the JSON from that point.*

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
