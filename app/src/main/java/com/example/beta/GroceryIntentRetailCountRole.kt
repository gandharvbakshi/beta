package com.example.beta

import java.math.BigDecimal
import java.util.Locale
import com.example.beta.automation.IntentLocalAmount
import com.example.beta.automation.IntentQuantityOrigin
import com.example.beta.automation.IntentQuantityProvenance
import com.example.beta.automation.IntentCartQuantityMode
import com.example.beta.automation.Quantity
import com.example.beta.automation.ParsedItem
import com.example.beta.automation.ProductLexicon
import com.example.beta.automation.IntentSourceCountRole

/** Original source spans, not a model label or permission to mutate a cart. */
internal data class SourceRetailCountReceipt(
    val source: String,
    val count: IntentLocalAmount,
    val size: IntentLocalAmount?,
    val packaging: String,
    val container: IntRange,
    val identity: IntRange,
    val qualifier: IntRange?,
) {
    override fun toString(): String = "SourceRetailCountReceipt(<redacted>)"
}

/** Recover the existing closed production from the original source. Proposed
 * model values are deliberately not inputs; they are compared afterwards. */
internal fun sourceRetailCountReceipt(source: String): SourceRetailCountReceipt? {
    return compileSourceRetailSyntax(source, requireOf = true)
}

/** A count fact is not automatically a named packaging-style requirement. */
internal fun sourceNamedRetailCountReceipt(source: String): SourceRetailCountReceipt? =
    sourceRetailCountReceipt(source)?.takeUnless { it.isGenericPacketCountSyntax() }

/** V1 product-first retail role. A single complete source, four disjoint original spans.
 * No virtual "of" sentence, inferred count/size, loose wrapper, or SKU authority. */
internal fun productFirstMeasuredRetail(source: String): SourceRetailCountReceipt? {
    val m = productFirstMeasuredSlots(source) ?: return null
    if (intentV2HasCorrectionCue(source)) return null
    val nominal = m.groups["noun"] ?: return null
    val words = nominal.value.lowercase(Locale.ROOT).split(Regex("[ \\t'-]+"))
    if (!groceryIntentRetailNominalValid(nominal.value) || sourceNominalHasRelation(nominal.value) ||
        retailCountContainerPattern.containsMatchIn(nominal.value) || groceryIntentContainsNumericAtom(nominal.value) ||
        words.any { it in groceryIntentNominalScopeUnsafeWords || it in inlineParticleSelectors ||
            it in forbiddenGenericContainerNameWords || it in genitiveUnsafe || groceryIntentUncertainScope.matches(it) }) return null
    val form = requireNotNull(m.groups["form"])
    val packaging = groceryIntentRetailContainerKey(form.value)?.takeIf { it in measuredNamedForms } ?: return null
    val countGroup = listOfNotNull(m.groups["leading"], m.groups["trailing"]).singleOrNull() ?: return null
    val count = sourceQuotedLocalAmount(source, countGroup.value, "count")?.takeIf {
        it.startUtf16 == countGroup.range.first && it.endUtf16 == countGroup.range.last + 1 &&
            it.value.stripTrailingZeros().scale() <= 0 && it.value >= BigDecimal.ONE && it.value <= BigDecimal(20)
    } ?: return null
    val atom = requireNotNull(m.groups["size"])
    val originalUnit = requireNotNull(m.groups["unit"])
    val unit = retailCountPhysicalUnits.singleOrNull { key ->
        GroceryIntentPhysicalUnitMatcher.after(source, atom.range.last + 1, key)?.let {
            it.start == originalUnit.range.first && it.end == originalUnit.range.last + 1
        } == true
    } ?: return null
    val size = sourceQuotedLocalAmount(source, atom.value, unit)?.takeIf {
        it.startUtf16 == atom.range.first && it.endUtf16 == atom.range.last + 1 && it.value.signum() > 0
    } ?: return null
    if (sourceRetailCompositionIssue(source, count, size) != null) return null
    return SourceRetailCountReceipt(source, count, size, packaging, form.range, nominal.range, null)
}

