package com.aanand.edgeaicore

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import android.util.Base64
import com.google.ai.edge.litertlm.MessageCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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

    suspend fun generateResponse(
        messages: List<ChatMessage>,
        temperature: Double? = null,
        topP: Double? = null,
        topK: Int? = null,
        preamble: String? = null
    ): String = withContext(Dispatchers.IO) {
        val currentEngine = engine ?: throw IllegalStateException("Model not loaded")

        // Separate system, history and last message
        val systemMessage = messages.find { it.role == "system" }
        val nonSystemMessages = messages.filter { it.role != "system" }
        
        val lastMessage = nonSystemMessages.lastOrNull() ?: return@withContext "No user message found"
        val history = nonSystemMessages.dropLast(1)

        val lrtLastMessage = toLiteRTMessage(lastMessage)
        val lrtHistory = history.map { toLiteRTMessage(it) }
        
        return@withContext inferenceMutex.withLock {
            Log.d(TAG, "Request starting inference (lock acquired)")
            var conversation: Conversation? = null
            try {
                val conversationConfig = ConversationConfig(
                    systemInstruction = preamble?.let { Contents.of(Content.Text(it)) },
                    initialMessages = lrtHistory,
                    samplerConfig = SamplerConfig(
                        topK = topK ?: 40,
                        topP = topP ?: 0.95,
                        temperature = temperature ?: 0.8
                    )
                )
                conversation = currentEngine.createConversation(conversationConfig)
                val response = conversation!!.sendMessage(lrtLastMessage)
                val content = response.contents
                val responseText = extractText(content)
                Log.d(TAG, "Received response from engine: $responseText")
                responseText
            } catch (e: Exception) {
                 Log.e(TAG, "Error generating response", e)
                 throw e
            } finally {
                conversation?.close()
                Log.d(TAG, "Inference finished (lock released)")
            }
        }
    }

    suspend fun generateResponseAsync(
        messages: List<ChatMessage>,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (Throwable) -> Unit,
        temperature: Double? = null,
        topP: Double? = null,
        topK: Int? = null,
        preamble: String? = null
    ) = withContext(Dispatchers.IO) {
        val currentEngine = engine ?: throw IllegalStateException("Model not loaded")

        // Separate system, history and last message
        val systemMessage = messages.find { it.role == "system" }
        val nonSystemMessages = messages.filter { it.role != "system" }
        
        val lastMessage = nonSystemMessages.lastOrNull() ?: return@withContext Unit
        val history = nonSystemMessages.dropLast(1)

        val lrtLastMessage = toLiteRTMessage(lastMessage)
        val lrtHistory = history.map { toLiteRTMessage(it) }

        inferenceMutex.withLock {
            Log.d(TAG, "Request starting async inference (lock acquired)")
            var conversation: Conversation? = null
            try {
                val conversationConfig = ConversationConfig(
                    systemInstruction = preamble?.let { Contents.of(Content.Text(it)) },
                    initialMessages = lrtHistory,
                    samplerConfig = SamplerConfig(
                        topK = topK ?: 40,
                        topP = topP ?: 0.95,
                        temperature = temperature ?: 0.8
                    )
                )
                conversation = currentEngine.createConversation(conversationConfig)
                var lastResponseText = ""
                
                suspendCancellableCoroutine<Unit> { cont ->
                    conversation!!.sendMessageAsync(lrtLastMessage, object : MessageCallback {
                        override fun onMessage(message: Message) {
                            val fullText = extractText(message.contents)
                            // Calculate the new token part. 
                            // Note: Native SDK might provide full accumulated text each time.
                            val newToken = if (fullText.startsWith(lastResponseText)) {
                                fullText.substring(lastResponseText.length)
                            } else {
                                fullText
                            }
                            lastResponseText = fullText
                            if (newToken.isNotEmpty()) {
                                onToken(newToken)
                            }
                        }

                        override fun onDone() {
                            onComplete(lastResponseText)
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onError(error: Throwable) {
                            Log.e(TAG, "Async inference error", error)
                            onError(error)
                            if (cont.isActive) cont.resumeWithException(error)
                        }
                    })

                    cont.invokeOnCancellation {
                        // Attempt to cancel if possible, though LiteRT-LM might not support it directly here
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in generateResponseAsync", e)
                onError(e)
                throw e
            } finally {
                conversation?.close()
                Log.d(TAG, "Async inference finished (lock released)")
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
                                contents.add(Content.AudioBytes(bytes))
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
        
        val lrtContents = Contents.of(contents)
        return if (chatMessage.role.equals("assistant", ignoreCase = true)) {
            Message.model(lrtContents)
        } else {
            Message.user(lrtContents)
        }
    }

    private fun extractText(contents: Any): String {
        return if (contents is Iterable<*>) {
            contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
        } else {
            contents.toString()
        }
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
