package com.example.beta

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One-shot, in-process handoff for internal Swiggy order launches. */
internal object SwiggyOrderHandoff {
    const val EXTRA_TOKEN = "com.example.beta.extra.SWIGGY_ORDER_HANDOFF_TOKEN"

    private val pendingInstructions = ConcurrentHashMap<String, String>()

    @Synchronized
    fun issue(instruction: String): String {
        val normalized = instruction.trim()
        require(normalized.isNotBlank()) { "Swiggy handoff instruction must not be blank" }
        val token = UUID.randomUUID().toString()
        pendingInstructions.clear()
        pendingInstructions[token] = normalized
        return token
    }

    @Synchronized
    fun consume(token: String?): String? {
        val normalizedToken = token?.trim().orEmpty()
        if (normalizedToken.isBlank()) return null
        return pendingInstructions.remove(normalizedToken)
    }

    @Synchronized
    internal fun resetForTests() {
        pendingInstructions.clear()
    }
}
