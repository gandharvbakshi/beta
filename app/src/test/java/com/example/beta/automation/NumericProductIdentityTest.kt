package com.example.beta.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NumericProductIdentityTest {
    @Test
    fun recognizesOnlyTheEstablishedNumericProductIdentitiesAndExactAmountSpans() {
        data class Case(val text: String, val key: String, val identity: String, val amount: String)
        val cases = listOf(
            Case("7up", "7 up", "7up", "7"),
            Case("7 up", "7 up", "7 up", "7"),
            Case("7-Up drink", "7 up", "7-Up", "7"),
            Case("5star chocolate", "5 star", "5star", "5"),
            Case("5-Star chocolate", "5 star", "5-Star", "5"),
            Case("2 5 star chocolate", "5 star", "5 star", "5"),
            Case("24mantra atta", "24 mantra", "24mantra", "24"),
            Case("24-Mantra atta", "24 mantra", "24-Mantra", "24"),
            Case("दूध 🍵 24 mantra atta", "24 mantra", "24 mantra", "24"),
            Case("oneplus charger", "oneplus", "oneplus", "one"),
            Case("one plus charger", "oneplus", "one plus", "one"),
            Case("Maggi 2-minute noodles", "maggi 2 minute", "Maggi 2-minute", "2"),
            Case("Maggi two minute noodles", "maggi 2 minute", "Maggi two minute", "two"),
        )

        for (case in cases) {
            val matches = ProductLexicon.numericProductIdentities(case.text)
            assertEquals(case.text, 1, matches.size)
            val match = matches.single()
            assertEquals(case.text, case.key, match.key)
            assertEquals(case.text, case.identity, keyFragment(case.text, match.identityRange))
            assertEquals(case.text, case.amount, keyFragment(case.text, match.amountRange).lowercase())
            assertTrue(case.text, match.identityRange.first >= 0)
            assertTrue(case.text, match.identityRange.last < case.text.length)
            assertTrue(case.text, match.amountRange.first >= match.identityRange.first)
            assertTrue(case.text, match.amountRange.last <= match.identityRange.last)
        }

        val counted = "2 5 star chocolate"
        val countedMatch = ProductLexicon.numericProductIdentities(counted).single()
        assertEquals("5", keyFragment(counted, countedMatch.amountRange))
        assertEquals("2", counted.substring(0, countedMatch.identityRange.first).trim())

        val unicodePrefix = "दूध 🍵 24 mantra atta"
        val unicodeMatch = ProductLexicon.numericProductIdentities(unicodePrefix).single()
        assertEquals(unicodePrefix.indexOf("24"), unicodeMatch.amountRange.first)
        assertEquals(unicodePrefix.indexOf("24 mantra") + "24 mantra".length - 1,
            unicodeMatch.identityRange.last)

        assertTrue(ProductLexicon.isNumericBrandPrefix("7up drink"))
        assertTrue(ProductLexicon.isNumericBrandPrefix("5 star chocolate"))
        assertTrue(ProductLexicon.isNumericBrandPrefix("24mantra atta"))
        assertFalse(ProductLexicon.isNumericBrandPrefix("oneplus charger"))
        assertFalse(ProductLexicon.isNumericBrandPrefix("maggi 2 minute noodles"))
    }

    @Test
    fun numericIdentitiesRespectWordBoundariesAndDoNotInventBrandsOrDescriptors() {
        val negatives = listOf(
            "x7up", "a7up", "7upper", "5starship", "5 starfish", "24mantras",
            "2 minute noodles", "arbitrary 2 minute noodles", "maggi2minute noodles", "oneplusx charger",
            "-7up", "+7up", "−7up",
        )
        for (text in negatives) {
            assertTrue("unexpected numeric product identity in '$text'",
                ProductLexicon.numericProductIdentities(text).isEmpty())
        }
        assertFalse(ProductLexicon.isNumericBrandPrefix("2 5 star chocolate"))
        assertFalse(ProductLexicon.isNumericBrandPrefix("a 24 mantra atta"))
    }

    @Test
    fun identityBoundariesMirrorUnicodeAndAdjacentPunctuationRules() {
        val accepted = listOf(",7up drink", ":7up drink", "7up/bottle", "(7up)")
        for (text in accepted) {
            assertEquals(text, 1, ProductLexicon.numericProductIdentities(text).size)
        }
        val rejected = listOf(
            "milk/7up", "milk-7up", "milk.7up", "milk+7up", "a\u03017up", "7up\u0301",
        )
        for (text in rejected) {
            assertTrue("unexpected identity in '$text'", ProductLexicon.numericProductIdentities(text).isEmpty())
        }

        // Android/Kotlin ranges use UTF-16 code units; the emoji occupies two.
        val nonBmpPrefix = "🍎7up drink"
        val match = ProductLexicon.numericProductIdentities(nonBmpPrefix).single()
        assertEquals(nonBmpPrefix.indexOf("7up"), match.identityRange.first)
        assertEquals("7", keyFragment(nonBmpPrefix, match.amountRange))
    }

    @Test
    fun normalizationCanonicalizesOnlyRecognizedIdentitySpansAndLeavesInputUntouched() {
        val cases = listOf(
            "7up" to "7 up",
            "5star chocolate" to "5 star chocolate",
            "24mantra atta" to "24 mantra atta",
            "one plus charger" to "oneplus charger",
            "Maggi two-minute noodles" to "maggi 2 minute noodles",
        )
        for ((input, expected) in cases) {
            val original = input
            assertEquals(input, expected, ProductLexicon.normalizeNumericProductIdentities(input))
            assertEquals(input, original)
        }
    }

    private fun keyFragment(text: String, range: IntRange): String =
        text.substring(range.first, range.last + 1)
}
