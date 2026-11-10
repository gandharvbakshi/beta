package com.example.beta

import java.text.Normalizer
import java.util.Locale

// A small vocabulary of complete produce categories, not a SKU, variety, form or stock
// dictionary. These pairs appear in the parallel APEDA Fresh Fruits & Vegetables pages:
// https://apeda.gov.in/FreshFruitsAndVegetables and /hindi/index.php/hi/FreshFruitsAndVegetables.
// Keep this independent of broad search aliases; never replace a word inside another noun.
private val measuredProduceCategories = mapOf(
    "आलू" to "potato", "टमाटर" to "tomato", "प्याज" to "onion",
    "फूलगोभी" to "cauliflower", "पत्तागोभी" to "cabbage", "बैंगन" to "brinjal",
    "खीरा" to "cucumber", "लहसुन" to "garlic", "हरी मिर्च" to "green chilli",
    // e-NAM, Quality Parameters of commodities, fresh-vegetable entries:
    // https://enam.gov.in/web/docs/Quality_Parameters_193_commodities.pdf
    // Whole names only: the sweet-potato root is not an ordinary potato.
    "गाजर" to "carrot", "शकरकंद" to "sweet potato", "पालक" to "spinach",
    "भिंडी" to "okra", "चुकंदर" to "beetroot",
)
internal val measuredProduceCompoundNames = measuredProduceCategories.values.filter { ' ' in it }.toSet()
// Alternate complete names in e-NAM entry 65. A renderer may already have
// chosen one of these; don't trust a partial noun or a general search synonym.
private val measuredProduceAlternateNames = mapOf("bhindi" to "okra", "lady finger" to "okra")
internal fun measuredProduceExactName(name: String): String = measuredProduceAlternateNames[name] ?: name
private val listAnd = Regex("[ \\t]*,?[ \\t]+और[ \\t]+")
private val listComma = Regex("[ \\t]*,[ \\t]*")
private val listRequestEnd = Regex("[ \\t]+(?:ले[ \\t]+आना|भेज[ \\t]+देना|चाहिए)[ \\t]*[।.!]?[ \\t]*")
private val listPlainEnd = Regex("[ \\t]*[।.!][ \\t]*")
private fun nounKey(text: String) = Normalizer.normalize(text, Normalizer.Form.NFC)
    .trim(' ', '\t').replace(Regex("[ \\t]+"), " ").lowercase(Locale.ROOT)

/** Complete original list ONLY: noun + its own edge measure on every row, no inferred words.
 * Produces lookup categories, never a cart request or waiver for another validation step. */
internal class MeasuredProduceTerm(val sourceNoun: String, val category: String) {
    fun namesSameCommodity(name: String): Boolean =
        nounKey(name).let { it == nounKey(sourceNoun) || measuredProduceExactName(it) == category }
}

internal fun compileMeasuredProduceList(instruction: String, items: List<GroceryIntentItem>): List<MeasuredProduceTerm>? {
    if (items.size !in 1..50 || instruction.length !in 1..6000 ||
        instruction.any { Character.getType(it) == Character.FORMAT.toInt() || it.isISOControl() && it != '\t' } ||
        instruction.none { it in '\u0900'..'\u097f' } || groceryIntentHasUncompiledNativeSourceScope(instruction)) return null
    val terms = mutableListOf<MeasuredProduceTerm>()
    var end = 0
    var hasConjunction = false
    for (item in items) {
        val source = item.sourceText
        val span = bindGroceryIntentProductSource(instruction, source, 6000) ?: return null
        if (span.startUtf16 < end || item.needsClarification || item.packValue != null ||
            item.packUnit != "unspecified" || item.required.isNotEmpty() || item.excluded.isNotEmpty()) return null
        val gap = instruction.substring(end, span.startUtf16)
        when {
            terms.isEmpty() -> if (gap.any { it != ' ' && it != '\t' }) return null
            listAnd.matches(gap) -> hasConjunction = true
            !listComma.matches(gap) -> return null
        }
        if (item.quantityUnit != "g" && item.quantityUnit != "kg") return null
        val value = item.quantity?.takeIf { it.signum() > 0 } ?: return null
        val amount = sourceLocalAmount(source, value, item.quantityUnit) ?: return null
        val unit = GroceryIntentPhysicalUnitMatcher.after(source, amount.endUtf16, item.quantityUnit) ?: return null
        // The shared unit matcher accepts more separators for ordinary quantities. This
        // affirmative receipt must not silently consume signs, controls or line boundaries.
        if (source.substring(amount.endUtf16, unit.start).any { it != ' ' && it != '\t' }) return null
        val before = source.substring(0, amount.startUtf16)
        val after = source.substring(unit.end)
        val noun = when {
            before.isEmpty() && after.firstOrNull() in listOf(' ', '\t') -> after
            after.isEmpty() && before.lastOrNull() in listOf(' ', '\t') -> before
            else -> return null
        }
        val literal = nounKey(noun)
        val category = measuredProduceCategories[literal] ?: return null
        val term = MeasuredProduceTerm(noun.trim(' ', '\t'), category)
        if (!term.namesSameCommodity(item.name) || terms.any { it.category == category }) return null
        terms += term
        end = span.endUtf16
    }
    val tail = instruction.substring(end)
    return terms.takeIf { listRequestEnd.matches(tail) || hasConjunction && listPlainEnd.matches(tail) }
}
