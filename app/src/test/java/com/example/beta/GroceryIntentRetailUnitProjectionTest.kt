package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.SwiggyMcpClient.Recommendations
import com.example.beta.automation.IntentCartQuantityMode
import com.example.beta.automation.IntentLocalAmount
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Diagnostic parity coverage for source-bound Context quantity-unit projection. */
class GroceryIntentRetailUnitProjectionTest {
    private data class Fixture(
        val id: String,
        val source: String,
        val name: String,
        val quantity: Int,
        val sourceQuantityUnit: String,
        val packValue: String? = null,
        val packUnit: String = "unspecified",
        val candidateLabel: String,
        val mustRemainHeld: Boolean = false,
    )

    private data class Outcome(
        val prepared: Boolean,
        val previewQuantity: Int?,
        val logicalPieceCount: Boolean?,
        val resolutionClass: String,
    )

    private data class PreviewOutcome(
        val preview: SwiggyMcpClient.RequestedItem?,
        val resolutionClass: String,
    )

    @Test
    fun sourceBoundRetailCountsAreComparedAcrossModelUnitsAndCatalogueResolution() {
        val fixtures = listOf(
            Fixture("chocolate-size-first", "2 100 g chocolate bars", "chocolate bars", 2, "pack",
                packValue = "100", packUnit = "g",
                candidateLabel = "Chocolate Bars 100 g"),
            Fixture("chocolate-container-first", "2 bars of 100 g chocolate", "chocolate", 2, "pack",
                packValue = "100", packUnit = "g",
                candidateLabel = "Chocolate Bar 100 g"),
            Fixture("soap-size-first", "2 100 g soap bars", "soap bars", 2, "pack",
                packValue = "100", packUnit = "g",
                candidateLabel = "Soap Bars 100 g"),
            Fixture("soap-container-first", "2 bars of 100 g soap", "soap", 2, "pack",
                packValue = "100", packUnit = "g",
                candidateLabel = "Soap Bar 100 g"),
            Fixture("cheese-size-first", "2 200 g cheese blocks", "cheese blocks", 2, "pack",
                packValue = "200", packUnit = "g",
                candidateLabel = "Cheese Blocks 200 g"),
            Fixture("cheese-container-first", "2 blocks of 200 g cheese", "cheese", 2, "pack",
                packValue = "200", packUnit = "g",
                candidateLabel = "Cheese Block 200 g"),
            Fixture("spice-size-first", "2 50 g spice packets", "spice", 2, "pack",
                packValue = "50", packUnit = "g",
                candidateLabel = "Spice Packet 50 g"),
            Fixture("spice-container-first", "2 packets of 50 g spice", "spice", 2, "pack",
                packValue = "50", packUnit = "g",
                candidateLabel = "Spice Packet 50 g"),
            Fixture("bottles-milk", "2 bottles of milk", "milk", 2, "pack",
                candidateLabel = "Milk Bottle 500 ml"),
            Fixture("packets-noodles", "2 packets noodles", "noodles", 2, "pack",
                candidateLabel = "Noodles Packet"),
            Fixture("noodles-packets", "2 noodles packets", "noodles", 2, "pack",
                candidateLabel = "Noodles Packet"),
            Fixture("samosa-pieces", "6 pieces samosa", "samosa", 6, "piece",
                candidateLabel = "Samosa"),
            Fixture("eggs", "6 eggs", "eggs", 6, "piece", candidateLabel = "Eggs"),
            Fixture("single-samosa", "one samosa", "samosa", 1, "piece",
                candidateLabel = "Samosa"),
            Fixture("chocolate-single-physical-size", "one 100 g chocolate piece", "chocolate", 1, "piece",
                packValue = "100", packUnit = "g", candidateLabel = "Chocolate 100 g",
                mustRemainHeld = true),
            Fixture("egg-packet-inner-pieces", "2 packets of 6 pieces eggs", "eggs", 2, "pack",
                packValue = "6", packUnit = "piece",
                candidateLabel = "Eggs Packet of 6 Pieces"),
        )

        val outcomes = mutableMapOf<Pair<String, String>, Outcome>()
        val statusCodes = mutableListOf<String>()
        var preparedRows = 0
        var resolvedRows = 0
        var heldAsRequired = 0
        val explicitSizePreviewed = mutableListOf<String>()
        val wrongSizePreviewed = mutableListOf<String>()
        val parityFailures = mutableListOf<String>()

        fixtures.forEach { fixture ->
            val variants = listOf(fixture.sourceQuantityUnit, "count").distinct()
            variants.forEach { unit ->
                val response = GroceryIntentContextResponse.parse(
                    responseBody(fixture, unit), fixture.source,
                )
                when (val assessment = response.assessForReview()) {
                    is GroceryIntentContextAssessment.Held -> {
                        val codes = assessment.blockingIssues.map { it.kind.name }.toSet()
                        statusCodes += "${fixture.id}:$unit:held:${codes.sorted().joinToString("+")}"
                        outcomes[fixture.id to unit] = Outcome(false, null, null, "not_evaluated")
                        if (fixture.mustRemainHeld) {
                            assertTrue("${fixture.id} must retain ITEM_NUMERIC", "ITEM_NUMERIC" in codes)
                            heldAsRequired++
                        }
                    }
                    is GroceryIntentContextAssessment.Prepared -> {
                        val preparation = assessment.preparation
                        preparedRows++
                        assertEquals(fixture.source, preparation.instruction)
                        assertEquals(fixture.source, preparation.itemSources.single().text)
                        val item = preparation.legacyPreparation.items.single()
                        assertEquals(fixture.source, item.rawText)
                        assertSourceEvidenceIsOriginal(fixture.source, item)
                        if (item.requiresIntentQuantityProof && item.intentQuantityProvenance == null) {
                            val pending = preparation.pendingCounts[0]
                            if (pending != null) {
                                assertTrue("${fixture.id}: pending witness must bind the original source",
                                    pending.matches(fixture.source, 0, item))
                            } else {
                                assertTrue("${fixture.id}: absent proof must remain explicitly held",
                                    preparation.blockingIssues.any { it.kind == GroceryIntentContextIssueKind.ITEM_QUANTITY })
                            }
                        }
                        statusCodes += "${fixture.id}:$unit:prepared:${preparation.blockingIssues.map { it.kind.name }.distinct().sorted().joinToString("+")}:" +
                            "retail=${item.retailPackCount},logicalPiece=${item.logicalPieceCount}," +
                            "sourceRole=${item.intentQuantityProvenance?.sourceCountRole ?: "none"}," +
                            "proof=${item.intentQuantityProvenance != null}"

                        val candidate = candidate(fixture, unit)
                        val wrongSize = if (fixture.packValue != null && fixture.packUnit in setOf("piece", "g", "kg", "ml", "l"))
                            candidate(fixture, unit, wrongPhysicalSize = true) else null
                        val candidates = listOfNotNull(candidate, wrongSize)
                        val catalogue = Recommendations(
                            candidates = candidates,
                            suggested = candidate,
                            query = swiggyRecommendationQuery(item),
                        )
                        val basket = GroceryIntentContextBasket.create(preparation, listOf(catalogue))
                        val resolution = basket?.resolution(0, candidate)
                        val resolutionClass = when (resolution) {
                            is SwiggyCartQuantityResolution.Exact -> "Exact"
                            is SwiggyCartQuantityResolution.RetailProposal -> "RetailProposal"
                            is SwiggyCartQuantityResolution.Held -> "Held:${resolution.reason}"
                            null -> "BasketBlocked"
                        }
                        val preview = basket?.preview(0, candidate)
                        if (preview != null) {
                            resolvedRows++
                            assertEquals("${fixture.id}: resolved cart units", fixture.quantity, preview.quantity)
                        }
                        wrongSize?.let { wrong ->
                            if (basket?.preview(0, wrong) != null) wrongSizePreviewed += "${fixture.id}:$unit"
                        }
                        if (fixture.mustRemainHeld) {
                            assertTrue("${fixture.id} must retain an ITEM_NUMERIC blocker",
                                preparation.blockingIssues.any { it.kind == GroceryIntentContextIssueKind.ITEM_NUMERIC })
                            if (preview == null) heldAsRequired++ else explicitSizePreviewed += "${fixture.id}:$unit"
                        }
                        statusCodes[statusCodes.lastIndex] +=
                            ":preview=${preview?.quantity ?: "none"},resolution=$resolutionClass"
                        outcomes[fixture.id to unit] = Outcome(
                            true, preview?.quantity, item.logicalPieceCount, resolutionClass,
                        )
                    }
                }
            }

            val original = outcomes.getValue(fixture.id to fixture.sourceQuantityUnit)
            val count = outcomes.getValue(fixture.id to "count")
            if (original.prepared && !count.prepared) {
                parityFailures += "${fixture.id}:original_prepared_count_held"
            }
            if (original.prepared && count.prepared) {
                if (original.logicalPieceCount != count.logicalPieceCount)
                    parityFailures += "${fixture.id}:logicalPieceCount"
            }
            if (original.previewQuantity != null && count.previewQuantity == null) {
                parityFailures += "${fixture.id}:original_previewed_count_held"
            }
            if (original.previewQuantity != null && count.previewQuantity != null) {
                if (original.previewQuantity != count.previewQuantity)
                    parityFailures += "${fixture.id}:cartQuantity"
            }
        }

        println("retail_unit_projection: prepared=$preparedRows resolved=$resolvedRows required_holds=$heldAsRequired " +
            "explicit_size_previewed=${explicitSizePreviewed.joinToString(",")}," +
            "wrong_size_previewed=${wrongSizePreviewed.joinToString(",")}," +
            "parity_failures=${parityFailures.joinToString(",")}," +
            "outcomes=${statusCodes.joinToString(";")}")
        assertTrue("at least one pair must reach Context preparation", preparedRows > 0)
        assertTrue("at least one pair must reach a resolved cart preview", resolvedRows > 0)
        assertTrue("the explicit one 100 g chocolate piece must remain held", heldAsRequired >= 2)
        assertTrue("wrong-size candidate must never preview: ${wrongSizePreviewed.joinToString()}",
            wrongSizePreviewed.isEmpty())
        assertTrue("explicit physical-piece size previewed for ${explicitSizePreviewed.joinToString()}",
            explicitSizePreviewed.isEmpty())
        assertTrue("unit projection differences: ${parityFailures.joinToString()}", parityFailures.isEmpty())
    }

