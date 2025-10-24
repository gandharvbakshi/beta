package com.example.beta

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * Local file-based logging system for debugging coordinate issues and backend communication
 * Logs are stored in app's files directory and can be read programmatically
 */
object DebugLogger {
    private const val LOG_FILENAME = "beta_debug_log.txt"
    private const val MAX_LOG_SIZE = 5 * 1024 * 1024 // 5MB max log size
    private var context: Context? = null
    
    fun init(appContext: Context) {
        context = appContext
        logInfo("DebugLogger", "=== Debug Logger Initialized ===")
    }
    
    private fun getLogFile(): File? {
        return context?.filesDir?.let { File(it, LOG_FILENAME) }
    }
    
    private fun writeToLog(level: String, tag: String, message: String) {
        try {
            val logFile = getLogFile() ?: return
            
            // Check file size and rotate if necessary
            if (logFile.exists() && logFile.length() > MAX_LOG_SIZE) {
                val backupFile = File(logFile.parent, "${LOG_FILENAME}.old")
                logFile.renameTo(backupFile)
            }
            
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val logEntry = "[$timestamp] [$level] [$tag] $message\n"
            
            FileWriter(logFile, true).use { writer ->
                writer.append(logEntry)
            }
            
            // Also log to logcat for immediate viewing
            when (level) {
                "ERROR" -> Log.e(tag, message)
                "WARN" -> Log.w(tag, message)
                "INFO" -> Log.i(tag, message)
                "DEBUG" -> Log.d(tag, message)
            }
        } catch (e: Exception) {
            Log.e("DebugLogger", "Failed to write to log: ${e.message}")
        }
    }
    
    fun logError(tag: String, message: String) {
        writeToLog("ERROR", tag, message)
    }
    
    fun logWarn(tag: String, message: String) {
        writeToLog("WARN", tag, message)
    }
    
    fun logInfo(tag: String, message: String) {
        writeToLog("INFO", tag, message)
    }
    
    fun logDebug(tag: String, message: String) {
        writeToLog("DEBUG", tag, message)
    }
    
    /**
     * Log backend request details
     */
    fun logBackendRequest(inputText: String, appName: String?, treeDataLength: Int, imageWidth: Int, imageHeight: Int) {
        val logMsg = """
            |📤 BACKEND REQUEST
            |  Input Text: '$inputText'
            |  App Name: ${appName ?: "null"}
            |  Tree Data Length: $treeDataLength chars
            |  Image Dimensions: ${imageWidth}x${imageHeight}
        """.trimMargin()
        writeToLog("INFO", "BackendComm", logMsg)
    }
    
    /**
     * Log backend response details
     */
    fun logBackendResponse(
        actionId: String?,
        actionType: String?,
        actionTarget: String?,
        confidence: Double?,
        boundingBox: JSONObject?,
        coordinates: JSONObject?,
        statusBarHeight: Int,
        adjustedCoordinates: Pair<Int, Int>?
    ) {
        val logMsg = StringBuilder()
        logMsg.append("📥 BACKEND RESPONSE\n")
        logMsg.append("  Action ID: ${actionId ?: "null"}\n")
        logMsg.append("  Action Type: ${actionType ?: "null"}\n")
        logMsg.append("  Action Target: ${actionTarget ?: "null"}\n")
        logMsg.append("  Confidence: ${confidence ?: "null"}\n")
        
        if (boundingBox != null) {
            logMsg.append("  Bounding Box: x=${boundingBox.optInt("x")}, y=${boundingBox.optInt("y")}, ")
            logMsg.append("w=${boundingBox.optInt("width")}, h=${boundingBox.optInt("height")}\n")
        }
        
        if (coordinates != null) {
            val originalX = coordinates.optInt("x", 0)
            val originalY = coordinates.optInt("y", 0)
            logMsg.append("  Original Coordinates: ($originalX, $originalY)\n")
            logMsg.append("  Status Bar Height: $statusBarHeight px\n")
            
            if (adjustedCoordinates != null) {
                logMsg.append("  Adjusted Coordinates: (${adjustedCoordinates.first}, ${adjustedCoordinates.second})\n")
                logMsg.append("  Adjustment Applied: (0, +$statusBarHeight)\n")
            }
        }
        
        writeToLog("INFO", "BackendComm", logMsg.toString())
    }
    
