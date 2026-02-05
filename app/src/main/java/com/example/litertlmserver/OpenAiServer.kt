package com.example.litertlmserver

import android.util.Log
import io.ktor.serialization.gson.gson
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

class OpenAiServer(private val aiEngineManager: AiEngineManager) {
    private var server: io.ktor.server.engine.ApplicationEngine? = null

    fun start(port: Int = 8080) {
        if (server != null) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Starting server on port $port")
                server = embeddedServer(Netty, port = port) {
                    install(ContentNegotiation) {
                        gson {
                            setPrettyPrinting()
                        }
                    }
                    routing {
                        post("/v1/chat/completions") {
                            try {
                                val request = call.receive<ChatCompletionRequest>()
                                Log.d(TAG, "Received request: $request")

                                val responseText = aiEngineManager.generateResponse(request.messages)

                                val response = ChatCompletionResponse(
                                    id = "chatcmpl-${UUID.randomUUID()}",
                                    created = System.currentTimeMillis() / 1000,
                                    model = request.model ?: "litertlm-model",
                                    choices = listOf(
                                        Choice(
                                            index = 0,
                                            message = ChatMessageResponse(
                                                role = "assistant",
                                                content = responseText
                                            ),
                                            finish_reason = "stop"
                                        )
                                    ),
                                    usage = Usage(0, 0, 0)
                                )

                                call.respond(response)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error processing request", e)
                                call.respond(io.ktor.http.HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                            }
                        }
                    }
                }.start(wait = true)
            } catch (e: Exception) {
                Log.e(TAG, "Server error", e)
            }
        }
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
        Log.d(TAG, "Server stopped")
    }

    companion object {
        private const val TAG = "OpenAiServer"
    }
}
