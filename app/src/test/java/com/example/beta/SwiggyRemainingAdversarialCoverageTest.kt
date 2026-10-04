package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.SwiggyMcpClient.SwiggyAddress
import com.example.beta.automation.InstructionParser
import com.example.beta.automation.ParsedItem
import com.example.beta.automation.Quantity
import com.example.beta.automation.backendInputText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exact offline fixtures for the remaining adversarial cases.  These tests use
 * only parser/ranking/presentation helpers and never call a provider or alter
 * a cart, address, order, or account.
 */
class SwiggyRemainingAdversarialCoverageTest {
    private val now = 1_800_000_000_000L

    @Test
    fun h01_history_candidate_is_not_reused_when_fresh_catalogue_identity_is_available() {
        val historicalAmul = candidate("history-amul-500", "Amul Butter 500 g")
        val freshAmul = candidate("catalog-amul-500", "Amul Butter 500 g")
        val item = ParsedItem(rawText = "butter", query = "butter")

        val selected = swiggyDefaultSuggestion(
            item = item,
            usable = listOf(freshAmul),
            preferred = historicalAmul,
        )

        assertEquals("catalog-amul-500", selected.spinId)
        assertNotEquals("a historical identity must not be selected", historicalAmul.spinId, selected.spinId)
        assertTrue(isSwiggyCandidateAllowed(item, selected))
    }

    @Test
    fun l08_maggi_descriptor_stays_with_product_while_apples_keeps_its_count() {
        val items = InstructionParser.parse("2 Maggi 2 Minute noodles and 3 apples")

        assertEquals(2, items.size)
        assertEquals(listOf("maggi 2 minute noodles", "apples"), items.map { it.query })
        assertEquals(listOf(Quantity.Count(2), Quantity.Count(3)), items.map { it.quantity })
        assertEquals("2 maggi 2 minute noodles", items[0].backendInputText())
        assertEquals("3 apples", items[1].backendInputText())
    }

    @Test
    fun b01_exact_five_item_basket_preserves_order_measures_and_literal_pack_descriptor() {
        val items = InstructionParser.parse(
            "2 butter, 500 g rice, 2 l milk, 6 eggs, 24 pack paper towels",
        )

        assertEquals(5, items.size)
        assertEquals(
            listOf("butter", "rice", "milk", "eggs", "24 pack paper towels"),
            items.map { it.query },
        )
        assertEquals(
            listOf(
                Quantity.Count(2),
                Quantity.Weight(500),
                Quantity.Volume(2_000),
                Quantity.Count(6),
                Quantity.Default,
            ),
            items.map { it.quantity },
        )
        assertEquals(
            listOf(
                "2 butter",
                "500 g rice",
                "2000 ml milk",
                "6 eggs",
                "24 pack paper towels",
            ),
            items.map { it.backendInputText() },
        )
    }

    @Test
    fun b02_exact_ten_item_basket_is_a_single_valid_review_input_in_order() {
        val instruction = "milk, butter, rice, sugar, apples, bananas, eggs, dark chocolate, popcorn, tissues"
        val items = prepareSwiggyMcpItems(instruction, lookup = { null })

        assertEquals(10, items.size)
        assertEquals(
            listOf("milk", "butter", "rice", "sugar", "apples", "bananas", "eggs", "dark chocolate", "popcorn", "tissues"),
            items.map { it.query },
        )
        assertNull("the bounded list is reviewable without a validation error", swiggyMcpItemValidationMessage(instruction, items))
        assertTrue("every parsed row remains a default one-item request", items.all { it.quantity == Quantity.Default })
    }

    @Test
    fun u08_city_only_location_keeps_same_flat_different_buildings_distinct_and_unknown() {
        val first = address("home-maple", "Flat 12, Maple Court, Bengaluru, 560041")
        val second = address("home-orchard", "Flat 12, Orchard Court, Bengaluru, 560041")
        val ranked = rankSwiggyAddresses(
            addresses = listOf(first, second),
            usageByAddressId = emptyMap(),
            locationHint = SwiggyLocationHint(locality = "Bengaluru"),
            nowMillis = now,
        )

        assertEquals(listOf("home-maple", "home-orchard"), ranked.map { it.address.id })
        assertTrue(ranked.all { it.locationAssessment == SwiggyLocationAssessment.UNKNOWN })
        assertNotEquals(swiggyAddressPresentation(first).detail, swiggyAddressPresentation(second).detail)
        assertTrue(swiggyAddressSuggestionPrompt(SwiggyLocationAssessment.UNKNOWN).contains("check", ignoreCase = true))
    }

    @Test
    fun u09_location_off_keeps_all_three_saved_addresses_visible_without_nearby_claim() {
        val addresses = listOf(
            address("saved-one", "1 First Road, Bengaluru"),
            address("saved-two", "2 Second Road, Bengaluru"),
            address("saved-three", "3 Third Road, Bengaluru"),
        )
        val ranked = rankSwiggyAddresses(
            addresses = addresses,
            usageByAddressId = emptyMap(),
            locationHint = null,
            nowMillis = now,
        )

        assertEquals(addresses.map { it.id }, ranked.map { it.address.id })
        assertTrue(ranked.all { it.locationAssessment == SwiggyLocationAssessment.UNKNOWN })
        val notice = swiggyAddressLocationNotice(SwiggyLocationAssessment.UNKNOWN)
        assertTrue(notice.contains("still use a saved address"))
        assertFalse(notice.contains("matches your current area", ignoreCase = true))
    }

    @Test
    fun u10_area_mismatch_for_all_saved_addresses_requires_confirmation() {
        // The pure helper knows postal-area mismatch, not physical GPS distance;
        // this fixture intentionally asserts only the deterministic warning.
        val addresses = listOf(
            address("far-one", "1 First Road, Bengaluru, 560041"),
            address("far-two", "2 Second Road, Bengaluru, 560047"),
            address("far-three", "3 Third Road, Bengaluru, 560061"),
        )
        val ranked = rankSwiggyAddresses(
            addresses = addresses,
            usageByAddressId = emptyMap(),
            locationHint = SwiggyLocationHint(postalCode = "560099"),
            nowMillis = now,
        )

        assertTrue(ranked.all { it.locationAssessment == SwiggyLocationAssessment.NOT_MATCHED })
        val notice = swiggyAddressLocationNotice(SwiggyLocationAssessment.NOT_MATCHED)
        assertTrue(notice.contains("confirm", ignoreCase = true))
        assertTrue(swiggyAddressSuggestionPrompt(SwiggyLocationAssessment.NOT_MATCHED).contains("away", ignoreCase = true))
    }

    private fun candidate(spinId: String, label: String) = RecommendationCandidate(
        spinId = spinId,
        label = label,
    )

    private fun address(id: String, label: String) = SwiggyAddress(
        id = id,
        label = label,
        normalizedLabel = label,
        shortLabel = "Home — Bengaluru",
    )
}