// A recognised size/container with no proven outer role must not fall back to an
// invented default. This detection is denial-only; the full receipt is still required.
internal fun requiresProductFirstRetailProof(source: String): Boolean = productFirstMeasuredSlots(source) != null
private fun productFirstMeasuredSlots(source: String): MatchResult? {
    if (source.length !in 1..320 || !genitiveLink.containsMatchIn(source) ||
        source.any { it.code > 127 || it.isISOControl() && it != '\t' }) return null
    return genitiveMeasuredForm.matchEntire(source)?.takeIf {
        groceryIntentRetailContainerKey(requireNotNull(it.groups["form"]).value) in measuredNamedForms
    }
}
private val genitiveLink = Regex("[ \\t]+(?:ka|ki|ke)[ \\t]+", RegexOption.IGNORE_CASE)
private val genitiveUnsafe = setOf("agar", "mat", "sirf", "chhod", "chhodkar", "today", "tomorrow",
    "yesterday", "before", "after", "later", "refillable", "replacement", "dispenser")
internal val measuredNamedForms = setOf("bottle", "jar", "tube", "carton", "pouch", "box", "tin", "can", "tub", "sachet", "bag")
private val genitiveMeasuredForm = Regex(
    "(?<noun>[A-Za-z]+(?:[-'][A-Za-z]+)*(?:[ \\t]+[A-Za-z]+(?:[-'][A-Za-z]+)*){0,7})[ \\t]+(?:ka|ki|ke)[ \\t]+" +
        "(?:(?<leading>[A-Za-z]+|[0-9]{1,2})[ \\t]+)?(?<size>[0-9]{1,5}(?:\\.[0-9]{1,3})?)[ \\t]*" +
        "(?<unit>[A-Za-z]+)[ \\t]+(?<form>[A-Za-z]+)(?:[ \\t]+(?<trailing>[A-Za-z]+|[0-9]{1,2}))?",
    RegexOption.IGNORE_CASE,
)

/** An unambiguous generic packet request may review an offered SKU's contents.
 * This is NOT a named-retail receipt, divisor, or permission to use Exact.
 * Other word orders/sizes retain their existing paths; no text is normalized. */
internal fun sourcePacketOfferSyntax(source: String, context: GroceryIntentTerminalContext? = null): SourceRetailCountReceipt? {
    val coreEnd = if (context == null) source.length else context.spanFor(source)?.startUtf16 ?: return null
    return compileSourceRetailSyntax(source, requireOf = false, coreEnd = coreEnd)?.takeIf { it.isGenericPacketCountSyntax() }
}

/** Count syntax shared by parser projection and generic offers. Optional `of`
 * does not change a generic purchase unit into a packaging style. Agreement
 * distinguishes one pack / two packs from ambiguous descriptors like six pack. */
internal fun sourcePacketCountSyntax(source: String): SourceRetailCountReceipt? {
    return compileSourceRetailSyntax(source, requireOf = false)?.takeIf { it.isGenericPacketCountSyntax() }
}

internal fun SourceRetailCountReceipt.isGenericPacketCountSyntax(): Boolean {
    if (packaging !in setOf("pack", "packet") || size != null || qualifier != null) return false
    val expectedForm = packaging + if (count.value.compareTo(BigDecimal.ONE) == 0) "" else "s"
    return source.substring(container).equals(expectedForm, ignoreCase = true)
}

/** Revalidate the original amount and complete nominal independently of the
 * producer's unit/pack flag. Existing final catalogue checks also bind the
 * complete provenance and candidate snapshot. No new persistent role is added. */
internal fun ParsedItem.packetOfferProofMatches(syntax: SourceRetailCountReceipt): Boolean {
    val proof = intentQuantityProvenance ?: return false
    val local = proof.local ?: return false
    val requested = quantity as? Quantity.Count ?: return false
    if (!requiresIntentQuantityProof || unprovenIdentityScope || quantitySignal != null ||
        syntax.source != rawText || proof.rawText != rawText || proof.primaryText != rawText ||
        proof.origin !in setOf(IntentQuantityOrigin.LOCAL, IntentQuantityOrigin.SOURCE_NUMERIC) ||
        proof.resolvedQuantity != quantity || requested.n.toBigDecimal().compareTo(syntax.count.value) != 0 ||
        local.unit !in setOf("count", "pack") || local != syntax.count.copy(unit = local.unit) ||
        proof.query != query || proof.strictMatchPhrase != strictMatchPhrase ||
        proof.retailPackCount != retailPackCount || proof.logicalPieceCount || logicalPieceCount ||
        proof.corrected != null || proof.sourceCountRole != null || proof.retailContainer != null ||
        proof.unitSize != null || proof.genericContainerReceipt != null || containerContentsProof != null ||
        ingredientProvenance != null ||
        cartQuantityMode != IntentCartQuantityMode.TARGET || proof.cartQuantityMode != cartQuantityMode) return false
    val nominal = intentEvidenceTokens(rawText.substring(syntax.identity))
    return nominal.isNotEmpty() && intentEvidenceTokens(query).containsAll(nominal) &&
        (strictMatchPhrase.isNullOrBlank() || intentEvidenceTokens(strictMatchPhrase).containsAll(nominal))
}

