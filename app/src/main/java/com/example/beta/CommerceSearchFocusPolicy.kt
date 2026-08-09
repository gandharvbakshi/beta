package com.example.beta

internal object CommerceSearchFocusPolicy {
    fun isAccessibilityCandidate(
        resourceId: String,
        text: String,
        description: String,
        visible: Boolean,
        enabled: Boolean
    ): Boolean {
        if (!visible || !enabled) return false

        val semantics = "$text $description".trim()
        if (
            semantics.contains("voice search", ignoreCase = true) ||
            resourceId.contains("voice_search", ignoreCase = true)
        ) return false

        return resourceId.contains("search", ignoreCase = true) ||
            semantics.equals("search", ignoreCase = true) ||
            semantics.startsWith("search \"", ignoreCase = true) ||
            semantics.startsWith("search “", ignoreCase = true) ||
            semantics.startsWith("search for", ignoreCase = true) ||
            semantics.startsWith("search across", ignoreCase = true)
    }
}
