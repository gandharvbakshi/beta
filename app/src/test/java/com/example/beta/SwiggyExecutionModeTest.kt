package com.example.beta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SwiggyExecutionModeTest {
    @Test
    fun screenAssistedIsTheTemporaryDefault() {
        assertEquals(SwiggyExecutionMode.Mode.SCREEN_ASSISTED, SwiggyExecutionMode.current())
        assertFalse(SwiggyExecutionMode.usesMcpExperience())
    }
}
