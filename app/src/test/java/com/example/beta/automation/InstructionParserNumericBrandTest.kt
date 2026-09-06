package com.example.beta.automation

import com.example.beta.prepareSwiggyMcpItems
import com.example.beta.swiggyRecommendationQuery
import com.example.beta.swiggyMcpItemValidationMessage
import com.example.beta.swiggyMatchesProductIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class InstructionParserNumericBrandTest {
    @Test
    fun compact_and_spaced_numeric_brands_match_both_catalogue_spellings() {
        for ((compact, spaced) in listOf("7up" to "7 Up", "5star chocolate" to "5 Star Chocolate", "24mantra atta" to "24 Mantra Atta")) {
            org.junit.Assert.assertTrue(swiggyMatchesProductIdentity(ParsedItem(compact, compact), spaced))
            org.junit.Assert.assertTrue(swiggyMatchesProductIdentity(ParsedItem(spaced, spaced), compact))
        }
    }
    @Test
    fun parse_preserves_spaced_numeric_brand_identity_without_guessing_counts() {
        val input = "7 up, 5 star chocolate, 24 mantra atta"
        val items = prepareSwiggyMcpItems(input, lookup = { null })
        assertNull(swiggyMcpItemValidationMessage(input, items))

        assertEquals(listOf("7 up", "5 star chocolate", "24 mantra atta"), items.map { it.query })
        assertEquals(listOf(Quantity.Default, Quantity.Default, Quantity.Default), items.map { it.quantity })
        assertEquals(listOf("7 up", "5 star chocolate", "24 mantra atta"), items.map(::swiggyRecommendationQuery))
        assertEquals(listOf("7 up", "5 star chocolate", "24 mantra atta"), items.map { it.backendInputText() })
    }

    @Test
    fun parse_keeps_numeric_brand_identity_after_real_prefix_and_trailing_packet_counts() {
        val input = "2 7 up, 5 star chocolate 2 packets, do 24 mantra atta"
        val items = prepareSwiggyMcpItems(input, lookup = { null })
        assertNull(swiggyMcpItemValidationMessage(input, items))

        assertEquals(listOf("7 up", "5 star chocolate", "24 mantra atta"), items.map { it.query })
        assertEquals(listOf(Quantity.Count(2), Quantity.Count(2), Quantity.Count(2)), items.map { it.quantity })
        assertEquals(listOf("2 7 up", "2 5 star chocolate", "2 24 mantra atta"), items.map { it.backendInputText() })
        assertEquals(listOf("7 up", "5 star chocolate", "24 mantra atta"), items.map(::swiggyRecommendationQuery))
    }

    @Test
    fun parse_keeps_leading_pack_descriptors_without_turning_them_into_counts() {
        val item = prepareSwiggyMcpItems("2 pack milk", lookup = { null }).single()

        assertEquals("2 pack milk", item.query)
        assertEquals(Quantity.Default, item.quantity)
        assertEquals("2 pack milk", item.backendInputText())
        assertEquals("2 pack milk", swiggyRecommendationQuery(item))
    }

    @Test
    fun parse_keeps_numeric_models_and_brands_unchanged() {
        val items = prepareSwiggyMcpItems("CR2032 battery, 9V battery, One Plus charger", lookup = { null })

        assertEquals(listOf("cr2032 battery", "9v battery", "oneplus charger"), items.map { it.query })
        assertEquals(listOf(Quantity.Default, Quantity.Default, Quantity.Default), items.map { it.quantity })
        assertEquals(listOf("cr2032 battery", "9v battery", "oneplus charger"), items.map { it.backendInputText() })
    }

    @Test
    fun numeric_brand_exception_does_not_allow_oversized_real_counts() {
        for (input in listOf("24 eggs", "24 7 up", "24 packets milk")) {
            assertNotNull(input, swiggyMcpItemValidationMessage(input, prepareSwiggyMcpItems(input, lookup = { null })))
        }
    }
}
