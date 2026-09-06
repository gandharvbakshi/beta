package com.example.beta

import com.example.beta.automation.Quantity
import com.example.beta.automation.backendInputText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class SwiggyHistoryInputRegressionTest(
    private val scenario: Scenario,
) {
    @Test
    fun prepareSwiggyMcpItems_preserves_user_intent_for_history_style_inputs() {
        val items = prepareSwiggyMcpItems(scenario.input, lookup = { null })

        assertEquals("Unexpected item count for ${scenario.name}", scenario.expectedQueries.size, items.size)
        assertEquals(
            "Unexpected queries for ${scenario.name}",
            scenario.expectedQueries,
            items.map { it.query },
        )
        assertEquals(
            "Unexpected quantities for ${scenario.name}",
            scenario.expectedQuantities,
            items.map { it.quantity },
        )
        assertEquals(
            "Unexpected backend input text for ${scenario.name}",
            scenario.expectedBackendInputs,
            items.map { it.backendInputText() },
        )
        assertEquals(
            "Unexpected recommendation queries for ${scenario.name}",
            scenario.expectedRecommendationQueries,
            items.map(::swiggyRecommendationQuery),
        )
        assertEquals(
            "Unexpected avoid phrases for ${scenario.name}",
            scenario.expectedAvoidPhrases,
            items.map { it.avoidPhrases },
        )
        scenario.expectedValidationMessage?.let { expected ->
            assertEquals(expected, swiggyMcpItemValidationMessage(scenario.input, items))
        } ?: assertNull(
            "History regression input should not trigger a validation error for ${scenario.name}",
            swiggyMcpItemValidationMessage(scenario.input, items),
        )
    }

    @Test
    fun swiggyRecommendationQuery_keeps_numeric_product_identity_without_guessing_a_count() {
        assertEquals(
            "7up",
            swiggyRecommendationQuery(com.example.beta.automation.ParsedItem(rawText = "7up", query = "7up")),
        )
        assertEquals(
            "5star",
            swiggyRecommendationQuery(com.example.beta.automation.ParsedItem(rawText = "5star", query = "5star")),
        )
    }

    data class Scenario(
        val name: String,
        val input: String,
        val expectedQueries: List<String>,
        val expectedQuantities: List<Quantity>,
        val expectedBackendInputs: List<String>,
        val expectedRecommendationQueries: List<String> = expectedQueries,
        val expectedAvoidPhrases: List<List<String>> = List(expectedQueries.size) { emptyList() },
        val expectedValidationMessage: String? = null,
    ) {
        override fun toString(): String = name
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): Collection<Array<Any>> {
            return listOf(
                arrayOf<Any>(
                    Scenario(
                        name = "digit_count_and_indiquant_phrase",
                        input = "2 amul dark chocolate",
                        expectedQueries = listOf("amul dark chocolate"),
                        expectedQuantities = listOf(Quantity.Count(2)),
                        expectedBackendInputs = listOf("2 amul dark chocolate"),
                    ),
                ),
                arrayOf<Any>(
                    Scenario(
                        name = "suffix_packet_count_keeps_product_on_the_left",
                        input = "amul dark chocolate 2 packets",
                        expectedQueries = listOf("amul dark chocolate"),
                        expectedQuantities = listOf(Quantity.Count(2)),
                        expectedBackendInputs = listOf("2 amul dark chocolate"),
                    ),
                ),
                arrayOf<Any>(
                    Scenario(
                        name = "spoken_do_count_in_hinglish",
                        input = "do amul dark chocolate",
                        expectedQueries = listOf("amul dark chocolate"),
                        expectedQuantities = listOf(Quantity.Count(2)),
                        expectedBackendInputs = listOf("2 amul dark chocolate"),
                    ),
                ),
                arrayOf<Any>(
                    Scenario(
                        name = "mixed_hinglish_pair_with_follow_on_item",
                        input = "do amul dark chocolate aur ek mosquito patch",
                        expectedQueries = listOf("amul dark chocolate", "mosquito patch"),
                        expectedQuantities = listOf(Quantity.Count(2), Quantity.Count(1)),
                        expectedBackendInputs = listOf("2 amul dark chocolate", "mosquito patch"),
                    ),
                ),
                arrayOf<Any>(
                    Scenario(
                        name = "mixed_brand_name_numbers_and_aliases",
                        input = "do doodh aur chawal 500 g, namak 1 kg, teen seb, amul dark chocolate 2 packets, ek mosquito patch, keenwaa, CR2032 battery, 9V battery, One Plus charger",
                        expectedQueries = listOf(
                            "milk",
                            "rice",
                            "salt",
                            "apple",
                            "amul dark chocolate",
                            "mosquito patch",
                            "quinoa",
                            "cr2032 battery",
                            "9v battery",
                            "oneplus charger",
                        ),
                        expectedQuantities = listOf(
                            Quantity.Count(2),
                            Quantity.Weight(500),
                            Quantity.Weight(1000),
                            Quantity.Count(3),
                            Quantity.Count(2),
                            Quantity.Count(1),
                            Quantity.Default,
                            Quantity.Default,
                            Quantity.Default,
                            Quantity.Default,
                        ),
                        expectedBackendInputs = listOf(
                            "2 milk",
                            "500 g rice",
                            "1000 g salt",
                            "3 apple",
                            "2 amul dark chocolate",
                            "mosquito patch",
                            "quinoa",
                            "cr2032 battery",
                            "9v battery",
                            "oneplus charger",
                        ),
                        expectedRecommendationQueries = listOf(
                            "milk",
                            "500 g rice",
                            "1000 g salt",
                            "apple",
                            "amul dark chocolate",
                            "mosquito patch",
                            "quinoa",
                            "cr2032 battery",
                            "9v battery",
                            "oneplus charger",
                        ),
                    ),
                ),
                arrayOf<Any>(
                    Scenario(
                        name = "fractional_weights_are_preserved_as_rounding_intent",
                        input = "aadha kilo sugar, dedh kg rice, half litre milk, one and a half litre water",
                        expectedQueries = listOf("sugar", "rice", "milk", "water"),
                        expectedQuantities = listOf(
                            Quantity.Weight(500),
                            Quantity.Weight(1500),
                            Quantity.Volume(500),
                            Quantity.Volume(1500),
                        ),
                        expectedBackendInputs = listOf(
                            "500 g sugar",
                            "1500 g rice",
                            "500 ml milk",
                            "1500 ml water",
                        ),
                        expectedRecommendationQueries = listOf(
                            "500 g sugar",
                            "1500 g rice",
                            "500 ml milk",
                            "1500 ml water",
                        ),
                    ),
                ),
                arrayOf<Any>(
                    Scenario(
                        name = "spoken_and_wrapped_inputs_keep_requested_items_only",
                        input = "mujhe do doodh aur teen seb lao",
                        expectedQueries = listOf("milk", "apple"),
                        expectedQuantities = listOf(Quantity.Count(2), Quantity.Count(3)),
                        expectedBackendInputs = listOf("2 milk", "3 apple"),
                        expectedRecommendationQueries = listOf("milk", "apple"),
                    ),
                ),
                arrayOf<Any>(
                    Scenario(
                        name = "suffix_packet_plural_and_unit_counts_are_preserved",
                        input = "vixks cough tablet 1 packet, amul dark chocolate 2 packets, mosquito patch 1 unit",
                        expectedQueries = listOf("vixks cough tablet", "amul dark chocolate", "mosquito patch"),
                        expectedQuantities = listOf(Quantity.Count(1), Quantity.Count(2), Quantity.Count(1)),
                        expectedBackendInputs = listOf("vixks cough tablet", "2 amul dark chocolate", "mosquito patch"),
                        expectedRecommendationQueries = listOf("vixks cough tablet", "amul dark chocolate", "mosquito patch"),
                    ),
                ),
                arrayOf<Any>(
                    Scenario(
                        name = "duplicate_counted_items_aggregate_before_product_search",
                        input = "2 milk, 3 milk",
                        expectedQueries = listOf("milk"),
                        expectedQuantities = listOf(Quantity.Count(5)),
                        expectedBackendInputs = listOf("5 milk"),
                        expectedRecommendationQueries = listOf("milk"),
                    ),
                ),
                arrayOf<Any>(
                    Scenario(
                        name = "bare_items_keep_default_quantity_and_canonical_spelling",
                        input = "keenwaa, buttr, 7up",
                        expectedQueries = listOf("quinoa", "buttr", "7up"),
                        expectedQuantities = listOf(Quantity.Default, Quantity.Default, Quantity.Default),
                        expectedBackendInputs = listOf("quinoa", "buttr", "7up"),
                        expectedRecommendationQueries = listOf("quinoa", "buttr", "7up"),
                    ),
                ),
                arrayOf<Any>(
                    Scenario(
                        name = "explicit_normal_and_regular_qualifiers_are_preserved",
                        input = "milk regular, bread normal",
                        expectedQueries = listOf("milk regular", "bread normal"),
                        expectedQuantities = listOf(Quantity.Default, Quantity.Default),
                        expectedBackendInputs = listOf("milk regular", "bread normal"),
                        expectedRecommendationQueries = listOf("milk regular", "bread normal"),
                    ),
                ),
                arrayOf<Any>(
                    Scenario(
                        name = "bina_negative_preference_keeps_avoid_phrase_outside_query",
                        input = "ice cream bina vanilla",
                        expectedQueries = listOf("ice cream"),
                        expectedQuantities = listOf(Quantity.Default),
                        expectedBackendInputs = listOf("ice cream"),
                        expectedRecommendationQueries = listOf("ice cream without vanilla"),
                        expectedAvoidPhrases = listOf(listOf("vanilla")),
                    ),
                ),
            )
        }
    }
}
