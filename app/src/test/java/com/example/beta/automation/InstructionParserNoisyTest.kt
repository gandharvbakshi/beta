package com.example.beta.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstructionParserNoisyTest {
    @Test
    fun parse_stripsFillersAroundCommaSeparatedItems() {
        assertQueries(
            "butter , and apple, and maybe notebook",
            listOf("butter", "apple", "notebook")
        )
    }

    @Test
    fun parse_splitsKnownProductsWhenCommasAreMissing() {
        assertQueries(
            "order butter apple and notebook",
            listOf("butter", "apple", "notebook")
        )
    }

    @Test
    fun parse_handlesAmpersandPlusAndLeadingCount() {
        assertQueries(
            "get me some butter & apples plus 1 notebook please",
            listOf("butter", "apples", "notebook")
        )
    }

    @Test
    fun parse_handlesRepeatedPunctuation() {
        assertQueries(
            "please buy   butter,,, apple ;; notebook",
            listOf("butter", "apple", "notebook")
        )
    }

    @Test
    fun parse_dedupesRepeatedItems() {
        assertQueries("butter and butter", listOf("butter"))
    }

    @Test
    fun parse_returnsEmptyForNoOpIntent() {
        assertEquals(emptyList<ParsedItem>(), InstructionParser.parse("i want nothing"))
        assertEquals(emptyList<ParsedItem>(), InstructionParser.parse("order"))
    }

    @Test
    fun parse_marksInferredSplitsWithLowerConfidence() {
        val items = InstructionParser.parse("order butter apple and notebook")

        assertEquals(listOf("butter", "apple", "notebook"), items.map { it.query })
        assertTrue(items.all { it.parserConfidence < 1.0f })
        assertTrue(items.all { it.parserConfidence >= 0.6f })
    }

    private fun assertQueries(input: String, expected: List<String>) {
        assertEquals(expected, InstructionParser.parse(input).map { it.query })
    }
}
