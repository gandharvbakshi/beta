package com.example.beta

import java.math.BigDecimal
import java.util.Locale

internal enum class GroceryPackDimension { WEIGHT, VOLUME, PIECES, PACKS }

/** Canonical comparison value, separate from product identity and purchase quantity. */
internal data class GroceryPackMeasure private constructor(
    val dimension: GroceryPackDimension,
    val baseValue: BigDecimal,
) {
    companion object {
        fun of(value: BigDecimal, unit: String): GroceryPackMeasure {
            val normalizedUnit = unit.lowercase(Locale.ROOT)
            val dimension = when (normalizedUnit) {
                "kg", "kgs", "g", "gm", "gms", "gram", "grams" -> GroceryPackDimension.WEIGHT
                "ml", "l", "ltr", "liter", "litre", "liters", "litres" -> GroceryPackDimension.VOLUME
                "pack", "packs" -> GroceryPackDimension.PACKS
                "piece", "pieces", "pc", "pcs", "count", "lozenge", "lozenges", "tablet", "tablets",
                "capsule", "capsules", "sachet", "sachets", "pellet", "pellets", "egg", "eggs",
                "battery", "batteries" -> GroceryPackDimension.PIECES
                else -> error("Unsupported pack dimension")
            }
            val factor = if (normalizedUnit in setOf("kg", "kgs", "l", "ltr", "liter", "litre", "liters", "litres"))
                BigDecimal(1000) else BigDecimal.ONE
            return GroceryPackMeasure(dimension, value.multiply(factor).stripTrailingZeros())
        }
    }
}

internal val groceryPackMeasurePattern = Regex(
    "\\b(\\d+(?:\\.\\d+)?)\\s*(kg|kgs|g|gm|gms|grams?|ml|l|ltr|lit(?:er|re)s?|pcs?|pieces?|count|packs?|lozenges?|tablets?|capsules?|sachets?|pellets?|eggs?|battery|batteries)\\b",
    RegexOption.IGNORE_CASE,
)

/** Supported nutrient claims are masked; callers must reject unsupported nutrition evidence first. */
internal fun groceryPackMeasures(text: String): Set<GroceryPackMeasure> = groceryPackMeasurePattern
    .findAll(nutritionMeasureEvidence(text).physicalText)
    .map { GroceryPackMeasure.of(it.groupValues[1].toBigDecimal(), it.groupValues[2]) }.toSet()

/** Check the only typed-to-text boundary; source validation still precedes it. */
internal fun GroceryIntentItem.hasExactPackProjection(parsed: com.example.beta.automation.ParsedItem): Boolean {
    if (nutritionMeasureEvidence(parsed.query).unsupported || parsed.strictMatchPhrase == null ||
        nutritionMeasureEvidence(parsed.strictMatchPhrase).unsupported) return false
    val expected = packValue?.let { setOf(GroceryPackMeasure.of(it, packUnit)) }.orEmpty()
    return groceryPackMeasures(parsed.query) == expected &&
        groceryPackMeasures(parsed.strictMatchPhrase) == expected
}
