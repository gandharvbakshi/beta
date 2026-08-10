package com.example.beta.automation

import org.junit.Assert.assertEquals
import org.junit.Test

class InstructionParserLongListStressTest {
    @Test
    fun parse_keeps_long_mixed_category_basket_in_order() {
        val items = InstructionParser.parse(
            "baby diapers, baby shampoo, toothpaste, dish soap, frozen peas, paracetamol, tomatoes, 24 pack paper towels"
        )

        assertEquals(
            listOf(
                "baby diapers",
                "baby shampoo",
                "toothpaste",
                "dish soap",
                "frozen peas",
                "paracetamol",
                "tomatoes",
                "24 pack paper towels",
            ),
            items.map { it.query },
        )
        assertEquals(Quantity.Default, items.last().quantity)
        assertEquals("24 pack paper towels", items.last().backendInputText())
    }

    @Test
    fun parse_dedupes_repeated_categories_and_applyPreferences_overrides_matches() {
        val parsed = InstructionParser.parse(
            "baby diapers, dish soap, frozen peas, baby diapers, dish soap, paracetamol, tomatoes, 24 pack paper towels"
        )

        assertEquals(
            listOf(
                "baby diapers",
                "dish soap",
                "frozen peas",
                "paracetamol",
                "tomatoes",
                "24 pack paper towels",
            ),
            parsed.map { it.query },
        )

        val preferred = InstructionParser.applyPreferences(
            parsed,
            lookup = { token ->
                when (token) {
                    "baby diapers" -> Preference(
                        token = "baby diapers",
                        preferredPhrase = "premium baby diapers",
                        confidence = 0.9f,
                    )

                    "dish soap" -> Preference(
                        token = "dish soap",
                        preferredPhrase = "gentle dish soap",
                        confidence = 0.9f,
                    )

                    else -> null
                }
            },
        )

        assertEquals(
            listOf(
                "premium baby diapers",
                "gentle dish soap",
                "frozen peas",
                "paracetamol",
                "tomatoes",
                "24 pack paper towels",
            ),
            preferred.map { it.query },
        )
        assertEquals(Quantity.Default, preferred.last().quantity)
    }

    @Test
    fun parse_preserves_mixed_count_weight_volume_and_multipack_shapes() {
        val items = InstructionParser.parse(
            "2 baby diapers, 500 g detergent, 2 ltr juice, 6 apples, 24 pack paper towels"
        )

        assertEquals(
            listOf(
                "baby diapers",
                "detergent",
                "juice",
                "apples",
                "24 pack paper towels",
            ),
            items.map { it.query },
        )
        assertEquals(Quantity.Count(2), items[0].quantity)
        assertEquals(Quantity.Weight(500), items[1].quantity)
        assertEquals(Quantity.Volume(2000), items[2].quantity)
        assertEquals(Quantity.Count(6), items[3].quantity)
        assertEquals(Quantity.Default, items[4].quantity)
        assertEquals(
            listOf(
                "2 baby diapers",
                "500 g detergent",
                "2000 ml juice",
                "6 apples",
                "24 pack paper towels",
            ),
            items.map { it.backendInputText() },
        )
    }

    @Test
    fun parse_keeps_exact_order_for_twelve_item_comma_lists() {
        assertQueries(
            "apple, butter, notebook, pencil, bhindi, juice, chips, atta, milk, bread, eggs, coke",
            listOf(
                "apple",
                "butter",
                "notebook",
                "pencil",
                "bhindi",
                "juice",
                "chips",
                "atta",
                "milk",
                "bread",
                "eggs",
                "coke",
            ),
        )
    }

    @Test
    fun parse_keeps_exact_order_for_twenty_item_comma_lists() {
        assertQueries(
            "apple, butter, notebook, pencil, bhindi, juice, chips, atta, milk, bread, eggs, coke, maggi, lays, pepsi, vicks, soap, rice, dal, ghee",
            listOf(
                "apple",
                "butter",
                "notebook",
                "pencil",
                "bhindi",
                "juice",
                "chips",
                "atta",
                "milk",
                "bread",
                "eggs",
                "coke",
                "maggi",
                "lays",
                "pepsi",
                "vicks",
                "soap",
                "rice",
                "dal",
                "ghee",
            ),
        )
    }

    @Test
    fun parse_keeps_exact_order_for_twenty_five_item_comma_lists() {
        assertQueries(
            "apple, butter, notebook, pencil, bhindi, juice, chips, atta, milk, bread, eggs, coke, maggi, lays, pepsi, vicks, soap, rice, dal, ghee, curd, tea, sugar, salt, flour",
            listOf(
                "apple",
                "butter",
                "notebook",
                "pencil",
                "bhindi",
                "juice",
                "chips",
                "atta",
                "milk",
                "bread",
                "eggs",
                "coke",
                "maggi",
                "lays",
                "pepsi",
                "vicks",
                "soap",
                "rice",
                "dal",
                "ghee",
                "curd",
                "tea",
                "sugar",
                "salt",
                "flour",
            ),
        )
    }

    private fun assertQueries(input: String, expected: List<String>) {
        val items = InstructionParser.parse(input)
        assertEquals(expected.size, items.size)
        assertEquals(expected, items.map { it.query })
    }
}
