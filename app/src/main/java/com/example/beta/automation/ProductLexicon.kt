package com.example.beta.automation

import java.util.Locale

object ProductLexicon {
    private val tokenRegex = Regex("[a-z0-9']+")

    val knownProducts = listOf(
        "raw pressery refreshing jal jeera drink",
        "paper boat swing jeera masala soda",
        "pepsi zero lemon soft drink",
        "raw mango",
        "notebook",
        "butter",
        "apples",
        "apple",
        "pencil",
        "lay's",
        "lays",
        "maggi"
    )

    val knownProductTokens = knownProducts
        .map { product -> product to tokenize(product) }
        .sortedByDescending { (_, tokens) -> tokens.size }

    fun tokenize(text: String): List<String> {
        return tokenRegex.findAll(text.lowercase(Locale.US))
            .map { it.value }
            .toList()
    }
}
