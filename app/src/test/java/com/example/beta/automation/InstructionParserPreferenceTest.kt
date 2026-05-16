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
}
