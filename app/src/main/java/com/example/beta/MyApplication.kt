package com.example.beta

import android.app.Application
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import android.content.ContentValues
import android.content.Context
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import android.net.Uri // Ensure Uri is imported
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path

class MyApplication : Application() {

    companion object {
        private const val API_VERSION = "1.0"
    }

    private var screenCaptureService: ScreenCaptureService? = null
    private var currentActivity: ComponentActivity? = null
    // OverlayInputService removed - not available in current version
    // Services removed - not available in current version
    // private var buttonHighlightService: ButtonHighlightService? = null
    // private var accessibilityService: AccessibilityService? = null
    // private var automatedActionService: AutomatedActionService? = null

    override fun onCreate() {
        super.onCreate()
        // Initialize anything global here
        Log.d("MyApplication", "Application created")
    }

    // Saves the provided bitmap to the MediaStore
    fun saveScreenshot(bitmap: Bitmap?) {
        if (bitmap == null) {
            Log.e("MyApplication", "saveScreenshot: Bitmap is null, cannot save.")
            return
        }

        val context = applicationContext
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "screenshot_$timestamp.png"
        val mimeType = "image/png"

        Log.d("MyApplication", "Attempting to save screenshot: $fileName")
        saveImageToMediaStore(context, bitmap, fileName, mimeType)
    }

    private fun saveImageToMediaStore(
        context: Context,
        bitmap: Bitmap,
        filename: String,
        mimeType: String = "image/png"
    ) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            // Specify the directory for the image in Scoped Storage
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MyAppScreenshots") // Example folder in Pictures
                put(MediaStore.Images.Media.IS_PENDING, 1) // Mark as pending
            }
        }

        // Get the correct URI for saving the image based on Android version
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val contentResolver = context.contentResolver
        var uri: Uri? = null // Declare uri variable outside the try block

        try {
            uri = contentResolver.insert(collection, values)
            if (uri == null) {
                Log.e("MediaStore", "Failed to create new MediaStore record.")
                showToast("Failed to save screenshot (insert failed)")
                return // Exit if URI is null
            }

            // Open an output stream using the URI
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            }

            // Finalize the entry if on Android Q or higher
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0) // Mark the image as no longer pending
                val updatedRows = contentResolver.update(uri, values, null, null)
                if (updatedRows > 0) {
                    Log.d("MediaStore", "MediaStore entry marked as not pending.")
                } else {
                    Log.w("MediaStore", "Failed to update MediaStore entry to not pending.")
                }
            }

            Log.d("MediaStore", "Image saved successfully to URI: $uri")
            showToast("Screenshot saved!") // Show success message

        } catch (e: IOException) {
            Log.e("MediaStore", "IOException saving image: ${e.message}", e)
            if (uri != null) {
                // Clean up the MediaStore entry if an error occurs during saving
                contentResolver.delete(uri, null, null)
                Log.d("MediaStore", "Attempted to delete pending MediaStore entry due to error.")
            }
            showToast("Failed to save screenshot (IO error)")
        } catch (e: Exception) {
            Log.e("MediaStore", "Unexpected error saving image: ${e.message}", e)
            if (uri != null) {
                contentResolver.delete(uri, null, null) // Clean up for any other exceptions too
                Log.d("MediaStore", "Attempted to delete pending MediaStore entry due to unexpected error.")
            }
            showToast("Failed to save screenshot (unknown error)")
        }
    }

    // Sets the ScreenCaptureService instance (called from the service)
    fun setScreenCaptureService(service: ScreenCaptureService?) { // Allow null for cleanup
        this.screenCaptureService = service
        Log.d("MyApplication", "ScreenCaptureService instance ${if (service == null) "cleared" else "set"}.")
    }

    // Gets the ScreenCaptureService instance (potentially used by AccessibilityService or elsewhere)
    fun getScreenCaptureService(): ScreenCaptureService? {
        return screenCaptureService
    }

    // OverlayInputService methods removed - service not available in current version

    // ButtonHighlightService methods removed - service not available in current version

    // AccessibilityService methods
    private var accessibilityService: MyAccessibilityService? = null
    
    fun setAccessibilityService(service: MyAccessibilityService?) {
        this.accessibilityService = service
        Log.d("MyApplication", "AccessibilityService instance ${if (service == null) "cleared" else "set"}.")
        if (service != null) {
            Log.d("MyApplication", "AccessibilityService registered - Service class: ${service.javaClass.simpleName}")
        }
    }
    
    fun getAccessibilityService(): MyAccessibilityService? {
        val service = accessibilityService
        Log.d("MyApplication", "getAccessibilityService called - Service available: ${service != null}")
        if (service != null) {
            Log.d("MyApplication", "Returning AccessibilityService: ${service.javaClass.simpleName}")
        }
        return service
    }

    // AutomatedActionService methods removed - service not available in current version

    // --- Activity Lifecycle Management ---
    // Note: Holding direct activity references in Application can cause leaks.
    // Consider using ActivityLifecycleCallbacks or other patterns if needed.
    // The 'captureScreenshot' method below relies on this 'currentActivity'.
    fun registerActivity(activity: ComponentActivity) {
        Log.d("MyApplication", "Registering activity: ${activity.localClassName}")
        currentActivity = activity
    }

    fun unregisterActivity(activity: ComponentActivity) {
        // Only unregister if it's the currently registered activity
        if (currentActivity == activity) {
            Log.d("MyApplication", "Unregistering activity: ${activity.localClassName}")
            currentActivity = null
        } else {
            Log.d("MyApplication", "Ignoring unregister request from non-current activity: ${activity.localClassName}")
        }
    }
    // -------------------------------------


    // --- Method to capture APP'S VIEW (NOT full screen) ---
    // This captures only the view of the 'currentActivity'.
    // It's different from the MediaProjection capture in ScreenCaptureService.
    // Consider if this method is still needed for your use case.
    fun captureScreenshot(callback: (Bitmap?) -> Unit) {
        val activity = currentActivity
        if (activity == null) {
            Log.e("MyApplication", "captureScreenshot (App View): No current activity registered.")
            callback(null)
            return
        }

        Log.d("MyApplication", "Attempting to capture view of activity: ${activity.localClassName}")
        try {
            val view = activity.window.decorView.rootView
            if (view.width <= 0 || view.height <= 0) {
                Log.e("MyApplication", "captureScreenshot (App View): Invalid view dimensions.")
                callback(null)
                return
            }
            // Create bitmap and draw the view onto the canvas
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            view.draw(canvas)
            Log.d("MyApplication", "captureScreenshot (App View): Successfully captured view.")
            callback(bitmap)
        } catch (e: Exception) {
            Log.e("MyApplication", "captureScreenshot (App View): Error capturing view: ${e.message}", e)
            callback(null)
        }
    }
    // -------------------------------------------------------


    // Get client version (app version or fallback to 1.0)
    fun getClientVersion(): String {
        return try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            packageInfo.versionName ?: "1.0"
        } catch (e: Exception) {
            Log.w("MyApplication", "Could not get app version, using fallback: ${e.message}")
            "1.0"
        }
    }

    // Get API version constant
    fun getApiVersion(): String = API_VERSION

    // Helper function to show toasts on the main thread
    private fun showToast(message: String) {
        // Ensure toast is shown on the main UI thread
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }
}
