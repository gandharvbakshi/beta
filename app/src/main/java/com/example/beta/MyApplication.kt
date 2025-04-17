package com.example.beta

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class MyApplication : Application() {

    private var screenCaptureService: ScreenCaptureService? = null

    override fun onCreate() {
        super.onCreate()
        //  Initialize anything that needs to run when the application starts
    }

    fun saveScreenshot(bitmap: Bitmap) {
        Log.d("MyApplication", "saveScreenshot() called") // Add this line
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "screenshot_$timestamp.png"
        val directory = File(getExternalFilesDir(null), "screenshots") // Use getExternalFilesDir
        if (!directory.exists()) {
            if (!directory.mkdirs()) {
                Log.e("MyApplication", "Failed to create directory")
                return  // IMPORTANT:  Return on failure!
            }
        }
        val file = File(directory, fileName)
        var fos: FileOutputStream? = null
        try {
            fos = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
            Log.d("MyApplication", "Screenshot saved: ${file.absolutePath}")
            //  Optionally, notify MediaStore about the new file:
            //  This is important for the image to show up in the gallery
            /*
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                put(MediaStore.Images.Media.DATA, file.absolutePath) // deprecated in API 29
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.OWNER_PACKAGE_NAME, applicationContext.packageName)
                }
            }
            val contentResolver = applicationContext.contentResolver
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                Log.d("MyApplication", "MediaStore URI: $uri")
            } else {
                Log.e("MyApplication", "Failed to insert into MediaStore")
            }
            */

        } catch (e: IOException) {
            Log.e("MyApplication", "Error saving screenshot: ${e.message}")
        } finally {
            try {
                fos?.close()
            } catch (e: IOException) {
                Log.e("MyApplication", "Error closing stream: ${e.message}")
            }
        }
    }

    fun setScreenCaptureService(service: ScreenCaptureService) {
        this.screenCaptureService = service
    }

    fun captureScreenshot(callback: (Bitmap?) -> Unit) {
        screenCaptureService?.captureScreenshot(callback) ?: run {
            Log.e("MyApplication", "ScreenCaptureService is null")
            callback(null) // Ensure the callback is always called
        }
    }

    fun getScreenCaptureService(): ScreenCaptureService? {
        return screenCaptureService
    }
}
