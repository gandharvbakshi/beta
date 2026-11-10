package com.example.beta.automation

import com.example.beta.validCompositeRetailReceipt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline parser oracles for the currently uncovered rows in
 * SWIGGY_ADVERSARIAL_TEST_PROMPTS_20260916.md.
 *
 * These tests intentionally stop at ParsedItem values. They do not call a
 * provider, mutate a cart, or rely on a credential.
 */
class SwiggyAdversarialParserTest {
    @Test
    fun p0_06_milk_tetra_pack_without_size_preserves_form_without_quantity() {
        val items = InstructionParser.parse("milk tetra pack")

        assertEquals(1, items.size)
        assertEquals("milk tetra pack", items.single().query)
        assertEquals(Quantity.Default, items.single().quantity)
    }

    @Test
    fun p0_08_two_by_500_ml_is_a_variant_descriptor_not_two_purchase_units() {
        val items = InstructionParser.parse("2 x 500 ml milk")

        assertEquals(listOf("2 x 500 ml milk"), items.map { it.query })
        assertEquals(listOf(Quantity.Default), items.map { it.quantity })
        assertEquals(listOf("2 x 500 ml milk"), items.map { it.backendInputText() })
    }

    @Test
    fun p0_12_empty_input_fails_closed() {
        assertTrue(InstructionParser.parse("").isEmpty())
    }

    @Test
    fun p0_12_measure_only_input_fails_closed() {
        assertTrue(InstructionParser.parse("ml").isEmpty())
        assertTrue(InstructionParser.parse("500 ml").isEmpty())
    }

    @Test
    fun p0_12_count_only_input_fails_closed() {
        assertTrue(InstructionParser.parse("2 packets").isEmpty())
    }

    @Test
    fun p0_12_trailing_spoken_count_is_not_invented() {
        // The current grammar supports leading spoken counts, not trailing
        // counts. Keep this unresolved rather than inventing a count of two.
        val ambiguous = InstructionParser.parse("milk two")
        assertEquals(1, ambiguous.size)
        assertEquals("milk two", ambiguous.single().query)
        assertFalse(ambiguous.single().quantity is Quantity.Count)
    }

    @Test
    fun l07_hinglish_tetra_form_keeps_count_and_pack_intent() {
        val tetra = InstructionParser.parse("doodh tetra pack paanch").single()
        assertEquals("milk tetra pack", tetra.query)
        assertEquals(Quantity.Count(5), tetra.quantity)
    }

    @Test
    fun l07_hinglish_pouch_form_keeps_distinct_form_and_count() {
        val pouch = InstructionParser.parse("doodh pouch paanch").single()

        assertEquals("milk pouch", pouch.query)
        assertEquals(Quantity.Count(5), pouch.quantity)
    }

