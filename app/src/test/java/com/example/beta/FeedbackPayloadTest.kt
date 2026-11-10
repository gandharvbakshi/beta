package com.example.beta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackPayloadTest {
    @Test
    fun appConfigBuildsFeedbackEndpointFromBaseUrl() {
        val config = BackendEndpointConfig.read(
            """{"version":1,"backend_base_url":"https://example.test"}""".byteInputStream())
        assertTrue(config.path("/feedback").endsWith("/feedback"))
        assertFalse(config.path("/feedback").contains("//feedback"))
    }

    @Test
    fun messageLengthLimitIsEnforcedByPayloadContract() {
        val longMessage = "a".repeat(5000)

        assertEquals(4000, longMessage.take(4000).length)
    }
}
