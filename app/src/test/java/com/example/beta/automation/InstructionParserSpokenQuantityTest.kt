package com.example.beta.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstructionParserSpokenQuantityTest {
    @Test
    fun normalize_spoken_counts_in_english_hindi_and_kannada() {
        assertEquals("2 milk", InstructionParser.normalizeSpokenQuantitySegment("two milk").text)
        assertEquals("1 milk", InstructionParser.normalizeSpokenQuantitySegment("ek milk").text)
        assertEquals("3 milk", InstructionParser.normalizeSpokenQuantitySegment("mooru milk").text)
    }

    @Test
    fun normalize_leaves_brand_tokens_unaltered() {
        assertEquals("oneplus charger", InstructionParser.normalizeSpokenQuantitySegment("oneplus charger").text)
        assertEquals("7up", InstructionParser.normalizeSpokenQuantitySegment("7up").text)
        assertNull(InstructionParser.normalizeSpokenQuantitySegment("oneplus charger").quantitySignal)
    }

    @Test
    fun parse_handles_fractional_measures_without_splitting_and() {
        val half = InstructionParser.parse("half kg atta")
        val oneAndHalf = InstructionParser.parse("one and a half litre milk")

        assertEquals(1, half.size)
        assertEquals("atta", half.single().query)
        assertEquals(Quantity.Weight(500), half.single().quantity)

        assertEquals(1, oneAndHalf.size)
        assertEquals("milk", oneAndHalf.single().query)
        assertEquals(Quantity.Volume(1500), oneAndHalf.single().quantity)
    }

    @Test
    fun parse_signals_out_of_range_spoken_numbers_instead_of_defaulting() {
        val twentyOne = InstructionParser.parse("twenty one milk")
        val hundred = InstructionParser.parse("hundred eggs")

        assertEquals(1, twentyOne.size)
        assertEquals("milk", twentyOne.single().query)
        assertEquals(Quantity.Default, twentyOne.single().quantity)
        assertTrue(twentyOne.single().quantitySignal?.contains("twenty one") == true)

        assertEquals(1, hundred.size)
        assertEquals("eggs", hundred.single().query)
        assertEquals(Quantity.Default, hundred.single().quantity)
        assertTrue(hundred.single().quantitySignal?.contains("hundred") == true)
    }

    @Test
    fun parse_merges_repeated_counted_queries_by_sum() {
        val items = InstructionParser.parse("two milk, three milk, milk")

        assertEquals(2, items.size)
        assertEquals("milk", items[0].query)
        assertEquals(Quantity.Count(5), items[0].quantity)
        assertEquals("milk", items[1].query)
        assertEquals(Quantity.Default, items[1].quantity)
    }

    @Test
    fun parse_handles_simple_food_aliases() {
        assertEquals("eggs", InstructionParser.parse("ande").single().query)
        assertEquals("eggs", InstructionParser.parse("anda").single().query)

        val items = InstructionParser.parse("chawal, cheeni, namak, aata")

        assertEquals(
            listOf("rice", "sugar", "salt", "atta"),
            items.map { it.query }
        )
    }
}
