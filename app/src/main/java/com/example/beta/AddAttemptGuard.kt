package com.example.beta

internal class AddAttemptGuard {
    private var attemptConsumed = false

    @Synchronized
    fun reserve(): Boolean {
        if (attemptConsumed) return false
        attemptConsumed = true
        return true
    }

    @Synchronized
    fun isConsumed(): Boolean = attemptConsumed

    @Synchronized
    fun reset() {
        attemptConsumed = false
    }
}
