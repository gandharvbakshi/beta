package com.example.beta

object CommerceActionClassifier {
    data class ExecutableActionContext(
        val actionType: String? = null,
        val actionTarget: String? = null,
        val textToType: String? = null,
        val text: String? = null,
        val contentDescription: String? = null,
        val selectorText: String? = null,
        val selectorContentDescription: String? = null,
        val resourceId: String? = null,
        val className: String? = null,
        val hierarchyPath: String? = null
    ) {
        internal fun parts(): Array<String?> = arrayOf(
            actionType,
            actionTarget,
            textToType,
            text,
            contentDescription,
            selectorText,
            selectorContentDescription,
            resourceId,
            className,
            hierarchyPath
        )
    }

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
    private val checkoutOrPaymentExecutionTargets = listOf(
        "checkout",
        "open cart",
        "view cart",
        "go to cart",
        "cart to verify",
        "go to checkout",
        "proceed to checkout",
        "continue to checkout",
        "order now",
        "place order",
        "place the order",
        "submit & pay",
        "submit and pay",
        "pay now",
        "pay using",
        "continue to payment",
        "proceed to payment",
        "proceed to pay",
        "complete payment",
        "complete this payment",
        "confirm payment",
        "finalize order",
        "make payment"
    )
    private val checkoutOrPaymentSurfaceTerms = listOf(
        "checkout",
        "payment",
        "pay using",
        "payment method",
        "order summary",
        "place order",
        "to pay",
        "submit & pay",
        "submit and pay"
    )
    private val genericCheckoutExecutionControls = listOf(
        "continue",
        "confirm",
        "submit",
        "proceed",
        "next"
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

    fun isCheckoutOrPaymentExecutionAction(context: ExecutableActionContext): Boolean =
        isCheckoutOrPaymentExecutionAction(*context.parts())

    fun isCheckoutOrPaymentExecutionAction(vararg contextParts: String?): Boolean {
        val normalizedContext = contextParts
            .filterNot { it.isNullOrBlank() }
            .joinToString(" ")
            .trim()
            .lowercase()
        if (normalizedContext.isBlank()) return false

        if (checkoutOrPaymentExecutionTargets.any { normalizedContext.contains(it) }) {
            return true
        }

        val hasCheckoutOrPaymentSurface = checkoutOrPaymentSurfaceTerms.any {
            normalizedContext.contains(it)
        }
        if (!hasCheckoutOrPaymentSurface) {
            return false
        }
        return genericCheckoutExecutionControls.any { control ->
            Regex("""\b${Regex.escape(control)}\b""").containsMatchIn(normalizedContext)
        }
    }
}
