package com.example.beta.automation

import java.util.Locale
import com.example.beta.decimalDigitComparison

object ProductLexicon {
    private val tokenRegex = Regex("[\\p{L}\\p{M}\\p{Nd}']+")

    /** Existing parser/catalogue identities, not an expandable quantity grammar.
     * Match ORIGINAL text first; callers must separately prove preservation. */
    internal data class NumericProductIdentityMatch(
        val identityRange: IntRange,
        val amountRange: IntRange,
        val key: String,
    )

    private const val identityStart = "(?<![\\p{L}\\p{N}\\p{M}_.+/\\-\u2212\uff0b\uff0d])"
    private const val identityEnd = "(?![\\p{L}\\p{N}\\p{M}_])"
    private fun numericIdentityPattern(body: String) =
        Regex(identityStart + body + identityEnd, RegexOption.IGNORE_CASE)
    private val numericBrandRules = listOf(
        numericIdentityPattern("(7)[ \\t]*(?:-[ \\t]*)?up") to "7 up",
        numericIdentityPattern("(5)[ \\t]*(?:-[ \\t]*)?star") to "5 star",
        numericIdentityPattern("(24)[ \\t]*(?:-[ \\t]*)?mantra") to "24 mantra",
    )
    internal val onePlusPhraseRegex = numericIdentityPattern("(one)[ \\t]*plus")
    internal val maggiMinuteDescriptorRegex = numericIdentityPattern("maggi[ \\t]+(2|two)[ \\t-]+minutes?")
    private val numericIdentityRules = numericBrandRules + listOf(
        onePlusPhraseRegex to "oneplus",
        maggiMinuteDescriptorRegex to "maggi 2 minute",
    )

    internal fun numericProductIdentities(text: String): List<NumericProductIdentityMatch> {
        val comparison = decimalDigitComparison(text)
        return numericIdentityRules.flatMap { (pattern, key) ->
            pattern.findAll(comparison.text).map { NumericProductIdentityMatch(
                comparison.originalRange(it.range), comparison.originalRange(requireNotNull(it.groups[1]).range), key) }
        }.sortedBy { it.identityRange.first }
    }

    internal fun isNumericBrandPrefix(text: String): Boolean =
        numericBrandRules.any { (pattern, _) -> pattern.find(decimalDigitComparison(text.trimStart()).text)?.range?.first == 0 }