/** Literal identity only: an omitted connector is NOT evidence of a container
 * count. In particular bottle brush/can opener must remain compound identities. */
internal class SourceRetailIdentityConstraint internal constructor(private val syntax: SourceRetailCountReceipt) {
    fun hasRepresentableSize(): Boolean = syntax.hasRepresentableSize()
    // Reuse the SAME existing identity vocabulary that established grounding.
    // A known translation must not pass grounding then fail on a second lexicon.
    fun literalQuery(): String = intentEvidenceTokens(syntax.literalQuery()).joinToString(" ")
    override fun toString(): String = "SourceRetailIdentityConstraint(<redacted>)"
}

internal fun sourceRetailIdentityConstraint(source: String): SourceRetailIdentityConstraint? {
    if (sourceRetailCountReceipt(source) != null) return null
    // Generic retail units do not specify a container style. Keep their existing
    // count/multipack policy; do not demand the word "packet" on every SKU title.
    return compileSourceRetailSyntax(source, requireOf = false)
        ?.takeUnless { it.packaging in setOf("pack", "packet") }?.let(::SourceRetailIdentityConstraint)
}

private fun compileSourceRetailSyntax(source: String, requireOf: Boolean, coreEnd: Int = source.length): SourceRetailCountReceipt? {
    if (source.isEmpty() || source.length > 6000 || coreEnd !in 1..source.length) return null
    // Position and the count/size gap prove the outer role. Later container
    // words belong to the complete nominal (for example, "bottle brush").
    val container = retailCountContainerPattern.find(source) ?: return null
    val packaging = groceryIntentRetailContainerKey(container.value) ?: return null
    if (packaging in setOf("cup", "spray")) return null
    val start = source.indexOfFirst { it !in " \t" }.takeIf { it >= 0 } ?: return null
    val matches = mutableListOf<SourceRetailCountReceipt>()
    for (end in start + 1..minOf(start + 64, container.range.first)) {
        if (source[end - 1] in " \t") continue
        val count = sourceQuotedLocalAmount(source, source.substring(start, end), "count")?.takeIf {
            it.startUtf16 == start && it.endUtf16 == end
        } ?: continue
        compileRetailSyntax(packaging, source, count, null, requireOf, coreEnd)?.let { matches += it }
        val sizeRegions = mutableListOf(
            (end + source.substring(end).takeWhile { it in " \t" }.length) to container.range.first,
        )
        // The second production keeps all original spans: COUNT CONTAINER of
        // SIZE UNIT NOMINAL. No source rewriting or inferred numeric role.
        val afterContainer = container.range.last + 1
        Regex("^[ \\t]+of[ \\t]+", RegexOption.IGNORE_CASE).find(source.substring(afterContainer))?.let {
            sizeRegions += (afterContainer + it.value.length) to source.length
        }
        for ((sizeStart, sizeLimit) in sizeRegions) {
            for (sizeEnd in sizeStart + 1..minOf(sizeStart + 64, sizeLimit)) {
                if (source[sizeEnd - 1] in " \t") continue
                for (unit in retailCountPhysicalUnits) {
                    val size = sourceQuotedLocalAmount(source, source.substring(sizeStart, sizeEnd), unit)?.takeIf {
                        it.startUtf16 == sizeStart && it.endUtf16 == sizeEnd
                    } ?: continue
                    compileRetailSyntax(packaging, source, count, size, requireOf, coreEnd)?.let { matches += it }
                }
            }
        }
    }
    return matches.distinct().singleOrNull()
}

