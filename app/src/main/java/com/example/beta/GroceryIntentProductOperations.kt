package com.example.beta

import java.util.Locale

/** Source-compiled draft edit receipt. It grants coverage only; it never mutates either item. */
internal data class GroceryIntentProductOperation(
    val kind: String,
    val targetRowIndex: Int,
    val replacementRowIndex: Int,
    val source: GroceryIntentReviewV2Quote,
    val targetMention: GroceryIntentReviewV2Quote,
    /** Independently derived non-item fragments owned by this operation. */
    val coverageSources: List<GroceryIntentReviewV2Quote>,
) {
    fun payloadEquals(other: GroceryIntentProductOperation): Boolean =
        kind == other.kind && targetRowIndex == other.targetRowIndex &&
            replacementRowIndex == other.replacementRowIndex && source == other.source &&
            targetMention == other.targetMention

    companion object
}

/** Kotlin port of grocery_intent_product_operations.compile_product_replacements. */
internal fun compileGroceryIntentProductReplacements(
    instruction: String,
    items: List<GroceryIntentReviewV2Item>,
): List<GroceryIntentProductOperation> {
    if (items.isEmpty() || items.size > 50 || instruction.isEmpty() || '\u0000' in instruction) return emptyList()
    if (items.any { it.supportingText.isNotEmpty() || it.needsClarification }) return emptyList()

    val sources = items.map { bindUniqueSourceQuote(instruction, it.sourceText) ?: return emptyList() }
    if (sources.indices.any { i -> (i + 1 until sources.size).any { j -> sources[i].overlaps(sources[j]) } }) {
        return emptyList()
    }

    val boundaries = buildList {
        add(0)
        productOperationBoundary.findAll(instruction).forEach { add(it.range.last + 1) }
    }
    val operations = mutableListOf<GroceryIntentProductOperation>()
    for (boundaryIndex in boundaries.indices) {
        var clauseStart = boundaries[boundaryIndex]
        var clauseEnd = boundaries.getOrNull(boundaryIndex + 1) ?: instruction.length
        while (clauseStart < clauseEnd && instruction[clauseStart].isWhitespace()) clauseStart++
        while (clauseEnd > clauseStart && instruction[clauseEnd - 1] in " \t\r\n.;!?") clauseEnd--
        val members = sources.indices.filter { index ->
            clauseStart <= sources[index].startUtf16 && sources[index].endUtf16 <= clauseEnd
        }
        if (members.size != 1) continue

        val replacementIndex = members.single()
        val replacementSource = sources[replacementIndex]
        val prefix = instruction.substring(clauseStart, replacementSource.startUtf16)
        val suffix = instruction.substring(replacementSource.endUtf16, clauseEnd)
        val scaffold = prefix + '\u0000' + suffix
        if (scaffold.any { it.code > 127 } || unsupportedOperationScaffold.containsMatchIn(scaffold)) continue
        val matches = operationPatterns.mapNotNull { pattern -> pattern.matchEntire(scaffold) }
        if (matches.size != 1) continue
        val match = matches.single()
        val target = match.groups["target"]?.value.orEmpty()
        val targetWords = operationWords(target)
        if (targetWords.isEmpty()) continue

        val targetIndices = items.indices.filter { index ->
            val oldWords = operationWords(items[index].name)
            sources[index].endUtf16 <= clauseStart && oldWords.size >= targetWords.size &&
                oldWords.takeLast(targetWords.size) == targetWords
        }
        if (targetIndices.size != 1) continue
        val targetIndex = targetIndices.single()
        val old = items[targetIndex]
        val replacement = items[replacementIndex]
        if (old.quantity != null && replacement.quantity == null) continue
        if (old.packValue != null && replacement.packValue == null) continue
        if (!old.packaging.isNullOrEmpty() && old.packaging != replacement.packaging) continue
        if (!old.required.all { it in replacement.required } || !old.excluded.all { it in replacement.excluded }) continue

        val retainedWords = operationWords(old.name).dropLast(targetWords.size).toSet()
        if (!operationWords(replacementSource.text).toSet().containsAll(retainedWords)) continue

        var targetStart = match.groups["target"]!!.range.first
        var targetEnd = match.groups["target"]!!.range.last + 1
        if (targetStart > prefix.length) {
            val shift = replacementSource.text.length - 1
            targetStart += shift
            targetEnd += shift
        }
        val operationStart = clauseStart
        val operationEnd = clauseEnd
        val targetQuote = quoteAt(instruction, operationStart + targetStart, operationStart + targetEnd)
            ?: continue
        val coverage = listOf(
            clauseStart to replacementSource.startUtf16,
            replacementSource.endUtf16 to clauseEnd,
        ).filter { it.first < it.second }.mapNotNull { (start, end) -> quoteAt(instruction, start, end) }
        if (coverage.size != listOf(
                clauseStart to replacementSource.startUtf16,
                replacementSource.endUtf16 to clauseEnd,
            ).count { it.first < it.second }) continue
        operations += GroceryIntentProductOperation(
            kind = "replace",
            targetRowIndex = targetIndex,
            replacementRowIndex = replacementIndex,
            source = quoteAt(instruction, operationStart, operationEnd) ?: continue,
            targetMention = targetQuote,
            coverageSources = coverage,
        )
    }

    val usedRows = operations.flatMap { listOf(it.targetRowIndex, it.replacementRowIndex) }
    if (usedRows.size != usedRows.toSet().size) return emptyList()
    return operations
}

