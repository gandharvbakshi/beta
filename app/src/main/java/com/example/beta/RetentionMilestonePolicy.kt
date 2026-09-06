package com.example.beta

import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

object RetentionMilestonePolicy {
    const val RETENTION_EMISSION_VERSION = 2

    private const val WEEK_ONE_EVENT = "retention_w1"

    private val exactMilestones = linkedMapOf(
        1L to "retention_d1",
        5L to "retention_d5",
        7L to "retention_d7",
        28L to "retention_d28",
    )

    fun retentionFlagKey(eventName: String, emissionVersion: Int = RETENTION_EMISSION_VERSION): String {
        return "retention_v${emissionVersion}_$eventName"
    }

    fun dueEventNames(
        firstOpenAtMs: Long,
        nowMs: Long,
        alreadyLogged: Set<String> = emptySet(),
        emissionVersion: Int = RETENTION_EMISSION_VERSION,
    ): List<String> {
        if (nowMs < firstOpenAtMs) return emptyList()
        val ageDays = ageDays(firstOpenAtMs, nowMs)
        val due = mutableListOf<String>()
        exactMilestones[ageDays]?.let { eventName ->
            if (!isLogged(eventName, alreadyLogged, emissionVersion)) {
                due += eventName
            }
        }
        if (ageDays in 1..7L && !isLogged(WEEK_ONE_EVENT, alreadyLogged, emissionVersion)) {
            due += WEEK_ONE_EVENT
        }
        return due
    }

    fun dueEventName(
        firstOpenAtMs: Long,
        nowMs: Long,
        alreadyLogged: Set<String> = emptySet(),
    ): String? {
        if (nowMs < firstOpenAtMs) return null
        val ageDays = ChronoUnit.DAYS.between(utcDay(firstOpenAtMs), utcDay(nowMs))
        return exactMilestones[ageDays]
            ?.takeUnless { isLogged(it, alreadyLogged, RETENTION_EMISSION_VERSION) }
    }

    fun ageDays(firstOpenAtMs: Long, nowMs: Long): Long {
        if (nowMs < firstOpenAtMs) return 0L
        return ChronoUnit.DAYS.between(utcDay(firstOpenAtMs), utcDay(nowMs)).coerceAtLeast(0L)
    }

    private fun isLogged(
        eventName: String,
        alreadyLogged: Set<String>,
        emissionVersion: Int,
    ): Boolean {
        return retentionFlagKey(eventName, emissionVersion) in alreadyLogged
    }

    private fun utcDay(epochMs: Long) = Instant.ofEpochMilli(epochMs)
        .atZone(ZoneOffset.UTC)
        .toLocalDate()
}
