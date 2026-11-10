package com.example.beta

import com.example.beta.automation.IntentLocalAmount
import com.example.beta.automation.IntentCartQuantityMode
import com.example.beta.automation.IntentQuantityOrigin
import com.example.beta.automation.IntentQuantityProvenance
import com.example.beta.automation.IntentSourceCountRole
import com.example.beta.automation.ParsedItem
import com.example.beta.automation.ProductLexicon
import com.example.beta.automation.Quantity
import java.math.BigDecimal

/** The source's requested retail units and each offered unit's contents are
 * separate quantities. This receipt does not prove a SKU or permit cart writes. */
data class CompositeRetailReceipt(
    val source: String,
    val requestedCount: IntentLocalAmount,
    val skuFactor: IntentLocalAmount,
    val innerMeasure: IntentLocalAmount,
    val shapeSpan: IntRange,
    val container: IntRange,
    val containerKey: String,
    val identity: IntRange,
) {
    override fun toString(): String = "CompositeRetailReceipt(<redacted>)"
}

/** Shape equality is structural: 2 x 500 ml is not 1 x 1000 ml or 4 x 250 ml. */
internal data class CompositeRetailShape(val factor: Int, val inner: GroceryPackMeasure) {
    init {
        require(factor in 2..99 && inner.dimension in setOf(GroceryPackDimension.WEIGHT, GroceryPackDimension.VOLUME) &&
            inner.baseValue.signum() > 0)
    }
    val display: String get() = "$factor x ${inner.baseValue.stripTrailingZeros().toPlainString()} " +
        if (inner.dimension == GroceryPackDimension.WEIGHT) "g" else "ml"
}

internal fun CompositeRetailReceipt.shape(): CompositeRetailShape = CompositeRetailShape(
    skuFactor.value.intValueExact(), GroceryPackMeasure.of(innerMeasure.value, innerMeasure.unit),
)

internal fun CompositeRetailReceipt.literalQuery(): String =
    ProductLexicon.canonicalizeAliasesPreservingText(source.substring(identity)) + " " + shape().display

/** Recompile from the complete original source so stripping a proof or its
 * marker cannot reopen a legacy exact-count path. */
internal fun ParsedItem.requiresCompositeRetailOffer(): Boolean =
    intentQuantityProvenance?.sourceCountRole == IntentSourceCountRole.COMPOSITE_RETAIL_OFFER ||
        intentQuantityProvenance?.compositeRetailReceipt != null || sourceCompositeRetailReceipt(rawText) != null

internal fun ParsedItem.validCompositeRetailReceipt(): CompositeRetailReceipt? {
    val proof = intentQuantityProvenance ?: return null
    val receipt = sourceCompositeRetailReceipt(rawText) ?: return null
    val identity = receipt.literalQuery()
    if (!requiresIntentQuantityProof || quantitySignal != null || unprovenIdentityScope ||
        proof.compositeRetailReceipt != receipt || proof.sourceCountRole != IntentSourceCountRole.COMPOSITE_RETAIL_OFFER ||
        proof.origin != IntentQuantityOrigin.LOCAL || proof.rawText != rawText || proof.primaryText != rawText ||
        proof.local != receipt.requestedCount || proof.corrected != null || proof.numericSourceQuery != null ||
        proof.retailContainer != null || proof.unitSize != null || proof.genericContainerReceipt != null ||
        proof.ingredientProvenance != null || proof.produceCategory != null || proof.opaqueNumericLabels.isNotEmpty() ||
        ingredientProvenance != null || containerContentsProof != null || avoidPhrases.isNotEmpty() ||
        quantity != Quantity.Count(receipt.requestedCount.value.intValueExact()) || proof.resolvedQuantity != quantity ||
        query != identity || strictMatchPhrase != identity || proof.query != identity || proof.strictMatchPhrase != identity ||
        !retailPackCount || !proof.retailPackCount || logicalPieceCount || proof.logicalPieceCount ||
        cartQuantityMode != IntentCartQuantityMode.TARGET || proof.cartQuantityMode != cartQuantityMode) return null
    return receipt.takeIf { compositeRetailNumericIssue(it, identity, emptyList(), emptyList(), "count") == null }
}

/** Parser projection may omit owned size text, never add identity or change a
 * count. Callers separately prove the original clause belongs to the command. */
