package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.automation.ParsedItem
import java.text.Normalizer
import java.util.Locale

/** A purchase-limit/pack-count fact describes the SKU, not what it sells.
 * This role can veto a contents match; it never supplies positive identity. */
private val vesselNouns = ("bottle bottles carton cartons jar jars jug jugs can cans tin tins tube tubes box boxes " +
    "bag bags packet packets pouch pouches dispenser dispensers container containers").split(' ').toSet()
private val accessories = listOf("holder", "lid", "cap", "brush", "opener", "stopper", "rack", "pump")
    .flatMap { listOf(it to it, (it + if (it == "brush") "es" else "s") to it) }.toMap()
private val roleWords = setOf("empty", "unfilled", "refillable", "reusable")
private val vesselMaterials = setOf("glass", "steel", "stainless", "plastic", "metal", "silicone", "storage")
private val scopeWords = ("not no never without in into of with using filled containing contains includes including " +
    "use rinse wash discard pour after before").split(' ').toSet()
private val subjectTokens = Regex("[\\p{L}\\p{M}\\p{Nd}]+")
private val subjectParts = Regex("[·|;()]")
private val negatedPart = Regex("\\b(not|never|no)\\s*\\(")
private val packTail = Regex("\\s+\\d+(?:\\.\\d+)?\\s*(?:ml|l|g|kg|litres?|liters?)\\s*$")
private val lidTail = Regex("\\s+with\\s+(?:(?:a|an)\\s+)?(?:lid|cap|pump)\\s*$")
private val mixedRequest = Regex("\\b(?:and|or|not|never|no|without|except|instead)\\b")
private val vesselCue = Regex("\\b(?:bottles?|cartons?|jars?|jugs?|cans?|tins?|tubes?|box(?:es)?|bags?|packets?|pouch(?:es)?|dispensers?|containers?|empty)\\b", RegexOption.IGNORE_CASE)
private val supportCue = Regex("\\b(?:holders?|racks?|stands?)\\b", RegexOption.IGNORE_CASE)
private val supportHeads = setOf("holder", "rack", "stand")

internal fun exactSupportTitle(request: String, title: String): Boolean {
    val words = request.trim().lowercase(Locale.ROOT).split(Regex("\\s+"))
    return words.size >= 3 && words[0] in supportHeads && words[1] == "for" &&
        words.all { it.matches(Regex("[a-z]+")) } &&
        words.drop(2).none { it in scopeWords || it in setOf("for", "or", "and") } &&
        title.trim().lowercase(Locale.ROOT).split(Regex("\\s+")) == words.drop(2) + words[0]
}

private const val CONFLICT = "conflict"
private fun mergeRoles(roles: List<String>): String? = roles.distinct().let {
    if (it.size <= 1) it.firstOrNull() else CONFLICT
}
private fun vesselMerchandise(text: String, secondary: Boolean = false, request: Boolean = false): String? {
    val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    if (!vesselCue.containsMatchIn(normalized) && !supportCue.containsMatchIn(normalized)) return null
    val parts = subjectParts.split(negatedPart.replace(normalized, "$1 "))
    if (request && (parts.size != 1 || mixedRequest.containsMatchIn(normalized))) return null
    return mergeRoles(parts.withIndex().mapNotNull { (index, part) ->
        val words = subjectTokens.findAll(lidTail.replace(packTail.replace(part.trim(), ""), "")).map { it.value }.toList()
        if (words.isEmpty()) return@mapNotNull null
        if (!secondary && index == 0) {
            val subject = words.takeWhile { it != "for" && it != "with" }
            val support = subject.lastOrNull()?.removeSuffix("s")
            if (support in supportHeads && subject.none { it in scopeWords }) return@mapNotNull support
        }
        val head = words.takeWhile { it != "for" }
        if (head.isEmpty() || head.any { it in scopeWords }) return@mapNotNull null
        val last = head.last()
        val prior = head.dropLast(1)
        when {
            words.takeLast(2) in listOf(listOf("sold", "empty"), listOf("supplied", "empty"), listOf("ships", "empty")) -> "vessel"
            last == "only" && prior.lastOrNull() in vesselNouns -> "vessel"
            last in accessories && (if (prior.lastOrNull() in listOf("replacement", "cleaning")) prior.dropLast(1) else prior)
                .lastOrNull() in vesselNouns -> accessories[last]
            last !in vesselNouns -> null
            prior.any { it == "empty" || it == "unfilled" } -> "vessel"
            (!secondary && index == 0 || "for" in words) && prior.any { it in roleWords } -> "vessel"
            request && head == words && prior.dropWhile { it in setOf("one", "a", "an") || it.toIntOrNull() != null }
                .all { it in vesselMaterials } -> "vessel"
            else -> null
        }
    })
}

internal fun sellerVesselRole(candidate: RecommendationCandidate): String? {
    val title = candidate.variant?.let { candidate.label.removeSuffix(" · $it") } ?: candidate.label
    return mergeRoles(listOfNotNull(candidate.catalogueVesselRole, vesselMerchandise(title)) +
        (listOfNotNull(candidate.variant, candidate.subtitle) + candidate.catalogueQuantityFields().orEmpty().map { it.second })
            .mapNotNull { vesselMerchandise(it, secondary = true) })
}

internal fun sellerVesselConflicts(item: ParsedItem, candidate: RecommendationCandidate): Boolean {
    val role = sellerVesselRole(candidate) ?: return false
    // Only a complete original subject, or the nominal proved by its original
    // container/contents receipt, can identify the accessory the shopper wants.
    if (role == CONFLICT || subjectParts.containsMatchIn(item.rawText) || mixedRequest.containsMatchIn(item.rawText.lowercase(Locale.ROOT)))
        return true
    val sourceRole = vesselMerchandise(item.rawText, request = true)
        ?: item.containerContentsLiteralIdentity()?.let { vesselMerchandise(it, request = true) }
    if (sourceRole != role) return true
    return listOfNotNull(item.query, item.strictMatchPhrase).any { query ->
        val interpreted = vesselMerchandise(query, request = true)
        if (interpreted != null) interpreted != role else {
            if (role in supportHeads) return@any true
            // A renderer can reverse product tokens ("brush bottle") or append
            // pack quantity. Neither is permission to discard the accessory.
            val words = subjectTokens.findAll(query.lowercase(Locale.ROOT)).map { it.value }.toSet()
            role == "vessel" || words.none { accessories[it] == role } || words.none { it in vesselNouns }
        }
    }
}
