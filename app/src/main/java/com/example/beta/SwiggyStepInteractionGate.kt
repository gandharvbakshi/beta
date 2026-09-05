package com.example.beta

internal class SwiggyStepInteractionGate {
    private val lock = Any()
    private var currentEpoch = 0L
    private var consumedEpoch = 0L

    fun beginPresentation(): Long = synchronized(lock) {
        currentEpoch += 1
        consumedEpoch = 0L
        currentEpoch
    }

    fun invalidate(epoch: Long? = null) {
        synchronized(lock) {
            val targetEpoch = epoch ?: currentEpoch
            if (targetEpoch == currentEpoch) {
                consumedEpoch = targetEpoch
            }
        }
    }

    fun invalidateCurrentPresentation() = invalidate()

    fun wrap(epoch: Long, callback: () -> Unit): () -> Unit = {
        if (consumeOnce(epoch)) callback()
    }

    private fun consumeOnce(epoch: Long): Boolean = synchronized(lock) {
        if (epoch != currentEpoch || consumedEpoch == epoch) return false
        consumedEpoch = epoch
        true
    }
}
