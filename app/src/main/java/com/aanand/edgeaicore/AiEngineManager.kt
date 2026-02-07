package com.aanand.edgeaicore

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import android.util.Base64
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.SessionConfig
import com.google.ai.edge.litertlm.Session as LiteRTSession
import com.aanand.edgeaicore.ConversationState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.launch

class AiEngineManager {
    private var engine: Engine? = null
    private var currentModelPath: String? = null
    private val inferenceMutex = Mutex()
    // Track active conversations/sessions to support multi-session and handle hardware resource limits
    private var activeEngineConversation: Conversation? = null

    // ...

    suspend fun loadModel(modelPath: String, backendType: String = "GPU"): Unit = withContext(Dispatchers.IO) {
        if (currentModelPath == modelPath && engine != null) {
            return@withContext
        }

        close()
        Log.d(TAG, "Loading model from $modelPath with Backend=$backendType")

        try {
            val backendEnum = when (backendType.uppercase()) {
                "CPU" -> Backend.CPU
                "GPU" -> Backend.GPU
                "NPU" -> Backend.NPU
                else -> Backend.GPU
            }

            val config = EngineConfig(
                modelPath = modelPath,
                backend = backendEnum,
                visionBackend = backendEnum,
                audioBackend = Backend.CPU
            )

            val newEngine = Engine(config)
            newEngine.initialize()
            engine = newEngine
            currentModelPath = modelPath

            Log.d(TAG, "Model loaded successfully with Backend=$backendType")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model with Backend=$backendType", e)
            if (backendType.equals("GPU", ignoreCase = true)) {
                 Log.w(TAG, "Retrying with CPU backend...")
                 loadModel(modelPath, "CPU")
            } else {
                throw e
            }
        }
    }


