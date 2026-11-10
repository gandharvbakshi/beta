package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.SwiggyMcpClient.Recommendations
import com.example.beta.automation.Quantity
import com.example.beta.automation.IntentLocalAmount
import com.example.beta.automation.IntentQuantityOrigin
import com.example.beta.automation.IntentQuantityProvenance
import com.example.beta.automation.IntentSourceCountRole
import java.io.File
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentRetailCountRoleTest {
    @Test
    fun afterContainerNutrientClaimsDoNotBecomePackSizes() {
        for (source in listOf("2 bars of 20 g protein", "3 packs of 5 g fibre", "2 bottles of 30 g protein",
            "2 bars of 20 g protein please", "2 bars of 20 g protein kindly")) {
            assertEquals(source, null, sourceRetailCountReceipt(source))
        }
        for (source in listOf("2 packs of 500 g protein powder", "2 bars of 100 g chocolate", "2 20 g bars of protein")) {
            assertTrue(source, sourceRetailCountReceipt(source) != null)
        }
    }

    @Test
    fun rejectedNutrientRoleStaysHeldThroughParserAndLegacyFallbacks() {
        for (source in listOf("2 bars of 20 g protein", "3 packs of 5 g fibre", "2 bottles of 30 g protein",
            "2 bars of 20 g protein please", "2 bars of 20 g protein kindly")) {
            val parsed = prepareSwiggyMcpItems(source) { null }
            assertTrue(source, parsed.isNotEmpty())
            assertTrue(source, parsed.all { it.quantitySignal != null || it.unprovenIdentityScope ||
                (it.requiresIntentQuantityProof && it.intentQuantityProvenance == null) })
            for (pack in listOf<BigDecimal?>(null, BigDecimal(if (source.startsWith("3")) "5" else if (source.contains("bottles")) "30" else "20"))) {
                val item = GroceryIntentItem(if (source.contains("fibre")) "fibre" else "protein",
                    BigDecimal(if (source.startsWith("3")) "3" else "2"), "count", pack,
                    if (pack == null) "unspecified" else "g", emptyList(), emptyList(), source, false)
                assertTrue(source, item.legacyNumericOwnershipIssue() != null)
            }
        }
    }

    @Test
    fun failedMeasuredContentsGrammarCannotSplitIntoIndependentPurchases() {
        for (source in listOf("2 bars of 20 g protein please", "2 bags of 100 g wheat flour please",
            "2 bags of 100 g wheat flour please, if cheap", "3 bottles of 30 g protein for gym",
            "2 cups of 100 g rice", "2 sprays of 30 ml perfume", "2 packets of 1 kg sugar")) {
            val parsed = prepareSwiggyMcpItems(source) { null }
            assertTrue(source, parsed.isNotEmpty())
            assertTrue(source, parsed.any { it.rawText.contains(" of ") && it.quantitySignal != null })
            assertTrue(source, parsed.filter { it.rawText.contains(" of ") }.all { it.quantitySignal != null ||
                it.unprovenIdentityScope || (it.requiresIntentQuantityProof && it.intentQuantityProvenance == null) })
        }
    }

    @Test
    fun aHeldRowCannotRetainAValidNamedRetailReceipt() {
        val item = prepareSwiggyMcpItems("two bags of 100 g wheat flour") { null }.single()
        assertTrue(item.validNamedRetailReceipt() != null)
        val candidate = RecommendationCandidate("flour", "Wheat Flour Bag", "100 g", skuId = "flour-bag")
        for (held in listOf(item.copy(quantitySignal = "review"), item.copy(unprovenIdentityScope = true))) {
            assertEquals(null, held.validNamedRetailReceipt())
            assertFalse(resolveSwiggyCartQuantity(held, candidate) is SwiggyCartQuantityResolution.Exact)
        }
    }

    @Test
    fun measuredRetailProjectionRetainsWholeInstructionObligations() {
        val source = "two bags of 100 g wheat flour"
        for (instruction in listOf("for each household, $source", "$source, if cheap", "$source, 3 bags",
            "at most, $source", "$source, total")) {
            val items = prepareSwiggyMcpItems(instruction) { null }
            assertTrue(instruction, items.none {
                it.validNamedRetailReceipt() != null && it.quantitySignal == null && !it.unprovenIdentityScope
            })
        }
    }

    @Test
    fun measuredRetailProjectionKeepsOrdinarySiblingItems() {
        val source = "two bags of 100 g wheat flour"
        for (instruction in listOf("3 apples, $source", "$source, 2 oranges")) {
            val items = prepareSwiggyMcpItems(instruction) { null }
            assertEquals(instruction, 2, items.size)
            assertTrue(instruction, items.any { it.validNamedRetailReceipt() != null && it.quantitySignal == null && !it.unprovenIdentityScope })
        }
    }

    @Test
    fun plainV2PieceRowRetainsItsGroundedQualifiersAtTheFinalCatalogueBoundary() {
        val source = "4 pieces organic eggs"
        val prepared = review(source, "eggs", packaging = null, quantityUnit = "piece",
            quantity = "4", quantityText = "4", required = listOf("organic")).prepareForReview()
        assertEquals(1, prepared.items.size)
        val item = prepared.items.single()
        val sku = RecommendationCandidate("organic-eggs", "Organic Eggs", "4 pieces", skuId = "organic-eggs-four",
            catalogueQuantity = CatalogueQuantityEvidence(1, "search_variation", "organic-eggs", "organic-eggs-four",
                CatalogueQuantityFields(variantName = "4 pieces")))
        val assessed = assessSwiggyIntentQuantity(item, Recommendations(listOf(sku)))
        assertEquals(item.query, SwiggyCartQuantityResolution.Exact(1), resolveSwiggyCartQuantity(assessed, sku))
    }

    @Test
    fun sourceRecoveryUsesTheExistingArticleAndNumberAtomGrammar() {
        for (atom in listOf("a", "an", "one", "1", "ek")) {
            val source = "$atom small pack of cheese slices"
            val receipt = requireNotNull(sourceRetailCountReceipt(source)) {
                "No receipt for $atom; bound=${sourceQuotedLocalAmount(source, atom, "count") != null}"
            }
            assertEquals(0, receipt.count.value.compareTo(BigDecimal.ONE))
            assertEquals(atom, receipt.count.atom)
            assertEquals("small", source.substring(receipt.qualifier!!))
            assertEquals("cheese slices small pack", receipt.literalQuery())
        }
    }

    @Test
    fun receiptRetainsOriginalContainerIdentityAndQualifierSpans() {
        val source = "one small packet of organic rice."
        val count = IntentLocalAmount(source, 0, 3, "one", BigDecimal.ONE, "count")
        val receipt = sourceRetailCountReceipt("packet", source, count, null)!!
        assertEquals("packet", source.substring(receipt.container))
        assertEquals("organic rice", source.substring(receipt.identity))
        assertEquals("small", source.substring(receipt.qualifier!!))
        assertEquals(count, receipt.count)
        assertEquals(null, receipt.size)
        assertEquals("SourceRetailCountReceipt(<redacted>)", receipt.toString())
    }

    @Test
    fun receiptKeepsSizeAsAnIndependentOriginalAmount() {
        val source = "one 500 g packet of rice"
        val count = IntentLocalAmount(source, 0, 3, "one", BigDecimal.ONE, "count")
        val size = IntentLocalAmount(source, 4, 7, "500", BigDecimal("500"), "g")
        val receipt = sourceRetailCountReceipt("packet", source, count, size)!!
        assertEquals("rice", source.substring(receipt.identity))
        assertEquals(size, receipt.size)
        assertEquals(null, receipt.qualifier)
        assertEquals(null, sourceRetailCountReceipt("packet", source, count, size.copy(value = BigDecimal("600"))))
        assertEquals(null, sourceRetailCountReceipt("packet", source, count.copy(value = BigDecimal("2")), size))
    }

    @Test
    fun containerThenContentsSizeReceiptRecoversThroughV1AndParserOff() {
        data class Case(val source: String, val nominal: String, val quantity: Int,
            val packaging: String, val size: BigDecimal, val sizeUnit: String)
        val cases = listOf(
            Case("two bags of 100 g wheat flour", "wheat flour", 2, "bag", BigDecimal("100"), "g"),
            Case("3 bottles of 500 ml water", "water", 3, "bottle", BigDecimal("500"), "ml"),
            Case("ek packet of 250 g tea", "tea", 1, "packet", BigDecimal("250"), "g"),
            Case("2 cartons of 1 litre milk", "milk", 2, "carton", BigDecimal("1"), "l"),
            Case("one box of 0.5 kg rice", "rice", 1, "box", BigDecimal("0.5"), "kg"),
        )
        cases.forEach { case ->
            val receipt = requireNotNull(sourceRetailCountReceipt(case.source)) { case.source }
            assertEquals(case.source.substring(receipt.identity), case.nominal)
            assertEquals(0, case.size.compareTo(requireNotNull(receipt.size).value))
            assertEquals(case.sizeUnit, receipt.size?.unit)

            val parserOff = prepareSwiggyMcpItems(case.source) { null }
            assertEquals("parser-off row count: ${case.source}", 1, parserOff.size)
            assertEquals("parser-off quantity: ${case.source}", Quantity.Count(case.quantity), parserOff.single().quantity)
            assertEquals(case.source, parserOff.single().rawText)
            assertTrue("parser-off source receipt missing", parserOff.single().validNamedRetailReceipt() != null)

            val v1Item = GroceryIntentItem(
                name = case.nominal, quantity = BigDecimal(case.quantity), quantityUnit = "count",
                packValue = case.size, packUnit = case.sizeUnit, required = emptyList(), excluded = emptyList(),
                sourceText = case.source, needsClarification = false,
            )
            val v1 = GroceryIntentDraft("grocery-intent-v1", true, case.source, listOf(v1Item), false)
                .prepareForReview()
            assertEquals("V1 row count: ${case.source}", 1, v1.items.size)
            assertEquals("V1 quantity: ${case.source}", Quantity.Count(case.quantity), v1.items.single().quantity)
            assertTrue("V1 source receipt missing: ${case.source}", v1.items.single().intentQuantityProvenance != null)
            assertTrue("V1 source receipt changed", v1.items.single().validNamedRetailReceipt() != null)
        }
    }

    @Test
    fun namedMeasuredContainersConserveOriginalRoleThroughV1AndFinalReview() {
        data class Case(val source: String, val name: String, val count: Int, val atom: String,
            val form: String, val size: String, val unit: String)
        val cases = listOf(
            Case("one 180 ml bottle of shampoo", "shampoo", 1, "one", "bottle", "180", "ml"),
            Case("one 1 litre bottle of floor cleaner", "floor cleaner", 1, "one", "bottle", "1", "l"),
            Case("two 100 ml bottles of rose water", "rose water", 2, "two", "bottle", "100", "ml"),
            Case("one 500 ml tube of Harpic", "Harpic", 1, "one", "tube", "500", "ml"),
            Case("one 250 g jar of face cream", "face cream", 1, "one", "jar", "250", "g"),
        )
        cases.forEach { case ->
            val receipt = requireNotNull(sourceNamedRetailCountReceipt(case.source)) { case.source }
            assertEquals(case.form, groceryIntentRetailContainerKey(case.source.substring(receipt.container)))
            assertEquals(case.atom, receipt.count.atom)
            assertEquals(case.count.toBigDecimal().compareTo(receipt.count.value), 0)
            assertEquals(case.form, receipt.packaging)
            assertEquals(case.name, case.source.substring(receipt.identity))
            assertEquals(case.size.toBigDecimal().compareTo(requireNotNull(receipt.size).value), 0)
            assertEquals(case.unit, receipt.size?.unit)

            val prepared = GroceryIntentDraft("grocery-intent-v1", true, case.source, listOf(
                GroceryIntentItem(case.name, case.count.toBigDecimal(), "count", case.size.toBigDecimal(), case.unit,
                    emptyList(), emptyList(), case.source, false),
            ), false).prepareForReview()
            val item = prepared.items.single()
            val proof = requireNotNull(item.validNamedRetailReceipt()) { "V1 proof: ${case.source}" }
            assertEquals(case.source, item.rawText)
            assertEquals(Quantity.Count(case.count), item.quantity)
            assertTrue(intentEvidenceTokens(item.query).containsAll(intentEvidenceTokens(case.name)))
            assertEquals(case.form, proof.packaging)
            assertEquals(case.size.toBigDecimal().compareTo(requireNotNull(proof.size).value), 0)
            assertEquals(case.unit, proof.size?.unit)
        }

        val case = cases.first()
        val item = GroceryIntentDraft("grocery-intent-v1", true, case.source, listOf(
            GroceryIntentItem(case.name, BigDecimal.ONE, "count", BigDecimal("180"), "ml",
                emptyList(), emptyList(), case.source, false),
        ), false).prepareForReview().items.single()
        val candidate = RecommendationCandidate(
            spinId = "shampoo-bottle", label = "Shampoo 1 bottle 180 ml", skuId = "shampoo-bottle-sku",
            catalogueQuantity = CatalogueQuantityEvidence(1, "search_variation", "shampoo-bottle", "shampoo-bottle-sku",
                CatalogueQuantityFields(variantName = "1 bottle", packSize = "180 ml")),
        )
        val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
        assertEquals(SwiggyCartQuantityResolution.Exact(1), resolveSwiggyCartQuantity(checked, candidate))
        assertTrue(SwiggyCartReviewRequest.build(listOf(checked), mapOf(0 to candidate), emptySet()) != null)

        val rejectedOffers = listOf(
            RecommendationCandidate("wrong-form", "Shampoo 1 tube", skuId = "wrong-form-sku",
                catalogueQuantity = CatalogueQuantityEvidence(1, "search_variation", "wrong-form", "wrong-form-sku",
                    CatalogueQuantityFields(variantName = "1 tube", packSize = "180 ml"))),
            RecommendationCandidate("wrong-size", "Shampoo 1 bottle 200 ml", skuId = "wrong-size-sku",
                catalogueQuantity = CatalogueQuantityEvidence(1, "search_variation", "wrong-size", "wrong-size-sku",
                    CatalogueQuantityFields(variantName = "1 bottle", packSize = "200 ml"))),
            RecommendationCandidate("untyped", "Shampoo bottle", skuId = "untyped-sku"),
        )
        rejectedOffers.forEach { offer ->
            val assessed = assessSwiggyIntentQuantity(item, Recommendations(listOf(offer)))
            assertFalse("${offer.spinId} incorrectly exact", resolveSwiggyCartQuantity(assessed, offer) is SwiggyCartQuantityResolution.Exact)
            assertNull("${offer.spinId} reached final review", SwiggyCartReviewRequest.build(listOf(assessed), mapOf(0 to offer), emptySet()))
        }
    }

    @Test
    fun productFirstPieceLabelsAndUnsafeRetailContextsRemainHeldInV1() {
        data class Case(val source: String, val name: String, val count: Int, val size: String, val unit: String)
        val productFirst = listOf(
            Case("shampoo ki 180 ml bottle ek", "shampoo", 1, "180", "ml"),
            Case("floor cleaner ki 1 litre bottle ek", "floor cleaner", 1, "1", "l"),
            Case("rose water ki do 100 ml bottles", "rose water", 2, "100", "ml"),
            Case("Harpic ki ek 500 ml bottle", "Harpic", 1, "500", "ml"),
        )
        productFirst.forEach { case ->
            val source = case.source
            assertNull("No beginning-count receipt is available for $source", sourceNamedRetailCountReceipt(source))
            val result = runCatching { GroceryIntentDraft("grocery-intent-v1", true, source, listOf(
                GroceryIntentItem(case.name, case.count.toBigDecimal(), "piece", case.size.toBigDecimal(), case.unit,
                    emptyList(), emptyList(), source, false),
            ), false).prepareForReview() }
            if (result.isFailure) {
                assertTrue(result.exceptionOrNull() is IntentNeedsReviewException)
            } else {
                val prepared = result.getOrThrow()
                if (prepared.items.isEmpty()) assertTrue(prepared.unresolvedText.isNotEmpty())
                assertTrue("Product-first V1 emitted multiple rows", prepared.items.size <= 1)
                val item = prepared.items.singleOrNull() ?: return@forEach
                assertNull(item.validNamedRetailReceipt())
                val candidate = RecommendationCandidate("product-first", "${case.name} 1 bottle ${case.size} ${case.unit}", skuId = "product-first-sku",
                    catalogueQuantity = CatalogueQuantityEvidence(1, "search_variation", "product-first", "product-first-sku",
                        CatalogueQuantityFields(variantName = "1 bottle", packSize = "${case.size} ${case.unit}")))
                val assessed = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
                assertFalse("Unsupported product-first syntax became exact: $source",
                    resolveSwiggyCartQuantity(assessed, candidate) is SwiggyCartQuantityResolution.Exact)
                val request = SwiggyCartReviewRequest.build(listOf(assessed), mapOf(0 to candidate), emptySet())
                if (request != null) assertTrue("Unproven product-first form must require explicit offer review",
                    request.quantityProposals.isNotEmpty())
            }
        }

        listOf("if available, one 180 ml bottle of shampoo", "one 180 ml bottle of shampoo if available",
            "for tomorrow, one 180 ml bottle of shampoo").forEach { instruction ->
            val result = runCatching { GroceryIntentDraft("grocery-intent-v1", true, instruction, listOf(
                GroceryIntentItem("shampoo", BigDecimal.ONE, "count", BigDecimal("180"), "ml",
                    emptyList(), emptyList(), instruction, false),
            ), false).prepareForReview() }
            if (result.isFailure) assertTrue(result.exceptionOrNull() is IntentNeedsReviewException)
            else {
                val prepared = result.getOrThrow()
                if (prepared.items.isEmpty()) assertTrue(prepared.unresolvedText.isNotEmpty())
                assertTrue(prepared.items.size <= 1)
                val item = prepared.items.singleOrNull() ?: return@forEach
                assertNull(item.validNamedRetailReceipt())
                val offer = RecommendationCandidate("whole-context", "Shampoo 1 bottle 180 ml", skuId = "whole-context-sku",
                    catalogueQuantity = CatalogueQuantityEvidence(1, "search_variation", "whole-context", "whole-context-sku",
                        CatalogueQuantityFields(variantName = "1 bottle", packSize = "180 ml")))
                val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(offer)))
                assertFalse("Whole instruction context was discarded", resolveSwiggyCartQuantity(checked, offer) is SwiggyCartQuantityResolution.Exact)
                val request = SwiggyCartReviewRequest.build(listOf(checked), mapOf(0 to offer), emptySet())
                if (request != null) assertTrue("Whole-context fallback must remain a visible quantity proposal",
                    request.quantityProposals.isNotEmpty())
            }
        }

        val unsafe = listOf(
            "one pack of tea", "one cup of rice", "one spray of cleaner", "one bottle of shampoo for two people",
            "one bottle of shampoo with conditioner", "one bottle of shampoo, if available", "one bottle of shampoo on 12 May",
            "one bottle of 7Up", "one box of two packets of tea", "one bottle of shampoo plus 2 extra",
            "do not buy one 180 ml bottle of shampoo", "one\u00a0180 ml bottle of shampoo", "one 180\u200b ml bottle of shampoo",
        )
        unsafe.forEach { source ->
            assertNull("Unsafe or untyped role accepted: $source", sourceNamedRetailCountReceipt(source))
        }
        assertNull("Generic packet count must not be named-exact", sourceNamedRetailCountReceipt("two packets of tea"))
        listOf("two packs of oats" to "oats", "two packets of tea" to "tea").forEach { (source, name) ->
            val prepared = GroceryIntentDraft("grocery-intent-v1", true, source, listOf(
                GroceryIntentItem(name, BigDecimal("2"), "pack", null, "unspecified", emptyList(), emptyList(), source, false),
            ), false).prepareForReview()
            prepared.items.forEach { item ->
                assertNull(item.validNamedRetailReceipt())
                val candidate = RecommendationCandidate("generic-$name", "$name 500 g", skuId = "generic-$name-sku")
                val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
                assertFalse("Generic pack/packet became Exact", resolveSwiggyCartQuantity(checked, candidate) is SwiggyCartQuantityResolution.Exact)
            }
        }
    }

    @Test
    fun v1ProductFirstMeasuredRetailUsesExactSourceAndTypedNamedOffer() {
        data class Case(val source: String, val name: String, val count: Int, val countAtom: String,
            val size: String, val unit: String, val unitText: String, val form: String, val formText: String)
        val cases = listOf(
            Case("shampoo ki 180 ml bottle ek", "shampoo", 1, "ek", "180", "ml", "ml", "bottle", "bottle"),
            Case("floor cleaner ki 1 litre bottle ek", "floor cleaner", 1, "ek", "1", "l", "litre", "bottle", "bottle"),
            Case("rose water ki do 100 ml bottles", "rose water", 2, "do", "100", "ml", "ml", "bottle", "bottles"),
            Case("Harpic ki ek 500 ml bottle", "Harpic", 1, "ek", "500", "ml", "ml", "bottle", "bottle"),
            Case("face cream ki 250 g jar ek", "face cream", 1, "ek", "250", "g", "g", "jar", "jar"),
            Case("toothpaste ki 100 g tube ek", "toothpaste", 1, "ek", "100", "g", "g", "tube", "tube"),
        )
        fun draft(c: Case, source: String = c.source, count: Int = c.count, size: String = c.size) =
            GroceryIntentDraft("grocery-intent-v1", true, source, listOf(GroceryIntentItem(
                c.name, count.toBigDecimal(), "pack", size.toBigDecimal(), c.unit,
                emptyList(), emptyList(), source, false,
            )), false)
        fun v1(c: Case, source: String = c.source, count: Int = c.count, size: String = c.size) =
            draft(c, source, count, size).prepareForReview().items.single()
        fun offer(c: Case, id: String, form: String = c.formText, size: String = c.size, count: Int = 1) =
            RecommendationCandidate(id, "${c.name} $count $form $size ${c.unit}", skuId = "$id-sku",
                catalogueQuantity = CatalogueQuantityEvidence(1, "search_variation", id, "$id-sku",
                    CatalogueQuantityFields(variantName = "$count $form", packSize = "$size ${c.unit}")))
        fun assertHeld(item: com.example.beta.automation.ParsedItem, candidate: RecommendationCandidate, label: String) {
            val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
            assertFalse("$label became Exact", resolveSwiggyCartQuantity(checked, candidate) is SwiggyCartQuantityResolution.Exact)
            assertNull("$label reached cart review", SwiggyCartReviewRequest.build(listOf(checked), mapOf(0 to candidate), emptySet()))
        }
        fun assertNotExact(item: com.example.beta.automation.ParsedItem, candidate: RecommendationCandidate, label: String) {
            val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
            assertFalse("$label became Exact", resolveSwiggyCartQuantity(checked, candidate) is SwiggyCartQuantityResolution.Exact)
            val request = SwiggyCartReviewRequest.build(listOf(checked), mapOf(0 to candidate), emptySet())
            if (request != null) assertTrue("$label must remain a visible proposal", request.quantityProposals.isNotEmpty())
        }

        cases.forEachIndexed { index, c ->
            val receipt = requireNotNull(productFirstMeasuredRetail(c.source)) { c.source }
            val sourceSize = requireNotNull(receipt.size)
            assertEquals(c.source, receipt.source)
            assertEquals(c.name, c.source.substring(receipt.identity))
            assertEquals(c.formText, c.source.substring(receipt.container))
            assertEquals(c.form, groceryIntentRetailContainerKey(c.source.substring(receipt.container)))
            assertEquals(c.countAtom, c.source.substring(receipt.count.startUtf16, receipt.count.endUtf16))
            assertEquals(c.size, c.source.substring(sourceSize.startUtf16, sourceSize.endUtf16))
            val unitToken = requireNotNull(GroceryIntentPhysicalUnitMatcher.after(c.source, sourceSize.endUtf16, c.unit))
            assertEquals(c.unitText, c.source.substring(unitToken.start, unitToken.end))
            assertEquals(0, receipt.count.value.compareTo(c.count.toBigDecimal()))
            assertEquals(normalizePack(c.size.toBigDecimal(), c.unit), normalizePack(sourceSize.value, sourceSize.unit))

            val item = v1(c)
            val proof = requireNotNull(item.validNamedRetailReceipt()) { "V1 named proof: ${c.source}" }
            val provenance = requireNotNull(item.intentQuantityProvenance)
            val countDraft = draft(c).let { it.copy(items = listOf(it.items.single().copy(quantityUnit = "count"))) }
            assertEquals("A whole source-proved outer count has the same meaning as pack",
                item, countDraft.prepareForReview().items.single())
            assertEquals(c.source, item.rawText)
            assertEquals(Quantity.Count(c.count), item.quantity)
            assertEquals(IntentSourceCountRole.NAMED_RETAIL, provenance.sourceCountRole)
            assertEquals(c.form, provenance.retailContainer)
            assertEquals(receipt.count.copy(unit = "pack"), provenance.local)
            assertEquals(receipt.size, provenance.unitSize)
            assertEquals(c.name, item.rawText.substring(proof.identity))
            assertEquals(proof.literalQuery(), item.query)

            val chosen = offer(c, "chosen-$index")
            val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(chosen)))
            assertEquals(SwiggyCartQuantityResolution.Exact(c.count), resolveSwiggyCartQuantity(checked, chosen))
            assertNotNull(SwiggyCartReviewRequest.build(listOf(checked), mapOf(0 to chosen), emptySet()))
        }

        val shampoo = cases.first()
        val item = v1(shampoo)
        assertHeld(item, offer(shampoo, "wrong-count", count = 2), "wrong SKU unit count")
        assertHeld(item, offer(shampoo, "wrong-form", form = "tube"), "wrong seller form")
        assertHeld(item, offer(shampoo, "wrong-size", size = "200"), "wrong seller size")
        assertHeld(item, RecommendationCandidate("no-proof", "shampoo bottle 180 ml", skuId = "no-proof-sku"),
            "seller without typed one-container proof")

        for (badDraft in listOf(draft(shampoo, count = 2), draft(shampoo, size = "181"))) {
            val result = runCatching { badDraft.prepareForReview() }
            if (result.isFailure) assertTrue(result.exceptionOrNull() is IntentNeedsReviewException)
            else {
                val bad = result.getOrThrow().items.singleOrNull()
                if (bad == null) assertTrue(result.getOrThrow().unresolvedText.isNotEmpty())
                else {
                    assertNull("Conflicting model/source fields retained named proof", bad.validNamedRetailReceipt())
                    assertHeld(bad, offer(shampoo, "conflicting-model"), "conflicting model quantity/size")
                }
            }
        }
        val generic = GroceryIntentDraft("grocery-intent-v1", true, "two packets of tea", listOf(
            GroceryIntentItem("tea", BigDecimal("2"), "pack", null, "unspecified", emptyList(), emptyList(),
                "two packets of tea", false),
        ), false).prepareForReview().items.single()
        assertNull("Generic packet was promoted to named retail", generic.validNamedRetailReceipt())
        assertNotExact(generic, RecommendationCandidate("generic-packet", "Tea 1 packet 250 g", skuId = "generic-packet-sku",
            catalogueQuantity = CatalogueQuantityEvidence(1, "search_variation", "generic-packet", "generic-packet-sku",
                CatalogueQuantityFields(variantName = "1 packet", packSize = "250 g"))), "generic packet")

        val unavailable = v1(shampoo).copy(intentQuantityProvenance = null)
        val alteredProof = requireNotNull(v1(shampoo).intentQuantityProvenance).copy(
            unitSize = requireNotNull(v1(shampoo).intentQuantityProvenance).unitSize!!.copy(value = BigDecimal("181")))
        assertHeld(unavailable, offer(shampoo, "missing-proof"), "missing source provenance")
        assertHeld(v1(shampoo).copy(intentQuantityProvenance = alteredProof), offer(shampoo, "altered-proof"),
            "changed source provenance")

        val unsafeSources = listOf(
            "maybe shampoo ki 180 ml bottle ek", "shampoo ki 180 ml bottle ek if cheap",
            "shampoo ki 180 ml bottle ek, do not buy", "shampoo ki 180 ml bottle ek for two people",
            "do not buy shampoo ki 180 ml bottle ek", "shampoo ki 180 ml bottle ek if available",
            "tea ki do 250 g packets", "shampoo ki 180 ml bottle", "shampoo ki 180 ml bottle ek plus 2",
            "shampoo ki 180 ml bottle ek\u200b",
        )
        unsafeSources.forEach { source ->
            assertNull(source, productFirstMeasuredRetail(source))
            val result = runCatching { GroceryIntentDraft("grocery-intent-v1", true, source, listOf(GroceryIntentItem(
                "shampoo", BigDecimal.ONE, "pack", BigDecimal("180"), "ml", emptyList(), emptyList(), source, false,
            )), false).prepareForReview() }
            if (result.isFailure) assertTrue(result.exceptionOrNull() is IntentNeedsReviewException)
            else {
                val prepared = result.getOrThrow()
                if (prepared.items.isEmpty()) assertTrue(prepared.unresolvedText.isNotEmpty())
                assertTrue(prepared.items.size <= 1)
                val unsafe = prepared.items.singleOrNull()
                if (unsafe != null) assertNotExact(unsafe, offer(shampoo, "unsafe"), "unsafe context $source")
            }
        }
        for (cropped in listOf(draft(shampoo, source = "180 ml bottle ek"),
            draft(shampoo).copy(instruction = "without fragrance " + shampoo.source),
            draft(shampoo).copy(instruction = shampoo.source + " only if it is sugar free"))) {
            val outcome = runCatching { cropped.prepareForReview() }
            if (outcome.isFailure) assertTrue(outcome.exceptionOrNull() is IntentNeedsReviewException)
            else {
                assertTrue("Unowned restriction was dropped", outcome.getOrThrow().unresolvedText.isNotEmpty())
                outcome.getOrThrow().items.forEach {
                    assertNull("Cropped source minted named role", it.validNamedRetailReceipt())
                    assertHeld(it, offer(shampoo, "cropped"), "cropped source row")
                }
            }
        }
    }

    @Test
    fun v1ModelCountOrPerPackSizeConflictCannotReachNamedRetailReview() {
        val source = "one 180 ml bottle of shampoo"
        val row = { count: String, size: String -> GroceryIntentItem(
            "shampoo", BigDecimal(count), "count", BigDecimal(size), "ml", emptyList(), emptyList(), source, false,
        ) }
        val badRows = listOf(row("2", "180"), row("1", "200"), GroceryIntentItem(
            "shampoo", null, "unspecified", null, "unspecified", emptyList(), emptyList(), source, false,
        ))
        for (bad in badRows) {
            val prepared = runCatching { GroceryIntentDraft("grocery-intent-v1", true, source, listOf(bad), false).prepareForReview() }
                .getOrNull()
            assertFalse("Conflicting V1 fields acquired named proof", prepared?.items.orEmpty().any { it.validNamedRetailReceipt() != null })
            val candidate = RecommendationCandidate("bad-model", "Shampoo 1 bottle 180 ml", skuId = "bad-model-sku",
                catalogueQuantity = CatalogueQuantityEvidence(1, "search_variation", "bad-model", "bad-model-sku",
                    CatalogueQuantityFields(variantName = "1 bottle", packSize = "180 ml")))
            val items = prepared?.items.orEmpty()
            if (items.isNotEmpty()) {
                val assessed = assessSwiggyIntentQuantity(items.single(), Recommendations(listOf(candidate)))
                assertFalse(resolveSwiggyCartQuantity(assessed, candidate) is SwiggyCartQuantityResolution.Exact)
                assertNull(SwiggyCartReviewRequest.build(listOf(assessed), mapOf(0 to candidate), emptySet()))
            }
        }
    }

    @Test
    fun matchesSharedRetailCountRoleFixture() {
        val relativePath = "scripts/fixtures/grocery_retail_count_role_v1.json"
        val fixtureFile = listOf(File("../$relativePath"), File(relativePath)).firstOrNull { it.isFile }
            ?: error("Cannot find shared retail-count fixture at $relativePath")
        val fixture = (JsonParser(fixtureFile.readText(Charsets.UTF_8)).parseStrict() as JsonValue.Obj).members
        assertEquals("grocery-retail-count-role-v1", (fixture.getValue("schema") as JsonValue.Str).value)
        val cases = (fixture.getValue("cases") as JsonValue.Arr).items
        assertEquals(72, cases.size)
        cases.forEach { raw ->
            val case = (raw as JsonValue.Obj).members
            fun string(key: String) = (case.getValue(key) as JsonValue.Str).value
            fun nullableString(key: String) = (case.getValue(key) as? JsonValue.Str)?.value
            fun decimal(key: String) = (case.getValue(key) as? JsonValue.Num)?.raw?.let(::BigDecimal)
            val expected = (case.getValue("expected_retail") as JsonValue.Bool).value
            val row = review(
                instruction = string("source"), name = "water", packaging = string("packaging"),
                quantityUnit = "count", quantity = decimal("quantity")!!.toPlainString(),
                quantityText = string("quantity_text"), packValue = decimal("pack_value")?.toPlainString(),
                packUnit = string("pack_unit"), packText = nullableString("pack_text"),
            ).readyRows.singleOrNull()
            val proved = row?.sourceProvesRetailCount() == true
            assertEquals(string("id"), expected, proved)
        }
    }

    @Test
    fun genericPackCountUsesReviewedOfferAcrossAllPreparationAdapters() {
        data class Case(val source: String, val nominal: String, val count: Int, val packaging: String) {
            val countText: String get() = if (count == 1) "one" else "two"
        }
        val cases = listOf(
            Case("one pack of oats", "oats", 1, "pack"),
            Case("one pack oats", "oats", 1, "pack"),
            Case("two packs of salted butter", "salted butter", 2, "pack"),
            Case("two packs salted butter", "salted butter", 2, "pack"),
            Case("one packet of organic rice", "organic rice", 1, "packet"),
            Case("one packet organic rice", "organic rice", 1, "packet"),
            Case("two packets of organic rice", "organic rice", 2, "packet"),
            Case("two packets organic rice", "organic rice", 2, "packet"),
        )

        cases.forEach { case ->
            val normalizedNominal = case.nominal.replaceFirstChar { it.uppercase() }
            val size = if (case.nominal == "organic rice") "1 kg" else "500 g"
            val typedSpin = "typed-${case.nominal.replace(' ', '-')}"
            val typedSku = "$typedSpin-sku"
            val typed = RecommendationCandidate(
                spinId = typedSpin,
                label = normalizedNominal,
                skuId = typedSku,
                catalogueQuantity = CatalogueQuantityEvidence(
                    version = 1,
                    source = "search_variation",
                    spinId = typedSpin,
                    skuId = typedSku,
                    fields = CatalogueQuantityFields(packSize = size),
                ),
            )
            val displayOnly = RecommendationCandidate("display-${case.nominal.replace(' ', '-')}",
                "$normalizedNominal $size")

            fun assertReviewedOffer(
                item: com.example.beta.automation.ParsedItem,
                candidate: RecommendationCandidate,
                adapter: String,
            ) {
                assertEquals("$adapter changed original source", case.source, item.rawText)
                assertEquals("$adapter changed count", Quantity.Count(case.count), item.quantity)
                val nominalTokens = intentEvidenceTokens(case.nominal)
                assertTrue("$adapter lost complete nominal for ${case.source}: ${item.query}",
                    intentEvidenceTokens(item.query).containsAll(nominalTokens))
                val checked = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
                val result = resolveSwiggyCartQuantity(checked, candidate)
                assertTrue("$adapter must review, never Exact, for ${case.source}: $result",
                    result is SwiggyCartQuantityResolution.RetailProposal)
                val proposal = result as SwiggyCartQuantityResolution.RetailProposal
                assertEquals(case.count, proposal.cartUnits)
                assertTrue("$adapter bypassed offer review for ${case.source}", runCatching {
                    swiggyRequestedCartQuantity(checked, candidate)
                }.isFailure)

                if (case.nominal == "organic rice") {
                    val wrongOrganic = RecommendationCandidate("plain-rice", "Rice 1 kg")
                    val wrong = resolveSwiggyCartQuantity(
                        assessSwiggyIntentQuantity(item, Recommendations(listOf(wrongOrganic))), wrongOrganic,
                    )
                    assertTrue("$adapter accepted candidate missing organic identity for ${case.source}: $wrong",
                        wrong is SwiggyCartQuantityResolution.Held)
                }
            }

            val parsed = prepareSwiggyMcpItems(case.source) { null }
            assertEquals("standard parser row count for ${case.source}", 1, parsed.size)
            assertReviewedOffer(parsed.single(), typed, "standard parser with typed catalogue quantity")
            assertReviewedOffer(parsed.single(), displayOnly, "standard parser with display-only title size")

            val v1Row = GroceryIntentItem(
                name = case.nominal, quantity = BigDecimal(case.count), quantityUnit = "count",
                packValue = null, packUnit = "unspecified", required = emptyList(), excluded = emptyList(),
                sourceText = case.source, needsClarification = false,
            )
            val v1 = GroceryIntentDraft("grocery-intent-v1", true, case.source, listOf(v1Row), false)
                .prepareForReview()
            assertEquals("V1 row count for ${case.source}", 1, v1.items.size)
            assertReviewedOffer(v1.items.single(), typed, "V1 draft with typed catalogue quantity")
            assertReviewedOffer(v1.items.single(), displayOnly, "V1 draft with display-only title size")

            val v2 = review(case.source, case.nominal, packaging = case.packaging,
                quantityUnit = "pack", quantity = case.count.toString(), quantityText = case.countText)
                .prepareForReview()
            assertEquals("V2 normalized row count for ${case.source}", 1, v2.items.size)
            assertReviewedOffer(v2.items.single(), typed, "V2 normalized review with typed catalogue quantity")
            assertReviewedOffer(v2.items.single(), displayOnly, "V2 normalized review with display-only title size")
        }
    }

    @Test
    fun namedUnitCountsRequireExplicitOneUnitPerSkuEvidenceAcrossFamilies() {
        data class Case(
            val source: String,
            val nominal: String,
            val singularForm: String,
            val pluralForm: String,
            val physicalSize: String,
            val differentUnit: String,
        )
        val cases = listOf(
            Case("2 bars of bathing soap", "bathing soap", "bar", "bars", "400 g", "pack"),
            Case("2 bottles of water", "water", "bottle", "bottles", "500 ml", "bar"),
            Case("2 cans of beans", "beans", "can", "cans", "400 g", "bottle"),
            Case("2 loaves of bread", "bread", "loaf", "loaves", "400 g", "pack"),
        )
        val failures = mutableListOf<String>()

        fun candidateWithQuantity(
            spinId: String,
            label: String,
            quantityText: String,
            conflictingQuantityText: String? = null,
            physicalSize: String? = null,
        ): RecommendationCandidate {
            val skuId = "$spinId-sku"
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
                        variantName = quantityText,
                        packSize = physicalSize,
                        quantityDescription = conflictingQuantityText,
                    ),
                ),
            )
        }

        cases.forEachIndexed { index, case ->
            val parsed = prepareSwiggyMcpItems(case.source) { null }
            val item = parsed.singleOrNull()
            if (item == null) {
                failures += "case=$index standard parser did not return one row"
                return@forEachIndexed
            }
            if (item.rawText != case.source || item.quantity != Quantity.Count(2)) {
                failures += "case=$index parser changed source/count"
                return@forEachIndexed
            }
            if (sourceNamedRetailCountReceipt(case.source) == null) {
                failures += "case=$index source grammar did not establish named unit"
                return@forEachIndexed
            }

            fun outcome(candidate: RecommendationCandidate): SwiggyCartQuantityResolution =
                resolveSwiggyCartQuantity(
                    assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate))), candidate,
                )

            fun requireHeld(label: String, candidate: RecommendationCandidate) {
                val result = outcome(candidate)
                if (result !is SwiggyCartQuantityResolution.Held)
                    failures += "case=$index $label expected Held, got ${result::class.simpleName}"
            }

            val displayOnly = RecommendationCandidate(
                "display-$index", "${case.nominal.replaceFirstChar { it.uppercase() }} ${case.singularForm}",
                variant = case.physicalSize,
            )
            requireHeld("display-only physical size", displayOnly)

            val typedPhysical = candidateWithQuantity(
                "physical-$index", "${case.nominal.replaceFirstChar { it.uppercase() }} ${case.singularForm}",
                case.physicalSize,
            )
            requireHeld("typed physical size", typedPhysical)

            val oneUnit = candidateWithQuantity(
                "one-unit-$index",
                "${case.nominal.replaceFirstChar { it.uppercase() }} 1 ${case.singularForm}",
                "1 ${case.singularForm}",
            )
            val oneUnitResult = outcome(oneUnit)
            if (oneUnitResult != SwiggyCartQuantityResolution.Exact(2))
                failures += "case=$index explicit one-${case.singularForm} SKU expected Exact(2), got ${oneUnitResult::class.simpleName}"

            val oneUnitAndPhysicalSize = candidateWithQuantity(
                "one-unit-sized-$index",
                "${case.nominal.replaceFirstChar { it.uppercase() }} 1 ${case.singularForm} ${case.physicalSize}",
                "1 ${case.singularForm}",
                physicalSize = case.physicalSize,
            )
            val sizedOneUnitResult = outcome(oneUnitAndPhysicalSize)
            if (sizedOneUnitResult != SwiggyCartQuantityResolution.Exact(2))
                failures += "case=$index explicit one-${case.singularForm} SKU with physical size expected Exact(2), got ${sizedOneUnitResult::class.simpleName}"

            val displayOnlyOneUnit = RecommendationCandidate(
                "display-one-unit-$index",
                "${case.nominal.replaceFirstChar { it.uppercase() }} 1 ${case.singularForm}",
            )
            requireHeld("title-only one-unit claim", displayOnlyOneUnit)

            val mismatchedUnit = candidateWithQuantity(
                "mismatched-unit-$index",
                "${case.nominal.replaceFirstChar { it.uppercase() }} ${case.singularForm} 1 ${case.differentUnit}",
                "1 ${case.differentUnit}",
            )
            requireHeld("typed count of different unit", mismatchedUnit)

            val conflictingTypedCounts = candidateWithQuantity(
                "conflicting-counts-$index",
                "${case.nominal.replaceFirstChar { it.uppercase() }} 1 ${case.singularForm}",
                "1 ${case.singularForm}",
                "4 ${case.pluralForm}",
            )
            requireHeld("conflicting typed quantity fields", conflictingTypedCounts)

            val wrongSkuBindingBase = candidateWithQuantity(
                "wrong-sku-binding-$index",
                "${case.nominal.replaceFirstChar { it.uppercase() }} 1 ${case.singularForm}",
                "1 ${case.singularForm}",
            )
            val wrongSkuBinding = wrongSkuBindingBase.copy(
                catalogueQuantity = wrongSkuBindingBase.catalogueQuantity?.copy(skuId = "different-sku-$index"),
            )
            requireHeld("typed quantity bound to a different SKU", wrongSkuBinding)

            val fourUnitsText = "${case.nominal.replaceFirstChar { it.uppercase() }} pack of 4 ${case.pluralForm}"
            requireHeld("known four-unit bundle", candidateWithQuantity("four-unit-$index", fourUnitsText, "4 ${case.pluralForm}"))

            val ambiguousBundle = "${case.nominal.replaceFirstChar { it.uppercase() }} 4 x ${case.physicalSize}"
            requireHeld("ambiguous physical bundle", candidateWithQuantity("ambiguous-$index", ambiguousBundle, ambiguousBundle))
        }

        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun reconstructedNamedRetailProofCannotAuthorizeGenericOfferAsExact() {
        val source = "one pack of oats"
        val count = IntentLocalAmount(source, 0, 3, "one", BigDecimal.ONE, "pack")
        val oldNamedProof = IntentQuantityProvenance(
            origin = IntentQuantityOrigin.LOCAL,
            primaryText = source,
            rawText = source,
            resolvedQuantity = Quantity.Count(1),
            local = count,
            query = "oats pack",
            strictMatchPhrase = "oats pack",
            retailPackCount = true,
            logicalPieceCount = false,
            retailContainer = "pack",
            sourceCountRole = IntentSourceCountRole.NAMED_RETAIL,
        )
        val reconstructed = com.example.beta.automation.ParsedItem(
            rawText = source,
            query = "oats pack",
            quantity = Quantity.Count(1),
            strictMatchPhrase = "oats pack",
            retailPackCount = true,
            requiresIntentQuantityProof = true,
            intentQuantityProvenance = oldNamedProof,
        )
        assertEquals(null, reconstructed.validNamedRetailReceipt())
        val typed = RecommendationCandidate(
            spinId = "typed-oats", label = "Oats", skuId = "typed-oats-sku",
            catalogueQuantity = CatalogueQuantityEvidence(
                1, "search_variation", "typed-oats", "typed-oats-sku", CatalogueQuantityFields(packSize = "500 g"),
            ),
        )
        val result = resolveSwiggyCartQuantity(
            assessSwiggyIntentQuantity(reconstructed, Recommendations(listOf(typed))), typed,
        )
        assertTrue("reconstructed old named proof should be held: $result",
            result is SwiggyCartQuantityResolution.Held)

        val oldV2Local = reconstructed.copy(
            intentQuantityProvenance = oldNamedProof.copy(sourceCountRole = null),
        )
        val v2Result = resolveSwiggyCartQuantity(
            assessSwiggyIntentQuantity(oldV2Local, Recommendations(listOf(typed))), typed,
        )
        assertTrue("old V2 local retail-container proof should be held: $v2Result",
            v2Result is SwiggyCartQuantityResolution.Held)
    }

    @Test
    fun genericPacketCountCannotEscapeUnprovedOriginalClauseContext() {
        for (source in listOf("two packs of oats", "two packs oats")) {
            for (instruction in listOf("maybe $source", "$source, $source", "do not buy $source")) {
                val item = com.example.beta.automation.ParsedItem(
                    rawText = source, query = "oats", quantity = Quantity.Count(2), retailPackCount = true,
                ).withDeterministicSourceEvidence(source, instruction, ownsClause = true)
                assertTrue("Unproved context must not acquire a source count: $instruction",
                    item.quantitySignal != null)
                val candidate = RecommendationCandidate("context-oats", "Oats 500 g")
                val result = resolveSwiggyCartQuantity(
                    assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate))), candidate,
                )
                assertTrue("Unproved context must not reach a reviewed offer: $instruction",
                    result is SwiggyCartQuantityResolution.Held)
            }
        }
    }

    @Test
    fun v1CannotUseRetailCountReceiptToInventAnExcludedModifier() {
        val source = "one pack of rice"
        val row = GroceryIntentItem(
            name = "rice", quantity = BigDecimal.ONE, quantityUnit = "count", packValue = null,
            packUnit = "unspecified", required = emptyList(), excluded = listOf("sugar"),
            sourceText = source, needsClarification = false,
        )
        val result = runCatching {
            GroceryIntentDraft("grocery-intent-v1", true, source, listOf(row), false).prepareForReview()
        }
        assertTrue("Invented exclusion must be rejected before preparing an actionable row",
            result.exceptionOrNull() is IntentNeedsReviewException)
    }

    @Test
    fun sourceProvedRetailCountStillLabeledCountIsHeldRatherThanRelabeled() {
        val draft = review("one packet of rice", "rice", packaging = "packet", quantityUnit = "count")
        assertTrue(draft.readyRows.single().sourceProvesRetailCount())
        val prepared = draft.prepareForReview()
        assertTrue(prepared.items.isEmpty())
        assertEquals(listOf(draft.instruction), prepared.unresolvedText)
    }

    @Test
    fun sourceProvedPackRemainsPackAndSetsRetailFlag() {
        val prepared = review("one packet of rice", "rice", packaging = "packet", quantityUnit = "pack")
            .prepareForReview()
        assertEquals(1, prepared.items.size)
        assertTrue(prepared.items.single().retailPackCount)
        assertTrue(prepared.unresolvedText.isEmpty())
    }

    @Test
    fun bareApplesRemainCountAndDoNotGainRetailFlag() {
        val prepared = review("one apples", "apples", packaging = null, quantityUnit = "count")
            .prepareForReview()
        assertEquals(1, prepared.items.size)
        assertEquals(Quantity.Count(1), prepared.items.single().quantity)
        assertFalse(prepared.items.single().retailPackCount)
    }

    @Test
    fun blockedSiblingCannotPromoteOrChangeCountOfReadyRow() {
        val instruction = "one packet of rice; unclear beans"
        val base = review("one packet of rice", "rice", packaging = "packet", quantityUnit = "count")
        val source = "unclear beans"
        val start = instruction.indexOf(source)
        val withBlocked = base.copy(
            instruction = instruction,
            sourceItemCount = 2,
            blockedRows = listOf(GroceryIntentReviewV2BlockedRow(
                rowIndex = 1,
                source = GroceryIntentReviewV2Quote(source, start, start + source.length),
                issueCodes = listOf("unclear_item"),
            )),
        )
        val prepared = withBlocked.prepareForReview()
        assertTrue(prepared.items.isEmpty())
        assertEquals(listOf(instruction), prepared.unresolvedText)
    }

    @Test
    fun supportedSiblingCannotLaunderUnnormalizedRetailCount() {
        val instruction = "one tea fresh; one packet of rice"
        val riceSource = "one packet of rice"
        val riceStart = instruction.indexOf(riceSource)
        val riceBase = review(riceSource, "rice", packaging = "packet", quantityUnit = "count").readyRows.single()
        val rice = riceBase.copy(rowIndex = 1, evidence = riceBase.evidence.copy(
            primary = GroceryIntentReviewV2Quote(riceSource, riceStart, riceStart + riceSource.length),
        ))
        val teaStart = instruction.indexOf("one tea")
        val supportStart = instruction.indexOf("fresh")
        val teaItem = GroceryIntentReviewV2Item(
            name = "tea", quantity = BigDecimal.ONE, quantityUnit = "count", packValue = null,
            packUnit = "unspecified", packaging = null, required = emptyList(), excluded = emptyList(),
            sourceText = "one tea", supportingText = listOf(GroceryIntentReviewV2SupportText("modifier", "fresh")),
            needsClarification = false, quantityText = "one", packText = null,
        )
        val tea = GroceryIntentReviewV2ReadyRow(
            rowIndex = 0, item = teaItem,
            evidence = GroceryIntentReviewV2Evidence(
                GroceryIntentReviewV2Quote("one tea", teaStart, teaStart + "one tea".length),
                listOf(GroceryIntentReviewV2SupportQuote("modifier",
                    GroceryIntentReviewV2Quote("fresh", supportStart, supportStart + "fresh".length))),
            ), quantityOrigin = "stated", amountRoleStatus = "matched",
        )
        val draft = GroceryIntentReviewV2(
            contractVersion = "grocery-intent-v2", requiresReview = true, instruction = instruction,
            sourceItemCount = 2, readyRows = listOf(tea, rice), blockedRows = emptyList(),
        )
        val prepared = draft.prepareForReview()
        assertTrue(prepared.items.isEmpty())
        assertEquals(listOf(instruction), prepared.unresolvedText)
    }

    @Test
    fun smallSizeAndDietaryIdentityArePreserved() {
        val small = review("one small packet of organic rice", "organic rice", packaging = "packet",
            quantityUnit = "pack", required = listOf("organic", "small"))
        assertTrue(small.readyRows.single().sourceProvesRetailCount())
        val smallPrepared = small.prepareForReview()
        assertEquals(1, smallPrepared.items.size)
        assertTrue(smallPrepared.items.single().retailPackCount)
        assertTrue(smallPrepared.items.single().query.contains("organic"))
        assertTrue(smallPrepared.items.single().query.contains("small"))

        val missingSmall = review("one small packet of organic rice", "organic rice", packaging = "packet",
            quantityUnit = "pack", required = listOf("organic"))
        val missingSmallPrepared = missingSmall.prepareForReview()
        assertTrue(missingSmallPrepared.items.isEmpty())
        assertTrue((missingSmallPrepared.unresolvedText + missingSmallPrepared.excludedText)
            .contains(missingSmall.instruction))

        val sized = review("one 500 g packet of rice", "rice", packaging = "packet", quantityUnit = "pack",
            packValue = "500", packUnit = "g", packText = "500")
        assertTrue(sized.readyRows.single().sourceProvesRetailCount())
        assertTrue(sized.prepareForReview().items.single().retailPackCount)
    }

    @Test
    fun servingUnitsExtraNumbersAndInnerCountsAreNotRetailCountProof() {
        assertFalse(review("one cup of rice", "rice", packaging = "cup", quantityUnit = "count")
            .readyRows.single().sourceProvesRetailCount())
        assertFalse(review("one spray of cleaner", "cleaner", packaging = "spray", quantityUnit = "count")
            .readyRows.single().sourceProvesRetailCount())
        assertFalse(review("one packet of 9 batteries", "9 batteries", packaging = "packet", quantityUnit = "count")
            .readyRows.single().sourceProvesRetailCount())
        assertFalse(review("one packet of rice for 2 people", "rice", packaging = "packet", quantityUnit = "count")
            .readyRows.single().sourceProvesRetailCount())
        val wrongCount = review("one packet of rice", "rice", packaging = "packet", quantityUnit = "count",
            quantity = "2").readyRows.single()
        assertFalse(wrongCount.sourceProvesRetailCount())
        val wrongSize = review("one 500 g packet of rice", "rice", packaging = "packet", quantityUnit = "count",
            packValue = "600", packUnit = "g", packText = "500").readyRows.single()
        assertFalse(wrongSize.sourceProvesRetailCount())
    }

    @Test
    fun boxOuterContainerWithPlainNominalIsAcceptedWithoutConflatingInnerCount() {
        val draft = review("one box of batteries", "batteries", packaging = "box", quantityUnit = "count")
        assertTrue(draft.readyRows.single().sourceProvesRetailCount())
        val prepared = review("one box of batteries", "batteries", packaging = "box", quantityUnit = "pack")
            .prepareForReview().items.single()
        val titleOnlyBox = RecommendationCandidate("batteries-box-title-only", "Batteries Box")
        val boxSku = "batteries-box-sku"
        val box = RecommendationCandidate(
            spinId = "batteries-box",
            label = "Batteries Box 1 box",
            skuId = boxSku,
            catalogueQuantity = CatalogueQuantityEvidence(
                version = 1,
                source = "search_variation",
                spinId = "batteries-box",
                skuId = boxSku,
                fields = CatalogueQuantityFields(variantName = "1 box"),
            ),
        )
        val boxContents = RecommendationCandidate("batteries-box-contents", "Batteries Box 6 pcs")
        val innerSixPack = RecommendationCandidate("batteries-six-pack", "Batteries 6 Pack")
        val assessed = assessSwiggyIntentQuantity(prepared,
            Recommendations(candidates = listOf(titleOnlyBox, box, boxContents, innerSixPack)))
        assertTrue(isSwiggyCandidateAllowed(assessed, titleOnlyBox))
        assertFalse(isSwiggyCandidateCountCompatible(assessed, titleOnlyBox))
        assertTrue(resolveSwiggyCartQuantity(assessed, titleOnlyBox) is SwiggyCartQuantityResolution.Held)
        assertTrue(isSwiggyCandidateAllowed(assessed, box))
        assertTrue(isSwiggyCandidateCountCompatible(assessed, box))
        assertEquals(SwiggyCartQuantityResolution.Exact(1), resolveSwiggyCartQuantity(assessed, box))
        assertTrue(isSwiggyCandidateAllowed(assessed, boxContents))
        // Characterize the separate catalogue hierarchy gap: normalizing the
        // source role does not yet prove whether this count describes inner
        // contents or outer retail units. Keep it held, never round to one.
        assertFalse(isSwiggyCandidateCountCompatible(assessed, boxContents))
        assertTrue(runCatching { swiggyRequestedCartQuantity(assessed, boxContents) }.isFailure)
        assertFalse(isSwiggyCandidateAllowed(assessed, innerSixPack))
        assertFalse(isSwiggyCandidateCountCompatible(assessed, innerSixPack))
    }

    @Test
    fun missingProducerContainerIsNotInferredAndPieceSizeDoesNotProveRetailCount() {
        val missingProducer = review(
            instruction = "two jars of cookies", name = "cookies", packaging = null,
            quantityUnit = "count", quantity = "2", quantityText = "two",
        ).readyRows.single()
        assertTrue(missingProducer.sourceRetailCountEvidence() != null)
        assertFalse(missingProducer.sourceProvesRetailCount())
        assertEquals(null, missingProducer.retailPackEvidenceIssue(null))

        val prepared = review(
            instruction = "two jars of cookies", name = "cookies", packaging = null,
            quantityUnit = "count", quantity = "2", quantityText = "two",
        ).prepareForReview()
        val candidate = RecommendationCandidate(
            "cookies-box-six-pieces", "Cookies Box 6 Pieces", "6 pieces",
            skuId = "cookies-box-six-pieces-sku",
            catalogueQuantity = CatalogueQuantityEvidence(
                1, "search_variation", "cookies-box-six-pieces", "cookies-box-six-pieces-sku",
                CatalogueQuantityFields(variantName = "6 pieces"),
            ),
        )
        prepared.items.forEach { item ->
            val assessed = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
            assertFalse(resolveSwiggyCartQuantity(assessed, candidate) is SwiggyCartQuantityResolution.Exact)
        }

        val physicalOnly = review(
            instruction = "two 6 piece boxes of cookies", name = "cookies", packaging = "box",
            quantityUnit = "count", quantity = "2", quantityText = "two",
            packValue = "6", packUnit = "piece", packText = "6",
        ).readyRows.single()
        assertEquals(null, physicalOnly.sourceRetailCountEvidence())
        assertFalse(physicalOnly.sourceProvesRetailCount())
    }

    private fun review(
        instruction: String,
        name: String,
        packaging: String?,
        quantityUnit: String,
        quantity: String = "1",
        quantityText: String = "one",
        packValue: String? = null,
        packUnit: String = "unspecified",
        packText: String? = null,
        required: List<String> = emptyList(),
    ): GroceryIntentReviewV2 {
        val start = instruction.indexOf(instruction)
        val primary = GroceryIntentReviewV2Quote(instruction, start, start + instruction.length)
        val item = GroceryIntentReviewV2Item(
            name = name,
            quantity = quantity.toBigDecimalOrNull(),
            quantityUnit = quantityUnit,
            packValue = packValue?.let(::BigDecimal),
            packUnit = packUnit,
            packaging = packaging,
            required = required,
            excluded = emptyList(),
            sourceText = instruction,
            supportingText = emptyList(),
            needsClarification = false,
            quantityText = quantityText,
            packText = packText,
        )
        return GroceryIntentReviewV2(
            contractVersion = "grocery-intent-v2",
            requiresReview = true,
            instruction = instruction,
            sourceItemCount = 1,
            readyRows = listOf(GroceryIntentReviewV2ReadyRow(
                rowIndex = 0,
                item = item,
                evidence = GroceryIntentReviewV2Evidence(primary, emptyList()),
                quantityOrigin = "stated",
                amountRoleStatus = "matched",
            )),
            blockedRows = emptyList(),
        )
    }
}
