package com.example.beta

internal enum class GroceryIntentPreparationRoute {
    INPUT_REPAIR,
    EDITOR,
    EMPTY,
    DISCOVERY,
}

/** Pure mirror of the coordinator's preparation precedence; acknowledgement never selects a route. */
internal fun groceryIntentPreparationRoute(
    inputRepairPresent: Boolean,
    allowQuickRepair: Boolean,
    recoverySnapshotPresent: Boolean,
    unresolvedTextPresent: Boolean,
    validationMessagePresent: Boolean,
    itemsEmpty: Boolean,
): GroceryIntentPreparationRoute = when {
    inputRepairPresent && allowQuickRepair -> GroceryIntentPreparationRoute.INPUT_REPAIR
    inputRepairPresent -> GroceryIntentPreparationRoute.EDITOR
    recoverySnapshotPresent || unresolvedTextPresent || validationMessagePresent -> GroceryIntentPreparationRoute.EDITOR
    itemsEmpty -> GroceryIntentPreparationRoute.EMPTY
    else -> GroceryIntentPreparationRoute.DISCOVERY
}

/** In-memory, per-request acknowledgement that the shown basket accounts for highlighted wording. */
internal class GroceryIntentReviewGate {
    var unresolvedText: List<String> = emptyList()
        private set
    var excludedText: List<String> = emptyList()
        private set
    var sharedGroupNotes: List<String> = emptyList()
        private set
    var fulfillmentConstraints: List<String> = emptyList()
        private set
    private var acknowledged = false
    private var exclusionsConfirmed = false

    fun prepare(
        text: List<String>,
        excluded: List<String> = emptyList(),
        groups: List<String> = emptyList(),
        fulfillment: List<String> = emptyList(),
    ) {
        unresolvedText = text.filter { it.isNotBlank() }.distinct().toList()
        excludedText = excluded.filter { it.isNotBlank() }.distinct().toList()
        sharedGroupNotes = groups.filter { it.isNotBlank() }.distinct().toList()
        fulfillmentConstraints = fulfillment.filter { it.isNotBlank() }.distinct().toList()
        beginReview()
    }

    fun beginReview() { acknowledged = false; exclusionsConfirmed = false }
    fun acknowledge(checked: Boolean) { acknowledged = checked }
    /** Called only by the final action explicitly labelled to leave these rows out. */
    fun confirmExclusions() { exclusionsConfirmed = true }
    fun canApply(): Boolean = (unresolvedText.isEmpty() && fulfillmentConstraints.isEmpty() || acknowledged) &&
        (excludedText.isEmpty() || exclusionsConfirmed)
    fun clear() { prepare(emptyList()) }
}
