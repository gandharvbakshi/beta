package com.example.beta

internal object CaptureRecoveryPolicy {
    const val UNSEQUENCED_GENERATION = -1L
    const val MAX_CAPTURE_SURFACE_RETRIES = 2
    const val MAX_EMPTY_TREE_RETRIES = 2

    fun isSequencedRequestGeneration(sequenceGeneration: Long): Boolean {
        return sequenceGeneration != UNSEQUENCED_GENERATION
    }

    fun shouldProcessCaptureRequest(
        terminalStatusActive: Boolean,
        scheduledRequestGeneration: Long,
        currentRequestGeneration: Long,
        sequenceGeneration: Long,
        sequenceStillCurrent: Boolean
    ): Boolean {
        if (terminalStatusActive || scheduledRequestGeneration != currentRequestGeneration) {
            return false
        }
        return !isSequencedRequestGeneration(sequenceGeneration) || sequenceStillCurrent
    }

    fun shouldRetryCaptureSurface(currentAttempts: Int, captureActive: Boolean): Boolean {
        return captureActive && currentAttempts < MAX_CAPTURE_SURFACE_RETRIES
    }

    fun shouldRetryEmptyTree(currentAttempt: Int, treeData: String?): Boolean {
        return treeData.isNullOrBlank() && currentAttempt < MAX_EMPTY_TREE_RETRIES
    }
}
