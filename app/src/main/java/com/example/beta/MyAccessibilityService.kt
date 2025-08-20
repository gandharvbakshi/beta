package com.example.beta

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

class MyAccessibilityService : AccessibilityService() {

    private var screenCaptureService: ScreenCaptureService? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("MyAccessibilityService", "Service connected")
        //Set service info.
        val info = AccessibilityServiceInfo()
        info.eventTypes =
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or AccessibilityEvent.TYPE_VIEW_CLICKED or AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_VIEW_FOCUSED or AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        serviceInfo = info
        // Get the ScreenCaptureService instance.
        (application as? MyApplication)?.let {
            screenCaptureService = it.getScreenCaptureService()
            screenCaptureService?.setAccessibilityService(this) // Pass the accessibility service instance
        }
        
        // Register this service with the application
        (application as? MyApplication)?.setAccessibilityService(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        Log.d(
            "MyAccessibilityService",
            "onAccessibilityEvent: eventType = ${event.eventType}, event = ${event}"
        ) //Added event

        // Check for relevant events to trigger screenshot
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,  //handle window state changed.
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> { // Added this condition
                performScreenshot()
            }
        }
    }

    private fun performScreenshot() {
        if (screenCaptureService == null) {
            Log.e("MyAccessibilityService", "ScreenCaptureService is null")
            Toast.makeText(this, "ScreenCaptureService is not available", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                //Use method in service.
                (application as? MyApplication)?.let {
                    it.captureScreenshot { bitmap ->
                        if (bitmap != null) {
                            //  Save the bitmap.
                            it.saveScreenshot(bitmap)
                        } else {
                            Log.e("MyAccessibilityService", "Failed to capture screenshot")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MyAccessibilityService", "Error capturing screenshot: ${e.message}")
            Toast.makeText(this, "Error capturing screenshot: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onInterrupt() {
        Log.d("MyAccessibilityService", "onInterrupt")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("MyAccessibilityService", "onDestroy")
    }
}