internal fun SourceRetailCountReceipt.literalIdentity(): String = listOfNotNull(
    ProductLexicon.canonicalizeAliasesPreservingText(source.substring(identity)),
    qualifier?.let { source.substring(it) },
    packaging.replace('_', ' '),
).joinToString(" ")

internal fun SourceRetailCountReceipt.literalQuery(): String = listOfNotNull(
    literalIdentity(),
    size?.let { normalizePack(it.value, it.unit) }?.let { "${it.first} ${it.second}" },
).joinToString(" ")

/** Cart quantities currently use integer g/ml. Keep the source receipt even
 * when it cannot be projected exactly, so callers hold rather than drop size. */
internal fun SourceRetailCountReceipt.hasRepresentableSize(): Boolean =
    size?.let { runCatching { normalizePack(it.value, it.unit) }.isSuccess } ?: true

internal fun GroceryIntentItem.legacyRetailCountReceipt(): SourceRetailCountReceipt? =
    legacySourceRetailCountReceipt()?.takeUnless { it.isGenericPacketCountSyntax() }

/** Keep raw source/model-field agreement independent of the action role. */
internal fun GroceryIntentItem.legacySourceRetailCountReceipt(): SourceRetailCountReceipt? {
    if (needsClarification || excluded.isNotEmpty() || quantityUnit !in setOf("count", "pack", "piece")) return null
    val receipt = sourceRetailCountReceipt(sourceText) ?: if (quantityUnit in setOf("count", "pack") && packValue != null)
        productFirstMeasuredRetail(sourceText) ?: return null else return null
    if (!receipt.hasRepresentableSize()) return null
    if (quantity?.compareTo(receipt.count.value) != 0 ||
        packValue?.let { value -> receipt.size?.let { size ->
            packUnit !in retailCountPhysicalUnits || normalizePack(size.value, size.unit) != normalizePack(value, packUnit)
        } ?: true } == true) return null
    // The model may omit source form/size; it cannot add identity constraints.
    val proposed = intentEvidenceTokens(name + " " + required.joinToString(" ") { it.replace('_', ' ') })
    if (!intentEvidenceTokens(receipt.literalQuery()).containsAll(proposed)) return null
    return receipt
}

internal fun ParsedItem.validNamedRetailReceipt(): SourceRetailCountReceipt? {
    if (quantitySignal != null || unprovenIdentityScope) return null
    val proof = intentQuantityProvenance ?: return null
    val receipt = sourceNamedRetailCountReceipt(rawText) ?: productFirstMeasuredRetail(rawText) ?: return null
    if (!receipt.hasRepresentableSize()) return null
    if (!requiresIntentQuantityProof || proof.sourceCountRole != IntentSourceCountRole.NAMED_RETAIL ||
        proof.origin != IntentQuantityOrigin.LOCAL || proof.corrected != null || proof.genericContainerReceipt != null ||
        proof.rawText != rawText || proof.primaryText != rawText || proof.retailContainer != receipt.packaging ||
        proof.local != receipt.count.copy(unit = "pack") || proof.unitSize != receipt.size ||
        !retailPackCount || logicalPieceCount || !proof.retailPackCount || proof.logicalPieceCount ||
        query != receipt.literalQuery() || strictMatchPhrase != query || proof.query != query || proof.strictMatchPhrase != query ||
        quantity != Quantity.Count(receipt.count.value.intValueExact()) || proof.resolvedQuantity != quantity ||
        avoidPhrases.isNotEmpty() || cartQuantityMode != IntentCartQuantityMode.TARGET ||
        proof.cartQuantityMode != IntentCartQuantityMode.TARGET) return null
    return receipt
}

/** The model-off parser uses the same source contract. This can only restore
 * omitted source words on an untyped, unchanged count; never overwrite another
 * provenance path, a user edit, an exclusion or a prior catalogue assessment. */
