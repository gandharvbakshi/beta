package com.example.beta

import com.example.beta.automation.IntentLocalAmount
import java.text.Normalizer
import java.util.Locale

/** A total is a constraint on a purchase, not itself proof of another purchase.
 * This deny-only basket check never sums, deletes or reassigns any source row. */
internal fun splitPhysicalTotalConflicts(instruction: String, items: List<GroceryIntentItem>): List<String> {
    if (items.size < 2 || !aggregateWord.containsMatchIn(instruction)) return emptyList()
    val affected = linkedSetOf<String>()
    fun subject(row: GroceryIntentItem) = intentEvidenceTokens(row.name) - groceryIntentSpecificSourceFormTokens(row.name)
    for (total in items) {
        val measureUnit = total.markedPhysicalMeasureUnit() ?: continue
        val at = instruction.indexOf(total.sourceText)
        if (at < 0) continue
        val lastTotal = instruction.lastIndexOf(total.sourceText)
        val target = subject(total)
        if (target.isEmpty()) continue
        for (other in items) {
            if (other === total) continue
            val start = instruction.indexOf(other.sourceText)
            if (start < 0) continue
            // Existential for a hold, not an assignment: a short product quote may also
            // occur INSIDE the total. Only disjoint original occurrences can conflict.
            if (start + other.sourceText.length > lastTotal &&
                at + total.sourceText.length > instruction.lastIndexOf(other.sourceText)) continue
            val otherSubject = subject(other)
            // A common adjective alone is not an overlapping identity. A narrower identity
            // (e.g. a branded variant) can still contribute to a generic product's total.
            if (otherSubject.isEmpty() || !(target.containsAll(otherSubject) || otherSubject.containsAll(target))) continue
            val measure = if (other.quantityUnit in physicalUnits) other.quantityUnit else other.packUnit
            if (measure in physicalUnits && (measure in massUnits) != (measureUnit in massUnits)) continue
            affected += other.sourceText
            affected += total.sourceText
        }
    }
    return affected.toList()
}

/** Suspicion sufficient to stop, never proof sufficient to buy. An emitter putting the
 * requested amount in the pack field must not be able to bypass this source check. */
private fun GroceryIntentItem.markedPhysicalMeasureUnit(): String? {
    val suffix = aggregateSuffix.find(sourceText)
    val namedTotal = suffix?.value?.trim(' ', '\t', '.', ',', ';', '!', '?')?.equals("total", true) == true &&
        totalBrandToken.containsMatchIn(name)
    for ((value, unit) in listOf(quantity to quantityUnit, packValue to packUnit)) {
        if (value == null || unit !in physicalUnits) continue
        val (altValue, altUnit) = when (unit) {
            "l" -> value.movePointRight(3) to "ml"
            "ml" -> value.movePointLeft(3) to "l"
            "kg" -> value.movePointRight(3) to "g"
            else -> value.movePointLeft(3) to "kg"
        }
        val amount = legacyLocalAmount(value, unit) ?: legacyLocalAmount(altValue, altUnit) ?: continue
        val prefix = sourceText.substring(0, amount.startUtf16).trim()
        if (aggregatePrefix.matches(prefix) || (suffix != null && !namedTotal && suffix.range.first >= amount.endUtf16))
            return unit
    }
    return null
}

private val massUnits = setOf("g", "kg")
private val aggregateWord = Regex("""\b(?:total|altogether|kul)\b""", RegexOption.IGNORE_CASE)
private val totalBrandToken = Regex("""\btotal\b""", RegexOption.IGNORE_CASE)
private val aggregatePrefix = Regex("(?:total|altogether|kul)", RegexOption.IGNORE_CASE)
private val aggregateSuffix = Regex("""\s(?:in[ \t]+total|altogether|total|kul)[ \t.,;!?]*$""", RegexOption.IGNORE_CASE)

