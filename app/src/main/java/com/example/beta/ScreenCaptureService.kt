package com.example.beta

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.io.IOException
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit // Import TimeUnit

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
    // Removed unused overlay window variables
    // private lateinit var windowManager: WindowManager
    // private lateinit var overlayView: View
    // private lateinit var layoutParams: WindowManager.LayoutParams
    private val CHANNEL_ID = "ScreenCaptureServiceChannel"
    private val NOTIFICATION_ID = 1
    private var foregroundStarted = false
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null // Correct type
    private var accessibilityService: MyAccessibilityService? = null

    // --- Throttling variables ---
    private var lastCaptureTimeMillis: Long = 0
    private val captureIntervalMillis: Long = TimeUnit.SECONDS.toMillis(8) // 8 second interval
    // Add the screenshot counter
    private var screenshotCounter = 0
    private val maxScreenshots = 4
    // --------------------------


    override fun onCreate() {
        super.onCreate()
        Log.d("ScreenCaptureService", "Service created")
        // Create handler for image reader
        handlerThread = HandlerThread("ScreenCaptureThread").apply {
            start() // Start the thread
        }
        handler = Handler(handlerThread!!.looper) // Use the thread's looper

        mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        // Removed overlay window creation - seems unused
        // createOverlayWindow()
        createNotificationChannel()
        // Pass the service instance to the Application class
        (application as? MyApplication)?.setScreenCaptureService(this)
        Log.d("ScreenCaptureService", "ScreenCaptureService instance set in MyApplication")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("ScreenCaptureService", "onStartCommand received")
        if (intent == null) {
            Log.w("ScreenCaptureService", "Intent is null (Service restarted?). Stopping.")
            stopSelf()
            return START_NOT_STICKY // Don't restart if killed without intent
        }

        resultCode = intent.getIntExtra("resultCode", 0)
        resultData = intent.getParcelableExtra("resultData")

        // Get the width, height, and density from the Intent
        width = intent.getIntExtra("width", 0)
        height = intent.getIntExtra("height", 0)
        density = intent.getIntExtra("density", 0)

        if (resultCode != 0 && resultData != null) {
            Log.d("ScreenCaptureService", "Valid result code and data received.")
            showForegroundNotification()  // Show notification before starting capture
            startCapture() // Start the capture process
        } else {
            Log.e("ScreenCaptureService", "Intent data is missing or invalid. Stopping service.")
            stopSelf() // Stop if data is invalid
            return START_NOT_STICKY
        }

        return START_STICKY
    }


    private fun startCapture() {
        Log.d("ScreenCaptureService", "Attempting to start capture...")
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

        // Get MediaProjection instance
        // Check if mediaProjection is already obtained and valid
        if (mediaProjection == null) {
            mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, resultData!!)
            if (mediaProjection == null) {
                Log.e("ScreenCaptureService", "Failed to get MediaProjection.")
                Toast.makeText(this, "Failed to initialize screen capture", Toast.LENGTH_SHORT).show()
                stopSelf()
                return
            }
            Log.d("ScreenCaptureService", "MediaProjection obtained successfully.")
            // Register callback for stopping projection
            mediaProjection?.registerCallback(mediaProjectionCallback, handler)
        }


        // Create ImageReader
        // Close existing ImageReader if it exists
        imageReader?.close()
        createImageReader() // Create or recreate ImageReader

        // Create VirtualDisplay
        // Release existing virtual display if it exists
        virtualDisplay?.release()
        startVirtualDisplay() // Create or recreate VirtualDisplay

        if (virtualDisplay != null) {
            isCapturing = true
            lastCaptureTimeMillis = 0 // Reset last capture time
            Log.d("ScreenCaptureService", "Screen capture started successfully.")
            Toast.makeText(this, "Screen capture started", Toast.LENGTH_SHORT).show()
        } else {
            Log.e("ScreenCaptureService", "Failed to create VirtualDisplay. Stopping capture.")
            stopCapture() // Clean up resources if virtual display fails
            stopSelf()
        }
    }

    // MediaProjection callback moved outside startCapture
    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w("ScreenCaptureService", "MediaProjection stopped externally.")
            isCapturing = false
            // Clean up resources associated with the stopped projection
            handler?.post {
                virtualDisplay?.release()
                imageReader?.close()
                mediaProjection = null // Clear the reference
                virtualDisplay = null
                imageReader = null
            }
            stopSelf() // Stop the service if projection stops
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
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null, // Callback (optional)
                handler // Handler for callbacks
            )
            Log.d("ScreenCaptureService", "VirtualDisplay created.")
        } catch (e: SecurityException) {
            Log.e("ScreenCaptureService", "SecurityException creating VirtualDisplay: ", e)
            Toast.makeText(this, "Permission issue creating virtual display", Toast.LENGTH_SHORT).show()
            virtualDisplay = null // Ensure it's null on failure
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Exception creating VirtualDisplay: ", e)
            Toast.makeText(this, "Error creating virtual display", Toast.LENGTH_SHORT).show()
            virtualDisplay = null // Ensure it's null on failure
        }
    }


    private fun createImageReader() {
        if (width <= 0 || height <= 0) {
            Log.e("ScreenCaptureService", "Cannot create ImageReader: Invalid dimensions ($width x $height)")
            return
        }
        try {
            imageReader = ImageReader.newInstance(
                width, height, PixelFormat.RGBA_8888, 2 // Max images buffer
            )
            imageReader?.setOnImageAvailableListener(imageAvailableListener, handler)
            Log.d("ScreenCaptureService", "ImageReader created.")
        } catch (e: IllegalArgumentException) {
            Log.e("ScreenCaptureService", "IllegalArgumentException creating ImageReader: ", e)
            Toast.makeText(this, "Error setting up screen reader", Toast.LENGTH_SHORT).show()
            imageReader = null // Ensure it's null on failure
        }
    }

    // ImageAvailableListener moved outside createImageReader
    private val imageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        var image: Image? = null
        try {
            image = reader.acquireLatestImage() // Get the latest image
            if (image != null) {
                // --- Throttling Logic ---
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastCaptureTimeMillis >= captureIntervalMillis) {
                    Log.d("ScreenCaptureService", "Interval passed, processing image.")
                    processImage(image)
                    lastCaptureTimeMillis = currentTime // Update last capture time
                } else {
                    // Optional: Log skipping frame due to throttling
                    // Log.v("ScreenCaptureService", "Skipping frame due to throttling.");
                }
                // -------------------------
            }
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Error acquiring or processing image: ", e)
        } finally {
            image?.close() // VERY IMPORTANT: Always close the image
        }
    }

    private fun processImage(image: Image) {
        if (screenshotCounter >= maxScreenshots) {
            Log.d("ScreenCaptureService", "Maximum number of screenshots reached. Stopping capture.")
            //stopCapture() // Call this function to stop capturing if implemented
            return
        }

        if (width <= 0 || height <= 0) {
            Log.e("ScreenCaptureService", "Cannot process image: Invalid dimensions ($width x $height)")
            return
        }

        val planes = image.planes
        val buffer: ByteBuffer = planes[0].buffer
        val pixelStride: Int = planes[0].pixelStride
        val rowStride: Int = planes[0].rowStride
        val rowPadding: Int = rowStride - pixelStride * width

        var bitmap: Bitmap? = null
        try {
            bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)

            // If padding exists, crop the bitmap to the correct width
            if (rowPadding > 0) {
                val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                bitmap.recycle() // Recycle original bitmap
                bitmap = croppedBitmap
            }

            Log.d("ScreenCaptureService", "Bitmap created from image buffer.")
            // Define a filename for the screenshot being processed
            val filename = "screenshot_for_upload_${System.currentTimeMillis()}.jpg"

            // Use MyApplication to save the bitmap
            (application as? MyApplication)?.saveScreenshot(bitmap)
            uploadScreenshotAndProcess(this, bitmap, filename)

            // Increment screenshot counter
            screenshotCounter++


        } catch (e: OutOfMemoryError) {
            Log.e("ScreenCaptureService", "OutOfMemoryError processing image: ", e)
            Toast.makeText(this, "Low memory, cannot process screenshot", Toast.LENGTH_SHORT).show()
            bitmap?.recycle()
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Error processing image to bitmap: ", e)
            bitmap?.recycle()
        }
    }

    // Removed unused overlay window methods
    // private fun createOverlayWindow() { ... }

    private fun createNotificationChannel() {
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

    private fun showForegroundNotification() {
        if (foregroundStarted) {
            Log.d("ScreenCaptureService", "Foreground notification already shown.")
            return
        }
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
            action = ACTION_STOP_CAPTURE // Define a custom action
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopSelfIntent, pendingIntentFlags
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Capture Active")
            .setContentText("Capturing screen every second...")
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Replace with your actual icon
            .setContentIntent(pendingIntent) // Intent when notification is tapped
            .addAction(R.drawable.ic_launcher_foreground, "Stop Capture", stopPendingIntent) // Add stop action
            .setOngoing(true) // Makes it non-dismissible
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW) // Use LOW for background tasks
            .build()

        try {
            startForeground(NOTIFICATION_ID, notification)
            foregroundStarted = true
            Log.d("ScreenCaptureService", "Started foreground service with notification.")
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Error starting foreground service: ", e)
            // Handle specific exceptions like ForegroundServiceStartNotAllowedException on Android 12+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                Toast.makeText(this, "App cannot start capture from background.", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Could not start capture service.", Toast.LENGTH_SHORT).show()
            }
            stopSelf() // Stop service if foreground fails
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

    override fun onDestroy() {
        Log.d("ScreenCaptureService", "Service destroyed.")
        stopCapture() // Ensure all resources are released
        // Quit the handler thread's looper safely
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
        // Clear service reference in Application
        (application as? MyApplication)?.setScreenCaptureService(null)
        Log.d("ScreenCaptureService", "Cleaned up resources and handler thread.")
        super.onDestroy()
    }

    // Companion object for constants like the action string
    companion object {
        const val ACTION_STOP_CAPTURE = "com.example.beta.STOP_CAPTURE"
        // Consider adding actions for START if needed, though currently handled by intent extras
    }
}
