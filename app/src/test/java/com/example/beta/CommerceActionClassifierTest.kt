package com.example.beta

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommerceActionClassifierTest {
    @Test
    fun checkoutOrPaymentExecutionAction_blocksPaymentActions() {
        assertTrue(
            CommerceActionClassifier.isCheckoutOrPaymentExecutionAction(
                "click",
                "Place Order button"
            )
        )
        assertTrue(
            CommerceActionClassifier.isCheckoutOrPaymentExecutionAction(
                "tap",
                "PAY USING selected card"
            )
        )
        assertTrue(
            CommerceActionClassifier.isCheckoutOrPaymentExecutionAction(
                "click",
                "Checkout button"
            )
        )
        assertTrue(
            CommerceActionClassifier.isCheckoutOrPaymentExecutionAction(
                "click",
                "Continue button",
                "on the checkout screen"
            )
        )
        assertTrue(
            CommerceActionClassifier.isCheckoutOrPaymentExecutionAction(
                "click",
                "Submit & pay"
            )
        )
    }

    @Test
    fun checkoutOrPaymentExecutionAction_allowsCartReviewLanguage() {
        assertFalse(
            CommerceActionClassifier.isCheckoutOrPaymentExecutionAction(
                "click",
                "open cart to verify items"
            )
        )
        assertFalse(
            CommerceActionClassifier.isCheckoutOrPaymentExecutionAction(
                "click",
                "Continue shopping"
            )
        )
    }
}
