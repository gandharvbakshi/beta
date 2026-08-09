package com.example.beta

import java.util.concurrent.TimeUnit

internal object MultiItemTimeoutPolicy {
    private const val ITEM_DEADLINE_MS = 120_000L
    private const val TRANSITION_BUFFER_MS = 15_000L
    private val MIN_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(10)
    private val MAX_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(60)

    internal fun computeTimeoutMs(itemCount: Int): Long {
        val safeItemCount = itemCount.coerceAtLeast(0).toLong()
        val computedTimeoutMs = safeItemCount * (ITEM_DEADLINE_MS + TRANSITION_BUFFER_MS)
        return computedTimeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
    }
}
