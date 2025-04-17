package com.example.beta

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts // Import for ActivityResultContracts

class MainActivity : AppCompatActivity() {

    private val MEDIA_PROJECTION_REQUEST_CODE = 100
    private val OVERLAY_PERMISSION_REQUEST_CODE = 101
    private val STORAGE_PERMISSION_REQUEST_CODE = 102 // Added constant for storage permission
    private lateinit var startCaptureButton: Button
    private lateinit var mediaProjectionManager: MediaProjectionManager

    // Declare the ActivityResultLauncher for storage permission
    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                Log.d("MainActivity", "storagePermissionLauncher: Storage permission granted")
                checkOverlayPermissionAndStartCapture() // Proceed to check overlay permission
            } else {
                Log.w("MainActivity", "storagePermissionLauncher: Storage permission denied by user")
                Toast.makeText(
                    this,
                    "Write external storage permission is required",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        startCaptureButton = findViewById(R.id.startCaptureButton)
        mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        startCaptureButton.setOnClickListener {
            checkPermissionsAndStartCapture()
        }
    }

    private fun checkPermissionsAndStartCapture() {
        Log.d("MainActivity", "checkPermissionsAndStartCapture: Checking storage permission")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { // Android 11+
            if (!Environment.isExternalStorageManager()) {
                Log.d("MainActivity", "checkPermissionsAndStartCapture: Storage permission not granted, requesting")
                requestManageStoragePermission() // Redirect to settings instead
            } else {
                Log.d("MainActivity", "checkPermissionsAndStartCapture: Storage permission granted, checking overlay permission")
                checkOverlayPermissionAndStartCapture()
            }
        } else { // Android 10 and below (uses WRITE_EXTERNAL_STORAGE)
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.d("MainActivity", "checkPermissionsAndStartCapture: Requesting storage permission")
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                checkOverlayPermissionAndStartCapture()
            }
        }
    }


    private fun requestManageStoragePermission() {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
        intent.data = android.net.Uri.parse("package:$packageName")
        startActivity(intent)
    }



    private fun checkOverlayPermissionAndStartCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Log.d("MainActivity", "checkOverlayPermissionAndStartCapture: Checking overlay permission")
            if (!Settings.canDrawOverlays(this)) {
                Log.d("MainActivity", "checkOverlayPermissionAndStartCapture: Overlay permission not granted, requesting")
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + packageName)
                )
                startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE)
            } else {
                Log.d(
                    "MainActivity",
                    "checkOverlayPermissionAndStartCapture: Overlay permission granted, starting media projection"
                )
                startMediaProjection()
            }
        } else {
            Log.d("MainActivity", "checkOverlayPermissionAndStartCapture: SDK < M, starting media projection")
            startMediaProjection()
        }
    }

    private fun startMediaProjection() {
        Log.d("MainActivity", "startMediaProjection: Starting media projection")
        val createScreenCaptureIntent = mediaProjectionManager.createScreenCaptureIntent()
        startActivityForResult(
            createScreenCaptureIntent,
            MEDIA_PROJECTION_REQUEST_CODE
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.d("MainActivity", "onActivityResult: requestCode = $requestCode, resultCode = $resultCode")
        if (requestCode == MEDIA_PROJECTION_REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                Log.d("MainActivity", "onActivityResult: Media projection successful, starting service")
                val serviceIntent = Intent(this, ScreenCaptureService::class.java)
                serviceIntent.putExtra("resultCode", resultCode)
                serviceIntent.putExtra("resultData", data)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            } else {
                Log.w("MainActivity", "onActivityResult: Media projection failed")
                Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
            }
        } else if (requestCode == OVERLAY_PERMISSION_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(this)) {
                    Log.w("MainActivity", "onActivityResult: Overlay permission denied by user")
                    Toast.makeText(
                        this,
                        "Overlay permission is required to capture screen",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Log.d("MainActivity", "onActivityResult: Overlay permission granted by user")
                    startMediaProjection()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("MainActivity", "onDestroy")
    }
}