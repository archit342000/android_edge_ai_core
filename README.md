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
| `messages[].role` | String | Either "user" or "assistant". |
| `messages[].content` | String/Obj | The text content or multimodal data. |

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
    "prompt_tokens": 0,
    "completion_tokens": 0,
    "total_tokens": 0
  }
}
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

### Implementation Example

```kotlin
val requestJson = "{\"model\": \"gemma\", \"messages\": [{\"role\": \"user\", \"content\": \"Write a poem about AI\"}]}"

aiService?.generateResponseAsync(requestJson, object : IInferenceCallback.Stub() {
    override fun onToken(token: String) {
        // This is called on a background thread for every new token generated
        runOnUiThread {
            textView.append(token)
        }
    }

    override fun onComplete(fullResponse: String) {
        // fullResponse is the final completion JSON (OpenAI format)
        Log.d("AiClient", "Inference complete")
    }

    override fun onError(error: String) {
        Log.e("AiClient", "Streaming error: $error")
    }
})
```

## 7. Best Practices & Safety

1.  **Streaming vs. Synchronous**: Use `generateResponseAsync` for interactive chat applications to prevent the appearance of the app being frozen.
2.  **Thread Management**: Never call `generateResponse` on the Main Thread. Large models can take several seconds to generate text.
3.  **Service State**: Ensure that the **Edge AI Core** app has been opened and the "Enable Server" switch is **ON**. If the server is off, the service will return `{"error": "Model not loaded"}`.
4.  **Multimodal Streaming**: The streaming interface (`generateResponseAsync`) also supports Vision and Audio requests. Use it to provide real-time feedback during media analysis.
5.  **Error Handling**: Always wrap calls in `try-catch` blocks to handle `RemoteException` or connection drops.
6.  **Concurrency & Queuing**: The service is thread-safe. If multiple applications send requests at the same time, **Edge AI Core** automatically queues them and processes them sequentially. This prevents Out-Of-Memory (OOM) crashes and ensures stable performance on mobile hardware.
7.  **Security**: The service is strictly on-device. No data leaves the device during the inference process.
