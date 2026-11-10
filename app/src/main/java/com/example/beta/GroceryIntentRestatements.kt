package com.example.beta

import com.example.beta.automation.ProductLexicon
import java.math.BigDecimal
import java.util.Locale

/** Independently validated original row facts supplied by the caller. */
internal data class GroceryIntentRestatementRow(
    val rowIndex: Int,
    val primary: GroceryIntentReviewV2Quote,
    val quantity: BigDecimal?,
    val quantityUnit: String,
    val packValue: BigDecimal?,
    val packUnit: String,
    val packaging: String?,
    val required: List<String>,
) {
    override fun toString(): String =
        "GroceryIntentRestatementRow(rowIndex=$rowIndex, primary=<${primary.startUtf16}..${primary.endUtf16}>, facts=<redacted>)"
}

/** Provisional source receipt only; the owning row must survive normal validation. */
internal data class GroceryIntentRestatementReceipt(
    val source: GroceryIntentReviewV2Quote,
    val rowIndex: Int,
    val kind: String = "restatement",
) {
    override fun toString(): String =
        "GroceryIntentRestatementReceipt(source=<${source.startUtf16}..${source.endUtf16}>, rowIndex=$rowIndex)"
}

/**
 * Find source assertions about facts already present in a validated original
 * row. These receipts never change or add row facts. The
 * caller must recompute after row/group/property owners are rejected.
 */
internal fun groceryIntentRestatementReceipts(
    instruction: String,
    rows: List<GroceryIntentRestatementRow>,
    primaries: List<GroceryIntentReviewV2Quote>,
    protected: List<GroceryIntentReviewV2Quote> = emptyList(),
    allowQuantityExclusions: Boolean = false,
): List<GroceryIntentRestatementReceipt> {
    if (rows.isEmpty() || rows.size > 50 || instruction.length > 6000) return emptyList()
    val spans = primaries + protected
    if (rows.map { it.rowIndex }.toSet().size != rows.size ||
        rows.any { it.primary !in primaries } ||
        spans.any { !validRestatementQuote(instruction, it) }
    ) return emptyList()

    val boundaries = restatementBoundary.findAll(instruction).toList()
    val starts = listOf(0) + boundaries.map { it.range.last + 1 }
    val ends = boundaries.map { it.range.first } + instruction.length
    val receipts = mutableListOf<GroceryIntentRestatementReceipt>()
    if (allowQuantityExclusions) {
        receipts += quantityExclusionReceipts(instruction, rows, primaries, protected)
    }
    starts.zip(ends).forEach { (rawStart, rawEnd) ->
        var start = rawStart
        var end = rawEnd
        while (start < end && instruction[start].isWhitespace()) start++
        while (end > start && instruction[end - 1].isWhitespace()) end--
        if (start == end || spans.any { start < it.endUtf16 && it.startUtf16 < end }) return@forEach
        val clause = instruction.substring(start, end)
        val owners = rows.filter { row -> row.primary.endUtf16 < start && consumeRestatement(clause, row, primaries) }
        if (owners.size == 1) {
            receipts += GroceryIntentRestatementReceipt(
                GroceryIntentReviewV2Quote(clause, start, end),
                owners.single().rowIndex,
            )
        }
    }
    return receipts
}

/** A negative amount is never a prior/requested amount or a pack-size override. */
private fun quantityExclusionReceipts(
    instruction: String,
    rows: List<GroceryIntentRestatementRow>,
    primaries: List<GroceryIntentReviewV2Quote>,
    protected: List<GroceryIntentReviewV2Quote>,
): List<GroceryIntentRestatementReceipt> = rows.mapNotNull { row ->
    val anchors = correctionAnchors(ProductLexicon.canonicalizeAliasesPreservingText(row.primary.text))
    if (anchors.isEmpty() || primaries.any { peer ->
        peer != row.primary && correctionAnchors(ProductLexicon.canonicalizeAliasesPreservingText(peer.text)).any { it in anchors }
    }) return@mapNotNull null
    val end = restatementBoundary.find(instruction, row.primary.endUtf16)?.range?.first ?: instruction.length
    val tail = instruction.substring(row.primary.endUtf16, end)
    val match = quantityExclusionTail.matchEntire(tail) ?: return@mapNotNull null
    val source = match.groups[1] ?: return@mapNotNull null
    if (source.value.any { it !in quantityExclusionCharacters } ||
        !excludedQuantityMatchesRole(match.groupValues[2], row)) return@mapNotNull null
    val start = row.primary.endUtf16 + source.range.first
    val finish = row.primary.endUtf16 + source.range.last + 1
    if ((primaries + protected).any { start < it.endUtf16 && it.startUtf16 < finish }) return@mapNotNull null
    GroceryIntentRestatementReceipt(
        GroceryIntentReviewV2Quote(instruction.substring(start, finish), start, finish),
        row.rowIndex, "quantity_exclusion",
    )
}