internal fun ParsedItem.withCompositeRetailReceipt(receipt: CompositeRetailReceipt): ParsedItem {
    if (rawText != receipt.source || sourceCompositeRetailReceipt(rawText) != receipt ||
        quantity != Quantity.Count(receipt.requestedCount.value.intValueExact()) || quantitySignal != null ||
        unprovenIdentityScope || requiresIntentQuantityProof || intentQuantityProvenance != null ||
        intentQuantityCatalogueCheck != null || ingredientProvenance != null || containerContentsProof != null ||
        avoidPhrases.isNotEmpty() || logicalPieceCount || cartQuantityMode != IntentCartQuantityMode.TARGET) return this
    val identity = receipt.literalQuery()
    if (compositeRetailNumericIssue(receipt, identity, emptyList(), emptyList(), "count") != null) return this
    val sourceTokens = intentEvidenceTokens(identity)
    if (query.isBlank() || !sourceTokens.containsAll(intentEvidenceTokens(query)) ||
        !sourceTokens.containsAll(intentEvidenceTokens(strictMatchPhrase.orEmpty()))) return this
    return copy(query = identity, strictMatchPhrase = identity, retailPackCount = true,
        requiresIntentQuantityProof = true, intentQuantityProvenance = IntentQuantityProvenance(
            origin = IntentQuantityOrigin.LOCAL, primaryText = rawText, rawText = rawText,
            resolvedQuantity = quantity, local = receipt.requestedCount, query = identity,
            strictMatchPhrase = identity, retailPackCount = true, logicalPieceCount = false,
            sourceCountRole = IntentSourceCountRole.COMPOSITE_RETAIL_OFFER, compositeRetailReceipt = receipt,
        ))
}

/** Producer fields only corroborate this source receipt. They never supply its
 * factor, original amount spans or rendered identity. Missing model pack data
 * may be supplied by its own explicit name/tag measure, not by the source. */
internal fun GroceryIntentItem.legacyCompositeRetailReceipt(): CompositeRetailReceipt? {
    val receipt = sourceCompositeRetailReceipt(sourceText) ?: return null
    if (needsClarification || excluded.isNotEmpty() || quantityUnit !in setOf("count", "pack") ||
        quantity?.compareTo(receipt.requestedCount.value) != 0) return null
    val expected = receipt.shape()
    val phrases = listOf(name) + required.map { it.replace('_', ' ') }
    val measures = mutableSetOf<GroceryPackMeasure>()
    val identities = phrases.map { phrase ->
        val shapes = compositeRetailShapeMatches(phrase)
        if (shapes.any { it.shape != expected }) return null
        measures += shapes.map { it.shape.inner }
        val residual = phrase.toCharArray()
        shapes.forEach { match -> match.range.forEach { residual[it] = ' ' } }
        groceryPackMeasurePattern.findAll(String(residual)).forEach { match ->
            measures += GroceryPackMeasure.of(match.groupValues[1].toBigDecimal(), match.groupValues[2])
            match.range.forEach { residual[it] = ' ' }
        }
        val identity = String(residual)
        if (groceryIntentContainsNumericAtom(identity)) return null
        intentEvidenceTokens(identity)
    }
    if (measures.any { it != expected.inner }) return null
    val modelPack = if (packValue != null) {
        if (packUnit !in setOf("g", "kg", "ml", "l")) return null
        GroceryPackMeasure.of(packValue, packUnit)
    } else {
        if (packUnit != "unspecified") return null
        measures.singleOrNull() ?: return null
    }
    if (modelPack != expected.inner) return null
    val nominal = intentEvidenceTokens(sourceText.substring(receipt.identity))
    val packaging = intentEvidenceTokens(sourceText.substring(receipt.container))
    if (identities.any { !(nominal + packaging).containsAll(it) } ||
        identities.first().intersect(nominal).isEmpty()) return null
    return receipt
}

/** Denial-only scanner: intentionally wider than the accepting SKU grammar,
 * including out-of-range/spoken factors and punctuation-delimited shapes. */
private val compositeInstructionMultiplication = Regex(
    "(?<![\\p{L}\\p{N}_])(?:$groceryIntentNumericAmountPattern)" +
        "(?:[ \\t]*(?:kg|kgs|g|gm|gms|grams?|ml|l|ltr|lit(?:er|re)s?))?[ \\t]*[x×][ \\t]*" +
        "(?:$groceryIntentNumericAmountPattern)(?:[ \\t]*(?:kg|kgs|g|gm|gms|grams?|ml|l|ltr|lit(?:er|re)s?))?",
    RegexOption.IGNORE_CASE,
)

