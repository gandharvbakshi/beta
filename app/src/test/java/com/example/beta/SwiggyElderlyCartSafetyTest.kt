package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.SwiggyMcpClient.Recommendations
import com.example.beta.SwiggyMcpClient.RequestedItem
import com.example.beta.automation.ParsedItem
import com.example.beta.automation.Preference
import com.example.beta.automation.Quantity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwiggyElderlyCartSafetyTest {
    @Test
    fun invalid_counts_are_rejected_before_any_provider_request() {
        listOf("twenty one milk and bread", "Twenty-one milk, bread", "hundred eggs", "zero milk", "0 milk", "-2 milk", "12 milk, 12 milk").forEach { instruction ->
            val items = prepareSwiggyMcpItems(instruction, lookup = { null })
            assertTrue("Must reject $instruction", swiggyMcpItemValidationMessage(instruction, items) != null)
        }
    }

    @Test
    fun spoken_counts_are_case_insensitive_and_preserved_after_conjunctions() {
        val items = prepareSwiggyMcpItems("Two milk and Three bread", lookup = { null })
        assertEquals(listOf("milk", "bread"), items.map { it.query })
        assertEquals(listOf(Quantity.Count(2), Quantity.Count(3)), items.map { it.quantity })
    }

    @Test
    fun phonetic_food_aliases_need_review_without_corrupting_brands() {
        val items = prepareSwiggyMcpItems("keen waa, mozz a rela, One Plus charger, vitamin E", lookup = { null })
        assertEquals(listOf("quinoa", "mozzarella", "oneplus charger", "vitamin e"), items.map { it.query })
        assertTrue(items.take(2).all { it.parserConfidence < 1f })
    }

    @Test
    fun unknown_or_different_area_never_claims_nearby() {
        val unknown = swiggyAddressLocationNotice(SwiggyLocationAssessment.UNKNOWN)
        val different = swiggyAddressLocationNotice(SwiggyLocationAssessment.NOT_MATCHED)
        assertTrue(unknown.contains("still use a saved address"))
        assertTrue(different.contains("confirm"))
        assertTrue(different.contains("different address"))
        assertFalse(unknown.contains("nearby"))
        assertFalse(different.contains("nearby"))
    }

    @Test
    fun measuredPackQuantity_supports_divisible_rice_and_milk_pack_sizes() {
        val rice = ParsedItem(rawText = "1kg rice", query = "rice", quantity = Quantity.Weight(1000))
        val milk = ParsedItem(rawText = "1l milk", query = "milk", quantity = Quantity.Volume(1000))

        assertEquals(2, swiggyMeasuredPackQuantity(rice, candidate("Rice · 500 g")))
        assertEquals(2, swiggyMeasuredPackQuantity(milk, candidate("Milk · 500 ml")))
        assertEquals(2, swiggyRequestedCartQuantity(rice, candidate("Rice · 500 g")))
        assertEquals(2, swiggyRequestedCartQuantity(milk, candidate("Milk · 500 ml")))
    }

    @Test
    fun measuredPackQuantity_rejects_undividable_or_unknown_pack_measures() {
        val halfKiloRice = ParsedItem(rawText = "500g rice", query = "rice", quantity = Quantity.Weight(500))
        val unknownMeasure = ParsedItem(rawText = "500g rice", query = "rice", quantity = Quantity.Weight(500))
        val mixedMeasures = ParsedItem(rawText = "500g rice", query = "rice", quantity = Quantity.Weight(500))
        val multipack = ParsedItem(rawText = "500g rice", query = "rice", quantity = Quantity.Weight(500))

        assertNull(swiggyMeasuredPackQuantity(halfKiloRice, candidate("Rice · 1 kg")))
        assertNull(swiggyMeasuredPackQuantity(unknownMeasure, candidate("Rice · 500 oz")))
        assertNull(swiggyMeasuredPackQuantity(mixedMeasures, candidate("Rice · 500 g · 1 kg")))
        assertNull(swiggyMeasuredPackQuantity(multipack, candidate("2x500 g Rice")))
    }

    @Test
    fun measuredPackQuantity_accepts_branding_prefixes_but_rejects_true_multipacks() {
        val litreCleaner = ParsedItem(rawText = "1l cleaner", query = "cleaner", quantity = Quantity.Volume(1000))

        assertEquals(1, swiggyMeasuredPackQuantity(litreCleaner, candidate("Harpic 10X Advanced 1 L")))
        assertEquals(2, swiggyMeasuredPackQuantity(litreCleaner, candidate("Harpic 10X Advanced 500 ml")))
        assertEquals(1, swiggyMeasuredPackQuantity(litreCleaner, candidate("Harpic Original Toilet Cleaner, 5 Min Action, 10X Advanced, Disinfectant · 1 ltr")))
        // Without a separating descriptor, 10X 1L could mean ten bottles.
        assertNull(swiggyMeasuredPackQuantity(litreCleaner, candidate("Harpic 10X 1L")))
        assertNull(swiggyMeasuredPackQuantity(litreCleaner, candidate("Harpic 2x500 ml")))
        assertNull(swiggyMeasuredPackQuantity(litreCleaner, candidate("Harpic 1 Lx2")))
        assertNull(swiggyMeasuredPackQuantity(litreCleaner, candidate("Harpic pack of 2 1 L")))
        assertNull(swiggyMeasuredPackQuantity(litreCleaner, candidate("Harpic pack of 10 1 L")))
        assertNull(swiggyMeasuredPackQuantity(litreCleaner, candidate("Harpic 500 ml combo 1 L")))
    }

    @Test
    fun count_quantity_stays_unchanged_when_candidate_has_weight_label() {
        val item = ParsedItem(rawText = "2 milk", query = "milk", quantity = Quantity.Count(2))
        val candidate = candidate("Milk · 500 ml")

        assertTrue(isSwiggyCandidateCountCompatible(item, candidate))
        assertEquals(2, swiggyRequestedCartQuantity(item, candidate))
    }

    @Test
    fun low_parser_confidence_is_marked_for_basket_review() {
        val item = ParsedItem(rawText = "milk", query = "milk", parserConfidence = 0.75f)
        val recommendation = Recommendations(candidates = listOf(candidate("Milk")), requiresConfirmation = false)

        assertTrue(swiggySuggestionNeedsReview(item, recommendation, hasPreferred = true))
    }

    @Test
    fun generic_milk_is_marked_for_review_even_when_history_is_confident() {
        val item = ParsedItem("milk", "milk")
        val flavored = candidate("Chocolate Milk 200 ml")
        val recommendation = Recommendations(listOf(flavored), suggested = flavored, requiresConfirmation = false)
        assertTrue(swiggySuggestionNeedsReview(item, recommendation, hasPreferred = true))
    }

    @Test
    fun negative_preferences_force_review_and_filter_vanilla_only() {
        val items = prepareSwiggyMcpItems("ice cream without vanilla", lookup = { null })
        val item = items.single()
        val recommendation = Recommendations(candidates = listOf(candidate("Vanilla Ice Cream"), candidate("Chocolate Ice Cream")))

        assertTrue(item.avoidPhrases.contains("vanilla"))
        assertTrue(swiggySuggestionNeedsReview(item, recommendation, hasPreferred = true))
        assertFalse(isSwiggyCandidateAllowed(item, candidate("Vanilla Ice Cream")))
        assertTrue(isSwiggyCandidateAllowed(item, candidate("Chocolate Ice Cream")))
    }

    @Test
    fun default_suggestion_prefers_ordinary_milk_over_historical_flavored_milk() {
        val item = ParsedItem(rawText = "milk", query = "milk")
        val ordinary = candidate("Ordinary Cow Milk")
        val chocolate = candidate("Chocolate Milk 200 ml")

        assertEquals(ordinary, swiggyDefaultSuggestion(item, listOf(chocolate, ordinary), preferred = chocolate))
    }

    @Test
    fun default_suggestion_preserves_provider_preferred_plain_milk_variant_between_plain_choices() {
        val item = ParsedItem(rawText = "milk", query = "milk")
        val preferred = candidate("Daily Fresh Cow Milk")
        val backup = candidate("Toned Cow Milk")

        assertEquals(preferred, swiggyDefaultSuggestion(item, listOf(preferred, backup), preferred = preferred))
    }

    @Test
    fun default_suggestion_honors_strict_match_phrase_for_explicit_chocolate_milk() {
        val item = ParsedItem(
            rawText = "chocolate milk",
            query = "milk",
            strictMatchPhrase = "chocolate milk",
        )
        val ordinary = candidate("Ordinary Cow Milk")
        val chocolate = candidate("Chocolate Milk 200 ml")

        assertEquals(chocolate, swiggyDefaultSuggestion(item, listOf(ordinary, chocolate), preferred = chocolate))
    }

    @Test
    fun default_suggestion_falls_back_to_flavored_only_option_for_full_basket_review() {
        val item = ParsedItem(rawText = "milk", query = "milk")
        val flavoredOnly = candidate("Chocolate Milk 200 ml")

        assertEquals(flavoredOnly, swiggyDefaultSuggestion(item, listOf(flavoredOnly), preferred = null))
    }

    @Test
    fun prepareSwiggyMcpItems_preserves_newline_separated_lists_in_order() {
        val items = prepareSwiggyMcpItems("milk\nbread\nbanana", lookup = { null })

        assertEquals(listOf("milk", "bread", "banana"), items.map { it.query })
    }

    @Test
    fun validationMessage_blocks_duplicate_conflicting_preferences() {
        val items = listOf(
            ParsedItem(
                rawText = "milk without vanilla",
                query = "milk",
                avoidPhrases = listOf("vanilla"),
            ),
            ParsedItem(
                rawText = "milk without chocolate",
                query = "milk",
                avoidPhrases = listOf("chocolate"),
            ),
        )

        assertEquals(
            "One product appears with different quantities or preferences. Please combine it into one clear line; nothing was changed.",
            swiggyMcpItemValidationMessage("milk without vanilla, milk without chocolate", items),
        )
    }

    @Test
    fun needs_exact_health_product_for_vague_symptoms_but_not_for_explicit_medicine_or_regular_grocery() {
        assertTrue(swiggyNeedsExactHealthProduct(ParsedItem(rawText = "woh cough wali goli", query = "woh cough wali goli")))
        assertFalse(swiggyNeedsExactHealthProduct(ParsedItem(rawText = "Vicks cough drops", query = "vicks cough drops")))
        assertFalse(swiggyNeedsExactHealthProduct(ParsedItem(rawText = "milk", query = "milk")))
    }

    @Test
    fun consolidate_requested_items_sums_same_sku_and_keeps_distinct_skus_and_over_twenty() {
        val consolidated = consolidateSwiggyRequestedItems(
            listOf(
                RequestedItem(spinId = "milk-spin", quantity = 2, displayName = "Milk"),
                RequestedItem(spinId = "milk-spin", quantity = 3, displayName = "Milk"),
                RequestedItem(spinId = "bread-spin", quantity = 1, displayName = "Bread"),
                RequestedItem(spinId = "bread-spin", quantity = 19, displayName = "Bread"),
                RequestedItem(spinId = "juice-spin", quantity = 21, displayName = "Juice"),
            )
        )

        assertEquals(3, consolidated.size)
        assertEquals(5, consolidated.first { it.spinId == "milk-spin" }.quantity)
        assertEquals(20, consolidated.first { it.spinId == "bread-spin" }.quantity)
        assertEquals(21, consolidated.first { it.spinId == "juice-spin" }.quantity)
        assertTrue(
            consolidated.any { it.spinId == "juice-spin" && it.quantity == 21 }
        )
    }

    @Test
    fun raw_quantity_validation_rejects_over_twenty_before_provider_discovery() {
        val items = prepareSwiggyMcpItems("21 juice", lookup = { null })

        assertEquals(1, items.size)
        assertEquals(
            "Swiggy supports up to quantity 20 per item. Please reduce the quantity for juice; nothing was changed.",
            swiggyMcpItemValidationMessage("21 juice", items),
        )
    }

    private fun candidate(label: String): RecommendationCandidate {
        return RecommendationCandidate(
            spinId = label.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-'),
            label = label,
        )
    }
}
