package com.example.beta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndianEnglishTextToSpeechTest {
    @Test
    fun prefersLocalMaleLabelledIndianEnglishVoice() {
        val selected = selectPreferredIndianEnglishVoice(
            listOf(
                TtsVoiceOption("en-in-female-local", "en", "IN", false, 400),
                TtsVoiceOption("en-in-male-network", "en", "IN", true, 500),
                TtsVoiceOption("en-in-male-local", "en", "IN", false, 300),
                TtsVoiceOption("en-gb-male-local", "en", "GB", false, 500),
            ),
        )

        assertEquals("en-in-male-local", selected?.name)
    }

    @Test
    fun fallsBackToHighestQualityLocalIndianEnglishVoice() {
        val selected = selectPreferredIndianEnglishVoice(
            listOf(
                TtsVoiceOption("en-in-local-low", "en", "IN", false, 200),
                TtsVoiceOption("en-in-local-high", "en", "IN", false, 500),
                TtsVoiceOption("en-in-network", "en", "IN", true, 500),
            ),
        )

        assertEquals("en-in-local-high", selected?.name)
    }

    @Test
    fun pinsTheDeviceVerifiedGoogleIndianEnglishMaleVoice() {
        val selected = selectPreferredIndianEnglishVoice(
            listOf(
                TtsVoiceOption("en-in-x-ena-local", "en", "IN", false, 400),
                TtsVoiceOption("en-in-x-enc-local", "en", "IN", false, 400),
                TtsVoiceOption("en-in-x-end-local", "en", "IN", false, 400),
                TtsVoiceOption("en-in-x-ene-local", "en", "IN", false, 400),
            ),
        )

        assertEquals("en-in-x-end-local", selected?.name)
        assertTrue(isPreferredMaleIndianEnglishVoice(selected?.name.orEmpty()))
    }

    @Test
    fun maleLabelRequiresAWholeLabelToken() {
        assertTrue(hasMaleVoiceLabel("en-in-male-local"))
        assertTrue(hasMaleVoiceLabel("EN_IN_MALE"))
        assertFalse(hasMaleVoiceLabel("en-in-female-local"))
    }
}
