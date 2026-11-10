package com.example.beta

import com.example.beta.automation.ProductLexicon
import java.util.Locale

/** Categories attach only to source-validated produce groups or whole-item count corrections. */
enum class IntentProduceCategory { FRUITS, VEGETABLES }

/**
 * Require a closed, positive produce identity from the provider's candidate text.
 * This deliberately accepts no brand/category inference and fails closed on any
 * unrecognized title, variant, or subtitle token.
 */
internal fun swiggyIntentProduceCandidateMatches(
    category: IntentProduceCategory,
    candidate: SwiggyMcpClient.RecommendationCandidate,
): Boolean {
    val allowedNouns = when (category) {
        IntentProduceCategory.FRUITS -> fruitNouns
        IntentProduceCategory.VEGETABLES -> vegetableNouns
    }
    val labelTokens = produceTokens(candidate.label, allowedNouns) ?: return false
    val productNouns = labelTokens.intersect(allowedNouns)
    if (productNouns.size != 1) return false
    // Beans/peas also name dried pulses. A vegetable heading cannot supply
    // missing catalogue evidence of fresh green produce. Keep this narrow
    // until the provider exposes a typed fresh/dried category or form.
    val ambiguousLegume = category == IntentProduceCategory.VEGETABLES &&
        productNouns.single() in setOf("bean", "beans", "pea", "peas")
    if (ambiguousLegume && !labelTokens.containsAll(setOf("fresh", "green"))) return false
    fun pulseColourConflict(tokens: Set<String>): Boolean = ambiguousLegume &&
        tokens.any { it in setOf("black", "white", "red", "yellow") }
    if (pulseColourConflict(labelTokens)) return false
    fun cookingFruit(tokens: Set<String>): Boolean = category == IntentProduceCategory.FRUITS &&
        (tokens.any { it in setOf("raw", "baby") } ||
            "green" in tokens && productNouns.single() in setOf("banana", "papaya", "mango"))
    // Those qualifiers can denote cooking produce rather than eating fruit.
    // Even an explicit request remains unresolved here until source-specific
    // variety evidence is supported; do not silently infer ripeness.
    if (cookingFruit(labelTokens)) return false
    return listOfNotNull(candidate.variant, candidate.subtitle)
        .filterNot(String::isBlank)
        .all { field ->
            val tokens = produceTokens(field, allowedNouns) ?: return@all false
            !cookingFruit(tokens) && !pulseColourConflict(tokens) &&
                tokens.intersect(allowedNouns).all { it in productNouns }
        }
}

/** Only the same positive subject on the selected offer; a matching ingredient is not enough.
 * Undo the backend's exact display join, not arbitrary punctuation or a brand/variant prefix. */
internal fun swiggyMeasuredProduceSubjectMatches(subject: String, candidate: SwiggyMcpClient.RecommendationCandidate): Boolean {
    val expected = measuredSubjectTokens(subject)?.singleOrNull()?.takeIf { it in vegetableNouns } ?: return false
    val title = candidate.variant?.takeIf(String::isNotBlank)?.let { candidate.label.removeSuffix(" · $it") } ?: candidate.label
    val allowed = setOf(expected, "fresh", "whole")
    // Parentheses can contain a variety, flavour or pack, not necessarily a translation.
    // Only use an entire second name we can establish independently for this same subject.
    val ordinaryHead = measuredSubjectTokens(title)
    if (ordinaryHead == null || expected !in ordinaryHead || !allowed.containsAll(ordinaryHead)) {
        val legend = sellerMarketLegend.matchEntire(title) ?: return false
        val alias = legend.groupValues[2].lowercase(Locale.ROOT)
        val category = ProductLexicon.completeIdentityNoun(alias) ?: exactSellerMarketNames[alias]
        if (category != subject) return false
        val literalHead = legend.groupValues[1].lowercase(Locale.ROOT)
        if (literalHead != subject && producePluralAliases[literalHead] != subject) return false
    }
    return (listOfNotNull(candidate.variant, candidate.subtitle) + candidate.catalogueQuantityFields().orEmpty().map { it.second })
        .filter(String::isNotBlank).all { field -> measuredSubjectTokens(field)?.let(allowed::containsAll) == true }
}

// The attested whole commodity name is one identity, not a head noun plus an
// optional adjective. Keep this view limited to the measured-list proof.
private val measuredCompounds by lazy {
    measuredProduceCompoundNames.associate { name ->
        val head = name.substringAfterLast(' ')
        val heads = (producePluralAliases.filterValues { it == head }.keys + head).joinToString("|", transform = Regex::escape)
        val prefix = name.substringBeforeLast(' ').split(' ').joinToString("[ \\t]+", transform = Regex::escape)
        Regex("(?<![\\p{L}\\p{M}\\p{N}_])$prefix[ \\t]+(?:$heads)(?![\\p{L}\\p{M}\\p{N}_])",
            RegexOption.IGNORE_CASE) to name.replace(" ", "")
    }
}
private fun measuredSubjectTokens(text: String) = produceTokens(
    measuredCompounds.entries.fold(text) { field, (phrase, key) -> phrase.replace(field, key) }, vegetableNouns)
    ?.mapTo(mutableSetOf(), ::measuredProduceExactName)

// Seller display only: no change to source interpretation or general aliases.
// VTU Vyavaharika Kannada table 3.26 (Aalugadde / ಆಲೂಗಡ್ಡೆ); Savi-Ruchi's
// English/Kannada glossary independently confirms the spaced market spelling.
private val exactSellerMarketNames = mapOf("aloo gadde" to "potato", "aalugadde" to "potato")
private val sellerMarketLegend = Regex("([A-Za-z]+(?: [A-Za-z]+){0,4}) \\(([A-Za-z]+(?: [A-Za-z]+){0,4})\\)")