    @Test
    fun sourcePieceAndOuterPackTotalsResolveAgainstOneOrMultipieceOffers() {
        val counterfactuals = mutableListOf<String>()
        val samosa = Fixture(
            id = "samosa-six-loose-pieces", source = "6 pieces samosa", name = "samosa",
            quantity = 6, sourceQuantityUnit = "piece", candidateLabel = "Samosa",
        )
        val samosaLogicalFlags = mutableListOf<Boolean>()
        for (unit in listOf("piece", "count")) {
            val samosaPreparation = prepared(samosa, unit)
            val samosaItem = samosaPreparation.legacyPreparation.items.single()
            samosaLogicalFlags += samosaItem.logicalPieceCount
            assertTrue("$unit source pieces retain their logical-piece role", samosaItem.logicalPieceCount)
            val onePiece = withCatalogueQuantity(
                RecommendationCandidate("samosa-single-$unit", "Samosa 1 Piece", skuId = "samosa-single-$unit"), "1 piece",
            )
            val threePieces = withCataloguePackSize(
                RecommendationCandidate("samosa-three-pack-$unit", "Samosa Pack of 3 Pieces", skuId = "samosa-three-pack-$unit"),
                "3 pieces",
            )
            val oneOutcome = previewOutcome(samosaPreparation, samosaItem, onePiece)
            val threeOutcome = previewOutcome(samosaPreparation, samosaItem, threePieces)
            assertEquals("$unit source six-piece total with one-piece offer", 6, oneOutcome.preview?.quantity)
            assertEquals("$unit source six-piece total with three-piece offer", 2, threeOutcome.preview?.quantity)
            counterfactuals += "samosa-six/$unit:one-piece=${oneOutcome.preview?.quantity ?: "held"}/${oneOutcome.resolutionClass}"
            counterfactuals += "samosa-six/$unit:three-piece=${threeOutcome.preview?.quantity ?: "held"}/${threeOutcome.resolutionClass}"
        }
        assertEquals("piece/count projection preserves source-derived role", samosaLogicalFlags.first(), samosaLogicalFlags.last())

        val eggs = Fixture(
            id = "eggs-two-outer-packs", source = "2 packets of 6 pieces eggs", name = "eggs",
            quantity = 2, sourceQuantityUnit = "pack", packValue = "6", packUnit = "piece",
            candidateLabel = "Eggs Packet of 6 Pieces",
        )
        for (unit in listOf("pack", "count")) {
            val eggPreparation = prepared(eggs, unit)
            val eggItem = eggPreparation.legacyPreparation.items.single()
            val exactSixPack = withCataloguePackSize(
                RecommendationCandidate("eggs-packet-six-$unit", "Eggs Packet of 6 Pieces", skuId = "eggs-packet-six-$unit"),
                "6 pieces",
            )
            val shortThreePack = withCataloguePackSize(
                RecommendationCandidate("eggs-packet-three-$unit", "Eggs Packet of 3 Pieces", skuId = "eggs-packet-three-$unit"),
                "3 pieces",
            )
            val looseEgg = withCatalogueQuantity(
                RecommendationCandidate("eggs-single-$unit", "Eggs 1 Piece", skuId = "eggs-single-$unit"), "1 piece",
            )
            val exactOutcome = previewOutcome(eggPreparation, eggItem, exactSixPack)
            val shortOutcome = previewOutcome(eggPreparation, eggItem, shortThreePack)
            val looseOutcome = previewOutcome(eggPreparation, eggItem, looseEgg)
            assertEquals("$unit source outer packet count remains two", 2, exactOutcome.preview?.quantity)
            assertNull("$unit source undersized offer cannot satisfy a six-piece packet", shortOutcome.preview)
            assertNull("$unit source loose singles cannot replace the packet request", looseOutcome.preview)
            counterfactuals += "eggs-two-six/$unit:6-piece-offer=${exactOutcome.preview?.quantity ?: "held"}/${exactOutcome.resolutionClass}"
            counterfactuals += "eggs-two-six/$unit:3-piece-offer=${shortOutcome.preview?.quantity ?: "held"}/${shortOutcome.resolutionClass}"
            counterfactuals += "eggs-two-six/$unit:single-offer=${looseOutcome.preview?.quantity ?: "held"}/${looseOutcome.resolutionClass}"
        }
        println("retail_offer_counterfactuals:${counterfactuals.joinToString(";")}")
    }

