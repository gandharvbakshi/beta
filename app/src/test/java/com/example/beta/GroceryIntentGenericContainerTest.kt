package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.SwiggyMcpClient.Recommendations
import com.example.beta.automation.Quantity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentGenericContainerTest {
    @Test fun everyGenericSpellingPreservesCountAndPackRolesWithoutConcreteForm() {
        for (generic in groceryIntentGenericContainerWords) for (unit in listOf("count", "pack")) {
            val source = "2 $generic BrandA tea 500 g"
            val prepared = draft(source, "BrandA tea", quantityUnit = unit).prepareForReview()
            assertEquals(source, 1, prepared.items.size)
            assertTrue(prepared.items.single().retailPackCount)
            assertEquals(Quantity.Count(2), prepared.items.single().quantity)
            assertEquals("BrandA tea 500 g", prepared.items.single().query)
            assertTrue(prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
        }
    }

    @Test fun cannotHideStorageQualifiersOrInferAFormFromAnUnprovenGenericWord() {
        listOf(
            "1 coffee dabba 500 g" to "coffee",
            "1 steel dabba 500 g" to "steel",
            "1 dabba steel-tea 500 g" to "steel-tea",
            "1 dabba khaali 500 g" to "khaali",
            "1 dabba BrandA chocolate bar 500 g" to "BrandA chocolate bar",
            "1 dabba 500 g tea" to "tea",
            "1 dabba organic tea 500 g" to "tea",
            "1 dabba tea ka 500 g" to "tea ka",
            "1 dabba tea packet 500 g" to "tea packet",
            "1 dabba dabba tea 500 g" to "tea",
            "1 dabba sugar-free biscuits 500 g" to "biscuits",
            "1 dabbax tea 500 g" to "tea",
            "1 dabba\u0301 tea 500 g" to "tea",
            "1 dabba te\u0430 500 g" to "tea",
        ).forEach { (source, name) ->
            assertNull(source, row(source, name).genericContainerRange())
            assertTrue(source, draft(source, name).prepareForReview().items.isEmpty())
        }
    }

    @Test
    fun recognizesOnlyTheAnchoredGenericContainerShape(): Unit {
        listOf(
            "2 dabba Nescafe 500 g" to "Nescafe",
            "3 DIBBI Amul Dark Chocolate 250g." to "Amul Dark Chocolate",
            "1 dabbe Café Noir 1 kg!" to "Café Noir",
            "two dabba  Nescafe 500 g " to "Nescafe",
            "ek dibba coffee 100 g" to "coffee",
        ).forEach { (source, name) ->
            val row = row(source, name)
            val range = row.genericContainerRange()
            assertTrue("$source should be recognized", range != null)
            assertEquals(source.indexOf(Regex("(?i)dabba|dabbe|dibba|dibbe|dabbi|dibbi").find(source)!!.value),
                requireNotNull(range).first)
            assertTrue(range.last >= range.first)
        }
        assertEquals(setOf("dabba", "dabbe", "dibba", "dibbe", "dabbi", "dibbi"),
            groceryIntentGenericContainerWords)
    }

    @Test
    fun preparationKeepsLiteralSourceCountAndDisclosesUnknownOuterPackType(): Unit {
        val result = draft("2 dabba Nescafe 500 g", "Nescafe").prepareForReview()
        val item = result.items.single()
        val disclosure = " — Pack type not specified; review the suggested pack."
        assertEquals(Quantity.Count(2), item.quantity)
        assertTrue(item.retailPackCount)
        assertEquals("2 dabba Nescafe 500 g$disclosure", item.rawText)
        assertEquals(item.rawText, item.intentQuantityProvenance?.rawText)
        assertEquals(1, Regex(Regex.escape(disclosure)).findAll(item.rawText).count())
    }

    @Test
    fun legacyV2GenericProjectionCannotBypassTheSourceBoundOfferGate(): Unit {
        val item = draft("2 dabba Nescafe 500 g", "Nescafe").prepareForReview().items.single()
        val exact = candidate("Nescafe 500 g", "generic-container-exact")
        val assessedExact = assessSwiggyIntentQuantity(item, recommendations(exact))
        // V2 remains OFF. Its older generic projection has no immutable offer
        // receipt and must not inherit exact-cart authority from retailPackCount.
        assertFalse(isSwiggyCandidateCountCompatible(assessedExact, exact))
        assertTrue(resolveSwiggyCartQuantity(assessedExact, exact) is SwiggyCartQuantityResolution.Held)
        assertTrue(runCatching { swiggyRequestedCartQuantity(assessedExact, exact) }.isFailure)

        listOf("Nescafe 250 g x 2", "Nescafe 500 g x 2").forEachIndexed { index, title ->
            val multipack = candidate(title, "generic-container-multipack-$index")
            val assessed = assessSwiggyIntentQuantity(item, recommendations(multipack))
            assertFalse("$title must not pass both identity and count compatibility",
                isSwiggyCandidateAllowed(assessed, multipack) &&
                    isSwiggyCandidateCountCompatible(assessed, multipack))
        }
    }

    @Test
    fun genericContainerProofCannotInventRequiredFormOrReuseExcludedTags(): Unit {
        assertNull(row("2 dabba Nescafe 500 g", "Nescafe", required = listOf("sugar_free"))
            .genericContainerRange())
        assertNull(row("2 dabba Nescafe Dark Chocolate 500 g", "Nescafe Dark Chocolate",
            required = listOf("dark_chocolate"), excluded = listOf("chocolate")).genericContainerRange())
        assertTrue(row("2 dabba Nescafe Dark Chocolate 500 g", "Nescafe Dark Chocolate",
            required = listOf("dark_chocolate")).genericContainerRange() != null)
    }

    @Test
    fun rejectsAmbiguousPackagingMaterialAndCorrectionLanguage(): Unit {
        listOf(
            "2 dabba Nescafe tin 500 g" to "Nescafe tin",
            "2 dabba Nescafe steel 500 g" to "Nescafe steel",
            "2 dabba Nescafe airtight 500 g" to "Nescafe airtight",
            "2 dabba Nescafe 500 g instead" to "Nescafe",
            "2: dabba Nescafe 500 g" to "Nescafe",
            "2 dabba Nescafe 500 g please" to "Nescafe",
        ).forEach { (source, name) -> assertNull(source, row(source, name).genericContainerRange()) }
        assertNull(row("2 dabba Nescafe 500 g", "Nescafe", packaging = "box").genericContainerRange())
        assertTrue(runCatching { row("21 dabba Nescafe 500 g", "Nescafe") }.isFailure)
    }

    private fun row(
        source: String,
        name: String,
        packaging: String? = null,
        required: List<String> = emptyList(),
        excluded: List<String> = emptyList(),
    ): GroceryIntentReviewV2ReadyRow = draft(source, name, packaging, required, excluded).readyRows.single()

    private fun draft(
        source: String,
        name: String,
        packaging: String? = null,
        required: List<String> = emptyList(),
        excluded: List<String> = emptyList(),
        quantityUnit: String = "count",
    ): GroceryIntentReviewV2 {
        val start = 0
        val amount = Regex("[0-9]+(?:\\.[0-9]+)?").findAll(source).lastOrNull()?.value ?: "500"
        val unit = Regex("(?i)(?:kg|g|ml|l)\\b").find(source.substring(source.indexOf(amount) + amount.length))?.value
            ?.lowercase() ?: "g"
        val quantityText = Regex("^ *([\\p{L}0-9]+)").find(source)?.groupValues?.get(1) ?: "2"
        val quantity = requireNotNull(parseIntentCorrectionAmount(quantityText)).stripTrailingZeros().toPlainString()
        val instruction = source
        val body = """{"accepted":true,"draft":{"contract_version":"grocery-intent-v2","requires_review":true,"instruction":${q(instruction)},"source_item_count":1,"ready_rows":[{"row_index":0,"item":{"name":${q(name)},"quantity":$quantity,"quantity_unit":${q(quantityUnit)},"pack_value":$amount,"pack_unit":${q(unit)},"packaging":${packaging?.let(::q) ?: "null"},"required":${jsonArray(required)},"excluded":${jsonArray(excluded)},"source_text":${q(source)},"supporting_text":[],"needs_clarification":false,"quantity_text":${q(quantityText)},"pack_text":${q(amount)}},"evidence":{"primary":{"text":${q(source)},"start_utf16":$start,"end_utf16":${start + source.length}},"supporting":[]},"quantity_origin":"stated","amount_role_status":"matched"}],"blocked_rows":[]},"model":"test","promptVersion":"test","latencyMs":1,"usage":{}}"""
        return GroceryIntentReviewV2Response.parse(body, instruction).draft
    }

    private fun candidate(label: String, id: String) = RecommendationCandidate(
        spinId = id, label = label, skuId = id,
    )

    private fun recommendations(candidate: RecommendationCandidate) = Recommendations(
        candidates = listOf(candidate), suggested = candidate,
    )

    private fun jsonArray(values: List<String>): String = values.joinToString(",", "[", "]", transform = ::q)

    private fun q(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}
