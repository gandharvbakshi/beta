package com.example.beta

import android.app.*
import android.app.Activity.RESULT_OK
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.IOException
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class ScreenCaptureService : Service() {

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var handler: Handler? = null
    private var handlerThread: HandlerThread? = null // Keep reference to thread
    private var width: Int = 0
    private var height: Int = 0
    private var density: Int = 0
    private var resultCode = 0
    private var resultData: Intent? = null
    private var isCapturing = false
    // Session management
    private var currentSession: SessionContext? = null
    private var retryAttempts = 0
    private var consecutiveFailures = 0
    // Overlay window variables
    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var layoutParams: WindowManager.LayoutParams
    
    // Input overlay variables
    private var inputOverlayView: View? = null
    private var inputOverlayParams: WindowManager.LayoutParams? = null
    private val CHANNEL_ID = "ScreenCaptureServiceChannel"
    private val NOTIFICATION_ID = 1
    private var foregroundStarted = false
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null // Correct type
    private var accessibilityService: MyAccessibilityService? = null
    private var currentInputText: String? = null
    private var pendingScreenshot = false
    private var screenshotEnabled = false // Only take screenshots when explicitly enabled
    private var overlayBounds: android.graphics.Rect? = null
    private var currentTreeData: String? = null
    private var currentAppName: String? = null
    
    // Sequential action support
    private var isActionSequenceActive: Boolean = false
    private var originalInputText: String? = null

    // Check if running on emulator
    private fun isEmulator(): Boolean {
        val isEmu = (Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
                || "google_sdk" == Build.PRODUCT)
        
        Log.d("ScreenCaptureService", "Device detection - isEmulator: $isEmu")
        Log.d("ScreenCaptureService", "Build info - FINGERPRINT: ${Build.FINGERPRINT}, MODEL: ${Build.MODEL}, MANUFACTURER: ${Build.MANUFACTURER}")
        
        return isEmu
    }
    
    // Check if screen capture is supported
    private fun isScreenCaptureSupported(): Boolean {
        return try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            manager != null
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Error checking screen capture support: ${e.message}", e)
            false
        }
    }

    // Emulator-specific initialization
    private fun initializeForEmulator() {
        Log.d("ScreenCaptureService", "Running on emulator, using emulator-specific settings")
        
        // Use more conservative settings for emulator
        if (width <= 0 || height <= 0) {
            // Use default emulator dimensions if not provided
            width = 1080
            height = 1920
            density = 420
            Log.d("ScreenCaptureService", "Using default emulator dimensions: $width x $height @ $density dpi")
        }
        
        // Reduce buffer size for emulator
        try {
            if (imageReader != null) {
                imageReader?.close()
            }
            imageReader = ImageReader.newInstance(
                width, height, PixelFormat.RGB_565, 1 // Reduced buffer size for emulator
            )
            imageReader?.setOnImageAvailableListener(imageAvailableListener, handler)
            Log.d("ScreenCaptureService", "ImageReader created for emulator with RGB_565 format")
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Failed to create ImageReader for emulator: ${e.message}", e)
        }
    }

    // Emulator-specific receiver registration
    private fun registerEmulatorReceiver() {
        try {
            // Register local broadcast receiver for input text
            LocalBroadcastManager.getInstance(this).registerReceiver(
                inputReceiver,
                IntentFilter("com.example.beta.INPUT_RECEIVED")
            )
            // Register receiver for next action trigger
            LocalBroadcastManager.getInstance(this).registerReceiver(
                nextActionReceiver,
                IntentFilter("com.example.beta.TRIGGER_NEXT_ACTION")
            )
            Log.d("ScreenCaptureService", "🔍 DEBUG: Emulator broadcast receivers registered successfully")
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Error registering emulator receiver: ${e.message}", e)
        }
    }

    // Emulator-specific handler thread creation
    private fun createEmulatorHandlerThread() {
        try {
            // Create handler for image reader with emulator-specific settings
            handlerThread = HandlerThread("EmulatorScreenCaptureThread").apply {
                start() // Start the thread
            }
            handler = Handler(handlerThread!!.looper) // Use the thread's looper
            Log.d("ScreenCaptureService", "Emulator handler thread created successfully")
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Error creating emulator handler thread: ${e.message}", e)
        }
    }

    // Emulator-specific media projection manager initialization
    private fun initializeEmulatorMediaProjectionManager() {
        try {
            mediaProjectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            Log.d("ScreenCaptureService", "Emulator media projection manager initialized successfully")
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Error initializing emulator media projection manager: ${e.message}", e)
        }
    }

    // Emulator-specific overlay window creation
    private fun createEmulatorOverlayWindow() {
        try {
            // Create overlay window for input with error handling
            try {
                createOverlayWindow()
            } catch (e: Exception) {
                Log.e("ScreenCaptureService", "Failed to create emulator overlay window: ${e.message}", e)
                // Continue without overlay - this is not critical for basic functionality
            }
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Error in emulator overlay window creation: ${e.message}", e)
        }
    }

    // Emulator-specific application service setting
    private fun setEmulatorApplicationService() {
        try {
            // Pass the service instance to the Application class
            (application as? MyApplication)?.setScreenCaptureService(this)
            Log.d("ScreenCaptureService", "Emulator ScreenCaptureService instance set in MyApplication")
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Error setting emulator application service: ${e.message}", e)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("ScreenCaptureService", "Service created")
        
        // Check if screen capture is supported
        if (!isScreenCaptureSupported()) {
            Log.e("ScreenCaptureService", "Screen capture not supported on this device")
            Toast.makeText(this, "Screen capture not supported on this device", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        
        try {
            // Create handler for image reader
            if (isEmulator()) {
                createEmulatorHandlerThread()
                initializeEmulatorMediaProjectionManager() // Initialize for emulator
            } else {
                handlerThread = HandlerThread("ScreenCaptureThread").apply {
                    start() // Start the thread
                }
                handler = Handler(handlerThread!!.looper) // Use the thread's looper
                
                // Initialize MediaProjectionManager for non-emulator
                mediaProjectionManager =
                    getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            }
            
            // Create overlay window for input with error handling
            if (isEmulator()) {
                createEmulatorOverlayWindow()
            } else {
                try {
                    createOverlayWindow()
                } catch (e: Exception) {
                    Log.e("ScreenCaptureService", "Failed to create overlay window: ${e.message}", e)
                    // Continue without overlay - this is not critical for basic functionality
                }
            }
            
            // Create notification channel
            if (isEmulator()) {
                createEmulatorNotificationChannel()
            } else {
                createNotificationChannel()
            }
            
            // Pass the service instance to the Application class
            if (isEmulator()) {
                setEmulatorApplicationService()
            } else {
                (application as? MyApplication)?.setScreenCaptureService(this)
                Log.d("ScreenCaptureService", "ScreenCaptureService instance set in MyApplication")
            }

            // Try to connect to accessibility service if it's available
            Handler(Looper.getMainLooper()).postDelayed({
                // Try to connect to accessibility service if it's available
                val accessibilityService = (application as? MyApplication)?.getAccessibilityService()
                if (accessibilityService != null) {
                    accessibilityService.connectScreenCaptureService(this)
                    Log.d("ScreenCaptureService", "Connected to existing AccessibilityService")
                } else {
                    Log.d("ScreenCaptureService", "No AccessibilityService available yet - will connect when it becomes available")
                    // Try again after another delay
                    Handler(Looper.getMainLooper()).postDelayed({
                        val retryAccessibilityService = (application as? MyApplication)?.getAccessibilityService()
                        if (retryAccessibilityService != null) {
                            retryAccessibilityService.connectScreenCaptureService(this)
                            Log.d("ScreenCaptureService", "Connected to AccessibilityService on retry")
                        } else {
                            Log.d("ScreenCaptureService", "AccessibilityService still not available after retry")
                        }
                    }, 3000) // 3 second retry delay
                }
            }, 1000) // 1 second delay to ensure accessibility service has time to start

            // Register local broadcast receiver for input text
            if (isEmulator()) {
                registerEmulatorReceiver()
            } else {
                LocalBroadcastManager.getInstance(this).registerReceiver(
                    inputReceiver,
                    IntentFilter("com.example.beta.INPUT_RECEIVED")
                )
                // Register receiver for next action trigger
                LocalBroadcastManager.getInstance(this).registerReceiver(
                    nextActionReceiver,
                    IntentFilter("com.example.beta.TRIGGER_NEXT_ACTION")
                )
                Log.d("ScreenCaptureService", "🔍 DEBUG: Broadcast receivers registered successfully")
                
                // Commented out: test broadcast that could inadvertently seed sequences with "test"
                // Keeping disabled to avoid unintended backend pings and input_text contamination.
                // If needed for local debugging, guard with BuildConfig.DEBUG and ensure receiver ignores action_number==999.
                // val testIntent = android.content.Intent("com.example.beta.TRIGGER_NEXT_ACTION")
                // testIntent.putExtra("original_input", "test")
                // testIntent.putExtra("action_number", 999)
                // LocalBroadcastManager.getInstance(this).sendBroadcast(testIntent)
                // Log.d("ScreenCaptureService", "🔍 DEBUG: Test broadcast sent to verify receiver")
            }
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Error in onCreate: ${e.message}", e)
            // If we can't initialize properly, stop the service
            stopSelf()
        }
    }

    private val inputReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.beta.INPUT_RECEIVED") {
                currentInputText = intent.getStringExtra("input_text")
                // Log.d("ScreenCaptureService", "Received input text: $currentInputText")
            }
        }
    }
    
    private val nextActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d("ScreenCaptureService", "🔍 DEBUG: Broadcast received - Action: '${intent?.action}', Intent: ${intent}")
            
            if (intent?.action == "com.example.beta.TRIGGER_NEXT_ACTION") {
                val originalInput = intent.getStringExtra("original_input")
                val actionNumber = intent.getIntExtra("action_number", 0)
                Log.d("ScreenCaptureService", "🔍 DEBUG: Received next action trigger - Action #$actionNumber for: '$originalInput'")
                
                if (originalInput != null) {
                    Log.d("ScreenCaptureService", "🔍 DEBUG: Triggering next action in sequence...")
                    triggerNextActionInSequence(originalInput, actionNumber)
                } else {
                    Log.w("ScreenCaptureService", "🔍 DEBUG: Original input is null, cannot trigger next action")
                }
            } else {
                Log.d("ScreenCaptureService", "🔍 DEBUG: Broadcast received but not for TRIGGER_NEXT_ACTION: ${intent?.action}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("ScreenCaptureService", "onStartCommand received")
        
        try {
            // If intent is null and service is already running, just continue
            if (intent == null) {
                if (isCapturing) {
                    Log.d("ScreenCaptureService", "Intent is null but service is already capturing. Continuing.")
                    return START_STICKY
                } else {
                    Log.w("ScreenCaptureService", "Intent is null and service not capturing. Ignoring restart without tearing down.")
                    return START_NOT_STICKY
                }
            }

            // Only process intent data if we're not already capturing
            if (!isCapturing) {
                resultCode = intent.getIntExtra("resultCode", 0)
                resultData = intent.getParcelableExtra("resultData")

                // Get the width, height, and density from the Intent
                width = intent.getIntExtra("width", 0)
                height = intent.getIntExtra("height", 0)
                density = intent.getIntExtra("density", 0)

                // Log received data for debugging
                Log.d("ScreenCaptureService", "Received data - resultCode: $resultCode, width: $width, height: $height, density: $density")
                Log.d("ScreenCaptureService", "resultData: $resultData")
                
                // Check result code first
                if (resultCode != RESULT_OK) {
                    Log.e("ScreenCaptureService", "Invalid result code: $resultCode (expected: $RESULT_OK)")
                    Toast.makeText(this, "Screen capture permission denied (result code: $resultCode)", Toast.LENGTH_LONG).show()
                    stopSelf()
                    return START_NOT_STICKY
                }
                
                // Check result data
                if (resultData == null) {
                    Log.e("ScreenCaptureService", "Result data is null")
                    Toast.makeText(this, "Invalid media projection data received", Toast.LENGTH_LONG).show()
                    stopSelf()
                    return START_NOT_STICKY
                }
                
                // Validate dimensions
                if (width <= 0 || height <= 0) {
                    Log.e("ScreenCaptureService", "Invalid dimensions: ${width}x${height}")
                    if (isEmulator()) {
                        Log.w("ScreenCaptureService", "Using fallback dimensions for emulator")
                        width = 1080
                        height = 1920
                        density = 420
                    } else {
                        Toast.makeText(this, "Invalid screen dimensions: ${width}x${height}", Toast.LENGTH_SHORT).show()
                        stopSelf()
                        return START_NOT_STICKY
                    }
                }

                Log.d("ScreenCaptureService", "All validation passed. Starting capture process.")
                showForegroundNotification()  // Show notification before starting capture
                startCapture() // Start the capture process
            } else {
                Log.d("ScreenCaptureService", "Service already capturing, ignoring new intent")
            }
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Error in onStartCommand: ${e.message}", e)
            Toast.makeText(this, "Error starting capture service: ${e.message}", Toast.LENGTH_SHORT).show()
            stopSelf()
            return START_NOT_STICKY
        }

        return START_STICKY
    }


    private fun startCapture() {
        Log.d("ScreenCaptureService", "Attempting to start capture...")
        
        try {
            // Ensure we have valid projection data
            if (resultCode == 0 || resultData == null) {
                Log.e("ScreenCaptureService", "Cannot start capture: Missing result code or data.")
                stopSelf()
                return
            }

            // Check if already capturing
            if (isCapturing) {
                Log.w("ScreenCaptureService", "Capture already in progress.")
                return
            }

            Log.d("ScreenCaptureService", "Screen dimensions: $width x $height @ $density dpi")

            // Validate dimensions
            if (width <= 0 || height <= 0) {
                if (isEmulator()) {
                    Log.w("ScreenCaptureService", "Invalid dimensions on emulator, using emulator-specific initialization")
                    initializeForEmulator()
                } else {
                    Log.e("ScreenCaptureService", "Invalid screen dimensions: $width x $height")
                    Toast.makeText(this, "Invalid screen dimensions", Toast.LENGTH_SHORT).show()
                    stopSelf()
                    return
                }
            }

            // Get MediaProjection instance
            // Check if mediaProjection is already obtained and valid
            if (mediaProjection == null) {
                try {
                    Log.d("ScreenCaptureService", "Attempting to get MediaProjection with resultCode: $resultCode")
                    
                    if (mediaProjectionManager == null) {
                        Log.e("ScreenCaptureService", "MediaProjectionManager is null")
                        Toast.makeText(this, "MediaProjectionManager not available", Toast.LENGTH_SHORT).show()
                        stopSelf()
                        return
                    }
                    
                    if (resultData == null) {
                        Log.e("ScreenCaptureService", "Result data is null")
                        Toast.makeText(this, "Invalid media projection data", Toast.LENGTH_SHORT).show()
                        stopSelf()
                        return
                    }
                    
                    mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, resultData!!)
                    if (mediaProjection == null) {
                        Log.e("ScreenCaptureService", "Failed to get MediaProjection. ResultCode: $resultCode, ResultData: $resultData")
                        Log.e("ScreenCaptureService", "MediaProjectionManager: $mediaProjectionManager")
                        Log.e("ScreenCaptureService", "Is Emulator: ${isEmulator()}")
                        
                        // Provide more specific error messages
                        val errorMessage = if (isEmulator()) {
                            "Screen capture may not be fully supported on this emulator. Try using a physical device or a different emulator version."
                        } else {
                            "Failed to initialize screen capture. Please try again or restart the app."
                        }
                        
                        // Try a delay and retry once for emulator compatibility
                        Handler(Looper.getMainLooper()).postDelayed({
                            try {
                                Log.d("ScreenCaptureService", "Retrying MediaProjection creation after delay...")
                                mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, resultData!!)
                                if (mediaProjection != null) {
                                    Log.d("ScreenCaptureService", "MediaProjection obtained successfully on retry.")
                                    mediaProjection?.registerCallback(mediaProjectionCallback, handler)
                                    // Continue with capture setup
                                    continueWithCapture()
                                } else {
                                    Log.e("ScreenCaptureService", "MediaProjection retry failed. This might be an emulator limitation.")
                                    Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
                                    stopSelf()
                                }
                            } catch (e: Exception) {
                                Log.e("ScreenCaptureService", "Error on MediaProjection retry: ${e.message}", e)
                                Toast.makeText(this, "Screen capture failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                stopSelf()
                            }
                        }, 1000) // 1 second delay
                        
                        return // Exit here to wait for retry
                    }
                    Log.d("ScreenCaptureService", "MediaProjection obtained successfully.")
                    // Register callback for stopping projection
                    mediaProjection?.registerCallback(mediaProjectionCallback, handler)
                } catch (e: SecurityException) {
                    Log.e("ScreenCaptureService", "SecurityException getting MediaProjection: ${e.message}", e)
                    Toast.makeText(this, "Permission denied for screen capture: ${e.message}", Toast.LENGTH_LONG).show()
                    stopSelf()
                    return
                } catch (e: Exception) {
                    Log.e("ScreenCaptureService", "Error getting MediaProjection: ${e.message}", e)
                    Toast.makeText(this, "Error initializing screen capture: ${e.message}", Toast.LENGTH_SHORT).show()
                    stopSelf()
                    return
                }
            }

            // Create ImageReader
            // Close existing ImageReader if it exists
            imageReader?.close()
            try {
                createImageReader() // Create or recreate ImageReader
                if (imageReader == null) {
                    Log.e("ScreenCaptureService", "Failed to create ImageReader")
                    Toast.makeText(this, "Failed to create image reader", Toast.LENGTH_SHORT).show()
                    stopSelf()
                    return
                }
            } catch (e: Exception) {
                Log.e("ScreenCaptureService", "Error creating ImageReader: ${e.message}", e)
                Toast.makeText(this, "Error creating image reader: ${e.message}", Toast.LENGTH_SHORT).show()
                stopSelf()
                return
            }

            // Create VirtualDisplay
            // Release existing virtual display if it exists
            virtualDisplay?.release()
            try {
                startVirtualDisplay() // Create or recreate VirtualDisplay
                if (virtualDisplay == null) {
                    Log.e("ScreenCaptureService", "Failed to create VirtualDisplay")
                    Toast.makeText(this, "Failed to create virtual display", Toast.LENGTH_SHORT).show()
                    stopCapture() // Clean up resources if virtual display fails
                    stopSelf()
                    return
                }
            } catch (e: Exception) {
                Log.e("ScreenCaptureService", "Error creating VirtualDisplay: ${e.message}", e)
                Toast.makeText(this, "Error creating virtual display: ${e.message}", Toast.LENGTH_SHORT).show()
                stopCapture() // Clean up resources if virtual display fails
                stopSelf()
                return
            }

            if (virtualDisplay != null) {
                isCapturing = true
                Log.d("ScreenCaptureService", "Screen capture started successfully.")
                Toast.makeText(this, "Screen capture started", Toast.LENGTH_SHORT).show()
            } else {
                Log.e("ScreenCaptureService", "Failed to create VirtualDisplay. Stopping capture.")
                stopCapture() // Clean up resources if virtual display fails
                stopSelf()
            }
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Unexpected error in startCapture: ${e.message}", e)
            Toast.makeText(this, "Unexpected error starting capture: ${e.message}", Toast.LENGTH_SHORT).show()
            stopCapture()
            stopSelf()
        }
    }

    // Continue with capture after MediaProjection retry
    private fun continueWithCapture() {
        try {
            // Create ImageReader
            // Close existing ImageReader if it exists
            imageReader?.close()
            try {
                createImageReader() // Create or recreate ImageReader
                if (imageReader == null) {
                    Log.e("ScreenCaptureService", "Failed to create ImageReader")
                    Toast.makeText(this, "Failed to create image reader", Toast.LENGTH_SHORT).show()
                    stopSelf()
                    return
                }
            } catch (e: Exception) {
                Log.e("ScreenCaptureService", "Error creating ImageReader: ${e.message}", e)
                Toast.makeText(this, "Error creating image reader: ${e.message}", Toast.LENGTH_SHORT).show()
                stopSelf()
                return
            }

            // Create VirtualDisplay
            // Release existing virtual display if it exists
            virtualDisplay?.release()
            try {
                startVirtualDisplay() // Create or recreate VirtualDisplay
                if (virtualDisplay == null) {
                    Log.e("ScreenCaptureService", "Failed to create VirtualDisplay")
                    Toast.makeText(this, "Failed to create virtual display", Toast.LENGTH_SHORT).show()
                    stopCapture() // Clean up resources if virtual display fails
                    stopSelf()
                    return
                }
            } catch (e: Exception) {
                Log.e("ScreenCaptureService", "Error creating VirtualDisplay: ${e.message}", e)
                Toast.makeText(this, "Error creating virtual display: ${e.message}", Toast.LENGTH_SHORT).show()
                stopCapture() // Clean up resources if virtual display fails
                stopSelf()
                return
            }

            if (virtualDisplay != null) {
                isCapturing = true
                Log.d("ScreenCaptureService", "Screen capture started successfully after retry.")
                Toast.makeText(this, "Screen capture started", Toast.LENGTH_SHORT).show()
            } else {
                Log.e("ScreenCaptureService", "Failed to create VirtualDisplay. Stopping capture.")
                stopCapture() // Clean up resources if virtual display fails
                stopSelf()
            }
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Unexpected error in continueWithCapture: ${e.message}", e)
            Toast.makeText(this, "Unexpected error continuing capture: ${e.message}", Toast.LENGTH_SHORT).show()
            stopCapture()
            stopSelf()
        }
    }

    // MediaProjection callback moved outside startCapture
    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w("ScreenCaptureService", "MediaProjection stopped externally.")
            Log.w("ScreenCaptureService", "This usually means the user revoked screen capture permission or the system ended the session.")
            isCapturing = false
            // Clean up resources associated with the stopped projection
            handler?.post {
                virtualDisplay?.release()
                imageReader?.close()
                mediaProjection = null // Clear the reference
                virtualDisplay = null
                imageReader = null
            }
            // Don't stop the service immediately - let it try to restart
            Log.w("ScreenCaptureService", "MediaProjection stopped. Service will remain running but cannot capture screenshots.")
            Log.w("ScreenCaptureService", "User needs to restart screen capture from MainActivity.")
        }
    }

    private fun startVirtualDisplay() {
        if (mediaProjection == null) {
            Log.e("ScreenCaptureService", "Cannot create VirtualDisplay: MediaProjection is null")
            return
        }
        if (imageReader == null || imageReader?.surface == null) {
            Log.e("ScreenCaptureService", "Cannot create VirtualDisplay: ImageReader is null or has no surface")
            return
        }
        if (width <= 0 || height <= 0) {
            Log.e("ScreenCaptureService", "Cannot create VirtualDisplay: Invalid dimensions ($width x $height)")
            return
        }

        try {
            // Release existing virtual display if it exists
            virtualDisplay?.release()
            
            // Try to create virtual display with standard flags first
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                imageReader?.surface,
                null, // No callback
                handler
            )
            
            if (virtualDisplay != null) {
                Log.i("ScreenCaptureService", "VirtualDisplay created successfully with dimensions: $width x $height")
            } else {
                Log.w("ScreenCaptureService", "Standard VirtualDisplay creation failed, trying with minimal flags")
                
                // Try with minimal flags as fallback
                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "ScreenCapture",
                    width,
                    height,
                    density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC, // Minimal flags
                    imageReader?.surface,
                    null,
                    handler
                )
                
                if (virtualDisplay != null) {
                    Log.i("ScreenCaptureService", "VirtualDisplay created with minimal flags, dimensions: $width x $height")
                } else {
                    Log.e("ScreenCaptureService", "Failed to create VirtualDisplay even with minimal flags")
                }
            }
        } catch (e: SecurityException) {
            Log.e("ScreenCaptureService", "SecurityException creating VirtualDisplay: ", e)
            Toast.makeText(this, "Permission issue creating virtual display: ${e.message}", Toast.LENGTH_SHORT).show()
            virtualDisplay = null // Ensure it's null on failure
        } catch (e: IllegalArgumentException) {
            Log.e("ScreenCaptureService", "IllegalArgumentException creating VirtualDisplay: ", e)
            Toast.makeText(this, "Invalid parameters for virtual display: ${e.message}", Toast.LENGTH_SHORT).show()
            virtualDisplay = null // Ensure it's null on failure
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Exception creating VirtualDisplay: ", e)
            Toast.makeText(this, "Error creating virtual display: ${e.message}", Toast.LENGTH_SHORT).show()
            virtualDisplay = null // Ensure it's null on failure
        }
    }


    private fun createImageReader() {
        if (width <= 0 || height <= 0) {
            Log.e("ScreenCaptureService", "Cannot create ImageReader: Invalid dimensions ($width x $height)")
            return
        }
        
        try {
            // Use emulator-specific settings if running on emulator
            val bufferSize = if (isEmulator()) 1 else 2
            val pixelFormat = if (isEmulator()) PixelFormat.RGB_565 else PixelFormat.RGBA_8888
            
            // Try to create ImageReader with the selected format
            imageReader = ImageReader.newInstance(
                width, height, pixelFormat, bufferSize
            )
            imageReader?.setOnImageAvailableListener(imageAvailableListener, handler)
            Log.i("ScreenCaptureService", "ImageReader created with ${if (isEmulator()) "RGB_565" else "RGBA_8888"} format, dimensions: $width x $height, buffer: $bufferSize")
        } catch (e: IllegalArgumentException) {
            Log.w("ScreenCaptureService", "Selected format not supported, trying RGB_565: ${e.message}")
            try {
                // Fallback to RGB_565 format which is more widely supported
                imageReader = ImageReader.newInstance(
                    width, height, PixelFormat.RGB_565, 1 // Reduced buffer for compatibility
                )
                imageReader?.setOnImageAvailableListener(imageAvailableListener, handler)
                Log.i("ScreenCaptureService", "ImageReader created with RGB_565 format, dimensions: $width x $height")
            } catch (e2: Exception) {
                Log.e("ScreenCaptureService", "Failed to create ImageReader with RGB_565 format: ${e2.message}", e2)
                Toast.makeText(this, "Error setting up screen reader: ${e2.message}", Toast.LENGTH_SHORT).show()
                imageReader = null // Ensure it's null on failure
            }
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Unexpected error creating ImageReader: ${e.message}", e)
            Toast.makeText(this, "Error setting up screen reader: ${e.message}", Toast.LENGTH_SHORT).show()
            imageReader = null // Ensure it's null on failure
        }
    }

    // ImageAvailableListener moved outside createImageReader
    private val imageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        Log.d("ScreenCaptureService", "ImageAvailableListener triggered - pendingScreenshot: $pendingScreenshot")
        if (pendingScreenshot) {
            pendingScreenshot = false
            try {
                val image = reader.acquireLatestImage()
                if (image != null) {
                    processImage(image)
                    image.close()
                } else {
                    Log.e("ScreenCaptureService", "Failed to acquire image for pending screenshot - image is null")
                    // Restore overlay visibility if we can't get the image
                    restoreOverlayVisibility(currentInputText)
                }
            } catch (e: Exception) {
                Log.e("ScreenCaptureService", "Error processing pending screenshot: ", e)
                // Restore overlay visibility on error
                restoreOverlayVisibility(currentInputText)
            }
        } else {
            Log.d("ScreenCaptureService", "Image available but no pending screenshot - ignoring")
        }
    }

    // Emulator-specific overlay bounds calculation
    private fun getEmulatorOverlayBounds(): android.graphics.Rect? {
        return try {
            if (::overlayView.isInitialized && ::layoutParams.isInitialized) {
                // Use simpler bounds calculation for emulator
                val x = layoutParams.x
                val y = layoutParams.y
                val width = overlayView.width
                val height = overlayView.height
                
                // Add minimal padding for emulator
                val padding = 20
                val bounds = android.graphics.Rect(
                    maxOf(0, x - padding),
                    maxOf(0, y - padding),
                    x + width + padding,
                    y + height + padding
                )
                
                Log.d("ScreenCaptureService", "Emulator overlay bounds calculated: $bounds (pos: $x,$y size: ${width}x${height})")
                bounds
            } else {
                Log.d("ScreenCaptureService", "Emulator overlay not initialized, no bounds available")
                null
            }
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Error getting emulator overlay bounds: ${e.message}", e)
            null
        }
    }

    private fun getOverlayBounds(): android.graphics.Rect? {
        // Use emulator-specific bounds calculation if running on emulator
        if (isEmulator()) {
            Log.d("ScreenCaptureService", "Running on emulator, using emulator-specific overlay bounds calculation")
            return getEmulatorOverlayBounds()
        }
        
        return try {
            if (::overlayView.isInitialized && ::layoutParams.isInitialized) {
                // Get actual position on screen
                val location = IntArray(2)
                overlayView.getLocationOnScreen(location)
                val x = location[0]
                val y = location[1]
                val width = overlayView.width
                val height = overlayView.height
                
                // Add significant padding to ensure we clear the entire overlay area
                val padding = 50
                val bounds = android.graphics.Rect(
                    maxOf(0, x - padding),
                    maxOf(0, y - padding),
                    x + width + padding,
                    y + height + padding
                )
                
                Log.d("ScreenCaptureService", "Overlay bounds calculated: $bounds (actual pos: $x,$y size: ${width}x${height})")
                bounds
            } else {
                Log.d("ScreenCaptureService", "Overlay not initialized, no bounds available")
                null
            }
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Error getting overlay bounds: ${e.message}", e)
            null
        }
    }

    // Emulator-specific screenshot processing
    private fun processEmulatorScreenshot(image: Image) {
        Log.d("ScreenCaptureService", "Processing emulator screenshot with reduced functionality")
        
        try {
            if (width <= 0 || height <= 0) {
                Log.e("ScreenCaptureService", "Cannot process emulator screenshot: Invalid dimensions ($width x $height)")
                return
            }

            Log.d("ScreenCaptureService", "Starting emulator image processing with dimensions: $width x $height")
            val planes = image.planes
            val buffer: ByteBuffer = planes[0].buffer
            val pixelStride: Int = planes[0].pixelStride
            val rowStride: Int = planes[0].rowStride
            val rowPadding: Int = rowStride - pixelStride * width

            var bitmap: Bitmap? = null
            try {
                Log.d("ScreenCaptureService", "Creating bitmap from emulator image buffer")
                bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.RGB_565)
                bitmap.copyPixelsFromBuffer(buffer)

                if (rowPadding > 0) {
                    Log.d("ScreenCaptureService", "Cropping emulator bitmap to remove padding")
                    val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                    bitmap.recycle()
                    bitmap = croppedBitmap
                }

                // COMMENTED OUT: Black patch code - overlay is already hidden before screenshot
                // Get overlay bounds and black out the overlay area
                // val overlayBounds = getOverlayBounds()
                // if (overlayBounds != null) {
                //     Log.d("ScreenCaptureService", "Blacking out emulator overlay bounds: $overlayBounds")
                //     val finalBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
                //     val canvas = android.graphics.Canvas(finalBitmap)
                //     canvas.drawBitmap(bitmap, 0f, 0f, null)
                //     
                //     // Clear the overlay area with black color to prevent backend confusion
                //     val paint = android.graphics.Paint().apply {
                //         color = android.graphics.Color.BLACK
                //         style = android.graphics.Paint.Style.FILL
                //     }
                //     canvas.drawRect(overlayBounds, paint)
                //     
                //     bitmap.recycle()
                //     bitmap = finalBitmap
                //     Log.d("ScreenCaptureService", "Emulator overlay area blacked out")
                // } else {
                //     Log.d("ScreenCaptureService", "No emulator overlay bounds available")
                // }
                Log.d("ScreenCaptureService", "Emulator overlay black patch code commented out - overlay should be hidden")

                Log.d("ScreenCaptureService", "Emulator bitmap created successfully")
                
                // Store screenshot dimensions for coordinate calculations
                (application as? MyApplication)?.setLastScreenshotDimensions(bitmap.width, bitmap.height)
                
                val filename = "emulator_screenshot_${System.currentTimeMillis()}.jpg"

                Log.d("ScreenCaptureService", "Saving emulator screenshot to application")
                (application as? MyApplication)?.saveScreenshot(bitmap)
                
                            // Process with input text if available
            val inputTextForProcessing = if (isActionSequenceActive && originalInputText != null) {
                originalInputText // Use original input for sequential actions
            } else {
                currentInputText // Use current input for single actions
            }
            
            if (inputTextForProcessing != null) {
                // Log.d("ScreenCaptureService", "Processing emulator screenshot with input text: $inputTextForProcessing (sequence: $isActionSequenceActive)")
                BackendProcessing.processScreenshotWithInput(
                    this, 
                    bitmap, 
                    filename, 
                    inputTextForProcessing,
                    currentAppName,
                    currentTreeData,
                    (application as? MyApplication)?.getAccessibilityService(),
                    currentSession
                )
                
                // Only clear data if not in a sequence
                if (!isActionSequenceActive) {
                    currentInputText = null // Clear the input text after processing
                    currentTreeData = null // Clear the tree data after processing
                    currentAppName = null // Clear the app name after processing
                }
                
                // Disable screenshots after processing
                disableScreenshots()
            } else {
                // Log.d("ScreenCaptureService", "No input text available for emulator screenshot, using default processing with app: $currentAppName, treeData length: ${currentTreeData?.length ?: 0}")
                BackendProcessing.uploadScreenshotAndProcess(this, bitmap, filename, currentAppName, currentTreeData, currentSession)
                // Disable screenshots after processing
                disableScreenshots()
            }
                
                // Restore overlay visibility after screenshot processing (on main thread)
                restoreOverlayVisibility(inputTextForProcessing)

            } catch (e: OutOfMemoryError) {
                Log.e("ScreenCaptureService", "OutOfMemoryError processing emulator image: ", e)
                Toast.makeText(this, "Low memory, cannot process emulator screenshot", Toast.LENGTH_SHORT).show()
                bitmap?.recycle()
                // Ensure overlay is restored even on error
                restoreOverlayVisibility(null)
            } catch (e: Exception) {
                Log.e("ScreenCaptureService", "Error processing emulator image to bitmap: ", e)
                bitmap?.recycle()
                // Ensure overlay is restored even on error
                restoreOverlayVisibility(null)
            }
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Error in emulator screenshot processing: ${e.message}", e)
            // Ensure overlay is restored even on error
            restoreOverlayVisibility(null)
        }
    }

    private fun processImage(image: Image) {
        // Use emulator-specific processing if running on emulator
        if (isEmulator()) {
            Log.d("ScreenCaptureService", "Running on emulator, using emulator-specific screenshot processing")
            processEmulatorScreenshot(image)
            return
        }
        
        if (width <= 0 || height <= 0) {
            Log.e("ScreenCaptureService", "Cannot process image: Invalid dimensions ($width x $height)")
            return
        }

        Log.d("ScreenCaptureService", "Starting image processing with dimensions: $width x $height")
        val planes = image.planes
        val buffer: ByteBuffer = planes[0].buffer
        val pixelStride: Int = planes[0].pixelStride
        val rowStride: Int = planes[0].rowStride
        val rowPadding: Int = rowStride - pixelStride * width

        var bitmap: Bitmap? = null
        try {
            Log.d("ScreenCaptureService", "Creating bitmap from image buffer")
            bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)

            if (rowPadding > 0) {
                Log.d("ScreenCaptureService", "Cropping bitmap to remove padding")
                val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                bitmap.recycle()
                bitmap = croppedBitmap
            }

            // COMMENTED OUT: Black patch code - overlay is already hidden before screenshot
            // Get overlay bounds and exclude it from the screenshot
            // overlayBounds = getOverlayBounds()
            // if (overlayBounds != null) {
            //     Log.d("ScreenCaptureService", "Excluding overlay bounds from screenshot: $overlayBounds")
            //     val finalBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            //     val canvas = android.graphics.Canvas(finalBitmap)
            //     canvas.drawBitmap(bitmap, 0f, 0f, null)
            //     
            //     // Clear the overlay area with black color to prevent backend confusion
            //     val paint = android.graphics.Paint().apply {
            //         color = android.graphics.Color.BLACK  // Use black instead of white
            //         style = android.graphics.Paint.Style.FILL
            //     }
            //     canvas.drawRect(overlayBounds!!, paint)
            //     
            //     bitmap.recycle()
            //     bitmap = finalBitmap
            //     Log.d("ScreenCaptureService", "Overlay area cleared from screenshot")
            // } else {
            //     Log.d("ScreenCaptureService", "No overlay bounds available, screenshot will include overlay")
            // }
            Log.d("ScreenCaptureService", "Overlay black patch code commented out - overlay should be hidden")

            Log.d("ScreenCaptureService", "Bitmap created successfully")
            
            // Store screenshot dimensions for coordinate calculations
            (application as? MyApplication)?.setLastScreenshotDimensions(bitmap.width, bitmap.height)
            
            // Log screenshot resolution comparison
            val statusBarHeight = ScreenMetrics.getStatusBarHeight(this)
            DebugLogger.logScreenshotCapture(
                phoneWidth = width,
                phoneHeight = height,
                screenshotWidth = bitmap.width,
                screenshotHeight = bitmap.height,
                statusBarHeight = statusBarHeight
            )
            
            val filename = "screenshot_for_upload_${System.currentTimeMillis()}.jpg"

            Log.d("ScreenCaptureService", "Saving screenshot to application")
            (application as? MyApplication)?.saveScreenshot(bitmap)
            
            // Process with input text if available
            val inputTextForProcessing = if (isActionSequenceActive && originalInputText != null) {
                originalInputText // Use original input for sequential actions
            } else {
                currentInputText // Use current input for single actions
            }
            
            if (inputTextForProcessing != null) {
                // Log.d("ScreenCaptureService", "Processing screenshot with input text: $inputTextForProcessing (sequence: $isActionSequenceActive)")
                BackendProcessing.processScreenshotWithInput(
                    this, 
                    bitmap, 
                    filename, 
                    inputTextForProcessing,
                    currentAppName,
                    currentTreeData,
                    (application as? MyApplication)?.getAccessibilityService(),
                    currentSession
                )
                
                // Only clear data if not in a sequence
                if (!isActionSequenceActive) {
                    currentInputText = null // Clear the input text after processing
                    currentTreeData = null // Clear the tree data after processing
                    currentAppName = null // Clear the app name after processing
                }
                
                // Disable screenshots after processing
                disableScreenshots()
            } else {
                // Log.d("ScreenCaptureService", "No input text available, using default processing with app: $currentAppName, treeData length: ${currentTreeData?.length ?: 0}")
                BackendProcessing.uploadScreenshotAndProcess(this, bitmap, filename, currentAppName, currentTreeData, currentSession)
                // Disable screenshots after processing
                disableScreenshots()
            }
            
            // Restore overlay visibility after screenshot processing (on main thread)
            restoreOverlayVisibility(inputTextForProcessing)

        } catch (e: OutOfMemoryError) {
            Log.e("ScreenCaptureService", "OutOfMemoryError processing image: ", e)
            Toast.makeText(this, "Low memory, cannot process screenshot", Toast.LENGTH_SHORT).show()
            bitmap?.recycle()
            // Ensure overlay is restored even on error
            restoreOverlayVisibility(null)
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Error processing image to bitmap: ", e)
            bitmap?.recycle()
            // Ensure overlay is restored even on error
            restoreOverlayVisibility(null)
        }
    }

    // Emulator-specific overlay creation
    private fun createEmulatorOverlay() {
        Log.d("ScreenCaptureService", "Creating emulator-specific overlay with reduced functionality")
        
        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            
            // Check if overlay permission is granted
            if (!Settings.canDrawOverlays(this)) {
                Log.w("ScreenCaptureService", "Overlay permission not granted on emulator, skipping overlay creation")
                return
            }
            
            // Create a simple overlay for emulator
            overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)
            
            // Use simpler window type for emulator
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            
            layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = 50 // inset from right edge
                y = 0 // top edge
            }
            
            // Setup overlay text with emulator-specific message
            val overlayText = overlayView.findViewById<TextView>(R.id.overlay_text)
            overlayText.text = "Emulator Mode - Tap for input"
            
            // Make the overlay non-focusable so it doesn't steal focus from Blinkit
            overlayView.isFocusable = false
            overlayView.isFocusableInTouchMode = false
            
            // Make the overlay clickable to open input dialog
            overlayView.isClickable = true
            overlayView.setOnClickListener {
                Log.d("ScreenCaptureService", "Overlay clicked - calling showInputDialog()")
                showInputDialog()
            }
            
            Log.d("ScreenCaptureService", "Overlay click listener set up - isClickable: ${overlayView.isClickable}")
            
            try {
                windowManager.addView(overlayView, layoutParams)
                Log.d("ScreenCaptureService", "Emulator overlay window created successfully")
            } catch (e: Exception) {
                Log.e("ScreenCaptureService", "Error adding emulator overlay view: ${e.message}", e)
                // Don't rethrow - just log the error and continue without overlay
            }
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Error creating emulator overlay: ${e.message}", e)
            // Don't rethrow - just log the error and continue without overlay
        }
    }

    private fun createOverlayWindow() {
        try {
            // Use emulator-specific overlay if running on emulator
            if (isEmulator()) {
                Log.d("ScreenCaptureService", "Running on emulator, using emulator-specific overlay")
                createEmulatorOverlay()
                return
            }
            
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            
            // Check if overlay permission is granted
            if (!Settings.canDrawOverlays(this)) {
                Log.w("ScreenCaptureService", "Overlay permission not granted, skipping overlay creation")
                return
            }
            
            // Inflate the overlay layout
            overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)
            
            // Set up layout parameters for the overlay
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            
            layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = 50 // inset from right edge
                y = 0 // top edge
            }
            
            // Setup overlay text with initial message
            val overlayText = overlayView.findViewById<TextView>(R.id.overlay_text)
            overlayText.text = "Tap to add instruction"
            
            // Make the overlay non-focusable so it doesn't steal focus from Blinkit
            overlayView.isFocusable = false
            overlayView.isFocusableInTouchMode = false
            
            // Make the overlay clickable to open input dialog
            overlayView.isClickable = true
            overlayView.setOnClickListener {
                Log.d("ScreenCaptureService", "Overlay clicked - calling showInputDialog()")
                showInputDialog()
            }
            
            Log.d("ScreenCaptureService", "Overlay click listener set up - isClickable: ${overlayView.isClickable}")
            
            try {
                windowManager.addView(overlayView, layoutParams)
                Log.d("ScreenCaptureService", "Overlay window created successfully")
            } catch (e: Exception) {
                Log.e("ScreenCaptureService", "Error adding overlay view to window manager: ${e.message}", e)
                // Don't rethrow - just log the error and continue without overlay
            }
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Error creating overlay window: ${e.message}", e)
            // Don't rethrow - just log the error and continue without overlay
        }
    }
    
    // Emulator-specific input dialog
    private fun showEmulatorInputDialog() {
        try {
            Log.d("ScreenCaptureService", "Showing emulator input dialog")
            
            // Create a simple input overlay for emulator
            val containerLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = ContextCompat.getDrawable(this@ScreenCaptureService, android.R.drawable.edit_text)
                setPadding(20, 20, 20, 20)
            }
            
            // Create the input field
            val inputOverlay = EditText(this).apply {
                hint = "Enter instruction (Emulator Mode)..."
                setText(currentInputText ?: "")
                setPadding(20, 20, 20, 20)
                setTextColor(android.graphics.Color.BLACK)
                setHintTextColor(android.graphics.Color.GRAY)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                
                // Handle Enter key press
                setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_SEND) {
                        submitInstruction(text.toString().trim())
                        true
                    } else {
                        false
                    }
                }
            }
            
            // Create submit button
            val submitButton = TextView(this).apply {
                text = "Submit"
                setTextColor(android.graphics.Color.WHITE)
                background = ContextCompat.getDrawable(this@ScreenCaptureService, android.R.drawable.btn_default)
                gravity = Gravity.CENTER
                setPadding(40, 20, 40, 20)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER
                    topMargin = 20
                }
                
                // Make button clickable
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    submitInstruction(inputOverlay.text.toString().trim())
                }
            }
            
            // Add input and button to container
            containerLayout.addView(inputOverlay)
            containerLayout.addView(submitButton)
            
            // Store reference to input overlay and its params
            inputOverlayView = containerLayout
            inputOverlayParams = WindowManager.LayoutParams(
                300, // Smaller width for emulator
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
            }
            
            // Add input overlay to window manager
            windowManager.addView(containerLayout, inputOverlayParams!!)
            
            // Request focus and show keyboard
            inputOverlay.requestFocus()
            inputOverlay.postDelayed({
                try {
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(inputOverlay, InputMethodManager.SHOW_IMPLICIT)
                } catch (e: Exception) {
                    Log.w("ScreenCaptureService", "Could not show keyboard on emulator: ${e.message}")
                }
            }, 100)
            
            Log.d("ScreenCaptureService", "Emulator input overlay shown")
            
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Error creating emulator input overlay: ${e.message}", e)
        }
    }

    private fun showInputDialog() {
        try {
            Log.d("ScreenCaptureService", "Showing input dialog")
            
            // Start new session when user submits input
            startNewSession()
            
            // Use emulator-specific input dialog if running on emulator
            if (isEmulator()) {
                Log.d("ScreenCaptureService", "Running on emulator, using emulator-specific input dialog")
                showEmulatorInputDialog()
                return
            }
            
            // Instead of AlertDialog, use a simple overlay input to avoid service restart issues
            Log.d("ScreenCaptureService", "Calling showSimpleInputOverlay()")
            showSimpleInputOverlay()
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Error showing input dialog: ${e.message}", e)
        }
    }
    
    // Start a new session with new UUID
    private fun startNewSession() {
        currentSession = SessionContext()
        retryAttempts = 0
        consecutiveFailures = 0
        Log.d("ScreenCaptureService", "🚀 Starting new session: ${currentSession?.sessionId}")
    }
    
    // End current session (can be called from BackendProcessing)
    fun endSession(reason: String = "Session ended") {
        currentSession?.let { session ->
            Log.d("ScreenCaptureService", "🏁 Ending session: ${session.sessionId} - Reason: $reason")
        }
        currentSession = null
        retryAttempts = 0
        consecutiveFailures = 0
        
        // Hide overlay when session ends
        handler?.post {
            hideOverlay()
        }
    }
    
    // Hide the primary overlay and any input overlay
    private fun hideOverlay() {
        try {
            if (::overlayView.isInitialized) {
                overlayView.visibility = View.GONE
                Log.d("ScreenCaptureService", "Overlay visibility set to GONE")
            }
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Error hiding overlay: ${e.message}", e)
        }
        // Also ensure input overlay is removed
        hideInputOverlay()
    }

    // Expose retry attempts controls safely for BackendProcessing
    fun getRetryAttempts(): Int = retryAttempts
    fun incrementRetryAttempts() { retryAttempts += 1 }
    
    private fun showSimpleInputOverlay() {
        try {
            Log.d("ScreenCaptureService", "Creating simple input overlay")
            // Hide existing input overlay if any
            hideInputOverlay()
            
            // Create a container layout for input and button
            val containerLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = ContextCompat.getDrawable(this@ScreenCaptureService, android.R.drawable.edit_text)
                setPadding(20, 20, 20, 20)
            }
            
            // Create the input field
            val inputOverlay = EditText(this).apply {
                hint = "Enter instruction for backend..."
                setText(currentInputText ?: "")
                setPadding(20, 20, 20, 20)
                setTextColor(android.graphics.Color.BLACK)
                setHintTextColor(android.graphics.Color.GRAY)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                
                // Handle Enter key press
                setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_SEND) {
                        submitInstruction(text.toString().trim())
                        true
                    } else {
                        false
                    }
                }
            }
            
            // Create submit button
            val submitButton = TextView(this).apply {
                text = "Submit"
                setTextColor(android.graphics.Color.WHITE)
                background = ContextCompat.getDrawable(this@ScreenCaptureService, android.R.drawable.btn_default)
                gravity = Gravity.CENTER
                setPadding(40, 20, 40, 20)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER
                    topMargin = 20
                }
                
                // Make button clickable
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    submitInstruction(inputOverlay.text.toString().trim())
                }
            }
            
            // Add input and button to container
            containerLayout.addView(inputOverlay)
            containerLayout.addView(submitButton)
            
            // Store reference to input overlay and its params
            inputOverlayView = containerLayout
            inputOverlayParams = WindowManager.LayoutParams(
                400, // Fixed width
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
            }
            
            // Add input overlay to window manager
            windowManager.addView(containerLayout, inputOverlayParams!!)
            
            // Request focus and show keyboard
            inputOverlay.requestFocus()
            inputOverlay.postDelayed({
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(inputOverlay, InputMethodManager.SHOW_IMPLICIT)
            }, 100)
            
            Log.d("ScreenCaptureService", "Input overlay with submit button shown")
            
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Error creating input overlay: ${e.message}", e)
        }
    }
    
    private fun submitInstruction(inputText: String) {
        // Log.d("ScreenCaptureService", "submitInstruction called with: '$inputText'")
        
        if (inputText.isNotEmpty()) {
            currentInputText = inputText
            
            // Capture tree data first before starting sequence
            val myApp = application as? MyApplication
            val accessibilityService = myApp?.getAccessibilityService()
            
            if (accessibilityService != null) {
                // Capture current tree data and app name
                accessibilityService.showBlinkitTree()
                
                // Wait for tree data to be captured
                Handler(Looper.getMainLooper()).postDelayed({
                    val treeData = accessibilityService.getLastTreeData()
                    val appName = accessibilityService.getLastAppName()
                    
                    // Store the data for backend processing
                    currentTreeData = treeData
                    currentAppName = appName
                    
                    Log.d("ScreenCaptureService", "📤 CAPTURED DATA - Tree length: ${treeData.length}, App: $appName")
                    
                    // Start the action sequence with captured data
                    BackendProcessing.startActionSequence(this, inputText, accessibilityService)
                    
                    // Hide input overlay
                    if (isEmulator()) {
                        hideEmulatorInputOverlay()
                    } else {
                        hideInputOverlay()
                    }
                    
                    // Update main overlay text to show current instruction
                    if (isEmulator()) {
                        updateEmulatorOverlayText("Processing: $inputText")
                    } else {
                        updateOverlayText("Processing: $inputText")
                    }
                    
                }, 800) // Wait 800ms for tree data to be captured
                
            } else {
                // Fallback: start sequence without tree data if accessibility service not available
                Log.w("ScreenCaptureService", "Accessibility service not available, starting sequence without tree data")
                BackendProcessing.startActionSequence(this, inputText, null)
                
                // Hide input overlay
                if (isEmulator()) {
                    hideEmulatorInputOverlay()
                } else {
                    hideInputOverlay()
                }
                
                // Update main overlay text to show current instruction
                if (isEmulator()) {
                    updateEmulatorOverlayText("Processing: $inputText")
                } else {
                    updateOverlayText("Processing: $inputText")
                }
            }
            
            // Broadcast the input text
            val intent = Intent("com.example.beta.INPUT_RECEIVED")
            intent.putExtra("input_text", inputText)
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
            
            // Tree data is already captured above, now just trigger screenshot
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    // Enable screenshots and trigger capture
                    enableScreenshots()
                    pendingScreenshot = true
                    triggerScreenshot()
                } catch (e: Exception) {
                    Log.e("ScreenCaptureService", "Error triggering screenshot: ${e.message}", e)
                    e.printStackTrace()
                    // Fallback: trigger screenshot without tree data
                    enableScreenshots()
                    pendingScreenshot = true
                    triggerScreenshot()
                }
                
            }, 500) // 500ms delay to ensure overlay is hidden
        } else {
            // Log.w("ScreenCaptureService", "submitInstruction called with empty text")
        }
        
        // Log.d("ScreenCaptureService", "=== FINISHED SUBMIT INSTRUCTION PROCESS ===")
    }
    
    private fun triggerNextActionInSequence(originalInput: String, actionNumber: Int) {
        // Log.d("ScreenCaptureService", "Triggering next action #$actionNumber in sequence for: '$originalInput'")
        
        // Store the original input for this sequence
        originalInputText = originalInput
        isActionSequenceActive = true
        
        // Update overlay to show current action
        if (isEmulator()) {
            updateEmulatorOverlayText("Action #$actionNumber: $originalInput")
        } else {
            updateOverlayText("Action #$actionNumber: $originalInput")
        }
        
        // Trigger the same sequence as submitInstruction but for the next action
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val myApp = application as? MyApplication
                val accessibilityService = myApp?.getAccessibilityService()
                
                if (accessibilityService != null) {
                    accessibilityService.showBlinkitTree()
                    
                    // Wait for tree data to be captured, then trigger screenshot
                    Handler(Looper.getMainLooper()).postDelayed({
                        // Get the captured tree data and app name
                        val treeData = accessibilityService.getLastTreeData()
                        val appName = accessibilityService.getLastAppName()
                        
                        // Log.d("ScreenCaptureService", "Next action tree data captured - length: ${treeData.length}, app: $appName")
                        
                        // Store the data for backend processing
                        currentTreeData = treeData
                        currentAppName = appName
                        
                        // Now trigger screenshot with tree data ready
                        enableScreenshots()
                        pendingScreenshot = true
                        triggerScreenshot()
                        
                    }, 800) // Wait 800ms for tree data to be captured
                    
                } else {
                    // Log.w("ScreenCaptureService", "Accessibility service not available for next action")
                    // Fallback: trigger screenshot without tree data
                    enableScreenshots()
                    pendingScreenshot = true
                    triggerScreenshot()
                }
            } catch (e: Exception) {
                Log.e("ScreenCaptureService", "Error triggering next action tree view: ${e.message}", e)
                e.printStackTrace()
                // Fallback: trigger screenshot without tree data
                enableScreenshots()
                pendingScreenshot = true
                triggerScreenshot()
            }
            
        }, 500) // 500ms delay to ensure UI is ready
    }
    
    fun stopActionSequence() {
        // Log.d("ScreenCaptureService", "Stopping action sequence")
        isActionSequenceActive = false
        originalInputText = null
        BackendProcessing.stopActionSequence()
    }
    
    fun enableScreenshots() {
        screenshotEnabled = true
        Log.d("ScreenCaptureService", "🔍 DEBUG: Screenshots enabled")
    }
    
    fun disableScreenshots() {
        screenshotEnabled = false
        Log.d("ScreenCaptureService", "🔍 DEBUG: Screenshots disabled")
    }
    
    fun isScreenshotEnabled(): Boolean {
        return screenshotEnabled
    }
    
    fun storeTreeData(treeData: String, appName: String) {
        currentTreeData = treeData
        currentAppName = appName
        Log.d("ScreenCaptureService", "📤 STORED DATA - Tree length: ${treeData.length}, App: $appName")
    }

    // Public accessor for current overlay bounds (with padding rules consistent with capture exclusion)
    fun getOverlayRect(): android.graphics.Rect? {
        return try {
            getOverlayBounds()
        } catch (e: Exception) {
            null
        }
    }
    
    private fun hideInputOverlay() {
        try {
            if (isEmulator()) {
                hideEmulatorInputOverlay()
            } else {
                inputOverlayView?.let { overlay ->
                    windowManager.removeView(overlay)
                    inputOverlayView = null
                    Log.d("ScreenCaptureService", "Input overlay hidden")
                }
            }
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Error hiding input overlay: ${e.message}", e)
        }
    }
    
    // Emulator-specific overlay text update
    private fun updateEmulatorOverlayText(text: String) {
        try {
            if (::overlayView.isInitialized) {
                overlayView.post {
                    try {
                        val overlayText = overlayView.findViewById<TextView>(R.id.overlay_text)
                        overlayText.text = text
                        Log.d("ScreenCaptureService", "Emulator overlay text updated to: $text")
                    } catch (e: Exception) {
                        Log.e("ScreenCaptureService", "Error updating emulator overlay text: ${e.message}", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Error in updateEmulatorOverlayText: ${e.message}", e)
        }
    }

    private fun updateOverlayText(text: String) {
        try {
            if (::overlayView.isInitialized) {
                overlayView.post {
                    try {
                        val overlayText = overlayView.findViewById<TextView>(R.id.overlay_text)
                        overlayText.text = text
                        Log.d("ScreenCaptureService", "Overlay text updated to: $text")
                    } catch (e: Exception) {
                        Log.e("ScreenCaptureService", "Error updating overlay text: ${e.message}", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Error in updateOverlayText: ${e.message}", e)
        }
    }
    
    // Emulator-specific overlay restoration
    private fun restoreEmulatorOverlayVisibility(inputTextForProcessing: String?) {
        try {
            if (::overlayView.isInitialized) {
                overlayView.post {
                    try {
                        overlayView.visibility = View.VISIBLE
                        Log.d("ScreenCaptureService", "Emulator overlay visibility set to VISIBLE")
                        
                        // Update overlay text to show ready status
                        if (inputTextForProcessing != null) {
                            Log.d("ScreenCaptureService", "Setting emulator overlay text to 'Ready - Tap for input (Emulator)'")
                            updateEmulatorOverlayText("Ready - Tap for input (Emulator)")
                        } else {
                            Log.d("ScreenCaptureService", "Setting emulator overlay text to 'Tap for input (Emulator)'")
                            updateEmulatorOverlayText("Tap for input (Emulator)")
                        }
                        Log.d("ScreenCaptureService", "Emulator overlay restored to visible with ready status")
                    } catch (e: Exception) {
                        Log.e("ScreenCaptureService", "Error updating emulator overlay text: ${e.message}", e)
                    }
                }
            } else {
                Log.w("ScreenCaptureService", "Emulator overlay not initialized when trying to restore visibility")
            }
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Error restoring emulator overlay visibility: ${e.message}", e)
        }
    }

    private fun restoreOverlayVisibility(inputTextForProcessing: String?) {
        // Use emulator-specific restoration if running on emulator
        if (isEmulator()) {
            Log.d("ScreenCaptureService", "Running on emulator, using emulator-specific overlay restoration")
            restoreEmulatorOverlayVisibility(inputTextForProcessing)
            return
        }
        
        overlayView.post {
            try {
                if (::overlayView.isInitialized) {
                    overlayView.visibility = View.VISIBLE
                    Log.d("ScreenCaptureService", "Overlay visibility set to VISIBLE")
                    
                    // Update overlay text to show ready status
                    if (inputTextForProcessing != null) {
                        Log.d("ScreenCaptureService", "Setting overlay text to 'Ready - Tap to add instruction'")
                        updateOverlayText("Ready - Tap to add instruction")
                    } else {
                        Log.d("ScreenCaptureService", "Setting overlay text to 'Tap to add instruction'")
                        updateOverlayText("Tap to add instruction")
                    }
                    Log.d("ScreenCaptureService", "Overlay restored to visible with ready status")
                } else {
                    Log.w("ScreenCaptureService", "Overlay not initialized when trying to restore visibility")
                }
            } catch (e: Exception) {
                Log.e("ScreenCaptureService", "Error restoring overlay visibility: ${e.message}", e)
            }
        }
    }

    // Emulator-specific notification channel creation
    private fun createEmulatorNotificationChannel() {
        try {
            createNotificationChannel()
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Error creating emulator notification channel: ${e.message}", e)
        }
    }

    private fun createNotificationChannel() {
        // Use emulator-specific channel creation if running on emulator
        if (isEmulator()) {
            Log.d("ScreenCaptureService", "Running on emulator, using emulator-specific notification channel")
            createEmulatorNotificationChannel()
            return
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Screen Capture Service",
                NotificationManager.IMPORTANCE_LOW // Use LOW to be less intrusive
            ).apply {
                description = "Background service capturing the screen"
                // Optional: configure sound, vibration etc. Defaults are usually fine.
                // setSound(null, null)
                // enableVibration(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
            Log.d("ScreenCaptureService", "Notification channel created.")
        }
    }

    // Emulator-specific notification creation
    private fun createEmulatorNotification(): Notification? {
        return try {
            val notificationIntent = Intent(this, MainActivity::class.java)
            val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent, pendingIntentFlags
            )

            // Add a Stop button to the notification
            val stopSelfIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ACTION_STOP_CAPTURE
            }
            val stopPendingIntent = PendingIntent.getService(
                this, 0, stopSelfIntent, pendingIntentFlags
            )

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Screen Capture Active (Emulator)")
                .setContentText("Ready to capture screenshots")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .addAction(R.drawable.ic_launcher_foreground, "Stop Capture", stopPendingIntent)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_MIN) // Use MIN priority for emulator
                .build()

            notification
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Error creating emulator notification: ${e.message}", e)
            null
        }
    }

    private fun showForegroundNotification() {
        if (foregroundStarted) {
            Log.d("ScreenCaptureService", "Foreground notification already shown.")
            return
        }
        
        try {
            val notification = if (isEmulator()) {
                createEmulatorNotification()
            } else {
                val notificationIntent = Intent(this, MainActivity::class.java)
                val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pendingIntent = PendingIntent.getActivity(
                    this, 0, notificationIntent, pendingIntentFlags
                )

                // Add a Stop button to the notification
                val stopSelfIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    action = ACTION_STOP_CAPTURE
                }
                val stopPendingIntent = PendingIntent.getService(
                    this, 0, stopSelfIntent, pendingIntentFlags
                )

                val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("Screen Capture Active")
                    .setContentText("Ready to capture screenshots")
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentIntent(pendingIntent)
                    .addAction(R.drawable.ic_launcher_foreground, "Stop Capture", stopPendingIntent)
                    .setOngoing(true)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build()
                notification
            }

            if (notification != null) {
                try {
                    startForeground(NOTIFICATION_ID, notification)
                    foregroundStarted = true
                    Log.d("ScreenCaptureService", "Started foreground service with notification.")
                } catch (e: Exception) {
                    Log.e("ScreenCaptureService", "Error starting foreground service: ", e)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                        Toast.makeText(this, "App cannot start capture from background.", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "Could not start capture service.", Toast.LENGTH_SHORT).show()
                    }
                    
                    // On emulator, try to continue without foreground service
                    if (isEmulator()) {
                        Log.w("ScreenCaptureService", "Foreground service failed on emulator, continuing without it")
                        // Continue without foreground service - this might work on some emulators
                    } else {
                        stopSelf()
                    }
                }
            } else {
                Log.e("ScreenCaptureService", "Notification is null, cannot start foreground service.")
                // On emulator, try to continue without notification
                if (isEmulator()) {
                    Log.w("ScreenCaptureService", "Notification creation failed on emulator, continuing without it")
                    // Continue without notification - this might work on some emulators
                } else {
                    Toast.makeText(this, "Could not create notification: Notification is null", Toast.LENGTH_SHORT).show()
                    stopSelf()
                }
            }
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Error creating notification: ${e.message}", e)
            // On emulator, try to continue without notification
            if (isEmulator()) {
                Log.w("ScreenCaptureService", "Notification creation failed on emulator, continuing without it")
                // Continue without notification - this might work on some emulators
            } else {
                Toast.makeText(this, "Could not create notification: ${e.message}", Toast.LENGTH_SHORT).show()
                stopSelf()
            }
        }
    }

    private fun stopCapture() {
        Log.d("ScreenCaptureService", "Stopping screen capture...")
        isCapturing = false
        // Use handler to ensure cleanup happens on the correct thread
        handler?.post {
            try {
                virtualDisplay?.release()
                virtualDisplay = null
                Log.d("ScreenCaptureService", "VirtualDisplay released.")
            } catch (e: Exception) {
                Log.e("ScreenCaptureService", "Error releasing VirtualDisplay: ", e)
            }

            try {
                if (mediaProjection != null) {
                    mediaProjection?.unregisterCallback(mediaProjectionCallback)
                    mediaProjection?.stop()
                    mediaProjection = null
                    Log.d("ScreenCaptureService", "MediaProjection stopped and callback unregistered.")
                }
            } catch (e: Exception) {
                Log.e("ScreenCaptureService", "Error stopping MediaProjection: ", e)
            }

            try {
                imageReader?.close()
                imageReader = null
                Log.d("ScreenCaptureService", "ImageReader closed.")
            } catch (e: Exception) {
                Log.e("ScreenCaptureService", "Error closing ImageReader: ", e)
            }
        }
        // Update notification or remove foreground state
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE) // Remove notification
            foregroundStarted = false
            Log.d("ScreenCaptureService", "Foreground service stopped.")
        }
    }

    fun setAccessibilityService(accessibilityService: MyAccessibilityService) {
        // Set reference to the AccessibilityService if needed for future interactions.
        this.accessibilityService = accessibilityService // If you define a variable to hold this reference.
        Log.d("ScreenCaptureService", "Accessibility service set")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // Not a bound service
    }

    // Emulator-specific overlay cleanup
    private fun cleanupEmulatorOverlays() {
        try {
            // Remove main overlay window
            if (::overlayView.isInitialized && ::windowManager.isInitialized) {
                windowManager.removeView(overlayView)
                Log.d("ScreenCaptureService", "Emulator overlay window removed")
            }
            
            // Remove input overlay if it exists
            try {
                hideEmulatorInputOverlay()
            } catch (e: Exception) {
                Log.e("ScreenCaptureService", "Error removing emulator input overlay: ${e.message}", e)
            }
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Error cleaning up emulator overlays: ${e.message}", e)
        }
    }

    // Emulator-specific service cleanup
    private fun cleanupEmulatorService() {
        try {
            Log.d("ScreenCaptureService", "Cleaning up emulator service...")
            
            // Unregister receiver
            try {
                unregisterEmulatorReceiver()
            } catch (e: Exception) {
                Log.e("ScreenCaptureService", "Error unregistering emulator receiver: ${e.message}", e)
            }
            
            // Stop capture with emulator-specific handling
            try {
                stopCapture()
            } catch (e: Exception) {
                Log.e("ScreenCaptureService", "Error stopping emulator capture: ${e.message}", e)
            }
            
            // Clean up overlays
            try {
                cleanupEmulatorOverlays()
            } catch (e: Exception) {
                Log.e("ScreenCaptureService", "Error cleaning up emulator overlays: ${e.message}", e)
            }
            
            // Quit the handler thread's looper safely
            try {
                handlerThread?.quitSafely()
                handlerThread = null
                handler = null
            } catch (e: Exception) {
                Log.e("ScreenCaptureService", "Error cleaning up emulator handler thread: ${e.message}", e)
            }
            
            // Clear service reference in Application
            try {
                (application as? MyApplication)?.setScreenCaptureService(null)
            } catch (e: Exception) {
                Log.e("ScreenCaptureService", "Error clearing emulator application service: ${e.message}", e)
            }
            
            Log.d("ScreenCaptureService", "Emulator service cleanup completed.")
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Error in emulator service cleanup: ${e.message}", e)
        }
    }

    // Emulator-specific receiver unregistration
    private fun unregisterEmulatorReceiver() {
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(inputReceiver)
            LocalBroadcastManager.getInstance(this).unregisterReceiver(nextActionReceiver)
            Log.d("ScreenCaptureService", "Emulator receiver unregistered successfully")
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Error unregistering emulator receiver: ${e.message}", e)
        }
    }

    override fun onDestroy() {
        Log.d("ScreenCaptureService", "Service destroyed.")
        
        try {
            // Use emulator-specific cleanup if running on emulator
            if (isEmulator()) {
                Log.d("ScreenCaptureService", "Running on emulator, using emulator-specific cleanup")
                cleanupEmulatorService()
            } else {
                // Standard cleanup
                try {
                    LocalBroadcastManager.getInstance(this).unregisterReceiver(inputReceiver)
                    LocalBroadcastManager.getInstance(this).unregisterReceiver(nextActionReceiver)
                    Log.d("ScreenCaptureService", "Receivers unregistered successfully")
                } catch (e: Exception) {
                    Log.e("ScreenCaptureService", "Error unregistering receivers: ${e.message}", e)
                }
                stopCapture() // Ensure all resources are released
                
                // Remove overlay window
                try {
                    if (::overlayView.isInitialized && ::windowManager.isInitialized) {
                        windowManager.removeView(overlayView)
                        Log.d("ScreenCaptureService", "Overlay window removed")
                    }
                } catch (e: Exception) {
                    Log.e("ScreenCaptureService", "Error removing overlay window: ${e.message}", e)
                }
                
                // Remove input overlay if it exists
                try {
                    hideInputOverlay()
                } catch (e: Exception) {
                    Log.e("ScreenCaptureService", "Error removing input overlay: ${e.message}", e)
                }
                
                // Quit the handler thread's looper safely
                handlerThread?.quitSafely()
                handlerThread = null
                handler = null
                
                // Clear service reference in Application
                (application as? MyApplication)?.setScreenCaptureService(null)
                Log.d("ScreenCaptureService", "Cleaned up resources and handler thread.")
            }
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Error in onDestroy: ${e.message}", e)
        }
        
        super.onDestroy()
    }

    // Companion object for constants like the action string
    companion object {
        const val ACTION_STOP_CAPTURE = "com.example.beta.STOP_CAPTURE"
        // Consider adding actions for START if needed, though currently handled by intent extras
    }

    // Emulator-specific overlay hiding
    private fun hideEmulatorOverlay() {
        try {
            if (::overlayView.isInitialized) {
                overlayView.visibility = View.INVISIBLE
                Log.d("ScreenCaptureService", "Emulator overlay hidden for screenshot capture")
            }
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Error hiding emulator overlay: ${e.message}", e)
        }
    }

    // Check if the service can capture screenshots
    fun canCapture(): Boolean {
        return isCapturing && mediaProjection != null && virtualDisplay != null && imageReader != null
    }
    
    // Get detailed status for debugging
    fun getCaptureStatus(): String {
        return "isCapturing: $isCapturing, mediaProjection: ${if (mediaProjection != null) "exists" else "null"}, " +
                "virtualDisplay: ${if (virtualDisplay != null) "exists" else "null"}, " +
                "imageReader: ${if (imageReader != null) "exists" else "null"}"
    }

    // Add method to trigger screenshot manually
    fun triggerScreenshot() {
        if (!screenshotEnabled) {
            // Log.d("ScreenCaptureService", "Screenshot disabled, not taking screenshot")
            return
        }
        
        if (!isCapturing) {
            Log.e("ScreenCaptureService", "Cannot trigger screenshot: Service not capturing")
            Log.e("ScreenCaptureService", "MediaProjection state: ${if (mediaProjection != null) "exists" else "null"}")
            Log.e("ScreenCaptureService", "VirtualDisplay state: ${if (virtualDisplay != null) "exists" else "null"}")
            Log.e("ScreenCaptureService", "ImageReader state: ${if (imageReader != null) "exists" else "null"}")
            return
        }
        
        if (imageReader == null) {
            Log.e("ScreenCaptureService", "Cannot trigger screenshot: ImageReader is null")
            return
        }

        if (virtualDisplay == null) {
            Log.e("ScreenCaptureService", "Cannot trigger screenshot: VirtualDisplay is null")
            return
        }

        Log.d("ScreenCaptureService", "Setting pending screenshot flag")
        Log.d("ScreenCaptureService", "Current input text: $currentInputText")
        
        // ENHANCED: Temporarily hide the overlay during screenshot capture
        try {
            if (::overlayView.isInitialized) {
                if (isEmulator()) {
                    hideEmulatorOverlay()
                } else {
                    overlayView.visibility = View.INVISIBLE
                    Log.d("ScreenCaptureService", "Overlay hidden for screenshot capture")
                }
            } else {
                Log.w("ScreenCaptureService", "Overlay view not initialized - cannot hide for screenshot")
            }
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Error hiding overlay: ${e.message}", e)
        }
        
        // Add delay to ensure overlay is hidden before capturing
        Handler(Looper.getMainLooper()).postDelayed({
            // ENHANCED: Verify overlay is actually hidden before proceeding
            if (::overlayView.isInitialized) {
                if (overlayView.visibility != View.INVISIBLE) {
                    Log.w("ScreenCaptureService", "Overlay still visible, hiding again")
                    overlayView.visibility = View.INVISIBLE
                } else {
                    Log.d("ScreenCaptureService", "Overlay confirmed hidden before screenshot")
                }
            } else {
                Log.w("ScreenCaptureService", "Overlay view not initialized - cannot verify hiding")
            }
            
            pendingScreenshot = true
            Log.d("ScreenCaptureService", "Pending screenshot set to true after 500ms delay")
            
            // Force a new frame to be rendered with the hidden overlay
            try {
                virtualDisplay?.surface?.let { surface ->
                    // Request immediate frame
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        virtualDisplay?.resize(width, height, density)
                        Log.d("ScreenCaptureService", "VirtualDisplay resized to force new frame")
                    }
                }
            } catch (e: Exception) {
                Log.e("ScreenCaptureService", "Error forcing frame update: ${e.message}", e)
            }
            
            // Add a timeout to restore overlay if image never arrives
            Handler(Looper.getMainLooper()).postDelayed({
                if (pendingScreenshot) {
                    Log.w("ScreenCaptureService", "Screenshot timeout - attempting manual image acquisition")
                    pendingScreenshot = false
                    
                    // Try to manually acquire an image as fallback
                    try {
                        val image = imageReader?.acquireLatestImage()
                        if (image != null) {
                            Log.d("ScreenCaptureService", "Manual image acquisition successful after timeout")
                            processImage(image)
                            image.close()
                        } else {
                            Log.w("ScreenCaptureService", "Manual image acquisition failed - restoring overlay")
                            if (isEmulator()) {
                                restoreEmulatorOverlayVisibility(currentInputText)
                            } else {
                                restoreOverlayVisibility(currentInputText)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("ScreenCaptureService", "Error in manual image acquisition: ", e)
                        if (isEmulator()) {
                            restoreEmulatorOverlayVisibility(currentInputText)
                        } else {
                            restoreOverlayVisibility(currentInputText)
                        }
                    }
                }
            }, 5000) // 5 second timeout
        }, 500) // Increased to 500ms delay to ensure overlay is hidden
        
        // Use handler to ensure we're on the correct thread
        handler?.post {
            try {
                Log.d("ScreenCaptureService", "Requesting new frame from VirtualDisplay")
                // Force a new frame to be captured by resizing
                virtualDisplay?.resize(width, height, density)
                
                // Wait for the ImageAvailableListener to handle the new frame
                // The listener will automatically process the image when it becomes available
                Log.d("ScreenCaptureService", "Waiting for ImageAvailableListener to process new frame")
                
            } catch (e: Exception) {
                Log.e("ScreenCaptureService", "Error requesting new frame: ", e)
                pendingScreenshot = false
                // Restore overlay visibility on error
                if (isEmulator()) {
                    restoreEmulatorOverlayVisibility(currentInputText)
                } else {
                    restoreOverlayVisibility(currentInputText)
                }
            }
        }
    }

    // Emulator-specific input overlay hiding
    private fun hideEmulatorInputOverlay() {
        try {
            inputOverlayView?.let { overlay ->
                windowManager.removeView(overlay)
                inputOverlayView = null
                Log.d("ScreenCaptureService", "Emulator input overlay hidden")
            }
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Error hiding emulator input overlay: ${e.message}", e)
        }
    }
}

