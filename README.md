# LiteRTLM Server

An Android native AI server app that serves LiteRT-LM models using an OpenAI-compatible API.

## Features

- **Load Model**: Load `.litertlm` models (e.g., Gemma 3n).
- **OpenAI API**: Exposes a `POST /v1/chat/completions` endpoint on port 8080.
- **Inference Service**: Runs as a foreground service to keep the model alive.
- **Test Inference**: Built-in test to verify model functionality.
- **Minimalistic UI**: Simple toggle to enable/disable the server.

## Setup Instructions

1. **Prerequisites**:
   - Android Device with Android 8.0+ (Oreo).
   - `.litertlm` model file (e.g., `gemma-3n-e2b-it.litertlm`).
     - Download from [Hugging Face LiteRT Community](https://huggingface.co/litert-community).
     - Models like `gemma-3n-e2b-it-litertlm` are supported.

2. **Installation**:
   - Build the APK using `./gradlew assembleDebug` or install via Android Studio.
   - Install the APK on your device.

3. **Usage**:
   - Open the app.
   - Tap **Select Model** and choose your `.litertlm` file.
   - Wait for the file to be copied.
   - Toggle **Enable Server**. The status should change to "Loading..." then "Ready".
   - Use **Test Inference** to check if it works.

## API Usage

Send POST requests to `http://<PHONE_IP>:8080/v1/chat/completions`.

**Request Body:**
```json
{
  "model": "gemma",
  "messages": [
    {"role": "user", "content": "Hello!"}
  ]
}
```

**Response:**
```json
{
  "id": "chatcmpl-...",
  "choices": [
    {
      "message": {
        "role": "assistant",
        "content": "Hello! How can I help you today?"
      }
    }
  ],
  ...
}
```

## Supported Models

- Gemma 3n E2B IT LiteRT-LM
- Gemma 3n E4B IT LiteRT-LM
- Multimodal support is experimental (requires model with vision support).

## Development

- **Build**: `./gradlew assembleDebug`
- **Dependencies**:
  - `com.google.ai.edge.litertlm:litertlm-android:0.8.0`
  - Ktor Server
