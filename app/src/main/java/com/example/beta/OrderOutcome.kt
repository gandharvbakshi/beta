package com.example.beta

enum class ItemOutcomeStatus(val code: String) {
    SUCCESS("success"),
    OOS("oos"),
    STORE_UNAVAILABLE("store_unavailable"),
    BACKEND_UNAVAILABLE("backend_unavailable"),
    QUANTITY_NOT_REACHED("quantity_not_reached"),
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

fun isBackendUnavailableFailureReason(reason: String?): Boolean {
    val normalized = reason?.trim()?.lowercase()?.replace('-', ' ').orEmpty()
    if (normalized.isBlank()) return false

    return listOf(
        "backend 401",
        "http 401",
        "unauthorized",
        "missing backend api key",
        "api key",
        "backend unavailable",
        "backend error",
        "backend_error"
    ).any { normalized.contains(it) }
}

fun terminalFailureStatusForReason(reason: String?): ItemOutcomeStatus {
    val normalized = reason?.trim()?.lowercase()?.replace('-', ' ').orEmpty()
    if (normalized.isBlank()) return ItemOutcomeStatus.NOT_FOUND

    return when {
        isBackendUnavailableFailureReason(normalized) -> ItemOutcomeStatus.BACKEND_UNAVAILABLE
        isStoreAppUnavailableFailureReason(normalized) ||
            isStoreUnavailableFailureReason(normalized) -> ItemOutcomeStatus.STORE_UNAVAILABLE
        normalized.contains("limited") && (
            normalized.contains("below quantity") ||
                normalized.contains("this item to one")
            ) -> ItemOutcomeStatus.OOS
        normalized.contains("quantity") && (
            normalized.contains("did not reach") ||
                normalized.contains("not confirmed") ||
                normalized.contains("not visible") ||
                normalized.contains("could not confirm") ||
                normalized.contains("controls did not reach") ||
                normalized.contains("exceeded requested")
            ) -> ItemOutcomeStatus.QUANTITY_NOT_REACHED
        normalized.contains("out of stock") ||
            normalized.contains("sold out") ||
            normalized.contains("notify me") ||
            normalized.contains("currently unavailable") ||
            normalized.contains("unavailable") ||
            normalized.contains("substitution") -> ItemOutcomeStatus.OOS
        normalized.contains("cart verification never became available") -> ItemOutcomeStatus.TIMEOUT
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
        ItemOutcomeStatus.BACKEND_UNAVAILABLE -> "backend_unavailable"
        ItemOutcomeStatus.QUANTITY_NOT_REACHED -> "quantity_not_reached"
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

private fun orderFailureLabel(reason: ItemOutcomeStatus): String {
    return when (reason) {
        ItemOutcomeStatus.OOS -> "unavailable"
        ItemOutcomeStatus.STORE_UNAVAILABLE -> "store unavailable"
        ItemOutcomeStatus.BACKEND_UNAVAILABLE -> "blocked by Beta"
        ItemOutcomeStatus.QUANTITY_NOT_REACHED -> "needs quantity adjustment"
        ItemOutcomeStatus.LOW_CONFIDENCE -> "needs review"
        ItemOutcomeStatus.TIMEOUT -> "timed out"
        ItemOutcomeStatus.MISCLICK -> "needs review"
        ItemOutcomeStatus.NOT_FOUND -> "not found"
        ItemOutcomeStatus.SKIPPED -> "skipped"
        ItemOutcomeStatus.SUCCESS -> "added"
    }
}

fun formatOrderSummaryStateLine(summary: OrderOutcomeSummary): String {
    if (summary.itemsFailed > 0 && summary.failures.all { it.reason == ItemOutcomeStatus.BACKEND_UNAVAILABLE }) {
        return "Stopped: Backend unavailable\nTry again after Beta is fixed."
    }

    if (summary.itemsFailed <= 0) {
        return "Done: ${summary.itemsSucceeded} added\nCheck cart before paying."
    }

    val firstFailure = summary.failures.firstOrNull()
    val failureText = firstFailure?.let { failure ->
        "${failure.item} ${orderFailureLabel(failure.reason)}"
    } ?: "${summary.itemsFailed} need review"
    val extraFailures = if (summary.failures.size > 1) " +${summary.failures.size - 1} more" else ""

    return if (summary.itemsSucceeded > 0) {
        "Done: ${summary.itemsSucceeded} added; $failureText$extraFailures\nCheck cart before paying."
    } else {
        "Stopped: $failureText$extraFailures\nTry manually or choose another item."
    }
}

fun formatOrderDoneStateLine(): String = "STATE: ORDER_DONE"
