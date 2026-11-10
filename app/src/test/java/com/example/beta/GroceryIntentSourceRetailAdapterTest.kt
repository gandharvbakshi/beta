package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.SwiggyMcpClient.Recommendations
import com.example.beta.automation.IntentSourceCountRole
import com.example.beta.automation.Quantity
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentSourceRetailAdapterTest {
    @Test
    fun nonrepresentableSourceSizesHoldWithoutRoundingOrThrowingInModelOffAssessment() {
        for ((source, name, label) in listOf(
            Triple("one 0.5 g jar of saffron", "saffron", "Saffron Jar 1 g"),
            Triple("one 0.5 ml bottle of oil", "oil", "Oil Bottle 1 ml"),
            Triple("one 0.0005 kg packet of spice", "spice", "Spice Packet 1 g"),
        )) {
            val receipt = requireNotNull(sourceRetailCountReceipt(source))
            assertFalse(receipt.hasRepresentableSize())
            val preparation = runCatching { prepare(source, name) }
            assertTrue(source, preparation.exceptionOrNull() is IntentNeedsReviewException)
            val old = com.example.beta.automation.ParsedItem(source, name, Quantity.Count(1))
            val candidate = sku(label, "rounded-size")
            val checked = assessSwiggyIntentQuantity(old, Recommendations(listOf(candidate)))
            assertTrue(source, resolveSwiggyCartQuantity(checked, candidate) is SwiggyCartQuantityResolution.Held)
            assertEquals(Quantity.Count(1), checked.quantity)
        }
        assertTrue(requireNotNull(sourceRetailCountReceipt("one 0.5 kg packet of rice")).hasRepresentableSize())
    }

    @Test
    fun literalFormNounsAndRelativeSizeRemainMandatoryWithoutInventingInnerCounts() {
        val small = prepare("a small pack of cheese slices", "cheese slices")
        val titleOnlySmallPack = sku("Cheese Slices Small Pack 200 g", "title-only-qualified-small-pack")
        assertTrue(resolveSwiggyCartQuantity(
            assessSwiggyIntentQuantity(small, Recommendations(listOf(titleOnlySmallPack))), titleOnlySmallPack,
        ) is SwiggyCartQuantityResolution.Held)
        val smallCandidate = skuWithRetailUnit("Cheese Slices Small Pack 1 pack 200 g", "qualified-small-pack", "pack", "200 g")
        val checkedSmall = assessSwiggyIntentQuantity(small, Recommendations(listOf(smallCandidate)))
        assertEquals(SwiggyCartQuantityResolution.Exact(1), resolveSwiggyCartQuantity(checkedSmall, smallCandidate))

        for ((source, identity, label, contents) in listOf(
            listOf("one packet of paper sheets", "paper sheets", "Paper Sheets Packet", "50 pieces"),
            listOf("one pack of serving spoons", "serving spoons", "Serving Spoons Pack", "6 pieces"),
        )) {
            val item = prepare(source, identity)
            assertEquals(Quantity.Count(1), item.quantity)
            assertTrue(item.query.contains(identity))
            assertEquals(item.query, item.strictMatchPhrase)
            assertEquals(null, item.intentQuantityProvenance?.unitSize)
            assertEquals(null, item.containerContentsProof)

            val titleOnly = sku(label, "title-only-${identity.replace(' ', '-')}")
            val checkedTitleOnly = assessSwiggyIntentQuantity(item, Recommendations(listOf(titleOnly)))
            assertTrue(source, resolveSwiggyCartQuantity(checkedTitleOnly, titleOnly) is SwiggyCartQuantityResolution.Held)

            val typed = skuWithCatalogueContents(label, "typed-${identity.replace(' ', '-')}", contents)
            val receipt = requireNotNull(sourcePacketOfferSyntax(item.rawText))
            assertTrue(item.packetOfferProofMatches(receipt))
            assertEquals(identity, item.rawText.substring(receipt.identity))
            assertReviewedRetailProposal(assessSwiggyIntentQuantity(item, Recommendations(listOf(typed))), typed, 1)
        }

        val fishHeads = prepare("one pack of fish heads", "fish heads")
        val fishHeads500g = skuWithCatalogueContents("Fish Heads Pack 500 g", "fish-heads-500g", "500 g")
        assertReviewedRetailProposal(
            assessSwiggyIntentQuantity(fishHeads, Recommendations(listOf(fishHeads500g))), fishHeads500g, 1,
        )
        for (label in listOf("Cheese Slices Pack 200 g", "Cheese Small Pack 200 g", "Cheese Slices Large Pack 500 g")) {
            val candidate = skuWithCatalogueContents(label, "missing-literal-form", "200 g")
            val assessed = assessSwiggyIntentQuantity(small, Recommendations(listOf(candidate)))
            assertTrue(label, resolveSwiggyCartQuantity(assessed, candidate) is SwiggyCartQuantityResolution.Held)
        }
    }

    @Test
    fun modelUnitLabelsShareSourceOwnedRetailCountConversionAndConcreteSkuPath() {
        val source = "one jar of rice"
        for (unit in listOf("count", "pack", "piece")) {
            val item = prepare(source, "rice", quantityUnit = unit)
            assertEquals(Quantity.Count(1), item.quantity)
            assertTrue(item.retailPackCount)
            assertFalse(item.logicalPieceCount)
            assertTrue(item.requiresIntentQuantityProof)
            assertEquals(IntentSourceCountRole.NAMED_RETAIL,
                item.intentQuantityProvenance?.sourceCountRole)
            assertNotNull(item.validNamedRetailReceipt())

            val titleOnly = sku("Rice Jar 500 g", "rice-title-only-$unit")
            assertTrue("unit=$unit title-only SKU", resolveSwiggyCartQuantity(
                assessSwiggyIntentQuantity(item, Recommendations(listOf(titleOnly))), titleOnly,
            ) is SwiggyCartQuantityResolution.Held)
            val candidate = skuWithRetailUnit("Rice Jar 1 jar 500 g", "rice-$unit", "jar", "500 g")
            val assessed = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
            assertTrue("unit=$unit", isSwiggyCandidateAllowed(assessed, candidate))
            assertEquals(SwiggyCartQuantityResolution.Exact(1), resolveSwiggyCartQuantity(assessed, candidate))
        }
    }

    @Test
    fun sourceIdentityContainerAndQualifierBuildThePreparedQuery() {
        val examples = listOf(
            Example("one bottle of water", "water", 1, "bottle"),
            Example("one loaf of bread", "bread", 1, "loaf"),
            Example("one small pack of cheese", "cheese", 1, "small pack", required = listOf("small")),
        )
        for (example in examples) {
            val item = prepare(example.source, example.name, example.quantity, required = example.required)
            assertEquals(Quantity.Count(example.quantity), item.quantity)
            assertTrue(item.retailPackCount)
            assertFalse(item.logicalPieceCount)
            val query = item.query.lowercase()
            assertTrue("${example.source} -> $query", query.contains(example.name))
            example.packaging.split(' ').forEach { form ->
                assertTrue("${example.source} missing $form in $query", query.contains(form))
            }
            if ("small" in example.packaging) {
                assertTrue(query.contains("small"))
                assertFalse(query.contains("g"))
                assertFalse(query.contains("ml"))
            }

            val titleOnly = sku("${item.query} 500 g", "title-only-${example.name}")
            assertTrue("${example.source} title-only SKU", resolveSwiggyCartQuantity(
                assessSwiggyIntentQuantity(item, Recommendations(listOf(titleOnly))), titleOnly,
            ) is SwiggyCartQuantityResolution.Held)
            val unit = example.packaging.substringAfterLast(' ')
            val candidate = skuWithRetailUnit("${item.query} 1 $unit 500 g", "sku-${example.name}", unit, "500 g")
            val assessed = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
            assertTrue("${example.source}", isSwiggyCandidateAllowed(assessed, candidate))
            assertEquals(SwiggyCartQuantityResolution.Exact(example.quantity),
                resolveSwiggyCartQuantity(assessed, candidate))
        }

        val genericPacket = prepare("2 packets of sweetcorn", "sweetcorn", quantity = 2)
        val genericReceipt = requireNotNull(sourcePacketOfferSyntax(genericPacket.rawText))
        assertTrue(genericPacket.packetOfferProofMatches(genericReceipt))
        assertEquals("sweetcorn", genericPacket.rawText.substring(genericReceipt.identity))
        assertTrue(genericPacket.query.contains("sweetcorn"))
        val sweetcornOffer = skuWithCatalogueContents("Sweetcorn 500 g", "sweetcorn-offer", "500 g")
        assertReviewedRetailProposal(
            assessSwiggyIntentQuantity(genericPacket, Recommendations(listOf(sweetcornOffer))), sweetcornOffer, 2,
        )
    }

    @Test
    fun omittedModelSizeIsRecoveredOnlyWhenItIsLiteralInSource() {
        val source = "one 500 ml bottle of milk"
        val item = prepare(source, "milk", quantityUnit = "count")
        assertTrue(item.query.contains("500 ml"))
        val proof = requireNotNull(item.intentQuantityProvenance)
        assertEquals(0, requireNotNull(proof.unitSize).value.compareTo(BigDecimal("500")))
        assertEquals("ml", proof.unitSize?.unit)

        val noSizeSource = "one small packet of milk"
        val small = prepare(noSizeSource, "milk", quantityUnit = "count", required = listOf("small"))
        assertTrue(small.query.contains("small"))
        assertFalse(small.query.contains(Regex("\\b\\d+\\s*(?:g|kg|ml|l)\\b", RegexOption.IGNORE_CASE)))
        assertEquals(null, small.intentQuantityProvenance?.unitSize)

        val equivalentSource = "one 1 l bottle of milk"
        val equivalent = prepare(equivalentSource, "milk", quantityUnit = "pack",
            packValue = "1000", packUnit = "ml")
        val size = requireNotNull(equivalent.intentQuantityProvenance?.unitSize)
        assertEquals(0, size.value.multiply(BigDecimal("1000")).compareTo(BigDecimal("1000")))
        assertEquals("l", size.unit)
        val titleOnlyCandidate = sku("Milk Bottle 1 l", "milk-1l-title-only")
        assertTrue(resolveSwiggyCartQuantity(
            assessSwiggyIntentQuantity(equivalent, Recommendations(listOf(titleOnlyCandidate))), titleOnlyCandidate,
        ) is SwiggyCartQuantityResolution.Held)
        val candidate = skuWithRetailUnit("Milk Bottle 1 bottle 1 l", "milk-1l", "bottle", "1 l")
        val checked = assessSwiggyIntentQuantity(equivalent, Recommendations(listOf(candidate)))
        assertEquals(SwiggyCartQuantityResolution.Exact(1), resolveSwiggyCartQuantity(checked, candidate))

        assertTrue(runCatching {
            prepare(equivalentSource, "milk", quantityUnit = "pack", packValue = "900", packUnit = "ml")
        }.isFailure)
        assertTrue(runCatching {
            prepare(equivalentSource, "milk", quantityUnit = "pack", packValue = "1", packUnit = "kg")
        }.isFailure)
    }

    @Test
    fun modelIdentityCannotReplaceTheSourceIdentity() {
        val wrongProduct = runCatching { prepare("one packet of rice", "wheat", quantityUnit = "pack") }
        assertTrue(wrongProduct.isFailure)
    }

    @Test
    fun wrongContainerFrozenLooseAndMultipackSkusStayHeld() {
        val source = "one can of sweet corn"
        val item = prepare(source, "sweet corn", quantityUnit = "pack")
        val wrong = listOf(
            sku("Sweet Corn Bottle 500 g", "wrong-container"),
            sku("Frozen Sweet Corn Packet 500 g", "frozen-wrong-container"),
            sku("Loose Sweet Corn", "loose-wrong-container"),
            sku("Sweet Corn Can 2 x 500 g", "multipack"),
        )
        val outcomes = wrong.map { candidate ->
            val assessed = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
            candidate.label to resolveSwiggyCartQuantity(assessed, candidate)
        }
        val failures = outcomes.filterNot { it.second is SwiggyCartQuantityResolution.Held }
        assertTrue("Expected nonmatching product forms to be held: $failures", failures.isEmpty())
    }

    @Test
    fun unsupportedOrUnownedCountContainerGrammarProducesNoReceipt() {
        val negatives = listOf(
            "2 garbage bags", "one bottle gourd", "one can opener", "one cup of rice",
            "one spray of cleaner", "not one packet of rice", "one packet of rice, actually two",
            "one refill pouch of shampoo",
        )
        for (source in negatives) {
            assertEquals(source, null, sourceRetailCountReceipt(source))
        }
    }

    @Test
    fun rewritingReceiptMarkerFlagsCountOrBothQueriesCannotUpgradeOnReassessment() {
        val item = prepare("one jar of rice", "rice", quantityUnit = "pack")
        val candidate = skuWithRetailUnit("Rice Jar 500 g", "rice-sku", "jar", "500 g")
        assertEquals(SwiggyCartQuantityResolution.Exact(1), resolveSwiggyCartQuantity(
            assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate))), candidate,
        ))
        val proof = requireNotNull(item.intentQuantityProvenance)
        val local = requireNotNull(proof.local)
        val mutations = listOf(
            item.copy(
                retailPackCount = false,
                requiresIntentQuantityProof = false,
                intentQuantityProvenance = proof.copy(
                    retailPackCount = false, retailContainer = null, sourceCountRole = null,
                ),
            ),
            item.copy(
                quantity = Quantity.Count(2),
                intentQuantityProvenance = proof.copy(
                    local = local.copy(atom = "two", value = BigDecimal("2")),
                    resolvedQuantity = Quantity.Count(2),
                ),
            ),
            item.copy(
                query = "lentils packet", strictMatchPhrase = "lentils packet",
                intentQuantityProvenance = proof.copy(
                    query = "lentils packet", strictMatchPhrase = "lentils packet",
                ),
            ),
            item.copy(
                strictMatchPhrase = null,
                intentQuantityProvenance = proof.copy(strictMatchPhrase = null),
            ),
            item.copy(
                intentQuantityProvenance = null,
                intentQuantityCatalogueCheck = null,
                retailPackCount = false,
                logicalPieceCount = false,
                requiresIntentQuantityProof = false,
                query = "lentils packet", strictMatchPhrase = "lentils packet",
            ),
            item.copy(
                intentQuantityProvenance = null,
                intentQuantityCatalogueCheck = null,
                retailPackCount = false,
                logicalPieceCount = false,
                requiresIntentQuantityProof = false,
                quantity = Quantity.Count(2),
            ),
        )
        for ((index, changed) in mutations.withIndex()) {
            val checked = assessSwiggyIntentQuantity(changed, Recommendations(listOf(candidate)))
            assertTrue("mutation $index", resolveSwiggyCartQuantity(checked, candidate) is SwiggyCartQuantityResolution.Held)
        }

        val weakenedRole = item.copy(
            strictMatchPhrase = "rice",
            intentQuantityProvenance = proof.copy(
                sourceCountRole = null, strictMatchPhrase = "rice",
            ),
        )
        val pouch = sku("Rice Pouch 500 g", "rice-pouch")
        val reassessed = assessSwiggyIntentQuantity(weakenedRole, Recommendations(listOf(pouch)))
        assertTrue(resolveSwiggyCartQuantity(reassessed, pouch) is SwiggyCartQuantityResolution.Held)
    }

    @Test
    fun sourceOnlyLegacyItemsRegenerateOnlyTheirLiteralCountContainerReceipt() {
        val old = com.example.beta.automation.ParsedItem(
            "2 cans of sweet corn", "sweet corn", Quantity.Count(2),
        )
        val titleOnlyCan = sku("Sweet Corn Can 500 g", "sweet-corn-title-only")
        val titleOnlyChecked = assessSwiggyIntentQuantity(old, Recommendations(listOf(titleOnlyCan)))
        assertTrue(resolveSwiggyCartQuantity(titleOnlyChecked, titleOnlyCan) is SwiggyCartQuantityResolution.Held)
        val can = skuWithRetailUnit("Sweet Corn Can 1 can 500 g", "sweet-corn-can", "can", "500 g")
        val assessed = assessSwiggyIntentQuantity(old, Recommendations(listOf(can)))
        assertTrue(assessed.query.contains("can"))
        assertEquals(IntentSourceCountRole.NAMED_RETAIL,
            assessed.intentQuantityProvenance?.sourceCountRole)
        assertEquals(SwiggyCartQuantityResolution.Exact(2), resolveSwiggyCartQuantity(assessed, can))

        val pouch = sku("Sweet Corn Pouch 500 g", "sweet-corn-pouch")
        val wrongContainer = assessSwiggyIntentQuantity(old, Recommendations(listOf(pouch)))
        assertTrue(resolveSwiggyCartQuantity(wrongContainer, pouch) is SwiggyCartQuantityResolution.Held)

        val wrongProduct = old.copy(query = "wheat", strictMatchPhrase = "wheat")
        val wrongProductChecked = assessSwiggyIntentQuantity(wrongProduct, Recommendations(listOf(can)))
        assertTrue(resolveSwiggyCartQuantity(wrongProductChecked, can) is SwiggyCartQuantityResolution.Held)
    }

    @Test
    fun removingOnlyNamedRetailRoleCannotAuthorizePouchForSourceCan() {
        val source = "2 cans of sweet corn"
        val original = prepare(source, "sweet corn", quantity = 2, quantityUnit = "pack")
        val proof = requireNotNull(original.intentQuantityProvenance)
        val roleCleared = original.copy(
            intentQuantityProvenance = proof.copy(sourceCountRole = null),
        )
        assertEquals(original.query, roleCleared.query)
        assertEquals(original.strictMatchPhrase, roleCleared.strictMatchPhrase)

        val titleOnlyCan = sku("Sweet Corn Can 500 g", "role-cleared-can-title-only")
        val titleOnlyChecked = assessSwiggyIntentQuantity(roleCleared, Recommendations(listOf(titleOnlyCan)))
        assertTrue(resolveSwiggyCartQuantity(titleOnlyChecked, titleOnlyCan) is SwiggyCartQuantityResolution.Held)
        val can = skuWithRetailUnit("Sweet Corn Can 1 can 500 g", "role-cleared-can", "can", "500 g")
        val rehydrated = assessSwiggyIntentQuantity(roleCleared, Recommendations(listOf(can)))
        assertEquals(SwiggyCartQuantityResolution.Exact(2), resolveSwiggyCartQuantity(rehydrated, can))

        val pouch = sku("Sweet Corn Pouch 500 g", "role-cleared-pouch")
        val pouchChecked = assessSwiggyIntentQuantity(roleCleared, Recommendations(listOf(pouch)))
        assertTrue(resolveSwiggyCartQuantity(pouchChecked, pouch) is SwiggyCartQuantityResolution.Held)
    }

    private data class Example(
        val source: String,
        val name: String,
        val quantity: Int,
        val packaging: String,
        val required: List<String> = emptyList(),
    )

    private fun prepare(
        source: String,
        name: String,
        quantity: Int = 1,
        quantityUnit: String = "count",
        packValue: String? = null,
        packUnit: String = "unspecified",
        required: List<String> = emptyList(),
    ): com.example.beta.automation.ParsedItem = GroceryIntentResponse.parse(
        response(source, name, quantity, quantityUnit, packValue, packUnit, required), source,
    ).draft.prepareForReview().items.single()

    private fun sku(label: String, skuId: String) =
        RecommendationCandidate("spin-$skuId", label, skuId = skuId)

    private fun skuWithRetailUnit(label: String, skuId: String, unit: String, physicalSize: String? = null): RecommendationCandidate {
        val spinId = "spin-$skuId"
        return RecommendationCandidate(
            spinId = spinId,
            label = label,
            variant = physicalSize,
            skuId = skuId,
            catalogueQuantity = CatalogueQuantityEvidence(
                version = 1,
                source = "search_variation",
                spinId = spinId,
                skuId = skuId,
                fields = CatalogueQuantityFields(
                    variantName = "1 $unit",
                    packSize = physicalSize,
                ),
            ),
        )
    }

    private fun skuWithCatalogueContents(label: String, skuId: String, contents: String): RecommendationCandidate {
        val spinId = "spin-$skuId"
        return RecommendationCandidate(
            spinId = spinId,
            label = label,
            skuId = skuId,
            catalogueQuantity = CatalogueQuantityEvidence(
                version = 1,
                source = "search_variation",
                spinId = spinId,
                skuId = skuId,
                fields = CatalogueQuantityFields(variantName = contents),
            ),
        )
    }

    private fun response(
        source: String,
        name: String,
        quantity: Int,
        quantityUnit: String,
        packValue: String?,
        packUnit: String,
        required: List<String>,
    ): String = """{"accepted":true,"draft":{"contract_version":"grocery-intent-v1","requires_review":true,"instruction":${jsonString(source)},"items":[{"name":${jsonString(name)},"quantity":$quantity,"quantity_unit":${jsonString(quantityUnit)},"pack_value":${packValue ?: "null"},"pack_unit":${jsonString(packUnit)},"required":${required.joinToString(",", "[", "]", transform = ::jsonString)},"excluded":[],"source_text":${jsonString(source)},"needs_clarification":false}],"model_needs_clarification":false},"model":"fixture","promptVersion":"fixture","latencyMs":0,"usage":{}}"""

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
