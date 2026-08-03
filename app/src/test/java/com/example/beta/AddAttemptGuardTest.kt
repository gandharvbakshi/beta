package com.example.beta

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddAttemptGuardTest {
    @Test
    fun reserve_allowsOnlyOneAttemptUntilNewItemReset() {
        val guard = AddAttemptGuard()

        assertTrue(guard.reserve())
        assertTrue(guard.isConsumed())
        assertFalse(guard.reserve())
    }

    @Test
    fun reset_allowsTheNextItemAttempt() {
        val guard = AddAttemptGuard()

        assertTrue(guard.reserve())
        assertFalse(guard.reserve())

        guard.reset()

        assertTrue(guard.reserve())
        assertTrue(guard.isConsumed())
    }

    @Test
    fun stockRecovery_allowsExactlyOneMarkedAlternativeAttempt() {
        val guard = AddAttemptGuard()

        assertTrue(guard.reserve())
        assertTrue(guard.reserve(allowStockRecovery = true))
        assertFalse(guard.reserve(allowStockRecovery = true))
        assertFalse(guard.reserve())
    }

    @Test
    fun markingInitialAttempt_doesNotCreateAnExtraRecoveryAllowance() {
        val guard = AddAttemptGuard()

        assertTrue(guard.reserve(allowStockRecovery = true))
        assertFalse(guard.reserve(allowStockRecovery = true))
    }
}