/** A quote cannot crop or split a multiplier away from the requested item.
 * Contained two-amount rows retain their existing validation; complete
 * three-amount receipts additionally require model agreement and list scope. */
internal fun compositeV1Receipts(instruction: String, items: List<GroceryIntentItem>): Map<Int, CompositeRetailReceipt> {
    val receipts = items.mapIndexedNotNull { index, item ->
        if (sourceCompositeRetailReceipt(item.sourceText) == null) null else
            index to (item.legacyCompositeRetailReceipt() ?: throw IntentNeedsReviewException(listOf(item.sourceText)))
    }.toMap()
    val multiplications = compositeInstructionMultiplication.findAll(instruction).map { it.range }.toList()
    if (receipts.isEmpty() && multiplications.isEmpty()) return emptyMap()
    val sources = items.map { it.sourceText }
    val quotes = sources.map { bindGroceryIntentProductSource(instruction, it, maxLength = 6000)
        ?: throw IntentNeedsReviewException(listOf(it)) }
    if (multiplications.any { range -> quotes.count { quote ->
            quote.startUtf16 <= range.first && quote.endUtf16 > range.last &&
                groceryIntentSourceClauseEndProven(instruction, quote.text, quote.text.length)
        } != 1 }) throw IntentNeedsReviewException(listOf(instruction))
    // Ordinary rows retain their existing grounding/scope gates. Still prove
    // the end above so a cropped quote cannot discard a trailing pack count.
    if (receipts.isEmpty()) return emptyMap()
    if (!groceryIntentParserClauseScopeProven(instruction, sources)) throw IntentNeedsReviewException(listOf(instruction))
    receipts.forEach { (_, receipt) ->
        if (!groceryIntentSourceClauseContextProven(instruction, receipt.source, receipt.source.length) ||
            // Parser is a denial witness for compound product nominals. It does
            // not supply any model field, and no provider/network is involved.
            com.example.beta.automation.InstructionParser.parse(receipt.source)
                .singleOrNull()?.validCompositeRetailReceipt() == null)
            throw IntentNeedsReviewException(listOf(receipt.source))
    }
    return receipts
}

private val compositeRetailShapeSyntax: List<String> = run {
    val factor = "(?<factor>[2-9]|[1-9][0-9])"
    val measure = "(?<measure>[0-9]+(?:\\.[0-9]+)?)[ \\t]*(?<unit>kg|kgs|g|gm|gms|grams?|ml|l|ltr|lit(?:er|re)s?)"
    // The explicit operator bounds the reversed measure even when glued:
    // 500mlx2 and 2x500ml are the same shape, not alternate amount rules.
    listOf("$factor[ \\t]*[x×][ \\t]*$measure\\b", "$measure[ \\t]*[x×][ \\t]*$factor")
}

private val compositeRetailFieldPatterns = compositeRetailShapeSyntax.map {
    Regex("(?<![\\p{L}\\p{N}_.,/+\\-])(?:$it)(?![\\p{L}\\p{N}_.,/+\\-])", RegexOption.IGNORE_CASE)
}

internal data class CompositeRetailShapeMatch(val range: IntRange, val shape: CompositeRetailShape)

/** Lexical shape facts only. Callers separately establish which field owns them. */
internal fun compositeRetailShapeMatches(text: String): List<CompositeRetailShapeMatch> =
    compositeRetailFieldPatterns.flatMap { pattern -> pattern.findAll(text).mapNotNull { match ->
        runCatching {
            CompositeRetailShapeMatch(match.range, CompositeRetailShape(
                match.groups["factor"]!!.value.toInt(),
                GroceryPackMeasure.of(match.groups["measure"]!!.value.toBigDecimal(), match.groups["unit"]!!.value),
            ))
        }.getOrNull()
    }.toList() }

internal fun exactCompositeRetailShape(text: String): CompositeRetailShape? {
    val field = text.trim()
    return compositeRetailShapeMatches(field).singleOrNull()?.takeIf { it.range == field.indices }?.shape
}

