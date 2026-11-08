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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger

class InferenceService : Service() {

    private val aiEngineManager = AiEngineManager()
    private lateinit var tokenManager: TokenManager
    private lateinit var conversationManager: ConversationManager
    private lateinit var openAIServer: OpenAIServer

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeRequests = AtomicInteger(0)
    @Volatile private var serviceStatus: String = "Idle"
    private var isModelLoading = false

    override fun onCreate() {
        super.onCreate()
        tokenManager = TokenManager.getInstance(this)
        conversationManager = ConversationManager(
            context = this,
            onConversationRemoved = { id -> aiEngineManager.closeConversation(id) }
        )
        openAIServer = OpenAIServer(aiEngineManager, tokenManager)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called with intent: $intent")
        val action = intent?.action
        if (action == ACTION_STOP) {
            getSharedPreferences("inference_service_prefs", Context.MODE_PRIVATE).edit().clear().apply()
            conversationManager.deleteAllConversations()
            openAIServer.stop()
            stopSelf()
            return START_NOT_STICKY
        }

        sendStatusBroadcast("Service starting...")
        openAIServer.start()

        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val prefs = getSharedPreferences("inference_service_prefs", Context.MODE_PRIVATE)
        var modelPath = intent?.getStringExtra(EXTRA_MODEL_PATH)
        var backend = intent?.getStringExtra(EXTRA_BACKEND)

        if (modelPath != null) {
            // Save to prefs for sticky restarts
            prefs.edit()
                .putString("saved_model_path", modelPath)
                .putString("saved_backend", backend ?: "GPU")
                .putBoolean("crashed_during_init", false) // Reset on new manual start
                .apply()
        } else {
            // Restore from prefs if this is a sticky restart
            if (prefs.getBoolean("crashed_during_init", false)) {
                Log.e(TAG, "Detected crash loop during previous initialization. Aborting service start.")
                sendStatusBroadcast("Error: Crash loop detected. Service stopped.")
                stopSelf()
                return START_NOT_STICKY
            }

            modelPath = prefs.getString("saved_model_path", null)
            backend = prefs.getString("saved_backend", "GPU")
            if (modelPath != null) {
                Log.i(TAG, "Restored model path from prefs: $modelPath")
                sendStatusBroadcast("Restoring service state...")
            }
        }
        
        if (modelPath != null) {
            sendStatusBroadcast("Loading model from path: $modelPath")
            val finalBackend = backend ?: "GPU"
            
            serviceScope.launch {
                try {
                    val file = java.io.File(modelPath)
                    if (!file.exists()) {
                        Log.e(TAG, "Model file not found at: $modelPath")
                        sendStatusBroadcast("Error: Model file missing")
                        stopSelf()
                        return@launch
                    }

                    if (isModelLoading) {
                        Log.i(TAG, "Model load already in progress, skipping duplicate request")
                        return@launch
                    }
                    isModelLoading = true

                    // Mark as potentially crashing region
                    prefs.edit().putBoolean("crashed_during_init", true).commit()

                    Log.d(TAG, "Attempting to load model with backend: $finalBackend")
                    sendStatusBroadcast("Initializing engine...")
                    
                    withTimeout(300000) { // 5 minutes timeout
                        aiEngineManager.loadModel(modelPath, finalBackend)
                    }

                    // If we reached here, we didn't crash (Java-wise).
                    // Update flag to false implies success or handled exception
                    prefs.edit().putBoolean("crashed_during_init", false).apply()

                    Log.d(TAG, "loadModel returned. Starting readiness verification...")
                    sendStatusBroadcast("Verifying model readiness...")
                    
                    var isReady = false
                    var lastDummyId: String? = null
                    
                    for (i in 1..10) {
                        try {
                             var responseText = ""
                             // Create a new dummy session for each attempt
                             val newDummyState = ConversationState(
                                 conversationId = "ping_${System.currentTimeMillis()}",
                                 apiToken = "self_test",
                                 ttlMs = 60000
                             )
                             lastDummyId = newDummyState.conversationId
                             val dummyMessage = listOf(ChatMessage(role = "user", content = com.google.gson.JsonPrimitive("Hello")))

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

                    // Cleanup the last dummy session
                    lastDummyId?.let { aiEngineManager.closeConversation(it) }

                    if (isReady) {
                        sendStatusBroadcast("Model loaded successfully ($finalBackend)")
                    } else {
                        sendStatusBroadcast("Error: Model verification failed")
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize in service", e)
                    sendStatusBroadcast("Error loading model: ${e.message}")
                    // Note: If we caught an exception, it's not a native crash, so clear the flag?
                    // Yes, because we handled it.
                    prefs.edit().putBoolean("crashed_during_init", false).apply()
                } finally {
                    isModelLoading = false
                }
            }
        }

        return START_STICKY
    }

    private fun sendStatusBroadcast(status: String) {
        serviceStatus = status
        val intent = Intent(ACTION_STATUS_UPDATE)
        intent.setPackage(packageName)
        intent.putExtra(EXTRA_STATUS, status)
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        // Cancel all ongoing coroutines
        serviceScope.cancel()
        // Clean up conversations (tokens persist across restarts)
        if (::conversationManager.isInitialized) {
            conversationManager.shutdown()
        }
        aiEngineManager.close()
        if (::openAIServer.isInitialized) {
            openAIServer.stop()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.d(TAG, "onBind called")
        return null
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
