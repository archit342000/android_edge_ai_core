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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AiEngineManager {
    private var engine: Engine? = null
    private var currentModelPath: String? = null
    private val inferenceMutex = Mutex()
    // Track active conversations/sessions to support multi-session and handle hardware resource limits
    private val activeConversations = java.util.Collections.synchronizedList(mutableListOf<Conversation>())
    private val activeSessions = java.util.Collections.synchronizedList(mutableListOf<LiteRTSession>())



    suspend fun loadModel(modelPath: String, backendType: String = "GPU"): Unit = withContext(Dispatchers.IO) {
        if (currentModelPath == modelPath && engine != null) {
            // TODO: check if backend changed
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
        val systemMessage = messages.find { it.role == "system" }
        val nonSystemMessages = messages.filter { it.role != "system" }
        
        val lastMessage = nonSystemMessages.lastOrNull() ?: return@withContext "No user message found"
        val history = nonSystemMessages.dropLast(1)

        val lrtLastMessage = toLiteRTMessage(lastMessage)
        val lrtHistory = history.map { toLiteRTMessage(it) }
        
        return@withContext inferenceMutex.withLock {
            Log.d(TAG, "Request starting inference (lock acquired)")
            var conversation: Conversation? = null
            try {
                val conversationConfig = ConversationConfig(
                    systemInstruction = preamble?.let { Contents.of(Content.Text(it)) },
                    initialMessages = lrtHistory,
                    samplerConfig = SamplerConfig(
                        topK = topK ?: 40,
                        topP = topP ?: 0.95,
                        temperature = temperature ?: 0.8
                    )
                )
                conversation = currentEngine.createConversation(conversationConfig)
                val response = conversation!!.sendMessage(lrtLastMessage)
                val content = response.contents
                val responseText = extractText(content)
                Log.d(TAG, "Received response from engine: $responseText")
                responseText
            } catch (e: Exception) {
                 Log.e(TAG, "Error generating response", e)
                 throw e
            } finally {
                conversation?.close()
                Log.d(TAG, "Inference finished (lock released)")
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

        // Separate system, history and last message
        val systemMessage = messages.find { it.role == "system" }
        val nonSystemMessages = messages.filter { it.role != "system" }
        
        val lastMessage = nonSystemMessages.lastOrNull() ?: return@withContext Unit
        val history = nonSystemMessages.dropLast(1)

        val lrtLastMessage = toLiteRTMessage(lastMessage)
        val lrtHistory = history.map { toLiteRTMessage(it) }

        inferenceMutex.withLock {
            Log.d(TAG, "Request starting async inference (lock acquired)")
            var conversation: Conversation? = null
            try {
                val conversationConfig = ConversationConfig(
                    systemInstruction = preamble?.let { Contents.of(Content.Text(it)) },
                    initialMessages = lrtHistory,
                    samplerConfig = SamplerConfig(
                        topK = topK ?: 40,
                        topP = topP ?: 0.95,
                        temperature = temperature ?: 0.8
                    )
                )
                conversation = currentEngine.createConversation(conversationConfig)
                var lastResponseText = ""
                
                suspendCancellableCoroutine<Unit> { cont ->
                    conversation!!.sendMessageAsync(lrtLastMessage, object : MessageCallback {
                        override fun onMessage(message: Message) {
                            val fullText = extractText(message.contents)
                            // Calculate the new token part. 
                            // Note: Native SDK might provide full accumulated text each time.
                            val newToken = if (fullText.startsWith(lastResponseText)) {
                                fullText.substring(lastResponseText.length)
                            } else {
                                fullText
                            }
                            lastResponseText = fullText
                            if (newToken.isNotEmpty()) {
                                onToken(newToken)
                            }
                        }

                        override fun onDone() {
                            onComplete(lastResponseText)
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onError(error: Throwable) {
                            Log.e(TAG, "Async inference error", error)
                            onError(error)
                            if (cont.isActive) cont.resumeWithException(error)
                        }
                    })

                    cont.invokeOnCancellation {
                        // Attempt to cancel if possible, though LiteRT-LM might not support it directly here
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in generateResponseAsync", e)
                onError(e)
                throw e
            } finally {
                conversation?.close()
                Log.d(TAG, "Async inference finished (lock released)")
            }
        }
    }

    // ===================================================================
    // Session-Based Inference (Uses existing conversation for KV cache)
    // ===================================================================

    /**
     * Creates a new conversation for a session.
     * The returned conversation should be stored in the Session object.
     */
    /**
     * Creates a new LiteRT Session.
     */
    fun createLiteRTSession(
        temperature: Double? = null,
        topP: Double? = null,
        topK: Int? = null
    ): LiteRTSession {
        val currentEngine = engine ?: throw IllegalStateException("Model not loaded")

        val samplerConfig = SamplerConfig(
            topK = topK ?: 40,
            topP = topP ?: 0.95,
            temperature = temperature ?: 0.8
        )
        val sessionConfig = SessionConfig(samplerConfig)

        return try {
            val session = currentEngine.createSession(sessionConfig)
            activeSessions.add(session)
            session
        } catch (e: Exception) {
             if (e.message?.contains("FAILED_PRECONDITION", ignoreCase = true) == true || 
                e.message?.contains("session already exists", ignoreCase = true) == true) {
                
                Log.w(TAG, "Hardware session limit reached. Closing oldest session(s) to make room...")
                synchronized(activeSessions) {
                    if (activeSessions.isNotEmpty()) {
                        val oldest = activeSessions.removeAt(0)
                        try { oldest.close() } catch (ex: Exception) { /* ignore */ }
                    }
                }
                // Retry creation
                val session = currentEngine.createSession(sessionConfig)
                activeSessions.add(session)
                session
            } else {
                throw e
            }
        }
    }

    /**
     * Closes all active conversations. Used during model reload or service shutdown.
     */
    fun closeAllConversations() {
        synchronized(activeConversations) {
            val it = activeConversations.iterator()
            while (it.hasNext()) {
                val conv = it.next()
                try { conv.close() } catch (e: Exception) { /* ignore */ }
                it.remove()
            }
        }
        synchronized(activeSessions) {
            val it = activeSessions.iterator()
            while (it.hasNext()) {
                val session = it.next()
                try { session.close() } catch (e: Exception) { /* ignore */ }
                it.remove()
            }
        }
    }

    /**
     * Generates a response using an existing session conversation.
     * This reuses the KV cache from previous messages in the session.
     */
    suspend fun generateResponseWithSession(
        session: Session,
        messages: List<ChatMessage>,
        temperature: Double? = null,
        topP: Double? = null,
        topK: Int? = null,
        preamble: String? = null
    ): Pair<String, Any?> = withContext(Dispatchers.IO) {
        val currentEngine = engine ?: throw IllegalStateException("Model not loaded")

        val paramNonSystemMessages = messages.filter { it.role != "system" }
        if (paramNonSystemMessages.isEmpty()) {
            throw IllegalArgumentException("No user or assistant messages found in request")
        }
        
        return@withContext inferenceMutex.withLock {
            var lrtSession = session.engineSession
            var isNewSession = false
            
            val currentCount = session.processedMessageCount
            val requestCount = paramNonSystemMessages.size
            
            // Determine if we can append or need to reset
            // We implementation strict append-only logic. If request has fewer messages or same count 
            // (but we are re-calling), we assume history modification and reset.
            if (lrtSession != null && requestCount <= currentCount) {
                Log.i(TAG, "Session history mismatch (req=$requestCount, cur=$currentCount). Resetting session.")
                try { lrtSession.close() } catch (e: Exception) { /* ignore */ }
                lrtSession = null
                session.engineSession = null
                session.processedMessageCount = 0
            }
            
            val messagesToProcess: List<ChatMessage>
            
            if (lrtSession == null) {
                Log.d(TAG, "Creating new LiteRT session for session ${session.sessionId.take(8)}...")
                lrtSession = createLiteRTSession(temperature, topP, topK)
                session.engineSession = lrtSession
                session.processedMessageCount = 0
                isNewSession = true
                messagesToProcess = paramNonSystemMessages
            } else {
                 // Append only new messages
                 messagesToProcess = paramNonSystemMessages.drop(currentCount)
            }
            
            if (messagesToProcess.isEmpty()) {
                 return@withLock Pair("", null)
            }

            val inputDataList = mutableListOf<InputData>()
            
            // If new session and preamble exists, prefill it
            if (isNewSession && preamble != null && preamble.isNotEmpty()) {
               inputDataList.add(InputData.Text("<start_of_turn>system\n$preamble<end_of_turn>\n"))
            }

             // Identify if the LAST message in the NEW batch is an Assistant Prefill
            val lastMsg = messagesToProcess.last()
            val isAssistantPrefill = lastMsg.role.equals("assistant", ignoreCase = true)
            
            val lastIndex = messagesToProcess.size - 1
            messagesToProcess.forEachIndexed { index, msg ->
                val isLast = index == lastIndex
                val treatAsPrefill = isLast && isAssistantPrefill
                processMessageToInputData(msg, inputDataList, treatAsPrefill)
            }
            
            if (!isAssistantPrefill) {
                inputDataList.add(InputData.Text("<start_of_turn>model\n"))
            }
            
            Log.d(TAG, "Session running prefill with ${inputDataList.size} InputData items")
            
            try {
                // 1. Run Prefill
                if (inputDataList.isNotEmpty()) {
                    lrtSession?.runPrefill(inputDataList)
                }
                
                // 2. Run Decode
                val responseText = lrtSession?.runDecode() ?: ""
                Log.d(TAG, "Session inference complete: $responseText")
                
                // Update processed count
                // If prefill (Assistant last), we simply caught up to the request.
                // If normal (User last), we generated a NEW assistant turn.
                if (isAssistantPrefill) {
                    session.processedMessageCount = requestCount
                } else {
                    session.processedMessageCount = requestCount + 1
                }
                
                Pair(responseText, null)
            } catch (e: Exception) {
                Log.e(TAG, "Error in session inference", e)
                activeSessions.remove(lrtSession)
                try { lrtSession?.close() } catch (ex: Exception) { /* ignore */ }
                session.engineSession = null
                session.processedMessageCount = 0
                throw e
            }
        }
    }
    
    private fun formatTurn(role: String, content: String): String {
        return "<start_of_turn>$role\n$content<end_of_turn>\n"
    }

    private fun formatPartialTurn(role: String, content: String): String {
        return "<start_of_turn>$role\n$content"
    }

    /**
     * Generates a streaming response using an existing session conversation.
     * This reuses the KV cache from previous messages in the session.
     */
    /**
     * Generates a streaming response using an existing session.
     * This reuses the KV cache from previous messages.
     */
    suspend fun generateResponseAsyncWithSession(
        session: Session,
        messages: List<ChatMessage>,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (Throwable) -> Unit,
        temperature: Double? = null,
        topP: Double? = null,
        topK: Int? = null,
        preamble: String? = null
    ): Any? = withContext(Dispatchers.IO) {
        val currentEngine = engine ?: throw IllegalStateException("Model not loaded")

        val paramNonSystemMessages = messages.filter { it.role != "system" }
        if (paramNonSystemMessages.isEmpty()) {
            throw IllegalArgumentException("No user or assistant messages found in request")
        }

        return@withContext inferenceMutex.withLock {
            var lrtSession = session.engineSession
            var isNewSession = false
            
            val currentCount = session.processedMessageCount
            val requestCount = paramNonSystemMessages.size
            
            if (lrtSession != null && requestCount <= currentCount) {
                Log.i(TAG, "Session async history mismatch (req=$requestCount, cur=$currentCount). Resetting session.")
                try { lrtSession.close() } catch (e: Exception) { /* ignore */ }
                lrtSession = null
                session.engineSession = null
                session.processedMessageCount = 0
            }
            
            val messagesToProcess: List<ChatMessage>
            
            if (lrtSession == null) {
                Log.d(TAG, "Creating new LiteRT session for session ${session.sessionId.take(8)}...")
                lrtSession = createLiteRTSession(temperature, topP, topK)
                session.engineSession = lrtSession
                session.processedMessageCount = 0
                isNewSession = true
                messagesToProcess = paramNonSystemMessages
            } else {
                 messagesToProcess = paramNonSystemMessages.drop(currentCount)
            }
            
            if (messagesToProcess.isEmpty()) {
                 return@withLock null
            }

            val inputDataList = mutableListOf<InputData>()
            if (isNewSession && preamble != null && preamble.isNotEmpty()) {
               inputDataList.add(InputData.Text("<start_of_turn>system\n$preamble<end_of_turn>\n"))
            }
            
            val lastMsg = messagesToProcess.last()
            val isAssistantPrefill = lastMsg.role.equals("assistant", ignoreCase = true)
            
            val lastIndex = messagesToProcess.size - 1
            messagesToProcess.forEachIndexed { index, msg ->
                val isLast = index == lastIndex
                val treatAsPrefill = isLast && isAssistantPrefill
                processMessageToInputData(msg, inputDataList, treatAsPrefill)
            }
            
            if (!isAssistantPrefill) {
                inputDataList.add(InputData.Text("<start_of_turn>model\n"))
            }
            
            Log.d(TAG, "Session async running prefill with ${inputDataList.size} InputData items")
            
            try {
                val responseSb = StringBuilder()
                
                // Branching logic based on turn type
                if (!isAssistantPrefill) {
                     // Standard User Turn: Pass everything to generateContentStream.
                     // It handles prefill + decode internally.
                     suspendCancellableCoroutine<Unit> { cont ->
                        try {
                            lrtSession?.generateContentStream(inputDataList, object : com.google.ai.edge.litertlm.ResponseCallback {
                                override fun onNext(chunk: String) {
                                    responseSb.append(chunk)
                                    onToken(chunk)
                                }
                                override fun onDone() {
                                    onComplete(responseSb.toString())
                                    if (cont.isActive) cont.resume(Unit)
                                }
                                override fun onError(error: Throwable) {
                                    Log.e(TAG, "Session async inference error", error)
                                    onError(error)
                                    if (cont.isActive) cont.resume(Unit)
                                }
                            })
                        } catch (e: Exception) {
                            Log.e(TAG, "Exception calling generateContentStream", e)
                            onError(e)
                            if (cont.isActive) cont.resume(Unit)
                        }
                    }
                } else {
                    // Assistant Continuation: Use Advanced Control (Split Prefill & Decode).
                    // 1. Manually run prefill with the partial assistant message.
                    if (inputDataList.isNotEmpty()) {
                        lrtSession?.runPrefill(inputDataList)
                    }

                    // 2. Trigger streaming decode.
                    // We need a dummy input to satisfy the API. A single space is inclusive and usually harmless.
                    // Empty string ("") causes a tokenizer error.
                    val triggerInput = listOf(InputData.Text(" ")) 
                    
                    suspendCancellableCoroutine<Unit> { cont ->
                        try {
                            lrtSession?.generateContentStream(triggerInput, object : com.google.ai.edge.litertlm.ResponseCallback {
                                override fun onNext(chunk: String) {
                                    responseSb.append(chunk)
                                    onToken(chunk)
                                }
                                override fun onDone() {
                                    onComplete(responseSb.toString())
                                    if (cont.isActive) cont.resume(Unit)
                                }
                                override fun onError(error: Throwable) {
                                    Log.e(TAG, "Session async inference error", error)
                                    onError(error)
                                    if (cont.isActive) cont.resume(Unit)
                                }
                            })
                        } catch (e: Exception) {
                             Log.e(TAG, "Exception calling generateContentStream (continuation)", e)
                             onError(e)
                             if (cont.isActive) cont.resume(Unit)
                        }
                    }
                }
                
                // Update processed count (same logic as sync)
                if (isAssistantPrefill) {
                    session.processedMessageCount = requestCount
                } else {
                    session.processedMessageCount = requestCount + 1
                }
                
                null 
            } catch (e: Exception) {
                Log.e(TAG, "Error in session async inference", e)
                activeSessions.remove(lrtSession)
                try { lrtSession?.close() } catch (ex: Exception) { /* ignore */ }
                session.engineSession = null
                session.processedMessageCount = 0
                throw e
            }
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

