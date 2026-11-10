package com.example.beta

import android.app.Activity
import android.os.SystemClock
import android.util.Log
import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.SwiggyMcpClient.Recommendations
import com.example.beta.SwiggyMcpClient.RequestedItem
import com.example.beta.SwiggyMcpClient.SwiggyAddress
import com.example.beta.SwiggyMcpClient.SwiggyMcpResult
import com.example.beta.automation.InstructionParser
import com.example.beta.automation.IntentCartQuantityMode
import com.example.beta.automation.ParsedItem
import com.example.beta.automation.Preference
import com.example.beta.automation.PreferenceStore
import com.example.beta.automation.Quantity
import com.example.beta.automation.backendInputText

internal const val MAX_SWIGGY_MCP_ITEMS = 50
internal const val SWIGGY_RECOMMENDATION_BATCH_SIZE = 25
internal const val SWIGGY_ADDRESS_PAGE_SIZE = 8
private const val WHOLE_EDIT_PROMPT = "Your whole message is in the box. Move to the part you want to change, or say 'read my message'."

internal fun prepareSwiggyMcpItems(
    instruction: String,
    lookup: (String) -> Preference?,
): List<ParsedItem> {
    if (hasExplicitGroceryIntentProductOperationCue(instruction)) return emptyList()
    val sanitized = CommerceProviderRouter.sanitizeOrderInstruction(instruction)
    return InstructionParser.applyPreferences(
        InstructionParser.parse(sanitized),
        lookup = lookup,
    )
}

internal fun swiggyMcpItemValidationMessage(
    instruction: String,
    items: List<ParsedItem>,
): String? {
    if (items.size > MAX_SWIGGY_MCP_ITEMS) {
        return "Swiggy supports up to $MAX_SWIGGY_MCP_ITEMS items in one Beta cart run. Please split this list so nothing is skipped."
    }
    // Inner package contents are not actionable cart units. Exempt only the
    // exact source span owned by a currently valid receipt, never all numbers
    // in a row or an unbound model-provided pack label.
    val ownedContents = items.flatMap { item ->
        val amounts = listOfNotNull(item.validContainerContentsProof()?.inner, item.validSourcePieceAmount(),
            item.validCompositeRetailReceipt()?.skuFactor)
        val offset = instruction.indexOf(item.rawText)
        if (offset < 0 || offset != instruction.lastIndexOf(item.rawText)) return@flatMap emptyList()
        amounts.map { amount -> (offset + amount.startUtf16) until (offset + amount.endUtf16) }
    }
    val oversizedCountQuery = Regex("\\b([1-9]\\d+)\\s+([A-Za-z][\\w-]*)", RegexOption.IGNORE_CASE)
        .findAll(instruction)
        .mapNotNull { match ->
            if (ownedContents.any { it.first == match.range.first && it.last + 1 >= match.range.first + match.groupValues[1].length }) {
                return@mapNotNull null
            }
            if (InstructionParser.isNumericBrandPrefix(instruction.substring(match.range.first))) {
                return@mapNotNull null
            }
            val count = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val followingWord = match.groupValues[2].lowercase()
            val isMeasureOrPack = followingWord in setOf(
                "g", "gm", "gms", "gram", "grams", "kg", "kgs",
                "ml", "l", "ltr", "liter", "litre", "liters", "litres",
                "pack", "pk", "pc", "pcs", "piece", "pieces",
            )
            if (count > 20 && !isMeasureOrPack) followingWord else null
        }
        .firstOrNull()
    oversizedCountQuery?.let {
        return "Swiggy supports up to quantity 20 per item. Please reduce the quantity for $it; nothing was changed."
    }
    if (items.any { it.quantitySignal != null }) {
        return "Please write the quantity using digits, for example 2 milk or 500 g rice. Beta could not safely understand a quantity; nothing was changed."
    }
    if (items.any { item -> (item.quantity as? Quantity.Count)?.n?.let { n ->
            n !in 1..(if (item.logicalPieceCount) 1000 else 20)
        } == true }) {
        return "Swiggy supports quantity 1 to 20 per item, including repeated items. Please reduce the quantity; nothing was changed."
    }
    if (items.groupBy { it.query }.any { (_, repeated) -> repeated.size > 1 }) {
        return "One product appears with different quantities or preferences. Please combine it into one clear line; nothing was changed."
    }
    if (Regex("(?:^|[\\s,;])(?:[-−]\\d+(?:\\.\\d+)?|0(?:\\.0+)?)\\s*(?=\\p{L})").containsMatchIn(instruction)) {
        return "Please use a positive quantity, or remove that item from your list. Nothing was changed."
    }
    return null
}

internal fun swiggyNoCandidateMessage(
    item: ParsedItem,
    address: SwiggyAddress,
    hasUnverifiedResults: Boolean = false,
): String {
    val strictMatchPhrase = item.strictMatchPhrase?.trim().takeIf { !it.isNullOrBlank() }
    val identity = swiggyIdentityTokens(item.query)
    if ("readytodrink" in identity && ("sugarfree" in identity || "sugar" in item.avoidPhrases)) {
        return "I could not verify a ready-to-drink tea or coffee with your sugar preference. I have not swapped it for powder or sweetened tea. You can edit this item or leave it out."
    }
    if ("samosa" in identity && item.quantity is Quantity.Count && !item.retailPackCount) {
        return "I could not verify the exact number of prepared samosas here. I have not substituted a frozen bag or a larger pack. You can edit this item or leave it out."
    }
    if ("lowfat" in identity) {
        return "I could not verify lower-fat milk from the product label. I have not swapped it for regular or full-cream milk. You can edit this item or leave it out."
    }
    if (hasUnverifiedResults) {
        return "Beta could not confirm the product or pack you asked for from Swiggy's results. Edit this item or leave it out. Nothing was added."
    }
    return if (strictMatchPhrase != null) {
        "I could not verify this exact product and pack: $strictMatchPhrase. You can edit it or leave it out."
    } else {
        "No confirmed match for ${item.query} at ${address.shortLabel}. You can edit it or leave it out."
    }
}

internal fun isSafeSwiggyCartPlan(
    plan: SwiggyMcpClient.CartPlan,
    selected: List<RequestedItem>,
): Boolean {
    if (selected.isEmpty() || plan.changes.isEmpty()) return false
    val requested = linkedMapOf<String, Pair<Int, IntentCartQuantityMode>>()
    val requestedSkus = mutableMapOf<String, String?>()
    val knownLimits = mutableMapOf<String, Int>()
    for ((spinId, items) in selected.groupBy { it.spinId.trim() }) {
        if (items.any { row -> row.cataloguePurchaseLimit?.validFor(row.spinId, row.skuId) == false }) return false
        items.mapNotNull { it.cataloguePurchaseLimit?.maxUnits }.minOrNull()?.let { knownLimits[spinId] = it }
        if (spinId.isBlank() || items.any { it.quantity !in 1..20 } ||
            items.map { it.quantityMode }.distinct().size != 1 ||
            items.mapNotNull { it.skuId?.trim()?.takeIf { sku -> sku.isNotEmpty() } }.distinct().size > 1) return false
        if (items.first().quantityMode == IntentCartQuantityMode.INCREMENT && items.size != 1) return false
        val total = if (items.first().quantityMode == IntentCartQuantityMode.TARGET) {
            items.maxOf { it.quantity }.toLong()
        } else {
            items.fold(0L) { sum, item -> sum + item.quantity.toLong() }
        }
        if (total !in 1L..20L) return false
        requested[spinId] = total.toInt() to items.first().quantityMode
        requestedSkus[spinId] = items.mapNotNull { it.skuId?.trim()?.takeIf(String::isNotEmpty) }.firstOrNull()
    }
    val seen = mutableSetOf<String>()
    val safeKinds = setOf("add", "increase", "change", "update")
    val validChanges = plan.changes.all { change ->
        val spinId = change.spinId?.trim().orEmpty()
        val kind = change.kind.trim().lowercase()
        val request = requested[spinId]
        val from = change.fromQuantity
        val to = change.toQuantity
        spinId.isNotBlank() &&
            kind in safeKinds &&
            seen.add(spinId) &&
            request != null &&
            from != null && from >= 0 &&
            to != null && to > from && to <= (knownLimits[spinId] ?: Int.MAX_VALUE) && when (request.second) {
                IntentCartQuantityMode.TARGET -> to == request.first
                IntentCartQuantityMode.INCREMENT -> {
                    val expected = from.toLong() + request.first.toLong()
                    expected <= 20L && to.toLong() == expected
                }
            }
    }
    if (!validChanges || !requested.all { (spinId, request) ->
        request.second != IntentCartQuantityMode.INCREMENT || spinId in seen
    }) return false
    // Older responses have no full snapshot. Preserve their existing flow, but
    // they cannot provide the basis required for future optional-row choices.
    val snapshot = plan.snapshot ?: return true
    fun byId(rows: List<SwiggyMcpClient.CartPlanItem>): Map<String, SwiggyMcpClient.CartPlanItem>? {
        if (rows.any { it.spinId.isBlank() || it.spinId != it.spinId.trim() || it.quantity <= 0 ||
                it.skuId?.let { sku -> sku.isBlank() || sku != sku.trim() } == true } ||
            rows.map { it.spinId }.distinct().size != rows.size) return null
        return rows.associateBy { it.spinId }
    }
    val existing = byId(snapshot.existingItems) ?: return false
    val proposed = byId(snapshot.proposedItems) ?: return false
    if (plan.existingItemCount != null && plan.existingItemCount != existing.size) return false
    val expected = existing.toMutableMap()
    for ((spinId, request) in requested) {
        val current = existing[spinId]
        val sku = requestedSkus[spinId]
        if (current?.skuId != null && sku != null && current.skuId != sku) return false
        val quantity = when (request.second) {
            IntentCartQuantityMode.TARGET -> maxOf(current?.quantity ?: 0, request.first).toLong()
            IntentCartQuantityMode.INCREMENT -> (current?.quantity ?: 0).toLong() + request.first
        }
        if (quantity !in 1L..20L) return false
        expected[spinId] = SwiggyMcpClient.CartPlanItem(spinId, quantity.toInt(), sku ?: current?.skuId)
    }
    if (proposed != expected) return false
    val expectedChanges = proposed.filter { (id, item) -> item.quantity != (existing[id]?.quantity ?: 0) }
    return seen == expectedChanges.keys && plan.changes.all { change ->
        change.fromQuantity == (existing[change.spinId]?.quantity ?: 0) &&
            change.toQuantity == proposed[change.spinId]?.quantity
    }
}

internal fun swiggyCartStartsEmpty(plan: SwiggyMcpClient.CartPlan): Boolean =
    plan.existingItemCount == 0

internal fun areSwiggyRecommendationsOrdered(
    recommendations: List<Recommendations>,
    queries: List<String>,
): Boolean {
    return recommendations.size == queries.size &&
        recommendations.zip(queries).all { (recommendation, query) ->
            recommendation.query?.equals(query, ignoreCase = true) == true
        }
}

internal fun rememberedSwiggyAddress(
    addresses: List<SwiggyAddress>,
    rememberedAddressId: String?,
): SwiggyAddress? {
    val remembered = rememberedAddressId
        ?.takeIf { it.isNotBlank() }
        ?.let { id -> addresses.firstOrNull { it.id == id } }
        ?: return null
    val currentCartAddress = addresses.firstOrNull(SwiggyAddress::hasCurrentCart)
    return remembered.takeIf { currentCartAddress == null || currentCartAddress.id == remembered.id }
}

internal fun isRememberedSwiggyAddressFresh(
    selectedAtElapsedRealtime: Long,
    nowElapsedRealtime: Long,
    ttlMillis: Long,
): Boolean = selectedAtElapsedRealtime > 0L &&
    nowElapsedRealtime >= selectedAtElapsedRealtime &&
    nowElapsedRealtime - selectedAtElapsedRealtime <= ttlMillis

internal fun swiggyAddressChoiceLabel(address: SwiggyAddress): String {
    return address.shortLabel.trim().ifBlank { "Saved Swiggy address" }
}

internal fun swiggyAddressChoiceLabels(addresses: List<SwiggyAddress>): List<String> {
    val baseLabels = addresses.map(::swiggyAddressChoiceLabel)
    val counts = baseLabels.groupingBy { it.lowercase() }.eachCount()
    return baseLabels.mapIndexed { index, label ->
        if (counts[label.lowercase()] == 1) label else "$label — saved address ${index + 1}"
    }
}

internal fun swiggySpokenAddressConfirmation(address: SwiggyAddress): String {
    val category = address.categoryLabel.trim().ifBlank { "saved address" }
    val detail = address.confirmationDetail
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.replace(Regex("\\b\\d{6}\\b"), "")
        ?.replace(Regex("\\s+"), " ")
        ?.trim(' ', ',')
        ?.split(' ')
        ?.take(16)
        ?.joinToString(" ")
        ?.takeIf(String::isNotBlank)
    return if (detail == null) "You selected $category." else "You selected $category, $detail."
}

internal fun swiggyAddressSuggestionDetail(
    address: SwiggyAddress,
    index: Int,
    suggestionReasons: Map<String, String>,
): String {
    val reason = suggestionReasons[address.id] ?: return "Saved in Swiggy"
    return if (index == 0) "Suggested · $reason" else reason
}

internal fun swiggyAddressLocationNotice(assessment: SwiggyLocationAssessment): String = when (assessment) {
    SwiggyLocationAssessment.UNKNOWN -> "Location is unavailable or not reliable. You can still use a saved address. Please check the house or flat and area."
    SwiggyLocationAssessment.AREA_MATCH -> "This address matches your current area, not an exact GPS distance. Please check the house or flat."
    SwiggyLocationAssessment.NOT_MATCHED -> "This address could not be matched to your current area. Please confirm this is where you want the groceries, or choose a different address."
}

internal fun swiggyMatchedWithoutQuestionMessage(matchedCount: Int): String? = when (matchedCount) {
    0 -> null
    1 -> "1 other item matched without a question. Choose one exact pack for this item."
    else -> "$matchedCount other items matched without a question. Choose one exact pack for this item."
}

internal fun swiggyCandidateDetail(candidate: RecommendationCandidate): String? {
    val label = candidate.label.trim()
    return swiggyRawCatalogueFields(candidate).drop(1)
        .map(String::trim)
        .filter { it.isNotBlank() && !label.contains(it, ignoreCase = true) }
        .distinctBy(String::lowercase)
        .joinToString(" · ")
        .ifBlank { null }
}

internal fun swiggyCandidateLabel(candidate: RecommendationCandidate): String {
    val label = candidate.label.trim()
    val extras = swiggyRawCatalogueFields(candidate).drop(1)
        .map(String::trim)
        .filter { it.isNotBlank() && !label.contains(it, ignoreCase = true) }
        .distinctBy(String::lowercase)
    return (listOf(label) + extras).filter(String::isNotBlank).joinToString(" — ")
}

internal fun swiggyRecommendationQuery(item: ParsedItem): String {
    val quantityAware = when (item.quantity) {
        is Quantity.Weight, is Quantity.Volume -> item.backendInputText()
        is Quantity.Count, Quantity.Default -> item.query
    }
    return if (item.avoidPhrases.isEmpty()) quantityAware else {
        "$quantityAware without ${item.avoidPhrases.joinToString(" or ")}"
    }
}

private const val candidatePieceUnits = "(?:pieces?|pcs?|eggs?)"
private val candidatePieceUnitRegex = Regex("\\b$candidatePieceUnits\\b", RegexOption.IGNORE_CASE)
private val candidatePieceCountRegex = Regex(
    "\\b([1-9]\\d?)\\s*$candidatePieceUnits\\b",
    RegexOption.IGNORE_CASE,
)
private val candidatePieceCompositionRegex = Regex(
    "[×+]|\\b(?:x|times|each|per|extra|bonus|additional|pack\\s+of|packs|trays|sleeves|boxes|bundles?|combos?|multipacks?)\\b",
    RegexOption.IGNORE_CASE,
)

private fun swiggyExplicitPieceCount(candidate: RecommendationCandidate): Int? {
    val counts = candidatePieceCountRegex.findAll(swiggyCandidateLabel(candidate))
        .map { it.groupValues[1].toInt() }.distinct().toList()
    return if (counts.isEmpty()) null else counts.singleOrNull() ?: 0
}

/** Logical pieces require one evidenced total, not a guessed nested bundle. */
private fun swiggyLogicalPieceCartQuantity(item: ParsedItem, candidate: RecommendationCandidate): Int? {
    val requested = (item.quantity as? Quantity.Count)?.n ?: return null
    if (requested !in 1..1000) return null
    val label = swiggyCandidateLabel(candidate)
    // "6 pcs x 2" and "pack of 2, 6 eggs each" do not mean six pieces per
    // cart unit. Do not reuse the retail-package divisor for logical pieces.
    val directTotalLabel = label.replace(Regex(
        "\\b(?:pack|tray)\\s+of\\s+(?=[1-9]\\d?\\s*(?:pieces?|pcs?|eggs?)\\b)",
        RegexOption.IGNORE_CASE,
    ), "")
    if (candidatePieceCountRegex.findAll(directTotalLabel).count() != 1) return null
    val remaining = candidatePieceCountRegex.replace(directTotalLabel, " ")
    if (remaining.any(Char::isDigit) || candidatePieceCompositionRegex.containsMatchIn(remaining)) return null
    val pieces = swiggyExplicitPieceCount(candidate) ?: return null
    return if (pieces > 0 && requested % pieces == 0) (requested / pieces).takeIf { it in 1..20 } else null
}

/** A validated literal pack total is not brand identity. Mask only that exact
 * closed piece-unit grammar, never arbitrary numbers or product words. */
internal fun swiggyQuantityIdentityTitle(item: ParsedItem, candidate: RecommendationCandidate, title: String): String {
    if (item.retailPackCount || swiggyLogicalPieceCartQuantity(item, candidate) == null) return title
    return candidatePieceCountRegex.replace(title, " ")
}

