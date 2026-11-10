package com.example.beta

import com.example.beta.SwiggyMcpClient.CartPlanItem
import com.example.beta.SwiggyMcpClient.CartPlan
import com.example.beta.SwiggyMcpClient.CartPlanSnapshot
import com.example.beta.SwiggyMcpClient.CartReviewSnapshot
import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.SwiggyMcpClient.SwiggyAddress
import com.example.beta.automation.InstructionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Selection-only refresh may carry consent only for the identical reviewed basket context. */
class SwiggyQuantityProposalRefreshTest {
    @Test
    fun acknowledgedPacketProposalCarriesOnlyToNewTokenAndConsumesOnce() {
        val fixture = fixture()
        val gate = SwiggyQuantityProposalGate()
        gate.prepareWithContext(OPERATION, ADDRESS.id, plan(OLD_TOKEN), fixture.proposals, fixture.context)
        assertTrue(gate.acknowledge(OPERATION, ADDRESS.id, OLD_TOKEN, fixture.proposals, checked = true))

        gate.beginRefresh(fixture.context)
        assertFalse(gate.canApply(OPERATION, ADDRESS.id, OLD_TOKEN, fixture.proposals))
        gate.beginRefresh(fixture.context) // Repeating a refresh for the same context is safe.

        assertTrue(gate.prepareAfterRefresh(
            OPERATION, ADDRESS.id, plan(NEW_TOKEN), fixture.proposals, fixture.context,
        ))
        assertTrue(gate.canApply(OPERATION, ADDRESS.id, NEW_TOKEN, fixture.proposals))
        assertTrue(gate.consumeForApply(OPERATION, ADDRESS.id, NEW_TOKEN, fixture.proposals))
        assertFalse(gate.consumeForApply(OPERATION, ADDRESS.id, NEW_TOKEN, fixture.proposals))
        assertFalse(gate.canApply(OPERATION, ADDRESS.id, OLD_TOKEN, fixture.proposals))
    }

    @Test
    fun carriedAcknowledgementCannotReturnToAnEarlierPlanToken() {
        val fixture = fixture()
        val gate = SwiggyQuantityProposalGate()
        gate.prepareWithContext(OPERATION, ADDRESS.id, plan(OLD_TOKEN), fixture.proposals, fixture.context)
        assertTrue(gate.acknowledge(OPERATION, ADDRESS.id, OLD_TOKEN, fixture.proposals, checked = true))

        gate.beginRefresh(fixture.context)
        assertTrue(gate.prepareAfterRefresh(
            OPERATION, ADDRESS.id, plan(NEW_TOKEN), fixture.proposals, fixture.context,
        ))
        gate.beginRefresh(fixture.context)
        assertFalse(gate.prepareAfterRefresh(
            OPERATION, ADDRESS.id, plan(OLD_TOKEN), fixture.proposals, fixture.context,
        ))
        assertFalse(gate.canApply(OPERATION, ADDRESS.id, OLD_TOKEN, fixture.proposals))
        assertFalse(gate.acknowledge(OPERATION, ADDRESS.id, OLD_TOKEN, fixture.proposals, checked = true))
        assertFalse(gate.canApply(OPERATION, ADDRESS.id, OLD_TOKEN, fixture.proposals))
    }

    @Test
    fun repeatedRefreshPreparationCarriesOnlyToFirstResultPlan() {
        val f = fixture()
        val gate = acknowledged(f)
        gate.beginRefresh(f.context)
        gate.beginRefresh(f.context)
        assertTrue(gate.prepareAfterRefresh(OPERATION, ADDRESS.id, plan("refresh-result-one"), f.proposals, f.context))
        assertFalse(gate.prepareAfterRefresh(OPERATION, ADDRESS.id, plan("refresh-result-two"), f.proposals, f.context))
        assertFalse(gate.canApply(OPERATION, ADDRESS.id, "refresh-result-two", f.proposals))
    }

    @Test
    fun unacknowledgedOrOrdinarilyPreparedProposalCannotCarryAcrossRefresh() {
        val fixture = fixture()
        val gate = SwiggyQuantityProposalGate()
        gate.prepareWithContext(OPERATION, ADDRESS.id, plan(OLD_TOKEN), fixture.proposals, fixture.context)
        gate.beginRefresh(fixture.context)
        assertFalse(gate.prepareAfterRefresh(
            OPERATION, ADDRESS.id, plan(NEW_TOKEN), fixture.proposals, fixture.context,
        ))

        gate.prepareWithContext(OPERATION, ADDRESS.id, plan(OLD_TOKEN), fixture.proposals, fixture.context)
        assertTrue(gate.acknowledge(OPERATION, ADDRESS.id, OLD_TOKEN, fixture.proposals, checked = true))
        gate.beginRefresh(fixture.context)
        gate.prepare(OPERATION, ADDRESS.id, OLD_TOKEN, fixture.proposals)
        assertFalse(gate.prepareAfterRefresh(
            OPERATION, ADDRESS.id, plan(NEW_TOKEN), fixture.proposals, fixture.context,
        ))
    }

