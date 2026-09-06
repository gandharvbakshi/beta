package com.example.beta

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class RetentionMilestonePolicyTest {
    @Test
    fun dueEventName_returns_only_the_exact_milestone_for_the_matching_day() {
        val first = utc("2026-08-01T12:00:00Z")

        assertEquals("retention_d1", RetentionMilestonePolicy.dueEventName(first, utc("2026-08-02T12:00:00Z")))
        assertEquals(null, RetentionMilestonePolicy.dueEventName(first, utc("2026-08-03T12:00:00Z")))
        assertEquals("retention_d7", RetentionMilestonePolicy.dueEventName(first, utc("2026-08-08T12:00:00Z")))
        assertEquals("retention_d28", RetentionMilestonePolicy.dueEventName(first, utc("2026-08-29T12:00:00Z")))
    }

    @Test
    fun dueEventName_ignores_legacy_unversioned_flags() {
        val first = utc("2026-08-01T12:00:00Z")

        assertEquals(
            "retention_d1",
            RetentionMilestonePolicy.dueEventName(
                first,
                utc("2026-08-02T12:00:00Z"),
                setOf("retention_d1"),
            ),
        )
    }

    @Test
    fun dueEventName_ignores_legacy_unversioned_flags_for_later_days() {
        val first = utc("2026-08-01T12:00:00Z")

        assertEquals(
            "retention_d7",
            RetentionMilestonePolicy.dueEventName(
                first,
                utc("2026-08-08T12:00:00Z"),
                setOf("retention_d7"),
            ),
        )
    }

    @Test
    fun dueEventNames_emits_week_one_umbrella_alongside_exact_milestones() {
        val first = utc("2026-08-01T12:00:00Z")

        assertEquals(
            listOf("retention_d1", "retention_w1"),
            RetentionMilestonePolicy.dueEventNames(first, utc("2026-08-02T12:00:00Z")),
        )
        assertEquals(
            listOf("retention_w1"),
            RetentionMilestonePolicy.dueEventNames(first, utc("2026-08-03T12:00:00Z")),
        )
        assertEquals(
            listOf("retention_d5", "retention_w1"),
            RetentionMilestonePolicy.dueEventNames(first, utc("2026-08-06T12:00:00Z")),
        )
        assertEquals(
            listOf("retention_d7", "retention_w1"),
            RetentionMilestonePolicy.dueEventNames(first, utc("2026-08-08T12:00:00Z")),
        )
        assertEquals(
            emptyList<String>(),
            RetentionMilestonePolicy.dueEventNames(first, utc("2026-08-09T12:00:00Z")),
        )
        assertEquals(
            listOf("retention_d28"),
            RetentionMilestonePolicy.dueEventNames(first, utc("2026-08-29T12:00:00Z")),
        )
    }

    @Test
    fun dueEventNames_ignores_v1_flags_but_honors_v2_flags() {
        val first = utc("2026-08-01T12:00:00Z")

        assertEquals(
            listOf("retention_d1", "retention_w1"),
            RetentionMilestonePolicy.dueEventNames(
                first,
                utc("2026-08-02T12:00:00Z"),
                alreadyLogged = setOf(
                    RetentionMilestonePolicy.retentionFlagKey("retention_d1", emissionVersion = 1),
                    RetentionMilestonePolicy.retentionFlagKey("retention_w1", emissionVersion = 1),
                ),
            ),
        )
        assertEquals(
            emptyList<String>(),
            RetentionMilestonePolicy.dueEventNames(
                first,
                utc("2026-08-02T12:00:00Z"),
                alreadyLogged = setOf(
                    RetentionMilestonePolicy.retentionFlagKey("retention_d1"),
                    RetentionMilestonePolicy.retentionFlagKey("retention_w1"),
                ),
            ),
        )
        assertEquals(
            listOf("retention_w1"),
            RetentionMilestonePolicy.dueEventNames(
                first,
                utc("2026-08-02T12:00:00Z"),
                alreadyLogged = setOf(RetentionMilestonePolicy.retentionFlagKey("retention_d1")),
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