    @Test
    fun legacyPhysicalSizeWithoutPurchaseCountRemainsAnImplicitDefaultOnly() {
        val source = "100 g chocolate piece"
        val absentCount = legacyQuantityItem(source, "chocolate", "unspecified", null)
        assertNull("no-count source retains its independently supplied physical size", absentCount.legacyNumericOwnershipIssue())
        assertEquals(false, absentCount.legacyImplicitUnitDefault())

        for (unit in listOf("count", "piece")) {
            val conventionalOne = legacyQuantityItem(source, "chocolate", unit, 1)
            assertNull("$unit one is not rejected by numeric ownership", conventionalOne.legacyNumericOwnershipIssue())
            assertEquals("$unit one remains an implicit default, not explicit source proof",
                true, conventionalOne.legacyImplicitUnitDefault())
        }
    }

    @Test
    fun mixedBasketPreservesPerRowOfferRolesAndCartUnitVector() {
        val instruction = "6 pieces samosa; 2 packets of 6 pieces eggs; 1 litre milk; 2 litres milk"
        val rows = listOf(
            Fixture("mixed-samosa", "6 pieces samosa", "samosa", 6, "piece", candidateLabel = "Samosa Pack of 3 Pieces"),
            Fixture("mixed-eggs", "2 packets of 6 pieces eggs", "eggs", 2, "pack",
                packValue = "6", packUnit = "piece", candidateLabel = "Eggs Packet of 6 Pieces"),
            Fixture("mixed-milk-one-litre", "1 litre milk", "milk", 1, "l", candidateLabel = "Milk 500 ml"),
            Fixture("mixed-milk-two-litres", "2 litres milk", "milk", 2, "l", candidateLabel = "Milk 500 ml"),
        )
        val response = GroceryIntentContextResponse.parse(mixedBasketResponse(instruction, rows), instruction)
        val preparation = response.prepareForReview()
        assertTrue("all source rows must pass independent preparation checks: ${preparation.blockingIssues}",
            preparation.blockingIssues.isEmpty())
        assertEquals(rows.map { it.source }, preparation.itemSources.map { it.text })

        val samosaOffer = withCataloguePackSize(
            RecommendationCandidate("samosa-three-pack", "Samosa Pack of 3 Pieces", skuId = "sku-samosa-3"),
            "3 pieces",
        )
        val eggsOffer = withCataloguePackSize(
            RecommendationCandidate("eggs-six-pack", "Eggs Packet of 6 Pieces", skuId = "sku-eggs-6"),
            "6 pieces",
        )
        val milkOffer = withCataloguePackSize(
            RecommendationCandidate("milk-half-litre", "Milk 500 ml", skuId = "sku-milk-500"),
            "500 ml",
        )
        val selected = mapOf(0 to samosaOffer, 1 to eggsOffer, 2 to milkOffer, 3 to milkOffer)
        val catalogues = preparation.legacyPreparation.items.mapIndexed { index, item ->
            Recommendations(
                candidates = listOf(selected.getValue(index)),
                suggested = selected.getValue(index),
                query = swiggyRecommendationQuery(item),
            )
        }
        val basket = requireNotNull(GroceryIntentContextBasket.create(preparation, catalogues))

        assertEquals(SwiggyCartQuantityResolution.Exact(2), basket.resolution(0, samosaOffer))
        val eggResolution = basket.resolution(1, eggsOffer)
        assertTrue("outer egg packets must remain a reviewable retail proposal: $eggResolution",
            eggResolution is SwiggyCartQuantityResolution.RetailProposal)
        assertEquals(2, (eggResolution as SwiggyCartQuantityResolution.RetailProposal).cartUnits)
        assertEquals(SwiggyCartQuantityResolution.Exact(2), basket.resolution(2, milkOffer))
        assertEquals(SwiggyCartQuantityResolution.Exact(4), basket.resolution(3, milkOffer))
        assertTrue("six requested samosa remain source-derived loose pieces", basket.items[0].logicalPieceCount)
        assertFalse("outer egg packet count is not an inner piece count", basket.items[1].logicalPieceCount)

        val request = requireNotNull(SwiggyCartReviewRequest.buildContext(basket, selected, emptySet()))
        val expectedVector = listOf(
            SwiggyMcpClient.RequestedItem("samosa-three-pack", 2, "Samosa Pack of 3 Pieces", "sku-samosa-3", IntentCartQuantityMode.TARGET),
            SwiggyMcpClient.RequestedItem("eggs-six-pack", 2, "Eggs Packet of 6 Pieces", "sku-eggs-6", IntentCartQuantityMode.TARGET),
            SwiggyMcpClient.RequestedItem("milk-half-litre", 6, "Milk 500 ml", "sku-milk-500", IntentCartQuantityMode.TARGET),
        )
        assertEquals(expectedVector, request.requestedItems)
        assertEquals(rows.map { it.source }, request.sourceItems.map { it.rawText })
        assertEquals("only the egg pack amount requires a quantity proposal acknowledgement", listOf(1),
            request.quantityProposals.map { it.rowIndex })
        val eggProposal = request.quantityProposals.single()
        assertEquals("sku-eggs-6", eggProposal.skuId)
        assertEquals(2, eggProposal.quantity)
        assertEquals("retail-offer-v2", eggProposal.evidence.resolverVersion)
        assertEquals("2 packets of 6 pieces eggs", eggProposal.source)
        assertEquals("2 packets of 6 pieces eggs", eggProposal.evidence.sourceItem.rawText)
        assertEquals(eggsOffer, eggProposal.evidence.candidate)
        assertTrue(eggProposal.evidence.contents is SwiggyRetailOfferContents.Proven)
        assertEquals(GroceryPackMeasure.of(BigDecimal("6"), "pieces"),
            (eggProposal.evidence.contents as SwiggyRetailOfferContents.Proven).measure)

        val countRows = rows.mapIndexed { index, row ->
            if (index < 2) row.copy(sourceQuantityUnit = "count") else row
        }
        val countPreparation = GroceryIntentContextResponse.parse(
            mixedBasketResponse(instruction, countRows), instruction,
        ).prepareForReview()
        assertTrue("count-projected source must preserve the whole-basket preparation: ${countPreparation.blockingIssues}",
            countPreparation.blockingIssues.isEmpty())
        val countCatalogues = countPreparation.legacyPreparation.items.mapIndexed { index, item ->
            Recommendations(
                candidates = listOf(selected.getValue(index)),
                suggested = selected.getValue(index),
                query = swiggyRecommendationQuery(item),
            )
        }
        val countBasket = requireNotNull(GroceryIntentContextBasket.create(countPreparation, countCatalogues))
        assertEquals(SwiggyCartQuantityResolution.Exact(2), countBasket.resolution(0, samosaOffer))
        assertTrue(countBasket.resolution(1, eggsOffer) is SwiggyCartQuantityResolution.RetailProposal)
        assertEquals(SwiggyCartQuantityResolution.Exact(2), countBasket.resolution(2, milkOffer))
        assertEquals(SwiggyCartQuantityResolution.Exact(4), countBasket.resolution(3, milkOffer))
        assertTrue("source words continue to own the loose-piece role", countBasket.items[0].logicalPieceCount)
        assertFalse("the outer egg pack remains distinct from inner pieces", countBasket.items[1].logicalPieceCount)
        val countRequest = requireNotNull(SwiggyCartReviewRequest.buildContext(countBasket, selected, emptySet()))
        assertEquals("count projection preserves the exact selected SKU/cart-unit/mode vector",
            expectedVector, countRequest.requestedItems)
        assertEquals(rows.map { it.source }, countRequest.sourceItems.map { it.rawText })
        assertEquals("count projection preserves the review-proposal row and quantity",
            request.quantityProposals.map { Triple(it.rowIndex, it.skuId, it.quantity) },
            countRequest.quantityProposals.map { Triple(it.rowIndex, it.skuId, it.quantity) })
        val countProposal = countRequest.quantityProposals.single()
        assertEquals("2 packets of 6 pieces eggs", countProposal.source)
        assertEquals("2 packets of 6 pieces eggs", countProposal.evidence.sourceItem.rawText)
        assertEquals(eggsOffer, countProposal.evidence.candidate)
        assertEquals(eggProposal.evidence.contents, countProposal.evidence.contents)

        val wrongEggOffer = withCataloguePackSize(
            RecommendationCandidate("eggs-three-pack", "Eggs Packet of 3 Pieces", skuId = "sku-eggs-3"),
            "3 pieces",
        )
        val wrongShapeCatalogues = catalogues.toMutableList().also {
            it[1] = Recommendations(
                candidates = listOf(wrongEggOffer),
                suggested = wrongEggOffer,
                query = catalogues[1].query,
            )
        }
        val wrongShapeBasket = requireNotNull(GroceryIntentContextBasket.create(preparation, wrongShapeCatalogues))
        val wrongShapeSelection = selected.toMutableMap().also { it[1] = wrongEggOffer }
        assertTrue("wrong inner shape is held", wrongShapeBasket.resolution(1, wrongEggOffer) is SwiggyCartQuantityResolution.Held)
        assertNull("one wrong offer blocks the entire mixed request",
            SwiggyCartReviewRequest.buildContext(wrongShapeBasket, wrongShapeSelection, emptySet()))

        assertNull("a missing row candidate blocks the whole request",
            SwiggyCartReviewRequest.buildContext(basket, selected - 3, emptySet()))
        val unlistedEggSku = withCataloguePackSize(
            eggsOffer.copy(skuId = "sku-eggs-stale", catalogueQuantity = null), "6 pieces",
        )
        assertEquals(SwiggyCartQuantityResolution.Held("context_candidate_not_current"),
            basket.resolution(1, unlistedEggSku))
        assertNull("a stale SKU is not selected from this basket revision",
            SwiggyCartReviewRequest.buildContext(basket, selected + (1 to unlistedEggSku), emptySet()))

        val alternateMilkSku = withCataloguePackSize(
            milkOffer.copy(skuId = "sku-milk-alternate", catalogueQuantity = null),
            "500 ml",
        )
        val alternateSkuCatalogues = catalogues.toMutableList().also {
            it[3] = Recommendations(
                candidates = listOf(alternateMilkSku),
                suggested = alternateMilkSku,
                query = catalogues[3].query,
            )
        }
        val alternateSkuBasket = requireNotNull(GroceryIntentContextBasket.create(preparation, alternateSkuCatalogues))
        assertEquals("first milk row still independently resolves before SKU consolidation",
            SwiggyCartQuantityResolution.Exact(2), alternateSkuBasket.resolution(2, milkOffer))
        assertEquals("second milk row has current evidence for the alternate SKU",
            SwiggyCartQuantityResolution.Exact(4), alternateSkuBasket.resolution(3, alternateMilkSku))
        val alternateSkuSelection = selected.toMutableMap().also { it[3] = alternateMilkSku }
        assertEquals(listOf(2, 2, 2, 4), alternateSkuSelection.map { (index, offer) ->
            requireNotNull(alternateSkuBasket.preview(index, offer)).quantity
        })
        assertNull("duplicate spin IDs with different SKU IDs cannot be consolidated",
            SwiggyCartReviewRequest.buildContext(alternateSkuBasket, alternateSkuSelection, emptySet()))

        val overLimitInstruction = "6 litres of milk; 6 litres milk"
        val overLimitRows = listOf(
            Fixture("overflow-milk-a", "6 litres of milk", "milk", 6, "l", candidateLabel = "Milk 500 ml"),
            Fixture("overflow-milk-b", "6 litres milk", "milk", 6, "l", candidateLabel = "Milk 500 ml"),
        )
        val overLimitPreparation = GroceryIntentContextResponse.parse(
            mixedBasketResponse(overLimitInstruction, overLimitRows), overLimitInstruction,
        ).prepareForReview()
        assertTrue("both independent source rows must prepare before consolidation",
            overLimitPreparation.blockingIssues.isEmpty())
        val overLimitCatalogues = overLimitPreparation.legacyPreparation.items.map { item ->
            Recommendations(listOf(milkOffer), suggested = milkOffer, query = swiggyRecommendationQuery(item))
        }
        val overLimitBasket = requireNotNull(GroceryIntentContextBasket.create(overLimitPreparation, overLimitCatalogues))
        assertEquals(SwiggyCartQuantityResolution.Exact(12), overLimitBasket.resolution(0, milkOffer))
        assertEquals(SwiggyCartQuantityResolution.Exact(12), overLimitBasket.resolution(1, milkOffer))
        assertEquals(listOf(12, 12), listOf(0, 1).map { index ->
            requireNotNull(overLimitBasket.preview(index, milkOffer)).quantity
        })
        assertNull("two individually exact source rows totaling 24 cart units exceed the request cap",
            SwiggyCartReviewRequest.buildContext(overLimitBasket, mapOf(0 to milkOffer, 1 to milkOffer), emptySet()))
    }