private fun excludedQuantityMatchesRole(text: String, row: GroceryIntentRestatementRow): Boolean {
    val positive = row.quantity?.takeIf { it.signum() > 0 } ?: return false
    val count = restatementCount.matchEntire(text)
    val physical = quantityExclusionPhysical.matchEntire(text)
    val pieces = quantityExclusionPieces.matchEntire(text)
    val excluded = when {
        count != null -> {
            val form = canonicalRestatementContainer(count.groupValues[2])
            val original = restatementContainerToken.findAll(row.primary.text)
                .map { canonicalRestatementContainer(it.value) }.toSet()
            if (row.quantityUnit !in setOf("count", "pack") || row.packaging == null ||
                form != canonicalRestatementContainer(row.packaging) || original != setOf(form)) return false
            parseIntentCorrectionAmount(count.groupValues[1])
        }
        physical != null -> {
            val unitPattern = quantityExclusionUnits[row.quantityUnit] ?: return false
            if (row.packValue != null || !unitPattern.matches(physical.groupValues[2])) return false
            parseIntentCorrectionAmount(physical.groupValues[1])
        }
        pieces != null -> {
            if (row.quantityUnit != "piece" || row.packValue != null) return false
            parseIntentCorrectionAmount(pieces.groupValues[1])
        }
        else -> {
            if (row.quantityUnit !in setOf("count", "pack", "piece") || row.packValue != null) return false
            parseIntentCorrectionAmount(text)
        }
    } ?: return false
    return excluded.compareTo(positive) != 0 &&
        (row.quantityUnit !in setOf("count", "pack", "piece") ||
            isPositiveInteger(positive) && isPositiveInteger(excluded))
}

private val restatementBoundary = Regex("[;!?\\r\\n]|\\.(?=\\s|$)")
private val restatementContainer =
    "(?:tetra[ -]?packs?|packs?|packets?|cartons?|bottles?|bags?|boxes|box|cups?|cans?|tins?|tubs?|jars?|pouches|pouch|sachets?)"
private val restatementUnit =
    "(?:kilograms?|kgs?|kilos?|keji|grams?|gms?|g|millilitres?|milliliters?|ml|litres?|liters?|ltr|l)"
private val quantityExclusionTail = Regex("[ \\t]*,?[ \\t]*(not[ \\t]+([^\\r\\n]+?))[ \\t]*", RegexOption.IGNORE_CASE)
private val quantityExclusionCharacters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 \t.,/'-".toSet()
private val quantityExclusionPhysical = Regex("(.+?)[ \\t]*($restatementUnit)", RegexOption.IGNORE_CASE)
private val quantityExclusionPieces = Regex("(.+?)[ \\t]+(?:pieces?|pcs)", RegexOption.IGNORE_CASE)
private val quantityExclusionUnits = mapOf(
    "g" to "g|gms?|grams?", "kg" to "kgs?|kilos?|kilograms?|keji",
    "ml" to "ml|millilitres?|milliliters?", "l" to "l|ltr|litres?|liters?",
).mapValues { Regex(it.value, RegexOption.IGNORE_CASE) }
private val restatementTail = Regex(
    "(?:[ \\t]+(?:rehna|rehne[ \\t]+do|rakhna|hona|chahiye|same|as[ \\t]+before))?$",
    RegexOption.IGNORE_CASE,
)
private val restatementForbiddenTarget = setOf(
    "no", "not", "nahi", "nahin", "never", "without", "bina", "if", "agar", "unless",
    "or", "ya", "instead", "except", "only", "sirf", "bas", "all", "every", "sab", "both",
    "hi", "each", "same", "again", "extra", "and", "aur",
)

private fun consumeRestatement(
    clause: String,
    row: GroceryIntentRestatementRow,
    primaries: List<GroceryIntentReviewV2Quote>,
): Boolean {
    // Recognition is deny-only. Let the scoped property compiler own this
    // clause rather than masking it as competing restatement context.
    if (hasGroceryIntentPropertyObligationClause(clause)) return false
    if (clause.any { it.code !in 0x20..0x7e && it != '\t' }) return false
    if (clause.any { it !in restatementAllowedCharacters }) return false

    val candidateTargets = mutableListOf<String>()
    restatementWhitespace.findAll(clause).forEach { gap ->
        val target = clause.substring(0, gap.range.first)
        val rest = clause.substring(gap.range.last + 1)
        if (!restatementTarget.matches(target)) return@forEach
        val targetWords = asciiWords(target).toSet()
        if (targetWords.isEmpty() || targetWords.any { it in restatementForbiddenTarget }) return@forEach
        val matchingPrimaries = primaries.filter { primary -> targetWords.all { it in asciiWords(primary.text) } }
        if (matchingPrimaries != listOf(row.primary)) return@forEach

        val affirmed = restatementAffirmation.containsMatchIn(rest)
        if (!affirmed) return@forEach
        val factText = restatementTail.replaceFirst(rest, "").trim(' ', '\t')
        val facts = mutableListOf<String>()
        val parts = restatementConjunction.split(factText)
        for (rawPart in parts) {
            val part = restatementTrailingHi.replace(rawPart, "").trim(' ', '\t')
            val fact = restatementFact(part, row) ?: break
            if (fact in facts) break
            facts += fact
        }
        if (facts.isNotEmpty() && facts.size == parts.size && facts.none(String::isBlank)) {
            candidateTargets += target
        }
    }
    return candidateTargets.size == 1
}

