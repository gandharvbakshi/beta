package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.SwiggyMcpClient.Recommendations
import com.example.beta.automation.InstructionParser
import com.example.beta.automation.IntentQuantityOrigin
import com.example.beta.automation.Quantity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwiggyOrdinaryPackReviewFlowTest {
    @Test fun namedRetailMeasuredSizeNeedsChosenSkuRawSizeAtFinalRequestBoundary() {
        data class NamedCase(val source: String, val name: String, val count: Int, val ml: Int)
        for (case in listOf(NamedCase("2 200 ml bottles of coconut milk", "coconut milk", 2, 200),
                NamedCase("one 500 ml bottle of shampoo", "shampoo", 1, 500))) {
            val body = """{"accepted":true,"draft":{"contract_version":"grocery-intent-v1","requires_review":true,"instruction":"${case.source}","items":[{"name":"${case.name}","quantity":${case.count},"quantity_unit":"pack","pack_value":${case.ml},"pack_unit":"ml","required":["bottle"],"excluded":[],"source_text":"${case.source}","needs_clarification":false}],"model_needs_clarification":false},"model":"synthetic","promptVersion":"named-retail-boundary","latencyMs":0,"usage":{}}"""
            val item = GroceryIntentResponse.parse(body, case.source).draft.prepareForReview().items.single()
            assertEquals(Quantity.Count(case.count), item.quantity)
            val good = RecommendationCandidate("named-retail", "${case.name} Bottle · ${case.ml} ml",
                variant = "${case.ml} ml", skuId = "named-retail-sku", catalogueQuantity = CatalogueQuantityEvidence(
                    1, "search_variation", "named-retail", "named-retail-sku",
                    CatalogueQuantityFields(quantityDescription = "1 bottle", packSize = "${case.ml} ml")))
            fun fields(raw: CatalogueQuantityFields) = good.copy(catalogueQuantity = good.catalogueQuantity!!.copy(fields = raw))
            val bad = listOf(
                fields(CatalogueQuantityFields(quantityDescription = "1 bottle")), // size exists only in display
                fields(CatalogueQuantityFields(quantityDescription = "1 bottle", packSize = "${case.ml} g")),
                fields(CatalogueQuantityFields(quantityDescription = "1 bottle", packSize = "${case.ml + 1} ml")),
                fields(CatalogueQuantityFields(quantityDescription = "1 bottle", packSize = "${case.ml} ml", variant = "100 ml")),
                fields(CatalogueQuantityFields(quantityDescription = "2 bottles", packSize = "2 x ${case.ml} ml")),
                good.copy(catalogueQuantity = good.catalogueQuantity!!.copy(skuId = "different-sku")),
            )
            val all = Recommendations(listOf(good) + bad.mapIndexed { i, c ->
                val spin = "named-bad-$i"
                val sku = "named-bad-sku-$i"
                c.copy(spinId = spin, skuId = sku, catalogueQuantity = c.catalogueQuantity!!.copy(spinId = spin,
                    skuId = if (i == bad.lastIndex) "different-sku" else sku))
            })
            val checked = assessSwiggyIntentQuantity(item, all)
            val request = requireNotNull(SwiggyCartReviewRequest.build(listOf(checked), mapOf(0 to good), emptySet()))
            assertEquals(case.count, request.requestedItems.single().quantity)
            assertEquals(good.skuId, request.requestedItems.single().skuId)
            assertTrue(resolveSwiggyCartQuantity(checked, good) is SwiggyCartQuantityResolution.Exact)
            for (candidate in all.candidates.drop(1)) {
                for (assessed in listOf(checked, assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate))))) {
                    assertNull("Chosen raw-size failure must not borrow a matching alternative", SwiggyCartReviewRequest.build(
                        listOf(assessed), mapOf(0 to candidate), emptySet()))
                    assertFalse("Earlier retail-count branch must not bypass chosen physical-size proof",
                        (swiggyCatalogueRetailCartQuantity(assessed, candidate) ?: 0) > 0)
                    assertFalse(resolveSwiggyCartQuantity(assessed, candidate) is SwiggyCartQuantityResolution.Exact)
                }
            }
        }
    }

    @Test
    fun parserAndV1PreparationKeepOrdinaryPacketCountsAsReviewProposalsInSourceOrder() {
        val cases = listOf(
            Case("milk two packets", "milk", "Milk Packet 500 ml"),
            Case("two packets milk", "milk", "Milk Packet 500 ml"),
            Case("2 milk tetra packs", "milk tetra pack", "Milk Tetra Pack 500 ml"),
        )
        val items = mutableListOf<com.example.beta.automation.ParsedItem>()
        val candidates = linkedMapOf<Int, RecommendationCandidate>()
        cases.forEachIndexed { index, case ->
            val parsed = InstructionParser.parse(case.source).single()
            val prepared = prepareV1(case).items.single()
            for (item in listOf(parsed, prepared)) {
                assertEquals("${case.source}: source count", Quantity.Count(2), item.quantity)
                assertTrue("${case.source}: proof required", item.requiresIntentQuantityProof)
                val candidate = offer("spin-$index-${items.size}", "sku-$index-${items.size}", case.title, "500 ml")
                val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
                val resolution = resolveSwiggyCartQuantity(checked, candidate)
                assertTrue("${case.source}: proposal, not exact: $resolution",
                    resolution is SwiggyCartQuantityResolution.RetailProposal)
                assertFalse("${case.source}: not exact authorization",
                    resolution is SwiggyCartQuantityResolution.Exact)
                items += checked
                candidates[items.lastIndex] = candidate
            }
        }

        val request = requireNotNull(SwiggyCartReviewRequest.build(items, candidates, emptySet()))
        assertEquals(candidates.values.map { it.spinId }, request.requestedItems.map { it.spinId })
        assertEquals(List(items.size) { 2 }, request.requestedItems.map { it.quantity })
        assertEquals(items.indices.toList(), request.quantityProposals.map { it.rowIndex })
        assertEquals(candidates.values.map { it.skuId }, request.quantityProposals.map { it.skuId })

        val gate = SwiggyQuantityProposalGate()
        gate.prepare(OPERATION, ADDRESS, PLAN, request.quantityProposals)
        assertFalse("unchecked proposal cannot apply",
            gate.canApply(OPERATION, ADDRESS, PLAN, request.quantityProposals))
        assertTrue(gate.acknowledge(OPERATION, ADDRESS, PLAN, request.quantityProposals, checked = true))
        assertTrue(gate.canApply(OPERATION, ADDRESS, PLAN, request.quantityProposals))
        assertTrue(gate.consumeForApply(OPERATION, ADDRESS, PLAN, request.quantityProposals))
        assertFalse("acknowledgement is consumed once",
            gate.consumeForApply(OPERATION, ADDRESS, PLAN, request.quantityProposals))
    }

    @Test
    fun changedProposalSourceSkuOrPlanTokenInvalidatesAcknowledgement() {
        val item = prepareV1(Case("two packets milk", "milk", "Milk Packet 500 ml")).items.single()
        val candidate = offer("milk-spin", "milk-sku", "Milk Packet 500 ml", "500 ml")
        val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
        val request = requireNotNull(SwiggyCartReviewRequest.build(listOf(checked), mapOf(0 to candidate), emptySet()))
        val proposal = request.quantityProposals.single()
        val gate = SwiggyQuantityProposalGate()

        fun prepareAndAcknowledge() {
            gate.prepare(OPERATION, ADDRESS, PLAN, listOf(proposal))
            assertTrue(gate.acknowledge(OPERATION, ADDRESS, PLAN, listOf(proposal), checked = true))
            assertTrue(gate.canApply(OPERATION, ADDRESS, PLAN, listOf(proposal)))
        }

        prepareAndAcknowledge()
        assertFalse(gate.canApply(OPERATION, ADDRESS, PLAN, listOf(proposal.copy(source = "milk two packets"))))
        assertFalse(gate.canApply(OPERATION, ADDRESS, PLAN, listOf(proposal)))

        prepareAndAcknowledge()
        assertFalse(gate.canApply(OPERATION, ADDRESS, PLAN, listOf(proposal.copy(skuId = "changed-sku"))))
        assertFalse(gate.canApply(OPERATION, ADDRESS, PLAN, listOf(proposal)))

        prepareAndAcknowledge()
        assertFalse(gate.canApply(OPERATION, ADDRESS, "changed-plan", listOf(proposal)))
        assertFalse(gate.canApply(OPERATION, ADDRESS, PLAN, listOf(proposal)))
    }

    @Test
    fun titleOnlyAndTypedPhysicalOffersAreProposalsButUnusableFactsCannotFallback() {
        val item = prepareV1(Case("two packets milk", "milk", "Milk Packet 500 ml")).items.single()
        val titleOnly = RecommendationCandidate("title-only", "Milk Packet 500 ml", skuId = "title-only-sku")
        val typed = offer("typed", "typed-sku", "Milk Packet 500 ml", "500 ml")
        for (candidate in listOf(titleOnly, typed)) {
            val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
            assertTrue("${candidate.spinId} should remain a proposal",
                resolveSwiggyCartQuantity(checked, candidate) is SwiggyCartQuantityResolution.RetailProposal)
            val request = requireNotNull(SwiggyCartReviewRequest.build(listOf(checked), mapOf(0 to candidate), emptySet()))
            assertEquals(2, request.quantityProposals.single().quantity)
        }

        val invalidTypedButCleanTitle = typed.copy(catalogueQuantityInvalid = true)
        val unusableOffers = listOf(
            invalidTypedButCleanTitle,
            RecommendationCandidate("no-size", "Milk Packet", skuId = "no-size-sku"),
            offer("conflict", "conflict-sku", "Milk Packet 500 ml", "1 kg"),
            offer("bundle", "bundle-sku", "Milk Packet 2 x 500 ml", "2 x 500 ml"),
        )
        for (candidate in unusableOffers) {
            val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
            assertTrue("${candidate.spinId} must be held; no fallback from invalid facts",
                resolveSwiggyCartQuantity(checked, candidate) is SwiggyCartQuantityResolution.Held)
            assertNull("${candidate.spinId} cannot enter review request",
                SwiggyCartReviewRequest.build(listOf(checked), mapOf(0 to candidate), emptySet()))
        }
        // Explicit outer-packet source + bound piece contents now permits a
        // reviewed offer, never inferred division or an automatic Exact action.
        val pieces = offer("pieces", "pieces-sku", "Milk Packet 6 Pieces", "6 pieces")
        val checkedPieces = assessSwiggyIntentQuantity(item, Recommendations(listOf(pieces)))
        assertReviewedRetailProposal(checkedPieces, pieces, 2)
        assertTrue((resolveSwiggyCartQuantity(checkedPieces, pieces) as
            SwiggyCartQuantityResolution.RetailProposal).contents is SwiggyRetailOfferContents.Proven)
    }

    @Test
    fun v1PackOnlyPhysicalTotalCannotCreateAnUnconservedDefaultCartPlan() {
        data class OfferSpec(val title: String, val variant: String, val description: String = variant)

        fun runPhysicalTotal(source: String, product: String, dimension: GroceryPackDimension, expected: String,
                             specs: List<OfferSpec>, validSpin: String) {
            val unit = if (dimension == GroceryPackDimension.WEIGHT) "g" else "ml"
            val item = GroceryIntentDraft(
                "grocery-intent-v1", true, source,
                listOf(GroceryIntentItem(product, null, "unspecified", expected.toBigDecimal(), unit,
                    emptyList(), emptyList(), source, false)),
                false,
            ).prepareForReview().items.single()
            assertEquals(Quantity.Default, item.quantity)

            val offers = specs.mapIndexed { index, spec ->
                val spin = "physical-total-$index"
                val sku = "physical-total-sku-$index"
                RecommendationCandidate(
                    spinId = spin,
                    skuId = sku,
                    label = spec.title,
                    catalogueQuantity = CatalogueQuantityEvidence(
                        version = 1,
                        source = "search_variation",
                        spinId = spin,
                        skuId = sku,
                        fields = CatalogueQuantityFields(
                            variantName = spec.variant,
                            quantityDescription = spec.description,
                        ),
                    ),
                )
            }

            fun assertConserved(candidate: RecommendationCandidate) {
                val recommendation = Recommendations(listOf(candidate))
                val checked = assessSwiggyIntentQuantity(item, recommendation)
                val usable = swiggyUsableCandidates(checked, recommendation)
                if (candidate !in usable) {
                    assertNull("held offer cannot build a cart request", SwiggyCartReviewRequest.build(
                        listOf(checked), mapOf(0 to candidate), emptySet()))
                    return
                }
                val request = requireNotNull(SwiggyCartReviewRequest.build(
                    listOf(checked), mapOf(0 to candidate), emptySet()))
                val resolution = resolveSwiggyCartQuantity(checked, candidate)
                val units = when (resolution) {
                    is SwiggyCartQuantityResolution.Exact -> resolution.cartUnits
                    is SwiggyCartQuantityResolution.RetailProposal -> resolution.cartUnits
                    is SwiggyCartQuantityResolution.Held -> error("usable candidate resolved held")
                }
                assertEquals(units, request.requestedItems.single().quantity)
                val measure = requireNotNull(swiggyCatalogueSourcePhysicalMeasure(candidate)) {
                    "usable physical offer needs an independently SKU-bound measure"
                }
                assertEquals(dimension, measure.dimension)
                assertEquals(0, measure.baseValue.multiply(units.toBigDecimal()).compareTo(expected.toBigDecimal()))
            }

            offers.forEach(::assertConserved)

            val all = Recommendations(offers)
            val assessed = assessSwiggyIntentQuantity(item, all)
            val usable = swiggyUsableCandidates(assessed, all)
            val default = usable.firstOrNull() // coordinator's normal first-usable suggestion
            assertNotNull("the exact-size source-bound SKU is a positive usable control",
                usable.firstOrNull { it.spinId == validSpin })
            assertConserved(requireNotNull(default))
        }

        runPhysicalTotal(
            source = "500 g oats", product = "oats", dimension = GroceryPackDimension.WEIGHT, expected = "500",
            specs = listOf(
                OfferSpec("Oats 250 g", "250 g"),
                OfferSpec("Oats 1 kg", "1 kg"),
                OfferSpec("Oats 2 x 250 g", "2 x 250 g"),
                OfferSpec("Oats 2 x 500 g", "2 x 500 g"),
                OfferSpec("Oats 500 g with conflicting variant", "500 g", "250 g"),
                OfferSpec("Oats 500 ml", "500 ml"),
                OfferSpec("Oats 500 g", "500 g"),
            ),
            validSpin = "physical-total-6",
        )
        runPhysicalTotal(
            source = "500 ml juice", product = "juice", dimension = GroceryPackDimension.VOLUME, expected = "500",
            specs = listOf(
                OfferSpec("Juice 250 ml", "250 ml"),
                OfferSpec("Juice 1 L", "1 l"),
                OfferSpec("Juice 2 x 250 ml", "2 x 250 ml"),
                OfferSpec("Juice 2 x 500 ml", "2 x 500 ml"),
                OfferSpec("Juice 500 ml with conflicting variant", "500 ml", "250 ml"),
                OfferSpec("Juice 500 g", "500 g"),
                OfferSpec("Juice 500 ml", "500 ml"),
            ),
            validSpin = "physical-total-6",
        )
    }

    @Test
    fun parsedSavedWeightTotalsCannotBuildAnUnconservedOatsOrAlmondsPlan() {
        data class TypedOffer(
            val title: String,
            val variant: String?,
            val description: String?,
            val packSize: String? = null,
        )

        fun savedPhysicalTotal(source: String, product: String, grams: Int): com.example.beta.automation.ParsedItem {
            val body = """{"accepted":true,"draft":{"contract_version":"grocery-intent-v1","requires_review":true,"instruction":"$source","items":[{"name":"$product","quantity":null,"quantity_unit":"unspecified","pack_value":$grams,"pack_unit":"g","required":[],"excluded":[],"source_text":"$source","needs_clarification":false}],"model_needs_clarification":false},"model":"saved-synthetic","promptVersion":"v1","latencyMs":0,"usage":{}}"""
            val item = GroceryIntentResponse.parse(body, source).draft.prepareForReview().items.single()
            assertEquals(Quantity.Default, item.quantity)
            return item
        }

        fun candidate(index: Int, spec: TypedOffer) = RecommendationCandidate(
            spinId = "saved-physical-$index",
            skuId = "saved-physical-sku-$index",
            label = spec.title,
            catalogueQuantity = CatalogueQuantityEvidence(
                version = 1,
                source = "search_variation",
                spinId = "saved-physical-$index",
                skuId = "saved-physical-sku-$index",
                fields = CatalogueQuantityFields(
                    variantName = spec.variant,
                    quantityDescription = spec.description,
                    packSize = spec.packSize,
                ),
            ),
        )

        fun assertNoUnconservedPlan(source: String, product: String, expectedGrams: Int, specs: List<TypedOffer>) {
            val item = savedPhysicalTotal(source, product, expectedGrams)
            val offers = specs.mapIndexed(::candidate)
            val recommendations = Recommendations(offers)
            val checked = assessSwiggyIntentQuantity(item, recommendations)
            val usable = swiggyUsableCandidates(checked, recommendations)

            fun assertBuildConserves(candidate: RecommendationCandidate) {
                if (candidate !in usable) {
                    assertNull("rejected SKU ${candidate.skuId} cannot enter final cart plan",
                        SwiggyCartReviewRequest.build(listOf(checked), mapOf(0 to candidate), emptySet()))
                    return
                }
                val request = requireNotNull(SwiggyCartReviewRequest.build(
                    listOf(checked), mapOf(0 to candidate), emptySet(),
                ))
                val resolution = resolveSwiggyCartQuantity(checked, candidate)
                val units = when (resolution) {
                    is SwiggyCartQuantityResolution.Exact -> resolution.cartUnits
                    is SwiggyCartQuantityResolution.RetailProposal -> resolution.cartUnits
                    is SwiggyCartQuantityResolution.Held -> error("usable SKU resolved held")
                }
                assertEquals(units, request.requestedItems.single().quantity)
                assertEquals(candidate.skuId, request.requestedItems.single().skuId)
                val measure = requireNotNull(swiggyCatalogueSourcePhysicalMeasure(candidate))
                assertEquals(GroceryPackDimension.WEIGHT, measure.dimension)
                assertEquals(0, measure.baseValue.multiply(units.toBigDecimal())
                    .compareTo(expectedGrams.toBigDecimal()))
            }

            offers.forEach(::assertBuildConserves)
            if (usable.isNotEmpty()) {
                val selected = swiggyDefaultSuggestion(checked, usable, preferred = null)
                assertBuildConserves(selected) // Current exact matching is not a required positive.
            }
        }

        val variants = listOf(
            TypedOffer("Oats 500 g", "500 g", "500 g"),
            TypedOffer("Oats 1 kg", "1 kg", "1 kg"),
            TypedOffer("Oats 2 x 500 g", "2 x 500 g", "2 x 500 g"),
            TypedOffer("Oats 500 g pack of 2", "Pack of 2", "500 g each", "1 pack of 2"),
            TypedOffer("Oats 500 g, conflicting structured 1 kg", "1 kg", "1 kg"),
        )
        assertNoUnconservedPlan("500 g oats", "oats", 500, variants)
        assertNoUnconservedPlan("100 g almonds", "almonds", 100, variants.map {
            it.copy(title = it.title.replace("Oats", "Almonds"))
        })
    }

    @Test
    fun copiedOrChangedSourceProofCannotAuthorizeReviewProposal() {
        val source = "two packets milk"
        val original = prepareV1(Case(source, "milk", "Milk Packet 500 ml")).items.single()
        val candidate = offer("milk-spin", "milk-sku", "Milk Packet 500 ml", "500 ml")
        val checked = assessSwiggyIntentQuantity(original, Recommendations(listOf(candidate)))
        val mutations = listOf(
            checked.copy(rawText = "two bottles milk"),
            checked.copy(quantity = Quantity.Count(3)),
            checked.copy(requiresIntentQuantityProof = false),
            checked.copy(intentQuantityProvenance = null),
        )
        mutations.forEachIndexed { index, mutation ->
            val reassessed = assessSwiggyIntentQuantity(mutation, Recommendations(listOf(candidate)))
            assertTrue("mutation $index must be held",
                resolveSwiggyCartQuantity(reassessed, candidate) is SwiggyCartQuantityResolution.Held)
            assertNull("mutation $index cannot build request",
                SwiggyCartReviewRequest.build(listOf(reassessed), mapOf(0 to candidate), emptySet()))
        }
    }

    @Test
    fun contextTerminalBhiKeepsPacketOfferForBothOriginalQuoteBoundaries() {
        assertTerminalPacketQuoteParity("bhi")
    }

    @Test
    fun contextTerminalTooKeepsPacketOfferForBothOriginalQuoteBoundaries() {
        assertTerminalPacketQuoteParity("too")
    }

    @Test
    fun contextTerminalAsWellKeepsPacketOfferForBothOriginalQuoteBoundaries() {
        assertTerminalPacketQuoteParity("as well")
    }

    @Test
    fun contextTerminalPacketEnvelopeCannotHideAnotherProductOrClause() {
        for (product in listOf("oats", "rice", "Monaco biscuits")) {
            val core = "ek packet $product"
            for (instruction in listOf("$core bhi and milk", "$core bhi. Add milk", "$core too; milk")) {
                for (source in listOf(core, instruction)) {
                    assertContextPacketHeld(instruction, source, product)
                }
            }
        }
    }

    @Test
    fun contextTerminalPacketEnvelopeCannotHideConditionsDietOrChangedQuantity() {
        for (product in listOf("oats", "rice", "Monaco biscuits")) {
            val core = "ek packet $product"
            for (instruction in listOf("$core bhi if available", "$core too, only if cheap",
                    "$core as well, only sugar free", "$core bhi, make that two")) {
                for (source in listOf(core, instruction)) {
                    assertContextPacketHeld(instruction, source, product)
                }
            }
            val dietary = "ek packet sugar free $product bhi"
            assertContextPacketHeld(dietary, dietary, product) // Producer omitted a source requirement.
            assertContextPacketHeld("$core bhi", core, product, count = 2)
            assertContextPacketHeld("$core bhi", "$core bhi", product, count = 2)
        }
    }

    @Test
    fun contextTerminalPacketEnvelopeCannotRepairDuplicateOrPartialSourceQuotes() {
        for (product in listOf("oats", "rice", "Monaco biscuits")) {
            val core = "ek packet $product"
            assertContextPacketHeld("$core bhi, $core bhi", core, product)
            assertContextPacketHeld("$core bhi, $core bhi", "$core bhi", product)
            // A whole terminal production may be context; a token/phrase prefix is not.
            assertContextPacketHeld("$core bhi", "$core bh", product)
            assertContextPacketHeld("$core too", "$core to", product)
            assertContextPacketHeld("$core as well", "$core as", product)
        }
    }

    @Test
    fun terminalContextReceiptTamperCannotBuildCartRequest() {
        val source = "ek packet oats bhi"
        val assessment = contextPacketAssessment(source, source, "oats")
        assertTrue(assessment is GroceryIntentContextAssessment.Prepared)
        val preparation = (assessment as GroceryIntentContextAssessment.Prepared).preparation
        assertTrue(preparation.blockingIssues.isEmpty())
        val item = preparation.legacyPreparation.items.single()
        assertEquals(source, item.rawText)
        assertEquals(Quantity.Count(1), item.quantity)
        val proof = requireNotNull(item.intentQuantityProvenance)
        assertNotNull(proof.terminalContext)
        assertEquals(IntentQuantityOrigin.SOURCE_NUMERIC, proof.origin)

        val candidate = offer("receipt-oats", "receipt-oats-sku", "Oats 200 g", "200 g")
        val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
        assertTrue(resolveSwiggyCartQuantity(checked, candidate) is SwiggyCartQuantityResolution.RetailProposal)
        val baseline = requireNotNull(SwiggyCartReviewRequest.build(listOf(checked), mapOf(0 to candidate), emptySet()))
        assertEquals(1, baseline.requestedItems.single().quantity)

        val riceSource = "ek packet rice bhi"
        val riceAssessment = contextPacketAssessment(riceSource, riceSource, "rice")
        assertTrue(riceAssessment is GroceryIntentContextAssessment.Prepared)
        val ricePreparation = (riceAssessment as GroceryIntentContextAssessment.Prepared).preparation
        assertTrue(ricePreparation.blockingIssues.isEmpty())
        val riceReceipt = requireNotNull(ricePreparation.legacyPreparation.items.single().intentQuantityProvenance?.terminalContext)
        val changedCount = Quantity.Count(2)
        val changedSource = "ek packet oats too"
        val mutations = listOf(
            "missing terminal receipt" to item.copy(intentQuantityProvenance = proof.copy(terminalContext = null)),
            "another product's receipt" to item.copy(intentQuantityProvenance = proof.copy(terminalContext = riceReceipt)),
            "changed quantity origin" to item.copy(intentQuantityProvenance = proof.copy(origin = IntentQuantityOrigin.LOCAL)),
            "changed primary quote" to item.copy(intentQuantityProvenance = proof.copy(primaryText = "ek packet oats")),
            "changed original quote" to item.copy(rawText = changedSource,
                intentQuantityProvenance = proof.copy(rawText = changedSource, primaryText = changedSource)),
            "changed count" to item.copy(quantity = changedCount,
                intentQuantityProvenance = proof.copy(resolvedQuantity = changedCount)),
            "changed identity" to item.copy(query = "rice", strictMatchPhrase = "rice",
                intentQuantityProvenance = proof.copy(query = "rice", strictMatchPhrase = "rice", numericSourceQuery = "rice")),
            "missing quantity proof" to item.copy(intentQuantityProvenance = null),
            "disabled proof requirement" to item.copy(requiresIntentQuantityProof = false),
        )
        for ((label, mutation) in mutations) {
            // The changed identity gets its own matching shelf, so source rejection
            // cannot pass merely because a rice query was offered an oats product.
            val selected = if (mutation.query == "rice")
                offer("receipt-rice", "receipt-rice-sku", "Rice 200 g", "200 g") else candidate
            val fresh = assessSwiggyIntentQuantity(mutation, Recommendations(listOf(selected)))
            assertTrue("$label must remain held after a fresh catalogue assessment",
                resolveSwiggyCartQuantity(fresh, selected) is SwiggyCartQuantityResolution.Held)
            assertNull("$label cannot build a cart request after reassessment",
                SwiggyCartReviewRequest.build(listOf(fresh), mapOf(0 to selected), emptySet()))
        }
    }

    @Test
    fun rebuiltTerminalContextInterpretationCannotReusePriorAcknowledgement() {
        val source = "ek packet oats bhi"
        val candidate = offer("rebuilt-oats", "rebuilt-oats-sku", "Oats 200 g", "200 g")
        fun request(): SwiggyCartReviewRequest {
            val assessment = contextPacketAssessment(source, source, "oats")
            assertTrue(assessment is GroceryIntentContextAssessment.Prepared)
            val preparation = (assessment as GroceryIntentContextAssessment.Prepared).preparation
            assertTrue(preparation.blockingIssues.isEmpty())
            val item = preparation.legacyPreparation.items.single()
            val basket = requireNotNull(GroceryIntentContextBasket.create(preparation,
                listOf(Recommendations(listOf(candidate), query = swiggyRecommendationQuery(item)))))
            assertTrue(basket.resolution(0, candidate) is SwiggyCartQuantityResolution.RetailProposal)
            return requireNotNull(SwiggyCartReviewRequest.buildContext(basket, mapOf(0 to candidate), emptySet()))
        }

        val first = request()
        val rebuilt = request()
        val firstReceipt = requireNotNull(first.sourceItems.single().intentQuantityProvenance?.terminalContext)
        val rebuiltReceipt = requireNotNull(rebuilt.sourceItems.single().intentQuantityProvenance?.terminalContext)
        assertEquals(source, first.sourceItems.single().rawText)
        assertEquals(source, rebuilt.sourceItems.single().rawText)
        assertEquals(first.requestedItems, rebuilt.requestedItems)
        assertFalse("a new interpretation must bind a new receipt", firstReceipt === rebuiltReceipt)
        assertFalse("identical visible quantities do not make interpretation consent interchangeable",
            first.quantityProposals == rebuilt.quantityProposals)

        val gate = SwiggyQuantityProposalGate()
        gate.prepare(OPERATION, ADDRESS, PLAN, first.quantityProposals)
        assertTrue(gate.acknowledge(OPERATION, ADDRESS, PLAN, first.quantityProposals, checked = true))
        assertTrue(gate.canApply(OPERATION, ADDRESS, PLAN, first.quantityProposals))
        assertFalse("old acknowledgement cannot authorize the rebuilt interpretation",
            gate.canApply(OPERATION, ADDRESS, PLAN, rebuilt.quantityProposals))
        assertFalse("a mismatched interpretation revokes the old acknowledgement",
            gate.canApply(OPERATION, ADDRESS, PLAN, first.quantityProposals))

        gate.prepare(OPERATION, ADDRESS, PLAN, rebuilt.quantityProposals)
        assertFalse(gate.canApply(OPERATION, ADDRESS, PLAN, rebuilt.quantityProposals))
        assertTrue(gate.acknowledge(OPERATION, ADDRESS, PLAN, rebuilt.quantityProposals, checked = true))
        assertTrue(gate.consumeForApply(OPERATION, ADDRESS, PLAN, rebuilt.quantityProposals))
        assertFalse(gate.consumeForApply(OPERATION, ADDRESS, PLAN, rebuilt.quantityProposals))
    }

    private fun assertTerminalPacketQuoteParity(suffix: String) {
        val failures = mutableListOf<String>()
        for (product in listOf("oats", "rice", "Monaco biscuits")) {
            val core = "ek packet $product"
            val instruction = "$core $suffix"
            for (source in listOf(core, instruction)) {
                // Collect every independent minimal pair instead of stopping at the first product.
                runCatching {
                    val assessment = contextPacketAssessment(instruction, source, product)
                    assertTrue("expected a prepared context assessment", assessment is GroceryIntentContextAssessment.Prepared)
                    val preparation = (assessment as GroceryIntentContextAssessment.Prepared).preparation
                    val item = preparation.legacyPreparation.items.single()
                    assertEquals("the model's exact original quote must remain owned", source, item.rawText)
                    assertEquals("a terminal particle cannot change the requested count", Quantity.Count(1), item.quantity)
                    assertTrue("blocking issues: ${preparation.blockingIssues.map { it.kind }}", preparation.blockingIssues.isEmpty())

                    val candidate = offer("terminal-packet", "terminal-packet-sku", "$product 200 g", "200 g")
                    val recommendations = Recommendations(listOf(candidate), query = swiggyRecommendationQuery(item))
                    val basket = requireNotNull(GroceryIntentContextBasket.create(preparation, listOf(recommendations)))
                    assertTrue("the controlled ordinary pack must be usable", candidate in basket.usable(0))
                    val resolution = basket.resolution(0, candidate)
                    assertTrue("an unspecified packet remains a reviewed offer", resolution is SwiggyCartQuantityResolution.RetailProposal)
                    assertFalse("context cannot manufacture exact count authority", resolution is SwiggyCartQuantityResolution.Exact)
                    val request = requireNotNull(SwiggyCartReviewRequest.buildContext(basket, mapOf(0 to candidate), emptySet()))
                    assertEquals(1, request.requestedItems.single().quantity)
                    assertEquals(candidate.skuId, request.requestedItems.single().skuId)
                    assertEquals(source, request.sourceItems.single().rawText)
                    assertEquals(source, request.quantityProposals.single().source)
                    assertEquals(1, request.quantityProposals.single().quantity)

                    val row = swiggyQuantityProposalReviewRow(basket.items.single(), candidate,
                        resolution as SwiggyCartQuantityResolution.RetailProposal, "Cart line: 0 → 1 packs")
                    assertTrue("review must display the exact owned source", row.title.contains(source))
                    val gate = SwiggyQuantityProposalGate()
                    gate.prepare(OPERATION, ADDRESS, PLAN, request.quantityProposals)
                    assertFalse(gate.canApply(OPERATION, ADDRESS, PLAN, request.quantityProposals))
                    assertTrue(gate.acknowledge(OPERATION, ADDRESS, PLAN, request.quantityProposals, checked = true))
                    assertTrue(gate.canApply(OPERATION, ADDRESS, PLAN, request.quantityProposals))
                    assertTrue(gate.consumeForApply(OPERATION, ADDRESS, PLAN, request.quantityProposals))
                    assertFalse("consent is one-shot", gate.consumeForApply(OPERATION, ADDRESS, PLAN, request.quantityProposals))
                }.exceptionOrNull()?.let { failure ->
                    failures += "instruction=[$instruction], source=[$source]: ${failure.javaClass.simpleName}: ${failure.message}"
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun assertContextPacketHeld(instruction: String, source: String, product: String, count: Int = 1) {
        val assessment = try {
            contextPacketAssessment(instruction, source, product, count)
        } catch (_: IllegalArgumentException) {
            // The strict source binder may reject ambiguous or mid-token quotes before preparation.
            return
        }
        val message = "instruction=[$instruction], source=[$source], count=$count"
        when (assessment) {
            is GroceryIntentContextAssessment.Held -> assertTrue(message, assessment.blockingIssues.isNotEmpty())
            is GroceryIntentContextAssessment.Prepared -> {
                val preparation = assessment.preparation
                assertTrue(message, preparation.blockingIssues.isNotEmpty())
                val catalogues = preparation.legacyPreparation.items.map { item ->
                    Recommendations(listOf(offer("held-packet", "held-packet-sku", "$product 200 g", "200 g")),
                        query = swiggyRecommendationQuery(item))
                }
                assertNull("a source hold cannot enter a cart review: $message", GroceryIntentContextBasket.create(preparation, catalogues))
            }
        }
    }

    private fun contextPacketAssessment(instruction: String, source: String, product: String, count: Int = 1): GroceryIntentContextAssessment {
        fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val body = """{"accepted":true,"draft":{"contract_version":"grocery-intent-v1-context","requires_review":true,"instruction":${quoted(instruction)},"items":[{"name":${quoted(product)},"quantity":$count,"quantity_unit":"count","pack_value":null,"pack_unit":"unspecified","required":[],"excluded":[],"source_text":${quoted(source)},"needs_clarification":false}],"model_needs_clarification":false,"context":[]},"model":"synthetic","promptVersion":"terminal-packet-quote","latencyMs":0,"usage":{}}"""
        return GroceryIntentContextResponse.parse(body, instruction).assessForReview()
    }

    private data class Case(val source: String, val name: String, val title: String)

    private fun prepareV1(case: Case): GroceryIntentPreparation {
        val body = """{"accepted":true,"draft":{"contract_version":"grocery-intent-v1","requires_review":true,"instruction":"${case.source}","items":[{"name":"${case.name}","quantity":2,"quantity_unit":"pack","pack_value":null,"pack_unit":"unspecified","required":[],"excluded":[],"source_text":"${case.source}","needs_clarification":false}],"model_needs_clarification":false},"model":"fixture","promptVersion":"ordinary-pack-review","latencyMs":0,"usage":{}}"""
        return GroceryIntentResponse.parse(body, case.source).draft.prepareForReview()
    }

    private fun offer(spin: String, sku: String, title: String, size: String) = RecommendationCandidate(
        spinId = spin,
        skuId = sku,
        label = title,
        catalogueQuantity = CatalogueQuantityEvidence(
            version = 1,
            source = "search_variation",
            spinId = spin,
            skuId = sku,
            fields = CatalogueQuantityFields(variantName = size),
        ),
    )

    private companion object {
        const val OPERATION = 74L
        const val ADDRESS = "test-address"
        const val PLAN = "test-plan"
    }
}
