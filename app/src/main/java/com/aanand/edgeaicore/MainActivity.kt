package com.aanand.edgeaicore

import androidx.core.content.ContextCompat
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.os.PowerManager
import android.provider.Settings
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import java.io.File
import java.io.FileOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import android.content.ClipboardManager
import android.content.ClipData
import android.widget.Toast
import android.media.AudioRecord
import android.media.AudioFormat
import android.media.MediaRecorder
import android.content.pm.PackageManager
import com.aanand.edgeaicore.ChatCompletionRequest
import com.aanand.edgeaicore.ChatCompletionResponse
import com.aanand.edgeaicore.ChatMessage
import com.google.gson.JsonPrimitive
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var btnSelectModel: MaterialButton
    private lateinit var tvModelPath: TextView
    private lateinit var switchEnableServer: MaterialSwitch
    private lateinit var tvStatus: TextView
    private lateinit var rgBackend: RadioGroup
    private lateinit var btnTestInference: MaterialButton
    private lateinit var btnTestVision: MaterialButton
    private lateinit var btnTestAudio: MaterialButton
    private lateinit var btnTestMultiTurn: MaterialButton
    private lateinit var btnTestHealth: MaterialButton
    private lateinit var btnTestLoad: MaterialButton
    private lateinit var tvLogs: TextView
    
    // API Token UI
    private lateinit var tvApiToken: TextView
    private lateinit var btnGenerateToken: MaterialButton
    private lateinit var btnCopyToken: MaterialButton
    private lateinit var btnDeleteToken: MaterialButton
    private lateinit var btnBackupTokens: MaterialButton
    private lateinit var btnRestoreTokens: MaterialButton
    private lateinit var llAuthorizedApps: android.widget.LinearLayout
    private lateinit var tvAuthorizedLabel: TextView
    private lateinit var bottomNav: com.google.android.material.bottomnavigation.BottomNavigationView
    private lateinit var pageServer: android.view.View
    private lateinit var pageBackend: android.view.View
    private lateinit var pageTokens: android.view.View
    private lateinit var tokenManager: TokenManager
    private var currentToken: String? = null

    private var selectedModelPath: String? = null
    private val gson = Gson()

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "Broadcast received: ${intent.action}")
            if (intent.action == InferenceService.ACTION_STATUS_UPDATE) {
                val status = intent.getStringExtra(InferenceService.EXTRA_STATUS)
                Log.d(TAG, "Received STATUS_UPDATE: $status")
                appendLog("Status Update: $status")
                updateUIForStatus(status ?: "")
            }
        }
    }

    private val backupTokensLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { performBackup(it) }
    }

    private val restoreTokensLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { performRestore(it) }
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
        val filter = IntentFilter().apply {
            addAction(InferenceService.ACTION_STATUS_UPDATE)
        }
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
        btnTestMultiTurn = findViewById(R.id.btn_test_multiturn)
        btnTestHealth = findViewById(R.id.btn_test_health)
        btnTestLoad = findViewById(R.id.btn_test_load)
        tvLogs = findViewById(R.id.tv_logs)
        
        // Navigation UI
        val toolbar: com.google.android.material.appbar.MaterialToolbar = findViewById(R.id.toolbar)
        bottomNav = findViewById(R.id.bottom_navigation)
        pageServer = findViewById(R.id.page_server)
        pageBackend = findViewById(R.id.page_backend)
        pageTokens = findViewById(R.id.page_tokens)
        
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_server -> {
                    pageServer.visibility = android.view.View.VISIBLE
                    pageBackend.visibility = android.view.View.GONE
                    pageTokens.visibility = android.view.View.GONE
                    toolbar.title = "Edge AI Server"
                    true
                }
                R.id.nav_backend -> {
                    pageServer.visibility = android.view.View.GONE
                    pageBackend.visibility = android.view.View.VISIBLE
                    pageTokens.visibility = android.view.View.GONE
                    toolbar.title = "Backend Settings"
                    true
                }
                R.id.nav_tokens -> {
                    pageServer.visibility = android.view.View.GONE
                    pageBackend.visibility = android.view.View.GONE
                    pageTokens.visibility = android.view.View.VISIBLE
                    toolbar.title = "API Token Management"
                    updateAuthorizedAppsUI()
                    true
                }
                else -> false
            }
        }
        
        // Default title
        toolbar.title = "Edge AI Server"
        
        // API Token UI
        tvApiToken = findViewById(R.id.tv_api_token)
        btnGenerateToken = findViewById(R.id.btn_generate_token)
        btnCopyToken = findViewById(R.id.btn_copy_token)
        btnDeleteToken = findViewById(R.id.btn_delete_token)
        btnBackupTokens = findViewById(R.id.btn_backup_tokens)
        btnRestoreTokens = findViewById(R.id.btn_restore_tokens)
        // llPendingRequests & tvPendingLabel removed
        findViewById<android.view.View>(R.id.ll_pending_requests)?.visibility = View.GONE
        findViewById<android.view.View>(R.id.tv_pending_label)?.visibility = View.GONE

        llAuthorizedApps = findViewById(R.id.ll_authorized_apps)
        tvAuthorizedLabel = findViewById(R.id.tv_authorized_label)
        
        // Initialize TokenManager
        tokenManager = TokenManager.getInstance(this)
        
        // Refresh UI
        updateAuthorizedAppsUI()
        
        // Restore existing token (use the first one if any exist)
        val existingTokens = tokenManager.getAllTokens()
        if (existingTokens.isNotEmpty()) {
            currentToken = existingTokens.first()
            updateTokenUI()
            appendLog("Restored existing API token")
        }
        
        // Token button listeners
        setupTokenListeners()

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
                 updateUIForStatus("Idle")
                 appendLog("Stopping server...")
             }
        }

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
        
        btnTestMultiTurn.setOnClickListener { runTestMultiTurn() }
        
        btnTestHealth.setOnClickListener {
            val token = getOrGenerateToken()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val result = performGetRequest("http://localhost:8080/v1/health", token)
                    withContext(Dispatchers.Main) {
                        appendLog("Health Check: $result")
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        appendLog("Health Check Failed: ${e.message}")
                    }
                }
            }
        }
        
        btnTestLoad.setOnClickListener {
            val token = getOrGenerateToken()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val result = performGetRequest("http://localhost:8080/v1/load", token)
                    val json = JSONObject(result)
                    val load = json.optInt("load", -1)
                    withContext(Dispatchers.Main) {
                        appendLog("Current Server Load: $load")
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        appendLog("Get Load Failed: ${e.message}")
                    }
                }
            }
        }
        
        checkAndRequestPermissions()
        requestIgnoreBatteryOptimizations()
    }

    private fun getOrGenerateToken(): String {
        if (currentToken != null) return currentToken!!
        generateNewToken()
        return currentToken!!
    }

    private fun setupTokenListeners() {
        btnGenerateToken.setOnClickListener {
            // Generate new token (or regenerate if one exists)
            if (currentToken != null) {
                // Ask user for confirmation before regenerating
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Generate New Token?")
                    .setMessage("This will create a new token. The old token will still be valid. Continue?")
                    .setPositiveButton("Generate") { _, _ ->
                        generateNewToken()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                generateNewToken()
            }
        }
        
        btnCopyToken.setOnClickListener {
            currentToken?.let { token ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("API Token", token)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, getString(R.string.token_copied), Toast.LENGTH_SHORT).show()
                appendLog("Token copied to clipboard")
            }
        }
        
        btnDeleteToken.setOnClickListener {
            currentToken?.let { token ->
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Delete Token?")
                    .setMessage("This will revoke the token. Any client using this token will lose access. Continue?")
                    .setPositiveButton("Delete") { _, _ ->
                        tokenManager.revokeToken(token)
                        currentToken = null
                        updateTokenUI()
                        Toast.makeText(this, getString(R.string.token_deleted), Toast.LENGTH_SHORT).show()
                        appendLog("Token deleted")
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        btnBackupTokens.setOnClickListener {
            val fileName = "edge_ai_tokens_${System.currentTimeMillis()}.json"
            backupTokensLauncher.launch(fileName)
        }

        btnRestoreTokens.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
            }
            restoreTokensLauncher.launch(arrayOf("application/json"))
        }
    }

    private fun performBackup(uri: Uri) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val tokens = tokenManager.getAllTokens()
                val json = gson.toJson(tokens)
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(json.toByteArray())
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, getString(R.string.tokens_backed_up), Toast.LENGTH_SHORT).show()
                    appendLog("Tokens backed up to: $uri")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Backup failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, getString(R.string.backup_error), Toast.LENGTH_SHORT).show()
                    appendLog("Backup failed: ${e.message}")
                }
            }
        }
    }

    private fun performRestore(uri: Uri) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val json = inputStream.bufferedReader().readText()
                    val type = object : com.google.gson.reflect.TypeToken<Set<String>>() {}.type
                    val tokens: Set<String> = gson.fromJson(json, type)
                    
                    tokenManager.addTokens(tokens)
                    
                    withContext(Dispatchers.Main) {
                        if (currentToken == null && tokens.isNotEmpty()) {
                            currentToken = tokens.first()
                            updateTokenUI()
                        }
                        Toast.makeText(this@MainActivity, getString(R.string.tokens_restored), Toast.LENGTH_SHORT).show()
                        appendLog("Tokens restored from: $uri")
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Restore failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, getString(R.string.restore_error), Toast.LENGTH_SHORT).show()
                    appendLog("Restore failed: ${e.message}")
                }
            }
        }
    }
    
    private fun generateNewToken() {
        currentToken = tokenManager.generateToken()
        updateTokenUI()
        Toast.makeText(this, getString(R.string.token_generated), Toast.LENGTH_SHORT).show()
        appendLog("New API token generated: ${currentToken?.take(8)}...")
    }
    
    private fun updateTokenUI() {
        if (currentToken != null) {
            tvApiToken.text = currentToken
            btnCopyToken.isEnabled = true
            btnDeleteToken.isEnabled = true
        } else {
            tvApiToken.text = getString(R.string.no_token_generated)
            btnCopyToken.isEnabled = false
            btnDeleteToken.isEnabled = false
        }
    }

    private fun updateAuthorizedAppsUI() {
        val mappings = tokenManager.getTokenMappings()
        llAuthorizedApps.removeAllViews()
        
        if (mappings.isEmpty()) {
            tvAuthorizedLabel.visibility = View.GONE
            return
        }
        
        tvAuthorizedLabel.visibility = View.VISIBLE
        mappings.forEach { (pkgName, token) ->
            val itemView = layoutInflater.inflate(android.R.layout.simple_list_item_2, llAuthorizedApps, false)
            itemView.setPadding(32, 16, 32, 16)
            val text1 = itemView.findViewById<TextView>(android.R.id.text1)
            val text2 = itemView.findViewById<TextView>(android.R.id.text2)
            
            text1.text = pkgName
            text2.text = "Token: ${token.take(8)}... (Tap to Revoke)"
            
            itemView.setOnClickListener {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Revoke Access")
                    .setMessage("Revoke API access for '$pkgName'? All active conversations for this token will be closed.")
                    .setPositiveButton("Revoke") { _, _ ->
                        val tokenToRevoke = mappings[pkgName]
                        if (tokenToRevoke != null) {
                            tokenManager.revokeToken(tokenToRevoke)
                            if (currentToken == tokenToRevoke) {
                                currentToken = null
                                updateTokenUI()
                            }
                            updateAuthorizedAppsUI()
                            appendLog("Revoked access for $pkgName")
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            llAuthorizedApps.addView(itemView)
        }
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
         // (WAV Header generation code omitted for brevity as it was already correct, just keeping it same)
         val totalAudioLen = file.length() - 44
        val totalDataLen = totalAudioLen + 36
        val sampleRate = SAMPLE_RATE.toLong()
        val channels = 1
        val byteRate = 16 * sampleRate * channels / 8

        val header = ByteArray(44)
        header[0] = 'R'.code.toByte() // RIFF/WAV header
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = (totalDataLen shr 8 and 0xff).toByte()
        header[6] = (totalDataLen shr 16 and 0xff).toByte()
        header[7] = (totalDataLen shr 24 and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte() // 'fmt ' chunk
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
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
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
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
        val token = getOrGenerateToken()
        
        appendLog("=== Starting Audio Test (Streaming) ===")
        CoroutineScope(Dispatchers.IO).launch {
             try {
                // Step 3: Encode audio
                val bytes = file.readBytes()
                val base64Audio = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                
                val messages = listOf(
                    ChatMessage("user", gson.toJsonTree(listOf(
                        mapOf("type" to "text", "text" to "What is in this audio?"),
                        mapOf("type" to "audio_url", "audio_url" to mapOf("url" to "data:audio/wav;base64,$base64Audio"))
                    )))
                )

                val req = ChatCompletionRequest(model = "gemma-audio", messages = messages, stream = true)

                withContext(Dispatchers.Main) {
                    appendLog("Assistant: ", newLine = false)
                }

                performStreamingChatRequest(req, token) { chunk ->
                    runOnUiThread { appendLog(chunk, newLine = false) }
                }

                withContext(Dispatchers.Main) {
                    appendLog("\n=== Audio Test Complete ===")
                    tvStatus.text = getString(R.string.status_label) + " " + getString(R.string.status_ready)
                }
             } catch (e: Exception) {
                 withContext(Dispatchers.Main) {
                     appendLog("\n❌ Audio Test Failed: ${e.message}")
                     tvStatus.text = getString(R.string.status_label) + " " + getString(R.string.status_error)
                 }
             } finally {
                 file.delete()
             }
        }
    }

    private fun runTestVision(imageUri: Uri) {
        val token = getOrGenerateToken()
        tvStatus.text = getString(R.string.status_label) + " " + getString(R.string.status_selecting_image)
        appendLog("=== Starting Vision Test (Streaming) ===")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Encode image
                val base64Image = getBase64EncodedImage(imageUri)
                if (base64Image == null) {
                    withContext(Dispatchers.Main) {
                         appendLog("❌ Could not encode image")
                    }
                    return@launch
                }

                val messages = listOf(
                    ChatMessage("user", gson.toJsonTree(listOf(
                        mapOf("type" to "text", "text" to "What is in this image?"),
                        mapOf("type" to "image_url", "image_url" to mapOf("url" to "data:image/jpeg;base64,$base64Image"))
                    )))
                )

                val req = ChatCompletionRequest(model = "gemma-vision", messages = messages, stream = true)

                withContext(Dispatchers.Main) {
                    appendLog("Assistant: ", newLine = false)
                }

                performStreamingChatRequest(req, token) { chunk ->
                    runOnUiThread { appendLog(chunk, newLine = false) }
                }

                withContext(Dispatchers.Main) {
                    appendLog("\n=== Vision Test Complete ===")
                    tvStatus.text = getString(R.string.status_label) + " " + getString(R.string.status_ready)
                }
            } catch (e: Exception) {
                 withContext(Dispatchers.Main) {
                     appendLog("\n❌ Vision Test Failed: ${e.message}")
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
                // Ensure it ends with a valid extension, or append .litertlm as default
                val validFileName = if (fileName.endsWith(".litertlm") || fileName.endsWith(".bin") || fileName.endsWith(".tflite")) {
                    fileName
                } else {
                    "$fileName.litertlm"
                }

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
    }

    private fun stopInferenceService() {
        val intent = Intent(this, InferenceService::class.java).apply {
            action = InferenceService.ACTION_STOP
        }
        startService(intent)
    }

    private fun updateUIForStatus(status: String) {
        val baseStatus = getString(R.string.status_label) + " " + status
        
        if (status.contains("loaded", ignoreCase = true)) {
            switchEnableServer.isEnabled = true
            switchEnableServer.isChecked = true
            btnTestInference.isEnabled = true
            btnTestVision.isEnabled = true
            btnTestAudio.isEnabled = true
            btnTestMultiTurn.isEnabled = true
            btnTestHealth.isEnabled = true
            btnTestLoad.isEnabled = true
            tvStatus.text = "$baseStatus (http://localhost:8080)"
        } else if (status.contains("Error", ignoreCase = true)) {
            switchEnableServer.isEnabled = true
            switchEnableServer.isChecked = false
            btnTestInference.isEnabled = false
            btnTestVision.isEnabled = false
            btnTestAudio.isEnabled = false
            btnTestMultiTurn.isEnabled = false
            btnTestHealth.isEnabled = false
            btnTestLoad.isEnabled = false
            tvStatus.text = baseStatus
        } else if (status.contains("Initializing", ignoreCase = true) || 
                   status.contains("Loading", ignoreCase = true) ||
                   status.contains("Verifying", ignoreCase = true) ||
                   status.contains("Restoring", ignoreCase = true) ||
                   status.contains("Service starting", ignoreCase = true)) {
            switchEnableServer.isEnabled = false
            switchEnableServer.isChecked = true
            tvStatus.text = baseStatus
        } else if (status.contains("Idle", ignoreCase = true)) {
            switchEnableServer.isEnabled = (selectedModelPath != null)
            switchEnableServer.isChecked = false
            btnTestInference.isEnabled = false
            tvStatus.text = baseStatus
        } else {
            tvStatus.text = baseStatus
        }
    }

    private fun runTestInference() {
        val token = getOrGenerateToken()
        appendLog("=== Starting Inference Test (Streaming) ===")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val messages = listOf(ChatMessage("user", JsonPrimitive("Hello!")))
                // Enable streaming
                val req = ChatCompletionRequest(model = "gemma", messages = messages, stream = true)

                withContext(Dispatchers.Main) {
                    appendLog("User: Hello!")
                    appendLog("Assistant: ", newLine = false)
                }

                performStreamingChatRequest(req, token) { chunk ->
                    runOnUiThread {
                        appendLog(chunk, newLine = false)
                    }
                }
                
                withContext(Dispatchers.Main) {
                    appendLog("\n=== Test Complete ===")
                    tvStatus.text = getString(R.string.status_label) + " " + getString(R.string.status_ready)
                }
            } catch (e: Exception) {
                 withContext(Dispatchers.Main) {
                     appendLog("\n❌ Test Failed: ${e.message}")
                     tvStatus.text = getString(R.string.status_label) + " " + getString(R.string.status_error)
                 }
            }
        }
    }

    private fun runTestMultiTurn() {
        val token = getOrGenerateToken()
        appendLog("=== Starting Multi-Turn Test (Streaming) ===")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Turn 1
                val messages = mutableListOf(ChatMessage("user", JsonPrimitive("Hello! Who are you?")))
                var req = ChatCompletionRequest(model = "gemma", messages = messages, stream = true)

                withContext(Dispatchers.Main) {
                    appendLog("User: Hello! Who are you?")
                    appendLog("Assistant: ", newLine = false)
                }
                
                var reply1 = ""
                performStreamingChatRequest(req, token) { chunk ->
                    reply1 += chunk
                    runOnUiThread { appendLog(chunk, newLine = false) }
                }
                messages.add(ChatMessage("assistant", JsonPrimitive(reply1)))
                withContext(Dispatchers.Main) { appendLog("\n") }
                
                // Turn 2
                delay(1000)
                messages.add(ChatMessage("user", JsonPrimitive("What did I just ask?")))
                withContext(Dispatchers.Main) {
                    appendLog("User: What did I just ask?")
                    appendLog("Assistant: ", newLine = false)
                }

                req = ChatCompletionRequest(model = "gemma", messages = messages, stream = true)
                var reply2 = ""
                performStreamingChatRequest(req, token) { chunk ->
                    reply2 += chunk
                    runOnUiThread { appendLog(chunk, newLine = false) }
                }
                
                withContext(Dispatchers.Main) {
                    appendLog("\n=== Multi-Turn Test Complete ===")
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) { appendLog("\n❌ Error: ${e.message}") }
            }
        }
    }

    private fun performChatRequest(req: ChatCompletionRequest, token: String): ChatCompletionResponse {
        val url = URL("http://localhost:8080/v1/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.doOutput = true

        val jsonInput = gson.toJson(req)
        OutputStreamWriter(conn.outputStream).use { it.write(jsonInput) }

        val responseCode = conn.responseCode
        if (responseCode == HttpURLConnection.HTTP_OK) {
            val responseString = BufferedReader(InputStreamReader(conn.inputStream)).readText()
            return gson.fromJson(responseString, ChatCompletionResponse::class.java)
        } else {
            val errorString = try {
                 BufferedReader(InputStreamReader(conn.errorStream)).readText()
            } catch (e: Exception) { "Unknown error" }
            throw Exception("HTTP $responseCode: $errorString")
        }
    }

    private fun performStreamingChatRequest(req: ChatCompletionRequest, token: String, onChunk: (String) -> Unit) {
        val url = URL("http://localhost:8080/v1/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.doOutput = true

        val jsonInput = gson.toJson(req)
        OutputStreamWriter(conn.outputStream).use { it.write(jsonInput) }

        val responseCode = conn.responseCode
        if (responseCode == HttpURLConnection.HTTP_OK) {
            BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val currentLine = line ?: continue
                    if (currentLine.startsWith("data: ")) {
                        val data = currentLine.substring(6)
                        if (data == "[DONE]") break

                        try {
                            val chunk = gson.fromJson(data, ChatCompletionChunk::class.java)
                            val content = chunk.choices.firstOrNull()?.delta?.content
                            if (!content.isNullOrEmpty()) {
                                onChunk(content)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing chunk", e)
                        }
                    }
                }
            }
        } else {
            val errorString = try {
                 BufferedReader(InputStreamReader(conn.errorStream)).readText()
            } catch (e: Exception) { "Unknown error" }
            throw Exception("HTTP $responseCode: $errorString")
        }
    }

    private fun performGetRequest(urlString: String, token: String): String {
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Authorization", "Bearer $token")

        val responseCode = conn.responseCode
        if (responseCode == HttpURLConnection.HTTP_OK) {
            return BufferedReader(InputStreamReader(conn.inputStream)).readText()
        } else {
             throw Exception("HTTP $responseCode")
        }
    }

    private fun appendLog(msg: String, newLine: Boolean = true) {
        val current = tvLogs.text.toString()
        if (newLine) {
            tvLogs.text = "$current\n$msg"
        } else {
            tvLogs.text = "$current$msg"
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "litertlm_prefs"
        private const val KEY_MODEL_PATH = "model_path"
    }
}
