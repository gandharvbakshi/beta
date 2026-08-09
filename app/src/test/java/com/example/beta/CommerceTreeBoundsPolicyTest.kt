package com.example.beta

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommerceTreeBoundsPolicyTest {
    @Test
    fun `keeps root window bounds for coordinate scaling`() {
        assertTrue(
            MyAccessibilityService.shouldAppendCommerceNodeBounds(
                className = "android.widget.FrameLayout",
                viewId = "",
                contentDescription = "",
                isRoot = true,
            )
        )
    }

    @Test
    fun `keeps bounds for blinkit product name view`() {
        assertTrue(
            MyAccessibilityService.shouldAppendCommerceNodeBounds(
                className = "android.view.View",
                viewId = "com.grofers.customerapp:id/tv_name",
                contentDescription = "Impact Sugar Free Mint Candies (Strong Mints)",
            )
        )
    }

    @Test
    fun `keeps bounds for anchored accessibility quantity`() {
        assertTrue(
            MyAccessibilityService.shouldAppendCommerceNodeBounds(
                className = "android.view.View",
                viewId = "com.grofers.customerapp:id/tv_title",
                contentDescription = "quantity 2",
            )
        )
    }

    @Test
    fun `keeps bounds for blinkit add control`() {
        assertTrue(
            MyAccessibilityService.shouldAppendCommerceNodeBounds(
                className = "android.view.View",
                viewId = "com.grofers.customerapp:id/tv_title",
                contentDescription = "ADD",
            )
        )
    }

    @Test
    fun `retains existing text view behavior`() {
        assertTrue(
            MyAccessibilityService.shouldAppendCommerceNodeBounds(
                className = "android.widget.TextView",
                viewId = "",
                contentDescription = "",
            )
        )
    }

    @Test
    fun `does not expand ordinary view nodes`() {
        assertFalse(
            MyAccessibilityService.shouldAppendCommerceNodeBounds(
                className = "android.view.View",
                viewId = "com.grofers.customerapp:id/price",
                contentDescription = "150 rupees",
            )
        )
    }
}