    @Test
    fun changedReviewedContextOrProposalCannotReuseAcknowledgement() {
        val fixture = fixture()
        val gate = SwiggyQuantityProposalGate()
        gate.prepareWithContext(OPERATION, ADDRESS.id, plan(OLD_TOKEN), fixture.proposals, fixture.context)
        assertTrue(gate.acknowledge(OPERATION, ADDRESS.id, OLD_TOKEN, fixture.proposals, checked = true))

        val changedContexts = listOf(
            context(instruction = "get two packets of milk", items = fixture.items) to snapshot(),
            context(items = fixture.items, reviewText = SwiggyQuantityReviewText(unresolved = listOf("different review notes"))) to snapshot(),
            context(items = fixture.items, snapshot = snapshot("cart-basis-v1:${"b".repeat(64)}")) to snapshot("cart-basis-v1:${"b".repeat(64)}"),
            context(items = fixture.items, snapshot = snapshot().copy(existingItems = listOf(CartPlanItem("other-existing", 1)))) to snapshot().copy(existingItems = listOf(CartPlanItem("other-existing", 1))),
        )
        changedContexts.forEach { (changed, changedSnapshot) ->
            gate.beginRefresh(changed)
            assertFalse(gate.prepareAfterRefresh(
                OPERATION, ADDRESS.id, plan(NEW_TOKEN, changedSnapshot), fixture.proposals, changed,
            ))
            gate.prepareWithContext(OPERATION, ADDRESS.id, plan(OLD_TOKEN), fixture.proposals, fixture.context)
            assertTrue(gate.acknowledge(OPERATION, ADDRESS.id, OLD_TOKEN, fixture.proposals, checked = true))
        }

        val unresolvedText = context(
            items = fixture.items,
            reviewText = SwiggyQuantityReviewText(unresolved = listOf("same note")),
        )
        gate.prepareWithContext(OPERATION, ADDRESS.id, plan(OLD_TOKEN), fixture.proposals, unresolvedText)
        assertTrue(gate.acknowledge(OPERATION, ADDRESS.id, OLD_TOKEN, fixture.proposals, checked = true))
        val textMovedCategory = context(
            items = fixture.items,
            reviewText = SwiggyQuantityReviewText(excluded = listOf("same note")),
        )
        gate.beginRefresh(textMovedCategory)
        assertFalse(gate.prepareAfterRefresh(
            OPERATION, ADDRESS.id, plan(NEW_TOKEN), fixture.proposals, textMovedCategory,
        ))

        val changedSource = context(items = listOf(fixture.items.single().copy(rawText = "changed source")))
        gate.beginRefresh(changedSource)
        assertTrue(runCatching {
            gate.prepareAfterRefresh(OPERATION, ADDRESS.id, plan(NEW_TOKEN), fixture.proposals, changedSource)
        }.isFailure) // The stale proposal no longer binds the reviewed source.

        val changedCandidate = SwiggyQuantityReviewContext.create(
            instruction = INSTRUCTION,
            address = ADDRESS,
            items = fixture.items,
            candidates = mapOf(0 to offer().copy(label = "Different milk offer")),
            skipped = emptySet(),
            reviewText = SwiggyQuantityReviewText(),
            snapshot = fixture.snapshot,
        )
        gate.beginRefresh(changedCandidate)
        assertTrue(runCatching {
            gate.prepareAfterRefresh(OPERATION, ADDRESS.id, plan(NEW_TOKEN), fixture.proposals, changedCandidate)
        }.isFailure)

        val skippedContext = SwiggyQuantityReviewContext.create(
            instruction = INSTRUCTION, address = ADDRESS, items = fixture.items,
            candidates = mapOf(0 to offer()), skipped = setOf(0), reviewText = SwiggyQuantityReviewText(),
            snapshot = fixture.snapshot,
        )
        gate.beginRefresh(skippedContext)
        assertFalse(gate.prepareAfterRefresh(OPERATION, ADDRESS.id, plan(NEW_TOKEN), emptyList(), skippedContext))

        val changedAddress = ADDRESS.copy(id = "other-address")
        val addressContext = SwiggyQuantityReviewContext.create(
            instruction = INSTRUCTION, address = changedAddress, items = fixture.items,
            candidates = mapOf(0 to offer()), skipped = emptySet(), reviewText = SwiggyQuantityReviewText(),
            snapshot = fixture.snapshot,
        )
        gate.beginRefresh(addressContext)
        assertFalse(gate.prepareAfterRefresh(
            OPERATION, changedAddress.id, plan(NEW_TOKEN), fixture.proposals, addressContext,
        ))

        gate.beginRefresh(fixture.context)
        assertFalse(gate.prepareAfterRefresh(
            OPERATION, ADDRESS.id, plan(NEW_TOKEN),
            listOf(fixture.proposals.single().copy(skuId = "different-sku")), fixture.context,
        ))
    }

