package com.aanand.edgeaicore

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.gson.gson
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.UUID

class EdgeAiServer(
    private val context: Context,
    private val aiEngineManager: AiEngineManager,
    private val tokenManager: TokenManager
) {
    private var server: NettyApplicationEngine? = null
    private val gson = Gson()

    fun start() {
        if (server != null) return

        server = embeddedServer(Netty, port = 8080) {
            install(ContentNegotiation) {
                gson {
                    setPrettyPrinting()
                }
            }

            routing {
                get("/v1/models") {
                    if (!checkAuth(call)) return@get

                    val modelId = if (aiEngineManager.isModelLoaded) "loaded-model" else "none"
                    // Simple OpenAI-like models response
                    val response = mapOf(
                        "object" to "list",
                        "data" to listOf(
                            mapOf(
                                "id" to modelId,
                                "object" to "model",
                                "created" to System.currentTimeMillis() / 1000,
                                "owned_by" to "user"
                            )
                        )
                    )
                    call.respond(response)
                }

                post("/v1/chat/completions") {
                    if (!checkAuth(call)) return@post

                    try {
                        val request = call.receive<ChatCompletionRequest>()

                        // Extract Token from Header for ownership tracking if needed,
                        // though we already validated it exists.
                        val token = extractToken(call) ?: "unknown"

                        // Prepare Conversation State
                        // Stateless request: Create a transient conversation
                        val conversationId = "chatcmpl-${UUID.randomUUID()}"

                        // Extract System Instruction
                        var systemInstruction: String? = null
                        val messagesToProcess = request.messages.toMutableList()

                        // Check if first message is system
                        if (messagesToProcess.isNotEmpty() && messagesToProcess.first().role == "system") {
                            // Extract content
                            val first = messagesToProcess.first()
                            if (first.content.isJsonPrimitive && first.content.asJsonPrimitive.isString) {
                                systemInstruction = first.content.asString
                            } else {
                                // Simple fallback for complex content, taking it as string representation
                                systemInstruction = first.content.toString()
                            }
                            // Remove system message from history to prevent duplication if engine handles preamble separately
                            // AiEngineManager maps preamble to SystemInstruction and the rest to history.
                            messagesToProcess.removeAt(0)
                        }

                        val state = ConversationState(
                            conversationId = conversationId,
                            apiToken = token,
                            systemInstruction = systemInstruction,
                            ttlMs = 0, // Transient
                            temperature = request.temperature ?: 0.8,
                            topP = request.top_p ?: 0.95,
                            topK = request.top_k ?: 40
                        )

                        if (request.stream == true) {
                            handleStreaming(this, state, messagesToProcess, request.model)
                        } else {
                            handleNonStreaming(this, state, messagesToProcess, request.model)
                        }

                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing request", e)
                        call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: "Internal Server Error"))
                    }
                }
            }
        }.start(wait = false)
    }

    private suspend fun checkAuth(call: io.ktor.server.application.ApplicationCall): Boolean {
        val token = extractToken(call)
        if (token == null || !tokenManager.isValidToken(token)) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid API Token"))
            return false
        }
        return true
    }

    private fun extractToken(call: io.ktor.server.application.ApplicationCall): String? {
        val authHeader = call.request.header("Authorization")
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7).trim()
        }
        return null
    }

    private suspend fun handleNonStreaming(
        pipeline: io.ktor.util.pipeline.PipelineContext<Unit, io.ktor.server.application.ApplicationCall>,
        state: ConversationState,
        messages: List<ChatMessage>,
        model: String?
    ) {
        var responseContent = ""
        var errorOccurred: Throwable? = null

        try {
            aiEngineManager.generateConversationResponseAsync(
                state = state,
                messages = messages,
                onToken = { /* Ignore tokens for non-streaming */ },
                onComplete = { fullText -> responseContent = fullText },
                onErrorCallback = { errorOccurred = it }
            )

            if (errorOccurred != null) {
                throw errorOccurred!!
            }

            val response = ChatCompletionResponse(
                id = state.conversationId,
                created = System.currentTimeMillis() / 1000,
                model = model ?: "litertlm-model",
                choices = listOf(
                    Choice(
                        index = 0,
                        message = ChatMessageResponse(
                            role = "assistant",
                            content = responseContent
                        ),
                        finish_reason = "stop"
                    )
                ),
                usage = Usage(0, 0, 0)
            )

            pipeline.call.respond(response)

        } finally {
            // Cleanup transient conversation
            aiEngineManager.closeConversation(state.conversationId)
        }
    }

    private suspend fun handleStreaming(
        pipeline: io.ktor.util.pipeline.PipelineContext<Unit, io.ktor.server.application.ApplicationCall>,
        state: ConversationState,
        messages: List<ChatMessage>,
        model: String?
    ) {
        val channel = Channel<String>(Channel.UNLIMITED)

        // Launch inference in parallel
        CoroutineScope(Dispatchers.IO).launch {
            try {
                aiEngineManager.generateConversationResponseAsync(
                    state = state,
                    messages = messages,
                    onToken = { token -> channel.trySend(token) },
                    onComplete = { channel.close() },
                    onErrorCallback = { e ->
                        Log.e(TAG, "Streaming error", e)
                        channel.close(e)
                    }
                )
            } catch (e: Exception) {
                channel.close(e)
            } finally {
                // We should close conversation AFTER usage, but for streaming, we assume it's done when channel closes.
                // However, we can't easily close it here if we want to support long sessions?
                // But this is a stateless request, so we close it.
                // We'll close it after the channel is consumed in the writer.
            }
        }

        pipeline.call.respondTextWriter(contentType = ContentType.Text.EventStream) {
            try {
                for (token in channel) {
                    val chunkResponse = ChatCompletionChunk(
                        id = state.conversationId,
                        created = System.currentTimeMillis() / 1000,
                        model = model ?: "litertlm-model",
                        choices = listOf(
                            ChunkChoice(
                                index = 0,
                                delta = Delta(content = token),
                                finish_reason = null
                            )
                        )
                    )
                    write("data: ${gson.toJson(chunkResponse)}\n\n")
                    flush()
                }

                // Final [DONE] message
                 val finalChunk = ChatCompletionChunk(
                        id = state.conversationId,
                        created = System.currentTimeMillis() / 1000,
                        model = model ?: "litertlm-model",
                        choices = listOf(
                            ChunkChoice(
                                index = 0,
                                delta = Delta(content = ""),
                                finish_reason = "stop"
                            )
                        )
                    )
                write("data: ${gson.toJson(finalChunk)}\n\n")
                write("data: [DONE]\n\n")
                flush()

            } catch (e: Exception) {
                // If client disconnects, we stop
                Log.w(TAG, "Streaming interrupted: ${e.message}")
            } finally {
                 aiEngineManager.closeConversation(state.conversationId)
            }
        }
    }

    fun stop() {
        server?.stop(1000, 1000)
        server = null
    }

    companion object {
        private const val TAG = "EdgeAiServer"
    }
}

// Data classes for Streaming (Chunks)
data class ChatCompletionChunk(
    val id: String,
    val `object`: String = "chat.completion.chunk",
    val created: Long,
    val model: String,
    val choices: List<ChunkChoice>
)

data class ChunkChoice(
    val index: Int,
    val delta: Delta,
    val finish_reason: String?
)

data class Delta(
    val role: String? = null,
    val content: String? = null
)
