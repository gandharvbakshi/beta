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

    @Test
    fun checkoutBoundary_ignoresPassiveBlinkitCartReviewTreeForSearchAction() {
        val cartReviewTree = """
            package=com.grofers.customerapp
            Checkout
            PAY USING
            Icici Visa Card
            Rs 240 TOTAL
            Place Order
        """.trimIndent()
        val backendAction = "click search bar open search field"

        assertFalse(
            BackendProcessing.isCheckoutBoundaryDetected(
                responseText = backendAction,
                treeData = cartReviewTree
            )
        )
    }

    @Test
    fun checkoutBoundary_blocksPaymentExecutionRecommendedAction() {
        assertTrue(
            BackendProcessing.isCheckoutBoundaryDetected(
                responseText = "click Place Order button",
                treeData = "Blinkit search results"
            )
        )
    }

    @Test
    fun checkoutBoundary_blocksActivePaymentExecutionSurface() {
        assertTrue(
            BackendProcessing.isCheckoutBoundaryDetected(
                responseText = "wait for screen",
                treeData = "Enter UPI PIN to complete this payment"
            )
        )
    }

    @Test
    fun checkoutBoundary_blocksGenericContinueOnCheckoutSurface() {
        assertTrue(
            BackendProcessing.isCheckoutBoundaryDetected(
                responseText = "click Continue button",
                treeData = "Checkout Order summary To pay Place Order"
            )
        )
    }
}
