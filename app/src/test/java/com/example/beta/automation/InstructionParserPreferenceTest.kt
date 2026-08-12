package com.example.beta.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstructionParserPreferenceTest {
    @Test
    fun bareMinimalHistoryPromptsDoNotRequireOrderVerb() {
        val prompts = listOf("mints", "aata", "bhindi", "juice", "chips")

        prompts.forEach { prompt ->
            val item = InstructionParser.parse(prompt).single()
            assertEquals(prompt, item.query)
        }
    }

    @Test
    fun longMinimalCategoryListKeepsEveryRequestedLine() {
        val items = InstructionParser.parse("mints, aata, bhindi, juice, chips")

        assertEquals(
            listOf("mints", "aata", "bhindi", "juice", "chips"),
            items.map { it.query }
        )
    }

    @Test
    fun bareMintPromptUsesTheSameLocalPreferenceAsCommandPrompt() {
        var lookupCount = 0
        val item = InstructionParser.applyPreferences(
            InstructionParser.parse("mints"),
            lookup = { query ->
                lookupCount += 1
                if (query == "mints") {
                    Preference(
                        token = "mints",
                        preferredPhrase = "Impact Sugar Free Mint Candies Ice Mints",
                        confidence = 1.0f
                    )
                } else {
                    null
                }
            }
        ).single()

        assertEquals(1, lookupCount)
        assertEquals("impact sugar free mint candies ice mints", item.query)
        assertEquals("impact sugar free mint candies ice mints", item.strictMatchPhrase)
    }

    @Test
    fun shorthandPreferencePreservesRequestedQuantity() {
        val item = InstructionParser.applyPreferences(
            InstructionParser.parse("order 2 mints"),
            lookup = {
                Preference(
                    token = "mints",
                    preferredPhrase = "Impact Sugar Free Mint Candies Ice Mints",
                    confidence = 1.0f
                )
            }
        ).single()

        assertEquals(Quantity.Count(2), item.quantity)
        assertEquals(
            "2 impact sugar free mint candies ice mints",
            item.backendInputText()
        )
    }

    @Test
    fun explicitVariantDoesNotUseGenericShorthandPreference() {
        val item = InstructionParser.applyPreferences(
            InstructionParser.parse("order fresh mints"),
            lookup = { query ->
                if (query == "mints") {
                    Preference(
                        token = "mints",
                        preferredPhrase = "Impact Sugar Free Mint Candies Ice Mints",
                        confidence = 1.0f
                    )
                } else {
                    null
                }
            }
        ).single()

        assertEquals("fresh mints", item.query)
        assertEquals(null, item.strictMatchPhrase)
    }

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
        assertEquals("unsalted butter", items.single().strictMatchPhrase)
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
        assertEquals(null, items.single().strictMatchPhrase)
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