    @Test
    fun refreshRejectsPlanSnapshotThatDoesNotMatchTheReviewedContextAndClearsState() {
        val f = fixture()
        val invalidPlans = listOf(
            plan(NEW_TOKEN, snapshot("cart-basis-v1:${"b".repeat(64)}")),
            plan(NEW_TOKEN, snapshot().copy(existingItems = listOf(CartPlanItem("existing-spin", 2, "existing-sku")))),
            plan(NEW_TOKEN, snapshot().copy(existingItems = listOf(CartPlanItem("existing-spin", 1, "other-sku")))),
            plan(NEW_TOKEN, cartReview = null),
            plan(NEW_TOKEN, planBasis = null),
            plan(NEW_TOKEN, planBasis = "malformed"),
            plan(token = null),
            plan(token = " "),
        )
        for (invalid in invalidPlans) for (refreshing in listOf(false, true)) {
            val gate = acknowledged(f)
            if (refreshing) gate.beginRefresh(f.context)
            assertTrue(runCatching {
                if (refreshing) gate.prepareAfterRefresh(OPERATION, ADDRESS.id, invalid, f.proposals, f.context)
                else gate.prepareWithContext(OPERATION, ADDRESS.id, invalid, f.proposals, f.context)
            }.isFailure)
            assertFalse(gate.canApply(OPERATION, ADDRESS.id, OLD_TOKEN, f.proposals))
            assertFalse(gate.prepareAfterRefresh(
                OPERATION, ADDRESS.id, plan(NEW_TOKEN), f.proposals, f.context,
            ))
        }
    }

    @Test
    fun contextValidatesBasisAndProposalSourceCandidateBinding() {
        val fixture = fixture()
        assertTrue(runCatching { context(snapshot = snapshot("invalid-basis")) }.isFailure)
        assertTrue(runCatching {
            SwiggyQuantityReviewContext.create(
                instruction = INSTRUCTION,
                address = ADDRESS,
                items = fixture.items,
                candidates = mapOf(1 to offer()),
                skipped = emptySet(),
                reviewText = SwiggyQuantityReviewText(),
                snapshot = fixture.snapshot,
            )
        }.isFailure)
    }

    @Test
    fun contextDetachesCollectionsAndDoesNotExposePrivateReviewContentInDiagnostics() {
        val fixture = fixture()
        val mutableItems = fixture.items.toMutableList()
        val mutableCandidates = mutableMapOf(0 to offer())
        val mutableReview = mutableListOf("private review note")
        val mutableReviewText = SwiggyQuantityReviewText(unresolved = mutableReview)
        val captured = SwiggyQuantityReviewContext.create(
            instruction = INSTRUCTION,
            address = ADDRESS,
            items = mutableItems,
            candidates = mutableCandidates,
            skipped = mutableSetOf(),
            reviewText = mutableReviewText,
            snapshot = fixture.snapshot,
        )
        mutableItems.clear()
        mutableCandidates.clear()
        mutableReview.clear()

        assertEquals(context(items = fixture.items,
            reviewText = SwiggyQuantityReviewText(unresolved = listOf("private review note"))), captured)
        assertTrue(captured.toString().contains("SwiggyQuantityReviewContext"))
        assertFalse(captured.toString().contains("private review note"))
        assertFalse(captured.toString().contains("two packets milk"))
    }

    @Test fun changedAndRestoredContextCannotReviveCarry() {
        val f = fixture()
        val gate = acknowledged(f)
        gate.beginRefresh(f.context)
        gate.beginRefresh(context(items = f.items, instruction = "changed instruction"))
        gate.beginRefresh(f.context)
        assertFalse(gate.prepareAfterRefresh(OPERATION, ADDRESS.id, plan(NEW_TOKEN), f.proposals, f.context))
        assertFalse(gate.canApply(OPERATION, ADDRESS.id, NEW_TOKEN, f.proposals))
    }