internal fun ParsedItem.withSourceRetailCountReceipt(): ParsedItem {
    if (requiresIntentQuantityProof || intentQuantityProvenance != null || intentQuantityCatalogueCheck != null ||
        containerContentsProof != null || ingredientProvenance != null || avoidPhrases.isNotEmpty() ||
        cartQuantityMode != IntentCartQuantityMode.TARGET) return this
    val receipt = sourceNamedRetailCountReceipt(rawText) ?: return this
    if (!receipt.hasRepresentableSize()) return this
    if (quantity != Quantity.Count(receipt.count.value.intValueExact())) return this
    val identity = receipt.literalQuery()
    val sourceTokens = intentEvidenceTokens(identity)
    if (query.isBlank() || !sourceTokens.containsAll(intentEvidenceTokens(query)) ||
        !sourceTokens.containsAll(intentEvidenceTokens(strictMatchPhrase.orEmpty()))) return this
    return copy(
        query = identity, strictMatchPhrase = identity, retailPackCount = true, logicalPieceCount = false,
        requiresIntentQuantityProof = true,
        intentQuantityProvenance = IntentQuantityProvenance(
            origin = IntentQuantityOrigin.LOCAL, primaryText = rawText, rawText = rawText,
            resolvedQuantity = quantity, local = receipt.count.copy(unit = "pack"),
            query = identity, strictMatchPhrase = identity, retailPackCount = true, logicalPieceCount = false,
            opaqueNumericLabels = groceryIntentOpaqueNumericLabels(identity), unitSize = receipt.size,
            retailContainer = receipt.packaging, sourceCountRole = IntentSourceCountRole.NAMED_RETAIL,
        ),
    )
}

/** Kotlin mirror of backend source_proves_retail_count; a proof, never a rewrite. */
internal fun GroceryIntentReviewV2ReadyRow.sourceProvesRetailCount(): Boolean =
    sourceRetailCountEvidence()?.let { it.packaging == item.packaging } ?: false

/** Compile the original outer role before checking the producer's label. */
internal fun GroceryIntentReviewV2ReadyRow.sourceRetailCountEvidence(): SourceRetailCountReceipt? {
    val source = evidence.primary.text
    val packaging = retailCountContainerPattern.find(source)?.value?.let(::groceryIntentRetailContainerKey) ?: return null
    if (packaging in setOf("cup", "spray")) return null
    if (item.quantityUnit !in setOf("count", "pack") || item.quantity == null || item.quantityText.isNullOrBlank() ||
        source.any { it.code > 127 || (it.code < 32 && it != '\t') } || intentV2HasCorrectionCue(source)) return null
    if (parseIntentCorrectionAmount(item.quantityText)?.compareTo(item.quantity) != 0) return null
    // Rebind the same proposed amount under the source proof's fixed count
    // unit. This keeps source evidence independent of the producer's label.
    val count = (if (item.quantityUnit == "count") this else copy(item = item.copy(quantityUnit = "count")))
        .localQuantityEvidence() ?: return null
    val size = if (item.packValue == null && item.packText == null && item.packUnit == "unspecified") null else {
        val packValue = item.packValue ?: return null
        val packText = item.packText ?: return null
        if (item.packUnit !in retailCountPhysicalUnits ||
            parseIntentCorrectionAmount(packText)?.compareTo(packValue) != 0) return null
        localPackSizeEvidence() ?: return null
    }
    return sourceRetailCountReceipt(packaging, source, count, size)
}

/** Recheck the local receipt at the final catalogue/cart boundary, not query words. */
internal fun groceryIntentSourceRetailReceiptValid(proof: IntentQuantityProvenance): Boolean {
    val packaging = proof.retailContainer ?: return false
    val local = proof.local ?: return false
    if (proof.origin != IntentQuantityOrigin.LOCAL || proof.corrected != null ||
        proof.cartQuantityMode != IntentCartQuantityMode.TARGET || !proof.retailPackCount || proof.logicalPieceCount ||
        local.unit != "pack" || local.source != proof.primaryText ||
        runCatching { Quantity.Count(local.value.intValueExact()) }.getOrNull() != proof.resolvedQuantity) return false
    if (sourceRetailCountValid(packaging, proof.primaryText, local, proof.unitSize)) return true
    if (proof.sourceCountRole != IntentSourceCountRole.NAMED_RETAIL || proof.unitSize == null) return false
    val productFirst = productFirstMeasuredRetail(proof.primaryText) ?: return false
    return productFirst.packaging == packaging && productFirst.count.copy(unit = "pack") == local &&
        productFirst.size == proof.unitSize
}

private fun sourceRetailCountValid(packaging: String, source: String, count: IntentLocalAmount,
                                  size: IntentLocalAmount?): Boolean =
    sourceRetailCountReceipt(packaging, source, count, size)?.isGenericPacketCountSyntax() == false