    private fun assertSourceEvidenceIsOriginal(source: String, item: com.example.beta.automation.ParsedItem) {
        val proof = item.intentQuantityProvenance
        if (proof != null) {
            assertEquals(source, proof.primaryText)
            assertEquals(source, proof.rawText)
            proof.local?.let { assertAmountSource(source, it) }
            proof.corrected?.let { assertAmountSource(source, it) }
            proof.unitSize?.let { assertAmountSource(source, it) }
        }
    }

    private fun assertAmountSource(source: String, amount: IntentLocalAmount) {
        assertEquals(source, amount.source)
        assertTrue(amount.startUtf16 in 0..source.length)
        assertTrue(amount.endUtf16 in amount.startUtf16..source.length)
        assertEquals(amount.atom, source.substring(amount.startUtf16, amount.endUtf16))
    }

    private fun candidate(fixture: Fixture, unit: String, wrongPhysicalSize: Boolean = false): RecommendationCandidate {
        val label = if (wrongPhysicalSize) {
            val wrong = fixture.packValue!!.toBigDecimal().add(java.math.BigDecimal.ONE)
            "${fixture.name} $wrong ${fixture.packUnit}"
        } else fixture.candidateLabel
        return RecommendationCandidate(
            spinId = "synthetic-${fixture.id}-$unit${if (wrongPhysicalSize) "-wrong" else ""}",
            skuId = "synthetic-${fixture.id}-$unit${if (wrongPhysicalSize) "-wrong" else ""}",
            label = label,
        )
    }

