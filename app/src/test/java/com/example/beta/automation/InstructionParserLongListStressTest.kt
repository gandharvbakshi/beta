package com.example.beta.automation

import org.junit.Assert.assertEquals
import org.junit.Test

class InstructionParserLongListStressTest {
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
