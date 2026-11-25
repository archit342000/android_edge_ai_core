package com.aanand.edgeaicore

import androidx.core.content.ContextCompat
import android.animation.AnimatorInflater
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.animation.AnimationUtils
import android.view.inputmethod.EditorInfo
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationView
import com.google.android.material.navigationrail.NavigationRailView
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
import android.widget.EditText
import android.widget.ImageView
import android.view.ViewGroup
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.net.toUri
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import java.io.File
import java.util.Locale
import java.io.FileOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import android.content.ClipboardManager
import android.content.ClipData
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
    private lateinit var rgSpeculative: RadioGroup
    private lateinit var rgVisionBackend: RadioGroup
    private lateinit var rgAudioBackend: RadioGroup
    private lateinit var etContextSize: EditText
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
    private lateinit var tvNoAuthorizedApps: TextView
    // One of the three navigation pieces exists, depending on the width frame.
    private var bottomNav: BottomNavigationView? = null
    private var navRail: NavigationRailView? = null
    private var navPanel: NavigationView? = null
    private lateinit var toolbar: MaterialToolbar
    private var currentPage = R.id.nav_server
    private var serverScrolledPastOpen = false
    private var solid = false
    private lateinit var pageServer: android.view.View
    private lateinit var pageBackend: android.view.View
    private lateinit var pageTokens: android.view.View
    private lateinit var tokenManager: TokenManager
    private var currentToken: String? = null

    private var selectedModelPath: String? = null
    private val gson = Gson()

    // Console at Rest UI. Control state is decided by ControlsPolicy and applied only in render().
    private lateinit var sphere: DotSphereView
    private lateinit var ui: ConsoleUi
    private lateinit var ivStatusLight: ImageView
    private lateinit var tvStatusWord: TextView
    private lateinit var workingDots: ViewGroup
    private var ivHeaderStatusLight: ImageView? = null
    private var tvHeaderStatusWord: TextView? = null
    private lateinit var tvSwitchReason: TextView
    private lateinit var tvTestsReason: TextView
    private lateinit var tvTokenReason: TextView
    private lateinit var tvContextSizeReason: TextView
    private lateinit var progressModelCopy: LinearProgressIndicator
    private lateinit var noticeError: View
    private lateinit var tvNoticeText: TextView
    private lateinit var btnNoticeAction: MaterialButton
    private lateinit var testButtons: Map<BusyTask, MaterialButton>
    private val labels = HashMap<MaterialButton, CharSequence>()
    private var pendingAction = PendingAction.NONE
    private val busy = mutableSetOf<BusyTask>()
    private var contextSizeInvalid = false
    private var errorAction: ErrorAction? = null
    private lateinit var foldHeader: View
    private lateinit var foldBody: View
    private lateinit var foldChevron: View
    private lateinit var cardExplain: View
    private lateinit var cardExplainMic: View
    private lateinit var tvSettingsState: TextView
    private lateinit var tvSettingsNote: TextView
    private var awaitingCard: ExplainCard? = null
    private var micCardShown = false
    private var micAwaiting = false

    /** Back returns to Server from the other pages; on Server it is off, so Back leaves the app. */
    private val backToServer = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            showPage(R.id.nav_server)
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "Broadcast received: ${intent.action}")
            if (intent.action == InferenceService.ACTION_STATUS_UPDATE) {
                val status = ServerStatusStore.current
                Log.d(TAG, "Received STATUS_UPDATE: ${status.kind}")
                if (status.detail.isNotEmpty()) appendLog(getString(R.string.log_status, status.detail))
                pendingAction = PendingAction.NONE
                render()
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
        micAwaiting = false
        micCardShown = false
        if (isGranted) {
            startAudioRecording()
        } else {
            appendLog(getString(R.string.log_mic_denied))
            ui.brief(getString(R.string.microphone_off))
        }
        render()
    }

    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        answerCard(ExplainCard.NOTIFICATIONS)
    }

    private val batteryExemptionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        answerCard(ExplainCard.BATTERY)
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(InferenceService.ACTION_STATUS_UPDATE)
        }
        ContextCompat.registerReceiver(this, statusReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        // A status reached while the activity was in the background did not arrive as a broadcast; read it now.
        pendingAction = PendingAction.NONE
        render()
        sphere.start()
        ui.resume()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(statusReceiver)
        sphere.stop()
        ui.pause()
    }

    override fun onDestroy() {
        if (::ui.isInitialized) ui.dismissSheet()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_PAGE, currentPage)
        outState.putBoolean(STATE_FOLD_OPEN, foldBody.isVisible)
    }

    /** The sphere leans toward a touch and returns to the middle when it ends; the touch goes on to the views as usual. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> sphere.leanToward(ev.x, ev.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> sphere.release()
        }
        return super.dispatchTouchEvent(ev)
    }

    /** Escape closes the top layer (rule book 7.14): on Backend or Tokens that is the page, so it returns to Server. */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE && backToServer.isEnabled) {
            showPage(R.id.nav_server)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Frosted glass needs API 31; below it, or when a debug build is asked to, every surface uses its solid colour.
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        solid = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || (debuggable && intent.getBooleanExtra(EXTRA_FORCE_SOLID, false))
        if (solid) theme.applyStyle(R.style.ThemeOverlay_Console_Solid, true)
        setContentView(R.layout.activity_main)

        btnSelectModel = findViewById(R.id.btn_select_model)
        tvModelPath = findViewById(R.id.tv_model_path)
        switchEnableServer = findViewById(R.id.switch_enable_server)
        tvStatus = findViewById(R.id.tv_status)
        rgBackend = findViewById(R.id.rg_backend)
        rgSpeculative = findViewById(R.id.rg_speculative)
        rgVisionBackend = findViewById(R.id.rg_vision_backend)
        rgAudioBackend = findViewById(R.id.rg_audio_backend)
        etContextSize = findViewById(R.id.et_context_size)
        btnTestInference = findViewById(R.id.btn_test_inference)
        btnTestVision = findViewById(R.id.btn_test_vision)
        btnTestAudio = findViewById(R.id.btn_test_audio)
        btnTestMultiTurn = findViewById(R.id.btn_test_multiturn)
        btnTestHealth = findViewById(R.id.btn_test_health)
        btnTestLoad = findViewById(R.id.btn_test_load)
        tvLogs = findViewById(R.id.tv_logs)
        sphere = findViewById(R.id.sphere)
        ui = ConsoleUi(this, findViewById(R.id.brief_host))
        ivStatusLight = findViewById(R.id.iv_status_light)
        tvStatusWord = findViewById(R.id.tv_status_word)
        workingDots = findViewById(R.id.working_dots)
        ivHeaderStatusLight = findViewById(R.id.iv_header_status_light)
        tvHeaderStatusWord = findViewById(R.id.tv_header_status_word)
        // At 1024 and over the content header carries the status, so the Server page does not show its light and word again.
        // The working dots stay: the header has none of its own.
        if (tvHeaderStatusWord != null) {
            listOf(ivStatusLight, tvStatusWord).forEach { it.visibility = View.GONE }
            // With the light and word gone the dots start the line, so they drop the gap that followed the word.
            workingDots.updateLayoutParams<ViewGroup.MarginLayoutParams> { marginStart = 0 }
        }
        listOf(rgBackend, rgSpeculative, rgVisionBackend, rgAudioBackend).forEach(ui::fitSegments)
        tvSwitchReason = findViewById(R.id.tv_switch_reason)
        tvTestsReason = findViewById(R.id.tv_tests_reason)
        tvTokenReason = findViewById(R.id.tv_token_reason)
        tvContextSizeReason = findViewById(R.id.tv_context_size_reason)
        progressModelCopy = findViewById(R.id.progress_model_copy)
        noticeError = findViewById(R.id.notice_error)
        tvNoticeText = noticeError.findViewById(R.id.tv_notice_text)
        btnNoticeAction = noticeError.findViewById(R.id.btn_notice_action)
        noticeError.findViewById<ImageView>(R.id.iv_notice_light).setImageLevel(ServerStatus.ERROR.ordinal)
        findViewById<TextView>(R.id.tv_server_address).text = SERVER_ADDRESS
        testButtons = mapOf(
            BusyTask.TEST_INFERENCE to btnTestInference, BusyTask.TEST_MULTI_TURN to btnTestMultiTurn,
            BusyTask.TEST_VISION to btnTestVision, BusyTask.TEST_AUDIO to btnTestAudio,
            BusyTask.TEST_HEALTH to btnTestHealth, BusyTask.TEST_LOAD to btnTestLoad,
        )
        foldHeader = findViewById(R.id.fold_diagnostics)
        foldBody = findViewById(R.id.fold_diagnostics_body)
        foldChevron = findViewById(R.id.iv_fold_chevron)
        cardExplain = findViewById(R.id.card_explain)
        cardExplainMic = findViewById(R.id.card_explain_mic)
        tvSettingsState = findViewById(R.id.tv_settings_state)
        tvSettingsNote = findViewById(R.id.tv_settings_note)

        // Navigation UI
        toolbar = findViewById(R.id.toolbar)
        bottomNav = findViewById(R.id.bottom_navigation)
        navRail = findViewById(R.id.nav_rail)
        navPanel = findViewById(R.id.nav_panel)
        pageServer = findViewById(R.id.page_server)
        pageBackend = findViewById(R.id.page_backend)
        pageTokens = findViewById(R.id.page_tokens)
        bottomNav?.setOnItemSelectedListener { showPage(it.itemId) }
        navRail?.setOnItemSelectedListener { showPage(it.itemId) }
        navPanel?.setNavigationItemSelectedListener { showPage(it.itemId) }
        navRail?.let { rail ->
            // Icons only: each keeps its title as its spoken name and gets it as a tip.
            for (i in 0 until rail.menu.size()) {
                val item = rail.menu.getItem(i)
                rail.findViewById<View>(item.itemId)?.let { TooltipCompat.setTooltipText(it, item.title) }
            }
        }
        onBackPressedDispatcher.addCallback(this, backToServer)
        val openUntilScroll = resources.getDimensionPixelSize(R.dimen.sphere_open_until_scroll)
        (pageServer as androidx.core.widget.NestedScrollView).setOnScrollChangeListener { _: androidx.core.widget.NestedScrollView, _: Int, y: Int, _: Int, _: Int ->
            serverScrolledPastOpen = y > openUntilScroll
            updateSphereStrength()
        }
        foldHeader.setOnClickListener { setFoldOpen(!foldBody.isVisible, animate = true) }
        fillToBottom(pageServer, R.id.server_panel)
        fillToBottom(pageBackend, R.id.backend_panel)
        fillToBottom(pageTokens, R.id.tokens_panel)
        setUpEdgeToEdge()
        attachFrosts()
        
        // API Token UI
        tvApiToken = findViewById(R.id.tv_api_token)
        btnGenerateToken = findViewById(R.id.btn_generate_token)
        btnCopyToken = findViewById(R.id.btn_copy_token)
        btnDeleteToken = findViewById(R.id.btn_delete_token)
        btnBackupTokens = findViewById(R.id.btn_backup_tokens)
        btnRestoreTokens = findViewById(R.id.btn_restore_tokens)
        (testButtons.values + listOf(btnSelectModel, btnBackupTokens, btnRestoreTokens)).forEach { labels[it] = it.text }

        llAuthorizedApps = findViewById(R.id.ll_authorized_apps)
        tvNoAuthorizedApps = findViewById(R.id.tv_no_authorized_apps)
        
        // Initialize TokenManager
        tokenManager = TokenManager.getInstance(this)
        
        // Refresh UI
        updateAuthorizedAppsUI()
        
        // Restore existing token (use the first one if any exist)
        val existingTokens = tokenManager.getAllTokens()
        if (existingTokens.isNotEmpty()) {
            currentToken = existingTokens.first()
            updateTokenUI()
            appendLog(getString(R.string.log_token_restored))
        }
        
        // Token button listeners
        setupTokenListeners()

        // Restore saved model path
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        rgSpeculative.check(
            when (SpeculativeDecodingOption.fromStored(prefs.getString(KEY_SPECULATIVE, null))) {
                SpeculativeDecodingOption.OFF -> R.id.rb_spec_off
                SpeculativeDecodingOption.ON -> R.id.rb_spec_on
                SpeculativeDecodingOption.ADAPTIVE -> R.id.rb_spec_adaptive
            }
        )
        rgVisionBackend.check(encoderRadioId(EncoderBackendOption.fromStored(prefs.getString(KEY_VISION_BACKEND, null)), R.id.rb_vision_auto, R.id.rb_vision_cpu, R.id.rb_vision_gpu))
        rgAudioBackend.check(encoderRadioId(EncoderBackendOption.fromStored(prefs.getString(KEY_AUDIO_BACKEND, null)), R.id.rb_audio_auto, R.id.rb_audio_cpu, R.id.rb_audio_gpu))
        rgBackend.check(
            when (prefs.getString(KEY_BACKEND, null)) {
                BACKEND_CPU -> R.id.rb_cpu
                BACKEND_NPU -> R.id.rb_npu
                else -> R.id.rb_gpu
            }
        )
        val savedContextSize = prefs.getInt(KEY_CONTEXT_SIZE, 0)
        if (savedContextSize > 0) etContextSize.setText(String.format(Locale.ROOT, "%d", savedContextSize)) // lint SetTextI18n: no bare number text
        setUpSettingsListeners()
        val savedPath = prefs.getString(KEY_MODEL_PATH, null)
        if (savedPath != null) {
            val file = File(savedPath)
            if (file.exists()) {
                selectedModelPath = savedPath
                tvModelPath.text = savedPath
                appendLog(getString(R.string.log_model_restored, savedPath))
            }
        }

        btnSelectModel.setOnClickListener { openModelPicker() }

        // A click, not a checked-change listener: the switch shows what the person chose until render() shows the state.
        switchEnableServer.setOnClickListener {
            if (switchEnableServer.isChecked) requestStart() else requestStop()
        }

        btnNoticeAction.setOnClickListener {
            when (errorAction) {
                ErrorAction.TRY_AGAIN -> requestStart()
                ErrorAction.SELECT_MODEL -> openModelPicker()
                null -> Unit
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
                // Explain first; the system prompt comes only from the card's allow button.
                micCardShown = true
                render()
            }
        }
        
        btnTestMultiTurn.setOnClickListener { runTestMultiTurn() }
        
        btnTestHealth.setOnClickListener {
            val token = getOrGenerateToken()
            startBusy(BusyTask.TEST_HEALTH)
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val result = performGetRequest("$SERVER_ADDRESS/v1/health", token)
                    withContext(Dispatchers.Main) {
                        appendLog(getString(R.string.log_health, result))
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        appendLog(getString(R.string.log_health_failed, e.message))
                    }
                } finally {
                    endBusy(BusyTask.TEST_HEALTH)
                }
            }
        }

        btnTestLoad.setOnClickListener {
            val token = getOrGenerateToken()
            startBusy(BusyTask.TEST_LOAD)
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val result = performGetRequest("$SERVER_ADDRESS/v1/load", token)
                    val json = JSONObject(result)
                    val load = json.optInt("load", -1)
                    withContext(Dispatchers.Main) {
                        appendLog(getString(R.string.log_load, load))
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        appendLog(getString(R.string.log_load_failed, e.message))
                    }
                } finally {
                    endBusy(BusyTask.TEST_LOAD)
                }
            }
        }
        
        setFoldOpen(savedInstanceState?.getBoolean(STATE_FOLD_OPEN) == true, animate = false)
        showPage(savedInstanceState?.getInt(STATE_PAGE, R.id.nav_server) ?: R.id.nav_server, animate = false)
        render()
    }

    /** Shows one page: its visibility, the top bar title, the selected navigation item, Back and the sphere's strength. */
    private fun showPage(itemId: Int, animate: Boolean = true): Boolean {
        val (page, title) = when (itemId) {
            R.id.nav_server -> pageServer to R.string.nav_server
            R.id.nav_backend -> pageBackend to R.string.nav_backend
            R.id.nav_tokens -> pageTokens to R.string.nav_tokens
            else -> return false
        }
        val changed = currentPage != itemId || page.visibility != View.VISIBLE
        currentPage = itemId
        for (p in listOf(pageServer, pageBackend, pageTokens)) p.visibility = if (p === page) View.VISIBLE else View.GONE
        toolbar.title = getString(title)
        bottomNav?.menu?.findItem(itemId)?.isChecked = true
        navRail?.menu?.findItem(itemId)?.isChecked = true
        navPanel?.setCheckedItem(itemId)
        if (itemId == R.id.nav_tokens) updateAuthorizedAppsUI()
        backToServer.isEnabled = itemId != R.id.nav_server
        updateSphereStrength()
        if (animate && changed && ValueAnimator.areAnimatorsEnabled()) {
            AnimatorInflater.loadAnimator(this, R.animator.fade_scale).apply { setTarget(page) }.start()
        }
        return true
    }

    /** Keeps a page's reading panel reaching the bottom of the screen when its content is shorter than the page. */
    private fun fillToBottom(page: View, panelId: Int) {
        val panel = page.findViewById<View>(panelId)
        page.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val offset = panel.top
            val min = maxOf(0, bottom - top - offset)
            if (panel.minimumHeight != min) panel.post { panel.minimumHeight = min }
        }
    }

    /** Open at the top of Server; reading strength once its headline has scrolled away, and on every other page. */
    private fun updateSphereStrength() {
        sphere.setOpen(currentPage == R.id.nav_server && !serverScrolledPastOpen)
    }

    private fun setFoldOpen(open: Boolean, animate: Boolean) {
        foldBody.visibility = if (open) View.VISIBLE else View.GONE
        val angle = if (open) resources.getInteger(R.integer.fold_chevron_open_degrees).toFloat() else 0f
        foldChevron.animate().cancel()
        if (animate && ValueAnimator.areAnimatorsEnabled()) {
            foldChevron.animate().rotation(angle)
                .setDuration(resources.getInteger(R.integer.duration_base).toLong())
                .setInterpolator(AnimationUtils.loadInterpolator(this, R.interpolator.ease_spring))
                .start()
        } else {
            foldChevron.rotation = angle
        }
        ViewCompat.setStateDescription(foldHeader, getString(if (open) R.string.state_expanded else R.string.state_collapsed))
    }

    /**
     * Draws behind the system bars and keeps content clear of them and of the keyboard (rule book Part 6). Bar icons follow
     * day and night here, for every API level.
     */
    private fun setUpEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
        // The background also fills a camera cutout on a short edge (landscape); the insets below keep content clear of it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val gap = resources.getDimensionPixelSize(R.dimen.floating_gap)
        val topBarHeight = resources.getDimensionPixelSize(R.dimen.top_bar_height)
        val briefHost = findViewById<View>(R.id.brief_host)
        val frameRow = findViewById<View?>(R.id.frame_row)
        val column = findViewById<View>(R.id.content_column)
        val bars = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        val phoneNav = bottomNav
        if (frameRow == null && phoneNav != null) {
            ViewCompat.setOnApplyWindowInsetsListener(column) { _, insets ->
                val b = insets.getInsets(bars)
                val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
                val typing = insets.isVisible(WindowInsetsCompat.Type.ime())
                // The bar reaches under the status bar; its own height stays clear of it.
                toolbar.updatePadding(top = b.top)
                toolbar.minimumHeight = topBarHeight + b.top
                column.updatePadding(left = b.left, right = b.right, bottom = if (typing) ime.bottom else 0)
                // While typing, the field rides above the keyboard and the bottom bar steps aside.
                phoneNav.visibility = if (typing) View.GONE else View.VISIBLE
                briefHost.updatePadding(bottom = if (typing) ime.bottom else b.bottom + phoneNav.height)
                insets
            }
            phoneNav.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) ViewCompat.requestApplyInsets(column)
            }
        } else if (frameRow != null) {
            ViewCompat.setOnApplyWindowInsetsListener(frameRow) { _, insets ->
                val b = insets.getInsets(bars)
                val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
                val bottom = maxOf(b.bottom, ime.bottom)
                frameRow.updatePadding(left = gap + b.left, top = gap + b.top, right = gap + b.right, bottom = gap + bottom)
                briefHost.updatePadding(bottom = gap + bottom)
                // The frame has made room; the rail and the navigation panel must not pad again.
                WindowInsetsCompat.CONSUMED
            }
        }
    }

    /**
     * On API 31 and later in glass mode, frosts the floating navigation piece (rail or navigation panel) with a blurred
     * copy of the sphere. Content panels use the light glass tint alone: rule book 2.1 keeps their blur "almost none", and a
     * blurred copy behind every panel cost about two thirds of CPU decode speed on the test emulator.
     */
    private fun attachFrosts() {
        if (solid || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val r = resources
        val panel = floatArrayOf(
            r.getDimension(R.dimen.radius_panel_top_left), r.getDimension(R.dimen.radius_panel_top_right),
            r.getDimension(R.dimen.radius_panel_bottom_right), r.getDimension(R.dimen.radius_panel_bottom_left),
        )
        findViewById<SphereFrostView?>(R.id.frost_nav)?.attach(sphere, r.getDimension(R.dimen.glass_float_blur), panel)
    }

    /** Settings apply at once (rule book 7.7): each change is saved as it is made, with "Saving" then "Saved". */
    private fun setUpSettingsListeners() {
        rgBackend.setOnCheckedChangeListener { _, id -> saveSetting { putString(KEY_BACKEND, backendName(id)) } }
        rgSpeculative.setOnCheckedChangeListener { _, id -> saveSetting { putString(KEY_SPECULATIVE, speculativeOption(id).name) } }
        rgVisionBackend.setOnCheckedChangeListener { _, id ->
            saveSetting { putString(KEY_VISION_BACKEND, encoderOption(id, R.id.rb_vision_cpu, R.id.rb_vision_gpu).name) }
        }
        rgAudioBackend.setOnCheckedChangeListener { _, id ->
            saveSetting { putString(KEY_AUDIO_BACKEND, encoderOption(id, R.id.rb_audio_cpu, R.id.rb_audio_gpu).name) }
        }
        // Checked when the person leaves the box or presses Done, never while they type.
        etContextSize.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitContextSize() }
        etContextSize.setOnEditorActionListener { view, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
            view.clearFocus()
            WindowCompat.getInsetsController(window, view).hide(WindowInsetsCompat.Type.ime())
            true
        }
    }

    private fun commitContextSize() {
        val stored = ContextSizeInput.toStored(ContextSizeInput.parse(etContextSize.text.toString()))
        contextSizeInvalid = stored == null
        render()
        if (stored != null) saveSetting { putInt(KEY_CONTEXT_SIZE, stored) }
    }

    /**
     * Writes one setting. "Saving" shows while the write is made; "Saved" once SharedPreferences holds it (the disk write
     * finishes before the activity pauses). A running server reads settings when it starts, so the page says so.
     */
    private fun saveSetting(write: android.content.SharedPreferences.Editor.() -> Unit) {
        tvSettingsState.text = getString(R.string.settings_saving)
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit { write() }
        val running = ServerStatusStore.current.kind.let { it == ServerStatus.STARTING || it == ServerStatus.READY }
        tvSettingsState.post {
            tvSettingsState.text = getString(R.string.settings_saved)
            tvSettingsNote.visibility = if (running) View.VISIBLE else View.GONE
        }
    }

    private fun backendName(checkedId: Int) = when (checkedId) {
        R.id.rb_cpu -> BACKEND_CPU
        R.id.rb_npu -> BACKEND_NPU
        else -> BACKEND_GPU
    }

    private fun speculativeOption(checkedId: Int) = when (checkedId) {
        R.id.rb_spec_off -> SpeculativeDecodingOption.OFF
        R.id.rb_spec_on -> SpeculativeDecodingOption.ON
        else -> SpeculativeDecodingOption.ADAPTIVE
    }

    /** Records an answer to an explain card ("Not now", the system's answer, or a failed launch); it is not shown again. */
    private fun answerCard(card: ExplainCard) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            putBoolean(if (card == ExplainCard.NOTIFICATIONS) KEY_ANSWERED_NOTIFICATIONS else KEY_ANSWERED_BATTERY, true)
        }
        if (awaitingCard == card) awaitingCard = null
        render()
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return answerCard(ExplainCard.NOTIFICATIONS)
        awaitingCard = ExplainCard.NOTIFICATIONS
        render()
        notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    // The exemption keeps the foreground service alive (docs/standards/android.md); it is asked for only from its card.
    @android.annotation.SuppressLint("BatteryLife")
    private fun askBatteryExemption() {
        awaitingCard = ExplainCard.BATTERY
        render()
        try {
            batteryExemptionLauncher.launch(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:$packageName".toUri()))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No screen for the battery exemption", e)
            answerCard(ExplainCard.BATTERY)
        }
    }

    private fun renderExplainCards(kind: ServerStatus) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val notificationsMissing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        val batteryMissing = !(getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        val choice = ExplainCardPolicy.next(
            ExplainCardState(
                kind, notificationsMissing, batteryMissing,
                prefs.getBoolean(KEY_ANSWERED_NOTIFICATIONS, false), prefs.getBoolean(KEY_ANSWERED_BATTERY, false), awaitingCard,
            )
        )
        when (choice.card) {
            null -> cardExplain.visibility = View.GONE
            ExplainCard.NOTIFICATIONS -> ui.bindExplainCard(
                cardExplain, getString(R.string.explain_notifications_title), getString(R.string.explain_notifications_body),
                getString(R.string.explain_notifications_allow), choice.buttonsEnabled,
                onAllow = { askNotifications() }, onNotNow = { answerCard(ExplainCard.NOTIFICATIONS) },
            )
            ExplainCard.BATTERY -> ui.bindExplainCard(
                cardExplain, getString(R.string.explain_battery_title), getString(R.string.explain_battery_body),
                getString(R.string.explain_battery_allow), choice.buttonsEnabled,
                onAllow = { askBatteryExemption() }, onNotNow = { answerCard(ExplainCard.BATTERY) },
            )
        }
        if (!micCardShown) {
            cardExplainMic.visibility = View.GONE
            return
        }
        ui.bindExplainCard(
            cardExplainMic, getString(R.string.explain_microphone_title), getString(R.string.explain_microphone_body),
            getString(R.string.explain_microphone_allow), !micAwaiting,
            onAllow = {
                micAwaiting = true
                render()
                requestPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            },
            onNotNow = {
                micCardShown = false
                render()
            },
        )
    }

    private fun getOrGenerateToken(): String = currentToken ?: tokenManager.generateToken().also {
        currentToken = it
        updateTokenUI()
    }

    private fun openModelPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        selectModelLauncher.launch(intent)
    }

    /** Switching the server on (or "Try again"): checks the context size, then asks the service to start. */
    private fun requestStart() {
        val contextTokens = ContextSizeInput.toStored(ContextSizeInput.parse(etContextSize.text.toString()))
        if (contextTokens == null) {
            contextSizeInvalid = true
            ui.brief(getString(R.string.context_size_check_backend))
            render()
            return
        }
        contextSizeInvalid = false
        pendingAction = PendingAction.START
        try {
            startInferenceService(contextTokens)
            appendLog(getString(R.string.log_starting))
            sphere.ripple()
        } catch (e: IllegalStateException) {
            couldNotReachService(e)
        } catch (e: SecurityException) {
            couldNotReachService(e)
        }
        render()
    }

    private fun requestStop() {
        pendingAction = PendingAction.STOP
        try {
            stopInferenceService()
            appendLog(getString(R.string.log_stopping))
        } catch (e: IllegalStateException) {
            couldNotReachService(e)
        } catch (e: SecurityException) {
            couldNotReachService(e)
        }
        render()
    }

    private fun couldNotReachService(e: Exception) {
        pendingAction = PendingAction.NONE
        Log.e(TAG, "Could not reach the inference service", e)
        ui.brief(getString(R.string.service_unreachable))
    }

    /** Marks [task] busy and redraws. Main thread. */
    private fun startBusy(task: BusyTask) {
        busy.add(task)
        render()
    }

    /** Ends [task] from any thread (called from `finally` blocks). */
    private fun endBusy(task: BusyTask) {
        runOnUiThread {
            busy.remove(task)
            render()
        }
    }

    /**
     * The only code that sets a control's enabled or checked state, the status light, word and detail, reason lines,
     * busy labels, the working movements and the error strip. Every event ends here.
     */
    private fun render() {
        val status = ServerStatusStore.current
        val c = ControlsPolicy.compute(ControlsState(status, pendingAction, selectedModelPath != null, currentToken != null, busy))
        val word = getString(statusWordRes(c.kind))
        listOf(ivStatusLight to tvStatusWord, ivHeaderStatusLight to tvHeaderStatusWord).forEach { (light, label) ->
            light?.setImageLevel(c.kind.ordinal)
            light?.let { ui.setLightPulsing(it, c.working) }
            label?.text = word
        }
        // An error's detail is in the notice strip, so it is not repeated above it.
        tvStatus.text = if (c.kind == status.kind && c.kind != ServerStatus.ERROR) status.detail else ""
        tvStatus.visibility = if (tvStatus.text.isEmpty()) View.GONE else View.VISIBLE
        ui.setDotsWorking(workingDots, c.working)
        sphere.setWorking(c.working)

        switchEnableServer.isChecked = c.switchChecked
        switchEnableServer.isEnabled = c.switchEnabled
        showReason(tvSwitchReason, c.switchReason)
        for ((task, button) in testButtons) {
            val state = c.tests.getValue(task)
            button.isEnabled = state.enabled
            val busyLabel = if (task == BusyTask.TEST_AUDIO && isRecording) R.string.busy_recording else R.string.busy_running
            ui.setBusy(button, state.busy, labels.getValue(button), getString(busyLabel))
        }
        showReason(tvTestsReason, c.testsReason)
        btnSelectModel.isEnabled = c.selectModel.enabled
        ui.setBusy(btnSelectModel, c.selectModel.busy, labels.getValue(btnSelectModel), getString(R.string.busy_copying))
        progressModelCopy.visibility = if (c.selectModel.busy) View.VISIBLE else View.GONE

        btnCopyToken.isEnabled = c.copyToken.enabled
        btnDeleteToken.isEnabled = c.deleteToken.enabled
        showReason(tvTokenReason, c.tokenReason)
        btnBackupTokens.isEnabled = c.backup.enabled
        ui.setBusy(btnBackupTokens, c.backup.busy, labels.getValue(btnBackupTokens), getString(R.string.busy_backing_up))
        btnRestoreTokens.isEnabled = c.restore.enabled
        ui.setBusy(btnRestoreTokens, c.restore.busy, labels.getValue(btnRestoreTokens), getString(R.string.busy_restoring))

        etContextSize.isActivated = contextSizeInvalid
        tvContextSizeReason.text = getString(R.string.context_size_invalid)
        tvContextSizeReason.visibility = if (contextSizeInvalid) View.VISIBLE else View.GONE

        errorAction = c.errorAction
        val noticeArriving = c.errorAction != null && noticeError.visibility != View.VISIBLE
        noticeError.visibility = if (c.errorAction == null) View.GONE else View.VISIBLE
        if (noticeArriving) ui.arrive(noticeError)
        tvNoticeText.text = status.detail
        btnNoticeAction.text = getString(if (c.errorAction == ErrorAction.SELECT_MODEL) R.string.select_model else R.string.try_again)
        btnNoticeAction.isEnabled = c.errorActionEnabled

        renderExplainCards(status.kind)
    }

    private fun showReason(view: TextView, reason: OffReason?) {
        view.visibility = if (reason == null) View.GONE else View.VISIBLE
        if (reason != null) view.text = getString(reasonRes(reason))
    }

    private fun reasonRes(reason: OffReason) = when (reason) {
        OffReason.SELECT_MODEL_FIRST -> R.string.reason_select_model_first
        OffReason.WAIT_FOR_COPY -> R.string.reason_wait_for_copy
        OffReason.START_SERVER_FIRST -> R.string.reason_start_server_first
        OffReason.GENERATE_TOKEN_FIRST -> R.string.reason_generate_token_first
    }

    private fun statusWordRes(kind: ServerStatus) = when (kind) {
        ServerStatus.OFF -> R.string.status_word_off
        ServerStatus.STARTING -> R.string.status_word_starting
        ServerStatus.READY -> R.string.status_word_ready
        ServerStatus.ERROR -> R.string.status_word_error
    }

    private fun setupTokenListeners() {
        // Generating is not confirmed: it can be undone by generating again (only the irreversible asks first).
        btnGenerateToken.setOnClickListener { generateNewToken() }

        btnCopyToken.setOnClickListener {
            currentToken?.let { token ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText(getString(R.string.section_api_token), token)
                clipboard.setPrimaryClip(clip)
                ui.brief(getString(R.string.token_copied))
                appendLog(getString(R.string.token_copied))
            }
        }

        btnDeleteToken.setOnClickListener {
            currentToken?.let { token ->
                ui.confirm(getString(R.string.delete_token_title), getString(R.string.delete_token_body), getString(R.string.delete_token)) {
                    tokenManager.revokeToken(token)
                    currentToken = null
                    updateTokenUI()
                    updateAuthorizedAppsUI()
                    ui.brief(getString(R.string.token_deleted))
                    appendLog(getString(R.string.token_deleted))
                }
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
        startBusy(BusyTask.BACKUP)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val tokens = tokenManager.getAllTokens()
                val json = gson.toJson(tokens)
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(json.toByteArray())
                }
                withContext(Dispatchers.Main) {
                    ui.brief(getString(R.string.tokens_backed_up))
                    appendLog(getString(R.string.log_backed_up, uri.toString()))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Backup failed", e)
                withContext(Dispatchers.Main) {
                    ui.brief(getString(R.string.backup_error))
                    appendLog(getString(R.string.log_backup_failed, e.message))
                }
            } finally {
                endBusy(BusyTask.BACKUP)
            }
        }
    }

    private fun performRestore(uri: Uri) {
        startBusy(BusyTask.RESTORE)
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
                        updateAuthorizedAppsUI()
                        ui.brief(getString(R.string.tokens_restored))
                        appendLog(getString(R.string.log_restored, uri.toString()))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Restore failed", e)
                withContext(Dispatchers.Main) {
                    ui.brief(getString(R.string.restore_error))
                    appendLog(getString(R.string.log_restore_failed, e.message))
                }
            } finally {
                endBusy(BusyTask.RESTORE)
            }
        }
    }
    
    private fun generateNewToken() {
        currentToken = tokenManager.generateToken()
        updateTokenUI()
        updateAuthorizedAppsUI()
        ui.brief(getString(R.string.token_generated))
        appendLog(getString(R.string.token_generated))
        sphere.ripple()
    }
    
    private fun updateTokenUI() {
        tvApiToken.text = currentToken ?: getString(R.string.no_token_generated)
        render()
    }

    private fun updateAuthorizedAppsUI() {
        val mappings = tokenManager.getTokenMappings()
        llAuthorizedApps.removeAllViews()
        tvNoAuthorizedApps.visibility = if (mappings.isEmpty()) View.VISIBLE else View.GONE
        mappings.forEach { (pkgName, token) ->
            val row = layoutInflater.inflate(R.layout.row_authorized_app, llAuthorizedApps, false)
            row.findViewById<TextView>(R.id.tv_app_package).text = pkgName
            row.findViewById<TextView>(R.id.tv_app_detail).text = getString(R.string.app_has_token)
            row.findViewById<MaterialButton>(R.id.btn_revoke_app).setOnClickListener {
                ui.confirm(getString(R.string.revoke_title, pkgName), getString(R.string.revoke_body), getString(R.string.revoke_access)) {
                    tokenManager.revokeToken(token)
                    if (currentToken == token) {
                        currentToken = null
                        updateTokenUI()
                    }
                    updateAuthorizedAppsUI()
                    appendLog(getString(R.string.log_revoked, pkgName))
                }
            }
            llAuthorizedApps.addView(row)
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
        // The audio test stays busy through the recording and the request; every exit below ends it.
        startBusy(BusyTask.TEST_AUDIO)
        appendLog(getString(R.string.log_recording))

        try {
            val file = File(cacheDir, "test_audio.wav")
            audioFile = file
            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)

            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                appendLog(getString(R.string.log_mic_missing))
                isRecording = false
                endBusy(BusyTask.TEST_AUDIO)
                return
            }

            val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize)

            recordingJob = CoroutineScope(Dispatchers.IO).launch {
                try {
                    val data = ByteArray(bufferSize)
                    FileOutputStream(file).use { output ->
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
                    }

                    // Add WAV header
                    addWavHeader(file)

                    withContext(Dispatchers.Main) {
                        stopAudioRecording()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Recording failed", e)
                    withContext(Dispatchers.Main) {
                        isRecording = false
                        appendLog(getString(R.string.log_recording_failed, e.message))
                        endBusy(BusyTask.TEST_AUDIO)
                    }
                } finally {
                    recorder.release()
                }
            }

        } catch (e: Exception) {
            isRecording = false
            appendLog(getString(R.string.log_recording_failed, e.message))
            endBusy(BusyTask.TEST_AUDIO)
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
        appendLog(getString(R.string.log_recording_done))
        runTestAudio()
    }

    private fun runTestAudio() {
        val file = audioFile ?: run {
            endBusy(BusyTask.TEST_AUDIO)
            return
        }
        val token = getOrGenerateToken()
        render()
        
        appendLog(getString(R.string.log_test_started, getString(R.string.test_audio)))
        CoroutineScope(Dispatchers.IO).launch {
             try {
                // Step 3: Encode audio
                val bytes = file.readBytes()
                val base64Audio = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                
                val messages = listOf(
                    ChatMessage("user", gson.toJsonTree(listOf(
                        mapOf("type" to "text", "text" to getString(R.string.test_prompt_audio)),
                        mapOf("type" to "audio_url", "audio_url" to mapOf("url" to "data:audio/wav;base64,$base64Audio"))
                    )))
                )

                val req = ChatCompletionRequest(model = "gemma-audio", messages = messages, stream = true)

                withContext(Dispatchers.Main) {
                    appendLog(getString(R.string.log_assistant))
                }

                performStreamingChatRequest(req, token) { chunk ->
                    runOnUiThread { appendLog(chunk, newLine = false) }
                }

                withContext(Dispatchers.Main) {
                    appendLog(getString(R.string.log_test_finished, getString(R.string.test_audio)))
                }
             } catch (e: Exception) {
                 withContext(Dispatchers.Main) {
                     appendLog(getString(R.string.log_test_failed, getString(R.string.test_audio), e.message))
                 }
             } finally {
                 file.delete()
                 endBusy(BusyTask.TEST_AUDIO)
             }
        }
    }

    private fun runTestVision(imageUri: Uri) {
        val token = getOrGenerateToken()
        startBusy(BusyTask.TEST_VISION)
        appendLog(getString(R.string.log_test_started, getString(R.string.test_vision)))

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Encode image
                val base64Image = getBase64EncodedImage(imageUri)
                if (base64Image == null) {
                    withContext(Dispatchers.Main) {
                         appendLog(getString(R.string.log_image_unreadable))
                    }
                    return@launch
                }

                val messages = listOf(
                    ChatMessage("user", gson.toJsonTree(listOf(
                        mapOf("type" to "text", "text" to getString(R.string.test_prompt_image)),
                        mapOf("type" to "image_url", "image_url" to mapOf("url" to "data:image/jpeg;base64,$base64Image"))
                    )))
                )

                val req = ChatCompletionRequest(model = "gemma-vision", messages = messages, stream = true)

                withContext(Dispatchers.Main) {
                    appendLog(getString(R.string.log_assistant))
                }

                performStreamingChatRequest(req, token) { chunk ->
                    runOnUiThread { appendLog(chunk, newLine = false) }
                }

                withContext(Dispatchers.Main) {
                    appendLog(getString(R.string.log_test_finished, getString(R.string.test_vision)))
                }
            } catch (e: Exception) {
                 withContext(Dispatchers.Main) {
                     appendLog(getString(R.string.log_test_failed, getString(R.string.test_vision), e.message))
                 }
            } finally {
                endBusy(BusyTask.TEST_VISION)
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
        appendLog(getString(R.string.log_copying_model))
        // A known size gives a progress bar with an amount; otherwise it moves without one. Set while it is hidden.
        progressModelCopy.isIndeterminate = true
        startBusy(BusyTask.MODEL_COPY)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val size = getFileSize(uri)
                if (size > 0) {
                    withContext(Dispatchers.Main) {
                        progressModelCopy.max = PROGRESS_MAX
                        progressModelCopy.setProgressCompat(0, false)
                    }
                }
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
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        var copied = 0L
                        var lastPost = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            val now = System.currentTimeMillis()
                            if (size > 0 && now - lastPost >= PROGRESS_INTERVAL_MS) {
                                lastPost = now
                                val progress = (copied * PROGRESS_MAX / size).toInt()
                                runOnUiThread { progressModelCopy.setProgressCompat(progress, true) }
                            }
                        }
                    }
                }

                selectedModelPath = destinationFile.absolutePath

                // Save path
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putString(KEY_MODEL_PATH, selectedModelPath)
                    .apply()

                withContext(Dispatchers.Main) {
                    tvModelPath.text = selectedModelPath
                    appendLog(getString(R.string.log_model_copied, selectedModelPath))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error copying file", e)
                withContext(Dispatchers.Main) {
                    appendLog(getString(R.string.log_copy_failed, e.message))
                }
            } finally {
                endBusy(BusyTask.MODEL_COPY)
            }
        }
    }

    /** The picked file's size in bytes, or -1 when the provider does not say. */
    private fun getFileSize(uri: Uri): Long {
        if (uri.scheme != "content") return -1
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.SIZE)
                if (index != -1 && !it.isNull(index)) return it.getLong(index)
            }
        }
        return -1
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

    private fun encoderRadioId(option: EncoderBackendOption, autoId: Int, cpuId: Int, gpuId: Int) = when (option) {
        EncoderBackendOption.AUTO -> autoId
        EncoderBackendOption.CPU -> cpuId
        EncoderBackendOption.GPU -> gpuId
    }

    private fun encoderOption(checkedId: Int, cpuId: Int, gpuId: Int) = when (checkedId) {
        cpuId -> EncoderBackendOption.CPU
        gpuId -> EncoderBackendOption.GPU
        else -> EncoderBackendOption.AUTO
    }

    /** Starts the service with the settings on screen; [contextTokens] is the checked context size (0 = model default). */
    private fun startInferenceService(contextTokens: Int) {
        val selectedBackend = backendName(rgBackend.checkedRadioButtonId)

        val contextSize = contextTokens
        val speculative = speculativeOption(rgSpeculative.checkedRadioButtonId)
        val vision = encoderOption(rgVisionBackend.checkedRadioButtonId, R.id.rb_vision_cpu, R.id.rb_vision_gpu)
        val audio = encoderOption(rgAudioBackend.checkedRadioButtonId, R.id.rb_audio_cpu, R.id.rb_audio_gpu)
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            putInt(KEY_CONTEXT_SIZE, contextSize)
            putString(KEY_SPECULATIVE, speculative.name)
            putString(KEY_VISION_BACKEND, vision.name)
            putString(KEY_AUDIO_BACKEND, audio.name)
        }

        val intent = Intent(this, InferenceService::class.java).apply {
            putExtra(InferenceService.EXTRA_MODEL_PATH, selectedModelPath)
            putExtra(InferenceService.EXTRA_BACKEND, selectedBackend)
            putExtra(InferenceService.EXTRA_MAX_NUM_TOKENS, contextSize)
            putExtra(InferenceService.EXTRA_SPECULATIVE_DECODING, speculative.name)
            putExtra(InferenceService.EXTRA_VISION_BACKEND, vision.name)
            putExtra(InferenceService.EXTRA_AUDIO_BACKEND, audio.name)
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


    private fun runTestInference() {
        val token = getOrGenerateToken()
        startBusy(BusyTask.TEST_INFERENCE)
        appendLog(getString(R.string.log_test_started, getString(R.string.test_inference)))

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prompt = getString(R.string.test_prompt_hello)
                val messages = listOf(ChatMessage("user", JsonPrimitive(prompt)))
                // Enable streaming
                val req = ChatCompletionRequest(model = "gemma", messages = messages, stream = true)

                withContext(Dispatchers.Main) {
                    appendLog(getString(R.string.log_user, prompt))
                    appendLog(getString(R.string.log_assistant))
                }

                performStreamingChatRequest(req, token) { chunk ->
                    runOnUiThread {
                        appendLog(chunk, newLine = false)
                    }
                }
                
                withContext(Dispatchers.Main) {
                    appendLog(getString(R.string.log_test_finished, getString(R.string.test_inference)))
                }
            } catch (e: Exception) {
                 withContext(Dispatchers.Main) {
                     appendLog(getString(R.string.log_test_failed, getString(R.string.test_inference), e.message))
                 }
            } finally {
                endBusy(BusyTask.TEST_INFERENCE)
            }
        }
    }

    private fun runTestMultiTurn() {
        val token = getOrGenerateToken()
        startBusy(BusyTask.TEST_MULTI_TURN)
        appendLog(getString(R.string.log_test_started, getString(R.string.test_multiturn)))

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Turn 1
                val firstPrompt = getString(R.string.test_prompt_first)
                val messages = mutableListOf(ChatMessage("user", JsonPrimitive(firstPrompt)))
                var req = ChatCompletionRequest(model = "gemma", messages = messages, stream = true)

                withContext(Dispatchers.Main) {
                    appendLog(getString(R.string.log_user, firstPrompt))
                    appendLog(getString(R.string.log_assistant))
                }
                
                var reply1 = ""
                performStreamingChatRequest(req, token) { chunk ->
                    reply1 += chunk
                    runOnUiThread { appendLog(chunk, newLine = false) }
                }
                messages.add(ChatMessage("assistant", JsonPrimitive(reply1)))
                
                // Turn 2
                delay(1000)
                // Ask about the first message explicitly: "What did I just ask?" is read by
                // small models (Gemma 4 E2B) as the current question.
                val turn2Prompt = getString(R.string.test_prompt_second)
                messages.add(ChatMessage("user", JsonPrimitive(turn2Prompt)))
                withContext(Dispatchers.Main) {
                    appendLog(getString(R.string.log_user, turn2Prompt))
                    appendLog(getString(R.string.log_assistant))
                }

                req = ChatCompletionRequest(model = "gemma", messages = messages, stream = true)
                var reply2 = ""
                performStreamingChatRequest(req, token) { chunk ->
                    reply2 += chunk
                    runOnUiThread { appendLog(chunk, newLine = false) }
                }
                
                withContext(Dispatchers.Main) {
                    appendLog(getString(R.string.log_test_finished, getString(R.string.test_multiturn)))
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) { appendLog(getString(R.string.log_test_failed, getString(R.string.test_multiturn), e.message)) }
            } finally {
                endBusy(BusyTask.TEST_MULTI_TURN)
            }
        }
    }

    private fun performChatRequest(req: ChatCompletionRequest, token: String): ChatCompletionResponse {
        val url = URL("$SERVER_ADDRESS/v1/chat/completions")
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
            } catch (e: Exception) { getString(R.string.log_error_unreadable) }
            throw Exception("HTTP $responseCode: $errorString")
        }
    }

    private fun performStreamingChatRequest(req: ChatCompletionRequest, token: String, onChunk: (String) -> Unit) {
        val url = URL("$SERVER_ADDRESS/v1/chat/completions")
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
            } catch (e: Exception) { getString(R.string.log_error_unreadable) }
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
        if (newLine && tvLogs.text.isNotEmpty()) tvLogs.append(System.lineSeparator())
        tvLogs.append(msg)
    }

    companion object {
        private const val TAG = "MainActivity"
        /** Where the app's own server listens; the diagnostics tests call it and the Server page shows it. */
        private const val SERVER_ADDRESS = "http://localhost:8080"
        private const val COPY_BUFFER_BYTES = 1 shl 16
        private const val PROGRESS_MAX = 1000
        private const val PROGRESS_INTERVAL_MS = 250L
        private const val PREFS_NAME = "litertlm_prefs"
        private const val KEY_MODEL_PATH = "model_path"
        private const val KEY_CONTEXT_SIZE = "context_size"
        private const val KEY_SPECULATIVE = "speculative_decoding"
        private const val KEY_VISION_BACKEND = "vision_backend"
        private const val KEY_AUDIO_BACKEND = "audio_backend"
        private const val KEY_BACKEND = "backend"
        private const val KEY_ANSWERED_NOTIFICATIONS = "explain_notifications_answered"
        private const val KEY_ANSWERED_BATTERY = "explain_battery_answered"
        private const val BACKEND_CPU = "CPU"
        private const val BACKEND_GPU = "GPU"
        private const val BACKEND_NPU = "NPU"
        private const val STATE_PAGE = "page"
        private const val STATE_FOLD_OPEN = "fold_open"
        /** Debug builds only: start with solid surfaces, to check the solid look on an API level that can blur. */
        const val EXTRA_FORCE_SOLID = "console_solid"
    }
}
