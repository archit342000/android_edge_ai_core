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
        // 0 or absent means the model's default context size
        var speculativeValue = intent?.getStringExtra(EXTRA_SPECULATIVE_DECODING)
        var maxNumTokens = intent?.getIntExtra(EXTRA_MAX_NUM_TOKENS, 0)?.takeIf { it > 0 }

        if (modelPath != null) {
            // Save to prefs for sticky restarts
            prefs.edit()
                .putString(KEY_SAVED_MODEL_PATH, modelPath)
                .putString("saved_backend", backend ?: "GPU")
                .putInt(KEY_SAVED_MAX_NUM_TOKENS, maxNumTokens ?: 0)
                .putString(KEY_SAVED_SPECULATIVE, speculativeValue)
                .putBoolean(KEY_CRASHED_DURING_INIT, false) // Reset on new manual start
                .apply()
        } else {
            // Restore from prefs if this is a sticky restart
            if (prefs.getBoolean(KEY_CRASHED_DURING_INIT, false)) {
                Log.e(TAG, "Detected crash loop during previous initialization. Aborting service start.")
                sendStatusBroadcast("Error: Crash loop detected. Service stopped.")
                stopSelf()
                return START_NOT_STICKY
            }

            modelPath = prefs.getString(KEY_SAVED_MODEL_PATH, null)
            backend = prefs.getString("saved_backend", "GPU")
            maxNumTokens = prefs.getInt(KEY_SAVED_MAX_NUM_TOKENS, 0).takeIf { it > 0 }
            speculativeValue = prefs.getString(KEY_SAVED_SPECULATIVE, null)
            if (modelPath != null) {
                Log.i(TAG, "Restored model path from prefs: $modelPath")
                sendStatusBroadcast("Restoring service state...")
            }
        }
        
        if (modelPath != null) {
            sendStatusBroadcast("Loading model from path: $modelPath")
            val finalBackend = backend ?: "GPU"
            if (SpeculativeDecodingOption.isUnknown(speculativeValue)) {
                Log.w(TAG, "Unknown speculative decoding setting, using ${SpeculativeDecodingOption.DEFAULT}")
            }
            val speculativeOption = SpeculativeDecodingOption.fromStored(speculativeValue)
            
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
                        aiEngineManager.loadModel(modelPath, finalBackend, cacheDir.absolutePath, maxNumTokens, speculativeOption)
                    }

                    // If we reached here, we didn't crash (Java-wise).
                    // Update flag to false implies success or handled exception
                    prefs.edit().putBoolean(KEY_CRASHED_DURING_INIT, false).apply()

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
                        withTimeout(300000) { aiEngineManager.fallBackToCpu(modelPath, cacheDir.absolutePath, maxNumTokens, speculativeOption) }
                        prefs.edit().putBoolean(KEY_CRASHED_DURING_INIT, false).apply()
                        readinessError = verifyEngineReady()
                    }

                    if (readinessError == null) {
                        val backendInUse = aiEngineManager.activeBackend ?: finalBackend
                        val mtp = aiEngineManager.activeSpeculativeMode?.let { ", MTP ${it.name.lowercase()}" } ?: ""
                        val status = if (aiEngineManager.fellBackFromGpu) {
                            "Model loaded successfully ($backendInUse, GPU unavailable$mtp)"
                        } else {
                            "Model loaded successfully ($backendInUse$mtp)"
                        }
                        Log.i(TAG, status)
                        sendStatusBroadcast(status)
                    } else {
                        Log.e(TAG, "Model verification failed", readinessError)
                        sendStatusBroadcast("Error: Model verification failed")
                    }

                } catch (e: UnsupportedModelException) {
                    Log.e(TAG, "Unsupported model file", e)
                    sendStatusBroadcast(STATUS_UNSUPPORTED_MODEL)
                    // A sticky restart must not retry a file known to be unusable.
                    prefs.edit {
                        putBoolean(KEY_CRASHED_DURING_INIT, false)
                        remove(KEY_SAVED_MODEL_PATH)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize in service", e)
                    sendStatusBroadcast(STATUS_LOAD_FAILED)
                    // Note: If we caught an exception, it's not a native crash, so clear the flag?
                    // Yes, because we handled it.
                    prefs.edit().putBoolean(KEY_CRASHED_DURING_INIT, false).apply()
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
        prefs.edit(commit = true) { putBoolean(KEY_CRASHED_DURING_INIT, true) }
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
        const val EXTRA_MAX_NUM_TOKENS = "MAX_NUM_TOKENS"
        private const val KEY_SAVED_MAX_NUM_TOKENS = "saved_max_num_tokens"
        const val EXTRA_SPECULATIVE_DECODING = "SPECULATIVE_DECODING"
        private const val KEY_SAVED_SPECULATIVE = "saved_speculative_decoding"
        const val ACTION_STATUS_UPDATE = "com.aanand.edgeaicore.STATUS_UPDATE"
        const val EXTRA_STATUS = "STATUS"
        const val ACTION_TOKEN_REQUEST = "com.aanand.edgeaicore.TOKEN_REQUEST"
        const val EXTRA_PACKAGE_NAME = "PACKAGE_NAME"
        private const val PING_ATTEMPTS = 10
        private const val GPU_PING_ATTEMPTS = 2
        private const val KEY_CRASHED_DURING_INIT = "crashed_during_init"
        private const val KEY_SAVED_MODEL_PATH = "saved_model_path"
        private const val STATUS_UNSUPPORTED_MODEL = "Error: Unsupported model file"
        private const val STATUS_LOAD_FAILED = "Error: Model failed to load"
    }
}
