package com.example.beta

object SearchSubmissionPolicy {
    fun keepSearchInputOpen(
        isSearchInputAction: Boolean,
        isBlinkitForeground: Boolean
    ): Boolean {
        if (!isSearchInputAction) return false
        return !isBlinkitForeground
    }
}
