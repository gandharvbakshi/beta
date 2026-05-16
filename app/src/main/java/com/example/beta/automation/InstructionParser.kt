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
    val quantity: Quantity = Quantity.Default
)

object InstructionParser {
    private val leadingPrefixRegex = Regex(
        "^(?:\\s*(?:order|buy|add|get me)\\b[\\s,]*)+",
        RegexOption.IGNORE_CASE
    )

    fun parse(input: String): List<ParsedItem> {
        val normalized = input.trim()
        if (normalized.isEmpty()) return emptyList()

        val withoutPrefix = normalized
            .replace(leadingPrefixRegex, "")
            .trim()
            .trimStart(',')
            .trim()

        if (withoutPrefix.isEmpty()) return emptyList()

        return withoutPrefix
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { item ->
                ParsedItem(
                    rawText = item,
                    query = item.lowercase(Locale.US)
                )
            }
    }
}
