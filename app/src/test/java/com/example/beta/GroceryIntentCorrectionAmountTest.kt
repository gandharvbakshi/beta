package com.example.beta

import java.math.BigDecimal
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentCorrectionAmountTest {
    @Test
    fun parsesClosedPositiveAmountAtoms(): Unit {
        mapOf(
            "one" to "1",
            "ek" to "1",
            "do" to "2",
            "twenty five" to "25",
            "twenty-five" to "25",
            "dedh" to "1.5",
            "dhai" to "2.5",
            "one and a half" to "1.5",
            "two and a quarter" to "2.25",
            "half a" to "0.5",
            "quarter a" to "0.25",
            "3/4" to "0.75",
            "2 1/2" to "2.5",
            "dozen" to "12",
            "two dozen" to "24",
            "two dozens" to "24",
            "half a dozen" to "6",
            "one and a half dozen" to "18",
            "1,234.50" to "1234.5",
            "1,23,456.75" to "123456.75",
            "10,00,000" to "1000000",
            "\u00be" to "0.75",
        ).forEach { (input, expected) ->
            assertAmount(expected, parseIntentCorrectionAmount(input))
        }
    }

    @Test
    fun parsesClosedEnglishAndHindiHundredCompoundsExactly(): Unit {
        mapOf(
            "hundred" to "100",
            "sau" to "100",
            "a hundred" to "100",
            "one hundred" to "100",
            "nine hundred" to "900",
            "one hundred one" to "101",
            "one hundred and one" to "101",
            "a hundred twenty" to "120",
            "two hundred fifty" to "250",
            "three hundred twenty five" to "325",
            "three hundred twenty-five" to "325",
            "one hundred and ninety nine" to "199",
            "four hundred and forty-two" to "442",
            "ek sau" to "100",
            "do sau" to "200",
            "teen sau" to "300",
            "char sau" to "400",
            "chaar sau" to "400",
            "paanch sau" to "500",
            "panch sau" to "500",
            "chhe sau" to "600",
            "cheh sau" to "600",
            "saat sau" to "700",
            "aath sau" to "800",
            "nau sau" to "900",
            "aadha sau" to "50",
            "adha sau" to "50",
            "dedh sau" to "150",
            "dhai sau" to "250",
            "ONE HUNDRED AND TWENTY-FIVE" to "125",
            "  one   hundred\tand\tforty-two \t" to "142",
            "DO SAU" to "200",
        ).forEach { (input, expected) -> assertAmount(expected, parseIntentCorrectionAmount(input)) }
    }

    @Test
    fun rejectsUnknownPartialCompoundOrOutOfRangeAtoms(): Unit {
        listOf(
            "",
            " ".repeat(65),
            "0",
            "0.0",
            "1000000.01",
            "-1",
            "+1",
            "1 + 2",
            "2 kg",
            "one milk",
            "one and half",
            "twenty five six",
            "two dozen dozen",
            "dozen dozen",
            "1/3",
            "2/0",
            "2 3/2",
            "2 0/5",
            "one and a aadha",
            "zero hundred",
            "ten hundred",
            "twenty hundred",
            "hundred one",
            "one hundred and",
            "one hundred and half",
            "one hundred zero",
            "one hundred hundred",
            "two hundred hundred",
            "two sau hundred",
            "do sau bees",
            "do sau bees eggs",
            "one hundred plus one",
            "one hundred, one",
            "one\u202ehundred",
            "1,23,45",
            "1e3",
            "NaN",
            "Infinity",
        ).forEach { input ->
            assertNull(input, parseIntentCorrectionAmount(input))
        }
    }

    @Test
    fun parsesOnlyFullStringsAndDoesNotExtractEmbeddedNumbers(): Unit {
        assertNull(parseIntentCorrectionAmount("make rice 2 kg"))
        assertNull(parseIntentCorrectionAmount("actually one"))
        assertNull(parseIntentCorrectionAmount("one, two"))
        assertNull(parseIntentCorrectionAmount("2 or 3"))
    }

    private fun assertAmount(expected: String, actual: BigDecimal?) {
        assertTrue("Expected $expected but got $actual", actual != null && actual.compareTo(BigDecimal(expected)) == 0)
    }
}
