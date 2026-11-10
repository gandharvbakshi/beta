package com.example.beta

import java.util.Locale

/** A nutrition label is identity evidence, never the physical size of a SKU. */
internal data class NutritionClaim(
    val nutrient: String,
    val amount: String,
    val unit: String,
    val basis: String,
)

internal data class NutritionMeasureEvidence(
    val claims: Set<NutritionClaim>,
    val physicalText: String,
    val unsupported: Boolean,
)

private const val nutritionNutrient = "(?:protein|fibre|fiber|carbs?|fat|sugar|sodium|calcium)"
private const val nutritionNumber = "[0-9]+(?:\\.[0-9]+)?"
private const val nutritionPhysicalUnit = "(?:kg|kgs|g|gm|gms|grams?|ml|l|ltr|lit(?:er|re)s?)"
private val nutritionClaimPattern = unicodeEvidenceRegex(
    "(?<![\\w.+/\\-−])($nutritionNumber)\\s*(grams?|g|ml)\\s+" +
        "($nutritionNutrient)\\s+per\\s+(serving|portion|100\\s*(?:grams?|g|ml))(?![\\w.])",
)
// Attached explanations/arithmetic are outside the closed basis grammar. Do
// not consume the known prefix and then use the extension as a package size.
private val nutritionExtendedBasis = unicodeEvidenceRegex("^\\s*(?:of\\b|[({\\[:=/+*×]|[\\-−]?[0-9])")
private val nutritionUnownedFragments = listOf(
    unicodeEvidenceRegex("(?<![\\w.])[+\\-−]?$nutritionNumber\\s*$nutritionPhysicalUnit\\s+$nutritionNutrient\\b"),
    unicodeEvidenceRegex("\\b$nutritionNutrient\\s*:?\\s*[+\\-−]?$nutritionNumber\\s*$nutritionPhysicalUnit\\b"),
    unicodeEvidenceRegex("\\b$nutritionNutrient\\s+per\\b"),
    unicodeEvidenceRegex("\\bper\\s+(?:serving|portion|100\\s*(?:g|grams?|ml))\\b"),
)
private val qualitativeNutrition = unicodeEvidenceRegex(
    "\\b(?:(?:no(?:[\\s-]+added)?|without|low|reduced|less|zero)[\\s-]+$nutritionNutrient|" +
        "$nutritionNutrient[\\s-]+free)\\b(?!\\s*:)",
)

private val commodityMeasure = unicodeEvidenceRegex("(?<![\\w.+/\\-−])$nutritionNumber\\s*(?:kg|kgs|grams?|g|gm|gms)(?!\\w)")
private val commodityRelation = unicodeEvidenceRegex("\\b(?:with|without|contains?|containing|per|each|nutrition|serving|portion|of|free|added|no|zero|one|two|three|four|five|six|seven|eight|nine|ten)\\b")

/** Bounded nominal-head convention, never a brand or ingredient inference. */
private fun isMeasuredSugarCommodity(text: String): Boolean {
    val measures = commodityMeasure.findAll(text).toList()
    if (measures.size != 1 || commodityRelation.containsMatchIn(text)) return false
    val measure = measures.single()
    if (Regex("[0-9]+(?:\\.[0-9]+)?").find(measure.value)?.value?.toBigDecimal()?.signum() != 1) return false
    var nominal = text.replaceRange(measure.range, " ").trim()
    if (unicodeEvidenceRegex("[\\p{N}:=/+*×()\\[\\]{}]", ignoreCase = false).containsMatchIn(nominal)) return false
    nominal = nominal.trim(' ', '·', '—', '–', '-', ',')
        .replace(unicodeEvidenceRegex("\\b(?:packs?|packets?|bags?|pouches?)\\b"), " ").trim()
    if (!unicodeEvidenceRegex("[\\p{L}\\p{M}'’ -]+").matches(nominal)) return false
    return unicodeEvidenceRegex("\\bsugar$").containsMatchIn(nominal)
}

internal fun nutritionClaimsConflict(claims: Set<NutritionClaim>): Boolean =
    claims.groupBy { Triple(it.nutrient, it.unit, it.basis) }.values.any { it.size > 1 }

/** Closed, literal grammar only. Unsupported claim measures remain held. */
internal fun nutritionMeasureEvidence(text: String): NutritionMeasureEvidence {
    val matches = nutritionClaimPattern.findAll(text).toList()
    val claims = matches.map { match ->
        val nutrient = match.groupValues[3].lowercase(Locale.ROOT).let { if (it == "carbs") "carb" else it }
        val basisText = match.groupValues[4].lowercase(Locale.ROOT).replace(unicodeEvidenceRegex("\\s+", ignoreCase = false), "")
        NutritionClaim(
            nutrient,
            match.groupValues[1].toBigDecimal().stripTrailingZeros().toPlainString(),
            if (match.groupValues[2].equals("ml", ignoreCase = true)) "ml" else "g",
            when {
                basisText.endsWith("ml") -> "100ml"
                basisText.startsWith("100") -> "100g"
                else -> basisText
            },
        )
    }.toSet()
    val extended = matches.any { match ->
        nutritionExtendedBasis.containsMatchIn(text.substring(match.range.last + 1))
    }
    // A source-preserved qualitative property is not the owner of a following
    // pack size ("no added sugar 50g"). Mask only for ambiguous-fragment checks.
    val ambiguousText = qualitativeNutrition.replace(text) { " ".repeat(it.value.length) }
    val unowned = !isMeasuredSugarCommodity(text) && nutritionUnownedFragments.any { pattern ->
        pattern.findAll(ambiguousText).any { fragment ->
            matches.none { it.range.first <= fragment.range.first && it.range.last >= fragment.range.last }
        }
    }
    val qualitativeBasis = ambiguousText != text && unicodeEvidenceRegex("\\bper\\b").findAll(ambiguousText).any { fragment ->
        matches.none { it.range.first <= fragment.range.first && it.range.last >= fragment.range.last }
    }
    val masked = text.toCharArray()
    matches.forEach { match -> match.range.forEach { masked[it] = ' ' } }
    return NutritionMeasureEvidence(claims, String(masked), extended || unowned || qualitativeBasis || nutritionClaimsConflict(claims))
}

internal fun nutritionClaimsMatch(request: String, label: String): Boolean {
    val wanted = nutritionMeasureEvidence(request)
    val offered = nutritionMeasureEvidence(label)
    if (wanted.unsupported || offered.unsupported || !offered.claims.containsAll(wanted.claims)) return false
    return wanted.claims.none { required ->
        offered.claims.any { candidate ->
            candidate.nutrient == required.nutrient && candidate.unit == required.unit &&
                candidate.basis == required.basis && candidate.amount != required.amount
        }
    }
}