private fun restatementFact(part: String, row: GroceryIntentRestatementRow): String? {
    groceryIntentWholeProperty(part)?.let { property ->
        val local = groceryIntentPropertiesIn(row.primary.text)
        val required = row.required.flatMap(::groceryIntentPropertiesIn).toSet()
        return if (property in local && property in required) "property:$property" else null
    }

    val count = restatementCount.matchEntire(part)
    if (count != null) {
        val amount = parseIntentCorrectionAmount(count.groupValues[1]) ?: return null
        val form = canonicalRestatementContainer(count.groupValues[2])
        val original = restatementContainerToken.findAll(row.primary.text)
            .map { canonicalRestatementContainer(it.value) }.toSet()
        return if (row.quantityUnit == "pack" && row.quantity != null &&
            amount.compareTo(row.quantity) == 0 && isPositiveInteger(amount) &&
            row.packaging != null && form == canonicalRestatementContainer(row.packaging) && original == setOf(form)
        ) "quantity" else null
    }

    val physical = restatementPhysical.matchEntire(part)
    if (physical != null) {
        val amount = parseIntentCorrectionAmount(physical.groupValues[1]) ?: return null
        val unit = physical.groupValues[2]
        val each = physical.groupValues[3].isNotEmpty()
        val expected = if (each) {
            row.packValue?.let { measureRestatement(it, row.packUnit) }
        } else {
            row.quantity?.let { measureRestatement(it, row.quantityUnit) }
        }
        val actual = measureRestatement(amount, unit)
        val kind = if (each) "pack_size" else "quantity"
        return if (amount.signum() > 0 && expected != null && actual != null &&
            actual.first == expected.first && actual.second.compareTo(expected.second) == 0) kind else null
    }

    val amount = parseIntentCorrectionAmount(part) ?: return null
    return if (row.quantityUnit == "count" && row.quantity != null &&
        amount.compareTo(row.quantity) == 0 && isPositiveInteger(amount)
    ) "quantity" else null
}

private fun measureRestatement(value: BigDecimal, rawUnit: String): Pair<String, BigDecimal>? = when (
    rawUnit.lowercase(Locale.ROOT)
) {
    "kg", "kgs", "kilo", "kilos", "kilogram", "kilograms", "keji" -> "g" to value.multiply(BigDecimal("1000"))
    "g", "gm", "gms", "gram", "grams" -> "g" to value
    "l", "ltr", "litre", "litres", "liter", "liters" -> "ml" to value.multiply(BigDecimal("1000"))
    "ml", "millilitre", "millilitres", "milliliter", "milliliters" -> "ml" to value
    else -> null
}

private fun canonicalRestatementContainer(raw: String): String {
    val token = raw.lowercase(Locale.ROOT).replace(" ", "").replace("-", "")
    return when (token) {
        "pack", "packs", "packet", "packets" -> "pack"
        "boxes" -> "box"
        "pouches" -> "pouch"
        "tetrapacks" -> "tetrapack"
        else -> token.removeSuffix("s")
    }
}

private fun isPositiveInteger(value: BigDecimal): Boolean =
    value.signum() > 0 && value.stripTrailingZeros().scale() <= 0

private fun asciiWords(text: String): List<String> = asciiWord.findAll(text.lowercase(Locale.ROOT)).map { it.value }.toList()

private fun validRestatementQuote(instruction: String, quote: GroceryIntentReviewV2Quote): Boolean {
    val start = quote.startUtf16
    val end = quote.endUtf16
    if (start < 0 || start >= end || end > instruction.length) return false
    if (start in 1 until instruction.length && instruction[start - 1].isHighSurrogate() && instruction[start].isLowSurrogate()) return false
    if (end in 1 until instruction.length && instruction[end - 1].isHighSurrogate() && instruction[end].isLowSurrogate()) return false
    return instruction.substring(start, end) == quote.text &&
        quote.text.firstOrNull()?.isWhitespace() != true && quote.text.lastOrNull()?.isWhitespace() != true
}

private val restatementAllowedCharacters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 \t,.'/-".toSet()
private val restatementWhitespace = Regex("[ \\t]+")
private val restatementTarget = Regex("[A-Za-z]+(?:[ '-][A-Za-z]+)*")
private val restatementAffirmation = Regex("(?i)\\bhi\\b|(?:^|[ \\t])same$|as[ \\t]+before$")
private val restatementConjunction = Regex("(?i)[ \\t]*,[ \\t]*|[ \\t]+(?:and|aur)[ \\t]+")
private val restatementTrailingHi = Regex("(?i)[ \\t]+hi$")
private val restatementCount = Regex("(.+?)[ \\t]+($restatementContainer)", RegexOption.IGNORE_CASE)
private val restatementPhysical = Regex("(.+?)[ \\t]*($restatementUnit)([ \\t]+each)?", RegexOption.IGNORE_CASE)
private val restatementContainerToken = Regex("(?i)(?<![A-Za-z0-9_])$restatementContainer(?![A-Za-z0-9_])")
private val asciiWord = Regex("[a-z]+")
