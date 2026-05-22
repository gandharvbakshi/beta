package com.example.beta

object CommerceActionClassifier {
    private val nonProductAddTargets = listOf(
        "add more items",
        "add address",
        "add new address",
        "add gstin",
        "add coupon",
        "apply coupon",
        "add payment",
        "add card",
        "add money"
    )

    fun isProductAddButtonAction(actionTarget: String): Boolean {
        val normalized = actionTarget.trim().lowercase()
        if (normalized.isBlank()) return false
        if (containsNonProductAddTarget(normalized)) return false

        return normalized.contains("add button") ||
            normalized.contains("add to cart") ||
            normalized.startsWith("add one ") ||
            Regex("""\badd\b""").containsMatchIn(normalized)
    }

    fun isProductAddButtonAction(
        actionTarget: String,
        elementText: String?,
        contentDescription: String?
    ): Boolean {
        val context = listOf(actionTarget, elementText.orEmpty(), contentDescription.orEmpty())
            .joinToString(" ")
            .trim()
            .lowercase()
        if (context.isBlank()) return false
        if (containsNonProductAddTarget(context)) return false

        val normalizedText = elementText.orEmpty().trim().lowercase()
        val normalizedContentDescription = contentDescription.orEmpty().trim().lowercase()

        return isProductAddButtonAction(actionTarget) ||
            normalizedText == "add" ||
            normalizedText == "+ add" ||
            normalizedContentDescription.contains("add to cart")
    }

    private fun containsNonProductAddTarget(normalizedContext: String): Boolean =
        nonProductAddTargets.any { normalizedContext.contains(it) }
}
