package com.example.beta

import com.example.beta.automation.Quantity
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentQuantityInterpretationTest {
    @Test
    fun clauseLeadingDoAmbiguityWarnsAcrossProductsAndPreservesTheSelectedInterpretation() {
        val packInterpretation = item(
            name = "milk", source = "do 1 litre milk", quantity = "2", quantityUnit = "count",
            packValue = "1", packUnit = "l",
        )
        val totalInterpretation = item(
            name = "ghee", source = "do 1 litre ghee", quantity = "1", quantityUnit = "l",
        )

        val warnings = reviews(listOf(packInterpretation, totalInterpretation))
        assertEquals(2, warnings.size)
        assertTrue(warnings[0].contains("Beta understood 2 pack(s), each 1 l for milk"))
        assertTrue(warnings[1].contains("Beta understood 1 l in total for ghee"))
        assertTrue(warnings.all { it.contains("can mean 'two' or 'give'") })
    }

    @Test
    fun ordinaryDoImperativeSuffixNominalAndSimilarWordsDoNotWarn() {
        val ordinary = listOf(
            item("milk", "do litre milk"),
            item("milk", "1 litre milk de do"),
            item("milk", "double 1 litre milk"),
            item("milk", "dozen 1 litre milk"),
        )
        assertTrue(reviews(ordinary).isEmpty())
    }

    @Test
    fun warningScopeIsPerItemAndDoesNotRewriteTheChosenAmount() {
        val ambiguous = item(
            name = "milk", source = "do 1 litre milk", quantity = "1", quantityUnit = "l",
        )
        val ordinary = item("rice", "2 kg rice", quantity = "2", quantityUnit = "kg")
        val warnings = reviews(listOf(ordinary, ambiguous))
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("Beta understood 1 l in total for milk"))
        assertEquals(BigDecimal("2"), ordinary.quantity)
        assertEquals(BigDecimal("1"), ambiguous.quantity)
        assertEquals("l", ambiguous.quantityUnit)
    }

    @Test
    fun modelQuoteBoundariesCannotHideTheAmbiguousAmountFromCombinedReview() {
        val instruction = "rice do 1 litre milk"
        val rows = listOf(item("rice", "rice do", "2", "count"), item("milk", "1 litre milk", "1", "l"))
        val prepared = GroceryIntentDraft(
            contractVersion = "grocery-intent-v1", requiresReview = true,
            instruction = instruction, items = rows, modelNeedsClarification = false,
        ).prepareForReview()
        val warning = prepared.unresolvedText.single()
        assertTrue(warning.contains("Quantity to check: do 1 litre"))
        assertTrue(warning.contains("2 count for rice"))
        assertTrue(warning.contains("1 l in total for milk"))
        val gate = GroceryIntentReviewGate()
        gate.prepare(prepared.unresolvedText)
        assertFalse(gate.canApply())
    }

    @Test
    fun croppedOutHomographStillProducesAnAmountInterpretationWarning() {
        val rows = listOf(item("milk", "1 litre milk", "1", "l"))
        val warnings = groceryIntentQuantityInterpretationReviews("do 1 litre milk", rows)
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("1 l in total for milk"))
    }

    @Test
    fun existingClosedRequestGrammarAvoidsUnnecessaryQuantityAcknowledgements() {
        val row = item("rice", "2 kg rice", "2", "kg")
        listOf("la do", "mangwa do", "cart mein daal do").forEach { request ->
            assertTrue(groceryIntentQuantityInterpretationReviews("$request 2 kg rice", listOf(row)).isEmpty())
        }
        assertEquals(1, groceryIntentQuantityInterpretationReviews("aur do 2 kg rice", listOf(row)).size)
    }

    private fun reviews(items: List<GroceryIntentItem>) =
        groceryIntentQuantityInterpretationReviews(items.joinToString(", ") { it.sourceText }, items)

    @Test
    fun v1PreparationShowsWarningWithoutChangingItemsAndReviewGateRequiresFreshAcknowledgement() {
        val source = "do 1 litre milk"
        val body = """{"accepted":true,"draft":{"contract_version":"grocery-intent-v1","requires_review":true,"instruction":"do 1 litre milk","items":[{"name":"milk","quantity":2,"quantity_unit":"count","pack_value":1,"pack_unit":"l","required":[],"excluded":[],"source_text":"do 1 litre milk","needs_clarification":false}],"model_needs_clarification":false},"model":"fixture","promptVersion":"fixture","latencyMs":0,"usage":{}}"""
        val prepared = GroceryIntentResponse.parse(body, source).draft.prepareForReview()

        assertEquals(1, prepared.items.size)
        assertEquals("do 1 litre milk", prepared.items.single().rawText)
        assertEquals(Quantity.Count(2), prepared.items.single().quantity)
        assertEquals(1, prepared.unresolvedText.size)
        assertTrue(prepared.unresolvedText.single().contains("Beta understood 2 pack(s), each 1 l for milk"))

        val gate = GroceryIntentReviewGate()
        gate.prepare(prepared.unresolvedText)
        assertFalse(gate.canApply())
        gate.acknowledge(true)
        assertTrue(gate.canApply())
        gate.beginReview()
        assertFalse(gate.canApply())
    }

    private fun item(
        name: String,
        source: String,
        quantity: String = "1",
        quantityUnit: String = "count",
        packValue: String? = null,
        packUnit: String = "unspecified",
    ) = GroceryIntentItem(
        name = name,
        quantity = BigDecimal(quantity),
        quantityUnit = quantityUnit,
        packValue = packValue?.let { BigDecimal(it) },
        packUnit = packUnit,
        required = emptyList(),
        excluded = emptyList(),
        sourceText = source,
        needsClarification = false,
    )
}
