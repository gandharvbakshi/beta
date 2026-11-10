package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.SwiggyMcpClient.RequestedItem
import com.example.beta.automation.InstructionParser
import com.example.beta.automation.IntentQuantityCandidate
import com.example.beta.automation.IntentCartQuantityMode
import com.example.beta.automation.ParsedItem
import com.example.beta.automation.Quantity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SwiggyCartReviewRequestTest {
    @Test
    fun validDefaultRowsKeepSourceOrderAndAllSkippedIsAValidEmptyRequest() {
        val items = listOf(item("tea bags"), item("milk"))
        val candidates = mapOf(0 to candidate("tea", "Tea Bags"), 1 to candidate("milk", "Milk"))

        val request = requireNotNull(SwiggyCartReviewRequest.build(items, candidates, emptySet()))
        assertEquals(listOf("tea", "milk"), request.requestedItems.map { it.spinId })
        assertEquals(listOf(1, 1), request.requestedItems.map { it.quantity })
        assertTrue(request.quantityProposals.isEmpty())

        val allSkipped = requireNotNull(SwiggyCartReviewRequest.build(items, candidates, setOf(0, 1)))
        assertTrue(allSkipped.requestedItems.isEmpty())
        assertTrue(allSkipped.quantityProposals.isEmpty())
    }

    @Test
    fun missingOutOfRangeHeldOrWrongIdentityRowsAreInvalid() {
        val milk = item("milk")
        assertNull(SwiggyCartReviewRequest.build(listOf(milk), emptyMap(), emptySet()))
        assertNull(SwiggyCartReviewRequest.build(listOf(milk), mapOf(1 to candidate("milk", "Milk")), emptySet()))
        assertNull(SwiggyCartReviewRequest.build(listOf(milk), mapOf(-1 to candidate("milk", "Milk")), emptySet()))
        assertNull(SwiggyCartReviewRequest.build(listOf(milk), mapOf(0 to candidate("eggs", "Eggs")), emptySet()))

        val packet = InstructionParser.parse("two packets frozen vegetables").single()
        val titleOnly = candidate("frozen", "Frozen Vegetables 6 Pieces")
        val assessed = assessSwiggyIntentQuantity(packet, SwiggyMcpClient.Recommendations(listOf(titleOnly)))
        assertNull(SwiggyCartReviewRequest.build(listOf(assessed), mapOf(0 to titleOnly), emptySet()))
    }

    @Test
    fun duplicateRowsAggregateOnlyWhenModeAndSkuAgreeAndStayWithinCap() {
        val butter = item("butter")
        val sameSku = candidate("butter", "Butter", sku = "sku-butter")
        val sameMode = requireNotNull(SwiggyCartReviewRequest.build(
            listOf(butter, butter.copy(rawText = "some butter")),
            mapOf(0 to sameSku, 1 to sameSku),
            emptySet(),
        ))
        assertEquals(listOf(RequestedItem("butter", 2, "Butter", "sku-butter")), sameMode.requestedItems)

        val mixedMode = SwiggyCartReviewRequest.build(
            listOf(butter, butter.copy(cartQuantityMode = IntentCartQuantityMode.INCREMENT)),
            mapOf(0 to sameSku, 1 to sameSku),
            emptySet(),
        )
        assertNull(mixedMode)

        val differentSkus = SwiggyCartReviewRequest.build(
            listOf(butter, butter.copy(rawText = "some butter")),
            mapOf(0 to sameSku, 1 to candidate("butter", "Butter", sku = "sku-other")),
            emptySet(),
        )
        assertNull(differentSkus)

        val elevenDefaults = List(21) { butter }
        val overflow = SwiggyCartReviewRequest.build(
            elevenDefaults, elevenDefaults.indices.associateWith { sameSku }, emptySet(),
        )
        assertNull(overflow)
    }

    @Test
    fun equalityIncludesSourceAndCandidateEvenWhenRequestedSkuAndQuantityMatch() {
        val firstItem = ParsedItem(rawText = "milk", query = "milk")
        val secondItem = ParsedItem(rawText = "please get milk", query = "milk")
        val firstCandidate = candidate("milk", "Milk", sku = "same-sku")
        val secondCandidate = firstCandidate.copy(suggested = true)

        val first = requireNotNull(SwiggyCartReviewRequest.build(
            listOf(firstItem), mapOf(0 to firstCandidate), emptySet(),
        ))
        val changedSource = requireNotNull(SwiggyCartReviewRequest.build(
            listOf(secondItem), mapOf(0 to firstCandidate), emptySet(),
        ))
        val changedCandidate = requireNotNull(SwiggyCartReviewRequest.build(
            listOf(firstItem), mapOf(0 to secondCandidate), emptySet(),
        ))

        assertEquals(first.requestedItems, changedSource.requestedItems)
        assertEquals(first.requestedItems, changedCandidate.requestedItems)
        assertNotEquals(first, changedSource)
        assertNotEquals(first, changedCandidate)
    }

    @Test
    fun snapshotDetachesMutableInputsAndExposesImmutableRequestAndProposalLists() {
        val avoid = mutableListOf("no salted butter")
        val original = item("butter").copy(avoidPhrases = avoid)
        val candidate = candidate("butter", "Butter")
        val rows = mutableListOf(original)
        val candidates = mutableMapOf(0 to candidate)
        val skipped = mutableSetOf<Int>()
        val snapshot = requireNotNull(SwiggyCartReviewRequest.build(rows, candidates, skipped))

        avoid += "changed preference"
        rows += item("eggs")
        candidates[0] = candidate("other-butter", "Other Butter")
        skipped += 0

        val unchangedExpected = requireNotNull(SwiggyCartReviewRequest.build(
            listOf(original.copy(avoidPhrases = listOf("no salted butter"))),
            mapOf(0 to candidate),
            emptySet(),
        ))
        assertEquals(unchangedExpected, snapshot)
        assertEquals(listOf("butter"), snapshot.requestedItems.map { it.spinId })
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.requestedItems as MutableList<RequestedItem>).clear()
        }

        val packet = InstructionParser.parse("two packets milk").single()
        val offer = packageOffer("Milk Packet", "500 ml")
        val assessed = assessSwiggyIntentQuantity(packet, SwiggyMcpClient.Recommendations(listOf(offer)))
        val mutableAvoid = mutableListOf("avoid synthetic ingredient")
        val check = requireNotNull(assessed.intentQuantityCatalogueCheck)
        val sourceProvenance = requireNotNull(assessed.intentQuantityProvenance)
        val mutableSourceLabels = sourceProvenance.opaqueNumericLabels.toMutableSet()
        val mutableCatalogueLabels = check.provenance!!.opaqueNumericLabels.toMutableSet()
        val originalSourceLabels = mutableSourceLabels.toSet()
        val originalCatalogueLabels = mutableCatalogueLabels.toSet()
        val mutableCheckCandidates = check.candidates.toMutableList()
        val mutableSource = assessed.copy(
            avoidPhrases = mutableAvoid,
            intentQuantityProvenance = sourceProvenance.copy(opaqueNumericLabels = mutableSourceLabels),
            intentQuantityCatalogueCheck = check.copy(
                provenance = check.provenance?.copy(opaqueNumericLabels = mutableCatalogueLabels),
                candidates = mutableCheckCandidates,
            ),
        )
        val withProposal = requireNotNull(SwiggyCartReviewRequest.build(
            listOf(mutableSource), mapOf(0 to offer), emptySet(),
        ))
        assertEquals(1, withProposal.quantityProposals.size)
        mutableAvoid += "later mutation"
        mutableSourceLabels += "later-source"
        mutableCatalogueLabels += "later-catalogue"
        mutableCheckCandidates += IntentQuantityCandidate("extra", "extra-sku", "Extra", null, null)
        val frozenEvidenceItem = withProposal.quantityProposals.single().evidence.sourceItem
        assertEquals(listOf("avoid synthetic ingredient"), frozenEvidenceItem.avoidPhrases)
        assertEquals(originalSourceLabels, frozenEvidenceItem.intentQuantityProvenance?.opaqueNumericLabels)
        assertEquals(
            originalCatalogueLabels,
            frozenEvidenceItem.intentQuantityCatalogueCheck?.provenance?.opaqueNumericLabels,
        )
        assertEquals(1, frozenEvidenceItem.intentQuantityCatalogueCheck?.candidates?.size)
        assertThrows(UnsupportedOperationException::class.java) {
            (withProposal.quantityProposals as MutableList<SwiggyQuantityProposalBinding>).clear()
        }
    }

    @Test
    fun ordinaryRetailProposalRemainsInTheReviewAcknowledgementSnapshot() {
        val packet = InstructionParser.parse("two packets milk").single()
        val offer = packageOffer("Milk Packet", "500 ml")
        val assessed = assessSwiggyIntentQuantity(packet, SwiggyMcpClient.Recommendations(listOf(offer)))

        val request = requireNotNull(SwiggyCartReviewRequest.build(
            listOf(assessed), mapOf(0 to offer), emptySet(),
        ))
        assertEquals(2, request.requestedItems.single().quantity)
        assertEquals(1, request.quantityProposals.size)
        val proposal = request.quantityProposals.single()
        assertEquals(0, proposal.rowIndex)
        assertEquals(2, proposal.quantity)
        assertEquals(offer.spinId, proposal.spinId)
        assertEquals(offer.skuId, proposal.skuId)
        // This ordinary physical offer uses the existing display-only review
        // path. The builder must preserve it, not manufacture typed proof.
        assertTrue(proposal.evidence.contents is SwiggyRetailOfferContents.DisplayOnly)
        assertEquals("500 ml", proposal.evidence.packSize)
        assertEquals("retail-offer-v1", proposal.evidence.resolverVersion)
    }

    private fun item(query: String) = ParsedItem(rawText = query, query = query)

    private fun candidate(spin: String, title: String, sku: String? = spin) =
        RecommendationCandidate(spinId = spin, label = title, skuId = sku)

    private fun packageOffer(title: String, size: String, subtitle: String? = null): RecommendationCandidate {
        val spin = "milk-package"
        val sku = "sku-milk-package"
        return RecommendationCandidate(
            spinId = spin,
            label = title,
            subtitle = subtitle,
            skuId = sku,
            catalogueQuantity = CatalogueQuantityEvidence(
                version = 1,
                source = "search_variation",
                spinId = spin,
                skuId = sku,
                fields = CatalogueQuantityFields(variantName = size),
            ),
        )
    }
}
