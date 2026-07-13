package com.example.beta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderRunLifecycleTest {
    @Test
    fun staleCallbackAfterCancel_isRejected() {
        val lifecycle = OrderRunLifecycle<String>()
        val generation = lifecycle.start("milk")
        val token = lifecycle.beginRequest(generation)

        assertTrue(token != null)

        lifecycle.cancel(generation)

        assertFalse(lifecycle.completeRequest(generation, token!!))
        assertFalse(lifecycle.shouldAccept(generation, token))
    }

    @Test
    fun terminal_isAcceptedExactlyOnce() {
        val lifecycle = OrderRunLifecycle<String>()
        val generation = lifecycle.start("milk")

        val terminalSnapshot = lifecycle.terminal(generation, "terminal_key", "done")

        assertTrue(terminalSnapshot != null)
        assertEquals(generation + 1L, terminalSnapshot!!.generation)
        assertEquals(OrderRunLifecycle.Phase.TERMINAL, terminalSnapshot.phase)
        assertEquals("milk", terminalSnapshot.input)
        assertEquals("terminal_key", terminalSnapshot.terminalKey)
        assertEquals("done", terminalSnapshot.terminalStatus)

        assertNull(lifecycle.terminal(generation, "terminal_key_2", "later"))
    }

    @Test
    fun terminal_allowsNextItemToStartWithNewGeneration() {
        val lifecycle = OrderRunLifecycle<String>()
        val firstGeneration = lifecycle.start("milk")
        val terminal = lifecycle.terminal(firstGeneration, "milk:success", "success")

        val secondGeneration = lifecycle.start("butter")

        assertTrue(terminal != null)
        assertTrue(secondGeneration > terminal!!.generation)
        assertEquals("butter", lifecycle.snapshot().input)
        assertEquals(OrderRunLifecycle.Phase.RUNNING, lifecycle.snapshot().phase)
    }

    @Test
    fun pause_blocksRequests() {
        val lifecycle = OrderRunLifecycle<String>()
        val generation = lifecycle.start("milk")

        val paused = lifecycle.pause(generation)

        assertTrue(paused != null)
        assertEquals(OrderRunLifecycle.Phase.PAUSED, paused!!.phase)
        assertNull(lifecycle.beginRequest(generation))
        assertFalse(lifecycle.shouldAccept(generation))
    }

    @Test
    fun resume_advancesGenerationAndPreservesInput() {
        val lifecycle = OrderRunLifecycle<String>()
        val generation = lifecycle.start("snacks")

        val paused = lifecycle.pause(generation)
        val resumed = lifecycle.resume(generation)

        assertTrue(paused != null)
        assertEquals("snacks", paused!!.input)

        assertTrue(resumed != null)
        assertEquals(generation + 1L, resumed!!.generation)
        assertEquals(OrderRunLifecycle.Phase.RUNNING, resumed.phase)
        assertEquals("snacks", resumed.input)
    }

    @Test
    fun staleTokenBeforeResume_isRejected() {
        val lifecycle = OrderRunLifecycle<String>()
        val generation = lifecycle.start("milk")
        val token = lifecycle.beginRequest(generation)

        assertTrue(token != null)

        lifecycle.pause(generation)
        val resumed = lifecycle.resume(generation)

        assertTrue(resumed != null)
        assertEquals(generation + 1L, resumed!!.generation)
        assertFalse(lifecycle.completeRequest(generation, token!!))
        assertFalse(lifecycle.shouldAccept(generation, token))
    }

    @Test
    fun onlyOneInFlightRequestIsAllowed() {
        val lifecycle = OrderRunLifecycle<String>()
        val generation = lifecycle.start("milk")

        val firstToken = lifecycle.beginRequest(generation)
        assertTrue(firstToken != null)
        assertNull(lifecycle.beginRequest(generation))
        assertFalse(lifecycle.shouldAccept(generation))
        assertTrue(lifecycle.completeRequest(generation, firstToken!!))

        val secondToken = lifecycle.beginRequest(generation)
        assertTrue(secondToken != null)
        assertTrue(secondToken!! > firstToken)
    }
}
