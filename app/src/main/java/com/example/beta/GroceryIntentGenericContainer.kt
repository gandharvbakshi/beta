package com.example.beta

import java.math.BigDecimal
import java.text.Normalizer
import java.util.Locale
import com.example.beta.automation.IntentLocalAmount
import com.example.beta.automation.IntentQuantityOrigin
import com.example.beta.automation.IntentSourceCountRole
import com.example.beta.automation.ParsedItem
import com.example.beta.automation.Quantity
import com.example.beta.automation.ProductLexicon

/**
 * Narrow proof for a generic household container used as a physical-size
 * wrapper, e.g. "2 dabba Nescafe 500 g". It never assigns a retail
 * packaging type; callers may only use the range as source evidence.
 */
internal fun GroceryIntentReviewV2ReadyRow.genericContainerRange(): IntRange? {
    val source = evidence.primary.text
    if (corrections.isNotEmpty() || item.supportingText.isNotEmpty() || evidence.supporting.isNotEmpty() ||
        item.needsClarification || item.packaging != null || item.excluded.isNotEmpty() ||
        item.quantityUnit !in setOf("count", "pack") ||
        item.quantity?.stripTrailingZeros()?.scale()?.let { it <= 0 } != true ||
        source.any { it.isISOControl() } || intentV2HasCorrectionCue(source)
    ) return null

    val quantity = item.quantity
    if (quantity < BigDecimal.ONE || quantity > BigDecimal("20")) return null
    val quantityEvidence = localQuantityEvidence() ?: return null
    if (quantityEvidence.source != source ||
        quantityEvidence.value.compareTo(quantity) != 0 || quantityEvidence.unit != item.quantityUnit) return null

    if (item.quantityText == null) return null
    val packValue = item.packValue ?: return null
    if (item.packUnit !in setOf("g", "kg", "ml", "l") || packValue.signum() <= 0) return null
    val pack = localPackSizeEvidence() ?: return null
    if (pack.source != source || pack.value.compareTo(packValue) != 0 || pack.unit != item.packUnit ||
        !physicalUnitFollowsAt(source, pack.startUtf16, pack.atom, pack.unit)) return null

    val receipt = compileGenericContainerReceipt(source, item.name, quantityEvidence, pack) ?: return null
    if (!genericContainerNameMatches(source.substring(receipt.identity), item.name, item)) return null
    return receipt.wrapper
}

/** A generic wrapper stays unresolved packaging. This receipt is not a SKU,
 * a divisor, or permission to act; future adapters must require an offer review. */
sealed interface GenericContainerRequestedSize {
    data class Exact(val amount: IntentLocalAmount, val physicalSpan: IntRange) : GenericContainerRequestedSize {
        override fun toString(): String = "GenericContainerRequestedSize.Exact(<redacted>)"
    }
    data object Unspecified : GenericContainerRequestedSize
}

data class GenericContainerReceipt(
    val source: String,
    val count: IntentLocalAmount,
    val requestedSize: GenericContainerRequestedSize,
    val wrapper: IntRange,
    val identity: IntRange,
    val request: IntRange? = null,
    val connector: IntRange? = null,
) {
    val size: IntentLocalAmount? get() = (requestedSize as? GenericContainerRequestedSize.Exact)?.amount
    val physicalSize: IntRange? get() = (requestedSize as? GenericContainerRequestedSize.Exact)?.physicalSpan
    override fun toString(): String = "GenericContainerReceipt(<redacted>)"
}

internal fun sourceHasGenericContainer(source: String): Boolean = Regex(
    "(?<![\\p{L}\\p{N}_])(?:${groceryIntentGenericContainerWords.joinToString("|")})(?![\\p{L}\\p{N}_])",
).containsMatchIn(source.asciiLowercase())

