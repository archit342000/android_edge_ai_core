package com.aanand.edgeaicore

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
        openAIServer = OpenAIServer(aiEngineManager, tokenManager::isValidToken)
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
                    markInitStarted(prefs)

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

                    // A GPU engine can load and still fail its first request (no OpenCL): give it fewer attempts,
                    // then fall back to CPU instead of waiting out the full retry budget.
                    var readinessError = verifyEngineReady()
                    val fallbackBackend = if (readinessError != null) BackendFallbackPolicy.next(aiEngineManager.activeBackend ?: "") else null
                    if (readinessError != null && fallbackBackend != null) {
                        Log.w(TAG, "GPU readiness failed, falling back to CPU: ${readinessError.message}")
                        sendStatusBroadcast("GPU unavailable, loading on CPU...")
                        markInitStarted(prefs)
                        withTimeout(300000) { aiEngineManager.fallBackToCpu(modelPath) }
                        prefs.edit().putBoolean("crashed_during_init", false).apply()
                        readinessError = verifyEngineReady()
                    }

                    if (readinessError == null) {
                        val backendInUse = aiEngineManager.activeBackend ?: finalBackend
                        val status = if (aiEngineManager.fellBackFromGpu) {
                            "Model loaded successfully ($backendInUse, GPU unavailable)"
                        } else {
                            "Model loaded successfully ($backendInUse)"
                        }
                        Log.i(TAG, status)
                        sendStatusBroadcast(status)
                    } else {
                        Log.e(TAG, "Model verification failed", readinessError)
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

    /**
     * Sets the crash-loop guard before a native model load. `commit()` is deliberate: the flag must be on disk before
     * the native code can abort the process, which `apply()` (asynchronous) would not guarantee.
     */
    private fun markInitStarted(prefs: SharedPreferences) {
        prefs.edit(commit = true) { putBoolean("crashed_during_init", true) }
    }

    /** Runs the readiness ping on the loaded engine; fewer attempts on GPU, which may fall back to CPU. */
    private suspend fun verifyEngineReady(): Throwable? {
        val attempts = if (aiEngineManager.activeBackend == BackendFallbackPolicy.GPU) GPU_PING_ATTEMPTS else PING_ATTEMPTS
        return aiEngineManager.verifyReady(attempts) { attempt, error ->
            if (error == null) {
                Log.d(TAG, "Ping response (attempt $attempt): Success")
                sendStatusBroadcast("Ping success ($attempt)")
            } else {
                val msg = "Ping failed ($attempt): ${error.message}"
                Log.d(TAG, msg)
                sendStatusBroadcast(msg)
            }
        }
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
        private const val PING_ATTEMPTS = 10
        private const val GPU_PING_ATTEMPTS = 2
    }
}
