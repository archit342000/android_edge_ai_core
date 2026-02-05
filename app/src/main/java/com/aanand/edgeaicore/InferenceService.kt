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
    private val gson = Gson()

    private val binder = object : IInferenceService.Stub() {
        override fun generateResponse(jsonRequest: String): String {
            return try {
                Log.d(TAG, "Received request: $jsonRequest")
                val request = gson.fromJson(jsonRequest, ChatCompletionRequest::class.java)
                
                val responseText = runBlocking {
                     aiEngineManager.generateResponse(request.messages)
                }

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
                gson.toJson(response)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing request", e)
                val errorResponse = mapOf("error" to (e.message ?: "Unknown error"))
                gson.toJson(errorResponse)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
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

    companion object {
        private const val TAG = "InferenceService"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "STOP"
        const val EXTRA_MODEL_PATH = "MODEL_PATH"
        const val EXTRA_BACKEND = "BACKEND"
        const val ACTION_STATUS_UPDATE = "com.aanand.edgeaicore.STATUS_UPDATE"
        const val EXTRA_STATUS = "STATUS"
    }
}
