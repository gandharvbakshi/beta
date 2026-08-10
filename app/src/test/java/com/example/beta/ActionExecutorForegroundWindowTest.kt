package com.example.beta

import android.view.accessibility.AccessibilityWindowInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionExecutorForegroundWindowTest {
    @Test
    fun `accepts only active or focused application windows`() {
        assertTrue(
            ActionExecutor.shouldTreatApplicationWindowAsForeground(
                AccessibilityWindowInfo.TYPE_APPLICATION,
                isActive = true,
                isFocused = false
            )
        )
        assertTrue(
            ActionExecutor.shouldTreatApplicationWindowAsForeground(
                AccessibilityWindowInfo.TYPE_APPLICATION,
                isActive = false,
                isFocused = true
            )
        )
        assertFalse(
            ActionExecutor.shouldTreatApplicationWindowAsForeground(
                AccessibilityWindowInfo.TYPE_APPLICATION,
                isActive = false,
                isFocused = false
            )
        )
        assertFalse(
            ActionExecutor.shouldTreatApplicationWindowAsForeground(
                AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
                isActive = true,
                isFocused = true
            )
        )
    }

    @Test
    fun `does not accept retained blinkit window while launcher is foreground`() {
        assertFalse(
            ActionExecutor.hasSupportedCommerceForeground(
                activeRootPackage = "com.android.launcher",
                foregroundApplicationPackages = listOf("com.android.launcher")
            )
        )
    }

    @Test
    fun `accepts blinkit behind a focused beta overlay when its app window is active`() {
        assertTrue(
            ActionExecutor.hasSupportedCommerceForeground(
                activeRootPackage = "live.betaapp.android",
                foregroundApplicationPackages = listOf("com.grofers.customerapp")
            )
        )
    }
}
