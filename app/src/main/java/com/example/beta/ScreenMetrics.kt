package com.example.beta

import android.content.Context
import android.content.res.Resources
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.WindowMetrics

/**
 * Utility class for getting screen metrics and system UI dimensions
 * Helps solve coordinate offset issues by providing accurate status bar heights
 */
object ScreenMetrics {
    
    /**
     * Get the status bar height in pixels
     * This is critical for coordinate adjustment when clicking
     */
    fun getStatusBarHeight(context: Context): Int {
        var result = 0
        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            result = context.resources.getDimensionPixelSize(resourceId)
        }
        
        // Fallback: typical status bar height is ~24dp
        if (result == 0) {
            result = (24 * context.resources.displayMetrics.density).toInt()
        }
        
        DebugLogger.logDebug("ScreenMetrics", "Status bar height: $result px")
        return result
    }
    
    /**
     * Get the navigation bar height in pixels
     */
    fun getNavigationBarHeight(context: Context): Int {
        var result = 0
        val resourceId = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        if (resourceId > 0) {
            result = context.resources.getDimensionPixelSize(resourceId)
        }
        DebugLogger.logDebug("ScreenMetrics", "Navigation bar height: $result px")
        return result
    }
    
    /**
     * Get the current screen dimensions
     */
    fun getScreenDimensions(context: Context): Pair<Int, Int> {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics: WindowMetrics = windowManager.currentWindowMetrics
            val bounds: Rect = metrics.bounds
            Pair(bounds.width(), bounds.height())
        } else {
            val displayMetrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(displayMetrics)
            Pair(displayMetrics.widthPixels, displayMetrics.heightPixels)
        }
    }
    
    /**
     * Get comprehensive screen information
     */
    fun getScreenInfo(context: Context): ScreenInfo {
        val (width, height) = getScreenDimensions(context)
        val statusBarHeight = getStatusBarHeight(context)
        val navigationBarHeight = getNavigationBarHeight(context)
        val density = context.resources.displayMetrics.density
        
        val info = ScreenInfo(
            width = width,
            height = height,
            statusBarHeight = statusBarHeight,
            navigationBarHeight = navigationBarHeight,
            density = density
        )
        
        DebugLogger.logScreenInfo(width, height, statusBarHeight, navigationBarHeight, density)
        
        return info
    }
    
    /**
     * Adjust coordinates from screenshot space to screen space
     * This is THE KEY FUNCTION that fixes the coordinate offset issue
     * 
     * Screenshot coordinates start at (0,0) which is the top-left of the app content
     * Screen coordinates for gestures need to include the status bar height
     * 
     * @param screenshotX X coordinate from screenshot/backend
     * @param screenshotY Y coordinate from screenshot/backend
     * @param context Application context
     * @return Pair of adjusted (x, y) coordinates for gesture dispatch
     */
    fun adjustCoordinatesForScreen(screenshotX: Int, screenshotY: Int, context: Context): Pair<Int, Int> {
        val statusBarHeight = getStatusBarHeight(context)
        
        // X coordinate stays the same
        // Y coordinate needs to be adjusted by status bar height
        val adjustedX = screenshotX
        val adjustedY = screenshotY + statusBarHeight
        
        DebugLogger.logDebug(
            "ScreenMetrics",
            "Coordinate adjustment: ($screenshotX, $screenshotY) -> ($adjustedX, $adjustedY) [+$statusBarHeight px for status bar]"
        )
        
        return Pair(adjustedX, adjustedY)
    }
    
    data class ScreenInfo(
        val width: Int,
        val height: Int,
        val statusBarHeight: Int,
        val navigationBarHeight: Int,
        val density: Float
    )
}

