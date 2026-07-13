package com.example.beta

/**
 * Small synchronized lifecycle for one active item run.
 */
class OrderRunLifecycle<T> {
    enum class Phase {
        IDLE,
        RUNNING,
        PAUSED,
        TERMINAL,
    }

    data class Snapshot<T>(
        val generation: Long,
        val phase: Phase,
        val input: T?,
        val inFlightRequestToken: Long?,
        val terminalKey: String?,
        val terminalStatus: String?,
    )

    private data class State<T>(
        val generation: Long,
        val phase: Phase,
        val input: T?,
        val inFlightRequestToken: Long?,
        val terminalKey: String?,
        val terminalStatus: String?,
    )

    private var state = State<T>(
        generation = 0L,
        phase = Phase.IDLE,
        input = null,
        inFlightRequestToken = null,
        terminalKey = null,
        terminalStatus = null,
    )
    private var nextRequestToken = 1L

    @Synchronized
    fun start(input: T): Long {
        check(state.phase == Phase.IDLE || state.phase == Phase.TERMINAL) {
            "start() requires IDLE or TERMINAL state"
        }

        state = State(
            generation = state.generation + 1L,
            phase = Phase.RUNNING,
            input = input,
            inFlightRequestToken = null,
            terminalKey = null,
            terminalStatus = null,
        )
        return state.generation
    }

    @Synchronized
    fun pause(generation: Long): Snapshot<T>? {
        val current = state
        if (current.phase != Phase.RUNNING || current.generation != generation) {
            return null
        }

        state = current.copy(
            phase = Phase.PAUSED,
            inFlightRequestToken = null,
            terminalKey = null,
            terminalStatus = null,
        )
        return state.snapshot()
    }

    @Synchronized
    fun resume(generation: Long): Snapshot<T>? {
        val current = state
        if (current.phase != Phase.PAUSED || current.generation != generation) {
            return null
        }

        state = current.copy(
            generation = current.generation + 1L,
            phase = Phase.RUNNING,
            inFlightRequestToken = null,
            terminalKey = null,
            terminalStatus = null,
        )
        return state.snapshot()
    }

    @Synchronized
    fun cancel(generation: Long): Snapshot<T>? {
        val current = state
        if (current.phase != Phase.RUNNING && current.phase != Phase.PAUSED) {
            return null
        }
        if (current.generation != generation) {
            return null
        }

        state = current.copy(
            generation = current.generation + 1L,
            phase = Phase.IDLE,
            inFlightRequestToken = null,
            terminalKey = null,
            terminalStatus = null,
        )
        return state.snapshot()
    }

    @Synchronized
    fun terminal(generation: Long, terminalKey: String, terminalStatus: String): Snapshot<T>? {
        val current = state
        if (current.phase != Phase.RUNNING && current.phase != Phase.PAUSED) {
            return null
        }
        if (current.generation != generation) {
            return null
        }

        state = current.copy(
            generation = current.generation + 1L,
            phase = Phase.TERMINAL,
            inFlightRequestToken = null,
            terminalKey = terminalKey,
            terminalStatus = terminalStatus,
        )
        return state.snapshot()
    }

    @Synchronized
    fun beginRequest(generation: Long): Long? {
        val current = state
        if (current.phase != Phase.RUNNING || current.generation != generation) {
            return null
        }
        if (current.inFlightRequestToken != null) {
            return null
        }

        val token = nextRequestToken
        nextRequestToken += 1L
        state = current.copy(inFlightRequestToken = token)
        return token
    }

    @Synchronized
    fun completeRequest(generation: Long, token: Long): Boolean {
        val current = state
        if (current.phase != Phase.RUNNING || current.generation != generation) {
            return false
        }
        if (current.inFlightRequestToken != token) {
            return false
        }

        state = current.copy(inFlightRequestToken = null)
        return true
    }

    @Synchronized
    fun shouldAccept(generation: Long, token: Long? = null): Boolean {
        val current = state
        if (current.phase != Phase.RUNNING || current.generation != generation) {
            return false
        }

        return if (token == null) {
            current.inFlightRequestToken == null
        } else {
            current.inFlightRequestToken == token
        }
    }

    @Synchronized
    fun snapshot(): Snapshot<T> = state.snapshot()

    private fun State<T>.snapshot(): Snapshot<T> {
        return Snapshot(
            generation = generation,
            phase = phase,
            input = input,
            inFlightRequestToken = inFlightRequestToken,
            terminalKey = terminalKey,
            terminalStatus = terminalStatus,
        )
    }
}
