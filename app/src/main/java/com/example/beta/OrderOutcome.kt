package com.example.beta

enum class ItemOutcomeStatus(val code: String) {
    SUCCESS("success"),
    OOS("oos"),
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
