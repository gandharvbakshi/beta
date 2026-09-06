package com.example.beta.automation

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.prepareSwiggyMcpItems
import com.example.beta.isSwiggyCandidateAllowed
import com.example.beta.swiggyMcpItemValidationMessage
import com.example.beta.swiggyRecommendationQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class InstructionParserMixedPackQuantityTest {
    @Test
    fun prepareSwiggyMcpItems_normalizes_packet_and_unit_counts_on_both_sides() {
        assertCountedMilk("doodh do packets")
        assertCountedMilk("do packets doodh")
        assertCountedMilk("milk two packets")
        assertCountedMilk("2 packets milk")
    }

    @Test
    fun prepareSwiggyMcpItems_preserves_count_and_measure_together_for_packed_volume_inputs() {
        val input = "2 500 ml milk"
        val item = prepareSwiggyMcpItems(input, lookup = { null }).single()

        assertEquals("500 ml milk", item.query)
        assertEquals(Quantity.Count(2), item.quantity)
        assertEquals("500 ml milk", item.strictMatchPhrase)
        assertEquals("2 500 ml milk", item.backendInputText())
        assertEquals("500 ml milk", swiggyRecommendationQuery(item))
        assertNull(swiggyMcpItemValidationMessage(input, listOf(item)))
    }

    @Test
    fun prepareSwiggyMcpItems_preserves_decimal_measured_packs_with_prefix_and_suffix_packet_counts() {
        assertDecimalCountedMilk("2 packets 0.5 litre milk")
        assertDecimalCountedMilk("0.5 litre milk do packets")
    }

    @Test
    fun prepareSwiggyMcpItems_splits_multiple_measured_items_with_integer_canonicalization() {
        val items = prepareSwiggyMcpItems("500 g rice 1 kg sugar", lookup = { null })

        assertEquals(listOf("rice", "sugar"), items.map { it.query })
        assertEquals(listOf(Quantity.Weight(500), Quantity.Weight(1000)), items.map { it.quantity })
        assertEquals(listOf("500 g rice", "1000 g sugar"), items.map { it.backendInputText() })
        assertNull(swiggyMcpItemValidationMessage("500 g rice 1 kg sugar", items))
    }

    @Test
    fun prepareSwiggyMcpItems_keeps_mixed_measured_list_items_and_exact_pack_match_phrases_separate() {
        val items = prepareSwiggyMcpItems("2 500 ml milk 1 kg rice", lookup = { null })

        assertEquals(listOf("500 ml milk", "rice"), items.map { it.query })
        assertEquals(listOf(Quantity.Count(2), Quantity.Weight(1000)), items.map { it.quantity })
        assertEquals("500 ml milk", items[0].strictMatchPhrase)
        assertEquals("2 500 ml milk", items[0].backendInputText())
        assertEquals("1000 g rice", items[1].backendInputText())
        assertNull(swiggyMcpItemValidationMessage("2 500 ml milk 1 kg rice", items))
    }

    @Test
    fun swiggy_candidate_allowed_accepts_exact_pack_and_rejects_competing_measures() {
        val item = prepareSwiggyMcpItems("2 500 ml milk", lookup = { null }).single()

        assertTrue(isSwiggyCandidateAllowed(item, candidate("Milk · 500 ml")))
        assertFalse(isSwiggyCandidateAllowed(item, candidate("Milk · 250 ml")))
        assertFalse(isSwiggyCandidateAllowed(item, candidate("Milk · 1 litre")))
        assertFalse(isSwiggyCandidateAllowed(item, candidate("Milk")))
        assertFalse(isSwiggyCandidateAllowed(item, candidate("Milk · 500 ml x 3")))
    }

    @Test
    fun prepareSwiggyMcpItems_keeps_fractional_measures_and_numeric_brands_unchanged() {
        val fractionalItems = prepareSwiggyMcpItems("aadha kilo doodh, dedh kg atta", lookup = { null })
        val numericBrandItems = prepareSwiggyMcpItems("7 up, 5 star chocolate, 24 mantra atta", lookup = { null })

        assertEquals(listOf("milk", "atta"), fractionalItems.map { it.query })
        assertEquals(listOf(Quantity.Weight(500), Quantity.Weight(1500)), fractionalItems.map { it.quantity })
        assertNull(swiggyMcpItemValidationMessage("aadha kilo doodh, dedh kg atta", fractionalItems))

        assertEquals(listOf("7 up", "5 star chocolate", "24 mantra atta"), numericBrandItems.map { it.query })
        assertEquals(listOf(Quantity.Default, Quantity.Default, Quantity.Default), numericBrandItems.map { it.quantity })
        assertNull(swiggyMcpItemValidationMessage("7 up, 5 star chocolate, 24 mantra atta", numericBrandItems))
    }

    @Test
    fun prepareSwiggyMcpItems_keeps_pack_descriptors_and_oversized_counts_in_expected_states() {
        val packDescriptor = prepareSwiggyMcpItems("2 pack milk", lookup = { null }).single()
        val oversized = prepareSwiggyMcpItems("21 packets milk", lookup = { null }).single()

        assertEquals("2 pack milk", packDescriptor.query)
        assertEquals(Quantity.Default, packDescriptor.quantity)
        assertEquals("2 pack milk", packDescriptor.backendInputText())

        assertEquals("milk", oversized.query)
        assertEquals(Quantity.Count(21), oversized.quantity)
        assertNull(swiggyMcpItemValidationMessage("2 pack milk", listOf(packDescriptor)))
        assertNotNull(swiggyMcpItemValidationMessage("21 packets milk", listOf(oversized)))
    }

    private fun assertCountedMilk(input: String) {
        val item = prepareSwiggyMcpItems(input, lookup = { null }).single()

        assertEquals("milk", item.query)
        assertEquals(Quantity.Count(2), item.quantity)
        assertEquals("2 milk", item.backendInputText())
        assertEquals("milk", swiggyRecommendationQuery(item))
        assertNull(swiggyMcpItemValidationMessage(input, listOf(item)))
    }

    private fun assertDecimalCountedMilk(input: String) {
        val item = prepareSwiggyMcpItems(input, lookup = { null }).single()

        assertEquals("500 ml milk", item.query)
        assertEquals(Quantity.Count(2), item.quantity)
        assertEquals("500 ml milk", item.strictMatchPhrase)
        assertEquals("2 500 ml milk", item.backendInputText())
        assertEquals("500 ml milk", swiggyRecommendationQuery(item))
        assertNull(swiggyMcpItemValidationMessage(input, listOf(item)))
    }

    private fun candidate(label: String): RecommendationCandidate = RecommendationCandidate(
        spinId = "spin-$label",
        label = label,
    )
}
