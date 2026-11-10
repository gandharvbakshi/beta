package com.example.beta

import com.example.beta.automation.ParsedItem
import com.example.beta.automation.ProductLexicon
import com.example.beta.automation.IntentQuantityOrigin
import com.example.beta.automation.IntentLocalAmount
import com.example.beta.automation.IntentQuantityProvenance
import com.example.beta.automation.IntentCartQuantityMode
import java.text.Normalizer
import java.math.BigDecimal
import java.util.Locale

// Only equivalent strengths may represent an original ingredient exclusion.
// A preparation label such as "unsalted" is not a salt-absence obligation.
private val equivalentExclusionProperties = mapOf("added sugar" to "noaddedsugar", "sugar" to "sugarfree")

/** V2-only mapping. Blocked rows have no item fields and cannot reach this conversion. */
internal fun GroceryIntentReviewV2.prepareForReview(
    allowPropertyObligations: Boolean = false,
    allowIngredientPredicates: Boolean = false,
    allowTypedIngredientPredicates: Boolean = false,
): GroceryIntentPreparation {
    fun held() = GroceryIntentPreparation(items = emptyList(), unresolvedText = listOf(instruction),
        excludedText = emptyList(), recoverySnapshot = GroceryIntentRecoverySnapshot.generic(instruction))
    if (allowTypedIngredientPredicates && (!allowPropertyObligations || !allowIngredientPredicates)) return held()
    if (!allowTypedIngredientPredicates && !ingredientObligations.isNullOrEmpty()) return held()
    val productOperations = if (operations.isEmpty()) emptyList() else {
        val initialSliceIsOrdinary = blockedRows.isEmpty() && groups.isEmpty() && constraints.isEmpty() &&
            readyRows.size == sourceItemCount &&
            readyRows.map { it.rowIndex } == (0 until sourceItemCount).toList() &&
            readyRows.all { row -> row.item.sourceText == row.evidence.primary.text &&
                bindGroceryIntentProductSource(instruction, row.item.sourceText) == row.evidence.primary } &&
            readyRows.all { it.corrections.isEmpty() && it.evidence.supporting.isEmpty() &&
                it.item.supportingText.isEmpty() && !it.item.needsClarification } &&
            sameCountRefinementReceipts().isEmpty() && restatementReceipts().isEmpty()
        val compiled = if (initialSliceIsOrdinary) {
            compileGroceryIntentProductReplacements(instruction, readyRows.map { it.item })
        } else emptyList()
        if (compiled.size != operations.size || compiled.indices.any { index ->
                !compiled[index].payloadEquals(operations[index])
            }) {
            return GroceryIntentPreparation(items = emptyList(), unresolvedText = listOf(instruction), excludedText = emptyList(),
                recoverySnapshot = GroceryIntentRecoverySnapshot.generic(instruction))
        }
        compiled
    }
    // Backend normalization is the only path allowed to change count to pack.
    // If the original source independently proves an outer retail container
    // but this DTO still says count, deny the row rather than silently
    // preparing it as a loose-count product or guessing a replacement unit.
    val retailCountNormalizationEligible = groups.isEmpty() && readyRows.all { row -> row.corrections.isEmpty() }
    if (retailCountNormalizationEligible && readyRows.any { row ->
            row.evidence.primary.startUtf16 >= 0 &&
                row.evidence.primary.endUtf16 == row.evidence.primary.startUtf16 + row.evidence.primary.text.length &&
                row.evidence.primary.endUtf16 <= instruction.length &&
                instruction.substring(row.evidence.primary.startUtf16, row.evidence.primary.endUtf16) == row.evidence.primary.text &&
                instruction.indexOf(row.evidence.primary.text) == row.evidence.primary.startUtf16 &&
                instruction.lastIndexOf(row.evidence.primary.text) == row.evidence.primary.startUtf16 &&
                row.item.sourceText == row.evidence.primary.text && row.item.quantityUnit == "count" &&
                row.evidence.supporting.isEmpty() && row.item.supportingText.isEmpty() && !row.item.needsClarification &&
                row.sourceProvesRetailCount()
        }) {
        return GroceryIntentPreparation(items = emptyList(), unresolvedText = listOf(instruction), excludedText = emptyList(),
            recoverySnapshot = GroceryIntentRecoverySnapshot.generic(instruction))
    }
    // Server readiness cannot turn described contents into separate purchases.
    // Blocked source rows still participate; never inspect only ready survivors.
    val localConnectors = localPropertyConnectors()
    fun finish(result: GroceryIntentPreparation): GroceryIntentPreparation =
        if (localConnectors.isNotEmpty() && result.items.size != sourceItemCount) held() else result
    if (hasGroceryIntentCrossItemContentsScope(instruction,
            readyRows.map { it.evidence.primary } + blockedRows.mapNotNull { it.source },
            maxOf(sourceItemCount, readyRows.size + blockedRows.size),
            productOperations.flatMap { it.coverageSources }, localConnectors)) {
        return GroceryIntentPreparation(items = emptyList(), unresolvedText = listOf(instruction), excludedText = emptyList(),
            recoverySnapshot = GroceryIntentRecoverySnapshot.generic(instruction))
    }
    if (!allowPropertyObligations) {
        if (terminalExcludedPropertyScopeUnproven()) return GroceryIntentPreparation(
            items = emptyList(), unresolvedText = listOf(instruction), excludedText = emptyList(),
            recoverySnapshot = GroceryIntentRecoverySnapshot.generic(instruction),
        )
        return finish(prepareWithPropertyObligations(emptyList(), productOperations))
    }
    val scoped = provenPropertyPreparation(allowIngredientPredicates, allowTypedIngredientPredicates) ?: return held()
    val expectedIngredientObligations = scoped.properties.filter { it.ingredientPredicates.isNotEmpty() }.map {
        GroceryIntentReviewV2IngredientObligation(it.source, it.memberIndices, it.ingredientPredicates)
    }
    if (allowTypedIngredientPredicates && ingredientObligations != expectedIngredientObligations) return held()
    if (productOperations.isNotEmpty() && scoped.properties.isNotEmpty()) return GroceryIntentPreparation(
        items = emptyList(), unresolvedText = listOf(instruction), excludedText = emptyList(),
        recoverySnapshot = GroceryIntentRecoverySnapshot.generic(instruction),
    )
    // Only exact supports removed by independently derived typed property
    // receipts can discharge the short-form guard. Unknown supports stay held.
    if (scoped.review.terminalExcludedPropertyScopeUnproven()) return GroceryIntentPreparation(
        items = emptyList(), unresolvedText = listOf(instruction), excludedText = emptyList(),
        recoverySnapshot = GroceryIntentRecoverySnapshot.generic(instruction),
    )
    return finish(scoped.review.prepareWithPropertyObligations(
        scoped.properties, productOperations, allowTypedIngredientPredicates,
    ))
}

/** A cropped fragment-final exclusion cannot prove its original product target. */
private fun GroceryIntentReviewV2.terminalExcludedPropertyScopeUnproven(): Boolean {
    val fragments = readyRows.flatMap { row ->
        listOf(row.item.sourceText, row.evidence.primary.text) + row.evidence.supporting.map { it.quote.text } +
            row.corrections.map { it.source.text }
    } + blockedRows.flatMap { listOfNotNull(it.source?.text) + it.excludedContext.map { quote -> quote.text } } +
        groups.flatMap { listOfNotNull(it.source.text, it.header?.text) }
    if (fragments.none { groceryIntentTerminalExcludedPropertyMatch(it) != null &&
            namedPropertyContrast.matchEntire(it) == null }) return false
    if (sourceItemCount != 1 || readyRows.size != 1 || blockedRows.isNotEmpty() || groups.isNotEmpty()) return true
    val row = readyRows.single()
    if (row.evidence.supporting.isNotEmpty() || row.item.supportingText.isNotEmpty() || row.corrections.isNotEmpty()) return true
    val primary = row.evidence.primary
    if (primary.startUtf16 < 0 || primary.endUtf16 <= primary.startUtf16 || primary.endUtf16 > instruction.length ||
        instruction.substring(primary.startUtf16, primary.endUtf16) != primary.text || row.item.sourceText != primary.text ||
        instruction.indexOf(primary.text) != primary.startUtf16 || instruction.lastIndexOf(primary.text) != primary.startUtf16) return true
    return !groceryIntentTerminalPropertyScopeProven(instruction, primary)
}

/** Shared original-context guard, not a lexical negation exemption. Every
 * producer must prove this before using a terminal property as local evidence. */
