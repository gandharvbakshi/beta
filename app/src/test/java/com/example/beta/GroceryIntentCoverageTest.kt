package com.example.beta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentCoverageTest {
    @Test fun aPartialWordCannotClaimTheRestOfItsMarkedSourceToken() {
        val word = "तेल"
        assertEquals(listOf(word), uncoveredGroceryIntentText(word, listOf("त")))
        assertEquals(listOf(word), uncoveredGroceryIntentText(word, listOf("ल")))
        assertEquals(listOf(word), uncoveredGroceryIntentSegmentsBySpan(word, listOf(GroceryIntentCoverageSpan(0, 1))))
        assertEquals(listOf(word), uncoveredGroceryIntentSegmentsBySpan(word, listOf(
            GroceryIntentCoverageSpan(0, 1), GroceryIntentCoverageSpan(1, word.length))))
        assertTrue(uncoveredGroceryIntentText("a(milk)b", listOf("(milk)")).contains("milk"))
        assertEquals(emptyList<String>(), uncoveredGroceryIntentText(word, listOf(word)))
        val joined = "क्\u200dष"
        assertEquals(listOf(joined), uncoveredGroceryIntentText(joined, listOf("क्", "ष")))
    }
    @Test fun keepsSeparateResidualSpansAndDoesNotMaskPartiallyCitedCommands() {
        assertEquals(listOf("one samosa", "no sugar"), uncoveredGroceryIntentSegments(
            "one samosa and 2 milk and no sugar", listOf("2 milk"),
        ))
        assertEquals(listOf("cart", "mein", "do"), uncoveredGroceryIntentText(
            "cart mein daal do", listOf("daal"),
        ))
    }
    @Test
    fun allowsConversationalHinglishWithoutDroppingHindiNumberWords() {
        assertEquals(
            emptyList<String>(),
            uncoveredGroceryIntentText("Haan ji cart mein daal do milk then bread", listOf("milk", "bread")),
        )
        assertEquals(listOf("do"), uncoveredGroceryIntentText("cart mein daal do do milk", listOf("milk")))
        assertEquals(listOf("ek"), uncoveredGroceryIntentText("ek kaam karo ek milk", listOf("milk")))
    }

    @Test
    fun doesNotCreateCourtesyPhrasesByJoiningUncoveredWords() {
        assertEquals(listOf("kar", "do"), uncoveredGroceryIntentText("kar milk do", listOf("milk")))
        assertEquals(listOf("daal", "do"), uncoveredGroceryIntentText("milk and daal do", listOf("milk")))
    }

    @Test fun completeSpokenBringPhrasesDoNotHideNumbersOrProducts() {
        assertEquals(emptyList<String>(), uncoveredGroceryIntentText("milk bhi le aao", listOf("milk")))
        assertEquals(emptyList<String>(), uncoveredGroceryIntentText("milk la do. Bas itna", listOf("milk")))
        assertEquals(listOf("la", "do"), uncoveredGroceryIntentText("la milk do", listOf("milk")))
        assertEquals(listOf("do"), uncoveredGroceryIntentText("milk la do do", listOf("milk")))
        assertEquals(listOf("price", "sugar", "free"), uncoveredGroceryIntentText(
            "milk le aao price sugar free", listOf("milk"),
        ))
    }

    @Test
    fun naturalFillersDoNotSuppressMissingProductsOrProtectedModifiers() {
        assertEquals(
            listOf("sugar", "free", "tea"),
            uncoveredGroceryIntentText("uh milk then sugar free tea", listOf("milk")),
        )
        assertEquals(
            listOf("three", "fruit", "yoghurt", "cups"),
            uncoveredGroceryIntentText("add three fruit yoghurt cups then blueberry one", listOf("blueberry one")),
        )
        assertEquals(listOf("do", "not"), uncoveredGroceryIntentText("please do not kar do milk", listOf("milk")))
    }

    @Test
    fun reportsOmittedProductAmongOtherwiseCoveredItems() {
        assertEquals(
            listOf("samosa"),
            uncoveredGroceryIntentText("please get milk and bread and samosa", listOf("milk", "bread")),
        )
    }

    @Test
    fun reportsOmittedQuantityWithoutInterpretingIt() {
        assertEquals(
            listOf("2"),
            uncoveredGroceryIntentText("add 2 milk", listOf("milk")),
        )
    }

    @Test
    fun quotedSourceSpansAndSmallCourtesySetLeaveNoResidual() {
        assertEquals(
            emptyList<String>(),
            uncoveredGroceryIntentText("Please get \"milk\" and \"bread\"", listOf("milk", "bread")),
        )
    }

    @Test
    fun exactSourceTextCanCoverVerbatimUnquotedSpan() {
        assertEquals(
            emptyList<String>(),
            uncoveredGroceryIntentText("two packets of milk", listOf("two packets of milk")),
        )
    }

    @Test
    fun doesNotRemovePartialWordMatchesOrUnlistedTerms() {
        assertEquals(
            listOf("salt", "do", "not", "remove"),
            uncoveredGroceryIntentText("salt and do not remove", emptyList()),
        )
        assertTrue(uncoveredGroceryIntentText("price please", listOf("rice")).contains("price"))
    }

    @Test
    fun retainsUnknownDevanagariText() {
        assertEquals(listOf("अपरिचित"), uncoveredGroceryIntentText("अपरिचित", emptyList()))
    }

    @Test
    fun modelQuotingTheWholeUtteranceCanMaskOmissionsSoThisIsNotSemanticProof() {
        assertEquals(
            emptyList<String>(),
            uncoveredGroceryIntentText("milk and omitted samosa", listOf("milk and omitted samosa")),
        )
    }

    @Test
    fun spanCoverageDoesNotExpandAnOwnedQuoteToRepeatedOccurrences() {
        val instruction = "milk and milk"
        assertEquals(
            listOf("milk"),
            uncoveredGroceryIntentSegmentsBySpan(instruction, listOf(GroceryIntentCoverageSpan(0, 4))),
        )
    }

    @Test
    fun spanCoverageUsesUtf16OffsetsAndUnionsOverlappingRanges() {
        val instruction = "🥛 milk and bread"
        val start = instruction.indexOf("milk")
        assertEquals(
            listOf("bread"),
            uncoveredGroceryIntentSegmentsBySpan(
                instruction,
                listOf(GroceryIntentCoverageSpan(start, start + 4), GroceryIntentCoverageSpan(start + 1, start + 4)),
            ),
        )
    }

    @Test
    fun exactRangesMapToTheExistingSegmentStrings() {
        val instruction = "rice and bread"
        val bread = instruction.indexOf("bread")
        val ranges = uncoveredGroceryIntentRangesBySpan(
            instruction, listOf(GroceryIntentCoverageSpan(0, 4)),
        )

        assertEquals(listOf(GroceryIntentCoverageSpan(bread, instruction.length)), ranges)
        assertEquals(listOf("bread"), ranges.map { instruction.substring(it.startUtf16, it.endUtf16) })
        assertEquals(listOf("bread"), uncoveredGroceryIntentSegmentsBySpan(
            instruction, listOf(GroceryIntentCoverageSpan(0, 4)),
        ))
    }

    @Test
    fun exactRangesUseUtf16OffsetsAfterEmojiAndUnicodeText() {
        val instruction = "🥛 milk. अपरिचित"
        val unknownStart = instruction.indexOf("अपरिचित")
        val ranges = uncoveredGroceryIntentRangesBySpan(
            instruction,
            listOf(GroceryIntentCoverageSpan(instruction.indexOf("milk"), instruction.indexOf("milk") + 4)),
        )

        assertEquals(2, instruction.codePointCount(0, instruction.indexOf("milk")))
        assertEquals(listOf(GroceryIntentCoverageSpan(unknownStart, instruction.length)), ranges)
        assertEquals(listOf("अपरिचित"), ranges.map { instruction.substring(it.startUtf16, it.endUtf16) })
    }

    @Test
    fun repeatedUnownedWordsKeepTheirOwnOffsetsAroundAnOwnedOccurrence() {
        val instruction = "price milk price"
        val first = instruction.indexOf("price")
        val second = instruction.lastIndexOf("price")
        val milk = instruction.indexOf("milk")
        val ranges = uncoveredGroceryIntentRangesBySpan(
            instruction, listOf(GroceryIntentCoverageSpan(milk, milk + "milk".length)),
        )

        assertEquals(listOf(
            GroceryIntentCoverageSpan(first, first + "price".length),
            GroceryIntentCoverageSpan(second, second + "price".length),
        ), ranges)
        assertEquals(listOf("price", "price"), ranges.map { instruction.substring(it.startUtf16, it.endUtf16) })
    }

    @Test
    fun punctuationDoesNotMoveUncoveredTokenBoundaries() {
        val instruction = "!rice,, milk!!! sugar?"
        val milk = instruction.indexOf("milk")
        val ranges = uncoveredGroceryIntentRangesBySpan(
            instruction, listOf(GroceryIntentCoverageSpan(milk, milk + 4)),
        )

        assertEquals(listOf(
            GroceryIntentCoverageSpan(instruction.indexOf("rice"), instruction.indexOf("rice") + 4),
            GroceryIntentCoverageSpan(instruction.indexOf("sugar"), instruction.indexOf("sugar") + 5),
        ), ranges)
    }

    @Test
    fun outOfBoundsOrReversedOwnedSpansAreRejected() {
        val instruction = "milk"
        for (span in listOf(
            GroceryIntentCoverageSpan(-1, 1),
            GroceryIntentCoverageSpan(0, instruction.length + 1),
            GroceryIntentCoverageSpan(3, 2),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                uncoveredGroceryIntentRangesBySpan(instruction, listOf(span))
            }
        }
    }
}
