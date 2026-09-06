package com.example.beta

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.example.beta.SwiggyMcpClient.SwiggyMcpResult

class MainActivity : ComponentActivity() {

    private lateinit var agentStatusText: TextView
    private lateinit var primaryNoteText: TextView
    private lateinit var orderComposerCard: View
    private lateinit var orderCommandInput: EditText
    private lateinit var orderVoiceInputButton: ImageButton
    private lateinit var orderSubmitButton: Button
    private lateinit var orderInputStatus: TextView
    private lateinit var analyticsSettingsButton: ImageButton
    private lateinit var swiggyConnectionPanel: View
    private lateinit var swiggyConnectionStatus: TextView
    private lateinit var swiggyConnectionDetail: TextView
    private lateinit var swiggySelectedAddress: TextView
    private lateinit var swiggyChangeAddressAction: Button
    private lateinit var swiggyConnectionAction: Button
    private lateinit var swiggyOfflineDemoAction: Button
    private lateinit var microphonePermissionResult: ActivityResultLauncher<String>
    private lateinit var locationPermissionResult: ActivityResultLauncher<Array<String>>
    private lateinit var voiceInputController: OrderVoiceInputController
    private lateinit var textToSpeech: IndianEnglishTextToSpeech
    private var swiggyConnectionState = SwiggyMcpClient.ConnectionState.DISCONNECTED
    private var swiggyMcpRequestGeneration = 0L
    private var swiggyStatusRequestGeneration: Long? = null
    private var resumeSwiggyOrderAfterStatus = false
    private var pendingSwiggyInstruction: String? = null
    private var swiggyConnectPromptShowing = false
    private var swiggyConnectPrompt: AlertDialog? = null
    private var pendingLocationSwiggyInstruction: String? = null
    private var swiggyLocationPrompt: AlertDialog? = null
    private var selectedSwiggyAddressLabel: String? = null
    private lateinit var swiggyOrderCoordinator: SwiggyVoiceOrderCoordinator
    private lateinit var swiggyCheckoutCoordinator: SwiggyCheckoutCoordinator
    private lateinit var swiggyOfflineDemo: SwiggyOfflineDemo
    private lateinit var draftStore: SwiggyDraftStore
    private val draftHandler = Handler(Looper.getMainLooper())
    private var draftPersistenceBlocked = false
    private var draftRestored = false
    private val persistDraftRunnable = Runnable { persistGroceryDraft() }
    private var applyingSpeechText = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        agentStatusText = findViewById(R.id.text_agent_status)
        primaryNoteText = findViewById(R.id.mainPrimaryNote)
        orderComposerCard = findViewById(R.id.orderComposerCard)
        orderCommandInput = findViewById(R.id.orderCommandInput)
        // The encrypted, no-backup store is authoritative across recreation.
        // Never revive an already-confirmed instruction from view saved state.
        orderCommandInput.isSaveEnabled = false
        orderVoiceInputButton = findViewById(R.id.orderVoiceInputButton)
        orderSubmitButton = findViewById(R.id.orderSubmitButton)
        orderInputStatus = findViewById(R.id.orderInputStatus)
        analyticsSettingsButton = findViewById(R.id.analyticsSettingsButton)
        swiggyConnectionPanel = findViewById(R.id.swiggyConnectionPanel)
        swiggyConnectionStatus = findViewById(R.id.swiggyConnectionStatus)
        swiggyConnectionDetail = findViewById(R.id.swiggyConnectionDetail)
        swiggySelectedAddress = findViewById(R.id.swiggySelectedAddress)
        swiggyChangeAddressAction = findViewById(R.id.swiggyChangeAddressAction)
        swiggyConnectionAction = findViewById(R.id.swiggyConnectionAction)
        swiggyOfflineDemoAction = findViewById(R.id.swiggyOfflineDemoAction)

