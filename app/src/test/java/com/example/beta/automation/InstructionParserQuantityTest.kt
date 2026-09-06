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

    @Test
    fun parse_trailingWeightStaysWithSingleProduct() {
        val item = InstructionParser.parse("Order Amul salted butter 100 grams").single()

        assertEquals("amul salted butter", item.query)
        assertEquals(Quantity.Weight(100), item.quantity)
    }

    @Test
    fun parse_trailingMeasuresStayWithExplicitlySeparatedProducts() {
        val items = InstructionParser.parse("Amul salted butter 100 grams and Coke 2 litres")

        assertEquals(listOf("amul salted butter", "coke"), items.map { it.query })
        assertEquals(Quantity.Weight(100), items[0].quantity)
        assertEquals(Quantity.Volume(2000), items[1].quantity)
    }

    @Test
    fun parse_trailingPacketAndUnitKeepsCountsAndProductSpelling() {
        val items = InstructionParser.parse("vixks cough tablet 1 packet, 2 amul dark chocolate, mosquito patche 1 unit")

        assertEquals(listOf("vixks cough tablet", "amul dark chocolate", "mosquito patche"), items.map { it.query })
        assertEquals(Quantity.Count(1), items[0].quantity)
        assertEquals(Quantity.Count(2), items[1].quantity)
        assertEquals(Quantity.Count(1), items[2].quantity)
    }

    @Test
    fun parse_trailingPluralCountsRemainWithEachProductWithoutCommas() {
        val items = InstructionParser.parse("vicks 2 packets 2 chocolate 1 mosquito patches 1 unit")
        assertEquals(listOf("vicks", "chocolate", "mosquito patches"), items.map { it.query })
        assertEquals(listOf(Quantity.Count(2), Quantity.Count(2), Quantity.Count(1)), items.map { it.quantity })
    }

    @Test
    fun parse_leadingMultipackDescriptorStaysAProductDescriptor() {
        val item = InstructionParser.parse("6 pack juice").single()

        assertEquals("6 pack juice", item.query)
        assertEquals(Quantity.Default, item.quantity)
        assertEquals("6 pack juice", item.backendInputText())
    }

    @Test
    fun parse_preservesOversizedPacketCountsForProviderValidation() {
        assertEquals(Quantity.Count(21), InstructionParser.parse("mosquito patches 21 units").single().quantity)
        assertEquals(Quantity.Count(25), InstructionParser.parse("vicks 25 packets").single().quantity)
    }
}
