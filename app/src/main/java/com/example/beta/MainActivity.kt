package com.example.beta

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var captureScreenButton: Button
    private lateinit var textRecognitionButton: Button
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private val screenCaptureRequestCode = 100
    private var isCapturing = false // Track capture state

    // Declare the screenCaptureResult as a lateinit var
    private lateinit var screenCaptureResult: ActivityResultLauncher<Intent>

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
        textRecognitionButton = findViewById(R.id.textRecognitionButton)

        // Get MediaProjectionManager
        mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Initialize ActivityResultLauncher
        screenCaptureResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                Log.d("MainActivity", "Media projection successful, starting service")

                // Get display metrics here to pass it along to the service
                val displayMetrics = DisplayMetrics()
                windowManager.defaultDisplay.getMetrics(displayMetrics)

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
            } else {
                Log.e("MainActivity", "Media projection failed")
                Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
                isCapturing = false
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

        // AutomatedActionTestActivity removed - not available in current version

        (application as MyApplication).registerActivity(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        (application as MyApplication).unregisterActivity(this)
    }

    private fun checkPermissionsAndStartCapture() {
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
        checkOverlayPermissionAndStartCapture()
    }

    private fun checkOverlayPermissionAndStartCapture() {
        Log.d("MainActivity", "checkOverlayPermissionAndStartCapture: Checking overlay permission")
        // Check for overlay permission
        if (!Settings.canDrawOverlays(this)) {
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