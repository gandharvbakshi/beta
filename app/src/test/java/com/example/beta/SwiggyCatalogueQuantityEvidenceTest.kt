package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.SwiggyMcpClient.Recommendations
import com.example.beta.automation.IntentLocalAmount
import com.example.beta.automation.IntentQuantityOrigin
import com.example.beta.automation.IntentQuantityProvenance
import com.example.beta.automation.ParsedItem
import com.example.beta.automation.Quantity
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwiggyCatalogueQuantityEvidenceTest {
    @Test
    fun directQuantityDescriptionAndSeparateDisplayNameProveOneOuterAndNetSize() {
        val offer = directVariationOffer("Masoor Dal", "spin-dal", "sku-dal", "1 bag · 500 g")

        assertEquals(listOf("quantityDescription" to "1 bag · 500 g"), offer.catalogueQuantityFields())
        assertEquals("Masoor Dal", offer.catalogueIngredients?.titlesFor("spin-dal", "sku-dal")?.single())
        assertEquals(SingleOuterNetSize("bag", GroceryPackMeasure.of(BigDecimal(500), "g")),
            offer.ownedOuterAndNetSize())
        assertEquals(GroceryPackMeasure.of(BigDecimal(500), "g"), swiggyCatalogueSourcePhysicalMeasure(offer))
    }

    @Test
    fun directQuantityDescriptionSupportsExactTwoLinePhysicalReviewRequest() {
        val dal = directVariationOffer("Masoor Dal", "spin-dal", "sku-dal", "1 bag · 500 g")
        val rice = directVariationOffer("Basmati Rice", "spin-rice", "sku-rice", "1 bag · 500 g")
        val items = listOf(
            measuredItem("500 g masoor dal", "masoor dal", "500", "g", 500),
            measuredItem("1 kg basmati rice", "basmati rice", "1", "kg", 1000),
        )
        val offers = listOf(dal, rice)
        val assessed = items.mapIndexed { index, item ->
            assessSwiggyIntentQuantity(item, Recommendations(listOf(offers[index])))
        }

        assessed.zip(offers).forEach { (item, offer) ->
            assertTrue("${offer.label} must pass source and merchandise identity", isSwiggyCandidateAllowed(item, offer))
            assertTrue("${offer.label} must pass final quantity proof", swiggyIntentQuantityCandidateChecked(item, offer))
        }
        val request = requireNotNull(SwiggyCartReviewRequest.build(
            assessed, mapOf(0 to dal, 1 to rice), emptySet(),
        ))
        assertEquals(listOf("sku-dal", "sku-rice"), request.requestedItems.map { it.skuId })
        assertEquals(listOf(1, 2), request.requestedItems.map { it.quantity })
        assertTrue(request.quantityProposals.isEmpty())
    }

    @Test
    fun quantityDescriptionOnlyEvidenceRejectsIncompleteOrConflictingShapes() {
        val baseline = directVariationOffer("Masoor Dal", "spin-dal", "sku-dal", "1 bag · 500 g")
        val invalid = listOf(
            baseline.copy(catalogueQuantity = baseline.catalogueQuantity!!.copy(
                fields = CatalogueQuantityFields(quantityDescription = "bag · 500 g"))),
            baseline.copy(catalogueQuantity = baseline.catalogueQuantity!!.copy(
                fields = CatalogueQuantityFields(quantityDescription = "500 g"))),
            baseline.copy(catalogueQuantity = baseline.catalogueQuantity!!.copy(
                fields = CatalogueQuantityFields(quantityDescription = "1 bag"))),
            baseline.copy(skuId = "sku-alternate"),
            baseline.copy(catalogueIngredients = null),
            baseline.copy(catalogueQuantity = baseline.catalogueQuantity!!.copy(fields = CatalogueQuantityFields())),
            baseline.copy(catalogueQuantity = baseline.catalogueQuantity!!.copy(fields = CatalogueQuantityFields(
                quantityDescription = "1 bag · 500 g", variantName = "1 bag · 300 g",
            ))),
            RecommendationCandidate("spin-dal", "Masoor Dal · 1 bag · 500 g", variant = "1 bag · 500 g", skuId = "sku-dal"),
            directVariationOffer("Masoor Dal Promo", "spin-dal", "sku-dal", "1 bag · 500 g"),
            directVariationOffer("Masoor Dal", "spin-dal", "sku-dal", "2 bags · 500 g"),
            directVariationOffer("Masoor Dal", "spin-dal", "sku-dal", "1 bag · 500 g + extra"),
        )
        invalid.forEachIndexed { index, offer ->
            assertNull("shape $index must not prove an outer-and-net offer: ${offer.label}", offer.ownedOuterAndNetSize())
        }

        val item = measuredItem("500 g masoor dal", "masoor dal", "500", "g", 500)
        assertFalse("wrong seller title cannot establish the requested merchandise identity",
            isSwiggyCandidateAllowed(item, directVariationOffer("Other Dal", "spin-dal", "sku-dal", "1 bag · 500 g")))
        val salted = baseline.copy(subtitle = "salted")
        assertFalse("dietary conflict remains a veto", isSwiggyCandidateAllowed(item.copy(avoidPhrases = listOf("salt")), salted))
    }

    @Test
    fun joinedSizeDoesNotTurnAPromotionIntoSingleSkuContentsOrMistakeDietaryFreeForAGift() {
        val size = "1 carton · 250 ml"
        val base = candidateWithEvidence()
        fun offer(title: String) = base.copy(label = "$title · $size", variant = size,
            catalogueQuantity = base.catalogueQuantity!!.copy(fields = CatalogueQuantityFields(variantName = size)))
        val expected = GroceryPackMeasure.of(BigDecimal(250), "ml")
        for (title in listOf("Oat Drink", "Sugar-Free Oat Drink", "Lactose Free Milk", "Gluten-Free Cocoa")) {
            val candidate = offer(title)
            assertEquals(title, SingleOuterNetSize("carton", expected), candidate.ownedOuterAndNetSize())
            assertEquals(title, expected, swiggyCatalogueSourcePhysicalMeasure(candidate))
        }
        for (title in listOf("Oat Drink Gift", "Oat Drink Promo", "Cocoa Free Spoon", "Cocoa Bonus Sample",
            "Oat Drink + Extra", "Sugar-Free Oat Drink Free Spoon", "Oat Drink Complimentary Cup")) {
            val candidate = offer(title)
            assertNull(title, candidate.ownedOuterAndNetSize())
            assertNull(title, swiggyCatalogueSourcePhysicalMeasure(candidate))
        }
    }

    @Test
    fun absentEvidenceRemainsLegacyAndValidWirePreservesRawFields() {
        val absent = parseCandidate("""{"spinId":"spin-1","skuId":"sku-1","label":"Milk"}""")
        assertNull(absent.catalogueQuantity)
        assertFalse(absent.catalogueQuantityInvalid)
        assertNull(absent.catalogueQuantityIssue())
        assertNull(absent.catalogueQuantityFields())
        assertNull(absent.catalogueQuantityIdentityKey())

        val valid = parseCandidate(candidateJson(fields = """{"variantName":" Full cream ","packSize":"500 ml"}"""))
        assertEquals(listOf("variantName" to " Full cream ", "packSize" to "500 ml"),
            valid.catalogueQuantityFields())
        assertNull(valid.catalogueQuantityIssue())
        assertNotNull(valid.catalogueQuantityIdentityKey())
        assertEquals(valid.catalogueQuantityIdentityKey(), valid.catalogueQuantityIdentityKey())
    }

    @Test
    fun invalidPresentEvidenceIsRetainedAsInvalidAcrossWireShapes() {
        val invalidCandidates = listOf(
            candidateJson(version = "2"),
            candidateJson(source = "history"),
            candidateJson(evidenceSpinId = "another-spin"),
            candidateJson(evidenceSkuId = "another-sku"),
            candidateJson(fields = """{"variant":"Milk","unknown":"x"}"""),
            candidateJson(fields = """{"variant":500}"""),
            candidateJson(fields = """{"variant":"   "}"""),
            candidateJson(fields = """{"variant":"bad\u0001text"}"""),
            candidateJson(fields = "{}"),
            """{"spinId":"spin-1","skuId":"sku-1","label":"Milk","catalogueQuantity":null}""",
            """{"id":"spin-1","skuId":"sku-1","label":"Milk","catalogueQuantity":{"version":1,"source":"search_variation","spinId":"spin-1","skuId":"sku-1","fields":{"packSize":"500 ml"}}}""",
            """{"spinId":"spin-1","skuId":"sku-1","label":"Milk","catalogueQuantity":{"version":1,"source":"search_variation","spinId":"spin-1","skuId":"sku-1","fields":{"packSize":"500 ml","packSize":"1 L"}}}""",
            """{"spinId":"spin-1","skuId":"sku-1","skuId":"sku-2","label":"Milk","catalogueQuantity":{"version":1,"source":"search_variation","spinId":"spin-1","skuId":"sku-2","fields":{"packSize":"500 ml"}}}""",
        )
        invalidCandidates.forEachIndexed { index, json ->
            val candidate = parseCandidate(json)
            assertTrue("case $index", candidate.catalogueQuantityInvalid)
            assertNotNull("case $index", candidate.catalogueQuantityIssue())
            assertNull("case $index", candidate.catalogueQuantityFields())
            assertNull("case $index", candidate.catalogueQuantityIdentityKey())
        }
    }

    @Test
    fun recursiveFieldLookupCannotUseNestedEvidenceAsCandidateVariant() {
        val candidate = parseCandidate(candidateJson(fields = """{"variant":"500 ml"}"""))
        assertEquals("Milk", candidate.label)
        assertNull(candidate.variant)
        assertEquals(listOf("variant" to "500 ml"), candidate.catalogueQuantityFields())
    }

    @Test
    fun directDtoEvidenceMustStillBindToExactCandidateIdsAndValues() {
        val valid = candidateWithEvidence()
        assertNull(valid.catalogueQuantityIssue())
        assertEquals(listOf("packSize" to "500 ml"), valid.catalogueQuantityFields())
        assertTrue(valid.catalogueQuantityIdentityKey()!!.contains("search_variation"))
        assertNotNull(valid.copy(skuId = "sku-other").catalogueQuantityIssue())
        assertNull(valid.copy(catalogueQuantity = valid.catalogueQuantity!!.copy(spinId = "spin-other"))
            .catalogueQuantityIdentityKey())
        assertNotNull(valid.copy(catalogueQuantityInvalid = true).catalogueQuantityIssue())
    }

    @Test
    fun changedQuantityEvidenceInvalidatesPreviouslyAssessedCandidate() {
        val source = "two milk"
        val item = ParsedItem(
            rawText = source,
            query = "milk",
            quantity = Quantity.Count(2),
            strictMatchPhrase = "milk",
            requiresIntentQuantityProof = true,
            intentQuantityProvenance = com.example.beta.automation.IntentQuantityProvenance(
                origin = IntentQuantityOrigin.LOCAL,
                primaryText = source,
                rawText = source,
                resolvedQuantity = Quantity.Count(2),
                local = IntentLocalAmount(source, 0, 3, "two", BigDecimal("2"), "count"),
                query = "milk",
                strictMatchPhrase = "milk",
                retailPackCount = false,
                logicalPieceCount = false,
            ),
        )
        val original = candidateWithEvidence()
        val assessed = assessSwiggyIntentQuantity(item, Recommendations(listOf(original), original))
        assertTrue(swiggyIntentQuantityCandidateChecked(assessed, original))

        val changed = original.copy(catalogueQuantity = original.catalogueQuantity!!.copy(
            fields = CatalogueQuantityFields(packSize = "1 L"),
        ))
        assertFalse(swiggyIntentQuantityCandidateChecked(assessed, changed))
        assertFalse(swiggyIntentQuantityCandidateChecked(assessed, original.copy(catalogueQuantityInvalid = true)))
    }

    private fun parseCandidate(candidate: String): RecommendationCandidate =
        SwiggyMcpClient.parseRecommendations("""{"candidates":[$candidate]}""").candidates.single()

    private fun directVariationOffer(displayName: String, spin: String, sku: String,
                                     quantityDescription: String): RecommendationCandidate = parseCandidate(
        """{"spinId":"$spin","skuId":"$sku","label":"$displayName · $quantityDescription","variant":"$quantityDescription","catalogueQuantity":{"version":1,"source":"search_variation","spinId":"$spin","skuId":"$sku","fields":{"quantityDescription":"$quantityDescription"}},"catalogueIngredients":{"version":1,"source":"search_variation","spinId":"$spin","skuId":"$sku","fields":{"displayName":"$displayName","quantityDescription":"$quantityDescription"}}}""",
    )

    private fun measuredItem(source: String, identity: String, amount: String, unit: String, grams: Int): ParsedItem {
        val start = source.indexOf(amount)
        require(start >= 0 && source.indexOf(amount, start + amount.length) < 0)
        val local = IntentLocalAmount(source, start, start + amount.length, amount, BigDecimal(amount), unit)
        val quantity = Quantity.Weight(grams)
        return ParsedItem(
            rawText = source,
            query = identity,
            quantity = quantity,
            strictMatchPhrase = identity,
            requiresIntentQuantityProof = true,
            intentQuantityProvenance = IntentQuantityProvenance(
                origin = IntentQuantityOrigin.LOCAL,
                primaryText = source,
                rawText = source,
                resolvedQuantity = quantity,
                local = local,
                query = identity,
                strictMatchPhrase = identity,
                retailPackCount = false,
                logicalPieceCount = false,
            ),
        )
    }

    private fun candidateJson(
        version: String = "1",
        source: String = "search_variation",
        evidenceSpinId: String = "spin-1",
        evidenceSkuId: String = "sku-1",
        fields: String = """{"packSize":"500 ml"}""",
    ): String = """{"spinId":"spin-1","skuId":"sku-1","label":"Milk","catalogueQuantity":{"version":$version,"source":"$source","spinId":"$evidenceSpinId","skuId":"$evidenceSkuId","fields":$fields}}"""

    private fun candidateWithEvidence() = RecommendationCandidate(
        spinId = "spin-1",
        skuId = "sku-1",
        label = "Milk",
        catalogueQuantity = CatalogueQuantityEvidence(
            version = 1,
            source = "search_variation",
            spinId = "spin-1",
            skuId = "sku-1",
            fields = CatalogueQuantityFields(packSize = "500 ml"),
        ),
    )
}
