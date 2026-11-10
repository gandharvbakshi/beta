package com.example.beta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentRequestRevisionTest {
    private fun quote(text: String, value: String, occurrence: Int = 0): GroceryIntentReviewV2Quote {
        var start = -1
        var from = 0
        repeat(occurrence + 1) {
            start = text.indexOf(value, from)
            require(start >= 0) { "Quote not present" }
            from = start + value.length
        }
        return GroceryIntentReviewV2Quote(value, start, start + value.length)
    }

    @Test fun explicitReplacementPreservesOriginalAndAllTextOutsideTheBoundSpan() {
        val original = "Buy 2 rice, 1 milk; keep it dairy-free and deliver tomorrow."
        val root = GroceryIntentRequestRevision.begin(original)

        val changed = root.replace(root, quote(original, "rice"), "basmati rice")

        assertEquals(original, root.originalInstruction)
        assertEquals(original, root.instruction)
        assertEquals("Buy 2 basmati rice, 1 milk; keep it dairy-free and deliver tomorrow.", changed?.instruction)
        assertEquals(original, changed?.originalInstruction)
        assertEquals(1, changed?.revision)
        assertEquals(0, changed?.amendments?.single()?.revision)
        assertEquals("rice", changed?.amendments?.single()?.source?.text)
        assertEquals("basmati rice", changed?.amendments?.single()?.replacement)
    }

    @Test fun repeatedQuoteUsesOnlyTheExplicitUtf16Location() {
        val original = "rice and rice, no substitutions"
        val root = GroceryIntentRequestRevision.begin(original)
        val changed = root.replace(root, quote(original, "rice", occurrence = 1), "brown rice")

        assertEquals("rice and brown rice, no substitutions", changed?.instruction)
        assertEquals("millet and rice, no substitutions",
            root.replace(root, quote(original, "rice"), "millet")?.instruction)
    }

    @Test fun staleRevisionIsRejectedEvenAfterTextReturnsToItsOriginalValue() {
        val root = GroceryIntentRequestRevision.begin("rice")
        val first = requireNotNull(root.replace(root, quote("rice", "rice"), "millet"))
        val second = requireNotNull(first.replace(first, quote("millet", "millet"), "rice"))

        assertEquals(root.instruction, second.instruction)
        assertEquals(2, second.revision)
        assertNull(second.replace(root, quote(second.instruction, "rice"), "oats"))
    }

    @Test fun editChainUsesCurrentCoordinatesAndLeavesEarlierSnapshotsImmutable() {
        val root = GroceryIntentRequestRevision.begin("A, B, C")
        val first = requireNotNull(root.replace(root, quote(root.instruction, "B"), "longer item B"))
        val second = requireNotNull(first.replace(first, quote(first.instruction, "C"), "item C"))

        assertEquals("A, B, C", root.instruction)
        assertEquals("A, longer item B, C", first.instruction)
        assertEquals("A, longer item B, item C", second.instruction)
        assertEquals(1, first.amendments.size)
        assertEquals(2, second.amendments.size)
        assertNotSame(root.amendments, second.amendments)
        try {
            (second.amendments as MutableList<*>).clear()
            fail("Amendment snapshots must not expose a mutable backing list")
        } catch (_: UnsupportedOperationException) {
            // Expected: callers may inspect, not mutate, the revision history.
        }
    }

    @Test fun identicalTextFromAnotherRootCannotSupplyTheExpectedBase() {
        val text = "rice and milk"
        val current = GroceryIntentRequestRevision.begin(text)
        val otherRoot = GroceryIntentRequestRevision.begin(text)

        assertNull(current.replace(otherRoot, quote(text, "rice"), "oats"))
        assertEquals(text, current.instruction)
    }

    @Test fun siblingBranchesWithIdenticalRevisionAndTextCannotCrossAuthorize() {
        val root = GroceryIntentRequestRevision.begin("rice and milk")
        val left = requireNotNull(root.replace(root, quote(root.instruction, "rice"), "oats"))
        val right = requireNotNull(root.replace(root, quote(root.instruction, "rice"), "oats"))

        assertEquals(left.revision, right.revision)
        assertEquals(left.instruction, right.instruction)
        assertNull(left.replace(right, quote(left.instruction, "milk"), "curd"))
    }

    @Test fun twentyRowsAndCrossRowConditionsRemainUntouchedBySingleSpanEdit() {
        val rows = (1..20).map { "item$it" }
        val original = rows.joinToString(", ") + "; no peanuts; deliver after 6 pm"
        val root = GroceryIntentRequestRevision.begin(original)
        val revised = requireNotNull(root.replace(root, quote(original, "item11"), "item eleven"))

        val expected = rows.map { if (it == "item11") "item eleven" else it }
            .joinToString(", ") + "; no peanuts; deliver after 6 pm"
        assertEquals(expected, revised.instruction)
        assertTrue(revised.instruction.contains("no peanuts; deliver after 6 pm"))
        rows.filterNot { it == "item11" }.forEach { assertTrue(revised.instruction.contains(it)) }
        assertEquals(20, expected.substringBefore(';').split(", ").size)
    }

    @Test fun blankReplacementIsRepresentableButDoesNotChangeTheOriginal() {
        val original = "just rice"
        val root = GroceryIntentRequestRevision.begin(original)
        val revised = root.replace(root, quote(original, original), "")

        assertEquals("", revised?.instruction)
        assertEquals(original, revised?.originalInstruction)
        assertEquals(original, root.instruction)
    }

    @Test fun unchangedReplacementIsNoOpAndSurroundingWhitespaceIsPreservedExactly() {
        val original = "  चावल और दूध  "
        val root = GroceryIntentRequestRevision.begin(original)

        assertNull(root.replace(root, quote(original, "चावल"), "चावल"))
        val revised = requireNotNull(root.replace(root, quote(original, "चावल"), "धान"))
        assertEquals("  धान और दूध  ", revised.instruction)
    }

    @Test fun forgedWrongAndOutOfBoundsQuotesAreRejected() {
        val original = "rice, milk"
        val root = GroceryIntentRequestRevision.begin(original)

        assertNull(root.replace(root, GroceryIntentReviewV2Quote("beans", 0, 4), "oats"))
        assertNull(root.replace(root, GroceryIntentReviewV2Quote("rice", 1, 5), "oats"))
        assertNull(root.replace(root, GroceryIntentReviewV2Quote("rice", -1, 3), "oats"))
        assertNull(root.replace(root, GroceryIntentReviewV2Quote("rice", 6, 10), "oats"))
        assertNull(root.replace(root, GroceryIntentReviewV2Quote("", 0, 0), "oats"))
    }

    @Test fun utf16AndCombiningCharacterBoundariesCannotBeSplit() {
        val emojiText = "rice 😀 milk"
        val emoji = emojiText.indexOf("😀")
        val emojiRoot = GroceryIntentRequestRevision.begin(emojiText)
        assertNull(emojiRoot.replace(emojiRoot,
            GroceryIntentReviewV2Quote("\uD83D", emoji, emoji + 1), "x"))

        val combiningText = "cafe\u0301 milk"
        val mark = combiningText.indexOf('\u0301')
        val combiningRoot = GroceryIntentRequestRevision.begin(combiningText)
        assertNull(combiningRoot.replace(combiningRoot,
            GroceryIntentReviewV2Quote("\u0301", mark, mark + 1), "x"))
        assertNull(combiningRoot.replace(combiningRoot,
            GroceryIntentReviewV2Quote("e", mark - 1, mark), "x"))
    }

    @Test fun wellBoundEmojiAndNativeTextCanBeReplacedWithoutOffsetDrift() {
        val original = "😀 चावल, cafe\u0301"
        val root = GroceryIntentRequestRevision.begin(original)
        val revised = requireNotNull(root.replace(root, quote(original, "चावल"), "धान"))

        assertEquals("😀 धान, cafe\u0301", revised.instruction)
        assertEquals(original, revised.originalInstruction)
    }

    @Test fun malformedUtf16ReplacementIsRejected() {
        val original = "rice"
        val root = GroceryIntentRequestRevision.begin(original)

        assertNull(root.replace(root, quote(original, "rice"), "bad\uD800"))
        assertNull(root.replace(root, quote(original, "rice"), "bad\uDC00"))
    }

    @Test fun everyInternalUtf16BoundaryOfComplexClustersIsRejectedButWholeClusterWorks() {
        val clusters = listOf(
            "👨‍👩‍👧‍👦", // ZWJ family
            "👍🏽", // skin-tone modifier
            "🇮🇳", // regional-indicator flag
            "✈️", // variation selector
            "\r\n", // CRLF grapheme
            "क्ष", // Indic consonant + virama + consonant
        )

        clusters.forEachIndexed { clusterIndex, cluster ->
            val text = "A${cluster}B"
            val root = GroceryIntentRequestRevision.begin(text)
            val start = 1
            for (internalBoundary in 1 until cluster.length) {
                val partial = text.substring(start, start + internalBoundary)
                val quote = GroceryIntentReviewV2Quote(partial, start, start + internalBoundary)
                assertNull("cluster[$clusterIndex] boundary=$internalBoundary",
                    root.replace(root, quote, "X"))
            }
            val fullQuote = GroceryIntentReviewV2Quote(cluster, start, start + cluster.length)
            val revised = requireNotNull(root.replace(root, fullQuote, "X"))
            assertEquals("AXB", revised.instruction)
        }
    }

    @Test fun disallowedControlsAndOversizedInstructionsAreRejected() {
        val root = GroceryIntentRequestRevision.begin("rice")
        assertNull(root.replace(root, quote(root.instruction, "rice"), "rice\u0001"))

        var rejectedControl = false
        try {
            GroceryIntentRequestRevision.begin("rice\u0001")
        } catch (_: IllegalArgumentException) {
            rejectedControl = true
        }
        assertTrue(rejectedControl)

        var rejectedOversizedRoot = false
        try {
            GroceryIntentRequestRevision.begin("x".repeat(6001))
        } catch (_: IllegalArgumentException) {
            rejectedOversizedRoot = true
        }
        assertTrue(rejectedOversizedRoot)

        val atLimit = GroceryIntentRequestRevision.begin("x".repeat(6000))
        assertNull(atLimit.replace(atLimit,
            GroceryIntentReviewV2Quote("x", 0, 1), "yy")) // Final instruction would be 6001 UTF-16 units.
        val fullSpan = GroceryIntentReviewV2Quote(atLimit.instruction, 0, atLimit.instruction.length)
        assertNull(atLimit.replace(atLimit, fullSpan, "y".repeat(6001)))
    }

    @Test fun supplementaryCharactersCountAsOneTowardTheInstructionLimit() {
        val atLimitText = "😀".repeat(6000)
        assertEquals(6000, atLimitText.codePointCount(0, atLimitText.length))
        val atLimit = GroceryIntentRequestRevision.begin(atLimitText)
        assertEquals(atLimitText.length, atLimit.instruction.length)
        assertNull(atLimit.replace(atLimit,
            GroceryIntentReviewV2Quote("😀", 0, 2), "😁😀")) // Final would have 6001 code points.

        var rejected = false
        try {
            GroceryIntentRequestRevision.begin("😀".repeat(6001))
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test fun revisionAndAmendmentStringFormsNeverExposeInstructionOrEditText() {
        val secretWords = "private grocery phrase"
        val root = GroceryIntentRequestRevision.begin(secretWords)
        val revised = requireNotNull(root.replace(root, quote(secretWords, secretWords), "confidential replacement"))
        val amendmentText = revised.amendments.single().toString()

        listOf(root.toString(), revised.toString(), amendmentText).forEach { rendered ->
            assertFalse(rendered.contains(secretWords))
            assertFalse(rendered.contains("confidential replacement"))
        }
    }
}
