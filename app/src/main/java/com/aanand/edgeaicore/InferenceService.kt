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
import kotlinx.coroutines.runBlocking
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
        openAIServer = OpenAIServer(aiEngineManager, isValidToken = tokenManager::isValidToken)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called with intent: $intent")
        val action = intent?.action
        if (action == ACTION_STOP) {
            getSharedPreferences(PREFS_NAME_SERVICE, Context.MODE_PRIVATE).edit().clear().apply()
            conversationManager.deleteAllConversations()
            openAIServer.stop()
            stopSelf()
            return START_NOT_STICKY
        }

        sendStatusBroadcast(getString(R.string.status_service_starting))
        openAIServer.start()

        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val prefs = getSharedPreferences(PREFS_NAME_SERVICE, Context.MODE_PRIVATE)
        var modelPath = intent?.getStringExtra(EXTRA_MODEL_PATH)
        var backend = intent?.getStringExtra(EXTRA_BACKEND)
        // 0 or absent means the model's default context size
        var speculativeValue = intent?.getStringExtra(EXTRA_SPECULATIVE_DECODING)
        var visionValue = intent?.getStringExtra(EXTRA_VISION_BACKEND)
        var audioValue = intent?.getStringExtra(EXTRA_AUDIO_BACKEND)
        var maxNumTokens = intent?.getIntExtra(EXTRA_MAX_NUM_TOKENS, 0)?.takeIf { it > 0 }

        // A rejected new file keeps the running model, so remember what was stored for it before it is overwritten.
        val previous = if (modelPath != null && aiEngineManager.isModelLoaded) savedModel(prefs) else null

        if (modelPath != null) {
            // Save to prefs for sticky restarts
            prefs.edit()
                .putString(KEY_SAVED_MODEL_PATH, modelPath)
                .putString(KEY_SAVED_BACKEND, backend ?: "GPU")
                .putInt(KEY_SAVED_MAX_NUM_TOKENS, maxNumTokens ?: 0)
                .putString(KEY_SAVED_SPECULATIVE, speculativeValue)
                .putString(KEY_SAVED_VISION_BACKEND, visionValue)
                .putString(KEY_SAVED_AUDIO_BACKEND, audioValue)
                .putBoolean(KEY_CRASHED_DURING_INIT, false) // Reset on new manual start
                .apply()
        } else {
            // Restore from prefs if this is a sticky restart
            if (prefs.getBoolean(KEY_CRASHED_DURING_INIT, false)) {
                Log.e(TAG, "Detected crash loop during previous initialization. Aborting service start.")
                sendStatusBroadcast(getString(R.string.status_crash_loop))
                stopSelf()
                return START_NOT_STICKY
            }

            modelPath = prefs.getString(KEY_SAVED_MODEL_PATH, null)
            backend = prefs.getString(KEY_SAVED_BACKEND, "GPU")
            maxNumTokens = prefs.getInt(KEY_SAVED_MAX_NUM_TOKENS, 0).takeIf { it > 0 }
            speculativeValue = prefs.getString(KEY_SAVED_SPECULATIVE, null)
            visionValue = prefs.getString(KEY_SAVED_VISION_BACKEND, null)
            audioValue = prefs.getString(KEY_SAVED_AUDIO_BACKEND, null)
            if (modelPath != null) {
                Log.i(TAG, "Restored model path from prefs: $modelPath")
                sendStatusBroadcast(getString(R.string.status_restoring))
            }
        }
        
        if (modelPath != null) {
            sendStatusBroadcast(getString(R.string.status_loading_model, modelPath))
            val finalBackend = backend ?: "GPU"
            if (SpeculativeDecodingOption.isUnknown(speculativeValue)) {
                Log.w(TAG, "Unknown speculative decoding setting, using ${SpeculativeDecodingOption.DEFAULT}")
            }
            val speculativeOption = SpeculativeDecodingOption.fromStored(speculativeValue)
            if (EncoderBackendOption.isUnknown(visionValue) || EncoderBackendOption.isUnknown(audioValue)) {
                Log.w(TAG, "Unknown encoder backend setting, using ${EncoderBackendOption.AUTO}")
            }
            val visionOption = EncoderBackendOption.fromStored(visionValue)
            val audioOption = EncoderBackendOption.fromStored(audioValue)
            
            serviceScope.launch {
                try {
                    val file = java.io.File(modelPath)
                    if (!file.exists()) {
                        Log.e(TAG, "Model file not found at: $modelPath")
                        sendStatusBroadcast(getString(R.string.status_model_missing))
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
                    sendStatusBroadcast(getString(R.string.status_initializing))
                    
                    withTimeout(300000) { // 5 minutes timeout
                        aiEngineManager.loadModel(modelPath, finalBackend, cacheDir.absolutePath, maxNumTokens, speculativeOption, visionOption, audioOption)
                    }

                    // The crash flag stays set through the readiness check: a GPU engine can load and still abort natively
                    // on its first request, and a sticky restart must not retry it. It is cleared after the final check.
                    Log.d(TAG, "loadModel returned. Starting readiness verification...")
                    sendStatusBroadcast(getString(R.string.status_verifying))
                    
                    // A GPU engine can load and still fail its first request (no OpenCL): give it fewer attempts,
                    // then fall back to CPU instead of waiting out the full retry budget.
                    var readinessError = verifyEngineReady()
                    val fallbackBackend = if (readinessError != null) BackendFallbackPolicy.next(aiEngineManager.activeBackend ?: "") else null
                    if (readinessError != null && fallbackBackend != null) {
                        Log.w(TAG, "GPU readiness failed, falling back to CPU: ${readinessError.message}")
                        sendStatusBroadcast(getString(R.string.status_gpu_fallback))
                        markInitStarted(prefs)
                        withTimeout(300000) { aiEngineManager.fallBackToCpu(modelPath, cacheDir.absolutePath, maxNumTokens, speculativeOption, visionOption, audioOption) }
                        readinessError = verifyEngineReady()
                    }
                    // Whatever the check found, the process survived the load and the first requests.
                    prefs.edit().putBoolean(KEY_CRASHED_DURING_INIT, false).apply()

                    if (readinessError == null) {
                        val backendInUse = aiEngineManager.activeBackend ?: finalBackend
                        val mtp = aiEngineManager.activeSpeculativeMode?.let { getString(R.string.status_loaded_mtp_part, it.name.lowercase()) } ?: ""
                        val effective = aiEngineManager.effectiveMaxNumTokens
                        val ctx = if (maxNumTokens != null && effective != null && effective != maxNumTokens) getString(R.string.status_loaded_context_part, effective) else ""
                        val gpuNote = if (aiEngineManager.fellBackFromGpu) getString(R.string.status_loaded_gpu_unavailable_part) else ""
                        val status = getString(R.string.status_loaded, "$backendInUse$gpuNote$mtp$ctx; ${aiEngineManager.activeEncoders.describe()}")
                        Log.i(TAG, status)
                        sendStatusBroadcast(status)
                    } else {
                        Log.e(TAG, "Model verification failed", readinessError)
                        sendStatusBroadcast(getString(R.string.status_verification_failed))
                    }

                } catch (e: UnsupportedModelException) {
                    Log.e(TAG, "Unsupported model file", e)
                    // A sticky restart must not retry a file known to be unusable: with a running model its settings come
                    // back, otherwise the path is dropped.
                    if (previous != null) {
                        sendStatusBroadcast(getString(R.string.status_unsupported_model_kept))
                        prefs.edit {
                            putBoolean(KEY_CRASHED_DURING_INIT, false)
                            putString(KEY_SAVED_MODEL_PATH, previous.path)
                            putString(KEY_SAVED_BACKEND, previous.backend)
                            putInt(KEY_SAVED_MAX_NUM_TOKENS, previous.maxNumTokens)
                            putString(KEY_SAVED_SPECULATIVE, previous.speculative)
                            putString(KEY_SAVED_VISION_BACKEND, previous.vision)
                            putString(KEY_SAVED_AUDIO_BACKEND, previous.audio)
                        }
                    } else {
                        sendStatusBroadcast(getString(R.string.status_unsupported_model))
                        prefs.edit {
                            putBoolean(KEY_CRASHED_DURING_INIT, false)
                            remove(KEY_SAVED_MODEL_PATH)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize in service", e)
                    sendStatusBroadcast(getString(R.string.status_load_failed))
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
                sendStatusBroadcast(getString(R.string.status_ping_success, attempt))
            } else {
                val msg = getString(R.string.status_ping_failed, attempt, error.message)
                Log.d(TAG, msg)
                sendStatusBroadcast(msg)
            }
        }
    }

    /** The model settings stored for sticky restarts. */
    private class SavedModel(
        val path: String, val backend: String?, val maxNumTokens: Int, val speculative: String?, val vision: String?, val audio: String?
    )

    private fun savedModel(prefs: SharedPreferences): SavedModel? {
        val path = prefs.getString(KEY_SAVED_MODEL_PATH, null) ?: return null
        return SavedModel(
            path, prefs.getString(KEY_SAVED_BACKEND, null), prefs.getInt(KEY_SAVED_MAX_NUM_TOKENS, 0),
            prefs.getString(KEY_SAVED_SPECULATIVE, null), prefs.getString(KEY_SAVED_VISION_BACKEND, null),
            prefs.getString(KEY_SAVED_AUDIO_BACKEND, null)
        )
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
        // Stop the server first: no new requests, and running calls are cancelled, which stops native decoding.
        if (::openAIServer.isInitialized) {
            openAIServer.stop()
        }
        // Then close the engine once nothing is generating (bounded wait; the process is ending anyway).
        runBlocking { aiEngineManager.closeWhenIdle(ENGINE_CLOSE_WAIT_MS) }
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
        const val EXTRA_VISION_BACKEND = "VISION_BACKEND"
        const val EXTRA_AUDIO_BACKEND = "AUDIO_BACKEND"
        private const val KEY_SAVED_VISION_BACKEND = "saved_vision_backend"
        private const val KEY_SAVED_AUDIO_BACKEND = "saved_audio_backend"
        private const val KEY_SAVED_SPECULATIVE = "saved_speculative_decoding"
        const val ACTION_STATUS_UPDATE = "com.aanand.edgeaicore.STATUS_UPDATE"
        const val EXTRA_STATUS = "STATUS"
        const val ACTION_TOKEN_REQUEST = "com.aanand.edgeaicore.TOKEN_REQUEST"
        const val EXTRA_PACKAGE_NAME = "PACKAGE_NAME"
        private const val PING_ATTEMPTS = 10
        private const val GPU_PING_ATTEMPTS = 2
        private const val KEY_CRASHED_DURING_INIT = "crashed_during_init"
        private const val KEY_SAVED_MODEL_PATH = "saved_model_path"
        private const val ENGINE_CLOSE_WAIT_MS = 2000L
        private const val PREFS_NAME_SERVICE = "inference_service_prefs"
        private const val KEY_SAVED_BACKEND = "saved_backend"
    }
}
