package com.example.beta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureCompletionPolicyTest {
    @Test
    fun defaultCompletionTimeoutIsBoundedAt2500Ms() {
        assertEquals(2500L, GestureCompletionPolicy.DEFAULT_COMPLETION_TIMEOUT_MS)
    }

    @Test
    fun defaultCompletionTimeoutCoversObservedDelayedCallbackWithoutBeingTight() {
        val observedDelayMs = 323L

        assertTrue(GestureCompletionPolicy.DEFAULT_COMPLETION_TIMEOUT_MS >= observedDelayMs)
        assertTrue(GestureCompletionPolicy.DEFAULT_COMPLETION_TIMEOUT_MS <= 2500L)
    }
}
