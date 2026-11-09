package com.aanand.edgeaicore

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
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
import java.util.concurrent.atomic.AtomicBoolean

class AiEngineManager(
    private val engineFactory: (EngineConfig) -> LlmEngine = ::createLiteRtEngine
) {
    /** How long a cancelled request waits for native decoding to stop; tests shorten it. */
    @Volatile internal var cancelWaitMs: Long = CANCEL_WAIT_MS

    /** Reads a model's metadata before it is loaded; tests replace it. */
    @Volatile internal var modelInspector: (String) -> ModelCapabilities = ModelInspector()::inspect

    /** What the loaded model supports; null while no model is loaded. */
    @Volatile var modelCapabilities: ModelCapabilities? = null
        private set

    private var engine: LlmEngine? = null
    val isModelLoaded: Boolean get() = engine != null
    private var currentModelPath: String? = null
    private var currentMaxNumTokens: Int? = null
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

    /** @param maxNumTokens the KV-cache size (input plus output tokens); null keeps the model's default */
    suspend fun loadModel(modelPath: String, backendType: String = "GPU", cacheDir: String? = null, maxNumTokens: Int? = null): Unit = withContext(Dispatchers.IO) {
        if (currentModelPath == modelPath && currentMaxNumTokens == maxNumTokens && engine != null) {
            return@withContext
        }

        // Replace the engine under the inference lock: a queued request then finds no engine ("Model not loaded"), never a closed one.
        inferenceMutex.withLock {
            conversationCache.clear()
            engine?.close()
            engine = null
            currentModelPath = null
            currentMaxNumTokens = null
            modelCapabilities = null
        }
        Log.d(TAG, "Loading model from $modelPath with Backend=$backendType")

        val file = java.io.File(modelPath)
        if (!file.exists()) {
            Log.e(TAG, "Model file does not exist: $modelPath")
            throw java.io.FileNotFoundException("Model file not found: $modelPath")
        }

        // Inspect before the native engine is created: an unsupported file must not reach Engine.initialize().
        val capabilities = modelInspector(modelPath)
        ModelInspector.validate(capabilities)

        loadEngine(modelPath, backendType, capabilities, cacheDir, maxNumTokens)
    }

    /**
     * Creates a new engine-level conversation. Call inside [inferenceMutex].
     */
    fun createEngineConversation(
        temperature: Double? = null,
        topP: Double? = null,
        topK: Int? = null,
        seed: Int? = null,
        systemInstruction: String? = null,
        initialMessages: List<Message> = emptyList()
    ): LlmConversation {
        val currentEngine = engine ?: throw IllegalStateException("Model not loaded")
        
        Log.d(TAG, "System instruction: ${systemInstruction?.let { "${it.length} chars" } ?: "none (model default)"}")

        val conversationConfig = ConversationConfig(
            systemInstruction = systemInstruction?.let { Contents.of(Content.Text(it)) },
            initialMessages = initialMessages,
            samplerConfig = SamplerConfig(
                topK = topK ?: 40,
                topP = topP ?: 0.95,
                temperature = temperature ?: 0.8,
                seed = seed ?: 0
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
        onComplete: (String, TokenUsage) -> Unit,
        onErrorCallback: (Throwable) -> Unit,
        options: GenerationOptions = GenerationOptions()
    ) = withContext(Dispatchers.IO) {
        if (messages.isEmpty()) {
            onErrorCallback(IllegalArgumentException("No messages provided"))
            return@withContext
        }

        // A cancel while this request is still queued for the lock is reported here, once.
        try {
            inferenceMutex.lock()
        } catch (e: CancellationException) {
            onErrorCallback(e)
            throw e
        }
        try {
            // Read under the lock: loadModel replaces the engine under it too.
            if (engine == null) throw IllegalStateException("Model not loaded")

            // The conversation in use; closed in the finally block unless it goes back into the cache.
            var conversation: LlmConversation? = null
            var generation: CancellableGeneration? = null
            // Whichever of completion and cancel claims this first is the one that reports (exactly one callback).
            val outcomeReported = AtomicBoolean(false)
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
                val settings = CacheSettings(state.temperature, state.topP, state.topK, state.systemInstruction, state.seed)
                val context = fullHistory.dropLast(1)
                val cached = conversationCache.take(settings, context)?.takeIf { it.isAlive }
                if (cached != null) {
                    Log.d(TAG, "Conversation cache hit, context size=${context.size}")
                    conversation = cached
                } else {
                    Log.d(TAG, "Conversation cache miss, context size=${context.size}")
                    conversation = createEngineConversation(
                        temperature = state.temperature,
                        topP = state.topP,
                        topK = state.topK,
                        seed = state.seed,
                        systemInstruction = state.systemInstruction,
                        initialMessages = context.map { toLiteRTMessage(it) }
                    )
                }
                val active: LlmConversation = conversation

                // 3. Trigger Inference with the last message
                val lastMsg = fullHistory.last()
                Log.d(TAG, "Triggering inference with role=${lastMsg.role}")

                var lastResponseText = ""

                // cancelProcess() throws on a closed conversation, so skip it then. A close can still race in
                // between, and an exception here would escape the cancellation handler, so log it instead.
                val stopNative: () -> Unit = {
                    try {
                        if (active.isAlive) active.cancelProcess()
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
                    active.sendMessageAsync(toLiteRTMessage(lastMsg), object : MessageCallback {
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
                            // Read the counts first: a generation whose usage cannot be read is reported as an error, never as zeros.
                            val usage = try {
                                readUsage(active)
                            } catch (t: Throwable) {
                                Log.e(TAG, "Could not read token usage", t)
                                // The conversation is closed in the finally block (not cached): the engine holds a reply that the history will not.
                                if (cont.isActive) cont.resumeWithException(UsageUnavailableException(t))
                                return
                            }
                            Log.d(TAG, "Inference complete for ID=${state.conversationId}. Total Response Length: ${lastResponseText.length}")

                            // 4. Update History with Response
                            synchronized(state) {
                                if (lastResponseText.isNotEmpty()) {
                                    state.history.add(ChatMessage("assistant", com.google.gson.JsonPrimitive(lastResponseText)))
                                }
                            }
                            onComplete(lastResponseText, usage)
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
                    }, options)
                    // Cancelled between the check above and the send: the handler's cancelProcess() found nothing to
                    // stop, so stop the decoding that has just started.
                    if (cont.isCancelled) stopNative()
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
                // The caller was cancelled (for example a streaming client disconnected). Wait for native decoding
                // to stop so the next request does not start on a busy engine; the conversation is then closed in the
                // finally block (never cached: its KV state holds a partial reply). If the wait times out, the lock is
                // still released below and native decoding may still be running; that is logged as a warning.
                val stopped = generation?.awaitStopped(cancelWaitMs) ?: true
                val stopMessage = "Generation stopped ${generation?.msSinceCancel() ?: 0} ms after cancel (stopped=$stopped)"
                if (stopped) Log.i(TAG, stopMessage) else Log.w(TAG, stopMessage)
                if (outcomeReported.compareAndSet(false, true)) onErrorCallback(e)
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error in generateConversationResponseAsync", e)
                onErrorCallback(e)
            } finally {
                // An error, a cancel or an empty reply leaves the conversation's KV state unknown: never cache it.
                conversation?.let { closeQuietly(it) }
            }
        } finally {
            inferenceMutex.unlock()
        }
    }

    private fun loadEngine(modelPath: String, backendType: String, capabilities: ModelCapabilities, cacheDir: String?, maxNumTokens: Int?) {
        val initStartedAt = System.nanoTime()
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
                audioBackend = Backend.CPU(),
                cacheDir = cacheDir,
                maxNumTokens = maxNumTokens
            )

            // Needed for per-request token counts; read when the engine is created.
            @OptIn(ExperimentalApi::class)
            ExperimentalFlags.enableBenchmark = true
            Log.d(TAG, "Initializing Engine with config: $config")
            val newEngine = engineFactory(config)
            engine = newEngine
            currentModelPath = modelPath
            currentMaxNumTokens = maxNumTokens
            // Set together with the engine, so it is non-null exactly while a model is loaded.
            modelCapabilities = capabilities

            Log.i(TAG, "Engine initialized in ${(System.nanoTime() - initStartedAt) / 1_000_000} ms (backend=$backendType)")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load model with Backend=$backendType after ${(System.nanoTime() - initStartedAt) / 1_000_000} ms. Error: ${t.message}", t)
            if (backendType.equals("GPU", ignoreCase = true)) {
                 Log.w(TAG, "Retrying with CPU backend...")
                 loadEngine(modelPath, "CPU", capabilities, cacheDir, maxNumTokens)
            } else {
                if (t is Exception) throw t else throw Exception(t)
            }
        }
    }

    // ===================================================================
    // Helper Methods
    // ===================================================================

    /** Token counts of the generation that just finished, as the official LiteRT-LM server computes them. */
    private fun readUsage(conversation: LlmConversation): TokenUsage {
        val benchmark = conversation.getBenchmarkInfo()
        return TokenUsage.from(benchmark.lastPrefillTokenCount, benchmark.lastDecodeTokenCount, conversation.getTokenCount())
    }

    /** `system` and `developer` messages never get here: [SystemPromptSplitter] turns them into the system instruction. */
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
        currentMaxNumTokens = null
        modelCapabilities = null
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

        /** How long a cancelled request waits for native decoding to stop before releasing the inference lock. */
        private const val CANCEL_WAIT_MS = 2000L
    }
}

