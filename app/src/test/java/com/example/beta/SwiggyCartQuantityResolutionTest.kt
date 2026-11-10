package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.SwiggyMcpClient.Recommendations
import com.example.beta.automation.ParsedItem
import com.example.beta.automation.Quantity
import org.junit.Assert.*
import org.junit.Test

/** Safety contract only: a held result is NOT a usable-basket success. Existing
 * identity/provenance tests use this instead of assuming a bare count is a SKU count. */
internal fun swiggyCountResolutionDiagnostic(item: ParsedItem, candidate: RecommendationCandidate): String {
    val provenance = item.intentQuantityProvenance
    return "${candidate.label}: resolution=${resolveSwiggyCartQuantity(item, candidate)}, " +
        "requiresProof=${item.requiresIntentQuantityProof}, origin=${provenance?.origin}, " +
        "sourceCountRole=${provenance?.sourceCountRole}, catalogueQuantity=${candidate.catalogueQuantity != null}"
}

internal fun assertUnqualifiedCountNeedsReview(item: ParsedItem, candidate: RecommendationCandidate, expectedCount: Int) {
    val diagnostic = swiggyCountResolutionDiagnostic(item, candidate)
    assertEquals(diagnostic, Quantity.Count(expectedCount), item.quantity)
    assertFalse(diagnostic, isSwiggyCandidateCountCompatible(item, candidate))
    assertThrows(diagnostic, IllegalArgumentException::class.java) { swiggyRequestedCartQuantity(item, candidate) }
    when (val result = resolveSwiggyCartQuantity(item, candidate)) {
        is SwiggyCartQuantityResolution.Exact -> fail("Bare count must not become exact from catalogue contents: $diagnostic")
        is SwiggyCartQuantityResolution.Held ->
            assertThrows(IllegalStateException::class.java) { swiggyPreviewItemFromParsedItem(candidate, item) }
        is SwiggyCartQuantityResolution.RetailProposal -> {
            assertEquals(expectedCount, result.cartUnits)
            assertEquals(expectedCount, swiggyPreviewItemFromParsedItem(candidate, item).quantity)
            val binding = requireNotNull(swiggyQuantityProposalBinding(0, item, candidate))
            val gate = SwiggyQuantityProposalGate()
            gate.prepare(1, "synthetic-address", "synthetic-plan", listOf(binding))
            assertFalse(gate.canApply(1, "synthetic-address", "synthetic-plan", listOf(binding)))
            assertTrue(gate.acknowledge(1, "synthetic-address", "synthetic-plan", listOf(binding), true))
            assertTrue(gate.canApply(1, "synthetic-address", "synthetic-plan", listOf(binding)))
        }
    }
}

/** Positive usability contract: Held is not accepted as a successful proposal. */
internal fun assertReviewedRetailProposal(item: ParsedItem, candidate: RecommendationCandidate, expectedCount: Int) {
    val result = resolveSwiggyCartQuantity(item, candidate)
    assertTrue(swiggyCountResolutionDiagnostic(item, candidate), result is SwiggyCartQuantityResolution.RetailProposal)
    assertEquals(expectedCount, (result as SwiggyCartQuantityResolution.RetailProposal).cartUnits)
    assertTrue(isSwiggyCandidateAllowed(item, candidate))
    assertUnqualifiedCountNeedsReview(item, candidate, expectedCount)
    val binding = requireNotNull(swiggyQuantityProposalBinding(0, item, candidate))
    val gate = SwiggyQuantityProposalGate()
    for ((changedAddress, changedPlan) in listOf("other-address" to "plan", "address" to "other-plan")) {
        gate.prepare(1, "address", "plan", listOf(binding))
        assertFalse(gate.canApply(1, "address", "plan", listOf(binding)))
        assertTrue(gate.acknowledge(1, "address", "plan", listOf(binding), true))
        assertTrue(gate.canApply(1, "address", "plan", listOf(binding)))
        assertFalse(gate.canApply(1, changedAddress, changedPlan, listOf(binding)))
    }
}

class SwiggyCartQuantityResolutionTest {
    private fun singlePieceCandidate(name: String = "Toothbrush", amount: String = "1 piece") =
        RecommendationCandidate("piece-product", name, skuId = "piece-sku", catalogueQuantity =
            CatalogueQuantityEvidence(1, "search_variation", "piece-product", "piece-sku",
                CatalogueQuantityFields(quantityDescription = amount)))