private fun produceTokens(raw: String, allowedNouns: Set<String>): Set<String>? {
    if (raw.isBlank()) return null
    if (Regex("[^\\p{L}\\p{N}\\s.,()×-]").containsMatchIn(raw)) return null
    var text = raw.lowercase(Locale.ROOT)
        .replace(Regex("\\bbell\\s+peppers?\\b"), " capsicum ")
        .replace(Regex("\\bsweet\\s+limes?\\b"), " mosambi ")
        .replace(Regex("\\bcustard\\s+apples?\\b"), " sitaphal ")
        .replace(Regex("\\bdragon\\s+fruits?\\b"), " dragonfruit ")
        .replace(Regex("\\bbottle\\s+gourds?\\b"), " bottlegourd ")
        .replace(Regex("\\bbitter\\s+gourds?\\b"), " bittergourd ")
        .replace(Regex("\\bridge\\s+gourds?\\b"), " ridgegourd ")
        .replace(Regex("\\blady\\s+fingers?\\b"), " okra ")
        .replace(Regex("\\bgreen\\s+chill(?:i|ies)\\b"), " greenchilli ")

    // Only explicit bounded measure, piece-count, or pack-count syntax is metadata.
    val metadata = listOf(
        Regex("\\b\\d{1,3}\\s*[x×]\\s*\\d{1,4}(?:\\.\\d+)?\\s*(?:kg|kgs|g|gm|gms|grams?)\\b"),
        Regex("\\b\\d{1,4}(?:\\.\\d+)?\\s*(?:kg|kgs|g|gm|gms|grams?)(?:\\s+pack(?:s)?)?\\b"),
        Regex("\\bpack\\s+of\\s+\\d{1,3}\\b"),
        Regex("\\b\\d{1,3}\\s*(?:pcs?|pieces?|count)\\b"),
    )
    var removedMetadata = false
    metadata.forEach { pattern ->
        if (pattern.findAll(text).any { match ->
            Regex("[0-9]+(?:\\.[0-9]+)?").findAll(match.value).any { it.value.toBigDecimal().signum() <= 0 }
        }) return null
        if (pattern.containsMatchIn(text)) removedMetadata = true
        text = pattern.replace(text, " ")
    }

    val tokens = Regex("[\\p{L}\\p{N}]+").findAll(text)
        .map { match -> producePluralAliases[match.value] ?: match.value }
        .toSet()
    if (tokens.isEmpty()) return if (removedMetadata) emptySet() else null
    val allowed = allowedProduceQualifiers + allowedNouns
    return tokens.takeIf { it.all(allowed::contains) }
}

private val allowedProduceQualifiers = setOf(
    "fresh", "local", "organic", "imported", "green", "red", "yellow", "orange",
    "small", "medium", "large", "seedless", "royal", "gala", "pink", "white",
    "black", "purple", "ripe", "raw", "whole", "farm", "seasonal", "baby",
)

private val fruitNouns = setOf(
    "apple", "banana", "orange", "mango", "grape", "pomegranate", "anar", "guava", "amrood",
    "papaya", "watermelon", "muskmelon", "pineapple", "pear", "peach", "plum", "kiwi",
    "strawberry", "blueberry", "lemon", "lime", "mosambi", "chikoo", "sapota", "sitaphal",
    "jackfruit", "kathal", "coconut", "nariyal", "dragonfruit", "cherry", "apricot", "fig",
    "date", "dates", "lychee", "litchi", "raspberry", "blackberry",
)

internal fun isLiteralProduceNoun(word: String): Boolean = word in fruitNouns || word in vegetableNouns

private val vegetableNouns = setOf(
    "tomato", "potato", "onion", "carrot", "cucumber", "capsicum", "drumstick", "moringa",
    "brinjal", "eggplant", "okra", "bhindi", "cabbage", "cauliflower", "broccoli", "spinach",
    "palak", "coriander", "dhaniya", "lettuce", "bean", "beans", "pea", "peas", "pumpkin",
    "bottlegourd", "lauki", "bittergourd", "karela", "ridgegourd", "turai", "zucchini", "radish",
    "mooli", "beetroot", "turnip", "garlic", "ginger", "greenchilli", "chilli", "chili", "corn",
    "maize", "sweetpotato", "yam", "celery", "kale", "springonion", "leek", "mushroom",
)

private val producePluralAliases = mapOf(
    "anaar" to "anar",
    "apples" to "apple", "bananas" to "banana", "oranges" to "orange", "mangoes" to "mango",
    "mangos" to "mango", "grapes" to "grape", "guavas" to "guava", "papayas" to "papaya",
    "watermelons" to "watermelon", "pineapples" to "pineapple", "pears" to "pear", "peaches" to "peach",
    "plums" to "plum", "kiwis" to "kiwi", "strawberries" to "strawberry", "blueberries" to "blueberry",
    "lemons" to "lemon", "limes" to "lime", "cherries" to "cherry", "figs" to "fig",
    "tomatoes" to "tomato", "potatoes" to "potato", "onions" to "onion", "carrots" to "carrot",
    "cucumbers" to "cucumber", "drumsticks" to "drumstick", "brinjals" to "brinjal", "eggplants" to "eggplant",
    "cabbages" to "cabbage", "cauliflowers" to "cauliflower", "beans" to "bean", "peas" to "pea",
    "pumpkins" to "pumpkin", "radishes" to "radish", "beetroots" to "beetroot", "turnips" to "turnip",
    "mushrooms" to "mushroom",
)
