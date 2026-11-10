package com.example.beta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentReviewGateTest {
    @Test fun preparationRouteKeepsRepairEditorEmptyAndDiscoveryPrecedence() {
        fun route(
            repair: Boolean = false,
            allowRepair: Boolean = true,
            recovery: Boolean = false,
            unresolved: Boolean = false,
            validation: Boolean = false,
            empty: Boolean = false,
        ) = groceryIntentPreparationRoute(repair, allowRepair, recovery, unresolved, validation, empty)

        assertEquals(GroceryIntentPreparationRoute.DISCOVERY, route())
        assertEquals(GroceryIntentPreparationRoute.EDITOR, route(unresolved = true))
        assertEquals(GroceryIntentPreparationRoute.EDITOR, route(recovery = true))
        assertEquals(GroceryIntentPreparationRoute.EDITOR, route(validation = true))
        assertEquals(GroceryIntentPreparationRoute.EMPTY, route(empty = true))
        assertEquals(GroceryIntentPreparationRoute.INPUT_REPAIR, route(repair = true))
        assertEquals(GroceryIntentPreparationRoute.EDITOR, route(repair = true, allowRepair = false))

        // Repair owns precedence; disabling quick repair keeps the historical editor path.
        assertEquals(GroceryIntentPreparationRoute.INPUT_REPAIR,
            route(repair = true, recovery = true, unresolved = true, validation = true, empty = true))
        assertEquals(GroceryIntentPreparationRoute.EDITOR,
            route(repair = true, allowRepair = false, recovery = true, unresolved = true, validation = true, empty = true))
        // Any non-repair review condition precedes the empty result.
        assertEquals(GroceryIntentPreparationRoute.EDITOR, route(unresolved = true, empty = true))
    }

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

    @Test fun excludedRowsRequireTheirOwnConfirmation() {
        val gate = GroceryIntentReviewGate()
        gate.prepare(emptyList(), listOf("unclear sauce"))
        assertFalse(gate.canApply())
        gate.acknowledge(true)
        assertFalse(gate.canApply())
        gate.confirmExclusions()
        assertTrue(gate.canApply())
    }

    @Test fun beginningANewPlanResetsBothAcknowledgements() {
        val gate = GroceryIntentReviewGate()
        gate.prepare(listOf("unmatched wording"), listOf("blocked item"))
        gate.acknowledge(true)
        gate.confirmExclusions()
        assertTrue(gate.canApply())

        gate.beginReview()
        assertFalse(gate.canApply())
        gate.acknowledge(true)
        assertFalse(gate.canApply())
        gate.confirmExclusions()
        assertTrue(gate.canApply())
    }

    @Test fun changingRequestAndClearingCannotCarryOldAcknowledgements() {
        val gate = GroceryIntentReviewGate()
        gate.prepare(listOf("old unmatched words"), listOf("old blocked row"))
        gate.acknowledge(true)
        gate.confirmExclusions()
        gate.clear()
        assertTrue(gate.unresolvedText.isEmpty())
        assertTrue(gate.excludedText.isEmpty())
        assertTrue(gate.canApply())

        gate.prepare(listOf("new unmatched words"))
        assertFalse(gate.canApply())
    }

    @Test fun sharedGroupNotesAreDeduplicatedAndClearedOnPrepareAndClear() {
        val gate = GroceryIntentReviewGate()
        gate.prepare(emptyList(), groups = listOf("BrandA yogurt cups — one each", "BrandA yogurt cups — one each"))
        assertEquals(listOf("BrandA yogurt cups — one each"), gate.sharedGroupNotes)

        gate.prepare(listOf("new unresolved wording"))
        assertTrue(gate.sharedGroupNotes.isEmpty())

        gate.prepare(emptyList(), groups = listOf("another shared group"))
        gate.clear()
        assertTrue(gate.sharedGroupNotes.isEmpty())
    }

    @Test fun bothAcknowledgementAndExclusionConfirmationAreRequiredWhenBothExist() {
        val gate = GroceryIntentReviewGate()
        gate.prepare(listOf("unmatched wording"), listOf("blocked item"))
        gate.confirmExclusions()
        assertFalse(gate.canApply())
        gate.acknowledge(true)
        assertTrue(gate.canApply())
        gate.acknowledge(false)
        assertFalse(gate.canApply())
    }

    @Test fun fulfillmentConstraintsRequireTheSameExplicitAcknowledgement() {
        val gate = GroceryIntentReviewGate()
        gate.prepare(emptyList(), fulfillment = listOf("Please get these tomorrow:"))
        assertEquals(listOf("Please get these tomorrow:"), gate.fulfillmentConstraints)
        assertFalse(gate.canApply())

        gate.acknowledge(true)
        assertTrue(gate.canApply())
        gate.beginReview()
        assertFalse(gate.canApply())

        gate.prepare(emptyList())
        assertTrue(gate.fulfillmentConstraints.isEmpty())
        assertTrue(gate.canApply())
    }
}