internal fun bindGroceryIntentProductSource(
    instruction: String,
    sourceText: String,
    maxLength: Int = 320,
): GroceryIntentReviewV2Quote? = bindUniqueSourceQuote(instruction, sourceText, maxLength)

/** Shared terminal boundary for source roles that cannot borrow a following nominal or size. */
internal fun groceryIntentSourceClauseEndProven(instruction: String, source: String, terminalEnd: Int): Boolean {
    if (terminalEnd !in 1..source.length) return false
    val start = instruction.indexOf(source)
    if (start < 0 || instruction.indexOf(source, start + 1) >= 0 ||
        bindGroceryIntentProductSource(instruction, source, maxLength = 6000)?.startUtf16 != start) return false
    // Only terminal punctuation may remain INSIDE a fully consumed quote.
    // An internal list separator cannot hide an unowned nominal or quantity.
    if (!Regex("[ \\t]*[,.;!?]?[ \\t]*").matches(source.substring(terminalEnd))) return false
    val tail = instruction.substring(start + terminalEnd).trimStart(' ', '\t')
    if (tail.isEmpty() || sourceClauseEndPattern.containsMatchIn(tail)) return true
    // Reuse complete neutral ORIGINAL spans; no new word/phrase allowlist.
    val ownedEnd = start + source.length
    val spans = neutralGroceryIntentContextSpans(instruction,
        listOf(GroceryIntentCoverageSpan(start, ownedEnd))).distinct()
    if (spans.any { it.startUtf16 < ownedEnd && ownedEnd < it.endUtf16 }) return false
    var cursor = ownedEnd
    for (span in spans) {
        if (span.endUtf16 <= ownedEnd) continue
        if (span.startUtf16 < cursor || span.endUtf16 <= span.startUtf16 || span.endUtf16 > instruction.length ||
            instruction.substring(cursor, span.startUtf16).any { it !in " \t" }) return false
        cursor = span.endUtf16
    }
    return instruction.substring(cursor).all { it in " \t" }
}

private val sourceClauseEndPattern =
    Regex("^(?:[,.;!?\\r\\n]|(?:and|aur)(?=$|[ \\t,.;!?\\r\\n]))", RegexOption.IGNORE_CASE)

/** Both edges belong to the original instruction. Another model row cannot
 * supply a missing delimiter or hide a negation/qualifier before this quote. */
