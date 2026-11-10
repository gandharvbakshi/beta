package com.example.beta

import java.text.Normalizer
import java.util.Locale

/** Validates source-only condition receipts; a receipt never authorizes its blocked target. */
internal fun validateExcludedContexts(
    readyRows: List<GroceryIntentReviewV2ReadyRow>,
    blockedRows: List<GroceryIntentReviewV2BlockedRow>,
    groups: List<GroceryIntentReviewV2Group>,
    sourceCount: Int,
    instruction: String,
) {
    val receipts = blockedRows.flatMap { row -> row.excludedContext.map { row.rowIndex to it } }
    if (receipts.isEmpty()) return

    val primaries = (readyRows.map { it.rowIndex to it.evidence.primary } +
        blockedRows.mapNotNull { row -> row.source?.let { row.rowIndex to it } }).sortedBy { it.first }
    require(sourceCount > 0 && primaries.size == sourceCount)
    require(primaries.map { it.first } == (0 until sourceCount).toList())
    require(primaries.indices.all { left -> (left + 1 until primaries.size).all { right ->
        !primaries[left].second.overlaps(primaries[right].second)
    } })

    val corrections = readyRows.flatMap { row -> row.corrections.map { it.source } }
    val supports = readyRows.flatMap { row -> row.evidence.supporting.map { it.quote } }
    val groupSources = groups.map { it.source }
    val otherOwned = supports + corrections + groupSources
    require(!hasUnsupportedExcludedHeaderScope(instruction, primaries.map { it.second }, corrections, groups))
    require(receipts.indices.all { left -> (left + 1 until receipts.size).all { right ->
        !receipts[left].second.overlaps(receipts[right].second)
    } })

    receipts.forEach { (targetIndex, quote) ->
        require(targetIndex in blockedRows.map { it.rowIndex })
        require(uniqueExactQuoteInInstruction(instruction, quote))
        require(primaries.none { it.second.overlaps(quote) })
        require(otherOwned.none { it.overlaps(quote) })
        require(validateScopedExcludedCondition(instruction, quote, targetIndex, primaries.map { it.second }))
    }
    // A new local exclusion must not turn unknown global purpose/diet wording
    // into generic acknowledgement authority for its surviving neighbours.
    val owned = primaries.map { it.second } + otherOwned + receipts.map { it.second }
    require(groceryIntentOwnedContextComplete(instruction, owned, primaries.map { it.second }, corrections))
}

/** Shared original-span context proof. Unknown wording cannot become mere consent. */
internal fun groceryIntentOwnedContextComplete(
    instruction: String,
    owned: List<GroceryIntentReviewV2Quote>,
    primaries: List<GroceryIntentReviewV2Quote>,
    corrections: List<GroceryIntentReviewV2Quote> = emptyList(),
): Boolean {
    val primaryCoverage = primaries.map { GroceryIntentCoverageSpan(it.startUtf16, it.endUtf16) }
    val correctionCoverage = corrections.map { GroceryIntentCoverageSpan(it.startUtf16, it.endUtf16) }
    val neutral = (neutralGroceryIntentContextSpans(instruction, primaryCoverage) +
        correctionGroceryIntentDiscourseSpans(instruction, correctionCoverage, primaryCoverage)).filter { context ->
        owned.none { it.startUtf16 < context.endUtf16 && context.startUtf16 < it.endUtf16 }
    }
    val remaining = instruction.toCharArray()
    neutral.forEach { for (index in it.startUtf16 until it.endUtf16) remaining[index] = ' ' }
    owned.forEach { for (index in it.startUtf16 until it.endUtf16) remaining[index] = ' ' }
    val words = Regex("[\\p{L}\\p{N}]+").findAll(String(remaining).lowercase(Locale.ROOT))
    return words.all { it.value in EXCLUDED_CONTEXT_FILLERS }
}

private val EXCLUDED_CONTEXT_FILLERS =
    "please thanks thank you and also plus add get buy order cart my list i want need".split(" ").toSet()

private fun hasUnsupportedExcludedHeaderScope(
    instruction: String,
    primaries: List<GroceryIntentReviewV2Quote>,
    corrections: List<GroceryIntentReviewV2Quote>,
    groups: List<GroceryIntentReviewV2Group>,
): Boolean {
    val primaryCoverage = primaries.map { GroceryIntentCoverageSpan(it.startUtf16, it.endUtf16) }
    val correctionCoverage = corrections.map { GroceryIntentCoverageSpan(it.startUtf16, it.endUtf16) }
    val allowed = neutralGroceryIntentContextSpans(instruction, primaryCoverage) +
        correctionGroceryIntentDiscourseSpans(instruction, correctionCoverage, primaryCoverage)
    val markers = Regex("[:\\u2014\\u2013\\u2012\\uff1a]|(?<=\\s)-(?=\\s)").findAll(instruction)
    return markers.any { marker ->
        val before = instruction.substring(0, marker.range.first)
        val after = instruction.substring(marker.range.last + 1)
        Regex("[\\p{L}\\p{N}_]").containsMatchIn(before) && Regex("[\\p{L}\\p{N}_]").containsMatchIn(after) &&
            groups.none { marker.range.first >= it.source.startUtf16 && marker.range.last + 1 <= it.source.endUtf16 } &&
            allowed.none { marker.range.first >= it.startUtf16 && marker.range.last + 1 <= it.endUtf16 }
    }
}

