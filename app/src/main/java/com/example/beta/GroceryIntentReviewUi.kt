package com.example.beta

internal const val FULFILLMENT_LIMITATION =
    "Beta prepares a Swiggy Instamart cart. It cannot schedule a delivery time or shop at another store. Availability is checked for the items shown."

internal const val UNREADABLE_FULFILLMENT_WARNING =
    "Beta cannot read a delivery request saved earlier. It may have asked for a time or another shop. Beta cannot arrange either. Nothing has been added or ordered."

/** Explicit recovery consent, never a cart or order confirmation. */
internal fun groceryIntentUnreadableConstraintRecoveryScreen(
    discard: () -> Unit,
    back: () -> Unit,
): SwiggyStepScreen {
    var acknowledged = false
    return SwiggyStepScreen(
        eyebrow = "Saved request needs attention",
        title = "Review an unreadable request",
        message = UNREADABLE_FULFILLMENT_WARNING,
        caption = "Forget only this unreadable request, then review your list and delivery details again. Your cart will not change.",
        acknowledgement = SwiggyStepAcknowledgement(
            "I understand this saved request will be forgotten. I will review my list and delivery details again.",
            { acknowledged = it },
        ),
        primary = SwiggyStepAction("Forget unreadable request") { if (acknowledged) discard() },
        secondary = SwiggyStepAction("Go back") { back() },
        cancel = back,
    )
}

internal fun groceryIntentFulfillmentReviewRows(quotes: List<String>): List<SwiggyStepRow> =
    if (quotes.isEmpty()) emptyList() else listOf(
        SwiggyStepRow(
            title = "Your delivery request",
            detail = quotes.joinToString("\n") + "\n\n" + FULFILLMENT_LIMITATION,
            badge = "Not arranged by Beta",
            tone = SwiggyStepTone.AMBER,
        ),
    )

/** Acknowledgement belongs only to the exact notes shown in this final review. */
internal class GroceryIntentCheckoutConstraintGate {
    private var shown: List<String>? = null
    private var acknowledged = false
    fun beginReview(quotes: List<String>) { shown = quotes.toList(); acknowledged = false }
    fun acknowledge(checked: Boolean) { acknowledged = checked }
    fun invalidate() { shown = null; acknowledged = false }
    fun canPlace(current: List<String>): Boolean = shown == current && (current.isEmpty() || acknowledged)
}

/** The same excluded rows and explicit skip action used by the actual cart review. */
internal fun groceryIntentExcludedReviewRows(text: List<String>, edit: () -> Unit): List<SwiggyStepRow> =
    text.map { source ->
        SwiggyStepRow(
            title = source,
            detail = "Beta could not safely match this part of your list. It will NOT be added. Edit your list to include it, or confirm below to leave it out.",
            badge = "Not added",
            tone = SwiggyStepTone.AMBER,
            action = SwiggyStepAction("Edit your list", edit),
        )
    }

internal fun groceryIntentCartReviewAction(
    ordinaryLabel: String,
    gate: GroceryIntentReviewGate,
    isCurrent: () -> Boolean,
    apply: () -> Unit,
): SwiggyStepAction {
    val excludes = gate.excludedText.isNotEmpty()
    val label = if (excludes) "Add shown items; leave unclear items out" else ordinaryLabel
    return SwiggyStepAction(label) {
        if (isCurrent()) {
            // A generic acknowledgement must not mark excluded rows as included.
            // This explicitly labelled final action records only their exclusion.
            if (excludes) gate.confirmExclusions()
            apply()
        }
    }
}
