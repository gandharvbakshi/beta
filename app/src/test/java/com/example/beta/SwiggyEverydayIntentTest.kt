package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.automation.ParsedItem
import com.example.beta.automation.InstructionParser
import com.example.beta.automation.Quantity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Offline catalogue identity/exclusion fixtures; no provider or cart calls. */
class SwiggyEverydayIntentTest {
    @Test
    fun everyday_spoken_counts_pass_app_validation_not_only_parser() {
        listOf("Order one samosa", "I want one lean milk", "One milk with less cream",
            "Order one tetra pack milk", "Can you please order two samosa", "ek tetra pack doodh").forEach { command ->
            val parsed = prepareSwiggyMcpItems(command, lookup = { null })
            assertEquals(command, 1, parsed.size)
            assertNull(command, swiggyMcpItemValidationMessage(command, parsed))
        }
    }

    @Test
    fun exact_piece_arithmetic_never_rounds_up_or_ignores_conflicting_counts() {
        val two = ParsedItem("two samosa", "samosa", Quantity.Count(2))
        val single = candidate("Fresh Samosa Ready to Eat 1 pc")
        assertTrue(isSwiggyCandidateCountCompatible(two, single))
        assertEquals(2, swiggyRequestedCartQuantity(two, single))
        assertFalse(isSwiggyCandidateCountCompatible(two, candidate("Samosa 3 pcs")))
        assertFalse(isSwiggyCandidateCountCompatible(two, candidate("Samosa 2 pcs — 6 pcs")))
        val dozen = ParsedItem("12 eggs", "eggs", Quantity.Count(12))
        val six = candidate("Eggs 6 pcs")
        assertTrue(isSwiggyCandidateCountCompatible(dozen, six))
        assertEquals(2, swiggyRequestedCartQuantity(dozen, six))
    }

    @Test
    fun explicit_retail_pack_counts_do_not_multiply_or_divide_inner_pieces() {
        val oneCarton = InstructionParser.parse("one tetra pack milk").single()
        assertFalse(isSwiggyCandidateCountCompatible(oneCarton, candidate("Milk Tetra Pack 6 x 200 ml")))
        val frozenPack = InstructionParser.parse("one packet frozen samosa").single()
        val frozen = candidate("Frozen Samosa 6 pcs")
        assertTrue(frozenPack.retailPackCount)
        assertTrue(isSwiggyCandidateAllowed(frozenPack, frozen))
        assertTrue(isSwiggyCandidateCountCompatible(frozenPack, frozen))
        assertEquals(1, swiggyRequestedCartQuantity(frozenPack, frozen))
        val five = InstructionParser.parse("2 x 500 ml milk packs paanch").single()
        val twinPack = candidate("Milk 2 x 500 ml")
        assertTrue(five.retailPackCount)
        assertTrue(isSwiggyCandidateAllowed(five, twinPack))
        assertTrue(isSwiggyCandidateCountCompatible(five, twinPack))
        assertEquals(5, swiggyRequestedCartQuantity(five, twinPack))
    }
    @Test
    fun sugar_free_spellings_are_equivalent_for_tea() {
        listOf("Sugar-Free Tea", "Sugar Free Tea", "Sugarfree Tea").forEach { label ->
            assertAllowed("sugar-free tea", label)
        }
    }

    @Test
    fun sugar_free_tea_rejects_generic_no_added_sugar_and_contradictory_titles() {
        assertAllowed("sugar-free tea", "Sugar-Free Tea")
        assertBlocked("sugar-free tea", "Green Tea")
        assertBlocked("sugar-free tea", "Tea No Added Sugar")
        assertBlocked("sugar-free tea", "Sugar Free Tea With Sugar")
    }

    @Test
    fun standalone_sugar_free_requires_explicit_sweetener_evidence() {
        assertAllowed("sugar free", "Sugar Free Sweetener Tablets")
        assertAllowed("sugar free", "Sugar Free Sweetener Sachets")
        assertBlocked("sugar free", "Sugar Free Cookies")
        assertBlocked("sugar free", "Sugar Free Tea")
    }

    @Test
    fun standalone_sugar_free_accepts_only_complete_known_sweetener_titles() {
        val accepted = listOf(
            "Sugar Free Gold · 100 Pieces",
            "Sugar Free Gold · 50 g",
            "Sugar Free Green Stevia · 300 Pieces",
            "Sugar Free Green 100% Natural Made From Stevia · 200 g",
            "Sugar Free Natura Low Calorie Sweetner · 75 g",
        )
        accepted.forEach { label -> assertAllowed("sugar free", label) }

        listOf(
            "Sugar Free Gold Cookies",
            "Sugar Free D-Lite Chocolate",
            "Sugar Free Tea",
            "Gold Tea",
        ).forEach { label -> assertBlocked("sugar free", label) }
    }

