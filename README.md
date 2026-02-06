# Edge AI Core - Developer Integration Guide

This application provides a system-wide AI inference service via **AIDL (Android Interface Definition Language)**. This guide provides a step-by-step walkthrough for integrating your Android application with the **Edge AI Core** inference engine.

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
    /** Synchronous response generation */
    String generateResponse(String jsonRequest);

    /** Asynchronous streaming response generation */
    void generateResponseAsync(String jsonRequest, IInferenceCallback callback);
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

## 2. Implementation Logic

### Binding to the Service
The service runs as a **Bound Service**. You must establish a connection before sending requests.

```kotlin
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.aanand.edgeaicore.IInferenceService

class AiClient(private val context: Context) {
    private var aiService: IInferenceService? = null
    private var isConnected = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            aiService = IInferenceService.Stub.asInterface(service)
            isConnected = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            aiService = null
            isConnected = false
        }
    }

    fun connect() {
        val intent = Intent()
        intent.component = ComponentName("com.aanand.edgeaicore", "com.aanand.edgeaicore.InferenceService")
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun disconnect() {
        if (isConnected) {
            context.unbindService(connection)
            isConnected = false
        }
    }
}
```

### Sending an Inference Request
Inference is **synchronous** at the AIDL level. **Always** call it from a background thread (e.g., using Coroutines) to avoid ANRs.

```kotlin
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun askAi(prompt: String): String? {
    // 1. Prepare OpenAI-compatible JSON
    val requestJson = """
        {
          "model": "gemma",
          "messages": [
            {"role": "user", "content": "$prompt"}
          ]
        }
    """.trimIndent()

    return withContext(Dispatchers.IO) {
        try {
            // 2. Execute call
            val response = aiService?.generateResponse(requestJson)
            response // Return the result of the call
        } catch (e: Exception) {
            Log.e("AiClient", "Inference failed", e)
            null
        }
    }
}
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
| `top_k` | Integer | (Optional) Limit vocabulary to top K probable tokens. |

### Response Structure
The service returns a stringified JSON mimicking the OpenAI standard:

```json
{
  "id": "chatcmpl-0719e869-b5a1-428b-be6c-b75d31995d6f",
  "object": "chat.completion",
  "created": 1770314114,
  "model": "gemma",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "Yes, I am working! I am ready to respond to your prompts..."
      },
      "finish_reason": "stop"
    }
  ],
  "usage": {
    "prompt_tokens": 12,
    "completion_tokens": 25,
    "total_tokens": 37
  }
}
```

### Parsing the Response (Kotlin Data Classes)
To parse the JSON response in your Android app using **Gson**, you can use the following data classes:

```kotlin
data class ChatResponse(
    val id: String,
    val created: Long,
    val model: String,
    val choices: List<Choice>,
    val usage: Usage
)

data class Choice(
    val index: Int,
    val message: ChatMessageResponse,
    val finish_reason: String
)

data class ChatMessageResponse(
    val role: String,
    val content: String
)

data class Usage(
    val prompt_tokens: Int,
    val completion_tokens: Int,
    val total_tokens: Int
)
```

## 4. Multimodal Inference (Vision)

The service supports multimodal models (e.g., Gemma 3 1B with Vision). You can send images as base64-encoded strings within the `content` array.

### Request Format (with Image)
```json
{
  "model": "gemma-vision",
  "messages": [
    {
      "role": "user",
      "content": [
        {
          "type": "text",
          "text": "What is in this image?"
        },
        {
          "type": "image_url",
          "image_url": {
            "url": "data:image/jpeg;base64,/9j/4AAQSkZJRg..." 
          }
        }
      ]
    }
  ]
}
```

### Response Format (Vision Output)
The service returns detailed image descriptions in the same OpenAI format:

```json
{
  "id": "chatcmpl-335bfcad-81ca-4bb0-a3f8-1e3f1c3ce5b9",
  "object": "chat.completion",
  "created": 1770315012,
  "model": "gemma-vision",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "The image shows a mobile app interface for managing addresses. Here's a breakdown of what's visible..."
      },
      "finish_reason": "stop"
    }
  ],
  "usage": {
    "prompt_tokens": 0,
    "completion_tokens": 0,
    "total_tokens": 0
  }
}
```

> [!IMPORTANT]
> -   **Model Support**: Ensure you have loaded a model that supports vision (e.g., `.litertlm` file with vision capabilities).
> -   **Base64 Format**: The image must be a standard base64 string prefixed with `data:image/...;base64,`.
> -   **Performance**: Processing images takes significantly more memory and time than plain text. Use a background thread.

## 5. Multimodal Inference (Audio)

The service supports models with audio capabilities (e.g., Gemma 3n E2B/E4B). Audio should be sent as base64-encoded strings.

### Request Format (with Audio)
```json
{
  "model": "gemma-audio",
  "messages": [
    {
      "role": "user",
      "content": [
        {
          "type": "text",
          "text": "Transcribe this audio"
        },
        {
          "type": "audio_url",
          "audio_url": {
            "url": "data:audio/wav;base64,UklGRiS..." 
          }
        }
      ]
    }
  ]
}
```

### Response Format (Audio Output)
The service returns audio transcriptions or analysis in the OpenAI format:

```json
{
  "id": "chatcmpl-bdf8c05c-48d8-40e3-a425-f0e08fa29bb6",
  "object": "chat.completion",
  "created": 1770315818,
  "model": "gemma-audio",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "The audio contains the question: \"What is five plus ten?\""
      },
      "finish_reason": "stop"
    }
  ],
  "usage": {
    "prompt_tokens": 0,
    "completion_tokens": 0,
    "total_tokens": 0
  }
}
```

> [!TIP]
> -   **Audio Format**: The engine highly prefers **16kHz Mono 16-bit PCM WAV** files. Other compressed formats may fail with decoder errors.
> -   **Permissions**: Ensure your client app has `RECORD_AUDIO` if you are capturing intent directly.

## 6. Streaming Responses

Starting from **v1.1.0**, the service supports streaming tokens in real-time. This provides a better user experience for long responses by showing progress as it's generated.

### Implementation Guide

To correctly handle streaming, you should distinguish between incremental token updates (`onToken`) and the final response metadata (`onComplete`).

#### 1. Manage State & UI
Use a `StringBuilder` to accumulate tokens if you need the raw text, and always update UI components on the main thread (e.g., using `runOnUiThread` or `View.post`).

#### 2. Handle Final Response
`onComplete` provides the **entire** conversation response in the OpenAI-compatible JSON format (the same format as the synchronous `generateResponse` method). This includes usage statistics (token counts) and the final combined message.

### Example Code

```kotlin
val requestJson = """
    {
      "model": "gemma",
      "messages": [{"role": "user", "content": "Explain quantum physics in one sentence."}]
    }
""".trimIndent()

