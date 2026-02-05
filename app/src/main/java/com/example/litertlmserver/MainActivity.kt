package com.example.litertlmserver

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

class MainActivity : AppCompatActivity() {

    private lateinit var btnSelectModel: Button
    private lateinit var tvModelPath: TextView
    private lateinit var switchEnableServer: SwitchMaterial
    private lateinit var tvStatus: TextView
    private lateinit var btnTestInference: Button
    private lateinit var tvLogs: TextView

    private var selectedModelPath: String? = null

    private val selectModelLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                copyModelFile(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnSelectModel = findViewById(R.id.btn_select_model)
        tvModelPath = findViewById(R.id.tv_model_path)
        switchEnableServer = findViewById(R.id.switch_enable_server)
        tvStatus = findViewById(R.id.tv_status)
        btnTestInference = findViewById(R.id.btn_test_inference)
        tvLogs = findViewById(R.id.tv_logs)

        btnSelectModel.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            selectModelLauncher.launch(intent)
        }

        switchEnableServer.setOnCheckedChangeListener { _, isChecked ->
            if (selectedModelPath == null) {
                if (isChecked) {
                    switchEnableServer.isChecked = false
                    appendLog("Error: No model selected")
                }
                return@setOnCheckedChangeListener
            }

            if (isChecked) {
                startInferenceService()
                tvStatus.text = getString(R.string.status_label) + " " + getString(R.string.status_loading)
                appendLog("Starting server...")
            } else {
                stopInferenceService()
                tvStatus.text = getString(R.string.status_label) + " " + getString(R.string.status_idle)
                btnTestInference.isEnabled = false
                appendLog("Stopping server...")
            }
        }

        btnTestInference.setOnClickListener {
            runTestInference()
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
        val intent = Intent(this, InferenceService::class.java).apply {
            putExtra(InferenceService.EXTRA_MODEL_PATH, selectedModelPath)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        btnTestInference.isEnabled = true
    }

    private fun stopInferenceService() {
        val intent = Intent(this, InferenceService::class.java).apply {
            action = InferenceService.ACTION_STOP
        }
        startService(intent)
    }

    private fun runTestInference() {
        appendLog("Sending test request...")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("http://localhost:8080/v1/chat/completions")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true

                val jsonInputString = """
                    {
                        "model": "gemma",
                        "messages": [{"role": "user", "content": "Hello, are you working?"}]
                    }
                """.trimIndent()

                conn.outputStream.use { os ->
                    val input = jsonInputString.toByteArray(Charset.forName("utf-8"))
                    os.write(input, 0, input.size)
                }

                val code = conn.responseCode
                if (code == 200) {
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    withContext(Dispatchers.Main) {
                        appendLog("Response ($code): $response")
                        tvStatus.text = getString(R.string.status_label) + " " + getString(R.string.status_ready)
                    }
                } else {
                    val error = conn.errorStream?.bufferedReader()?.use { it.readText() }
                    withContext(Dispatchers.Main) {
                        appendLog("Error ($code): $error")
                        tvStatus.text = getString(R.string.status_label) + " " + getString(R.string.status_error)
                    }
                }

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
    }
}
