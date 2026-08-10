package com.example.beta

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureRecoveryPolicyTest {
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