    @Test fun missingOrMalformedContextCannotLeaveAnOldTokenUsable() {
        val f = fixture()
        for (missing in listOf(false, true)) {
            val gate = acknowledged(f)
            gate.beginRefresh(if (missing) null else f.context) // Always FIRST, before new context construction.
            assertTrue(runCatching { context(snapshot = snapshot("malformed")) }.isFailure)
            assertFalse(gate.canApply(OPERATION, ADDRESS.id, OLD_TOKEN, f.proposals))
            gate.clear() // Failed refresh/lifecycle cleanup discards tentative acknowledgement.
            assertFalse(gate.prepareAfterRefresh(OPERATION, ADDRESS.id, plan(NEW_TOKEN), f.proposals, f.context))
        }
    }

    @Test fun staleAcknowledgementAndApplyAttemptDestroyTentativeCarry() {
        val f = fixture()
        for (attempt in listOf("acknowledge", "apply", "clear")) {
            val gate = acknowledged(f)
            gate.beginRefresh(f.context)
            when (attempt) {
                "acknowledge" -> assertFalse(gate.acknowledge(OPERATION, ADDRESS.id, OLD_TOKEN, f.proposals, true))
                "apply" -> assertFalse(gate.consumeForApply(OPERATION, ADDRESS.id, OLD_TOKEN, f.proposals))
                else -> gate.clear()
            }
            assertFalse(gate.prepareAfterRefresh(OPERATION, ADDRESS.id, plan(NEW_TOKEN), f.proposals, f.context))
        }
    }

    @Test fun changedProposalSetAndOperationLoseAcknowledgement() {
        val f = fixture()
        for ((operation, proposals) in listOf(OPERATION + 1 to f.proposals,
            OPERATION to emptyList(), OPERATION to listOf(f.proposals.single().copy(quantity = 1)))) {
            val gate = acknowledged(f)
            gate.beginRefresh(f.context)
            assertFalse(gate.prepareAfterRefresh(operation, ADDRESS.id, plan(NEW_TOKEN), proposals, f.context))
        }
    }

    @Test fun everyDisplayedRowAndBaselineFactParticipatesEvenWhenNotSelected() {
        val f = fixture()
        val extra = com.example.beta.automation.ParsedItem("coffee", "coffee", com.example.beta.automation.Quantity.Default)
        val extraCandidate = offer().copy(spinId = "coffee-spin", skuId = "coffee-sku", label = "Coffee")
        val allItems = f.items + extra
        val allCandidates = mapOf(0 to offer(), 1 to extraCandidate)
        fun full(items: List<com.example.beta.automation.ParsedItem> = allItems,
                 candidates: Map<Int, RecommendationCandidate> = allCandidates,
                 address: SwiggyAddress = ADDRESS, skipped: Set<Int> = emptySet(),
                 cart: CartReviewSnapshot = snapshot()) = SwiggyQuantityReviewContext.create(
            "$INSTRUCTION, coffee", address, items, candidates, skipped, SwiggyQuantityReviewText(), cart,
        )
        for (skipped in listOf(emptySet(), setOf(1))) {
            val base = full(skipped = skipped)
            val changed = listOf(
                full(items = f.items + extra.copy(rawText = "decaf coffee"), skipped = skipped) to snapshot(),
                full(candidates = allCandidates + (1 to extraCandidate.copy(label = "Other coffee")), skipped = skipped) to snapshot(),
                full(address = ADDRESS.copy(label = "Different home details"), skipped = skipped) to snapshot(),
                full(cart = snapshot().copy(existingItems = listOf(CartPlanItem("existing-spin", 2, "existing-sku"))), skipped = skipped) to snapshot().copy(existingItems = listOf(CartPlanItem("existing-spin", 2, "existing-sku"))),
                full(cart = snapshot().copy(existingItems = listOf(CartPlanItem("existing-spin", 1, "other-sku"))), skipped = skipped) to snapshot().copy(existingItems = listOf(CartPlanItem("existing-spin", 1, "other-sku"))),
            )
            for ((next, nextSnapshot) in changed) {
                val gate = SwiggyQuantityProposalGate()
                val baseSnapshot = snapshot()
                gate.prepareWithContext(OPERATION, ADDRESS.id, plan(OLD_TOKEN, baseSnapshot), f.proposals, base)
                assertTrue(gate.acknowledge(OPERATION, ADDRESS.id, OLD_TOKEN, f.proposals, true))
                gate.beginRefresh(base)
                assertFalse(gate.prepareAfterRefresh(OPERATION, ADDRESS.id, plan(NEW_TOKEN, nextSnapshot), f.proposals, next))
                assertFalse(gate.canApply(OPERATION, ADDRESS.id, NEW_TOKEN, f.proposals))
            }
        }
        assertTrue(runCatching { full(candidates = mapOf(0 to offer())) }.isFailure)
    }

