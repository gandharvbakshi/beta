package com.example.beta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentCoverageTest {
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
}