/** Deny-only mirror of grocery_intent_amount_roles.is_source_physical_total. */
internal fun isSourcePhysicalTotal(amount: IntentLocalAmount): Boolean {
    val source = amount.source
    if (amount.unit !in physicalUnits || amount.startUtf16 < 0 || amount.endUtf16 <= amount.startUtf16 ||
        amount.endUtf16 > source.length || source.substring(amount.startUtf16, amount.endUtf16) != amount.atom ||
        parseIntentCorrectionAmount(amount.atom)?.compareTo(amount.value) != 0 ||
        !hasUniqueAmountBinding(source, amount)) return false

    val tail = source.substring(amount.endUtf16)
    val measure = Regex("(?-u)^[ \\t]*-?[ \\t]*" + unitAliases.getValue(amount.unit) +
        "\\b(?!['\\u2019][^\\W\\d_])", RegexOption.IGNORE_CASE).find(tail) ?: return false
    val before = source.substring(0, amount.startUtf16).trim(' ', '\t')
    val after = source.substring(amount.endUtf16 + measure.value.length).trim(' ', '\t', '.', '!', '?')
    val suffix = Regex("(?-u)(.+?)[ \\t]+(?:in[ \\t]+total|total|altogether|kul)$", RegexOption.IGNORE_CASE)
        .matchEntire(after)
    val totalWords = "(?-u)(?:total|altogether|kul)"
    val nominal = when {
        Regex(totalWords, RegexOption.IGNORE_CASE).matches(before) ->
            Regex("(?-u)^of[ \\t]+", RegexOption.IGNORE_CASE).replaceFirst(after, "")
        before.isEmpty() && Regex("(?-u)^of[ \\t]+", RegexOption.IGNORE_CASE).containsMatchIn(after) ->
            Regex("(?-u)^of[ \\t]+", RegexOption.IGNORE_CASE).replaceFirst(after, "")
        before.isEmpty() && suffix != null -> suffix.groupValues[1]
        before.isNotEmpty() && Regex("(?-u)(?:in[ \\t]+total|" + totalWords + ")", RegexOption.IGNORE_CASE).matches(after) -> before
        else -> return false
    }
    val nominalCodePoints = nominal.codePoints().toArray()
    if (nominal.isEmpty() || nominalCodePoints.size > 200 ||
        Normalizer.normalize(nominal, Normalizer.Form.NFKC) != nominal || nominalCodePoints.any {
            val category = Character.getType(it)
            !(Character.isLetter(it) || category == Character.NON_SPACING_MARK.toInt() ||
                category == Character.COMBINING_SPACING_MARK.toInt() || category == Character.ENCLOSING_MARK.toInt() ||
                it == ' '.code || it == '\t'.code || it == '-'.code || it == '\''.code || it == '’'.code)
        }) return false
    val tokens = Regex("[ \\t\\-'’]+").split(nominal.lowercase(Locale.ROOT))
    return tokens.none { it in totalRoleStopwords } && !groceryIntentContainsNumericAtom(nominal) &&
        nominalCodePoints.any { Character.isLetter(it) }
}

private fun hasUniqueAmountBinding(source: String, amount: IntentLocalAmount): Boolean {
    val starts = mutableListOf<Int>()
    var from = 0
    while (from <= source.length - amount.atom.length) {
        val start = source.indexOf(amount.atom, from)
        if (start < 0) break
        val end = start + amount.atom.length
        val ordinary = hasQuoteBoundaries(source, amount.atom, start, end)
        val gluedUnit = physicalUnitTokenFollowsAt(source, end, amount.unit)
        val glued = amount.atom.lastOrNull()?.isDigit() == true && gluedUnit != null &&
            hasQuoteBoundaries(source, amount.atom + gluedUnit, start, end + gluedUnit.length)
        if (ordinary || glued) starts += start
        from = start + 1
    }
    return starts.singleOrNull() == amount.startUtf16
}

private val physicalUnits = setOf("g", "kg", "ml", "l")
private val unitAliases = mapOf(
    "g" to "(?:g|gm|gms|grams?)",
    "kg" to "(?:kg|kgs|kilos?|kilograms?|[kK][eE][jJ][iI](?=$|[\\s,.;:!?()\\[\\]{}-]))",
    "ml" to "(?:ml|millilitres?|milliliters?)",
    "l" to "(?:l|ltr|litres?|liters?)",
)
internal val totalRoleStopwords = ("""
    pack packs packet packets bag bags bottle bottles carton cartons cup cups can cans tin tins tub tubs jar jars box boxes
    bar bars block blocks pouch pouches sachet sachets tube tubes loaf loaves refill refills roll rolls spray sprays bunch bunches gaddi tetra
    piece pieces pc pcs nos size sizes variant variants wala wali wale ka ki ke each per har ek total altogether kul and aur or ya
    with without bina no not nahi nahin instead rather sorry make set keep more another extra about around approximately roughly
    minimum maximum least most upto under over from for in on at by of contains containing protein fibre fiber carbs carbohydrate
    carbohydrates fat sugar sodium calcium calories calorie kcal serving servings portion portions dose doses strength dimensions
    dimension sku code model mg mcg iu cm mm inch inches watt watts volt volts ply g gm gms gram grams kg kgs kilo kilos kilogram
    kilograms keji ml millilitre millilitres milliliter milliliters l ltr litre litres liter liters
""").trimIndent().split(Regex("\\s+")).toSet()