        draftStore = SwiggyDraftStore(applicationContext)
        draftStore.load()?.let { restored ->
            orderCommandInput.setText(restored)
            orderCommandInput.setSelection(restored.length)
            draftRestored = true
            orderInputStatus.text = "Your saved list is here. Continue to check the address and products again."
            Log.i("BetaAgent", "GROCERY_DRAFT_RESTORED")
        }
        orderCommandInput.doAfterTextChanged {
            if (!applyingSpeechText && ::voiceInputController.isInitialized && voiceInputController.isActive) {
                voiceInputController.cancel()
            }
            draftHandler.removeCallbacks(persistDraftRunnable)
            // Text cannot be edited during a mutation. Clearing it in the
            // pre-apply hook must not schedule a stale write after the clear.
            if (draftPersistenceBlocked) return@doAfterTextChanged
            draftRestored = false
            draftHandler.postDelayed(persistDraftRunnable, 250L)
        }

        textToSpeech = IndianEnglishTextToSpeech(this)
        voiceInputController = OrderVoiceInputController(
            context = this,
            onStateChanged = ::renderVoiceInputState,
            onPartialResult = { partial ->
                setRecognizedSpeechText(partial)
            },
            onFinalResult = { instruction ->
                setRecognizedSpeechText(instruction)
                submitOrderInstruction(instruction, source = "voice")
            },
            onRecognitionError = ::handleVoiceInputError,
        )
        swiggyCheckoutCoordinator = SwiggyCheckoutCoordinator(this, ::announceSwiggy) {
            swiggyOrderCoordinator.onHostResumed()
        }
        swiggyOfflineDemo = SwiggyOfflineDemo(this)
        findViewById<android.widget.Button>(R.id.swiggyCheckoutAction).apply {
            visibility = if (BuildConfig.BETA_SWIGGY_CHECKOUT_ENABLED) android.view.View.VISIBLE else android.view.View.GONE
            setOnClickListener {
                if (!SwiggyCartMutationGuard.isInFlight()) swiggyCheckoutCoordinator.startFromCart()
            }
        }
        swiggyOfflineDemoAction.setOnClickListener {
            if (SwiggyCartMutationGuard.isInFlight() || swiggyCheckoutCoordinator.isActive() ||
                swiggyOrderCoordinator.isActive()) return@setOnClickListener
            voiceInputController.cancel()
            textToSpeech.stop()
            swiggyOfflineDemo.start()
        }
        swiggyOrderCoordinator = SwiggyVoiceOrderCoordinator(
            activity = this,
            announce = ::announceSwiggy,
            onReconnectRequired = {
                updateSwiggyConnectionUi(SwiggyMcpClient.ConnectionState.RECONNECT_REQUIRED)
            },
            onAddressChanged = ::renderSwiggySelectedAddress,
            onTerminal = ::resetSwiggyOrderInputStatus,
            onVerified = ::onSwiggyCartVerified,
            beforeApply = ::prepareDraftForCartApply,
            onCheckoutRequested = { swiggyCheckoutCoordinator.start(it) },
            isCheckoutActive = { swiggyCheckoutCoordinator.isActive() },
            onEditRequest = {
                orderCommandInput.requestFocus()
                orderCommandInput.setSelection(orderCommandInput.text.length)
                orderCommandInput.announceForAccessibility("Edit this list or use the microphone to say a new list.")
            },
        )
        SwiggyCartMutationGuard.register(this) { inFlight ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                updateSwiggyMutationControls(inFlight)
                if (!inFlight) {
                    SwiggyCartMutationGuard.consumeTerminalNotice()?.let(::announceSwiggy)
                    orderCommandInput.post { swiggyOrderCoordinator.onSharedRequestSettled() }
                }
            }
        }

        microphonePermissionResult = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startVoiceRecognition()
            } else {
                Toast.makeText(this, R.string.voice_microphone_required, Toast.LENGTH_LONG).show()
            }
            BetaTelemetry.instance?.logPermissionResult("microphone", granted)
        }

        locationPermissionResult = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { grants ->
            val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            Log.i("BetaAgent", "SWIGGY_ADDRESS_LOCATION_PERMISSION_RESULT granted=$granted")
            BetaTelemetry.instance?.logPermissionResult("location", granted)
            continuePendingLocationAwareSwiggyOrder()
        }

        orderVoiceInputButton.setOnClickListener {
            checkMicrophoneAndStartVoice()
        }
        orderSubmitButton.setOnClickListener {
            submitOrderInstruction(orderCommandInput.text?.toString().orEmpty(), source = "text")
        }
        orderCommandInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submitOrderInstruction(orderCommandInput.text?.toString().orEmpty(), source = "text")
                true
            } else {
                false
            }
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
        analyticsSettingsButton.setOnClickListener { showAnalyticsConsentDialog(force = true) }
        configurePrimaryExperience()
        BetaTelemetry.instance?.onAppResume()
        handleSwiggyOAuthIntent(intent)
        handleSwiggyOrderIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        BetaTelemetry.instance?.onAppResume()
        refreshSwiggyConnectionStatus(resumePendingOrder = true)
        val checkoutActive = swiggyCheckoutCoordinator.onResume()
        if (!swiggyOrderCoordinator.onHostResumed(checkoutActive)) maybeShowFeedbackPrompt()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSwiggyOAuthIntent(intent)
        handleSwiggyOrderIntent(intent)
    }

    override fun onPause() {
        if (::swiggyCheckoutCoordinator.isInitialized) swiggyCheckoutCoordinator.onPause()
        if (::swiggyOfflineDemo.isInitialized) swiggyOfflineDemo.onPause()
        if (::swiggyOrderCoordinator.isInitialized) swiggyOrderCoordinator.onHostPaused()
        if (::voiceInputController.isInitialized) voiceInputController.cancel()
        if (::textToSpeech.isInitialized) textToSpeech.stop()
        draftHandler.removeCallbacks(persistDraftRunnable)
        if (::draftStore.isInitialized) persistGroceryDraft()
        super.onPause()
    }

    override fun onDestroy() {
        if (::swiggyCheckoutCoordinator.isInitialized) swiggyCheckoutCoordinator.destroy()
        if (::swiggyOfflineDemo.isInitialized) swiggyOfflineDemo.destroy()
        draftHandler.removeCallbacks(persistDraftRunnable)
        super.onDestroy()
        cancelPendingSwiggyMcpWork("activity_destroyed")
        SwiggyCartMutationGuard.unregister(this)
        if (::voiceInputController.isInitialized) voiceInputController.destroy()
        if (::textToSpeech.isInitialized) textToSpeech.shutdown()
    }

    private fun speak(message: String) {
        if (::textToSpeech.isInitialized) textToSpeech.speak(message)
    }

    private fun checkMicrophoneAndStartVoice() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startVoiceRecognition()
            return
        }
        val preferences = getSharedPreferences(VOICE_PERMISSION_PREFERENCES, MODE_PRIVATE)
        if (preferences.getBoolean(VOICE_PERMISSION_EXPLAINED, false)) {
            microphonePermissionResult.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.microphone_prompt_title)
            .setMessage(R.string.microphone_prompt_message)
            .setPositiveButton(R.string.microphone_prompt_allow) { _, _ ->
                preferences.edit().putBoolean(VOICE_PERMISSION_EXPLAINED, true).apply()
                microphonePermissionResult.launch(Manifest.permission.RECORD_AUDIO)
            }
            .setNegativeButton(R.string.common_not_now, null)
            .show()
    }

    private fun startVoiceRecognition() {
        textToSpeech.stop()
        voiceInputController.toggle()
    }

    private fun renderVoiceInputState(state: OrderVoiceInputController.State) {
        when (state) {
            OrderVoiceInputController.State.IDLE -> {
                orderVoiceInputButton.contentDescription = getString(R.string.order_voice_start)
                orderInputStatus.setText(R.string.order_input_help)
            }
            OrderVoiceInputController.State.LISTENING -> {
                orderVoiceInputButton.contentDescription = getString(R.string.order_voice_stop)
                orderInputStatus.setText(R.string.voice_listening)
                orderInputStatus.announceForAccessibility(orderInputStatus.text)
            }
            OrderVoiceInputController.State.PROCESSING -> {
                orderVoiceInputButton.contentDescription = getString(R.string.order_voice_stop)
                orderInputStatus.setText(R.string.voice_processing)
            }
        }
    }

    private fun setRecognizedSpeechText(text: String) {
        applyingSpeechText = true
        try {
            orderCommandInput.setText(text)
            orderCommandInput.setSelection(text.length)
        } finally {
            applyingSpeechText = false
        }
    }

    private fun handleVoiceInputError(error: OrderVoiceInputController.Error) {
        val messageRes = when (error) {
            OrderVoiceInputController.Error.UNAVAILABLE -> R.string.voice_unavailable
            OrderVoiceInputController.Error.NO_MATCH -> R.string.voice_no_order_heard
            OrderVoiceInputController.Error.BUSY -> R.string.voice_busy
            OrderVoiceInputController.Error.NETWORK -> R.string.voice_network_error
            OrderVoiceInputController.Error.PERMISSION -> R.string.voice_microphone_required
            OrderVoiceInputController.Error.OTHER -> R.string.voice_no_order_heard
        }
        orderInputStatus.setText(messageRes)
        Toast.makeText(this, messageRes, Toast.LENGTH_LONG).show()
    }

    private fun resetSwiggyOrderInputStatus() {
        if (!::orderInputStatus.isInitialized || isSwiggyMutationInFlight()) return
        draftPersistenceBlocked = false
        if (draftRestored) {
            orderInputStatus.text = "Your saved list is here. Continue to check the address and products again."
        } else {
            orderInputStatus.setText(R.string.order_input_help)
        }
    }

    private fun persistGroceryDraft(): Boolean {
        if (draftPersistenceBlocked || isSwiggyMutationInFlight()) return true
        val saved = draftStore.save(orderCommandInput.text?.toString().orEmpty())
        if (!saved) {
            Log.w("BetaAgent", "GROCERY_DRAFT_SAVE_FAILED")
            orderInputStatus.text = "Your list could not be saved on this phone. Keep Beta open and try again."
        }
        return saved
    }

    private fun prepareDraftForCartApply(): Boolean {
        draftHandler.removeCallbacks(persistDraftRunnable)
        draftPersistenceBlocked = true
        if (!draftStore.clear()) {
            draftPersistenceBlocked = false
            Log.w("BetaAgent", "GROCERY_DRAFT_CLEAR_FAILED")
            return false
        }
        orderCommandInput.setText("")
        draftRestored = false
        return true
    }

    private fun submitOrderInstruction(rawInstruction: String, source: String) {
        val instruction = rawInstruction.trim()
        if (instruction.isBlank()) {
            orderInputStatus.setText(R.string.order_input_required)
            orderCommandInput.requestFocus()
            orderCommandInput.announceForAccessibility(getString(R.string.order_input_required))
            return
        }
        voiceInputController.cancel()
        textToSpeech.stop()
        orderCommandInput.setText(instruction)
        orderCommandInput.setSelection(instruction.length)
        draftHandler.removeCallbacks(persistDraftRunnable)
        if (!persistGroceryDraft()) return
        orderInputStatus.setText(R.string.voice_processing)
        Log.i("BetaAgent", "ORDER_INSTRUCTION_RECEIVED source=$source characters=${instruction.length}")
        BetaTelemetry.instance?.logOrderRequestSubmitted(source, instruction)

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
        handleSwiggyVoiceInstruction(instruction)
    }

    private fun configurePrimaryExperience() {
        if (!::swiggyConnectionPanel.isInitialized) return
        swiggyConnectionPanel.visibility = View.VISIBLE
        orderComposerCard.visibility = if (swiggyConnectionState == SwiggyMcpClient.ConnectionState.READY) {
            View.VISIBLE
        } else {
            View.GONE
        }

        when (swiggyConnectionState) {
            SwiggyMcpClient.ConnectionState.READY -> updateSetupStatus(
                statusRes = R.string.swiggy_connection_ready,
                noteRes = R.string.swiggy_primary_note_ready,
            )
            SwiggyMcpClient.ConnectionState.RECONNECT_REQUIRED -> updateSetupStatus(
                statusRes = R.string.swiggy_connection_reconnect,
                noteRes = R.string.swiggy_primary_note_disconnected,
            )
            SwiggyMcpClient.ConnectionState.DISCONNECTED -> updateSetupStatus(
                statusRes = R.string.swiggy_connection_status,
                noteRes = R.string.swiggy_primary_note_disconnected,
            )
        }
        renderSwiggyConnectionPanel()
    }

    private fun renderSwiggyConnectionPanel(detailOverride: String? = null) {
        if (!::swiggyConnectionPanel.isInitialized) return
        swiggyConnectionAction.visibility = View.VISIBLE
        renderSwiggySelectedAddress(selectedSwiggyAddressLabel)
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
                if (requestGeneration != swiggyMcpRequestGeneration) {
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
        val previousState = swiggyConnectionState
        if (state != SwiggyMcpClient.ConnectionState.READY && ::swiggyOrderCoordinator.isInitialized) {
            swiggyOrderCoordinator.clearRememberedAddress()
        }
        swiggyConnectionState = state
        renderSwiggyConnectionPanel(detailOverride)
        swiggyConnectionAction.isEnabled = !isSwiggyMutationInFlight()
        swiggyConnectionStatus.announceForAccessibility(swiggyConnectionStatus.text)
        configurePrimaryExperience()
        if (state == SwiggyMcpClient.ConnectionState.READY && previousState != state) {
            val connectionPreferences = getSharedPreferences(CONNECTION_PREFERENCES, MODE_PRIVATE)
            if (connectionPreferences.getBoolean(CONNECTION_ATTEMPT_PENDING, false)) {
                BetaTelemetry.instance?.logEvent("swiggy_connect_completed")
                connectionPreferences.edit().putBoolean(CONNECTION_ATTEMPT_PENDING, false).apply()
            }
            BetaTelemetry.instance?.maybeLogOnboardingCompleted()
            showAnalyticsConsentDialog(force = false)
        }
    }

    private fun startSwiggyConnection() {
        BetaTelemetry.instance?.logEvent("swiggy_connect_started")
        getSharedPreferences(CONNECTION_PREFERENCES, MODE_PRIVATE)
            .edit()
            .putBoolean(CONNECTION_ATTEMPT_PENDING, true)
            .apply()
        val requestGeneration = swiggyMcpRequestGeneration
        swiggyConnectionAction.isEnabled = false
        swiggyConnectionStatus.setText(R.string.swiggy_connection_connecting)
        swiggyConnectionDetail.setText(R.string.swiggy_connection_browser_detail)
        SwiggyMcpClient.connect(this) { result ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (requestGeneration != swiggyMcpRequestGeneration) {
                    Log.i("BetaAgent", "SWIGGY_MCP_CONNECT_IGNORED_STALE")
                    return@runOnUiThread
                }
                when (result) {
                    is SwiggyMcpResult.Success -> {
                        val authUrl = result.value.authorizationUrl
                        if (authUrl.isNullOrBlank() || !openTrustedSwiggyAuthorization(authUrl)) {
                            BetaTelemetry.instance?.logEvent(
                                "swiggy_connect_failed",
                                mapOf("reason" to "invalid_authorization_link"),
                            )
                            clearPendingConnectionAttempt()
                            updateSwiggyConnectionUi(
                                SwiggyMcpClient.ConnectionState.DISCONNECTED,
                                getString(R.string.swiggy_connection_invalid_link),
                            )
                        }
                    }
                    is SwiggyMcpResult.Failure -> {
                        BetaTelemetry.instance?.logEvent(
                            "swiggy_connect_failed",
                            mapOf("reason" to if (result.reconnectRequired) "reconnect_required" else "backend_failure"),
                        )
                        clearPendingConnectionAttempt()
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

    private fun clearPendingConnectionAttempt() {
        getSharedPreferences(CONNECTION_PREFERENCES, MODE_PRIVATE)
            .edit()
            .putBoolean(CONNECTION_ATTEMPT_PENDING, false)
            .apply()
    }

    private fun handleSwiggyOAuthIntent(sourceIntent: Intent?) {
        val data = sourceIntent?.data ?: return
        if (data.scheme != "beta" || data.host != "swiggy" || data.path != "/oauth") return
        sourceIntent.data = null
        if (data.getQueryParameter("status") == "connected") {
            swiggyOrderCoordinator.clearRememberedAddress()
            swiggyConnectionStatus.setText(R.string.swiggy_connection_checking)
            swiggyConnectionDetail.setText(R.string.swiggy_connection_finishing)
            refreshSwiggyConnectionStatus(resumePendingOrder = true)
        } else {
            BetaTelemetry.instance?.logEvent("swiggy_connect_failed", mapOf("reason" to "oauth_callback"))
            clearPendingConnectionAttempt()
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
        orderCommandInput.post {
            orderCommandInput.setText(instruction)
            draftHandler.removeCallbacks(persistDraftRunnable)
            if (persistGroceryDraft()) handleSwiggyVoiceInstruction(instruction)
        }
    }

    private fun promptToConnectSwiggy() {
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
            .setNegativeButton(R.string.common_not_now) { _, _ ->
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
        val instruction = pendingSwiggyInstruction?.takeIf { it.isNotBlank() } ?: return
        pendingSwiggyInstruction = null
        startSwiggyOrderWithOptionalLocation(instruction)
    }

    private fun startSwiggyOrderWithOptionalLocation(instruction: String) {
        val hasLocationPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val preferences = getSharedPreferences(SWIGGY_LOCATION_PREFERENCES, MODE_PRIVATE)
        if (hasLocationPermission || preferences.getBoolean(SWIGGY_LOCATION_PERMISSION_ASKED, false)) {
            swiggyOrderCoordinator.start(instruction)
            return
        }
        if (swiggyLocationPrompt?.isShowing == true) {
            announceSwiggy("Please finish the address suggestion choice first.")
            return
        }
        pendingLocationSwiggyInstruction = instruction
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.swiggy_location_prompt_title)
            .setMessage(R.string.swiggy_location_prompt_message)
            .setPositiveButton(R.string.swiggy_location_prompt_accept) { _, _ ->
                preferences.edit().putBoolean(SWIGGY_LOCATION_PERMISSION_ASKED, true).apply()
                locationPermissionResult.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    )
                )
            }
            .setNegativeButton(R.string.swiggy_location_prompt_skip) { _, _ ->
                preferences.edit().putBoolean(SWIGGY_LOCATION_PERMISSION_ASKED, true).apply()
                continuePendingLocationAwareSwiggyOrder()
            }
            .setOnCancelListener {
                preferences.edit().putBoolean(SWIGGY_LOCATION_PERMISSION_ASKED, true).apply()
                continuePendingLocationAwareSwiggyOrder()
            }
            .create()
        swiggyLocationPrompt = dialog
        dialog.setOnDismissListener {
            if (swiggyLocationPrompt === dialog) swiggyLocationPrompt = null
        }
        dialog.show()
    }

    private fun continuePendingLocationAwareSwiggyOrder() {
        val instruction = pendingLocationSwiggyInstruction?.takeIf { it.isNotBlank() } ?: return
        pendingLocationSwiggyInstruction = null
        if (!isFinishing && !isDestroyed) {
            swiggyOrderCoordinator.start(instruction)
        }
    }

    private fun confirmSwiggyDisconnect() {
        AlertDialog.Builder(this)
            .setTitle(R.string.swiggy_disconnect_dialog_title)
            .setMessage(R.string.swiggy_disconnect_dialog_message)
            .setPositiveButton(R.string.swiggy_connection_disconnect) { _, _ ->
                if (!prepareDraftForCartApply()) {
                    announceSwiggy("Beta could not clear the saved list. Please try again.")
                    return@setPositiveButton
                }
                draftPersistenceBlocked = false
                val requestGeneration = swiggyMcpRequestGeneration
                swiggyConnectionAction.isEnabled = false
                SwiggyMcpClient.disconnect(this) { result ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (requestGeneration != swiggyMcpRequestGeneration) {
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
            .setNegativeButton(R.string.common_not_now, null)
            .show()
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
        pendingLocationSwiggyInstruction = null
        swiggyConnectPromptShowing = false
        swiggyConnectPrompt?.dismiss()
        swiggyConnectPrompt = null
        swiggyLocationPrompt?.dismiss()
        swiggyLocationPrompt = null
        if (::swiggyOrderCoordinator.isInitialized) {
            swiggyOrderCoordinator.cancel()
            swiggyOrderCoordinator.clearRememberedAddress()
        }
        Log.i("BetaAgent", "SWIGGY_MCP_PENDING_WORK_CANCELLED reason=$reason")
    }

    private fun updateSwiggyMutationControls(inFlight: Boolean) {
        if (::swiggyConnectionAction.isInitialized) {
            swiggyConnectionAction.isEnabled = !inFlight
        }
        if (::swiggyChangeAddressAction.isInitialized) {
            swiggyChangeAddressAction.isEnabled = !inFlight
        }
        if (::orderCommandInput.isInitialized) orderCommandInput.isEnabled = !inFlight
        if (::orderVoiceInputButton.isInitialized) orderVoiceInputButton.isEnabled = !inFlight
        if (::orderSubmitButton.isInitialized) orderSubmitButton.isEnabled = !inFlight
    }

    private fun isSwiggyMutationInFlight(): Boolean {
        return SwiggyCartMutationGuard.isInFlight() ||
            (::swiggyOrderCoordinator.isInitialized && swiggyOrderCoordinator.isMutationInFlight())
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

    private fun showAnalyticsConsentDialog(force: Boolean) {
        val consent = (application as MyApplication).analyticsConsentManager
        if (!force && consent.hasSeenChoice()) return
        val enabled = consent.isAnalyticsAllowed()
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.analytics_consent_title)
            .setMessage(R.string.analytics_consent_message)
            .setPositiveButton(R.string.analytics_consent_allow) { _, _ ->
                consent.setAnalyticsAllowed(true)
                BetaTelemetry.instance?.logEvent(
                    "consent_changed",
                    mapOf("consent_kind" to "analytics", "value" to true),
                )
                BetaTelemetry.instance?.onAppResume()
                if (swiggyConnectionState == SwiggyMcpClient.ConnectionState.READY) {
                    BetaTelemetry.instance?.maybeLogOnboardingCompleted()
                }
            }
        if (force && enabled) {
            builder.setNegativeButton(R.string.analytics_consent_turn_off) { _, _ ->
                BetaTelemetry.instance?.logEvent(
                    "consent_changed",
                    mapOf("consent_kind" to "analytics", "value" to false),
                )
                consent.setAnalyticsAllowed(false)
            }
        } else {
            builder.setNegativeButton(R.string.analytics_consent_not_now) { _, _ ->
                consent.setAnalyticsAllowed(false)
            }
        }
        builder.show()
    }

    private fun onSwiggyCartVerified(itemCount: Int) {
        // Pre-apply already cleared durably. Do not store a replayable basket.
        draftHandler.removeCallbacks(persistDraftRunnable)
        BetaTelemetry.instance?.logCartUpdateVerified(itemCount)
        getSharedPreferences(FEEDBACK_PREFERENCES, MODE_PRIVATE)
            .edit()
            .putBoolean(FEEDBACK_SUCCESS_PENDING, true)
            .apply()
    }

    private fun maybeShowFeedbackPrompt() {
        if (isSwiggyMutationInFlight()) return
        val preferences = getSharedPreferences(FEEDBACK_PREFERENCES, MODE_PRIVATE)
        val successPending = preferences.getBoolean(FEEDBACK_SUCCESS_PENDING, false)
        val milestone = BetaTelemetry.instance?.dueFeedbackMilestone()
        if (!successPending && milestone == null) return
        preferences.edit().putBoolean(FEEDBACK_SUCCESS_PENDING, false).apply()
        milestone?.let { BetaTelemetry.instance?.markFeedbackMilestoneShown(it) }
        showFeedbackPrompt(if (successPending) "verified_cart" else "retention_${milestone ?: 0}")
    }

    private fun showFeedbackPrompt(category: String) {
        BetaTelemetry.instance?.logEvent("feedback_prompt_shown", mapOf("category" to category))
        AlertDialog.Builder(this)
            .setTitle(R.string.feedback_prompt_title)
            .setMessage(R.string.feedback_prompt_message)
            .setPositiveButton(R.string.feedback_worked) { _, _ ->
                submitFeedback("worked", category, "")
            }
            .setNegativeButton(R.string.feedback_issue) { _, _ ->
                showFeedbackIssueChoices(category)
            }
            .show()
    }

    private fun showFeedbackIssueChoices(category: String) {
        val labels = resources.getStringArray(R.array.feedback_issue_labels)
        val codes = resources.getStringArray(R.array.feedback_issue_codes)
        AlertDialog.Builder(this)
            .setTitle(R.string.feedback_issue_title)
            .setItems(labels) { _, which ->
                submitFeedback("did_not_work", category, codes.getOrElse(which) { "other" })
            }
            .setNegativeButton(R.string.analytics_consent_not_now, null)
            .show()
    }

    private fun submitFeedback(rating: String, category: String, message: String) {
        FeedbackClient.submit(
            context = this,
            rating = rating,
            category = category,
            message = message,
            includeLogs = false,
        ) { success, detail ->
            runOnUiThread {
                BetaTelemetry.instance?.logEvent(
                    "feedback_submitted",
                    mapOf("category" to category, "outcome" to if (success) "success" else "failed"),
                )
                Toast.makeText(
                    this,
                    if (success) getString(R.string.feedback_sent) else getString(R.string.feedback_failed, detail),
                    if (success) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun updateSetupStatus(statusRes: Int, noteRes: Int) {
        if (::agentStatusText.isInitialized) {
            agentStatusText.setText(statusRes)
            agentStatusText.contentDescription = getString(statusRes)
        }
        if (::primaryNoteText.isInitialized) {
            primaryNoteText.setText(noteRes)
        }
    }

    companion object {
        private const val SWIGGY_LOCATION_PREFERENCES = "swiggy_location_preferences"
        private const val SWIGGY_LOCATION_PERMISSION_ASKED = "location_permission_asked"
        private const val VOICE_PERMISSION_PREFERENCES = "voice_permission_preferences"
        private const val VOICE_PERMISSION_EXPLAINED = "voice_permission_explained"
        private const val FEEDBACK_PREFERENCES = "feedback_prompt_preferences"
        private const val FEEDBACK_SUCCESS_PENDING = "feedback_success_pending"
        private const val CONNECTION_PREFERENCES = "swiggy_connection_preferences"
        private const val CONNECTION_ATTEMPT_PENDING = "connection_attempt_pending"
    }
}