    @Test fun provenOnePieceOffersConserveEverySupportedCountButStillRequireReview() {
        val candidate = singlePieceCandidate()
        for (count in 1..20) {
            val item = ParsedItem("$count toothbrush", "toothbrush", Quantity.Count(count))
            assertReviewedRetailProposal(item, candidate, count)
            val proposal = resolveSwiggyCartQuantity(item, candidate) as SwiggyCartQuantityResolution.RetailProposal
            val contents = proposal.contents as SwiggyRetailOfferContents.Proven
            assertEquals(GroceryPackDimension.PIECES, contents.measure.dimension)
            assertEquals(0, contents.measure.baseValue.compareTo(java.math.BigDecimal.ONE))
        }
    }

    @Test fun onePieceOfferRequiresTypedSingleDimensionEvidenceWithoutBundles() {
        val item = ParsedItem("2 toothbrush", "toothbrush", Quantity.Count(2))
        val valid = singlePieceCandidate()
        val unsafe = listOf(
            singlePieceCandidate(amount = "2 pieces"),
            singlePieceCandidate(amount = "6 pieces"),
            valid.copy(catalogueQuantity = null, label = "Toothbrush 1 piece"),
            valid.copy(label = "Toothbrush Pack of 3"),
            valid.copy(label = "Toothbrush 3N"),
            valid.copy(variant = "200 g"),
            valid.copy(catalogueQuantityInvalid = true, variant = "200 g"),
            valid.copy(skuId = "changed-sku"),
        )
        unsafe.forEachIndexed { index, candidate ->
            assertTrue("unsafe evidence $index", resolveSwiggyCartQuantity(item, candidate) is SwiggyCartQuantityResolution.Held)
        }
        assertTrue(resolveSwiggyCartQuantity(ParsedItem("2 eggs", "eggs", Quantity.Count(2)),
            singlePieceCandidate("Eggs", "6 pieces")) is SwiggyCartQuantityResolution.Held)
    }

    @Test fun onePieceOfferNeverOverridesSourceRoleProductIdentityOrRange() {
        val candidate = singlePieceCandidate()
        val item = ParsedItem("2 toothbrush", "toothbrush", Quantity.Count(2))
        for (count in listOf(-1, 0, 21)) {
            assertTrue(resolveSwiggyCartQuantity(item.copy(rawText = "$count toothbrush", quantity = Quantity.Count(count)),
                candidate) is SwiggyCartQuantityResolution.Held)
        }
        assertTrue(resolveSwiggyCartQuantity(item, singlePieceCandidate("Razor")) is SwiggyCartQuantityResolution.Held)
        assertTrue(resolveSwiggyCartQuantity(item.copy(retailPackCount = true), candidate) is SwiggyCartQuantityResolution.Held)
    }

    @Test fun onePieceOfferAcknowledgementCannotSurviveChangedCatalogueEvidence() {
        val item = ParsedItem("2 toothbrush", "toothbrush", Quantity.Count(2))
        val candidate = singlePieceCandidate()
        val binding = requireNotNull(swiggyQuantityProposalBinding(0, item, candidate))
        val gate = SwiggyQuantityProposalGate()
        gate.prepare(1, "address", "plan", listOf(binding))
        assertTrue(gate.acknowledge(1, "address", "plan", listOf(binding), true))
        assertFalse(gate.canApply(1, "address", "plan", listOf(requireNotNull(
            swiggyQuantityProposalBinding(0, item, singlePieceCandidate(amount = "1 pc"))))))
        assertNull(swiggyQuantityProposalBinding(0, item, singlePieceCandidate(amount = "6 pieces")))
    }

    @Test fun bareCountsOfferKnownRetailPacksButCannotEnterExactConversion() {
        for ((name, size) in listOf("carrot" to "500 g", "milk" to "500 ml", "soap" to "100 g")) {
            val item = ParsedItem("2 $name", name, Quantity.Count(2))
            val candidate = RecommendationCandidate("$name-sku", "$name $size", skuId = "current")
            val proposal = resolveSwiggyCartQuantity(item, candidate) as SwiggyCartQuantityResolution.RetailProposal
            assertEquals(2, proposal.cartUnits)
            assertEquals(size, proposal.packSize)
            assertFalse(isSwiggyCandidateCountCompatible(item, candidate))
            assertThrows(IllegalArgumentException::class.java) { swiggyRequestedCartQuantity(item, candidate) }
            assertEquals(2, swiggyPreviewItemFromParsedItem(candidate, item).quantity)
            val row = swiggyQuantityProposalReviewRow(item, candidate, proposal, "0 → 2 packs")
            assertEquals("Suggested pack amount", row.badge)
            assertTrue(row.detail!!.contains("You asked for 2."))
            assertFalse(row.detail!!.contains("total"))
            assertFalse(row.detail!!.contains("about"))
        }
    }

