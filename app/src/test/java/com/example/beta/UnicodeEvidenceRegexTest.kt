package com.example.beta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.PatternSyntaxException

class UnicodeEvidenceRegexTest {
    @Test fun unicodeWordAndWhitespaceClassesHandleHindiAndCombiningMarks() {
        val words = unicodeEvidenceRegex("\\w+")
        val phrase = unicodeEvidenceRegex("\\w+\\s+\\w+")

        assertTrue(words.matches("दूध"))
        assertTrue(words.matches("e\u0301"))
        assertTrue(phrase.matches("दूध\u00a0milk"))
        assertTrue(phrase.matches("sugar\u202fMILK"))
        assertFalse(phrase.matches("milk+sugar"))
        assertThrows(PatternSyntaxException::class.java) { unicodeEvidenceRegex("[") }
    }

    @Test fun caseFoldingIsEnabledOnlyWhenRequested() {
        assertTrue(unicodeEvidenceRegex("^milk$").matches("MILK"))
        assertFalse(unicodeEvidenceRegex("^milk$", ignoreCase = false).matches("MILK"))
        assertTrue(unicodeEvidenceRegex("^साखर$").matches("साखर"))
    }

    @Test fun nutritionClaimsKeepUnicodeZeroAndPhysicalSugarRolesDistinct() {
        val zero = nutritionMeasureEvidence("0\u00a0g\u202fsugar per serving bar 50g")
        assertFalse(zero.unsupported)
        assertEquals("0", zero.claims.single().amount)
        assertTrue(zero.physicalText.contains("50g"))

        val physicalSugar = nutritionMeasureEvidence("500\u00a0g sugar")
        assertFalse(physicalSugar.unsupported)
        assertEquals("500\u00a0g sugar", physicalSugar.physicalText)
        assertTrue(nutritionMeasureEvidence("0 g sugar per serving 2 g sugar per serving").unsupported)
        assertTrue(nutritionMeasureEvidence("Chocolate with 20g sugar").unsupported)
    }

    @Test fun numericDescriptorAndPhysicalPackEvidenceRemainSeparate() {
        assertTrue(groceryIntentNumericDescriptorClaimsMatch("battery 1.5 volts", "battery 1.5 volts"))
        assertFalse(groceryIntentNumericDescriptorClaimsMatch("battery 1.5 volts", "battery not 1.5 volts"))
        assertFalse(groceryIntentNumericDescriptorClaimsMatch("battery 1.5 volts", "battery 5.1 volts"))
        assertTrue(groceryIntentNumericDescriptorClaimsMatch("bulb 9 watts", "bulb 9 watts 50g"))
        assertTrue(groceryIntentNumericDescriptorClaimsMatch(
            "battery 1.5 volts", "battery 1.5 volts - 2 packs",
        ))
    }
}
