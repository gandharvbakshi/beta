package com.example.beta

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackReceiptTest {
    @Test fun acknowledgesOnlyAnExplicitPositiveReceiptWithANonblankStringId() {
        assertTrue(hasFeedbackReceipt("""{"accepted":true,"feedback_id":"synthetic-test-receipt"}"""))
        for (invalid in listOf(
            "", "OK", "[]", "{}", """{"accepted":true}""", """{"feedback_id":"x"}""",
            """{"accepted":false,"feedback_id":"x"}""", """{"accepted":"true","feedback_id":"x"}""",
            """{"accepted":true,"feedback_id":null}""", """{"accepted":true,"feedback_id":1}""",
            """{"accepted":true,"feedback_id":"  "}"""
        )) assertFalse(hasFeedbackReceipt(invalid))
    }
}