val responseBuffer = StringBuilder()

aiService?.generateResponseAsync(requestJson, object : IInferenceCallback.Stub() {
    override fun onToken(token: String) {
        // 1. Append raw token to your local buffer
        responseBuffer.append(token)

        // 2. Update UI incrementally
        runOnUiThread {
            // Recommendation: Append to existing text rather than re-setting the whole buffer
            // to improve performance on large responses.
            textView.append(token)
        }
    }

    override fun onComplete(fullResponseJson: String) {
        // 3. fullResponseJson is the final OpenAI-style JSON object
        // You can parse this to get usage metadata or verify the final text.
        try {
            val responseObj = Gson().fromJson(fullResponseJson, ChatResponse::class.java)
            val finalContent = responseObj.choices[0].message.content
            val usage = responseObj.usage
            
            Log.d("AiClient", "Inference complete. Used ${usage.total_tokens} tokens.")
        } catch (e: Exception) {
            Log.e("AiClient", "Failed to parse final response", e)
        }
    }

    override fun onError(error: String) {
        // 4. Handle errors (timeout, model unloaded, etc.)
        runOnUiThread {
            showErrorToast("Inference failed: $error")
        }
    }
})
```

> [!NOTE]
> **Performance Tip**: For very long responses, `textView.text = buffer.toString()` becomes slow. Use `textView.append(token)` inside `onToken` for the best performance.

## 7. System Prompts & Sampling Parameters

Starting from **v1.1.2**, the service supports defining AI behavior via **System Prompts** and fine-tuning output via **Sampling Parameters**.

### System Prompt (Preamble)
To set a system-wide behavior or "persona", include a message with the `role: "system"`. The engine extracts this message and uses it as the conversation preamble.

```json
{
  "model": "gemma",
  "messages": [
    {
      "role": "system", 
      "content": "You are a professional chef. Answer all questions with a culinary twist."
    },
    {
      "role": "user", 
      "content": "How do I fix a flat tire?"
    }
  ]
}
```

### Sampling Parameters
You can control the creativity and length of the response by providing optional parameters at the root of the JSON request.

| Parameter | Default | Description |
| :--- | :--- | :--- |
| `temperature` | 0.8 | Higher values make output more creative; lower make it more deterministic. |
| `top_p` | 0.95 | Nucleus sampling: only considers tokens with top P cumulative probability. |
| `top_k` | 40 | Limits the model to consider only the top K most likely next words. |

#### Example Request with Parameters
```json
{
  "model": "gemma",
  "messages": [{"role": "user", "content": "Write a poem about Kotlin."}],
  "temperature": 0.8,
  "top_p": 0.9,
  "top_k": 40
}
```

### Statelessness & History
To keep the service lightweight and efficient on mobile devices, each request to `generateResponse` or `generateResponseAsync` is **stateless**. The internal conversation instance is closed after each call.

If your application requires chat history (context), you must manage the history state in your client application. However, please note that current versions of the core engine optimize for the **last message** in the `messages` array while using the `system` message as a preamble. 

> [!TIP]
> Future updates will include optimized KV-cache management to support efficient multi-turn conversations without replaying history. For now, it is recommended to keep prompts self-contained for the best performance.

## 8. Best Practices & Safety

1.  **Streaming vs. Synchronous**: Use `generateResponseAsync` for interactive chat applications to prevent the appearance of the app being frozen.
2.  **Thread Management**: Never call `generateResponse` on the Main Thread. Large models can take several seconds to generate text.
3.  **Service State**: Ensure that the **Edge AI Core** app has been opened and the "Enable Server" switch is **ON**. If the server is off, the service will return `{"error": "Model not loaded"}`.
4.  **Multimodal Streaming**: The streaming interface (`generateResponseAsync`) also supports Vision and Audio requests. Use it to provide real-time feedback during media analysis.
5.  **Error Handling**: Always wrap calls in `try-catch` blocks to handle `RemoteException` or connection drops.
6.  **Concurrency & Queuing**: The service is thread-safe. If multiple applications send requests at the same time, **Edge AI Core** automatically queues them and processes them sequentially. This prevents Out-Of-Memory (OOM) crashes and ensures stable performance on mobile hardware.
7.  **Security**: The service is strictly on-device. No data leaves the device during the inference process.
