package com.example.litertlmserver

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AiEngineManager {
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var currentModelPath: String? = null

    suspend fun loadModel(modelPath: String) = withContext(Dispatchers.IO) {
        if (currentModelPath == modelPath && engine != null) {
            return@withContext
        }

        close()
        Log.d(TAG, "Loading model from $modelPath")

        try {
            val config = EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU,
                visionBackend = Backend.GPU,
                audioBackend = Backend.CPU
            )

            val newEngine = Engine(config)
            newEngine.initialize()
            engine = newEngine
            currentModelPath = modelPath

            // Create initial conversation
            conversation = newEngine.createConversation(ConversationConfig())

            Log.d(TAG, "Model loaded successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
            throw e
        }
    }

    suspend fun generateResponse(messages: List<ChatMessage>): String = withContext(Dispatchers.IO) {
        val currentConv = conversation ?: throw IllegalStateException("Model not loaded")

        // We only take the last user message, ignoring history passed in request
        // because we maintain our own stateful conversation.
        val lastMessage = messages.lastOrNull { it.role == "user" }
            ?: messages.last()

        val lastText = extractText(lastMessage)

        // Use Message.Companion.of(String) -> Message.of(String) in Kotlin
        val response = currentConv.sendMessage(Message.of(lastText))

        val sb = StringBuilder()
        for (content in response.contents) {
            if (content is Content.Text) {
                sb.append(content.text)
            }
        }
        return@withContext sb.toString()
    }

    private fun extractText(chatMessage: ChatMessage): String {
        if (chatMessage.content.isJsonPrimitive) {
            return chatMessage.content.asString
        } else if (chatMessage.content.isJsonArray) {
             val sb = StringBuilder()
             for (element in chatMessage.content.asJsonArray) {
                 if (element.isJsonObject) {
                     val obj = element.asJsonObject
                     if (obj.has("text")) {
                         sb.append(obj.get("text").asString)
                     }
                 }
             }
             return sb.toString()
        }
        return chatMessage.content.toString()
    }

    fun close() {
        conversation?.close()
        engine?.close()
        conversation = null
        engine = null
        currentModelPath = null
    }

    companion object {
        private const val TAG = "AiEngineManager"
    }
}