    /**
     * Log click execution details
     */
    fun logClickExecution(
        strategy: String,
        originalX: Int,
        originalY: Int,
        finalX: Int,
        finalY: Int,
        statusBarAdjustment: Int,
        overlayDeflection: Int,
        buttonCenterAdjustment: Int = 0,
        success: Boolean
    ) {
        val logMsg = """
            |🎯 CLICK EXECUTION
            |  Strategy: $strategy
            |  Original Coords: ($originalX, $originalY)
            |  Status Bar Adjustment: +$statusBarAdjustment px
            |  Button Center Adjustment: +$buttonCenterAdjustment px
            |  Overlay Deflection: +$overlayDeflection px
            |  Final Coords: ($finalX, $finalY)
            |  Total Y Offset: +${finalY - originalY} px
            |  Gesture Dispatch Success: $success
        """.trimMargin()
        writeToLog("INFO", "ClickExecution", logMsg)
    }
    
    /**
     * Log gesture callback results
     */
    fun logGestureResult(strategy: String, completed: Boolean, cancelled: Boolean) {
        val status = when {
            completed -> "✅ COMPLETED"
            cancelled -> "❌ CANCELLED"
            else -> "⏳ PENDING"
        }
        val logMsg = "🎭 GESTURE CALLBACK - Strategy: $strategy, Status: $status"
        writeToLog("INFO", "GestureResult", logMsg)
    }
    
    /**
     * Log screen dimensions and system UI details
     */
    fun logScreenInfo(
        screenWidth: Int,
        screenHeight: Int,
        statusBarHeight: Int,
        navigationBarHeight: Int,
        density: Float
    ) {
        val logMsg = """
            |📱 SCREEN INFO
            |  Screen Size: ${screenWidth}x${screenHeight}
            |  Status Bar Height: $statusBarHeight px
            |  Navigation Bar Height: $navigationBarHeight px
            |  Density: $density
        """.trimMargin()
        writeToLog("INFO", "ScreenInfo", logMsg)
    }
    
    /**
     * Log screenshot capture details with resolution comparison
     */
    fun logScreenshotCapture(
        phoneWidth: Int,
        phoneHeight: Int,
        screenshotWidth: Int,
        screenshotHeight: Int,
        statusBarHeight: Int
    ) {
        val resolutionMatch = (phoneWidth == screenshotWidth && phoneHeight == screenshotHeight)
        val logMsg = """
            |📸 SCREENSHOT CAPTURED
            |  Phone Resolution: ${phoneWidth}x${phoneHeight}
            |  Screenshot Resolution: ${screenshotWidth}x${screenshotHeight}
            |  Resolution Match: ${if (resolutionMatch) "✅ YES" else "⚠️ NO - Scaling may affect coordinates"}
            |  Status Bar Height: $statusBarHeight px
            |  Screenshot includes status bar: ${if (screenshotHeight == phoneHeight) "YES" else "NO"}
        """.trimMargin()
        writeToLog("INFO", "ScreenshotCapture", logMsg)
    }
    
    /**
     * Log the actual coordinates clicked on screen
     */
    fun logActualClickCoordinates(
        x: Int,
        y: Int,
        screenWidth: Int,
        screenHeight: Int,
        targetDescription: String = "unknown"
    ) {
        val logMsg = """
            |👆 ACTUAL CLICK PERFORMED
            |  Coordinates: ($x, $y)
            |  Screen Dimensions: ${screenWidth}x${screenHeight}
            |  Target: $targetDescription
            |  Relative Position: ${String.format("%.1f", (x.toFloat() / screenWidth) * 100)}% from left, ${String.format("%.1f", (y.toFloat() / screenHeight) * 100)}% from top
        """.trimMargin()
        writeToLog("INFO", "ActualClick", logMsg)
    }
    
    /**
     * Read the entire log file
     */
    fun readLogs(): String {
        return try {
            val logFile = getLogFile()
            if (logFile?.exists() == true) {
                logFile.readText()
            } else {
                "No logs available"
            }
        } catch (e: Exception) {
            "Error reading logs: ${e.message}"
        }
    }
    
    /**
     * Read last N lines of the log
     */
    fun readLastLogs(lines: Int = 50): String {
        return try {
            val allLogs = readLogs()
            val logLines = allLogs.split("\n")
            logLines.takeLast(lines).joinToString("\n")
        } catch (e: Exception) {
            "Error reading logs: ${e.message}"
        }
    }
    
