package com.aanand.edgeaicore

import androidx.core.content.ContextCompat
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.os.PowerManager
import android.provider.Settings
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import android.media.AudioRecord
import android.media.AudioFormat
import android.media.MediaRecorder
import android.content.pm.PackageManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import java.io.File
import java.io.FileOutputStream
import java.net.NetworkInterface
import java.nio.charset.Charset
import java.util.Collections

class MainActivity : AppCompatActivity() {

    private lateinit var btnSelectModel: MaterialButton
    private lateinit var tvModelPath: TextView
    private lateinit var switchEnableServer: MaterialSwitch
    private lateinit var tvStatus: TextView
    private lateinit var rgBackend: RadioGroup
    private lateinit var btnTestInference: MaterialButton
    private lateinit var btnTestVision: MaterialButton
    private lateinit var btnTestAudio: MaterialButton
    private lateinit var tvLogs: TextView

    private var selectedModelPath: String? = null
    private var inferenceService: IInferenceService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            inferenceService = IInferenceService.Stub.asInterface(service)
            isBound = true
            appendLog("Connected to Inference Service")
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            inferenceService = null
            isBound = false
            appendLog("Disconnected from Inference Service")
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == InferenceService.ACTION_STATUS_UPDATE) {
                val status = intent.getStringExtra(InferenceService.EXTRA_STATUS)
                tvStatus.text = getString(R.string.status_label) + " " + (status ?: "Unknown")
                appendLog("Status Update: $status")

                if (status?.contains("loaded", ignoreCase = true) == true) {
                     switchEnableServer.isEnabled = true
                     switchEnableServer.isChecked = true
                     btnTestInference.isEnabled = true
                     btnTestVision.isEnabled = true
                     btnTestAudio.isEnabled = true
                } else if (status?.contains("Error", ignoreCase = true) == true) {
                     switchEnableServer.isEnabled = true
                     switchEnableServer.isChecked = false
                     btnTestInference.isEnabled = false
                     btnTestVision.isEnabled = false
                     btnTestAudio.isEnabled = false
                }
            }
        }
    }

    private val selectModelLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                copyModelFile(uri)
            }
        }
    }

    private val selectImageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                runTestVision(uri)
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            startAudioRecording()
        } else {
            appendLog("Audio permission denied")
        }
    }

    private val requestMultiplePermissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        permissions.entries.forEach {
            appendLog("Permission ${it.key}: ${if (it.value) "Granted" else "Denied"}")
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(InferenceService.ACTION_STATUS_UPDATE)
        ContextCompat.registerReceiver(this, statusReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(statusReceiver)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnSelectModel = findViewById(R.id.btn_select_model)
        tvModelPath = findViewById(R.id.tv_model_path)
        switchEnableServer = findViewById(R.id.switch_enable_server)
        tvStatus = findViewById(R.id.tv_status)
        rgBackend = findViewById(R.id.rg_backend)
        btnTestInference = findViewById(R.id.btn_test_inference)
        btnTestVision = findViewById(R.id.btn_test_vision)
        btnTestAudio = findViewById(R.id.btn_test_audio)
        tvLogs = findViewById(R.id.tv_logs)

        // Restore saved model path
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val savedPath = prefs.getString(KEY_MODEL_PATH, null)
        if (savedPath != null) {
            val file = File(savedPath)
            if (file.exists()) {
                selectedModelPath = savedPath
                tvModelPath.text = savedPath
                switchEnableServer.isEnabled = true
                appendLog("Restored model path: $savedPath")
            }
        }

        btnSelectModel.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            selectModelLauncher.launch(intent)
        }

        switchEnableServer.setOnClickListener {
             // We handle logic here instead of OnCheckedChangeListener to have better control over user interaction vs programmatic changes
             if (switchEnableServer.isChecked) {
                 if (selectedModelPath == null) {
                     switchEnableServer.isChecked = false
                     appendLog("Error: No model selected")
                 } else {
                     // Start server
                     switchEnableServer.isEnabled = false // Disable until loaded
                     startInferenceService()
                     // Status will be updated by Broadcast
                     appendLog("Starting server...")
                 }
             } else {
                 // Stop server
                 stopInferenceService()
                 tvStatus.text = getString(R.string.status_label) + " " + getString(R.string.status_idle)
                 btnTestInference.isEnabled = false
                 appendLog("Stopping server...")
             }
        }
        // Remove standard OnCheckedChangeListener to avoid loops with programmatic setting
        // switchEnableServer.setOnCheckedChangeListener { ... }

        btnTestInference.setOnClickListener {
            runTestInference()
        }

        btnTestVision.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }
            selectImageLauncher.launch(intent)
        }

        btnTestAudio.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startAudioRecording()
            } else {
                requestPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            }
        }
        
        checkAndRequestPermissions()
        requestIgnoreBatteryOptimizations()
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        
        permissions.add(android.Manifest.permission.RECORD_AUDIO)
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissions.add(android.Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(android.Manifest.permission.READ_MEDIA_AUDIO)
            permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            permissions.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        
        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (permissionsToRequest.isNotEmpty()) {
            requestMultiplePermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                appendLog("Requesting battery optimization exclusion...")
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    appendLog("Error requesting battery optimization: ${e.message}")
                }
            }
        }
    }

    private var audioFile: File? = null
    private var isRecording = false
    private var recordingJob: Job? = null

    private val SAMPLE_RATE = 16000
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    private fun startAudioRecording() {
        if (!isBound || inferenceService == null) {
            appendLog("Error: Service not bound")
            return
        }

        if (isRecording) return
        isRecording = true

        tvStatus.text = getString(R.string.status_label) + " " + getString(R.string.status_recording)
        appendLog("Recording for 5 seconds...")

        try {
            audioFile = File(cacheDir, "test_audio.wav")
            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                appendLog("Permission missing")
                return
            }
            
            val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize)
            
            recordingJob = CoroutineScope(Dispatchers.IO).launch {
                val data = ByteArray(bufferSize)
                FileOutputStream(audioFile).use { output ->
                    // Leave space for 44-byte WAV header
                    output.write(ByteArray(44)) 
                    
                    recorder.startRecording()
                    val startTime = System.currentTimeMillis()
                    
                    while (isRecording && System.currentTimeMillis() - startTime < 5000) {
                        val read = recorder.read(data, 0, data.size)
                        if (read > 0) {
                            output.write(data, 0, read)
                        }
                    }
                    
                    recorder.stop()
                    recorder.release()
                }

                // Add WAV header
                addWavHeader(audioFile!!)

                withContext(Dispatchers.Main) {
                    stopAudioRecording()
                }
            }

        } catch (e: Exception) {
            isRecording = false
            appendLog("Recording Failed: ${e.message}")
            tvStatus.text = getString(R.string.status_label) + " " + getString(R.string.status_ready)
        }
    }

    private fun addWavHeader(file: File) {
        val totalAudioLen = file.length() - 44
        val totalDataLen = totalAudioLen + 36
        val sampleRate = SAMPLE_RATE.toLong()
        val channels = 1
        val byteRate = 16 * sampleRate * channels / 8

        val header = ByteArray(44)
        header[0] = 'R'.toByte() // RIFF/WAV header
        header[1] = 'I'.toByte()
        header[2] = 'F'.toByte()
        header[3] = 'F'.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = (totalDataLen shr 8 and 0xff).toByte()
        header[6] = (totalDataLen shr 16 and 0xff).toByte()
        header[7] = (totalDataLen shr 24 and 0xff).toByte()
        header[8] = 'W'.toByte()
        header[9] = 'A'.toByte()
        header[10] = 'V'.toByte()
        header[11] = 'E'.toByte()
        header[12] = 'f'.toByte() // 'fmt ' chunk
        header[13] = 'm'.toByte()
        header[14] = 't'.toByte()
        header[15] = ' '.toByte()
        header[16] = 16 // 4 bytes: size of 'fmt ' chunk
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // format = 1 (PCM)
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = (sampleRate shr 8 and 0xff).toByte()
        header[26] = (sampleRate shr 16 and 0xff).toByte()
        header[27] = (sampleRate shr 24 and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = (byteRate shr 8 and 0xff).toByte()
        header[30] = (byteRate shr 16 and 0xff).toByte()
        header[31] = (byteRate shr 24 and 0xff).toByte()
        header[32] = (1 * 16 / 8).toByte() // block align
        header[33] = 0
        header[34] = 16 // bits per sample
        header[35] = 0
        header[36] = 'd'.toByte()
        header[37] = 'a'.toByte()
        header[38] = 't'.toByte()
        header[39] = 'a'.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = (totalAudioLen shr 8 and 0xff).toByte()
        header[42] = (totalAudioLen shr 16 and 0xff).toByte()
        header[43] = (totalAudioLen shr 24 and 0xff).toByte()

        val raf = java.io.RandomAccessFile(file, "rw")
        raf.seek(0)
        raf.write(header)
        raf.close()
    }

    private fun stopAudioRecording() {
        isRecording = false
        appendLog("Recording finished.")
        runTestAudio()
    }

    private fun runTestAudio() {
        val file = audioFile ?: return
        if (!file.exists()) {
            appendLog("Error: Audio file not found")
            return
        }

        appendLog("Preparing WAV audio inference request...")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val bytes = file.readBytes()
                val base64Audio = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                
                val jsonInputString = """
                    {
                        "model": "gemma-audio",
                        "messages": [
                            {
                                "role": "user",
                                "content": [
                                    { "type": "text", "text": "What is in this audio?" },
                                    { "type": "audio_url", "audio_url": { "url": "data:audio/wav;base64,$base64Audio" } }
                                ]
                            }
                        ]
                    }
                """.trimIndent()

                withContext(Dispatchers.Main) {
                    appendLog("Sending audio request to service (${bytes.size} bytes)...")
                    appendLog("Response: ")
                }

                inferenceService?.generateResponseAsync(jsonInputString, object : IInferenceCallback.Stub() {
                    override fun onToken(token: String) {
                        runOnUiThread {
                            val current = tvLogs.text.toString()
                            tvLogs.text = current + token
                        }
                    }

                    override fun onComplete(fullResponse: String) {
                        runOnUiThread {
                            appendLog("\n---")
                            appendLog("Final Response: $fullResponse")
                            tvStatus.text = getString(R.string.status_label) + " " + getString(R.string.status_ready)
                        }
                    }

                    override fun onError(error: String) {
                        runOnUiThread {
                            appendLog("\nError: $error")
                            tvStatus.text = getString(R.string.status_label) + " " + getString(R.string.status_error)
                        }
                    }
                })

            } catch (e: Exception) {
                 withContext(Dispatchers.Main) {
                     appendLog("Audio Request Failed: ${e.message}")
                     tvStatus.text = getString(R.string.status_label) + " " + getString(R.string.status_error)
                 }
            } finally {
                file.delete()
            }
        }
    }

    private fun runTestVision(imageUri: Uri) {
        if (!isBound || inferenceService == null) {
            appendLog("Error: Service not bound")
            return
        }

        tvStatus.text = getString(R.string.status_label) + " " + getString(R.string.status_selecting_image)
        appendLog("Preparing multimodal request...")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val base64Image = getBase64EncodedImage(imageUri)
                if (base64Image == null) {
                    withContext(Dispatchers.Main) {
                        appendLog("Error: Could not encode image")
                        tvStatus.text = getString(R.string.status_label) + " " + getString(R.string.status_ready)
                    }
                    return@launch
                }

                val jsonInputString = """
                    {
                        "model": "gemma-vision",
                        "messages": [
                            {
                                "role": "user",
                                "content": [
                                    { "type": "text", "text": "What is in this image?" },
                                    { "type": "image_url", "image_url": { "url": "data:image/jpeg;base64,$base64Image" } }
                                ]
                            }
                        ]
                    }
                """.trimIndent()

                withContext(Dispatchers.Main) {
                    appendLog("Sending vision request to service...")
                    appendLog("Response: ")
                }

                inferenceService?.generateResponseAsync(jsonInputString, object : IInferenceCallback.Stub() {
                    override fun onToken(token: String) {
                        runOnUiThread {
                            val current = tvLogs.text.toString()
                            tvLogs.text = current + token
                        }
                    }

                    override fun onComplete(fullResponse: String) {
                        runOnUiThread {
                            appendLog("\n---")
                            appendLog("Final Response: $fullResponse")
                            tvStatus.text = getString(R.string.status_label) + " " + getString(R.string.status_ready)
                        }
                    }

                    override fun onError(error: String) {
                        runOnUiThread {
                            appendLog("\nError: $error")
                            tvStatus.text = getString(R.string.status_label) + " " + getString(R.string.status_error)
                        }
                    }
                })

            } catch (e: Exception) {
                 withContext(Dispatchers.Main) {
                     appendLog("Vision Request Failed: ${e.message}")
                     tvStatus.text = getString(R.string.status_label) + " " + getString(R.string.status_error)
                 }
            }
        }
    }

    private fun getBase64EncodedImage(uri: Uri): String? {
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error encoding image", e)
            null
        }
    }

    private fun copyModelFile(uri: Uri) {
        tvStatus.text = getString(R.string.status_label) + " Copying..."
        appendLog("Copying model file...")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val fileName = getFileName(uri) ?: "model.litertlm"
                // Ensure it ends with .litertlm
                val validFileName = if (fileName.endsWith(".litertlm")) fileName else "$fileName.litertlm"

                val destinationFile = File(filesDir, validFileName)

                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destinationFile).use { output ->
                        input.copyTo(output)
                    }
                }

                selectedModelPath = destinationFile.absolutePath

                // Save path
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putString(KEY_MODEL_PATH, selectedModelPath)
                    .apply()

                withContext(Dispatchers.Main) {
                    tvModelPath.text = selectedModelPath
                    switchEnableServer.isEnabled = true
                    tvStatus.text = getString(R.string.status_label) + " " + getString(R.string.status_idle)
                    appendLog("Model copied to: $selectedModelPath")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error copying file", e)
                withContext(Dispatchers.Main) {
                    tvStatus.text = getString(R.string.status_label) + " " + getString(R.string.status_error)
                    appendLog("Error copying file: ${e.message}")
                }
            }
        }
    }

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        result = it.getString(index)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result
    }

    private fun startInferenceService() {
        val selectedBackend = when(rgBackend.checkedRadioButtonId) {
            R.id.rb_cpu -> "CPU"
            R.id.rb_gpu -> "GPU"
            R.id.rb_npu -> "NPU"
            else -> "GPU"
        }

        val intent = Intent(this, InferenceService::class.java).apply {
            putExtra(InferenceService.EXTRA_MODEL_PATH, selectedModelPath)
            putExtra(InferenceService.EXTRA_BACKEND, selectedBackend)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun stopInferenceService() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        val intent = Intent(this, InferenceService::class.java).apply {
            action = InferenceService.ACTION_STOP
        }
        startService(intent)
    }

    private fun runTestInference() {
        if (!isBound || inferenceService == null) {
            appendLog("Error: Service not bound")
            return
        }

        appendLog("Sending test request via AIDL (Streaming)...")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val jsonInputString = """
                    {
                        "model": "gemma",
                        "messages": [{"role": "user", "content": "Hello, are you working?"}]
                    }
                """.trimIndent()

                withContext(Dispatchers.Main) {
                    appendLog("Response: ")
                }

                inferenceService?.generateResponseAsync(jsonInputString, object : IInferenceCallback.Stub() {
                    override fun onToken(token: String) {
                        runOnUiThread {
                            val current = tvLogs.text.toString()
                            tvLogs.text = current + token
                        }
                    }

                    override fun onComplete(fullResponse: String) {
                        runOnUiThread {
                            // fullResponse is the JSON, we don't necessarily need to print it all if we streamed tokens
                            appendLog("\n---")
                            appendLog("Final Response: $fullResponse")
                            tvStatus.text = getString(R.string.status_label) + " " + getString(R.string.status_ready)
                        }
                    }

                    override fun onError(error: String) {
                        runOnUiThread {
                            appendLog("\nError: $error")
                            tvStatus.text = getString(R.string.status_label) + " " + getString(R.string.status_error)
                        }
                    }
                })

            } catch (e: Exception) {
                 withContext(Dispatchers.Main) {
                     appendLog("Request Failed: ${e.message}")
                     tvStatus.text = getString(R.string.status_label) + " " + getString(R.string.status_error)
                 }
            }
        }
    }

    private fun appendLog(msg: String) {
        val current = tvLogs.text.toString()
        tvLogs.text = "$current\n$msg"
    }



    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "litertlm_prefs"
        private const val KEY_MODEL_PATH = "model_path"
    }
}