    @Test fun carryLineageIsBoundedAndNeverDropsHistoryToKeepAnAcknowledgement() {
        val f = fixture()
        val gate = acknowledged(f)
        for (index in 1..127) {
            gate.beginRefresh(f.context)
            assertTrue(gate.prepareAfterRefresh(OPERATION, ADDRESS.id, plan("token-$index"), f.proposals, f.context))
        }
        gate.beginRefresh(f.context)
        assertFalse(gate.prepareAfterRefresh(OPERATION, ADDRESS.id, plan("token-128"), f.proposals, f.context))
        assertFalse(gate.canApply(OPERATION, ADDRESS.id, "token-128", f.proposals))
    }

    private fun acknowledged(f: Fixture) = SwiggyQuantityProposalGate().also { gate ->
        gate.prepareWithContext(OPERATION, ADDRESS.id, plan(OLD_TOKEN), f.proposals, f.context)
        assertTrue(gate.acknowledge(OPERATION, ADDRESS.id, OLD_TOKEN, f.proposals, true))
    }

    private data class Fixture(
        val items: List<com.example.beta.automation.ParsedItem>,
        val snapshot: CartReviewSnapshot,
        val proposals: List<SwiggyQuantityProposalBinding>,
        val context: SwiggyQuantityReviewContext,
    )

    private fun fixture(): Fixture {
        val parsed = InstructionParser.parse(INSTRUCTION)
        val offer = offer()
        val assessed = assessSwiggyIntentQuantity(parsed.single(), SwiggyMcpClient.Recommendations(listOf(offer)))
        val request = requireNotNull(SwiggyCartReviewRequest.build(listOf(assessed), mapOf(0 to offer), emptySet()))
        assertEquals(2, request.requestedItems.single().quantity)
        assertEquals(1, request.quantityProposals.size)
        val snapshot = snapshot()
        return Fixture(
            listOf(assessed), snapshot, request.quantityProposals,
            context(items = listOf(assessed), snapshot = snapshot),
        )
    }

    private fun context(
        instruction: String = INSTRUCTION,
        items: List<com.example.beta.automation.ParsedItem> = InstructionParser.parse(INSTRUCTION),
        reviewText: SwiggyQuantityReviewText = SwiggyQuantityReviewText(),
        snapshot: CartReviewSnapshot = snapshot(),
    ) = SwiggyQuantityReviewContext.create(
        instruction = instruction,
        address = ADDRESS,
        items = items,
        candidates = mapOf(0 to offer()),
        skipped = emptySet(),
        reviewText = reviewText,
        snapshot = snapshot,
    )

    private fun offer() = RecommendationCandidate(
        spinId = "milk-packet-spin",
        label = "Milk Packet",
        skuId = "milk-packet-sku",
        catalogueQuantity = CatalogueQuantityEvidence(
            version = 1,
            source = "search_variation",
            spinId = "milk-packet-spin",
            skuId = "milk-packet-sku",
            fields = CatalogueQuantityFields(variantName = "500 ml"),
        ),
    )

    private fun snapshot(basis: String = BASIS) = CartReviewSnapshot(
        existingItems = listOf(CartPlanItem("existing-spin", 1, "existing-sku")),
        reviewBasis = basis,
    )

    private fun plan(
        token: String? = NEW_TOKEN,
        cartReview: CartReviewSnapshot? = snapshot(),
        planBasis: String? = cartReview?.reviewBasis,
    ) = CartPlan(
        changes = emptyList(),
        confirmationToken = token,
        snapshot = cartReview?.let {
            CartPlanSnapshot(
                existingItems = it.existingItems,
                proposedItems = listOf(CartPlanItem("milk-packet-spin", 2, "milk-packet-sku")),
                reviewBasis = planBasis,
            )
        },
    )

    private companion object {
        const val OPERATION = 91L
        const val OLD_TOKEN = "refresh-old-token"
        const val NEW_TOKEN = "refresh-new-token"
        const val INSTRUCTION = "two packets milk"
        const val ADDRESS_ID = "synthetic-address"
        val ADDRESS = SwiggyAddress(ADDRESS_ID, "Synthetic home")
        val BASIS = "cart-basis-v1:${"a".repeat(64)}"
    }
}