    @Test
    fun pack_counts_match_safely_across_leading_and_trailing_numeric_or_spoken_forms() {
        val failures = mutableListOf<String>()
        val brandPrefixRecognized = InstructionParser.isNumericBrandPrefix("24 mantra atta packs")
        if (!brandPrefixRecognized) failures += "brand-prefix parserState=recognized:$brandPrefixRecognized"
        data class Case(
            val input: String,
            val query: String,
            val quantity: Quantity,
            val needsReview: Boolean = false,
        )
        val cases = listOf(
            Case("5 milk pouch", "milk pouch", Quantity.Count(5)),
            Case("milk pouch 5", "milk pouch", Quantity.Count(5)),
            Case("milk pouch paanch", "milk pouch", Quantity.Count(5)),
            // Equal repeated amount atoms remain unresolved, even when they agree.
            Case("5 milk pouch paanch", "milk pouch", Quantity.Count(5), needsReview = true),
            Case("do milk pouch 2", "milk pouch", Quantity.Count(2), needsReview = true),
            Case("500 ml milk packs 5", "500 ml milk", Quantity.Count(5)),
            Case("1 l milk packs 5", "1000 ml milk", Quantity.Count(5)),
            // The two outer-looking amounts do not prove whether the 2 is an inner pack role.
            Case("2 x 500 ml milk packs paanch", "milk 2 x 500 ml", Quantity.Count(5)),
            Case("5 star chocolate packs 2", "5 star chocolate", Quantity.Count(2)),
            Case("24 mantra atta packs 2", "24 mantra atta", Quantity.Count(2)),
            Case("5 milk tetra pack", "milk tetra pack", Quantity.Count(5)),
            Case("milk tetra pack 5", "milk tetra pack", Quantity.Count(5)),
            Case("milk tetra pack paanch", "milk tetra pack", Quantity.Count(5)),
            Case("5 milk tetra pack paanch", "milk tetra pack", Quantity.Count(5), needsReview = true),
            Case("milk tetta packs 5", "milk tetra pack", Quantity.Count(5)),
            Case("milk tetra packets paanch", "milk tetra pack", Quantity.Count(5)),
        )

        cases.forEachIndexed { index, case ->
            val items = InstructionParser.parse(case.input)
            val parsed = items.singleOrNull()
            val parserState = "itemCount=${items.size}, quantities=${items.map { it.quantity }}, " +
                "signalCount=${items.count { it.quantitySignal != null }}, " +
                "proofRequiredCount=${items.count { it.requiresIntentQuantityProof }}, " +
                "queryMatches=${parsed?.query == case.query}, " +
                "quantityMatches=${parsed?.quantity == case.quantity}, " +
                "signalClear=${parsed?.quantitySignal == null}"
            val failedChecks = mutableListOf<String>()
            if (items.size != 1) {
                failedChecks += "itemCount"
            } else {
                val item = items.single()
                if (item.query != case.query) failedChecks += "query"
                if (item.quantity != case.quantity) failedChecks += "quantity"
                if ((item.quantitySignal != null) != case.needsReview) failedChecks += "quantitySignal"
                if (case.input == "2 x 500 ml milk packs paanch") {
                    val proof = item.intentQuantityProvenance
                    val receipt = item.validCompositeRetailReceipt()
                    if (item.rawText != case.input) failedChecks += "compositeRawText"
                    if (!item.requiresIntentQuantityProof) failedChecks += "compositeProofRequired"
                    if (proof?.sourceCountRole != IntentSourceCountRole.COMPOSITE_RETAIL_OFFER) {
                        failedChecks += "compositeSourceCountRole"
                    }
                    if (receipt == null) {
                        failedChecks += "compositeReceipt"
                    } else {
                        if (receipt.requestedCount.value.intValueExact() != 5) failedChecks += "compositeRequestedCount"
                        if (receipt.skuFactor.value.intValueExact() != 2) failedChecks += "compositeFactor"
                        if (receipt.innerMeasure.value.compareTo(java.math.BigDecimal("500")) != 0 ||
                            receipt.innerMeasure.unit != "ml") failedChecks += "compositeInnerMeasure"
                    }
                }
            }
            if (failedChecks.isNotEmpty()) {
                failures += "case[$index] failed=${failedChecks.joinToString()} parserState={$parserState}"
            }
        }
        assertTrue(failures.joinToString(separator = "\n"), failures.isEmpty())
    }

    @Test
    fun out_of_range_leading_pack_count_stays_visible_and_flagged() {
        listOf("100 milk packs 5", "100 milk packs5").forEach { input ->
            val item = InstructionParser.parse(input).single()

            assertEquals(input, item.query)
            assertEquals(Quantity.Default, item.quantity)
            assertTrue(item.quantitySignal?.contains("conflicting pack quantities") == true)
        }
    }

    @Test
    fun conflicting_leading_and_trailing_pack_counts_are_preserved_and_flagged() {
        val cases = listOf(
            "2 milk pouch paanch",
            "do milk pouch paanch",
            "2 milk pouch 5",
            "do milk tetra pack paanch",
        )

        cases.forEach { input ->
            val items = InstructionParser.parse(input)
            assertEquals("$input item count", 1, items.size)
            val item = items.single()
            assertEquals("$input quantity", Quantity.Default, item.quantity)
            assertTrue("$input signal", item.quantitySignal?.contains("conflicting pack quantities") == true)
            assertTrue("$input identity", item.query.contains("milk"))
            assertTrue("$input form", item.query.contains("pouch") || item.query.contains("tetra pack"))
            assertFalse("$input phantom split", item.query == "milk")
        }
    }

    @Test
    fun b03_semicolon_and_newline_boundaries_keep_quantities_with_their_items() {
        val items = InstructionParser.parse("500 ml milk, 1 kg rice; 3 apples\n2 packets butter")

        assertEquals(listOf("milk", "rice", "apples", "butter"), items.map { it.query })
        assertEquals(
            listOf(
                Quantity.Volume(500),
                Quantity.Weight(1000),
                Quantity.Count(3),
                Quantity.Count(2),
            ),
            items.map { it.quantity },
        )
        assertEquals(
            listOf("500 ml milk", "1000 g rice", "3 apples", "2 butter"),
            items.map { it.backendInputText() },
        )
    }

    @Test
    fun b04_separator_policy_keeps_hyphenated_preparation_and_numeric_brand_intact() {
        val items = InstructionParser.parse("milk & butter, ready-to-eat popcorn, and 5 star chocolate")

        assertEquals(listOf("milk", "butter", "ready to eat popcorn", "5 star chocolate"), items.map { it.query })
        assertEquals(
            listOf("milk", "butter", "ready-to-eat popcorn", "5 star chocolate"),
            items.map { it.rawText },
        )
        assertEquals(Quantity.Default, items[2].quantity)
        assertEquals(Quantity.Default, items[3].quantity)
    }
}
