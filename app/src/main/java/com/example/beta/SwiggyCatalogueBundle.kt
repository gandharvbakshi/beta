package com.example.beta

import java.text.Normalizer

/**
 * Denial-only packaging evidence shared by retrieval, selection and quantity calculation.
 * A catalogue word such as "twin pack" is evidence of a bundle, not evidence
 * of an exact sellable count. Never turn it into one (or two) cart units.
 * Numeric bundle parsing remains in the exact-count gate.
 */
private val unresolvedBundlePatterns = listOf(
    "\\b(?:multi[\\s-]*packs?|bundles?|combos?)\\b",
    "\\bcombi[\\s-]*packs?\\b",
    "\\b(?:twin|duo|trio|double|triple|quadruple)[\\s-]*(?:packs?|packets?|sets?|bundles?|boxes|cartons?|bottles?|bags?)\\b",
    "\\b(?:sets?|pairs?)[\\s-]+of\\b",
    // Word counts are deliberately unresolved; this layer does not invent arithmetic.
    "\\b(?:packs?|trays?)[\\s-]+of[\\s-]+[a-z]",
    "\\bbuy\\s*(?:[0-9]+|[a-z]+)\\s+get\\s*(?:[0-9]+|[a-z]+)\\b",
    "\\b[0-9]+\\s*\\+\\s*[0-9]+\\b",
    "\\b(?:bogo|b[0-9]+g[0-9]+)\\b",
    "\\b(?:[2-9]|[1-9][0-9]+)\\s+for\\s+(?:₹|rs\\.?|inr|\\$)\\s*[0-9]+\\b",
).map { Regex(it, RegexOption.IGNORE_CASE) }

internal fun swiggyCatalogueHasUnresolvedBundle(fields: List<String>): Boolean {
    val normalized = fields.map { field ->
        // Code points, not UTF-16 chars: supplementary formatting characters
        // must have the same denial-only treatment on Python and Android.
        buildString {
            Normalizer.normalize(field, Normalizer.Form.NFKC).codePoints().forEach { codePoint ->
                if (Character.getType(codePoint) != Character.FORMAT.toInt()) appendCodePoint(codePoint)
            }
        }.replace(Regex("[\u2010-\u2015\u2212]"), "-")
    }
    // A cross-field phrase supplies negative evidence only. Do not discard
    // variant/subtitle evidence via display substring de-duplication first.
    return (normalized + normalized.joinToString(" ")).any { text -> unresolvedBundlePatterns.any { it.containsMatchIn(text) } }
}
