package com.example.beta

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class GroceryIntentCheckoutConstraintGateTest {
    @Test fun unreadableRecoveryRequiresExplicitAcknowledgementAndBackDoesNotDiscard() {
        var discards = 0
        var backs = 0
        val screen = groceryIntentUnreadableConstraintRecoveryScreen({ discards++ }, { backs++ })
        screen.primary!!.onClick()
        assertEquals(0, discards)
        screen.secondary!!.onClick()
        assertEquals(1, backs)
        assertEquals(0, discards)
        screen.acknowledgement!!.onCheckedChange(true)
        screen.primary.onClick()
        assertEquals(1, discards)
        screen.acknowledgement.onCheckedChange(false)
        screen.primary.onClick()
        assertEquals(1, discards)
    }
    @Test fun ordinaryCheckoutStillRequiresAReviewButNotAnExtraCheckbox() {
        val gate = GroceryIntentCheckoutConstraintGate()
        assertFalse(gate.canPlace(emptyList()))
        gate.beginReview(emptyList())
        assertTrue(gate.canPlace(emptyList()))
    }

    @Test fun theExactSavedNotesNeedAFreshExplicitAcknowledgement() {
        val gate = GroceryIntentCheckoutConstraintGate()
        val notes = listOf("Bring these groceries tomorrow:")
        gate.beginReview(notes)
        assertFalse(gate.canPlace(notes))
        gate.acknowledge(true)
        assertTrue(gate.canPlace(notes))
        assertFalse(gate.canPlace(notes + "Bring these items tonight:"))
        assertFalse(gate.canPlace(emptyList()))
        gate.acknowledge(false)
        assertFalse(gate.canPlace(notes))
    }

    @Test fun reviewExpiryPaymentChangeAndPauseCannotReuseAcknowledgement() {
        val gate = GroceryIntentCheckoutConstraintGate()
        val notes = listOf("Bring these groceries tomorrow:")
        gate.beginReview(notes)
        gate.acknowledge(true)
        gate.beginReview(notes)
        assertFalse(gate.canPlace(notes))
        gate.acknowledge(true)
        gate.invalidate()
        assertFalse(gate.canPlace(notes))
    }
}
