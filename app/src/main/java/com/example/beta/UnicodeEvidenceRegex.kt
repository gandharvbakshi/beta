package com.example.beta

import java.text.Normalizer
import java.util.regex.Pattern

/** Exact decimal comparison only, with boundaries back to original UTF-16.
 * Supplementary digits become one ASCII char, so regex ranges need this map. */
internal class DecimalDigitComparison(val text: String, private val boundaries: IntArray) {
    fun originalRange(range: IntRange): IntRange = boundaries[range.first] until boundaries[range.last + 1]
}

internal fun decimalDigitComparison(source: String): DecimalDigitComparison {
    val boundaries = mutableListOf(0)
    val text = StringBuilder()
    var offset = 0
    while (offset < source.length) {
        val point = source.codePointAt(offset)
        val width = Character.charCount(point)
        if (Character.getType(point) == Character.DECIMAL_DIGIT_NUMBER.toInt()) {
            text.append(Character.digit(point, 10))
            boundaries += offset + width
        } else {
            text.appendCodePoint(point)
            repeat(width) { boundaries += offset + it + 1 }
        }
        offset += width
    }
    return DecimalDigitComparison(text.toString(), boundaries.toIntArray())
}

// Comparison only: never normalize the instruction used for source offsets.
// A combining mark belongs to its word; a mark alone is not an identity.
private val identityWord = Regex("[\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N}\\u200C\\u200D]*")
internal fun groceryIdentityWords(text: String): Sequence<String> =
    identityWord.findAll(text).map { Normalizer.normalize(it.value, Normalizer.Form.NFC) }

// OpenJDK needs this flag; Android ICU uses Unicode classes by default and
// rejects both the flag and inline (?U). Probe a fixed empty pattern once, not
// a caller's expression: malformed expressions must still fail normally.
private val unicodeEvidenceClassFlag: Int = try {
    Pattern.compile("", Pattern.UNICODE_CHARACTER_CLASS)
    Pattern.UNICODE_CHARACTER_CLASS
} catch (_: IllegalArgumentException) {
    check(Pattern.compile("\\w+\\s+\\w+").matcher("दूध\u00a0milk").matches()) {
        "Regex engine does not provide Unicode character classes"
    }
    0
}

/** Preserve Unicode word/space semantics on both Android ICU and desktop JVM. */
internal fun unicodeEvidenceRegex(pattern: String, ignoreCase: Boolean = true): Regex =
    Pattern.compile(pattern, unicodeEvidenceClassFlag or
        if (ignoreCase) Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE else 0).toRegex()
