package com.example.beta

internal class AddAttemptGuard {
    private var attemptConsumed = false
    private var stockRecoveryAttemptConsumed = false

    @Synchronized
    fun reserve(allowStockRecovery: Boolean = false): Boolean {
        if (!attemptConsumed) {
            attemptConsumed = true
            stockRecoveryAttemptConsumed = allowStockRecovery
            return true
        }
        if (!allowStockRecovery || stockRecoveryAttemptConsumed) return false
        stockRecoveryAttemptConsumed = true
        return true
    }

    @Synchronized
    fun isConsumed(): Boolean = attemptConsumed

    @Synchronized
    fun reset() {
        attemptConsumed = false
        stockRecoveryAttemptConsumed = false
    }
}
