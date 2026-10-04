package com.example.beta

import java.math.BigDecimal

/**
 * Bounded package-size evidence check, not a complete quantity interpretation.
 * Only physical sizes directly associated with a retail container are considered.
 */
internal fun groceryIntentPackEvidenceConflict(item: GroceryIntentItem): Boolean {
    val source = item.sourceText
    val matches = (amountBeforeContainer.findAll(source) + amountAfterContainer.findAll(source)).toList()
    val parsedSizes = matches.map { size(it) }
    if (parsedSizes.any { it == null }) return true
    val sizes = parsedSizes.filterNotNull().asSequence()
        .map { canonicalSize(it) }
        .distinctBy { it.amount.stripTrailingZeros() to it.unit }
        .toList()
    if (sizes.isEmpty()) return false
    if (sizes.size != 1) return true

    val countLikeQuantity = item.quantityUnit in setOf("count", "pack", "piece") ||
        (item.quantityUnit == "unspecified" && item.quantity == null)
    val packAmount = item.packValue ?: return true
    val packSize = canonicalSize(PhysicalSize(packAmount, item.packUnit))
    val expected = sizes.single()
    if (packSize.unit != expected.unit || packSize.amount.compareTo(expected.amount) != 0 ||
        expected.amount.signum() <= 0) return true
    if (countLikeQuantity) return false
    val quantity = item.quantity ?: return true
    val quantityUnit = normalizePhysicalUnit(item.quantityUnit) ?: return true
    val total = canonicalSize(PhysicalSize(quantity, quantityUnit))
    return total.unit != expected.unit || total.amount.signum() <= 0 ||
        total.amount.remainder(expected.amount).signum() != 0
}

private data class PhysicalSize(val amount: BigDecimal, val unit: String)

private fun size(match: MatchResult): PhysicalSize? {
    val amount = parsePhysicalAmount(match.groupValues[1]) ?: return null
    val unit = normalizePhysicalUnit(match.groupValues[2]) ?: return null
    return PhysicalSize(amount, unit)
}

private fun parsePhysicalAmount(raw: String): BigDecimal? {
    val normalized = raw.trim().replace(",", "")
    val fraction = when (normalized) {
        "½" -> "0.5"
        "¼" -> "0.25"
        "¾" -> "0.75"
        else -> normalized
    }
    if ('/' !in fraction) return fraction.toBigDecimalOrNull()
    val parts = fraction.split(Regex("\\s+"))
    val ratio = parts.last().split('/')
    return runCatching {
        val whole = if (parts.size == 2) parts.first().toBigDecimal() else BigDecimal.ZERO
        whole.add(ratio[0].toBigDecimal().divide(ratio[1].toBigDecimal()))
    }.getOrNull()
}

private fun normalizePhysicalUnit(raw: String): String? = when (raw.lowercase()) {
    "g", "gm", "gms", "gram", "grams" -> "g"
    "kg", "kgs", "kilogram", "kilograms" -> "kg"
    "ml", "milliliter", "milliliters", "millilitre", "millilitres" -> "ml"
    "l", "lt", "ltr", "ltrs", "liter", "liters", "litre", "litres" -> "l"
    else -> null
}

private fun canonicalSize(size: PhysicalSize): PhysicalSize = when (size.unit) {
    "kg" -> PhysicalSize(size.amount.multiply(THOUSAND), "g")
    "l" -> PhysicalSize(size.amount.multiply(THOUSAND), "ml")
    else -> size
}

private val containers = "(?:bags?|packs?|packets?|bottles?|cartons?|pouch(?:es)?|box(?:es)?|tins?|jars?|tubs?|blocks?)"
private val amountUnits = "(grams?|gms?|gm|g|kilograms?|kgs?|milliliters?|millilitres?|ml|liters?|litres?|ltrs?|lt|l)"
private const val amount = "((?:[0-9]+\\s+)?[0-9]+/[0-9]+|[0-9]+(?:,[0-9]{2,3})+(?:\\.[0-9]+)?|[0-9]+(?:\\.[0-9]+)?|[½¼¾])"
private val amountBeforeContainer = Regex(
    "(?i)(?<![\\p{L}\\d.,/])$amount\\s*-?\\s*$amountUnits\\s+(?:of\\s+)?$containers\\b",
)
private val amountAfterContainer = Regex(
    "(?i)\\b$containers\\s+(?:of\\s+)?$amount\\s*-?\\s*$amountUnits\\b",
)
private val THOUSAND = BigDecimal("1000")