/** V1 has no amount quotes: only uniquely recovered original atoms may compile. */
internal fun GroceryIntentItem.legacyGenericContainerReceipt(): GenericContainerReceipt? {
    if (needsClarification || excluded.isNotEmpty() || quantityUnit !in setOf("count", "pack", "piece") ||
        packUnit !in setOf("g", "kg", "ml", "l", "unspecified") ||
        (packValue == null) != (packUnit == "unspecified")) return null
    val count = quantity?.let { legacyLocalAmount(it, "count") } ?: return null
    val size = packValue?.let { legacyLocalAmount(it, packUnit) }
    if (packValue != null && size == null) return null
    val receipt = compileGenericContainerReceipt(sourceText, name, count, size, extended = true) ?: return null
    val literalWords = genericLiteralWords(sourceText.substring(receipt.identity))
    if (required.any { tag ->
            val words = Regex("[\\p{L}]+").findAll(tag.replace('_', ' ').asciiLowercase()).map { it.value }.toSet()
            words.isEmpty() || !literalWords.containsAll(words)
        }) return null
    return receipt
}

/** Source detection prevents removing a nullable marker from downgrading an
 * ambiguous wrapper to an exact pack or a guessed piece divisor. */
internal fun ParsedItem.requiresGenericContainerOffer(): Boolean =
    intentQuantityProvenance?.sourceCountRole == IntentSourceCountRole.GENERIC_WRAPPERS ||
        intentQuantityProvenance?.genericContainerReceipt != null || sourceHasGenericContainer(rawText)

/** Only the source-owned identity and exact size may feed retrieval. Required
 * tags for this narrow receipt must already occur literally inside the name. */
internal fun GenericContainerReceipt.literalIdentity(): String = source.substring(identity)

internal fun GenericContainerReceipt.literalQuery(): String? = runCatching {
    val pack = size?.let { normalizePack(it.value, it.unit) }
    listOfNotNull(ProductLexicon.canonicalizeAliasesPreservingText(literalIdentity()),
        pack?.let { "${it.first} ${it.second}" }).joinToString(" ")
}.getOrNull()

internal fun ParsedItem.validGenericContainerReceipt(): GenericContainerReceipt? {
    val proof = intentQuantityProvenance ?: return null
    val receipt = proof.genericContainerReceipt ?: return null
    if (!requiresIntentQuantityProof || proof.origin != IntentQuantityOrigin.LOCAL ||
        proof.sourceCountRole != IntentSourceCountRole.GENERIC_WRAPPERS ||
        proof.corrected != null || proof.retailContainer != null || retailPackCount || logicalPieceCount ||
        proof.retailPackCount || proof.logicalPieceCount || proof.rawText != rawText || proof.primaryText != rawText ||
        receipt.source != rawText || proof.local != receipt.count || proof.unitSize != receipt.size || avoidPhrases.isNotEmpty() ||
        proof.resolvedQuantity != quantity || proof.query != query || proof.strictMatchPhrase != strictMatchPhrase ||
        (quantity as? Quantity.Count)?.n?.toBigDecimal()?.compareTo(receipt.count.value) != 0 ||
        receipt.identity.first < 0 || receipt.identity.last >= rawText.length || receipt.identity.isEmpty()) return null
    val regenerated = compileGenericContainerReceipt(rawText, rawText.substring(receipt.identity),
        receipt.count, receipt.size, extended = true)
    return receipt.takeIf { it == regenerated && query == it.literalQuery() && strictMatchPhrase == query }
}

/** Mirrors the source-only backend compiler. The legacy production is default;
 * extended forms cannot broaden existing V2/API behavior accidentally. */
