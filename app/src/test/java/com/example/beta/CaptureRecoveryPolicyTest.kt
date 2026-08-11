package com.example.beta

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureRecoveryPolicyTest {
    @Test
    fun `positive and zero generations retain sequenced provenance`() {
        assertFalse(
            CaptureRecoveryPolicy.isSequencedRequestGeneration(
                CaptureRecoveryPolicy.UNSEQUENCED_GENERATION
            )
        )
        assertTrue(CaptureRecoveryPolicy.isSequencedRequestGeneration(0L))
        assertTrue(CaptureRecoveryPolicy.isSequencedRequestGeneration(7L))
    }

    @Test
    fun `capture request is rejected after terminal generation invalidation`() {
        assertTrue(
            CaptureRecoveryPolicy.shouldProcessCaptureRequest(
                terminalStatusActive = false,
                scheduledRequestGeneration = 4L,
                currentRequestGeneration = 4L,
                sequenceGeneration = 7L,
                sequenceStillCurrent = true
            )
        )
        assertFalse(
            CaptureRecoveryPolicy.shouldProcessCaptureRequest(
                terminalStatusActive = true,
                scheduledRequestGeneration = 4L,
                currentRequestGeneration = 4L,
                sequenceGeneration = 7L,
                sequenceStillCurrent = true
            )
        )
        assertFalse(
            CaptureRecoveryPolicy.shouldProcessCaptureRequest(
                terminalStatusActive = false,
                scheduledRequestGeneration = 4L,
                currentRequestGeneration = 5L,
                sequenceGeneration = 7L,
                sequenceStillCurrent = true
            )
        )
        assertFalse(
            CaptureRecoveryPolicy.shouldProcessCaptureRequest(
                terminalStatusActive = false,
                scheduledRequestGeneration = 4L,
                currentRequestGeneration = 4L,
                sequenceGeneration = 7L,
                sequenceStillCurrent = false
            )
        )
    }

    @Test
    fun `unsequenced capture still requires a live request generation`() {
        assertTrue(
            CaptureRecoveryPolicy.shouldProcessCaptureRequest(
                terminalStatusActive = false,
                scheduledRequestGeneration = 9L,
                currentRequestGeneration = 9L,
                sequenceGeneration = CaptureRecoveryPolicy.UNSEQUENCED_GENERATION,
                sequenceStillCurrent = false
            )
        )
        assertFalse(
            CaptureRecoveryPolicy.shouldProcessCaptureRequest(
                terminalStatusActive = false,
                scheduledRequestGeneration = 9L,
                currentRequestGeneration = 10L,
                sequenceGeneration = CaptureRecoveryPolicy.UNSEQUENCED_GENERATION,
                sequenceStillCurrent = false
            )
        )
    }

    @Test
    fun `capture retry is bounded and requires active projection`() {
        assertTrue(CaptureRecoveryPolicy.shouldRetryCaptureSurface(0, captureActive = true))
        assertTrue(CaptureRecoveryPolicy.shouldRetryCaptureSurface(1, captureActive = true))
        assertFalse(CaptureRecoveryPolicy.shouldRetryCaptureSurface(2, captureActive = true))
        assertFalse(CaptureRecoveryPolicy.shouldRetryCaptureSurface(0, captureActive = false))
    }

    @Test
    fun `empty tree retry is bounded`() {
        assertTrue(CaptureRecoveryPolicy.shouldRetryEmptyTree(0, ""))
        assertTrue(CaptureRecoveryPolicy.shouldRetryEmptyTree(1, null))
        assertFalse(CaptureRecoveryPolicy.shouldRetryEmptyTree(2, ""))
        assertFalse(CaptureRecoveryPolicy.shouldRetryEmptyTree(0, "<hierarchy />"))
    }
}
