package com.example.beta

import android.content.Context
import java.util.concurrent.TimeUnit

internal object OrderInterruptionCheckpointStore {
    private const val PREFS = "order_interruption_checkpoint"
    private const val KEY_ACTIVE = "active"
    private const val KEY_STARTED_AT_MS = "started_at_ms"
    private val MAX_REPORT_AGE_MS = TimeUnit.HOURS.toMillis(24)

    fun markActive(context: Context, nowMs: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ACTIVE, true)
            .putLong(KEY_STARTED_AT_MS, nowMs)
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    fun consumeInterrupted(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val active = prefs.getBoolean(KEY_ACTIVE, false)
        val startedAtMs = prefs.getLong(KEY_STARTED_AT_MS, 0L)
        prefs.edit().clear().apply()
        return shouldReport(active, startedAtMs, nowMs)
    }

    internal fun shouldReport(active: Boolean, startedAtMs: Long, nowMs: Long): Boolean {
        if (!active || startedAtMs <= 0L || nowMs < startedAtMs) return false
        return nowMs - startedAtMs <= MAX_REPORT_AGE_MS
    }
}