/** Compile an outer retail count and retain its complete literal contents.
 * Form/measure nouns inside contents do not introduce another purchase amount. */
internal fun sourceRetailCountReceipt(packaging: String, source: String, count: IntentLocalAmount,
                                     size: IntentLocalAmount?): SourceRetailCountReceipt? {
    return compileRetailSyntax(packaging, source, count, size, requireOf = true)
}

private fun compileRetailSyntax(packaging: String, source: String, count: IntentLocalAmount,
                                size: IntentLocalAmount?, requireOf: Boolean, coreEnd: Int = source.length): SourceRetailCountReceipt? {
    if (packaging !in retailCountContainerForms || packaging in setOf("cup", "spray") ||
        source.any { it.code > 127 || (it.code < 32 && it != '\t') } || intentV2HasCorrectionCue(source)) return null
    fun bound(amount: IntentLocalAmount): Boolean = amount.source == source && amount.startUtf16 >= 0 &&
        amount.endUtf16 > amount.startUtf16 && amount.endUtf16 <= source.length &&
        source.substring(amount.startUtf16, amount.endUtf16) == amount.atom &&
        parseIntentCorrectionAmount(amount.atom)?.compareTo(amount.value) == 0 && amount.value.signum() > 0
    if (!bound(count) || count.unit !in setOf("count", "pack") || count.value.stripTrailingZeros().scale() > 0 ||
        count.value < BigDecimal.ONE || count.value > BigDecimal(20) ||
        source.substring(0, count.startUtf16).any { it != ' ' && it != '\t' }) return null

    // Select the same first mention as the source compiler; never search for
    // a later word matching the model's packaging when the first gap fails.
    val container = retailCountContainerPattern.find(source) ?: return null
    val expected = retailCountContainerForms.getValue(packaging)
    if (!expected.matches(container.value) || count.endUtf16 >= container.range.first) return null
    val ownedNumericRanges = mutableListOf(count.startUtf16 until count.endUtf16)
    val gap = source.substring(count.endUtf16, container.range.first)
    var contentsStart = container.range.last + 1
    var connector = if (requireOf) "of[ \\t]+" else "(?:of[ \\t]+)?"
    if (size == null) {
        if (!Regex("[ \\t]+(?:(?:small|medium|large)[ \\t]+)?", RegexOption.IGNORE_CASE).matches(gap)) return null
    } else {
        if (!bound(size) || size.unit !in retailCountPhysicalUnits) return null
        val unitGap = Regex("^[ \\t]*").find(source.substring(size.endUtf16))!!.value.length
        val physicalUnit = physicalUnitTokenFollowsAt(source, size.endUtf16 + unitGap, size.unit) ?: return null
        val unitEnd = size.endUtf16 + unitGap + physicalUnit.length
        when {
            count.endUtf16 < size.startUtf16 && unitEnd < container.range.first -> {
                if (!Regex("[ \\t]+").matches(source.substring(count.endUtf16, size.startUtf16)) ||
                    !Regex("[ \\t]+").matches(source.substring(unitEnd, container.range.first))) return null
            }
            container.range.last + 1 < size.startUtf16 -> {
                if (!Regex("[ \\t]+").matches(gap) ||
                    !Regex("[ \\t]+of[ \\t]+", RegexOption.IGNORE_CASE)
                        .matches(source.substring(container.range.last + 1, size.startUtf16))) return null
                contentsStart = unitEnd
                connector = ""
            }
            else -> return null
        }
        ownedNumericRanges += size.startUtf16 until size.endUtf16
    }

    if (contentsStart >= coreEnd) return null
    val contents = Regex("[ \\t]+$connector(.+?)[ \\t]*[.!]?[ \\t]*", RegexOption.IGNORE_CASE)
        .matchEntire(source.substring(contentsStart, coreEnd)) ?: return null
    val nominal = contents.groupValues[1]
    if (!groceryIntentRetailNominalValid(nominal) || sourceNominalHasRelation(nominal)) return null

    val remaining = source.toCharArray()
    ownedNumericRanges.forEach { range -> range.forEach { index -> remaining[index] = ' ' } }
    if (groceryIntentContainsNumericAtom(String(remaining))) return null
    if (size != null && sourceRetailCompositionIssue(source, count, size) != null) return null
    val identity = contents.groups[1]!!.range.let {
        (contentsStart + it.first)..(contentsStart + it.last)
    }
    val qualifier = if (size == null) Regex("small|medium|large", RegexOption.IGNORE_CASE)
        .find(gap)?.range?.let { (count.endUtf16 + it.first)..(count.endUtf16 + it.last) } else null
    return SourceRetailCountReceipt(source, count, size, packaging, container.range, identity, qualifier)
}

