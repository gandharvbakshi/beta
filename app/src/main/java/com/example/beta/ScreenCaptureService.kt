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
import java.text.SimpleDateFormat
import java.util.*
import android.accessibilityservice.AccessibilityService
import android.hardware.display.VirtualDisplay
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ScreenCaptureService : Service() {

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var handler: Handler? = null
    private var width: Int = 0
    private var height: Int = 0
    private var density: Int = 0
    private var resultCode = 0  // Store these!
    private var resultData: Intent? = null // Store these!
    private var isCapturing = false
    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var layoutParams: WindowManager.LayoutParams
    private val CHANNEL_ID = "ScreenCaptureServiceChannel"
    private val NOTIFICATION_ID = 1
    private var foregroundStarted = false
    private var virtualDisplay: VirtualDisplay? = null // Store the VirtualDisplay
    private var accessibilityService: MyAccessibilityService? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("ScreenCaptureService", "Service created")
        // Create handler for image reader
        val handlerThread = HandlerThread("ScreenCaptureThread")
        handlerThread.start()
        handler = Handler(handlerThread.looper)

        mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        createOverlayWindow()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("ScreenCaptureService", "Service started")
        if (intent != null) {
            resultCode = intent.getIntExtra("resultCode", 0)
            resultData = intent.getParcelableExtra("resultData")
            if (resultCode != 0 && resultData != null) {
                showForegroundNotification()
                startCapture() // Call startCapture directly.
            } else {
                Log.e("ScreenCaptureService", "Intent data is missing or invalid")
                stopSelf()
            }
        } else {
            // Handle the case where the intent is null (service restarted)
            Log.w("ScreenCaptureService", "Intent is null.  Restarting capture.")
            // Check if we have stored data to restart the capture
            if (resultCode != 0 && resultData != null) {
                showForegroundNotification()
                startCapture() // Call startCapture directly.
            } else {
                Log.e(
                    "ScreenCaptureService",
                    "Service restarted without valid data.  Cannot capture."
                )
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startCapture() {
        Log.d("ScreenCaptureService", "startCapture called")
        if (resultCode != 0 && resultData != null) {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val displayMetrics = DisplayMetrics()
            windowManager.defaultDisplay.getMetrics(displayMetrics)
            width = displayMetrics.widthPixels
            height = displayMetrics.heightPixels
            density = displayMetrics.densityDpi

            // **Important:** Create MediaProjection and VirtualDisplay here, every time.
            if (mediaProjection == null) {
                mediaProjection =
                    mediaProjectionManager?.getMediaProjection(resultCode, resultData!!)
                if (mediaProjection == null) {
                    Log.e("ScreenCaptureService", "Failed to get MediaProjection")
                    Toast.makeText(
                        this,
                        "Failed to start screen capture",
                        Toast.LENGTH_SHORT
                    ).show()
                    stopSelf()
                    return
                }
            }

            createImageReader()
            startMediaProjection()  //start it
            isCapturing = true
            Log.d("ScreenCaptureService", "Media projection started")
            Toast.makeText(this, "Screen capture started", Toast.LENGTH_SHORT).show()

        } else {
            Log.e("ScreenCaptureService", "Result code or data is invalid")
            Toast.makeText(this, "Invalid result from MediaProjection", Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }

    private fun startMediaProjection() {
        if (mediaProjection != null && imageReader != null) {
            try {
                mediaProjection?.registerCallback(object :
                    MediaProjection.Callback() {
                    override fun onStop() {
                        Log.d("ScreenCaptureService", "Media projection stopped")
                        isCapturing = false
                        stopSelf()
                        handler?.post {
                            imageReader?.close()
                            mediaProjection?.unregisterCallback(this)
                            mediaProjection?.stop()
                        }
                    }
                }, handler)

                // **Important:** Create a new VirtualDisplay every time.
                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "ScreenCapture",
                    width,
                    height,
                    density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader?.surface,
                    null,
                    handler
                )
                if (virtualDisplay == null) {
                    Log.e("ScreenCaptureService", "Failed to create VirtualDisplay")
                    Toast.makeText(this, "Failed to create VirtualDisplay", Toast.LENGTH_SHORT).show()
                    stopSelf()
                }

            } catch (e: SecurityException) {
                Log.e("ScreenCaptureService", "SecurityException: ", e)
                Toast.makeText(this, "Security Exception: ${e.message}", Toast.LENGTH_SHORT)
                    .show()
                stopSelf()
            } catch (e: Exception) {
                Log.e("ScreenCaptureService", "Exception starting projection: ", e)
                Toast.makeText(this, "Error starting capture: ${e.message}", Toast.LENGTH_SHORT)
                    .show()
                stopSelf()
            }
        }
    }

    private fun createImageReader() {
        imageReader = ImageReader.newInstance(
            width, height, PixelFormat.RGBA_8888, 2
        ) // Capture 2 frames at a time
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                //processImage(image)
                image.close()
            }
        }, handler)
    }

    fun captureScreenshot(callback: (Bitmap?) -> Unit) {
        if (isCapturing && imageReader != null) {
            handler?.post {
                val image = imageReader?.acquireLatestImage()
                if (image != null) {
                    val bitmap = imageToBitmap(image)
                    image.close()
                    callback(bitmap)
                } else {
                    callback(null)
                }
            }
        } else {
            Log.e("ScreenCaptureService", "Not capturing or ImageReader is null")
            callback(null)
        }
    }


    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - width * pixelStride
        var bitmap: Bitmap? = null
        try {
            // Create bitmap, copying directly from the Image's ByteBuffer
            bitmap = Bitmap.createBitmap(
                width, height, Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
        } catch (e: IllegalArgumentException) {
            Log.e("ScreenCaptureService", "IllegalArgumentException: ", e)
            return null
        } catch (e: OutOfMemoryError) {
            Log.e("ScreenCaptureService", "OutOfMemoryError: ", e)
            return null
        }

        return bitmap
    }

    private fun createOverlayWindow() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null) // Create a simple layout
        val LAYOUT_TYPE: Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            LAYOUT_TYPE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        layoutParams.gravity = Gravity.TOP or Gravity.START
        layoutParams.x = 0
        layoutParams.y = 100 // Adjust as necessary
        try {
            windowManager.addView(overlayView, layoutParams)
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Error adding overlay view: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Screen Capture Service",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun showForegroundNotification() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Capture")
            .setContentText("Capturing screen...")
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Replace with your icon
            .setContentIntent(pendingIntent)
            .setOngoing(true) // Make it persistent!
            .setPriority(NotificationCompat.PRIORITY_HIGH) // High priority for fewer interruptions
            .build()
        startForeground(NOTIFICATION_ID, notification)
        foregroundStarted = true
    }

    override fun onBind(intent: Intent?): IBinder? {
        // This service is not designed for binding, so return null.
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("ScreenCaptureService", "Service destroyed")
        isCapturing = false
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        try {
            if (mediaProjection != null) {
                mediaProjection?.stop()
                mediaProjection = null
            }
            if (imageReader != null) {
                imageReader?.close()
                imageReader = null
            }
            if (virtualDisplay != null) { // Release the virtual display
                virtualDisplay?.release()
                virtualDisplay = null
            }
            if (::windowManager.isInitialized && ::overlayView.isInitialized) {
                windowManager.removeView(overlayView)
            }
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "Error in onDestroy: ", e)
        }
        handler?.looper?.quitSafely()
    }

    fun setAccessibilityService(service: MyAccessibilityService) {
        this.accessibilityService = service
    }
}
