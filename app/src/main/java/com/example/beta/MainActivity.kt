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
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
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
    private lateinit var swiggyKeptNote: TextView
    private lateinit var swiggyConnectionProgress: View
    private lateinit var mainScroll: ScrollView
    private lateinit var swiggySelectedAddress: TextView
    private lateinit var swiggyChangeAddressAction: Button
    private lateinit var swiggyConnectionAction: Button
    private lateinit var swiggyOfflineDemoAction: Button
    private lateinit var microphonePermissionResult: ActivityResultLauncher<String>
    private lateinit var locationPermissionResult: ActivityResultLauncher<Array<String>>
    private lateinit var voiceInputController: OrderVoiceInputController
    private lateinit var textToSpeech: IndianEnglishTextToSpeech
    private val connectionSession = SwiggyConnectionSession()
    private val swiggyConnectionState get() = connectionSession.state
    private val swiggyMcpRequestGeneration get() = connectionSession.generation
    private var swiggyConnectionOperationInFlight = false
    private var openingSwiggyBrowser = false
    private var retrySwiggySignIn = false
    private var finishingSwiggySignIn = false
    private var lastConnectionScreen: SwiggyConnectionSession.Screen? = null
    private var revealComposerAfterConnection = false
    private var connectionDetailOverride: String? = null
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
    private var persistedComposerText = ""
    private var retainedSwiggyOrderStatus = false
    private var lastVoiceInputState = OrderVoiceInputController.State.IDLE
    private val persistDraftRunnable = Runnable { persistGroceryDraft() }
    private var applyingSpeechText = false
    private var recoveryVoiceRequested = false
    private var scopedVoiceResult: ((String?) -> Unit)? = null
    private data class VoicePermissionAttempt(val epoch: Long, val recovery: Boolean)
    private var pendingVoicePermission: VoicePermissionAttempt? = null
    private var recoveryVoiceEpoch = 0L
    private var composerInstructionEpoch = 0L

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
        swiggyKeptNote = findViewById(R.id.swiggyKeptNote)
        swiggyConnectionProgress = findViewById(R.id.swiggyConnectionProgress)
        mainScroll = findViewById(R.id.mainScroll)
        swiggySelectedAddress = findViewById(R.id.swiggySelectedAddress)
        swiggyChangeAddressAction = findViewById(R.id.swiggyChangeAddressAction)
        swiggyConnectionAction = findViewById(R.id.swiggyConnectionAction)
        swiggyOfflineDemoAction = findViewById(R.id.swiggyOfflineDemoAction)

        draftStore = SwiggyDraftStore(applicationContext)
        draftStore.load()?.let { restored ->
            persistedComposerText = restored
            orderCommandInput.setText(restored)
            orderCommandInput.setSelection(restored.length)
            draftRestored = true
            orderInputStatus.text = "Your saved list is here. Continue to check the address and products again."
            Log.i("BetaAgent", "GROCERY_DRAFT_RESTORED")
        }
        // Restore presentation only. The encrypted draft remains authoritative;
        // saved state must never restart a request or revive a submitted list.
        savedInstanceState?.getString(RETAINED_ORDER_STATUS)?.takeIf {
            it.isNotBlank() && draftRestored && !SwiggyCartMutationGuard.isInFlight()
        }?.let { message ->
            retainedSwiggyOrderStatus = true
            orderInputStatus.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
            orderInputStatus.text = message
            orderInputStatus.post {
                orderInputStatus.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
        }
        var lastComposerText = orderCommandInput.text?.toString().orEmpty()
        orderCommandInput.doAfterTextChanged {
            val currentText = orderCommandInput.text?.toString().orEmpty()
            if (currentText != lastComposerText) {
                lastComposerText = currentText
                if (retainedSwiggyOrderStatus) {
                    retainedSwiggyOrderStatus = false
                    orderInputStatus.setText(R.string.order_input_help)
                }
            }
            composerInstructionEpoch += 1
            // A retry must never submit an earlier version after the user edits it.
            pendingSwiggyInstruction = null
            resumeSwiggyOrderAfterStatus = false
            if (!applyingSpeechText) {
                if (::voiceInputController.isInitialized && voiceInputController.isActive) voiceInputController.cancel()
                recoveryVoiceRequested = false
                recoveryVoiceEpoch += 1
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
            onPartialResult = ::handlePartialSpeechText,
            onFinalResult = { instruction ->
                val scopedResult = scopedVoiceResult
                scopedVoiceResult = null
                recoveryVoiceRequested = false
                recoveryVoiceEpoch += 1
                if (scopedResult != null) scopedResult(instruction)
                else {
                    setRecognizedSpeechText(instruction)
                    submitOrderInstruction(instruction, source = "voice")
                }
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
                connectionSession.invalidate()
                updateSwiggyConnectionUi(SwiggyMcpClient.ConnectionState.RECONNECT_REQUIRED)
            },
            onAddressChanged = ::renderSwiggySelectedAddress,
            onTerminal = { retainStatus -> resetSwiggyOrderInputStatus(retainStatus) },
            onVerified = ::onSwiggyCartVerified,
            beforeApply = ::prepareDraftForCartApply,
            onCheckoutRequested = { swiggyCheckoutCoordinator.start(it) },
            isCheckoutActive = { swiggyCheckoutCoordinator.isActive() },
            onEditRequest = {
                orderCommandInput.requestFocus()
                orderCommandInput.setSelection(orderCommandInput.text.length)
                orderCommandInput.announceForAccessibility("Edit this list or use the microphone to say a new list.")
            },
            onInstructionEdited = ::saveConfirmedInstructionEdit,
            onRecoveryVoiceRequested = ::beginRecoveryVoice,
        ).apply {
            onScopedInstructionEdited = ::saveScopedInstructionEdit
            onScopedVoiceRequested = ::beginScopedRecoveryVoice
        }
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

        microphonePermissionResult = registerForActivityResult(ActivityResultContracts.RequestPermission(), ::handleMicrophonePermissionResult)

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
            if (recoveryVoiceRequested && voiceInputController.isActive) {
                voiceInputController.cancel()
                finishRecoveryVoice(RECOVERY_VOICE_STOPPED)
            } else checkMicrophoneAndStartVoice()
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
            if (swiggyConnectionOperationInFlight || isSwiggyMutationInFlight()) return@setOnClickListener
            if (connectionSession.presentation == SwiggyConnectionSession.Presentation.RETRY) {
                if (retrySwiggySignIn) startSwiggyConnection()
                else refreshSwiggyConnectionStatus(resumePendingOrder = false)
                return@setOnClickListener
            }
            if (connectionSession.presentation == SwiggyConnectionSession.Presentation.CHECKING) return@setOnClickListener
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
        analyticsSettingsButton.setOnClickListener { showAppSettings() }
        configurePrimaryExperience()
        BetaTelemetry.instance?.onAppResume()
        handleSwiggyOAuthIntent(intent)
        handleSwiggyOrderIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        BetaTelemetry.instance?.onAppResume()
        refreshSwiggyConnectionStatus(resumePendingOrder = false)
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
        composerInstructionEpoch += 1
        // A backgrounded command must not restart merely because the app returns.
        pendingSwiggyInstruction = null
        resumeSwiggyOrderAfterStatus = false
        if (::swiggyCheckoutCoordinator.isInitialized) swiggyCheckoutCoordinator.onPause()
        if (::swiggyOfflineDemo.isInitialized) swiggyOfflineDemo.onPause()
        if (::swiggyOrderCoordinator.isInitialized) swiggyOrderCoordinator.onHostPaused()
        if (::voiceInputController.isInitialized) voiceInputController.cancel()
        if (recoveryVoiceRequested && pendingVoicePermission == null) finishRecoveryVoice(RECOVERY_VOICE_STOPPED)
        if (::textToSpeech.isInitialized) textToSpeech.stop()
        draftHandler.removeCallbacks(persistDraftRunnable)
        if (::draftStore.isInitialized) persistGroceryDraft()
        super.onPause()
    }

    override fun onStop() {
        // Permission result delivered to a recreated/backgrounded Activity is not a new mic request.
        recoveryVoiceRequested = false
        scopedVoiceResult = null
        recoveryVoiceEpoch += 1
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (retainedSwiggyOrderStatus && !isSwiggyMutationInFlight()) {
            outState.putString(RETAINED_ORDER_STATUS, orderInputStatus.text.toString())
        }
        super.onSaveInstanceState(outState)
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
        if (pendingVoicePermission != null) return
        val attempt = VoicePermissionAttempt(recoveryVoiceEpoch, recoveryVoiceRequested)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startVoiceRecognition()
            return
        }
        val preferences = getSharedPreferences(VOICE_PERMISSION_PREFERENCES, MODE_PRIVATE)
        if (preferences.getBoolean(VOICE_PERMISSION_EXPLAINED, false)) {
            pendingVoicePermission = attempt
            microphonePermissionResult.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.microphone_prompt_title)
            .setMessage(R.string.microphone_prompt_message)
            .setPositiveButton(R.string.microphone_prompt_allow) { _, _ ->
                if (attempt.epoch != recoveryVoiceEpoch) return@setPositiveButton
                preferences.edit().putBoolean(VOICE_PERMISSION_EXPLAINED, true).apply()
                pendingVoicePermission = attempt
                microphonePermissionResult.launch(Manifest.permission.RECORD_AUDIO)
            }
            .setNegativeButton(R.string.common_not_now) { _, _ ->
                if (recoveryVoiceRequested) finishRecoveryVoice(RECOVERY_VOICE_PERMISSION)
            }
            .setOnCancelListener { if (recoveryVoiceRequested) finishRecoveryVoice(RECOVERY_VOICE_STOPPED) }
            .show()
    }

    private fun handleMicrophonePermissionResult(granted: Boolean) {
        val attempt = pendingVoicePermission
        pendingVoicePermission = null
        BetaTelemetry.instance?.logPermissionResult("microphone", granted)
        // No locally initiated current request means a stale or recreation-delivered result.
        if (attempt == null || attempt.epoch != recoveryVoiceEpoch || isFinishing || isDestroyed) return
        if (attempt.recovery) {
            if (!recoveryVoiceRequested) return
            if (granted) startVoiceRecognition() else finishRecoveryVoice(RECOVERY_VOICE_PERMISSION)
        } else if (granted) startVoiceRecognition()
        else Toast.makeText(this, R.string.voice_microphone_required, Toast.LENGTH_LONG).show()
    }

    private fun startVoiceRecognition() {
        textToSpeech.stop()
        if (recoveryVoiceRequested) voiceInputController.start() else voiceInputController.toggle()
    }

    private fun beginRecoveryVoice() {
        // Coordinator has already saved exact editor text and invalidated every old request.
        voiceInputController.cancel()
        scopedVoiceResult = null
        recoveryVoiceRequested = true
        val epoch = ++recoveryVoiceEpoch
        orderCommandInput.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(orderCommandInput.windowToken, 0)
        mainScroll.post {
            if (epoch != recoveryVoiceEpoch || !recoveryVoiceRequested || isFinishing || isDestroyed) return@post
            mainScroll.scrollTo(0, (orderComposerCard.top - 16 * resources.displayMetrics.density).toInt().coerceAtLeast(0))
            checkMicrophoneAndStartVoice()
        }
    }

    private fun finishRecoveryVoice(message: String) {
        val scopedResult = scopedVoiceResult
        scopedVoiceResult = null
        recoveryVoiceRequested = false
        recoveryVoiceEpoch += 1
        if (scopedResult != null) {
            scopedResult(null)
            return
        }
        orderInputStatus.text = message
        val accessibility = getSystemService(ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if (accessibility?.isTouchExplorationEnabled == true) {
            orderInputStatus.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
        }
    }

    private fun renderVoiceInputState(state: OrderVoiceInputController.State) {
        val enteredState = state != lastVoiceInputState
        lastVoiceInputState = state
        when (state) {
            OrderVoiceInputController.State.IDLE -> {
                orderVoiceInputButton.contentDescription = getString(R.string.order_voice_start)
                if (!retainedSwiggyOrderStatus) orderInputStatus.setText(R.string.order_input_help)
            }
            OrderVoiceInputController.State.LISTENING -> {
                orderVoiceInputButton.contentDescription = getString(R.string.order_voice_stop)
                if (!retainedSwiggyOrderStatus) {
                    orderInputStatus.setText(R.string.voice_listening)
                }
                if (enteredState) orderVoiceInputButton.announceForAccessibility(getString(R.string.voice_listening))
            }
            OrderVoiceInputController.State.PROCESSING -> {
                orderVoiceInputButton.contentDescription = getString(R.string.order_voice_stop)
                if (!retainedSwiggyOrderStatus) orderInputStatus.setText(R.string.voice_processing)
                else if (enteredState) orderVoiceInputButton.announceForAccessibility(getString(R.string.voice_processing))
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

    private fun handlePartialSpeechText(text: String) {
        // A tentative transcript must not overwrite the saved typed recovery list.
        if (!recoveryVoiceRequested) setRecognizedSpeechText(text)
    }

    private fun handleVoiceInputError(error: OrderVoiceInputController.Error) {
        if (recoveryVoiceRequested) {
            finishRecoveryVoice(when (error) {
                OrderVoiceInputController.Error.NO_MATCH -> RECOVERY_VOICE_STOPPED
                OrderVoiceInputController.Error.PERMISSION -> RECOVERY_VOICE_PERMISSION
                else -> RECOVERY_VOICE_UNAVAILABLE
            })
            return
        }
        val messageRes = when (error) {
            OrderVoiceInputController.Error.UNAVAILABLE -> R.string.voice_unavailable
            OrderVoiceInputController.Error.NO_MATCH -> R.string.voice_no_order_heard
            OrderVoiceInputController.Error.BUSY -> R.string.voice_busy
            OrderVoiceInputController.Error.NETWORK -> R.string.voice_network_error
            OrderVoiceInputController.Error.PERMISSION -> R.string.voice_microphone_required
            OrderVoiceInputController.Error.OTHER -> R.string.voice_no_order_heard
        }
        if (!retainedSwiggyOrderStatus) orderInputStatus.setText(messageRes)
        val accessibility = getSystemService(ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if (retainedSwiggyOrderStatus && accessibility?.isTouchExplorationEnabled == true) {
            orderVoiceInputButton.announceForAccessibility(getString(messageRes))
        } else Toast.makeText(this, messageRes, Toast.LENGTH_LONG).show()
    }

    private fun resetSwiggyOrderInputStatus(retainStatus: Boolean = false) {
        if (!::orderInputStatus.isInitialized) return
        if (retainStatus) {
            retainedSwiggyOrderStatus = true
            if (!isSwiggyMutationInFlight()) draftPersistenceBlocked = false
            return
        }
        if (isSwiggyMutationInFlight()) return
        retainedSwiggyOrderStatus = false
        draftPersistenceBlocked = false
        if (draftRestored) {
            orderInputStatus.text = "Your saved list is here. Continue to check the address and products again."
        } else {
            orderInputStatus.setText(R.string.order_input_help)
        }
    }

    private fun persistGroceryDraft(): Boolean {
        if (draftPersistenceBlocked || isSwiggyMutationInFlight()) return true
        // The runnable reads CURRENT composer text on the UI thread, never a
        // captured pre-edit value. Equal text must not erase revision metadata.
        val text = orderCommandInput.text?.toString().orEmpty()
        val saved = draftStore.withStoreLock {
            val stored = draftStore.loadIncludingEmpty()
            // A failed write read-back may already have committed a newer
            // instruction. An unchanged old composer is not a new user request.
            if (text == persistedComposerText && draftStore.hasStoredRecord() && stored != text) false
            else draftStore.saveComposer(text)
        }
        if (saved) persistedComposerText = text
        if (!saved) {
            Log.w("BetaAgent", "GROCERY_DRAFT_SAVE_FAILED")
            orderInputStatus.text = "Your list could not be saved on this phone. Keep Beta open and try again."
        }
        return saved
    }

    /** Commit the confirmed edit before another request; never overwrite a newer composer draft. */
    private fun saveConfirmedInstructionEdit(original: String, edited: String): Boolean {
        if (isFinishing || isDestroyed || draftPersistenceBlocked || isSwiggyMutationInFlight() ||
            orderCommandInput.text?.toString() != original) return false
        draftHandler.removeCallbacks(persistDraftRunnable)
        if (!draftStore.saveConfirmedComposerEdit(original, edited)) {
            Log.w("BetaAgent", "GROCERY_DRAFT_SAVE_FAILED")
            return false
        }
        orderCommandInput.setText(edited)
        persistedComposerText = edited
        orderCommandInput.setSelection(edited.length)
        draftHandler.removeCallbacks(persistDraftRunnable) // Already durably saved; don't write it twice.
        draftRestored = false
        return true
    }

    private fun saveScopedInstructionEdit(
        expected: GroceryIntentRecoveryRecord,
        updated: GroceryIntentRecoveryRecord,
    ): Boolean {
        if (isFinishing || isDestroyed || draftPersistenceBlocked || isSwiggyMutationInFlight()) return false
        val composer = orderCommandInput.text?.toString()
        if (composer != expected.instruction && composer != updated.instruction) return false
        // No delayed lifecycle/debounce write may interleave the commit and the
        // composer adoption; both happen synchronously on the Activity thread.
        draftHandler.removeCallbacks(persistDraftRunnable)
        if (!draftStore.compareAndSetRecovery(expected, updated)) return false
        orderCommandInput.setText(updated.instruction)
        persistedComposerText = updated.instruction
        orderCommandInput.setSelection(updated.instruction.length)
        draftHandler.removeCallbacks(persistDraftRunnable)
        draftRestored = false
        return true
    }

    private fun beginScopedRecoveryVoice(result: (String?) -> Unit) {
        voiceInputController.cancel()
        scopedVoiceResult = result
        recoveryVoiceRequested = true
        recoveryVoiceEpoch += 1
        checkMicrophoneAndStartVoice()
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
        persistedComposerText = ""
        draftRestored = false
        return true
    }

    private fun submitOrderInstruction(rawInstruction: String, source: String) {
        retainedSwiggyOrderStatus = false
        recoveryVoiceRequested = false
        recoveryVoiceEpoch += 1
        // Continue must not turn an exact saved revision into a new request just
        // because an explicit phrase edit retained leading/trailing whitespace.
        val instruction = if (draftStore.loadRecovery()?.instruction == rawInstruction) rawInstruction else rawInstruction.trim()
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
        // Claude Design: keep the local draft intact, but put connection recovery first.
        val ready = connectionSession.canUseConnection
        orderComposerCard.visibility = if (ready) View.VISIBLE else View.GONE
        agentStatusText.visibility = if (ready) View.VISIBLE else View.GONE
        primaryNoteText.visibility = if (ready) View.VISIBLE else View.GONE
        if (ready) {
            updateSetupStatus(
                statusRes = R.string.swiggy_connection_ready,
                noteRes = R.string.swiggy_primary_note_ready,
            )
        }
        renderSwiggyConnectionPanel()
    }

    private fun renderSwiggyConnectionPanel(detailOverride: String? = null) {
        if (!::swiggyConnectionPanel.isInitialized) return
        if (detailOverride != null) connectionDetailOverride = detailOverride
        renderSwiggySelectedAddress(selectedSwiggyAddressLabel)
        findViewById<Button>(R.id.swiggyCheckoutAction).isEnabled =
            connectionSession.canUseConnection && !isSwiggyMutationInFlight() && !swiggyConnectionOperationInFlight
        val screen = connectionSession.screen(
            openingSwiggyBrowser,
            getSharedPreferences(CONNECTION_PREFERENCES, MODE_PRIVATE).getBoolean(CONNECTION_ATTEMPT_PENDING, false),
        )
        val busy = screen == SwiggyConnectionSession.Screen.CHECKING || screen == SwiggyConnectionSession.Screen.OPENING_BROWSER
        val ready = screen == SwiggyConnectionSession.Screen.CONNECTED
        if (!ready && screen != SwiggyConnectionSession.Screen.CHECKING) revealComposerAfterConnection = true
        swiggyConnectionAction.visibility = if (busy) View.GONE else View.VISIBLE
        swiggyConnectionProgress.visibility = if (busy) View.VISIBLE else View.GONE
        swiggyOfflineDemoAction.visibility = if (screen == SwiggyConnectionSession.Screen.FIRST_TIME) View.VISIBLE else View.GONE
        swiggyKeptNote.visibility = if (ready) View.GONE else View.VISIBLE
        swiggyKeptNote.text = when {
            selectedSwiggyAddressLabel == null -> getString(R.string.swiggy_connection_design_kept_no_address)
            orderCommandInput.text.isNullOrBlank() -> getString(R.string.swiggy_connection_design_kept_address, selectedSwiggyAddressLabel)
            else -> getString(R.string.swiggy_connection_design_kept_draft_address, selectedSwiggyAddressLabel)
        }
        when (screen) {
            SwiggyConnectionSession.Screen.CONNECTED -> {
                swiggyConnectionStatus.setText(R.string.swiggy_connection_ready)
                swiggyConnectionDetail.text = connectionDetailOverride
                    ?: getString(R.string.swiggy_connection_ready_detail)
                swiggyConnectionAction.setText(R.string.swiggy_connection_disconnect)
            }
            SwiggyConnectionSession.Screen.EXPIRED -> {
                swiggyConnectionStatus.setText(R.string.swiggy_connection_design_expired_title)
                swiggyConnectionDetail.setText(R.string.swiggy_connection_design_expired_detail)
                swiggyConnectionAction.setText(R.string.swiggy_connection_reconnect_action)
            }
            SwiggyConnectionSession.Screen.FIRST_TIME -> {
                swiggyConnectionStatus.setText(R.string.swiggy_connection_status)
                swiggyConnectionDetail.setText(R.string.swiggy_connection_detail)
                swiggyConnectionAction.setText(R.string.swiggy_connection_action)
            }
            SwiggyConnectionSession.Screen.CHECKING -> {
                swiggyConnectionStatus.setText(R.string.swiggy_connection_checking)
                swiggyConnectionDetail.setText(if (finishingSwiggySignIn) R.string.swiggy_connection_finishing else R.string.swiggy_connection_design_checking_detail)
            }
            SwiggyConnectionSession.Screen.OPENING_BROWSER -> {
                swiggyConnectionStatus.setText(R.string.swiggy_connection_connecting)
                swiggyConnectionDetail.setText(R.string.swiggy_connection_browser_detail)
            }
            SwiggyConnectionSession.Screen.SIGN_IN_PENDING -> {
                swiggyConnectionStatus.setText(R.string.swiggy_connection_design_pending_title)
                swiggyConnectionDetail.setText(R.string.swiggy_connection_design_pending_detail)
                swiggyConnectionAction.setText(R.string.swiggy_connection_design_pending_action)
            }
            SwiggyConnectionSession.Screen.RETRY -> {
                swiggyConnectionStatus.setText(if (retrySwiggySignIn) R.string.swiggy_connection_design_connect_retry_title else R.string.swiggy_connection_design_retry_title)
                swiggyConnectionDetail.setText(R.string.swiggy_connection_design_retry_detail)
                swiggyConnectionAction.setText(R.string.swiggy_connection_design_retry_action)
            }
        }
        swiggyConnectionAction.setBackgroundResource(if (ready) R.drawable.beta_btn_secondary else R.drawable.beta_btn_primary)
        swiggyConnectionAction.setTextColor(ContextCompat.getColor(this, if (ready) R.color.beta_text_primary else R.color.white))
        swiggyConnectionAction.minHeight = ((if (ready) 56 else 64) * resources.displayMetrics.density).toInt()
        swiggyConnectionAction.textSize = if (ready) 18f else 19f
        swiggyConnectionAction.isEnabled = !isSwiggyMutationInFlight() && !swiggyConnectionOperationInFlight
        swiggyConnectionAction.contentDescription = null // Speak its actual label, then the action hint.
        val actionHint = if (ready) getString(R.string.swiggy_connection_disconnect) else getString(
            if (screen == SwiggyConnectionSession.Screen.RETRY && !retrySwiggySignIn)
                R.string.swiggy_connection_design_retry_description else R.string.swiggy_connection_design_action_description,
        )
        ViewCompat.replaceAccessibilityAction(swiggyConnectionAction, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK, actionHint, null)
        if (lastConnectionScreen != screen) {
            lastConnectionScreen = screen
            if (!ready) {
                orderCommandInput.clearFocus()
                getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(orderCommandInput.windowToken, 0)
                focusSwiggyConnectionCard()
            } else if (revealComposerAfterConnection) {
                revealComposerAfterConnection = false
                orderCommandInput.clearFocus()
                mainScroll.post {
                    if (isFinishing || isDestroyed || !connectionSession.canUseConnection) return@post
                    mainScroll.smoothScrollTo(0, (orderComposerCard.top - 16 * resources.displayMetrics.density).toInt().coerceAtLeast(0))
                    focusForTalkBack(orderInputStatus)
                }
            }
        }
    }

    private fun focusSwiggyConnectionCard() {
        mainScroll.post {
            if (isFinishing || isDestroyed || connectionSession.canUseConnection) return@post
            mainScroll.scrollTo(0, (swiggyConnectionPanel.top - 16 * resources.displayMetrics.density).toInt().coerceAtLeast(0))
            focusForTalkBack(swiggyConnectionStatus)
        }
    }

    private fun focusForTalkBack(view: View) {
        if (getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true) {
            // Accessibility focus reads the current text once; no duplicate live-region announcement.
            view.performAccessibilityAction(AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS, null)
        }
    }

    private fun handleSwiggyVoiceInstruction(instruction: String) {
        if (SwiggyCartMutationGuard.isInFlight()) {
            announceSwiggy(getString(R.string.swiggy_cart_update_in_progress))
            return
        }
        if (!connectionSession.canUseConnection) {
            pendingSwiggyInstruction = null
            resumeSwiggyOrderAfterStatus = false
            configurePrimaryExperience()
            focusSwiggyConnectionCard()
            return // Saved locally only; authentication is never submission consent.
        }
        pendingSwiggyInstruction = instruction
        Log.i("BetaAgent", "SWIGGY_MCP_VOICE_INSTRUCTION_RECEIVED")
        refreshSwiggyConnectionStatus(resumePendingOrder = true)
    }

    private fun refreshSwiggyConnectionStatus(resumePendingOrder: Boolean = false) {
        if (!::swiggyConnectionStatus.isInitialized) return
        if (resumePendingOrder) resumeSwiggyOrderAfterStatus = true
        if (swiggyConnectionOperationInFlight) return
        val statusRequest = connectionSession.beginStatus() ?: return
        val requestGeneration = swiggyMcpRequestGeneration
        connectionDetailOverride = null
        retrySwiggySignIn = false
        Log.i(
            "BetaAgent",
            "SWIGGY_MCP_STATUS_REQUEST_STARTED generation=$requestGeneration resumePending=$resumePendingOrder",
        )
        configurePrimaryExperience()
        SwiggyMcpClient.fetchStatus(this) { result ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (!connectionSession.settleStatus(statusRequest)) {
                    Log.i("BetaAgent", "SWIGGY_MCP_STATUS_IGNORED_STALE")
                    return@runOnUiThread
                }
                val shouldResumePendingOrder = resumeSwiggyOrderAfterStatus
                resumeSwiggyOrderAfterStatus = false
                finishingSwiggySignIn = false
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
                        showSwiggyConnectionFailure(result)
                        if (shouldResumePendingOrder && result.reconnectRequired) promptToConnectSwiggy()
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
            swiggyOrderCoordinator.requireRememberedAddressRevalidation()
        }
        connectionSession.confirm(state)
        connectionDetailOverride = detailOverride
        configurePrimaryExperience()
        if (state == SwiggyMcpClient.ConnectionState.READY) {
            val connectionPreferences = getSharedPreferences(CONNECTION_PREFERENCES, MODE_PRIVATE)
            if (connectionPreferences.getBoolean(CONNECTION_ATTEMPT_PENDING, false)) {
                BetaTelemetry.instance?.logEvent("swiggy_connect_completed")
                connectionPreferences.edit().putBoolean(CONNECTION_ATTEMPT_PENDING, false).apply()
                if (!orderCommandInput.text.isNullOrBlank()) orderInputStatus.setText(R.string.swiggy_connection_design_reconnect_ready)
            }
            if (previousState != state) {
                BetaTelemetry.instance?.maybeLogOnboardingCompleted()
                showAnalyticsConsentDialog(force = false)
            }
        }
    }

    private fun startSwiggyConnection() {
        if (swiggyConnectionOperationInFlight || isSwiggyMutationInFlight()) return
        pendingSwiggyInstruction = null
        resumeSwiggyOrderAfterStatus = false
        pendingLocationSwiggyInstruction = null
        swiggyOrderCoordinator.requireRememberedAddressRevalidation()
        connectionSession.invalidate()
        swiggyConnectionOperationInFlight = true
        openingSwiggyBrowser = true
        retrySwiggySignIn = false
        BetaTelemetry.instance?.logEvent("swiggy_connect_started")
        getSharedPreferences(CONNECTION_PREFERENCES, MODE_PRIVATE)
            .edit()
            .putBoolean(CONNECTION_ATTEMPT_PENDING, true)
            .apply()
        val requestGeneration = swiggyMcpRequestGeneration
        configurePrimaryExperience()
        SwiggyMcpClient.connect(this) { result ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (requestGeneration != swiggyMcpRequestGeneration) {
                    Log.i("BetaAgent", "SWIGGY_MCP_CONNECT_IGNORED_STALE")
                    return@runOnUiThread
                }
                swiggyConnectionOperationInFlight = false
                openingSwiggyBrowser = false
                when (result) {
                    is SwiggyMcpResult.Success -> {
                        val authUrl = result.value.authorizationUrl
                        if (authUrl.isNullOrBlank() || !openTrustedSwiggyAuthorization(authUrl)) {
                            BetaTelemetry.instance?.logEvent(
                                "swiggy_connect_failed",
                                mapOf("reason" to "invalid_authorization_link"),
                            )
                            clearPendingConnectionAttempt()
                            retrySwiggySignIn = true
                            showSwiggyConnectionFailure(SwiggyMcpResult.Failure("Sign-in unavailable"))
                        }
                    }
                    is SwiggyMcpResult.Failure -> {
                        BetaTelemetry.instance?.logEvent(
                            "swiggy_connect_failed",
                            mapOf("reason" to if (result.reconnectRequired) "reconnect_required" else "backend_failure"),
                        )
                        clearPendingConnectionAttempt()
                        retrySwiggySignIn = true
                        showSwiggyConnectionFailure(result)
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
        // A browser callback requests a fresh server check; it is not proof of login.
        connectionSession.invalidate()
        swiggyConnectionOperationInFlight = false
        openingSwiggyBrowser = false
        pendingSwiggyInstruction = null
        resumeSwiggyOrderAfterStatus = false
        pendingLocationSwiggyInstruction = null
        swiggyOrderCoordinator.requireRememberedAddressRevalidation()
        if (data.getQueryParameter("status") == "connected") {
            finishingSwiggySignIn = true
            refreshSwiggyConnectionStatus(resumePendingOrder = false)
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
        // A new authored handoff replaces unconfirmed preparation, including a
        // retained failed-save editor, BEFORE onResume can reopen the old one.
        if (isSwiggyMutationInFlight() || swiggyCheckoutCoordinator.isActive() || !swiggyOrderCoordinator.cancel()) {
            announceSwiggy("Please finish checking your existing order before starting another cart.")
            return
        }
        val epoch = ++composerInstructionEpoch
        orderCommandInput.post {
            if (epoch != composerInstructionEpoch || isFinishing || isDestroyed) return@post
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
        if (!connectionSession.canUseConnection) return
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
        if (!isFinishing && !isDestroyed && connectionSession.canUseConnection) {
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
                connectionSession.invalidate()
                swiggyConnectionOperationInFlight = true
                pendingSwiggyInstruction = null
                resumeSwiggyOrderAfterStatus = false
                val requestGeneration = swiggyMcpRequestGeneration
                swiggyConnectionAction.isEnabled = false
                SwiggyMcpClient.disconnect(this) { result ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (requestGeneration != swiggyMcpRequestGeneration) {
                            Log.i("BetaAgent", "SWIGGY_MCP_DISCONNECT_IGNORED_STALE")
                            return@runOnUiThread
                        }
                        swiggyConnectionOperationInFlight = false
                        when (result) {
                            is SwiggyMcpResult.Success -> {
                                clearPendingConnectionAttempt()
                                swiggyOrderCoordinator.clearRememberedAddress()
                                updateSwiggyConnectionUi(SwiggyMcpClient.ConnectionState.DISCONNECTED)
                            }
                            is SwiggyMcpResult.Failure -> showSwiggyConnectionFailure(result)
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
        connectionSession.invalidate()
        swiggyConnectionOperationInFlight = false
        resumeSwiggyOrderAfterStatus = false
        pendingSwiggyInstruction = null
        pendingLocationSwiggyInstruction = null
        swiggyConnectPromptShowing = false
        swiggyConnectPrompt?.dismiss()
        swiggyConnectPrompt = null
        swiggyLocationPrompt?.dismiss()
        swiggyLocationPrompt = null
        if (::swiggyOrderCoordinator.isInitialized) {
            if (reason == "activity_destroyed" && isChangingConfigurations) swiggyOrderCoordinator.detachForConfigurationChange()
            else swiggyOrderCoordinator.cancel()
            swiggyOrderCoordinator.requireRememberedAddressRevalidation()
        }
        Log.i("BetaAgent", "SWIGGY_MCP_PENDING_WORK_CANCELLED reason=$reason")
    }

    private fun updateSwiggyMutationControls(inFlight: Boolean) {
        if (::swiggyConnectionAction.isInitialized) {
            renderSwiggyConnectionPanel()
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
        // Shopping feedback must not overwrite authentication instructions or cover
        // the basket/address actions with a second, long toast.
        if (::orderInputStatus.isInitialized) orderInputStatus.text = message
        val accessibility = getSystemService(AccessibilityManager::class.java)
        if (!retainedSwiggyOrderStatus || accessibility?.isTouchExplorationEnabled != true) speak(message)
    }

    private fun showSwiggyConnectionFailure(result: SwiggyMcpResult.Failure) {
        pendingSwiggyInstruction = null
        resumeSwiggyOrderAfterStatus = false
        swiggyOrderCoordinator.requireRememberedAddressRevalidation()
        if (result.reconnectRequired) {
            clearPendingConnectionAttempt()
            updateSwiggyConnectionUi(SwiggyMcpClient.ConnectionState.RECONNECT_REQUIRED)
        } else {
            connectionSession.fail(reconnectRequired = false)
            connectionDetailOverride = null
            configurePrimaryExperience()
        }
    }

    private fun renderSwiggySelectedAddress(label: String?) {
        selectedSwiggyAddressLabel = label?.trim()?.takeIf { it.isNotBlank() }
        if (!::swiggySelectedAddress.isInitialized || !::swiggyChangeAddressAction.isInitialized) return
        val visible = selectedSwiggyAddressLabel != null &&
            connectionSession.canUseConnection
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

    private fun showAppSettings() {
        if (swiggyOrderCoordinator.isActive() || swiggyCheckoutCoordinator.isActive()) return
        AlertDialog.Builder(this)
            .setTitle("Beta settings")
            .setItems(arrayOf("Usage analytics", if (GroceryIntentPreferences.eligiblePublicOptionAudience() != null ||
                GroceryIntentPreferences.isInterpretedTestAudience())
                getString(R.string.matching_settings_title) else "Enhanced list understanding")) { _, which ->
                if (which == 0) showAnalyticsConsentDialog(force = true) else showIntentSettings()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showIntentSettings() {
        val internalTest = GroceryIntentPreferences.isInterpretedTestAudience()
        if (internalTest || GroceryIntentPreferences.eligiblePublicOptionAudience() != null) {
            PublicMatchingSettingsDialog(
                this,
                read = { GroceryIntentPreferences.getSelection(this) },
                allow = { GroceryIntentPreferences.savePublicOpenAiConsent(this) },
                useStandard = { GroceryIntentPreferences.setMode(this, GroceryIntentPreferences.Mode.OFF) },
                internalAllow = if (internalTest) { mode -> GroceryIntentPreferences.setInterpretedDevelopmentMode(this, mode) } else null,
            ).show()
            return
        }
        val modes = GroceryIntentPreferences.availableModes()
        val labels = modes.map { mode ->
            when (mode) {
                GroceryIntentPreferences.Mode.OFF -> "Off — standard matching"
                GroceryIntentPreferences.Mode.GEMINI -> "Gemini — limited preview"
                GroceryIntentPreferences.Mode.AUTO -> "Automatic — development"
                GroceryIntentPreferences.Mode.DEEPSEEK -> "DeepSeek — development"
                GroceryIntentPreferences.Mode.OPENAI -> "OpenAI — development"
            }
        }.toTypedArray()
        var selected = modes.indexOf(GroceryIntentPreferences.getMode(this))
        AlertDialog.Builder(this)
            .setTitle("Enhanced list understanding")
            .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
            .setPositiveButton("Continue") { _, _ ->
                val chosen = modes[selected]
                if (chosen == GroceryIntentPreferences.Mode.OFF) {
                    GroceryIntentPreferences.setMode(this, chosen)
                    return@setPositiveButton
                }
                AlertDialog.Builder(this)
                    .setTitle("Use enhanced understanding?")
                    .setMessage("This optional preview is currently limited to approved connections. Your typed or transcribed shopping request will be sent securely through Beta to the selected AI provider. Beta does not automatically include saved addresses, GPS or Swiggy credentials. Anything you include in your request will be sent, so leave out personal details. You will still review the basket before adding anything. You can turn this off here at any time.")
                    .setPositiveButton("Enable") { _, _ -> GroceryIntentPreferences.setMode(this, chosen) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        }
        if (::primaryNoteText.isInitialized) {
            primaryNoteText.setText(noteRes)
        }
    }

    companion object {
        private const val RETAINED_ORDER_STATUS = "retained_order_status"
        private const val RECOVERY_VOICE_STOPPED = "Voice input stopped. Your typed list is saved below. Tap the microphone to try again, or tap Continue."
        private const val RECOVERY_VOICE_PERMISSION = "Beta can't use the microphone right now. Your typed list is saved below. You can change it and tap Continue."
        private const val RECOVERY_VOICE_UNAVAILABLE = "Voice input isn't working right now. Your typed list is saved below. You can change it and tap Continue."
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
