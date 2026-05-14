package com.example.beta

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityWindowInfo

class MyAccessibilityService : AccessibilityService() {

    private var screenCaptureService: ScreenCaptureService? = null
    private var lastTreeData: String = ""
    private var lastAppName: String = ""
    private var lastNonBlinkitStatusLogMs: Long = 0L
    
    // Get the currently active app package
    val activeAppPackage: String?
        get() = try {
            rootInActiveWindow?.packageName?.toString()
        } catch (e: Exception) {
            Log.e("MyAccessibilityService", "Error getting active app package: ${e.message}", e)
            null
        }

    // Get the last captured tree data
    fun getLastTreeData(): String = lastTreeData
    
    // Get the last app name
    fun getLastAppName(): String = lastAppName

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("MyAccessibilityService", "Service connected")
        //Set service info.
        val info = AccessibilityServiceInfo()
        info.eventTypes =
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or AccessibilityEvent.TYPE_VIEW_CLICKED or AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_VIEW_FOCUSED or AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or 
                     AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or 
                     AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                     AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        serviceInfo = info
        
        // Verify the flag is actually set at runtime
        val hasWin = (serviceInfo.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS) != 0
        Log.d("MyAccessibilityService", "Has RETRIEVE_INTERACTIVE_WINDOWS: $hasWin  flags=${serviceInfo.flags}")
        if (!hasWin) {
            Log.e("MyAccessibilityService", "CRITICAL: FLAG_RETRIEVE_INTERACTIVE_WINDOWS not set! This will prevent window access.")
        }
        // Register this accessibility service with MyApplication
        (application as? MyApplication)?.let {
            it.setAccessibilityService(this)
        }
        