    /**
     * Clear all logs
     */
    fun clearLogs() {
        try {
            getLogFile()?.delete()
            logInfo("DebugLogger", "Logs cleared")
        } catch (e: Exception) {
            Log.e("DebugLogger", "Failed to clear logs: ${e.message}")
        }
    }
    
    /**
     * Get log file path for external access
     */
    fun getLogFilePath(): String? {
        return getLogFile()?.absolutePath
    }
    
    /**
     * Dump recent logs to logcat for easy viewing
     * Useful for debugging without pulling the file
     */
    fun dumpToLogcat(lines: Int = 50) {
        val recentLogs = readLastLogs(lines)
        Log.i("DebugLogDump", "============ DEBUG LOG DUMP (Last $lines lines) ============")
        recentLogs.split("\n").forEach { line ->
            if (line.isNotBlank()) {
                Log.i("DebugLogDump", line)
            }
        }
        Log.i("DebugLogDump", "============ END DEBUG LOG DUMP ============")
    }
    
    /**
     * Log comprehensive coordinate system details for debugging coordinate mismatches
     */
    fun logCoordinateSystem(
        backendX: Int,
        backendY: Int,
        screenshotWidth: Int,
        screenshotHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
        statusBarHeight: Int,
        navigationBarHeight: Int,
        finalX: Int,
        finalY: Int,
        strategy: String
    ) {
        val logMsg = """
            |🔍 COORDINATE SYSTEM ANALYSIS
            |  Backend Coordinates: ($backendX, $backendY)
            |  Screenshot Dimensions: ${screenshotWidth}x${screenshotHeight}
            |  Screen Dimensions: ${screenWidth}x${screenHeight}
            |  Status Bar Height: $statusBarHeight px
            |  Navigation Bar Height: $navigationBarHeight px
            |  Final Gesture Coordinates: ($finalX, $finalY)
            |  Strategy: $strategy
            |  Dimension Difference: ${screenHeight - screenshotHeight} px (Navigation Bar?)
            |  Backend Y as % of screenshot: ${String.format("%.1f", (backendY.toFloat() / screenshotHeight) * 100)}%
            |  Final Y as % of screen: ${String.format("%.1f", (finalY.toFloat() / screenHeight) * 100)}%
        """.trimMargin()
        writeToLog("INFO", "CoordinateSystem", logMsg)
    }

    /**
     * Log a section separator for easier reading
     */
    fun logSeparator(title: String) {
        val separator = "=" .repeat(50)
        writeToLog("INFO", "Separator", "$separator $title $separator")
    }
    
    /**
     * Log comprehensive scaling analysis for debugging coordinate mismatches
     * This helps verify if scaling corrections are working correctly
     */
    fun logScalingAnalysis(
        backendX: Int,
        backendY: Int,
        screenshotWidth: Int,
        screenshotHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
        clickX: Int,
        clickY: Int
    ) {
        val scaleX = screenWidth.toFloat() / screenshotWidth.toFloat()
        val scaleY = screenHeight.toFloat() / screenshotHeight.toFloat()
        
        val scaledBackendX = (backendX * scaleX).toInt()
        val scaledBackendY = (backendY * scaleY).toInt()
        
        val offsetX = clickX - scaledBackendX
        val offsetY = clickY - scaledBackendY
        
        val logMsg = """
            |🔍 SCALING ANALYSIS
            |  Backend Coordinates: ($backendX, $backendY)
            |  Screenshot Dimensions: ${screenshotWidth}x${screenshotHeight}
            |  Screen Dimensions: ${screenWidth}x${screenHeight}
            |  Scaling Factors: X=${String.format("%.3f", scaleX)}, Y=${String.format("%.3f", scaleY)}
            |  Scaled Backend Coords: ($scaledBackendX, $scaledBackendY)
            |  Actual Click Coords: ($clickX, $clickY)
            |  Offset: X=$offsetX, Y=$offsetY
            |  Scaling Mismatch: ${if (Math.abs(scaleY - 1.0) > 0.01) "YES" else "NO"}
        """.trimMargin()
        writeToLog("INFO", "ScalingAnalysis", logMsg)
    }
    
    /**
     * Log the start of a new test run for easy identification
     */
    fun logTestRunStart(testDescription: String = "New Test Run") {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val separator = "=" .repeat(50)
        val logMsg = """
            |$separator
            |NEW TEST RUN: $timestamp - $testDescription
            |$separator
        """.trimMargin()
        writeToLog("INFO", "TestRunStart", logMsg)
    }
}

