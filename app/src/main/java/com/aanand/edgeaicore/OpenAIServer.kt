package com.aanand.edgeaicore

import android.util.Log
import com.google.gson.GsonBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.serialization.gson.gson
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.header
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class OpenAIServer(
    private val aiEngineManager: AiEngineManager,
    /** Calls the callback when the call's client goes away; null = not watched. A seam so tests can simulate a disconnect. */
    private val onClientGone: (ApplicationCall, () -> Unit) -> DisposableHandle? = NettyDisconnect::watch,
    private val isValidToken: (String?) -> Boolean
) {
    /** Chat requests held at once (one running, the rest waiting); more are refused at once. Tests shorten these. */
    @Volatile internal var maxActiveRequests: Int = MAX_ACTIVE_CHAT_REQUESTS
    @Volatile internal var queueWaitMs: Long = QUEUE_WAIT_MS
    @Volatile internal var generationTimeoutMs: Long = GENERATION_TIMEOUT_MS

    private var server: NettyApplicationEngine? = null
    private val activeRequests = AtomicInteger(0)

    fun start() {
        if (server != null) return
        Log.i(TAG, "Starting OpenAI Server on port 8080...")

        server = embeddedServer(Netty, port = 8080) {
            module()
        }.start(wait = false)
    }

    fun stop() {
        Log.i(TAG, "Stopping OpenAI Server...")
        server?.stop(1000, 2000)
        server = null
    }

    /** Installs JSON serialisation and the routes. Used by [start] and by route tests. */
    internal fun Application.module() {
        install(ContentNegotiation) {
            gson {
                setPrettyPrinting()
            }
        }
        configureRouting()
    }

    private fun Application.configureRouting() {
        routing {
            route("/v1") {
                // Chat Completions
                post("/chat/completions") {
                    val token = extractToken(call.request.header("Authorization"))
                    if (!isValidToken(token)) {
                        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid or missing API key"))
                        return@post
                    }

                    val held = activeRequests.incrementAndGet()
                    try {
                        if (held > maxActiveRequests) {
                            Log.w(TAG, "Refusing a chat request: $held requests held")
                            call.response.headers.append(HttpHeaders.RetryAfter, RETRY_AFTER_SECONDS)
                            call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse(EngineBusyException.MESSAGE))
                            return@post
                        }
                        val request = receiveChatRequest(call)
                        // Media input needs an encoder; a model without one gets a fixed 400 instead of a native failure
                        val encoders = aiEngineManager.activeEncoders
                        val unsupportedInput = when {
                            aiEngineManager.isModelLoaded && MessageParts.hasImage(request.messages) && !encoders.supportsImages -> "Model does not support image input"
                            aiEngineManager.isModelLoaded && MessageParts.hasAudio(request.messages) && !encoders.supportsAudio -> "Model does not support audio input"
                            else -> null
                        }
                        if (unsupportedInput != null) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse(unsupportedInput))
                            return@post
                        }
                        val validationError = ChatRequestValidator.validate(request)
                        if (validationError != null) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse(validationError))
                            return@post
                        }
                        // Tools, response format, reasoning effort and tool messages: invalid input is a fixed 400
                        val chatOptions = try {
                            OpenAIToolMapper.prepare(request)
                        } catch (e: IllegalArgumentException) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Invalid request"))
                            return@post
                        }
                        val options = GenerationOptions(
                            maxOutputTokens = request.maxOutputTokens,
                            presencePenalty = request.presence_penalty?.toFloat(),
                            frequencyPenalty = request.frequency_penalty?.toFloat()
                        )
                        val conversationId = UUID.randomUUID().toString()

                        // System messages become the system instruction; the rest are the conversation turns.
                        val (systemInstruction, turns) = SystemPromptSplitter.split(request.messages)
                        if (turns.isEmpty()) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("messages must include a non-system message"))
                            return@post
                        }

                        // Create transient state for this request
                        val state = ConversationState(
                            conversationId = conversationId,
                            apiToken = token!!,
                            ttlMs = 0, // Transient
                            systemInstruction = systemInstruction,
                            temperature = request.temperature ?: 0.8,
                            topP = request.top_p ?: 0.95,
                            topK = request.top_k ?: 40,
                            seed = request.seed ?: 0
                        )

                        if (request.stream == true) {
                            try {
                                call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                                    val channel = Channel<String>(Channel.UNLIMITED)
                                    // Updated from the engine's callback thread, read when the reply completes
                                    val streamedToolCalls = java.util.concurrent.atomic.AtomicInteger()
                                    val gson = GsonBuilder().create()
                                    fun sendDelta(delta: ChunkDelta, finishReason: String? = null) {
                                        val chunk = ChatCompletionChunk(
                                            id = "chatcmpl-${UUID.randomUUID()}",
                                            created = System.currentTimeMillis() / 1000,
                                            model = request.model ?: DEFAULT_MODEL_ID,
                                            choices = listOf(ChunkChoice(index = 0, delta = delta, finish_reason = finishReason))
                                        )
                                        channel.trySend("data: ${gson.toJson(chunk)}\n\n")
                                    }

                                    // The generation job is a child of this call: a failed write cancels it, which
                                    // cancels native decoding (AiEngineManager) and frees the inference lock.
                                    coroutineScope {
                                        val job = launch(Dispatchers.IO) {
                                            try {
                                                aiEngineManager.generateConversationResponseAsync(
                                                    state = state,
                                                    messages = turns,
                                                    chatOptions = chatOptions,
                                                    queueWaitMs = queueWaitMs,
                                                    generationTimeoutMs = generationTimeoutMs,
                                                    onReasoning = { sendDelta(ChunkDelta(reasoning_content = it)) },
                                                    onToolCalls = { calls ->
                                                        val mapped = OpenAIToolMapper.toOpenAiToolCalls(calls)
                                                            .map { it.copy(index = streamedToolCalls.getAndIncrement()) }
                                                        sendDelta(ChunkDelta(tool_calls = mapped))
                                                    },
                                                    onToken = { token -> sendDelta(ChunkDelta(content = token)) },
                                                    onComplete = { _, usage, stopped ->
                                                        sendDelta(ChunkDelta(), FinishReason.of(streamedToolCalls.get() > 0, usage.completionTokens, request.maxOutputTokens, stopped))
                                                        if (request.stream_options?.include_usage == true) {
                                                            val usageChunk = ChatCompletionChunk(
                                                                id = "chatcmpl-${UUID.randomUUID()}",
                                                                created = System.currentTimeMillis() / 1000,
                                                                model = request.model ?: DEFAULT_MODEL_ID,
                                                                choices = emptyList(),
                                                                usage = usage.toUsage()
                                                            )
                                                            channel.trySend("data: ${GsonBuilder().create().toJson(usageChunk)}\n\n")
                                                        }
                                                        channel.trySend("data: [DONE]\n\n")
                                                        channel.close()
                                                    },
                                                    onErrorCallback = { error ->
                                                        // A cancel is a client disconnect, not an error to send.
                                                        if (error !is CancellationException) {
                                                            Log.e(TAG, "Streaming error", error)
                                                            val errorJson = GsonBuilder().create().toJson(ErrorResponse(error.message ?: "Unknown error"))
                                                            channel.trySend("data: $errorJson\n\n")
                                                        }
                                                        channel.close()
                                                    },
                                                    options = options
                                                )
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (e: Exception) {
                                                Log.e(TAG, "Streaming launch error", e)
                                                val errorJson = GsonBuilder().create().toJson(ErrorResponse(e.message ?: "Unknown error"))
                                                channel.trySend("data: $errorJson\n\n")
                                                channel.close()
                                            }
                                        }

                                        // Write to the response stream
                                        OpenAIServer.forwardChunks(channel, job) { msg ->
                                            write(msg)
                                            flush()
                                        }
                                    }
                                }
                            } catch (e: IOException) {
                                // The connection failed; a failed write is already handled (and logged) by forwardChunks.
                                Log.d(TAG, "Streaming response closed after an I/O error")
                            }
                        } else {
                            var finalResponse: String? = null
                            var finalUsage: TokenUsage? = null
                            var finalStopped = false
                            var errorOccurred: Throwable? = null
                            val reasoning = StringBuilder()
                            val toolCalls = mutableListOf<com.google.ai.edge.litertlm.ToolCall>()

                            // The generation is a child job so a client disconnect can cancel it (and with it native decoding).
                            var clientGone = false
                            coroutineScope {
                                val generation = launch(Dispatchers.IO) {
                                    aiEngineManager.generateConversationResponseAsync(
                                        state = state,
                                        messages = turns,
                                        onToken = { /* No-op for non-streaming */ },
                                        onComplete = { text, usage, stopped -> finalResponse = text; finalUsage = usage; finalStopped = stopped },
                                        onErrorCallback = { errorOccurred = it },
                                        options = options,
                                        chatOptions = chatOptions,
                                        queueWaitMs = queueWaitMs,
                                        generationTimeoutMs = generationTimeoutMs,
                                        onReasoning = { reasoning.append(it) },
                                        onToolCalls = { toolCalls.addAll(it) }
                                    )
                                }
                                val watcher = onClientGone(call) {
                                    clientGone = true
                                    generation.cancel()
                                }
                                try {
                                    generation.join()
                                } finally {
                                    watcher?.dispose()
                                }
                            }
                            if (clientGone) {
                                Log.i(TAG, "Generation cancelled (client disconnected)")
                                return@post
                            }

                            if (errorOccurred is EngineBusyException) {
                                call.response.headers.append(HttpHeaders.RetryAfter, RETRY_AFTER_SECONDS)
                                call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse(EngineBusyException.MESSAGE))
                                return@post
                            }
                            if (errorOccurred is GenerationTimeoutException) {
                                call.respond(HttpStatusCode.GatewayTimeout, ErrorResponse(GenerationTimeoutException.MESSAGE))
                                return@post
                            }
                            if (errorOccurred != null) {
                                Log.e(TAG, "Inference failed", errorOccurred)
                                call.respond(HttpStatusCode.InternalServerError, ErrorResponse(errorOccurred?.message ?: "Unknown error"))
                                return@post
                            }

                            val text = finalResponse
                            val usage = finalUsage
                            if (text == null || usage == null) {
                                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Empty response from model"))
                                return@post
                            }

                            val response = ChatCompletionResponse(
                                id = "chatcmpl-${UUID.randomUUID()}",
                                created = System.currentTimeMillis() / 1000,
                                model = request.model ?: DEFAULT_MODEL_ID,
                                choices = listOf(
                                    Choice(
                                        index = 0,
                                        message = ChatMessageResponse(
                                            role = "assistant",
                                            content = text.takeIf { toolCalls.isEmpty() || it.isNotBlank() },
                                            tool_calls = toolCalls.takeIf { it.isNotEmpty() }?.let { calls -> OpenAIToolMapper.toOpenAiToolCalls(calls).map { it.copy(index = null) } }, // index is for streaming only
                                            reasoning_content = reasoning.toString().takeIf { it.isNotEmpty() }
                                        ),
                                        finish_reason = FinishReason.of(toolCalls.isNotEmpty(), usage.completionTokens, request.maxOutputTokens, finalStopped)
                                    )
                                ),
                                usage = usage.toUsage()
                            )

                            call.respond(response)
                        }

                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: InvalidRequestBodyException) {
                        // Never the exception message: it can name internal classes or echo the input.
                        Log.w(TAG, "Rejected request body: ${e.causeClass}")
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(INVALID_BODY_MESSAGE))
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing request", e)
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Bad Request: ${e.message}"))
                    } finally {
                        activeRequests.decrementAndGet()
                    }
                }

                // List Models
                get("/models") {
                    val token = extractToken(call.request.header("Authorization"))
                    if (!isValidToken(token)) {
                        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid or missing API key"))
                        return@get
                    }

                    val modelList = ModelListResponse(
                        data = listOf(
                            ModelInfo(
                                id = DEFAULT_MODEL_ID,
                                created = System.currentTimeMillis() / 1000
                            )
                        )
                    )
                    call.respond(modelList)
                }

                // Health Check
                get("/health") {
                     val token = extractToken(call.request.header("Authorization"))
                    if (!isValidToken(token)) {
                        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid or missing API key"))
                        return@get
                    }

                    if (aiEngineManager.isModelLoaded) {
                        call.respond(HealthResponse("ok", aiEngineManager.activeBackend))
                    } else {
                        call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Model not loaded"))
                    }
                }

                // Get Load
                get("/load") {
                    val token = extractToken(call.request.header("Authorization"))
                    if (!isValidToken(token)) {
                        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid or missing API key"))
                        return@get
                    }

                    if (!aiEngineManager.isModelLoaded) {
                        call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Model not loaded"))
                        return@get
                    }

                    call.respond(mapOf("load" to activeRequests.get()))
                }
            }

            // Health check at root
             get("/health") {
                 val token = extractToken(call.request.header("Authorization"))
                 if (!isValidToken(token)) {
                     call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid or missing API key"))
                     return@get
                 }
                 if (aiEngineManager.isModelLoaded) {
                     call.respond(HealthResponse("ok", aiEngineManager.activeBackend))
                 } else {
                     call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Model not loaded"))
                 }
             }
        }
    }

    private fun extractToken(header: String?): String? {
        if (header == null) return null
        return if (header.startsWith("Bearer ", ignoreCase = true)) {
            header.substring(7).trim()
        } else {
            header.trim()
        }
    }

    /** The request body could not be read as a chat request; carries only the class name of the cause. */
    private class InvalidRequestBodyException(val causeClass: String) : Exception(INVALID_BODY_MESSAGE)

    /**
     * Reads the chat request. Anything that goes wrong (malformed JSON, wrong types, an empty body, a wrong content
     * type) or a missing required field (Gson leaves a non-null Kotlin property null) becomes [InvalidRequestBodyException].
     */
    private suspend fun receiveChatRequest(call: ApplicationCall): ChatCompletionRequest {
        try {
            val request = call.receive<ChatCompletionRequest>()
            // Gson can leave these null although the types say otherwise
            val messages: List<ChatMessage?>? = request.messages
            if (messages == null || messages.any { it == null || (it.role as String?) == null }) {
                throw InvalidRequestBodyException("MissingField")
            }
            return request
        } catch (e: CancellationException) {
            throw e
        } catch (e: InvalidRequestBodyException) {
            throw e
        } catch (e: Exception) {
            throw InvalidRequestBodyException(e.javaClass.simpleName)
        }
    }

    companion object {
        private const val TAG = "OpenAIServer"

        /** The only text a client sees when the request body cannot be read. */
        const val INVALID_BODY_MESSAGE = "Invalid request body"

        /** Model id echoed back when the request names none. */
        private const val DEFAULT_MODEL_ID = "litertlm-model"

        /** One running chat request plus three waiting; the next is refused with 503. */
        const val MAX_ACTIVE_CHAT_REQUESTS = 4

        /** How long a request may wait for the engine before a 503. */
        const val QUEUE_WAIT_MS = 120_000L

        /** How long one generation may run before it is stopped with a 504. */
        const val GENERATION_TIMEOUT_MS = 600_000L

        private const val RETRY_AFTER_SECONDS = "5"

        /**
         * Copies [channel] to the client with [write]. If a write fails because the client disconnected, cancels the
         * generation [job] (which cancels native decoding) and, for a cancellation-style failure, rethrows it.
         */
        internal suspend fun forwardChunks(channel: ReceiveChannel<String>, job: Job, write: (String) -> Unit) {
            try {
                for (msg in channel) write(msg)
            } catch (e: IOException) {
                Log.i(TAG, "Generation cancelled (client disconnected)")
                job.cancel()
            } catch (e: CancellationException) {
                // Ktor reports a write to an already closed channel this way too.
                Log.i(TAG, "Generation cancelled (client disconnected)")
                job.cancel()
                throw e
            }
        }
    }
}

// Additional Data Classes needed for the Server

data class ModelListResponse(
    val `object`: String = "list",
    val data: List<ModelInfo>
)

data class ModelInfo(
    val id: String,
    val `object`: String = "model",
    val created: Long,
    val owned_by: String = "user"
)

data class ChatCompletionChunk(
    val id: String,
    val `object`: String = "chat.completion.chunk",
    val created: Long,
    val model: String,
    val choices: List<ChunkChoice>,
    val usage: Usage? = null
)

data class ChunkChoice(
    val index: Int,
    val delta: ChunkDelta,
    val finish_reason: String?
)

data class ChunkDelta(
    val content: String? = null,
    val role: String? = null,
    val reasoning_content: String? = null,
    val tool_calls: List<ToolCallDto>? = null
)
