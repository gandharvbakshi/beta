package com.example.beta

import com.example.beta.automation.ParsedItem
import com.example.beta.automation.Quantity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentReviewUiTest {
    @Test
    fun defaultQuantityNoteRetainsUnstatedRowBesideConsolidatedExplicitCount(): Unit {
        val rows = listOf(
            ParsedItem(rawText = "two rice bags", query = "rice", quantity = Quantity.Count(2)),
            ParsedItem(rawText = "salt", query = "salt", quantity = Quantity.Default),
        )

        val note = swiggyDefaultQuantityReviewNote(rows)

        assertTrue(note.contains("salt"))
        assertTrue(note.contains("1 pack for each request without a quantity"))
        assertTrue(note.contains("combined total above"))
        assertTrue(!note.contains("two rice bags"))
    }

    @Test
    fun explicitCountOneDoesNotReceiveDefaultQuantitySuggestion(): Unit {
        val note = swiggyDefaultQuantityReviewNote(
            listOf(ParsedItem(rawText = "one salt", query = "salt", quantity = Quantity.Count(1))),
        )

        assertEquals("", note)
    }
}
