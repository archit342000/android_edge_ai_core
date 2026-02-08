# Edge AI Core - Developer Integration Guide

This application provides a system-wide AI inference service via **AIDL (Android Interface Definition Language)**. 

Starting from **v1.7.0**, Edge AI Core implements **Smart Session Reuse** within its stateful architecture. This enables **KV Cache persistence** for consecutive messages, significantly reducing latency in multi-turn conversations while maintaining strict user isolation.

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
    String startConversation(String apiToken, String systemInstruction);

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
val systemPrompt = "You are a helpful assistant."
val convJson = aiService?.startConversation(apiToken, systemPrompt)
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
- **System Preamble**: The `systemInstruction` string provided in `startConversation()` is used as the **System Prompt** for the entire session. It cannot be changed once the conversation starts.
- **In-Chat System Messages**: Any messages with role 'system' sent *during* the conversation via `generateConversationResponseAsync` are treated as **User** messages to ensure they are seen by the model.
- **Message Processing**: 
    - Historical messages (if any) are processed synchronously to update the context.
    - The **final** message in the request triggers the asynchronous response generation.



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