internal fun groceryIntentTerminalPropertyScopeProven(instruction: String, primary: GroceryIntentReviewV2Quote): Boolean {
    if (primary.startUtf16 < 0 || primary.endUtf16 <= primary.startUtf16 || primary.endUtf16 > instruction.length ||
        instruction.substring(primary.startUtf16, primary.endUtf16) != primary.text ||
        instruction.indexOf(primary.text) != primary.startUtf16 || instruction.lastIndexOf(primary.text) != primary.startUtf16) return false
    val match = groceryIntentTerminalExcludedPropertyMatch(primary.text) ?: return false
    if (primary.text.any { it.code > 127 || it == '\r' || it == '\n' }) return false
    val positive = primary.text.substring(0, match.range.first)
    val nominal = Regex("^(?:please[ \\t]+)?(?:get|add|order|buy|send|bring)[ \\t]+", RegexOption.IGNORE_CASE).replaceFirst(positive, "")
    if (groceryIntentHasNominalClausePredicate(nominal) ||
        Regex("\\b(?:label(?:l?ed)?|notes?|reads?|stated|mentioned|called|reported|wrote|written)\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(nominal)) return false
    if (!Regex("[A-Za-z0-9 \\t'.-]+").matches(positive) || !Regex("[A-Za-z]").containsMatchIn(positive) ||
        Regex("(?<![0-9])\\.|\\.(?![0-9])|(?<![A-Za-z])-|-(?![A-Za-z])|(?<![A-Za-z])'|'(?![A-Za-z])").containsMatchIn(positive) ||
        intentV2HasCorrectionCue(positive, allowPropertyContrast = false) ||
        Regex("\\b(?:must|should|be|is|are|was|were|has|have|needs?|wants?)\\b", RegexOption.IGNORE_CASE).containsMatchIn(positive) ||
        Regex("\\b(?:and|aur|plus|or|ya|also|alongside|besides|additionally|furthermore|moreover|then|saath|sath|" +
            "with|contains?|containing|includes?|including|comprises?|comprising|" +
            "consists?|consisting|composed)\\b|\\bmade[ \\t]+up[ \\t]+of\\b", RegexOption.IGNORE_CASE).containsMatchIn(positive) ||
        Regex("\\b(?:as[ \\t]+well[ \\t]+as|in[ \\t]+addition[ \\t]+to|followed[ \\t]+by)\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(positive)) return false
    val prefix = instruction.substring(0, primary.startUtf16).toCharArray()
    val suffix = instruction.substring(primary.endUtf16).toCharArray()
    neutralGroceryIntentContextSpans(instruction, listOf(primary.coverageSpan())).forEach { span ->
        if (span.endUtf16 <= primary.startUtf16) for (index in span.startUtf16 until span.endUtf16) prefix[index] = ' '
        else if (span.startUtf16 >= primary.endUtf16)
            for (index in span.startUtf16 until span.endUtf16) suffix[index - primary.endUtf16] = ' '
    }
    return listOf(prefix, suffix).all { Regex("[ \\t\\r\\n,.!?;:]*").matches(String(it)) }
}

/** Independently derived source role, never supplied by model or server JSON. */
internal data class GroceryIntentLocalPropertyConnector(
    val rowIndex: Int, val marker: GroceryIntentReviewV2Quote, val propertySource: GroceryIntentReviewV2Quote,
) {
    override fun toString(): String = "GroceryIntentLocalPropertyConnector(rowIndex=$rowIndex, source=<redacted>)"
}

private val groceryIntentContentsMarker = Regex("\\b(?:with|contains?|containing|comprises?|comprising|includes?|including|" +
    "consists?[ \\t\\r\\n]+of|consisting[ \\t\\r\\n]+of|composed[ \\t\\r\\n]+of|" +
    "made[ \\t\\r\\n]+up[ \\t\\r\\n]+of|filled[ \\t\\r\\n]+with)\\b", RegexOption.IGNORE_CASE)

/** Complete head kind + owned property + separate purchase amounts, not count-based atomicity. */
private fun GroceryIntentReviewV2.localPropertyConnectors(): List<GroceryIntentLocalPropertyConnector> {
    val markers = groceryIntentContentsMarker.findAll(instruction).toList()
    if (markers.size != 1 || !markers.single().value.equals("with", ignoreCase = true) || sourceItemCount < 2 ||
        readyRows.size != sourceItemCount || blockedRows.isNotEmpty() || groups.isNotEmpty() ||
        constraints.isNotEmpty() || operations.isNotEmpty() || !ingredientObligations.isNullOrEmpty()) return emptyList()
    if (readyRows.any { row ->
            row.item.needsClarification || row.corrections.isNotEmpty() || row.evidence.supporting.isNotEmpty() ||
                row.item.supportingText.isNotEmpty() || row.item.quantity == null || row.item.quantityUnit !in setOf("count", "pack") ||
                row.numericOwnershipIssue() != null || row.quantityOmissionIssue() != null ||
                row.groundingIssue(instruction = instruction) != null || row.localQuantityEvidence() == null
        }) return emptyList()
    val sources = readyRows.map { it.evidence.primary }.sortedBy { it.startUtf16 }
    if (sources.any { it.startUtf16 < 0 || it.endUtf16 <= it.startUtf16 || it.endUtf16 > instruction.length ||
            instruction.substring(it.startUtf16, it.endUtf16) != it.text } ||
        sources.zipWithNext().any { (left, right) -> left.endUtf16 > right.startUtf16 }) return emptyList()
    if (instruction.substring(0, sources.first().startUtf16).isNotBlank() ||
        !Regex("[ \\t.!?]*").matches(instruction.substring(sources.last().endUtf16)) ||
        sources.zipWithNext().any { (left, right) -> !Regex("[ \\t]*(?:[,;]|\\r?\\n)[ \\t]*")
            .matches(instruction.substring(left.endUtf16, right.startUtf16)) }) return emptyList()
    val marker = markers.single()
    val owner = readyRows.singleOrNull { it.evidence.primary.startUtf16 < marker.range.first &&
        marker.range.first < it.evidence.primary.endUtf16 } ?: return emptyList()
    val primary = owner.evidence.primary
    if (owner.item.quantityUnit != "count" || owner.item.packValue != null) return emptyList()
    val prefix = Regex(Regex.escape(owner.item.quantityText ?: "") + "[ \\t]+([A-Za-z]+)[ \\t]+", RegexOption.IGNORE_CASE)
        .matchEntire(instruction.substring(primary.startUtf16, marker.range.first)) ?: return emptyList()
    if (!isBareGroceryIntentCommodityHead(prefix.groupValues[1])) return emptyList()
    val complement = Regex("[ \\t]+([^\\r\\n]+?)[ \\t]*")
        .matchEntire(instruction.substring(marker.range.last + 1, primary.endUtf16)) ?: return emptyList()
    val claim = complement.groupValues[1]
    val required = intentEvidenceTokens(owner.item.required.joinToString(" ").replace('_', ' '))
    val tokens = intentEvidenceTokens(claim)
    var retained = protectedPropertyAssertion.matches(claim) && tokens.isNotEmpty() && required.containsAll(tokens)
    negativeAttribute.matchEntire(claim)?.let { negative ->
        val ingredient = intentV2IngredientPhrase(negative.groupValues[1])
        val equivalent = equivalentExclusionProperties[ingredient]
        retained = retained || ingredient in owner.item.excluded.map(::intentV2IngredientPhrase) || equivalent in required
    }
    if (!retained || instruction.indexOf(claim) != instruction.lastIndexOf(claim)) return emptyList()
    val claimStart = marker.range.last + 1 + complement.groups[1]!!.range.first
    return listOf(GroceryIntentLocalPropertyConnector(owner.rowIndex,
        GroceryIntentReviewV2Quote(marker.value, marker.range.first, marker.range.last + 1),
        GroceryIntentReviewV2Quote(claim, claimStart, claimStart + claim.length)))
}

/** Denial only: exact counts/quotes do not prove the end of a contents scope. */
internal fun hasGroceryIntentCrossItemContentsScope(
    instruction: String, primaryQuotes: List<GroceryIntentReviewV2Quote>, sourceItemCount: Int = primaryQuotes.size,
    productOperationSources: List<GroceryIntentReviewV2Quote> = emptyList(),
    localPropertyConnectors: List<GroceryIntentLocalPropertyConnector> = emptyList(),
): Boolean {
    if (maxOf(sourceItemCount, primaryQuotes.size) < 2) return false
    val markers = groceryIntentContentsMarker.findAll(instruction).toList()
    val ordered = primaryQuotes.sortedBy { it.startUtf16 }
    if (ordered.size != sourceItemCount || ordered.any { it.startUtf16 < 0 || it.endUtf16 <= it.startUtf16 ||
            it.endUtf16 > instruction.length || instruction.substring(it.startUtf16, it.endUtf16) != it.text } ||
        ordered.zipWithNext().any { (left, right) -> left.endUtf16 > right.startUtf16 }) return markers.isNotEmpty()
    val firstStart = primaryQuotes.minOf { it.startUtf16 }
    val lastStart = primaryQuotes.maxOf { it.startUtf16 }
    // Only an explicitly named LIST has an independently established referent.
    // Bare "include" or "round it off with" can still describe hamper contents.
    val listTransitions = neutralGroceryIntentContextSpans(instruction, ordered.map { it.coverageSpan() }).filter { span ->
        Regex("\\b(?:the|this|my|our)[ \\t]+list\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(instruction.substring(span.startUtf16, span.endUtf16)) &&
            ordered.none { it.startUtf16 < span.endUtf16 && span.startUtf16 < it.endUtf16 }
    }
    return markers.any { marker -> marker.range.first > firstStart && marker.range.first < lastStart &&
        localPropertyConnectors.none { it.marker.startUtf16 == marker.range.first && it.marker.endUtf16 == marker.range.last + 1 } &&
        productOperationSources.none { source -> source.startUtf16 <= marker.range.first && marker.range.last < source.endUtf16 } &&
        listTransitions.none { it.startUtf16 <= marker.range.first && marker.range.last < it.endUtf16 } }
}

internal data class GroceryIntentAppositiveSourceRow(
    val rowIndex: Int,
    val primary: GroceryIntentReviewV2Quote,
    val ready: Boolean,
    val quantityUnstated: Boolean,
)

/** Denial-only guard for a comma apposition split into an unquantified alias row. */
internal fun groceryIntentAppositiveSourceSplitRows(
    instruction: String,
    rows: List<GroceryIntentAppositiveSourceRow>,
    provenQuantityRows: Set<Int> = emptySet(),
): Set<Int> {
    if (rows.map { it.rowIndex }.toSet().size != rows.size ||
        rows.any { row -> row.primary.startUtf16 < 0 || row.primary.endUtf16 <= row.primary.startUtf16 ||
            row.primary.endUtf16 > instruction.length ||
            instruction.substring(row.primary.startUtf16, row.primary.endUtf16) != row.primary.text }) return emptySet()
    val ordered = rows.sortedBy { it.primary.startUtf16 }
    val held = mutableSetOf<Int>()
    ordered.zipWithNext().forEach { (left, right) ->
        if (right.rowIndex in provenQuantityRows || left.primary.endUtf16 > right.primary.startUtf16) return@forEach
        val gap = instruction.substring(left.primary.endUtf16, right.primary.startUtf16)
        if (Regex("[ \\t]*,[ \\t]*(?:the[ \\t]+)?", RegexOption.IGNORE_CASE).matches(gap)) {
            val priorTokens = intentEvidenceTokens(left.primary.text)
            val aliasTokens = intentEvidenceTokens(right.primary.text)
            val definite = Regex("^the[ \\t]+", RegexOption.IGNORE_CASE).containsMatchIn(right.primary.text) ||
                Regex("the[ \\t]+$", RegexOption.IGNORE_CASE).containsMatchIn(gap)
            // A rejected definite description still prevents releasing its
            // incomplete predecessor. This denies; it never merges identities.
            val alias = right.ready && right.quantityUnstated && priorTokens.containsAll(aliasTokens)
            val refinement = definite && aliasTokens.any { it in priorTokens }
            if (aliasTokens.isNotEmpty() && (alias || refinement)) {
                if (left.ready) held += left.rowIndex
                if (right.ready) held += right.rowIndex
            }
        }
    }
    return held
}

internal data class GroceryIntentPropertyPreparation(
    val review: GroceryIntentReviewV2,
    val properties: List<GroceryIntentPropertyObligation>,
)

/** A reminder can cover source text, never supply missing row facts or amounts. */
private fun GroceryIntentReviewV2.restatementReceipts(
    eligibleRows: List<GroceryIntentReviewV2ReadyRow> = readyRows,
    properties: List<GroceryIntentPropertyObligation> = emptyList(),
): List<GroceryIntentRestatementReceipt> {
    if (blockedRows.any { it.source == null }) return emptyList()
    val primaries = readyRows.map { it.evidence.primary } + blockedRows.mapNotNull { it.source }
    val ineligible = groups.flatMap { it.memberIndices }.toSet() + properties.flatMap { it.memberIndices }
    val rows = eligibleRows.filter { row ->
        row.rowIndex !in ineligible && row.corrections.isEmpty() && row.evidence.supporting.isEmpty() &&
            row.groundingIssue(instruction = instruction) == null &&
            row.numericOwnershipIssue() == null && row.quantityOmissionIssue() == null &&
            (row.item.quantity == null || row.localQuantityEvidence() != null) &&
            (row.item.packValue == null || row.localPackSizeEvidence() != null) &&
            row.physicalTotalPackIssue(row.correctedMatchingItem()) == null
    }.map { row -> GroceryIntentRestatementRow(row.rowIndex, row.evidence.primary,
        row.item.quantity, row.item.quantityUnit, row.item.packValue, row.item.packUnit,
        row.item.packaging, row.item.required) }
    val protected = readyRows.flatMap { row -> row.evidence.supporting.map { it.quote } + row.corrections.map { it.source } } +
        blockedRows.flatMap { it.excludedContext } + groups.map { it.source } + constraints.map { it.source } + properties.map { it.source }
    return groceryIntentRestatementReceipts(instruction, rows, primaries, protected, allowQuantityExclusions = true)
}

/** Source-only same-count proofs: coverage receipts never supply final item facts. */
private fun GroceryIntentReviewV2.sameCountRefinementReceipts(
    eligibleRows: List<GroceryIntentReviewV2ReadyRow> = readyRows,
): List<SameCountRefinementReceipt> {
    if (groups.isNotEmpty() || readyRows.any { it.corrections.isNotEmpty() } ||
        blockedRows.any { it.source == null || it.excludedContext.isNotEmpty() } ||
        hasGroceryIntentPropertyObligationClause(instruction)) return emptyList()
    val rowsByIndex = (readyRows.map { it.rowIndex to it.evidence.primary } +
        blockedRows.mapNotNull { row -> row.source?.let { row.rowIndex to it } }).toMap()
    if (rowsByIndex.size != sourceItemCount || rowsByIndex.keys != (0 until sourceItemCount).toSet()) return emptyList()
    val primaries = (0 until sourceItemCount).map { rowsByIndex.getValue(it) }
    val protected = readyRows.flatMap { row ->
        row.evidence.supporting.map { it.quote } + row.corrections.map { it.source }
    } + constraints.map { it.source } + blockedRows.flatMap { it.excludedContext }
    // Local facts are checked without any refinement coverage or peer ready
    // label. The pure proof still has to account for the entire original clause.
    val independentPeers = eligibleRows.filter { row ->
        !row.item.needsClarification && row.item.quantity != null && row.item.excluded.isEmpty() &&
            row.evidence.supporting.isEmpty() && row.corrections.isEmpty() &&
            !intentV2HasCorrectionCue(row.evidence.primary.text) &&
            row.groundingIssue(instruction = instruction) == null &&
            row.numericOwnershipIssue() == null && row.quantityOmissionIssue() == null &&
            row.localQuantityEvidence()?.startUtf16 == 0 &&
            (row.item.packValue == null || row.localPackSizeEvidence() != null) &&
            row.physicalTotalPackIssue(row.correctedMatchingItem()) == null
    }.map { it.rowIndex }.toSet()
    return eligibleRows.mapNotNull { row ->
        val quantity = row.item.quantity
        val amountText = row.item.quantityText
        if (readyRows.none { it.rowIndex == row.rowIndex }) return@mapNotNull null
        if (row.evidence.supporting.isNotEmpty() || row.corrections.isNotEmpty() || row.item.needsClarification ||
            quantity == null || row.item.quantityUnit !in setOf("count", "pack") || amountText.isNullOrBlank() ||
            !refinementRetailContract(row.item.quantityUnit, row.item.packaging, row.item.packValue,
                row.item.packUnit, row.item.packText) ||
            row.groundingIssue(instruction = instruction) != null || row.numericOwnershipIssue() != null ||
            row.quantityOmissionIssue() != null || row.localQuantityEvidence() == null ||
            row.physicalTotalPackIssue(row.correctedMatchingItem()) != null) return@mapNotNull null
        try {
            reconstructSameCountRefinement(
                instruction = instruction,
                rowIndex = row.rowIndex,
                finalAmountText = amountText,
                quantity = quantity,
                quantityUnit = row.item.quantityUnit,
                finalName = row.item.name,
                originalPrimaries = primaries,
                protected = protected,
                independentPeerIndices = independentPeers,
                packaging = row.item.packaging,
                packValue = row.item.packValue,
                packUnit = row.item.packUnit,
                finalPackText = row.item.packText,
            )
        } catch (_: SameCountRefinementError) {
            null
        }
    }
}

/** Shared by actual preparation and offline diagnostics; no independent test-only grants. */
internal fun GroceryIntentReviewV2.provenPropertyPreparation(
    allowIngredientPredicates: Boolean = false,
    allowTypedIngredientPredicates: Boolean = false,
): GroceryIntentPropertyPreparation? {
    if (!hasGroceryIntentPropertyObligationClause(instruction, allowIngredientPredicates,
            allowTypedIngredientPredicates)) return GroceryIntentPropertyPreparation(this, emptyList())
    if (blockedRows.isNotEmpty()) return null
    // Never infer membership from a proposed model name. Remove only bound
    // original amount atoms before compiling scope; full grounding still runs.
    val localRows = readyRows.map { row -> row.copy(
        item = row.item.copy(supportingText = emptyList()),
        evidence = row.evidence.copy(supporting = emptyList()),
    ) }
    val amountsProven = localRows.all { row ->
        row.numericOwnershipIssue() == null && row.quantityOmissionIssue() == null &&
            (row.item.quantity == null || row.localQuantityEvidence() != null) &&
            (row.item.packValue == null || row.localPackSizeEvidence() != null)
    }
    if (!amountsProven) return null
    val primarySpans = localRows.map { it.evidence.primary.coverageSpan() }
    // DTO parsing already validates each numeric correction's original amount,
    // exact replacement, unique target and non-overlap. Those local receipts
    // may compose with property scope; they do not excuse unknown source text.
    val correctionSources = readyRows.flatMap { row -> row.corrections.map { it.source } }
    val neutral = (neutralGroceryIntentContextSpans(instruction, primarySpans) +
        correctionGroceryIntentDiscourseSpans(instruction, correctionSources.map { it.coverageSpan() }, primarySpans))
        .map { span ->
            // Keep canonical original quote offsets; context includes spacing.
            var start = span.startUtf16
            var end = span.endUtf16
            while (start < end && instruction[start].isWhitespace()) start++
            while (end > start && instruction[end - 1].isWhitespace()) end--
            GroceryIntentReviewV2Quote(instruction.substring(start, end), start, end)
        }
    val notes = validateFulfillmentConstraints(instruction, localRows.map { it.evidence.primary }, constraints,
        readyRows.flatMap { listOf(it.evidence.primary) + it.evidence.supporting.map { support -> support.quote } } +
            correctionSources + groups.flatMap { listOfNotNull(it.source, it.header, it.total?.source, it.each?.source) })
    val properties = compileGroceryIntentPropertyObligations(instruction, localRows.map { row ->
        GroceryIntentPropertyRow(row.rowIndex, row.evidence.primary,
            row.identitySourceWithoutAmounts(boundAtomsOnly = true), row.item.name, row.item.required, row.item.excluded)
    }, coveredContext = neutral + correctionSources + notes.map { it.source } + restatementReceipts().map { it.source },
        allowIngredientPredicates = allowIngredientPredicates,
        allowTypedIngredientPredicates = allowTypedIngredientPredicates).obligations
    if (properties.isEmpty()) return null
    val cleanedRows = readyRows.map { row ->
        val quotes = properties.filter { row.rowIndex in it.memberIndices }.map { it.source }.filter { quote ->
            instruction.indexOf(quote.text) == quote.startUtf16 && instruction.lastIndexOf(quote.text) == quote.startUtf16
        }.toSet()
        // A support may include the same original final clause delimiter.
        // This is span equality plus one punctuation mark, never text cleanup
        // that could hide a suffix, different target or cropped constraint.
        val ownedSupports = row.evidence.supporting.filter { support ->
            support.kind == "modifier" && quotes.any { source ->
                support.quote == source || (
                    support.quote.startUtf16 == source.startUtf16 &&
                    support.quote.endUtf16 == source.endUtf16 + 1 &&
                    instruction.getOrNull(source.endUtf16) in setOf('.', '!', '?', ';') &&
                    support.quote.text == source.text + instruction[source.endUtf16]
                )
            }
        }.map { it.quote }.toSet()
        row.copy(
            item = row.item.copy(supportingText = row.item.supportingText.filterNot { support ->
                support.kind == "modifier" && ownedSupports.any { it.text == support.text }
            }),
            evidence = row.evidence.copy(supporting = row.evidence.supporting.filterNot { support ->
                support.kind == "modifier" && support.quote in ownedSupports
            }),
        )
    }
    return GroceryIntentPropertyPreparation(copy(readyRows = cleanedRows), properties)
}

private fun GroceryIntentReviewV2.prepareWithPropertyObligations(
    properties: List<GroceryIntentPropertyObligation>,
    productOperations: List<GroceryIntentProductOperation> = emptyList(),
    allowTypedIngredientPredicates: Boolean = false,
): GroceryIntentPreparation {
    val initialRefinementReceipts = sameCountRefinementReceipts()
    val parsedByRowIndex = linkedMapOf<Int, ParsedItem>()
    val excluded = blockedRows.sortedBy { it.rowIndex }.mapNotNull { row ->
        (listOfNotNull(row.source?.text) + row.excludedContext.map { it.text })
            .takeIf { it.isNotEmpty() }?.joinToString("\n")
    }.toMutableList()
    val unlocated = blockedRows.count { it.source == null }
    if (unlocated > 0) excluded +=
        "$unlocated unclear part(s) could not be located. Please check your original list: $instruction"
    val coveredSpans = (blockedRows.flatMap { row -> listOfNotNull(row.source?.coverageSpan()) +
        row.excludedContext.map { it.coverageSpan() } }).toMutableList()
    coveredSpans += productOperations.flatMap { operation -> operation.coverageSources.map { it.coverageSpan() } }
    val primaryQuotes = readyRows.map { it.evidence.primary } + blockedRows.mapNotNull { it.source }
    val baseOwned = readyRows.flatMap { row ->
        listOf(row.evidence.primary) + row.evidence.supporting.map { it.quote } + row.corrections.map { it.source }
    } + blockedRows.flatMap { row -> listOfNotNull(row.source) + row.excludedContext } + groups.map { it.source } +
        initialRefinementReceipts.flatMap { it.coverageSources } + productOperations.flatMap { it.coverageSources }
    val verifiedConstraints = validateFulfillmentConstraints(
        instruction, primaryQuotes, constraints, baseOwned + groups.flatMap { group ->
            listOfNotNull(group.header, group.total?.source, group.each?.source)
        },
    )
    val constraintSources = verifiedConstraints.map { it.source }
    val restatements = restatementReceipts(properties = properties)
    val owned = baseOwned + constraintSources + properties.map { it.source } + restatements.map { it.source }
    coveredSpans += constraintSources.map { it.coverageSpan() }
    val primarySpans = readyRows.map { it.evidence.primary.coverageSpan() } +
        blockedRows.mapNotNull { it.source?.coverageSpan() }
    val allPrimarySources = readyRows.map { it.rowIndex to it.evidence.primary.text } +
        blockedRows.mapNotNull { row -> row.source?.let { row.rowIndex to it.text } }
    // Derive whole neutral clauses and bound request prefixes before masking evidence. Never turn e.g.
    // 'no [owned product] rush' into 'no rush' by joining residual fragments.
    val correctionSpans = readyRows.flatMap { row -> row.corrections.map { it.source.coverageSpan() } }
    val quantifiedMembers = groups.flatMap { group -> group.members.filter { it.value != null }.map { it.rowIndex } }.toSet()
    val quantitySafeRows = readyRows.filter { row ->
        row.numericOwnershipIssue() == null && row.quantityOmissionIssue() == null &&
            (row.rowIndex in quantifiedMembers || row.localQuantityEvidence() != null ||
                (row.item.quantity == null && (listOf(row.evidence.primary.text) +
                    row.evidence.supporting.map { it.quote.text }).none { intentV2HasCorrectionCue(it) }))
    }
    val unstatedSpans = quantitySafeRows.filter { it.item.quantity == null && it.rowIndex !in quantifiedMembers }
        .map { it.evidence.primary.coverageSpan() }
    val identityBoundaries = quantitySafeRows.filter { it.item.quantity != null && !it.item.needsClarification }
        .map { it.evidence.primary }
    val cartRequest = if (unlocated == 0) groceryIntentCartRequestPrefix(instruction, primarySpans,
        quantitySafeRows.map { it.evidence.primary.coverageSpan() }, unstatedSpans) else null
    fun neutralSpansFor(
        activeCorrections: List<GroceryIntentCoverageSpan>, activeGroups: List<GroceryIntentReviewV2Group>,
        activeOwned: List<GroceryIntentReviewV2Quote>,
    ) = (neutralGroceryIntentContextSpans(instruction, primarySpans) +
        listOfNotNull(cartRequest) +
        activeGroups.mapNotNull { it.sequenceContextSpan(instruction, primaryQuotes) } +
        activeGroups.mapNotNull { it.contextualAffirmedHeading(instruction, allPrimarySources)?.transition } +
        correctionGroceryIntentDiscourseSpans(instruction, activeCorrections, primarySpans)).filter { neutral ->
        activeOwned.none { it.startUtf16 < neutral.endUtf16 && neutral.startUtf16 < it.endUtf16 }
    }
    val neutralSpans = neutralSpansFor(correctionSpans, groups, owned)
    val emphasisContext = neutralSpans + constraintSources.map { it.coverageSpan() }
    val countEmphasis = quantitySafeRows.filter { row ->
        row.rowIndex !in quantifiedMembers && groups.none { row.rowIndex in it.memberIndices } &&
            !row.item.needsClarification && row.evidence.supporting.isEmpty() && row.corrections.isEmpty()
    }.mapNotNull { row -> row.terminalCountModifierRange(instruction, identityBoundaries, emphasisContext)
        ?.let { row.evidence.primary to it } }.toMap()
    val additiveCountPrefixes = quantitySafeRows.filter { it.evidence.primary in countEmphasis }
        .mapNotNull { row -> row.additiveCountPrefixRange(instruction, identityBoundaries, emphasisContext)
            ?.let { row.evidence.primary to it } }.toMap()
    fun quantityCueText(primary: GroceryIntentReviewV2Quote): String {
        val characters = primary.text.toCharArray()
        listOfNotNull(countEmphasis[primary], additiveCountPrefixes[primary]).forEach { range ->
            for (position in range) characters[position] = ' '
        }
        return String(characters)
    }
    coveredSpans += neutralSpans
    val remaining = instruction.toCharArray()
    neutralSpans.forEach { span -> for (index in span.startUtf16 until span.endUtf16) remaining[index] = ' ' }
    owned.forEach { span -> for (index in span.startUtf16 until span.endUtf16) remaining[index] = ' ' }
    val remainingText = String(remaining)
    // A nominal stock condition cannot conceal an unknown cancellation,
    // cross-product condition or distribution outside its primary. Unlike a
    // display-only uncovered-text warning, this must remain non-actionable.
    val hasLocalAvailability = primaryQuotes.any { intentV2LocalAvailabilityGrammarRanges(it.text).isNotEmpty() }
    // A dietary clause with no unique owner might apply to another row too.
    // Excluding only the model-selected row would silently discard that scope.
    val unboundPropertyContrast = allPrimarySources.any { (_, source) ->
        intentV2NamedPropertyContrast(source) != null
    } || readyRows.any { row ->
        row.evidence.supporting.any { support ->
            intentV2NamedPropertyContrast(support.quote.text)?.let { contrast ->
                !substitutionTargetIsProven(contrast.target, row.evidence.primary.text) ||
                    !substitutionTargetIsUnique(contrast.target, row.rowIndex, allPrimarySources)
            } == true
        }
    }
    // A model-selected primary, including a blocked row's source, cannot claim
    // an operation's target merely by swallowing its words. Check ORIGINAL
    // primaries before masking; typed corrections/support receipts stay separate.
    val unclaimedCorrection = primaryQuotes.any { primary -> intentV2HasPrimaryOperation(
        quantityCueText(primary),
        primaryQuotes.filter { it !== primary }.map { it.text },
        allowLocalAvailability = localAvailabilityPrimaryIsComplete(primary, instruction),
    ) } || unboundPropertyContrast || groups.any { group ->
        group.outsideEmbeddedCorrectionFragments(readyRows).any {
            intentV2HasCorrectionCue(it, allowPropertyContrast = false) || negativeSubstitution.containsMatchIn(it) ||
                excludedAttributeVariant.containsMatchIn(it) || intentV2ExcludedOneMatch(it) != null
        }
    } ||
        negativeAttribute.containsMatchIn(remainingText) || excludedAttributeVariant.containsMatchIn(remainingText) ||
        intentV2ExcludedOneMatch(remainingText) != null ||
        negativeSubstitution.containsMatchIn(remainingText) ||
        intentV2HasCorrectionCue(remainingText, allowPropertyContrast = false) ||
        // An unowned keep/make/set clause may change a product or property.
        // Proven original-source list narration has already been masked.
        Regex("\\b(?:make|set|keep)\\b", RegexOption.IGNORE_CASE).containsMatchIn(remainingText)
    val groupByMember = groups.flatMap { group -> group.memberIndices.map { it to group } }.toMap()
    val unownedPostfixRows = mutableSetOf<Int>()
    fun literalIdentity(text: String) = text.trim(' ', '\t', '\r', '\n', '.').lowercase(Locale.ROOT)
        .split(Regex("\\s+")).joinToString(" ")
    groceryIntentPostfixIdentityCandidates(instruction).forEach { scope ->
        // Independently validated request notes own only their exact clause;
        // Hindi 'sab' there is not a new product identity for following rows.
        if (constraintSources.any { it.startUtf16 <= scope.start && scope.end <= it.endUtf16 }) return@forEach
        if (properties.any { it.source.startUtf16 <= scope.start && scope.end <= it.source.endUtf16 }) return@forEach
        if (neutralSpans.any { it.startUtf16 <= scope.start && scope.end <= it.endUtf16 }) return@forEach
        val ownedGroup = groups.any { group -> group.postfixIdentity() != null &&
            group.header?.startUtf16 == scope.start && scope.end <= group.header.endUtf16 }
        // Explicit universal grammar cannot masquerade as a standalone product.
        val literalRows = if (Regex("^(?i:all)[ \\t]+").containsMatchIn(scope.text) &&
            groceryIntentPostfixIdentityHeading(scope.text) == null) readyRows.filter { row ->
            val primary = row.evidence.primary
            row.rowIndex !in groupByMember && primary.startUtf16 <= scope.start && primary.endUtf16 >= scope.end && row.groundingIssue(instruction = instruction) == null &&
                literalIdentity(row.identitySourceWithoutAmounts(boundAtomsOnly = true)) == literalIdentity(scope.text) &&
                literalIdentity(row.item.name) == literalIdentity(scope.text)
        }.map { it.rowIndex }.toSet() else emptySet()
        // A literal product preserves itself only, never unbranded siblings.
        if (!ownedGroup) unownedPostfixRows += readyRows.filter { row ->
            row.rowIndex !in literalRows && row.evidence.primary.startUtf16 < scope.right && row.evidence.primary.endUtf16 > scope.left
        }.map { it.rowIndex }
    }
    val readyByIndex = readyRows.associateBy { it.rowIndex }
    val categoryHeaders = groups.associateWith { group ->
        group.contextualFromRequestIdentity(instruction, allPrimarySources)
            ?: group.contextualAffirmedHeadingIdentity(instruction, allPrimarySources)
            ?: group.categoryHeader(instruction, allPrimarySources)
    }
    val categoryIssues = groups.associateWith { group ->
        categoryHeaders[group]?.let { header -> groceryIntentGroupCategoryCoverageIssue(header, group.memberIndices.map { index ->
            val row = readyByIndex.getValue(index)
            row.categoryIdentitySource(group) to row.item.name
        }, group.memberIndices.map {
            val item = readyByIndex.getValue(it).item
            item.required + listOfNotNull(item.packaging, item.quantityUnit.takeIf { item.quantity != null })
        }, localQuantities = group.hasEligibleLocalQuantityHeading()) }
    }
    val appositiveSplitRows = groceryIntentAppositiveSourceSplitRows(instruction,
        readyRows.map { row -> GroceryIntentAppositiveSourceRow(row.rowIndex, row.evidence.primary, true, row.item.quantity == null) } +
            blockedRows.mapNotNull { row -> row.source?.let { GroceryIntentAppositiveSourceRow(row.rowIndex, it, false, false) } },
        quantitySafeRows.filter { it.item.quantity != null }.map { it.rowIndex }.toSet() + quantifiedMembers,
    )
    val nominalEdgeRows = mutableSetOf<Int>()
    val refinementSourceContext = initialRefinementReceipts.flatMap { receipt ->
        receipt.coverageSources.map { it.coverageSpan() }
    }
    val conversions = readyRows.associate { row ->
        val group = groupByMember[row.rowIndex]
        // Only inherited/corrected amounts use operation-scoped ownership.
        // A validated group owns a local member's left context, not its tail.
        val inherited = group?.members?.singleOrNull { it.rowIndex == row.rowIndex }?.origin == "each"
        val sourceContextIssue = if (!inherited && row.corrections.none { it.kind == "quantity" })
            sourcePiecePurchaseReceipt(row.evidence.primary.text)?.let {
                if (group == null) sourcePieceInstructionIssue(instruction, it)
                else "source_piece_context_unproven".takeUnless { _ ->
                    groceryIntentSourceClauseEndProven(instruction, it.source, it.terminalEnd)
                }
            } else null
        val existingIssue = if (row.rowIndex in appositiveSplitRows) "appositive_source_split_unproven"
            else if (row.rowIndex in unownedPostfixRows) "postfix_identity_requires_group_proof"
            else if (unclaimedCorrection) "unclaimed_correction_requires_review"
            else sourceContextIssue ?: categoryIssues[group] ?: row.groundingIssue(group, allPrimarySources, instruction,
                properties.filter { row.rowIndex in it.memberIndices },
                neutralSpans + constraintSources.map { it.coverageSpan() } + refinementSourceContext +
                    productOperations.flatMap { operation -> operation.coverageSources.map { it.coverageSpan() } },
                identityBoundaries, nominalEdgeRows,
                enforceSourceTotalRole = groups.isEmpty() && readyRows.all { it.corrections.isEmpty() } &&
                    !intentV2HasCorrectionCue(instruction),
                allowTypedIngredientPredicates = allowTypedIngredientPredicates)
        val purposeHeader = group?.header?.takeIf { groceryIntentPurposeGroupHeading(it.text) != null }
        val issue = existingIssue ?: listPurposeIdentityIssue(
            row.evidence.primary, row.identitySourceWithoutAmounts(boundAtomsOnly = true),
            verifiedConstraints + listOfNotNull(purposeHeader?.let { GroceryIntentReviewV2Constraint("list_purpose", it) }),
        )
        row.rowIndex to if (issue == null) runCatching {
            val matched = row.correctedMatchingItem(group)
            require(row.physicalTotalPackIssue(matched) == null)
            require(row.discretePackRoleIssue(matched) == null)
            require(row.physicalUnitSizeIssue(matched, group) == null)
            val genericContainer = if (group == null) row.genericContainerRange() else null
            val local = row.localQuantityEvidence()
            require(row.item.quantity == null || local != null)
            val origin = when {
                row.corrections.any { it.kind == "quantity" } -> IntentQuantityOrigin.CORRECTION
                group?.members?.single { it.rowIndex == row.rowIndex }?.origin == "each" -> IntentQuantityOrigin.GROUP
                local != null -> IntentQuantityOrigin.LOCAL
                else -> IntentQuantityOrigin.UNSTATED
            }
            val retailReceipt = group == null && row.corrections.isEmpty() && origin == IntentQuantityOrigin.LOCAL &&
                matched.quantityUnit == "pack" && row.sourceProvesRetailCount() &&
                row.sourceRetailCountEvidence()?.isGenericPacketCountSyntax() == false
            // Decide eligibility before rendering: only a source-bound receipt
            // may separate a generic purchase unit from product identity.
            val contentsSource = if (group == null && row.corrections.isEmpty() &&
                origin == IntentQuantityOrigin.LOCAL && genericContainer == null && !retailReceipt &&
                row.evidence.primary !in additiveCountPrefixes &&
                matched.sourceText == row.evidence.primary.text)
                matched.sourceContainerContentsReceipt() else null
            // Binding numbers must not erase their original roles. Until an
            // operation can carry this proof, hold it instead of falling back
            // from outer packages to loose-piece arithmetic (equal or unequal).
            require(contentsSource != null || row.item.toMatchingItem().sourceContainerContentsReceipt() == null) {
                "Source contents role has no supported conversion"
            }
            // With two discrete amounts, the enum cannot choose the purchase
            // unit. Require original piece wording unless an outer/inner proof
            // already owns the roles; product nouns and adjectives do not.
            require(contentsSource != null || matched.quantityUnit != "piece" || row.item.packUnit != "piece" ||
                row.item.quantityText?.let { hasExplicitPieceQuantityAtom(row.evidence.primary.text, it) } == true) {
                "Discrete purchase count role is not proven"
            }
            // V2 owns its independently checked provenance, not legacy proofs.
            val parsedItem = matched.toParsedItem(
                listOfNotNull(row.item.packaging, group?.packaging).toSet(),
                legacySourceProofs = false, contentsSource = contentsSource,
            )
            require(matched.hasExactPackProjection(parsedItem)) { "Pack projection changed" }
            val contentsProof = parsedItem.containerContentsProof
            require(contentsProof == null || parsedItem.rawText == matched.sourceText)
            val converted = parsedItem.copy(
                logicalPieceCount = contentsProof == null && matched.quantityUnit == "piece",
                retailPackCount = parsedItem.retailPackCount || genericContainer != null || contentsProof != null,
                containerContentsProof = contentsProof,
                rawText = if (genericContainer == null) parsedItem.rawText else
                    parsedItem.rawText + " — Pack type not specified; review the suggested pack.",
                cartQuantityMode = if (row.evidence.primary in additiveCountPrefixes) IntentCartQuantityMode.INCREMENT
                    else IntentCartQuantityMode.TARGET,
            )
            val categories = categoryHeaders[group]?.let { header ->
                groceryIntentGroupCategoryHeader(header,
                    localQuantities = requireNotNull(group).hasEligibleLocalQuantityHeading())?.categories
            }.orEmpty()
            // Produce is currently a single-category source grammar. If that
            // expands, require a member-specific proof rather than dropping it.
            require(categories.none { it in setOf("fruits", "vegetables") } || categories.size == 1)
            val namedPieceCategory = row.corrections.singleOrNull { it.kind == "quantity" }
                ?.namedPieceProduceCategory(row.item, row.evidence.primary)
            require(namedPieceCategory == null || categories.isEmpty())
            val ingredientProof = if (allowTypedIngredientPredicates) {
                IntentIngredientProvenance.fromProvenObligations(instruction, row.rowIndex, converted, properties)
            } else null
            converted.copy(requiresIntentQuantityProof = true, intentQuantityProvenance = IntentQuantityProvenance(
                origin, row.evidence.primary.text, converted.rawText, converted.quantity, local,
                converted.query, converted.strictMatchPhrase, converted.retailPackCount, converted.logicalPieceCount,
                produceCategory = namedPieceCategory ?: when (categories.singleOrNull()) {
                    "fruits" -> IntentProduceCategory.FRUITS
                    "vegetables" -> IntentProduceCategory.VEGETABLES
                    else -> null
                },
                opaqueNumericLabels = groceryIntentOpaqueNumericLabels(converted.query),
                cartQuantityMode = converted.cartQuantityMode,
                retailContainer = row.item.packaging.takeIf { retailReceipt },
                unitSize = row.localPackSizeEvidence().takeIf { retailReceipt },
                corrected = row.corrections.singleOrNull { it.kind == "quantity" }?.let { correction ->
                    IntentLocalAmount(
                        correction.source.text,
                        correction.newAmount.startUtf16 - correction.source.startUtf16,
                        correction.newAmount.endUtf16 - correction.source.startUtf16,
                        correction.newAmount.text, correction.newValue, correction.newUnit,
                    )
                },
                ingredientProvenance = ingredientProof,
            ), ingredientProvenance = ingredientProof)
        }.getOrNull() else null
    }.toMutableMap()
    // A backend row label cannot make a dietary apposition independent. Derive
    // the original source components again, including visibly rejected rows.
    val scopePredecessors = groups.associateWith { group ->
        if (group.header == null && group.each?.source?.startUtf16 == group.source.startUtf16) {
            // The decoder proved this forward-each scope only because these
            // original preceding rows were fully owned. Revoke it if any fails
            // later conversion/fragment checks; do not reuse stale readiness.
            val scopeStart = Regex("[;!?]|\\.(?=\\s|$)")
                .findAll(instruction.substring(0, group.source.startUtf16)).lastOrNull()
                ?.range?.last?.plus(1) ?: 0
            readyRows.filter { row -> row.evidence.primary.startUtf16 in scopeStart until group.source.startUtf16 }
                .map { it.rowIndex }.toSet()
        } else emptySet()
    }
    while (true) {
        val eligible = conversions.filterValues { it != null }.keys.toSet()
        val activeGroups = groups.filter { group ->
            group.correctedAmountsIssue(readyByIndex) == null && group.memberIndices.all { it in eligible } &&
                scopePredecessors.getValue(group).all { it in eligible }
        }
        val activeProperties = properties.filter { proof -> proof.memberIndices.all { it in eligible } }
        val activeRestatements = restatementReceipts(readyRows.filter { it.rowIndex in eligible }, activeProperties)
        val activeRefinementReceipts = sameCountRefinementReceipts(readyRows.filter { it.rowIndex in eligible })
        // Losing any peer invalidates this rooted partition, not just the
        // corrected row. Do not let earlier coverage survive a later failure.
        val failedRefinementMembers = initialRefinementReceipts.filter { receipt ->
            receipt.rowIndex !in eligible || receipt.scopeMembers.any { it !in eligible }
        }.flatMap { listOf(it.rowIndex) + it.scopeMembers }
        val activeBoundaries = readyRows.filter { it.rowIndex in eligible && it.evidence.primary in identityBoundaries }
            .map { it.evidence.primary }
        val activeProductSources = if (productOperations.isNotEmpty() && eligible.size == sourceItemCount)
            productOperations.flatMap { it.coverageSources } else emptyList()
        // This receipt narrowed a whole-basket operation cue. If a neighbour
        // loses its independent edge, revoke the exemption for all items.
        if (countEmphasis.keys.any { it !in activeBoundaries ||
                !originalNominalEdgesComplete(it, instruction, activeBoundaries, emphasisContext) }) {
            conversions.keys.toList().forEach { conversions[it] = null }
            break
        }
        val nominalHeld = readyRows.filter { it.rowIndex in eligible && it.rowIndex in nominalEdgeRows &&
            !originalNominalEdgesComplete(it.evidence.primary, instruction, activeBoundaries,
                neutralSpans + constraintSources.map { source -> source.coverageSpan() }) }.map { it.rowIndex }
        val held = fragmentSourceScopeRows(eligible,
            activeGroups.map { it.source } + activeProperties.map { it.source } + constraintSources +
                readyRows.filter { it.rowIndex in eligible }.flatMap { row ->
                    row.corrections.map { it.source } + row.evidence.supporting.map { it.quote }
                } +
                activeRestatements.map { it.source } + activeRefinementReceipts.flatMap { it.coverageSources } + listOfNotNull(cartRequest?.let { span ->
                    GroceryIntentReviewV2Quote(instruction.substring(span.startUtf16, span.endUtf16), span.startUtf16, span.endUtf16)
                }) + activeProductSources) + groups.filter { it !in activeGroups }.flatMap { it.memberIndices }
        val newlyHeld = (held + nominalHeld + failedRefinementMembers).filter { it in eligible }
        if (newlyHeld.isEmpty()) break
        newlyHeld.forEach { conversions[it] = null }
    }
    // All original rows participated in the scope proof. Do not keep a partial
    // basket after a late grounding failure changes the independently known set.
    if (properties.isNotEmpty() && conversions.values.any { it == null }) {
        return GroceryIntentPreparation(items = emptyList(), unresolvedText = listOf(instruction), excludedText = emptyList(),
            recoverySnapshot = GroceryIntentRecoverySnapshot.generic(instruction))
    }
    coveredSpans += properties.map { it.source.coverageSpan() }
    val failedGroups = groups.filter { group ->
        group.correctedAmountsIssue(readyByIndex) != null || group.memberIndices.any { conversions[it] == null }
    }
    val failedMembers = failedGroups.flatMap { it.memberIndices }.toSet()
    failedGroups.forEach { group ->
        // One missing/unsafe variant must not silently break a shared total.
        // This original text is covered only because it is visibly excluded.
        excluded += group.source.text
        coveredSpans += group.source.coverageSpan()
    }
    val successfulGroups = groups.filter { it !in failedGroups }
    val survivingRows = readyRows.filter { conversions[it.rowIndex] != null && it.rowIndex !in failedMembers }
    val terminalCart = if (unlocated == 0) groceryIntentTerminalCommaCartRequest(
        instruction, primarySpans, survivingRows.map { it.evidence.primary.coverageSpan() },
    ) else null
    // Revoke coverage after any late failure. Original primaries still take
    // part in uniqueness, even if the competing row could not be converted.
    val survivingRestatements = restatementReceipts(survivingRows, properties)
    val survivingRefinementReceipts = sameCountRefinementReceipts(survivingRows)
    if (hasLocalAvailability || readyRows.any { it.corrections.isNotEmpty() } || productOperations.isNotEmpty() ||
        restatements.any { it.kind == "quantity_exclusion" } ||
        (properties.isNotEmpty() && survivingRows.isNotEmpty()) || initialRefinementReceipts.isNotEmpty()) {
        // Match the backend's complete-source closing check, independently.
        // A failed conversion/group cannot leave its correction quote behind as
        // borrowed coverage for another row. Plain omitted wording matters too,
        // even when it contains none of the known correction/negation cues.
        val survivingPrimary = if (hasLocalAvailability) primaryQuotes.filter { source ->
            survivingRows.any { it.evidence.primary == source } ||
                (intentV2LocalAvailabilityGrammarRanges(source.text).isNotEmpty() &&
                    localAvailabilityPrimaryIsComplete(source, instruction))
        } else primaryQuotes
        val survivingOwned = survivingPrimary + survivingRows.flatMap { row ->
            row.evidence.supporting.map { it.quote } + row.corrections.map { it.source }
        } + blockedRows.flatMap { it.excludedContext } + successfulGroups.map { it.source } +
            constraintSources + properties.map { it.source } + survivingRestatements.map { it.source } +
            survivingRefinementReceipts.flatMap { it.coverageSources } +
            (if (productOperations.isNotEmpty() && conversions.values.all { it != null })
                productOperations.flatMap { it.coverageSources } else emptyList()) + listOfNotNull(terminalCart?.let { span ->
                GroceryIntentReviewV2Quote(instruction.substring(span.startUtf16, span.endUtf16), span.startUtf16, span.endUtf16)
            })
        val closing = instruction.toCharArray()
        val survivingNeutral = neutralSpansFor(survivingRows.flatMap { row -> row.corrections.map { it.source.coverageSpan() } },
            successfulGroups, survivingOwned)
        survivingNeutral.forEach { span -> for (index in span.startUtf16 until span.endUtf16) closing[index] = ' ' }
        survivingOwned.forEach { span -> for (index in span.startUtf16 until span.endUtf16) closing[index] = ' ' }
        val connectiveWords = setOf("please", "thanks", "thank", "you", "and", "also", "plus", "add", "get", "buy",
            "order", "cart", "my", "list", "i", "want", "need")
        if (Regex("[\\p{L}\\p{N}]+").findAll(String(closing).lowercase(Locale.ROOT)).any { it.value !in connectiveWords }) {
            return GroceryIntentPreparation(items = emptyList(), unresolvedText = listOf(instruction), excludedText = emptyList(),
                recoverySnapshot = GroceryIntentRecoverySnapshot.generic(instruction))
        }
    }
    successfulGroups.forEach { coveredSpans += it.source.coverageSpan() }
    terminalCart?.let { coveredSpans += it }
    coveredSpans += survivingRestatements.map { it.source.coverageSpan() }
    coveredSpans += survivingRefinementReceipts.flatMap { receipt ->
        receipt.coverageSources.map { it.coverageSpan() }
    }
    readyRows.sortedBy { it.rowIndex }.forEach { row ->
        if (row.rowIndex in failedMembers) return@forEach
        // Evidence was independently parsed and tied to this row. Normalize each
        // quote separately: joining 'no' + 'added sugar' must not invent a phrase.
        val fragments = listOf(row.evidence.primary.text) + row.evidence.supporting.map { it.quote.text }
        coveredSpans += row.evidence.primary.coverageSpan()
        val converted = conversions[row.rowIndex]
        if (converted == null) {
            // Never let a rejected phrase become a ParsedItem via the legacy
            // parser. Keep its original words visible as excluded at final review.
            excluded += (fragments + row.corrections.map { it.source.text }).joinToString("\n")
            coveredSpans += row.evidence.supporting.map { it.quote.coverageSpan() }
            coveredSpans += row.corrections.map { it.source.coverageSpan() }
        } else {
            val shared = fragments.drop(1)
            val correctionNote = row.corrections.joinToString("\n") {
                val label = if (it.kind == "quantity") "Quantity" else "Pack size"
                "$label changed: ${it.oldValue.stripTrailingZeros().toPlainString()} ${it.oldUnit} → " +
                    "${it.newValue.stripTrailingZeros().toPlainString()} ${it.newUnit}. You said: ${it.source.text}"
            }
            val rawText = converted.rawText +
                (if (shared.isEmpty()) "" else "\nShared wording: " + shared.joinToString("; ")) +
                (if (correctionNote.isEmpty()) "" else "\n" + correctionNote) +
                survivingRestatements.filter { it.rowIndex == row.rowIndex }.joinToString("") { "\nYou also said: ${it.source.text}" }
            val ingredientProof = if (converted.ingredientProvenance == null) null else {
                requireNotNull(IntentIngredientProvenance.fromProvenObligations(
                    instruction, row.rowIndex, converted.copy(rawText = rawText), properties,
                ))
            }
            parsedByRowIndex[row.rowIndex] = converted.copy(
                rawText = rawText,
                intentQuantityProvenance = converted.intentQuantityProvenance?.copy(
                    rawText = rawText, ingredientProvenance = ingredientProof,
                ),
                ingredientProvenance = ingredientProof,
            )
            coveredSpans += row.evidence.supporting.map { it.quote.coverageSpan() }
            coveredSpans += row.corrections.map { it.source.coverageSpan() }
        }
    }
    // A section label may be covered only after the ordinary conversions above
    // succeeded. Do not feed it into correction ownership or readiness checks.
    val snackMembers = readyRows.filter { row ->
        conversions[row.rowIndex] != null && row.rowIndex !in groupByMember &&
            row.evidence.supporting.isEmpty() && row.corrections.isEmpty() &&
            groceryIntentHasStandaloneSnackIdentity(row.identitySourceWithoutAmounts(), row.item.name)
    }.map { it.evidence.primary.coverageSpan() }
    val snackProtected = readyRows.flatMap { row ->
        row.evidence.supporting.map { it.quote.coverageSpan() } + row.corrections.map { it.source.coverageSpan() }
    } + blockedRows.flatMap { row -> row.excludedContext.map { it.coverageSpan() } } +
        groups.map { it.source.coverageSpan() } + constraintSources.map { it.coverageSpan() }
    if (unlocated == 0) coveredSpans += groceryIntentSnackSectionSpans(
        instruction, primarySpans, snackMembers, snackProtected,
    )
    val unresolvedText = uncoveredGroceryIntentSegmentsBySpan(instruction, coveredSpans).distinct()
    val hasUnprovenHold = unresolvedText.isNotEmpty() || blockedRows.isNotEmpty() || failedGroups.isNotEmpty() ||
        conversions.values.any { it == null }
    if (productOperations.isNotEmpty() && hasUnprovenHold) {
        return GroceryIntentPreparation(items = emptyList(), unresolvedText = listOf(instruction), excludedText = emptyList(),
            recoverySnapshot = GroceryIntentRecoverySnapshot.generic(instruction))
    }
    val replacedRows = productOperations.map { it.targetRowIndex }.toSet()
    val replacementNotes = productOperations.map { operation ->
        val oldName = readyByIndex.getValue(operation.targetRowIndex).item.name
        val newName = readyByIndex.getValue(operation.replacementRowIndex).item.name
        "You asked to replace $oldName with $newName. The original item is left out; only $newName is included."
    }
    val recoverySnapshot = if (!hasUnprovenHold) null else {
        // This only improves the reason in the existing editor. It supplies no coverage,
        // source edit, row promotion or shortcut around a fresh whole-list validation.
        val onlyOpeningCanBeExplained = blockedRows.isEmpty() && failedGroups.isEmpty() &&
            unlocated == 0 && excluded.isEmpty() && parsedByRowIndex.size == sourceItemCount &&
            readyRows.size == sourceItemCount && groups.isEmpty() && properties.isEmpty() &&
            constraints.isEmpty() && productOperations.isEmpty() &&
            readyRows.all { it.corrections.isEmpty() && it.evidence.supporting.isEmpty() }
        if (onlyOpeningCanBeExplained) GroceryIntentRecoverySnapshot.withUnresolvedOpening(
            instruction, readyRows.minOfOrNull { it.evidence.primary.startUtf16 } ?: 0, coveredSpans,
        ) else GroceryIntentRecoverySnapshot.generic(instruction)
    }
    return GroceryIntentPreparation(
        items = if (productOperations.isEmpty()) parsedByRowIndex.values.toList() else
            readyRows.sortedBy { it.rowIndex }.filter { it.rowIndex !in replacedRows }
                .mapNotNull { parsedByRowIndex[it.rowIndex] },
        unresolvedText = unresolvedText,
        excludedText = excluded.distinct(),
        sharedGroupNotes = (successfulGroups.map { group ->
            if (group.header?.let { groceryIntentPurposeGroupHeading(it.text) } != null) group.header.text
            else listOfNotNull(group.header?.text?.trim()?.trimEnd(':', '：', '—', '–', '‒', '-')?.trim(), group.each?.source?.text)
                .joinToString(" — ")
        } + properties.map { it.source.text } + verifiedConstraints.filter { it.kind == "list_purpose" }
            .map { it.source.text } + replacementNotes).distinct(),
        fulfillmentConstraints = verifiedConstraints.filter { it.kind == "fulfillment" }.map { it.source.text },
        recoverySnapshot = recoverySnapshot,
    )
}

private fun GroceryIntentReviewV2.fragmentSourceScopeRows(
    locallyReady: Set<Int>, protectedSources: List<GroceryIntentReviewV2Quote>,
): Set<Int> {
    val localConnectors = if (locallyReady.size == sourceItemCount) localPropertyConnectors() else emptyList()
    val primaries = (readyRows.map { it.rowIndex to it.evidence.primary } +
        blockedRows.mapNotNull { row -> row.source?.let { row.rowIndex to it } })
        .sortedWith(compareBy({ it.second.startUtf16 }, { it.second.endUtf16 }))
    val terminalCart = if (primaries.size == sourceItemCount) groceryIntentTerminalCommaCartRequest(
        instruction, primaries.map { it.second.coverageSpan() },
        readyRows.filter { it.rowIndex in locallyReady }.map { it.evidence.primary.coverageSpan() },
    ) else null
    val neutralContext = neutralGroceryIntentContextSpans(instruction, primaries.map { it.second.coverageSpan() }) +
        listOfNotNull(terminalCart)
    val imperativeDoSpans = neutralHindiImperativeDoSpans(instruction, neutralContext)
    val scopedContext = protectedSources.map { it.startUtf16 until it.endUtf16 } +
        neutralContext.map { it.startUtf16 until it.endUtf16 }
    val quantified = readyRows.filter { row ->
        row.rowIndex in locallyReady && row.numericOwnershipIssue() == null && row.quantityOmissionIssue() == null && row.localQuantityEvidence() != null
    }.map { it.rowIndex }.toSet()
    val property = Regex("\\b(?:$V2_PROTECTED_PROPERTY_PATTERN)\\b", RegexOption.IGNORE_CASE)
    val hardEnd = Regex("[;!?\\r\\n]|\\.(?=\\s|$)")
    val trailing = Regex("[ \\t\\r\\n;.!?]*$")
    fun propertyOnly(text: String): Boolean =
        (property.containsMatchIn(text) || negativeAttribute.containsMatchIn(text)) &&
            groceryIntentFragmentIdentityTokens(property.replace(negativeAttribute.replace(text, " "), " ")).isEmpty()
    val affected = mutableSetOf<Int>()
    val numericDependents = mutableSetOf<Int>()
    val backwardCounts = mutableSetOf<Int>()
    fun amountOnly(text: String): Boolean =
        groceryIntentContainsNumericAtom(text) && groceryIntentFragmentIdentityTokens(text).isEmpty()
    fun hasProductIdentity(text: String): Boolean =
        groceryIntentFragmentIdentityTokens(property.replace(negativeAttribute.replace(text, " "), " ")).isNotEmpty()
    fun ownedPropertySuffixes(row: GroceryIntentReviewV2ReadyRow): List<IntRange> {
        val primary = row.evidence.primary
        val local = localConnectors.filter { it.rowIndex == row.rowIndex }.map {
            it.propertySource.startUtf16 until it.propertySource.endUtf16
        }
        if (local.isNotEmpty()) return local
        primaries.map { it.second }.filter { it.endUtf16 <= primary.startUtf16 }.maxByOrNull { it.endUtf16 }?.let { prior ->
            if (!Regex("[ \\t]*,[ \\t]*").matches(instruction.substring(prior.endUtf16, primary.startUtf16))) return emptyList()
        }
        return listOf(property, negativeAttribute).flatMap { pattern -> pattern.findAll(primary.text).mapNotNull { match ->
            val before = primary.text.substring(0, match.range.first)
            val after = primary.text.substring(match.range.last + 1)
            // Extra nominal-prefix words may carry a qualifier, report or
            // shared scope. Preserving them in the model name is not ownership.
            val noun = before.trim().lowercase(Locale.ROOT)
            if (after.isNotBlank() || !Regex("[A-Za-z]+[ \\t]+").matches(before) ||
                noun in groceryIntentNominalScopeUnsafeWords ||
                noun in setOf("both", "all", "any", "every", "either", "neither", "some", "none",
                    "very", "reported", "reportedly", "labelled", "labeled", "called",
                    "stated", "mentioned", "written", "according", "as") ||
                groceryIntentHasNominalClausePredicate(noun) || !hasProductIdentity(before)) return@mapNotNull null
            val required = intentEvidenceTokens(row.item.required.joinToString(" ").replace('_', ' '))
            val retained = if (pattern === property) {
                val tokens = intentEvidenceTokens(match.value)
                tokens.isNotEmpty() && required.containsAll(tokens)
            } else {
                fun normalize(value: String) = value.lowercase(Locale.ROOT).replace(Regex("[\\s_-]+"), " ").trim()
                normalize(match.groupValues[1]) in row.item.excluded.map(::normalize)
            }
            if (retained) (primary.startUtf16 + match.range.first)..(primary.startUtf16 + match.range.last) else null
        }.toList() }
    }
    primaries.forEach { (index, primary) ->
        val priorEnd = primaries.map { it.second.endUtf16 }.filter { it <= primary.startUtf16 }.maxOrNull() ?: 0
        val gap = instruction.substring(priorEnd, primary.startUtf16)
        val scopeGapStart = hardEnd.findAll(gap).lastOrNull()?.range?.last?.plus(1) ?: 0
        // Cropping the item quote must not discard a surrounding quantity,
        // history or timing relation. Verified context/group receipts survive.
        if (groceryIntentPurchaseScopeContextRanges(gap).any { range -> range.first >= scopeGapStart && scopedContext.none {
                it.first <= priorEnd + range.first && priorEnd + range.last <= it.last
            } }) affected += index
        val atomStart = groceryIntentTrailingNumericAtomStart(gap)
        if (atomStart != null && protectedSources.none {
            it.startUtf16 <= priorEnd + atomStart && primary.startUtf16 <= it.endUtf16
        }) affected += index
        val nextStart = primaries.map { it.second.startUtf16 }.filter { it >= primary.endUtf16 }.minOrNull() ?: instruction.length
        val suffix = instruction.substring(primary.endUtf16, nextStart)
        val localSuffix = suffix.substring(0, hardEnd.find(suffix)?.range?.first ?: suffix.length)
        val scopeSuffixEnd = Regex(",[ \\t]*(?:please[ \\t]+)?(?:get|buy|add|order|bring)[ \\t]+" +
            "(?:(?:weekly|monthly|daily)[ \\t]+)?(?:basics|essentials|groceries|supplies|" +
            "household[ \\t]+items|pantry[ \\t]+items)[ \\t]*[.!?]?[ \\t]*\\z", RegexOption.IGNORE_CASE)
            .find(localSuffix)?.range?.first ?: localSuffix.length
        if (groceryIntentPurchaseScopeContextRanges(localSuffix).any { range -> range.last < scopeSuffixEnd && scopedContext.none {
                it.first <= primary.endUtf16 + range.first && primary.endUtf16 + range.last <= it.last
            } }) affected += index
        val unownedSuffix = localSuffix.toCharArray()
        protectedSources.forEach { quote ->
            for (offset in maxOf(primary.endUtf16, quote.startUtf16) until minOf(primary.endUtf16 + localSuffix.length, quote.endUtf16))
                unownedSuffix[offset - primary.endUtf16] = ' '
        }
        imperativeDoSpans.forEach { span ->
            for (offset in maxOf(primary.endUtf16, span.startUtf16) until minOf(primary.endUtf16 + localSuffix.length, span.endUtf16))
                unownedSuffix[offset - primary.endUtf16] = ' '
        }
        // An entirely omitted numeric fragment is as unproven as a blocked one.
        if (groceryIntentContainsNumericAtom(String(unownedSuffix))) affected += index
    }
    val component = mutableListOf<Pair<Int, GroceryIntentReviewV2Quote>>()
    fun finish() {
        if (component.size < 2) return
        val first = component.first().second
        val tail = instruction.substring(first.endUtf16, component.maxOf { it.second.endUtf16 })
        val numericTail = tail.toCharArray()
        // Fully validated neighbour numbers/descriptors already have owners.
        // Rejected or uncovered numeric tails cannot be skipped independently.
        val contextOnly = protectedSources.filter { quote -> primaries.none { (_, primary) ->
            quote.startUtf16 < primary.endUtf16 && primary.startUtf16 < quote.endUtf16
        } }
        (readyRows.filter { it.rowIndex in locallyReady && it.rowIndex !in numericDependents }.map { it.evidence.primary } + contextOnly).forEach { quote ->
            for (offset in maxOf(first.endUtf16, quote.startUtf16) until minOf(first.endUtf16 + tail.length, quote.endUtf16)) {
                numericTail[offset - first.endUtf16] = ' '
            }
        }
        // 'low fat milk, eggs' retains its complete first product. A bare
        // property prefix or later unquantified fragment has no proven owner.
        if (propertyOnly(first.text) || amountOnly(first.text)) {
            affected += component.map { it.first }
            return
        }
        // Only retained nominal suffixes own this detail, never arbitrary
        // support/group text. The fixed point
        // revokes these spans when their independently checked owner is lost.
        val propertyRanges = readyRows.filter { it.rowIndex in locallyReady && it.rowIndex !in numericDependents }
            .flatMap(::ownedPropertySuffixes)
        val detailEnds = (property.findAll(tail) + negativeAttribute.findAll(tail)).filter { match ->
            propertyRanges.none { span -> span.first <= first.endUtf16 + match.range.first &&
                first.endUtf16 + match.range.last <= span.last }
        }.map { it.range.last + 1 }.toMutableList()
        detailEnds += component.filter { it.first in affected }.map { it.second.endUtf16 - first.endUtf16 }
        groceryIntentLastNumericAtomEnd(String(numericTail))?.let { detailEnds += it }
        detailEnds.maxOrNull()?.let { last ->
            affected += component.filter { it.second.startUtf16 < first.endUtf16 + last }.map { it.first }
        }
    }
    primaries.forEach { entry ->
        if (component.isNotEmpty()) {
            val prior = component.last().second
            val tailLength = requireNotNull(trailing.find(prior.text)).value.length
            val gap = if (entry.second.startUtf16 < prior.endUtf16) "" else
                instruction.substring(prior.endUtf16 - tailLength, entry.second.startUtf16)
            val hardBoundary = hardEnd.containsMatchIn(gap)
            val contentsTail = !hardBoundary && groceryIntentContainsNumericAtom(entry.second.text) && component.any { (_, quote) ->
                Regex("\\b(?:packs?|packets?|bottles?|boxes|box|pouches|pouch|jars?|cartons?|bags?|tubs?|tins?|cans?|sachets?)\\b", RegexOption.IGNORE_CASE).containsMatchIn(quote.text) &&
                    groceryIntentFragmentIdentityTokens(quote.text).containsAll(groceryIntentFragmentIdentityTokens(entry.second.text))
            }
            if (contentsTail) numericDependents += entry.first
            if (!hardBoundary && amountOnly(entry.second.text) && component.last().first !in quantified) {
                affected += component.last().first
                affected += entry.first
                if (gap.isBlank()) backwardCounts += entry.first
            }
            if (hardBoundary ||
                (component.last().first in backwardCounts && gap.trimStart().startsWith(",")) ||
                // Disjoint nouns do not prove a rejected numeric fragment is
                // another purchase. Only a validated local amount can do so.
                (!contentsTail && ((entry.first in quantified && hasProductIdentity(entry.second.text)) ||
                    amountOnly(entry.second.text)) &&
                    !propertyOnly(component.first().second.text) && !amountOnly(component.first().second.text)) ||
                protectedSources.any { quote -> prior.endUtf16 <= quote.startUtf16 &&
                    quote.startUtf16 <= entry.second.startUtf16 && entry.second.endUtf16 <= quote.endUtf16 } ||
                protectedSources.any { quote -> quote.startUtf16 <= component.first().second.startUtf16 &&
                    component.last().second.endUtf16 <= quote.endUtf16 && entry.second.startUtf16 !in quote.startUtf16 until quote.endUtf16 }) {
                finish()
                component.clear()
            }
        }
        component += entry
    }
    finish()
    // Shared context is not evidence that a property or contents tail is a
    // second product. Never exempt a whole group from dependency checking.
    return affected
}

private fun GroceryIntentReviewV2Quote.coverageSpan() = GroceryIntentCoverageSpan(startUtf16, endUtf16)

private fun GroceryIntentReviewV2Item.toMatchingItem(group: GroceryIntentReviewV2Group? = null) = GroceryIntentItem(
    name, quantity, quantityUnit, packValue, packUnit,
    (required + listOfNotNull(packaging, group?.packaging)).distinct(), excluded, sourceText, false,
)

private fun GroceryIntentReviewV2ReadyRow.correctedMatchingItem(group: GroceryIntentReviewV2Group? = null): GroceryIntentItem {
    val member = group?.members?.single { it.rowIndex == rowIndex }
    val base = item.toMatchingItem(group)
    val grouped = if (member?.origin == "each") {
        require(base.quantity == null && member.value != null)
        base.copy(quantity = member.value, quantityUnit = member.unit)
    } else base
    return corrections.fold(grouped) { current, correction ->
        // Base identity/dietary/pack evidence is checked BEFORE these overrides.
        // Product text is never merged. A complete single-pack fulfillment
        // assertion separately proves an exact size/container equal to the total.
        when (correction.kind) {
            "quantity" -> current.copy(quantity = correction.newValue, quantityUnit = correction.newUnit).let { corrected ->
                correction.singlePackSelection?.let { packaging -> corrected.copy(
                    packValue = correction.newValue, packUnit = correction.newUnit,
                    required = (corrected.required + packaging).distinct(),
                ) } ?: corrected
            }
            "pack_size" -> current.copy(packValue = correction.newValue, packUnit = correction.newUnit)
            else -> error("Unsupported correction")
        }
    }
}

private val physicalTotalFamilies = mapOf("g" to "weight", "kg" to "weight", "ml" to "volume", "l" to "volume")

/** Deny-only final-value check, independent of the server's amount-role label.
 * An unequal physical total/pack pair would derive several packs (or a fraction).
 * Only an ORIGINAL local total marker proves that interpretation. Groups and
 * corrections cannot invent the role; equal canonical measures remain one pack.
 */
private fun GroceryIntentReviewV2ReadyRow.physicalTotalPackIssue(final: GroceryIntentItem): String? {
    val quantity = final.quantity ?: return null
    val pack = final.packValue ?: return null
    val family = physicalTotalFamilies[final.quantityUnit] ?: return null
    if (physicalTotalFamilies[final.packUnit] != family) return null
    fun canonical(value: BigDecimal, unit: String) =
        value.multiply(if (unit in setOf("kg", "l")) BigDecimal("1000") else BigDecimal.ONE)
    if (canonical(quantity, final.quantityUnit).compareTo(canonical(pack, final.packUnit)) == 0) return null
    return if (physicalTotalFamilies[item.quantityUnit] == family && physicalTotalMarkerRanges().isNotEmpty()) null
        else "physical_total_pack_ambiguous"
}

/** Original offsets around a separately bound quantity, never masked/model text. */
private fun GroceryIntentReviewV2ReadyRow.physicalTotalMarkerRanges(): List<IntRange> {
    val local = localQuantityEvidence() ?: return emptyList()
    if (local.unit !in physicalTotalFamilies) return emptyList()
    val before = local.source.substring(0, local.startUtf16)
    val after = local.source.substring(local.endUtf16)
    val leading = Regex("^\\s*-?\\s*").find(after)?.value?.length ?: return emptyList()
    val unit = physicalUnitTokenFollowsAt(after, leading, local.unit) ?: return emptyList()
    val suffixStart = local.endUtf16 + leading + unit.length
    // A competing per-container or nutrition grammar makes the role unknown,
    // even if the same amount also has a total marker. Do not choose a reading.
    val containers = "(?:packs?|packets?|bags?|bottles?|cartons?|cups?|cans?|tins?|tubs?|jars?|box(?:es)?|bars?|blocks?|pouch(?:es)?|sachets?|tubes?|loaf|loaves|refills?|rolls?|sprays?|bunch(?:es)?)"
    val suffixText = local.source.substring(suffixStart)
    if (Regex("^\\s*(?:(?:ka|ki|ke|wala|wali|wale|tetra)\\s+)?$containers\\b", RegexOption.IGNORE_CASE).containsMatchIn(suffixText) ||
        Regex("\\b$containers\\s+of\\s*$", RegexOption.IGNORE_CASE).containsMatchIn(before) ||
        Regex("^\\s*(?:protein|fib(?:re|er)|carbs?|carbohydrates?|fat|sugar|sodium|calcium|calories?|kcal)\\b\\s+per\\s+(?:serving|portion|100\\s*(?:g|grams?|ml))\\b", RegexOption.IGNORE_CASE).containsMatchIn(suffixText)) return emptyList()
    val word = "[\\p{L}\\p{N}_\\p{M}]"
    val prefix = Regex("(?<!$word)(?:total|altogether|kul)\\s*$", RegexOption.IGNORE_CASE).find(before)?.range
    if (prefix != null) return listOf(prefix)
    val suffix = Regex("^\\s*(?:in\\s+total(?!$word)|(?:total|altogether|kul)(?=\\s*(?:[,;.!?]|$)))", RegexOption.IGNORE_CASE)
        .find(local.source.substring(suffixStart))?.range?.let { (suffixStart + it.first)..(suffixStart + it.last) }
    return listOfNotNull(prefix, suffix)
}

/** Category proof may ignore only amounts already independently bound to this row. */
private fun GroceryIntentReviewV2ReadyRow.categoryIdentitySource(group: GroceryIntentReviewV2Group?): String =
    if (group != null && numericOwnershipIssue() == null && quantityOmissionIssue() == null)
        identitySourceWithoutAmounts(boundAtomsOnly = true) else evidence.primary.text

/** Coarse offline diagnostics only: never contains item text or model output. */
internal fun GroceryIntentReviewV2ReadyRow.groundingIssue(
    group: GroceryIntentReviewV2Group? = null,
    allPrimarySources: List<Pair<Int, String>>? = null,
    instruction: String? = null,
    properties: List<GroceryIntentPropertyObligation> = emptyList(),
    sourceContext: List<GroceryIntentCoverageSpan> = emptyList(),
    sourceBoundaries: List<GroceryIntentReviewV2Quote> = emptyList(),
    nominalEdgeRows: MutableSet<Int>? = null,
    enforceSourceTotalRole: Boolean = false,
    allowTypedIngredientPredicates: Boolean = false,
): String? {
    if (groceryIntentHasUncompiledNativeSourceScope(evidence.primary.text) ||
        evidence.supporting.any { groceryIntentHasUncompiledNativeSourceScope(it.quote.text) }) {
        return "item_contains_correction_or_alternative"
    }
    if (group != null && corrections.any { it.singlePackSelection != null }) {
        return "correction_pack_selection_with_group_unsupported"
    }
    if (properties.any { rowIndex !in it.memberIndices }) return "property_scope_mismatch"
    val provenIngredientExclusions = try {
        requireIngredientPredicateProjection(properties.flatMap { it.ingredientPredicates }, item.excluded,
            allowTypedIngredientPredicates)
    } catch (_: IllegalArgumentException) {
        return "ingredient_predicate_projection_unproven"
    }
    val provenRequired = properties.flatMap { it.required }.flatMap { intentV2PropertyTokens(groceryIntentPropertyPhrase(it)) }.toSet()
    val provenExcluded = (properties.flatMap { it.excluded }.map(::groceryIntentPropertyPhrase) + provenIngredientExclusions)
        .flatMap(::intentV2PropertyTokens).toSet()
    val item = item.toMatchingItem(group)
    val requestIdentity = group?.contextualFromRequestIdentity(instruction, allPrimarySources)
        ?: group?.contextualAffirmedHeadingIdentity(instruction, allPrimarySources)
    val semanticHeader = requestIdentity ?: group?.semanticHeader(instruction, allPrimarySources)
    val categoryHeader = semanticHeader.takeIf { group?.postfixIdentity() == null }
    if (group != null && categoryHeader != null) {
        groceryIntentGroupCategoryIssue(categoryHeader, categoryIdentitySource(group),
            item.name, item.required + listOfNotNull(item.quantityUnit.takeIf { item.quantity != null }),
            localQuantities = group.hasEligibleLocalQuantityHeading())?.let { return it }
    }
    // A proved category heading contributes only its universal identity prefix.
    // A sibling's category must never become positive evidence for this member.
    val fragments = listOf(evidence.primary.text) + evidence.supporting.map { it.quote.text } +
        listOfNotNull(group?.takeIf { it.header != null && semanticHeader != null }?.let {
            it.postfixIdentity() ?: groceryIntentGroupCategoryGroundingHeader(
                requireNotNull(semanticHeader), evidence.primary.text, localQuantities = it.hasEligibleLocalQuantityHeading(),
            )
        })
    if (fragments.any(::hasQualifiedV2NegativeIngredient)) return "unproven_negative_ingredient"
    if (fragments.any(::hasUnprovenV2PropertyNegation)) return "unproven_property_negation"
    val countEmphasis = if (group == null) terminalCountModifierRange(instruction, sourceBoundaries, sourceContext) else null
    val additivePrefix = if (group == null) additiveCountPrefixRange(instruction, sourceBoundaries, sourceContext) else null
    if (fragments.withIndex().any { (index, fragment) ->
            val cueText = fragment.toCharArray()
            if (index == 0) listOfNotNull(countEmphasis, additivePrefix).forEach { range ->
                for (position in range) cueText[position] = ' '
            }
            intentV2HasCorrectionCue(String(cueText), allowLocalAvailability = index == 0 &&
                group == null && evidence.supporting.isEmpty() && corrections.isEmpty() &&
                localAvailabilityPrimaryIsComplete(evidence.primary, instruction))
        }) return "item_contains_correction_or_alternative"
    val fragmentTokens = fragments.map(::intentV2PropertyTokens)
    val source = fragmentTokens.flatten().toSet() + provenRequired
    val proposed = intentV2PropertyTokens(item.name + " " + item.required.joinToString(" ") { it.replace('_', ' ') })
    // Negated variants are exclusions, not positive requirements. Derive both
    // polarities per exact fragment; joining separate quotes must not invent one.
    val contrasts = fragments.map(::intentV2NamedPropertyContrast)
    contrasts.forEach { contrast ->
        if (contrast != null) {
            if (!substitutionTargetIsProven(contrast.target, item.sourceText) ||
                allPrimarySources?.let { !substitutionTargetIsUnique(contrast.target, rowIndex, it) } == true
            ) return "property_contrast_target_unproven"
            if (!proposed.containsAll(contrast.positive)) return "missing_protected_property"
        }
    }
    val negativeProperties = fragments.flatMapIndexed { index, fragment ->
        if (intentV2ExcludedOneMatch(fragment) != null &&
            intentV2RetainedExcludedOneMatch(fragment, item.name, item.required, item.excluded) == null)
            return "missing_or_conflicting_excluded_variant"
        val found = (excludedAttributeVariant.findAll(fragment).toList() + negativeSubstitution.findAll(fragment).toList() +
            listOfNotNull(intentV2ExcludedOneMatch(fragment)))
            .flatMap { intentV2PropertyTokens(it.groupValues[1]) }
        found + contrasts[index]?.negative.orEmpty()
    }.toSet() + provenExcluded
    val positiveFragments = fragments.mapIndexed { index, fragment ->
        val contrast = contrasts[index]
        if (contrast != null) contrast.target + " " + contrast.positiveText
        else stripIntentV2ExcludedOne(negativeSubstitution.replace(excludedAttributeVariant.replace(fragment, " "), " "))
    }
    val positiveSource = positiveFragments.flatMap(::intentV2PropertyTokens).toSet() + provenRequired
    val preparedFood = fragmentTokens.any { tokens ->
        "readymade" in tokens && tokens.none { it in setOf("batter", "dough", "mix", "premix", "flour", "paste") }
    }
    val categoryAliases = group?.takeIf { categoryHeader != null }?.let {
        groceryIntentGroupCategoryAliases(
            requireNotNull(semanticHeader), evidence.primary.text, localQuantities = it.hasEligibleLocalQuantityHeading(),
        ).flatMap(::intentV2PropertyTokens).toSet()
    }.orEmpty()
    // Lexicalized forms may prove a container only for the same independently
    // named product. Never borrow this from a sibling/support quote or translate
    // a generic word such as "tikki" into a packaging requirement.
    val packagingAliases = groceryIntentPackagingEvidence(evidence.primary.text, item.name, this.item.packaging)
    // semanticHeader intentionally removes the variety count. Preserve only its
    // independently proved literal descriptor for names, never that count or a
    // sibling identity, and never treat it as new dietary/quantity evidence.
    val varietyDescriptor = group?.variantCardinalityDescriptorWords()?.flatMap(::intentV2PropertyTokens).orEmpty()
    // Use original fragments: masking a negated variant must not join a claim.
    val absenceEquivalents = fragments.flatMap(::intentV2IngredientPropertyEquivalents).toSet()
    val equivalent = (if (preparedFood) setOf("readytoeat") else emptySet()) + categoryAliases + packagingAliases + varietyDescriptor + absenceEquivalents
    val exclusions = item.excluded.flatMap { intentV2PropertyTokens(it.replace('_', ' ')) }.toSet()
    val excludedIngredients = item.excluded.map(::intentV2IngredientPhrase).toSet()
    // Property normalization may consume the literal ingredient tokens (e.g.
    // zero sugar -> sugarfree). An exact, same-fragment negative ingredient
    // claim still proves that WHOLE exclusion; never manufacture it by joining
    // fragments or infer sugar-free from the weaker no-added-sugar claim.
    val explicitlyExcludedIngredients = fragments.flatMap { fragment ->
        negativeAttribute.findAll(fragment).map { intentV2IngredientPhrase(it.groupValues[1]) }.toList()
    }.toSet()
    val literalPositive = intentV2PropertyTokens(negativeAttribute.replace(
        item.name + " " + item.required.joinToString(" ") { it.replace('_', ' ') }, " "))
    for (fragment in fragments) {
        for (match in negativeAttribute.findAll(fragment)) {
            val ingredient = intentV2IngredientPhrase(match.groupValues[1])
            val tokens = intentV2PropertyTokens(ingredient)
            val propertyEquivalent = equivalentExclusionProperties[ingredient]
            if (propertyEquivalent !in proposed && ingredient !in excludedIngredients) return "missing_negative_ingredient"
            if (propertyEquivalent !in proposed && literalPositive.containsAll(tokens)) return "conflicting_negative_ingredient"
        }
    }
    if (!(source + equivalent).containsAll(proposed)) return "unproven_name_or_modifier"
    if (item.excluded.any { exclusion ->
            intentV2IngredientPhrase(exclusion) !in explicitlyExcludedIngredients &&
                !(source + provenExcluded).containsAll(intentV2PropertyTokens(exclusion.replace('_', ' ')))
        }) return "unproven_exclusion"
    if (hasUncertainV2PropertyAssertions(fragments)) return "unproven_property_assertion"
    val protected = setOf(
        "sugarfree", "noaddedsugar", "lowfat", "tetrapack", "readytoeat", "readytodrink", "readytocook",
        "organic", "certified", "unsalted", "plain", "highfibre", "highprotein", "lowcalorie", "lowsugar",
        "glutenfree", "lactosefree", "vegan", "vegetarian", "unsweetened", "sweetened",
    )
    fragments.forEach { fragment ->
        for (match in negativeSubstitution.findAll(fragment)) {
            val prefix = fragment.substring(0, match.range.first)
            val prefixProof = namedPositivePropertyPrefix.matchEntire(prefix)
            val prefixValid = prefix.isBlank() || (prefixProof != null &&
                substitutionTargetIsProven(prefixProof.groupValues[1].trim(), item.sourceText) &&
                proposed.containsAll(intentV2PropertyTokens(prefixProof.groupValues[2])))
            if (!prefixValid ||
                !Regex("[\\s.;!?]*").matches(fragment.substring(match.range.last + 1))
            ) return "negative_substitution_scope_unproven"
            val target = match.groupValues[2].trim()
            if (!substitutionTargetIsProven(target, item.sourceText)) return "negative_substitution_target_unproven"
            val opposite = when (intentV2PropertyTokens(match.groupValues[1]).singleOrNull()) {
                "sweetened" -> "unsweetened"
                "unsweetened" -> "sweetened"
                else -> null
            }
            if (opposite == null || opposite !in intentV2PropertyTokens(item.sourceText)) {
                return "negative_substitution_polarity_ambiguous"
            }
        }
    }
    if ((negativeProperties.intersect(positiveSource + proposed)).isNotEmpty()) return "conflicting_property_polarity"
    if (hasConflictingV2PropertyAssertions(item.name + " " + item.required.joinToString(" "))) {
        return "conflicting_property_requirements"
    }
    if ((negativeProperties - exclusions).isNotEmpty()) return "missing_protected_exclusion"
    if ((exclusions.intersect(protected) - negativeProperties).isNotEmpty()) return "unproven_protected_exclusion"
    // An exact owned absence may be retained as an exclusion instead of a
    // duplicated positive tag. Keep no-added-sugar distinct from sugar-free;
    // an unmentioned or dropped ingredient must never satisfy this check.
    val retainedIngredientProperties = fragments.flatMap { fragment ->
        negativeAttribute.findAll(fragment)
            .filter { intentV2IngredientPhrase(it.groupValues[1]) in excludedIngredients }
            .flatMap { intentV2IngredientPropertyEquivalents(it.value).asSequence() }.toList()
    }.toSet()
    if ((positiveSource.intersect(protected) - proposed - retainedIngredientProperties).isNotEmpty()) return "missing_protected_property"
    if ((proposed.intersect(protected) - source - equivalent).isNotEmpty()) return "added_protected_property"
    if (preparedFood && proposed.none { it in setOf("readymade", "readytoeat") }) return "missing_prepared_food_form"
    // Proven negative variants (e.g. no tetra-pack substitutes) are exclusions,
    // not requested containers. Earlier polarity/exclusion checks still apply.
    if (groceryIntentStatedPackagingMissing(positiveFragments, proposed)) return "stated_packaging_missing"
    if (group != null && !groupHeaderPreserved(group, item, requestIdentity, semanticHeader)) return "missing_shared_identity_or_exclusion"
    if (groceryIntentPackEvidenceConflict(item)) return "pack_evidence_conflict"
    quantityOmissionIssue()?.let { return it }
    numericOwnershipIssue(group?.header?.text.orEmpty())?.let { return it }
    nativeMeasuredIdentityIssue(group)?.let { return it }
    // The server is the sole producer of source-owned role normalization.
    // Independently deny a stale/forged pack-only interpretation; never repair
    // it here or infer a pack count. Bare sizes/capacities remain unchanged.
    if (enforceSourceTotalRole && group == null && corrections.isEmpty() &&
        this.item.quantity == null && this.item.packaging == null &&
        localPackSizeEvidence()?.let(::isSourcePhysicalTotal) == true) {
        return "physical_total_as_pack"
    }
    retailPackEvidenceIssue(group, instruction)?.let { return it }
    sourceIdentityIssue(item, proposed, preparedFood, packagingAliases, instruction, group != null,
        sourceBoundaries, sourceContext, nominalEdgeRows)?.let { return it }
    val final = correctedMatchingItem(group)
    return discretePackRoleIssue(final) ?: physicalTotalPackIssue(final) ?: physicalUnitSizeIssue(final, group)
}

/** Native unit projection cannot certify the polarity of arbitrary copied text.
 * Keep unknown affirmative nouns reviewable until independently typed or resolved.
 * ASCII units retain the existing path; original source/offsets are never rewritten. */
private fun GroceryIntentReviewV2ReadyRow.nativeMeasuredIdentityIssue(group: GroceryIntentReviewV2Group?): String? {
    val source = evidence.primary.text
    val amounts = listOfNotNull(localQuantityEvidence(), localPackSizeEvidence())
    val nativeUnit = amounts.any { amount ->
        GroceryIntentPhysicalUnitMatcher.after(source, amount.endUtf16, amount.unit)
            ?.token?.any { it.code in 0x0900..0x097f } == true
    }
    if (!nativeUnit) return null
    if (localQuantityEvidence() == null || item.packValue != null || item.packText != null ||
        item.packUnit != "unspecified" || group != null || corrections.isNotEmpty() || evidence.supporting.isNotEmpty() ||
        item.supportingText.isNotEmpty() || item.required.isNotEmpty() || item.excluded.isNotEmpty() ||
        item.packaging != null) return "missing_source_identity"
    val quantity = requireNotNull(localQuantityEvidence())
    val unit = GroceryIntentPhysicalUnitMatcher.after(source, quantity.endUtf16, quantity.unit)
        ?: return "missing_source_identity"
    // Never join words from opposite sides of a removed measure into a noun.
    val before = source.substring(0, quantity.startUtf16)
    val after = source.substring(unit.end)
    if (before.any { it != ' ' && it != '\t' } && after.any { it != ' ' && it != '\t' }) {
        return "missing_source_identity"
    }
    val sourceNoun = ProductLexicon.completeIdentityNoun(identitySourceWithoutAmounts(boundAtomsOnly = true))
        ?: return "missing_source_identity"
    return if (ProductLexicon.completeIdentityNoun(item.name) == sourceNoun) null else "missing_source_identity"
}

/** Reverse grounding: proving every model word is sourced is not enough if a
 * brand/flavour/form from the source can disappear. Exclusions are deliberately
 * NOT positive identity evidence. Only independently proved negative clauses
 * and source-bound amounts can be removed before checking retained wording.
 */
private fun GroceryIntentReviewV2ReadyRow.sourceIdentityIssue(
    matchingItem: GroceryIntentItem,
    proposed: Set<String>,
    preparedFood: Boolean,
    packagingAliases: Set<String>,
    instruction: String?,
    inGroup: Boolean,
    sourceBoundaries: List<GroceryIntentReviewV2Quote>,
    sourceContext: List<GroceryIntentCoverageSpan>,
    nominalEdgeRows: MutableSet<Int>?,
): String? {
    // Check ORIGINAL fragments before masking amounts/scaffolding or dropping
    // grammatical `of`; a copied model name cannot complete a dangling phrase.
    if (instruction != null && intentHasDescriptiveOneIngredientTail(evidence.primary.text,
            matchingItem.name, matchingItem.required, matchingItem.excluded)) {
        if (!originalNominalEdgesComplete(evidence.primary, instruction, sourceBoundaries, sourceContext))
            return "numeric_descriptor_scope_unproven"
        // Recheck after filtering neighbours: a rejected row cannot supply an edge.
        nominalEdgeRows?.add(rowIndex)
    }
    val unfinished = Regex("(?<![\\p{L}\\p{N}_])(?:of|with|containing|contains)[^\\p{L}\\p{N}_]*\\z", RegexOption.IGNORE_CASE)
    for ((index, original) in (listOf(evidence.primary.text) + evidence.supporting.map { it.quote.text }).withIndex()) {
        val requestOnly = original.toCharArray()
        val requestRanges = sourceIdentityRequestRanges(original) +
            if (index == 0) terminalPositiveRequestRanges(evidence.primary, instruction) else emptyList()
        requestRanges.forEach { range -> for (position in range) requestOnly[position] = ' ' }
        // Deny-only courtesy projection; never erase amounts or product facts.
        if (unfinished.containsMatchIn(original) || unfinished.containsMatchIn(String(requestOnly)))
            return "incomplete_source_relation"
    }
    val totalMarkers = physicalTotalMarkerRanges()
    val withoutMarkers = evidence.primary.text.toCharArray()
    totalMarkers.forEach { range -> for (position in range) withoutMarkers[position] = ' ' }
    val markerWords = totalMarkers.flatMap { intentV2PropertyTokens(evidence.primary.text.substring(it)) }.toSet()
    if ((markerWords - intentV2PropertyTokens(String(withoutMarkers))).intersect(proposed).isNotEmpty()) {
        return "amount_role_name_reused"
    }
    val fragments = listOf(evidence.primary.text to identitySourceWithoutAmounts()) +
        evidence.supporting.map { it.quote.text to it.quote.text }
    val localGrammar = localIdentityGrammarRanges(proposed)
    val countEmphasis = if (!inGroup) terminalCountModifierRange(instruction, sourceBoundaries, sourceContext) else null
    val additivePrefix = if (!inGroup) additiveCountPrefixRange(instruction, sourceBoundaries, sourceContext) else null
    if (additivePrefix != null && "another" in proposed) return "quantity_operation_name_reused"
    if (countEmphasis != null) nominalEdgeRows?.add(rowIndex)
    val namedAlias = if (!inGroup && evidence.supporting.isEmpty() && corrections.isEmpty())
        metalinguisticAliasRange(proposed, instruction, sourceBoundaries, sourceContext) else null
    // A literal copy of an unproved naming claim is not identity evidence.
    val namingClaim = Regex("\\bjisko[^\\r\\n]*\\bbolte[ \\t]+hain\\b|\\balso[ \\t]+known[ \\t]+as\\b", RegexOption.IGNORE_CASE)
    if (namedAlias == null && fragments.any { namingClaim.containsMatchIn(it.first) })
        return "metalinguistic_alias_unproven"
    if (namedAlias != null) nominalEdgeRows?.add(rowIndex)
    val genitive = if (!inGroup && evidence.supporting.isEmpty() && corrections.isEmpty())
        retailNameGenitiveRanges(instruction, sourceBoundaries, sourceContext) else emptyList()
    if (genitive.any { range -> localGrammar.none { it.first <= range.first && range.last <= it.last } }) {
        nominalEdgeRows?.add(rowIndex)
    }
    for ((index, pair) in fragments.withIndex()) {
        val (original, amountMasked) = pair
        val scaffoldMasked = amountMasked.toCharArray()
        // Find scaffolding on the ORIGINAL text. Masking 'one' in 'one Add gel
        // pen' must not turn the brand Add into a leading request verb.
        val grammarRanges = if (index == 0) {
            totalMarkers + localGrammar + listOfNotNull(countEmphasis, additivePrefix, namedAlias) +
                (if (!inGroup && evidence.supporting.isEmpty() && corrections.isEmpty() &&
                    localAvailabilityPrimaryIsComplete(evidence.primary, instruction))
                    intentV2LocalAvailabilityGrammarRanges(original) else emptyList()) + if (!inGroup) {
                listOfNotNull(genericContainerRange()) +
                    (if (evidence.supporting.isEmpty() && corrections.isEmpty())
                        terminalPositiveRequestRanges(evidence.primary, instruction) +
                            genitive else emptyList()) +
                terminalRetainedDescriptorParticleRanges(original, proposed, localQuantityEvidence() != null, instruction) +
                    packSizeGenitiveRanges(instruction) + terminalBoundParticleRanges(proposed, instruction)
            } else emptyList()
        } else emptyList()
        (sourceIdentityRequestRanges(original) + grammarRanges +
            terminalNegativeParticleRanges(original, matchingItem, instruction) +
            listOfNotNull(intentV2RetainedExcludedOneMatch(original, matchingItem.name,
                matchingItem.required, matchingItem.excluded)?.range)).forEach { range ->
            for (position in range) scaffoldMasked[position] = ' '
        }
        val fragment = String(scaffoldMasked)
        val contrast = intentV2NamedPropertyContrast(fragment)
        var positive = if (contrast != null) contrast.target + " " + contrast.positiveText else fragment
        val substitution = negativeSubstitution.find(positive)
        val namedPrefix = substitution?.let { namedPositivePropertyPrefix.matchEntire(positive.substring(0, it.range.first)) }
        if (namedPrefix != null) {
            // The earlier polarity check proved this complete named wrapper.
            positive = namedPrefix.groupValues[1] + " " + namedPrefix.groupValues[2]
        } else positive = negativeSubstitution.replace(positive, " ")
        val variant = excludedAttributeVariant.find(positive)
        val leadingVariant = variant != null && positive.substring(0, variant.range.first).isBlank()
        positive = excludedAttributeVariant.replace(positive, " ")
        if (leadingVariant) positive = Regex("^\\s*for\\s+(?:the\\s+)?", RegexOption.IGNORE_CASE).replaceFirst(positive, " ")
        val remaining = positive.toCharArray()
        intentV2ProvenIngredientRanges(positive, matchingItem.name, matchingItem.required, matchingItem.excluded)
            .forEach { range ->
                // 'with' belongs to this exact, proved negative ingredient
                // phrase; never remove it elsewhere or across quoted fragments.
                val withPrefix = Regex("\\bwith\\s+$", RegexOption.IGNORE_CASE)
                    .find(positive.substring(0, range.first))
                for (position in (withPrefix?.range?.first ?: range.first)..range.last) remaining[position] = ' '
            }
        positive = String(remaining)
        positive = Regex("\\b(spray)\\s+for\\s+(cockroaches?|mosquito(?:es)?|ants?|flies|insects?|spiders?|termites?)\\b", RegexOption.IGNORE_CASE)
            .replace(positive, "$1 $2")
        // The existing alias proof requires the entire original primary to be
        // this exact lexicalized product/container, e.g. '3 sabun ki tikki'.
        val identity = if (index == 0 && packagingAliases.isNotEmpty()) {
            intentV2PropertyTokens(matchingItem.name + " " + item.packaging)
        } else intentV2PropertyTokens(positive)
        val equivalent = if (preparedFood && "readytoeat" in proposed) setOf("readymade") else emptySet()
        if (!(proposed + equivalent).containsAll(identity)) return "missing_source_identity"
        val excluded = matchingItem.excluded.flatMap { intentV2PropertyTokens(it.replace('_', ' ')) }.toSet()
        if (identity.intersect(excluded).isNotEmpty()) return "conflicting_source_identity_exclusion"
    }
    return null
}

/** Original offsets only: masking another word must not manufacture a terminal
 * Hindi particle or hide a later product/qualifier. Polarity is proved first.
 */
private fun terminalNegativeParticleRanges(original: String, item: GroceryIntentItem, instruction: String?): List<IntRange> {
    if (instruction == null) return emptyList()
    val quoteStart = instruction.indexOf(original)
    if (quoteStart < 0 || quoteStart != instruction.lastIndexOf(original)) return emptyList()
    return intentV2ProvenIngredientRanges(original, item.name, item.required, item.excluded).mapNotNull { claim ->
        val end = claim.last + 1
        Regex("^[ \\t]+([wW][aA][lL][aAiIeE])(?=[ \\t]*[,.!?]?[ \\t]*\\z)")
            .find(original.substring(end))?.groups?.get(1)?.range?.let { range ->
                val after = instruction.substring(quoteStart + end + range.last + 1)
                if (Regex("^[ \\t]*(?:[,.!?;]|\\z)").containsMatchIn(after))
                    (end + range.first)..(end + range.last) else null
            }
    }
}

/** Keep the complete descriptor; only its terminal Hindi particle is grammar.
 * Original quote/clause boundaries prevent borrowing a following product.
 */
private fun terminalRetainedDescriptorParticleRanges(
    original: String, proposed: Set<String>, hasQuantity: Boolean, instruction: String?,
): List<IntRange> {
    if (!hasQuantity || instruction == null || '\n' in original || '\r' in original ||
        intentV2HasCorrectionCue(original)) return emptyList()
    val quoteStart = instruction.indexOf(original)
    if (quoteStart < 0 || quoteStart != instruction.lastIndexOf(original)) return emptyList()
    // Compose only complete property clauses on ORIGINAL source spans. They
    // remain visible to the independent polarity/identity validators; this
    // changes which descriptor is inspected, not the evidence or its meaning.
    var descriptorEnd = original.length
    while (true) {
        val commaAt = original.lastIndexOf(',', descriptorEnd - 1)
        if (commaAt < 0) break
        val finalPunctuation = if (descriptorEnd == original.length) "[.!?]?" else ""
        val suffix = Regex("[ \\t]*([^,;.!?\\r\\n]+?)[ \\t]*$finalPunctuation[ \\t]*")
            .matchEntire(original.substring(commaAt + 1, descriptorEnd)) ?: break
        val property = suffix.groupValues[1]
        if (property.any { it.code > 127 } || !groceryIntentIsClosedNominalProperty(property)) break
        descriptorEnd = commaAt
    }
    if (descriptorEnd < original.length && !Regex("^[ \\t]*(?:[,;!?\\r\\n]|\\.(?=[ \\t\\r\\n]|\\z)|\\z)")
            .containsMatchIn(instruction.substring(quoteStart + original.length))) return emptyList()
    val comma = original.lastIndexOf(',', descriptorEnd - 1)
    if (comma < 0) return emptyList()
    val tail = original.substring(comma + 1, descriptorEnd)
    val match = Regex("[ \\t]*([A-Za-z]+(?:[ \\t-]+[A-Za-z]+){0,3})[ \\t]+" +
        "(wala|wali|wale)[ \\t]*[.!?]?[ \\t]*\\z").matchEntire(tail) ?: return emptyList()
    if (descriptorEnd < original.length && Regex("\\b(?:wala|wali|wale)\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(original.substring(0, comma))) return emptyList()
    val words = Regex("[A-Za-z]+").findAll(match.groupValues[1]).map { it.value.lowercase(Locale.ROOT) }.toSet()
    val references = setOf("no", "not", "nahi", "nahin", "bina", "without", "or", "ya", "woh", "vo", "yeh", "ye", "wahi",
        "same", "usual", "last", "previous", "pichli", "pichla", "pichhle", "doosra", "dusra", "dusri", "other",
        "this", "that", "these", "those", "almost", "nearly", "maybe", "perhaps", "possibly", "potentially",
        "apparently", "roughly", "approximately", "around", "supposedly", "allegedly", "sometimes",
        "partially", "mostly", "somewhat", "kinda")
    val descriptor = intentV2PropertyTokens(match.groupValues[1])
    if (words.intersect(references).isNotEmpty() || groceryIntentContainsNumericAtom(match.groupValues[1]) ||
        Regex("\\b(?:g|gm|gms|grams?|kg|kgs|kilos?|kilograms?|keji|ml|millilitres?|milliliters?|" +
            "l|ltr|ltrs|litres?|liters?|small|medium|large|big|chh?ot[aei]|bad[aei])\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(match.groupValues[1]) ||
        Regex("\\b(?:kind|sort)[ \\t-]+of\\b", RegexOption.IGNORE_CASE).containsMatchIn(match.groupValues[1]) ||
        descriptor.isEmpty() || !proposed.containsAll(descriptor)) return emptyList()
    val particle = requireNotNull(match.groups[2]).range
    val start = comma + 1 + particle.first
    val end = comma + 1 + particle.last + 1
    if (!Regex("^[ \\t]*(?:[,;!?]|\\.(?=[ \\t\\r\\n]|\\z)|\\z)")
            .containsMatchIn(instruction.substring(quoteStart + end))) return emptyList()
    return listOf(start until end)
}

private fun sourceIdentityRequestRanges(original: String): List<IntRange> {
    val prefix = Regex("^\\s*(?:please\\s+)?(?:(?:can\\s+(?:you|we|i)\\s+)?(?:get|buy|order|add))\\s+", RegexOption.IGNORE_CASE)
        .find(original)?.range
    val suffix = Regex("(?:\\bplease|\\b(?:la|de|daal|kar|bhej|mangwa)\\s+do|\\bke\\s+saath)\\s*[.!?]*$", RegexOption.IGNORE_CASE)
        .find(original)?.range
    return listOfNotNull(prefix, suffix)
}

/** Closed positive request/additive ending on the ORIGINAL complete clause.
 * Never discard arbitrary words or infer a quantity from conversational grammar. */
private fun terminalPositiveRequestRanges(primary: GroceryIntentReviewV2Quote, instruction: String?): List<IntRange> {
    val source = primary.text
    if (instruction == null || source.any { it.code > 127 || it.code < 32 && it != '\t' } ||
        intentV2HasCorrectionCue(source)) return emptyList()
    val suffix = Regex("[ \\t]+((?:bhi[ \\t]+)?(?:chahiye|chahie|chaiye)(?:[ \\t]+(?:ji|please))?|bhi)" +
        "[ \\t]*[.!?]?[ \\t]*\\z", RegexOption.IGNORE_CASE).find(source) ?: return emptyList()
    val start = instruction.indexOf(source)
    if (start < 0 || start != instruction.lastIndexOf(source) || start != primary.startUtf16 ||
        start + source.length != primary.endUtf16) return emptyList()
    val before = instruction.substring(0, start)
    if (!Regex("(?:\\A|[,;:.!?\\r\\n])[ \\t]*(?:(?:and|aur|then|also|phir|plus)[ \\t]+)?\\z", RegexOption.IGNORE_CASE)
            .containsMatchIn(before)) return emptyList()
    val after = instruction.substring(primary.endUtf16)
    if (source.trimEnd(' ', '\t').last() in ".!?") {
        if (after.isNotEmpty() && after.first() !in " \t\r\n,;") return emptyList()
    } else if (!Regex("^[ \\t]*(?:[,;!?]|\\.(?=[ \\t\\r\\n]|\\z)|\\z)").containsMatchIn(after)) return emptyList()
    return listOf(requireNotNull(suffix.groups[1]).range)
}

/** Original source edges, not punctuation alone, separate a product from qualifiers. */
private fun originalNominalEdgesComplete(
    primary: GroceryIntentReviewV2Quote, instruction: String,
    sourceBoundaries: List<GroceryIntentReviewV2Quote>, context: List<GroceryIntentCoverageSpan>,
): Boolean {
    val quoteStart = instruction.indexOf(primary.text)
    if (quoteStart < 0 || quoteStart != instruction.lastIndexOf(primary.text) ||
        quoteStart != primary.startUtf16 || quoteStart + primary.text.length != primary.endUtf16) return false
    val sources = sourceBoundaries.ifEmpty { listOf(primary) }
    if (sources.any { it.startUtf16 < 0 || it.startUtf16 >= it.endUtf16 || it.endUtf16 > instruction.length ||
            instruction.substring(it.startUtf16, it.endUtf16) != it.text }) return false
    var left = sources.map { it.endUtf16 }.filter { it <= primary.startUtf16 }.maxOrNull() ?: 0
    var right = sources.map { it.startUtf16 }.filter { it >= primary.endUtf16 }.minOrNull() ?: instruction.length
    val separator = Regex("[,;:!?\\r\\n]|\\.(?=[ \\t\\r\\n]|$)|\\b(?:and|aur|then|also|phir|plus)\\b", RegexOption.IGNORE_CASE)
    val leftTerminal = sources.any { it.endUtf16 == left && it.text.trimEnd(' ', '\t').last() in ".;!?\n" }
    val terminal = primary.text.trimEnd(' ', '\t').last() in ".!?"
    if ((left > 0 && !leftTerminal && !separator.containsMatchIn(instruction.substring(left, primary.startUtf16))) ||
        (right < instruction.length && !terminal && !separator.containsMatchIn(instruction.substring(primary.endUtf16, right)))) return false
    val hard = Regex("[;!?\\r\\n]|\\.(?=[ \\t\\r\\n]|$)")
    hard.findAll(instruction.substring(left, primary.startUtf16)).lastOrNull()?.let { left += it.range.last + 1 }
    val after = instruction.substring(primary.endUtf16, right)
    hard.find(after)?.let { right = primary.endUtf16 + it.range.first }
    if (primary.text.trimEnd(' ', '\t').last() in ".!?") {
        if (after.isNotEmpty() && after.first() !in " \t\r\n,;") return false
        right = primary.endUtf16
    }
    fun cleanGap(start: Int, end: Int): Boolean {
        val chars = instruction.substring(start, end).toCharArray()
        context.forEach { span ->
            for (offset in maxOf(start, span.startUtf16) until minOf(end, span.endUtf16)) chars[offset - start] = ' '
        }
        if (chars.any { it.code > 127 }) return false
        return Regex("[ \\t,:]*(?:(?:and|aur|then|also|phir|plus)[ \\t,:]+)?", RegexOption.IGNORE_CASE).matches(String(chars))
    }
    return cleanGap(left, primary.startUtf16) && cleanGap(primary.endUtf16, right)
}

/** Own only emphasis beside an exact terminal count; never infer an amount. */
private fun GroceryIntentReviewV2ReadyRow.terminalCountEmphasisRange(
    instruction: String?, sourceBoundaries: List<GroceryIntentReviewV2Quote>, sourceContext: List<GroceryIntentCoverageSpan>,
): IntRange? {
    val source = evidence.primary.text
    if (instruction == null || item.quantityUnit != "count" || item.packValue != null ||
        evidence.supporting.isNotEmpty() || corrections.isNotEmpty() ||
        source.any { it.code > 127 || (it.code < 32 && it != '\t') }) return null
    val amount = localQuantityEvidence() ?: return null
    val emphasis = groceryIntentClosedCountEmphasisRange(amount) ?: return null
    if (!originalNominalEdgesComplete(evidence.primary, instruction, sourceBoundaries, sourceContext)) return null
    return emphasis
}

/** Bound emphasis or reference; additive wording still uses the original emphasis-only proof. */
private fun GroceryIntentReviewV2ReadyRow.terminalCountModifierRange(
    instruction: String?, sourceBoundaries: List<GroceryIntentReviewV2Quote>, sourceContext: List<GroceryIntentCoverageSpan>,
): IntRange? {
    terminalCountEmphasisRange(instruction, sourceBoundaries, sourceContext)?.let { return it }
    val source = evidence.primary.text
    if (instruction == null || item.quantityUnit != "count" || item.packValue != null ||
        evidence.supporting.isNotEmpty() || corrections.isNotEmpty() ||
        source.any { it.code > 127 || (it.code < 32 && it != '\t') }) return null
    val match = Regex(",[ \\t]*([A-Za-z]+|[0-9]+)[ \\t]+(of[ \\t]+those)[ \\t]*[.!?]?[ \\t]*$", RegexOption.IGNORE_CASE)
        .find(source) ?: return null
    val exactCounts = (1..20).map(Int::toString).toSet() +
        "one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen twenty".split(' ')
    if (match.groupValues[1].lowercase(Locale.ROOT) !in exactCounts) return null
    val segments = source.substring(0, match.range.first).split(',').map { it.trim(' ', '\t') }
    val head = segments.first()
    val words = Regex("[a-z]+").findAll(head.lowercase(Locale.ROOT)).map { it.value }.toSet()
    if (!Regex("[A-Za-z]+(?:[ '-]+[A-Za-z]+){0,15}").matches(head) ||
        words.intersect(groceryIntentNominalScopeUnsafeWords + inlineParticleSelectors + GROUP_NUMERIC_WORDS + "another").isNotEmpty() ||
        groceryIntentHasNominalClausePredicate(head)) return null
    val required = intentV2PropertyTokens(item.required.joinToString(" ") { it.replace('_', ' ') })
    for (segment in segments.drop(1)) {
        // Property words stay literal and must survive as required catalogue restrictions.
        if (protectedPropertyAssertion.matches(segment) && required.containsAll(intentV2PropertyTokens(segment))) continue
        val canonical = primaryAppositionIdentities[segment.lowercase(Locale.ROOT)] ?: return null
        if (!Regex("(?<![A-Za-z'-])${Regex.escape(canonical)}(?![A-Za-z'-])", RegexOption.IGNORE_CASE)
                .containsMatchIn(head)) return null
    }
    val amount = localQuantityEvidence() ?: return null
    val count = parseIntentCorrectionAmount(match.groupValues[1]) ?: return null
    val range = requireNotNull(match.groups[1]).range
    if (count.compareTo(amount.value) != 0 || amount.startUtf16 != range.first || amount.endUtf16 != range.last + 1) return null
    if (!originalNominalEdgesComplete(evidence.primary, instruction, sourceBoundaries, sourceContext)) return null
    val sentenceStart = Regex("[;!?\\r\\n]|\\.(?=[ \\t\\r\\n]|$)")
        .findAll(instruction.substring(0, evidence.primary.startUtf16)).lastOrNull()?.range?.last?.plus(1) ?: 0
    if (sourceBoundaries.any { it.endUtf16 > sentenceStart && it.startUtf16 < evidence.primary.startUtf16 }) return null
    return requireNotNull(match.groups[2]).range
}

/** Revalidated at the final quantity boundary; this grammar alone grants no scope. */
internal fun groceryIntentClosedCountEmphasisRange(amount: IntentLocalAmount): IntRange? {
    val source = amount.source
    if (amount.unit != "count" || source.any { it.code > 127 || (it.code < 32 && it != '\t') }) return null
    val match = Regex(",[ \\t]*([A-Za-z]+|[0-9]+)[ \\t]+(only)[ \\t]*[.!?]?[ \\t]*$", RegexOption.IGNORE_CASE)
        .find(source) ?: return null
    val nominal = source.substring(0, match.range.first).trim(' ', '\t')
    val words = Regex("[a-z]+").findAll(nominal.lowercase(Locale.ROOT)).map { it.value }.toSet()
    if (!Regex("[A-Za-z]+(?:[ '-]+[A-Za-z]+){0,15}").matches(nominal) ||
        words.intersect(groceryIntentNominalScopeUnsafeWords).isNotEmpty() ||
        groceryIntentHasNominalClausePredicate(nominal)) return null
    val count = parseIntentCorrectionAmount(match.groupValues[1]) ?: return null
    if (count.stripTrailingZeros().scale() > 0 || count < BigDecimal.ONE || count > BigDecimal(20) ||
        count.compareTo(amount.value) != 0) return null
    val countRange = requireNotNull(match.groups[1]).range
    if (amount.startUtf16 != countRange.first || amount.endUtf16 != countRange.last + 1 ||
        !amount.atom.equals(match.groupValues[1], ignoreCase = true)) return null
    return requireNotNull(match.groups[2]).range
}

/** 'Another NOMINAL, one only' is a delta, not a replacement cart total. */
private fun GroceryIntentReviewV2ReadyRow.additiveCountPrefixRange(
    instruction: String?, sourceBoundaries: List<GroceryIntentReviewV2Quote>, sourceContext: List<GroceryIntentCoverageSpan>,
): IntRange? {
    if (terminalCountEmphasisRange(instruction, sourceBoundaries, sourceContext) == null) return null
    return groceryIntentAdditiveCountPrefixRange(localQuantityEvidence() ?: return null)
}

internal fun groceryIntentAdditiveCountPrefixRange(amount: IntentLocalAmount): IntRange? {
    val match = Regex("^another[ \\t]+", RegexOption.IGNORE_CASE).find(amount.source) ?: return null
    if (amount.value.compareTo(BigDecimal.ONE) != 0 ||
        Regex("\\banother\\b", RegexOption.IGNORE_CASE).findAll(amount.source).count() != 1 ||
        groceryIntentClosedCountEmphasisRange(amount) == null) return null
    return match.range
}

/** Original NAME ka/ki/ke [BOUND COUNT] RETAIL FORM; no inferred fields. */
private fun GroceryIntentReviewV2ReadyRow.retailNameGenitiveRanges(
    instruction: String?, sourceBoundaries: List<GroceryIntentReviewV2Quote>, sourceContext: List<GroceryIntentCoverageSpan>,
): List<IntRange> {
    val source = evidence.primary.text
    val packaging = item.packaging ?: return emptyList()
    if (instruction == null || item.packValue != null || item.packUnit != "unspecified" ||
        source.any { it.code > 127 || (it.code < 32 && it != '\t') } || intentV2HasCorrectionCue(source) ||
        !originalNominalEdgesComplete(evidence.primary, instruction, sourceBoundaries, sourceContext)) return emptyList()
    val words = Regex("[a-z]+").findAll(item.name.lowercase(Locale.ROOT)).map { it.value }.toSet()
    if (words.isEmpty() || words.intersect(groceryIntentNominalScopeUnsafeWords + inlineParticleSelectors).isNotEmpty() ||
        groceryIntentContainsNumericAtom(item.name) || groceryIntentHasNominalClausePredicate(item.name) ||
        !Regex("[A-Za-z]+(?:[ \\t'-]+[A-Za-z]+)*").matches(item.name)) return emptyList()
    val literal = item.name.split(Regex("[ \\t]+")).joinToString("[ \\t]+") { Regex.escape(it) }
    val head = Regex("^[ \\t]*$literal[ \\t]+(ka|ki|ke)[ \\t]+", RegexOption.IGNORE_CASE)
        .find(source) ?: return emptyList()
    var start = head.range.last + 1
    val quantity = localQuantityEvidence()
    if (item.quantity != null) {
        if (item.quantityUnit != "pack" || quantity == null || quantity.startUtf16 != start) return emptyList()
        start = quantity.endUtf16
        if (!Regex("^[ \\t]+").containsMatchIn(source.substring(start))) return emptyList()
    } else if (quantity != null || item.quantityText != null) return emptyList()
    val tail = Regex("[ \\t]*([A-Za-z]+(?:[ -][A-Za-z]+)?)[ \\t]*[.!?]?[ \\t]*")
        .matchEntire(source.substring(start)) ?: return emptyList()
    val container = Regex("(?:tetra[ -]*packs?|packs?|packets?|bags?|bottles?|cartons?|cups?|cans?|tins?|tubs?|jars?|" +
        "box(?:es)?|bars?|blocks?|pouch(?:es)?|sachets?|tubes?|loaf|loaves|refills?|rolls?|sprays?|bunch(?:es)?|gaddi)",
        RegexOption.IGNORE_CASE)
    if (!container.matches(tail.groupValues[1]) ||
        intentV2PropertyTokens(tail.groupValues[1]) != intentV2PropertyTokens(packaging.replace('_', ' '))) return emptyList()
    return listOf(head.groups[1]!!.range)
}

/** Connectors between independently proved count/size/container slots are grammar.
 * Preserve count, size, container and preceding product; do not infer a role.
 */
private fun GroceryIntentReviewV2ReadyRow.packSizeGenitiveRanges(instruction: String?): List<IntRange> {
    if (instruction == null || item.quantityUnit != "pack" || item.packUnit !in setOf("g", "kg", "ml", "l") ||
        item.packaging.isNullOrEmpty()) return emptyList()
    val source = evidence.primary.text
    val quoteStart = instruction.indexOf(source)
    if (quoteStart < 0 || quoteStart != instruction.lastIndexOf(source)) return emptyList()
    val quantity = localQuantityEvidence() ?: return emptyList()
    val pack = localPackSizeEvidence() ?: return emptyList()
    val before = source.substring(0, pack.startUtf16)
    val connector = Regex("(?<=[ \\t])(ka|ki|ke)[ \\t]+\\z").find(before)?.groups?.get(1)
    val name = intentV2PropertyTokens(item.name)
    if (name.isEmpty()) return emptyList()
    if (connector != null && quantity.endUtf16 <= connector.range.first &&
        intentV2PropertyTokens(before.substring(0, connector.range.first)).containsAll(name)) return listOf(connector.range)
    // New word orders require the original physical unit and a terminal retail
    // container. Only whitespace can separate proven slots, never extra meaning.
    if (source.any { it.code > 127 || (it.code < 32 && it != '\t') } || intentV2HasCorrectionCue(source)) return emptyList()
    val unitGap = Regex("^[ \\t]*-?[ \\t]*").find(source.substring(pack.endUtf16))!!.value.length
    val unit = physicalUnitTokenFollowsAt(source, pack.endUtf16 + unitGap, item.packUnit) ?: return emptyList()
    val packEnd = pack.endUtf16 + unitGap + unit.length
    fun containerTail(start: Int, allowParticle: Boolean = false): List<IntRange>? {
        val tail = Regex("[ \\t]+(?:(wala|wali|wale)[ \\t]+)?([A-Za-z]+)[ \\t]*[.!?]?[ \\t]*")
            .matchEntire(source.substring(start)) ?: return null
        val container = Regex("(?:tetrapacks?|packs?|packets?|bags?|bottles?|cartons?|cups?|cans?|tins?|tubs?|jars?|" +
            "box(?:es)?|bars?|blocks?|pouch(?:es)?|sachets?|tubes?|loaf|loaves|refills?|rolls?|sprays?|bunch(?:es)?)",
            RegexOption.IGNORE_CASE)
        if (!container.matches(tail.groupValues[2])) return null
        val particle = tail.groups[1] ?: return emptyList()
        if (!allowParticle) return null
        return listOf((start + particle.range.first)..(start + particle.range.last))
    }
    // Product ka COUNT SIZE [wala] container.
    val beforeCount = Regex("(?<=[ \\t])(ka|ki|ke)[ \\t]+\\z")
        .find(source.substring(0, quantity.startUtf16))?.groups?.get(1)
    if (beforeCount != null && quantity.endUtf16 < pack.startUtf16 &&
        Regex("[ \\t]+").matches(source.substring(quantity.endUtf16, pack.startUtf16)) &&
        intentV2PropertyTokens(source.substring(0, beforeCount.range.first)).containsAll(name)) {
        containerTail(packEnd, allowParticle = true)?.let { return listOf(beforeCount.range) + it }
    }
    // Product SIZE ke COUNT container.
    if (packEnd < quantity.startUtf16 && intentV2PropertyTokens(before).containsAll(name)) {
        val between = Regex("[ \\t]+(ka|ki|ke)[ \\t]+")
            .matchEntire(source.substring(packEnd, quantity.startUtf16))?.groups?.get(1)
        if (between != null && containerTail(quantity.endUtf16) != null) {
            return listOf((packEnd + between.range.first)..(packEnd + between.range.last))
        }
    }
    return emptyList()
}

/** A complete noun phrase may end in a Hindi relative particle without a comma.
 * Original local amount offsets and full-phrase selector checks are required;
 * every other identity token still goes through reverse grounding unchanged.
 */
private fun GroceryIntentReviewV2ReadyRow.terminalBoundParticleRanges(
    proposed: Set<String>, instruction: String?,
): List<IntRange> {
    val source = evidence.primary.text
    if (instruction == null || source.any { it.code > 127 || (it.code < 32 && it != '\t') } ||
        ',' in source || '&' in source || intentV2HasCorrectionCue(source) ||
        Regex("[a-z]+").findAll(source.lowercase(Locale.ROOT)).any { it.value in inlineParticleSelectors }) return emptyList()
    val quoteStart = instruction.indexOf(source)
    if (quoteStart < 0 || quoteStart != instruction.lastIndexOf(source)) return emptyList()
    val particle = Regex("(?<=[ \\t])(wala|wali|wale)[ \\t]*[.!?]?[ \\t]*\\z")
        .find(source)?.groups?.get(1) ?: return emptyList()
    if (!Regex("^[ \\t]*(?:[,;!?]|\\.(?=[ \\t\\r\\n]|\\z)|\\z)")
            .containsMatchIn(instruction.substring(quoteStart + particle.range.last + 1))) return emptyList()
    // A provider-selected quote cannot hide a selector just before its start.
    // Coverage still handles every other original prefix word; ordinary list
    // conjunctions alone do not impose a product preference.
    val prefix = instruction.substring(0, quoteStart).split(Regex("[,;:!?\\r\\n]|\\.(?=\\s|$)")).last()
    if (Regex("[a-z]+").findAll(prefix.lowercase(Locale.ROOT)).any {
            it.value in inlineParticleSelectors && it.value !in setOf("and", "aur", "plus")
        } || Regex("\\bup[ \\t]+to\\b", RegexOption.IGNORE_CASE).containsMatchIn(prefix)) return emptyList()
    val quantity = localQuantityEvidence() ?: return emptyList()
    if (item.quantityUnit !in setOf("count", "pack", "piece")) return emptyList()
    val pack = localPackSizeEvidence()
    // The evidence atom is numeric; bind its immediately adjacent literal unit
    // without re-detecting another size or accepting intervening range words.
    fun withUnitEnd(end: Int, unit: String): Int {
        val gap = Regex("^[ \\t]*-?[ \\t]*").find(source.substring(end))!!.value.length
        val token = physicalUnitTokenFollowsAt(source, end + gap, unit)
        return if (token != null) end + gap + token.length else end
    }
    val quantityEnd = withUnitEnd(quantity.endUtf16, item.quantityUnit)
    val packEnd = pack?.let { withUnitEnd(it.endUtf16, item.packUnit) }
    if (pack != null && packEnd != null && packEnd > pack.endUtf16 && item.quantityUnit == "pack" &&
        !item.packaging.isNullOrEmpty() && item.packUnit in setOf("g", "kg", "ml", "l") &&
        quantityEnd <= pack.startUtf16 && packEnd < particle.range.first &&
        Regex("[ \\t]+").matches(source.substring(packEnd, particle.range.first))) return listOf(particle.range)
    // Never reinterpret a failed size-tail proof as a product descriptor.
    if (pack != null) return emptyList()
    val tailStart = maxOf(quantityEnd, packEnd ?: 0)
    if (tailStart >= particle.range.first) return emptyList()
    val descriptor = source.substring(tailStart, particle.range.first).trim(' ', '\t')
    if (!Regex("[A-Za-z]+(?:[ \\t-]+[A-Za-z]+)*").matches(descriptor) ||
        groceryIntentContainsNumericAtom(descriptor) ||
        Regex("\\b(?:g|gm|gms|grams?|kg|kgs|kilos?|kilograms?|keji|ml|millilitres?|milliliters?|" +
            "l|ltr|ltrs|litres?|liters?|small|medium|large|big|chh?ot[aei]|bad[aei])\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(descriptor) ||
        Regex("\\b(?:kind|sort)[ \\t-]+of\\b", RegexOption.IGNORE_CASE).containsMatchIn(descriptor)) return emptyList()
    val identity = intentV2PropertyTokens(descriptor)
    return if (identity.isNotEmpty() && proposed.containsAll(identity)) listOf(particle.range) else emptyList()
}

internal val inlineParticleSelectors = (
    "no not nahi nahin bina without or ya woh vo yeh ye wahi same usual last previous " +
        "pichli pichla pichhle doosra dusra dusri other this that these those " +
        "almost nearly maybe perhaps possibly potentially apparently roughly approximately around " +
        "supposedly allegedly sometimes partially mostly somewhat kinda " +
        "mera meri mere apna apni apne papa mummy bhaiya didi ghar roz daily hamesha regular aaj kal " +
        "sasta saste sasti mehnga mehngi mehnge cheap cheapest offer discount best top popular " +
        "accha acche achha achhe badhiya jaisa jaise jaisi type tarah like similar kuch koi bhi jo " +
        "dono sab saare naya nayi naye purana purani purane and aur plus " +
        "kam kum jyada zyada zyaada adhik tak aas paas less more least most minimum maximum under over upto " +
        "rs inr rupee rupees rupay rupaye rupaiya rupaiye"
    ).split(' ').toSet()

/** Closed colloquial relations; all brand/flavour words on either side are
 * still checked by reverse grounding. Recognition uses original offsets only.
 */
private fun GroceryIntentReviewV2ReadyRow.localIdentityGrammarRanges(proposed: Set<String>): List<IntRange> {
    val source = evidence.primary.text
    val ranges = mutableListOf<IntRange>()
    val local = localQuantityEvidence()
    ranges += primaryAppositionRanges(source, proposed, local != null)
    if (local != null && Regex("[\\s.,;!?]*").matches(source.substring(local.endUtf16))) {
        val before = source.substring(0, local.startUtf16)
        Regex("\\b(wala|wali|wale)[\\s,]*$", RegexOption.IGNORE_CASE).find(before)?.groups?.get(1)?.let { ranges += it.range }
        val from = Regex("\\b(from)\\s+([\\p{L}\\p{N}][\\p{L}\\p{N}'-]*(?:\\s+[\\p{L}\\p{N}][\\p{L}\\p{N}'-]*){0,4})[\\s,]*$", RegexOption.IGNORE_CASE).find(before)
        if (from != null && intentV2PropertyTokens(before.substring(0, from.range.first)).intersect(proposed).isNotEmpty() &&
            proposed.containsAll(intentV2PropertyTokens(from.groupValues[2]))) ranges += requireNotNull(from.groups[1]).range
    }
    ranges += groceryIntentRetainedGenitiveRanges(source, proposed)
    Regex("\\b(?:yogurts?|yoghurt|curd)\\s+(in)\\s+", RegexOption.IGNORE_CASE).findAll(source)
        .forEach { ranges += requireNotNull(it.groups[1]).range }
    if (local != null) Regex("\\b(also)\\s*[.!?]*$", RegexOption.IGNORE_CASE).find(source)?.groups?.get(1)?.let { ranges += it.range }
    return ranges
}

/** Original local grammar only, not evidence of identity, quantity or package
 * role. The preceding noun must remain in the positive proposed identity. */
internal fun groceryIntentRetainedGenitiveRanges(source: String, proposed: Set<String>): List<IntRange> {
    val genitive = Regex("^\\s*([\\p{L}\\p{N}][\\p{L}\\p{N}'-]*)\\s+(ka|ki|ke)\\s+", RegexOption.IGNORE_CASE)
        .find(source) ?: return emptyList()
    val noun = intentV2PropertyTokens(genitive.groupValues[1])
    return if (noun.isNotEmpty() && genitive.groupValues[1].lowercase(Locale.ROOT) !in GROUP_NUMERIC_WORDS &&
        proposed.containsAll(noun))
        listOf(requireNotNull(genitive.groups[2]).range) else emptyList()
}

// V2-only apposition proof, never a global search/catalogue translation.
// The English identity must be literal before the final comma in this primary.
private val primaryAppositionIdentities = mapOf(
    "kangni" to "foxtail millet", "methi" to "fenugreek",
    "elaichi" to "cardamom", "dalchini" to "cinnamon",
    "shunti" to "ginger", // Audited IISc category correspondence, never fresh/dry/powder inference.
    "baalehannu" to "banana", "baale hannu" to "banana",
)

/** Original naming relation; never a globally ignored word or inferred product. */
private fun GroceryIntentReviewV2ReadyRow.metalinguisticAliasRange(
    proposed: Set<String>, instruction: String?, boundaries: List<GroceryIntentReviewV2Quote>,
    context: List<GroceryIntentCoverageSpan>,
): IntRange? {
    val source = evidence.primary.text
    if (instruction == null || item.quantityUnit != "count" || item.packValue != null || item.packaging != null ||
        evidence.supporting.isNotEmpty() || corrections.isNotEmpty() ||
        source.any { it.code > 127 || (it.code < 32 && it != '\t') }) return null
    val amount = localQuantityEvidence() ?: return null
    if (source.substring(0, amount.startUtf16).isNotBlank() || amount.value.stripTrailingZeros().scale() > 0 ||
        amount.value < java.math.BigDecimal.ONE || amount.value > 20.toBigDecimal()) return null
    val match = Regex(
        "[ \\t]*(?<head>[A-Za-z]+(?:[ '-]+[A-Za-z]+){0,15})[ \\t]*,[ \\t]*" +
            "(?<clause>jisko[ \\t]+(?<hi>[A-Za-z]+(?:[ \\t]+[A-Za-z]+){0,2})[ \\t]+bolte[ \\t]+hain|" +
            "also[ \\t]+known[ \\t]+as[ \\t]+(?<en>[A-Za-z]+(?:[ \\t]+[A-Za-z]+){0,2}))" +
            "[ \\t]*[.!?]?[ \\t]*", RegexOption.IGNORE_CASE,
    ).matchEntire(source.substring(amount.endUtf16)) ?: return null
    val head = requireNotNull(match.groups["head"]).value.trim()
    val alias = (match.groups["hi"] ?: match.groups["en"])?.value
        ?.lowercase(Locale.ROOT)?.replace(Regex("[ \\t]+"), " ") ?: return null
    val canonical = primaryAppositionIdentities[alias]
        ?: alias.takeIf { it in primaryAppositionIdentities.values } ?: return null
    val equivalents = listOf(canonical) + primaryAppositionIdentities.filterValues { it == canonical }.keys
    if (equivalents.none { Regex("(?:^|[ \\t])${Regex.escape(it)}s?$", RegexOption.IGNORE_CASE).containsMatchIn(head) } ||
        Regex("[a-z]+").findAll(head.lowercase(Locale.ROOT)).map { it.value }.toSet().intersect(
            groceryIntentNominalScopeUnsafeWords + GROUP_NUMERIC_WORDS + inlineParticleSelectors).isNotEmpty() ||
        !intentV2PropertyTokens(head).containsAll(intentV2PropertyTokens(canonical)) ||
        groceryIntentHasNominalClausePredicate(head) || intentV2PropertyTokens(head) != proposed ||
        !originalNominalEdgesComplete(evidence.primary, instruction, boundaries, context)) return null
    val clause = requireNotNull(match.groups["clause"]).range
    return (amount.endUtf16 + clause.first)..(amount.endUtf16 + clause.last)
}

private fun primaryAppositionRanges(source: String, proposed: Set<String>, hasQuantity: Boolean): List<IntRange> {
    if (!hasQuantity || '\n' in source || '\r' in source || intentV2HasCorrectionCue(source)) return emptyList()
    val comma = source.lastIndexOf(',')
    if (comma < 0) return emptyList()
    val prefix = source.substring(0, comma)
    val asciiLowerPrefix = buildString(prefix.length) {
        prefix.forEach { append(if (it in 'A'..'Z') it + 32 else it) }
    }
    val tail = source.substring(comma + 1)
    val matched = Regex("[ \\t]*([A-Za-z]+(?:[ \\t]+[A-Za-z]+){0,4})[ \\t]*[.!?]?[ \\t]*\\z").matchEntire(tail)
        ?: return emptyList()
    if (groceryIntentContainsNumericAtom(matched.groupValues[1])) return emptyList()
    val ranges = mutableListOf<IntRange>()
    val equivalents = mutableMapOf<String, Set<String>>()
    Regex("[A-Za-z]+").findAll(tail).forEach { word ->
        val alias = word.value.lowercase(Locale.ROOT)
        val canonical = primaryAppositionIdentities[alias] ?: return@forEach
        val literal = Regex("(?<![\\p{L}\\p{N}\\p{M}_'’\\-])" + canonical.split(' ').joinToString("[ \\t]+") +
            "(?![\\p{L}\\p{N}\\p{M}_'’\\-])")
        val tokens = intentV2PropertyTokens(canonical)
        if (literal.containsMatchIn(asciiLowerPrefix) && proposed.containsAll(tokens)) {
            ranges += (comma + 1 + word.range.first)..(comma + 1 + word.range.last)
            equivalents[alias] = tokens
        }
    }
    // Lowercase-only particle, bounded repeated descriptor, original offsets.
    val particle = Regex("[ \\t]*([A-Za-z]+(?:[ \\t]+[A-Za-z]+){0,3})[ \\t]+(wala|wali|wale)[ \\t]*[.!?]?[ \\t]*\\z")
        .matchEntire(tail)
    if (particle != null) {
        val descriptor = intentV2PropertyTokens(particle.groupValues[1]).toMutableSet()
        equivalents.forEach { (alias, canonical) -> if (descriptor.remove(alias)) descriptor.addAll(canonical) }
        if (descriptor.isNotEmpty() && proposed.containsAll(descriptor) && intentV2PropertyTokens(prefix).containsAll(descriptor)) {
            val range = requireNotNull(particle.groups[2]).range
            ranges += (comma + 1 + range.first)..(comma + 1 + range.last)
        }
    }
    return ranges
}

// A named target requires literal ownership, not category translation (for
// example a quote about 'milk' cannot be attached to a primary saying 'doodh').
private fun literalSubstitutionTargetWords(text: String): Set<String> {
    val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    return Regex("[\\p{L}]+").findAll(normalized).map { it.value }
        .filter { it !in setOf("the", "a", "an") }.map { substitutionTargetPlurals[it] ?: it }.toSet()
}

private fun substitutionTargetIsProven(target: String, source: String): Boolean {
    val words = literalSubstitutionTargetWords(target)
    return Regex("[\\p{L}]+(?:[ '-]+[\\p{L}]+)*").matches(target) && words.isNotEmpty() &&
        (intentEvidenceTokens(target) - substitutionNonProductTokens).isNotEmpty() &&
        literalSubstitutionTargetWords(source).containsAll(words) &&
        !Regex("\\b(?:with|and|or|instead|except|unless|actually|not|no|only)\\b", RegexOption.IGNORE_CASE).containsMatchIn(target)
}

private fun substitutionTargetIsUnique(target: String, rowIndex: Int, primaries: List<Pair<Int, String>>): Boolean {
    val matches = primaries.filter { (_, source) -> substitutionTargetIsProven(target, source) }
    return matches.size == 1 && matches.single().first == rowIndex
}

private val substitutionTargetPlurals = mapOf(
    "yoghurts" to "yogurt", "yoghurt" to "yogurt", "yogurts" to "yogurt",
    "eggs" to "egg", "bananas" to "banana", "apples" to "apple", "oranges" to "orange",
    "tomatoes" to "tomato", "onions" to "onion", "potatoes" to "potato", "lemons" to "lemon",
    "biscuits" to "biscuit", "chocolates" to "chocolate", "lentils" to "lentil", "samosas" to "samosa",
    "strawberries" to "strawberry", "blueberries" to "blueberry", "mangoes" to "mango",
)

private val substitutionNonProductTokens = (
    "a an the and or of for with without in on to from please get buy order add " +
        "make keep only just that this these those it is be are should must not no " +
        "one two three four five six seven eight nine ten eleven twelve dozen half quarter " +
        "ek do teen char chaar paanch panch chhe saat aath nau das dedh dhai aadha adha paav " +
        "kilo kg keji g gm gram ml litre liter l pack packet bag bottle carton cup can tin tub jar " +
        "box bar block loaf bunch roll refill pouch sachet tube spray piece pieces pcs count " +
        "small large medium big little plain unsalted salted unsweetened sweetened organic certified " +
        "spicy sweet vegan vegetarian nonvegetarian full fat free fresh each instead sorry " +
        "noaddedsugar noaddedsalt sugarfree lowsugar lowfat highfibre highprotein glutenfree " +
        "lactosefree pulpfree readymade readytoeat readytodrink readytocook"
    ).split(" ").toSet()

/** Do not let citing a heading license dropping its brand/category or exclusions. */
private fun groupHeaderPreserved(
    group: GroceryIntentReviewV2Group,
    item: GroceryIntentItem,
    requestIdentity: String?,
    semanticHeader: String?,
): Boolean {
    group.postfixIdentity()?.let { identity ->
        return postfixIdentityWords(item.name + " " + item.required.joinToString(" "))
            .containsAll(postfixIdentityWords(identity))
    }
    val header = semanticHeader ?: return true // Each-only, purpose and proven narration confer no identity evidence.
    var heading = requestIdentity ?: groupIdentityHeading(group)
    group.total?.let { total -> heading = heading.replaceFirst(total.amountText, " ") }
    // Parse before stripping negatives, so an exclusion or conditional cannot
    // manufacture an eligible category suffix from an otherwise unknown header.
    val categories = groceryIntentGroupCategoryHeader(
        header, localQuantities = group.hasEligibleLocalQuantityHeading(),
    )
    val identity = if (categories != null) {
        group.total?.let { categories.identity.replaceFirst(it.amountText, " ") } ?: categories.identity
    } else heading
    val proposed = intentEvidenceTokens(item.name + " " + item.required.joinToString(" ") { it.replace('_', ' ') })
    val exclusions = item.excluded.map(::intentV2IngredientPhrase).toSet()
    if (hasQualifiedV2NegativeIngredient(identity)) return false
    for (match in negativeAttribute.findAll(identity)) {
        val ingredient = intentV2IngredientPhrase(match.groupValues[1])
        val equivalent = equivalentExclusionProperties[ingredient]
        if (ingredient !in exclusions && equivalent !in proposed) return false
    }
    // Whole exclusions have already passed polarity/conflict checks above.
    // Never let separate palm/oil filters stand in for a palm-oil exclusion.
    val totalScaffold = if (group.total != null) setOf("ke", "ki", "ka", "total", "altogether") else emptySet()
    val requiredTokens = intentEvidenceTokens(negativeAttribute.replace(identity, " ")) - totalScaffold
    return proposed.containsAll(requiredTokens)
}

/**
 * A complete instruction to keep the written local counts is not product identity.
 * This exemption needs independently validated amounts on EVERY member. Unknown
 * prose, missing counts and all brand/category/diet words stay subject to grounding.
 * A separate complete terminal request may be projected without changing any
 * amount; it introduces no count rule and retains the entire identity prefix.
 * The original, unshortened header is still shown once in the combined review.
 */
private fun groupIdentityHeading(group: GroceryIntentReviewV2Group): String {
    val original = group.header?.text ?: return ""
    group.postfixIdentity()?.let { return it }
    group.variantCardinalityIdentity()?.let { return it }
    // Recognize the complete request on the ORIGINAL header. Removing a total
    // first must never join words into a request that the user did not write.
    val requestIdentity = groceryIntentGroupRequestIdentity(original)
    if (!group.hasEligibleLocalQuantityHeading()) return requestIdentity ?: original
    return groceryIntentLocalQuantityHeadingIdentity(original)
        ?.takeIf { intentEvidenceTokens(it).isNotEmpty() } ?: requestIdentity ?: original
}

// Mirrors grocery_intent_corrections.py. Only these explicit negative variant
// forms are exempt from correction detection, and grounding requires their
// properties in excluded. An unowned form still blocks the whole review.
private const val V2_PROTECTED_PROPERTY_PATTERN =
    "sugar[\\s-]*free|no[\\s-]+added[\\s-]+sugar|low[\\s-]*fat|tetra[\\s-]*pack|" +
        "ready[\\s-]*to[\\s-]*(?:eat|drink|cook)|organic|certified|unsalted|plain|" +
        "high[\\s-]+fib(?:re|er)|high[\\s-]+protein|low[\\s-]+calorie|low[\\s-]+sugar|" +
        "gluten[\\s-]*free|lactose[\\s-]*free|vegan|vegetarian|unsweetened|(?:un|non)[\\s-]+sweetened|sweetened"
private const val V2_VARIANT_END = "(?:substitutions?|substitutes?|versions?)\\b(?![\\w-])"
internal val terminalExcludedAttribute = Regex(
    ",[ \\t]*not[ \\t]+($V2_PROTECTED_PROPERTY_PATTERN)(?=[ \\t]*[.!?]?[ \\t]*\\z)",
    RegexOption.IGNORE_CASE,
)
// Separate vocabulary: do not widen unrelated protected-property grammars.
private val excludedOneVariant = Regex(
    ",[ \\t]*not[ \\t]+the[ \\t]+($V2_PROTECTED_PROPERTY_PATTERN|fresh|dry|dried|frozen)[ \\t]+(one)(?=[ \\t]*[.!?]?[ \\t]*\\z)",
    RegexOption.IGNORE_CASE,
)
internal fun intentV2ExcludedOneMatch(text: String): MatchResult? =
    if (text.any { it.code > 127 }) null else excludedOneVariant.find(text)

internal fun intentV2RetainedExcludedOneMatch(text: String, name: String, required: List<String>,
                                            excluded: List<String>): MatchResult? {
    val match = intentV2ExcludedOneMatch(text) ?: return null
    fun key(value: String) = Regex("[\\s_-]+").replace(value.lowercase(Locale.ROOT), "").replace("highfiber", "highfibre")
    val property = key(match.groupValues[1])
    val positive = Regex("[\\s_-]+").replace(name + " " + required.joinToString(" "), " ")
    val phrase = Regex("[\\s_-]+").replace(match.groupValues[1], " ")
    if (excluded.map(::key).toSet() != setOf(property) ||
        Regex("\\b" + Regex.escape(phrase) + "\\b", RegexOption.IGNORE_CASE).containsMatchIn(positive)) return null
    return match
}

/** Lexical only: prepareForReview separately proves ORIGINAL single-item scope. */
private fun stripIntentV2ExcludedOne(text: String): String {
    val match = intentV2ExcludedOneMatch(text) ?: return text
    return text.replaceRange(match.range, " ".repeat(match.value.length))
}
// The short branch grants no scope by itself. prepareForReview proves one
// complete ORIGINAL clause before this lexical interpretation can be used.
private val excludedAttributeVariant = Regex(
    "(?:\\b(?:no|without|not)(?=[\\t -]+(?:$V2_PROTECTED_PROPERTY_PATTERN)[\\t -]+$V2_VARIANT_END)" +
        "|,[ \\t]*not(?=[ \\t]+(?:$V2_PROTECTED_PROPERTY_PATTERN)[ \\t]*[.!?]?[ \\t]*\\z))" +
        "[\\t -]+($V2_PROTECTED_PROPERTY_PATTERN)(?:[\\t -]+$V2_VARIANT_END)?",
    RegexOption.IGNORE_CASE,
)

private val negativeSubstitution = Regex(
    "\\b(?:do\\s+not|don't)\\s+substitute\\s+(?:the\\s+|any\\s+)?(" +
        "sugar[\\s-]*free|no[\\s-]+added[\\s-]+sugar|low[\\s-]*fat|tetra[\\s-]*pack|" +
        "ready[\\s-]*to[\\s-]*(?:eat|drink|cook)|organic|certified|unsalted|plain|" +
        "high[\\s-]+fib(?:re|er)|high[\\s-]+protein|low[\\s-]+calorie|low[\\s-]+sugar|" +
        "gluten[\\s-]*free|lactose[\\s-]*free|vegan|vegetarian|unsweetened|(?:un|non)[\\s-]+sweetened|sweetened" +
        ")\\s+([^,:;.!?\\r\\n\\u2014\\u2013\\u2012\\uff1a]+)(?=$|[,:;.!?\\r\\n\\u2014\\u2013\\u2012\\uff1a])", RegexOption.IGNORE_CASE,
)

// A compound support may contain one separately proven positive requirement,
// then the exclusion. Neither clause can cover another product or quantity.
private val namedPositivePropertyPrefix = Regex(
    "\\s*(?:please\\s+)?(?:make\\s+sure|ensure)\\s+(?:the\\s+)?(.+?)\\s+(?:has|is)\\s+(" +
        "sugar[\\s-]*free|no[\\s-]+added[\\s-]+sugar|low[\\s-]*fat|tetra[\\s-]*pack|" +
        "ready[\\s-]*to[\\s-]*(?:eat|drink|cook)|organic|certified|unsalted|plain|" +
        "high[\\s-]+fib(?:re|er)|high[\\s-]+protein|low[\\s-]+calorie|low[\\s-]+sugar|" +
        "gluten[\\s-]*free|lactose[\\s-]*free|vegan|vegetarian|unsweetened|(?:un|non)[\\s-]+sweetened|sweetened" +
        ")\\s*;\\s*", RegexOption.IGNORE_CASE,
)

private fun normalizeV2SweetenedProperty(text: String): String =
    Regex("\\b(?:un|non)[\\s-]+sweetened\\b", RegexOption.IGNORE_CASE)
        .replace(Normalizer.normalize(text, Normalizer.Form.NFKC).replace('_', ' '), "unsweetened")

private fun intentV2PropertyTokens(text: String): Set<String> = intentEvidenceTokens(normalizeV2SweetenedProperty(text))

private val namedPropertyContrast = Regex(
    "\\s*(?:please\\s+)?(?:the\\s+)?(?<target>[^,:;.!?\\r\\n\\u2014\\u2013\\u2012\\uff1a]+?)" +
        "\\s+(?:must|should)\\s+be\\s+(?<positive>$V2_PROTECTED_PROPERTY_PATTERN)" +
        "\\s*,\\s*not\\s+(?<negative>$V2_PROTECTED_PROPERTY_PATTERN)\\s*[.!]?\\s*",
    RegexOption.IGNORE_CASE,
)
private val v2PropertyOpposites = mapOf("sweetened" to "unsweetened", "unsweetened" to "sweetened")

private data class IntentV2NamedPropertyContrast(
    val target: String,
    val positiveText: String,
    val positive: Set<String>,
    val negative: Set<String>,
)

private fun intentV2NamedPropertyContrast(text: String): IntentV2NamedPropertyContrast? {
    val match = namedPropertyContrast.matchEntire(text) ?: return null
    val positiveText = match.groups["positive"]?.value ?: return null
    val negativeText = match.groups["negative"]?.value ?: return null
    val positive = intentV2PropertyTokens(positiveText)
    val negative = intentV2PropertyTokens(negativeText)
    if (positive.size != 1 || negative.size != 1 || v2PropertyOpposites[negative.single()] != positive.single()) return null
    return IntentV2NamedPropertyContrast(
        target = match.groups["target"]?.value?.trim().orEmpty(),
        positiveText = positiveText,
        positive = positive,
        negative = negative,
    )
}

private fun hasUnprovenV2PropertyNegation(text: String): Boolean = Regex(
    "\\b(?:nonvegetarian|nonveg|nonorganic|uncertified)\\b|\\b(?:un|non)[\\s-]+(?:sugar[\\s-]*free|no[\\s-]+added[\\s-]+sugar|low[\\s-]*fat|tetra[\\s-]*pack|" +
        "ready[\\s-]*to[\\s-]*(?:eat|drink|cook)|organic|certified|unsalted|plain|high[\\s-]+fib(?:re|er)|" +
        "high[\\s-]+protein|low[\\s-]+calorie|low[\\s-]+sugar|gluten[\\s-]*free|lactose[\\s-]*free|" +
        "vegan|vegetarian|unsweetened|sweetened)\\b", RegexOption.IGNORE_CASE,
).containsMatchIn(normalizeV2SweetenedProperty(text))

// A spoken edit is not a seller property. Shared by source-compiled and
// interpreted review; deliberately distinct from the broader uncertainty and
// negation checks below (a named product or an explicit diet is not an edit).
internal val groceryIntentExplicitOrderEdit = Regex(
    """(?ix)(?<![\p{L}\p{M}\p{N}_])(?:actually|instead|cancel|replace|swap|remove|corrections?|galti)(?![\p{L}\p{M}\p{N}_])
        |\bno\s+wait\b|\b(?:make|set|keep)\s+(?:it|that|this)\b|\bbadal(?:\s+do)?\b
        |\b(?:(?:ki|uski)[\x20\t]+jagah|ke[\x20\t]+(?:bajay|badle))\b"""
)

/** Mirrors the backend omission guard; NOT a general semantic/negation parser. */
internal fun intentV2HasPrimaryOperation(text: String, otherPrimaries: List<String> = emptyList(),
                                       allowLocalAvailability: Boolean = false): Boolean =
    intentV2HasCorrectionCue(text, allowPropertyContrast = false, allowLocalAvailability = allowLocalAvailability) || Regex(
        "(?:[,;:.!?\\r\\n]|\\b(?:and|aur)\\b)[\\t ]*(?:make|set|keep)\\b",
        RegexOption.IGNORE_CASE,
    ).containsMatchIn(text) || (!countedSetDairyNominal(text) && Regex("\\b(?:make|set|keep)\\s+([^,;:.!?\\r\\n]+)", RegexOption.IGNORE_CASE)
        .findAll(text).any { operation ->
            // Possible reference only: overlap denies readiness, never authorizes
            // a correction. Literal name-first 'set curd' without a competing
            // curd primary still has to pass all normal source/quantity checks.
            val anchors = correctionAnchors(operation.groupValues[1])
            otherPrimaries.any { other -> anchors.intersect(correctionAnchors(other)).isNotEmpty() }
        })

internal val groceryIntentNominalClauseUnsafeWords = (
    "and aur or ya of with for by per from to in into on at than then if unless without instead " +
        "not no nahi nahin please kindly add order get make set sets keep change replace remove cancel " +
        "i you we they he she it this who which that these those is are was were be been being have has had"
    ).split(' ').toSet()
internal val groceryIntentNominalScopeUnsafeWords = groceryIntentNominalClauseUnsafeWords + (
    "serving servings glass glasses spoon spoons teaspoon teaspoons tablespoon tablespoons pinch pinches " +
        "handful handfuls scoop scoops drop drops ladle ladles sip sips dose doses portion portions slice slices " +
        "piece pieces pc pcs pair pairs chunk chunks dollop dollops lump lumps bit bits case cases crate crates " +
        "tray trays strip strips sheet sheets bowl bowls stick sticks head heads bundle bundles each " +
        "ounce ounces oz pound pounds lb lbs couple couples few g gm gms gram grams kg kilo kilos " +
        "kilogram kilograms keji ml millilitre millilitres milliliter milliliters l litre litres liter liters ltr"
    ).split(' ').toSet()
private val nominalPredicateWords = (
    "prefer want need like love enjoy request require order buy choose select use drink eat take bring send " +
        "deliver purchase recommend ask say wish demand receive share contain hold cost weigh"
    ).split(' ').flatMap { base ->
        listOf(base, base + "s", base + if (base.endsWith('e')) "d" else "ed",
            base.removeSuffix("e") + "ing")
    }.toSet() + "preferred preferring bought chose chosen took taken brought sent said ate eaten drank drunk held wishes wished wishing".split(' ')

/** Necessary deny grammar for nominal receipts, not a general POS tagger. */
internal fun groceryIntentHasNominalClausePredicate(text: String): Boolean =
    Regex("[a-z]+").findAll(text.lowercase(Locale.ROOT)).any { it.value in nominalPredicateWords }

private val localAvailabilityCondition = Regex(
    "[ \\t]*(?<amount>[0-9]+(?:\\.[0-9]+)?|[A-Za-z]+)[ \\t]+" +
        "(?<body>[A-Za-z]+(?:[ '-]+[A-Za-z]+){0,15})[ \\t]+" +
        "(?<condition>if[ \\t]+(?:(?:the[ \\t]+)?(?<property>" +
        "unscented|scented|organic|unsalted|salted|unsweetened|sweetened|" +
        "sugar[ -]*free|no[ -]+added[ -]+sugar|gluten[ -]*free|lactose[ -]*free|low[ -]*fat)" +
        "[ \\t]+(?:kind|variant|version)[ \\t]+is[ \\t]+)?available)[ \\t]*[.!]?[ \\t]*",
    RegexOption.IGNORE_CASE,
)
private val availabilityLeadingUnit = Regex(
    "^(?:packs?|packets?|bags?|bottles?|cartons?|cups?|cans?|tins?|tubs?|jars?|box(?:es)?|bars?|blocks?|" +
        "pouch(?:es)?|sachets?|tubes?|loaf|loaves|refills?|rolls?|sprays?|bunch(?:es)?|" +
        "g|gm|gms|grams?|kg|kgs|kilos?|kilograms?|keji|ml|millilitres?|milliliters?|l|ltr|litres?|liters?)" +
        "[ \\t]+(?:of[ \\t]+)?", RegexOption.IGNORE_CASE,
)

private fun localAvailabilityPrimaryIsComplete(primary: GroceryIntentReviewV2Quote, instruction: String?): Boolean {
    if (instruction == null || primary.startUtf16 < 0 || primary.endUtf16 <= primary.startUtf16 ||
        primary.endUtf16 > instruction.length ||
        instruction.substring(primary.startUtf16, primary.endUtf16) != primary.text) return false
    val after = instruction.substring(primary.endUtf16).trimStart(' ', '\t')
    return after.isEmpty() || after.first() in ",;.!?\r\n" || primary.text.trimEnd(' ', '\t').endsWith('.') ||
        primary.text.trimEnd(' ', '\t').endsWith('!')
}

/** Grammar-only proof on the COMPLETE original primary. Inventory, identity,
 * amounts and retained attributes need their normal independent checks. Never
 * use on residual/joined text, excuse model ambiguity, or authorize a fallback. */
internal fun intentV2LocalAvailabilityGrammarRanges(text: String): List<IntRange> {
    if (text.length > 320 || text.any { it.code > 127 }) return emptyList()
    val match = localAvailabilityCondition.matchEntire(text) ?: return emptyList()
    val amount = parseIntentCorrectionAmount(match.groups["amount"]!!.value) ?: return emptyList()
    if (amount <= BigDecimal.ZERO || amount > BigDecimal(10000)) return emptyList()
    val nominal = availabilityLeadingUnit.replaceFirst(match.groups["body"]!!.value, "")
    val words = Regex("[a-z]+").findAll(nominal.lowercase(Locale.ROOT)).map { it.value }.toSet()
    if (words.isEmpty() || words.intersect(groceryIntentNominalScopeUnsafeWords).isNotEmpty() ||
        groceryIntentContainsNumericAtom(nominal) || groceryIntentHasNominalClausePredicate(nominal)) return emptyList()
    val condition = match.groups["condition"]!!.range
    val property = match.groups["property"]?.range ?: return listOf(condition)
    return listOf(condition.first until property.first, (property.last + 1)..condition.last)
}

/** A complete counted dairy noun phrase, not an exemption for commands to set
 * another row. Every original word and the stated count still require grounding. */
private fun countedSetDairyNominal(text: String): Boolean {
    if (text.any { it.code > 127 }) return false
    val match = Regex(
        "[ \\t]*([0-9]+|[A-Za-z]+)[ \\t]+((?:[A-Za-z]+[ \\t]+){0,4})" +
            "set[ \\t]+(?:curd|yogurt|yoghurt)[ \\t]*[.!?]?[ \\t]*", RegexOption.IGNORE_CASE,
    ).matchEntire(text) ?: return false
    val count = parseIntentCorrectionAmount(match.groupValues[1]) ?: return false
    if (count.stripTrailingZeros().scale() > 0 || count < BigDecimal.ONE || count > BigDecimal(20)) return false
    val prefix = match.groupValues[2].lowercase(Locale.ROOT).split(Regex("[ \\t]+")).toSet()
    return prefix.intersect(groceryIntentNominalScopeUnsafeWords).isEmpty() &&
        !groceryIntentContainsNumericAtom(match.groupValues[2]) &&
        !groceryIntentContainsRetailContainer(match.groupValues[2]) &&
        !groceryIntentHasNominalClausePredicate(match.groupValues[2])
}

/** Mirrors the backend omission guard; NOT a general semantic/negation parser. */
internal fun intentV2HasCorrectionCue(text: String, allowPropertyContrast: Boolean = true,
                                    allowLocalAvailability: Boolean = false): Boolean {
    val characters = text.toCharArray()
    if (allowLocalAvailability) intentV2LocalAvailabilityGrammarRanges(text).forEach { range ->
        for (position in range) characters[position] = ' '
    }
    val scopedText = String(characters)
    if (hasUnprovenV2PropertyNegation(scopedText)) return true
    if (!allowPropertyContrast && namedPropertyContrast.matchEntire(scopedText) != null) return true
    val contrast = if (allowPropertyContrast) intentV2NamedPropertyContrast(scopedText) else null
    val cueText = contrast?.let { it.target + " " + it.positiveText } ?: scopedText
    val withoutDietPhrases = negativeAttribute.replace(stripIntentV2ExcludedOne(
        negativeSubstitution.replace(excludedAttributeVariant.replace(cueText, " "), " ")), " ")
    return groceryIntentExplicitOrderEdit.containsMatchIn(withoutDietPhrases) || Regex(
        "(?<![\\p{L}\\p{N}_\\p{M}])(?:no|not|nahi|nahin|sorry|rather|or|ya|change|" +
            "correct|mat|bina|bagair|except|unless|otherwise|only|another|sirf|agar|varna|warna|" +
            "lagbhag|takriban|thoda|thodi|zyada|jyada|halka|halki|approximately|roughly|around|" +
            "kal|aaj|subah|shaam|raat|tomorrow|today|tonight|if|without|don't|dont)(?![\\p{L}\\p{N}_\\p{M}])" +
            "|(?<![\\p{L}\\p{N}_\\p{M}])[\\p{L}]+n['\u2019]t(?![\\p{L}\\p{N}_\\p{M}])" +
            "|\\b(?:jo|jab)\\s+(?:mil|stock|available|sasta)\\b" +
            "|\\b(?:at|by|before|after)\\s+\\d",
        RegexOption.IGNORE_CASE,
    ).containsMatchIn(withoutDietPhrases) || sequenceOf(Regex(
        "\\bko[ \\t]+([^,;!?\\r\\n]+?)[ \\t]+(?:(?:g|gm|gms|grams?|kg|kgs|kilos?|kilograms?|keji|ml|millilitres?|milliliters?|l|ltr|litres?|liters?|" +
            "pieces?|pcs|packs?|packets?|bags?|bottles?|cartons?|cups?|cans?|tins?|tubs?|jars?|box(?:es)?|bars?|blocks?|" +
            "pouch(?:es)?|sachets?|tubes?|loaf|loaves|refills?|rolls?|sprays?|bunch(?:es)?)[ \\t]+)?(?:hi[ \\t]+)?" +
            "(?:kar[ \\t]+(?:do|dena)|karna|rakh[ \\t]+do|rakhna|rakho)\\b", RegexOption.IGNORE_CASE,
    ), Regex(
        "([^\\s,;:!?]+)[\\t ]+(?:rakho|(?:rakh|kar|bana)[\\t ]+do)\\b", RegexOption.IGNORE_CASE,
    )).flatMap { it.findAll(withoutDietPhrases) }.any { match ->
        // Only an amount+verb is suspicious; 'kar do' can be a request suffix
        // and 'section bana do' is an independently proven listing heading.
        val atom = match.groupValues[1]
        atom.any { it in '0'..'9' } || atom.lowercase(Locale.ROOT) in setOf("zero", "shunya") ||
            parseIntentCorrectionAmount(atom) != null
    }
}

// A recognised diet phrase is exempt only inside owned, grounded item evidence.
// Left outside the items it still changes the basket and must block it.
private fun negativeAttributePattern(negator: String) = Regex(
    "(?<![\\p{L}\\p{N}_\\p{M}.+/\\-\\u2212])(?:$negator[\\s-]+|(?:zero|0)(?:[\\s-]+(?:percent[\\s-]+)?|\\s*%[\\s-]*))" +
        "((?:added[\\s-]+)?(?:sugar|salt)|palm[\\s-]+oil|maida|" +
        "chemicals|preservatives|artificial[\\s-]+(?:colours|flavours))\\b" +
        "(?![\\w-])(?!\\s+(?:free|substitutions?|substitutes?|versions?)\\b)", RegexOption.IGNORE_CASE,
)
private val negativeAttribute = negativeAttributePattern("no")
// Lexical classification only; never feed this into ownership/property policy.
private val compositionDietAttribute = negativeAttributePattern("(?:no|without)")

/** Grammar only for a local nominal-product suffix. Property preservation and
 * polarity still require the complete, unchanged item-evidence checks. */
internal fun groceryIntentIsClosedNominalProperty(text: String): Boolean =
    closedNominalProperty.matches(text) || negativeAttribute.matches(text)

private val closedNominalProperty = Regex("(?:$V2_PROTECTED_PROPERTY_PATTERN)", RegexOption.IGNORE_CASE)

// Mirrors the adjacent claim-qualifier grammar in grocery_intent_corrections.py.
// Never upgrade "almost zero sugar" to strict absence. A separate property such
// as "low fat ... zero sugar" must not be treated as a qualifier of zero sugar.
private val absencePrefixQualifier = Regex(
    "\\b(?:(?:almost|nearly|possibly|maybe|perhaps|potentially|apparently|roughly|" +
        "approximately|around|supposedly|allegedly|probably|likely|sometimes)(?:[\\t -]+(?:completely|entirely|totally|absolutely|fully))?" +
        "|(?:may|might|could|cannot|never|not|can['\\u2019]?t|isn['\\u2019]?t|aren['\\u2019]?t)(?:[\\t -]+(?:be|have|contain|quite)){0,2}" +
        "|low|less|reduced)[\\t ,:-]*$", RegexOption.IGNORE_CASE,
)
private val absenceSuffixQualifier = Regex(
    "^[\\t ,:-]*(?:maybe|perhaps|possibly|probably|likely|apparently|allegedly|" +
        "added|used|mixed|sprinkled|coated|has|have|had|is|was|were|being|" +
        "per|in|on|to|from|for|syrup|coating|cubes?)\\b", RegexOption.IGNORE_CASE,
)
private val propertyUncertainty = Regex(
    "\\b(?:maybe|perhaps|possibly|probably|likely|potentially|apparently|allegedly|" +
        "supposedly|almost|nearly|roughly|approximately|sometimes|partially|mostly|" +
        "somewhat|preferably|ideally|optional|shayad)\\b", RegexOption.IGNORE_CASE,
)
private val protectedPropertyAssertion = Regex("\\b(?:$V2_PROTECTED_PROPERTY_PATTERN)\\b", RegexOption.IGNORE_CASE)
private fun hasUncertainV2PropertyAssertions(fragments: List<String>): Boolean =
    // Owner-level denial across separately bound fragments, never joined proof.
    fragments.any { protectedPropertyAssertion.containsMatchIn(it) } &&
        fragments.any { propertyUncertainty.containsMatchIn(it) }

private fun hasConflictingV2PropertyAssertions(text: String): Boolean {
    // Denial-only closed opposites, mirrored by grocery_intent_corrections.py.
    // Never use this as proof that other property combinations are meaningful.
    val canonical = normalizeV2SweetenedProperty(text.replace('_', ' '))
    fun has(pattern: String) = Regex("(?<![\\w-])(?:$pattern)(?![\\w-])", RegexOption.IGNORE_CASE)
        .containsMatchIn(canonical)
    return has("unsweetened|(?:un|non)[ \\t-]+sweetened") && has("sweetened") ||
        has("low[ \\t-]*fat") && has("full[ \\t-]+(?:cream|fat)")
}
private fun hasQualifiedV2NegativeIngredient(text: String): Boolean =
    hasQualifiedIngredientMatches(text, negativeAttribute.findAll(text))

internal fun hasQualifiedIngredientMatches(text: String, matches: Sequence<MatchResult>): Boolean = matches.any {
    absencePrefixQualifier.containsMatchIn(text.substring(0, it.range.first)) ||
        absenceSuffixQualifier.containsMatchIn(text.substring(it.range.last + 1))
}

private fun intentV2IngredientPropertyEquivalents(text: String): Set<String> {
    if (hasQualifiedV2NegativeIngredient(text)) return emptySet()
    return negativeAttribute.findAll(text).mapNotNull {
        equivalentExclusionProperties[intentV2IngredientPhrase(it.groupValues[1])]
    }.toSet()
}

private fun intentV2IngredientPhrase(value: String): String =
    value.lowercase(Locale.ROOT).trim().replace(Regex("[\\s_-]+"), " ")

/** A zero-valued ingredient claim owns only its own numeric atom, never a count. */
internal fun intentV2ZeroIngredientRanges(source: String, name: String, required: List<String>,
                                        excluded: List<String>): List<IntRange> =
    intentV2ProvenIngredientRanges(source, name, required, excluded).filter {
        Regex("^(?:zero|0)(?=[\\s%\\-])", RegexOption.IGNORE_CASE).containsMatchIn(source.substring(it))
    }

internal fun intentV2ProvenIngredientRanges(source: String, name: String, required: List<String>,
                                          excluded: List<String>): List<IntRange> =
    retainedIngredientRanges(source, name, required, excluded, negativeAttribute)

/** Classify retained diet words only; these ranges confer no numeric ownership. */
internal fun compositionDietClaimRanges(source: String, name: String, required: List<String>,
                                       excluded: List<String>): List<IntRange> =
    retainedIngredientRanges(source, name, required, excluded, compositionDietAttribute)

private fun retainedIngredientRanges(source: String, name: String, required: List<String>,
                                     excluded: List<String>, pattern: Regex): List<IntRange> {
    if (hasQualifiedIngredientMatches(source, pattern.findAll(source))) return emptyList()
    val exclusions = excluded.map(::intentV2IngredientPhrase).toSet()
    val properties = intentV2PropertyTokens(name + " " + required.joinToString(" "))
    return pattern.findAll(source).filter { match ->
        val ingredient = intentV2IngredientPhrase(match.groupValues[1])
        val equivalent = equivalentExclusionProperties[ingredient]
        ingredient in exclusions || equivalent in properties
    }.map { it.range }.toList()
}
