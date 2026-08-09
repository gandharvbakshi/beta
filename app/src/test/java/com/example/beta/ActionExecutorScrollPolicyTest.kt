package com.example.beta

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
}
