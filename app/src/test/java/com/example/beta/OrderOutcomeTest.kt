package com.example.beta

import org.junit.Assert.assertEquals
import org.junit.Test

class OrderOutcomeTest {
    @Test
    fun itemResultLine_isExactForSuccessCase() {
        val outcome = ItemOutcome(
            item = "amul butter",
            status = ItemOutcomeStatus.SUCCESS,
            matchedSku = "Amul Butter Pack",
            qtyRequested = 1,
            qtyAdded = 1,
            notes = "verified_in_cart"
        )

        val line = formatItemResultLine(outcome)

        assertEquals(
            "ITEM_RESULT item=\"amul butter\" status=success matched_sku=\"Amul Butter Pack\" qty_requested=1 qty_added=1 notes=\"verified_in_cart\"",
            line
        )
    }

    @Test
    fun orderResultLine_isExactForFailures() {
        val summary = OrderOutcomeSummary(
            itemsTotal = 3,
            itemsSucceeded = 1,
            itemsFailed = 2,
            failures = listOf(
                OrderFailure("apple", ItemOutcomeStatus.OOS),
                OrderFailure("notebook", ItemOutcomeStatus.NOT_FOUND)
            )
        )

        val line = formatOrderResultLine(summary)

        assertEquals(
            "ORDER_RESULT items_total=3 items_succeeded=1 items_failed=2 failures=\"apple:oos;notebook:not_found\"",
            line
        )
    }

    @Test
    fun overlayStateLines_arePhase0Contract() {
        assertEquals(
            "STATE: ITEM_RESULT (butter: not_found)",
            formatItemResultStateLine("butter", ItemOutcomeStatus.NOT_FOUND)
        )
        assertEquals(
            "STATE: ORDER_DONE",
            formatOrderDoneStateLine()
        )
    }

    @Test
    fun normalizeOrderOutcomeItem_stripsOrderVerbAndNormalizesProbePlural() {
        assertEquals("apple", normalizeOrderOutcomeItem("order apples"))
        assertEquals("pencil", normalizeOrderOutcomeItem("Buy pencil"))
        assertEquals("unsalted butter", normalizeOrderOutcomeItem("get me unsalted butter"))
    }

    @Test
    fun normalizeOrderOutcomeItem_prefersMatchedTargetWhenProvided() {
        assertEquals("apple", normalizeOrderOutcomeItem("order something noisy", matchedTarget = "apple"))
        assertEquals("unknown_item", normalizeOrderOutcomeItem("   "))
    }
}
