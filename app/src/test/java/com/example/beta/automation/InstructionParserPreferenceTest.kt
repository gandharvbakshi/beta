package com.example.beta.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstructionParserPreferenceTest {
    @Test
    fun applyPreferenceRewritesQueryAndLogs() {
        val logs = mutableListOf<String>()
        val items = InstructionParser.applyPreferences(
            InstructionParser.parse("order butter"),
            lookup = {
                Preference(
                    token = "butter",
                    preferredPhrase = "unsalted butter",
                    confidence = 0.9f
                )
            },
            log = { logs.add(it) }
        )

        assertEquals("unsalted butter", items.single().query)
        assertTrue(logs.single().contains("PREFERENCE_APPLIED"))
    }

    @Test
    fun applyPreferenceMergesStoredAndExplicitAvoidPhrases() {
        val items = InstructionParser.applyPreferences(
            InstructionParser.parse("order lays not bingo"),
            lookup = {
                Preference(
                    token = "lays",
                    preferredPhrase = "lays classic salted chips",
                    avoidPhrases = listOf("too spicy"),
                    confidence = 0.9f
                )
            }
        )

        assertEquals("lays classic salted chips", items.single().query)
        assertEquals(listOf("bingo", "too spicy"), items.single().avoidPhrases)
    }

    @Test
    fun noPreferenceKeepsQueryAndLogsNone() {
        val logs = mutableListOf<String>()
        val items = InstructionParser.applyPreferences(
            InstructionParser.parse("order butter"),
            lookup = { null },
            log = { logs.add(it) }
        )

        assertEquals("butter", items.single().query)
        assertTrue(logs.single().contains("PREFERENCE_NONE"))
    }

    @Test
    fun parse_stripsNaturalLanguagePreferenceNoiseFromQuery() {
        assertParsedQuery(
            "order butter with my usual preference",
            "butter"
        )
        assertParsedQuery(
            "order butter normally",
            "butter"
        )
        assertParsedQuery(
            "order butter as-is",
            "butter"
        )
        assertParsedQuery(
            "order notebook as-is",
            "notebook"
        )
    }

    @Test
    fun parse_extractsAvoidPhrasesWithoutLeakingThemIntoQuery() {
        assertParsedAvoids(
            "order apple without the sour ones",
            "apple",
            listOf("sour")
        )
        assertParsedAvoids(
            "order makhana without peri peri",
            "makhana",
            listOf("peri peri")
        )
        assertParsedAvoids(
            "order lays not bingo",
            "lays",
            listOf("bingo")
        )
    }

    private fun assertParsedQuery(input: String, expectedQuery: String) {
        val item = InstructionParser.parse(input).single()
        assertEquals(expectedQuery, item.query)
    }

    private fun assertParsedAvoids(input: String, expectedQuery: String, expectedAvoids: List<String>) {
        val item = InstructionParser.parse(input).single()
        assertEquals(expectedQuery, item.query)
        assertEquals(expectedAvoids, item.avoidPhrases)
    }
}
