package com.aanand.edgeaicore

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class InferenceService : Service() {

    private val aiEngineManager = AiEngineManager()
    private lateinit var tokenManager: TokenManager
    private val conversationManager = ConversationManager(
        onConversationRemoved = { id -> aiEngineManager.closeConversation(id) }
    )
    private val gson = Gson()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeRequests = AtomicInteger(0)

    private val binder = object : IInferenceService.Stub() {
        
        // =====================
        // Token Management
        // =====================
        
        override fun generateApiToken(): String {
            logIpcRequest("generateApiToken", "none")
            val callingUid = getCallingUid()
            val packages = packageManager.getPackagesForUid(callingUid)
            val pkgName = packages?.firstOrNull() ?: "unknown"
            
            val result = tokenManager.requestToken(pkgName)
            if (result == TokenManager.STATUS_PENDING) {
                // Notify UI that a request is pending
                val intent = Intent(ACTION_TOKEN_REQUEST).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_PACKAGE_NAME, pkgName)
                }
                sendBroadcast(intent)
                Log.i(TAG, "Token requested by $pkgName. Pending manual approval.")
            } else {
                Log.i(TAG, "Token request for $pkgName already approved.")
            }
            return result
        }
        
        override fun revokeApiToken(apiToken: String): Boolean {
            val sanitizedToken = apiToken.trim()
            logIpcRequest("revokeApiToken", sanitizedToken)
            // Security Check: Only allow revocation from the Edge AI Core app itself
            val callingUid = getCallingUid()
            if (callingUid != android.os.Process.myUid()) {
                Log.w(TAG, "Security: Blocked external client attempt to revoke token: ${apiToken.take(8)}... (Caller UID: $callingUid)")
                return false
            }

            // Close all conversations for this token first
            conversationManager.closeAllForToken(apiToken)
            val revoked = tokenManager.revokeToken(apiToken)
            Log.i(TAG, "Revoked API token: ${apiToken.take(8)}... -> $revoked")
            return revoked
        }
        
        // =====================
        // Conversation Management
        // =====================
        
        override fun startConversation(apiToken: String, systemInstruction: String?, ttlMs: Long): String {
            val sanitizedToken = apiToken.trim()
            val safeSystemInstruction = systemInstruction?.takeIf { it.isNotBlank() }
            logIpcRequest("startConversation", sanitizedToken, "systemInstruction={(length=${safeSystemInstruction?.length ?: 0})}, ttlMs=$ttlMs")
            
            return try {
                // Validate token
                if (!tokenManager.isValidToken(sanitizedToken)) {
                    Log.w(TAG, "startConversation failed: invalid token ${sanitizedToken.take(8)}...")
                    return gson.toJson(ErrorResponse("Invalid API token"))
                }
                
                val state = if (ttlMs > 0) {
                    conversationManager.createConversation(sanitizedToken, safeSystemInstruction, ttlMs)
                } else {
                    conversationManager.createConversation(sanitizedToken, safeSystemInstruction)
                }
                
                val response = ConversationResponse(
                    conversation_id = state.conversationId,
                    ttl_ms = state.ttlMs,
                    created_at = state.createdAt,
                    expires_at = state.createdAt + state.ttlMs
                )
                
                val msg = "Started conversation ${state.conversationId.take(8)}... for ${sanitizedToken.take(8)}... (TTL: ${state.ttlMs}ms)"
                Log.i(TAG, msg)
                sendStatusBroadcast(msg)
                gson.toJson(response)
            } catch (e: Exception) {
                Log.e(TAG, "Internal error in startConversation", e)
                gson.toJson(ErrorResponse("Internal server error: ${e.message}"))
            }
        }
        
        override fun closeConversation(apiToken: String, conversationId: String): String {
            val sanitizedToken = apiToken.trim()
            logIpcRequest("closeConversation", sanitizedToken, "conv=${conversationId.take(8)}...")
            
            // Validate token
            if (!tokenManager.isValidToken(sanitizedToken)) {
                Log.w(TAG, "closeConversation failed: invalid token ${sanitizedToken.take(8)}...")
                return gson.toJson(ErrorResponse("Invalid API token"))
            }
            
            val closed = conversationManager.closeConversation(conversationId, sanitizedToken)
            return if (closed) {
                val msg = "Closed conversation ${conversationId.take(8)}..."
                Log.i(TAG, msg)
                sendStatusBroadcast(msg)
                gson.toJson(SuccessResponse(true))
            } else {
                Log.w(TAG, "Failed to close conversation ${conversationId.take(8)}...")
                gson.toJson(ErrorResponse("Conversation not found or unauthorized"))
            }
        }
        
        override fun getConversationInfo(apiToken: String, conversationId: String): String {
            val sanitizedToken = apiToken.trim()
            logIpcRequest("getConversationInfo", sanitizedToken, "conv=${conversationId.take(8)}...")
            
            // Validate token
            if (!tokenManager.isValidToken(sanitizedToken)) {
                return gson.toJson(ErrorResponse("Invalid API token"))
            }
            
            val state = conversationManager.getConversation(conversationId, sanitizedToken)
            return if (state != null) {
                val now = System.currentTimeMillis()
                val response = ConversationInfoResponse(
                    conversation_id = state.conversationId,
                    ttl_ms = state.ttlMs,
                    created_at = state.createdAt,
                    last_access_time = state.lastAccessTime,
                    expires_at = state.lastAccessTime + state.ttlMs,
                    remaining_ttl_ms = (state.lastAccessTime + state.ttlMs) - now
                )
                gson.toJson(response)
            } else {
                gson.toJson(ErrorResponse("Conversation not found, expired, or unauthorized"))
            }
        }
        



        
        // =====================
        // Conversation-Based Inference
        // =====================
        
        override fun generateConversationResponseAsync(
            apiToken: String, 
            conversationId: String, 
            jsonRequest: String, 
            callback: IInferenceCallback
        ) {
            val sanitizedToken = apiToken.trim()
            logIpcRequest("generateConversationResponseAsync", sanitizedToken, "conv=${conversationId.take(8)}...")
            
            activeRequests.incrementAndGet()
            
            // Validate token
            if (!tokenManager.isValidToken(sanitizedToken)) {
                Log.w(TAG, "generateConversationResponseAsync failed: invalid token")
                try {
                    callback.onError("Invalid API token")
                } catch (e: Exception) {
                    Log.e(TAG, "Error calling onError callback", e)
                } finally {
                    activeRequests.decrementAndGet()
                }
                return
            }
            
            // Get conversation (this also validates ownership and resets TTL)
            val state = conversationManager.getConversation(conversationId, sanitizedToken)
            if (state == null) {
                Log.w(TAG, "generateConversationResponseAsync failed: conversation not found or expired")
                try {
                    callback.onError("Conversation not found, expired, or unauthorized")
                } catch (e: Exception) {
                    Log.e(TAG, "Error calling onError callback", e)
                } finally {
                    activeRequests.decrementAndGet()
                }
                return
            }
            
            serviceScope.launch {
                try {
                    Log.d(TAG, "Received conversation request for ${conversationId.take(8)}... (Length: ${jsonRequest.length})")
                    Log.d(TAG, "Request: $jsonRequest")
                    val request = gson.fromJson(jsonRequest, ChatCompletionRequest::class.java)

                    // Update conversation state with request parameters if provided
                    if (request.temperature != null) state.temperature = request.temperature
                    if (request.top_p != null) state.topP = request.top_p
                    if (request.top_k != null) state.topK = request.top_k
                    
                    aiEngineManager.generateConversationResponseAsync(
                        state = state,
                        messages = request.messages,
                        onToken = { token ->
                            try {
                                callback.onToken(token)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error calling onToken callback", e)
                            }
                        },
                        onComplete = { fullText ->
                            try {
                                val response = createChatCompletionResponse(request, fullText)
                                callback.onComplete(gson.toJson(response))
                            } catch (e: Exception) {
                                Log.e(TAG, "Error calling onComplete callback", e)
                            }
                        },
                        onErrorCallback = { error ->
                            try {
                                callback.onError(error.message ?: "Unknown inference error")
                            } catch (e: Exception) {
                                Log.e(TAG, "Error calling onError callback", e)
                            }
                        }
                    )
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Internal error in conversation async request", e)
                    try {
                        callback.onError(e.message ?: "Unknown internal error")
                    } catch (ce: Exception) {
                        Log.e(TAG, "Error notifying client of internal failure", ce)
                    }
                } finally {
                    activeRequests.decrementAndGet()
                }
            }
        }

        override fun ping(apiToken: String): String {
            val sanitizedToken = apiToken.trim()
            logIpcRequest("ping", sanitizedToken)
            if (!tokenManager.isValidToken(sanitizedToken)) {
                return "error: invalid token"
            }
            return "pong"
        }

        override fun health(apiToken: String): String {
            val sanitizedToken = apiToken.trim()
            logIpcRequest("health", sanitizedToken)
            if (!tokenManager.isValidToken(sanitizedToken)) {
                return "error: invalid token"
            }
            return "ok"
        }

        override fun getLoad(apiToken: String): Int {
            val sanitizedToken = apiToken.trim()
            logIpcRequest("getLoad", sanitizedToken)
            if (!tokenManager.isValidToken(sanitizedToken)) {
                return -1
            }
            return activeRequests.get()
        }

        private fun logIpcRequest(methodName: String, apiToken: String, extras: String = "") {
            val callingUid = getCallingUid()
            val packages = packageManager.getPackagesForUid(callingUid)
            val pkgName = packages?.firstOrNull() ?: "unknown"
            val sanitized = apiToken.trim()
            val tokenPart = if (sanitized.length >= 8) sanitized.take(8) + "..." else sanitized
            val msg = "IPC: [$pkgName] $methodName(token=$tokenPart) $extras"
            Log.i(TAG, msg)
            sendStatusBroadcast(msg)
        }
    }

    private fun createChatCompletionResponse(request: ChatCompletionRequest, content: String): ChatCompletionResponse {
        return ChatCompletionResponse(
            id = "chatcmpl-${UUID.randomUUID()}",
            created = System.currentTimeMillis() / 1000,
            model = request.model ?: "litertlm-model",
            choices = listOf(
                Choice(
                    index = 0,
                    message = ChatMessageResponse(
                        role = "assistant",
                        content = content
                    ),
                    finish_reason = "stop"
                )
            ),
            usage = Usage(0, 0, 0)
        )
    }

    override fun onCreate() {
        super.onCreate()
        tokenManager = TokenManager.getInstance(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called with intent: $intent")
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        sendStatusBroadcast("Service starting...")

        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val modelPath = intent?.getStringExtra(EXTRA_MODEL_PATH)
        val backend = intent?.getStringExtra(EXTRA_BACKEND) ?: "GPU"
        
        if (modelPath != null) {
            sendStatusBroadcast("Loading model from path: $modelPath")
            val useGpu = backend.equals("GPU", ignoreCase = true)
            // We pass the backend string. The logic for NPU/CPU/GPU needs to be handled in Manager or passed as enum/config.
            // Current loadModel takes (String, Boolean) for GPU. We might need to refactor loadModel first.
            // For now, let's map "GPU" -> true, others -> false AND we need to support NPU.
            
            serviceScope.launch {
                try {
                    // Update AiEngineManager.loadModel to accept backend string
                    Log.d(TAG, "Attempting to load model with backend: $backend")
                    sendStatusBroadcast("Initializing engine...")
                    
                    withTimeout(300000) { // 5 minutes timeout
                        aiEngineManager.loadModel(modelPath, backend)
                    }

                    Log.d(TAG, "loadModel returned. Starting readiness verification...")
                    sendStatusBroadcast("Verifying model readiness...")
                    
                    var isReady = false
                    val dummyState = ConversationState(
                        conversationId = "ping_session_${UUID.randomUUID()}",
                        apiToken = "internal_ping",
                        ttlMs = 60000L
                    )
                    val dummyMessage = listOf(ChatMessage(role = "user", content = com.google.gson.JsonPrimitive("Hello")))
                    Log.d(TAG, "Created dummy session: ${dummyState.conversationId}. Entering loop...")
                    
                    for (i in 1..10) {
                        try {
                             // Reset history for retry
                             dummyState.history.clear()
                             
                             var responseText = ""
                             // generateConversationResponseAsync suspends until inference completes
                             val newDummyState = ConversationState(
                                 conversationId = "ping_${System.currentTimeMillis()}",
                                 apiToken = "self_test",
                                 ttlMs = 60000
                             )
                             aiEngineManager.generateConversationResponseAsync(
                                 state = newDummyState,
                                 messages = dummyMessage,
                                 onToken = {},
                                 onComplete = { responseText = it },
                                 onErrorCallback = { throw it }
                             )                       
                             if (responseText.isNotEmpty()) {
                                 Log.d(TAG, "Ping response (attempt $i): Success. Length: ${responseText.length}")
                                 sendStatusBroadcast("Ping success ($i)")
                                 isReady = true
                                 break
                             } else {
                                 throw Exception("Empty response")
                             }
                        } catch (e: Exception) {
                            val msg = "Ping failed ($i): ${e.message}"
                            Log.d(TAG, msg)
                            sendStatusBroadcast(msg)
                            delay(2000)
                        }
                    }

                    // Cleanup the dummy session
                    aiEngineManager.closeConversation(dummyState.conversationId)

                    if (isReady) {
                        sendStatusBroadcast("Model loaded successfully ($backend)")
                    } else {
                        sendStatusBroadcast("Error: Model verification failed")
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize in service", e)
                    sendStatusBroadcast("Error loading model: ${e.message}")
                }
            }
        }

        return START_STICKY
    }

    private fun sendStatusBroadcast(status: String) {
        val intent = Intent(ACTION_STATUS_UPDATE)
        intent.setPackage(packageName)
        intent.putExtra(EXTRA_STATUS, status)
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        // Cancel all ongoing coroutines
        serviceScope.cancel()
        // Clean up conversations (tokens persist across restarts)
        conversationManager.shutdown()
        aiEngineManager.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.d(TAG, "onBind called")
        return binder
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = getString(R.string.notification_channel_id)
            val channelName = getString(R.string.notification_channel_name)
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val channelId = getString(R.string.notification_channel_id)
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_content))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
    }

    private fun extractSystemPreamble(messages: List<ChatMessage>): String? {
        return messages.firstOrNull { it.role == "system" }?.let {
            if (it.content.isJsonPrimitive) it.content.asString
            else it.content.toString()
        }
    }

    companion object {
        private const val TAG = "InferenceService"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "STOP"
        const val EXTRA_MODEL_PATH = "MODEL_PATH"
        const val EXTRA_BACKEND = "BACKEND"
        const val ACTION_STATUS_UPDATE = "com.aanand.edgeaicore.STATUS_UPDATE"
        const val EXTRA_STATUS = "STATUS"
        const val ACTION_TOKEN_REQUEST = "com.aanand.edgeaicore.TOKEN_REQUEST"
        const val EXTRA_PACKAGE_NAME = "PACKAGE_NAME"
    }
}

