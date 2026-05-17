package com.example.beta.automation

import java.util.Locale

object ProductLexicon {
    private val tokenRegex = Regex("[\\p{L}\\p{M}\\p{Nd}']+")

    private data class ProductAlias(val alias: String, val canonicalProduct: String)

    private val productAliases = listOf(
        ProductAlias("doodh", "milk"),
        ProductAlias("दूध", "milk"),
        ProductAlias("haalu", "milk"),
        ProductAlias("ಹಾಲು", "milk"),
        ProductAlias("makkhan", "butter"),
        ProductAlias("makhan", "butter"),
        ProductAlias("मक्खन", "butter"),
        ProductAlias("benne", "butter"),
        ProductAlias("ಬೆಣ್ಣೆ", "butter"),
        ProductAlias("seb", "apple"),
        ProductAlias("सेब", "apple"),
        ProductAlias("sebu", "apple"),
        ProductAlias("ಸೇಬು", "apple"),
        ProductAlias("pencil", "pencil"),
        ProductAlias("पेन्सिल", "pencil"),
        ProductAlias("पेंसिल", "pencil"),
        ProductAlias("ಪೆನ್ಸಿಲ್", "pencil"),
        ProductAlias("bhindi", "bhindi"),
        ProductAlias("भिंडी", "bhindi"),
        ProductAlias("bendekayi", "bhindi"),
        ProductAlias("ಬೆಂಡೆಕಾಯಿ", "bhindi"),
        ProductAlias("lay s", "lays"),
        ProductAlias("lay's", "lays")
    )

    private val aliasTokenEntries = productAliases
        .map { alias -> alias.canonicalProduct to tokenize(alias.alias) }
        .filter { (_, tokens) -> tokens.isNotEmpty() }
        .sortedByDescending { (_, tokens) -> tokens.size }

    val knownProducts = listOf(
        "raw pressery refreshing jal jeera drink",
        "paper boat swing jeera masala soda",
        "pepsi zero lemon soft drink",
        "raw mango",
        "notebook",
        "butter",
        "apples",
        "apple",
        "milk",
        "pencil",
        "lay's",
        "lays",
        "bhindi",
        "maggi"
    )

    val knownProductTokens = (
        knownProducts
        .map { product -> product to tokenize(product) }
            + aliasTokenEntries
        )
        .sortedByDescending { (_, tokens) -> tokens.size }

    fun tokenize(text: String): List<String> {
        return tokenRegex.findAll(text.lowercase(Locale.US))
            .map { it.value }
            .toList()
    }

    fun canonicalizeProductText(text: String): String {
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return text.trim().lowercase(Locale.US)

        val canonicalTokens = mutableListOf<String>()
        var index = 0
        while (index < tokens.size) {
            val match = aliasTokenEntries.firstOrNull { (_, aliasTokens) ->
                index + aliasTokens.size <= tokens.size &&
                    tokens.subList(index, index + aliasTokens.size) == aliasTokens
            }

            if (match != null) {
                canonicalTokens.addAll(tokenize(match.first))
                index += match.second.size
            } else {
                canonicalTokens.add(tokens[index])
                index += 1
            }
        }

        return canonicalTokens.joinToString(" ")
    }
}
