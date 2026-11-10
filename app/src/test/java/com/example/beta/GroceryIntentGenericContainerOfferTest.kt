package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.SwiggyMcpClient.Recommendations
import com.example.beta.automation.IntentSourceCountRole
import com.example.beta.automation.Quantity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentGenericContainerOfferTest {
    @Test
    fun gluedPhysicalMeasuresUseTheExistingCompleteTokenBinder() {
        for (source in listOf("1 dabba butter 500g", "1 500g butter ka dabba", "1 dabba butter 500grams")) {
            val item = prepare(Example(source, "butter", 1, "pack", 500, "g", "Butter 500 g"))
            assertNotNull(item.validGenericContainerReceipt())
        }
        for (source in listOf("1 dabba butter 500gfoo", "1 dabba butter A500g",
                "1 dabba butter 500.5g", "1 dabba butter -500g")) {
            assertTrue(runCatching {
                prepare(Example(source, "butter", 1, "pack", 500, "g", "Butter 500 g"))
            }.isFailure)
        }
    }

    @Test
    fun v1GenericWrapperRequestsBecomeOnlyExplicitRetailProposals() {
        val examples = listOf(
            Example("Ek dabba Amul butter le aao, 100 g", "Amul butter", 1, "count", 100, "g",
                "Amul butter 100 g"),
            Example("Ek 500 g ghee ka dabba", "ghee", 1, "count", 500, "g",
                "Ghee 500 g"),
            Example("2 dibbe atta 1 kg", "atta", 2, "count", 1, "kg",
                "Atta 1 kg"),
            Example("1 dabbi chai 250 ml", "chai", 1, "count", 250, "ml",
                "Chai 250 ml"),
        )
        examples.forEachIndexed { index, example ->
            val item = prepare(example)
            val proof = requireNotNull(item.intentQuantityProvenance)
            assertEquals(IntentSourceCountRole.GENERIC_WRAPPERS, proof.sourceCountRole)
            assertNotNull(proof.genericContainerReceipt)
            assertTrue(item.requiresIntentQuantityProof)
            assertFalse(item.retailPackCount)
            assertFalse(item.logicalPieceCount)
            assertEquals(Quantity.Count(example.count), item.quantity)

            val candidate = candidate(example.name, example.catalogueLabel, example.sizeText)
            val assessed = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
            val result = resolveSwiggyCartQuantity(assessed, candidate)
            assertTrue("example $index must remain a proposal, not exact", result is SwiggyCartQuantityResolution.RetailProposal)
            assertEquals(example.count, (result as SwiggyCartQuantityResolution.RetailProposal).cartUnits)
            assertTrue("exact-only cart quantity API must reject a proposal", runCatching {
                swiggyRequestedCartQuantity(assessed, candidate)
            }.isFailure)
        }
    }

    @Test
    fun unsizedGenericCountUsesAuthoritativeSkuSizeOnlyAsRetailProposalForEveryLegacyEnum() {
        for (quantityUnit in listOf("count", "pack", "piece")) {
            val source = "2 dabbe tea"
            val item = prepareUnsized(source, "tea", 2, quantityUnit)
            val receipt = requireNotNull(item.validGenericContainerReceipt())
            assertTrue(receipt.requestedSize is GenericContainerRequestedSize.Unspecified)
            assertEquals(null, receipt.size)
            assertEquals(null, receipt.physicalSize)

            for ((sizeText, label) in listOf("250 g" to "Tea 250 g", "25 pieces" to "Tea 25 Pieces")) {
                val candidate = candidate("tea", label, sizeText)
                val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
                val result = resolveSwiggyCartQuantity(checked, candidate)
                assertTrue("$quantityUnit $sizeText must remain a retail proposal",
                    result is SwiggyCartQuantityResolution.RetailProposal)
                assertEquals(2, (result as SwiggyCartQuantityResolution.RetailProposal).cartUnits)
                assertTrue(runCatching { swiggyRequestedCartQuantity(checked, candidate) }.isFailure)
            }
        }
    }

    @Test
    fun unsizedOfferRequiresAuthoritativeOwnSkuEvidenceAndHoldsTitleOnlyWrongSkuBundlesAndConflicts() {
        val item = prepareUnsized("1 dabba tea", "tea", 1)
        val invalidCandidates = listOf(
            candidate("tea", "Tea 250 g", "250 g", includeEvidence = false),
            candidate("tea", "Tea 250 g", "250 g", evidenceSku = "different-sku"),
            candidate("tea", "Tea 2 x 250 g", "2 x 250 g"),
            candidate("tea", "Tea 250 g", "500 g"),
        )
        invalidCandidates.forEachIndexed { index, candidate ->
            val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
            assertTrue("unproven unsized offer $index must be held",
                resolveSwiggyCartQuantity(checked, candidate) is SwiggyCartQuantityResolution.Held)
        }
    }

    @Test
    fun unsizedReceiptCannotCropAPhysicalSizeFromTheFullInstruction() {
        val cropped = runCatching {
            prepareUnsized("ek dabba tea", "tea", 1, instruction = "ek dabba tea 250 g")
        }
        assertTrue(cropped.isFailure)
    }

    @Test
    fun unsizedReceiptRequiresCompletePrefixAndSuffixContextOwnership() {
        val source = "2 dabba Nescafe"
        val unownedContext = listOf(
            "500 gram wale 2 dabba Nescafe",
            "bade 2 dabba Nescafe",
            "2 dabba Nescafe, 500 g wala",
            "2 dabba Nescafe aur 1 kg wala",
            "sugar-free 2 dabba Nescafe",
            "2 dabba Nescafe without sugar",
            "2 dabba Nescafe, 2 dabba Nescafe",
        )
        unownedContext.forEach { instruction ->
            assertTrue("unowned context must not be cropped: $instruction", runCatching {
                prepareUnsized(source, "Nescafe", 2, instruction = instruction)
            }.isFailure)
        }
    }

    @Test
    fun unsizedReceiptAllowsFillerAndSizeOwnedByNeighboringRow() {
        val instruction = "please 2 dabba Nescafe, 1 kg atta thanks"
        val nescafeRow = """{"name":"Nescafe","quantity":2,"quantity_unit":"count","pack_value":null,"pack_unit":"unspecified","required":[],"excluded":[],"source_text":"2 dabba Nescafe","needs_clarification":false}"""
        val attaRow = """{"name":"atta","quantity":1,"quantity_unit":"kg","pack_value":null,"pack_unit":"unspecified","required":[],"excluded":[],"source_text":"1 kg atta","needs_clarification":false}"""
        val body = """{"accepted":true,"draft":{"contract_version":"grocery-intent-v1","requires_review":true,"instruction":${jsonString(instruction)},"items":[$nescafeRow,$attaRow],"model_needs_clarification":false},"model":"fixture","promptVersion":"fixture","latencyMs":0,"usage":{}}"""
        val prepared = GroceryIntentResponse.parse(body, instruction).draft.prepareForReview()
        assertTrue(prepared.items[0].rawText.contains("Nescafe"))
        assertTrue(prepared.items[1].rawText.contains("atta"))
        assertEquals(null, prepared.items[0].validGenericContainerReceipt()?.physicalSize)
        assertEquals(Quantity.Weight(1000), prepared.items[1].quantity)

        val polite = prepareUnsized("2 dabba Nescafe", "Nescafe", 2,
            instruction = "please 2 dabba Nescafe, thanks")
        assertNotNull(polite.validGenericContainerReceipt())
    }

    @Test
    fun unsizedNominalRetainsModelOmittedRequiredQualifierAndExcludedOrCorrectedTextStaysHeld() {
        val source = "2 dabbe sugar-free biscuits"
        val item = prepareUnsized(source, "biscuits", 2, required = listOf("sugar_free"))
        val receipt = requireNotNull(item.validGenericContainerReceipt())
        assertEquals("sugar-free biscuits", source.substring(receipt.identity))
        val query = item.query.lowercase().replace('_', '-')
        assertTrue(query.contains("sugar"))
        assertTrue(query.contains("free"))

        assertTrue(runCatching {
            prepareUnsized("1 dabba tea", "tea", 1, excluded = listOf("sugar"))
        }.isFailure)
        assertTrue(runCatching {
            prepareUnsized("1 dabba tea, actually 2 dabbe tea", "tea", 1)
        }.isFailure)
    }

    @Test
    fun terminalTeaBagNominalUsesOnlySourceOwnedPieceEvidenceAsRetailProposal() {
        val item = prepareUnsized("2 dabbe tea bags", "tea bags", 2)
        val receipt = requireNotNull(item.validGenericContainerReceipt())
        assertEquals("tea bags", item.rawText.substring(receipt.identity))
        assertTrue(receipt.requestedSize is GenericContainerRequestedSize.Unspecified)

        val piecesCandidate = candidateWithFields(
            "tea bags", "Tea Bags", CatalogueQuantityFields(variantName = "25 pieces"),
        )
        val assessed = assessSwiggyIntentQuantity(item, Recommendations(listOf(piecesCandidate)))
        val result = resolveSwiggyCartQuantity(assessed, piecesCandidate)
        assertTrue(result is SwiggyCartQuantityResolution.RetailProposal)
        assertEquals(2, (result as SwiggyCartQuantityResolution.RetailProposal).cartUnits)
        assertTrue(runCatching { swiggyRequestedCartQuantity(assessed, piecesCandidate) }.isFailure)

        val looseTea = candidateWithFields(
            "tea bags", "Tea 250 g", CatalogueQuantityFields(variantName = "250 g"),
        )
        val looseTeaAssessed = assessSwiggyIntentQuantity(item, Recommendations(listOf(looseTea)))
        assertTrue(resolveSwiggyCartQuantity(looseTeaAssessed, looseTea) is SwiggyCartQuantityResolution.Held)
    }

    @Test
    fun actualBoxContentsAndTitleCanSupportTeaBagProposalWithoutDiscardingSourceNominal() {
        val item = prepareUnsized("2 dabbe tea bags", "tea bags", 2)
        val boxedTea = candidateWithFields(
            "tea bags", "Tea Bags", CatalogueQuantityFields(quantityDescription = "1 box of 25 pieces"),
        )
        val assessed = assessSwiggyIntentQuantity(item, Recommendations(listOf(boxedTea)))
        val result = resolveSwiggyCartQuantity(assessed, boxedTea)
        assertTrue(result is SwiggyCartQuantityResolution.RetailProposal)
        assertEquals(2, (result as SwiggyCartQuantityResolution.RetailProposal).cartUnits)

        val sourceNominal = requireNotNull(item.validGenericContainerReceipt()).let {
            item.rawText.substring(it.identity)
        }
        val contents = requireNotNull(swiggyProvenRetailOfferContents(boxedTea, sourceNominal))
        assertEquals(GroceryPackDimension.PIECES, contents.measure.dimension)
        assertEquals("25", contents.measure.baseValue.stripTrailingZeros().toPlainString())
        assertEquals("1 box of 25 pieces", contents.catalogue.fields.quantityDescription)
    }

    @Test
    fun genericOfferConsentBindsProvenContentsAndInvalidatesChangedContentsSizeAndSource() {
        val item = prepareUnsized("2 dabbe tea bags", "tea bags", 2)
        val boxedTea = candidateWithFields(
            "tea bags", "Tea Bags", CatalogueQuantityFields(quantityDescription = "1 box of 25 pieces"),
        )
        val assessed = assessSwiggyIntentQuantity(item, Recommendations(listOf(boxedTea)))
        val binding = requireNotNull(swiggyQuantityProposalBinding(0, assessed, boxedTea))
        val contents = binding.evidence.contents as? SwiggyRetailOfferContents.Proven
        assertNotNull(contents)
        assertEquals(GroceryPackDimension.PIECES, contents?.measure?.dimension)
        assertEquals("1 box of 25 pieces", contents?.catalogue?.fields?.quantityDescription)

        assertInvalidated(binding, binding.copy(source = "changed source"))
        val proven = requireNotNull(contents)
        val changedContents = proven.copy(measure = GroceryPackMeasure.of(java.math.BigDecimal("24"), "pieces"))
        val changedEvidence = SwiggyQuantityProposalEvidence.snapshot(
            assessed, boxedTea, binding.evidence.resolverVersion, proven.display,
            contents = changedContents.copy(display = proven.display),
        )
        assertInvalidated(binding, binding.copy(evidence = changedEvidence))

        val changedSize = SwiggyQuantityProposalEvidence.snapshot(
            assessed, boxedTea, binding.evidence.resolverVersion, "24 pieces",
            contents = changedContents.copy(display = "24 pieces"),
        )
        assertInvalidated(binding, binding.copy(evidence = changedSize))
    }

    @Test
    fun explicitPhysicalSizeDoesNotCrossMatchPieceOffersAndNoisySkuClaimsStayHeld() {
        val physicalRequest = prepare(Example("1 dabba tea bags 250 g", "tea bags", 1, "count", 250, "g",
            "Tea Bags 250 g"))
        val pieceOffer = candidateWithFields(
            "tea bags", "Tea Bags", CatalogueQuantityFields(variantName = "25 pieces"),
        )
        val physicalAssessed = assessSwiggyIntentQuantity(physicalRequest, Recommendations(listOf(pieceOffer)))
        assertTrue(resolveSwiggyCartQuantity(physicalAssessed, pieceOffer) is SwiggyCartQuantityResolution.Held)

        val unsized = prepareUnsized("2 dabbe tea bags", "tea bags", 2)
        for (noisy in listOf(
            candidateWithFields("tea bags", "Tea Bags 2 boxes", CatalogueQuantityFields(variantName = "25 pieces")),
            candidateWithFields("tea bags", "Tea Bags 25 pieces 500 g", CatalogueQuantityFields(variantName = "25 pieces")),
        )) {
            val assessed = assessSwiggyIntentQuantity(unsized, Recommendations(listOf(noisy)))
            assertTrue(resolveSwiggyCartQuantity(assessed, noisy) is SwiggyCartQuantityResolution.Held)
        }
    }

    @Test
    fun absentWrongBundledQualifiedAndMismatchedCatalogueFactsStayHeld() {
        val example = Example("Ek dabba Amul butter le aao, 100 g", "Amul butter", 1, "count", 100, "g",
            "Amul butter 100 g")
        val item = prepare(example)
        val candidates = listOf(
            candidate(example.name, example.catalogueLabel, example.sizeText, includeEvidence = false),
            candidate(example.name, example.catalogueLabel, example.sizeText, evidenceSku = "different-sku"),
            candidate(example.name, "Amul butter 2 x 100 g", example.sizeText),
            candidate(example.name, "Amul butter approximately 100 g", example.sizeText),
            candidate(example.name, example.catalogueLabel, "approximately 100 g"),
            candidate(example.name, "Amul butter 200 g", "200 g"),
        )
        candidates.forEachIndexed { index, candidate ->
            val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
            val result = resolveSwiggyCartQuantity(checked, candidate)
            assertTrue("invalid catalogue case $index must be held", result is SwiggyCartQuantityResolution.Held)
        }
    }

    @Test
    fun removedOrMutatedGenericReceiptCannotBeReassessedAsAnExactQuantity() {
        val example = Example("Ek dabba Amul butter le aao, 100 g", "Amul butter", 1, "count", 100, "g",
            "Amul butter 100 g")
        val original = prepare(example)
        val candidate = candidate(example.name, example.catalogueLabel, example.sizeText)
        val proof = requireNotNull(original.intentQuantityProvenance)
        val receipt = requireNotNull(proof.genericContainerReceipt)
        val exactSize = requireNotNull(receipt.size)
        val exactRequestedSize = receipt.requestedSize as GenericContainerRequestedSize.Exact
        val mutations = listOf(
            original.copy(intentQuantityProvenance = proof.copy(
                genericContainerReceipt = null, sourceCountRole = null,
            )),
            original.copy(intentQuantityProvenance = proof.copy(
                genericContainerReceipt = receipt.copy(wrapper = receipt.wrapper.first + 1..receipt.wrapper.last),
            )),
            original.copy(intentQuantityProvenance = proof.copy(
                genericContainerReceipt = receipt.copy(requestedSize = exactRequestedSize.copy(
                    amount = exactSize.copy(value = java.math.BigDecimal("200")),
                )),
            )),
            original.copy(intentQuantityProvenance = proof.copy(
                genericContainerReceipt = receipt.copy(
                    requestedSize = GenericContainerRequestedSize.Unspecified,
                ),
            )),
            original.copy(intentQuantityProvenance = proof.copy(
                unitSize = proof.unitSize?.copy(value = java.math.BigDecimal("200")),
            )),
        )
        mutations.forEachIndexed { index, mutated ->
            val reassessed = assessSwiggyIntentQuantity(mutated, Recommendations(listOf(candidate)))
            assertTrue("mutated proof $index must remain held",
                resolveSwiggyCartQuantity(reassessed, candidate) is SwiggyCartQuantityResolution.Held)
        }
    }

    @Test
    fun rewrittenQueryAndReceiptClaimsCannotChangeSourceProductIdentity() {
        val example = Example("1 dabba butter 100 g", "butter", 1, "count", 100, "g", "Ghee 100 g")
        val original = prepare(example)
        val proof = requireNotNull(original.intentQuantityProvenance)
        val rewritten = original.copy(
            query = "ghee 100 g",
            strictMatchPhrase = "ghee 100 g",
            intentQuantityProvenance = proof.copy(
                query = "ghee 100 g",
                strictMatchPhrase = "ghee 100 g",
            ),
        )
        val gheeSku = candidate("ghee", "Ghee 100 g", "100 g")
        val reassessed = assessSwiggyIntentQuantity(rewritten, Recommendations(listOf(gheeSku)))
        assertTrue(resolveSwiggyCartQuantity(reassessed, gheeSku) is SwiggyCartQuantityResolution.Held)
    }

    @Test
    fun requiredTagsMustBeLiteralInGenericWrapperProductName() {
        val example = Example(
            "1 dabba unsalted butter 100 g", "unsalted butter", 1, "count", 100, "g", "Unsalted Butter 100 g",
        )
        val literalTagItem = prepare(example, required = listOf("unsalted"))
        assertEquals(IntentSourceCountRole.GENERIC_WRAPPERS,
            literalTagItem.intentQuantityProvenance?.sourceCountRole)
        assertNotNull(literalTagItem.intentQuantityProvenance?.genericContainerReceipt)

        val inventedTag = runCatching { prepare(example, required = listOf("organic")) }
        assertTrue(inventedTag.isFailure)
    }

    @Test
    fun quantityProposalAcknowledgementIsBoundToSourceSizeCandidateAddressAndPlan() {
        val example = Example("Ek dabba Amul butter le aao, 100 g", "Amul butter", 1, "count", 100, "g",
            "Amul butter 100 g")
        val item = prepare(example)
        val candidate = candidate(example.name, example.catalogueLabel, example.sizeText)
        val assessed = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
        val binding = requireNotNull(swiggyQuantityProposalBinding(0, assessed, candidate))
        assertFalse(binding.toString().contains(example.source))

        assertInvalidated(binding, binding.copy(source = "changed source"))
        val changedSizeEvidence = SwiggyQuantityProposalEvidence.snapshot(
            assessed, candidate, "retail-offer-v1", "200 g",
        )
        assertInvalidated(binding, binding.copy(evidence = changedSizeEvidence))

        val changedCandidate = candidate.copy(
            spinId = "spin-changed",
            skuId = "sku-changed",
            catalogueQuantity = requireNotNull(candidate.catalogueQuantity).copy(
                spinId = "spin-changed", skuId = "sku-changed",
            ),
        )
        val changedCandidateEvidence = SwiggyQuantityProposalEvidence.snapshot(
            assessed, changedCandidate, "retail-offer-v1", "100 g",
        )
        assertInvalidated(binding, binding.copy(
            spinId = changedCandidate.spinId,
            skuId = changedCandidate.skuId,
            evidence = changedCandidateEvidence,
        ))

        assertInvalidated(binding, binding, currentAddress = "address-changed")
        assertInvalidated(binding, binding, currentPlan = "plan-changed")
    }

    private data class Example(
        val source: String,
        val name: String,
        val count: Int,
        val quantityUnit: String,
        val size: Int,
        val sizeUnit: String,
        val catalogueLabel: String,
    ) {
        val sizeText: String get() = "$size $sizeUnit"
    }

    private fun prepare(
        example: Example,
        required: List<String> = emptyList(),
    ): com.example.beta.automation.ParsedItem {
        val body = responseBody(example, required)
        return GroceryIntentResponse.parse(body, example.source).draft.prepareForReview().items.single()
    }

    private fun prepareUnsized(
        source: String,
        name: String,
        count: Int,
        quantityUnit: String = "count",
        required: List<String> = emptyList(),
        excluded: List<String> = emptyList(),
        instruction: String = source,
    ): com.example.beta.automation.ParsedItem {
        val requiredJson = required.joinToString(",", "[", "]", transform = ::jsonString)
        val excludedJson = excluded.joinToString(",", "[", "]", transform = ::jsonString)
        val body = """{"accepted":true,"draft":{"contract_version":"grocery-intent-v1","requires_review":true,"instruction":${jsonString(instruction)},"items":[{"name":${jsonString(name)},"quantity":$count,"quantity_unit":${jsonString(quantityUnit)},"pack_value":null,"pack_unit":"unspecified","required":$requiredJson,"excluded":$excludedJson,"source_text":${jsonString(source)},"needs_clarification":false}],"model_needs_clarification":false},"model":"fixture","promptVersion":"fixture","latencyMs":0,"usage":{}}"""
        return GroceryIntentResponse.parse(body, instruction).draft.prepareForReview().items.single()
    }

    private fun candidate(
        name: String,
        label: String,
        sizeText: String,
        includeEvidence: Boolean = true,
        evidenceSku: String = "sku-$name",
    ): RecommendationCandidate {
        val spinId = "spin-$name"
        val skuId = "sku-$name"
        val evidence = if (includeEvidence) CatalogueQuantityEvidence(
            version = 1,
            source = "search_variation",
            spinId = spinId,
            skuId = evidenceSku,
            fields = CatalogueQuantityFields(variantName = sizeText),
        ) else null
        return RecommendationCandidate(
            spinId = spinId,
            label = label,
            skuId = skuId,
            catalogueQuantity = evidence,
        )
    }

    private fun candidateWithFields(
        name: String,
        label: String,
        fields: CatalogueQuantityFields,
    ): RecommendationCandidate {
        val base = candidate(name, label, "", includeEvidence = true)
        return base.copy(catalogueQuantity = requireNotNull(base.catalogueQuantity).copy(fields = fields))
    }

    private fun assertInvalidated(
        original: SwiggyQuantityProposalBinding,
        current: SwiggyQuantityProposalBinding,
        currentAddress: String = "address-1",
        currentPlan: String = "plan-1",
    ) {
        val gate = SwiggyQuantityProposalGate()
        gate.prepare(1L, "address-1", "plan-1", listOf(original))
        assertTrue(gate.acknowledge(1L, "address-1", "plan-1", listOf(original), checked = true))
        assertFalse(gate.canApply(1L, currentAddress, currentPlan, listOf(current)))
    }

    private fun responseBody(example: Example, required: List<String>): String =
        """{"accepted":true,"draft":{"contract_version":"grocery-intent-v1","requires_review":true,"instruction":${jsonString(example.source)},"items":[{"name":${jsonString(example.name)},"quantity":${example.count},"quantity_unit":${jsonString(example.quantityUnit)},"pack_value":${example.size},"pack_unit":${jsonString(example.sizeUnit)},"required":${required.joinToString(",", "[", "]", transform = ::jsonString)},"excluded":[],"source_text":${jsonString(example.source)},"needs_clarification":false}],"model_needs_clarification":false},"model":"fixture","promptVersion":"fixture","latencyMs":0,"usage":{}}"""

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }
}