    @Test
    fun tea_without_sugar_requires_unsweetened_or_sugar_free_evidence() {
        val item = ParsedItem(
            rawText = "tea without sugar",
            query = "tea",
            avoidPhrases = listOf("sugar"),
        )

        assertTrue(isSwiggyCandidateAllowed(item, candidate("Unsweetened Tea")))
        assertTrue(isSwiggyCandidateAllowed(item, candidate("Sugar-Free Tea")))
        assertTrue(isSwiggyCandidateAllowed(item, candidate("Tea Zero Sugar")))
        assertFalse(isSwiggyCandidateAllowed(item, candidate("Green Tea")))
        assertFalse(isSwiggyCandidateAllowed(item, candidate("Tea No Added Sugar")))
    }

    @Test
    fun no_added_sugar_and_sugar_free_intents_remain_distinct() {
        assertAllowed("tea with no added sugar", "Tea No Added Sugar")
        assertAllowed("tea without added sugar", "Tea No Added Sugar")
        assertAllowed("tea no added sugar", "Tea No Added Sugar")
        assertBlocked("tea no added sugar", "Tea No Added Sugar With Sugar")
        assertBlocked("tea no added sugar", "Sugar-Free Tea")
        assertBlocked("sugar-free tea", "Tea No Added Sugar")
    }

    @Test
    fun low_fat_milk_accepts_only_explicit_bounded_low_fat_labels() {
        assertAllowed("low-fat milk", "Amul Low Fat Milk")
        assertAllowed("low-fat milk", "Amul Skimmed Milk")
        assertAllowed("low-fat milk", "Amul Double Toned Milk")
        assertBlocked("low-fat milk", "Amul Full Cream Milk")
        assertBlocked("low-fat milk", "Amul Milk")
    }

    @Test
    fun explicit_skimmed_milk_stays_skimmed() {
        assertAllowed("skimmed milk", "Amul Skimmed Milk")
        assertBlocked("skimmed milk", "Amul Low Fat Milk")
        assertBlocked("skimmed milk", "Amul Double Toned Milk")
    }

    @Test
    fun lean_milk_and_less_cream_are_bounded_low_fat_interpretations() {
        listOf("lean milk", "milk with less cream").forEach { query ->
            assertAllowed(query, "Amul Low Fat Milk")
            assertAllowed(query, "Amul Skimmed Milk")
            assertAllowed(query, "Amul Double Toned Milk")
            assertBlocked(query, "Lean Meat")
            assertBlocked(query, "Lean Chicken")
        }
    }

    @Test
    fun ready_made_tea_means_ready_to_drink_not_leaves_or_premix() {
        assertAllowed("ready-made tea", "Brewed Tea Ready to Drink")
        assertBlocked("ready-made tea", "Tea Leaves Ready Made")
        assertBlocked("ready-made tea", "Instant Tea Premix Ready Made")
    }

    @Test
    fun plain_samosa_rejects_masala_and_pastry_products() {
        assertAllowed("samosa", "Fresh Vegetable Samosa")
        assertBlocked("samosa", "Samosa Masala")
        assertBlocked("samosa", "Samosa Pastry Sheets")
    }

    @Test
    fun ready_to_eat_samosa_rejects_frozen_and_ready_to_cook() {
        assertAllowed("ready-to-eat samosa", "Vegetable Samosa Ready to Eat")
        assertBlocked("ready-to-eat samosa", "Frozen Samosa Ready to Eat")
        assertBlocked("ready-to-eat samosa", "Samosa Ready to Cook")
    }

    @Test
    fun one_samosa_accepts_only_a_fresh_ready_to_eat_one_piece_candidate() {
        val item = ParsedItem(
            rawText = "Order one samosa",
            query = "samosa",
            quantity = Quantity.Count(1),
        )
        val cases = listOf(
            "Fresh Vegetable Samosa Ready to Eat · 1 pc" to true,
            "Fresh Vegetable Samosa Ready to Eat · 6 pcs" to false,
            "Fresh Vegetable Samosa Ready to Eat · 400 g" to false,
            "Frozen Vegetable Samosa · 1 pc" to false,
            "Vegetable Samosa · 1 pc" to false,
        )

        cases.forEach { (label, expected) ->
            val candidate = candidate(label)
            val allowed = isSwiggyCandidateAllowed(item, candidate) &&
                isSwiggyCandidateCountCompatible(item, candidate)
            assertTrueOrFalse(
                expected,
                allowed,
                "${item.rawText}: '$label'",
            )
        }
    }

    private fun assertAllowed(query: String, label: String) {
        assertTrue(
            "Expected '$query' to allow '$label'",
            isSwiggyCandidateAllowed(item(query), candidate(label)),
        )
    }

    private fun assertBlocked(query: String, label: String) {
        assertFalse(
            "Expected '$query' to reject '$label'",
            isSwiggyCandidateAllowed(item(query), candidate(label)),
        )
    }

    private fun item(query: String): ParsedItem = ParsedItem(rawText = query, query = query)

    private fun candidate(label: String): RecommendationCandidate = RecommendationCandidate(
        spinId = "synthetic-" + label.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-'),
        label = label,
    )

    private fun assertTrueOrFalse(expected: Boolean, actual: Boolean, message: String) {
        if (expected) assertTrue(message, actual) else assertFalse(message, actual)
    }
}