internal fun compileGenericContainerReceipt(source: String, name: String,
    count: IntentLocalAmount, size: IntentLocalAmount?, extended: Boolean = false): GenericContainerReceipt? {
    if (source.isEmpty() || source.length > 6000 || source.any(Char::isISOControl) ||
        count.unit !in setOf("count", "pack") || (size == null && !extended) ||
        (size != null && size.unit !in setOf("g", "kg", "ml", "l")) ||
        !genericContainerNamePattern.matches(name)) return null
    val countValue = runCatching { count.value.intValueExact() }.getOrNull() ?: return null
    if (countValue !in 1..20 || (size != null && size.value.signum() <= 0)) return null
    for (amount in listOfNotNull(count, size)) {
        if (amount.source != source || amount.startUtf16 < 0 || amount.endUtf16 <= amount.startUtf16 ||
            amount.endUtf16 > source.length || source.substring(amount.startUtf16, amount.endUtf16) != amount.atom ||
            parseIntentCorrectionAmount(amount.atom)?.compareTo(amount.value) != 0) return null
    }
    if (source.substring(0, count.startUtf16).any { it != ' ' } ||
        (size != null && count.endUtf16 >= size.startUtf16)) return null
    val sizeSpan = size?.let {
        val unit = Regex(
            "^[ ]*-?[ ]*(?:g|gm|gms|grams?|kg|kgs|kilos?|kilograms?|keji|ml|millilitres?|milliliters?|l|ltr|litres?|liters?)\\b(?!['’]\\p{L})",
            RegexOption.IGNORE_CASE,
        ).find(source.substring(it.endUtf16)) ?: return null
        if (!genericContainerUnitMatches(unit.value.trimStart(' ').trimStart('-').trimStart(' '), it.unit)) return null
        it.startUtf16 until it.endUtf16 + unit.value.length
    }
    val wrappers = Regex("\\b(?:${groceryIntentGenericContainerWords.joinToString("|")})\\b")
        .findAll(source.asciiLowercase()).map { it.range }.toList()
    val wrapper = wrappers.singleOrNull() ?: return null
    val requests = if (extended) groceryIntentClosedHindiRequestRanges(source) else emptyList()
    val connectors = if (extended) Regex("\\b(?:ka|ki|ke)\\b")
        .findAll(source.asciiLowercase()).map { it.range }.toList() else emptyList()
    if (requests.size > 1 || connectors.size > 1) return null
    val request = requests.singleOrNull()
    val connector = connectors.singleOrNull()
    val anchors = mutableMapOf("count" to (count.startUtf16 until count.endUtf16), "wrapper" to wrapper)
    sizeSpan?.let { anchors["size"] = it }
    request?.let { anchors["request"] = it }
    connector?.let { anchors["connector"] = it }
    val allowed = mutableSetOf(listOf("count", "wrapper", "identity", "size"))
    if (extended) allowed += setOf(
        listOf("count", "wrapper", "identity", "request", "size"),
        listOf("count", "size", "identity", "connector", "wrapper"),
        listOf("count", "size", "identity", "connector", "wrapper", "request"),
        listOf("count", "wrapper", "size", "identity"),
        listOf("count", "wrapper", "size", "identity", "request"),
    )
    if (size == null) {
        allowed.clear()
        allowed += setOf(listOf("count", "wrapper", "identity"), listOf("count", "wrapper", "identity", "request"))
    }
    val anchorOrder = anchors.keys.sortedBy { anchors.getValue(it).first }
    val receipts = mutableListOf<GenericContainerReceipt>()
    for (production in allowed) {
        if (production.filter { it != "identity" } != anchorOrder) continue
        val position = production.indexOf("identity")
        val left = anchors.getValue(production[position - 1]).last + 1
        val right = production.getOrNull(position + 1)?.let { anchors.getValue(it).first } ?: source.length
        if (left > right) continue
        val identityStart = left + source.substring(left, right).takeWhile { it == ' ' }.length
        var identityEnd = source.substring(0, right).trimEnd(' ').length
        if (position == production.lastIndex && identityEnd > identityStart && source[identityEnd - 1] in ",;.!?") {
            identityEnd = source.substring(0, identityEnd - 1).trimEnd(' ').length
        }
        if (identityStart >= identityEnd) continue
        val identity = identityStart until identityEnd
        val nominal = source.substring(identity)
        if (!genericNominalAllowed(nominal, extended, size == null) ||
            (!extended && !nominal.asciiEquals(name)) ||
            (extended && !genericLiteralWords(nominal).containsAll(genericLiteralWords(name)))) continue
        val spans = anchors + ("identity" to identity)
        val owners = production.map { it to spans.getValue(it) }
        if (owners.zipWithNext().any { (prior, current) ->
                prior.second.last + 1 >= current.second.first ||
                    !Regex(if (prior.first == "request" && current.first == "size") "[ ]*,[ ]*" else "[ ]+")
                        .matches(source.substring(prior.second.last + 1, current.second.first))
            }) continue
        if (!Regex("[ ]*[,;.!?]?[ ]*").matches(source.substring(owners.last().second.last + 1))) continue
        val requestedSize = if (size != null) GenericContainerRequestedSize.Exact(size, requireNotNull(sizeSpan))
            else GenericContainerRequestedSize.Unspecified
        receipts += GenericContainerReceipt(source, count, requestedSize, wrapper, identity, request, connector)
    }
    return receipts.distinct().singleOrNull()
}