internal fun groceryIntentSourceClauseContextProven(instruction: String, source: String, terminalEnd: Int): Boolean {
    if (!groceryIntentSourceClauseEndProven(instruction, source, terminalEnd)) return false
    val start = instruction.indexOf(source)
    val prefix = instruction.substring(0, start)
    val boundary = Regex("[,.;!?\\r\\n]|(?<![\\p{L}\\p{N}_])(?:and|aur)(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)
        .findAll(prefix).lastOrNull()?.range?.last?.plus(1) ?: 0
    val localStart = start - boundary
    if (prefix.substring(boundary).all { it in " \t" }) return true
    // Preserve the original suffix: a paired request may need it to prove the
    // prefix. The item owns only its exact quote, never that following context.
    val local = instruction.substring(boundary)
    val neutral = neutralGroceryIntentContextSpans(local,
        listOf(GroceryIntentCoverageSpan(localStart, localStart + source.length)))
    return (0 until localStart).all { position -> local[position] in " \t" ||
        neutral.any { it.startUtf16 <= position && position < it.endUtf16 && it.endUtf16 <= localStart } }
}

/** A local clause boundary does not prove whole-list scope. Parser clauses
 * include conditions and detached properties, not only products. Reuse the
 * shared denials before treating those clauses as coverage; never borrow their
 * constraints into another row or let a comma silently discard them. */
internal fun groceryIntentParserClauseScopeProven(
    instruction: String, clauses: List<String>, representedClauses: List<String> = clauses,
): Boolean {
    if (clauses.isEmpty() || clauses.size > 50 || clauses.any { clause ->
            val identity = groceryIntentFragmentIdentityTokens(clause)
            intentV2HasCorrectionCue(clause) || groceryIntentHasNominalClausePredicate(clause) ||
                groceryIntentWholeProperty(clause) != null || propertyHasUnprovedNegation(clause) ||
                groceryIntentUncertainScope.containsMatchIn(clause) ||
                groceryIntentPurchaseScopeContextRanges(clause).isNotEmpty() ||
                (groceryIntentContainsNumericAtom(clause) && identity.isEmpty()) ||
                (identity.isNotEmpty() && identity.all(::sourceNominalHasRelation))
        } || !clauses.containsAll(representedClauses)) return false
    val quotes = clauses.map { bindGroceryIntentProductSource(instruction, it, maxLength = 6000) ?: return false }
        .sortedBy { it.startUtf16 }
    if (quotes.zipWithNext().any { (left, right) -> left.endUtf16 > right.startUtf16 }) return false
    // Input coverage is not output conservation. Only emitted rows own their
    // clauses; discarded amounts must not disappear behind successful parsing.
    val represented = quotes.filter { it.text in representedClauses }
    return groceryIntentOwnedContextComplete(instruction, represented, represented)
}

/** Fail closed before legacy parsing, which cannot preserve a replacement receipt. */
internal fun hasExplicitGroceryIntentProductOperationCue(instruction: String): Boolean =
    productOperationCue.containsMatchIn(instruction)

private fun bindUniqueSourceQuote(instruction: String, source: String, maxLength: Int = 320): GroceryIntentReviewV2Quote? {
    if (maxLength !in 1..6000 || instruction.length > 6000 || instruction.any { it.isISOControl() && it !in "\n\r\t" } ||
        source.isBlank() || source.codePointCount(0, source.length) > maxLength ||
        source.any { it.isISOControl() && it !in "\n\r\t" }) return null
    val positions = mutableListOf<Int>()
    var from = 0
    while (from <= instruction.length - source.length) {
        val start = instruction.indexOf(source, from)
        if (start < 0) break
        val end = start + source.length
        if (groceryQuoteBoundarySafe(instruction, source, start, end)) positions += start
        from = start + 1
    }
    return positions.singleOrNull()?.let { quoteAt(instruction, it, it + source.length) }
}

/** Caller has located a nonempty exact substring at [start, end). */
internal fun groceryQuoteBoundarySafe(text: String, quote: String, start: Int, end: Int, requireOuterDelimiter: Boolean = false): Boolean {
    if (((requireOuterDelimiter || isWordCodePoint(Character.codePointAt(quote, 0))) && start > 0 &&
            isWordCodePoint(Character.codePointBefore(text, start))) ||
        ((requireOuterDelimiter || isWordCodePoint(Character.codePointBefore(quote, quote.length))) && end < text.length &&
            isWordCodePoint(Character.codePointAt(text, end)))) return false
    if (Character.isDigit(Character.codePointAt(quote, 0)) && start > 0) {
        val previous = Character.codePointBefore(text, start)
        if (previous in charArrayOf('.', '\u066b', '\uff0e', '+', '-', '\u2212', '\uff0b', '\uff0d').map { it.code } ||
            previous in charArrayOf(',', '/', '\u066c', '\uff0c', '\u2044', '\u2215').map { it.code } && start > 1 &&
            Character.isDigit(Character.codePointBefore(text, start - Character.charCount(previous)))) return false
    }
    if (Character.isDigit(Character.codePointBefore(quote, quote.length)) && end + 1 < text.length) {
        val next = Character.codePointAt(text, end)
        val afterNext = end + Character.charCount(next)
        if (next in charArrayOf(',', '.', '/', '\u066b', '\u066c', '\uff0e', '\uff0c', '\u2044', '\u2215').map { it.code } && afterNext < text.length &&
            Character.isDigit(Character.codePointAt(text, afterNext))) return false
    }
    return true
}

private fun isWordCodePoint(codePoint: Int): Boolean = codePoint in 0x200C..0x200D || codePoint == '_'.code || when (Character.getType(codePoint)) {
    Character.UPPERCASE_LETTER.toInt(), Character.LOWERCASE_LETTER.toInt(),
    Character.TITLECASE_LETTER.toInt(), Character.MODIFIER_LETTER.toInt(),
    Character.OTHER_LETTER.toInt(), Character.NON_SPACING_MARK.toInt(),
    Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt(),
    Character.DECIMAL_DIGIT_NUMBER.toInt(), Character.LETTER_NUMBER.toInt(),
    Character.OTHER_NUMBER.toInt() -> true
    else -> false
}

private fun quoteAt(text: String, start: Int, end: Int): GroceryIntentReviewV2Quote? =
    if (start < 0 || end <= start || end > text.length ||
        start in 1 until text.length && text[start - 1].isHighSurrogate() && text[start].isLowSurrogate() ||
        end in 1 until text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) null
    else GroceryIntentReviewV2Quote(text.substring(start, end), start, end)

private fun operationWords(text: String): List<String> = Regex("[a-z0-9]+")
    .findAll(text.lowercase(Locale.ROOT)).map { it.value }.toList()

private val productOperationBoundary = Regex("[;!?\\r\\n]|\\.(?=\\s|$)")
private val unsupportedOperationScaffold = Regex(
    "\\b(?:if|agar|unless|available|mile|chalega|bhi|or|ya|either|half|aadha)\\b",
    RegexOption.IGNORE_CASE,
)
private val productOperationCue = Regex(
    "\\b(?:replace|swap|instead|badal(?:\\s+do)?)\\b|\\brather\\s+than\\b|" +
        "\\bchange\\b[^;.!?]*\\bto\\b|\\b(?:ki\\s+jagah|ke\\s+badle|ke\\s+bajay|uski\\s+jagah)\\b",
    RegexOption.IGNORE_CASE,
)
private val operationPatterns = listOf(
    Regex("(?:(?:actually|please)\\s*,?\\s*)?replace\\s+(?:the\\s+)?(?<target>[A-Za-z][A-Za-z '\\-]{0,99}?)\\s+(?:with|by)\\s+\\u0000", RegexOption.IGNORE_CASE),
    Regex("(?:(?:actually|please)\\s*,?\\s*)?\\u0000\\s+instead\\s+of\\s+(?:the\\s+)?(?<target>[A-Za-z][A-Za-z '\\-]{0,99}?)", RegexOption.IGNORE_CASE),
    Regex("(?:(?:actually|please)\\s*,?\\s*)?instead\\s+of\\s+(?:the\\s+)?(?<target>[A-Za-z][A-Za-z '\\-]{0,99}?)\\s*,?\\s+\\u0000", RegexOption.IGNORE_CASE),
    Regex("(?:(?:actually|please)\\s*,?\\s*)?(?<target>[A-Za-z][A-Za-z '\\-]{0,99}?)\\s+(?:ki\\s+jagah|ke\\s+badle|ke\\s+bajay)\\s+\\u0000(?:\\s+(?:le\\s+lena|chahiye))?", RegexOption.IGNORE_CASE),
    Regex("(?:(?:actually|please)\\s*,?\\s*)?uski\\s+jagah\\s+\\u0000(?:\\s+(?:le\\s+lena|chahiye))?\\s*,\\s*(?<target>[A-Za-z][A-Za-z '\\-]{0,99}?)\\s+nahi", RegexOption.IGNORE_CASE),
)