    private fun legacyQuantityItem(source: String, name: String, unit: String, quantity: Int?) = GroceryIntentItem(
        name = name,
        quantity = quantity?.let { BigDecimal(it) },
        quantityUnit = unit,
        packValue = BigDecimal("100"),
        packUnit = "g",
        required = emptyList(),
        excluded = emptyList(),
        sourceText = source,
        needsClarification = false,
    )

    private fun prepared(fixture: Fixture, unit: String): GroceryIntentContextPreparation {
        val response = GroceryIntentContextResponse.parse(responseBody(fixture, unit), fixture.source)
        val assessment = response.assessForReview()
        assertTrue("${fixture.id}: expected prepared comparison case, got $assessment",
            assessment is GroceryIntentContextAssessment.Prepared)
        return (assessment as GroceryIntentContextAssessment.Prepared).preparation
    }

    private fun previewOutcome(
        preparation: GroceryIntentContextPreparation,
        item: com.example.beta.automation.ParsedItem,
        candidate: RecommendationCandidate,
    ): PreviewOutcome {
        val catalogue = Recommendations(
            candidates = listOf(candidate),
            suggested = candidate,
            query = swiggyRecommendationQuery(item),
        )
        val basket = GroceryIntentContextBasket.create(preparation, listOf(catalogue))
            ?: return PreviewOutcome(null, "BasketBlocked")
        val resolution = basket.resolution(0, candidate)
        val resolutionClass = when (resolution) {
            is SwiggyCartQuantityResolution.Exact -> "Exact"
            is SwiggyCartQuantityResolution.RetailProposal -> "RetailProposal"
            is SwiggyCartQuantityResolution.Held -> "Held:${resolution.reason}"
        }
        return PreviewOutcome(basket.preview(0, candidate), resolutionClass)
    }

