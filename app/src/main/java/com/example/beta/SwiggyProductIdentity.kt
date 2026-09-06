package com.example.beta

import com.example.beta.automation.ParsedItem
import java.util.Locale

/** Candidate text is evidence of identity, not just a bag of overlapping words. */
internal fun swiggyMatchesProductIdentity(item: ParsedItem, label: String): Boolean {
    val strict = !item.strictMatchPhrase.isNullOrBlank()
    if (strict && !swiggyStrictPackMatches(item.strictMatchPhrase.orEmpty(), label)) return false
    val query = swiggyIdentityTokens(item.strictMatchPhrase?.takeIf { it.isNotBlank() } ?: item.query)
    val candidate = swiggyIdentityTokens(label)
    if (query.isEmpty()) return false
    if (candidate.containsAll(query)) return true
    if (strict || query.size > 4) return false
    val missing = (query - candidate).sorted()
    val available = candidate - query
    fun match(index: Int, unused: Set<String>): Boolean = index == missing.size ||
        unused.any { token ->
            swiggyIdentityNearToken(missing[index], token) && match(index + 1, unused - token)
        }
    return match(0, available)
}

private fun swiggyStrictPackMatches(request: String, label: String): Boolean {
    val measure = Regex("\\b(\\d+(?:\\.\\d+)?)\\s*(kg|kgs|g|gm|gms|grams?|ml|l|ltr|lit(?:er|re)s?|pcs?|pieces?|count|packs?)\\b", RegexOption.IGNORE_CASE)
    fun codes(text: String): Set<String> = measure.findAll(text).map { match ->
        val unit = match.groupValues[2].lowercase(Locale.ROOT)
        val dimension = when (unit) {
            "kg", "kgs", "g", "gm", "gms", "gram", "grams" -> "weight"
            "ml", "l", "ltr", "liter", "litre", "liters", "litres" -> "volume"
            "pack", "packs" -> "pack"
            else -> "pieces"
        }
        val factor = if (unit in setOf("kg", "kgs", "l", "ltr", "liter", "litre", "liters", "litres")) 1000 else 1
        "$dimension:${match.groupValues[1].toBigDecimal().multiply(factor.toBigDecimal()).stripTrailingZeros().toPlainString()}"
    }.toSet()
    val required = codes(request)
    val multipack = Regex(
        "\\b(?:pack\\s+of\\s+(\\d+)|(\\d+)\\s*[x×](?=\\s*\\d)|\\d+(?:\\.\\d+)?\\s*(?:kg|kgs|g|gm|gms|grams?|ml|l|ltr|lit(?:er|re)s?)\\s*[x×]\\s*(\\d+))",
        RegexOption.IGNORE_CASE,
    )
    fun multipliers(text: String) = multipack.findAll(text).map { match ->
        match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.toIntOrNull()
    }.toSet()
    return (required.isEmpty() || codes(label) == required) &&
        (required.isEmpty() && multipliers(request).isEmpty() || multipliers(request) == multipliers(label))
}

internal fun swiggyIdentityTokens(value: String): Set<String> {
    var text = value.lowercase(Locale.ROOT)
        .replace(Regex("\\b7\\s*up\\b"), "7 up")
        .replace(Regex("\\b5\\s*star\\b"), "5 star")
        .replace(Regex("\\b24\\s*mantra\\b"), "24 mantra")
        .replace(Regex("\\bsesame\\s+paste\\b"), "tahini")
        .replace(Regex("\\bkeen\\s*waa?\\b"), "quinoa")
        .replace(Regex("\\bsugar\\s+free\\b"), "sugarfree")
    // Remove quantities, never bracketed flavours, model codes or identity words
    // following a measure. Exact pack arithmetic is checked separately.
    text = text.replace(Regex("\\bpack\\s+of\\s+\\d+\\b"), " ")
        .replace(Regex("\\b\\d+(?:\\.\\d+)?\\s*[x×]\\s*(?=\\d)"), " ")
        .replace(Regex("\\b\\d+(?:\\.\\d+)?\\s*(?:kg|kgs|g|gm|gms|grams?|ml|l|ltr|lit(?:er|re)s?|pcs?|pieces?|count|packs?)\\b"), " ")
    val plurals = mapOf("eggs" to "egg", "bananas" to "banana", "tomatoes" to "tomato",
        "onions" to "onion", "potatoes" to "potato", "leaves" to "leaf")
    return Regex("[\\p{L}\\p{N}]+").findAll(text).map { it.value }
        .filter { it !in setOf("fresh", "and", "the", "of") }
        .map { plurals[it] ?: it }.toSet()
}

private val exactIdentityWords = setOf(
    "milk", "silk", "rice", "salt", "sugar", "soap", "soda", "butter", "batter", "flour", "flower",
    "corn", "curd", "tea", "coffee", "salted", "unsalted", "sweetened", "unsweetened", "sugarfree",
    "free", "decaf", "regular", "taped", "pants", "adult", "baby", "almond", "coconut", "vanilla", "chocolate", "ginger", "finger",
)

private fun swiggyIdentityNearToken(left: String, right: String): Boolean {
    // Keep this observed misspelling, without allowing real words such as
    // bitter -> butter or soup -> soap through the general fuzzy fallback.
    if (left == "buttr" && right == "butter") return true
    if (left in exactIdentityWords || right in exactIdentityWords || minOf(left.length, right.length) < 4 ||
        !left.all(Char::isLetter) || !right.all(Char::isLetter)) return false
    val limit = if (minOf(left.length, right.length) >= 7) 2 else 1
    if (kotlin.math.abs(left.length - right.length) > limit) return false
    var previous = IntArray(right.length + 1) { it }
    left.forEachIndexed { index, char ->
        val current = IntArray(right.length + 1)
        current[0] = index + 1
        right.forEachIndexed { j, other ->
            current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + if (char == other) 0 else 1)
        }
        previous = current
    }
    return previous.last() <= limit
}
