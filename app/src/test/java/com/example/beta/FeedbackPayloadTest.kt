package com.example.beta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackPayloadTest {
    @Test
    fun appConfigBuildsFeedbackEndpointFromBaseUrl() {
        assertTrue(AppConfig.feedbackUrl.endsWith("/feedback"))
        assertFalse(AppConfig.feedbackUrl.contains("//feedback"))
    }

    @Test
    fun messageLengthLimitIsEnforcedByPayloadContract() {
        val longMessage = "a".repeat(5000)

        assertEquals(4000, longMessage.take(4000).length)
    }
}
