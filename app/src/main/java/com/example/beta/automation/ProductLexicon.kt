package com.example.beta.automation

import java.util.Locale

object ProductLexicon {
    private val tokenRegex = Regex("[\\p{L}\\p{M}\\p{Nd}']+")

    private data class ProductAlias(val alias: String, val canonicalProduct: String)

    private val productAliases = listOf(
        ProductAlias("keen waa", "quinoa"),
        ProductAlias("keenwaa", "quinoa"),
        ProductAlias("keenwa", "quinoa"),
        ProductAlias("mozz a rela", "mozzarella"),
        ProductAlias("ande", "eggs"),
        ProductAlias("anda", "eggs"),
        ProductAlias("andaa", "eggs"),
        ProductAlias("chawal", "rice"),
        ProductAlias("chaawal", "rice"),
        ProductAlias("cheeni", "sugar"),
        ProductAlias("chai", "tea"),
        ProductAlias("namak", "salt"),
        ProductAlias("aata", "atta"),
        ProductAlias("aataa", "atta"),
        ProductAlias("doodh", "milk"),
        ProductAlias("दूध", "milk"),
        ProductAlias("haalu", "milk"),
        ProductAlias("ಹಾಲು", "milk"),
        ProductAlias("makkhan", "butter"),
        ProductAlias("makhan", "butter"),
        ProductAlias("aloo", "potato"),
        ProductAlias("aaloo", "potato"),
        ProductAlias("pyaaz", "onion"),
        ProductAlias("pyaz", "onion"),
        ProductAlias("gajar", "carrot"),
        ProductAlias("gaajar", "carrot"),
        ProductAlias("kheera", "cucumber"),
        ProductAlias("khira", "cucumber"),
        ProductAlias("nimbu", "lemon"),
        ProductAlias("neembu", "lemon"),
        ProductAlias("kela", "banana"),
        ProductAlias("kele", "banana"),
        ProductAlias("tamatar", "tomato"),
        ProductAlias("tamaatar", "tomato"),
        ProductAlias("nariyal paani", "coconut water"),
        ProductAlias("nariyal pani", "coconut water"),
        ProductAlias("nariyal", "coconut"),
        ProductAlias("dahi", "curd"),
        ProductAlias("gud", "jaggery"),
        ProductAlias("gur", "jaggery"),
        ProductAlias("shimla mirch", "capsicum"),
        ProductAlias("bell peppers", "capsicum"),
        ProductAlias("bell pepper", "capsicum"),
        ProductAlias("adrak", "ginger"),
        ProductAlias("lehsun", "garlic"),
        ProductAlias("lahsun", "garlic"),
        ProductAlias("haldi", "turmeric"),
        ProductAlias("hing", "asafoetida"),
        ProductAlias("palak", "spinach"),
        ProductAlias("suji", "semolina"),
        ProductAlias("sooji", "semolina"),
        ProductAlias("amrood", "guava"),
        ProductAlias("murmura", "puffed rice"),
        ProductAlias("mamra", "puffed rice"),
        ProductAlias("laal", "red"),
        ProductAlias("kachcha", "raw"),
        ProductAlias("kachche", "raw"),
        ProductAlias("kaccha", "raw"),
        ProductAlias("kacche", "raw"),
        ProductAlias("aam", "mango"),
        ProductAlias("kapde dhone ka powder", "laundry detergent powder"),
        ProductAlias("मक्खन", "butter"),
        ProductAlias("benne", "butter"),
        ProductAlias("ಬೆಣ್ಣೆ", "butter"),
        // Keep the everyday milk requests bounded to explicit low-cream
        // wording; do not broaden this to arbitrary "lean" products.
        ProductAlias("lean milk", "low fat milk"),
        ProductAlias("milk with less cream", "low fat milk"),
        ProductAlias("less cream milk", "low fat milk"),
        ProductAlias("kam malai wala doodh", "low fat milk"),
        ProductAlias("doodh kam malai wala", "low fat milk"),
        ProductAlias("ready made tea", "ready to drink tea"),
        ProductAlias("tea ready made", "ready to drink tea"),
        ProductAlias("ready made coffee", "ready to drink coffee"),
        ProductAlias("coffee ready made", "ready to drink coffee"),
        ProductAlias("without added sugar", "no added sugar"),
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

    private val preservingAliasEntries = productAliases
        .map { alias ->
            val aliasTokens = tokenize(alias.alias)
            val phrase = aliasTokens.joinToString("\\s+") { Regex.escape(it) }
            Triple(
                Regex("(?<![\\p{L}\\p{M}\\p{N}])$phrase(?![\\p{L}\\p{M}\\p{N}])", RegexOption.IGNORE_CASE),
                alias.canonicalProduct,
                aliasTokens.size,
            )
        }
        .sortedByDescending { it.third }
        .map { (pattern, canonical, _) -> pattern to canonical }

    val knownProducts = listOf(
        "raw pressery refreshing jal jeera drink",
        "paper boat swing jeera masala soda",
        "pepsi zero lemon soft drink",
        "raw mango",
        "eggs",
        "rice",
        "sugar",
        "salt",
        "atta",
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

    /** Replaces known aliases without retokenizing or rewriting punctuation and measures. */
    fun canonicalizeAliasesPreservingText(text: String): String = preservingAliasEntries.fold(text) { value, (pattern, canonical) ->
        pattern.replace(value, canonical)
    }
}