    @Test fun genericRetailRequestsNeedReviewWhilePhysicalRequestsKeepExactArithmetic() {
        val source = "2 packets of milk"
        val prepared = GroceryIntentDraft("grocery-intent-v1", true, source, listOf(
            GroceryIntentItem("milk", 2.toBigDecimal(), "pack", null, "unspecified",
                emptyList(), emptyList(), source, false),
        ), false).prepareForReview().items.single()
        val sku = RecommendationCandidate("milk-sku", "Milk Packet 500 ml", skuId = "milk-packet")
        val pack = assessSwiggyIntentQuantity(prepared, Recommendations(listOf(sku)))
        assertNull(pack.validNamedRetailReceipt())
        assertReviewedRetailProposal(pack, sku, 2)
        val bundle = sku.copy(label = "Milk Packet 500 ml Twin Pack", skuId = "milk-twin")
        val checkedBundle = assessSwiggyIntentQuantity(prepared, Recommendations(listOf(bundle)))
        assertTrue(resolveSwiggyCartQuantity(checkedBundle, bundle) is SwiggyCartQuantityResolution.Held)
        assertThrows(IllegalArgumentException::class.java) { swiggyRequestedCartQuantity(checkedBundle, bundle) }
        val litre = ParsedItem("1 litre milk", "milk", Quantity.Volume(1000))
        assertEquals(swiggyCountResolutionDiagnostic(litre, sku), SwiggyCartQuantityResolution.Exact(2),
            resolveSwiggyCartQuantity(litre, sku))
        val indivisible = ParsedItem("750 ml milk", "milk", Quantity.Volume(750))
        assertTrue(resolveSwiggyCartQuantity(indivisible, sku) is SwiggyCartQuantityResolution.Held)
    }

    @Test fun editableRetailFlagCannotReplaceTheSourceReceiptOrConsent() {
        val sku = RecommendationCandidate("milk-sku", "Milk 500 ml", skuId = "milk-single")
        val bare = ParsedItem("2 milk", "milk", Quantity.Count(2))
        assertReviewedRetailProposal(bare, sku, 2)
        val flagOnly = ParsedItem("2 packets milk", "milk", Quantity.Count(2), retailPackCount = true)
        assertTrue(resolveSwiggyCartQuantity(flagOnly, sku) is SwiggyCartQuantityResolution.Held)
        assertThrows(IllegalArgumentException::class.java) { swiggyRequestedCartQuantity(flagOnly, sku) }
    }

    @Test fun nonAffirmativeCatalogueMeasuresCannotAuthorizeProposalsOrExactPhysicalTotals() {
        val bare = ParsedItem("2 milk", "milk", Quantity.Count(2))
        val total = ParsedItem("1 litre milk", "milk", Quantity.Volume(1000))
        for (text in listOf("not 500 ml", "no 500 ml", "no 1 litre", "without 500 grams", "without 1 ltr", "500 ml if available", "500 ml or 1 litre",
            "approximately 500 ml", "up to 500 ml", "-500 ml", "1/500 ml", "1,500 ml")) {
            for (sku in listOf(
                RecommendationCandidate("milk", "Milk $text"),
                RecommendationCandidate("milk", "Milk", variant = text),
                RecommendationCandidate("milk", "Milk 500 ml", subtitle = text),
            )) {
                assertTrue("Bare count must be held: $text", resolveSwiggyCartQuantity(bare, sku) is SwiggyCartQuantityResolution.Held)
                assertTrue("Physical total must be held: $text", resolveSwiggyCartQuantity(total, sku) is SwiggyCartQuantityResolution.Held)
            }
        }
    }

    @Test fun missingConflictingOrNestedPacksCannotBecomeProposals() {
        val item = ParsedItem("1 carrot", "carrot", Quantity.Count(1))
        for (label in listOf("carrot", "carrot 500 g 1 kg", "carrot 2 x 500 g", "carrot 500 g + 100 g",
            "carrot -500 g", "carrot − 500 g", "carrot 1/2 kg", "carrot 500-500 g", "carrot +500 g",
            "carrot 1,500 g", "carrot ~500 g", "carrot approx 500 g", "carrot upto 1 kg",
            "carrot 500 g ± 10%", "carrot 450-500 g")) {
            val sku = RecommendationCandidate("sku", label)
            assertTrue(label, resolveSwiggyCartQuantity(item, sku) is SwiggyCartQuantityResolution.Held)
            assertThrows(IllegalStateException::class.java) { swiggyPreviewItemFromParsedItem(sku, item) }
        }
        val ambiguousMilk = ParsedItem("2 milk", "milk", Quantity.Count(2))
        assertTrue(resolveSwiggyCartQuantity(ambiguousMilk,
            RecommendationCandidate("milk", "milk 2 x 500 ml")) is SwiggyCartQuantityResolution.Held)
    }

