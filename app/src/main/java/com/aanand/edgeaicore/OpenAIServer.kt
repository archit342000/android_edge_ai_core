package com.aanand.edgeaicore

import android.util.Log
import com.google.gson.GsonBuilder
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
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class OpenAIServer(
    private val aiEngineManager: AiEngineManager,
    private val tokenManager: TokenManager
) {
    private var server: NettyApplicationEngine? = null
    private val activeRequests = AtomicInteger(0)

    fun start() {
        if (server != null) return
        Log.i(TAG, "Starting OpenAI Server on port 8080...")

        server = embeddedServer(Netty, port = 8080) {
            install(ContentNegotiation) {
                gson {
                    setPrettyPrinting()
                }
            }
            configureRouting()
        }.start(wait = false)
    }

    fun stop() {
        Log.i(TAG, "Stopping OpenAI Server...")
        server?.stop(1000, 2000)
        server = null
    }

    private fun Application.configureRouting() {
        routing {
            route("/v1") {
                // Chat Completions
                post("/chat/completions") {
                    val token = extractToken(call.request.header("Authorization"))
                    if (!tokenManager.isValidToken(token)) {
                        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid or missing API key"))
                        return@post
                    }

                    activeRequests.incrementAndGet()
                    try {
                        val request = call.receive<ChatCompletionRequest>()
                        val conversationId = UUID.randomUUID().toString()

                        // Create transient state for this request
                        val state = ConversationState(
                            conversationId = conversationId,
                            apiToken = token!!,
                            ttlMs = 0, // Transient
                            temperature = request.temperature ?: 0.8,
                            topP = request.top_p ?: 0.95,
                            topK = request.top_k ?: 40
                        )

                        if (request.stream == true) {
                            call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                                val channel = Channel<String>(Channel.UNLIMITED)

                                // Launch inference in a separate coroutine
                                CoroutineScope(Dispatchers.IO).launch {
                                    try {
                                        aiEngineManager.generateConversationResponseAsync(
                                            state = state,
                                            messages = request.messages,
                                            onToken = { token ->
                                                val chunk = ChatCompletionChunk(
                                                    id = "chatcmpl-${UUID.randomUUID()}",
                                                    created = System.currentTimeMillis() / 1000,
                                                    model = request.model ?: "litertlm-model",
                                                    choices = listOf(
                                                        ChunkChoice(
                                                            index = 0,
                                                            delta = ChunkDelta(content = token),
                                                            finish_reason = null
                                                        )
                                                    )
                                                )
                                                val json = GsonBuilder().create().toJson(chunk)
                                                channel.trySend("data: $json\n\n")
                                            },
                                            onComplete = {
                                                channel.trySend("data: [DONE]\n\n")
                                                channel.close()
                                                activeRequests.decrementAndGet()
                                            },
                                            onErrorCallback = { error ->
                                                Log.e(TAG, "Streaming error", error)
                                                val errorJson = GsonBuilder().create().toJson(ErrorResponse(error.message ?: "Unknown error"))
                                                channel.trySend("data: $errorJson\n\n")
                                                channel.close()
                                                activeRequests.decrementAndGet()
                                            }
                                        )
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Streaming launch error", e)
                                        channel.close()
                                        activeRequests.decrementAndGet()
                                    }
                                }

                                // Write to the response stream
                                for (msg in channel) {
                                    write(msg)
                                    flush()
                                }
                            }
                        } else {
                            var finalResponse: String? = null
                            var errorOccurred: Throwable? = null

                            try {
                                // Call the engine
                                aiEngineManager.generateConversationResponseAsync(
                                    state = state,
                                    messages = request.messages,
                                    onToken = { /* No-op for non-streaming */ },
                                    onComplete = { finalResponse = it },
                                    onErrorCallback = { errorOccurred = it }
                                )

                                if (errorOccurred != null) {
                                    Log.e(TAG, "Inference failed", errorOccurred)
                                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse(errorOccurred?.message ?: "Unknown error"))
                                    return@post
                                }

                                if (finalResponse == null) {
                                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Empty response from model"))
                                    return@post
                                }

                                val response = ChatCompletionResponse(
                                    id = "chatcmpl-${UUID.randomUUID()}",
                                    created = System.currentTimeMillis() / 1000,
                                    model = request.model ?: "litertlm-model",
                                    choices = listOf(
                                        Choice(
                                            index = 0,
                                            message = ChatMessageResponse(
                                                role = "assistant",
                                                content = finalResponse!!
                                            ),
                                            finish_reason = "stop"
                                        )
                                    ),
                                    usage = Usage(0, 0, 0)
                                )

                                call.respond(response)
                            } finally {
                                activeRequests.decrementAndGet()
                            }
                        }

                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing request", e)
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Bad Request: ${e.message}"))
                        if (activeRequests.get() > 0) activeRequests.decrementAndGet() // Safety decrement if not streaming
                    }
                }

                // List Models
                get("/models") {
                    val token = extractToken(call.request.header("Authorization"))
                    if (!tokenManager.isValidToken(token)) {
                        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid or missing API key"))
                        return@get
                    }

                    val modelList = ModelListResponse(
                        data = listOf(
                            ModelInfo(
                                id = "litertlm-model",
                                created = System.currentTimeMillis() / 1000
                            )
                        )
                    )
                    call.respond(modelList)
                }

                // Health Check
                get("/health") {
                     val token = extractToken(call.request.header("Authorization"))
                    if (!tokenManager.isValidToken(token)) {
                        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid or missing API key"))
                        return@get
                    }

                    if (aiEngineManager.isModelLoaded) {
                        call.respond(mapOf("status" to "ok"))
                    } else {
                        call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Model not loaded"))
                    }
                }

                // Get Load
                get("/load") {
                    val token = extractToken(call.request.header("Authorization"))
                    if (!tokenManager.isValidToken(token)) {
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
                 if (!tokenManager.isValidToken(token)) {
                     call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid or missing API key"))
                     return@get
                 }
                 if (aiEngineManager.isModelLoaded) {
                     call.respond(mapOf("status" to "ok"))
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

    companion object {
        private const val TAG = "OpenAIServer"
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
    val choices: List<ChunkChoice>
)

data class ChunkChoice(
    val index: Int,
    val delta: ChunkDelta,
    val finish_reason: String?
)

data class ChunkDelta(
    val content: String? = null,
    val role: String? = null
)
