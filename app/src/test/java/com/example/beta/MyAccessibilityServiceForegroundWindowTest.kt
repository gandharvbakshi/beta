package com.example.beta

import android.view.accessibility.AccessibilityWindowInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MyAccessibilityServiceForegroundWindowTest {
    @Test
    fun `accepts only active or focused application windows`() {
        assertTrue(
            MyAccessibilityService.shouldTreatApplicationWindowAsForeground(
                AccessibilityWindowInfo.TYPE_APPLICATION,
                isActive = true,
                isFocused = false
            )
        )
        assertTrue(
            MyAccessibilityService.shouldTreatApplicationWindowAsForeground(
                AccessibilityWindowInfo.TYPE_APPLICATION,
                isActive = false,
                isFocused = true
            )
        )
        assertFalse(
            MyAccessibilityService.shouldTreatApplicationWindowAsForeground(
                AccessibilityWindowInfo.TYPE_APPLICATION,
                isActive = false,
                isFocused = false
            )
        )
        assertFalse(
            MyAccessibilityService.shouldTreatApplicationWindowAsForeground(
                AccessibilityWindowInfo.TYPE_SYSTEM,
                isActive = true,
                isFocused = true
            )
        )
    }
}