    @Test fun proposalNeverWaivesPieceIdentityOrDietRequirements() {
        val sku = RecommendationCandidate("milk-sku", "milk 500 ml")
        val pieces = ParsedItem("2 pieces milk", "milk", Quantity.Count(2), logicalPieceCount = true)
        assertTrue(resolveSwiggyCartQuantity(pieces, sku) is SwiggyCartQuantityResolution.Held)
        val wrongIdentity = ParsedItem("2 carrots", "carrots", Quantity.Count(2))
        assertTrue(resolveSwiggyCartQuantity(wrongIdentity, sku) is SwiggyCartQuantityResolution.Held)
        val sugarFree = ParsedItem("2 sugar free tea", "sugar free tea", Quantity.Count(2), strictMatchPhrase = "sugar free tea")
        assertTrue(resolveSwiggyCartQuantity(sugarFree, RecommendationCandidate("tea", "sweetened tea 200 ml")) is SwiggyCartQuantityResolution.Held)
    }

    @Test fun offerBindingChangesWithSourceSkuAmountAndCatalogueFacts() {
        val item = ParsedItem("2 milk", "milk", Quantity.Count(2))
        val sku = RecommendationCandidate("milk-sku", "milk 500 ml", skuId = "current")
        val original = swiggyQuantityProposalBinding(0, item, sku)
        assertNotNull(original)
        assertNotEquals(original, swiggyQuantityProposalBinding(0, item.copy(rawText = "two milk"), sku))
        assertNotEquals(original, swiggyQuantityProposalBinding(0, item.copy(quantity = Quantity.Count(3)), sku))
        assertNotEquals(original, swiggyQuantityProposalBinding(0, item, sku.copy(skuId = "other")))
        assertNotEquals(original, swiggyQuantityProposalBinding(0, item, sku.copy(label = "milk 1 litre")))
        assertFalse(original.toString().contains("milk"))
    }

    @Test fun reviewPreservesSourceOrderIncludingUnchangedAndConsolidatedRequests() {
        val items = listOf(
            ParsedItem("1 milk", "milk", Quantity.Count(1)),
            ParsedItem("1 packet sugar", "sugar", Quantity.Count(1), retailPackCount = true),
            ParsedItem("2 milk", "milk", Quantity.Count(2)),
            ParsedItem("1 carrot", "carrot", Quantity.Count(1)),
        )
        val milk = RecommendationCandidate("milk", "milk 500 ml")
        val candidates = mapOf(3 to RecommendationCandidate("carrot", "carrot 500 g"),
            2 to milk, 0 to milk, 1 to RecommendationCandidate("sugar", "sugar 1 kg"))
        val resolutionContext = items.mapIndexed { index, item ->
            val candidate = candidates.getValue(index)
            swiggyCountResolutionDiagnostic(assessSwiggyIntentQuantity(item,
                SwiggyMcpClient.Recommendations(listOf(candidate))), candidate)
        }.joinToString("; ")
        val changes = listOf(SwiggyMcpClient.CartPlanChange("milk", "add", "milk", 0, 3))
        val clicked = mutableListOf<Int>()
        val rows = swiggyOrderedCartReviewRows(items, candidates, emptySet(), changes) { clicked += it }
        assertEquals(resolutionContext, 4, rows.size)
        assertTrue(rows[0].title.contains("1 milk"))
        assertEquals("1 packet sugar", rows[1].title)
        assertEquals("Not verified", rows[1].badge)
        assertFalse(rows[1].detail.orEmpty().contains("→"))
        assertFalse(rows[1].detail.orEmpty().contains("No quantity change shown"))
        assertTrue(rows[2].title.contains("2 milk"))
        assertTrue(rows[3].title.contains("1 carrot"))
        assertTrue(rows[0].detail!!.contains("Combined cart line: 0 → 3 packs"))
        assertTrue(rows[3].detail!!.contains("No quantity change shown"))
        assertFalse(rows[3].detail!!.contains("already"))
        rows[3].action!!.onClick()
        assertEquals(listOf(3), clicked)
    }
}
