package com.example.beta.automation

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
        assertTrue(InstructionParser.isNumericBrandPrefix("24 mantra atta packs"))
        val cases = listOf(
            "5 milk pouch" to ("milk pouch" to Quantity.Count(5)),
            "milk pouch 5" to ("milk pouch" to Quantity.Count(5)),
            "milk pouch paanch" to ("milk pouch" to Quantity.Count(5)),
            "5 milk pouch paanch" to ("milk pouch" to Quantity.Count(5)),
            "do milk pouch 2" to ("milk pouch" to Quantity.Count(2)),
            "500 ml milk packs 5" to ("500 ml milk" to Quantity.Count(5)),
            "1 l milk packs 5" to ("1000 ml milk" to Quantity.Count(5)),
            "2 x 500 ml milk packs paanch" to ("2 x 500 ml milk" to Quantity.Count(5)),
            "5 star chocolate packs 2" to ("5 star chocolate" to Quantity.Count(2)),
            "24 mantra atta packs 2" to ("24 mantra atta" to Quantity.Count(2)),
            "5 milk tetra pack" to ("milk tetra pack" to Quantity.Count(5)),
            "milk tetra pack 5" to ("milk tetra pack" to Quantity.Count(5)),
            "milk tetra pack paanch" to ("milk tetra pack" to Quantity.Count(5)),
            "5 milk tetra pack paanch" to ("milk tetra pack" to Quantity.Count(5)),
            "milk tetta packs 5" to ("milk tetra pack" to Quantity.Count(5)),
            "milk tetra packets paanch" to ("milk tetra pack" to Quantity.Count(5)),
        )

        cases.forEach { (input, expected) ->
            val (expectedQuery, expectedQuantity) = expected
            val items = InstructionParser.parse(input)
            assertEquals("$input item count", 1, items.size)
            val item = items.single()
            assertEquals("$input query quantity=${item.quantity} raw=${item.rawText}", expectedQuery, item.query)
            assertEquals("$input quantity", expectedQuantity, item.quantity)
            assertEquals("$input signal", null, item.quantitySignal)
        }
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