    /** Comparison only. Never replace the original quote or its offsets. */
    internal fun normalizeNumericProductIdentities(text: String): String =
        numericProductIdentities(text).asReversed().fold(text) { value, match -> value.replaceRange(match.identityRange, match.key) }

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
        ProductAlias("chai patti", "tea leaves"),
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
        ProductAlias("santra", "orange"),
        ProductAlias("santre", "orange"),
        ProductAlias("tamatar", "tomato"),
        ProductAlias("tamaatar", "tomato"),
        ProductAlias("nariyal paani", "coconut water"),
        ProductAlias("nariyal pani", "coconut water"),
        ProductAlias("nariyal", "coconut"),
        ProductAlias("dahi", "curd"),
        ProductAlias("gud", "jaggery"),
        ProductAlias("gur", "jaggery"),
        ProductAlias("shimla mirch", "capsicum"),
        // Translate the complete phrase: bare mirch does not identify its colour/type.
        ProductAlias("laal mirch", "red chilli"),
        ProductAlias("kali mirch", "black pepper"),
        ProductAlias("hari mirch", "green chilli"),
        ProductAlias("bell peppers", "capsicum"),
        ProductAlias("bell pepper", "capsicum"),
        ProductAlias("adrak", "ginger"),
        ProductAlias("lehsun", "garlic"),
        ProductAlias("lahsun", "garlic"),
        ProductAlias("haldi", "turmeric"),
        ProductAlias("hing", "asafoetida"),
        ProductAlias("palak", "spinach"),
        // A category translation only; never infer leaves, seeds or a prepared form.
        ProductAlias("methi", "fenugreek"),
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
        // Whole Kannada banana phrases only; bare "baale"/"bale" is
        // ambiguous and must not imply a fruit category or variety.
        ProductAlias("baalehannu", "banana"),
        ProductAlias("baale hannu", "banana"),
        ProductAlias("balehannu", "banana"),
        ProductAlias("bale hannu", "banana"),
        ProductAlias("ಬಾಳೆಹಣ್ಣು", "banana"),
        // Full, verified Kannada phrases only. Bare "menasinakayi" must not
        // invent a colour or be treated as capsicum.
        ProductAlias("hasiru menasinakaayi", "green chilli"),
        ProductAlias("hasiru menasinakayi", "green chilli"),
        ProductAlias("dalimbe", "pomegranate"),
        ProductAlias("daalimbe", "pomegranate"),
        // Verified category equivalents only; never infer colour, species or form.
        // Sources/limits: docs/INTENT_PROVIDER_DEV_COMPARISON_2026-10-22.md.
        ProductAlias("shunti", "ginger"),
        ProductAlias("elaichi", "cardamom"),
        ProductAlias("dalchini", "cinnamon"),
        ProductAlias("kittale hannu", "orange"),
        ProductAlias("kittale", "orange"),
        // Literal produce varieties outrank the shorter spice alias, in either order.
        ProductAlias("elaichi banana", "elaichi banana"),
        ProductAlias("elaichi bananas", "elaichi banana"),
        ProductAlias("elaichi kela", "elaichi banana"),
        ProductAlias("elaichi kele", "elaichi banana"),
        ProductAlias("banana elaichi", "elaichi banana"),
        ProductAlias("bananas elaichi", "elaichi banana"),
        ProductAlias("kela elaichi", "elaichi banana"),
        ProductAlias("kele elaichi", "elaichi banana"),
        ProductAlias("elaichi litchi", "elaichi litchi"),
        ProductAlias("litchi elaichi", "elaichi litchi"),
        ProductAlias("elaichi lychee", "elaichi lychee"),
        ProductAlias("lychee elaichi", "elaichi lychee"),
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

    // Comparison/retrieval spelling only: a single word-internal hyphen is not
    // a clause separator. Preserve the existing whitespace policy; never turn
    // other punctuation or numeric operators into alias separators.
    private const val lexicalAliasSeparator = "(?:\\s+|(?<=[A-Za-z])-(?=[A-Za-z]))"
    private val preservingAliasEntries = productAliases
        .map { alias ->
            val aliasTokens = tokenize(alias.alias)
            val phrase = aliasTokens.joinToString(lexicalAliasSeparator) { Regex.escape(it) }
            Triple(
                Regex("(?<![\\p{L}\\p{M}\\p{N}_'])$phrase(?![\\p{L}\\p{M}\\p{N}_'])", RegexOption.IGNORE_CASE),
                alias.canonicalProduct,
                aliasTokens.size,
            )
        }
        .sortedByDescending { it.third }
        .map { (pattern, canonical, _) -> pattern to canonical }

    // Strict source-reference equality excludes search-only property rewrites.
    // Whole variety phrases prevent a nested spice alias from changing its head.
    private val identityNounAliasKeys = (
        "keen waa|keenwaa|keenwa|mozz a rela|ande|anda|andaa|chawal|chaawal|cheeni|" +
            "namak|aata|aataa|doodh|haalu|makkhan|makhan|benne|aloo|aaloo|pyaaz|pyaz|" +
            "gajar|gaajar|kheera|khira|nimbu|neembu|kela|kele|baalehannu|baale hannu|" +
            "balehannu|bale hannu|santra|santre|tamatar|tamaatar|nariyal paani|nariyal pani|" +
            "nariyal|dahi|gud|gur|shimla mirch|bell peppers|bell pepper|laal mirch|kali mirch|" +
            "hari mirch|adrak|lehsun|lahsun|haldi|hing|palak|suji|sooji|methi|amrood|" +
            "murmura|mamra|seb|sebu|bhindi|bendekayi|hasiru menasinakaayi|hasiru menasinakayi|" +
            "dalimbe|daalimbe|shunti|elaichi|dalchini|kittale hannu|kittale|" +
            "elaichi banana|elaichi bananas|elaichi kela|elaichi kele|banana elaichi|bananas elaichi|" +
            "kela elaichi|kele elaichi|elaichi litchi|litchi elaichi|elaichi lychee|lychee elaichi"
        ).split('|').toSet()
    private val identityNounAliasEntries = productAliases.filter { it.alias in identityNounAliasKeys }
        .sortedByDescending { it.alias.split(' ').size }
        .map { alias ->
            val phrase = alias.alias.split(' ').joinToString("[ \\t]+") { Regex.escape(it) }
            Regex("(?<![\\p{L}\\p{M}\\p{N}_'])$phrase(?![\\p{L}\\p{M}\\p{N}_'])", RegexOption.IGNORE_CASE) to
                alias.canonicalProduct
        }

