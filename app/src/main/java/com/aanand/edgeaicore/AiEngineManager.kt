package com.aanand.edgeaicore

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AiEngineManager {
    private var engine: Engine? = null
    private var currentModelPath: String? = null
    private val inferenceMutex = Mutex()


    suspend fun loadModel(modelPath: String, backendType: String = "GPU"): Unit = withContext(Dispatchers.IO) {
        if (currentModelPath == modelPath && engine != null) {
            // TODO: check if backend changed
            return@withContext
        }

        close()
        Log.d(TAG, "Loading model from $modelPath with Backend=$backendType")

        try {
            val backendEnum = when (backendType.uppercase()) {
                "CPU" -> Backend.CPU
                "GPU" -> Backend.GPU
                "NPU" -> Backend.NPU
                else -> Backend.GPU
            }

            val config = EngineConfig(
                modelPath = modelPath,
                backend = backendEnum,
                visionBackend = backendEnum,
                audioBackend = Backend.CPU
            )

            val newEngine = Engine(config)
            newEngine.initialize()
            engine = newEngine
            currentModelPath = modelPath

            Log.d(TAG, "Model loaded successfully with Backend=$backendType")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model with Backend=$backendType", e)
            if (backendType.equals("GPU", ignoreCase = true)) {
                 Log.w(TAG, "Retrying with CPU backend...")
                 loadModel(modelPath, "CPU")
            } else {
                throw e
            }
        }
    }

    suspend fun generateResponse(messages: List<ChatMessage>): String = withContext(Dispatchers.IO) {
        val currentEngine = engine ?: throw IllegalStateException("Model not loaded")

        // We take the last user message
        val lastMessage = messages.lastOrNull { it.role == "user" }
            ?: messages.last()

        val lrtMessage = toLiteRTMessage(lastMessage)
        
        return@withContext inferenceMutex.withLock {
            Log.d(TAG, "Request starting inference (lock acquired)")
            var conversation: Conversation? = null
            try {
                conversation = currentEngine.createConversation(ConversationConfig())
                val response = conversation!!.sendMessage(lrtMessage)
                val content = response.contents
                Log.d(TAG, "Received response from engine: $content")
                content.toString()
            } catch (e: Exception) {
                 Log.e(TAG, "Error generating response", e)
                 throw e
            } finally {
                conversation?.close()
                Log.d(TAG, "Inference finished (lock released)")
            }
        }
    }

    private fun toLiteRTMessage(chatMessage: ChatMessage): Message {
        val contents = mutableListOf<Content>()
        
        if (chatMessage.content.isJsonPrimitive) {
            contents.add(Content.Text(chatMessage.content.asString))
        } else if (chatMessage.content.isJsonArray) {
            for (element in chatMessage.content.asJsonArray) {
                if (element.isJsonObject) {
                    val obj = element.asJsonObject
                    val type = obj.get("type")?.asString ?: "text"
                    if (type == "text" && obj.has("text")) {
                        contents.add(Content.Text(obj.get("text").asString))
                    } else if (type == "image_url" && obj.has("image_url")) {
                        val imageUrlObj = obj.get("image_url").asJsonObject
                        val url = imageUrlObj.get("url").asString
                        if (url.startsWith("data:image")) {
                            try {
                                val base64Data = url.substringAfter("base64,")
                                val bytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                                contents.add(Content.ImageBytes(bytes))
                                Log.d(TAG, "Added image part (${bytes.size} bytes)")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to decode base64 image", e)
                            }
                        }
                    } else if (type == "audio_url" && obj.has("audio_url")) {
                        val audioUrlObj = obj.get("audio_url").asJsonObject
                        val url = audioUrlObj.get("url").asString
                        if (url.startsWith("data:audio")) {
                            try {
                                val base64Data = url.substringAfter("base64,")
                                val bytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                                // Assuming factory method is Content.AudioBytes(bytes)
                                contents.add(Content.AudioBytes(bytes))
                                Log.d(TAG, "Added audio part (${bytes.size} bytes)")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to decode base64 audio", e)
                            }
                        }
                    }
                }
            }
        }
        
        if (contents.isEmpty()) {
            contents.add(Content.Text(chatMessage.content.toString()))
        }
        
        return Message.of(contents)
    }

    fun close() {
        engine?.close()
        engine = null
        currentModelPath = null
    }

    companion object {
        private const val TAG = "AiEngineManager"
    }
}
