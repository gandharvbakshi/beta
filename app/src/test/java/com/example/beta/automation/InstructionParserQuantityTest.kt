package com.example.beta.automation

import org.junit.Assert.assertEquals
import org.junit.Test

class InstructionParserQuantityTest {
    @Test
    fun parse_weightCountAndCountItems() {
        val items = InstructionParser.parse("500 gms bhindi, 2 butter, 6 apples")

        assertEquals(listOf("bhindi", "butter", "apples"), items.map { it.query })
        assertEquals(Quantity.Weight(500), items[0].quantity)
        assertEquals(Quantity.Count(2), items[1].quantity)
        assertEquals(Quantity.Count(6), items[2].quantity)
    }

    @Test
    fun parse_compactWeightAndVolume() {
        val weight = InstructionParser.parse("1kg tomato").single()
        val volume = InstructionParser.parse("2 ltr coke").single()

        assertEquals("tomato", weight.query)
        assertEquals(Quantity.Weight(1000), weight.quantity)
        assertEquals("coke", volume.query)
        assertEquals(Quantity.Volume(2000), volume.quantity)
    }

    @Test
    fun parse_quantityBoundariesWithoutCommas() {
        val items = InstructionParser.parse("500g bhindi 2 butter 6 apples")

        assertEquals(listOf("bhindi", "butter", "apples"), items.map { it.query })
        assertEquals(Quantity.Weight(500), items[0].quantity)
        assertEquals(Quantity.Count(2), items[1].quantity)
        assertEquals(Quantity.Count(6), items[2].quantity)
    }
}
