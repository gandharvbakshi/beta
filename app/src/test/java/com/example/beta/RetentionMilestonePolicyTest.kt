package com.example.beta

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class RetentionMilestonePolicyTest {
    @Test
    fun logsTheHighestReachedMilestoneWithoutBackfillingOlderOnes() {
        val first = utc("2026-08-01T12:00:00Z")

        assertEquals("retention_d1", RetentionMilestonePolicy.dueEventName(first, utc("2026-08-02T12:00:00Z")))
        assertEquals("retention_d7", RetentionMilestonePolicy.dueEventName(first, utc("2026-08-08T12:00:00Z")))
        assertEquals("retention_d28", RetentionMilestonePolicy.dueEventName(first, utc("2026-08-30T12:00:00Z")))
    }

    @Test
    fun doesNotRepeatAlreadyLoggedMilestone() {
        val first = utc("2026-08-01T12:00:00Z")

        assertEquals(
            null,
            RetentionMilestonePolicy.dueEventName(
                first,
                utc("2026-08-02T12:00:00Z"),
                setOf("retention_d1"),
            ),
        )
    }

    @Test
    fun doesNotBackfillOlderMilestonesAfterAReachedMilestoneWasLogged() {
        val first = utc("2026-08-01T12:00:00Z")

        assertEquals(
            null,
            RetentionMilestonePolicy.dueEventName(
                first,
                utc("2026-08-08T12:00:00Z"),
                setOf("retention_d7"),
            ),
        )
    }

    @Test
    fun feedbackMilestoneDependsOnLocalActivationNotAnalyticsConsent() {
        val first = utc("2026-08-01T12:00:00Z")

        assertEquals(
            1,
            dueFeedbackMilestoneDay(
                activationCompleted = true,
                firstOpenAtMs = first,
                nowMs = utc("2026-08-02T12:00:00Z"),
            ),
        )
        assertEquals(
            null,
            dueFeedbackMilestoneDay(
                activationCompleted = false,
                firstOpenAtMs = first,
                nowMs = utc("2026-08-02T12:00:00Z"),
            ),
        )
    }

    private fun utc(value: String): Long = Instant.parse(value).toEpochMilli()
}
