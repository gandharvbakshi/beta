package com.example.beta

/** In-memory, per-request acknowledgement that the shown basket accounts for highlighted wording. */
internal class GroceryIntentReviewGate {
    var unresolvedText: List<String> = emptyList()
        private set
    private var acknowledged = false

    fun prepare(text: List<String>) {
        unresolvedText = text.filter { it.isNotBlank() }.distinct().toList()
        beginReview()
    }

    fun beginReview() { acknowledged = false }
    fun acknowledge(checked: Boolean) { acknowledged = checked }
    fun canApply(): Boolean = unresolvedText.isEmpty() || acknowledged
    fun clear() { prepare(emptyList()) }
}
