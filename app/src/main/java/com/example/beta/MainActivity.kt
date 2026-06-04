package com.example.beta

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.util.DisplayMetrics
import android.util.Log
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private val screenCaptureRequestCode = 100
    private var isCapturing = false // Track capture state

    // Declare the screenCaptureResult as a lateinit var
    private lateinit var screenCaptureResult: ActivityResultLauncher<Intent>
    private lateinit var voiceInputResult: ActivityResultLauncher<Intent>
    private lateinit var microphonePermissionResult: ActivityResultLauncher<String>
    private var textToSpeech: TextToSpeech? = null

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

        // Get MediaProjectionManager
        mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.language = Locale("en", "IN")
            }
        }

        // Initialize ActivityResultLauncher
        screenCaptureResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                Log.d("MainActivity", "Media projection successful, starting service")

                // Get display metrics here to pass it along to the service
                val displayMetrics = DisplayMetrics()
                windowManager.defaultDisplay.getMetrics(displayMetrics)
                
                // Also get real metrics for comparison (includes status bar and navigation bar)
                val realMetrics = DisplayMetrics()
                windowManager.defaultDisplay.getRealMetrics(realMetrics)
                
                // Log dimension comparison for scaling hypothesis verification
                Log.i("MainActivity", "Display Metrics (getMetrics): ${displayMetrics.widthPixels}x${displayMetrics.heightPixels}")
                Log.i("MainActivity", "Real Metrics (getRealMetrics): ${realMetrics.widthPixels}x${realMetrics.heightPixels}")
                
                val widthDiff = realMetrics.widthPixels - displayMetrics.widthPixels
                val heightDiff = realMetrics.heightPixels - displayMetrics.heightPixels
                
                if (widthDiff != 0 || heightDiff != 0) {
                    Log.w("MainActivity", "DIMENSION MISMATCH: Width diff: ${widthDiff}px, Height diff: ${heightDiff}px")
                    Log.w("MainActivity", "This could cause scaling issues in screenshots!")
                } else {
                    Log.i("MainActivity", "Dimensions match - no scaling issues expected")
                }

                // Start the ScreenCaptureService with the metrics after receiving result from projection intent
                val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    putExtra("resultCode", result.resultCode)
                    putExtra("resultData", result.data)
                    putExtra("width", displayMetrics.widthPixels)
                    putExtra("height", displayMetrics.heightPixels)
                    putExtra("density", displayMetrics.densityDpi)
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
            } else {
                Log.e("MainActivity", "Media projection failed")
                Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
                isCapturing = false
                updateSetupStatus(
                    statusRes = R.string.main_status_permission_needed,
                    noteRes = R.string.main_primary_note,
                    actionRes = R.string.main_primary_action
                )
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
        }

        // Set click listener for the capture screen button
        captureScreenButton.setOnClickListener {
            checkPermissionsAndStartCapture()
        }

        // Set click listener for the text recognition button
        textRecognitionButton.setOnClickListener {
            val intent = Intent(this, TextRecognitionActivity::class.java)
            startActivity(intent)
        }

        voiceOrderButton.setOnClickListener {
            checkMicrophoneAndStartVoice()
        }

        feedbackWorkedButton.setOnClickListener {
            sendFeedback("worked", "order_flow")
        }

        feedbackIssueButton.setOnClickListener {
            sendFeedback("did_not_work", "order_flow")
        }

        // AutomatedActionTestActivity removed - not available in current version

        (application as MyApplication).registerActivity(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        textToSpeech?.shutdown()
        textToSpeech = null
        (application as MyApplication).unregisterActivity(this)
    }

    private fun speak(message: String) {
        textToSpeech?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "beta_voice_prompt")
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
        if (isOpenCommerceAppInstruction(instruction)) {
            val launchResult = CommerceAppLauncher.launchPreferred(this)
            speak(launchResult.message)
            Toast.makeText(
                this,
                launchResult.message,
                if (launchResult.launched) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
            ).show()
            return
        }

        val service = (application as MyApplication).getScreenCaptureService()
        if (service == null) {
            speak(getString(R.string.voice_start_capture_first))
            Toast.makeText(this, getString(R.string.voice_start_capture_first), Toast.LENGTH_LONG).show()
            return
        }
        Log.i("BetaAgent", "VOICE_INSTRUCTION_RECOGNIZED: $instruction")
        val launchResult = CommerceAppLauncher.launchPreferred(this)
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

    private fun isOpenCommerceAppInstruction(instruction: String): Boolean {
        val normalized = instruction
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isBlank()) {
            return false
        }
        return normalized in setOf(
            "open blinkit",
            "launch blinkit",
            "start blinkit",
            "open blinkit app",
            "open zepto",
            "launch zepto",
            "start zepto",
            "open zepto app",
            "open grocery app",
            "launch grocery app",
        )
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
            .setNegativeButton(R.string.automation_disclosure_cancel, null)
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
            .setNegativeButton(R.string.automation_disclosure_cancel, null)
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
        val projectionIntent = mediaProjectionManager.createScreenCaptureIntent()  // No need for safe call
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
        private const val STORAGE_PERMISSION_CODE = 101
    }
}