private val compositeRetailPatterns: List<Regex> = run {
    val gap = "[ \\t]+"
    val count = "(?<count>${groceryIntentNumericAmountPattern})"
    val container = "(?<container>${retailCountContainerPattern.pattern})"
    val nominal = "(?<identity>${retailCountNominal.pattern})"
    compositeRetailShapeSyntax.flatMap { inner ->
        val shape = "(?<shape>$inner)"
        listOf(
            "$shape$gap$nominal$gap$container$gap$count",
            "$shape$gap$nominal$gap$count$gap$container",
            "$nominal$gap$shape$gap$container$gap$count",
            "$nominal$gap$shape$gap$count$gap$container",
            "$count$gap$container$gap(?:(?<containment>of)$gap)?$shape$gap$nominal",
            "$count$gap$container$gap(?:(?<containment>of)$gap)?$nominal$gap$shape",
        ).map { Regex("[ \\t]*$it[ \\t]*[.!]?[ \\t]*", RegexOption.IGNORE_CASE) }
    }
}

/** An anchored single-clause production owns all three original amounts.
 * Missing counts, nested multipliers, extra amounts and clause operators cannot
 * be swallowed by a nominal. No model-proposed values are inputs to this compiler. */
internal fun sourceCompositeRetailReceipt(source: String): CompositeRetailReceipt? {
    if (source.isEmpty() || source.length > 512 ||
        source.any { it.code > 127 && it != '×' || it.isISOControl() && it != '\t' } ||
        intentV2HasCorrectionCue(source) || groceryIntentUncertainScope.containsMatchIn(source)) return null
    // A second container might own a different purchase count or contents role.
    // Do not pick the most convenient occurrence after matching a production.
    if (retailCountContainerPattern.findAll(source).take(2).count() != 1) return null
    return compositeRetailPatterns.mapNotNull { pattern ->
        val match = pattern.matchEntire(source) ?: return@mapNotNull null
        val nominal = match.groups["identity"] ?: return@mapNotNull null
        if (!groceryIntentRetailNominalValid(nominal.value) || groceryIntentContainsNumericAtom(nominal.value))
            return@mapNotNull null
        val container = match.groups["container"] ?: return@mapNotNull null
        val key = groceryIntentRetailContainerKey(container.value)?.takeUnless { it in setOf("cup", "spray") }
            ?: return@mapNotNull null
        fun amount(group: String, unit: String): IntentLocalAmount? {
            val atom = match.groups[group] ?: return null
            val value = parseIntentCorrectionAmount(atom.value) ?: return null
            return IntentLocalAmount(source, atom.range.first, atom.range.last + 1, atom.value, value, unit)
        }
        val count = amount("count", "count") ?: return@mapNotNull null
        // Postfix Hinglish `do` can be the imperative "give", not a count.
        // Preserve numeral use before the container, never guess at the end.
        if (count.atom.equals("do", ignoreCase = true) && count.startUtf16 > container.range.last)
            return@mapNotNull null
        val countValue = runCatching { count.value.intValueExact() }.getOrNull()?.takeIf { it in 1..20 }
            ?: return@mapNotNull null
        val singular = key.replace('_', ' ')
        // Compare the already-bound label, without rewriting source or spans.
        val isSingular = container.value.replace(Regex("[ \\t-]+"), " ").equals(singular, ignoreCase = true)
        if (isSingular != (countValue == 1)) return@mapNotNull null
        val factor = amount("factor", "factor") ?: return@mapNotNull null
        // Repetition can restate the same units, not another multiplication.
        // Only an explicit outer containment relation separates equal counts.
        val containment = runCatching { match.groups["containment"] }.getOrNull()
        if (count.value.compareTo(factor.value) == 0 && containment == null) return@mapNotNull null
        val rawUnit = match.groups["unit"]!!.value.lowercase(java.util.Locale.ROOT)
        val unit = when (rawUnit) {
            "kg", "kgs" -> "kg"
            "g", "gm", "gms", "gram", "grams" -> "g"
            "ml" -> "ml"
            else -> "l"
        }
        val measure = amount("measure", unit)?.takeIf { it.value > BigDecimal.ZERO } ?: return@mapNotNull null
        CompositeRetailReceipt(source, count, factor, measure, match.groups["shape"]!!.range,
            container.range, key, nominal.range)
    }.distinct().singleOrNull()
}
