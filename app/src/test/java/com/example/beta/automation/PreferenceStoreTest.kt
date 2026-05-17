package com.example.beta.automation

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreferenceStoreTest {
    @After
    fun tearDown() {
        PreferenceStore.forgetAll()
    }

    @Test
    fun insertAndLookupRoundTrip() {
        PreferenceStore.upsert(
            Preference(
                token = "butter",
                preferredPhrase = "unsalted butter",
                confidence = 0.9f
            )
        )

        assertEquals("unsalted butter", PreferenceStore.lookup("butter")?.preferredPhrase)
    }

    @Test
    fun lookupUsesCentralProductAliases() {
        PreferenceStore.upsert(
            Preference(
                token = "Lay's",
                preferredPhrase = "Lay's classic salted chips",
                avoidPhrases = listOf("Bingo"),
                confidence = 0.9f
            )
        )

        val preference = PreferenceStore.lookup("lay s")

        assertEquals("lays", preference?.token)
        assertEquals("lays classic salted chips", preference?.preferredPhrase)
        assertEquals(listOf("bingo"), preference?.avoidPhrases)
    }

    @Test
    fun lookupUsesMultilingualProductAliases() {
        PreferenceStore.upsert(
            Preference(
                token = "मक्खन",
                preferredPhrase = "unsalted butter",
                confidence = 0.9f
            )
        )

        assertEquals("unsalted butter", PreferenceStore.lookup("butter")?.preferredPhrase)
    }

    @Test
    fun lookupReturnsNullForUnknownAndLowConfidence() {
        PreferenceStore.upsert(
            Preference(
                token = "butter",
                preferredPhrase = "unsalted butter",
                confidence = 0.4f
            )
        )

        assertNull(PreferenceStore.lookup("makhana"))
        assertNull(PreferenceStore.lookup("butter"))
    }

    @Test
    fun forgetClearsPreferences() {
        PreferenceStore.upsert(
            Preference(
                token = "butter",
                preferredPhrase = "unsalted butter",
                confidence = 0.9f
            )
        )

        PreferenceStore.forgetAll()

        assertNull(PreferenceStore.lookup("butter"))
    }
}
