package com.example.beta.automation

import java.util.Locale

sealed class Quantity {
    data object Default : Quantity()
    data class Count(val n: Int) : Quantity()
    data class Weight(val grams: Int) : Quantity()
}

data class ParsedItem(
    val rawText: String,
    val query: String,
    val quantity: Quantity = Quantity.Default,
    val parserConfidence: Float = 1.0f
)

fun Quantity.requestedCount(): Int = when (this) {
    is Quantity.Count -> n.coerceAtLeast(1)
    else -> 1
}

fun ParsedItem.backendInputText(): String {
    val count = quantity.requestedCount()
    return if (count > 1) "$count $query" else query
}

object InstructionParser {
    private val leadingCommandRegex = Regex(
        "^(?:\\s*(?:please\\s+|kindly\\s+)?(?:get\\s+me|pick\\s+up|order|buy|add|get|fetch|bring)\\b[\\s,]*)+",
        RegexOption.IGNORE_CASE
    )
    private val splitterRegex = Regex("\\s*(?:[,;]+|\\s+&\\s+|\\s+and\\s+|\\s+plus\\s+)\\s*", RegexOption.IGNORE_CASE)
    private val noisySplitterRegex = Regex("\\s+(?:&|and|plus)\\s+", RegexOption.IGNORE_CASE)
    private val leadingNoiseRegex = Regex(
        "^(?:(?:and|then|also|plus|with|some|a|an|the|maybe|perhaps|please|kindly|of|for\\s+me|for|me|\\d+)\\b\\s*)+",
        RegexOption.IGNORE_CASE
    )
    private val trailingNoiseRegex = Regex(
        "(?:\\s*\\b(?:and|then|also|plus|with|some|a|an|the|maybe|perhaps|please|kindly|of|for\\s+me|for|me|\\d+)\\b)+$",
        RegexOption.IGNORE_CASE
    )
    private val leadingFillerRegex = Regex(
        "^(?:(?:and|then|also|plus|with|some|a|an|the|maybe|perhaps|please|kindly|of|for\\s+me|for|me)\\b\\s*)+",
        RegexOption.IGNORE_CASE
    )
    private val leadingCountRegex = Regex("^([1-9]\\d?)\\s+(.+)$")
    private val noOpRegex = Regex("^(?:i\\s+want\\s+)?(?:nothing|none|no\\s+items?)$", RegexOption.IGNORE_CASE)

    fun parse(input: String): List<ParsedItem> {
        val normalized = input.trim()
        if (normalized.isEmpty()) return emptyList()

        val withoutPrefix = stripLeadingCommands(normalized)

        if (withoutPrefix.isEmpty()) return emptyList()
        if (noOpRegex.matches(withoutPrefix.lowercase(Locale.US))) return emptyList()

        val seenQueries = linkedSetOf<String>()
        val hasNoisySplitters = noisySplitterRegex.containsMatchIn(withoutPrefix)
        val parsedItems = mutableListOf<ParsedItem>()

        splitterRegex.split(withoutPrefix)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { segment ->
                val (withoutQuantity, quantity) = extractQuantityPrefix(segment)
                val cleaned = cleanSegment(withoutQuantity)
                if (cleaned.isEmpty() || noOpRegex.matches(cleaned.lowercase(Locale.US))) {
                    return@forEach
                }

                val expanded = expandKnownProductSequence(cleaned)
                val confidence = when {
                    expanded.size > 1 -> 0.70f
                    hasNoisySplitters || !cleaned.equals(segment.trim(), ignoreCase = false) -> 0.85f
                    else -> 1.0f
                }

                expanded.forEach { item ->
                    val query = item.lowercase(Locale.US)
                    if (query.isNotBlank() && seenQueries.add(query)) {
                        val itemQuantity = if (expanded.size == 1) quantity else Quantity.Default
                        parsedItems.add(
                            ParsedItem(
                                rawText = item,
                                query = query,
                                quantity = itemQuantity,
                                parserConfidence = confidence
                            )
                        )
                    }
                }
            }

        return parsedItems
    }

    private fun stripLeadingCommands(input: String): String {
        var text = input.trim().trimStart(',', ';').trim()
        while (true) {
            val before = text
            text = text
                .replace(leadingCommandRegex, "")
                .trim()
                .trimStart(',', ';')
                .trim()
            if (text == before) return text
        }
    }

    private fun cleanSegment(segment: String): String {
        var text = segment.trim().trim(',', ';', '&').trim()
        while (true) {
            val before = text
            text = text
                .replace(leadingNoiseRegex, "")
                .replace(trailingNoiseRegex, "")
                .trim()
                .trim(',', ';', '&')
                .trim()
            if (text == before) return text
        }
    }

    private fun extractQuantityPrefix(segment: String): Pair<String, Quantity> {
        val normalized = segment
            .trim()
            .trim(',', ';', '&')
            .trim()
            .replace(leadingFillerRegex, "")
            .trim()
        val match = leadingCountRegex.find(normalized) ?: return segment to Quantity.Default
        val count = match.groupValues[1].toIntOrNull() ?: return segment to Quantity.Default
        if (count <= 0 || count > 20) return segment to Quantity.Default
        return match.groupValues[2].trim() to Quantity.Count(count)
    }

    private fun expandKnownProductSequence(segment: String): List<String> {
        val tokens = ProductLexicon.tokenize(segment)
        if (tokens.size < 2) return listOf(segment)

        val matches = mutableListOf<String>()
        var index = 0
        while (index < tokens.size) {
            val match = ProductLexicon.knownProductTokens.firstOrNull { (_, productTokens) ->
                productTokens.isNotEmpty() &&
                    index + productTokens.size <= tokens.size &&
                    tokens.subList(index, index + productTokens.size) == productTokens
            } ?: return listOf(segment)

            matches.add(match.first)
            index += match.second.size
        }

        return if (matches.size > 1) matches else listOf(segment)
    }
}
