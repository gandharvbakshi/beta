package com.example.beta

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class SwiggyOrderHandoffTest {
    @Before
    fun resetBeforeTest() {
        SwiggyOrderHandoff.resetForTests()
    }

    @After
    fun resetAfterTest() {
        SwiggyOrderHandoff.resetForTests()
    }

    @Test
    fun issuedInstructionCanBeConsumedOnlyOnce() {
        val token = SwiggyOrderHandoff.issue("  add milk  ")

        assertEquals("add milk", SwiggyOrderHandoff.consume(token))
        assertNull(SwiggyOrderHandoff.consume(token))
    }

    @Test
    fun unknownOrBlankTokenCannotInjectAnInstruction() {
        assertNull(SwiggyOrderHandoff.consume("untrusted-token"))
        assertNull(SwiggyOrderHandoff.consume(" "))
        assertNull(SwiggyOrderHandoff.consume(null))
    }

    @Test
    fun newerHandoffInvalidatesAnUnconsumedOlderInstruction() {
        val oldToken = SwiggyOrderHandoff.issue("add milk")
        val newToken = SwiggyOrderHandoff.issue("add bread")

        assertNull(SwiggyOrderHandoff.consume(oldToken))
        assertEquals("add bread", SwiggyOrderHandoff.consume(newToken))
    }
}