    // Classification of EXISTING noun aliases only. This is not the broad
    // search alias table: property rewrites/transliterated unknowns confer no proof.
    private val nativeIdentityNounAliasKeys = setOf(
        "दूध", "मक्खन", "सेब", "भिंडी", "पेन्सिल", "पेंसिल",
    )
    private fun completeNounKey(text: String): String =
        java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC)
            .trim(' ', '\t').replace(Regex("[ \\t]+"), " ").lowercase(Locale.ROOT)

    private val completeIdentityNouns: Map<String, String> = productAliases
        .filter { it.alias in identityNounAliasKeys || it.alias in nativeIdentityNounAliasKeys }
        .flatMap { listOf(completeNounKey(it.alias) to it.canonicalProduct,
            completeNounKey(it.canonicalProduct) to it.canonicalProduct) }.toMap()

    /** Complete independently attested noun only; never tokenize away extra words. */
    internal fun completeIdentityNoun(text: String): String? = completeIdentityNouns[completeNounKey(text)]

    /** Translate only an independently attested terminal merchandise noun.
     * Everything before that noun must remain literally the same, in order. */
    internal fun sameQualifiedNoun(left: String, right: String): Boolean {
        if (left.equals(right, ignoreCase = true)) return true
        fun signature(text: String): Pair<String, String>? {
            if (text.length > 200 || !qualifiedNounWords.matches(text)) return null
            val name = text.lowercase(Locale.ROOT)
            for (start in name.indices) if (start == 0 || name[start - 1] == ' ') {
                completeIdentityNoun(name.substring(start))?.let { return name.substring(0, start) to it }
            }
            return null
        }
        val wanted = signature(left) ?: return false
        return wanted == signature(right)
    }
    private val qualifiedNounWords = Regex("[\\p{L}\\p{M}]+(?: [\\p{L}\\p{M}]+)*")

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

    /** Longest match on ORIGINAL text only; never reinterpret a replacement. */
    fun canonicalizeAliasesPreservingText(text: String): String = replaceAliasesPreservingText(text, preservingAliasEntries)

    /** Closed noun translations only; no property rewrite, line crossing or search recall. */
    fun canonicalizeIdentityNounsPreservingText(text: String): String = replaceAliasesPreservingText(text, identityNounAliasEntries)

    private fun replaceAliasesPreservingText(text: String, entries: List<Pair<Regex, String>>): String {
        data class Replacement(val start: Int, val end: Int, val priority: Int, val value: String)
        // Scan once per alias, rather than allocate a regex matcher for every
        // character/alias pair. Overlapping shorter matches are ignored below.
        val replacements = entries.flatMapIndexed { priority, (pattern, canonical) ->
            pattern.findAll(text).map {
                Replacement(it.range.first, it.range.last + 1, priority, canonical)
            }.toList()
        }.sortedWith(compareBy<Replacement> { it.start }.thenBy { it.priority })
        return buildString(text.length) {
            var cursor = 0
            replacements.forEach { match ->
                if (match.start >= cursor) {
                    append(text, cursor, match.start)
                    append(match.value)
                    cursor = match.end
                }
            }
            append(text, cursor, text.length)
        }
    }
}
