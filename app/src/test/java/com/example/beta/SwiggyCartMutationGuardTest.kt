package com.example.beta

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SwiggyCartMutationGuardTest {
    @Before
    fun resetBeforeTest() {
        SwiggyCartMutationGuard.resetForTests()
    }

    @After
    fun resetAfterTest() {
        SwiggyCartMutationGuard.resetForTests()
    }

    @Test
    fun recreatedOwnerImmediatelyObservesActiveMutationAndOldOwnerCannotUnregisterIt() {
        val oldOwner = Any()
        val newOwner = Any()
        val oldStates = mutableListOf<Boolean>()
        val newStates = mutableListOf<Boolean>()

        SwiggyCartMutationGuard.register(oldOwner, oldStates::add)
        SwiggyCartMutationGuard.begin()
        SwiggyCartMutationGuard.register(newOwner, newStates::add)
        SwiggyCartMutationGuard.unregister(oldOwner)
        SwiggyCartMutationGuard.end("Review your cart")

        assertEquals(listOf(false, true), oldStates)
        assertEquals(listOf(true, false), newStates)
        assertFalse(SwiggyCartMutationGuard.isInFlight())
        assertEquals("Review your cart", SwiggyCartMutationGuard.consumeTerminalNotice())
        assertNull(SwiggyCartMutationGuard.consumeTerminalNotice())
    }

    @Test
    fun guardStaysActiveUntilExplicitTerminalRelease() {
        SwiggyCartMutationGuard.begin()

        assertTrue(SwiggyCartMutationGuard.isInFlight())

        SwiggyCartMutationGuard.end()
        assertFalse(SwiggyCartMutationGuard.isInFlight())
    }
}
