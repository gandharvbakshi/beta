package com.example.beta

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentReviewGateTest {
    @Test fun unmatchedWordsRequireAnExplicitAcknowledgement() {
        val gate = GroceryIntentReviewGate()
        gate.prepare(listOf("one samosa"))
        assertFalse(gate.canApply())
        gate.acknowledge(true)
        assertTrue(gate.canApply())
        gate.acknowledge(false)
        assertFalse(gate.canApply())
    }

    @Test fun newPlanOrRequestCannotInheritEarlierAcknowledgement() {
        val gate = GroceryIntentReviewGate()
        gate.prepare(listOf("one samosa"))
        gate.acknowledge(true)
        gate.beginReview()
        assertFalse(gate.canApply())
        gate.acknowledge(true)
        gate.prepare(listOf("without sugar"))
        assertFalse(gate.canApply())
    }

    @Test fun ordinaryReviewDoesNotNeedAnExtraAcknowledgement() {
        val gate = GroceryIntentReviewGate()
        assertTrue(gate.canApply())
        gate.prepare(listOf("one samosa"))
        gate.clear()
        assertTrue(gate.canApply())
    }
}