private fun swiggyRetailMultipackCount(candidate: RecommendationCandidate, includeInnerPieces: Boolean): Int? {
    if (swiggyCatalogueHasUnresolvedBundle(swiggyRawCatalogueFields(candidate))) return 0
    // Signs must not be ignored as punctuation before an apparently valid count.
    val label = swiggyCandidateLabel(candidate).replace('\u2212', '-').replace('\uFF0D', '-').replace('\uFF0B', '+')
    // A catalogue count of containers is a bundle in every interpretation mode,
    // not just V2. Missing cup/jar/etc. units silently turned three into one.
    val packageUnit = "(?:tetra[\\s-]*packs?|packs?|packets?|bags?|bottles?|cartons?|cups?|cans?|tins?|tubs?|jars?|" +
        "box(?:es)?|bars?|blocks?|pouch(?:es)?|sachets?|tubes?|loaf|loaves|refills?|rolls?|sprays?|bunch(?:es)?|matchbox(?:es)?" +
        (if (includeInnerPieces) "|pieces?|pcs?|eggs?" else "") + ")"
    val text = label.replace(Regex(
        "\\b(?:pack|tray)\\s+of\\s+(?=[1-9]\\d{0,2}\\s*$packageUnit\\b)", RegexOption.IGNORE_CASE,
    ), "")
    // Inner and outer counts cannot be collapsed just because they agree:
    // "3 pcs x 3" is nine pieces, not three. No nesting is inferred here.
    val directUnits = Regex("\\b[0-9]+\\s*[-‐‑‒–—]*\\s*$packageUnit\\b", RegexOption.IGNORE_CASE)
    val hasDirectUnits = directUnits.containsMatchIn(text)
    if (hasDirectUnits && (Regex("\\b$packageUnit\\s*(?:[x×]|each\\b)", RegexOption.IGNORE_CASE).containsMatchIn(text) ||
            Regex("\\bpack\\s+of\\b|(?:(?<![\\p{L}])x|×)\\s*[0-9]", RegexOption.IGNORE_CASE).containsMatchIn(text))) return 0
    if (hasDirectUnits) {
        if (directUnits.findAll(text).count() != 1) return 0
        val remainder = directUnits.replace(text, " ")
            .replace(Regex("\\b[0-9]+(?:\\.[0-9]+)?\\s*(?:kg|kgs|g|gm|gms|grams?|ml|l|ltr|litres?|liters?)\\b", RegexOption.IGNORE_CASE), " ")
        // Do not guess which leftover number is an outer bundle multiplier.
        // Physical weight/volume metadata is independent, but nested counts,
        // offers and 'per'/'each' descriptions need an authoritative total.
        if (groceryIntentContainsNumericAtom(remainder) || Regex("[×+]|\\b(?:x|times|each|per|extra|bonus|additional|plus|sets?|bundles?|combos?|multipacks?|sleeves|packs|trays|boxes)\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(remainder)) return 0
    }
    // Consume malformed numeric clusters as a whole so a valid-looking suffix
    // (e.g. the 2 in 1.5.2) cannot become an outer count.
    val number = "[+-]?(?:\\d+(?:[.,/+\\-]+\\d+)*|[.,]\\d+)(?:[eE][+-]?\\d+)?"
    val countStart = "(?<![\\p{L}\\p{N}.,/+\\-])"
    val countEnd = "(?![\\p{L}\\p{N}./+\\-]|,(?!\\s))"
    val size = "\\d+(?:\\.\\d+)?"
    val measure = "(?:kg|kgs|g|gm|gms|grams?|ml|l|ltr|litres?|liters?)"
    // Offers and additive units need an authoritative whole-bundle total;
    // identical '1 bar + 1 bar' counts must not collapse to a single unit.
    if (Regex(
            "\\bbuy\\s*[0-9]+\\s*get\\s*[0-9]+\\b|\\b[0-9]+\\s*(?:free|extra|bonus|additional)\\b|" +
                "\\+\\s*$number\\s*$packageUnit\\b",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(text)) return 0
    val patterns = listOf(
        Regex("$countStart($number)\\s*[x×]\\s*$size\\s*$measure\\b", RegexOption.IGNORE_CASE),
        Regex("\\b$size\\s*$measure\\s*[x×]\\s*($number)$countEnd", RegexOption.IGNORE_CASE),
        Regex("\\bpack\\s+of\\s+$countStart($number)$countEnd", RegexOption.IGNORE_CASE),
        Regex("$countStart($number)\\s*[-‐‑‒–—]*\\s*$packageUnit\\b", RegexOption.IGNORE_CASE),
        Regex("\\b$packageUnit\\s*[x×]\\s*($number)$countEnd", RegexOption.IGNORE_CASE),
    )
    val matches = patterns.flatMap { it.findAll(text).toList() }
    // Equal suffix counts can still be additive or nested: "cups x 3 and
    // cups x 3" is not three cups. Never deduplicate unproved totals.
    if (matches.size > 1) return 0
    // A recognizable bundle marker with an unsupported number/size is not
    // evidence of one unit. Do not fall through after a partial numeric parse.
    val bundleCues = Regex(
        "\\bpack\\s+of\\b|\\b[0-9]+\\s*[x×](?=\\s*[+-]?[0-9])|\\b$measure\\s*[x×](?=\\s*[+-]?[0-9])|\\b$packageUnit\\s*[x×]",
        RegexOption.IGNORE_CASE,
    ).findAll(text)
    if (bundleCues.any { cue -> matches.none { it.range.first <= cue.range.first && it.range.last >= cue.range.last } }) return 0
    val counts = matches.map { match ->
        val raw = match.groupValues[1]
        // Explicit malformed, nonpositive, out-of-range, or overflowing counts
        // are a fail-closed sentinel, never an unknown single retail unit.
        raw.takeIf { it.matches(Regex("[0-9]+")) }
            ?.toIntOrNull()?.takeIf { it in 1..999 } ?: 0
    }.distinct()
    // Conflicting multipliers are unknown, not evidence of a single unit.
    return if (counts.isEmpty()) null else counts.singleOrNull() ?: 0
}

/** One decision feeds discovery, review and the final cart boundary. A numeric
 * count without a proved retail/piece relationship is an offer, not an exact match. */
internal sealed class SwiggyCartQuantityResolution {
    data class Exact(val cartUnits: Int) : SwiggyCartQuantityResolution()
    data class RetailProposal(val cartUnits: Int, val contents: SwiggyRetailOfferContents) : SwiggyCartQuantityResolution() {
        constructor(cartUnits: Int, packSize: String) : this(cartUnits, SwiggyRetailOfferContents.DisplayOnly(packSize))
        val packSize: String get() = contents.display
    }
    data class Held(val reason: String) : SwiggyCartQuantityResolution()
}

internal fun resolveSwiggyCartQuantity(
    item: ParsedItem,
    candidate: RecommendationCandidate,
): SwiggyCartQuantityResolution {
    val resolved = resolveSwiggyCartQuantityRules(item, candidate)
    val units = when (resolved) {
        is SwiggyCartQuantityResolution.Exact -> resolved.cartUnits
        is SwiggyCartQuantityResolution.RetailProposal -> resolved.cartUnits
        is SwiggyCartQuantityResolution.Held -> return resolved
    }
    candidate.purchaseLimitHold(units)?.let { return SwiggyCartQuantityResolution.Held(it) }
    val subject = item.measuredProduceSubject ?: return resolved
    val grams = (item.quantity as? Quantity.Weight)?.grams
    val pack = swiggyProvenRetailOfferContents(candidate, subject)?.measure
    return if (resolved is SwiggyCartQuantityResolution.Exact && subject == item.query && subject == item.strictMatchPhrase &&
        swiggyMeasuredProduceSubjectMatches(subject, candidate) && grams != null && pack?.dimension == GroceryPackDimension.WEIGHT &&
        pack.baseValue.multiply(resolved.cartUnits.toBigDecimal()).compareTo(grams.toBigDecimal()) == 0) resolved
    else SwiggyCartQuantityResolution.Held("measured_produce_offer_unproven")
}

internal fun RecommendationCandidate.purchaseLimitHold(cartUnits: Int): String? = when {
    cataloguePurchaseLimitInvalid || cataloguePurchaseLimit?.validFor(spinId, skuId) == false -> "seller_limit_invalid"
    cataloguePurchaseLimit != null && cartUnits > cataloguePurchaseLimit.maxUnits -> "seller_limit_exceeded"
    else -> null
}

private fun resolveSwiggyCartQuantityRules(
    item: ParsedItem,
    candidate: RecommendationCandidate,
): SwiggyCartQuantityResolution {
    fun held(reason: String) = SwiggyCartQuantityResolution.Held(reason)
    fun exact(units: Int?) = units?.takeIf { it in 1..20 }
        ?.let { SwiggyCartQuantityResolution.Exact(it) } ?: held("quantity_not_proven")
    if (!swiggyIntentQuantityCandidateChecked(item, candidate)) return held("catalogue_evidence_not_proven")
    if (item.hasAuditedIntent()) return resolveAuditedSwiggyQuantity(item, candidate)
    // A missing or stale local receipt must not downgrade a measured product-first
    // order to the ordinary outer-unit path. This only denies, never promotes a role.
    if (requiresProductFirstRetailProof(item.rawText) && item.validNamedRetailReceipt() == null)
        return held("named_retail_provenance_missing")
    if (!swiggyNamedRetailSizeConfirmed(item, candidate)) return held("named_retail_seller_size_unproven")
    if (!swiggySourceRetailIdentityMatches(item, candidate)) return held("source_retail_identity_not_proven")
    if ((item.intentQuantityProvenance?.sourceCountRole == com.example.beta.automation.IntentSourceCountRole.NAMED_RETAIL ||
            sourceNamedRetailCountReceipt(item.rawText) != null) &&
        !isSwiggyCandidateAllowed(item, candidate)) return held("named_retail_identity_not_proven")
    if (item.containerContentsProof != null && item.validContainerContentsProof() == null) return held("source_contents_not_proven")
    if (item.requiresGenericContainerOffer()) {
        val receipt = item.validGenericContainerReceipt() ?: return held("generic_wrapper_not_proven")
        val offer = swiggyProvenRetailOfferContents(candidate, receipt.literalIdentity())
            ?: return held("generic_wrapper_offer_not_proven")
        val requestedSize = receipt.size?.let { GroceryPackMeasure.of(it.value, it.unit) }
        if (requestedSize != null && (offer.measure.dimension != requestedSize.dimension ||
                offer.measure.baseValue.compareTo(requestedSize.baseValue) != 0)) return held("generic_wrapper_offer_not_proven")
        if (!isSwiggyCandidateAllowed(item, candidate)) return held("generic_wrapper_identity_not_proven")
        val form = receipt.literalFormNoun()
        if (form != null && !intentEvidenceTokens(swiggyCandidateLabel(candidate)).containsAll(intentEvidenceTokens(form)))
            return held("generic_wrapper_form_not_proven")
        // Exclusive proposal path: never inherit an Exact result from model pack
        // flags, a catalogue multipack or the piece-division fallback below.
        return SwiggyCartQuantityResolution.RetailProposal(receipt.count.value.intValueExact(), offer)
    }
    if (item.requiresCompositeRetailOffer()) {
        val receipt = item.validCompositeRetailReceipt() ?: return held("composite_retail_source_not_proven")
        // A physical multipack shape cannot prove that one SKU is one named
        // bar/bottle/carton. Generic pack requests can review that SKU's offer.
        if (receipt.containerKey !in setOf("pack", "packet")) return held("composite_named_unit_not_proven")
        val offer = swiggyCatalogueCompositeShape(candidate) ?: return held("composite_retail_offer_not_proven")
        if (offer.shape != receipt.shape() || !isSwiggyCandidateAllowed(item, candidate))
            return held("composite_retail_offer_mismatch")
        return SwiggyCartQuantityResolution.RetailProposal(receipt.requestedCount.value.intValueExact(), offer)
    }
    if (item.requiresSourcePieceConversion()) {
        // Exclusive source role: never fall through to a title-only piece guess
        // or an outer-package proposal if the catalogue lacks an exact divisor.
        val amount = item.validSourcePieceAmount() ?: return held("source_pieces_not_proven")
        if (!isSwiggyCandidateAllowed(item, candidate)) return held("piece_identity_not_proven")
        val perSku = swiggyCatalogueSourcePieceCount(candidate) ?: return held("catalogue_piece_contents_not_proven")
        val pieces = amount.value.intValueExact()
        return exact(if (pieces % perSku == 0) pieces / perSku else null)
    }
    sourcePacketOfferSyntax(item.rawText, item.intentQuantityProvenance?.terminalContext)?.let { syntax ->
        // A recognized source packet may only become an explicitly reviewed
        // offer. Damaged evidence cannot fall through to one-piece/size guesses.
        if (!item.packetOfferProofMatches(syntax)) return held("packet_offer_source_not_proven")
        if (!isSwiggyCandidateAllowed(item, candidate)) return held("packet_offer_identity_not_proven")
        // Preserve the existing physical-size proposal/acknowledgement contract;
        // typed contents add support for piece-count offers, not a new exact path.
        val contents = swiggyRetailProposalPackSize(candidate)?.let(SwiggyRetailOfferContents::DisplayOnly)
            ?: swiggyProvenRetailOfferContents(candidate, item.rawText.substring(syntax.identity))
            ?: return held("packet_offer_contents_not_proven")
        return SwiggyCartQuantityResolution.RetailProposal(syntax.count.value.intValueExact(), contents)
    }
    if (item.quantity is Quantity.Default) {
        // No stated amount means one sellable SKU on every producer route.
        // Proof flags validate provenance; they must not choose whether the
        // same catalogue count means inner contents or an outer bundle.
        val source = item.intentQuantityProvenance?.primaryText ?: item.rawText
        if (item.copy(rawText = source).untypedSourceNumericIssue() != null)
            return held("default_source_quantity_not_proven")
        // Retrieval may be loose; one-SKU finalization may not discard a
        // remaining variant measure just because no strict marker was set.
        // Typed physical totals use the separate conversion path below.
        if (!isSwiggyCandidateAllowed(item, candidate) ||
            !swiggyStrictPackMatches(item.query, swiggyCandidateLabel(candidate), candidate))
            return held("default_identity_not_proven")
        // A default cart count means one SKU, not an unspecified physical size.
        // Display text can rule a size out, but only this SKU's own quantity
        // evidence can establish the requested weight/volume. Do not infer a
        // divisible purchase total, or apply this to a nutrient claim.
        val sizes = (groceryPackMeasures(item.query) + groceryPackMeasures(item.strictMatchPhrase.orEmpty()))
            .filter { it.dimension == GroceryPackDimension.WEIGHT || it.dimension == GroceryPackDimension.VOLUME }
        if (sizes.isNotEmpty() && (sizes.size != 1 || swiggyCatalogueSourcePhysicalMeasure(candidate, defaultVariant = true) != sizes.single()))
            return held("default_physical_size_not_proven")
        return if (swiggySingleSkuDefaultAllowed(candidate)) exact(1) else held("default_bundle_not_proven")
    }
    item.validContainerContentsProof()?.takeIf { it.container in setOf("pack", "packet") }?.let { source ->
        val literal = item.containerContentsLiteralIdentity()
        val analysis = swiggyCatalogueContents(candidate, literal)
        // A generic wrapper may use this SKU's proven inner count as a reviewed
        // offer. Preserve the outer count, never divide by the inner pieces.
        // Explicit relations (and contradictions) stay on the existing path.
        if (!analysis.issue && analysis.contents == null) {
            if (literal == null || swiggyCatalogueContainerRelationConflicts(candidate, source.container, literal))
                return held("contents_identity_not_proven")
            val count = (item.quantity as? Quantity.Count)?.n?.takeIf { it in 1..20 }
            val offer = swiggyProvenRetailOfferContents(candidate, literal)
            return if (count != null && item.cartQuantityMode == IntentCartQuantityMode.TARGET &&
                item.intentQuantityProvenance?.corrected == null &&
                offer?.measure?.dimension == GroceryPackDimension.PIECES &&
                offer.measure.baseValue.compareTo(source.inner.value) == 0 && isSwiggyCandidateAllowed(item, candidate))
                SwiggyCartQuantityResolution.RetailProposal(count, offer)
            else held("contents_offer_not_proven")
        }
    }
    swiggyCatalogueRetailCartQuantity(item, candidate)?.let { return exact(it) }
    if (item.quantity is Quantity.Weight || item.quantity is Quantity.Volume) {
        return exact(swiggyMeasuredPackQuantity(item, candidate))
    }
    if (swiggyRetailMultipackCount(candidate, true) == 0) return held("bundle_not_proven")
    val requested = item.quantity as? Quantity.Count ?: return held("quantity_not_proven")
    // Only a revalidated source receipt can establish an exact outer count.
    // A model/parser flag or a query containing a pack size is not that receipt.
    // Explicit nested contents were resolved by the typed path above; here the
    // candidate must retain that container identity and affirm one such unit.
    // An absent bundle count is unknown, not proof of a single unit: a physical
    // size (for example 400 g) cannot establish a bar/bottle/etc. count.
    item.intentQuantityProvenance?.takeIf { it.retailContainer != null }?.let { proof ->
        return if (groceryIntentSourceRetailReceiptValid(proof) &&
            swiggyCatalogueHasSingleRetailUnit(candidate, requireNotNull(proof.retailContainer)) &&
            swiggyRetailMultipackCount(candidate, true) == 1 &&
            isSwiggyCandidateAllowed(item, candidate))
            exact(requested.n) else held("retail_container_not_proven")
    }
    if (item.retailPackCount && item.intentQuantityProvenance == null)
        return held("retail_count_source_not_proven")
    // A count of prepared snacks is a piece count, not an unknown frozen bag.
    // Explicit retail-pack requests are handled separately from individual food.
    if (!swiggyPreparedPieceOfferMatches(item, candidate)) return held("prepared_piece_not_proven")
    if (item.logicalPieceCount) return exact(swiggyLogicalPieceCartQuantity(item, candidate))
    val offer = swiggyOrdinaryRetailOfferContents(candidate)
    return if (requested.n in 1..20 && offer != null && isSwiggyCandidateAllowed(item, candidate))
        SwiggyCartQuantityResolution.RetailProposal(requested.n, offer)
    else held("retail_offer_not_proven")
}

/** Shared seller-only default policy. One suggested SKU is not a claim about
 * its physical contents and must never be used as a piece/measure divisor. */
internal fun swiggySingleSkuDefaultAllowed(candidate: RecommendationCandidate): Boolean {
    val contents = swiggyCatalogueContents(candidate)
    if (contents.issue) return false
    if (contents.contents != null) return true
    val rawFields = swiggyRawCatalogueFields(candidate).joinToString(" ")
    if (candidatePieceUnitRegex.containsMatchIn(rawFields) && candidatePieceCompositionRegex.containsMatchIn(rawFields)) return false
    if (swiggyRetailMultipackCount(candidate, true) == 0) return false
    return swiggyRetailMultipackCount(candidate, false).let { it == null || it == 1 }
}

/** Shared existing prepared-piece veto; never infers a product-specific count. */
internal fun swiggyPreparedPieceOfferMatches(item: ParsedItem, candidate: RecommendationCandidate): Boolean {
    if ("samosa" !in swiggyIdentityTokens(item.query)) return true
    val tokens = swiggyIdentityTokens(swiggyCandidateLabel(candidate))
    return tokens.none { it in setOf("frozen", "readytocook", "raw") } && "readytoeat" in tokens &&
        (candidatePieceCountRegex.containsMatchIn(swiggyCandidateLabel(candidate)) ||
            swiggyRetailMultipackCount(candidate, item.requiresIntentQuantityProof) != null)
}

/** Concrete SKU contents only. This pure fact never establishes how the user
 * meant their count, authorises a divisor, or returns an executable quantity. */
internal fun swiggyOrdinaryRetailOfferContents(candidate: RecommendationCandidate): SwiggyRetailOfferContents? {
    if (swiggyCatalogueQuantityConflict(candidate) || swiggyRetailMultipackCount(candidate, true) == 0) return null
    // Exactly one proven piece per SKU conserves N for either interpretation
    // of a bare count. This is still an offer, never proof of a source count role.
    val singlePieceOffer = swiggyProvenRetailOfferContents(candidate, null)?.takeIf {
        it.measure.dimension == GroceryPackDimension.PIECES && it.measure.baseValue.compareTo(java.math.BigDecimal.ONE) == 0
    }
    if (singlePieceOffer != null) return singlePieceOffer
    // Catalogue contents do not establish the source's count role. In
    // particular, "2 milk" + "2 x 500 ml" must not silently become one SKU.
    // Only the explicit/proven source roles above authorize a divisor.
    // No product-name inference: a bare count is not proof of what one SKU
    // contains. Keep a concrete ordinary retail offer for an explicit review,
    // but never let the exact/action conversion below return its quantity.
    return swiggyRetailProposalPackSize(candidate)?.let(SwiggyRetailOfferContents::DisplayOnly)
}

/** An offer needs one ordinary, directly stated pack measure. No estimates,
 * joined-field inference, unknown contents or complicated bundles are offered. */
private fun swiggyHasAffirmativePhysicalMeasures(
    fields: List<String>, evidence: List<NutritionMeasureEvidence>,
): Boolean {
    if (fields.any { field ->
            field.any { it in "~≈±<>≤≥" } || Regex(
                "\\b(?:approx(?:imately)?|about|up\\s*to|estimated|at\\s+least|at\\s+most|less\\s+than|more\\s+than|not|never|neither|nor|unless|if|whether|instead)\\b",
                RegexOption.IGNORE_CASE,
            ).containsMatchIn(field) || Regex(
                "\\b(?:no|without|min(?:imum)?|max(?:imum)?)\\s*[:=]?\\s+(?=${groceryPackMeasurePattern.pattern})|${groceryPackMeasurePattern.pattern}\\s+(?:min(?:imum)?|max(?:imum)?)\\b",
                RegexOption.IGNORE_CASE,
            ).containsMatchIn(field)
        }) return false
    // A numeric substring inside a signed, comma-formatted or fractional value
    // is not affirmative pack evidence, even when another field repeats it.
    return evidence.none { field -> groceryPackMeasurePattern.findAll(field.physicalText).any { match ->
        val prefix = field.physicalText.substring(0, match.range.first)
        val previous = prefix.trimEnd().lastOrNull()
        previous != null && previous !in setOf('(', '·') &&
            !(previous.isLetter() && prefix.lastOrNull()?.isWhitespace() == true)
    } }
}

private fun swiggyRetailProposalPackSize(candidate: RecommendationCandidate): String? {
    val fields = swiggyRawCatalogueFields(candidate)
    val evidence = fields.map(::nutritionMeasureEvidence)
    if (evidence.any { it.unsupported } || nutritionClaimsConflict(evidence.flatMap { it.claims }.toSet())) return null
    if (!swiggyHasAffirmativePhysicalMeasures(fields, evidence)) return null
    if (swiggyRetailMultipackCount(candidate, true) != null) return null
    val joined = nutritionMeasureEvidence(evidence.joinToString(" ") { it.physicalText })
    if (joined.unsupported || joined.claims.isNotEmpty()) return null
    val measures = fields.flatMap(::groceryPackMeasures).toSet()
    val measure = measures.singleOrNull() ?: return null
    if (measure.dimension !in setOf(GroceryPackDimension.WEIGHT, GroceryPackDimension.VOLUME) ||
        measure.baseValue.signum() <= 0) return null
    return evidence.asSequence().flatMap { groceryPackMeasurePattern.findAll(it.physicalText) }
        .firstOrNull()?.value
}

internal fun isSwiggyCandidateCountCompatible(item: ParsedItem, candidate: RecommendationCandidate): Boolean =
    resolveSwiggyCartQuantity(item, candidate) is SwiggyCartQuantityResolution.Exact

internal fun swiggyRequestedCartQuantity(
    item: ParsedItem,
    candidate: RecommendationCandidate,
): Int {
    val result = resolveSwiggyCartQuantity(item, candidate)
    require(result is SwiggyCartQuantityResolution.Exact) { "Quantity requires explicit retail-offer review" }
    return result.cartUnits
}

/** Exact, divisible measures only. An unknown or mixed multipack is not guessed. */
internal fun swiggyMeasuredPackQuantity(item: ParsedItem, candidate: RecommendationCandidate): Int? {
    val fields = swiggyRawCatalogueFields(candidate)
    val evidence = fields.map(::nutritionMeasureEvidence)
    if (!swiggyHasAffirmativePhysicalMeasures(fields, evidence)) return null
    if (evidence.any { it.unsupported }) return null
    if (nutritionClaimsConflict(evidence.flatMap { it.claims }.toSet())) return null
    val text = evidence.joinToString(" ") { it.physicalText }
    // Joining independent provider fields may reveal a fragmented nutrition
    // claim. Such a join supplies negative evidence only, never a pack size.
    val combinedEvidence = nutritionMeasureEvidence(text)
    if (combinedEvidence.unsupported || combinedEvidence.claims.isNotEmpty()) return null
    // This path converts physical totals only. Incidental inner pieces are
    // neither the divisor nor outer packages, regardless of producer/proof.
    val outerCount = swiggyRetailMultipackCount(candidate, false)
    if (outerCount != null && outerCount != 1) return null
    val trueMultipackPrefix = Regex("\\b\\d+(?:\\.\\d+)?\\s*[x×]\\s*(?=\\d)", RegexOption.IGNORE_CASE)
    val trueMultipackSuffix = Regex(
        "\\b\\d+(?:\\.\\d+)?\\s*(?:kg|kgs|g|gm|gms|grams?|ml|l|ltr|litres?|liters?)\\s*[x×]\\s*\\d+\\b",
        RegexOption.IGNORE_CASE,
    )
    if (trueMultipackPrefix.containsMatchIn(text) || trueMultipackSuffix.containsMatchIn(text) ||
        Regex("\\bpack\\s+of\\s+(?:[2-9]|[1-9]\\d+)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
    ) return null
    // Compare all eligible same-dimension fields exactly before validating an
    // integral pack. Discarding fractional/overflowing fields first can hide
    // contradictory seller evidence; floating point can manufacture equality.
    val dimension = if (item.quantity is Quantity.Weight) GroceryPackDimension.WEIGHT else GroceryPackDimension.VOLUME
    val size = evidence.asSequence().flatMap { groceryPackMeasurePattern.findAll(it.physicalText) }
        .map { GroceryPackMeasure.of(it.groupValues[1].toBigDecimal(), it.groupValues[2]) }
        .filter { it.dimension == dimension }.distinct().singleOrNull() ?: return null
    val packSize = try { size.baseValue.intValueExact() } catch (_: ArithmeticException) { return null }
    if (packSize <= 0) return null
    val requested = when (val quantity = item.quantity) {
        is Quantity.Weight -> quantity.grams
        is Quantity.Volume -> quantity.ml
        else -> return null
    }
    return (requested / packSize).takeIf { requested % packSize == 0 && it in 1..20 }
}

internal fun swiggySuggestionNeedsReview(item: ParsedItem, recommendation: Recommendations, hasPreferred: Boolean): Boolean =
    recommendation.requiresConfirmation || !hasPreferred || item.parserConfidence < 1f ||
        item.quantity is Quantity.Weight || item.quantity is Quantity.Volume || item.avoidPhrases.isNotEmpty() ||
        (item.strictMatchPhrase.isNullOrBlank() && item.query.trim().split(Regex("\\s+")).size == 1)

internal fun swiggyNeedsExactHealthProduct(item: ParsedItem): Boolean {
    // A model must not erase the user's uncertainty by inventing a precise name.
    val words = "${item.query} ${item.rawText}".lowercase().split(Regex("[^\\p{L}]+" )).toSet()
    return words.any { it in setOf("cough", "khansi", "khaansi", "fever", "headache", "pain") } &&
        words.any { it in setOf("woh", "wo", "wali", "waali", "wala", "goli", "medicine", "dawai", "dawa", "something") }
}

internal fun consolidateSwiggyRequestedItems(selected: List<RequestedItem>): List<RequestedItem> {
    if (selected.isEmpty()) return emptyList()
    val consolidated = mutableListOf<RequestedItem>()
    for ((spinId, sameProduct) in selected.groupBy { it.spinId.trim() }) {
        if (spinId.isBlank() || sameProduct.any { it.quantity !in 1..20 } ||
            sameProduct.map { it.quantityMode }.distinct().size != 1) return emptyList()
        val skuIds = sameProduct.mapNotNull { it.skuId?.trim()?.takeIf { sku -> sku.isNotEmpty() } }.distinct()
        if (skuIds.size > 1) return emptyList()
        val total = sameProduct.fold(0L) { sum, item -> sum + item.quantity.toLong() }
        if (total !in 1L..20L) return emptyList()
        if (sameProduct.any { row -> row.cataloguePurchaseLimit?.validFor(row.spinId, row.skuId) == false }) return emptyList()
        val limit = sameProduct.mapNotNull { it.cataloguePurchaseLimit }.minByOrNull { it.maxUnits }
        if (limit != null && total > limit.maxUnits) return emptyList()
        consolidated += sameProduct.first().copy(
            spinId = spinId,
            quantity = total.toInt(),
            skuId = skuIds.singleOrNull() ?: sameProduct.first().skuId,
            cataloguePurchaseLimit = limit,
        )
    }
    return consolidated
}

internal fun swiggyRequestedItemFromParsedItem(
    candidate: RecommendationCandidate,
    item: ParsedItem,
): RequestedItem = RequestedItem(
    spinId = candidate.spinId,
    skuId = candidate.skuId,
    quantity = swiggyRequestedCartQuantity(item, candidate),
    displayName = swiggyCandidateLabel(candidate),
    quantityMode = item.cartQuantityMode,
    cataloguePurchaseLimit = candidate.cataloguePurchaseLimit,
)

/** Read-only plan input. This must never bypass the review-bound gate in applyPlan. */
internal fun swiggyPreviewItemFromParsedItem(candidate: RecommendationCandidate, item: ParsedItem): RequestedItem {
    val quantity = when (val result = resolveSwiggyCartQuantity(item, candidate)) {
        is SwiggyCartQuantityResolution.Exact -> result.cartUnits
        is SwiggyCartQuantityResolution.RetailProposal -> result.cartUnits
        is SwiggyCartQuantityResolution.Held -> error("Unresolved quantity cannot enter a cart preview")
    }
    return RequestedItem(candidate.spinId, quantity, swiggyCandidateLabel(candidate), candidate.skuId, item.cartQuantityMode,
        candidate.cataloguePurchaseLimit)
}

internal const val SWIGGY_MAX_CANDIDATES = 5

internal fun swiggyUsableCandidates(item: ParsedItem, recommendation: Recommendations): List<RecommendationCandidate> {
    if (swiggyIntentQuantityIssue(item, recommendation) != null || swiggyNeedsExactHealthProduct(item)) return emptyList()
    return recommendation.candidates.filter {
        it.spinId.isNotBlank() && resolveSwiggyCartQuantity(item, it) !is SwiggyCartQuantityResolution.Held && isSwiggyCandidateAllowed(item, it)
    }.take(SWIGGY_MAX_CANDIDATES)
}

internal fun swiggyQuantityProposalBinding(
    rowIndex: Int, item: ParsedItem, candidate: RecommendationCandidate,
): SwiggyQuantityProposalBinding? {
    val proposal = resolveSwiggyCartQuantity(item, candidate) as? SwiggyCartQuantityResolution.RetailProposal ?: return null
    // Consent identity uses typed field equality, never diagnostic toString().
    val version = when (proposal.contents) {
        is SwiggyRetailOfferContents.DisplayOnly -> "retail-offer-v1"
        is SwiggyRetailOfferContents.Proven -> "retail-offer-v2"
        is SwiggyRetailOfferContents.ProvenComposite -> "retail-offer-v3"
    }
    val evidence = SwiggyQuantityProposalEvidence.snapshot(item, candidate, version, proposal.packSize, proposal.contents)
    return SwiggyQuantityProposalBinding(rowIndex, swiggyIntentQuantitySource(item) ?: item.rawText,
        proposal.cartUnits, candidate.spinId, candidate.skuId, evidence)
}

internal fun swiggyQuantityProposalReviewRow(
    item: ParsedItem, candidate: RecommendationCandidate,
    proposal: SwiggyCartQuantityResolution.RetailProposal, cartChange: String,
): SwiggyStepRow {
    val source = swiggyIntentQuantitySource(item) ?: item.rawText
    val statedAmount = item.intentQuantityProvenance?.corrected?.atom
        ?: item.intentQuantityProvenance?.local?.atom ?: (item.quantity as Quantity.Count).n.toString()
    return SwiggyStepRow(
        title = "You said: “$source”",
        detail = "${swiggyCandidateLabel(candidate)}\n" +
            "Beta suggests: ${proposal.cartUnits} pack(s) of ${proposal.packSize}\n" +
            "You asked for $statedAmount. This product is sold as ${proposal.packSize} packs.\n$cartChange",
        badge = "Suggested pack amount", tone = SwiggyStepTone.AMBER,
    )
}

/** Review the original requests in their original order, even when the backend
 * consolidates several requests into one SKU or omits an unchanged line. */
internal fun swiggyOrderedCartReviewRows(
    items: List<ParsedItem>,
    candidates: Map<Int, RecommendationCandidate>,
    skipped: Set<Int>,
    changes: List<SwiggyMcpClient.CartPlanChange>,
    resolve: (Int, ParsedItem, RecommendationCandidate) -> SwiggyCartQuantityResolution = { _, item, candidate ->
        resolveSwiggyCartQuantity(item, candidate)
    },
    onChange: (Int) -> Unit,
): List<SwiggyStepRow> = items.indices.mapNotNull { index ->
    val item = items[index]
    if (index in skipped) return@mapNotNull SwiggyStepRow(
        title = item.rawText, detail = "You chose to leave this item out.",
        badge = "Not added", tone = SwiggyStepTone.AMBER,
    )
    val candidate = candidates[index] ?: return@mapNotNull if (item.hasAuditedIntent()) SwiggyStepRow(
        title = item.rawText, detail = "Beta could not verify this amount. Edit your list before adding it.",
        badge = "Not verified", tone = SwiggyStepTone.AMBER,
        action = SwiggyStepAction("Change ${item.query}") { onChange(index) },
    ) else null
    val change = changes.singleOrNull { it.spinId == candidate.spinId }
    val from = change?.fromQuantity ?: 0
    val to = change?.toQuantity ?: from
    val sharedLine = candidates.entries.count { it.key !in skipped && it.value.spinId == candidate.spinId } > 1
    val cartChange = if (change == null) "No quantity change shown in this plan"
        else (if (sharedLine) "Combined cart line: " else "Cart line: ") + "$from → $to packs"
    val action = SwiggyStepAction("Change ${item.query}") { onChange(index) }
    when (val resolution = resolve(index, item, candidate)) {
        is SwiggyCartQuantityResolution.RetailProposal ->
            swiggyQuantityProposalReviewRow(item, candidate, resolution, cartChange).copy(action = action)
        is SwiggyCartQuantityResolution.Exact -> SwiggyStepRow(
            title = swiggyCandidateLabel(candidate),
            detail = "You asked: ${item.rawText}\n$cartChange" + swiggyDefaultQuantityReviewNote(listOf(item)),
            badge = if (change == null) "No change shown" else "$from → $to packs",
            action = action,
        )
        is SwiggyCartQuantityResolution.Held -> SwiggyStepRow(
            title = item.rawText, detail = "Beta could not verify this amount. Edit your list before adding it.",
            badge = "Not verified", tone = SwiggyStepTone.AMBER, action = action,
        )
    }
}

/** Keep suggestion provenance even when multiple requests consolidate into one SKU. */
internal fun swiggyDefaultQuantityReviewNote(items: List<ParsedItem>): String {
    val defaults = items.filter { it.quantity is Quantity.Default }
    if (defaults.isEmpty()) return ""
    return "\nSuggested quantity: 1 pack for each request without a quantity: " +
        defaults.joinToString("; ") { it.rawText } + ". Check the combined total above."
}

/** A hint identifies an eligible offer, but never supplies its current catalogue evidence. */
internal fun swiggyCurrentChoice(usable: List<RecommendationCandidate>, hint: RecommendationCandidate?): RecommendationCandidate? {
    if (hint == null || hint.spinId.isBlank() || hint.spinId != hint.spinId.trim()) return null
    val sku = hint.skuId ?: return usable.singleOrNull { it.spinId == hint.spinId }?.takeIf { it == hint }
    if (sku.isBlank() || sku != sku.trim()) return null
    return usable.singleOrNull { it.spinId == hint.spinId && it.skuId == sku }
}

internal fun swiggyDefaultSuggestion(
    item: ParsedItem,
    usable: List<RecommendationCandidate>,
    preferred: RecommendationCandidate?,
): RecommendationCandidate {
    // The backend applies the same history-first identity policy to all items.
    // This list has already passed exact quantity and explicit-intent checks.
    return swiggyCurrentChoice(usable, preferred) ?: usable.first()
}

internal fun isSwiggyCandidateAllowed(item: ParsedItem, candidate: RecommendationCandidate): Boolean =
    swiggyCatalogueContentMatches(item, candidate) && swiggyIntentProduceConstraintMatches(item, candidate) &&
        swiggyIntentNumericCodeMatches(item, candidate)

/** Pure product/form/exclusion comparison. This is not quantity or execution
 * authority; the ordinary wrapper above retains those independent checks. */
internal fun swiggyCatalogueContentMatches(item: ParsedItem, candidate: RecommendationCandidate): Boolean {
    // Undo only our exact name/variant display join. Raw fields remain separate
    // when interpreting claims; all other constraints still use original text.
    val primaryIdentity = candidate.variant?.takeIf { it.isNotBlank() }
        ?.let { candidate.label.removeSuffix(" · $it") } ?: candidate.label
    if (!swiggyDietaryFormsAgree(item, candidate, primaryIdentity)) return false
    if (listOf(item.query, item.strictMatchPhrase.orEmpty()).any {
            swiggyClaimOnlyIdentityConflict(it, primaryIdentity)
        }) return false
    if (!swiggySourceRetailIdentityMatches(item, candidate)) return false
    if (!swiggyTypedIngredientsMatch(item, candidate)) return false
    val sellerProperties = validatedSellerPropertyRequirement(item)
    if (sellerProperties != null) {
        if (candidate.sellerAssessment?.matches(sellerProperties, candidate) != true) return false
    }
    if (swiggyCatalogueQuantityConflict(candidate)) return false
    if (swiggyCatalogueHasUnresolvedBundle(swiggyRawCatalogueFields(candidate))) return false
    // Inspect raw fields before display de-duplication can hide an exact form
    // word that is only a substring of a brand in another field.
    if (!swiggyProductFormsAllowed(listOf(item.query, item.strictMatchPhrase.orEmpty()), item.avoidPhrases,
            swiggyRawCatalogueFields(candidate))) return false
    if (!swiggyMatchesProductIdentity(item, swiggyCandidateLabel(candidate),
            listOf(primaryIdentity) + swiggyRawCatalogueFields(candidate).drop(1), candidate)) return false
    val rawLabel = swiggyCandidateLabel(candidate)
    val label = rawLabel.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ")
    return item.avoidPhrases.none { phrase ->
        val words = phrase.lowercase().trim().replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        // This exact source-owned restriction was checked against raw SKU fields above.
        if (validatedIngredientPredicates(item)?.any { it.literalExclusion() == words } == true) return@none false
        if (words == "sugar") {
            val evidence = swiggyIdentityTokens(rawLabel)
            return@none evidence.none { it in setOf("unsweetened", "sugarfree") } ||
                swiggyHasSugarContradiction(rawLabel) || !swiggyHasExplicitIngredientAbsence(rawLabel, "sugar")
        }
        val predicate = IngredientPredicate.fromProtectedExclusion(words)
        if (predicate != null) {
            return@none !swiggyHasIngredientPredicate(rawLabel, predicate)
        }
        // Explicit "lactose free" / "sugar free" is not the excluded ingredient.
        // Do not infer ingredient safety from an otherwise silent catalog label.
        val unnegated = label.replace(Regex("\\b${Regex.escape(words)} free\\b"), " ")
        words.isNotBlank() && Regex("(?:^| )${Regex.escape(words)}(?: |$)").containsMatchIn(unnegated)
    }
}

/** Recomputed from source at discovery AND final resolution; editable query
 * copies cannot remove the obligation. This cannot grant a quantity role. */
private fun swiggySourceRetailIdentityMatches(item: ParsedItem, candidate: RecommendationCandidate): Boolean {
    if (sellerVesselConflicts(item, candidate)) return false
    if (item.hasAuditedIntent()) return item.auditedIntent()?.let { swiggyOwnsMerchandise(it, candidate) } == true
    val label = swiggyCandidateLabel(candidate)
    // The editable query cannot remove literal source forms. This is a deny
    // gate only: it neither proves a retail quantity nor upgrades a proposal.
    val positive = item.query + " " + item.strictMatchPhrase.orEmpty()
    if (groceryIntentTerminalExcludedPropertyMatch(item.rawText) != null &&
        (groceryIntentRetainedTerminalPropertyView(item.rawText, positive, item.avoidPhrases) == null ||
            !groceryIntentTerminalPropertyScopeProven(item.rawText, item.rawText))) return false
    val sourceForms = groceryIntentPositiveSourceFormTokens(item.rawText, positive, item.avoidPhrases) ?: run {
        // A verified V2 correction can change amount/size while leaving form
        // unchanged. Reuse its bound primary, not the appended review prose.
        // All source forms must survive; quantity proof never supplies a form.
        if (!swiggyIntentQuantityCandidateChecked(item, candidate)) return false
        val primary = swiggyIntentQuantitySource(item) ?: return false
        val forms = groceryIntentPositiveSourceFormTokens(primary, positive, item.avoidPhrases) ?: return false
        if (forms != groceryIntentSpecificSourceFormTokens(item.rawText)) return false
        forms
    }
    if (!intentEvidenceTokens(label).containsAll(sourceForms)) return false
    val constraint = sourceRetailIdentityConstraint(item.rawText) ?: return true
    if (!constraint.hasRepresentableSize()) return false
    val literal = constraint.literalQuery()
    return swiggyMatchesProductIdentity(item.copy(query = literal, strictMatchPhrase = literal),
        label, offer = candidate) && swiggyMatchesProductIdentity(item, label, offer = candidate)
}


internal fun swiggyQuestionPosition(
    needsQuestion: List<Boolean>,
    itemIndex: Int,
): Pair<Int, Int>? {
    if (itemIndex !in needsQuestion.indices || !needsQuestion[itemIndex]) return null
    val total = needsQuestion.count { it }
    val current = needsQuestion.take(itemIndex + 1).count { it }
    return current to total
}

internal fun swiggyContinuableItemCount(
    selectedCount: Int,
    itemCount: Int,
    unavailableIndex: Int,
): Int = selectedCount.coerceAtLeast(0) + (itemCount - unavailableIndex - 1).coerceAtLeast(0)

internal fun swiggyProviderAddedMessage(message: String?): String? {
    val normalized = message?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val describesFreeProviderItem = normalized.contains("free", ignoreCase = true) &&
        (normalized.contains("gift", ignoreCase = true) || normalized.contains("sample", ignoreCase = true))
    return normalized.takeIf { describesFreeProviderItem }
}

/** Coordinates the user-visible, no-checkout Swiggy voice flow. */
class SwiggyVoiceOrderCoordinator internal constructor(
    private val activity: Activity,
    private val announce: (String) -> Unit,
    private val onReconnectRequired: () -> Unit,
    private val onAddressChanged: (String?) -> Unit = {},
    private val onTerminal: (retainStatus: Boolean) -> Unit = {},
    private val onVerified: (Int) -> Unit = {},
    private val onEditRequest: () -> Unit = {},
    private val onInstructionEdited: (String, String) -> Boolean = { _, _ -> false },
    private val onRecoveryVoiceRequested: () -> Unit = {},
    private val beforeApply: () -> Boolean = { true },
    private val onCheckoutRequested: (String) -> Unit = {},
    private val isCheckoutActive: () -> Boolean = { false },
    private val planCartRequest: (String, List<RequestedItem>, SwiggyCallback<SwiggyMcpClient.CartPlan>) -> Unit = { addressId, requested, callback ->
        SwiggyMcpClient.planCart(activity, addressId, requested, callback)
    },
    private val checkReviewedCart: (String, (SwiggyMcpResult<Boolean>) -> Unit) -> Unit = { token, callback ->
        SwiggyMcpClient.checkCartPlan(activity, token, callback)
    },
    private val readIntentSelection: () -> GroceryIntentSelection = { GroceryIntentPreferences.getSelection(activity) },
) {
    private var running = false
    private var operationGeneration = 0L
    private var mutationInFlight = false
    private val pendingCartStore = SwiggyPendingCartStore(activity.applicationContext)
    private val cartReviewStore = SwiggyCartReviewStore(activity.applicationContext)
    private val fulfillmentStore = GroceryIntentConstraintStore(activity.applicationContext)
    private var hostResumed = false
    private var interruptedDuringRequest = false
    private var preparationInterrupted = false
    private var waitingForExistingRequest = false
    private val stepDialog = SwiggyOrderStepDialog(activity)
    private val addressIntelligence = SwiggyAddressIntelligence(activity.applicationContext)
    private var addressSuggestionReasons: Map<String, String> = emptyMap()
    private var addressLocationAssessments: Map<String, SwiggyLocationAssessment> = emptyMap()
    private var selectedAddressId: String? = null
    private var selectedAddressAtElapsedRealtime = 0L
    private var selectedAddressNeedsRevalidation = false
    private val draftCandidates = mutableMapOf<Int, RecommendationCandidate>()
    private val draftSkipped = mutableSetOf<Int>()
    private var draftRecommendations: List<Recommendations> = emptyList()
    private val intentReviewGate = GroceryIntentReviewGate()
    private val quantityProposalGate = SwiggyQuantityProposalGate()
    private val cartPlanRefresh = SwiggyCartPlanRefresh()
    private var reviewedRequest: SwiggyCartReviewRequest? = null
    // Interpretation survives an address change; catalogue evidence and consent do not.
    private var contextPreparation: GroceryIntentContextPreparation? = null
    private var contextHeldDraft: GroceryIntentContextAssessment.Held? = null
    private var contextBasket: GroceryIntentContextBasket? = null
    private var auditedBasket: AuditedIntentBasket? = null
    private var contextProvider: GroceryIntentProvider? = null
    // Provider and processing scope are one consent decision for this operation.
    private var activeIntentSelection: GroceryIntentSelection.Enabled? = null
    // Opaque interpretation identity only; both review contracts share disclosure.
    private var spokenContext: Any? = null
    private var recoverySession: GroceryIntentRecoverySession? = null
    private val recoveryStore = SwiggyDraftStore(activity.applicationContext)
    private var scopedRecovery: GroceryIntentScopedRecoverySession? = null
    private var scopedProvider: GroceryIntentProvider? = null
    private var scopedQuotes: List<GroceryIntentReviewV2Quote> = emptyList()
    private var scopedDecision: androidx.appcompat.app.AlertDialog? = null
    private var scopedLegacyOrigin: GroceryIntentRecoverySnapshot? = null
    internal var onScopedInstructionEdited: (GroceryIntentRecoveryRecord, GroceryIntentRecoveryRecord) -> Boolean = { _, _ -> false }
    internal var onScopedVoiceRequested: ((String?) -> Unit) -> Unit = { it(null) }
    private var inputRepairReview: GroceryIntentInputRepairReview? = null
    private var interruptedDraftSaveFailed = false
    private var restoringRecoveryAfterSaveFailure = false
    internal data class RecoveryCheckpoint(
        val instruction: String, val provider: GroceryIntentProvider,
        val snapshot: GroceryIntentRecoverySnapshot?, val text: String, val lastSavedText: String,
    )
    internal class RecoveryDraftHolder : androidx.lifecycle.ViewModel() {
        var checkpoint: RecoveryCheckpoint? = null
        var scoped: ScopedRecoveryCheckpoint? = null
    }
    internal data class ScopedRecoveryCheckpoint(
        val record: GroceryIntentRecoveryRecord, val quote: GroceryIntentReviewV2Quote,
        val text: String, val provider: GroceryIntentProvider, val quotes: List<GroceryIntentReviewV2Quote>,
        val kind: GroceryIntentEditKind, val legacyOrigin: GroceryIntentRecoverySnapshot?,
    ) {
        override fun toString() = "ScopedRecoveryCheckpoint(<redacted>)"
    }
    private val retainedRecovery = (activity as? androidx.lifecycle.ViewModelStoreOwner)?.let { owner ->
        androidx.lifecycle.ViewModelProvider(owner)[RecoveryDraftHolder::class.java]
    } ?: RecoveryDraftHolder()
    private var activeRecovery: RecoveryCheckpoint? = null
    private var pausedRecovery: RecoveryCheckpoint? = retainedRecovery.checkpoint

    init {
        if (pausedRecovery != null) {
            preparationInterrupted = true
            interruptedDraftSaveFailed = true
        }
    }

    private fun clearDraft() {
        cartPlanRefresh.invalidate()
        reviewedRequest = null
        quantityProposalGate.clear()
        draftCandidates.clear()
        draftSkipped.clear()
        draftRecommendations = emptyList()
        contextBasket = null
    }

    private fun clearInterpretation() {
        auditedBasket = null
        contextPreparation = null
        contextHeldDraft = null
        contextBasket = null
        contextProvider = null
        activeIntentSelection = null
        spokenContext = null
        intentReviewGate.clear()
    }

    fun start(instruction: String) {
        if (isCheckoutActive()) {
            announce("Please finish checking your existing order before starting another cart.")
            return
        }
        if (restorePendingCartWarning()) return
        if (SwiggyCartMutationGuard.isInFlight()) {
            stopWithVisibleStatus("Swiggy is still checking the last confirmed cart update. Please wait.")
            return
        }
        if (running) {
            announce("Beta is already working on your Swiggy cart.")
            return
        }
        val provider = GroceryIntentPreferences.getMode(activity).providerOrNull()
        val saved = recoveryStore.loadRecovery()?.takeIf { it.instruction == instruction && it.dirty != null }
        if (saved != null) {
            running = true
            // Restoring words is local and independent of the enabled provider.
            // Save below re-reads the current mode before starting any inference.
            showScopedRecoveryRecord(saved, provider ?: GroceryIntentProvider.GEMINI,
                listOfNotNull(saved.dirty!!.quote.takeIf { saved.dirty.kind == GroceryIntentEditKind.PHRASE }),
                saved.dirty.quote, restored = true, kind = saved.dirty.kind)
            return
        }
        startIntentPreparation(instruction)
    }

    private fun startStandardPreparation(instruction: String) {
        clearPausedRecovery()
        clearScopedRecovery()
        recoverySession?.invalidate()
        recoverySession = null
        inputRepairReview?.invalidate()
        inputRepairReview = null
        running = true
        clearDraft()
        clearInterpretation()
        val operationId = ++operationGeneration
        if (hasExplicitGroceryIntentProductOperationCue(instruction)) {
            completeAndDismiss(operationId, "This list includes a product replacement. Standard matching cannot safely preserve which item replaces which, so edit the list or use enhanced understanding. Nothing was added.", retainStatus = true)
            return
        }
        val items = prepareSwiggyMcpItems(
            instruction = instruction,
            lookup = { PreferenceStore.lookup(activity, it) },
        )
        if (items.isEmpty()) {
            completeAndDismiss(operationId, "I could not find a grocery item in that request. Please try again.", retainStatus = true)
            return
        }
        swiggyMcpItemValidationMessage(instruction, items)?.let { message ->
            completeAndDismiss(operationId, message, retainStatus = true)
            return
        }

        val addressCaption = "Checking your saved Swiggy delivery addresses."
        showYourList(operationId, items, addressCaption)
        announce(addressCaption)
        SwiggyMcpClient.fetchAddresses(activity) { result ->
            onUi(operationId) {
                when (result) {
                    is SwiggyMcpResult.Success -> rankAndChooseAddress(operationId, result.value, items)
                    is SwiggyMcpResult.Failure -> fail(operationId, result)
                }
            }
        }
    }

    private fun startIntentPreparation(instruction: String, allowQuickRepair: Boolean = true) {
        // Every explicit retry/save gets a fresh selection. A retained editor's
        // provider label is not permission to send a new request to that provider.
        val selection = readIntentSelection()
        if (selection == GroceryIntentSelection.Disabled) {
            startStandardPreparation(instruction)
            return
        }
        clearPausedRecovery()
        clearScopedRecovery()
        recoverySession?.invalidate()
        recoverySession = null
        inputRepairReview?.invalidate()
        inputRepairReview = null
        running = true
        clearDraft()
        clearInterpretation()
        val operationId = ++operationGeneration
        if (selection !is GroceryIntentSelection.Enabled) {
            // Revoked, obsolete or disabled receipts require an explicit choice;
            // never silently fall back to a different processing contract.
            showIntentRecovery(operationId, instruction, ambiguous = false)
            return
        }
        activeIntentSelection = selection
        val provider = selection.provider
        val started = SystemClock.elapsedRealtime()
        val contract = selection.contract
        val editHintBase = if (contract == GroceryIntentContract.V1) recoveryStore.editHintGeneration() else null
        val revisionBinding = recoveryStore.loadRecovery()?.takeIf { it.instruction == instruction }
        fun revisionStillCurrent(): Boolean = revisionBinding == null || recoveryStore.loadRecovery()?.let {
            it.requestId == revisionBinding.requestId && it.revision == revisionBinding.revision && it.instruction == instruction
        } == true
        val join = GroceryPreparationJoin<List<ParsedItem>, SwiggyMcpResult<List<SwiggyAddress>>> { items, addresses ->
            Log.i("BetaAgent", "INTENT_PREPARATION_READY provider=${provider.requestValue} items=${items.size} elapsed_ms=${SystemClock.elapsedRealtime() - started}")
            when (addresses) {
                is SwiggyMcpResult.Success -> {
                    showYourList(operationId, items, "Checking your delivery address.")
                    rankAndChooseAddress(operationId, addresses.value, items)
                }
                is SwiggyMcpResult.Failure -> fail(operationId, addresses)
            }
        }
        stepDialog.show(SwiggyStepScreen(
            eyebrow = "Your list",
            title = "Understanding your whole list",
            message = "Checking your items and saved delivery addresses together. Nothing is being added.",
            caption = "You can stop at any time. Your list will be kept.",
            cancel = { cancelAndDismiss(operationId, if (contract == GroceryIntentContract.INTERPRETED)
                "Stopped. We can’t recall anything already sent, and we won’t use ${provider.displayName}’s results for this list."
                else "Your list is saved. Nothing was changed.") },
        ))
        announce("Understanding your list. Nothing is being added.")
        // Independent reads overlap; matching still waits for BOTH results and explicit address confirmation.
        SwiggyMcpClient.fetchAddresses(activity) { result ->
            onUi(operationId) {
                if (!hasCurrentIntentConsent(operationId, selection)) return@onUi
                if (!revisionStillCurrent()) return@onUi
                // Buffer even a failed address read until interpretation has passed.
                // It must never dismiss an editor for a genuinely held list.
                join.second(result)
            }
        }
        if (contract == GroceryIntentContract.CONTEXT) {
            SwiggyMcpClient.fetchIntentContextPreparation(activity, instruction, provider) { result ->
                onUi(operationId) {
                    if (!hasCurrentIntentConsent(operationId, selection)) return@onUi
                    if (!revisionStillCurrent()) return@onUi
                    when (result) {
                        is SwiggyMcpResult.Success -> {
                            val assessment = result.value
                            if (assessment is GroceryIntentContextAssessment.Held) {
                                // No executable preparation is retained and no legacy fallback
                                // is allowed. Keep every original row for the existing review UI.
                                clearInterpretation()
                                contextHeldDraft = assessment
                                showContextIssues(operationId, assessment.draft.instruction, assessment.blockingIssues, provider)
                                return@onUi
                            }
                            val preparation = (assessment as GroceryIntentContextAssessment.Prepared).preparation
                            contextPreparation = preparation
                            contextProvider = provider
                            val legacy = preparation.legacyPreparation
                            if (preparation.blockingIssues.isNotEmpty()) {
                                showContextIssues(operationId, preparation, provider)
                            } else if (legacy.items.isEmpty()) {
                                completeAndDismiss(operationId, "There are no items to add from this list. Nothing was added.", retainStatus = true)
                            } else if (swiggyMcpItemValidationMessage("", legacy.items) != null) {
                                showIntentEditor(operationId, instruction, provider)
                            } else {
                                // Context has its own typed issues; legacy uncovered prose is not
                                // an acknowledgement bypass and must not be fed back into routing.
                                intentReviewGate.prepare(emptyList(), legacy.excludedText,
                                    legacy.sharedGroupNotes, preparation.fulfillmentConstraints)
                                join.first(legacy.items)
                            }
                        }
                        is SwiggyMcpResult.Failure -> {
                            if (result.reconnectRequired) fail(operationId, result)
                            else if (result.intentNeedsReview) showIntentEditor(operationId, instruction, provider)
                            else showIntentRecovery(operationId, instruction, ambiguous = false,
                                unavailableForInstallation = result.intentUnavailable)
                            // A disabled/new contract never changes the saved provider choice.
                        }
                    }
                }
            }
            return
        }
        SwiggyMcpClient.fetchIntentPreparation(activity, instruction, provider, contract) { result ->
            onUi(operationId) {
                if (!hasCurrentIntentConsent(operationId, selection)) return@onUi
                if (!revisionStillCurrent()) return@onUi
                when (result) {
                    is SwiggyMcpResult.Success -> {
                        val preparation = result.value
                        val items = preparation.items
                        auditedBasket = preparation.auditedBasket
                        if (auditedBasket != null) contextProvider = provider
                        if (!auditedIntentBasketMatches(items, instruction, auditedBasket)) {
                            showIntentEditor(operationId, instruction, provider)
                            return@onUi
                        }
                        if (preparation.inputRepair == null) {
                            intentReviewGate.prepare(preparation.unresolvedText, preparation.excludedText,
                                preparation.sharedGroupNotes, preparation.fulfillmentConstraints)
                        }
                        // Typed quantities already passed exact numeric validation. Raw correction text
                        // must not be reparsed by the legacy heuristic (e.g. a cancelled quantity).
                        val validation = if (preparation.inputRepair == null) swiggyMcpItemValidationMessage("", items) else null
                        val route = groceryIntentPreparationRoute(
                            inputRepairPresent = preparation.inputRepair != null,
                            allowQuickRepair = allowQuickRepair,
                            recoverySnapshotPresent = preparation.recoverySnapshot != null,
                            unresolvedTextPresent = preparation.unresolvedText.isNotEmpty(),
                            validationMessagePresent = validation != null,
                            itemsEmpty = items.isEmpty(),
                        )
                        when (route) {
                            GroceryIntentPreparationRoute.INPUT_REPAIR -> {
                                showIntentInputRepair(operationId, instruction, provider,
                                    requireNotNull(preparation.inputRepair))
                                return@onUi
                            }
                            GroceryIntentPreparationRoute.EDITOR -> {
                                // Disabled input-repair keeps its original no-snapshot editor path.
                                showIntentEditor(operationId, instruction, provider,
                                    preparation.recoverySnapshot.takeIf { preparation.inputRepair == null })
                            }
                            GroceryIntentPreparationRoute.EMPTY ->
                                // Deliberately cancelled/excluded items are not unresolved purchases.
                                completeAndDismiss(operationId, "There are no items to add from this list. Nothing was added.", retainStatus = true)
                            GroceryIntentPreparationRoute.DISCOVERY -> {
                                Log.i("BetaAgent", "INTENT_DRAFT_VALIDATED provider=${provider.requestValue} items=${items.size} elapsed_ms=${SystemClock.elapsedRealtime() - started}")
                                join.first(items)
                            }
                        }
                    }
                    is SwiggyMcpResult.Failure -> {
                        if (result.reconnectRequired) fail(operationId, result)
                        else {
                            if (result.intentUnavailable && contract.disablesProviderOnUnavailable())
                                GroceryIntentPreferences.setMode(activity, GroceryIntentPreferences.Mode.OFF)
                            if (result.intentNeedsReview) {
                                val quotes = if (contract == GroceryIntentContract.V1 && editHintBase != null)
                                    GroceryIntentEditHints.fromRanges(instruction, result.intentEditRanges) else emptyList()
                                if (quotes.isEmpty()) showIntentEditor(operationId, instruction, provider)
                                else {
                                    val record = recoveryStore.openEditHintRecovery(instruction, requireNotNull(editHintBase))
                                    if (record != null && GroceryIntentPreferences.getMode(activity).providerOrNull() == provider)
                                        showScopedRecoveryRecord(record, provider, quotes)
                                    else cancelAndDismiss(operationId, "Your draft changed. Nothing was added.")
                                }
                            }
                            else showIntentRecovery(operationId, instruction, ambiguous = false,
                                unavailableForInstallation = result.intentUnavailable && contract.disablesProviderOnUnavailable())
                        }
                    }
                }
            }
        }
    }

    private fun showContextIssues(
        operationId: Long,
        preparation: GroceryIntentContextPreparation,
        provider: GroceryIntentProvider,
    ) = showContextIssues(operationId, preparation.instruction, preparation.blockingIssues, provider)

    private fun showContextIssues(
        operationId: Long,
        instruction: String,
        issues: List<GroceryIntentContextIssue>,
        provider: GroceryIntentProvider,
    ) {
        if (!isCurrent(operationId)) return
        val record = recoveryStore.openRecovery(instruction)
        if (record == null) {
            cancelAndDismiss(operationId, GroceryIntentScopedRecoverySession.FAILED_SAVE)
            return
        }
        val quotes = issues.mapNotNull { it.source }.distinct().filter { record.withDirty(it, it.text) != null }
            .ifEmpty { listOf(GroceryIntentReviewV2Quote(instruction, 0, instruction.length)) }
        showScopedRecoveryRecord(record, provider, quotes)
    }

    /** Corrections are source edits, never a waiver for an unresolved obligation. */
    private fun showScopedRecoveryRecord(
        record: GroceryIntentRecoveryRecord,
        provider: GroceryIntentProvider,
        quotes: List<GroceryIntentReviewV2Quote>,
        selected: GroceryIntentReviewV2Quote? = null,
        restored: Boolean = false,
        unsavedText: String? = null,
        kind: GroceryIntentEditKind = GroceryIntentEditKind.PHRASE,
        legacyOrigin: GroceryIntentRecoverySnapshot? = null,
        returnFocus: Pair<GroceryIntentEditKind, GroceryIntentReviewV2Quote>? = null,
    ) {
        val currentQuotes = (quotes + listOfNotNull(record.dirty?.takeIf { it.kind == GroceryIntentEditKind.PHRASE }?.quote)).distinct()
        val editingQuote = if (selected == null) null else record.dirty?.quote ?: selected
        val editingKind = if (editingQuote != null) record.dirty?.kind ?: kind else kind
        scopedDecision?.dismiss()
        scopedRecovery?.invalidate()
        recoverySession?.invalidate()
        recoverySession = null
        activeRecovery = null
        inputRepairReview?.invalidate()
        inputRepairReview = null
        clearDraft()
        clearInterpretation()
        running = true
        val recoveryId = ++operationGeneration
        scopedProvider = provider
        scopedQuotes = currentQuotes
        scopedLegacyOrigin = legacyOrigin
        val session = editingQuote?.let { quote -> GroceryIntentScopedRecoverySession(record, quote,
            isCurrent = { isCurrent(recoveryId) && !activity.isFinishing && !activity.isDestroyed },
            persist = { expected, updated -> recoveryStore.compareAndSetRecovery(expected, updated) },
            commit = onScopedInstructionEdited,
            kind = editingKind,
        ).also { if (unsavedText != null) it.edit(unsavedText) } }
        scopedRecovery = session
        lateinit var view: GroceryIntentScopedRecoveryView
        fun reportFailure() {
            view.updateStatus(session?.message ?: GroceryIntentScopedRecoverySession.FAILED_SAVE, saveFailed = true)
            view.focusStatus()
        }
        var voiceEpoch = 0L
        fun ask(title: String, yes: String, no: String, onYes: () -> Unit, onNo: () -> Unit = {}) {
            voiceEpoch++
            val witness = session?.generation
            recoveryDecision(recoveryId, title, yes, no, { scopedRecovery === session && session?.generation == witness }, onYes, onNo)
        }
        fun commit(confirmedRemoval: Boolean) {
            if (!isCurrent(recoveryId)) return
            val updated = session?.save(confirmedRemoval) ?: run { reportFailure(); return }
            retainedRecovery.scoped = null
            announce("Saved. Checking your whole request again…")
            startIntentPreparation(updated.instruction)
        }
        fun save() {
            if (session?.requiresRemovalConfirmation == true && session.canSave)
                ask("This removes most of your message. Save anyway?", "Save", "Keep editing", { commit(true) })
            else commit(false)
        }
        fun overview(base: GroceryIntentRecoveryRecord) {
            if (legacyOrigin != null && base.dirty == null && base.revision == 0) {
                showIntentEditor(recoveryId, base.instruction, provider, legacyOrigin, returnFromWhole = true)
            } else {
                showScopedRecoveryRecord(base, provider, currentQuotes, legacyOrigin = legacyOrigin,
                    returnFocus = editingQuote?.let { editingKind to it })
            }
        }
        fun discardEdit() {
            if (!isCurrent(recoveryId) || session == null) return
            if (!session.cancel()) { reportFailure(); return }
            retainedRecovery.scoped = null
            overview(session.record)
        }
        fun cancelEdit() {
            if (session?.kind == GroceryIntentEditKind.WHOLE_MESSAGE && session.hasChanges)
                ask("Discard your changes? Your message stays as it was.", "Discard", "Keep editing", ::discardEdit)
            else discardEdit()
        }
        fun choose(quote: GroceryIntentReviewV2Quote, targetKind: GroceryIntentEditKind) {
            if (!isCurrent(recoveryId)) return
            if (session?.kind == targetKind && session.quote == quote) return // Already editing this exact buffer.
            if (session != null && !session.checkpoint()) { reportFailure(); return }
            val base = session?.record ?: record
            fun open(next: GroceryIntentRecoveryRecord, q: GroceryIntentReviewV2Quote, k: GroceryIntentEditKind) {
                showScopedRecoveryRecord(next, provider, currentQuotes, q, kind = k, legacyOrigin = legacyOrigin)
                announce(if (k == GroceryIntentEditKind.WHOLE_MESSAGE) WHOLE_EDIT_PROMPT
                    else "Change your words. Your words: '${q.text}'. Say the new words, then say save.")
            }
            val pending = base.dirty
            if (pending != null && (pending.kind != targetKind || pending.quote != quote)) {
                ask("You have an unsaved change.", "Continue editing", "Discard", {
                    open(base, pending.quote, pending.kind)
                }, {
                    val clean = base.withoutDirty()
                    if (clean == null || !recoveryStore.compareAndSetRecovery(base, clean)) reportFailure()
                    else open(clean, quote, targetKind)
                })
            } else open(base, quote, targetKind)
        }
        view = stepDialog.showScopedRecovery(
            originalInstruction = record.originalInstruction, currentInstruction = record.instruction,
            changedRanges = record.changedRanges, quotes = currentQuotes, selectedQuote = editingQuote,
            editorText = session?.text, restored = restored, pendingQuote = record.dirty?.quote,
            editKind = editingKind, pendingEditKind = record.dirty?.kind ?: GroceryIntentEditKind.PHRASE,
            focusEditorOnOpen = editingQuote != null && editingKind == GroceryIntentEditKind.WHOLE_MESSAGE,
            onChoose = { choose(it, GroceryIntentEditKind.PHRASE) },
            onChooseWholeMessage = { choose(GroceryIntentReviewV2Quote(record.instruction, 0, record.instruction.length),
                GroceryIntentEditKind.WHOLE_MESSAGE) },
            onTextChanged = { session?.edit(it) }, onSave = ::save, onCancelEdit = ::cancelEdit,
            onVoice = {
                if (session != null && isCurrent(recoveryId)) {
                    val request = ++voiceEpoch
                    val generation = session.generation
                    val selection = view.editorSelection()
                    onScopedVoiceRequested voiceResult@{ result ->
                        if (!isCurrent(recoveryId) || scopedRecovery !== session || request != voiceEpoch ||
                            session.generation != generation || selection != view.editorSelection()) return@voiceResult
                        voiceEpoch++ // A late or duplicate recognition result must not become a second command.
                        val prior = session.text
                        val cursor = selection?.second ?: -1
                        if (result == null) view.updateStatus("Type or say something.")
                        else when (session.voice(result, cursor)) {
                            GroceryIntentScopedRecoverySession.VoiceAction.SAVE -> save()
                            GroceryIntentScopedRecoverySession.VoiceAction.CANCEL -> cancelEdit()
                            GroceryIntentScopedRecoverySession.VoiceAction.READ -> session.message?.let(announce)
                            GroceryIntentScopedRecoverySession.VoiceAction.CONFIRM -> {
                                view.updateText(session.text)
                                view.updateStatus(session.message)
                                session.message?.let(announce)
                            }
                            GroceryIntentScopedRecoverySession.VoiceAction.NONE -> {
                                if (session.kind == GroceryIntentEditKind.WHOLE_MESSAGE && prior != session.text)
                                    view.updateText(session.text, cursor + session.text.length - prior.length)
                                view.updateStatus(session.message)
                            }
                        }
                    }
                }
            },
            onBack = {
                if (isCurrent(recoveryId)) {
                    if (session == null) cancelAndDismiss(recoveryId, "Your list is saved. Nothing was changed.")
                    else if (!session.hasChanges) discardEdit()
                    else if (!session.checkpoint()) reportFailure()
                    else overview(session.record)
                }
            },
        )
        returnFocus?.let { (k, q) -> view.post { if (isCurrent(recoveryId)) view.focusOrigin(k, q) } }
    }

    private fun showContextQuestion(
        operationId: Long, preparation: GroceryIntentContextPreparation, proposalIndex: Int?,
        address: SwiggyAddress, items: List<ParsedItem>, selected: List<RequestedItem>,
    ) = showInterpretationQuestion(operationId, preparation.instruction, preparation.proposals, proposalIndex,
        address, items, selected, { contextPreparation === preparation }, { contextPreparation = preparation.disputeBackground(it) })

    private fun showAuditedQuestion(
        operationId: Long, basket: AuditedIntentBasket, proposalIndex: Int?,
        address: SwiggyAddress, items: List<ParsedItem>, selected: List<RequestedItem>,
    ) = showInterpretationQuestion(operationId, basket.instruction, basket.backgroundProposals, proposalIndex,
        address, items, selected, { auditedBasket === basket }, { auditedBasket = null })

    private fun showInterpretationQuestion(
        operationId: Long, instruction: String, proposals: List<GroceryIntentContextProposal>, proposalIndex: Int?,
        address: SwiggyAddress, items: List<ParsedItem>, selected: List<RequestedItem>,
        sameInterpretation: () -> Boolean, disputeBackground: (Int) -> Unit,
    ) {
        if (!isCurrent(operationId) || mutationInFlight || !sameInterpretation()) return
        val proposal = proposalIndex?.let { proposals.getOrNull(it) }
        if (proposalIndex != null && proposal?.kind != GroceryIntentContextKind.BACKGROUND) return
        val questionId = ++operationGeneration
        cartPlanRefresh.invalidate()
        reviewedRequest = null
        quantityProposalGate.clear()
        val originalOnly = proposalIndex == null
        stepDialog.show(SwiggyStepScreen(
            eyebrow = "How Beta understood your request",
            title = if (originalOnly) "Your original request" else "Does this change an item?",
            message = if (originalOnly) recoveryStore.loadRecovery()?.takeIf { it.instruction == instruction }
                ?.originalInstruction ?: instruction else "“${proposal?.source?.text}”",
            caption = if (originalOnly) "Nothing has been added." else "Used as background, not as an item",
            primary = SwiggyStepAction(if (originalOnly) "Edit your list" else "Change this item") {
                if (isCurrent(questionId)) {
                    val provider = contextProvider
                    if (provider == null) cancelAndDismiss(questionId, "Your list is saved. Please check it again. Nothing was changed.")
                    else {
                        if (proposalIndex != null) disputeBackground(proposalIndex)
                        showIntentEditor(questionId, instruction, provider)
                    }
                }
            },
            secondary = SwiggyStepAction(if (originalOnly) "Back to review" else "It's fine as background") {
                if (isCurrent(questionId) && sameInterpretation())
                    planCart(questionId, address, items, selected)
            },
            cancel = { cancelAndDismiss(questionId, "Your list is saved. Nothing was changed.") },
        ))
        announce(if (originalOnly) "Your original request. Nothing has been added." else
            "Does this change an item? ${proposal?.source?.text}")
    }

    private fun showIntentEditor(
        operationId: Long,
        instruction: String,
        provider: GroceryIntentProvider,
        snapshot: GroceryIntentRecoverySnapshot? = null,
        editorText: String = instruction,
        lastSavedText: String = instruction,
        returnFromWhole: Boolean = false,
    ) {
        if (!isCurrent(operationId)) return
        if (!returnFromWhole && (activeIntentSelection?.contract == GroceryIntentContract.CONTEXT ||
            recoveryStore.loadRecovery()?.instruction == instruction)) {
            val record = recoveryStore.openRecovery(instruction)
            if (record == null) cancelAndDismiss(operationId, GroceryIntentScopedRecoverySession.FAILED_SAVE)
            else {
                val quote = GroceryIntentReviewV2Quote(instruction, 0, instruction.length)
                showScopedRecoveryRecord(record, provider, listOf(quote), quote)
            }
            return
        }
        clearScopedRecovery() // A cancelled whole editor must not reappear on the next host resume.
        val recoveryId = ++operationGeneration
        recoverySession?.invalidate()
        inputRepairReview?.invalidate()
        inputRepairReview = null
        clearDraft()
        clearInterpretation()
        val session = GroceryIntentRecoverySession(instruction,
            isCurrent = { isCurrent(recoveryId) && !activity.isFinishing && !activity.isDestroyed },
            persist = onInstructionEdited, blankMessage = activity.getString(R.string.order_input_required),
            initialText = editorText, initiallySavedText = lastSavedText)
        recoverySession = session
        activeRecovery = RecoveryCheckpoint(instruction, provider, snapshot, editorText, lastSavedText)
        lateinit var view: GroceryIntentRecoveryView
        fun handle(outcome: GroceryIntentRecoverySession.Outcome) {
            when (outcome) {
                GroceryIntentRecoverySession.Outcome.None -> view.updateStatus(session.message, session.canLeaveWithoutSaving)
                is GroceryIntentRecoverySession.Outcome.Recheck -> startIntentPreparation(outcome.text)
                is GroceryIntentRecoverySession.Outcome.Voice -> {
                    cancel()
                    onRecoveryVoiceRequested()
                }
                is GroceryIntentRecoverySession.Outcome.Exit -> {
                    // The Home composer always contains the last successfully saved text.
                    cancel()
                }
            }
        }
        val display = (snapshot ?: GroceryIntentRecoverySnapshot.generic(instruction)).toDisplay().copy(editorText = editorText)
        view = stepDialog.showRecovery(display,
            onTextChanged = { text -> session.edit(text); view.updateStatus(session.message, session.canLeaveWithoutSaving) },
            onRecheck = { handle(session.attempt(GroceryIntentRecoverySession.Action.RECHECK)) },
            onVoice = { handle(session.attempt(GroceryIntentRecoverySession.Action.VOICE)) },
            onCancel = { handle(session.attempt(GroceryIntentRecoverySession.Action.EXIT)) },
            onLeaveWithoutSaving = { handle(session.leaveWithoutSaving()) },
            allowDirtyWholeChoice = lastSavedText == instruction,
            onEditWholeMessage = if (lastSavedText != instruction) null else ({
                fun openFromLegacy() {
                    if (!isCurrent(recoveryId) || session.lastSavedText != instruction) return
                    val base = recoveryStore.openRecovery(instruction)
                    if (base == null || base.dirty != null) view.updateStatus(GroceryIntentScopedRecoverySession.FAILED_SAVE, false)
                    else {
                        showScopedRecoveryRecord(base, provider, emptyList(), GroceryIntentReviewV2Quote(instruction, 0, instruction.length),
                            kind = GroceryIntentEditKind.WHOLE_MESSAGE, legacyOrigin = snapshot ?: GroceryIntentRecoverySnapshot.generic(instruction))
                        announce(WHOLE_EDIT_PROMPT)
                    }
                }
                if (session.text == instruction) openFromLegacy()
                else {
                    val draftAtPrompt = session.text
                    recoveryDecision(recoveryId, "You have an unsaved change.", "Continue editing", "Discard",
                        { recoverySession === session && session.text == draftAtPrompt && session.lastSavedText == instruction },
                        {}, ::openFromLegacy)
                }
            }))
        if (returnFromWhole) view.post { if (isCurrent(recoveryId)) view.focusWholeMessageAction() }
        if (restoringRecoveryAfterSaveFailure) {
            view.updateStatus(GroceryIntentRecoverySession.FAILED_SAVE, false)
        }
        announce(if (restoringRecoveryAfterSaveFailure) GroceryIntentRecoverySession.FAILED_SAVE else display.spokenPrompt)
    }

    private fun showIntentInputRepair(
        operationId: Long,
        instruction: String,
        provider: GroceryIntentProvider,
        repair: GroceryIntentInputRepair,
    ) {
        if (!isCurrent(operationId)) return
        if (repair.originalInstruction != instruction || recoveryStore.loadRecovery()?.instruction == instruction) {
            showIntentEditor(operationId, instruction, provider)
            return
        }
        // Reject the outstanding address callback and any earlier preparation before asking.
        val repairId = ++operationGeneration
        clearDraft()
        clearInterpretation()
        var updateStatus: (String?, Boolean) -> Unit = { _, _ -> }
        val review = GroceryIntentInputRepairReview(
            repair = repair,
            isCurrent = { isCurrent(repairId) && !activity.isFinishing && !activity.isDestroyed },
            persist = onInstructionEdited,
            recheck = { edited ->
                // This is new user-confirmed text, not a ready row or an approved cart.
                startIntentPreparation(edited, allowQuickRepair = false)
            },
            failed = {}, // The same screen exposes an inline retry; never dismiss a failed save.
            edit = {
                showIntentEditor(repairId, instruction, provider)
            },
            cancel = { cancelAndDismiss(repairId, "Your list is saved. Nothing was added.") },
            onStatusChanged = { message, canLeave -> updateStatus(message, canLeave) },
            onUnsavedExit = { cancel() },
        )
        inputRepairReview = review
        updateStatus = stepDialog.showInputRepair(review.screen(), review::leaveWithoutSaving)
        announce(review.question)
    }

    private fun showIntentRecovery(operationId: Long, instruction: String, ambiguous: Boolean, affectedText: List<String> = emptyList(), unavailableForInstallation: Boolean = false) {
        // Invalidate outstanding preparation callbacks before showing a recovery choice.
        if (!isCurrent(operationId)) return
        operationGeneration += 1
        val recoveryId = operationGeneration
        val named = activeIntentSelection?.takeIf { !ambiguous && it.contract == GroceryIntentContract.INTERPRETED }?.provider
        val message = if (ambiguous) {
            "I could not safely understand the whole list. Please check the item names and amounts together. Your list is saved; nothing was added."
        } else if (named != null) {
            "${named.displayName} isn’t available. Nothing was switched."
        } else if (unavailableForInstallation) {
            "Enhanced understanding is not available for this connection yet, so it has been turned off. Your list is saved. You can choose standard matching below."
        } else {
            "Enhanced understanding is unavailable right now. Your list is saved. You can try standard matching and review its suggestions before anything is added."
        }
        stepDialog.show(SwiggyStepScreen(
            eyebrow = "Your list is safe",
            title = if (ambiguous) "Please check your list" else "Try standard matching?",
            message = message,
            caption = "Nothing was changed in your Swiggy cart.",
            rows = affectedText.distinct().map { text ->
                SwiggyStepRow(title = text, detail = "Please check this part of your list", badge = "Needs review")
            },
            primary = SwiggyStepAction(if (named != null) "Edit my list" else "Edit your list") {
                if (isCurrent(recoveryId)) {
                    cancelAndDismiss(recoveryId, "Your list is ready to edit.")
                    onEditRequest()
                }
            },
            secondary = if (ambiguous) null else SwiggyStepAction(if (named != null) "Use standard matching for this list" else "Try standard matching") {
                if (isCurrent(recoveryId)) {
                    cancelAndDismiss(recoveryId, "Using standard matching. Please check every suggestion.")
                    startStandardPreparation(instruction)
                }
            },
            cancel = { cancelAndDismiss(recoveryId, "Your list is saved. Nothing was changed.") },
        ))
        announce(message)
    }

    fun cancel(): Boolean = cancelInternal(preservePausedRecovery = false)

    private fun clearPausedRecovery() {
        pausedRecovery = null
        retainedRecovery.checkpoint = null
        retainedRecovery.scoped = null
        preparationInterrupted = false
        interruptedDraftSaveFailed = false
    }

    private fun clearScopedRecovery() {
        scopedDecision?.dismiss()
        scopedDecision = null
        scopedLegacyOrigin = null
        scopedRecovery?.invalidate()
        scopedRecovery = null
        scopedProvider = null
        scopedQuotes = emptyList()
    }

    /** Decisions apply only to the displayed edit; Back/rotation never confirm them. */
    private fun recoveryDecision(id: Long, title: String, yes: String, no: String, stillCurrent: () -> Boolean,
                                 onYes: () -> Unit, onNo: () -> Unit) {
        if (!isCurrent(id) || !hostResumed || scopedDecision != null || activity.isFinishing || activity.isDestroyed) return
        lateinit var dialog: androidx.appcompat.app.AlertDialog
        fun applyChoice(action: () -> Unit) {
            if (scopedDecision === dialog && isCurrent(id) && hostResumed && !activity.isDestroyed &&
                !activity.isFinishing && stillCurrent()) action()
        }
        dialog = androidx.appcompat.app.AlertDialog.Builder(activity).setTitle(title)
            .setPositiveButton(yes) { _, _ -> applyChoice(onYes) }
            .setNegativeButton(no) { _, _ -> applyChoice(onNo) }.create()
        scopedDecision = dialog
        dialog.setOnDismissListener { if (scopedDecision === dialog) scopedDecision = null }
        dialog.show()
    }

    internal fun detachForConfigurationChange() {
        cancelInternal(preservePausedRecovery = true)
    }

    private fun cancelInternal(preservePausedRecovery: Boolean): Boolean {
        if (mutationInFlight) return false
        if (!preservePausedRecovery) clearPausedRecovery()
        recoverySession?.invalidate()
        recoverySession = null
        clearScopedRecovery()
        inputRepairReview?.invalidate()
        inputRepairReview = null
        activeRecovery = null
        operationGeneration += 1
        running = false
        clearDraft()
        clearInterpretation()
        dismissActiveDialog()
        notifyTerminal()
        return true
    }

    fun clearRememberedAddress(): Boolean {
        if (mutationInFlight) return false
        selectedAddressId = null
        selectedAddressAtElapsedRealtime = 0L
        selectedAddressNeedsRevalidation = false
        onAddressChanged(null)
        return true
    }

    /** Keep the local choice through auth/network recovery, never treat it as fresh consent. */
    fun requireRememberedAddressRevalidation() {
        if (selectedAddressId != null) selectedAddressNeedsRevalidation = true
    }

    fun isMutationInFlight(): Boolean = mutationInFlight

    fun isActive(): Boolean = running || mutationInFlight

    /** Detects Beta leaving the foreground, not which other app the user opened. */
    fun onHostPaused() {
        cartPlanRefresh.invalidate()
        reviewedRequest = null
        quantityProposalGate.clear()
        hostResumed = false
        if (mutationInFlight) {
            interruptedDuringRequest = true
        } else if (running) {
            preparationInterrupted = true
            scopedRecovery?.let { session ->
                session.checkpoint()
                scopedProvider?.takeIf { session.hasChanges || session.kind == GroceryIntentEditKind.WHOLE_MESSAGE }?.let { provider ->
                    retainedRecovery.scoped = ScopedRecoveryCheckpoint(session.record, session.quote, session.text, provider, scopedQuotes, session.kind, scopedLegacyOrigin)
                }
            }
            interruptedDraftSaveFailed = recoverySession?.saveDraft() == false || inputRepairReview?.saveDraft() == false
            if (interruptedDraftSaveFailed) recoverySession?.let { session ->
                // Retain failed-save edits in memory across configuration changes,
                // never on disk and never as an automatically restarted request.
                pausedRecovery = activeRecovery?.copy(text = session.text, lastSavedText = session.lastSavedText)
                retainedRecovery.checkpoint = pausedRecovery
            }
            cancelInternal(preservePausedRecovery = true) // Never resume the unconfirmed request.
        }
    }

    fun onHostResumed(suppressCartReview: Boolean = false): Boolean {
        hostResumed = true
        if (suppressCartReview || isCheckoutActive()) return true
        if (SwiggyCartMutationGuard.isInFlight()) {
            waitingForExistingRequest = !mutationInFlight
            return true
        }
        if (running) return true
        if (pendingCartStore.isPending()) {
            checkPendingCartOnReturn()
            return true
        }
        val retainedScoped = retainedRecovery.scoped
        val savedScoped = recoveryStore.loadRecovery()
        if (retainedScoped != null && savedScoped != null &&
            (savedScoped == retainedScoped.record ||
                savedScoped == retainedScoped.record.withDirty(retainedScoped.quote, retainedScoped.text, retainedScoped.kind))) {
            retainedRecovery.scoped = null
            preparationInterrupted = false
            showScopedRecoveryRecord(savedScoped, retainedScoped.provider, retainedScoped.quotes,
                retainedScoped.quote, restored = retainedScoped.text != retainedScoped.quote.text, unsavedText = retainedScoped.text,
                kind = retainedScoped.kind, legacyOrigin = retainedScoped.legacyOrigin)
            return true
        }
        if (savedScoped?.dirty != null) {
            val provider = GroceryIntentPreferences.getMode(activity).providerOrNull() ?: GroceryIntentProvider.GEMINI
            preparationInterrupted = false
            showScopedRecoveryRecord(savedScoped, provider, listOfNotNull(savedScoped.dirty.quote.takeIf { savedScoped.dirty.kind == GroceryIntentEditKind.PHRASE }),
                savedScoped.dirty.quote, restored = true, kind = savedScoped.dirty.kind)
            return true
        }
        if (preparationInterrupted) {
            preparationInterrupted = false
            pausedRecovery?.let { checkpoint ->
                pausedRecovery = null
                retainedRecovery.checkpoint = null
                interruptedDraftSaveFailed = false
                running = true
                restoringRecoveryAfterSaveFailure = true
                try {
                    showIntentEditor(operationGeneration, checkpoint.instruction, checkpoint.provider, checkpoint.snapshot,
                        checkpoint.text, checkpoint.lastSavedText)
                } finally {
                    restoringRecoveryAfterSaveFailure = false
                }
                return true
            }
            announce(if (interruptedDraftSaveFailed) "Your latest changes could not be saved. Please check the list below before continuing. Nothing was added."
                else activity.getString(R.string.swiggy_preparation_interrupted))
            interruptedDraftSaveFailed = false
            return true
        }
        return false
    }

    fun onSharedRequestSettled() {
        if (hostResumed && waitingForExistingRequest && !SwiggyCartMutationGuard.isInFlight()) {
            waitingForExistingRequest = false
            onHostResumed()
        }
    }

    private fun clearPendingCartReview(): Boolean = cartReviewStore.clear() && pendingCartStore.clear()

    private fun checkPendingCartOnReturn() {
        if (!hostResumed || running || SwiggyCartMutationGuard.isInFlight()) return
        val token = cartReviewStore.load()
        if (token == null) {
            restorePendingCartWarning()
            return
        }
        running = true
        interruptedDuringRequest = false
        val operationId = ++operationGeneration
        setMutationInFlight(true) // Block new writes while this read-only check is unresolved.
        val message = activity.getString(R.string.swiggy_return_check_message)
        stepDialog.show(SwiggyStepScreen(
            eyebrow = "Read-only check",
            title = "Checking your cart and address",
            message = message,
            caption = message,
            safetyNote = activity.getString(R.string.swiggy_keep_closed_warning),
        ))
        announce(message)
        checkReviewedCart(token) { result ->
            onUi(operationId) {
                running = false
                setMutationInFlight(false)
                if (!hostResumed || interruptedDuringRequest) {
                    stepDialog.dismiss()
                    if (hostResumed) restorePendingCartWarning()
                    return@onUi
                }
                if (result is SwiggyMcpResult.Success && result.value && clearPendingCartReview()) {
                    val checked = activity.getString(R.string.swiggy_return_check_matches)
                    stepDialog.show(SwiggyStepScreen(
                        eyebrow = "Checked now",
                        title = "Your cart and address still match",
                        message = checked,
                        caption = checked,
                        safetyNote = activity.getString(R.string.swiggy_handoff_warning),
                        primary = SwiggyStepAction("Done") { stepDialog.dismiss() },
                        cancel = { stepDialog.dismiss() },
                    ))
                    // This is not another addition or activation event.
                    announce(checked)
                    notifyTerminal()
                } else {
                    val failure = result as? SwiggyMcpResult.Failure
                    if (failure?.reconnectRequired == true) onReconnectRequired()
                    val warning = if (failure?.httpCode == 409) {
                        activity.getString(R.string.swiggy_return_check_changed)
                    } else activity.getString(R.string.swiggy_return_check_unknown)
                    restorePendingCartWarning(warning)
                }
            }
        }
    }

    fun restorePendingCartWarning(message: String? = null): Boolean {
        if (running || SwiggyCartMutationGuard.isInFlight() || !pendingCartStore.isPending()) return false
        val warning = message ?: activity.getString(R.string.swiggy_return_check_unknown)
        stepDialog.show(SwiggyStepScreen(
            eyebrow = "Review needed",
            title = "Check your last cart update",
            message = warning,
            caption = warning,
            safetyNote = activity.getString(R.string.swiggy_handoff_warning),
            primary = SwiggyStepAction("Open Swiggy to review") {
                CommerceAppLauncher.launchPreferred(activity, "open swiggy")
                stepDialog.dismiss()
            },
            secondary = SwiggyStepAction("I have reviewed the cart") {
                if (clearPendingCartReview()) {
                    stepDialog.dismiss()
                    announce("You can now start a new list. Beta has not changed the cart.")
                    notifyTerminal()
                } else announce("Beta could not save that confirmation. Please try again.")
            },
            tertiary = cartReviewStore.load()?.let {
                SwiggyStepAction("Check again without adding") { checkPendingCartOnReturn() }
            },
            cancel = { stepDialog.dismiss() },
        ))
        announce(warning)
        notifyTerminal()
        return true
    }

    private fun rankAndChooseAddress(
        operationId: Long,
        addresses: List<SwiggyAddress>,
        items: List<ParsedItem>,
    ) {
        SwiggyMcpClient.fetchRecentAddressIds(activity) { result ->
            onUi(operationId) {
                val recentAddressIds = (result as? SwiggyMcpResult.Success)?.value.orEmpty()
                addressIntelligence.rank(addresses, recentAddressIds) { ranked ->
                    onUi(operationId) {
                        addressSuggestionReasons = ranked
                            .mapNotNull { entry -> entry.reason?.let { entry.address.id to it } }
                            .toMap()
                        addressLocationAssessments = ranked.associate { it.address.id to it.locationAssessment }
                        chooseAddress(operationId, ranked.map(RankedSwiggyAddress::address), items)
                    }
                }
            }
        }
    }

    private fun showYourList(operationId: Long, items: List<ParsedItem>, caption: String) {
        stepDialog.show(
            SwiggyStepScreen(
                eyebrow = "Your list · ${items.size} items",
                title = "Here is what Beta understood",
                message = "Beta will confirm your saved delivery address, search live Instamart stock, and show every cart change before adding anything.",
                caption = caption,
                rows = items.mapIndexed { index, item ->
                    SwiggyStepRow(
                        title = displayItem(item),
                        detail = swiggyIntentQuantitySource(item)?.let { "You said: $it" }
                            ?: "Item ${index + 1} of ${items.size}",
                        badge = "Ready",
                        tone = SwiggyStepTone.SUCCESS,
                    )
                },
                safetyNote = activity.getString(R.string.swiggy_step_no_checkout),
                cancel = { cancelAndDismiss(operationId, "Swiggy cart changes cancelled.") },
            )
        )
    }

    private fun chooseAddress(
        operationId: Long,
        addresses: List<SwiggyAddress>,
        items: List<ParsedItem>,
        pageIndex: Int = 0,
        allowRememberedAddress: Boolean = true,
        showAllAddresses: Boolean = false,
    ) {
        val usable = addresses.filter { it.id.isNotBlank() }.distinctBy { it.id }.take(MAX_ADDRESSES)
        if (usable.isEmpty()) {
            finish(operationId, "No saved Swiggy delivery address was found. Add an address in Swiggy, then try again.")
            return
        }
        if (
            allowRememberedAddress && (selectedAddressNeedsRevalidation || isRememberedSwiggyAddressFresh(
                selectedAtElapsedRealtime = selectedAddressAtElapsedRealtime,
                nowElapsedRealtime = SystemClock.elapsedRealtime(),
                ttlMillis = ADDRESS_CONFIRMATION_TTL_MS,
            ))
        ) {
            rememberedSwiggyAddress(usable, selectedAddressId)?.let { remembered ->
                onAddressChanged(remembered.shortLabel)
                val ageMinutes = ((SystemClock.elapsedRealtime() - selectedAddressAtElapsedRealtime) / 60_000L)
                    .coerceAtLeast(0L)
                val presentation = swiggyAddressPresentation(remembered)
                val caption = "${presentation.title} was confirmed ${ageMinutes} min ago. Check the address before continuing."
                stepDialog.show(
                    SwiggyStepScreen(
                        eyebrow = "Step 1 of 4 · Delivery address",
                        title = if (selectedAddressNeedsRevalidation) "Still delivering to ${presentation.title}?" else "Use this delivery address?",
                        message = swiggyAddressSuggestionPrompt(addressLocationAssessments[remembered.id]),
                        caption = "",
                        rows = listOf(
                            SwiggyStepRow(
                                title = presentation.title,
                                detail = presentation.detail,
                                badge = if (selectedAddressNeedsRevalidation) "Please confirm again" else "Confirmed ${ageMinutes} min ago",
                                tone = SwiggyStepTone.SUCCESS,
                            )
                        ),
                        primary = SwiggyStepAction("Deliver here") {
                            if (isCurrent(operationId)) {
                                rememberAddress(remembered)
                                collectRecommendations(operationId, remembered, items)
                            }
                        },
                        secondary = SwiggyStepAction("Change address") {
                            if (isCurrent(operationId)) chooseAddress(operationId, usable, items, allowRememberedAddress = false, showAllAddresses = true)
                        },
                        tertiary = SwiggyStepAction(activity.getString(R.string.swiggy_step_cancel_list)) {
                            cancelAndDismiss(operationId, "Swiggy cart changes cancelled.")
                        },
                        cancel = { cancelAndDismiss(operationId, "Swiggy cart changes cancelled.") },
                    )
                )
                announce("${swiggySpokenAddressSuggestion(remembered)} $caption")
                return
            }
        }
        clearRememberedAddress()

        // Make the safe default a single, readable recommendation.  Elderly
        // users should be able to accept the address we ranked from current
        // cart/recent use/location without first scanning a long provider list.
        // The full list remains one explicit action away and keeps the same
        // paging and exact provider-ID selection path.
        val suggestedAddress = usable.firstOrNull()
        if (!showAllAddresses && suggestedAddress != null) {
            val presentation = swiggyAddressPresentation(suggestedAddress)
            val suggestionReason = addressSuggestionReasons[suggestedAddress.id]
            val locationNotice = swiggyAddressSuggestionPrompt(addressLocationAssessments[suggestedAddress.id])
            stepDialog.show(
                SwiggyStepScreen(
                    eyebrow = "Step 1 of 4 · Delivery address",
                    title = "Use this delivery address?",
                    message = locationNotice,
                    caption = "",
                    rows = listOf(
                        SwiggyStepRow(
                            title = presentation.title,
                            detail = presentation.detail,
                            badge = if (suggestionReason == null) "Suggested" else "Suggested · $suggestionReason",
                            tone = SwiggyStepTone.SUCCESS,
                        )
                    ),
                    primary = SwiggyStepAction("Deliver here") {
                        if (isCurrent(operationId)) {
                            rememberAddress(suggestedAddress)
                            collectRecommendations(operationId, suggestedAddress, items)
                        }
                    },
                    secondary = SwiggyStepAction("Change address") {
                        if (isCurrent(operationId)) {
                            chooseAddress(
                                operationId,
                                usable,
                                items,
                                pageIndex = 0,
                                allowRememberedAddress = false,
                                showAllAddresses = true,
                            )
                        }
                    },
                    tertiary = SwiggyStepAction(activity.getString(R.string.swiggy_step_cancel_list)) {
                        cancelAndDismiss(operationId, "Swiggy cart changes cancelled.")
                    },
                    cancel = { cancelAndDismiss(operationId, "Swiggy cart changes cancelled.") },
                )
            )
            announce("${swiggySpokenAddressSuggestion(suggestedAddress)} $locationNotice")
            return
        }

        val pageCount = (usable.size + SWIGGY_ADDRESS_PAGE_SIZE - 1) / SWIGGY_ADDRESS_PAGE_SIZE
        val safePageIndex = pageIndex.coerceIn(0, pageCount - 1)
        val pageStart = safePageIndex * SWIGGY_ADDRESS_PAGE_SIZE
        val pageEnd = minOf(pageStart + SWIGGY_ADDRESS_PAGE_SIZE, usable.size)
        val labels = swiggyAddressChoiceLabels(usable)
        val suggested = usable.firstOrNull()?.takeIf { addressSuggestionReasons.containsKey(it.id) }
        val caption = suggested?.let {
            "${it.shortLabel} is suggested. Choose it or another saved Swiggy delivery address. Showing ${pageStart + 1} to $pageEnd of ${usable.size}."
        } ?: "Choose the saved Swiggy delivery address for this cart. Showing ${pageStart + 1} to $pageEnd of ${usable.size}."
        stepDialog.show(
            SwiggyStepScreen(
                eyebrow = "Step 1 of 4 · Delivery address",
                title = "Where should Swiggy deliver?",
                message = "Choose one saved address. Location is only a suggestion; you may choose an address for someone else. " +
                    if (addressLocationAssessments.values.all { it == SwiggyLocationAssessment.UNKNOWN }) "Location is unavailable. Check the house or flat and area." else "Check the house or flat and area before choosing.",
                caption = caption,
                choices = (pageStart until pageEnd).map { index ->
                    val presentation = swiggyAddressPresentation(usable[index])
                    SwiggyStepChoice(
                        title = labels[index],
                        detail = presentation.detail + " · " +
                            swiggyAddressSuggestionDetail(usable[index], index, addressSuggestionReasons) +
                            if (addressLocationAssessments[usable[index].id] == SwiggyLocationAssessment.NOT_MATCHED) " · Not matched to your current area" else "",
                        onClick = {
                            if (isCurrent(operationId)) {
                                rememberAddress(usable[index])
                                collectRecommendations(operationId, usable[index], items)
                            }
                        },
                    )
                },
                primary = if (pageEnd < usable.size) {
                    SwiggyStepAction("Show next ${minOf(SWIGGY_ADDRESS_PAGE_SIZE, usable.size - pageEnd)} addresses") {
                        if (isCurrent(operationId)) {
                            chooseAddress(
                                operationId,
                                usable,
                                items,
                                pageIndex = safePageIndex + 1,
                                allowRememberedAddress = false,
                                showAllAddresses = true,
                            )
                        }
                    }
                } else null,
                secondary = if (safePageIndex > 0) {
                    SwiggyStepAction("Show previous addresses") {
                        if (isCurrent(operationId)) {
                            chooseAddress(
                                operationId,
                                usable,
                                items,
                                pageIndex = safePageIndex - 1,
                                allowRememberedAddress = false,
                                showAllAddresses = true,
                            )
                        }
                    }
                } else null,
                tertiary = SwiggyStepAction(activity.getString(R.string.swiggy_step_cancel_list)) {
                    cancelAndDismiss(operationId, "Swiggy cart changes cancelled.")
                },
                cancel = { cancelAndDismiss(operationId, "Swiggy cart changes cancelled.") },
            )
        )
        announce(caption)
    }

    private fun collectRecommendations(
        operationId: Long,
        address: SwiggyAddress,
        items: List<ParsedItem>,
    ) {
        // A retained semantic hold is not an empty context or permission to
        // fall through to caller-supplied legacy rows, even from a stale UI.
        if (!isCurrent(operationId) || contextHeldDraft != null) return
        clearDraft()
        val sourceItems = contextPreparation?.legacyPreparation?.items ?: items
        val queries = sourceItems.map(::swiggyRecommendationQuery)
        val strictMatchPhrases = sourceItems.map { it.strictMatchPhrase }
        showRecommendationProgress(operationId, address, sourceItems, completedCount = 0)
        fetchRecommendationChunk(
            operationId = operationId,
            address = address,
            items = sourceItems,
            queries = queries,
            strictMatchPhrases = strictMatchPhrases,
            offset = 0,
            accumulated = mutableListOf(),
        )
    }

    private fun showRecommendationProgress(
        operationId: Long,
        address: SwiggyAddress,
        items: List<ParsedItem>,
        completedCount: Int,
    ) {
        if (!isCurrent(operationId)) return
        val batchEnd = minOf(completedCount + SWIGGY_RECOMMENDATION_BATCH_SIZE, items.size)
        val caption = if (items.size <= SWIGGY_RECOMMENDATION_BATCH_SIZE) {
            "Finding options for all ${items.size} items using your recent Swiggy choices."
        } else {
            "Finding options for items ${completedCount + 1} to $batchEnd of ${items.size}."
        }
        stepDialog.show(
            SwiggyStepScreen(
                eyebrow = "Step 2 of 4 · Searching",
                title = activity.getString(R.string.swiggy_step_progress_title, items.size),
                message = activity.getString(R.string.swiggy_step_progress_detail),
                caption = caption,
                rows = items.mapIndexed { index, item ->
                    val completed = index < completedCount
                    val searching = index in completedCount until minOf(completedCount + 4, batchEnd)
                    val queuedInBatch = index in completedCount until batchEnd
                    SwiggyStepRow(
                        title = displayItem(item),
                        detail = when {
                            completed -> "Options found in your list order"
                            searching -> "Searching live stock at ${address.shortLabel}"
                            queuedInBatch -> "Queued in this search group"
                            else -> "Waiting in your list order"
                        },
                        badge = when {
                            completed -> "Found"
                            searching -> "Searching"
                            else -> "Queued"
                        },
                        tone = when {
                            completed -> SwiggyStepTone.SUCCESS
                            searching -> SwiggyStepTone.AMBER
                            else -> SwiggyStepTone.NEUTRAL
                        },
                    )
                },
                safetyNote = "Beta searches a few products at a time and keeps every result in the order you said it.",
                cancel = { cancelAndDismiss(operationId, "Swiggy cart changes cancelled.") },
            )
        )
        announce(caption)
    }

    private fun fetchRecommendationChunk(
        operationId: Long,
        address: SwiggyAddress,
        items: List<ParsedItem>,
        queries: List<String>,
        strictMatchPhrases: List<String?>,
        offset: Int,
        accumulated: MutableList<Recommendations>,
    ) {
        if (!isCurrent(operationId)) return
        val catalogueSelection = activeIntentSelection
        val interpreted = items.any { it.hasAuditedIntent() }
        if (interpreted && (catalogueSelection == null || !auditedIntentBasketMatches(items))) {
            cancelAndDismiss(operationId, "Your list is saved. Nothing was changed.")
            return
        }
        if (!hasCurrentIntentConsent(operationId, catalogueSelection)) return
        if (offset >= queries.size) {
            if (!areSwiggyRecommendationsOrdered(accumulated, queries)) {
                finish(operationId, "Swiggy returned incomplete or out-of-order choices. Nothing was changed.")
                return
            }
            val catalogue = SwiggyMcpClient.reconcileCatalogueVetoes(accumulated.toList())
            draftRecommendations = catalogue
            announce("Preparing one suggested basket for you to review.")
            val preparation = contextPreparation
            val assessedItems = if (preparation != null) {
                val basket = GroceryIntentContextBasket.create(preparation, catalogue)
                if (basket == null) {
                    finish(operationId, "Beta could not match these choices to your request. Nothing was changed.")
                    return
                }
                contextBasket = basket
                basket.items
            } else items.mapIndexed { index, item -> assessSwiggyIntentQuantity(item, catalogue[index]) }
            chooseCandidate(operationId, address, assessedItems, catalogue, 0, mutableListOf())
            return
        }
        if (offset > 0) {
            showRecommendationProgress(operationId, address, items, completedCount = offset)
        }
        val end = minOf(offset + SWIGGY_RECOMMENDATION_BATCH_SIZE, queries.size)
        val batchQueries = queries.subList(offset, end)
        SwiggyMcpClient.fetchRecommendationBatch(
            context = activity,
            addressId = address.id,
            queries = batchQueries,
            strictMatchPhrases = strictMatchPhrases.subList(offset, end),
            ingredientProofs = items.subList(offset, end).map { it.ingredientProvenance },
            sourceItems = items.takeIf { interpreted },
            sourceOffset = offset.takeIf { interpreted && (offset > 0 || end < items.size) },
            sellerAssessmentProvider = contextProvider,
        ) { result ->
            onUi(operationId) {
                if (!hasCurrentIntentConsent(operationId, catalogueSelection)) return@onUi
                when (result) {
                    is SwiggyMcpResult.Success -> {
                        if (!areSwiggyRecommendationsOrdered(result.value, batchQueries)) {
                            finish(operationId, "Swiggy returned incomplete or out-of-order choices. Nothing was changed.")
                        } else {
                            accumulated += result.value
                            fetchRecommendationChunk(
                                operationId,
                                address,
                                items,
                                queries,
                                strictMatchPhrases,
                                offset = end,
                                accumulated = accumulated,
                            )
                        }
                    }
                    is SwiggyMcpResult.Failure -> fail(operationId, result)
                }
            }
        }
    }

    private fun chooseCandidate(
        operationId: Long,
        address: SwiggyAddress,
        items: List<ParsedItem>,
        recommendations: List<Recommendations>,
        index: Int,
        selected: MutableList<RequestedItem>,
        forceQuestion: Boolean = false,
    ) {
        if (!isCurrent(operationId)) return
        cartPlanRefresh.invalidate()
        reviewedRequest = null
        quantityProposalGate.clear()
        if (index >= items.size) {
            planCart(operationId, address, items, selected)
            return
        }
        if (index in draftSkipped) {
            chooseCandidate(operationId, address, items, recommendations, index + 1, selected)
            return
        }
        val item = items[index]
        val recommendation = recommendations[index]
        val usable = usableCandidates(index, item, recommendation)
        if (usable.isEmpty()) {
            showNoSubstitution(
                operationId = operationId,
                address = address,
                items = items,
                recommendations = recommendations,
            )
            return
        }

        if (!forceQuestion) {
            val suggestion = swiggyCurrentChoice(usable, draftCandidates[index])
                ?: swiggyDefaultSuggestion(item, usable, recommendation.suggested)
            draftCandidates[index] = suggestion
            val preview = if (contextPreparation != null) contextBasket?.preview(index, suggestion)
                else runCatching { swiggyPreviewItemFromParsedItem(suggestion, item) }.getOrNull()
            if (preview == null) {
                finish(operationId, "Beta could not verify this product amount. Nothing was changed.")
                return
            }
            selected += preview
            chooseCandidate(operationId, address, items, recommendations, index + 1, selected)
            return
        }

        val preferred = swiggyDefaultSuggestion(item, usable, recommendation.suggested)
        val caption = "Which ${item.query} do you want?"
        stepDialog.show(
            SwiggyStepScreen(
                eyebrow = "Change one item",
                title = caption,
                message = "Choose a replacement, or keep the suggestion. Your other items stay the same; nothing is added until you confirm the whole basket.",
                caption = caption,
                choices = usable.map { candidate ->
                    SwiggyStepChoice(
                        title = candidate.label,
                        detail = listOfNotNull(swiggyCandidateDetail(candidate), if (item.quantity is Quantity.Weight || item.quantity is Quantity.Volume) "${swiggyRequestedCartQuantity(item, candidate)} pack(s) for your requested amount" else null).joinToString(" · ").ifBlank { null },
                        badge = if (candidate === preferred) "Suggested" else null,
                        onClick = {
                            if (isCurrent(operationId)) {
                                draftCandidates[index] = candidate
                                chooseCandidate(++operationGeneration, address, items, recommendations, 0, mutableListOf())
                            }
                        },
                    )
                },
                safetyNote = if (item.quantity is Quantity.Count) {
                    "Beta checks pack count separately from cart quantity. One matching multi-piece pack is added as one cart line."
                } else null,
                secondary = SwiggyStepAction("Keep the suggestion") {
                    if (isCurrent(operationId)) {
                        draftCandidates[index] = preferred
                        chooseCandidate(++operationGeneration, address, items, recommendations, 0, mutableListOf())
                    }
                },
                tertiary = SwiggyStepAction(activity.getString(R.string.swiggy_step_cancel_list)) {
                    cancelAndDismiss(operationId, "Swiggy cart changes cancelled.")
                },
                cancel = { cancelAndDismiss(operationId, "Swiggy cart changes cancelled.") },
            )
        )
        announce(caption)
    }

    private fun planCart(
        operationId: Long,
        address: SwiggyAddress,
        items: List<ParsedItem>,
        selected: List<RequestedItem>,
    ) {
        if (!isCurrent(operationId) || !hostResumed || mutationInFlight) return
        cartPlanRefresh.invalidate()
        reviewedRequest = null
        quantityProposalGate.clear()
        val request = currentReviewRequest(items)
        val combined = request?.requestedItems.orEmpty()
        if (request != null && combined.isEmpty() && selected.isEmpty()) {
            finish(operationId, "No items are selected. Nothing was changed.")
            return
        }
        if (request == null || combined.isEmpty() || combined != consolidateSwiggyRequestedItems(selected)) {
            finish(operationId, "Please reduce or clarify the combined quantity for repeated products. Nothing was changed.")
            return
        }
        if (!fulfillmentStore.canAppend(intentReviewGate.fulfillmentConstraints)) {
            if (fulfillmentStore.read() == GroceryIntentConstraintStore.State.Unreadable)
                showUnreadableFulfillmentRecovery(operationId)
            else finish(operationId, "Beta could not safely save your delivery request. Nothing was added. Please edit your list.")
            return
        }
        BetaTelemetry.instance?.logEvent(
            "product_discovery_completed",
            mapOf("item_count" to selected.size),
        )
        announce("Reading your current Swiggy cart and preparing the exact changes.")
        stepDialog.show(SwiggyStepScreen(
            eyebrow = "Preparing your review",
            title = "Checking the exact cart changes",
            message = "Your suggested basket is ready. Beta is checking the existing cart before showing one final confirmation.",
            caption = "Nothing has been added yet.",
            cancel = { cancelAndDismiss(operationId, "Swiggy cart changes cancelled.") },
        ))
        cartPlanRefresh.select(SwiggyCartPlanRefresh.Input.Plan(operationId, address, request))?.let(::requestCartPlan)
    }

    private fun requestCartPlan(read: SwiggyCartPlanRefresh.Read) {
        // This consumer is plan-only. Snapshot scheduling is not connected to
        // the UI yet and cannot enter the reviewed-plan/apply path.
        val input = read.input
        require(input is SwiggyCartPlanRefresh.Input.Plan) { "Expected a cart plan read" }
        val (operationId, address, request) = input
        if (!isCurrent(operationId) || !hostResumed || activity.isFinishing || activity.isDestroyed) {
            // This reserved read was never sent, so it is safe to retire it.
            cartPlanRefresh.invalidate()
            cartPlanRefresh.complete(read)
            return
        }
        planCartRequest(address.id, request.requestedItems) { result ->
            activity.runOnUiThread {
                // Release even an old operation's call before filtering UI work.
                // Otherwise a cancel/restart could strand the newest selection.
                val canRender = !activity.isFinishing && !activity.isDestroyed && hostResumed
                if (!canRender) cartPlanRefresh.invalidate()
                val completion = cartPlanRefresh.complete(read)
                if (!canRender) return@runOnUiThread
                completion.next?.let(::requestCartPlan)
                if (completion.current == null || !isCurrent(operationId)) return@runOnUiThread
                val items = request.sourceItems
                if (request != currentReviewRequest(items)) {
                    finish(operationId, "Your list changed while the review was being prepared. Please check it again. Nothing was changed.")
                    return@runOnUiThread
                }
                when (result) {
                    is SwiggyMcpResult.Success -> reviewPlan(operationId, address, items, request.requestedItems, result.value)
                    is SwiggyMcpResult.Failure -> {
                        if (isAddressMismatch(result.userMessage)) {
                            showAddressMismatch(operationId, address, items, result.userMessage)
                        } else {
                            fail(operationId, result)
                        }
                    }
                }
            }
        }
    }

    private fun reviewPlan(
        operationId: Long,
        address: SwiggyAddress,
        items: List<ParsedItem>,
        selected: List<RequestedItem>,
        plan: SwiggyMcpClient.CartPlan,
    ) {
        reviewedRequest = null
        val request = currentReviewRequest(items)
        if (request == null || request.requestedItems != selected) {
            finish(operationId, "Beta could not match this review to your current list. Nothing was changed.")
            return
        }
        val token = plan.confirmationToken
        val quantityProposals = request.quantityProposals
        if (plan.changes.isEmpty() && selected.none { it.quantityMode == IntentCartQuantityMode.INCREMENT }) {
            if (quantityProposals.isEmpty() && contextPreparation == null) finish(operationId, "No Swiggy cart change is needed.")
            else {
                cancelAndDismiss(operationId, "Nothing was changed. Please edit your list to specify the pack amounts; Beta has not confirmed that your request is already in the cart.")
                onEditRequest()
            }
            return
        }
        if (token.isNullOrBlank() || !isSafeSwiggyCartPlan(plan, selected)) {
            finish(operationId, "Beta could not verify that the Swiggy cart plan only adds your selected items. Nothing was changed.")
            return
        }
        reviewedRequest = request
        val cartStartsEmpty = swiggyCartStartsEmpty(plan)
        intentReviewGate.beginReview()
        val unmatchedText = intentReviewGate.unresolvedText
        val excludedText = intentReviewGate.excludedText
        val fulfillmentConstraints = intentReviewGate.fulfillmentConstraints
        BetaTelemetry.instance?.logEvent(
            "cart_review_viewed",
            mapOf(
                "change_count" to plan.changes.size,
                "starts_empty" to cartStartsEmpty,
            ),
        )
        val preparation = contextPreparation
        val interpretationRows = preparation?.let {
            groceryIntentContextReviewRows(it,
                onBackground = { index -> showContextQuestion(operationId, it, index, address, items, selected) },
                onOriginal = { showContextQuestion(operationId, it, null, address, items, selected) })
        } ?: auditedBasket?.let {
            groceryIntentContextReviewRows(it.backgroundProposals,
                onBackground = { index -> showAuditedQuestion(operationId, it, index, address, items, selected) },
                onOriginal = { showAuditedQuestion(operationId, it, null, address, items, selected) })
        }.orEmpty()
        val rows = listOf(
            SwiggyStepRow(
                title = "Delivery address · ${address.categoryLabel}",
                detail = address.label,
                badge = "Please check",
                tone = SwiggyStepTone.AMBER,
                action = SwiggyStepAction("Change address") {
                    if (isCurrent(operationId) && !mutationInFlight) restartAddressSelection(operationId, items)
                },
            )
        ) + interpretationRows + groceryIntentFulfillmentReviewRows(fulfillmentConstraints) + intentReviewGate.sharedGroupNotes.map { note ->
            SwiggyStepRow(title = "Shared request", detail = note, badge = "Check with the items below")
        } + unmatchedText.map { text ->
            SwiggyStepRow(
                title = text,
                detail = "Beta could not verify how these words were used. Check them against the items below. If anything is missing or wrong, edit your list.",
                badge = "Please check this wording",
                tone = SwiggyStepTone.AMBER,
                action = SwiggyStepAction("Edit your list") {
                    if (isCurrent(operationId) && !mutationInFlight) {
                        cancelAndDismiss(operationId, "Your list is ready to edit. Nothing was changed.")
                        onEditRequest()
                    }
                },
            )
        } + groceryIntentExcludedReviewRows(excludedText) {
            if (isCurrent(operationId) && !mutationInFlight) {
                cancelAndDismiss(operationId, "Your list is ready to edit. Nothing was changed.")
                onEditRequest()
            }
        } + swiggyOrderedCartReviewRows(items, draftCandidates, draftSkipped, plan.changes,
            resolve = { index, item, candidate ->
                if (contextPreparation != null) contextBasket?.resolution(index, candidate)
                    ?: SwiggyCartQuantityResolution.Held("context_basket_missing")
                else resolveSwiggyCartQuantity(item, candidate)
            }) { index ->
            if (isCurrent(operationId) && !mutationInFlight) {
                chooseCandidate(++operationGeneration, address, items, draftRecommendations, index, mutableListOf(), forceQuestion = true)
            }
        }
        val caption = "Review everything together. Nothing has been added yet."
        val addressConfirmation = swiggySpokenAddressConfirmation(address)

        if (!plan.cartMutationEnabled) {
            stepDialog.show(
                SwiggyStepScreen(
                    eyebrow = "Step 3 of 4 · Cart preview",
                    title = "Check these cart changes",
                    message = "$addressConfirmation Cart updates are not enabled on the Beta backend, so this is a preview only.",
                    caption = caption,
                    rows = rows,
                    safetyNote = activity.getString(R.string.swiggy_step_no_checkout),
                    primary = SwiggyStepAction("Done") {
                        completeAndDismiss(operationId, "Your Swiggy cart preview is ready. Nothing was changed.")
                    },
                    secondary = SwiggyStepAction("Change address") {
                        if (isCurrent(operationId)) restartAddressSelection(operationId, items)
                    },
                    cancel = { completeAndDismiss(operationId, "Your Swiggy cart preview is ready. Nothing was changed.") },
                )
            )
            announce(addressConfirmation)
            return
        }

        stepDialog.show(
            SwiggyStepScreen(
                eyebrow = "Step 4 of 4 · Review",
                title = "Please check these cart changes",
                message = "$addressConfirmation ${addressLocationNotice(address)} " + activity.getString(
                    if (cartStartsEmpty) R.string.swiggy_step_review_empty else R.string.swiggy_step_review_existing
                ),
                caption = if (quantityProposals.isEmpty()) caption else
                    "${quantityProposals.size} requests use a pack amount Beta suggested — please check those. Nothing has been added yet.",
                rows = rows,
                safetyNote = "${activity.getString(R.string.swiggy_keep_closed_warning)} Check the suggested brands and packs together. Use Change only if needed. ${activity.getString(R.string.swiggy_step_no_checkout)}",
                acknowledgement = if (unmatchedText.isEmpty() && fulfillmentConstraints.isEmpty()) null else SwiggyStepAcknowledgement(
                    label = if (fulfillmentConstraints.isNotEmpty())
                        "I checked my request and the items. Prepare this cart now; a delivery time is NOT arranged."
                    else "I checked the wording above against my basket. Add only the items shown below.",
                    onCheckedChange = { checked ->
                        if (isCurrent(operationId) && !mutationInFlight) intentReviewGate.acknowledge(checked)
                    },
                ),
                quantityAcknowledgement = if (quantityProposals.isEmpty()) null else SwiggyStepAcknowledgement(
                    label = "I checked the ${quantityProposals.size} suggested pack amount(s) marked above.",
                    onCheckedChange = { checked ->
                        if (isCurrent(operationId) && !mutationInFlight) {
                            val current = currentReviewRequest(items)
                            if (current == null || current != reviewedRequest) quantityProposalGate.clear()
                            else quantityProposalGate.acknowledge(operationId, address.id, token, current.quantityProposals, checked)
                        }
                    },
                ),
                jumpToQuantityAcknowledgement = quantityProposals.isNotEmpty() && selected.size > 5,
                primary = groceryIntentCartReviewAction(
                    ordinaryLabel = if (cartStartsEmpty) "Add ${plan.changes.size} lines to cart" else "Apply ${plan.changes.size} cart changes",
                    gate = intentReviewGate,
                    isCurrent = { isCurrent(operationId) && !mutationInFlight },
                ) {
                    applyPlan(operationId, token, address, items, selected)
                },
                secondary = if (quantityProposals.isNotEmpty()) SwiggyStepAction("Change amounts — edit my list") {
                    if (isCurrent(operationId) && !mutationInFlight) {
                        cancelAndDismiss(operationId, "Your list is ready to edit. Nothing was changed.")
                        onEditRequest()
                    }
                } else SwiggyStepAction("Change address") {
                    if (isCurrent(operationId)) restartAddressSelection(operationId, items)
                },
                tertiary = SwiggyStepAction("Cancel - change nothing") {
                    cancelAndDismiss(operationId, "Swiggy cart changes cancelled.")
                },
                cancel = { cancelAndDismiss(operationId, "Swiggy cart changes cancelled.") },
            )
        )
        // Showing a new screen resets the old screen's callbacks first. Bind the
        // new consent only after that reset so stale callbacks cannot erase it.
        quantityProposalGate.prepare(operationId, address.id, token, quantityProposals)
        val skippedNotice = if (draftSkipped.isEmpty()) "" else " ${draftSkipped.size} items you chose to skip are not included."
        val excludedNotice = if (excludedText.isEmpty()) "" else " These parts will not be added: ${excludedText.joinToString("; ")}. Confirm only if you want to leave them out."
        val defaultNotice = if (draftCandidates.keys.any { items[it].quantity is Quantity.Default })
            " Where you did not give a quantity, Beta suggests one pack. Please check that suggestion." else ""
        val unmatchedNotice = if (unmatchedText.isEmpty()) "" else " Some wording needs your review. Check it against the basket, then edit your list if anything is missing or wrong. Check the box only when the items shown are the ones you want."
        val groupNotice = if (intentReviewGate.sharedGroupNotes.isEmpty()) "" else
            " Your shared requests were: ${intentReviewGate.sharedGroupNotes.joinToString(". ")}."
        val fulfillmentNotice = if (fulfillmentConstraints.isEmpty()) "" else
            " Your delivery request was: ${fulfillmentConstraints.joinToString(". ")}. $FULFILLMENT_LIMITATION Check the box only if you want to prepare this cart now."
        val quantityNotice = if (quantityProposals.isEmpty()) "" else
            " ${quantityProposals.size} requests use a pack amount I suggested. Tick the box when you've checked them. Nothing is added until you tap Add."
        val interpretation = preparation ?: auditedBasket
        val backgroundCount = (preparation?.proposals ?: auditedBasket?.backgroundProposals)
            ?.count { it.kind == GroceryIntentContextKind.BACKGROUND } ?: 0
        val contextNotice = if (interpretation != null && spokenContext !== interpretation && backgroundCount > 0)
            " Beta used $backgroundCount phrases as background, not as items. Check how Beta understood your request above." else ""
        spokenContext = interpretation
        announce("$addressConfirmation ${addressLocationNotice(address)}$contextNotice$skippedNotice$excludedNotice$defaultNotice$groupNotice$unmatchedNotice$fulfillmentNotice$quantityNotice Review all ${selected.size} cart lines and the suggested brands and pack quantities together. ${activity.getString(R.string.swiggy_keep_closed_warning)} Nothing is added until you confirm. No order will be placed.")
    }

    private fun restartAddressSelection(
        operationId: Long,
        items: List<ParsedItem>,
    ) {
        if (!isCurrent(operationId)) return
        val nextOperationId = ++operationGeneration
        clearDraft()
        clearRememberedAddress()
        announce("Choose a different Swiggy address. Beta will search every item again for that address.")
        SwiggyMcpClient.fetchAddresses(activity) { result ->
            onUi(nextOperationId) {
                when (result) {
                    is SwiggyMcpResult.Success -> rankAndChooseAddress(nextOperationId, result.value, items)
                    is SwiggyMcpResult.Failure -> fail(nextOperationId, result)
                }
            }
        }
    }

    private fun rememberAddress(address: SwiggyAddress) {
        selectedAddressId = address.id
        selectedAddressAtElapsedRealtime = SystemClock.elapsedRealtime()
        selectedAddressNeedsRevalidation = false
        addressIntelligence.recordSelection(address.id)
        onAddressChanged(address.shortLabel)
        BetaTelemetry.instance?.logEvent(
            "address_selected",
            mapOf("selection_reason" to (addressSuggestionReasons[address.id] ?: "saved_address")),
        )
    }

    private fun addressLocationNotice(address: SwiggyAddress): String = swiggyAddressLocationNotice(
        addressLocationAssessments[address.id] ?: SwiggyLocationAssessment.UNKNOWN,
    )

    private fun applyPlan(
        operationId: Long,
        token: String,
        address: SwiggyAddress,
        items: List<ParsedItem>,
        selected: List<RequestedItem>,
    ) {
        if (!isCurrent(operationId) || mutationInFlight) return
        if (!hostResumed) {
            cancelAndDismiss(operationId, "Your list is saved. Please review it again when you return. Nothing was added.")
            return
        }
        if (!intentReviewGate.canApply()) {
            // The dialog already consumed its one-shot action. Close that screen and
            // release running state instead of leaving all its controls inert.
            cancelAndDismiss(operationId, "Your list is saved. Please review the highlighted wording again. Nothing was added.")
            return
        }
        val currentRequest = currentReviewRequest(items)
        if (currentRequest == null || currentRequest != reviewedRequest || currentRequest.requestedItems != selected ||
            !cartPlanRefresh.isCurrentCompletedPlan(SwiggyCartPlanRefresh.Input.Plan(operationId, address, currentRequest)) ||
            !quantityProposalGate.consumeForApply(operationId, address.id, token, currentRequest.quantityProposals)) {
            cancelAndDismiss(operationId, "Your list is saved. Please check the suggested pack amounts again. Nothing was added.")
            return
        }
        reviewedRequest = null
        cartPlanRefresh.invalidate()
        // Save before any cart mutation or draft clearing. A crash after the
        // provider write must not let home-screen checkout forget these notes.
        if (!fulfillmentStore.appendBeforeApply(intentReviewGate.fulfillmentConstraints)) {
            if (fulfillmentStore.read() == GroceryIntentConstraintStore.State.Unreadable) {
                showUnreadableFulfillmentRecovery(operationId)
            } else finish(operationId, "Beta could not safely save your delivery request. Nothing was added. Please try again.")
            return
        }
        if (!pendingCartStore.markPending() || !cartReviewStore.save(token)) {
            finish(operationId, "Beta could not safely save the cart-check state. Nothing was added. Please try again.")
            return
        }
        if (!beforeApply()) {
            clearPendingCartReview()
            finish(operationId, "Beta could not safely save the change of state on this phone. Nothing was added. Please try your list again.")
            return
        }
        BetaTelemetry.instance?.logEvent("cart_apply_started", mapOf("item_count" to selected.size))
        interruptedDuringRequest = false
        setMutationInFlight(true)
        val caption = "Updating your Swiggy cart once, then checking that the changes stay. This takes about a minute."
        stepDialog.show(
            SwiggyStepScreen(
                eyebrow = "Applying once · Do not close",
                title = "Updating your cart once, then checking it",
                message = "Beta sends one confirmed update, checks every line, then checks again after 45 seconds before showing success.",
                caption = caption,
                rows = listOf(
                    SwiggyStepRow(
                        title = "Update sent",
                        detail = "Reading all ${selected.size} requested cart lines back now",
                        badge = "Checking...",
                        tone = SwiggyStepTone.AMBER,
                    )
                ),
                safetyNote = activity.getString(R.string.swiggy_keep_closed_warning),
                cancel = null,
            )
        )
        announce(caption)
        SwiggyMcpClient.applyCartPlan(activity, token) { result ->
            onUi(operationId) {
                if (!hostResumed || interruptedDuringRequest) {
                    // Never replay a write or trust a result that completed across an app switch.
                    running = false
                    setMutationInFlight(false)
                    stepDialog.dismiss()
                    if (hostResumed) checkPendingCartOnReturn()
                    return@onUi
                }
                when (result) {
                    is SwiggyMcpResult.Success -> {
                        if (result.value.verified && result.value.persistenceVerified) {
                            showVerifiedResult(operationId, address, selected, result.value.message, token)
                        } else {
                            BetaTelemetry.instance?.logEvent(
                                "cart_update_failed",
                                mapOf("reason" to "provider_unverified"),
                            )
                            finish(operationId, "Swiggy did not confirm the cart change. Please review your cart before continuing.")
                        }
                    }
                    is SwiggyMcpResult.Failure -> {
                        if (isAddressMismatch(result.userMessage)) {
                            setMutationInFlight(false)
                            showAddressMismatch(operationId, address, items, result.userMessage)
                        } else {
                            BetaTelemetry.instance?.logEvent(
                                "cart_update_failed",
                                mapOf("reason" to if (result.reconnectRequired) "reconnect_required" else "backend_failure"),
                            )
                            fail(operationId, result)
                        }
                    }
                }
            }
        }
    }

    private fun showUnreadableFulfillmentRecovery(operationId: Long) {
        stepDialog.show(groceryIntentUnreadableConstraintRecoveryScreen(
            discard = {
                if (isCurrent(operationId) && hostResumed && !mutationInFlight) {
                    if (fulfillmentStore.discardUnreadableAfterConfirmation()) {
                        android.util.Log.i("BetaAgent", "Unreadable fulfillment record discarded after confirmation")
                        cancelAndDismiss(operationId, "The unreadable request was forgotten. Your list is saved. Please review it again before adding items.")
                    } else finish(operationId, "Beta could not safely forget the unreadable request. Nothing was added. Please go back and try again.")
                }
            },
            back = { cancelAndDismiss(operationId, "Your list is saved. Nothing was added.") },
        ))
        announce(UNREADABLE_FULFILLMENT_WARNING)
    }

    private fun fail(operationId: Long, failure: SwiggyMcpResult.Failure) {
        if (!isCurrent(operationId)) return
        if (failure.cartPlanUnchanged && !failure.reconnectRequired) {
            reviewedRequest = null
            quantityProposalGate.clear()
            cancelAndDismiss(operationId, "Beta could not verify this cart review. Please review your list again. Nothing was changed.")
            onEditRequest()
            return
        }
        if (failure.reconnectRequired) onReconnectRequired()
        finish(operationId, failure.userMessage)
    }

    private fun finish(operationId: Long, message: String) {
        if (!isCurrent(operationId)) return
        clearDraft()
        clearInterpretation()
        val completedMutation = mutationInFlight
        running = false
        stepDialog.show(
            SwiggyStepScreen(
                eyebrow = if (completedMutation) "Review needed" else "Nothing added",
                title = if (completedMutation) "Please check your Swiggy cart" else "Beta stopped safely",
                message = message,
                caption = message,
                safetyNote = if (completedMutation) {
                    "Beta has stopped and will not retry this update. Open Swiggy and review the cart before starting another list."
                } else {
                    activity.getString(R.string.swiggy_step_no_checkout)
                },
                primary = if (completedMutation) {
                    SwiggyStepAction("Open Swiggy to review") {
                        CommerceAppLauncher.launchPreferred(activity, "open swiggy")
                        stepDialog.dismiss()
                    }
                } else {
                    SwiggyStepAction("Done") { stepDialog.dismiss() }
                },
                secondary = if (completedMutation) {
                    SwiggyStepAction("Close") { stepDialog.dismiss() }
                } else null,
                cancel = { stepDialog.dismiss() },
            )
        )
        if (completedMutation) setMutationInFlight(false)
        notifyTerminal(retainStatus = true)
        announce(message)
    }

    private fun showNoSubstitution(
        operationId: Long,
        address: SwiggyAddress,
        items: List<ParsedItem>,
        recommendations: List<Recommendations>,
    ) {
        if (!isCurrent(operationId)) return
        val unresolved = items.indices.filter { it !in draftSkipped && usableCandidates(it, items[it], recommendations[it]).isEmpty() }
        val otherItemCount = items.size - draftSkipped.size - unresolved.size
        val message = "Check these ${if (unresolved.size == 1) "item details" else "items"}, or continue without them. Nothing has been added."
        stepDialog.show(
            SwiggyStepScreen(
                eyebrow = "No substitution",
                title = if (unresolved.size == 1) "One item needs your help" else "${unresolved.size} items need your help",
                message = message,
                caption = "",
                rows = unresolved.map { missingIndex ->
                    val item = items[missingIndex]
                    val quantityIssue = swiggyIntentQuantityIssue(item, recommendations[missingIndex])
                    SwiggyStepRow(
                        title = displayItem(item),
                        detail = when {
                            quantityIssue == "numeric_product_quantity_ambiguous" ->
                                "The number may be part of the product name. Please edit your list to say how many packs you want. " +
                                    "Nothing was added. You said: ${swiggyIntentQuantitySource(item).orEmpty()}"
                            quantityIssue != null -> "Beta could not verify this quantity. Please check it in your list. Nothing was added."
                            swiggyNeedsExactHealthProduct(item) -> "Please name the exact product or brand. Beta will not guess a medicine from a symptom."
                            recommendations[missingIndex].usualProductUnavailable -> "Your usual product could not be verified as available here. Beta has not substituted a different brand or variant. You can edit this item or leave it out."
                            else -> swiggyNoCandidateMessage(
                                item,
                                address,
                                recommendations[missingIndex].catalogueMatchUnverified ||
                                    recommendations[missingIndex].candidates.isNotEmpty(),
                            )
                        },
                        badge = "Not added",
                        tone = SwiggyStepTone.AMBER,
                    )
                },
                safetyNote = if (otherItemCount > 0) {
                    "Your other ${if (otherItemCount == 1) "item is" else "$otherItemCount items are"} ready for review. Beta will not swap these missing products or packs."
                } else {
                    "Beta will not swap the product or pack, and your cart was not changed."
                },
                primary = if (otherItemCount > 0) {
                    SwiggyStepAction("Review $otherItemCount matched ${if (otherItemCount == 1) "item" else "items"}") {
                        if (isCurrent(operationId)) {
                            draftSkipped.addAll(unresolved)
                            chooseCandidate(++operationGeneration, address, items, recommendations, 0, mutableListOf())
                        }
                    }
                } else {
                    SwiggyStepAction("Edit your list") {
                        if (isCurrent(operationId)) {
                            cancelAndDismiss(operationId, "Your list is ready to edit. Nothing was changed.")
                            onEditRequest()
                        }
                    }
                },
                secondary = if (otherItemCount > 0) {
                    SwiggyStepAction("Edit your list") {
                        if (isCurrent(operationId)) {
                            cancelAndDismiss(operationId, "Your list is ready to edit. Nothing was changed.")
                            onEditRequest()
                        }
                    }
                } else null,
                tertiary = SwiggyStepAction("Cancel - change nothing") {
                    cancelAndDismiss(operationId, "Swiggy cart changes cancelled.")
                },
                cancel = { cancelAndDismiss(operationId, "Swiggy cart changes cancelled.") },
            )
        )
        announce(message)
    }

    private fun showAddressMismatch(
        operationId: Long,
        address: SwiggyAddress,
        items: List<ParsedItem>,
        message: String,
    ) {
        if (!isCurrent(operationId)) return
        stepDialog.show(
            SwiggyStepScreen(
                eyebrow = "Address mismatch · Nothing added",
                title = "The cart address changed",
                message = message,
                caption = message,
                rows = listOf(
                    SwiggyStepRow(
                        title = "Selected for this request",
                        detail = address.shortLabel,
                        badge = "Selected",
                        tone = SwiggyStepTone.SUCCESS,
                    ),
                    SwiggyStepRow(
                        title = "Current Swiggy cart",
                        detail = "The provider did not confirm that this cart uses the same saved address.",
                        badge = "Different",
                        tone = SwiggyStepTone.AMBER,
                    ),
                ),
                safetyNote = "Beta refused to move, clear, or overwrite the cart. Choose the right address and it will search every item again.",
                primary = SwiggyStepAction("Choose the right address") {
                    if (isCurrent(operationId)) restartAddressSelection(operationId, items)
                },
                secondary = SwiggyStepAction("Cancel - change nothing") {
                    cancelAndDismiss(operationId, "Swiggy cart changes cancelled.")
                },
                cancel = { cancelAndDismiss(operationId, "Swiggy cart changes cancelled.") },
            )
        )
        announce(message)
    }

    private fun showVerifiedResult(
        operationId: Long,
        address: SwiggyAddress,
        selected: List<RequestedItem>,
        providerMessage: String?,
        confirmationToken: String,
    ) {
        if (!isCurrent(operationId)) return
        val providerNote = swiggyProviderAddedMessage(providerMessage)
        val caption = buildString {
            append("Your cart was updated and checked in Beta.")
            providerNote?.let { append(" ").append(it) }
            append(" ").append(activity.getString(R.string.swiggy_handoff_warning))
        }
        val completedMutation = mutationInFlight
        running = false
        clearDraft()
        clearInterpretation()
        if (completedMutation) setMutationInFlight(false)
        onVerified(selected.size)
        stepDialog.show(
            SwiggyStepScreen(
                eyebrow = "Checked in Beta · ${selected.size} of ${selected.size} lines match",
                title = "Your cart is checked in Beta",
                message = "Deliver to ${address.shortLabel}. ${activity.getString(R.string.swiggy_step_readback_verified)}",
                caption = caption,
                rows = buildList {
                    selected.forEach {
                        add(
                            SwiggyStepRow(
                                title = it.displayName,
                                detail = "Requested cart quantity ${it.quantity}",
                                badge = "Verified",
                                tone = SwiggyStepTone.SUCCESS,
                            )
                        )
                    }
                    providerNote?.let {
                        add(
                            SwiggyStepRow(
                                title = activity.getString(R.string.swiggy_step_provider_added_note),
                                detail = it,
                                badge = if (it.contains("sample", ignoreCase = true)) "Free sample" else "Free gift",
                                tone = SwiggyStepTone.AMBER,
                            )
                        )
                    }
                },
                safetyNote = activity.getString(R.string.swiggy_handoff_warning),
                primary = if (BuildConfig.BETA_SWIGGY_CHECKOUT_ENABLED) SwiggyStepAction("Review and pay in Beta") {
                    stepDialog.dismiss()
                    onCheckoutRequested(address.id)
                } else SwiggyStepAction("Open Swiggy to review") {
                    // Another client may have changed the cart since the delayed
                    // check. Recheck read-only immediately before this handoff.
                    running = true
                    interruptedDuringRequest = false
                    setMutationInFlight(true)
                    announce("Checking your cart once more before opening Swiggy.")
                    checkReviewedCart(confirmationToken) { result ->
                        onUi(operationId) {
                            if (!hostResumed || interruptedDuringRequest) {
                                running = false
                                setMutationInFlight(false)
                                stepDialog.dismiss()
                                if (hostResumed) restorePendingCartWarning()
                            } else if (result is SwiggyMcpResult.Success && result.value) {
                                if (!CommerceAppLauncher.launchPreferred(activity).launched) {
                                    finish(operationId, "Beta could not open Swiggy. Please open it and review your cart. Beta has not repeated the update.")
                                    return@onUi
                                }
                                running = false
                                setMutationInFlight(false)
                                stepDialog.dismiss()
                                notifyTerminal()
                            } else {
                                finish(operationId, "The cart may have changed since Beta checked it. Please review the current Swiggy cart. Beta will not add anything again.")
                            }
                        }
                    }
                },
                secondary = SwiggyStepAction("Done") { stepDialog.dismiss() },
                cancel = { stepDialog.dismiss() },
            )
        )
        announce(caption)
        notifyTerminal()
    }

    private fun completeAndDismiss(operationId: Long, message: String, retainStatus: Boolean = false) {
        if (!isCurrent(operationId)) return
        val completedMutation = mutationInFlight
        running = false
        clearDraft()
        clearInterpretation()
        stepDialog.dismiss()
        if (completedMutation) setMutationInFlight(false)
        if (retainStatus) notifyTerminal(retainStatus = true)
        announce(message)
        if (!retainStatus) notifyTerminal()
    }

    private fun cancelAndDismiss(operationId: Long, message: String) {
        completeAndDismiss(operationId, message)
    }

    private fun onUi(operationId: Long, block: () -> Unit) {
        activity.runOnUiThread {
            if (!activity.isFinishing && !activity.isDestroyed && isCurrent(operationId)) {
                block()
            } else if (operationGeneration == operationId) {
                val abandonedMutation = mutationInFlight
                mutationInFlight = false
                running = false
                operationGeneration += 1
                clearDraft()
                clearInterpretation()
                stepDialog.dismiss()
                if (abandonedMutation) {
                    SwiggyCartMutationGuard.end(
                        "Swiggy finished the cart request while Beta was reopening. Please review your Swiggy cart before continuing.",
                    )
                }
                notifyTerminal()
            }
        }
    }

    private fun isCurrent(operationId: Long): Boolean {
        return running && operationGeneration == operationId
    }

    private fun hasCurrentIntentConsent(operationId: Long, captured: GroceryIntentSelection.Enabled?): Boolean {
        if (captured == null) return true // Standard matching does not send data to an intent provider.
        if (activeIntentSelection == captured && readIntentSelection() == captured) return true
        cancelAndDismiss(operationId, "Your list is saved. Nothing was changed.")
        return false
    }

    private fun setMutationInFlight(inFlight: Boolean) {
        if (mutationInFlight == inFlight) return
        mutationInFlight = inFlight
        if (inFlight) SwiggyCartMutationGuard.begin() else SwiggyCartMutationGuard.end()
    }

    private fun stopWithVisibleStatus(message: String) {
        notifyTerminal(retainStatus = true)
        announce(message)
    }

    private fun notifyTerminal(retainStatus: Boolean = false) {
        if (!mutationInFlight) {
            onTerminal(retainStatus)
        }
    }

    private fun dismissActiveDialog() {
        stepDialog.dismiss()
    }

    private fun usableCandidates(
        index: Int,
        item: ParsedItem,
        recommendation: Recommendations,
    ): List<RecommendationCandidate> = if (contextPreparation != null)
        contextBasket?.usable(index).orEmpty() else swiggyUsableCandidates(item, recommendation)

    private fun displayItem(item: ParsedItem): String {
        return swiggyDisplayItem(item)
    }

    private fun isAddressMismatch(message: String): Boolean {
        return message.contains("does not match the selected delivery address", ignoreCase = true)
    }

    private fun currentReviewRequest(items: List<ParsedItem>): SwiggyCartReviewRequest? {
        if (contextPreparation == null) return SwiggyCartReviewRequest.build(items, draftCandidates, draftSkipped, auditedBasket)
        val basket = contextBasket ?: return null
        if (basket.preparation !== contextPreparation || basket.items != items) return null
        return SwiggyCartReviewRequest.buildContext(basket, draftCandidates, draftSkipped)
    }

    private companion object {
        const val MAX_ADDRESSES = 50
        const val ADDRESS_CONFIRMATION_TTL_MS = 15 * 60 * 1000L
    }
}
