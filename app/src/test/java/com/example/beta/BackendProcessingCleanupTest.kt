package com.example.beta

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendProcessingCleanupTest {
    @Test
    fun secondCleanupBack_isSkippedAfterBlinkitReturnsToHome() {
        val homeTree = """
            package=com.grofers.customerapp
            Blinkit in 9 minutes
            Search "gift for him"
            Frequently bought
            View cart
        """.trimIndent()

        assertFalse(
            BackendProcessing.shouldPressSecondMultiItemCleanupBack(
                activePackage = "com.grofers.customerapp",
                lastCapturedPackage = "com.grofers.customerapp",
                treeData = homeTree
            )
        )
    }

    @Test
    fun secondCleanupBack_isAllowedOnBlinkitCartOrCheckoutSurface() {
        val cartTree = """
            package=com.grofers.customerapp
            Your cart
            Bill details
            Item total
            To pay
        """.trimIndent()

        assertTrue(
            BackendProcessing.shouldPressSecondMultiItemCleanupBack(
                activePackage = "com.grofers.customerapp",
                lastCapturedPackage = "com.grofers.customerapp",
                treeData = cartTree
            )
        )
    }

    @Test
    fun secondCleanupBack_isSkippedWhenCommerceAppAlreadyLeftForeground() {
        assertFalse(
            BackendProcessing.shouldPressSecondMultiItemCleanupBack(
                activePackage = "com.android.launcher",
                lastCapturedPackage = "com.android.launcher",
                treeData = "Home screen"
            )
        )
    }

    @Test
    fun secondCleanupBack_requiresTreeEvidence() {
        assertFalse(
            BackendProcessing.shouldPressSecondMultiItemCleanupBack(
                activePackage = "com.grofers.customerapp",
                lastCapturedPackage = "com.grofers.customerapp",
                treeData = ""
            )
        )
    }
}
