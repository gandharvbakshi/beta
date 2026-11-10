package com.example.beta

import java.text.BreakIterator
import java.util.Collections
import java.util.Locale

/**
 * User-authored text edits, never model evidence or permission to add anything.
 * A consumer must adopt against the same current snapshot, persist successfully,
 * and recheck the entire revised request. No row proof or consent carries over.
 */
internal class GroceryIntentRequestRevision private constructor(
    val originalInstruction: String,
    val instruction: String,
    val revision: Int,
    amendments: List<Amendment>,
) {
    val amendments: List<Amendment> = Collections.unmodifiableList(ArrayList(amendments))

    internal data class Amendment(
        val revision: Int,
        val source: GroceryIntentReviewV2Quote,
        val replacement: String,
    ) {
        override fun toString() = "Amendment(revision=$revision, text=<redacted>)"
    }

    /** Called only for an explicit user edit; identical text does not identify a revision. */
    fun replace(
        expectedBase: GroceryIntentRequestRevision,
        source: GroceryIntentReviewV2Quote,
        replacement: String,
    ): GroceryIntentRequestRevision? {
        if (expectedBase !== this || revision == Int.MAX_VALUE || !validText(replacement)) return null
        val start = source.startUtf16
        val end = source.endUtf16
        if (!containsSource(source) || source.text == replacement) return null
        val revised = instruction.replaceRange(start, end, replacement)
        if (!validText(revised)) return null
        return GroceryIntentRequestRevision(originalInstruction, revised, revision + 1,
            amendments + Amendment(revision, source, replacement))
    }

    override fun toString() = "GroceryIntentRequestRevision(revision=$revision, text=<redacted>)"

    internal fun containsSource(source: GroceryIntentReviewV2Quote): Boolean =
        source.startUtf16 >= 0 && source.endUtf16 > source.startUtf16 && source.endUtf16 <= instruction.length &&
            instruction.substring(source.startUtf16, source.endUtf16) == source.text &&
            isSourceBoundary(source.startUtf16) && isSourceBoundary(source.endUtf16)

    internal fun isSourceBoundary(offset: Int): Boolean {
        if (offset !in 0..instruction.length) return false
        val boundaries = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(instruction) }
        return safeBoundary(boundaries, offset)
    }

    private fun safeBoundary(boundaries: BreakIterator, offset: Int): Boolean {
        if (!boundaries.isBoundary(offset)) return false
        if (offset == 0 || offset == instruction.length) return true
        // Older runtime Unicode tables can split an Indic consonant after its
        // combining linker. Conservatively require a larger edit span whenever
        // a mark directly precedes a letter; never normalize or drop the mark.
        if (!Character.isLetter(instruction.codePointAt(offset))) return true
        var base = offset
        while (base > 0 && isMark(instruction.codePointBefore(base))) {
            base -= Character.charCount(instruction.codePointBefore(base))
        }
        return base == offset || base == 0 || !Character.isLetter(instruction.codePointBefore(base))
    }

    companion object {
        private fun isMark(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
            Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt() -> true
            else -> false
        }

        fun begin(instruction: String): GroceryIntentRequestRevision {
            require(validText(instruction)) { "Invalid request text" }
            return GroceryIntentRequestRevision(instruction, instruction, 0, emptyList())
        }

        private fun validText(text: String): Boolean {
            if (text.codePointCount(0, text.length) > 6000) return false // Intent contract counts code points.
            var index = 0
            while (index < text.length) {
                val char = text[index]
                if (char.isHighSurrogate()) {
                    if (text.getOrNull(index + 1)?.isLowSurrogate() != true) return false
                    index += 2
                } else {
                    if (char.isLowSurrogate() || char.isISOControl() && char !in "\n\r\t") return false
                    index++
                }
            }
            return true
        }
    }
}
