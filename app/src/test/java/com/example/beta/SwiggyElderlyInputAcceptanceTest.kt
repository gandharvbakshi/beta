package com.example.beta

import com.example.beta.automation.Quantity
import com.example.beta.automation.backendInputText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SwiggyElderlyInputAcceptanceTest {
    @Test
    fun parse_preserves_two_milk_as_a_counted_item() {
        val items = prepareSwiggyMcpItems("2 milk", lookup = { null })

        assertEquals(1, items.size)
        assertEquals("milk", items.single().query)
        assertEquals(Quantity.Count(2), items.single().quantity)
    }

    @Test
    fun parse_maps_two_milk_spoken_as_words_to_a_counted_milk_item() {
        val items = prepareSwiggyMcpItems("two milk", lookup = { null })

        assertEquals(1, items.size)
        assertEquals("milk", items.single().query)
        assertEquals(Quantity.Count(2), items.single().quantity)
    }

    @Test
    fun parse_maps_two_doodh_to_a_counted_milk_item() {
        val items = prepareSwiggyMcpItems("two doodh", lookup = { null })

        assertEquals(1, items.size)
        assertEquals("milk", items.single().query)
        assertEquals(Quantity.Count(2), items.single().quantity)
    }

    @Test
    fun parse_preserves_repeated_counted_milk_rows_withoutSumming() {
        val instruction = "2 milk, 3 milk"
        val items = prepareSwiggyMcpItems(instruction, lookup = { null })

        assertEquals(listOf("milk", "milk"), items.map { it.query })
        assertEquals(listOf(Quantity.Count(2), Quantity.Count(3)), items.map { it.quantity })
        assertEquals(listOf("2 milk", "3 milk"), items.map { it.backendInputText() })
        assertEquals(listOf("2 milk", "3 milk"), items.map { it.intentQuantityProvenance?.primaryText })
        assertNotNull(swiggyMcpItemValidationMessage(instruction, items))
    }
}
