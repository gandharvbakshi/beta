package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.SwiggyMcpClient.Recommendations
import com.example.beta.automation.IntentSourceCountRole
import com.example.beta.automation.Quantity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentCompositeRetailFlowTest {
    private data class Example(
        val source: String,
        val identity: String,
        val count: Int,
        val shape: String,
    )

    private val examples = listOf(
        Example("2 x 500 ml milk packs paanch", "milk", 5, "2 x 500 ml"),
        Example("rice 2 x 500 g packs 3", "rice", 3, "2 x 500 g"),
        Example("3 packets of 500 ml x 2 juice", "juice", 3, "2 x 500 ml"),
        Example("3 packs rice 2 x 500 g", "rice", 3, "2 x 500 g"),
    )

    @Test
    fun genericPackCompositeRequestsBecomeOnlyTypedReviewedRetailProposals() {
        examples.forEachIndexed { index, example ->
            val item = prepareSwiggyMcpItems(example.source) { null }.single()
            val receipt = requireNotNull(item.intentQuantityProvenance?.compositeRetailReceipt)
            assertEquals(example.source, receipt.source)
            assertEquals(example.count, receipt.requestedCount.value.intValueExact())
            assertEquals(IntentSourceCountRole.COMPOSITE_RETAIL_OFFER,
                item.intentQuantityProvenance?.sourceCountRole)

            val candidate = candidate(example.identity, example.shape)
            val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
            val result = resolveSwiggyCartQuantity(checked, candidate)
            assertTrue("case $index should remain a proposal, not Exact", result is SwiggyCartQuantityResolution.RetailProposal)
            result as SwiggyCartQuantityResolution.RetailProposal
            assertEquals(example.count, result.cartUnits)
            assertTrue(result.contents is SwiggyRetailOfferContents.ProvenComposite)
            assertTrue("exact-only quantity API must reject a proposal", runCatching {
                swiggyRequestedCartQuantity(checked, candidate)
            }.isFailure)

            val binding = requireNotNull(swiggyQuantityProposalBinding(index, checked, candidate))
            assertEquals(example.count, binding.quantity)
            assertEquals("retail-offer-v3", binding.evidence.resolverVersion)
            val gate = SwiggyQuantityProposalGate()
            gate.prepare(1L, "address", "plan", listOf(binding))
            assertFalse("proposal cannot apply before acknowledgement",
                gate.canApply(1L, "address", "plan", listOf(binding)))
            assertTrue(gate.acknowledge(1L, "address", "plan", listOf(binding), checked = true))
            assertTrue(gate.canApply(1L, "address", "plan", listOf(binding)))
        }
    }

    @Test
    fun titleOnlySplitOrStructurallyDifferentCatalogueShapeIsHeld() {
        val example = examples.first()
        val item = assess(example)
        val invalid = listOf(
            candidate(example.identity, example.shape, typed = false),
            candidate(example.identity, "2 x", quantityDescription = "500 ml"),
            candidate(example.identity, "4 x 250 ml"), // Same total, different decomposition.
            candidate(example.identity, "3 x 500 ml"), // Same inner size, different factor.
        )
        invalid.forEachIndexed { index, offer ->
            val checked = assessSwiggyIntentQuantity(itemWithoutAssessment(example), Recommendations(listOf(offer)))
            assertTrue("invalid catalogue shape $index must be held",
                resolveSwiggyCartQuantity(checked, offer) is SwiggyCartQuantityResolution.Held)
        }
        assertTrue(resolveSwiggyCartQuantity(item, invalid.first()) is SwiggyCartQuantityResolution.Held)
    }

    @Test
    fun compositeProofAndCandidateBindingMutationsCannotRetainProposalAuthority() {
        val example = examples.first()
        val sourceItem = itemWithoutAssessment(example)
        val candidate = candidate(example.identity, example.shape)
        val assessed = assessSwiggyIntentQuantity(sourceItem, Recommendations(listOf(candidate)))
        val baseline = resolveSwiggyCartQuantity(assessed, candidate)
        assertTrue(baseline is SwiggyCartQuantityResolution.RetailProposal)

        val proof = requireNotNull(assessed.intentQuantityProvenance)
        val changedProofs = listOf(
            proof.copy(compositeRetailReceipt = null),
            proof.copy(sourceCountRole = IntentSourceCountRole.REQUESTED_PIECES),
            proof.copy(query = "milk other"),
            proof.copy(local = proof.local?.copy(value = requireNotNull(proof.local).value.add(java.math.BigDecimal.ONE))),
        )
        changedProofs.forEachIndexed { index, changed ->
            val changedItem = assessSwiggyIntentQuantity(
                assessed.copy(intentQuantityProvenance = changed), Recommendations(listOf(candidate)),
            )
            assertHeld(changedItem, candidate, "proof mutation $index")
        }

        val receipt = requireNotNull(proof.compositeRetailReceipt)
        val changedReceipts = listOf(
            receipt.copy(requestedCount = receipt.requestedCount.copy(startUtf16 = receipt.requestedCount.startUtf16 + 1)),
            receipt.copy(requestedCount = receipt.requestedCount.copy(endUtf16 = receipt.requestedCount.endUtf16 - 1)),
            receipt.copy(skuFactor = receipt.skuFactor.copy(startUtf16 = receipt.skuFactor.startUtf16 + 1)),
            receipt.copy(skuFactor = receipt.skuFactor.copy(endUtf16 = receipt.skuFactor.endUtf16 - 1)),
            receipt.copy(skuFactor = receipt.skuFactor.copy(value = java.math.BigDecimal("3"))),
            receipt.copy(innerMeasure = receipt.innerMeasure.copy(startUtf16 = receipt.innerMeasure.startUtf16 + 1)),
            receipt.copy(innerMeasure = receipt.innerMeasure.copy(endUtf16 = receipt.innerMeasure.endUtf16 - 1)),
            receipt.copy(innerMeasure = receipt.innerMeasure.copy(unit = "g")),
            receipt.copy(shapeSpan = (receipt.shapeSpan.first + 1)..receipt.shapeSpan.last),
            receipt.copy(container = (receipt.container.first + 1)..receipt.container.last),
            receipt.copy(identity = (receipt.identity.first + 1)..receipt.identity.last),
        )
        changedReceipts.forEachIndexed { index, changedReceipt ->
            val changedItem = assessSwiggyIntentQuantity(
                assessed.copy(intentQuantityProvenance = proof.copy(compositeRetailReceipt = changedReceipt)),
                Recommendations(listOf(candidate)),
            )
            assertHeld(changedItem, candidate, "receipt span/value mutation $index")
        }

        val changedItemFacts = listOf(
            assessed.copy(rawText = "2 packs of 2 x 500 ml milk"),
            assessed.copy(quantity = Quantity.Count(6)),
            assessed.copy(query = "milk other"),
            assessed.copy(strictMatchPhrase = "milk other"),
        )
        changedItemFacts.forEachIndexed { index, changed ->
            assertHeld(assessSwiggyIntentQuantity(changed, Recommendations(listOf(candidate))), candidate,
                "item mutation $index")
        }

        val strippedAuthority = assessed.copy(
            requiresIntentQuantityProof = false,
            retailPackCount = false,
            intentQuantityProvenance = null,
        )
        assertHeld(assessSwiggyIntentQuantity(strippedAuthority, Recommendations(listOf(candidate))), candidate,
            "proof and parser flags removed")

        val changedCandidates = listOf(
            candidate.copy(skuId = "another-sku"),
            candidate.copy(catalogueQuantityInvalid = true),
            candidate.copy(catalogueQuantity = candidate.catalogueQuantity?.copy(source = "product_detail")),
        )
        changedCandidates.forEachIndexed { index, changed ->
            assertHeld(assessSwiggyIntentQuantity(sourceItem, Recommendations(listOf(changed))), changed,
                "candidate mutation $index")
        }

        val otherCandidate = candidate("milk", "2 x 500 ml", sku = "other-sku", evidenceSku = "other-sku")
        assertHeld(assessed, otherCandidate, "other SKU without reassessment")
        assertNull("removing the proof must invalidate the binding",
            swiggyQuantityProposalBinding(0, assessed.copy(intentQuantityProvenance = null), candidate))
    }

    @Test
    fun namedBarUnitsAndNonActionableParserContextsDoNotGrantCompositeCount() {
        val named = prepareSwiggyMcpItems("2 x 100 g soap bars 4") { null }.single()
        val barCandidate = candidate("soap", "2 x 100 g", containerTitle = "Soap Bars")
        val assessed = assessSwiggyIntentQuantity(named, Recommendations(listOf(barCandidate)))
        assertTrue(resolveSwiggyCartQuantity(assessed, barCandidate) is SwiggyCartQuantityResolution.Held)

        val nonActionable = listOf(
            "do not get three packs of 2 x 500 ml milk",
            "if available, three packs of 2 x 500 ml milk",
            "unless expensive, three packs of 2 x 500 ml milk",
            "someone said, three packs of 2 x 500 ml milk",
            "no, three packs of 2 x 500 ml milk",
            "three packs of 2 x 500 ml milk, if cheap",
            "agar mil jaye, three packs of 2 x 500 ml milk",
            "three packs of 2 x 500 ml milk, no sugar",
            "three packs of 2 x 500 ml milk, organic",
            "milk, milk, three packs of 2 x 500 ml juice",
            "3 packs of 2 x 500 ml milk, 2 packs",
            "3 packs of 2 x 500 ml milk, 1 l",
            "3 packs of 2 x 500 ml milk and 250 ml",
            "2 packs, 3 packs of 2 x 500 ml milk",
            "maybe, three packs of 2 x 500 ml milk",
            "when cheap, three packs of 2 x 500 ml milk",
            "three packs of 2 x 500 ml milk, when cheap",
            "shayad, three packs of 2 x 500 ml milk",
        )
        val wronglyActionable = nonActionable.filter { source ->
            val parsed = prepareSwiggyMcpItems(source) { null }
            val offer = candidate(if (source.endsWith("juice")) "juice" else "milk", "2 x 500 ml")
            parsed.any { item ->
                val result = resolveSwiggyCartQuantity(assessSwiggyIntentQuantity(item, Recommendations(listOf(offer))), offer)
                result !is SwiggyCartQuantityResolution.Held
            }
        }
        assertTrue("Unproven whole-instruction scope: $wronglyActionable", wronglyActionable.isEmpty())
    }

    @Test
    fun independentListRowsKeepTheirOwnSourceAmounts() {
        val source = "3 packs of 2 x 500 ml milk; 4 packets of 2 x 500 g rice"
        val parsed = prepareSwiggyMcpItems(source) { null }
        assertEquals(2, parsed.size)
        for ((index, identity) in listOf("milk", "rice").withIndex()) {
            val shape = if (index == 0) "2 x 500 ml" else "2 x 500 g"
            val offer = candidate(identity, shape)
            val checked = assessSwiggyIntentQuantity(parsed[index], Recommendations(listOf(offer)))
            assertReviewedRetailProposal(checked, offer, index + 3)
        }
    }

    @Test
    fun plainSiblingProductsAndNeutralCourtesyDoNotDisableCompositeOffers() {
        for (source in listOf("3 packs of 2 x 500 ml milk, bread",
            "eggs, 3 packs of 2 x 500 ml milk, bread",
            "please add 3 packs of 2 x 500 ml milk, thanks")) {
            val parsed = prepareSwiggyMcpItems(source) { null }
            assertNull(source, swiggyMcpItemValidationMessage(source, parsed))
            val item = parsed.single { it.validCompositeRetailReceipt() != null }
            val offer = candidate("milk", "2 x 500 ml")
            assertReviewedRetailProposal(assessSwiggyIntentQuantity(item, Recommendations(listOf(offer))), offer, 3)
        }
    }

    @Test
    fun ambiguousRepeatedFactorAndRequestedCountDoesNotEnterCompositeProposalPath() {
        val source = "2 x 500 ml milk packs 2"
        val item = prepareSwiggyMcpItems(source) { null }.single()
        assertNull(item.intentQuantityProvenance?.compositeRetailReceipt)
        assertFalse(item.intentQuantityProvenance?.sourceCountRole == IntentSourceCountRole.COMPOSITE_RETAIL_OFFER)
        val offer = candidate("milk", "2 x 500 ml")
        val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(offer)))
        assertHeld(checked, offer, "ambiguous repeated factor/count source")
    }

    @Test
    fun largeInnerFactorIsNotMistakenForPurchaseCountAndProductSequenceStaysAmbiguous() {
        val source = "3 packs of 24 x 10 g tea"
        val parsed = prepareSwiggyMcpItems(source) { null }
        assertEquals(1, parsed.size)
        assertNull(swiggyMcpItemValidationMessage(source, parsed))
        val tea = candidate("tea", "24 x 10 g")
        val checked = assessSwiggyIntentQuantity(parsed.single(), Recommendations(listOf(tea)))
        val result = resolveSwiggyCartQuantity(checked, tea)
        assertTrue(result is SwiggyCartQuantityResolution.RetailProposal)
        assertEquals(3, (result as SwiggyCartQuantityResolution.RetailProposal).cartUnits)

        val ambiguousSource = "3 packs of 2 x 500 ml milk sugar"
        val ambiguousItems = prepareSwiggyMcpItems(ambiguousSource) { null }
        val attempted = ambiguousItems.flatMap { item ->
            val offer = candidate(item.query, "2 x 500 ml")
            val assessed = assessSwiggyIntentQuantity(item, Recommendations(listOf(offer)))
            listOf(resolveSwiggyCartQuantity(assessed, offer))
        }
        assertFalse("multi-product nominal must not produce an actionable composite offer",
            attempted.any { it is SwiggyCartQuantityResolution.RetailProposal })
    }

    private fun assess(example: Example): com.example.beta.automation.ParsedItem {
        val candidate = candidate(example.identity, example.shape)
        return assessSwiggyIntentQuantity(itemWithoutAssessment(example), Recommendations(listOf(candidate)))
    }

    private fun itemWithoutAssessment(example: Example) = prepareSwiggyMcpItems(example.source) { null }.single()

    private fun assertHeld(
        item: com.example.beta.automation.ParsedItem,
        candidate: RecommendationCandidate,
        reason: String,
    ) {
        assertTrue("$reason must not become an Exact or RetailProposal",
            resolveSwiggyCartQuantity(item, candidate) is SwiggyCartQuantityResolution.Held)
        assertNull("$reason must not create a proposal binding", swiggyQuantityProposalBinding(0, item, candidate))
    }

    private fun candidate(
        identity: String,
        shape: String,
        typed: Boolean = true,
        quantityDescription: String? = null,
        sku: String = "sku-$identity",
        evidenceSku: String = sku,
        containerTitle: String? = null,
    ): RecommendationCandidate {
        val spin = "spin-$identity"
        val fields = if (!typed) null else CatalogueQuantityFields(
            packSize = shape,
            quantityDescription = quantityDescription,
        )
        return RecommendationCandidate(
            spinId = spin,
            skuId = sku,
            label = listOfNotNull(identity.replaceFirstChar(Char::uppercase), containerTitle, shape).joinToString(" "),
            catalogueQuantity = fields?.let {
                CatalogueQuantityEvidence(1, "search_variation", spin, evidenceSku, it)
            },
        )
    }
}