private val genericContainerFormNouns = setOf(
    "bags", "bottles", "cartons", "cups", "cans", "tins", "tubs", "jars", "boxes", "bars", "blocks",
    "pouches", "sachets", "tubes", "loaves", "refills", "rolls", "sprays", "bunches",
)
private val genericRelativeSizeWords = setOf(
    "small", "big", "large", "medium", "mini", "jumbo", "family", "chhota", "chota", "chhoti", "choti",
    "chhote", "chote", "bada", "badi", "bade",
)
private fun genericLiteralWords(text: String): Set<String> =
    Regex("[\\p{L}]+").findAll(text.asciiLowercase()).map { it.value }.toSet()

private fun genericNominalAllowed(nominal: String, extended: Boolean, unspecified: Boolean): Boolean {
    if (!genericContainerNamePattern.matches(nominal)) return false
    val denial = Normalizer.normalize(nominal, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    val words = Regex("[\\p{L}]+").findAll(denial).map { it.value }.toList()
    val form = extended && words.size > 1 && words.last() in genericContainerFormNouns
    val checked = if (form) words.dropLast(1) else words
    if (checked.any { it in forbiddenGenericContainerNameWords } || sourceNominalHasAmountOrRelation(nominal)) return false
    if (extended && (words.any { it in groceryIntentNominalScopeUnsafeWords && it != "of" } ||
            groceryIntentHasNominalClausePredicate(denial))) return false
    return !(unspecified && words.any { it in genericRelativeSizeWords })
}

internal fun GenericContainerReceipt.literalFormNoun(): String? =
    source.substring(identity).substringAfterLast(' ').asciiLowercase().takeIf { it in genericContainerFormNouns }

internal fun GenericContainerReceipt.instructionContextProven(instruction: String, sources: List<String>): Boolean {
    if (requestedSize != GenericContainerRequestedSize.Unspecified) return true
    return groceryIntentClosedRetailContext(instruction, sources, source, maxOf(identity.last + 1, request?.last?.plus(1) ?: 0))
}

internal fun groceryIntentClosedRetailContext(instruction: String, sources: List<String>, source: String, end: Int): Boolean {
    if (!groceryIntentSourceClauseEndProven(instruction, source, end)) return false
    // Absence is a whole-instruction fact: a cropped prefix/suffix must not
    // turn a stated size, diet or other constraint into an unspecified offer.
    val primaries = sources.map { bindGroceryIntentProductSource(instruction, it, maxLength = 6000) ?: return false }
    if (primaries.isEmpty() || primaries.indices.any { left -> (left + 1 until primaries.size).any { right ->
            primaries[left].overlaps(primaries[right])
        } }) return false
    val target = primaries.singleOrNull { it.text == source } ?: return false
    if (primaries.any { it.startUtf16 < target.startUtf16 } && !Regex(
            "(?:[,.;!?\\r\\n]|(?<![\\p{L}\\p{N}_])(?:and|aur))$", RegexOption.IGNORE_CASE,
        ).containsMatchIn(instruction.substring(0, target.startUtf16).trimEnd(' ', '\t'))) return false
    return groceryIntentCoordinatedItemContextProven(instruction, primaries.sortedBy { it.startUtf16 })
}

internal fun groceryIntentCoordinatedItemContextProven(instruction: String, primaries: List<GroceryIntentReviewV2Quote>): Boolean {
    if (primaries.isEmpty() || !primaries.zipWithNext().all { (a, b) -> a.endUtf16 <= b.startUtf16 }) return false
    val connectors = primaries.zipWithNext().mapNotNull { (left, right) ->
        val gap = instruction.substring(left.endUtf16, right.startUtf16)
        if (Regex("[ \\t,.;!?\\r\\n]*(?:and|aur)[ \\t,.;!?\\r\\n]*", RegexOption.IGNORE_CASE).matches(gap))
            GroceryIntentReviewV2Quote(gap, left.endUtf16, right.startUtf16) else null
    }
    return groceryIntentOwnedContextComplete(instruction, primaries + connectors, primaries)
}

/** The complete vocabulary is shared with the backend detector for parity tests. */
internal val groceryIntentGenericContainerWords: Set<String> = setOf(
    "dabba", "dabbe", "dibba", "dibbe", "dabbi", "dibbi",
)

internal val forbiddenGenericContainerNameWords = setOf(
    "khaali", "kanch", "stil", "plastik", "tetra", "tetrapack", "tetrapacks",
    "container", "containers", "pack", "packs", "packet", "packets", "bag", "bags", "bottle", "bottles",
    "carton", "cartons", "cup", "cups", "can", "cans", "tin", "tins", "tub", "tubs", "jar", "jars",
    "box", "boxes", "bar", "bars", "block", "blocks", "pouch", "pouches", "sachet", "sachets", "tube", "tubes",
    "loaf", "loaves", "refill", "refills", "roll", "rolls", "spray", "sprays", "bunch", "bunches",
    "crate", "crates", "tray", "trays", "case", "cases", "dabba", "dabbe", "dibba", "dibbe", "dabbi", "dibbi",
    "set", "sets", "tiffin", "lunch", "steel", "plastic", "glass", "kaanch",
    "airtight", "khali", "empty", "storage", "metal", "aluminium", "aluminum", "wooden", "wood",
    "lid", "lids", "capacity", "holder", "holders", "organizer", "organizers", "canister", "canisters",
    "ka", "ki", "ke", "wala", "wali", "wale",
)

private val genericContainerNamePattern = Regex("[\\p{L}]+(?:['’-][\\p{L}]+)*(?: +[\\p{L}]+(?:['’-][\\p{L}]+)*)*")

private fun genericContainerNameMatches(
    sourceName: String,
    proposedName: String,
    item: GroceryIntentReviewV2Item,
): Boolean {
    if (!genericContainerNamePattern.matches(sourceName) || !genericContainerNamePattern.matches(proposedName)) return false
    if (!sourceName.asciiEquals(proposedName)) return false
    val words = Regex("[\\p{L}]+").findAll(sourceName)
        .map { it.value.asciiLowercase() }.toSet()
    if (words.any { it in forbiddenGenericContainerNameWords }) return false
    val excluded = item.excluded.flatMap { intentEvidenceTokens(it.replace('_', ' ')) }.toSet()
    val literalIdentity = intentEvidenceTokens(item.name) - excluded
    if (item.required.any { required ->
            val requiredTokens = intentEvidenceTokens(required.replace('_', ' '))
            requiredTokens.isEmpty() || !literalIdentity.containsAll(requiredTokens)
        }) return false
    return true
}

private fun genericContainerUnitMatches(unit: String, expected: String): Boolean = when (expected) {
    "g" -> unit.asciiLowercase() in setOf("g", "gm", "gms", "gram", "grams")
    "kg" -> unit.asciiLowercase() in setOf("kg", "kgs", "kilo", "kilos", "kilogram", "kilograms", "keji")
    "ml" -> unit.asciiLowercase() in setOf("ml", "millilitre", "millilitres", "milliliter", "milliliters")
    "l" -> unit.asciiLowercase() in setOf("l", "ltr", "litre", "litres", "liter", "liters")
    else -> false
}

private fun String.asciiEquals(other: String): Boolean = asciiLowercase() == other.asciiLowercase()

private fun String.asciiLowercase(): String = buildString(length) {
    for (char in this@asciiLowercase) append(if (char in 'A'..'Z') char + ('a' - 'A') else char)
}