private fun validateScopedExcludedCondition(
    instruction: String,
    quote: GroceryIntentReviewV2Quote,
    targetIndex: Int,
    primaries: List<GroceryIntentReviewV2Quote>,
): Boolean {
    if (targetIndex !in primaries.indices || primaries.isEmpty()) return false
    if (quote.text.length > 180 || quote.startUtf16 < primaries[targetIndex].endUtf16) return false
    if (quote.text.any { it.code > 0x7f }) return false
    val before = instruction.substring(0, quote.startUtf16).trimEnd(' ', '\t')
    val after = instruction.substring(quote.endUtf16).trimStart(' ', '\t')
    if (before.isNotEmpty() && before.last() !in ".!?;\n\r") return false
    if (after.isNotEmpty() && after.first() !in ".!?;\n\r" && !quote.text.endsWith(".") && !quote.text.endsWith("!")) return false

    val match = EXCLUDED_CONDITION.matchEntire(quote.text) ?: return false
    val target = match.groupValues[1]
    val words = excludedTargetWords(target)
    val anchors = excludedTargetAnchors(target)
    if (words.isEmpty() || words.any { it in EXCLUDED_SCOPE_WORDS } || anchors.isEmpty()) return false
    if (!words.all { it in excludedTargetWords(primaries[targetIndex].text) }) return false
    val primaryWords = Regex("[a-z]+")
        .findAll(primaries[targetIndex].text.lowercase(Locale.ROOT))
        .map { it.value }
        .toList()
    if (anchors != excludedTargetAnchors(primaries[targetIndex].text) || primaryWords.isEmpty() ||
        (EXCLUDED_PLURALS[primaryWords.last()] ?: primaryWords.last()) !in words
    ) return false
    val matches = primaries.indices.filter { index ->
        val primaryAnchors = excludedTargetAnchors(primaries[index].text)
        anchors.all { it in primaryAnchors }
    }
    return matches == listOf(targetIndex)
}

private val EXCLUDED_CONDITION = Regex(
    "(?:please\\s+)?choose\\s+(?:the\\s+)?(?:unscented|scented|organic|unsalted|salted|unsweetened|sweetened|" +
        "sugar[ -]*free|gluten[ -]*free|lactose[ -]*free|low[ -]*fat)\\s+" +
        "([A-Za-z]+(?:[ '-]+[A-Za-z]+)*)\\s+if\\s+available[.!]?",
    RegexOption.IGNORE_CASE,
)
private val EXCLUDED_SCOPE_WORDS = setOf(
    "and", "or", "aur", "ya", "for", "with", "without", "no", "not", "all", "any", "every", "everything", "each", "other",
    "only", "except", "unless", "instead", "if", "then", "otherwise", "both", "either", "neither",
)
private val EXCLUDED_PROTECTED_PHRASES = Regex(
    "\\b(?:no[\\s-]+added[\\s-]+(?:sugar|salt)|sugar[\\s-]*free|zero[\\s-]+sugar|" +
        "low[\\s-]+(?:fat|sugar)|high[\\s-]+(?:fibre|fiber|protein)|gluten[\\s-]*free|" +
        "lactose[\\s-]*free|pulp[\\s-]*free|ready[\\s-]+(?:made|to[\\s-]+(?:eat|drink|cook)))\\b",
    RegexOption.IGNORE_CASE,
)
private val EXCLUDED_IGNORED_ANCHORS = setOf(
    "a", "an", "the", "and", "or", "of", "for", "with", "without", "in", "on", "to", "from", "please", "get", "buy", "order", "add",
    "make", "keep", "only", "just", "that", "this", "these", "those", "it", "is", "be", "are", "should", "must", "not", "no",
    "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "dozen", "half", "quarter",
    "ek", "do", "teen", "char", "chaar", "paanch", "panch", "chhe", "saat", "aath", "nau", "das", "dedh", "dhai", "aadha", "adha",
    "paav", "kilo", "kg", "g", "gm", "gram", "grams", "ml", "litre", "litres", "liter", "liters", "l", "pack", "packs", "packet", "packets",
    "bag", "bags", "bottle", "bottles", "carton", "cartons", "cup", "cups", "can", "cans", "tin", "tins", "tub", "tubs", "jar", "jars",
    "box", "boxes", "bar", "bars", "block", "blocks", "loaf", "loaves", "bunch", "bunches", "roll", "rolls", "refill", "refills",
    "pouch", "pouches", "sachet", "sachets", "tube", "tubes", "spray", "sprays", "piece", "pieces", "pcs", "count", "small", "large",
    "medium", "big", "little", "plain", "unsalted", "salted", "unsweetened", "sweetened", "organic", "certified", "spicy", "sweet", "vegan",
    "vegetarian", "nonvegetarian", "full", "fat", "free", "fresh", "each", "instead", "sorry",
)
private val EXCLUDED_PLURALS = mapOf(
    "yoghurts" to "yogurt", "yoghurt" to "yogurt", "yogurts" to "yogurt", "eggs" to "egg", "bananas" to "banana", "apples" to "apple",
    "oranges" to "orange", "tomatoes" to "tomato", "onions" to "onion", "potatoes" to "potato", "lemons" to "lemon",
    "biscuits" to "biscuit", "chocolates" to "chocolate", "lentils" to "lentil", "samosas" to "samosa", "strawberries" to "strawberry",
    "blueberries" to "blueberry", "mangoes" to "mango",
)

private fun excludedTargetWords(text: String): Set<String> = Regex("[a-z]+")
    .findAll(text.lowercase(Locale.ROOT))
    .map { EXCLUDED_PLURALS[it.value] ?: it.value }
    .toSet()

private fun excludedTargetAnchors(text: String): Set<String> {
    val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    val withoutProtected = EXCLUDED_PROTECTED_PHRASES.replace(normalized, " ")
    return Regex("[\\p{L}]+")
        .findAll(withoutProtected)
        .map { EXCLUDED_PLURALS[it.value] ?: it.value }
        .filter { it !in EXCLUDED_IGNORED_ANCHORS }
        .toSet()
}