internal fun groceryIntentRetailContainerKey(text: String): String? =
    retailCountContainerForms.entries.singleOrNull { it.value.matches(text) }?.key

private val retailCountContainerForms = mapOf(
    "pack" to Regex("packs?", RegexOption.IGNORE_CASE),
    "packet" to Regex("packets?", RegexOption.IGNORE_CASE),
    "bag" to Regex("bags?", RegexOption.IGNORE_CASE),
    "bottle" to Regex("bottles?", RegexOption.IGNORE_CASE),
    "carton" to Regex("cartons?", RegexOption.IGNORE_CASE),
    "cup" to Regex("cups?", RegexOption.IGNORE_CASE),
    "can" to Regex("cans?", RegexOption.IGNORE_CASE),
    "tin" to Regex("tins?", RegexOption.IGNORE_CASE),
    "tub" to Regex("tubs?", RegexOption.IGNORE_CASE),
    "jar" to Regex("jars?", RegexOption.IGNORE_CASE),
    "box" to Regex("box(?:es)?", RegexOption.IGNORE_CASE),
    "bar" to Regex("bars?", RegexOption.IGNORE_CASE),
    "block" to Regex("blocks?", RegexOption.IGNORE_CASE),
    "loaf" to Regex("(?:loaf|loaves)", RegexOption.IGNORE_CASE),
    "bunch" to Regex("bunch(?:es)?", RegexOption.IGNORE_CASE),
    "roll" to Regex("rolls?", RegexOption.IGNORE_CASE),
    "refill" to Regex("refills?", RegexOption.IGNORE_CASE),
    "pouch" to Regex("pouch(?:es)?", RegexOption.IGNORE_CASE),
    "sachet" to Regex("sachets?", RegexOption.IGNORE_CASE),
    "tube" to Regex("tubes?", RegexOption.IGNORE_CASE),
    "spray" to Regex("sprays?", RegexOption.IGNORE_CASE),
    "tetra_pack" to Regex("tetra[ \\t-]+packs?", RegexOption.IGNORE_CASE),
)
internal val retailCountContainerPattern = Regex(
    "\\b(?:tetra[ \\t-]+packs?|packs?|packets?|bags?|bottles?|cartons?|cups?|cans?|tins?|tubs?|jars?|" +
        "box(?:es)?|bars?|blocks?|(?:loaf|loaves)|bunch(?:es)?|rolls?|refills?|pouch(?:es)?|sachets?|tubes?|sprays?)\\b",
    RegexOption.IGNORE_CASE,
)
internal val retailCountNominal = Regex("[A-Za-z]+(?:[-'][A-Za-z]+)*(?:[ \\t]+[A-Za-z]+(?:[-'][A-Za-z]+)*)*")
private val retailCountPhysicalUnits = setOf("g", "kg", "ml", "l")
private val retailCountNominalScopeUnsafeWords = setOf(
    "plus", "also", "along", "alongside", "including", "extra", "bonus",
    // Unspecified grouping or a second container not covered by the outer
    // container recognizer is not an ordinary contents/form noun.
    "pair", "pairs", "each", "couple", "couples", "few",
    "case", "cases", "crate", "crates", "tray", "trays", "bowl", "bowls", "bundle", "bundles",
)

/** A source nominal, not a purchase clause or an ownership decision. */
internal fun groceryIntentRetailNominalValid(nominal: String): Boolean {
    if (!retailCountNominal.matches(nominal)) return false
    val words = Regex("[a-z]+", RegexOption.IGNORE_CASE).findAll(nominal.lowercase(Locale.ROOT)).map { it.value }.toSet()
    return words.intersect(groceryIntentNominalClauseUnsafeWords + retailCountNominalScopeUnsafeWords).isEmpty() &&
        !groceryIntentHasNominalClausePredicate(nominal)
}
