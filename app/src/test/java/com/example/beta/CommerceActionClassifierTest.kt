package com.example.beta

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommerceActionClassifierTest {
    @Test
    fun productAddButtonAction_doesNotTreatVariantOptionsAsCartMutation() {
        assertFalse(
            CommerceActionClassifier.isProductAddButtonAction(
                "Open variant options for butter",
                "2 options",
                ""
            )
        )
        assertTrue(
            CommerceActionClassifier.isProductAddButtonAction(
                "ADD button for smallest variant of butter",
                "ADD",
                ""
            )
        )
    }

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
    fun checkoutOrPaymentExecutionAction_blocksOpeningCartForVerification() {
        assertTrue(
            CommerceActionClassifier.isCheckoutOrPaymentExecutionAction(
                "click",
                "open cart to verify items"
            )
        )
        assertTrue(
            CommerceActionClassifier.isCheckoutOrPaymentExecutionAction(
                "click",
                "View cart"
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
