package com.example.beta

internal object CaptureRecoveryPolicy {
    const val MAX_CAPTURE_SURFACE_RETRIES = 2
    const val MAX_EMPTY_TREE_RETRIES = 2

    fun shouldRetryCaptureSurface(currentAttempts: Int, captureActive: Boolean): Boolean {
        return captureActive && currentAttempts < MAX_CAPTURE_SURFACE_RETRIES
    }

    fun shouldRetryEmptyTree(currentAttempt: Int, treeData: String?): Boolean {
        return treeData.isNullOrBlank() && currentAttempt < MAX_EMPTY_TREE_RETRIES
    }
}
