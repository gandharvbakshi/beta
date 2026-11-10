package com.example.beta

import java.math.BigDecimal
import java.math.BigInteger
import java.util.Locale

private val correctionAmountWords: Map<String, CorrectionRational> = mapOf(
    "a" to 1.correctionAmountRational(),
    "an" to 1.correctionAmountRational(),
    "one" to 1.correctionAmountRational(),
    "two" to 2.correctionAmountRational(),
    "three" to 3.correctionAmountRational(),
    "four" to 4.correctionAmountRational(),
    "five" to 5.correctionAmountRational(),
    "six" to 6.correctionAmountRational(),
    "seven" to 7.correctionAmountRational(),
    "eight" to 8.correctionAmountRational(),
    "nine" to 9.correctionAmountRational(),
    "ten" to 10.correctionAmountRational(),
    "eleven" to 11.correctionAmountRational(),
    "twelve" to 12.correctionAmountRational(),
    "thirteen" to 13.correctionAmountRational(),
    "fourteen" to 14.correctionAmountRational(),
    "fifteen" to 15.correctionAmountRational(),
    "sixteen" to 16.correctionAmountRational(),
    "seventeen" to 17.correctionAmountRational(),
    "eighteen" to 18.correctionAmountRational(),
    "nineteen" to 19.correctionAmountRational(),
    "twenty" to 20.correctionAmountRational(),
    "thirty" to 30.correctionAmountRational(),
    "forty" to 40.correctionAmountRational(),
    "fifty" to 50.correctionAmountRational(),
    "sixty" to 60.correctionAmountRational(),
    "seventy" to 70.correctionAmountRational(),
    "eighty" to 80.correctionAmountRational(),
    "ninety" to 90.correctionAmountRational(),
    "ek" to 1.correctionAmountRational(),
    "do" to 2.correctionAmountRational(),
    "teen" to 3.correctionAmountRational(),
    "char" to 4.correctionAmountRational(),
    "chaar" to 4.correctionAmountRational(),
    "paanch" to 5.correctionAmountRational(),
    "panch" to 5.correctionAmountRational(),
    "chhe" to 6.correctionAmountRational(),
    "cheh" to 6.correctionAmountRational(),
    "saat" to 7.correctionAmountRational(),
    "aath" to 8.correctionAmountRational(),
    "nau" to 9.correctionAmountRational(),
    "das" to 10.correctionAmountRational(),
    "barah" to 12.correctionAmountRational(),
    "एक" to 1.correctionAmountRational(),
    "दो" to 2.correctionAmountRational(),
    "तीन" to 3.correctionAmountRational(),
    "चार" to 4.correctionAmountRational(),
    "पांच" to 5.correctionAmountRational(),
    "पाँच" to 5.correctionAmountRational(),
    "छह" to 6.correctionAmountRational(),
    "छः" to 6.correctionAmountRational(),
    "सात" to 7.correctionAmountRational(),
    "आठ" to 8.correctionAmountRational(),
    "नौ" to 9.correctionAmountRational(),
    "दस" to 10.correctionAmountRational(),
    "ग्यारह" to 11.correctionAmountRational(),
    "बारह" to 12.correctionAmountRational(),
    "तेरह" to 13.correctionAmountRational(),
    "चौदह" to 14.correctionAmountRational(),
    "पंद्रह" to 15.correctionAmountRational(),
    "पन्द्रह" to 15.correctionAmountRational(),
    "सोलह" to 16.correctionAmountRational(),
    "सत्रह" to 17.correctionAmountRational(),
    "अठारह" to 18.correctionAmountRational(),
    "उन्नीस" to 19.correctionAmountRational(),
    "बीस" to 20.correctionAmountRational(),
    "तीस" to 30.correctionAmountRational(),
    "चालीस" to 40.correctionAmountRational(),
    "पचास" to 50.correctionAmountRational(),
    "साठ" to 60.correctionAmountRational(),
    "सत्तर" to 70.correctionAmountRational(),
    "अस्सी" to 80.correctionAmountRational(),
    "नब्बे" to 90.correctionAmountRational(),
    "सौ" to 100.correctionAmountRational(),
    "half" to CorrectionRational(1, 2),
    "आधा" to CorrectionRational(1, 2),
    "आधी" to CorrectionRational(1, 2),
    "aadha" to CorrectionRational(1, 2),
    "adha" to CorrectionRational(1, 2),
    "quarter" to CorrectionRational(1, 4),
    "paav" to CorrectionRational(1, 4),
    "pav" to CorrectionRational(1, 4),
    "पाव" to CorrectionRational(1, 4),
    "dedh" to CorrectionRational(3, 2),
    "डेढ़" to CorrectionRational(3, 2),
    "dhai" to CorrectionRational(5, 2),
    "ढाई" to CorrectionRational(5, 2),
    "\u00bd" to CorrectionRational(1, 2),
    "\u00bc" to CorrectionRational(1, 4),
    "\u00be" to CorrectionRational(3, 4),
)

