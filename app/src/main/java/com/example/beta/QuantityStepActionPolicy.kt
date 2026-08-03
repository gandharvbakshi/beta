package com.example.beta

internal enum class QuantityStepDirection {
    INCREMENT,
    DECREMENT
}

internal object QuantityStepActionPolicy {
    fun detectDirection(
        selectorText: String,
        actionTarget: String,
        reasoning: String,
        contentDescription: String,
        resourceId: String
    ): QuantityStepDirection? {
        when (selectorText.trim()) {
            "+" -> return QuantityStepDirection.INCREMENT
            "-" -> return QuantityStepDirection.DECREMENT
        }

        val combined = listOf(actionTarget, reasoning, contentDescription, resourceId)
            .joinToString(" ")
            .lowercase()

        if (
            combined.contains("increase quantity") ||
            combined.contains("increment") ||
            combined.contains("tap plus") ||
            combined.contains(" plus ") ||
            combined.contains("reach requested quantity")
        ) {
            return QuantityStepDirection.INCREMENT
        }

        if (
            combined.contains("decrease quantity") ||
            combined.contains("decrement") ||
            combined.contains("tap minus") ||
            combined.contains(" minus ") ||
            combined.contains("remove one")
        ) {
            return QuantityStepDirection.DECREMENT
        }

        return null
    }

    fun requiresCoordinateOnlyExecution(
        direction: QuantityStepDirection?,
        isSwiggyForeground: Boolean
    ): Boolean = direction != null && !isSwiggyForeground
}
