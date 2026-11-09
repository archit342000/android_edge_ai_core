package com.aanand.edgeaicore

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.BackendType
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.ai.edge.litertlm.ToolCall
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SpeculativeDecodingConfig
import com.google.ai.edge.litertlm.SpeculativeDecodingMode
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import android.util.Base64
import com.google.ai.edge.litertlm.MessageCallback
import com.aanand.edgeaicore.ConversationState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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

    /** The context size the loaded engine was created with (the request clamped to the model's maximum); null = the model's default. */
    @Volatile var effectiveMaxNumTokens: Int? = null
        private set
    private var currentSpeculativeOption: SpeculativeDecodingOption? = null

    /** The encoders of the loaded model and the backends they run on (null = the model has no usable such encoder). */
    @Volatile var activeEncoders: EncoderSummary = EncoderSummary()
        private set

    /** The speculative decoding mode the loaded engine was configured with; null when it runs without MTP. */
    @Volatile var activeSpeculativeMode: SpeculativeDecodingMode? = null
        private set

    /** The backend the loaded engine actually runs on ("CPU", "GPU" or "NPU"); null while no model is loaded. */
    @Volatile var activeBackend: String? = null
        private set

    /** True when the engine runs on CPU because the requested GPU could not be used. */
    @Volatile var fellBackFromGpu: Boolean = false
        private set
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

    /**
     * @param maxNumTokens the KV-cache size (input plus output tokens); null keeps the model's default
     * @param speculativeDecoding the MTP setting; only used when the model ships a drafter
     * @param visionOption backend for the vision encoder (AUTO follows [backendType]); the encoder is only created when the model has one
     * @param audioOption same for the audio encoder
     */
    suspend fun loadModel(modelPath: String, backendType: String = "GPU", cacheDir: String? = null, maxNumTokens: Int? = null,
        speculativeDecoding: SpeculativeDecodingOption = SpeculativeDecodingOption.DEFAULT,
        visionOption: EncoderBackendOption = EncoderBackendOption.AUTO,
        audioOption: EncoderBackendOption = EncoderBackendOption.AUTO
    ): Unit = withContext(Dispatchers.IO) {
        if (currentModelPath == modelPath && currentMaxNumTokens == maxNumTokens && currentSpeculativeOption == speculativeDecoding && engine != null) {
            return@withContext
        }

        // Replace the engine under the inference lock: a queued request then finds no engine ("Model not loaded"), never a closed one.
        inferenceMutex.withLock { releaseEngine() }
        Log.d(TAG, "Loading model from $modelPath with Backend=$backendType")

        val file = java.io.File(modelPath)
        if (!file.exists()) {
            Log.e(TAG, "Model file does not exist: $modelPath")
            throw java.io.FileNotFoundException("Model file not found: $modelPath")
        }

        // Inspect before the native engine is created: an unsupported file must not reach Engine.initialize().
        val inspectStartedAt = System.nanoTime()
        val capabilities = modelInspector(modelPath)
        ModelInspector.validate(capabilities)
        Log.d(TAG, "Model inspected in ${(System.nanoTime() - inspectStartedAt) / 1_000_000} ms")

        loadEngine(modelPath, backendType, capabilities, cacheDir, maxNumTokens, speculativeDecoding, visionOption, audioOption)
    }

    /** Closes the cached conversations and the engine and forgets the loaded model. Call inside [inferenceMutex]. */
    private fun releaseEngine() {
        conversationCache.clear()
        val closeStartedAt = System.nanoTime()
        engine?.close()
        Log.d(TAG, "Engine closed in ${(System.nanoTime() - closeStartedAt) / 1_000_000} ms")
        engine = null
        currentModelPath = null
        currentMaxNumTokens = null
        effectiveMaxNumTokens = null
        currentSpeculativeOption = null
        activeSpeculativeMode = null
        activeEncoders = EncoderSummary()
        modelCapabilities = null
        activeBackend = null
        fellBackFromGpu = false
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
        initialMessages: List<Message> = emptyList(),
        tools: List<ToolProvider> = emptyList(),
        thinkingConfig: ThinkingConfig? = null,
        enableResponseFormat: Boolean = false
    ): LlmConversation {
        val currentEngine = engine ?: throw IllegalStateException("Model not loaded")
        
        Log.d(TAG, "System instruction: ${systemInstruction?.let { "${it.length} chars" } ?: "none (model default)"}")

        val conversationConfig = ConversationConfig(
            systemInstruction = systemInstruction?.let { Contents.of(Content.Text(it)) },
            initialMessages = initialMessages,
            tools = tools,
            automaticToolCalling = false, // the client runs its tools; the model's calls are returned as tool_calls
            thinkingConfig = thinkingConfig,
            enableResponseFormat = enableResponseFormat,
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
        options: GenerationOptions = GenerationOptions(),
        chatOptions: ChatOptions = ChatOptions(),
        onReasoning: (String) -> Unit = {},
        onToolCalls: (List<ToolCall>) -> Unit = {}
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
                val settings = CacheSettings(
                    state.temperature, state.topP, state.topK, state.systemInstruction, state.seed,
                    toolsKey = chatOptions.toolsKey, thinking = chatOptions.thinking, responseFormatEnabled = chatOptions.responseFormatEnabled
                )
                val toolNames = OpenAIToolMapper.toolCallNames(fullHistory)
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
                        initialMessages = context.map { toLiteRTMessage(it, toolNames) },
                        tools = chatOptions.tools,
                        thinkingConfig = chatOptions.thinking,
                        enableResponseFormat = chatOptions.responseFormatEnabled
                    )
                }
                val active: LlmConversation = conversation

                // 3. Trigger Inference with the last message
                val lastMsg = fullHistory.last()
                Log.d(TAG, "Triggering inference with role=${lastMsg.role}")

                var lastResponseText = ""
                var loggedChannels = false

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
                    active.sendMessageAsync(toLiteRTMessage(lastMsg, toolNames), object : MessageCallback {
                        override fun onMessage(message: Message) {
                            if (!cont.isActive) return // cancelled: drop late tokens
                            val chunk = extractText(message.contents)
                            Log.v(TAG, "onMessage: Received chunk length=${chunk.length}")

                            if (message.channels.isNotEmpty()) {
                                if (!loggedChannels) {
                                    loggedChannels = true
                                    Log.d(TAG, "Message channels: ${message.channels.keys}")
                                }
                                val reasoning = message.channels.values.joinToString("")
                                if (reasoning.isNotEmpty()) onReasoning(reasoning)
                            }
                            if (message.toolCalls.isNotEmpty()) onToolCalls(message.toolCalls)

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
                    }, options, chatOptions.responseFormat, chatOptions.thinking)
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

    private fun toEngineBackend(type: BackendType): Backend = if (type == BackendType.GPU) Backend.GPU() else Backend.CPU()

    private fun loadEngine(
        modelPath: String,
        backendType: String,
        capabilities: ModelCapabilities,
        cacheDir: String?,
        maxNumTokens: Int?,
        speculativeDecoding: SpeculativeDecodingOption,
        visionOption: EncoderBackendOption,
        audioOption: EncoderBackendOption
    ) {
        val initStartedAt = System.nanoTime()
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

            // Encoders exist only when the model has them; each runs on a backend it supports
            val vision = EncoderBackendSelector.select(capabilities.visionBackends, resolvedBackend, visionOption)
            val audio = EncoderBackendSelector.select(capabilities.audioBackends, resolvedBackend, audioOption)
            if (vision.overrideIgnored) Log.w(TAG, "Vision encoder override $visionOption is not supported by the model, using ${vision.backend}")
            if (audio.overrideIgnored) Log.w(TAG, "Audio encoder override $audioOption is not supported by the model, using ${audio.backend}")
            Log.d(TAG, "Encoders: vision=${vision.backend ?: "none"}, audio=${audio.backend ?: "none"}")

            val speculativeMode = SpeculativeDecodingOption.toConfigMode(speculativeDecoding, capabilities.speculativeDecoding)
            Log.i(
                TAG,
                if (speculativeMode != null) "Speculative decoding (MTP): $speculativeMode"
                else "Speculative decoding off (${if (capabilities.speculativeDecoding) "user setting" else "unsupported model"})"
            )

            val contextLimit = capabilities.maxContextTokens
            val effectiveTokens = if (maxNumTokens != null && contextLimit > 0 && !capabilities.dynamicContext && maxNumTokens > contextLimit) {
                Log.w(TAG, "Context size $maxNumTokens is above the model's maximum $contextLimit, using $contextLimit")
                contextLimit
            } else maxNumTokens

            val config = EngineConfig(
                modelPath = modelPath,
                backend = backendEnum,
                visionBackend = vision.backend?.let(::toEngineBackend),
                audioBackend = audio.backend?.let(::toEngineBackend),
                cacheDir = cacheDir,
                maxNumTokens = effectiveTokens,
                speculativeDecodingConfig = speculativeMode?.let { SpeculativeDecodingConfig(it) }
            )

            // Needed for per-request token counts; read when the engine is created.
            @OptIn(ExperimentalApi::class)
            ExperimentalFlags.enableBenchmark = true
            Log.d(TAG, "Initializing Engine with config: $config")
            val newEngine = engineFactory(config)
            // activeBackend first, so a concurrent reader that sees the engine also sees its backend.
            activeBackend = resolvedBackend
            // Encoders first, so a concurrent reader that sees the engine also sees its encoders
            activeEncoders = EncoderSummary(vision.backend, audio.backend, vision.overrideIgnored, audio.overrideIgnored)
            engine = newEngine
            currentModelPath = modelPath
            currentMaxNumTokens = maxNumTokens
            effectiveMaxNumTokens = effectiveTokens
            currentSpeculativeOption = speculativeDecoding
            activeSpeculativeMode = speculativeMode
            // Set together with the engine, so it is non-null exactly while a model is loaded.
            modelCapabilities = capabilities

            Log.i(TAG, "Engine initialized in ${(System.nanoTime() - initStartedAt) / 1_000_000} ms (backend=$resolvedBackend)")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load model with Backend=$backendType after ${(System.nanoTime() - initStartedAt) / 1_000_000} ms. Error: ${t.message}", t)
            val fallback = BackendFallbackPolicy.next(resolvedBackend)
            if (fallback != null) {
                 Log.w(TAG, "Retrying with $fallback backend...")
                 loadEngine(modelPath, fallback, capabilities, cacheDir, maxNumTokens, speculativeDecoding, visionOption, audioOption)
                 fellBackFromGpu = true
            } else {
                if (t is Exception) throw t else throw Exception(t)
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
        try {
            for (attempt in 1..attempts) {
                val pingState = ConversationState(conversationId = "ping_${System.currentTimeMillis()}", apiToken = "self_test", ttlMs = 60000)
                var response = ""
                var error: Throwable? = null
                try {
                    generateConversationResponseAsync(
                        state = pingState,
                        messages = listOf(ChatMessage(role = "user", content = com.google.gson.JsonPrimitive("Hello"))),
                        onToken = {},
                        onComplete = { text, _ -> response = text },
                        onErrorCallback = { error = it }
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = e
                }
                currentCoroutineContext().ensureActive() // generateConversationResponseAsync reports a cancel as an error
                val failure = error ?: if (response.isEmpty()) IllegalStateException("Empty response") else null
                onAttempt(attempt, failure)
                if (failure == null) return null
                lastError = failure
                if (attempt < attempts) delay(retryDelayMs)
            }
            return lastError
        } finally {
            // The ping's conversation sits in the cache: nobody will ask for it again.
            clearConversationCache()
        }
    }

    /** Reloads the model on CPU after the GPU engine failed its readiness check. */
    suspend fun fallBackToCpu(
        modelPath: String,
        cacheDir: String? = null,
        maxNumTokens: Int? = null,
        speculativeDecoding: SpeculativeDecodingOption = SpeculativeDecodingOption.DEFAULT,
        visionOption: EncoderBackendOption = EncoderBackendOption.AUTO,
        audioOption: EncoderBackendOption = EncoderBackendOption.AUTO
    ) {
        require(activeBackend == BackendFallbackPolicy.GPU) { "Only a GPU engine falls back to CPU" }
        val startedAt = System.nanoTime()
        fun elapsedMs() = (System.nanoTime() - startedAt) / 1_000_000
        // Under the inference lock, so no request is using the GPU engine while it is closed.
        inferenceMutex.withLock {
            Log.d(TAG, "Fallback: lock acquired after ${elapsedMs()} ms")
            releaseEngine()
            Log.d(TAG, "Fallback: GPU engine released after ${elapsedMs()} ms")
        }
        loadModel(modelPath, BackendFallbackPolicy.CPU, cacheDir, maxNumTokens, speculativeDecoding, visionOption, audioOption)
        Log.d(TAG, "Fallback: CPU engine loaded after ${elapsedMs()} ms")
        fellBackFromGpu = true
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
    private fun toLiteRTMessage(chatMessage: ChatMessage, toolNames: Map<String, String>): Message {
        if (chatMessage.role.equals("tool", ignoreCase = true)) {
            return OpenAIToolMapper.toToolMessage(chatMessage, toolNames)
        }
        val contents = mutableListOf<Content>()
        // Gson parses "content": null as JsonNull, not as a Kotlin null
        val content = chatMessage.content?.takeUnless { it.isJsonNull }

        if (content != null && content.isJsonPrimitive) {
            contents.add(Content.Text(content.asString))
        } else if (content != null && content.isJsonArray) {
            for (element in content.asJsonArray) {
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
        
        val toolCalls = chatMessage.tool_calls.orEmpty().map { OpenAIToolMapper.toToolCall(it) }
        // A message with neither parts nor tool calls falls back to the raw content text (as before)
        if (contents.isEmpty() && toolCalls.isEmpty()) {
            contents.add(Content.Text(content?.toString() ?: ""))
        }

        val lrtContents = Contents.of(contents)
        return if (chatMessage.role.equals("assistant", ignoreCase = true)) {
            Message.model(lrtContents, toolCalls)
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



    fun close() {
        releaseEngine()
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
        private const val PING_RETRY_DELAY_MS = 2000L
        private const val NPU = "NPU"

        /** Idle conversations kept for reuse (a client plus the readiness ping or a second client). */
        private const val MAX_CACHED_CONVERSATIONS = 2

        /** A cached conversation unused for this long is closed, checked lazily on the next request. */
        private const val CACHE_IDLE_TTL_MS = 10 * 60 * 1000L

        /** How long a cancelled request waits for native decoding to stop before releasing the inference lock. */
        private const val CANCEL_WAIT_MS = 2000L
    }
}

