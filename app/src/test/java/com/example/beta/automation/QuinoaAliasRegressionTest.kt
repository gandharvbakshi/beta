package com.example.beta.automation

import org.junit.Assert.assertEquals
import org.junit.Test

class QuinoaAliasRegressionTest {
    @Test
    fun parse_keeps_keenwaa_and_keenwa_token_exact_while_preserving_quantities() {
        val items = InstructionParser.parse(
            "500g keenwaa, two keenwa, keen waa, 2 packs paper towels, coral"
        )

        assertEquals(
            listOf(
                "quinoa",
                "quinoa",
                "quinoa",
                "packs paper towels",
                "coral",
            ),
            items.map { it.query },
        )
        assertEquals(Quantity.Weight(500), items[0].quantity)
        assertEquals("500 g quinoa", items[0].backendInputText())
        assertEquals(Quantity.Count(2), items[1].quantity)
        assertEquals("2 quinoa", items[1].backendInputText())
        assertEquals(Quantity.Default, items[2].quantity)
        assertEquals("quinoa", items[2].backendInputText())
        assertEquals("2 packs paper towels", items[3].backendInputText())
        assertEquals("coral", items[4].query)
        assertEquals("quinoa", InstructionParser.parse("quinoa").single().query)
    }
}
