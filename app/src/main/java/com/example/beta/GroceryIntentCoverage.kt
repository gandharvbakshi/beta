package com.example.beta

/**
 * Returns original instruction snippets that are neither cited verbatim by an item source nor
 * one of a deliberately small set of courtesy/connective words. This is a conservative lexical
 * coverage signal only: it does not identify products, quantities, corrections, or intent.
 * A model can still hide omissions by quoting the whole utterance as one source span; callers
 * must treat this as a review aid, never as semantic proof or a reason to skip combined review.
 */
internal fun uncoveredGroceryIntentText(instruction: String, sourceTexts: List<String>): List<String> =
    uncoveredGroceryIntentTokens(instruction, sourceTexts).map { it.value }

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
    if (instruction.isEmpty()) return emptyList()

    val covered = BooleanArray(instruction.length)
    sourceTexts.asSequence().filter(String::isNotEmpty).forEach { source ->
        var from = 0
        while (from <= instruction.length - source.length) {
            val start = instruction.indexOf(source, from)
            if (start < 0) break
            val end = start + source.length
            val leftBoundary = start == 0 || !instruction[start - 1].isLetterOrDigit()
            val rightBoundary = end == instruction.length || !instruction[end].isLetterOrDigit()
            if (leftBoundary && rightBoundary) {
                for (index in start until end) covered[index] = true
            }
            from = start + 1
        }
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
    )
    orderingPhrases.forEach { phrase ->
        Regex("(?<![\\p{L}\\p{M}\\p{N}])(?:$phrase)(?:\\s+na)?(?![\\p{L}\\p{M}\\p{N}])", RegexOption.IGNORE_CASE)
            .findAll(instruction).forEach { match ->
                if (match.range.none { covered[it] }) match.range.forEach { covered[it] = true }
            }
    }

    val courtesyAndConnectors = setOf(
        "please", "get", "bring", "order", "add", "i", "want", "need", "and", "also",
        "aur", "mujhe", "chahiye", "lao", "de",
        "can", "could", "would", "you", "like", "me", "for", "today", "thanks", "thank",
        "then", "phir", "uh", "um", "umm", "hmm", "oh", "hi", "hello", "okay", "ok",
        "haan", "ji", "beta", "karo", "finally", "lastly", "plus",
    )
    val tokenPattern = Regex("[\\p{L}\\p{M}\\p{N}]+")
    return tokenPattern.findAll(instruction)
        .filter { match ->
            match.range.none { covered[it] } &&
                match.value.lowercase() !in courtesyAndConnectors
        }
        .toList()
}
