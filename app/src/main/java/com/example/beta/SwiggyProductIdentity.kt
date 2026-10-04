package com.example.beta

import com.example.beta.automation.ParsedItem
import com.example.beta.automation.ProductLexicon
import java.util.Locale

/** Candidate text is evidence of identity, not just a bag of overlapping words. */
internal fun swiggyMatchesProductIdentity(item: ParsedItem, label: String): Boolean {
    val strict = !item.strictMatchPhrase.isNullOrBlank()
    if (strict && !swiggyStrictPackMatches(item.strictMatchPhrase.orEmpty(), label)) return false
    val query = swiggyIdentityTokens(item.strictMatchPhrase?.takeIf { it.isNotBlank() } ?: item.query)
    val candidate = swiggyIdentityTokens(label)
    if (query.isEmpty()) return false
    // A staple-only request must not match a prepared dish that merely contains
    // the same ingredient (e.g. potato -> aloo bhujia). Explicit dish requests
    // remain eligible; this deliberately is not a general product classifier.
    if (query.any { it in stapleIdentityTokens } && query.none { it in preparedDishHeadTokens } &&
        candidate.any { it in preparedDishHeadTokens }) return false
    // Form attributes are mandatory evidence, never fuzzy spelling matches.
    val required = query.intersect(setOf("tetrapack", "readytoeat", "readytocook", "readytodrink",
        "sugarfree", "noaddedsugar", "lowfat", "skimmed", "doubletoned"))
    if (!candidate.containsAll(required)) return false
    if ("lowfat" in required && "fullcream" in candidate) return false
    if (required.any { it in setOf("sugarfree", "noaddedsugar") } && swiggyHasSugarContradiction(label)) return false
    if (query == setOf("sugarfree") && candidate.none { it in setOf("sweetener", "sweeteners", "tablet", "tablets", "sachet", "sachets") }) return false
    if ("readytodrink" in required && candidate.any { it in setOf("leaf", "premix", "powder", "instant", "readytocook") }) return false
    if ("samosa" in query && candidate.any { it in setOf("masala", "pastry", "sheet", "sheets") }) return false
    if ("tetrapack" in query && candidate.any { it in setOf("pouch", "pouches", "bottle", "bottles") }) return false
    if ("readytoeat" in query && candidate.any { it in setOf("raw", "kernel", "kernels", "microwave", "readytocook", "frozen") }) return false
    if (query.any { it in setOf("batter", "dough") } && query.none { it in setOf("mix", "powder") } &&
        candidate.any { it in setOf("mix", "powder") }) return false
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
    val measure = Regex("\\b(\\d+(?:\\.\\d+)?)\\s*(kg|kgs|g|gm|gms|grams?|ml|l|ltr|lit(?:er|re)s?|pcs?|pieces?|count|packs?|lozenges?|tablets?|capsules?|sachets?|pellets?|eggs?|batteries)\\b", RegexOption.IGNORE_CASE)
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
    var text = ProductLexicon.canonicalizeAliasesPreservingText(value).lowercase(Locale.ROOT)
        .replace(Regex("\\blean\\s+milk\\b|\\bmilk\\s+(?:with\\s+)?less\\s+cream\\b|\\bless\\s+cream\\s+milk\\b"), "low fat milk")
    if (Regex("\\b(?:tea|coffee)\\b").containsMatchIn(text)) {
        text = text.replace(Regex("\\bready[\\s-]*made\\b"), "ready to drink")
    }
    if (Regex("\\b(?:batter|dough)\\b").containsMatchIn(text)) {
        // Prepared ingredient, not an already cooked meal. Its named form still has
        // to match (and dry mixes are rejected above); do not infer ready-to-eat.
        text = text.replace(Regex("\\bready[\\s-]*made\\b"), " ")
    }
    // Exact provider shorthand, not a preferred brand or inferred UHT pack.
    // Keep this aligned with backend _normalize_product_identity_forms.
    if (Regex("\\s*amul\\s+taaza\\s+tetra(?:\\s*(?:[·|,-]\\s*)?\\d+(?:\\.\\d+)?\\s*(?:ml|l|ltr|litres?))?\\s*").matches(text)) {
        text = text.replace(Regex("\\btaaza\\b"), "taaza milk")
    }
    if (Regex("\\bmilk\\b").containsMatchIn(text)) {
        text = text.replace(Regex("\\btetra\\b(?![\\s-]*packs?\\b)"), "tetrapack")
    }
    text = text
        .replace(Regex("\\b7\\s*up\\b"), "7 up")
        .replace(Regex("\\b5\\s*star\\b"), "5 star")
        .replace(Regex("\\b24\\s*mantra\\b"), "24 mantra")
        .replace(Regex("\\bsesame\\s+paste\\b"), "tahini")
        .replace(Regex("\\bkeen\\s*waa?\\b"), "quinoa")
        .replace(Regex("\\b(?:with\\s+)?(?:no|without)[\\s-]+added[\\s-]+sugar\\b"), "noaddedsugar")
        .replace(Regex("\\b(?:sugar[\\s-]*free|zero[\\s-]+sugar)\\b"), "sugarfree")
        .replace(Regex("\\blow[\\s-]*fat\\b"), "lowfat")
        .replace(Regex("\\bdouble[\\s-]*toned\\b"), "doubletoned")
        .replace(Regex("\\bfull[\\s-]*cream\\b"), "fullcream")
        .replace(Regex("\\bready[\\s-]*to[\\s-]*drink\\b"), "readytodrink")
        .replace(Regex("\\b(?:tetra[\\s-]*packs?|tetrapaks?|tetrapacks?|tetta[\\s-]*packs?)\\b"), "tetrapack")
        .replace(Regex("\\bready[\\s-]*(?:made|to[\\s-]*eat)\\b"), "readytoeat")
        .replace(Regex("\\bready[\\s-]*to[\\s-]*cook\\b"), "readytocook")
    // Remove quantities, never bracketed flavours, model codes or identity words
    // following a measure. Exact pack arithmetic is checked separately.
    text = text.replace(Regex("\\bpack\\s+of\\s+\\d+\\b"), " ")
        .replace(Regex("\\b\\d+(?:\\.\\d+)?\\s*[x×]\\s*(?=\\d)"), " ")
        .replace(Regex("\\b\\d+(?:\\.\\d+)?\\s*(?:kg|kgs|g|gm|gms|grams?|ml|l|ltr|lit(?:er|re)s?|pcs?|pieces?|count|packs?)\\b"), " ")
    val plurals = mapOf("eggs" to "egg", "bananas" to "banana", "tomatoes" to "tomato",
        "onions" to "onion", "potatoes" to "potato", "leaves" to "leaf", "samosas" to "samosa",
        "apples" to "apple", "oranges" to "orange", "carrots" to "carrot", "cucumbers" to "cucumber",
        "lemons" to "lemon", "pears" to "pear", "mangoes" to "mango", "guavas" to "guava",
        "kiwis" to "kiwi", "plums" to "plum", "peaches" to "peach", "grapes" to "grape",
        "cauliflowers" to "cauliflower", "cabbages" to "cabbage", "berries" to "berry",
        "strawberries" to "strawberry", "blueberries" to "blueberry")
    val tokens = Regex("[\\p{L}\\p{N}]+").findAll(text).map { it.value }
        .filter { it !in setOf("fresh", "and", "the", "of") }
        .map { plurals[it] ?: it }.toMutableSet()
    if ("milk" in tokens && tokens.any { it in setOf("skimmed", "doubletoned") }) tokens += "lowfat"
    // Bounded complete catalogue titles, not a blanket brand/category guess.
    if (Regex(
        "\\s*sugar[\\s-]*free\\s+(?:gold\\+?|green(?:\\s+stevia|\\s+100%\\s+natural\\s+made\\s+from\\s+stevia)?|natura(?:\\s+low\\s+calorie\\s+sweet(?:e)?ner)?)" +
            "(?:\\s*(?:[·|—-]\\s*)?\\d+(?:\\.\\d+)?\\s*(?:g|gm|kg|pcs?|pieces?|tablets?|pellets?))?\\s*",
        RegexOption.IGNORE_CASE,
    ).matches(value)) tokens += "sweetener"
    return tokens
}

private val stapleIdentityTokens = setOf("milk", "potato", "mango", "curd", "carrot", "onion")
private val preparedDishHeadTokens = setOf(
    "bhujia", "namkeen", "peda", "barfi", "ladoo", "laddoo", "mithai", "halwa",
    "vada", "bhalla", "chaat", "papad", "kachori", "tikki", "paratha", "achaar", "pickle", "pickles",
)

internal fun swiggyHasSugarContradiction(label: String): Boolean {
    val text = label.replace(Regex("\\b(?:no|without)[\\s-]+added[\\s-]+sugar\\b", RegexOption.IGNORE_CASE), " ")
    return Regex("\\b(?:with|contains|added)\\s+sugar\\b|\\bsweetened\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
}

private val exactIdentityWords = setOf(
    "milk", "silk", "rice", "salt", "sugar", "soap", "soda", "butter", "batter", "flour", "flower",
    "corn", "curd", "tea", "coffee", "salted", "unsalted", "sweetened", "unsweetened", "sugarfree",
    "free", "decaf", "regular", "taped", "pants", "adult", "baby", "almond", "coconut", "vanilla", "chocolate", "ginger", "finger",
    "lowfat", "skimmed", "doubletoned", "fullcream", "noaddedsugar", "readytodrink",
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
