package com.aanand.edgeaicore

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import android.util.Base64
import com.google.ai.edge.litertlm.MessageCallback
import com.aanand.edgeaicore.ConversationState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.launch

class AiEngineManager(
    private val engineFactory: (EngineConfig) -> LlmEngine = ::createLiteRtEngine
) {
    private var engine: LlmEngine? = null
    val isModelLoaded: Boolean get() = engine != null
    private var currentModelPath: String? = null
    private val inferenceMutex = Mutex()
    // Idle engine conversations (with their KV caches), kept so a request that extends the previous one prefills only its new message.
    // Closed only while holding inferenceMutex.
    private val conversationCache = ConversationCache<LlmConversation>(
        maxEntries = MAX_CACHED_CONVERSATIONS,
        idleTtlMs = CACHE_IDLE_TTL_MS,
        close = ::closeQuietly
    )

    /** Closes every cached conversation, waiting for any generation in progress (they hold the inference lock). */
    suspend fun clearConversationCache() {
        inferenceMutex.withLock { conversationCache.clear() }
    }

    suspend fun loadModel(modelPath: String, backendType: String = "GPU"): Unit = withContext(Dispatchers.IO) {
        if (currentModelPath == modelPath && engine != null) {
            return@withContext
        }

        // Replace the engine under the inference lock: a queued request then finds no engine ("Model not loaded"), never a closed one.
        inferenceMutex.withLock {
            conversationCache.clear()
            engine?.close()
            engine = null
            currentModelPath = null
        }
        Log.d(TAG, "Loading model from $modelPath with Backend=$backendType")

        val file = java.io.File(modelPath)
        if (!file.exists()) {
            Log.e(TAG, "Model file does not exist: $modelPath")
            throw java.io.FileNotFoundException("Model file not found: $modelPath")
        }

        try {
            val backendEnum = when (backendType.uppercase()) {
                "CPU" -> Backend.CPU()
                "GPU" -> Backend.GPU()
                "NPU" -> Backend.NPU(nativeLibraryDir = "")
                else -> Backend.GPU()
            }

            val config = EngineConfig(
                modelPath = modelPath,
                backend = backendEnum,
                visionBackend = Backend.GPU(),
                audioBackend = Backend.CPU()
            )

            Log.d(TAG, "Initializing Engine with config: $config")
            val newEngine = engineFactory(config)
            engine = newEngine
            currentModelPath = modelPath

            Log.d(TAG, "Model loaded successfully with Backend=$backendType")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load model with Backend=$backendType. Error: ${t.message}", t)
            if (backendType.equals("GPU", ignoreCase = true)) {
                 Log.w(TAG, "Retrying with CPU backend...")
                 loadModel(modelPath, "CPU")
            } else {
                if (t is Exception) throw t else throw Exception(t)
            }
        }
    }


    /**
     * Creates a new engine-level conversation. Call inside [inferenceMutex].
     */
    fun createEngineConversation(
        temperature: Double? = null,
        topP: Double? = null,
        topK: Int? = null,
        preamble: String? = null,
        initialMessages: List<Message> = emptyList()
    ): LlmConversation {
        val currentEngine = engine ?: throw IllegalStateException("Model not loaded")
        
        if(preamble == null){
            Log.d(TAG, "No preamble provided, using default")
        }

        val conversationConfig = ConversationConfig(
            systemInstruction = Contents.of(Content.Text(preamble ?: "You are a helpful assistant.")),
            initialMessages = initialMessages,
            samplerConfig = SamplerConfig(
                topK = topK ?: 40,
                topP = topP ?: 0.95,
                temperature = temperature ?: 0.8
            )
        )
        return currentEngine.createConversation(conversationConfig)
    }




    /**
     * Generates a streaming response. Reuses the cached engine conversation when the request extends the previous one
     * (same settings, earlier messages unchanged), prefilling only the new message; otherwise builds a conversation from
     * `messages` minus the last. A conversation that failed, was cancelled or produced no text is closed, not cached.
     */
    suspend fun generateConversationResponseAsync(
        state: ConversationState,
        messages: List<ChatMessage>,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit,
        onErrorCallback: (Throwable) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (messages.isEmpty()) {
            onErrorCallback(IllegalArgumentException("No messages provided"))
            return@withContext
        }

        inferenceMutex.withLock {
            // Read under the lock: loadModel replaces the engine under it too.
            if (engine == null) throw IllegalStateException("Model not loaded")

            // The conversation in use; closed in the finally block unless it goes back into the cache.
            var conversation: LlmConversation? = null
            try {
                Log.d(TAG, "Processing request for ConversationId=${state.conversationId}. Total messages=${messages.size}")

                // 1. Update Persistent History
                // The history is read by other threads, so modify it and copy it under the state's lock
                val fullHistory = synchronized(state) {
                    state.history.addAll(messages)
                    // Return a copy for thread-safe iteration during context recreation
                    state.history.toMutableList()
                }

                // 2. Reuse the cached conversation when this request only extends it (same settings, and the history
                // before the last message is exactly what the conversation already holds); otherwise build a new one.
                val settings = CacheSettings(state.temperature, state.topP, state.topK, state.systemInstruction)
                val context = fullHistory.dropLast(1)
                val cached = conversationCache.take(settings, context)?.takeIf { it.isAlive }
                if (cached != null) {
                    Log.d(TAG, "Conversation cache hit, context size=${context.size}")
                    conversation = cached
                } else {
                    Log.d(TAG, "Conversation cache miss, context size=${context.size}")
                    val systemPrompt = state.systemInstruction ?: "You are a helpful assistant."
                    conversation = createEngineConversation(
                        temperature = state.temperature,
                        topP = state.topP,
                        topK = state.topK,
                        preamble = systemPrompt,
                        initialMessages = context.map { toLiteRTMessage(it) }
                    )
                }
                val active: LlmConversation = conversation

                // 3. Trigger Inference with the last message
                val lastMsg = fullHistory.last()
                Log.d(TAG, "Triggering inference with role=${lastMsg.role}")

                var lastResponseText = ""

                suspendCancellableCoroutine<Unit> { cont ->
                    active.sendMessageAsync(toLiteRTMessage(lastMsg), object : MessageCallback {
                        override fun onMessage(message: Message) {
                            val chunk = extractText(message.contents)
                            Log.v(TAG, "onMessage: Received chunk length=${chunk.length}")

                            if (chunk.isNotEmpty()) {
                                lastResponseText += chunk
                                onToken(chunk)
                            }
                        }

                        override fun onDone() {
                            Log.d(TAG, "Inference complete for ID=${state.conversationId}. Total Response Length: ${lastResponseText.length}")

                            // 4. Update History with Response
                            synchronized(state) {
                                if (lastResponseText.isNotEmpty()) {
                                    state.history.add(ChatMessage("assistant", com.google.gson.JsonPrimitive(lastResponseText)))
                                }
                            }
                            onComplete(lastResponseText)
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onError(error: Throwable) {
                            Log.e(TAG, "Inference error", error)
                            // Resolve the double-error callback issue by only calling resumeWithException
                            // The outer try-catch will handle calling onErrorCallback
                            if (cont.isActive) cont.resumeWithException(error)
                        }
                    })
                }

                // 5. Keep the conversation for the next request when it holds exactly history + reply
                if (lastResponseText.isNotEmpty()) {
                    conversationCache.put(
                        settings,
                        fullHistory + ChatMessage("assistant", com.google.gson.JsonPrimitive(lastResponseText)),
                        active
                    )
                    conversation = null // now owned by the cache
                }
            } catch (e: CancellationException) {
                // The conversation is closed below; a cancelled call reports nothing through the callbacks.
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error in generateConversationResponseAsync", e)
                onErrorCallback(e)
            } finally {
                // An error, a cancel or an empty reply leaves the conversation's KV state unknown: never cache it.
                conversation?.let { closeQuietly(it) }
            }
        }
    }

    // ===================================================================
    // Helper Methods
    // ===================================================================

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

    private fun extractText(anyContents: Any): String {
        val list = when (anyContents) {
            is com.google.ai.edge.litertlm.Contents -> anyContents.contents
            is Iterable<*> -> anyContents.toList()
            else -> return anyContents.toString()
        }
        return list.filterIsInstance<Content.Text>().joinToString("") { it.text }
    }

    /**
     * Extracts text content from a ChatMessage.
     */
    private fun extractTextFromChatMessage(chatMessage: ChatMessage): String {
        return if (chatMessage.content.isJsonPrimitive) {
            chatMessage.content.asString
        } else if (chatMessage.content.isJsonArray) {
            chatMessage.content.asJsonArray
                .filter { it.isJsonObject && it.asJsonObject.get("type")?.asString == "text" }
                .mapNotNull { it.asJsonObject.get("text")?.asString }
                .joinToString("")
        } else {
            chatMessage.content.toString()
        }
    }



    fun close() {
        conversationCache.clear()
        engine?.close()
        engine = null
        currentModelPath = null
    }

    /** Closes a conversation, logging instead of throwing (closing twice throws in LiteRT-LM). */
    private fun closeQuietly(conversation: LlmConversation) {
        try {
            if (conversation.isAlive) conversation.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing conversation", e)
        }
    }

    companion object {
        private const val TAG = "AiEngineManager"

        /** Idle conversations kept for reuse (a client plus the readiness ping or a second client). */
        private const val MAX_CACHED_CONVERSATIONS = 2

        /** A cached conversation unused for this long is closed, checked lazily on the next request. */
        private const val CACHE_IDLE_TTL_MS = 10 * 60 * 1000L
    }
}

