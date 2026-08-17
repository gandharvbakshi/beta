package com.example.beta

import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

object RetentionMilestonePolicy {
    private val milestones = linkedMapOf(
        1L to "retention_d1",
        5L to "retention_d5",
        7L to "retention_d7",
        28L to "retention_d28",
    )

    fun dueEventName(
        firstOpenAtMs: Long,
        nowMs: Long,
        alreadyLogged: Set<String> = emptySet(),
    ): String? {
        if (nowMs < firstOpenAtMs) return null
        val ageDays = ChronoUnit.DAYS.between(utcDay(firstOpenAtMs), utcDay(nowMs))
        return milestones.entries
            .filter { (day, _) -> day <= ageDays }
            .maxByOrNull(Map.Entry<Long, String>::key)
            ?.value
            ?.takeUnless(alreadyLogged::contains)
    }

    fun ageDays(firstOpenAtMs: Long, nowMs: Long): Long {
        if (nowMs < firstOpenAtMs) return 0L
        return ChronoUnit.DAYS.between(utcDay(firstOpenAtMs), utcDay(nowMs)).coerceAtLeast(0L)
    }

    private fun utcDay(epochMs: Long) = Instant.ofEpochMilli(epochMs)
        .atZone(ZoneOffset.UTC)
        .toLocalDate()
}
