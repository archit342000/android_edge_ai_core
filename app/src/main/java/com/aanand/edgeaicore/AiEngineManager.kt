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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

class AiEngineManager(
    private val engineFactory: (EngineConfig) -> LlmEngine = ::createLiteRtEngine
) {
    /** How long a cancelled request waits for native decoding to stop; tests shorten it. */
    @Volatile internal var cancelWaitMs: Long = CANCEL_WAIT_MS
    private var engine: LlmEngine? = null
    val isModelLoaded: Boolean get() = engine != null
    private var currentModelPath: String? = null
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
        
        // A cancel while this request is still queued for the lock is reported here, once.
        try {
            inferenceMutex.lock()
        } catch (e: CancellationException) {
            onErrorCallback(e)
            throw e
        }
        try {
            var generation: CancellableGeneration? = null
            // Whichever of completion and cancel claims this first is the one that reports (exactly one callback).
            val outcomeReported = AtomicBoolean(false)
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
                
                // cancelProcess() throws on a closed conversation, so skip it then. A close can still race in
                // between, and an exception here would escape the cancellation handler, so log it instead.
                val stopNative: () -> Unit = {
                    try {
                        if (conversation.isAlive) conversation.cancelProcess()
                    } catch (e: Exception) {
                        Log.w(TAG, "cancelProcess failed", e)
                    }
                }
                val currentGeneration = CancellableGeneration(cancelAction = stopNative)
                generation = currentGeneration

                suspendCancellableCoroutine<Unit> { cont ->
                    // Stop native decoding (the conversation and its KV state are closed after the wait below).
                    cont.invokeOnCancellation { currentGeneration.cancel() }
                    // Cancelled before the send (for example while the conversation was being built): the handler
                    // above has already run and cancelProcess() would find nothing to stop, so never start decoding.
                    if (!cont.isActive) {
                        currentGeneration.finish()
                        return@suspendCancellableCoroutine
                    }
                    conversation.sendMessageAsync(toLiteRTMessage(lastMsg), object : MessageCallback {
                        override fun onMessage(message: Message) {
                            if (!cont.isActive) return // cancelled: drop late tokens
                            val chunk = extractText(message.contents)
                            Log.v(TAG, "onMessage: Received chunk length=${chunk.length}")
                            
                            if (chunk.isNotEmpty()) {
                                lastResponseText += chunk
                                onToken(chunk)
                            }
                        }

                        override fun onDone() {
                            currentGeneration.finish()
                            // A cancelled request reports the cancel once, not a completion.
                            if (!cont.isActive || !outcomeReported.compareAndSet(false, true)) return
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
                            currentGeneration.finish()
                            // A cancelled request reports the cancel once, from the catch below.
                            if (!cont.isActive) return
                            Log.e(TAG, "Inference error", error)
                            // Resolve the double-error callback issue by only calling resumeWithException
                            // The outer try-catch will handle calling onErrorCallback
                            if (cont.isActive) cont.resumeWithException(error)
                        }
                    })
                    // Cancelled between the check above and the send: the handler's cancelProcess() found nothing to
                    // stop, so stop the decoding that has just started.
                    if (cont.isCancelled) stopNative()
                }

            } catch (e: CancellationException) {
                // The caller was cancelled (for example a streaming client disconnected). Wait for native decoding
                // to stop so the next request does not start on a busy engine, then drop the conversation: its KV
                // state holds a partial reply. If the wait times out, the lock is still released below and native
                // decoding may still be running; that is logged as a warning.
                val stopped = generation?.awaitStopped(cancelWaitMs) ?: true
                closeAllConversations()
                val stopMessage = "Generation stopped ${generation?.msSinceCancel() ?: 0} ms after cancel (stopped=$stopped)"
                if (stopped) Log.i(TAG, stopMessage) else Log.w(TAG, stopMessage)
                if (outcomeReported.compareAndSet(false, true)) onErrorCallback(e)
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error in generateConversationResponseAsync", e)
                onErrorCallback(e)
            }
        } finally {
            inferenceMutex.unlock()
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
    }

    companion object {
        private const val TAG = "AiEngineManager"

        /** How long a cancelled request waits for native decoding to stop before releasing the inference lock. */
        private const val CANCEL_WAIT_MS = 2000L
    }
}

