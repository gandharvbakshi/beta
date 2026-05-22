package com.example.beta

enum class ItemOutcomeStatus(val code: String) {
    SUCCESS("success"),
    OOS("oos"),
    STORE_UNAVAILABLE("store_unavailable"),
    NOT_FOUND("not_found"),
    MISCLICK("misclick"),
    SKIPPED("skipped"),
    LOW_CONFIDENCE("low_confidence"),
    TIMEOUT("timeout"),
}

data class ItemOutcome(
    val item: String,
    val status: ItemOutcomeStatus,
    val matchedSku: String = "",
    val qtyRequested: Int = 1,
    val qtyAdded: Int = 0,
    val notes: String = ""
)

private fun String.outcomeQuoted() = this.replace("\"", "\\\"")

private const val STORE_UNAVAILABLE_GUIDANCE = "Beta has paused. Try again later or continue manually."

fun isStoreUnavailableFailureReason(reason: String?): Boolean {
    val normalized = reason?.trim()?.lowercase().orEmpty()
    if (normalized.isBlank()) return false

    return listOf(
        "store unavailable",
        "unserviceable",
        "store or delivery is not available",
        "delivery is not available",
        "store is currently unserviceable",
        "currently unserviceable",
        "not serviceable",
        "store is currently unavailable",
        "store currently unavailable",
        "currently unavailable in blinkit",
        "currently unavailable in instamart",
        "delivery location",
        "service not available"
    ).any { normalized.contains(it) }
}

fun isStoreAppUnavailableFailureReason(reason: String?): Boolean {
    val normalized = reason?.trim()?.lowercase().orEmpty()
    if (normalized.isBlank()) return false

    return listOf(
        "temporarily unavailable",
        "high traffic",
        "outage",
        "store not available",
        "app unavailable"
    ).any { normalized.contains(it) }
}

fun terminalFailureStatusForReason(reason: String?): ItemOutcomeStatus {
    val normalized = reason?.trim()?.lowercase().orEmpty()
    if (normalized.isBlank()) return ItemOutcomeStatus.NOT_FOUND

    return when {
        isStoreAppUnavailableFailureReason(normalized) ||
            isStoreUnavailableFailureReason(normalized) -> ItemOutcomeStatus.STORE_UNAVAILABLE
        normalized.contains("out of stock") ||
            normalized.contains("sold out") ||
            normalized.contains("notify me") ||
            normalized.contains("currently unavailable") ||
            normalized.contains("unavailable") ||
            (normalized.contains("quantity") && normalized.contains("did not reach")) ||
            (normalized.contains("requested quantity") && normalized.contains("not visible")) -> ItemOutcomeStatus.OOS
        normalized.contains("low") && normalized.contains("confidence") -> ItemOutcomeStatus.LOW_CONFIDENCE
        normalized.contains("timeout") ||
            normalized.contains("timed out") ||
            normalized.contains("max steps") ||
            normalized.contains("maximum actions") -> ItemOutcomeStatus.TIMEOUT
        normalized.contains("misclick") ||
            normalized.contains("wrong") ||
            normalized.contains("wishlist") ||
            normalized.contains("summary_and_edit") -> ItemOutcomeStatus.MISCLICK
        else -> ItemOutcomeStatus.NOT_FOUND
    }
}

fun terminalFailureNoteForStatus(status: ItemOutcomeStatus, fallback: String): String {
    return when (status) {
        ItemOutcomeStatus.OOS -> "out_of_stock"
        ItemOutcomeStatus.STORE_UNAVAILABLE -> "store_unavailable"
        ItemOutcomeStatus.LOW_CONFIDENCE -> "low_confidence"
        ItemOutcomeStatus.TIMEOUT -> "timeout"
        ItemOutcomeStatus.MISCLICK -> "misclick"
        else -> fallback
    }
}

fun storeUnavailableGuidanceMessage(): String = STORE_UNAVAILABLE_GUIDANCE

fun formatStoreUnavailableStateLine(): String = "STATE: STORE_UNAVAILABLE\n$STORE_UNAVAILABLE_GUIDANCE"

fun normalizeOrderOutcomeItem(rawItem: String?, matchedTarget: String? = null): String {
    val matched = matchedTarget?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
    if (matched != null) {
        return normalizePluralProbe(matched)
    }

    val raw = rawItem?.trim()?.takeIf { it.isNotEmpty() } ?: return "unknown_item"
    val withoutVerb = raw.replace(
        Regex("^(get me|pick up|order|buy|add|get|please|fetch|bring)\\s+", RegexOption.IGNORE_CASE),
        ""
    ).trim()

    return normalizePluralProbe(withoutVerb.ifEmpty { raw })
}

private fun normalizePluralProbe(item: String): String {
    return when (item.trim().lowercase()) {
        "apples" -> "apple"
        else -> item.trim()
    }
}

fun formatItemResultLine(outcome: ItemOutcome): String {
    return "ITEM_RESULT item=\"${outcome.item.outcomeQuoted()}\" " +
        "status=${outcome.status.code} " +
        "matched_sku=\"${outcome.matchedSku.outcomeQuoted()}\" " +
        "qty_requested=${outcome.qtyRequested} " +
        "qty_added=${outcome.qtyAdded} " +
        "notes=\"${outcome.notes.outcomeQuoted()}\""
}

fun formatItemResultStateLine(item: String, status: ItemOutcomeStatus): String {
    return "STATE: ITEM_RESULT (${item}: ${status.code})"
}

data class OrderFailure(
    val item: String,
    val reason: ItemOutcomeStatus
)

data class OrderOutcomeSummary(
    val itemsTotal: Int,
    val itemsSucceeded: Int,
    val itemsFailed: Int,
    val failures: List<OrderFailure> = emptyList()
) {
    fun orderDoneLine(): String = formatOrderResultLine(this)
}

fun formatOrderResultLine(summary: OrderOutcomeSummary): String {
    val failuresText = summary.failures.joinToString(";") { failure ->
        "${failure.item}:${failure.reason.code}"
    }
    return "ORDER_RESULT items_total=${summary.itemsTotal} " +
        "items_succeeded=${summary.itemsSucceeded} " +
        "items_failed=${summary.itemsFailed} " +
        "failures=\"${failuresText}\""
}

fun formatOrderDoneStateLine(): String = "STATE: ORDER_DONE"
