package com.example.beta.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstructionParserMilkPackRegressionTest {
    @Test
    fun parse_keeps_volume_and_count_together_when_spoken_order_is_unusual() {
        val items = InstructionParser.parse(
            "500 ml 5 milk tetta packs, dark chocolate, popcorn butter ready made",
        )

        assertEquals(3, items.size)
        assertEquals("500 ml milk tetra pack", items[0].query)
        assertEquals(Quantity.Count(5), items[0].quantity)
        assertEquals("500 ml milk tetra pack", items[0].strictMatchPhrase)
        assertEquals(listOf("dark chocolate", "popcorn butter ready made"), items.drop(1).map { it.query })
    }

    @Test
    fun parse_accepts_volume_before_or_after_milk_and_tetra_pack_words() {
        val inputs = listOf(
            "milk 500 ml 5 tetra packs",
            "500 ml milk 5 tetra packs",
        )

        inputs.forEach { input ->
            val item = InstructionParser.parse(input).single()
            assertEquals("500 ml milk tetra pack", item.query)
            assertEquals(Quantity.Count(5), item.quantity)
            assertEquals("500 ml milk tetra pack", item.strictMatchPhrase)
        }
    }

    @Test
    fun parse_keeps_count_when_tetra_pack_descriptor_is_before_or_after_count() {
        listOf("milk tetra pack 5", "5 milk tetra packs").forEach { input ->
            val item = InstructionParser.parse(input).single()
            assertEquals("milk tetra pack", item.query)
            assertEquals(Quantity.Count(5), item.quantity)
        }
    }

    @Test
    fun parse_does_not_emit_a_measure_token_as_a_product() {
        assertTrue(InstructionParser.parse("ml").isEmpty())
        assertTrue(InstructionParser.parse("500 ml").isEmpty())
    }

    @Test
    fun parse_only_normalizes_tetta_inside_a_milk_tetra_pack_request() {
        assertEquals("tetta", InstructionParser.parse("tetta").single().query)
        assertEquals("milk tetra pack", InstructionParser.parse("doodh 5 tetta packets").single().query)
    }

    @Test
    fun parse_does_not_drop_conflicting_measures_or_brand_identity() {
        val conflicting = InstructionParser.parse("500 ml 1 l 5 milk tetra packs").single()
        assertEquals("500 ml 1 l 5 milk tetra packs", conflicting.query)
        assertEquals(Quantity.Default, conflicting.quantity)
        assertTrue(conflicting.quantitySignal != null)

        val branded = InstructionParser.parse("5 Amul milk tetra packs").single()
        assertEquals("amul milk tetra pack", branded.query)
        assertEquals(Quantity.Count(5), branded.quantity)
    }

    @Test fun fractionalSizeAndBrandSurviveTogether() {
        val item = InstructionParser.parse("0.5 l 5 Amul milk tetra packs").single()
        assertEquals("500 ml amul milk tetra pack", item.query)
        assertEquals(Quantity.Count(5), item.quantity)
        assertEquals(item.query, item.strictMatchPhrase)
    }
}
