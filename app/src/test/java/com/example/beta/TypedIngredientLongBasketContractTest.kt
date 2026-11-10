package com.example.beta

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shared Python-generated wire contract, independently source-checked by Android. */
class TypedIngredientLongBasketContractTest {
    @Test fun twentySourceRowsStayAccountedForButMissingCatalogueAmountsCannotBecomeExact() {
        val relative = "scripts/fixtures/intent_typed_contract_v1.json"
        val file = listOf(File("../$relative"), File(relative)).first(File::isFile)
        val fixture = (JsonParser(file.readText()).parseStrict() as JsonValue.Obj).members
        val instruction = (fixture.getValue("instruction") as JsonValue.Str).value
        val review = GroceryIntentReviewV2.parse((fixture.getValue("review") as JsonValue.Obj).members, instruction)
        val prepared = review.prepareForReview(allowPropertyObligations = true,
            allowIngredientPredicates = true, allowTypedIngredientPredicates = true)
        assertEquals(20, prepared.items.size)
        assertTrue(prepared.unresolvedText.isEmpty())
        assertEquals(2, prepared.items.count { it.ingredientProvenance != null })
        val expected = prepared.items.map { it.ingredientProvenance?.predicates().orEmpty() }
        val resultRows = prepared.items.mapIndexed { index, item ->
            val predicates = expected[index]
            val label = (predicates.joinToString(" ") { "No ${it.literalExclusion()}" } + " " + item.query).trim()
            val echo = predicates.joinToString(",") { """{"operator":"${it.toPayload().getValue("operator")}","operand":"${it.operand}"}""" }
            """{"query":"${item.query}","ingredientPredicates":[$echo],"candidates":[{"spinId":"s$index","skuId":"k$index","label":"$label","catalogueIngredients":{"version":1,"source":"search_variation","spinId":"s$index","skuId":"k$index","fields":{"name":"$label"}}}]}"""
        }
        val results = SwiggyMcpClient.parseRecommendationBatch(
            """{"ingredientPredicateVersion":1,"results":[${resultRows.joinToString(",") }]}""", expected)
        assertEquals(20, results.size)
        val assessedItems = prepared.items.mapIndexed { index, item ->
            val candidate = results[index].candidates.single()
            val assessed = assessSwiggyIntentQuantity(item, results[index])
            assertTrue("row $index", isSwiggyCandidateAllowed(assessed, candidate))
            assertTrue("row $index", swiggyIntentQuantityCandidateChecked(assessed, candidate))
            assertUnqualifiedCountNeedsReview(assessed, candidate, 1)
            assessed
        }
        assertEquals(20, assessedItems.size)
        val first = assessSwiggyIntentQuantity(prepared.items.first(), results.first())
        assertFalse(swiggyIntentQuantityCandidateChecked(first,
            results.first().candidates.single().copy(catalogueIngredients = null)))
    }
}
