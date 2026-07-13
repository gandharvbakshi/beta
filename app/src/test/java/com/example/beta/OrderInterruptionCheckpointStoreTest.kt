package com.example.beta

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class OrderInterruptionCheckpointStoreTest {
    @Test
    fun shouldReport_acceptsRecentActiveRun() {
        val startedAt = 1_000L
        assertTrue(OrderInterruptionCheckpointStore.shouldReport(true, startedAt, startedAt + 5_000L))
    }

    @Test
    fun shouldReport_rejectsInactiveFutureAndExpiredRuns() {
        val startedAt = 1_000L
        assertFalse(OrderInterruptionCheckpointStore.shouldReport(false, startedAt, startedAt + 1L))
        assertFalse(OrderInterruptionCheckpointStore.shouldReport(true, startedAt, startedAt - 1L))
        assertFalse(
            OrderInterruptionCheckpointStore.shouldReport(
                true,
                startedAt,
                startedAt + TimeUnit.HOURS.toMillis(25)
            )
        )
    }
}
