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
            assertEquals(if (prompt == "aata") "atta" else prompt, item.query)
        }
    }

    @Test
    fun longMinimalCategoryListKeepsEveryRequestedLine() {
        val items = InstructionParser.parse("mints, aata, bhindi, juice, chips")

        assertEquals(
            listOf("mints", "atta", "bhindi", "juice", "chips"),
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
                        source = PreferenceSource.USER_OVERRIDE,
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
                    source = PreferenceSource.USER_OVERRIDE,
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
                    source = PreferenceSource.USER_OVERRIDE,
                    confidence = 0.9f
                )
            },
            log = { logs.add(it) }
        )

        assertEquals("unsalted butter", items.single().query)
        assertEquals("unsalted butter", items.single().strictMatchPhrase)
        assertEquals(listOf("PREFERENCE_APPLIED source=USER_OVERRIDE"), logs)
    }

    @Test
    fun applyPreferenceMergesStoredAndExplicitAvoidPhrases() {
        val logs = mutableListOf<String>()
        val items = InstructionParser.applyPreferences(
            InstructionParser.parse("order lays not bingo"),
            lookup = {
                Preference(
                    token = "lays",
                    preferredPhrase = "lays classic salted chips",
                    avoidPhrases = listOf("too spicy"),
                    source = PreferenceSource.USER_OVERRIDE,
                    confidence = 0.9f
                )
            },
            log = { logs.add(it) },
        )

        assertEquals("lays classic salted chips", items.single().query)
        assertEquals(listOf("bingo", "too spicy"), items.single().avoidPhrases)
        assertEquals(listOf("PREFERENCE_APPLIED source=USER_OVERRIDE"), logs)
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
        assertEquals(listOf("PREFERENCE_NONE"), logs)
    }

    @Test
    fun seededAndInferredPreferencesCannotRewriteGenericSourceRows() {
        data class Case(val source: String, val preferred: String, val avoid: String)
        val cases = listOf(
            Case("order 2 rice without basmati", "sona masoori rice", "broken rice"),
            Case("order 500 g milk without lactose", "A2 milk", "full cream"),
            Case("order 1 l juice without added sugar", "mango juice", "pulp"),
        )
        val failures = mutableListOf<String>()

        for (case in cases) {
            val parsed = InstructionParser.parse(case.source).single()
            val item = if (parsed.query == "rice") {
                val amountStart = case.source.indexOf("2 rice")
                parsed.copy(intentQuantityProvenance = IntentQuantityProvenance(
                    origin = IntentQuantityOrigin.SOURCE_NUMERIC,
                    primaryText = case.source,
                    rawText = case.source,
                    resolvedQuantity = parsed.quantity,
                    local = IntentLocalAmount(
                        source = case.source,
                        startUtf16 = amountStart,
                        endUtf16 = amountStart + 1,
                        atom = "2",
                        value = java.math.BigDecimal("2"),
                        unit = "count",
                    ),
                    query = parsed.query,
                    strictMatchPhrase = parsed.strictMatchPhrase,
                    retailPackCount = parsed.retailPackCount,
                    logicalPieceCount = parsed.logicalPieceCount,
                    numericSourceQuery = parsed.query,
                ))
            } else parsed

            for (source in listOf(PreferenceSource.SEEDED, PreferenceSource.INFERRED)) {
                val actual = InstructionParser.applyPreferences(
                    listOf(item),
                    lookup = { token ->
                        if (token == item.query) Preference(
                            token = token,
                            preferredPhrase = case.preferred,
                            avoidPhrases = listOf(case.avoid),
                            source = source,
                            confidence = 1.0f,
                        ) else null
                    },
                ).single()
                if (actual != item) failures += "source=$source product=${item.query}"
            }
        }

        assertTrue("Non-user preferences must not rewrite the request: $failures", failures.isEmpty())
    }

    @Test
    fun explicitUserOverrideStillAppliesToAnUnownedGenericRow() {
        val item = InstructionParser.parse("order 2 rice without basmati").single()
        val actual = InstructionParser.applyPreferences(
            listOf(item),
            lookup = { token ->
                Preference(
                    token = token,
                    preferredPhrase = "sona masoori rice",
                    avoidPhrases = listOf("broken rice"),
                    source = PreferenceSource.USER_OVERRIDE,
                    confidence = 1.0f,
                )
            },
        ).single()

        assertEquals("sona masoori rice", actual.query)
        assertEquals("sona masoori rice", actual.strictMatchPhrase)
        assertEquals(item.quantity, actual.quantity)
        assertEquals(listOf("basmati", "broken rice"), actual.avoidPhrases)
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
