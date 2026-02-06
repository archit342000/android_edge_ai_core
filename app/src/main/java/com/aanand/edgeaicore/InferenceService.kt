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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.UUID

class InferenceService : Service() {

    private val aiEngineManager = AiEngineManager()
    private lateinit var tokenManager: TokenManager
    private val sessionManager = SessionManager()
    private val gson = Gson()

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

            // Close all sessions for this token first
            sessionManager.closeAllSessionsForToken(apiToken)
            val revoked = tokenManager.revokeToken(apiToken)
            Log.i(TAG, "Revoked API token: ${apiToken.take(8)}... -> $revoked")
            return revoked
        }
        
        // =====================
        // Session Management
        // =====================
        
        override fun startSession(apiToken: String, ttlMs: Long): String {
            val sanitizedToken = apiToken.trim()
            logIpcRequest("startSession", sanitizedToken, "ttl=$ttlMs")
            
            return try {
                // Validate token
                if (!tokenManager.isValidToken(sanitizedToken)) {
                    Log.w(TAG, "startSession failed: invalid token ${sanitizedToken.take(8)}...")
                    return gson.toJson(ErrorResponse("Invalid API token"))
                }
                
                val actualTtl = if (ttlMs <= 0) SessionManager.DEFAULT_SESSION_TTL_MS else ttlMs
                val session = sessionManager.createSession(sanitizedToken, actualTtl)
                
                val response = SessionResponse(
                    session_id = session.sessionId,
                    ttl_ms = session.ttlMs,
                    created_at = session.createdAt,
                    expires_at = session.createdAt + session.ttlMs
                )
                
                val msg = "Started session ${session.sessionId.take(8)}... for ${sanitizedToken.take(8)}..."
                Log.i(TAG, msg)
                sendStatusBroadcast(msg)
                gson.toJson(response)
            } catch (e: Exception) {
                Log.e(TAG, "Internal error in startSession", e)
                gson.toJson(ErrorResponse("Internal server error: ${e.message}"))
            }
        }
        
        override fun closeSession(apiToken: String, sessionId: String): String {
            val sanitizedToken = apiToken.trim()
            logIpcRequest("closeSession", sanitizedToken, "session=${sessionId.take(8)}...")
            
            // Validate token
            if (!tokenManager.isValidToken(sanitizedToken)) {
                Log.w(TAG, "closeSession failed: invalid token ${sanitizedToken.take(8)}...")
                return gson.toJson(ErrorResponse("Invalid API token"))
            }
            
            val closed = sessionManager.closeSession(sessionId, sanitizedToken)
            return if (closed) {
                val msg = "Closed session ${sessionId.take(8)}..."
                Log.i(TAG, msg)
                sendStatusBroadcast(msg)
                gson.toJson(SuccessResponse(true))
            } else {
                Log.w(TAG, "Failed to close session ${sessionId.take(8)}...")
                gson.toJson(ErrorResponse("Session not found or unauthorized"))
            }
        }
        
        override fun getSessionInfo(apiToken: String, sessionId: String): String {
            val sanitizedToken = apiToken.trim()
            logIpcRequest("getSessionInfo", sanitizedToken, "session=${sessionId.take(8)}...")
            
            // Validate token
            if (!tokenManager.isValidToken(sanitizedToken)) {
                return gson.toJson(ErrorResponse("Invalid API token"))
            }
            
            val session = sessionManager.getSession(sessionId, sanitizedToken)
            return if (session != null) {
                val now = System.currentTimeMillis()
                val response = SessionInfoResponse(
                    session_id = session.sessionId,
                    ttl_ms = session.ttlMs,
                    created_at = session.createdAt,
                    last_access_time = session.lastAccessTime,
                    expires_at = session.lastAccessTime + session.ttlMs,
                    remaining_ttl_ms = (session.lastAccessTime + session.ttlMs) - now
                )
                gson.toJson(response)
            } else {
                gson.toJson(ErrorResponse("Session not found, expired, or unauthorized"))
            }
        }
        



        
        // =====================
        // Session-Based Inference
        // =====================
        
        override fun generateResponseWithSession(apiToken: String, sessionId: String, jsonRequest: String): String {
            val sanitizedToken = apiToken.trim()
            logIpcRequest("generateResponseWithSession", sanitizedToken, "session=${sessionId.take(8)}...")
            
            // Validate token
            if (!tokenManager.isValidToken(sanitizedToken)) {
                Log.w(TAG, "generateResponseWithSession failed: invalid token")
                return gson.toJson(ErrorResponse("Invalid API token"))
            }
            
            // Get session (this also validates ownership and resets TTL)
            val session = sessionManager.getSession(sessionId, sanitizedToken)
            if (session == null) {
                Log.w(TAG, "generateResponseWithSession failed: session not found or expired")
                return gson.toJson(ErrorResponse("Session not found, expired, or unauthorized"))
            }
            
            return try {
                Log.d(TAG, "Received session request for ${sessionId.take(8)}...: $jsonRequest")
                val request = gson.fromJson(jsonRequest, ChatCompletionRequest::class.java)
                
                // For session-based inference, we only use the last user message
                val lastMessage = request.messages.lastOrNull { it.role != "system" }
                if (lastMessage == null) {
                    return gson.toJson(ErrorResponse("No user message found"))
                }
                
                val preamble = extractSystemPreamble(request.messages)
                val (responseText, conversation) = runBlocking {
                    aiEngineManager.generateResponseWithSession(
                        session = session,
                        message = lastMessage,
                        temperature = request.temperature,
                        topP = request.top_p,
                        topK = request.top_k,
                        preamble = preamble
                    )
                }
                
                // Update session with the conversation (for KV cache reuse)
                session.conversation = conversation

                val response = createChatCompletionResponse(request, responseText)
                gson.toJson(response)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing session request", e)
                gson.toJson(ErrorResponse(e.message ?: "Unknown error"))
            }
        }
        
        override fun generateResponseAsyncWithSession(
            apiToken: String, 
            sessionId: String, 
            jsonRequest: String, 
            callback: IInferenceCallback
        ) {
            val sanitizedToken = apiToken.trim()
            logIpcRequest("generateResponseAsyncWithSession", sanitizedToken, "session=${sessionId.take(8)}...")
            
            // Validate token
            if (!tokenManager.isValidToken(sanitizedToken)) {
                Log.w(TAG, "generateResponseAsyncWithSession failed: invalid token")
                try {
                    callback.onError("Invalid API token")
                } catch (e: Exception) {
                    Log.e(TAG, "Error calling onError callback", e)
                }
                return
            }
            
            // Get session (this also validates ownership and resets TTL)
            val session = sessionManager.getSession(sessionId, sanitizedToken)
            if (session == null) {
                Log.w(TAG, "generateResponseAsyncWithSession failed: session not found or expired")
                try {
                    callback.onError("Session not found, expired, or unauthorized")
                } catch (e: Exception) {
                    Log.e(TAG, "Error calling onError callback", e)
                }
                return
            }
            
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    Log.d(TAG, "Received session async request for ${sessionId.take(8)}...: $jsonRequest")
                    val request = gson.fromJson(jsonRequest, ChatCompletionRequest::class.java)
                    
                    // For session-based inference, we only use the last user message
                    val lastMessage = request.messages.lastOrNull { it.role != "system" }
                    if (lastMessage == null) {
                        callback.onError("No user message found")
                        return@launch
                    }
                    
                    val preamble = extractSystemPreamble(request.messages)
                    val conversation = aiEngineManager.generateResponseAsyncWithSession(
                        session = session,
                        message = lastMessage,
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
                        onError = { error ->
                            try {
                                callback.onError(error.message ?: "Unknown inference error")
                            } catch (e: Exception) {
                                Log.e(TAG, "Error calling onError callback", e)
                            }
                        },
                        temperature = request.temperature,
                        topP = request.top_p,
                        topK = request.top_k,
                        preamble = preamble
                    )
                    
                    // Update session with the conversation (for KV cache reuse)
                    session.conversation = conversation
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Internal error in session async request", e)
                    try {
                        callback.onError(e.message ?: "Unknown internal error")
                    } catch (ce: Exception) {
                        Log.e(TAG, "Error notifying client of internal failure", ce)
                    }
                }
            }
        }

        override fun ping(): String {
            logIpcRequest("ping", "none")
            return "pong"
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
            
            CoroutineScope(Dispatchers.IO).launch {
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
                    val dummyMessage = listOf(ChatMessage(role = "user", content = com.google.gson.JsonPrimitive("Hello")))
                    Log.d(TAG, "Created dummy message: $dummyMessage. Entering loop...")
                    
                    for (i in 1..10) {
                        try {
                             val response = aiEngineManager.generateResponse(dummyMessage)
                             Log.d(TAG, "Ping response (attempt $i): $response")
                             sendStatusBroadcast("Ping success ($i): $response")
                             isReady = true
                             break
                        } catch (e: Exception) {
                            val msg = "Ping failed ($i): ${e.message}"
                            Log.d(TAG, msg)
                            sendStatusBroadcast(msg)
                            delay(1000)
                        }
                    }

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
        // Clean up sessions (tokens persist across restarts)
        sessionManager.shutdown()
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

