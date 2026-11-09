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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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

    /** The backend the loaded engine actually runs on ("CPU", "GPU" or "NPU"); null while no model is loaded. */
    @Volatile var activeBackend: String? = null
        private set

    /** True when the engine runs on CPU because the requested GPU could not be used. */
    @Volatile var fellBackFromGpu: Boolean = false
        private set
    private val inferenceMutex = Mutex()
    // Track active conversations/sessions to support multi-session and handle hardware resource limits
    @Volatile private var activeEngineConversation: LlmConversation? = null
    @Volatile private var activeConversationId: String? = null
    private var activeConversationParams: Triple<Double, Double, Int>? = null

    fun closeConversation(conversationId: String) {
        if (activeConversationId == conversationId) {
            Log.i(TAG, "Closing active conversation: $conversationId")
            closeAllConversations()
        }
    }

    suspend fun loadModel(modelPath: String, backendType: String = "GPU"): Unit = withContext(Dispatchers.IO) {
        if (currentModelPath == modelPath && engine != null) {
            return@withContext
        }

        close()
        Log.d(TAG, "Loading model from $modelPath with Backend=$backendType")

        val file = java.io.File(modelPath)
        if (!file.exists()) {
            Log.e(TAG, "Model file does not exist: $modelPath")
            throw java.io.FileNotFoundException("Model file not found: $modelPath")
        }

        // An unknown name runs GPU, and that is what is used, reported and (on failure) retried on CPU.
        val resolvedBackend = when (backendType.uppercase()) {
            BackendFallbackPolicy.CPU -> BackendFallbackPolicy.CPU
            NPU -> NPU
            else -> BackendFallbackPolicy.GPU
        }
        try {
            val backendEnum = when (resolvedBackend) {
                BackendFallbackPolicy.CPU -> Backend.CPU()
                NPU -> Backend.NPU(nativeLibraryDir = "")
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
            // activeBackend first, so a concurrent reader that sees the engine also sees its backend.
            activeBackend = resolvedBackend
            engine = newEngine
            currentModelPath = modelPath

            Log.d(TAG, "Model loaded successfully with Backend=$resolvedBackend")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load model with Backend=$backendType. Error: ${t.message}", t)
            val fallback = BackendFallbackPolicy.next(resolvedBackend)
            if (fallback != null) {
                 Log.w(TAG, "Retrying with $fallback backend...")
                 loadModel(modelPath, fallback)
                 fellBackFromGpu = true
            } else {
                if (t is Exception) throw t else throw Exception(t)
            }
        }
    }


    fun closeAllConversations() {
        try {
            val currentConversation = activeEngineConversation
            if(currentConversation != null) {
                currentConversation.close()
            }
            else{
                Log.d(TAG, "No active conversation to close")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error closing active conversation", e)
        }
        activeEngineConversation = null
        activeConversationId = null
        activeConversationParams = null
    }

    /**
     * Creates a new engine-level conversation, ensuring only one exists.
     */
    fun createEngineConversation(
        temperature: Double? = null,
        topP: Double? = null,
        topK: Int? = null,
        preamble: String? = null,
        initialMessages: List<Message> = emptyList()
    ): LlmConversation {
        val currentEngine = engine ?: throw IllegalStateException("Model not loaded")
        
        // Enforce single-session limit
        closeAllConversations()

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
        val conversation = currentEngine.createConversation(conversationConfig)
        activeEngineConversation = conversation
        return conversation
    }




    /**
     * Generates a streaming response in a stateful conversation.
     * Recreates the engine conversation from history on every request.
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

        val currentEngine = engine ?: throw IllegalStateException("Model not loaded")
        
        inferenceMutex.withLock {
            try {
                Log.d(TAG, "Processing request for ConversationId=${state.conversationId}. Total messages=${messages.size}")
                
                // Sensitive Logging Removed

                // 1. Update Persistent History
                // Critical: Synchronize history modification to safely interact with ConversationManager.saveConversation
                val fullHistory = synchronized(state) {
                    state.history.addAll(messages)
                    // Return a copy for thread-safe iteration during context recreation
                    state.history.toMutableList()
                }

                val currentParams = Triple(state.temperature, state.topP, state.topK)
                
                // Check if we can reuse the active conversation
                // Optimization: If the incoming request is for the *current* active conversation 
                // AND it's a simple append (1 new message), we reuse the session (KV Cache).
                // AND the sampling parameters haven't changed.
                val isReuse = (activeConversationId == state.conversationId) && 
                              (activeEngineConversation != null) && 
                              (messages.size == 1) &&
                              (activeConversationParams == currentParams)

                val conversation: LlmConversation

                if (isReuse) {
                    Log.d(TAG, "Reusing active conversation for ID=${state.conversationId}")
                    conversation = activeEngineConversation!!
                } else {
                    // 2. Prepare Engine Context (Standard/Switch Flow)
                    val lastMsg = fullHistory.last()
                    // History excluding the new message(s) which will be sent now
                    // We drop only the LAST message, because that one will be sent via sendMessageAsync
                    // to trigger the response. Any intermediate messages (if messages.size > 1) 
                    // must be part of the initial context.
                    val initialMessages = fullHistory.dropLast(1)

                    // 3. Recreate Conversation (Implicitly closes old one via createEngineConversation)
                    Log.d(TAG, "Recreating conversation for ID=${state.conversationId}. History size: ${initialMessages.size}")
                    
                    val systemPrompt = state.systemInstruction ?: "You are a helpful assistant."
                    Log.d(TAG, "System Prompt configured (length=${systemPrompt.length})")
                    
                    conversation = createEngineConversation(
                        temperature = state.temperature,
                        topP = state.topP,
                        topK = state.topK,
                        preamble = systemPrompt,
                        initialMessages = initialMessages.map { toLiteRTMessage(it) }
                    )
                    activeConversationId = state.conversationId
                    activeConversationParams = currentParams
                }

                // 4. Trigger Inference with the last message (Standard Flow)
                val lastMsg = fullHistory.last()
                Log.d(TAG, "Triggering inference with role=${lastMsg.role}")

                var lastResponseText = ""
                
                suspendCancellableCoroutine<Unit> { cont ->
                    cont.invokeOnCancellation {
                        // Note: For stateful conversations, we don't necessarily want to close the KV cache
                        // unless specifically requested, but we should at least stop the current inference.
                        // SDK might not have a dedicated 'stopInference' on Conversation, but closing it
                        // acts as a hard stop.
                        conversation.close() 
                    }
                    conversation.sendMessageAsync(toLiteRTMessage(lastMsg), object : MessageCallback {
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
                            
                            // 5. Update History with Response
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

            } catch (e: Exception) {
                Log.e(TAG, "Error in generateConversationResponseAsync", e)
                onErrorCallback(e)
            }
        }
    }

    /**
     * Sends a "Hello" request to prove the loaded model answers. Returns null on success, otherwise the last error
     * (an engine that loaded can still fail on its first request, for example a GPU without OpenCL).
     *
     * @param onAttempt called after each attempt with its number and the error, or null on success
     */
    suspend fun verifyReady(
        attempts: Int,
        retryDelayMs: Long = PING_RETRY_DELAY_MS,
        onAttempt: (Int, Throwable?) -> Unit = { _, _ -> }
    ): Throwable? {
        var lastError: Throwable? = null
        for (attempt in 1..attempts) {
            val pingState = ConversationState(conversationId = "ping_${System.currentTimeMillis()}", apiToken = "self_test", ttlMs = 60000)
            var response = ""
            var error: Throwable? = null
            try {
                generateConversationResponseAsync(
                    state = pingState,
                    messages = listOf(ChatMessage(role = "user", content = com.google.gson.JsonPrimitive("Hello"))),
                    onToken = {},
                    onComplete = { response = it },
                    onErrorCallback = { error = it }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e
            } finally {
                closeConversation(pingState.conversationId)
            }
            currentCoroutineContext().ensureActive() // generateConversationResponseAsync reports a cancel as an error
            val failure = error ?: if (response.isEmpty()) IllegalStateException("Empty response") else null
            onAttempt(attempt, failure)
            if (failure == null) return null
            lastError = failure
            if (attempt < attempts) delay(retryDelayMs)
        }
        return lastError
    }

    /** Reloads the current model on CPU after the GPU engine failed its readiness check. */
    suspend fun fallBackToCpu(modelPath: String) {
        require(activeBackend == BackendFallbackPolicy.GPU) { "Only a GPU engine falls back to CPU" }
        // Under the inference lock, so no request is using the GPU engine while it is closed.
        inferenceMutex.withLock {
            close()
            loadModel(modelPath, BackendFallbackPolicy.CPU)
            fellBackFromGpu = true
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
        closeAllConversations()
        engine?.close()
        engine = null
        currentModelPath = null
        activeBackend = null
        fellBackFromGpu = false
    }

    companion object {
        private const val TAG = "AiEngineManager"
        private const val PING_RETRY_DELAY_MS = 2000L
        private const val NPU = "NPU"
    }
}