    fun closeAllConversations() {
        try {
            activeEngineConversation?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing active conversation", e)
        }
        activeEngineConversation = null
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
    ): Conversation {
        val currentEngine = engine ?: throw IllegalStateException("Model not loaded")
        
        // Enforce single-session limit
        closeAllConversations()

        val conversationConfig = ConversationConfig(
            systemInstruction = Contents.of(Content.Text(preamble ?: "")),
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

    // ===================================================================
    // Stateless Inference (Legacy - creates new conversation each call)
    // ===================================================================

    suspend fun generateResponse(
        messages: List<ChatMessage>,
        temperature: Double? = null,
        topP: Double? = null,
        topK: Int? = null,
        preamble: String? = null
    ): String = withContext(Dispatchers.IO) {
        val currentEngine = engine ?: throw IllegalStateException("Model not loaded")

        // Separate system, history and last message
        val nonSystemMessages = messages.filter { it.role != "system" }
        
        val lastMessage = nonSystemMessages.lastOrNull() ?: return@withContext "No user message found"
        val history = nonSystemMessages.dropLast(1)

        val lrtLastMessage = toLiteRTMessage(lastMessage)
        val lrtHistory = history.map { toLiteRTMessage(it) }
        
        return@withContext inferenceMutex.withLock {
            Log.d(TAG, "Request starting inference (lock acquired)")
            try {
                val conversation = createEngineConversation(
                    temperature = temperature, 
                    topP = topP, 
                    topK = topK, 
                    preamble = preamble,
                    initialMessages = lrtHistory
                )
                
                val response = conversation.sendMessage(lrtLastMessage)
                val content = response.contents
                val responseText = extractText(content)
                Log.d(TAG, "Received response from engine: $responseText")
                
                activeEngineConversation?.close()
                activeEngineConversation = null
                
                responseText
            } catch (e: Exception) {
                 Log.e(TAG, "Error generating response", e)
                 throw e
            }
        }
    }

    suspend fun generateResponseAsync(
        messages: List<ChatMessage>,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (Throwable) -> Unit,
        temperature: Double? = null,
        topP: Double? = null,
        topK: Int? = null,
        preamble: String? = null
    ) = withContext(Dispatchers.IO) {
        val currentEngine = engine ?: throw IllegalStateException("Model not loaded")

        val nonSystemMessages = messages.filter { it.role != "system" }
        val lastMessage = nonSystemMessages.lastOrNull() ?: return@withContext Unit
        val history = nonSystemMessages.dropLast(1)
        
        val lrtLastMessage = toLiteRTMessage(lastMessage)
        val lrtHistory = history.map { toLiteRTMessage(it) }

        inferenceMutex.withLock {
             try {
                val conversation = createEngineConversation(
                    temperature = temperature, 
                    topP = topP, 
                    topK = topK, 
                    preamble = preamble,
                    initialMessages = lrtHistory
                )
                
                var lastResponseText = ""
                 suspendCancellableCoroutine<Unit> { cont ->
                    conversation.sendMessageAsync(lrtLastMessage, object : MessageCallback {
                        override fun onMessage(message: Message) {
                            val fullText = extractText(message.contents)
                            val newToken = if (fullText.startsWith(lastResponseText)) {
                                fullText.substring(lastResponseText.length)
                            } else {
                                fullText
                            }
                            lastResponseText = fullText
                            if (newToken.isNotEmpty()) onToken(newToken)
                        }
                        override fun onDone() {
                            onComplete(lastResponseText)
                            if (cont.isActive) cont.resume(Unit)
                            // Legacy cleanup
                            activeEngineConversation?.close()
                            activeEngineConversation = null
                        }
                        override fun onError(error: Throwable) {
                            onError(error)
                            if (cont.isActive) cont.resumeWithException(error)
                        }
                    })
                 }
             } catch (e: Exception) {
                 Log.e(TAG, "Error in generateResponseAsync", e)
                 onError(e)
             }
        }
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
        onError: (Throwable) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (messages.isEmpty()) {
            onError(IllegalArgumentException("No messages provided"))
            return@withContext
        }

        val currentEngine = engine ?: throw IllegalStateException("Model not loaded")
        
        inferenceMutex.withLock {
            try {
                Log.d(TAG, "Processing request for ConversationId=${state.conversationId}. Total messages=${messages.size}")
                
                messages.forEachIndexed { index, msg ->
                    Log.d(TAG, "  Incoming Message[$index]: role=${msg.role}, content=${extractTextFromChatMessage(msg)}")
                }

                // 1. Update Persistent History
                state.history.addAll(messages)
                val fullHistory = state.history

                // 2. Prepare Engine Context
                val lastMsg = fullHistory.last()
                val initialMessages = fullHistory.dropLast(1)

                // 3. Recreate Conversation (Implicitly closes old one via createEngineConversation)
                Log.d(TAG, "Recreating conversation for ID=${state.conversationId}. History size: ${initialMessages.size}")
                
                val systemPrompt = state.systemInstruction ?: ""
                
                val conversation = createEngineConversation(
                    temperature = 0.8,
                    topP = 0.95,
                    topK = 40,
                    preamble = systemPrompt,
                    initialMessages = initialMessages.map { toLiteRTMessage(it) }
                )
                state.engineConversation = conversation

                // 4. Trigger Inference with the last message (Standard Flow)
                Log.d(TAG, "Triggering inference with role=${lastMsg.role}")

                var lastResponseText = ""
                
                suspendCancellableCoroutine<Unit> { cont ->
                    conversation.sendMessageAsync(toLiteRTMessage(lastMsg), object : MessageCallback {
                        override fun onMessage(message: Message) {
                            val fullText = extractText(message.contents)
                            val newToken = if (fullText.startsWith(lastResponseText)) {
                                fullText.substring(lastResponseText.length)
                            } else {
                                fullText
                            }
                            lastResponseText = fullText
                            if (newToken.isNotEmpty()) {
                                Log.d(TAG, "Token received: [$newToken]")
                                onToken(newToken)
                            }
                        }

                        override fun onDone() {
                            Log.d(TAG, "Inference complete. Full response: $lastResponseText")
                            // 5. Update History with Response
                            if (lastResponseText.isNotEmpty()) {
                                state.history.add(ChatMessage("assistant", com.google.gson.JsonPrimitive(lastResponseText)))
                            }
                            onComplete(lastResponseText)
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onError(error: Throwable) {
                            Log.e(TAG, "Inference error", error)
                            onError(error)
                            if (cont.isActive) cont.resumeWithException(error)
                        }
                    })
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in generateConversationResponseAsync", e)
                onError(e)
            }
        }
    }


    /**
     * Resets the streaming state for a session.
     */
    fun resetStreamingState(session: LiteRTSession) {
        // No-op for primitive API as state is managed by LiteRTSession itself
    }


    private fun collapseInputData(list: List<InputData>): List<InputData> {
        if (list.isEmpty()) return list
        val result = mutableListOf<InputData>()
        val currentText = StringBuilder()
        
        for (item in list) {
            if (item is InputData.Text) {
                currentText.append(item.text)
            } else {
                if (currentText.isNotEmpty()) {
                    result.add(InputData.Text(currentText.toString()))
                    currentText.clear()
                }
                result.add(item)
            }
        }
        if (currentText.isNotEmpty()) {
            result.add(InputData.Text(currentText.toString()))
        }
        return result
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

    private fun extractText(contents: Any): String {
        return if (contents is Iterable<*>) {
            contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
        } else {
            contents.toString()
        }
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

    private fun processMessageToInputData(
        chatMessage: ChatMessage, 
        targetList: MutableList<InputData>, 
        isLastAndPrefill: Boolean
    ) {
        val role = if (chatMessage.role.equals("assistant", ignoreCase = true)) "model" else "user"
        val currSb = StringBuilder()
        
        currSb.append("<start_of_turn>$role\n")
        
        if (chatMessage.content.isJsonPrimitive) {
             currSb.append(chatMessage.content.asString)
             // Flush immediately for text-only simple case
             targetList.add(InputData.Text(currSb.toString()))
             currSb.clear() // Clear for end token logic
        } else if (chatMessage.content.isJsonArray) {
             // For mixed content, we flush the header first if we encounter non-text
             // Or we accumulate text.
             // Strategy: flushing the header immediately is safer for mixed types, 
             // BUT for text-only parts of an array, we should try to attach to header if possible?
             // Simplest robust approach: Flush header now, handling split risk for multimodal,
             // but assuming text-only array is rare or safe.
             // Actually, to fix the specific "prefill ignored" bug, we care most about the
             // START of the content connecting to the HEADER.
             
             // Let's iterate and handle.
             var isFirstElement = true
             for (element in chatMessage.content.asJsonArray) {
                 if (element.isJsonObject) {
                     val obj = element.asJsonObject
                     val type = obj.get("type")?.asString ?: "text"
                     
                     if (type == "text" && obj.has("text")) {
                         val text = obj.get("text").asString
                         if (isFirstElement) {
                             currSb.append(text)
                             targetList.add(InputData.Text(currSb.toString()))
                             currSb.clear()
                         } else {
                             targetList.add(InputData.Text(text))
                         }
                     } else if ((type == "image_url" && obj.has("image_url")) || 
                               (type == "audio_url" && obj.has("audio_url"))) {
                         
                         // If we have pending header in SB (meaning this is first element AND it's invalid for SB), flush it
                         if (currSb.isNotEmpty()) {
                             targetList.add(InputData.Text(currSb.toString()))
                             currSb.clear()
                         }
                         
                         if (type == "image_url") {
                             val imageUrlObj = obj.get("image_url").asJsonObject
                             val url = imageUrlObj.get("url").asString
                             if (url.startsWith("data:image")) {
                                 try {
                                     val base64Data = url.substringAfter("base64,")
                                     val bytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                                     targetList.add(InputData.Image(bytes))
                                 } catch (e: Exception) {
                                     Log.e(TAG, "Failed to decode base64 image", e)
                                 }
                             }
                         } else {
                             // audio_url
                             val audioUrlObj = obj.get("audio_url").asJsonObject
                             val url = audioUrlObj.get("url").asString
                             if (url.startsWith("data:audio")) {
                                 try {
                                     val base64Data = url.substringAfter("base64,")
                                     val bytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                                     // Assuming Audio input support
                                     try {
                                         targetList.add(InputData.Audio(bytes))
                                     } catch (e: Throwable) {
                                          Log.e(TAG, "Audio input not supported", e)
                                     }
                                 } catch (e: Exception) {
                                     Log.e(TAG, "Failed to decode base64 audio", e)
                                 }
                             }
                         }
                     }
                 }
                 isFirstElement = false
             }
             
             // If SB still has content (e.g. empty array or all skipped), flush
             if (currSb.isNotEmpty()) {
                 targetList.add(InputData.Text(currSb.toString()))
                 currSb.clear()
             }
             
        } else {
             // Fallback for objects/etc
             currSb.append(chatMessage.content.toString())
             targetList.add(InputData.Text(currSb.toString()))
             currSb.clear()
        }
        
        if (!isLastAndPrefill) {
             targetList.add(InputData.Text("<end_of_turn>\n"))
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
    }
}

