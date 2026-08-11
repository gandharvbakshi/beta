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
    fun verificationOutcomeAccounting_marksAlreadyInCartAsZeroAddedAndNoIncrementLog() {
        val accounting = accountVerificationOutcome("already_in_cart", 3)

        assertEquals(0, accounting.qtyAdded)
        assertEquals("already_in_cart", accounting.notes)
        assertTrue(!accounting.emitCartIncrementLog)
    }

    @Test
    fun verificationOutcomeAccounting_usesRequestedQuantityForFreshSuccess() {
        val accounting = accountVerificationOutcome("verified_in_cart", 4)

        assertEquals(4, accounting.qtyAdded)
        assertEquals("verified_in_cart", accounting.notes)
        assertTrue(accounting.emitCartIncrementLog)
    }

    @Test
    fun outcomeQuantityNormalization_preservesZeroForAlreadyInCartSuccess() {
        assertEquals(
            0,
            normalizeOutcomeQuantityAdded(
                status = ItemOutcomeStatus.SUCCESS,
                qtyAdded = 0,
                notes = "already_in_cart"
            )
        )
    }

    @Test
    fun outcomeQuantityNormalization_preservesLegacyDefaultForFreshSuccess() {
        assertEquals(
            1,
            normalizeOutcomeQuantityAdded(
                status = ItemOutcomeStatus.SUCCESS,
                qtyAdded = 0,
                notes = "verified_in_cart"
            )
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
            terminalFailureStatusForReason(
                "Blinkit limited 2 matching items below quantity 2; Beta removed them and stopped with a clean cart."
            )
        )
        assertEquals(
            ItemOutcomeStatus.OOS,
            terminalFailureStatusForReason(
                "Blinkit limited this item to one, but Beta could not safely remove it before retrying."
            )
        )
        assertEquals(
            ItemOutcomeStatus.QUANTITY_NOT_REACHED,
            terminalFailureStatusForReason("Item was added, but quantity controls did not reach requested quantity 6.")
        )
        assertEquals(
            ItemOutcomeStatus.QUANTITY_NOT_REACHED,
            terminalFailureStatusForReason("Requested quantity 2 is not visible yet")
        )
        assertEquals(
            ItemOutcomeStatus.TIMEOUT,
            terminalFailureStatusForReason(
                "ADD was already attempted for atta, but cart verification never became available."
            )
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
        assertEquals(
            "quantity_not_reached",
            terminalFailureNoteForStatus(ItemOutcomeStatus.QUANTITY_NOT_REACHED, "workflow_failed")
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
