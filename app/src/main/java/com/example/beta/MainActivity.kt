package com.example.beta

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.beta.SwiggyMcpClient.SwiggyMcpResult
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var captureScreenButton: Button
    private lateinit var agentStatusText: TextView
    private lateinit var primaryNoteText: TextView
    private lateinit var textRecognitionButton: Button
    private lateinit var voiceOrderButton: Button
    private lateinit var feedbackMessageInput: EditText
    private lateinit var includeLogsCheckbox: CheckBox
    private lateinit var feedbackWorkedButton: Button
    private lateinit var feedbackIssueButton: Button
    private lateinit var setupAccessibilityStep: TextView
    private lateinit var setupOverlayStep: TextView
    private lateinit var setupScreenCaptureStep: TextView
    private lateinit var setupMicrophoneStep: TextView
    private lateinit var setupHeading: View
    private lateinit var setupPermissionsCard: View
    private lateinit var providerChoiceGroup: RadioGroup
    private lateinit var providerChoiceNote: TextView
    private lateinit var swiggyConnectionPanel: View
    private lateinit var swiggyConnectionStatus: TextView
    private lateinit var swiggyConnectionDetail: TextView
    private lateinit var swiggySelectedAddress: TextView
    private lateinit var swiggyChangeAddressAction: Button
    private lateinit var swiggyConnectionAction: Button
    private lateinit var swiggyExecutionModeAction: Button
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private val screenCaptureRequestCode = 100
    private var isCapturing = false // Track capture state

    // Declare the screenCaptureResult as a lateinit var
    private lateinit var screenCaptureResult: ActivityResultLauncher<Intent>
    private lateinit var voiceInputResult: ActivityResultLauncher<Intent>
    private lateinit var microphonePermissionResult: ActivityResultLauncher<String>
    private var textToSpeech: TextToSpeech? = null
    private var isBindingProviderChoice = false
    private var swiggyConnectionState = SwiggyMcpClient.ConnectionState.DISCONNECTED
    private var swiggyMcpRequestGeneration = 0L
    private var swiggyStatusRequestGeneration: Long? = null
    private var resumeSwiggyOrderAfterStatus = false
    private var pendingSwiggyInstruction: String? = null
    private var swiggyConnectPromptShowing = false
    private var swiggyConnectPrompt: AlertDialog? = null
    private var selectedSwiggyAddressLabel: String? = null
    private lateinit var swiggyOrderCoordinator: SwiggyVoiceOrderCoordinator

    /*// Activity result launcher for screen capture
    private var screenCaptureResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                Log.d("MainActivity", "Media projection successful, starting service")
                // Start the ScreenCaptureService
                val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    putExtra("resultCode", result.resultCode)
                    putExtra("resultData", result.data)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                isCapturing = true
            } else {
                Log.e("MainActivity", "Media projection failed")
                Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
                isCapturing = false
            }
        }*/

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI elements
        captureScreenButton = findViewById(R.id.captureScreenButton)
        agentStatusText = findViewById(R.id.text_agent_status)
        primaryNoteText = findViewById(R.id.mainPrimaryNote)
        textRecognitionButton = findViewById(R.id.textRecognitionButton)
        voiceOrderButton = findViewById(R.id.voiceOrderButton)
        feedbackMessageInput = findViewById(R.id.feedbackMessageInput)
        includeLogsCheckbox = findViewById(R.id.includeLogsCheckbox)
        feedbackWorkedButton = findViewById(R.id.feedbackWorkedButton)
        feedbackIssueButton = findViewById(R.id.feedbackIssueButton)
        setupAccessibilityStep = findViewById(R.id.setupAccessibilityStep)
        setupOverlayStep = findViewById(R.id.setupOverlayStep)
        setupScreenCaptureStep = findViewById(R.id.setupScreenCaptureStep)
        setupMicrophoneStep = findViewById(R.id.setupMicrophoneStep)
        setupHeading = findViewById(R.id.setupHeading)
        setupPermissionsCard = findViewById(R.id.setupPermissionsCard)
        providerChoiceGroup = findViewById(R.id.providerChoiceGroup)
        providerChoiceNote = findViewById(R.id.providerChoiceNote)
        swiggyConnectionPanel = findViewById(R.id.swiggyConnectionPanel)
        swiggyConnectionStatus = findViewById(R.id.swiggyConnectionStatus)
        swiggyConnectionDetail = findViewById(R.id.swiggyConnectionDetail)
        swiggySelectedAddress = findViewById(R.id.swiggySelectedAddress)
        swiggyChangeAddressAction = findViewById(R.id.swiggyChangeAddressAction)
        swiggyConnectionAction = findViewById(R.id.swiggyConnectionAction)
        swiggyExecutionModeAction = findViewById(R.id.swiggyExecutionModeAction)
        configureProviderChoice()

        // Get MediaProjectionManager
        mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.language = Locale("en", "IN")
            }
        }
        swiggyOrderCoordinator = SwiggyVoiceOrderCoordinator(
            activity = this,
            announce = ::announceSwiggy,
            onReconnectRequired = {
                updateSwiggyConnectionUi(SwiggyMcpClient.ConnectionState.RECONNECT_REQUIRED)
            },
            onAddressChanged = ::renderSwiggySelectedAddress,
        )
        SwiggyCartMutationGuard.register(this) { inFlight ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                updateSwiggyMutationControls(inFlight)
                if (!inFlight) {
                    SwiggyCartMutationGuard.consumeTerminalNotice()?.let(::announceSwiggy)
                }
            }
        }

        // Initialize ActivityResultLauncher
        screenCaptureResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                Log.d("MainActivity", "Media projection successful, starting service")

                // Keep getMetrics only as diagnostic evidence; the capture frame must use real display bounds.
                val displayMetrics = DisplayMetrics()
                windowManager.defaultDisplay.getMetrics(displayMetrics)

                // Also get real metrics for comparison (includes status bar and navigation bar)
                val realMetrics = DisplayMetrics()
                windowManager.defaultDisplay.getRealMetrics(realMetrics)

                val (captureWidth, captureHeight) = ScreenMetrics.getScreenDimensions(this)
                val captureDensity = resources.displayMetrics.densityDpi

                // Log dimension comparison for scaling hypothesis verification
                Log.i("MainActivity", "Display Metrics (getMetrics): ${displayMetrics.widthPixels}x${displayMetrics.heightPixels}")
                Log.i("MainActivity", "Real Metrics (getRealMetrics): ${realMetrics.widthPixels}x${realMetrics.heightPixels}")
                Log.i("BetaAgent", "CAPTURE_FRAME_REQUESTED: ${captureWidth}x${captureHeight} @ $captureDensity dpi")

                val widthDiff = captureWidth - displayMetrics.widthPixels
                val heightDiff = captureHeight - displayMetrics.heightPixels

                if (widthDiff != 0 || heightDiff != 0) {
                    Log.w("MainActivity", "DIMENSION MISMATCH: getMetrics differs from capture frame by ${widthDiff}px x ${heightDiff}px")
                    Log.w("MainActivity", "Using real/window metrics for MediaProjection to keep screenshots and taps in one frame.")
                } else {
                    Log.i("MainActivity", "Dimensions match - no scaling issues expected")
                }

                // Start the ScreenCaptureService with the metrics after receiving result from projection intent
                val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    putExtra("resultCode", result.resultCode)
                    putExtra("resultData", result.data)
                    putExtra("width", captureWidth)
                    putExtra("height", captureHeight)
                    putExtra("density", captureDensity)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }

                // OverlayInputService removed - not available in current version

                isCapturing = true
                updateSetupStatus(
                    statusRes = R.string.main_status_active,
                    noteRes = R.string.main_primary_note_ready,
                    actionRes = R.string.main_primary_action_ready
                )
                Handler(Looper.getMainLooper()).postDelayed({
                    refreshSetupChecklist()
                }, 1000)
            } else {
                Log.e("MainActivity", "Media projection failed")
                Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
                isCapturing = false
                updateSetupStatus(
                    statusRes = R.string.main_status_permission_needed,
                    noteRes = R.string.main_primary_note,
                    actionRes = R.string.main_primary_action
                )
                refreshSetupChecklist()
            }
        }

        voiceInputResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS).orEmpty()
                val instruction = matches.firstOrNull()?.trim().orEmpty()
                if (instruction.isNotBlank()) {
                    handleVoiceInstruction(instruction)
                } else {
                    Toast.makeText(this, R.string.voice_no_order_heard, Toast.LENGTH_SHORT).show()
                }
            }
        }

        microphonePermissionResult = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startVoiceRecognition()
            } else {
                Toast.makeText(this, R.string.voice_microphone_required, Toast.LENGTH_LONG).show()
            }
            refreshSetupChecklist()
        }

        // Set click listener for the capture screen button
        captureScreenButton.setOnClickListener {
            if (CommerceProviderRouter.currentSessionProvider() ==
                CommerceProviderRouter.CommerceProvider.SWIGGY_INSTAMART
            ) {
                if (SwiggyExecutionMode.usesMcpExperience()) {
                    if (swiggyConnectionState == SwiggyMcpClient.ConnectionState.READY) {
                        checkMicrophoneAndStartVoice()
                    } else {
                        startSwiggyConnection()
                    }
                } else {
                    checkPermissionsAndStartCapture()
                }
            } else {
                checkPermissionsAndStartCapture()
            }
        }

        // Set click listener for the text recognition button
        textRecognitionButton.setOnClickListener {
            val intent = Intent(this, TextRecognitionActivity::class.java)
            startActivity(intent)
        }

        voiceOrderButton.setOnClickListener {
            checkMicrophoneAndStartVoice()
        }

        swiggyConnectionAction.setOnClickListener {
            when (swiggyConnectionState) {
                SwiggyMcpClient.ConnectionState.READY -> confirmSwiggyDisconnect()
                SwiggyMcpClient.ConnectionState.DISCONNECTED,
                SwiggyMcpClient.ConnectionState.RECONNECT_REQUIRED -> startSwiggyConnection()
            }
        }

        swiggyChangeAddressAction.setOnClickListener {
            if (swiggyOrderCoordinator.clearRememberedAddress()) {
                announceSwiggy(getString(R.string.swiggy_address_change_next_order))
            } else {
                announceSwiggy(getString(R.string.swiggy_cart_update_in_progress))
            }
        }

        swiggyExecutionModeAction.setOnClickListener {
            if (SwiggyExecutionMode.usesMcpExperience()) {
                selectSwiggyExecutionMode(SwiggyExecutionMode.Mode.SCREEN_ASSISTED)
            } else {
                selectSwiggyExecutionMode(SwiggyExecutionMode.Mode.MCP)
            }
        }

        feedbackWorkedButton.setOnClickListener {
            sendFeedback("worked", "order_flow")
        }

        feedbackIssueButton.setOnClickListener {
            sendFeedback("did_not_work", "order_flow")
        }

        // AutomatedActionTestActivity removed - not available in current version

        (application as MyApplication).registerActivity(this)
        refreshSetupChecklist()
        handleStartCaptureIntent(intent)
        handleSwiggyOAuthIntent(intent)
        handleSwiggyOrderIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        syncProviderChoiceFromSession()
        refreshSetupChecklist()
        if (isSwiggyMcpSelected()) {
            refreshSwiggyConnectionStatus(resumePendingOrder = true)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleStartCaptureIntent(intent)
        handleSwiggyOAuthIntent(intent)
        handleSwiggyOrderIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelPendingSwiggyMcpWork("activity_destroyed")
        SwiggyCartMutationGuard.unregister(this)
        textToSpeech?.shutdown()
        textToSpeech = null
        (application as MyApplication).unregisterActivity(this)
    }

    private fun speak(message: String) {
        textToSpeech?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "beta_voice_prompt")
    }

    private fun handleStartCaptureIntent(sourceIntent: Intent?) {
        if (sourceIntent?.getBooleanExtra(EXTRA_START_CAPTURE_ON_OPEN, false) != true) {
            return
        }

        val reason = sourceIntent.getStringExtra(EXTRA_CAPTURE_RESTART_REASON).orEmpty()
        sourceIntent.removeExtra(EXTRA_START_CAPTURE_ON_OPEN)
        sourceIntent.removeExtra(EXTRA_CAPTURE_RESTART_REASON)
        Log.i("BetaAgent", "CAPTURE_RESTART_REQUESTED_FROM_INTENT: reason=$reason")
        Toast.makeText(this, "Restarting screen capture", Toast.LENGTH_SHORT).show()
        Handler(Looper.getMainLooper()).post {
            checkPermissionsAndStartCapture()
        }
    }

    private fun checkMicrophoneAndStartVoice() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startVoiceRecognition()
        } else {
            microphonePermissionResult.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startVoiceRecognition() {
        speak(getString(R.string.voice_listening))
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_listening))
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        runCatching {
            voiceInputResult.launch(intent)
        }.onFailure {
            Toast.makeText(this, "Voice recognition is not available on this device", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleVoiceInstruction(instruction: String) {
        CommerceProviderRouter.selectProviderFromInstruction(instruction)
        syncProviderChoiceFromSession()

        if (CommerceProviderRouter.isOpenCommerceAppInstruction(instruction)) {
            val launchResult = CommerceAppLauncher.launchPreferred(this, instruction)
            speak(launchResult.message)
            Toast.makeText(
                this,
                launchResult.message,
                if (launchResult.launched) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
            ).show()
            return
        }

        val selectedProvider = CommerceProviderRouter.currentSessionProvider()
        if (
            selectedProvider == CommerceProviderRouter.CommerceProvider.SWIGGY_INSTAMART &&
            SwiggyExecutionMode.usesMcpExperience()
        ) {
            handleSwiggyVoiceInstruction(instruction)
            return
        }
        if (selectedProvider == CommerceProviderRouter.CommerceProvider.SWIGGY_INSTAMART) {
            Log.i("BetaAgent", "SWIGGY_SCREEN_ASSISTED_VOICE_INSTRUCTION_RECEIVED")
        }

        val service = (application as MyApplication).getScreenCaptureService()
        if (service == null) {
            speak(getString(R.string.voice_start_capture_first))
            Toast.makeText(this, getString(R.string.voice_start_capture_first), Toast.LENGTH_LONG).show()
            return
        }
        Log.i("BetaAgent", "VOICE_INSTRUCTION_RECOGNIZED: $instruction")
        val launchResult = CommerceAppLauncher.launchPreferred(this, instruction)
        if (!launchResult.launched) {
            speak(launchResult.message)
            Toast.makeText(this, launchResult.message, Toast.LENGTH_LONG).show()
            return
        }
        speak("Opening ${launchResult.appName}. I heard $instruction")
        Toast.makeText(this, launchResult.message, Toast.LENGTH_SHORT).show()
        Handler(Looper.getMainLooper()).postDelayed({
            service.submitAutomationInstruction(instruction)
        }, CommerceAppLauncher.LAUNCH_SETTLE_DELAY_MS)
    }

    private fun configureProviderChoice() {
        syncProviderChoiceFromSession()
        providerChoiceGroup.setOnCheckedChangeListener { _, checkedId ->
            if (isBindingProviderChoice) return@setOnCheckedChangeListener
            if (::swiggyOrderCoordinator.isInitialized && swiggyOrderCoordinator.isMutationInFlight()) {
                syncProviderChoiceFromSession()
                announceSwiggy(getString(R.string.swiggy_cart_update_in_progress))
                return@setOnCheckedChangeListener
            }
            val provider = when (checkedId) {
                R.id.providerSwiggy -> CommerceProviderRouter.CommerceProvider.SWIGGY_INSTAMART
                R.id.providerBlinkit -> CommerceProviderRouter.CommerceProvider.BLINKIT
                R.id.providerZepto -> CommerceProviderRouter.CommerceProvider.ZEPTO
                else -> return@setOnCheckedChangeListener
            }
            CommerceProviderRouter.selectProviderFromUi(provider)
            if (provider != CommerceProviderRouter.CommerceProvider.SWIGGY_INSTAMART) {
                cancelPendingSwiggyMcpWork("provider_changed_to_${provider.name}")
            }
            val message = getString(R.string.provider_selected_for_session, provider.appName)
            providerChoiceNote.text = providerChoiceNoteFor(provider)
            providerChoiceGroup.announceForAccessibility(message)
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            Log.i("BetaAgent", "PROVIDER_SESSION_SELECTED source=UI provider=${provider.name}")
            updateSwiggyPanelVisibility()
            configurePrimaryExperience()
            if (
                provider == CommerceProviderRouter.CommerceProvider.SWIGGY_INSTAMART &&
                SwiggyExecutionMode.usesMcpExperience()
            ) {
                refreshSwiggyConnectionStatus()
            }
        }
    }

    private fun syncProviderChoiceFromSession() {
        if (!::providerChoiceGroup.isInitialized) return
        val provider = CommerceProviderRouter.currentSessionProvider()
        val checkedId = when (provider) {
            CommerceProviderRouter.CommerceProvider.SWIGGY_INSTAMART -> R.id.providerSwiggy
            CommerceProviderRouter.CommerceProvider.BLINKIT -> R.id.providerBlinkit
            CommerceProviderRouter.CommerceProvider.ZEPTO -> R.id.providerZepto
        }
        isBindingProviderChoice = true
        providerChoiceGroup.check(checkedId)
        isBindingProviderChoice = false
        providerChoiceNote.text = providerChoiceNoteFor(provider)
        updateSwiggyPanelVisibility()
        configurePrimaryExperience()
    }

    private fun providerChoiceNoteFor(provider: CommerceProviderRouter.CommerceProvider): String {
        return when {
            provider != CommerceProviderRouter.CommerceProvider.SWIGGY_INSTAMART ->
                getString(R.string.provider_choice_current, provider.appName)
            SwiggyExecutionMode.usesMcpExperience() ->
                getString(R.string.provider_choice_swiggy_mcp)
            else -> getString(R.string.provider_choice_swiggy_screen_assisted)
        }
    }

    private fun updateSwiggyPanelVisibility() {
        if (!::swiggyConnectionPanel.isInitialized) return
        val swiggySelected = CommerceProviderRouter.currentSessionProvider() ==
            CommerceProviderRouter.CommerceProvider.SWIGGY_INSTAMART
        swiggyConnectionPanel.visibility = if (swiggySelected) View.VISIBLE else View.GONE
        if (swiggySelected) renderSwiggyConnectionPanel()
    }

    private fun configurePrimaryExperience() {
        if (!::setupHeading.isInitialized) return
        val usesSwiggyMcp = isSwiggyMcpSelected()
        setupHeading.visibility = if (usesSwiggyMcp) View.GONE else View.VISIBLE
        setupPermissionsCard.visibility = if (usesSwiggyMcp) View.GONE else View.VISIBLE
        if (!usesSwiggyMcp) {
            refreshSetupChecklist()
            return
        }

        when (swiggyConnectionState) {
            SwiggyMcpClient.ConnectionState.READY -> updateSetupStatus(
                statusRes = R.string.swiggy_connection_ready,
                noteRes = R.string.swiggy_primary_note_ready,
                actionRes = R.string.swiggy_primary_action_ready,
            )
            SwiggyMcpClient.ConnectionState.RECONNECT_REQUIRED -> updateSetupStatus(
                statusRes = R.string.swiggy_connection_reconnect,
                noteRes = R.string.swiggy_primary_note_disconnected,
                actionRes = R.string.swiggy_connection_reconnect_action,
            )
            SwiggyMcpClient.ConnectionState.DISCONNECTED -> updateSetupStatus(
                statusRes = R.string.swiggy_connection_status,
                noteRes = R.string.swiggy_primary_note_disconnected,
                actionRes = R.string.swiggy_connection_action,
            )
        }
    }

    private fun renderSwiggyConnectionPanel(detailOverride: String? = null) {
        if (!::swiggyConnectionPanel.isInitialized) return
        if (!SwiggyExecutionMode.usesMcpExperience()) {
            swiggyConnectionStatus.setText(R.string.swiggy_screen_assisted_status)
            swiggyConnectionDetail.setText(R.string.swiggy_screen_assisted_detail)
            swiggyConnectionAction.visibility = View.GONE
            swiggySelectedAddress.visibility = View.GONE
            swiggyChangeAddressAction.visibility = View.GONE
            swiggyExecutionModeAction.apply {
                visibility = View.VISIBLE
                isEnabled = !isSwiggyMutationInFlight()
                setText(R.string.swiggy_use_mcp)
                contentDescription = getString(R.string.swiggy_use_mcp)
            }
            return
        }

        swiggyConnectionAction.visibility = View.VISIBLE
        renderSwiggySelectedAddress(selectedSwiggyAddressLabel)
        swiggyExecutionModeAction.apply {
            visibility = View.VISIBLE
            isEnabled = !isSwiggyMutationInFlight()
            setText(R.string.swiggy_use_screen_assisted)
            contentDescription = getString(R.string.swiggy_use_screen_assisted)
        }
        when (swiggyConnectionState) {
            SwiggyMcpClient.ConnectionState.READY -> {
                swiggyConnectionStatus.setText(R.string.swiggy_connection_ready)
                swiggyConnectionDetail.text = detailOverride
                    ?: getString(R.string.swiggy_connection_ready_detail)
                swiggyConnectionAction.setText(R.string.swiggy_connection_disconnect)
            }
            SwiggyMcpClient.ConnectionState.RECONNECT_REQUIRED -> {
                swiggyConnectionStatus.setText(R.string.swiggy_connection_reconnect)
                swiggyConnectionDetail.text = detailOverride
                    ?: getString(R.string.swiggy_connection_reconnect_detail)
                swiggyConnectionAction.setText(R.string.swiggy_connection_reconnect_action)
            }
            SwiggyMcpClient.ConnectionState.DISCONNECTED -> {
                swiggyConnectionStatus.setText(R.string.swiggy_connection_status)
                swiggyConnectionDetail.text = detailOverride
                    ?: getString(R.string.swiggy_connection_detail)
                swiggyConnectionAction.setText(R.string.swiggy_connection_action)
            }
        }
        swiggyConnectionAction.isEnabled = !isSwiggyMutationInFlight()
        swiggyConnectionAction.contentDescription = swiggyConnectionAction.text
    }

    private fun handleSwiggyVoiceInstruction(instruction: String) {
        if (SwiggyCartMutationGuard.isInFlight()) {
            announceSwiggy(getString(R.string.swiggy_cart_update_in_progress))
            return
        }
        pendingSwiggyInstruction = instruction
        Log.i("BetaAgent", "SWIGGY_MCP_VOICE_INSTRUCTION_RECEIVED")
        refreshSwiggyConnectionStatus(resumePendingOrder = true)
    }

    private fun refreshSwiggyConnectionStatus(resumePendingOrder: Boolean = false) {
        if (!isSwiggyMcpSelected()) return
        if (!::swiggyConnectionStatus.isInitialized) return
        if (resumePendingOrder) resumeSwiggyOrderAfterStatus = true
        if (swiggyStatusRequestGeneration != null) return
        val requestGeneration = swiggyMcpRequestGeneration
        swiggyStatusRequestGeneration = requestGeneration
        Log.i(
            "BetaAgent",
            "SWIGGY_MCP_STATUS_REQUEST_STARTED generation=$requestGeneration resumePending=$resumePendingOrder",
        )
        swiggyConnectionStatus.setText(R.string.swiggy_connection_checking)
        swiggyConnectionAction.isEnabled = false
        SwiggyMcpClient.fetchStatus(this) { result ->
            runOnUiThread {
                if (swiggyStatusRequestGeneration == requestGeneration) {
                    swiggyStatusRequestGeneration = null
                }
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (requestGeneration != swiggyMcpRequestGeneration || !isSwiggyMcpSelected()) {
                    Log.i("BetaAgent", "SWIGGY_MCP_STATUS_IGNORED_STALE")
                    return@runOnUiThread
                }
                val shouldResumePendingOrder = resumeSwiggyOrderAfterStatus
                resumeSwiggyOrderAfterStatus = false
                when (result) {
                    is SwiggyMcpResult.Success -> {
                        Log.i(
                            "BetaAgent",
                            "SWIGGY_MCP_STATUS_REQUEST_SUCCEEDED state=${result.value.state} resumePending=$shouldResumePendingOrder",
                        )
                        updateSwiggyConnectionUi(result.value.state)
                        if (shouldResumePendingOrder && result.value.state == SwiggyMcpClient.ConnectionState.READY) {
                            startPendingSwiggyOrder()
                        } else if (shouldResumePendingOrder) {
                            promptToConnectSwiggy()
                        }
                    }
                    is SwiggyMcpResult.Failure -> {
                        Log.w(
                            "BetaAgent",
                            "SWIGGY_MCP_STATUS_REQUEST_FAILED httpCode=${result.httpCode} reconnect=${result.reconnectRequired} resumePending=$shouldResumePendingOrder",
                        )
                        val state = if (result.reconnectRequired) {
                            SwiggyMcpClient.ConnectionState.RECONNECT_REQUIRED
                        } else {
                            SwiggyMcpClient.ConnectionState.DISCONNECTED
                        }
                        updateSwiggyConnectionUi(state, result.userMessage)
                        if (shouldResumePendingOrder) promptToConnectSwiggy()
                    }
                }
            }
        }
    }

    private fun updateSwiggyConnectionUi(
        state: SwiggyMcpClient.ConnectionState,
        detailOverride: String? = null,
    ) {
        if (state != SwiggyMcpClient.ConnectionState.READY && ::swiggyOrderCoordinator.isInitialized) {
            swiggyOrderCoordinator.clearRememberedAddress()
        }
        swiggyConnectionState = state
        renderSwiggyConnectionPanel(detailOverride)
        swiggyConnectionAction.isEnabled = !isSwiggyMutationInFlight()
        swiggyConnectionStatus.announceForAccessibility(swiggyConnectionStatus.text)
        configurePrimaryExperience()
    }

    private fun startSwiggyConnection() {
        if (!isSwiggyMcpSelected()) return
        val requestGeneration = swiggyMcpRequestGeneration
        swiggyConnectionAction.isEnabled = false
        swiggyConnectionStatus.setText(R.string.swiggy_connection_connecting)
        swiggyConnectionDetail.setText(R.string.swiggy_connection_browser_detail)
        SwiggyMcpClient.connect(this) { result ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (requestGeneration != swiggyMcpRequestGeneration || !isSwiggyMcpSelected()) {
                    Log.i("BetaAgent", "SWIGGY_MCP_CONNECT_IGNORED_STALE")
                    return@runOnUiThread
                }
                when (result) {
                    is SwiggyMcpResult.Success -> {
                        val authUrl = result.value.authorizationUrl
                        if (authUrl.isNullOrBlank() || !openTrustedSwiggyAuthorization(authUrl)) {
                            updateSwiggyConnectionUi(
                                SwiggyMcpClient.ConnectionState.DISCONNECTED,
                                getString(R.string.swiggy_connection_invalid_link),
                            )
                        }
                    }
                    is SwiggyMcpResult.Failure -> {
                        updateSwiggyConnectionUi(
                            if (result.reconnectRequired) SwiggyMcpClient.ConnectionState.RECONNECT_REQUIRED
                            else SwiggyMcpClient.ConnectionState.DISCONNECTED,
                            result.userMessage,
                        )
                    }
                }
            }
        }
    }

    private fun openTrustedSwiggyAuthorization(url: String): Boolean {
        return runCatching {
            val uri = Uri.parse(url)
            val trustedHost = uri.host == "mcp.swiggy.com" || uri.host == "mcp-staging.swiggy.com"
            if (uri.scheme != "https" || !trustedHost || uri.path != "/auth/authorize") return false
            startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        }.getOrDefault(false)
    }

    private fun handleSwiggyOAuthIntent(sourceIntent: Intent?) {
        val data = sourceIntent?.data ?: return
        if (data.scheme != "beta" || data.host != "swiggy" || data.path != "/oauth") return
        sourceIntent.data = null
        if (!isSwiggyMcpSelected()) {
            Log.i("BetaAgent", "SWIGGY_MCP_CALLBACK_IGNORED_SCREEN_ASSISTED_MODE")
            return
        }
        if (data.getQueryParameter("status") == "connected") {
            swiggyOrderCoordinator.clearRememberedAddress()
            swiggyConnectionStatus.setText(R.string.swiggy_connection_checking)
            swiggyConnectionDetail.setText(R.string.swiggy_connection_finishing)
            refreshSwiggyConnectionStatus(resumePendingOrder = true)
        } else {
            updateSwiggyConnectionUi(
                SwiggyMcpClient.ConnectionState.RECONNECT_REQUIRED,
                getString(R.string.swiggy_connection_failed),
            )
            announceSwiggy(getString(R.string.swiggy_connection_failed))
        }
    }

    private fun handleSwiggyOrderIntent(sourceIntent: Intent?) {
        val handoffToken = sourceIntent?.getStringExtra(SwiggyOrderHandoff.EXTRA_TOKEN)
        if (handoffToken.isNullOrBlank()) return
        sourceIntent.removeExtra(SwiggyOrderHandoff.EXTRA_TOKEN)
        val instruction = SwiggyOrderHandoff.consume(handoffToken)?.trim().orEmpty()
        if (instruction.isBlank()) {
            Log.w("BetaAgent", "SWIGGY_ORDER_HANDOFF_REJECTED")
            return
        }
        CommerceProviderRouter.selectProviderFromUi(CommerceProviderRouter.CommerceProvider.SWIGGY_INSTAMART)
        syncProviderChoiceFromSession()
        Handler(Looper.getMainLooper()).post {
            if (SwiggyExecutionMode.usesMcpExperience()) {
                handleSwiggyVoiceInstruction(instruction)
            } else {
                Log.i("BetaAgent", "STALE_SWIGGY_MCP_INTENT_ROUTED_TO_SCREEN_ASSISTED")
                handleVoiceInstruction(instruction)
            }
        }
    }

    private fun promptToConnectSwiggy() {
        if (!isSwiggyMcpSelected()) return
        if (pendingSwiggyInstruction.isNullOrBlank() || swiggyConnectPromptShowing) return
        swiggyConnectPromptShowing = true
        val reconnect = swiggyConnectionState == SwiggyMcpClient.ConnectionState.RECONNECT_REQUIRED
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (reconnect) R.string.swiggy_connection_reconnect else R.string.swiggy_connect_dialog_title)
            .setMessage(R.string.swiggy_connect_dialog_message)
            .setPositiveButton(if (reconnect) R.string.swiggy_connection_reconnect_action else R.string.swiggy_connection_action) { _, _ ->
                swiggyConnectPromptShowing = false
                startSwiggyConnection()
            }
            .setNegativeButton(R.string.automation_disclosure_cancel) { _, _ ->
                swiggyConnectPromptShowing = false
                pendingSwiggyInstruction = null
            }
            .setOnCancelListener {
                swiggyConnectPromptShowing = false
                pendingSwiggyInstruction = null
            }
            .create()
        swiggyConnectPrompt = dialog
        dialog.setOnDismissListener {
            if (swiggyConnectPrompt === dialog) swiggyConnectPrompt = null
            swiggyConnectPromptShowing = false
        }
        dialog.show()
    }

    private fun startPendingSwiggyOrder() {
        if (!isSwiggyMcpSelected()) return
        val instruction = pendingSwiggyInstruction?.takeIf { it.isNotBlank() } ?: return
        pendingSwiggyInstruction = null
        swiggyOrderCoordinator.start(instruction)
    }

    private fun confirmSwiggyDisconnect() {
        AlertDialog.Builder(this)
            .setTitle(R.string.swiggy_disconnect_dialog_title)
            .setMessage(R.string.swiggy_disconnect_dialog_message)
            .setPositiveButton(R.string.swiggy_connection_disconnect) { _, _ ->
                if (!isSwiggyMcpSelected()) return@setPositiveButton
                val requestGeneration = swiggyMcpRequestGeneration
                swiggyConnectionAction.isEnabled = false
                SwiggyMcpClient.disconnect(this) { result ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (requestGeneration != swiggyMcpRequestGeneration || !isSwiggyMcpSelected()) {
                            Log.i("BetaAgent", "SWIGGY_MCP_DISCONNECT_IGNORED_STALE")
                            return@runOnUiThread
                        }
                        when (result) {
                            is SwiggyMcpResult.Success -> updateSwiggyConnectionUi(SwiggyMcpClient.ConnectionState.DISCONNECTED)
                            is SwiggyMcpResult.Failure -> updateSwiggyConnectionUi(
                                if (result.reconnectRequired) SwiggyMcpClient.ConnectionState.RECONNECT_REQUIRED
                                else SwiggyMcpClient.ConnectionState.DISCONNECTED,
                                result.userMessage,
                            )
                        }
                    }
                }
            }
            .setNegativeButton(R.string.automation_disclosure_cancel, null)
            .show()
    }

    private fun selectSwiggyExecutionMode(mode: SwiggyExecutionMode.Mode) {
        if (SwiggyExecutionMode.current() == mode) return
        if (isSwiggyMutationInFlight()) {
            announceSwiggy(getString(R.string.swiggy_cart_update_in_progress))
            return
        }
        cancelPendingSwiggyMcpWork("execution_mode_changed_to_${mode.name}")
        when (mode) {
            SwiggyExecutionMode.Mode.MCP -> SwiggyExecutionMode.useMcp()
            SwiggyExecutionMode.Mode.SCREEN_ASSISTED -> SwiggyExecutionMode.useScreenAssisted()
        }
        Log.i("BetaAgent", "SWIGGY_EXECUTION_MODE_SELECTED mode=${mode.name}")
        providerChoiceNote.text = providerChoiceNoteFor(
            CommerceProviderRouter.CommerceProvider.SWIGGY_INSTAMART
        )
        updateSwiggyPanelVisibility()
        configurePrimaryExperience()

        val messageRes = if (mode == SwiggyExecutionMode.Mode.MCP) {
            R.string.swiggy_mode_mcp_selected
        } else {
            R.string.swiggy_mode_screen_assisted_selected
        }
        val message = getString(messageRes)
        swiggyConnectionStatus.announceForAccessibility(message)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        if (mode == SwiggyExecutionMode.Mode.MCP) {
            refreshSwiggyConnectionStatus()
        }
    }

    private fun cancelPendingSwiggyMcpWork(reason: String) {
        if (isSwiggyMutationInFlight()) {
            Log.w("BetaAgent", "SWIGGY_MCP_CANCEL_DEFERRED_MUTATION_IN_FLIGHT reason=$reason")
            return
        }
        swiggyMcpRequestGeneration += 1
        swiggyStatusRequestGeneration = null
        resumeSwiggyOrderAfterStatus = false
        pendingSwiggyInstruction = null
        swiggyConnectPromptShowing = false
        swiggyConnectPrompt?.dismiss()
        swiggyConnectPrompt = null
        if (::swiggyOrderCoordinator.isInitialized) {
            swiggyOrderCoordinator.cancel()
            swiggyOrderCoordinator.clearRememberedAddress()
        }
        Log.i("BetaAgent", "SWIGGY_MCP_PENDING_WORK_CANCELLED reason=$reason")
    }

    private fun updateSwiggyMutationControls(inFlight: Boolean) {
        if (!::providerChoiceGroup.isInitialized) return
        for (index in 0 until providerChoiceGroup.childCount) {
            providerChoiceGroup.getChildAt(index).isEnabled = !inFlight
        }
        if (::swiggyExecutionModeAction.isInitialized) {
            swiggyExecutionModeAction.isEnabled = !inFlight
        }
        if (::swiggyConnectionAction.isInitialized) {
            swiggyConnectionAction.isEnabled = !inFlight
        }
        if (::swiggyChangeAddressAction.isInitialized) {
            swiggyChangeAddressAction.isEnabled = !inFlight
        }
    }

    private fun isSwiggyMutationInFlight(): Boolean {
        return SwiggyCartMutationGuard.isInFlight() ||
            (::swiggyOrderCoordinator.isInitialized && swiggyOrderCoordinator.isMutationInFlight())
    }

    private fun isSwiggyMcpSelected(): Boolean {
        return CommerceProviderRouter.currentSessionProvider() ==
            CommerceProviderRouter.CommerceProvider.SWIGGY_INSTAMART &&
            SwiggyExecutionMode.usesMcpExperience()
    }

    private fun announceSwiggy(message: String) {
        if (::swiggyConnectionDetail.isInitialized) swiggyConnectionDetail.text = message
        speak(message)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun renderSwiggySelectedAddress(label: String?) {
        selectedSwiggyAddressLabel = label?.trim()?.takeIf { it.isNotBlank() }
        if (!::swiggySelectedAddress.isInitialized || !::swiggyChangeAddressAction.isInitialized) return
        val visible = selectedSwiggyAddressLabel != null &&
            SwiggyExecutionMode.usesMcpExperience() &&
            swiggyConnectionState == SwiggyMcpClient.ConnectionState.READY
        swiggySelectedAddress.visibility = if (visible) View.VISIBLE else View.GONE
        swiggyChangeAddressAction.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            swiggySelectedAddress.text = getString(
                R.string.swiggy_selected_address,
                selectedSwiggyAddressLabel,
            )
            swiggySelectedAddress.contentDescription = swiggySelectedAddress.text
        }
    }

    private fun checkPermissionsAndStartCapture() {
        updateSetupStatus(
            statusRes = R.string.main_status_starting,
            noteRes = R.string.main_primary_note
        )
        /*Log.d("MainActivity", "checkPermissionsAndStartCapture: Checking storage permission")
        // Check for storage permissions, request if not granted.
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                STORAGE_PERMISSION_CODE
            )
        } else {
            Log.d("MainActivity", "Storage permission granted, checking overlay permission")
            checkOverlayPermissionAndStartCapture()
        }*/
        if (BuildConfig.REQUIRE_AUTOMATION_DISCLOSURE && !automationDisclosureAccepted()) {
            showAutomationDisclosure()
        } else if (!isBetaAccessibilityEnabled()) {
            refreshSetupChecklist()
            showAccessibilitySetupHelp()
        } else {
            checkOverlayPermissionAndStartCapture()
        }
    }

    private fun isBetaAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, MyAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun showAccessibilitySetupHelp() {
        AlertDialog.Builder(this)
            .setTitle(R.string.accessibility_setup_title)
            .setMessage(R.string.accessibility_setup_message)
            .setPositiveButton(R.string.accessibility_setup_open_settings) { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton(R.string.automation_disclosure_cancel) { _, _ ->
                refreshSetupChecklist()
            }
            .show()
    }

    private fun automationDisclosureAccepted(): Boolean {
        return getSharedPreferences("beta_release_prefs", MODE_PRIVATE)
            .getBoolean("automation_disclosure_accepted", false)
    }

    private fun markAutomationDisclosureAccepted() {
        getSharedPreferences("beta_release_prefs", MODE_PRIVATE)
            .edit()
            .putBoolean("automation_disclosure_accepted", true)
            .apply()
    }

    private fun showAutomationDisclosure() {
        AlertDialog.Builder(this)
            .setTitle(R.string.automation_disclosure_title)
            .setMessage(R.string.automation_disclosure_message)
            .setPositiveButton(R.string.automation_disclosure_accept) { _, _ ->
                markAutomationDisclosureAccepted()
                checkPermissionsAndStartCapture()
            }
            .setNegativeButton(R.string.automation_disclosure_cancel) { _, _ ->
                refreshSetupChecklist()
            }
            .show()
    }

    private fun sendFeedback(rating: String, category: String) {
        val message = feedbackMessageInput.text?.toString().orEmpty()
        val includeLogs = includeLogsCheckbox.isChecked
        feedbackWorkedButton.isEnabled = false
        feedbackIssueButton.isEnabled = false
        FeedbackClient.submit(
            context = this,
            rating = rating,
            category = category,
            message = message,
            includeLogs = includeLogs
        ) { success, detail ->
            runOnUiThread {
                feedbackWorkedButton.isEnabled = true
                feedbackIssueButton.isEnabled = true
                if (success) {
                    feedbackMessageInput.setText("")
                    includeLogsCheckbox.isChecked = false
                    Toast.makeText(this, "Feedback sent", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Feedback failed: $detail", Toast.LENGTH_LONG).show()
                }
            }
        }

    }

    private fun checkOverlayPermissionAndStartCapture() {
        Log.d("MainActivity", "checkOverlayPermissionAndStartCapture: Checking overlay permission")
        // Check for overlay permission
        if (!Settings.canDrawOverlays(this)) {
            refreshSetupChecklist()
            updateSetupStatus(
                statusRes = R.string.main_status_permission_needed,
                noteRes = R.string.setup_overlay_body
            )
            // Request overlay permission
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            Toast.makeText(
                this,
                "Please grant overlay permission to use screen capture",
                Toast.LENGTH_LONG
            ).show()
        } else {
            Log.d("MainActivity", "Overlay permission granted, starting media projection")
            startMediaProjection()
        }
    }

    private fun startMediaProjection() {
        Log.d("MainActivity", "startMediaProjection: Starting media projection")
        updateSetupStatus(
            statusRes = R.string.main_status_capture_prompt,
            noteRes = R.string.main_primary_note_capture_prompt
        )
        val projectionIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Log.i("BetaAgent", "MEDIA_PROJECTION_DEFAULT_DISPLAY_REQUEST")
            mediaProjectionManager.createScreenCaptureIntent(
                MediaProjectionConfig.createConfigForDefaultDisplay()
            )
        } else {
            mediaProjectionManager.createScreenCaptureIntent()
        }
        screenCaptureResult.launch(projectionIntent)  // Only responsible for starting projection

        /*// Get display metrics here to pass it along to the service
        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(displayMetrics)

        // Start the ScreenCaptureService with the metrics after receiving result from projection intent
        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra("width", displayMetrics.widthPixels)
            putExtra("height", displayMetrics.heightPixels)
            putExtra("density", displayMetrics.densityDpi)
        }

        // Once you receive the result, start the service from the result handler:
        screenCaptureResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                Log.d("MainActivity", "Media projection successful, starting service")
                startForegroundService(serviceIntent)
            } else {
                Log.e("MainActivity", "Media projection failed")
                Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
            }
        }*/
    }

    private fun refreshSetupChecklist() {
        if (!::setupAccessibilityStep.isInitialized) return

        if (
            CommerceProviderRouter.currentSessionProvider() ==
                CommerceProviderRouter.CommerceProvider.SWIGGY_INSTAMART &&
            SwiggyExecutionMode.usesMcpExperience()
        ) {
            configurePrimaryExperience()
            return
        }

        val accessibilityReady = isBetaAccessibilityEnabled()
        val overlayReady = Settings.canDrawOverlays(this)
        val screenCaptureService = (application as? MyApplication)?.getScreenCaptureService()
        val captureReady = screenCaptureService?.canCapture() == true
        isCapturing = captureReady
        val microphoneReady = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val currentStep = when {
            !accessibilityReady -> 1
            !overlayReady -> 2
            !captureReady -> 3
            !microphoneReady -> 4
            else -> 0
        }

        updateStepMarker(setupAccessibilityStep, "1", accessibilityReady, currentStep == 1)
        updateStepMarker(setupOverlayStep, "2", overlayReady, currentStep == 2)
        updateStepMarker(setupScreenCaptureStep, "3", captureReady, currentStep == 3)
        updateStepMarker(setupMicrophoneStep, "4", microphoneReady, currentStep == 4)

        if (BackendProcessing.isSequenceActive()) {
            return
        }

        when {
            !accessibilityReady -> updateSetupStatus(
                statusRes = R.string.main_status_permission_needed,
                noteRes = R.string.setup_accessibility_body,
                actionRes = R.string.main_primary_action
            )
            !overlayReady -> updateSetupStatus(
                statusRes = R.string.main_status_permission_needed,
                noteRes = R.string.setup_overlay_body,
                actionRes = R.string.main_primary_action
            )
            !captureReady -> updateSetupStatus(
                statusRes = R.string.main_status_ready,
                noteRes = R.string.main_primary_note,
                actionRes = R.string.main_primary_action
            )
            else -> updateSetupStatus(
                statusRes = R.string.main_status_active,
                noteRes = R.string.main_primary_note_ready,
                actionRes = R.string.main_primary_action_ready
            )
        }
    }

    private fun updateStepMarker(
        marker: TextView,
        number: String,
        done: Boolean,
        current: Boolean
    ) {
        marker.text = if (done) "\u2713" else number
        marker.setBackgroundResource(
            when {
                done -> R.drawable.beta_step_done
                current -> R.drawable.beta_step_current
                else -> R.drawable.beta_step_pending
            }
        )
        marker.setTextColor(
            ContextCompat.getColor(
                this,
                if (done || current) R.color.white else R.color.beta_text_secondary
            )
        )
    }

    private fun updateSetupStatus(statusRes: Int, noteRes: Int, actionRes: Int? = null) {
        if (::agentStatusText.isInitialized) {
            agentStatusText.setText(statusRes)
            agentStatusText.contentDescription = getString(statusRes)
        }
        if (::primaryNoteText.isInitialized) {
            primaryNoteText.setText(noteRes)
        }
        if (actionRes != null && ::captureScreenButton.isInitialized) {
            captureScreenButton.setText(actionRes)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d("MainActivity", "Storage permission granted in onRequestPermissionsResult")
                checkOverlayPermissionAndStartCapture()
            } else {
                Log.e("MainActivity", "Storage permission denied")
                Toast.makeText(this, "Storage permission is required", Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        const val EXTRA_START_CAPTURE_ON_OPEN = "com.example.beta.extra.START_CAPTURE_ON_OPEN"
        const val EXTRA_CAPTURE_RESTART_REASON = "com.example.beta.extra.CAPTURE_RESTART_REASON"
        private const val STORAGE_PERMISSION_CODE = 101
    }
}