        // Connect to ScreenCaptureService if it's available
        val myApp = application as? MyApplication
        myApp?.getScreenCaptureService()?.let { screenCaptureService ->
            screenCaptureService.setAccessibilityService(this)
            this.screenCaptureService = screenCaptureService
            Log.d("MyAccessibilityService", "Connected to ScreenCaptureService")
        } ?: run {
            Log.d("MyAccessibilityService", "ScreenCaptureService not available yet, will connect when available")
            // Try to connect again after a delay
            Handler(Looper.getMainLooper()).postDelayed({
                attemptReconnectToScreenCaptureService()
            }, 2000) // 2 second delay
        }
    }
    
    // Method to allow ScreenCaptureService to connect to this service
    fun connectScreenCaptureService(service: ScreenCaptureService?) {
        screenCaptureService = service
        Log.d("MyAccessibilityService", "ScreenCaptureService connected: ${service != null}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString()
        val eventType = event.eventType

        if (packageName == OWN_PACKAGE) {
            Log.d("MyAccessibilityService", "Ignoring own app event: $eventType")
            return
        }

        if (packageName != BLINKIT_PACKAGE) {
            logNonBlinkitStatus(packageName, eventType)
            return
        }

        Log.d("MyAccessibilityService", "Blinkit event detected: $eventType")

        // Log ScreenCaptureService status for debugging
        if (screenCaptureService == null) {
            Log.d("MyAccessibilityService", "ScreenCaptureService status: null (waiting for connection)")
        } else {
            Log.d("MyAccessibilityService", "ScreenCaptureService status: connected")
        }

        // Check for relevant events to trigger screenshot
        when (eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,  //handle window state changed.
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> { // Added this condition
                performScreenshot()
            }
        }
    }

    private fun logNonBlinkitStatus(packageName: String?, eventType: Int) {
        val now = System.currentTimeMillis()
        if (now - lastNonBlinkitStatusLogMs < NON_BLINKIT_LOG_INTERVAL_MS) {
            return
        }
        lastNonBlinkitStatusLogMs = now
        Log.d(
            "MyAccessibilityService",
            "Ignoring non-Blinkit event: package=$packageName, eventType=$eventType, captureConnected=${screenCaptureService != null}"
        )
    }

    private fun performScreenshot() {
        // Check if screenshots are enabled via ScreenCaptureService
        val currentScreenCaptureService = screenCaptureService
        if (currentScreenCaptureService != null) {
            if (!currentScreenCaptureService.isScreenshotEnabled()) {
                // Log.d("MyAccessibilityService", "Screenshots disabled, skipping capture")
                return
            }
        }
        
        if (currentScreenCaptureService == null) {
            Log.d("MyAccessibilityService", "ScreenCaptureService is null - attempting to reconnect")
            // Try to reconnect to ScreenCaptureService
            attemptReconnectToScreenCaptureService()
            return
        }
        
        // Check if the service can actually capture screenshots
        if (!currentScreenCaptureService.canCapture()) {
            Log.w("MyAccessibilityService", "ScreenCaptureService cannot capture screenshots")
            Log.w("MyAccessibilityService", "Capture status: ${currentScreenCaptureService.getCaptureStatus()}")
            Log.w("MyAccessibilityService", "This usually means the MediaProjection session ended. User needs to restart screen capture from MainActivity.")
            return
        }

        try {
            // Capture tree data first, then trigger screenshot
            showBlinkitTree()
            
            // Wait a bit for tree data to be captured, then trigger screenshot
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                // Store the captured tree data in ScreenCaptureService
                val treeData = getLastTreeData()
                val appName = getLastAppName()
                
                // Store data in ScreenCaptureService for backend processing
                currentScreenCaptureService.storeTreeData(treeData, appName)
                
                Log.d("MyAccessibilityService", "📤 CAPTURED DATA - Tree length: ${treeData.length}, App: $appName")
                
                currentScreenCaptureService.triggerScreenshot()
            }, 500) // 500ms delay for tree data capture
            
        } catch (e: Exception) {
            Log.e("MyAccessibilityService", "Error triggering screenshot: ${e.message}")
        }
    }
    
    /**
     * Attempts to reconnect to ScreenCaptureService if it's not available
     */
    private fun attemptReconnectToScreenCaptureService() {
        try {
            val myApp = application as? MyApplication
            val screenCaptureService = myApp?.getScreenCaptureService()
            if (screenCaptureService != null) {
                screenCaptureService.setAccessibilityService(this)
                this.screenCaptureService = screenCaptureService
                Log.d("MyAccessibilityService", "Successfully reconnected to ScreenCaptureService")
            } else {
                Log.d("MyAccessibilityService", "ScreenCaptureService still not available - will retry later")
            }
        } catch (e: Exception) {
            Log.e("MyAccessibilityService", "Error attempting to reconnect to ScreenCaptureService: ${e.message}")
        }
    }

    override fun onInterrupt() {
        Log.d("MyAccessibilityService", "onInterrupt")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("MyAccessibilityService", "onDestroy")
        
        // Unregister this accessibility service from MyApplication
        try {
            (application as? MyApplication)?.setAccessibilityService(null)
        } catch (e: Exception) {
            Log.e("MyAccessibilityService", "Error unregistering accessibility service: ${e.message}", e)
        }
    }

    /**
     * Public method to manually trigger Blinkit tree view (for testing)
     */
    fun showBlinkitTree() {
        logBlinkitTree()
    }

    /**
     * Helper method to find the Blinkit app root by scanning all windows
     * This is more reliable than rootInActiveWindow when our overlay is active
     */
    private fun findBlinkitAppRoot(): AccessibilityNodeInfo? {
        try {
            Log.d("MyAccessibilityService", "Scanning all windows for Blinkit app...")
            
            // Get all windows
            val allWindows = windows ?: return null
            Log.d("MyAccessibilityService", "Found ${allWindows.size} windows")
            
            // Use the improved scanning approach
            for (window in allWindows) {
                if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    val root = window.root ?: continue
                    val packageName = root.packageName?.toString() ?: continue
                    Log.d("MyAccessibilityService", "Window type: ${window.type}, Package: $packageName")
                    
                    if (packageName == "com.grofers.customerapp") {
                        Log.d("MyAccessibilityService", "Found Blinkit app in application window")
                        return root
                    }
                }
            }
            
            Log.d("MyAccessibilityService", "Blinkit app not found in any application window")
            return null
            
        } catch (e: Exception) {
            Log.e("MyAccessibilityService", "Error scanning windows: ${e.message}", e)
            return null
        }
    }

    /**
     * Creates a rich tree visualization of the Blinkit app's accessibility tree
     */
    private fun logBlinkitTree() {
        Log.d("MyAccessibilityService", "=== ENTERING logBlinkitTree METHOD ===")
        
        // Debug accessibility service state
        Log.d("MyAccessibilityService", "Accessibility Service State:")
        Log.d("MyAccessibilityService", "  • Service Info: ${serviceInfo}")
        Log.d("MyAccessibilityService", "  • Flags: ${serviceInfo?.flags}")
        Log.d("MyAccessibilityService", "  • Event Types: ${serviceInfo?.eventTypes}")
        Log.d("MyAccessibilityService", "  • Feedback Type: ${serviceInfo?.feedbackType}")
        
        try {
            Log.d("MyAccessibilityService", "Trying to find Blinkit app root...")
            
            // First try rootInActiveWindow
            var rootNode = rootInActiveWindow
            Log.d("MyAccessibilityService", "rootInActiveWindow result: ${rootNode != null}")
            
            // If rootInActiveWindow is null, try scanning all windows
            if (rootNode == null) {
                Log.d("MyAccessibilityService", "rootInActiveWindow is null - trying window scanning...")
                rootNode = findBlinkitAppRoot()
                
                if (rootNode != null) {
                    Log.d("MyAccessibilityService", "Found Blinkit app via window scanning")
                } else {
                    Log.w("MyAccessibilityService", "Blinkit app not found in any window - waiting and retrying...")
                    
                    // Wait 500ms and try both methods again
                    Handler(Looper.getMainLooper()).postDelayed({
                        Log.d("MyAccessibilityService", "Retrying after delay...")
                        
                        // Try rootInActiveWindow first
                        var retryNode = rootInActiveWindow
                        if (retryNode == null) {
                            // Try window scanning
                            retryNode = findBlinkitAppRoot()
                        }
                        
                        if (retryNode != null) {
                            Log.d("MyAccessibilityService", "Retry successful - found Blinkit app")
                            processBlinkitTree(retryNode)
                        } else {
                            Log.w("MyAccessibilityService", "Retry failed - still no Blinkit app found")
                        }
                    }, 500)
                    
                    Log.d("MyAccessibilityService", "=== EXITING logBlinkitTree METHOD (will retry) ===")
                    return
                }
            }
            
            // Process the tree if we have a root node
            if (rootNode != null) {
                processBlinkitTree(rootNode)
            }
            
        } catch (e: Exception) {
            Log.e("MyAccessibilityService", "Error creating tree view: ${e.message}", e)
            e.printStackTrace()
        }
        
        Log.d("MyAccessibilityService", "=== EXITING logBlinkitTree METHOD ===")
    }
    
    /**
     * Helper method to process the Blinkit tree once we have a valid root node
     */
                private fun processBlinkitTree(rootNode: AccessibilityNodeInfo) {
                try {
                    val packageName = rootNode.packageName?.toString()

                    if (packageName != "com.grofers.customerapp") {
                        Log.d("MyAccessibilityService", "Not Blinkit app - current package: $packageName")
                        return
                    }

                    Log.d("MyAccessibilityService", "Processing Blinkit tree view")
            
            // Store app name
            lastAppName = "Blinkit"
            
            // Create a StringBuilder to capture tree data
            val treeBuilder = StringBuilder()
            
            treeBuilder.append("🌳 BLINKIT ACCESSIBILITY TREE\n")
            treeBuilder.append("=" * 50).append("\n")
            
            // Keep the backend tree data, but avoid logging every node on the
            // accessibility thread during automation runs.
            appendNodeInfo(treeBuilder, rootNode, 0, "ROOT")
            
            // Get tree summary first
            Log.d("MyAccessibilityService", "Getting tree summary...")
            val summary = getTreeSummary(rootNode)
            Log.d("MyAccessibilityService", "Tree summary calculated successfully")
            
            Log.d("MyAccessibilityService", "📊 TREE SUMMARY:")
            Log.d("MyAccessibilityService", "  • Total Nodes: ${summary.totalNodes}")
            Log.d("MyAccessibilityService", "  • Buttons: ${summary.buttonCount}")
            Log.d("MyAccessibilityService", "  • TextViews: ${summary.textViewCount}")
            Log.d("MyAccessibilityService", "  • ImageViews: ${summary.imageViewCount}")
            Log.d("MyAccessibilityService", "  • Clickable Elements: ${summary.clickableCount}")
            Log.d("MyAccessibilityService", "  • Max Depth: ${summary.maxDepth}")
            
            // Add summary to tree data
            treeBuilder.append("📊 TREE SUMMARY:\n")
            treeBuilder.append("  • Total Nodes: ${summary.totalNodes}\n")
            treeBuilder.append("  • Buttons: ${summary.buttonCount}\n")
            treeBuilder.append("  • TextViews: ${summary.textViewCount}\n")
            treeBuilder.append("  • ImageViews: ${summary.imageViewCount}\n")
            treeBuilder.append("  • Clickable Elements: ${summary.clickableCount}\n")
            treeBuilder.append("  • Max Depth: ${summary.maxDepth}\n\n")
            
            Log.d("MyAccessibilityService", "")
            Log.d("MyAccessibilityService", "🔍 CAPTURING TREE STRUCTURE")
            treeBuilder.append("🔍 DETAILED TREE STRUCTURE:\n")
            
            // Traverse and append the tree for backend processing.
            Log.d("MyAccessibilityService", "Starting tree traversal...")
            traverseAndAppendTree(treeBuilder, rootNode, 0)
            Log.d("MyAccessibilityService", "Tree traversal completed")
            
            treeBuilder.append("Tree traversal completed\n")
            treeBuilder.append("=" * 50).append("\n")
            treeBuilder.append("=== TREE VIEW COMPLETED SUCCESSFULLY ===\n")
            
            // Store the tree data
            lastTreeData = treeBuilder.toString()
            Log.d("MyAccessibilityService", "Tree data captured - length: ${lastTreeData.length}")
            
        } catch (e: Exception) {
            Log.e("MyAccessibilityService", "Error processing Blinkit tree: ${e.message}", e)
            e.printStackTrace()
        }
    }
    
    /**
     * Data class to hold tree summary information
     */
    private data class TreeSummary(
        val totalNodes: Int,
        val buttonCount: Int,
        val textViewCount: Int,
        val imageViewCount: Int,
        val clickableCount: Int,
        val maxDepth: Int
    )
    
    /**
     * Gets a summary of the tree structure
     */
    private fun getTreeSummary(rootNode: AccessibilityNodeInfo): TreeSummary {
        var totalNodes = 0
        var buttonCount = 0
        var textViewCount = 0
        var imageViewCount = 0
        var clickableCount = 0
        var maxDepth = 0
        
        fun countNodes(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 15) return // Prevent infinite recursion
            
            totalNodes++
            maxDepth = maxOf(maxDepth, depth)
            
            val className = node.className?.toString() ?: ""
            when {
                className.contains("Button") -> buttonCount++
                className.contains("TextView") -> textViewCount++
                className.contains("ImageView") -> imageViewCount++
            }
            
            if (node.isClickable) clickableCount++
            
            // Count children
            for (i in 0 until node.childCount) {
                try {
                    val child = node.getChild(i)
                    if (child != null) {
                        countNodes(child, depth + 1)
                        child.recycle()
                    }
                } catch (e: Exception) {
                    // Ignore errors in counting
                }
            }
        }
        
        countNodes(rootNode, 0)
        return TreeSummary(totalNodes, buttonCount, textViewCount, imageViewCount, clickableCount, maxDepth)
    }
    
    /**
     * Traverses the accessibility tree and logs each node with proper indentation
     */
    private fun traverseAndLogTree(node: AccessibilityNodeInfo, depth: Int) {
        if (depth > 10) return // Prevent infinite recursion
        
        val indent = "  ".repeat(depth)
        
        // Log current node
        logNodeInfo(node, depth, "NODE")
        
        // Traverse children
        for (i in 0 until node.childCount) {
            try {
                val child = node.getChild(i)
                if (child != null) {
                    traverseAndLogTree(child, depth + 1)
                    child.recycle() // Important: recycle child nodes
                }
            } catch (e: Exception) {
                Log.e("MyAccessibilityService", "Error traversing child $i: ${e.message}")
            }
        }
    }
    
    /**
     * Logs detailed information about a specific node
     */
    private fun logNodeInfo(node: AccessibilityNodeInfo, depth: Int, prefix: String) {
        val indent = "  ".repeat(depth)
        
        try {
            val className = node.className?.toString() ?: "Unknown"
            val text = node.text?.toString() ?: ""
            val contentDesc = node.contentDescription?.toString() ?: ""
            val viewId = node.viewIdResourceName ?: ""
            val isClickable = node.isClickable
            val isEnabled = node.isEnabled
            val isVisible = node.isVisibleToUser
            
            Log.d("MyAccessibilityService", "$indent$prefix [$className]")
            
            if (text.isNotEmpty()) {
                Log.d("MyAccessibilityService", "$indent  📝 Text: \"$text\"")
            }
            
            if (contentDesc.isNotEmpty()) {
                Log.d("MyAccessibilityService", "$indent  🏷️  ContentDesc: \"$contentDesc\"")
            }
            
            if (viewId.isNotEmpty()) {
                Log.d("MyAccessibilityService", "$indent  🆔 ViewID: $viewId")
            }
            
            Log.d("MyAccessibilityService", "$indent  ⚡ Clickable: $isClickable, Enabled: $isEnabled, Visible: $isVisible")
            Log.d("MyAccessibilityService", "$indent  🎯 Focused: ${node.isFocused}, AccessibilityFocused: ${node.isAccessibilityFocused}, Editable: ${node.isEditable}")
            
            // Log additional properties for important elements
            if (className.contains("Button") || className.contains("TextView") || className.contains("ImageView")) {
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)
                Log.d("MyAccessibilityService", "$indent  📍 Bounds: [${bounds.left},${bounds.top}] -> [${bounds.right},${bounds.bottom}]")
            }
            
        } catch (e: Exception) {
            Log.e("MyAccessibilityService", "$indent$prefix Error getting node info: ${e.message}")
        }
    }
    
    /**
     * Extension function to repeat a string
     */
    private operator fun String.times(count: Int): String {
        return buildString {
            repeat(count) { append(this@times) }
        }
    }
    
    /**
     * Traverses the accessibility tree and appends each node to StringBuilder
     */
    private fun traverseAndAppendTree(treeBuilder: StringBuilder, node: AccessibilityNodeInfo, depth: Int) {
        if (depth > 10) return // Prevent infinite recursion
        
        // Append current node
        appendNodeInfo(treeBuilder, node, depth, "NODE")
        
        // Traverse children
        for (i in 0 until node.childCount) {
            try {
                val child = node.getChild(i)
                if (child != null) {
                    traverseAndAppendTree(treeBuilder, child, depth + 1)
                    child.recycle() // Important: recycle child nodes
                }
            } catch (e: Exception) {
                Log.e("MyAccessibilityService", "Error traversing child $i: ${e.message}")
            }
        }
    }
    
    /**
     * Appends detailed information about a specific node to StringBuilder
     */
    private fun appendNodeInfo(treeBuilder: StringBuilder, node: AccessibilityNodeInfo, depth: Int, prefix: String) {
        val indent = "  ".repeat(depth)
        
        try {
            val className = node.className?.toString() ?: "Unknown"
            val text = node.text?.toString() ?: ""
            val contentDesc = node.contentDescription?.toString() ?: ""
            val viewId = node.viewIdResourceName ?: ""
            val isClickable = node.isClickable
            val isEnabled = node.isEnabled
            val isVisible = node.isVisibleToUser
            
            treeBuilder.append("$indent$prefix [$className]\n")
            
            if (text.isNotEmpty()) {
                treeBuilder.append("$indent  📝 Text: \"$text\"\n")
            }
            
            if (contentDesc.isNotEmpty()) {
                treeBuilder.append("$indent  🏷️  ContentDesc: \"$contentDesc\"\n")
            }
            
            if (viewId.isNotEmpty()) {
                treeBuilder.append("$indent  🆔 ViewID: $viewId\n")
            }
            
            treeBuilder.append("$indent  ⚡ Clickable: $isClickable, Enabled: $isEnabled, Visible: $isVisible\n")
            treeBuilder.append("$indent  🎯 Focused: ${node.isFocused}, AccessibilityFocused: ${node.isAccessibilityFocused}, Editable: ${node.isEditable}\n")
            
            // Append additional properties for important elements
            if (className.contains("Button") || className.contains("TextView") || className.contains("ImageView")) {
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)
                treeBuilder.append("$indent  📍 Bounds: [${bounds.left},${bounds.top}] -> [${bounds.right},${bounds.bottom}]\n")
            }
            
        } catch (e: Exception) {
            treeBuilder.append("$indent$prefix Error getting node info: ${e.message}\n")
        }
    }
    companion object {
        private const val OWN_PACKAGE = "com.example.beta"
        private const val BLINKIT_PACKAGE = "com.grofers.customerapp"
        private const val NON_BLINKIT_LOG_INTERVAL_MS = 5000L
    }
}
