package com.example.beta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun storeUnavailableFailureReason_matchesExpectedBackendVariants() {
        assertTrue(isStoreUnavailableFailureReason("Store unavailable for this location"))
        assertTrue(isStoreUnavailableFailureReason("Delivery location not serviceable"))
        assertTrue(isStoreUnavailableFailureReason("Service not available right now"))
        assertTrue(isStoreUnavailableFailureReason("Currently unavailable in Blinkit"))
        assertTrue(isStoreUnavailableFailureReason("Unserviceable area"))
        assertTrue(isStoreUnavailableFailureReason("Blinkit store or delivery is not available right now"))
        assertTrue(isStoreUnavailableFailureReason("This Instamart store is currently unserviceable"))
    }

    @Test
    fun storeAppUnavailableFailureReason_matchesHighTrafficAndOutageVariants() {
        assertTrue(isStoreAppUnavailableFailureReason("Temporarily unavailable due to high traffic"))
        assertTrue(isStoreAppUnavailableFailureReason("Service outage in your area"))
        assertTrue(isStoreAppUnavailableFailureReason("Store not available right now"))
    }

    @Test
    fun terminalFailureStatusForReason_separatesStoreUnavailableFromOutOfStock() {
        assertEquals(
            ItemOutcomeStatus.BACKEND_UNAVAILABLE,
            terminalFailureStatusForReason("backend 401")
        )
        assertEquals(
            ItemOutcomeStatus.STORE_UNAVAILABLE,
            terminalFailureStatusForReason("Temporarily unavailable due to high traffic")
        )
        assertEquals(
            ItemOutcomeStatus.STORE_UNAVAILABLE,
            terminalFailureStatusForReason("Blinkit store or delivery is not available right now")
        )
        assertEquals(
            ItemOutcomeStatus.OOS,
            terminalFailureStatusForReason("Out of stock right now")
        )
        assertEquals(
            ItemOutcomeStatus.OOS,
            terminalFailureStatusForReason("Item currently unavailable")
        )
        assertEquals(
            ItemOutcomeStatus.OOS,
            terminalFailureStatusForReason("Item was added, but quantity controls did not reach requested quantity 6.")
        )
    }

    @Test
    fun terminalFailureNoteForStatus_isSeparateForStoreUnavailable() {
        assertEquals(
            "backend_unavailable",
            terminalFailureNoteForStatus(ItemOutcomeStatus.BACKEND_UNAVAILABLE, "workflow_failed")
        )
        assertEquals(
            "store_unavailable",
            terminalFailureNoteForStatus(ItemOutcomeStatus.STORE_UNAVAILABLE, "workflow_failed")
        )
        assertEquals(
            "out_of_stock",
            terminalFailureNoteForStatus(ItemOutcomeStatus.OOS, "workflow_failed")
        )
    }

    @Test
    fun formatStoreUnavailableStateLine_isUserFriendly() {
        assertEquals(
            "STATE: STORE_UNAVAILABLE\nBeta has paused. Try again later or continue manually.",
            formatStoreUnavailableStateLine()
        )
    }

    @Test
    fun formatOrderSummaryStateLine_namesBackendUnavailableInsteadOfProductsNotFound() {
        val summary = OrderOutcomeSummary(
            itemsTotal = 2,
            itemsSucceeded = 0,
            itemsFailed = 2,
            failures = listOf(
                OrderFailure("skyr", ItemOutcomeStatus.BACKEND_UNAVAILABLE),
                OrderFailure("vicks", ItemOutcomeStatus.BACKEND_UNAVAILABLE)
            )
        )

        assertEquals(
            "Stopped: Backend unavailable\nTry again after Beta is fixed.",
            formatOrderSummaryStateLine(summary)
        )
    }
}
