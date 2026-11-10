package com.example.beta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentConstraintCodecTest {
    @Test fun strictCodecRoundTripsEscapedQuotesAndEmptyTombstone() {
        val quotes = listOf("Don't replace \"Amul\" milk.", "Use path C:\\delivery\\note")
        val encoded = GroceryIntentConstraintCodec.encode(quotes)!!

        assertEquals(quotes, GroceryIntentConstraintCodec.decode(encoded))
        assertEquals(emptyList<String>(), GroceryIntentConstraintCodec.decode(
            GroceryIntentConstraintCodec.encode(emptyList())!!,
        ))
    }

    @Test fun strictDecoderRejectsDuplicateKeysTrailingContentAndUnknownFields() {
        listOf(
            "{\"version\":1,\"version\":1,\"quotes\":[]}",
            "{\"version\":1,\"quotes\":[]} trailing",
            "{\"version\":1,\"quotes\":[],\"extra\":true}",
            "{\"version\":\"1\",\"quotes\":[]}",
            "{\"version\":1.0,\"quotes\":[]}",
            "{\"version\":1,\"quotes\":[4]}",
            "{\"version\":1,\"quotes\":[\"\"]}",
            "{\"version\":1,\"quotes\":[\"duplicate\",\"duplicate\"]}",
        ).forEach { assertNull(it, GroceryIntentConstraintCodec.decode(it)) }
    }

    @Test fun policyRejectsInvalidAndOverflowValuesWithoutTruncation() {
        assertFalse(GroceryIntentConstraintCodec.isValidQuote(" "))
        assertFalse(GroceryIntentConstraintCodec.isValidQuote(" padded "))
        assertFalse(GroceryIntentConstraintCodec.isValidQuote("line one\nline two"))
        assertFalse(GroceryIntentConstraintCodec.isValidQuote("x".repeat(181)))
        assertNull(GroceryIntentConstraintCodec.encode(listOf("x".repeat(181))))
        assertNull(GroceryIntentConstraintCodec.encode((1..21).map { "constraint $it" }))
        assertTrue(GroceryIntentConstraintCodec.encode((1..20).map { "constraint $it" }) != null)
    }
}
