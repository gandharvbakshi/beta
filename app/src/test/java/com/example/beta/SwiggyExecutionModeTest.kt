package com.example.beta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

class SwiggyExecutionModeTest {
    @Before
    fun resetModeBeforeTest() {
        SwiggyExecutionMode.resetSession()
    }

    @After
    fun resetModeAfterTest() {
        SwiggyExecutionMode.resetSession()
    }

    @Test
    fun defaultsToMcp() {
        assertEquals(SwiggyExecutionMode.Mode.MCP, SwiggyExecutionMode.current())
        assertTrue(SwiggyExecutionMode.usesMcpExperience())
    }

    @Test
    fun canSwitchToScreenAssistedAndBackWithinTheSession() {
        SwiggyExecutionMode.useScreenAssisted()

        assertEquals(SwiggyExecutionMode.Mode.SCREEN_ASSISTED, SwiggyExecutionMode.current())
        assertFalse(SwiggyExecutionMode.usesMcpExperience())

        SwiggyExecutionMode.useMcp()

        assertEquals(SwiggyExecutionMode.Mode.MCP, SwiggyExecutionMode.current())
        assertTrue(SwiggyExecutionMode.usesMcpExperience())
    }
}