    private fun preview(
        preparation: GroceryIntentContextPreparation,
        item: com.example.beta.automation.ParsedItem,
        candidate: RecommendationCandidate,
    ): SwiggyMcpClient.RequestedItem? {
        val catalogue = Recommendations(
            candidates = listOf(candidate),
            suggested = candidate,
            query = swiggyRecommendationQuery(item),
        )
        return GroceryIntentContextBasket.create(preparation, listOf(catalogue))?.preview(0, candidate)
    }

    private fun withCatalogueQuantity(
        candidate: RecommendationCandidate,
        quantity: String,
    ) = candidate.copy(catalogueQuantity = CatalogueQuantityEvidence(
        version = 1,
        source = "search_variation",
        spinId = candidate.spinId,
        skuId = requireNotNull(candidate.skuId),
        fields = CatalogueQuantityFields(variantName = quantity),
    ))

    private fun withCataloguePackSize(
        candidate: RecommendationCandidate,
        size: String,
    ) = candidate.copy(catalogueQuantity = CatalogueQuantityEvidence(
        version = 1,
        source = "search_variation",
        spinId = candidate.spinId,
        skuId = requireNotNull(candidate.skuId),
        fields = CatalogueQuantityFields(packSize = size),
    ))

    private fun responseBody(fixture: Fixture, unit: String): String {
        val row = """{"name":${json(fixture.name)},"quantity":${fixture.quantity},"quantity_unit":${json(unit)},"pack_value":${fixture.packValue ?: "null"},"pack_unit":${json(fixture.packUnit)},"required":[],"excluded":[],"source_text":${json(fixture.source)},"needs_clarification":false}"""
        return """{"accepted":true,"draft":{"contract_version":"grocery-intent-v1-context","requires_review":true,"instruction":${json(fixture.source)},"items":[$row],"model_needs_clarification":false,"context":[]},"model":"test","promptVersion":"context-v1","latencyMs":1,"usage":{}}"""
    }

    private fun mixedBasketResponse(instruction: String, fixtures: List<Fixture>): String {
        val itemRows = fixtures.joinToString(",") { fixture ->
            """{"name":${json(fixture.name)},"quantity":${fixture.quantity},"quantity_unit":${json(fixture.sourceQuantityUnit)},"pack_value":${fixture.packValue ?: "null"},"pack_unit":${json(fixture.packUnit)},"required":[],"excluded":[],"source_text":${json(fixture.source)},"needs_clarification":false}"""
        }
        return """{"accepted":true,"draft":{"contract_version":"grocery-intent-v1-context","requires_review":true,"instruction":${json(instruction)},"items":[$itemRows],"model_needs_clarification":false,"context":[]},"model":"test","promptVersion":"context-v1","latencyMs":1,"usage":{}}"""
    }

    private fun json(value: String): String = "\"" + value
        .replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
}