private val correctionAmountTens = setOf("twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
private val correctionAmountEnglishOnes = setOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
private val correctionAmountEnglishSmall = correctionAmountEnglishOnes + correctionAmountTens + setOf(
    "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
)
private val correctionAmountSauMultipliers = setOf(
    "ek", "do", "teen", "char", "chaar", "paanch", "panch", "chhe", "cheh", "saat", "aath", "nau",
    "aadha", "adha", "dedh", "dhai",
    "एक", "दो", "तीन", "चार", "पांच", "पाँच", "छह", "छः", "सात", "आठ", "नौ",
    "आधा", "आधी", "डेढ़", "ढाई",
)
private val correctionAmountDigits = Regex("(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+|[0-9]{1,2}(?:,[0-9]{2})*,[0-9]{3})(?:\\.[0-9]+)?")
private val correctionAmountFraction = Regex("[0-9]+/[0-9]+")
private val correctionAmountMixedFraction = Regex("[0-9]+ [0-9]+/[0-9]+")
private val correctionAmountLimit = BigDecimal("1000000")

/**
 * Parse one complete correction amount atom.
 *
 * This intentionally accepts only the closed grammar used for typed v2
 * correction proof. It never extracts numbers from a larger sentence and
 * returns null for unknown, partial, compound or non-exact decimal forms.
 */
internal fun parseIntentCorrectionAmount(text: String): BigDecimal? {
    if (text.isBlank() || text.length > 64) return null
    // Arithmetic-only Unicode decimal folding. Never normalize source quotes,
    // product identities, compatibility numerals or their original offsets.
    val decimalView = decimalDigitComparison(text).text
    val value = decimalView.trim().lowercase(Locale.ROOT).replace(unicodeEvidenceRegex("\\s+"), " ")
    val parsed = parseIntentCorrectionRational(value) ?: return null
    val decimal = parsed.toBigDecimalExact() ?: return null
    return decimal.takeIf { it > BigDecimal.ZERO && it <= correctionAmountLimit }
}

private fun parseIntentCorrectionRational(value: String): CorrectionRational? {
    correctionAmountWords[value]?.let { return it }
    parseHundredCorrectionAmount(value)?.let { return it }
    if (value == "dozen") return 12.correctionAmountRational()
    if (value.endsWith(" dozen") || value.endsWith(" dozens")) {
        val prefix = if (value.endsWith(" dozens")) {
            value.removeSuffix(" dozens")
        } else {
            value.removeSuffix(" dozen")
        }
        if ("dozen" in prefix) return null
        return parseIntentCorrectionRational(prefix)?.times(12.correctionAmountRational())
    }
    if (correctionAmountDigits.matches(value)) {
        return runCatching { BigDecimal(value.replace(",", "")).toCorrectionRational() }.getOrNull()
    }
    if (correctionAmountFraction.matches(value)) {
        val (numerator, denominator) = value.split("/", limit = 2)
        val den = denominator.toBigIntegerOrNull() ?: return null
        if (den == BigInteger.ZERO) return null
        return CorrectionRational(numerator.toBigIntegerOrNull() ?: return null, den)
    }
    if (correctionAmountMixedFraction.matches(value)) {
        val (wholeText, fractionText) = value.split(" ", limit = 2)
        val fraction = parseIntentCorrectionRational(fractionText) ?: return null
        if (fraction <= 0.correctionAmountRational() || fraction >= 1.correctionAmountRational()) return null
        return (wholeText.toBigIntegerOrNull() ?: return null).correctionAmountRational() + fraction
    }
    val words = value.replace("-", " ").split(" ")
    if (words.size == 2 && words[0] in correctionAmountTens) {
        val ones = correctionAmountWords[words[1]]
        if (ones != null && ones.denominator == BigInteger.ONE && ones.numerator in BigInteger.ONE..BigInteger.valueOf(9)) {
            return correctionAmountWords.getValue(words[0]) + ones
        }
    }
    if (words.size == 4 && words[1] == "and" && words[2] == "a") {
        val whole = correctionAmountWords[words[0]]
        val fraction = correctionAmountWords[words[3]]
        if (whole != null && fraction != null && whole.denominator == BigInteger.ONE &&
            words[3] in setOf("half", "quarter")) {
            return whole + fraction
        }
    }
    if (words.size == 2 && words[0] == "a" && words[1] in setOf("half", "quarter")) {
        return correctionAmountWords[words[1]]
    }
    if (words.size == 2 && words[0] in setOf("half", "quarter") && words[1] == "a") {
        return correctionAmountWords[words[0]]
    }
    return null
}

/** Complete hundreds only; this never assigns a purchase/pack/nutrition role. */
private fun parseHundredCorrectionAmount(value: String): CorrectionRational? {
    if (value in setOf("hundred", "sau", "सौ")) return 100.correctionAmountRational()
    val words = value.replace("-", " ").split(" ")
    if (words.size == 2 && words[1] in setOf("sau", "सौ") && words[0] in correctionAmountSauMultipliers) {
        return correctionAmountWords.getValue(words[0]) * 100.correctionAmountRational()
    }
    if (words.size < 2 || words[1] != "hundred" || words[0] !in correctionAmountEnglishOnes + "a") return null
    val base = correctionAmountWords.getValue(words[0]) * 100.correctionAmountRational()
    var tail = words.drop(2)
    if (tail.isEmpty()) return base
    if (tail.first() == "and") tail = tail.drop(1)
    if (tail.size == 1 && tail[0] in correctionAmountEnglishSmall) {
        return base + correctionAmountWords.getValue(tail[0])
    }
    if (tail.size == 2 && tail[0] in correctionAmountTens && tail[1] in correctionAmountEnglishOnes) {
        return base + correctionAmountWords.getValue(tail[0]) + correctionAmountWords.getValue(tail[1])
    }
    return null
}

private data class CorrectionRational(
    val numerator: BigInteger,
    val denominator: BigInteger,
) : Comparable<CorrectionRational> {
    constructor(numerator: Int, denominator: Int) : this(BigInteger.valueOf(numerator.toLong()), BigInteger.valueOf(denominator.toLong()))

    init {
        require(denominator != BigInteger.ZERO)
    }

    operator fun plus(other: CorrectionRational): CorrectionRational =
        CorrectionRational(numerator * other.denominator + other.numerator * denominator, denominator * other.denominator).normalized()

    operator fun times(other: CorrectionRational): CorrectionRational =
        CorrectionRational(numerator * other.numerator, denominator * other.denominator).normalized()

    override fun compareTo(other: CorrectionRational): Int =
        (numerator * other.denominator).compareTo(other.numerator * denominator)

    fun toBigDecimalExact(): BigDecimal? =
        runCatching { BigDecimal(numerator).divide(BigDecimal(denominator)) }.getOrNull()?.stripTrailingZeros()

    private fun normalized(): CorrectionRational {
        val divisor = numerator.gcd(denominator).takeUnless { it == BigInteger.ZERO } ?: BigInteger.ONE
        val sign = if (denominator.signum() < 0) BigInteger.valueOf(-1) else BigInteger.ONE
        return CorrectionRational(numerator / divisor * sign, denominator / divisor * sign)
    }
}

private fun Int.correctionAmountRational(): CorrectionRational = BigInteger.valueOf(toLong()).correctionAmountRational()

private fun BigInteger.correctionAmountRational(): CorrectionRational = CorrectionRational(this, BigInteger.ONE)

private fun BigDecimal.toCorrectionRational(): CorrectionRational {
    val scale = scale().coerceAtLeast(0)
    val denominator = BigInteger.TEN.pow(scale)
    return CorrectionRational(unscaledValue(), denominator)
}
