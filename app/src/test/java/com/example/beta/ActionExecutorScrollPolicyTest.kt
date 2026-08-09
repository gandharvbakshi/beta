package com.example.beta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionExecutorScrollPolicyTest {
    @Test
    fun `disallows node bound gesture scroll for non scrollable targets`() {
        assertFalse(ActionExecutor.shouldUseNodeBoundGestureScroll(targetIsScrollable = false))
    }

    @Test
    fun `allows node bound gesture scroll for scrollable targets`() {
        assertTrue(ActionExecutor.shouldUseNodeBoundGestureScroll(targetIsScrollable = true))
    }

    @Test
    fun `up direction maps only to backward scroll intent`() {
        assertEquals(
            "BACKWARD",
            ActionExecutor.requestedScrollIntent("up").name
        )
        assertEquals(
            "BACKWARD",
            ActionExecutor.requestedScrollIntent("backward").name
        )
    }

    @Test
    fun `default direction maps to forward scroll intent`() {
        assertEquals(
            "FORWARD",
            ActionExecutor.requestedScrollIntent("").name
        )
        assertEquals(
            "FORWARD",
            ActionExecutor.requestedScrollIntent("down").name
        )
    }
}
