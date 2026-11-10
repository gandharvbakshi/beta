package com.example.beta

/**
 * Returns original instruction snippets that are neither cited verbatim by an item source nor
 * independently recognized neutral context or courtesy/connective words. This is a conservative lexical
 * coverage signal only: it does not identify products, quantities, corrections, or intent.
 * A model can still hide omissions by quoting the whole utterance as one source span; callers
 * must treat this as a review aid, never as semantic proof or a reason to skip combined review.
 */
internal fun uncoveredGroceryIntentText(instruction: String, sourceTexts: List<String>): List<String> =
    uncoveredGroceryIntentTokens(instruction, sourceTexts).map { it.value }

/** A validated half-open UTF-16 range in the original instruction. */
internal data class GroceryIntentCoverageSpan(val startUtf16: Int, val endUtf16: Int)

/** Span-based counterpart for callers that already validated source ownership. */
internal fun uncoveredGroceryIntentSegmentsBySpan(
    instruction: String,
    ownedSpans: List<GroceryIntentCoverageSpan>,
): List<String> = uncoveredGroceryIntentRangesBySpan(instruction, ownedSpans)
    .map { instruction.substring(it.startUtf16, it.endUtf16) }

/** Exact diagnostic ranges; callers must never use these to promote an unresolved item. */
internal fun uncoveredGroceryIntentRangesBySpan(
    instruction: String,
    ownedSpans: List<GroceryIntentCoverageSpan>,
): List<GroceryIntentCoverageSpan> {
    val ranges = mutableListOf<IntRange>()
    uncoveredGroceryIntentTokensBySpan(instruction, ownedSpans).forEach { token ->
        val previous = ranges.lastOrNull()
        if (previous != null && instruction.substring(previous.last + 1, token.range.first).none { it.isLetterOrDigit() }) {
            ranges[ranges.lastIndex] = previous.first..token.range.last
        } else ranges += token.range
    }
    return ranges.map { GroceryIntentCoverageSpan(it.first, it.last + 1) }
}

/** Keep separate uncited phrases separate, rather than assembling a new sentence from them. */
internal fun uncoveredGroceryIntentSegments(instruction: String, sourceTexts: List<String>): List<String> {
    val ranges = mutableListOf<IntRange>()
    uncoveredGroceryIntentTokens(instruction, sourceTexts).forEach { token ->
        val previous = ranges.lastOrNull()
        if (previous != null && instruction.substring(previous.last + 1, token.range.first).none { it.isLetterOrDigit() }) {
            ranges[ranges.lastIndex] = previous.first..token.range.last
        } else ranges += token.range
    }
    return ranges.map { instruction.substring(it) }
}

private fun uncoveredGroceryIntentTokens(instruction: String, sourceTexts: List<String>): List<MatchResult> {
    val spans = mutableListOf<GroceryIntentCoverageSpan>()
    sourceTexts.asSequence().filter(String::isNotEmpty).forEach { source ->
        var from = 0
        while (from <= instruction.length - source.length) {
            val start = instruction.indexOf(source, from)
            if (start < 0) break
            val end = start + source.length
            if (groceryQuoteBoundarySafe(instruction, source, start, end, requireOuterDelimiter = true))
                spans += GroceryIntentCoverageSpan(start, end)
            from = start + 1
        }
    }
    // Coverage is not quantity, identity, polarity or group-scope proof. Those
    // independent preparation gates still inspect the complete original text.
    return uncoveredGroceryIntentTokensBySpan(instruction,
        spans + neutralGroceryIntentCoverageSpans(instruction, sourceTexts))
}

/** The same independent neutral-context evidence for legacy and negotiated
 * context coverage. Model role labels are never inputs to this compiler. */
internal fun neutralGroceryIntentCoverageSpans(instruction: String, sourceTexts: List<String>): List<GroceryIntentCoverageSpan> {
    // Share V2's ORIGINAL-context compiler, not another phrase list. Every
    // primary must first have one exact, non-overlapping occurrence; malformed
    // or ambiguous quotes cannot authorize extra coverage around the list.
    val primaries = sourceTexts.mapNotNull { source ->
        bindGroceryIntentProductSource(instruction, source, maxLength = 6000)
            ?.let { GroceryIntentCoverageSpan(it.startUtf16, it.endUtf16) }
    }.sortedBy { it.startUtf16 }
    return if (primaries.size in 1..50 && primaries.size == sourceTexts.size &&
        primaries.zipWithNext().none { (left, right) -> left.endUtf16 > right.startUtf16 }) {
        neutralGroceryIntentContextSpans(instruction, primaries).filter { neutral ->
            primaries.none { it.startUtf16 < neutral.endUtf16 && neutral.startUtf16 < it.endUtf16 }
        }
    } else emptyList()
}

private fun uncoveredGroceryIntentTokensBySpan(
    instruction: String,
    ownedSpans: List<GroceryIntentCoverageSpan>,
): List<MatchResult> {
    if (instruction.isEmpty()) return emptyList()

    // A token needs a single complete owner, not the union of unrelated fragments.
    val ownedThrough = IntArray(instruction.length)
    ownedSpans.forEach { span ->
        require(span.startUtf16 in 0..instruction.length && span.endUtf16 in span.startUtf16..instruction.length)
        for (index in span.startUtf16 until span.endUtf16) ownedThrough[index] = maxOf(ownedThrough[index], span.endUtf16)
    }

    // Ambiguous words such as Hindi "do" (two) and "ek" (one) are NEVER standalone
    // stopwords. Only a complete, unmistakable ordering courtesy may cover them.
    // Keep these matches on the original text: joining residual words could turn
    // "kar [quoted product] do" into a command and silently lose a quantity.
    val orderingPhrases = listOf(
        "cart\\s+mein\\s+(?:(?:ye|yeh)\\s+(?:sab\\s+)?)?(?:daal\\s+do|daalo|kar\\s+do)",
        "(?:to|in)\\s+(?:my|the)\\s+cart",
        "(?:kar\\s+do|kar\\s+dena|ek\\s+kaam\\s+karo|karo\\s+na)",
        "last\\s+mein",
        "(?:bhi\\s+)?le\\s+aao",
        "la\\s+do",
        "bas\\s+itna",
    )
    orderingPhrases.forEach { phrase ->
        Regex("(?<![\\p{L}\\p{M}\\p{N}])(?:$phrase)(?:\\s+na)?(?![\\p{L}\\p{M}\\p{N}])", RegexOption.IGNORE_CASE)
            .findAll(instruction).forEach { match ->
                if (match.range.none { ownedThrough[it] != 0 }) match.range.forEach { ownedThrough[it] = match.range.last + 1 }
            }
    }

    val courtesyAndConnectors = setOf(
        "please", "get", "bring", "order", "add", "i", "want", "need", "and", "also",
        "aur", "mujhe", "chahiye", "lao", "de",
        "can", "could", "would", "you", "like", "me", "for", "today", "thanks", "thank",
        "then", "phir", "uh", "um", "umm", "hmm", "oh", "hi", "hello", "okay", "ok",
        "haan", "ji", "beta", "karo", "finally", "lastly", "plus",
    )
    val tokenPattern = Regex("[\\p{L}\\p{M}\\p{N}\\u200C\\u200D]+")
    return tokenPattern.findAll(instruction)
        .filter { match ->
            ownedThrough[match.range.first] <= match.range.last &&
                match.value.lowercase() !in courtesyAndConnectors
        }
        .toList()
}
