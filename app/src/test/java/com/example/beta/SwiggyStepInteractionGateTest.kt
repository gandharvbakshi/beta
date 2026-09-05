package com.example.beta

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SwiggyStepInteractionGateTest {
    @Test
    fun staleCallbackAfterNewPresentationDoesNothing() {
        val gate = SwiggyStepInteractionGate()
        val calls = AtomicInteger(0)
        val firstEpoch = gate.beginPresentation()
        val stale = gate.wrap(firstEpoch) { calls.incrementAndGet() }

        gate.beginPresentation()
        stale()

        assertEquals(0, calls.get())
    }

    @Test
    fun sameCallbackCanOnlyFireOncePerEpoch() {
        val gate = SwiggyStepInteractionGate()
        val calls = AtomicInteger(0)
        val epoch = gate.beginPresentation()
        val wrapped = gate.wrap(epoch) { calls.incrementAndGet() }

        wrapped()
        wrapped()

        assertEquals(1, calls.get())
    }

    @Test
    fun twoCallbacksFromSameEpochOnlyFirstConsumes() {
        val gate = SwiggyStepInteractionGate()
        val firstCalls = AtomicInteger(0)
        val secondCalls = AtomicInteger(0)
        val epoch = gate.beginPresentation()
        val first = gate.wrap(epoch) { firstCalls.incrementAndGet() }
        val second = gate.wrap(epoch) { secondCalls.incrementAndGet() }

        first()
        second()

        assertEquals(1, firstCalls.get())
        assertEquals(0, secondCalls.get())
    }

    @Test
    fun newEpochPermitsNextCallback() {
        val gate = SwiggyStepInteractionGate()
        val calls = AtomicInteger(0)

        gate.wrap(gate.beginPresentation()) { calls.incrementAndGet() }()
        gate.wrap(gate.beginPresentation()) { calls.incrementAndGet() }()

        assertEquals(2, calls.get())
    }

    @Test
    fun invalidatePreventsLateCallback() {
        val gate = SwiggyStepInteractionGate()
        val calls = AtomicInteger(0)
        val epoch = gate.beginPresentation()
        val wrapped = gate.wrap(epoch) { calls.incrementAndGet() }

        gate.invalidateCurrentPresentation()
        wrapped()

        assertEquals(0, calls.get())
    }

    @Test
    fun invalidatingOldEpochDoesNotDisableNewCallback() {
        val gate = SwiggyStepInteractionGate()
        val calls = AtomicInteger(0)
        val oldEpoch = gate.beginPresentation()
        val newEpoch = gate.beginPresentation()

        gate.invalidate(oldEpoch)
        gate.wrap(newEpoch) { calls.incrementAndGet() }()

        assertEquals(1, calls.get())
    }

    @Test
    fun cancelCompetesWithPrimaryAndOnlyFirstRuns() {
        val gate = SwiggyStepInteractionGate()
        val primaryCalls = AtomicInteger(0)
        val cancelCalls = AtomicInteger(0)
        val epoch = gate.beginPresentation()
        val primary = gate.wrap(epoch) { primaryCalls.incrementAndGet() }
        val cancel = gate.wrap(epoch) { cancelCalls.incrementAndGet() }

        cancel()
        primary()

        assertEquals(0, primaryCalls.get())
        assertEquals(1, cancelCalls.get())
    }
}
