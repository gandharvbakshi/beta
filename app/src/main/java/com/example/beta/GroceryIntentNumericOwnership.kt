package com.example.beta

import com.example.beta.automation.IntentLocalAmount
import com.example.beta.automation.IntentQuantityOrigin
import com.example.beta.automation.IntentQuantityProvenance
import com.example.beta.automation.IntentContainerContentsProof
import com.example.beta.automation.IntentSourceCountRole
import com.example.beta.automation.ParsedItem
import com.example.beta.automation.ProductLexicon
import com.example.beta.automation.Quantity
import java.math.BigDecimal
import java.text.Normalizer
import java.util.Locale

/** Source-local closed roles, not general numeric semantics or cart permission. */
internal fun GroceryIntentReviewV2ReadyRow.numericOwnershipIssue(compositionContext: String = ""): String? {
    val source = evidence.primary.text
    if (nonRetailMeasurePrefix.containsMatchIn(source)) return "non_retail_measure_as_purchase"
    val literals = numericLiteralRoles(source, item.name, item.required, item.excluded)
    if (literals.any { it.role == "unpreserved_descriptor" }) return "numeric_descriptor_not_preserved"
    val contents = sourceContainerAmountOwners(literals)
    val bindings = mutableListOf<NumericOwner>()
    for (isPack in listOf(false, true)) {
        val atom = if (isPack) item.packText else item.quantityText
        val unit = if (isPack) item.packUnit else item.quantityUnit
        val value = if (isPack) item.packValue else item.quantity
        if (atom == null) {
            if (value != null) return "numeric_atom_unbound"
            continue
        }
        if (value == null || parseIntentCorrectionAmount(atom)?.compareTo(value) != 0) return "numeric_atom_unbound"
        val candidates = numericAmountCandidates(source, atom, unit).filter { candidate ->
            literals.none { it.overlaps(candidate) }
        }
        // Wire V2 has no model-selected numeric window. Reconstruct a unique
        // binding from literal unit roles; never arbitrarily pick an occurrence.
        val ordinary = if (isPack) candidates.filter { !numericEachAfter(source, it.end, unit) } else candidates
        val selected = contents?.let { if (isPack) it.pack else it.quantity }
            ?: (ordinary.ifEmpty { candidates }).singleOrNull() ?: return "numeric_atom_unbound"
        if (unit in setOf("count", "pack", "piece") &&
            nonRetailMeasureAfterAmount.containsMatchIn(source.substring(selected.end))) {
            return "non_retail_measure_as_purchase"
        }
        if (!isPack && intentQuantityIdentityConflict(source, selected.start, selected.end, value, unit, item.name)) {
            return "quantity_name_atom_reused"
        }
        bindings += selected.copy(role = if (isPack) "pack" else "quantity")
    }
    numericPartitionIssue(source, bindings, literals, item.packText, item.packUnit)?.let { return it }
    compositionPackRoleIssue(source, bindings, item.packUnit, item.quantityUnit, compositionContext,
        item.name, item.required, item.excluded)?.let { return it }
    if (item.quantity != null && item.packValue != null &&
        physicalUnitSizeIssueCode(source, item.quantityUnit) == "physical_piece_size_ambiguous") {
        physicalUnitSizeIssue(source, bindings, item.quantityUnit, item.packUnit,
            localRetailContainerReceipt())?.let { return it }
    }
    discretePackRoleIssue()?.let { return it }
    for (support in evidence.supporting) {
        numericPartitionIssue(support.quote.text, emptyList(),
            numericLiteralRoles(support.quote.text, item.name, item.required, item.excluded), null, "unspecified")?.let { return it }
    }
    return null
}

/** Literal nutrition/product words are not a proof of physical pack weight.
 * Mirrors require_composition_pack_role; server role labels cannot bypass it. */
private fun compositionPackRoleIssue(source: String, bindings: List<NumericOwner>, unit: String, quantityUnit: String,
                                    context: String, name: String = "", required: List<String> = emptyList(),
                                    excluded: List<String> = emptyList()): String? {
    val aliases = numericPhysicalUnits[unit] ?: return null
    val pack = bindings.singleOrNull { it.role == "pack" } ?: return null
    val nutrient = "(?:protein|fib(?:re|er)|carbs?|carbohydrates?|fat|sugar|sodium|calcium|calories?|kcal)\\b"
    fun pattern(value: String) = Regex(value.replace("\\s", servingSpace), RegexOption.IGNORE_CASE)
    fun hasComposition(text: String, retainedClaims: List<IntRange> = emptyList()) = pattern("\\b$nutrient").findAll(text).any {
        !pattern("\\b(?:no(?:[\\s-]+added)?|low|reduced|less|zero)[\\s-]+$").containsMatchIn(text.substring(0, it.range.first)) &&
            pattern("[\\s-]+free\\b").find(text.substring(it.range.last + 1))?.range?.first != 0 &&
            retainedClaims.none { claim -> claim.first <= it.range.first && it.range.last <= claim.last }
    }
    val contextualComposition = hasComposition(context)
    if (!contextualComposition && !hasComposition(source)) return null
    // Reuse original, unqualified claims retained by this row. An itemless
    // receipt or a separate context string cannot borrow this exemption.
    val retainedClaims = compositionDietClaimRanges(source, name, required, excluded)
    val retainedDietOnly = !contextualComposition && !hasComposition(source, retainedClaims)
    val before = source.substring(0, pack.start)
    val after = source.substring(pack.end)
    val measure = pattern("^\\s*-?\\s*$aliases\\b").find(after)
        ?: return "composition_pack_role_unproven"
    val tail = after.substring(measure.value.length)
    val tailStart = pack.end + measure.value.length
    val quantity = bindings.singleOrNull { it.role == "quantity" && quantityUnit in setOf("count", "pack", "piece") }
    val residue = StringBuilder(source).replace(pack.start, tailStart, " ".repeat(tailStart - pack.start))
    quantity?.let { residue.replace(it.start, it.end, " ".repeat(it.end - it.start)) }
    val commodity = "(?:protein|fib(?:re|er)|carbs?|carbohydrates?|fat|sugar|sodium|calcium)\\b"
    if (!contextualComposition && pattern("\\s*$commodity[\\s.!?]*").matches(residue)) return null
    val denominator = pattern("\\b(?:per(?:\\s+(?:serving|portion|$discretePackContainers)(?:\\s+of)?)?|(?:serving|portion)\\s+of)\\s*$").containsMatchIn(before)
    val nutrientEnd = pattern("^\\s*$nutrient").find(tail)
    val numerator = pattern("^\\s*$nutrient\\s+(?:per|each|with|containing|contains|in|for|of|and|plus|without|no)\\b").containsMatchIn(tail) ||
        (nutrientEnd != null && (!pattern("^\\s+[A-Za-z]").containsMatchIn(tail.substring(nutrientEnd.range.last + 1)) ||
            Regex("[a-z]+", RegexOption.IGNORE_CASE).findAll(tail).any { it.value.lowercase(Locale.ROOT) in groceryIntentNominalClauseUnsafeWords } ||
            groceryIntentHasNominalClausePredicate(tail)))
    val terminalTail = if (quantity != null && quantity.start >= tailStart)
        tail.replaceRange(quantity.start - tailStart, quantity.end - tailStart, " ".repeat(quantity.end - quantity.start)) else tail
    val terminalNutrient = pattern("\\s*$nutrient[\\s.!?,;]*").matches(terminalTail)
    val total = pattern("\\b(?:total|altogether|kul)\\s*$").containsMatchIn(before) ||
        pattern("^\\s*(?:in\\s+total|total|altogether|kul)\\b").containsMatchIn(tail)
    val retail = !total && (pattern("\\b$discretePackContainers\\s+of\\s*$").containsMatchIn(before) ||
        pattern("^\\s*(?:(?:ka|ki|ke|wala|wali|wale|tetra)\\s+)?$discretePackContainers\\b").containsMatchIn(tail))
    val each = pattern("\\s*(?:each|per\\s+(?:piece|pc)|har\\s+ek)[\\s.!?]*").matches(tail)
    val weighs = pattern("\\beach\\s+$discretePackContainers\\s+weighs\\s*$").containsMatchIn(before) &&
        pattern("[\\s.!?]*").matches(tail)
    return "composition_pack_role_unproven".takeIf {
        denominator || numerator || terminalNutrient || total || (!retainedDietOnly && !(retail || each || weighs))
    }
}

/** Denial only for the receipt compiler's independently bound original atoms.
 * Reuse the nutrition guard before a receipt can short-circuit numeric checks. */
internal fun sourceRetailCompositionIssue(source: String, count: IntentLocalAmount, size: IntentLocalAmount): String? =
    compositionPackRoleIssue(source, listOf(
        NumericOwner(count.startUtf16, count.endUtf16, "quantity"),
        NumericOwner(size.startUtf16, size.endUtf16, "pack"),
    ), size.unit, "count", "")

/** Groups/corrections supply final values, never an unstated per-piece size role. */
internal fun GroceryIntentReviewV2ReadyRow.physicalUnitSizeIssue(
    final: GroceryIntentItem, group: GroceryIntentReviewV2Group? = null,
): String? {
    val source = evidence.primary.text
    if (final.quantity == null || final.packValue == null ||
        !physicalUnitSizeApplies(final.quantityUnit, final.packUnit)) return null
    val issue = physicalUnitSizeIssueCode(source, final.quantityUnit)
    val pack = localPackSizeEvidence() ?: return issue
    if (item.packUnit !in numericPhysicalUnits) return issue
    val quantity = localQuantityEvidence()
    if (item.quantityText != null && quantity == null) return issue
    val bindings = listOfNotNull(
        quantity?.let { NumericOwner(it.startUtf16, it.endUtf16, "quantity") },
        NumericOwner(pack.startUtf16, pack.endUtf16, "pack"),
    )
    val groupReceipt = final.quantityUnit == "pack" && groupRetailContainerReceipt(group)
    return physicalUnitSizeIssue(source, bindings, final.quantityUnit, item.packUnit,
        groupReceipt || localRetailContainerReceipt() || (group == null && corrections.isEmpty() && genericContainerRange() != null))
}

private fun physicalUnitSizeApplies(quantityUnit: String, packUnit: String) =
    packUnit in numericPhysicalUnits && quantityUnit in setOf("count", "pack", "piece")

private fun physicalUnitSizeIssueCode(source: String, quantityUnit: String) =
    if (quantityUnit == "piece" || Regex("\\b(?:pieces?|pcs?)\\b", RegexOption.IGNORE_CASE).containsMatchIn(source))
        "physical_piece_size_ambiguous" else "physical_unit_size_ambiguous"

/** Exact arithmetic does not prove whether a physical measure is total or each.
 * Bindings are ORIGINAL local atoms; a group-inherited count has no local atom. */
private fun physicalUnitSizeIssue(source: String, bindings: List<NumericOwner>, quantityUnit: String, packUnit: String,
                                  sourceRetailReceipt: Boolean = false): String? {
    if (!physicalUnitSizeApplies(quantityUnit, packUnit)) return null
    val issue = physicalUnitSizeIssueCode(source, quantityUnit)
    val pieces = issue == "physical_piece_size_ambiguous"
    val aliases = numericPhysicalUnits.getValue(packUnit)
    val pack = bindings.singleOrNull { it.role == "pack" } ?: return issue
    val before = source.substring(0, pack.start)
    val after = source.substring(pack.end)
    val measure = Regex("^[ \\t]*-?[ \\t]*$aliases\\b", RegexOption.IGNORE_CASE).find(after)
        ?: return issue
    val tail = after.substring(measure.value.length)
    // A pack marker or "each" cannot override a conflicting ORIGINAL total
    // or nutrition role. This is independent of server amount-role metadata.
    val total = Regex("\\b(?:total|altogether|kul)\\s*$", RegexOption.IGNORE_CASE).containsMatchIn(before) ||
        Regex("^\\s*(?:in\\s+total|total|altogether|kul)\\b", RegexOption.IGNORE_CASE).containsMatchIn(tail)
    val nutrition = Regex(
        "^\\s*(?:protein|fib(?:re|er)|carbs?|carbohydrates?|fat|sugar|sodium|calcium|calories?|kcal)\\b\\s+per\\s+(?:serving|portion|100\\s*(?:g|grams?|ml))\\b",
        RegexOption.IGNORE_CASE,
    ).containsMatchIn(tail)
    if (total || nutrition) return issue
    if (!pieces && Regex("\\b(?:another|additional|separate)\\b", RegexOption.IGNORE_CASE).containsMatchIn(source)) return issue
    if (!pieces && sourceRetailReceipt) return null
    val each = Regex(
        "[ \\t]*(?:each|per[ \\t]+(?:piece|pc)|har[ \\t]+ek)[ \\t]*[.!?]?[ \\t]*",
        RegexOption.IGNORE_CASE,
    ).matches(tail)
    val suffix = Regex("^\\s*(?:(?:ka|ki|ke|wala|wali|wale|tetra)\\s+)?$discretePackContainers\\b",
        RegexOption.IGNORE_CASE).find(tail)
    val prefix = Regex("\\b$discretePackContainers\\s+of\\s*$", RegexOption.IGNORE_CASE).find(before)
    val retail = prefix != null || suffix != null
    var retailScope = true
    if (retail) {
        val quantity = bindings.singleOrNull { it.role == "quantity" }
        val phraseStart = prefix?.range?.first ?: pack.start
        val secondDeterminer = (quantity == null || quantity.end <= phraseStart) &&
            Regex("\\b(?:a|an|another|additional|separate|one|ek|single|\\d+)\\s*$", RegexOption.IGNORE_CASE)
                .containsMatchIn(source.substring(quantity?.end ?: 0, phraseStart))
        retailScope = !secondDeterminer && (quantity == null || quantity.end <= phraseStart) && sameDiscreteContentsPhrase(
            source.substring(0, phraseStart).let { if (quantity == null) it else it.removeRange(quantity.start, quantity.end) },
            tail.substring(if (prefix == null) suffix?.value?.length ?: 0 else 0)
                .replaceFirst(Regex("^\\s+of\\s+", RegexOption.IGNORE_CASE), ""),
        )
        if (!pieces && quantity != null && quantity.start >= pack.end + measure.value.length) {
            val contents = tail.substring(if (prefix == null) suffix?.value?.length ?: 0 else 0)
            val contentsStart = source.length - contents.length
            if (quantity.start >= contentsStart) retailScope = sameDiscreteContentsPhrase(
                source.substring(0, phraseStart),
                contents.removeRange(quantity.start - contentsStart, quantity.end - contentsStart)
                    .replaceFirst(Regex("^[ \\t]+of[ \\t]+", RegexOption.IGNORE_CASE), ""),
            )
        }
    }
    val scope = StringBuilder(source)
    bindings.forEach { scope.replace(it.start, it.end, " ".repeat(it.end - it.start)) }
    if (!pieces) {
        // Source-local receipts, never the server status or final model fields.
        // A group-inherited count cannot manufacture this adjacency.
        val quantity = bindings.singleOrNull { it.role == "quantity" }
        val adjacentCount = quantity != null && quantity.end < pack.start &&
            Regex("[A-Za-z]+(?:[ -][A-Za-z]+)*").matches(source.substring(quantity.start, quantity.end)) &&
            Regex("[0-9]+(?:\\.[0-9]+)?").matches(source.substring(pack.start, pack.end)) &&
            Regex("[ \\t]+").matches(source.substring(quantity.end, pack.start))
        val ordinary = adjacentCount && (!retail || retailScope) &&
            !discretePackBreak.containsMatchIn(scope) && !Regex("[.!?](?![ \\t]*$)").containsMatchIn(scope) &&
            !Regex("\\b(?:another|additional|separate)\\b", RegexOption.IGNORE_CASE).containsMatchIn(scope)
        if (ordinary) return null
        val weighs = Regex(",[ \\t]*each[ \\t]+($discretePackContainers)[ \\t]+weighs[ \\t]*$", RegexOption.IGNORE_CASE).find(before)
        if (quantity != null && weighs != null && Regex("[ \\t]*[.!?]?[ \\t]*").matches(tail)) {
            val base = scope.substring(0, weighs.range.first)
            if (!discretePackBreak.containsMatchIn(base) && !Regex("[.!?]").containsMatchIn(base) &&
                Regex("\\b${Regex.escape(weighs.groupValues[1])}s?\\b", RegexOption.IGNORE_CASE).containsMatchIn(base)) return null
        }
    }
    return issue.takeIf {
        discretePackBreak.containsMatchIn(scope) || Regex("[.!?](?![ \\t]*$)").containsMatchIn(scope) || !retailScope || (!retail && !each)
    }
}

/** Original same-product proof, never the server's amountRoleStatus. A separate
 * product/count cannot become bundled contents merely because the model kept
 * both nouns. Mirrors require_discrete_pack_role in the backend. */
internal fun GroceryIntentReviewV2ReadyRow.discretePackRoleIssue(final: GroceryIntentItem? = null, requireLeading: Boolean = false): String? {
    val finalUnit = final?.packUnit ?: item.packUnit
    if (item.packUnit != "piece") {
        return "discrete_pack_role_unproven".takeIf { finalUnit == "piece" }
    }
    val pack = localPackSizeEvidence() ?: return "discrete_pack_role_unproven"
    val quantity = localQuantityEvidence()
    if (item.quantityText != null && quantity == null) return "discrete_pack_role_unproven"
    return discretePackSourceIssue(evidence.primary.text, quantity?.startUtf16, quantity?.endUtf16,
        pack.startUtf16, pack.endUtf16, requireLeading)
}

/** The legacy switch-off route has no quoted atoms. Recover only unique exact
 * amount spans and validate every numeric role before allowing piece contents. */
internal fun GroceryIntentItem.legacyDiscretePackRoleIssue(): String? {
    if (packUnit != "piece") return null
    val literals = numericLiteralRoles(sourceText, name, required, excluded)
    val contents = sourceContainerAmountOwners(sourceText, quantity, quantityUnit, packValue, packUnit, literals)
    val bindings = mutableListOf<NumericOwner>()
    for (isPack in listOf(false, true)) {
        val value = (if (isPack) packValue else quantity) ?: continue
        val unit = if (isPack) packUnit else quantityUnit
        val bound = contents?.let { if (isPack) it.pack else it.quantity }
            ?: legacyBoundAmount(value, unit) ?: return "discrete_pack_role_unproven"
        if (!isPack && intentQuantityIdentityConflict(sourceText, bound.start, bound.end, value, unit, name)) {
            return "quantity_name_atom_reused"
        }
        if (unit in setOf("count", "pack", "piece") &&
            nonRetailMeasureAfterAmount.containsMatchIn(sourceText.substring(bound.end))) {
            return "non_retail_measure_as_purchase"
        }
        bindings += bound.copy(role = if (isPack) "pack" else "quantity")
    }
    val pack = bindings.singleOrNull { it.role == "pack" } ?: return "discrete_pack_role_unproven"
    numericPartitionIssue(sourceText, bindings, literals, sourceText.substring(pack.start, pack.end), packUnit)?.let { return it }
    val purchase = bindings.singleOrNull { it.role == "quantity" }
    return discretePackSourceIssue(sourceText, purchase?.start, purchase?.end, pack.start, pack.end, quantity == null)
}

private fun GroceryIntentItem.legacyBoundAmount(value: BigDecimal, unit: String): NumericOwner? =
    legacyBoundAmount(sourceText, value, unit)

private fun legacyBoundAmount(source: String, value: BigDecimal, unit: String): NumericOwner? {
    // Enumerate measured numeric prefixes too (500g); the existing binder
    // still proves the complete token and matching physical unit boundaries.
    val endBoundary = if (unit in setOf("g", "kg", "ml", "l")) "" else "(?![\\p{L}\\p{N}_\\p{M}.+/])"
    return Regex("(?<![\\p{L}\\p{N}_\\p{M}.+/\\-])(?:$numericAmount)$endBoundary", RegexOption.IGNORE_CASE)
        .findAll(source).filter { parseIntentCorrectionAmount(it.value)?.compareTo(value) == 0 }
        .mapNotNull { numericAmountCandidates(source, it.value, unit).singleOrNull() }.distinct().toList().singleOrNull()
}

/** Reuse the V1 unique arithmetic binder; no second parser for wrapper counts. */
internal fun GroceryIntentItem.legacyLocalAmount(value: BigDecimal, unit: String): IntentLocalAmount? =
    sourceLocalAmount(sourceText, value, unit)

/** The same unique atom binder can consume source evidence without constructing
 * a model item or trusting its quantity/unit labels. */
internal fun sourceLocalAmount(source: String, value: BigDecimal, unit: String): IntentLocalAmount? =
    legacyBoundAmount(source, value, unit)?.let { bound ->
        IntentLocalAmount(source, bound.start, bound.end, source.substring(bound.start, bound.end), value, unit)
    }

/** Bind an already located original atom with the same contextual grammar.
 * This includes ordinary a/an determiners supported by that grammar; a scan
 * for numeric words alone cannot discover them. */
internal fun sourceQuotedLocalAmount(source: String, atom: String, unit: String): IntentLocalAmount? {
    val value = parseIntentCorrectionAmount(atom) ?: return null
    val bound = numericAmountCandidates(source, atom, unit).singleOrNull() ?: return null
    return IntentLocalAmount(source, bound.start, bound.end, atom, value, unit)
}

/** Singular article attached to a physical unit, never a default count or an
 * article elsewhere in a sentence. The complete noun stays in the lookup.
 * Project for parsing only; cart proof still uses the exact original span. */
internal fun sourceArticleMeasure(source: String): Pair<IntentLocalAmount, String>? {
    val article = source.takeWhile { it.isLetter() }.takeIf { it.equals("a", true) || it.equals("an", true) } ?: return null
    val (unit, token) = listOf("g", "kg", "ml", "l").mapNotNull { u ->
        GroceryIntentPhysicalUnitMatcher.after(source, article.length, u)?.let { u to it }
    }.singleOrNull() ?: return null
    if (source.substring(article.length, token.start) !in setOf(" ", "\t")) return null
    val tail = source.substring(token.end).removeSuffix(".")
    val noun = Regex("^[ \\t]+of[ \\t]+(.+)$", RegexOption.IGNORE_CASE).matchEntire(tail)?.groupValues?.get(1) ?: return null
    if (!groceryIntentRetailNominalValid(noun) || sourceNominalHasRelation(noun) || groceryIntentContainsNumericAtom(noun))
        return null
    val local = sourceQuotedLocalAmount(source, article, unit) ?: return null
    return (local to noun).takeIf { local.startUtf16 == 0 && local.endUtf16 == article.length && local.value.compareTo(BigDecimal.ONE) == 0 }
}

/** V1 and model-off adapters share V2's exact arithmetic, literal classification
 * and numeric partition. This is a DENY gate, not a purchase-role receipt.
 * Values may be normalized between physical units, never between dimensions. */
internal fun GroceryIntentItem.legacyNumericOwnershipIssue(): String? {
    if (sourceCompositeRetailReceipt(sourceText) != null) {
        val receipt = legacyCompositeRetailReceipt() ?: return "composite_model_disagreement"
        return compositeRetailNumericIssue(receipt, name, required, excluded, quantityUnit)
    }
    // Closed receipts already own every number (including compound dozens and
    // source sizes omitted by the producer). Do not replace them with a weaker
    // token scan; their context/role checks still run independently.
    if (legacyRetailCountReceipt() != null || legacySourcePieceReceipt() != null ||
        legacyGenericContainerReceipt() != null) return null
    val pack = legacyNumericPack()
    return sourceNumericOwnershipIssue(sourceText, name, required, excluded,
        quantity, quantityUnit, pack.first, pack.second)
}

/** Closed source roles enter the existing partition/composition checks. Keep
 * this OUT of sourceNumericOwnershipIssue: its untyped/default-one callers
 * must not acquire composite authority merely from a successful numeric scan. */
internal fun compositeRetailNumericIssue(receipt: CompositeRetailReceipt, name: String,
                                        required: List<String>, excluded: List<String>, quantityUnit: String): String? {
    if (sourceCompositeRetailReceipt(receipt.source) != receipt) return "composite_source_changed"
    if (nonRetailMeasurePrefix.containsMatchIn(receipt.source)) return "non_retail_measure_as_purchase"
    val literals = numericLiteralRoles(receipt.source, name, required, excluded)
    fun owner(amount: IntentLocalAmount, role: String) = NumericOwner(amount.startUtf16, amount.endUtf16, role)
    val bindings = listOf(owner(receipt.requestedCount, "quantity"), owner(receipt.innerMeasure, "pack"),
        owner(receipt.skuFactor, "sku_factor"))
    numericPartitionIssue(receipt.source, bindings, literals, receipt.innerMeasure.atom, receipt.innerMeasure.unit)
        ?.let { return it }
    return compositionPackRoleIssue(receipt.source, bindings, receipt.innerMeasure.unit, quantityUnit, "",
        name, required, excluded)
}

private fun GroceryIntentItem.legacyNumericPack(): Pair<BigDecimal?, String> {
    if (packValue != null) return packValue to packUnit
    val identity = sourceRetailIdentityConstraint(sourceText)?.literalQuery() ?: (name + " " + required.joinToString(" "))
    val measure = groceryPackMeasures(identity).singleOrNull() ?: return null to "unspecified"
    return measure.baseValue to when (measure.dimension) {
        GroceryPackDimension.WEIGHT -> "g"
        GroceryPackDimension.VOLUME -> "ml"
        GroceryPackDimension.PIECES -> "piece"
        GroceryPackDimension.PACKS -> "pack"
    }
}

/** A producer's conventional one is not an explicit count. Keep it a visible
 * default only if the complete source has no unowned purchase amount. */
internal fun GroceryIntentItem.legacyImplicitUnitDefault(): Boolean {
    if (quantity?.compareTo(BigDecimal.ONE) != 0 || quantityUnit !in setOf("count", "pack", "piece")) return false
    val pack = legacyNumericPack()
    return sourceNumericOwnershipIssue(sourceText, name, required, excluded, null, "unspecified", pack.first, pack.second,
        allowDefaultOne = false) == null
}

/** Retain an owned original clause; query remains the retrieval identity.
 * Shared/implicit splits cannot acquire a closed receipt from guessed mapping. */
internal fun ParsedItem.withDeterministicSourceEvidence(
    source: String, instruction: String, ownsClause: Boolean, receiptSource: String = source,
    compositeScopeProven: Boolean = false,
): ParsedItem {
    val base = if (ownsClause) copy(rawText = source) else this
    if (groceryIntentTerminalExcludedPropertyMatch(source) != null &&
        (!ownsClause || !groceryIntentTerminalPropertyScopeProven(instruction, source) ||
            groceryIntentRetainedTerminalPropertyView(source, query + " " + strictMatchPhrase.orEmpty(), avoidPhrases) == null))
        return base.copy(unprovenIdentityScope = true, requiresIntentQuantityProof = true)
    if (quantitySignal != null || requiresIntentQuantityProof || intentQuantityProvenance != null) return base
    fun held() = base.copy(requiresIntentQuantityProof = true, quantitySignal = "quantity source needs review")
    if (ownsClause && (quantity is Quantity.Count || quantity is Quantity.Default) && avoidPhrases.isEmpty() &&
        intentQuantityCatalogueCheck == null && ingredientProvenance == null && containerContentsProof == null) {
        sourceCompositeRetailReceipt(receiptSource)?.let { receipt ->
            if (!compositeScopeProven ||
                !groceryIntentSourceClauseContextProven(instruction, receiptSource, receiptSource.length)) return held()
            return base.copy(rawText = receiptSource).withCompositeRetailReceipt(receipt)
                .takeIf { it.validCompositeRetailReceipt() != null } ?: held()
        }
        if (sourcePacketCountSyntax(receiptSource) != null &&
            !groceryIntentSourceClauseContextProven(instruction, receiptSource, receiptSource.length)) return held()
        val named = sourceNamedRetailCountReceipt(receiptSource)
        val pieces = sourcePiecePurchaseReceipt(receiptSource)
        if (named != null || pieces != null) {
            // Spoken amounts may be normalized for retrieval, but only the
            // exact receipt-owned span can contribute that equivalent token.
            val retrievalSource = pieces?.let { receipt -> receiptSource.replaceRange(
                receipt.amount.startUtf16, receipt.amount.endUtf16,
                receipt.amount.value.stripTrailingZeros().toPlainString(),
            ) } ?: receiptSource
            if (named != null && pieces != null ||
                !groceryIntentSourceClauseContextProven(instruction, receiptSource, pieces?.terminalEnd ?: receiptSource.length) ||
                !(intentEvidenceTokens(receiptSource) + intentEvidenceTokens(retrievalSource))
                    .containsAll(intentEvidenceTokens(query))) return held()
            if (named != null) return base.copy(rawText = receiptSource).withSourceRetailCountReceipt()
                .takeIf { it.validNamedRetailReceipt() != null } ?: held()
            val receipt = requireNotNull(pieces)
            val requested = Quantity.Count(receipt.amount.value.intValueExact())
            // A source number does not choose purchase quantity over variant
            // size. Preserve Default descriptors; only validate an existing
            // matching purchase count, never manufacture that interpretation.
            if (quantity != requested || retailPackCount ||
                sourceLocalAmount(receiptSource, receipt.amount.value, "piece") != receipt.amount) return held()
            val identity = receipt.literalIdentity()
            // The source compiler owns all noun/amount spans. Validate the same
            // local grounding and numeric contract as V1 before sharing its
            // provenance builder; whole-instruction context was checked above.
            val proposed = GroceryIntentItem(identity, receipt.amount.value, "piece", null, "unspecified",
                emptyList(), emptyList(), receiptSource, false)
            val grounding = groceryIntentGrounding(instruction, listOf(proposed))
            if (grounding.rejected.isNotEmpty() || grounding.review.isNotEmpty() ||
                proposed.legacyNumericOwnershipIssue() != null) return held()
            return base.copy(rawText = receiptSource, query = identity, strictMatchPhrase = identity, quantity = requested, logicalPieceCount = true,
                retailPackCount = false, requiresIntentQuantityProof = true,
                intentQuantityProvenance = receipt.provenance(requested, identity))
        }
    }
    if (quantity is Quantity.Default) return if (copy(rawText = source).untypedSourceNumericIssue() == null) base
        else held()
    val proof = base.numericSourceProvenance(source) ?: return held()
    return base.copy(requiresIntentQuantityProof = true, intentQuantityProvenance = proof)
}

/** Deterministically bound amounts, not an interpretation of what one SKU is.
 * All adapters reuse this snapshot and the same final numeric revalidation. */
private fun ParsedItem.numericSourceProvenance(source: String): IntentQuantityProvenance? {
    val local = numericSourceAmount(source) ?: return null
    return numericSourceProvenance(source, local)
}

/** Arithmetic evidence only. It does not establish the instruction's scope. */
private fun ParsedItem.numericSourceAmount(source: String): IntentLocalAmount? {
    val local = when (val amount = quantity) {
        is Quantity.Count -> sourceLocalAmount(source, amount.n.toBigDecimal(), "count")
        is Quantity.Weight -> sourceLocalAmount(source, amount.grams.toBigDecimal(), "g")
            ?: sourceLocalAmount(source, amount.grams.toBigDecimal().divide(BigDecimal(1000)), "kg")
        is Quantity.Volume -> sourceLocalAmount(source, amount.ml.toBigDecimal(), "ml")
            ?: sourceLocalAmount(source, amount.ml.toBigDecimal().divide(BigDecimal(1000)), "l")
        else -> null
    }
    val bound = local ?: sourceArticleMeasure(source)?.first?.takeIf { article ->
        val physical = GroceryPackMeasure.of(article.value, article.unit)
        when (val q = quantity) {
            is Quantity.Weight -> physical.dimension == GroceryPackDimension.WEIGHT && physical.baseValue.compareTo(q.grams.toBigDecimal()) == 0
            is Quantity.Volume -> physical.dimension == GroceryPackDimension.VOLUME && physical.baseValue.compareTo(q.ml.toBigDecimal()) == 0
            else -> false
        }
    } ?: return null
    if (copy(rawText = source).untypedSourceNumericIssue() != null) return null
    return bound
}

private fun ParsedItem.numericSourceProvenance(source: String, local: IntentLocalAmount): IntentQuantityProvenance =
    IntentQuantityProvenance(
        origin = IntentQuantityOrigin.SOURCE_NUMERIC,
        primaryText = source, rawText = rawText, resolvedQuantity = quantity, local = local,
        query = query, strictMatchPhrase = strictMatchPhrase, retailPackCount = retailPackCount,
        logicalPieceCount = logicalPieceCount, opaqueNumericLabels = groceryIntentOpaqueNumericLabels(query),
        numericSourceQuery = query,
    )

/** V1 calls this after grounding and numeric ownership validation. Parser
 * identities can deny an omitted product, never supply model identity/quantity.
 * Numeric attachment also preserves the independent dedicated receipt paths. */
internal fun ParsedItem.withLegacySourceEvidence(instruction: String): ParsedItem {
    val analysis = inspectLegacySourceCount(instruction)
    if (analysis.identityHeld) return copy(unprovenIdentityScope = true)
    if (!analysis.countEligible || requiresIntentQuantityProof) return this
    // Preserve the legacy contract: a locally correct amount is insufficient
    // when surrounding original wording has not been independently accounted for.
    val local = analysis.amount
    if (local == null || !groceryIntentSourceClauseContextProven(instruction, rawText, rawText.length))
        return copy(requiresIntentQuantityProof = true)
    return copy(requiresIntentQuantityProof = true, intentQuantityProvenance = numericSourceProvenance(rawText, local)
        .copy(terminalContext = GroceryIntentTerminalContext.bind(instruction, rawText)))
}

private class LegacySourceCountAnalysis(
    val identityHeld: Boolean = false,
    val countEligible: Boolean = false,
    val amount: IntentLocalAmount? = null,
)

/** Shared local checks; deliberately does not construct executable provenance. */
private fun ParsedItem.inspectLegacySourceCount(instruction: String): LegacySourceCountAnalysis {
    val terminalProperty = groceryIntentTerminalExcludedPropertyMatch(rawText)
    val propertyView = if (terminalProperty != null && groceryIntentTerminalPropertyScopeProven(instruction, rawText))
        groceryIntentRetainedTerminalPropertyView(rawText, query + " " + strictMatchPhrase.orEmpty(), avoidPhrases) else null
    if (terminalProperty != null && propertyView == null) return LegacySourceCountAnalysis(identityHeld = true)
    // These closed receipts already bind the whole original nominal and all
    // amounts. A legacy parser projection must not veto their stronger proof.
    if (validCompositeRetailReceipt() != null || validNamedRetailReceipt() != null || validGenericContainerReceipt() != null ||
        validSourcePieceAmount() != null) return LegacySourceCountAnalysis()
    fun identityTokens(text: String) = equivalentGroceryIntentIdentityTokens(groceryIntentFragmentIdentityTokens(text))
    // Positive-identity and count-consistency projection only: the proven
    // negative suffix cannot supply a product or a purchase count. The parser
    // can only veto the already proposed count; final partition, amount binding
    // and provenance below still read every atom from the ORIGINAL rawText.
    // A measured metadata clause (for example '1 litre each') is not a product.
    val originalView = propertyView ?: rawText
    val parsedSource = com.example.beta.automation.InstructionParser.parseSourceCountWitness(originalView, this)
    // Ignore only original measured metadata, not an arbitrary token-empty
    // parser query. A physical atom/unit must bind before it can be excluded.
    val countSource = parsedSource.filterNot { groceryIntentMeasuredMetadata(originalView, it.rawText) }
    val comparisonView = groceryIntentPhysicalUnitComparisonView(rawText, originalView)
    val identitySource = if (comparisonView == originalView) parsedSource
        else com.example.beta.automation.InstructionParser.parse(comparisonView)
    // A full quote is not coverage if the model silently drops one of its
    // products. Reuse parser-normalized identity only as a denial check; it
    // supplies neither the model's identity nor its purchase quantity.
    val retained = identityTokens(query)
    val grammarMasked = (propertyView ?: rawText).toCharArray()
    groceryIntentRetainedGenitiveRanges(rawText, intentEvidenceTokens(query)).forEach { range ->
        range.forEach { grammarMasked[it] = ' ' }
    }
    GroceryIntentTerminalContext.bind(instruction, rawText)?.spanFor(rawText)?.let { span ->
        (span.startUtf16 until span.endUtf16).forEach { grammarMasked[it] = ' ' }
    }
    // Subtract only tokens occurring exclusively in proven original grammar
    // spans. Repeated particles and every unexplained parser token still deny.
    // This projection never enters numeric binding, provenance or the query.
    val grammarOnly = identityTokens(rawText) - identityTokens(String(grammarMasked))
    val sourceIdentity = identitySource.flatMap { identityTokens(it.query) }.toSet() - grammarOnly
    val sourceExcluded = identitySource.flatMap { identityTokens(it.avoidPhrases.joinToString(" ")) }.toSet()
    val retainedExcluded = identityTokens(avoidPhrases.joinToString(" "))
    // Preserve polarity: a positive request cannot move into exclusions, and
    // an excluded attribute cannot be supplied by (or appear in) the query.
    if (!retained.containsAll(sourceIdentity) ||
        !retainedExcluded.containsAll(sourceExcluded) || sourceExcluded.any { it in retained })
        return LegacySourceCountAnalysis(identityHeld = true)
    if (quantity !is Quantity.Count || quantitySignal != null ||
        intentQuantityProvenance != null || intentQuantityCatalogueCheck != null ||
        containerContentsProof != null || ingredientProvenance != null || logicalPieceCount ||
        cartQuantityMode != com.example.beta.automation.IntentCartQuantityMode.TARGET ||
        sourceNamedRetailCountReceipt(rawText) != null || sourcePiecePurchaseReceipt(rawText) != null ||
        sourceHasGenericContainer(rawText)) return LegacySourceCountAnalysis()
    // A failed witness is an explicit hold, including bare Count rows whose
    // model unit is not 'pack'; it must not reopen the unproved-count fallback.
    fun unprovenCount() = LegacySourceCountAnalysis(countEligible = true)
    val requestedQuantity = quantity
    fun ParsedItem?.witnessesCount() = this != null && this.quantity == requestedQuantity &&
        this.quantitySignal == null && this.intentQuantityProvenance?.origin == IntentQuantityOrigin.SOURCE_NUMERIC
    // Unit comparison cannot supply a parser count witness. Metadata exclusion
    // above and count eligibility both read the original view only.
    if (!countSource.singleOrNull().witnessesCount()) {
        val amount = sourceLocalAmount(rawText, quantity.n.toBigDecimal(), "count") ?: return unprovenCount()
        val prefix = rawText.substring(0, amount.startUtf16).trim(' ', '\t')
        if (!sourcePieceNominalValid(prefix) ||
            ProductLexicon.knownProductTokens.any { (_, tokens) -> tokens == ProductLexicon.tokenize(prefix) } ||
            !retained.containsAll(identityTokens(rawText) - grammarOnly) ||
            !com.example.beta.automation.InstructionParser.parse(rawText.substring(amount.startUtf16))
                .singleOrNull().witnessesCount()) return unprovenCount()
    }
    val amount = numericSourceAmount(rawText) ?: return unprovenCount()
    return LegacySourceCountAnalysis(countEligible = true, amount = amount)
}

/** A non-executable local witness tied to one immutable interpretation row.
 * No conversion to ParsedItem/provenance exists: unresolved context must never
 * acquire authority merely because its arithmetic can be read locally. */
internal class GroceryIntentPendingCountWitness private constructor(
    private val instruction: String,
    private val rowIndex: Int,
    private val itemSnapshot: ParsedItem,
    val source: GroceryIntentReviewV2Quote,
    private val amount: IntentLocalAmount,
) {
    val requestedCount: Int get() = (itemSnapshot.quantity as Quantity.Count).n

    fun matches(originalInstruction: String, index: Int, item: ParsedItem): Boolean =
        instruction == originalInstruction && rowIndex == index && itemSnapshot == item

    /** Reuses the same unfiltered catalogue scan as executable quantities,
     * without constructing provenance or exposing the private amount receipt. */
    fun catalogueIssue(originalInstruction: String, index: Int, item: ParsedItem,
                       recommendations: SwiggyMcpClient.Recommendations): String? =
        if (!matches(originalInstruction, index, item)) "context_source_changed"
        else swiggyLocalAmountCatalogueIssue(itemSnapshot, recommendations, listOf(amount))

    /** Contents for an explicitly labelled proposal, never a source-role proof.
     * Keep the existing retail-flag/prepared-piece exclusions before offering it. */
    fun ordinaryOfferContents(candidate: SwiggyMcpClient.RecommendationCandidate): SwiggyRetailOfferContents? {
        if (requestedCount !in 1..20 || itemSnapshot.retailPackCount ||
            !swiggyPreparedPieceOfferMatches(itemSnapshot, candidate)) return null
        return swiggyOrdinaryRetailOfferContents(candidate)
    }

    override fun toString(): String = "GroceryIntentPendingCountWitness(<redacted>)"

    companion object {
        fun compile(instruction: String, rowIndex: Int, item: ParsedItem): GroceryIntentPendingCountWitness? {
            if (rowIndex !in 0 until 50 || item.unprovenIdentityScope ||
                !groceryIntentSourceClauseEndProven(instruction, item.rawText, item.rawText.length)) return null
            val source = bindGroceryIntentProductSource(instruction, item.rawText, maxLength = 6000) ?: return null
            val analysis = item.inspectLegacySourceCount(instruction)
            val amount = analysis.amount ?: return null
            if (analysis.identityHeld || !analysis.countEligible) return null
            val snapshot = item.copy(avoidPhrases = java.util.Collections.unmodifiableList(item.avoidPhrases.toList()))
            return GroceryIntentPendingCountWitness(instruction, rowIndex, snapshot, source, amount)
        }
    }
}

internal fun ParsedItem.untypedSourceNumericIssue(): String? {
    val (value, unit) = when (val amount = quantity) {
        is Quantity.Count -> amount.n.toBigDecimal() to "count"
        is Quantity.Weight -> amount.grams.toBigDecimal() to "g"
        is Quantity.Volume -> amount.ml.toBigDecimal() to "ml"
        else -> null to "unspecified"
    }
    val measures = groceryPackMeasures(query)
    if (measures.size > 1) return "numeric_pack_ambiguous"
    val pack = measures.singleOrNull()
    val packUnit = when (pack?.dimension) {
        GroceryPackDimension.WEIGHT -> "g"
        GroceryPackDimension.VOLUME -> "ml"
        GroceryPackDimension.PIECES -> "piece"
        GroceryPackDimension.PACKS -> return "numeric_pack_ambiguous"
        null -> "unspecified"
    }
    return sourceNumericOwnershipIssue(rawText, query, emptyList(), avoidPhrases,
        value, unit, pack?.baseValue, packUnit)
}

private fun sourceNumericOwnershipIssue(source: String, name: String, required: List<String>, excluded: List<String>,
                                        quantity: BigDecimal?, quantityUnit: String,
                                        packValue: BigDecimal?, packUnit: String,
                                        allowDefaultOne: Boolean = true): String? {
    if (nonRetailMeasurePrefix.containsMatchIn(source)) return "non_retail_measure_as_purchase"
    val literals = numericLiteralRoles(source, name, required, excluded)
    if (literals.any { it.role == "unpreserved_descriptor" }) return "numeric_descriptor_not_preserved"
    val contents = sourceContainerAmountOwners(source, quantity, quantityUnit, packValue, packUnit, literals)
    val bound = mutableListOf<NumericOwner>()
    var pack: IntentLocalAmount? = null
    for (isPack in listOf(false, true)) {
        val value = (if (isPack) packValue else quantity) ?: continue
        val unit = if (isPack) packUnit else quantityUnit
        val units = when (unit) {
            "g", "kg" -> listOf("g", "kg")
            "ml", "l" -> listOf("ml", "l")
            "count", "pack", "piece" -> listOf(unit)
            else -> return "numeric_atom_unbound"
        }
        fun canonical(amount: BigDecimal, amountUnit: String) =
            if (amountUnit in setOf("kg", "l")) amount.multiply(BigDecimal(1000)) else amount
        // Do not consume the 'a' in apple as another numeric word. Only a
        // digit-ending atom can defer a glued physical-unit boundary to binder.
        val candidates = Regex("(?<![\\p{L}\\p{N}_\\p{M}.+/\\-])(?:$numericAmount|an?)(?:(?![\\p{L}_\\p{M}])|(?<=[0-9]))", RegexOption.IGNORE_CASE)
            .findAll(source).flatMap { match -> units.asSequence().mapNotNull { sourceUnit ->
                val amount = parseIntentCorrectionAmount(match.value) ?: return@mapNotNull null
                if (canonical(amount, sourceUnit).compareTo(canonical(value, unit)) != 0) return@mapNotNull null
                // Apply the same retained literal roles as V2 BEFORE unique
                // occurrence selection. Never remove them from the partition.
                val candidate = numericAmountCandidates(source, match.value, sourceUnit)
                    .filter { candidate -> literals.none { it.overlaps(candidate) } }.singleOrNull()
                    ?: return@mapNotNull null
                IntentLocalAmount(source, candidate.start, candidate.end, match.value, amount, sourceUnit)
            } }.distinct().toList()
        val ordinary = if (isPack) candidates.filter { !numericEachAfter(source, it.endUtf16, it.unit) } else candidates
        val selected = contents?.let { owners ->
            val owner = if (isPack) owners.pack else owners.quantity
            IntentLocalAmount(source, owner.start, owner.end, source.substring(owner.start, owner.end), value, unit)
        } ?: (ordinary.ifEmpty { candidates }).singleOrNull() ?: return if (
            allowDefaultOne && !isPack && value.compareTo(BigDecimal.ONE) == 0 && unit in setOf("count", "pack", "piece")
        ) sourceNumericOwnershipIssue(source, name, required, excluded, null, "unspecified", packValue, packUnit,
            allowDefaultOne = false) else "numeric_atom_unbound"
        if (unit in setOf("count", "pack", "piece") &&
            nonRetailMeasureAfterAmount.containsMatchIn(source.substring(selected.endUtf16))) return "non_retail_measure_as_purchase"
        if (!isPack && intentQuantityIdentityConflict(source, selected.startUtf16, selected.endUtf16,
                selected.value, selected.unit, name)) return "quantity_name_atom_reused"
        bound += NumericOwner(selected.startUtf16, selected.endUtf16, if (isPack) "pack" else "quantity")
        if (isPack) pack = selected
    }
    numericPartitionIssue(source, bound, literals, pack?.atom, pack?.unit ?: packUnit)?.let { return it }
    compositionPackRoleIssue(source, bound, pack?.unit ?: packUnit, quantityUnit, "", name, required, excluded)
        ?.let { return it }
    // V1, Context and untyped source proofs must enforce the same ORIGINAL
    // physical-piece size role as V2 before numeric evidence can reach a cart.
    if (quantity != null && pack != null &&
        physicalUnitSizeIssueCode(source, quantityUnit) == "physical_piece_size_ambiguous") {
        return physicalUnitSizeIssue(source, bound, quantityUnit, pack.unit)
    }
    return null
}

internal enum class SourcePieceForm { PREFIX, POSTFIX, BARE_PREFIX }

private val sourcePieceDozenSuffix = Regex("\\bdozens?$", RegexOption.IGNORE_CASE)
private val sourcePiecePrefixMarker = Regex("^[ \\t]+(pieces?|pcs?)\\b[ \\t]*", RegexOption.IGNORE_CASE)
private val sourcePieceOptionalOf = Regex("^[ \\t]*(?:of[ \\t]+)?", RegexOption.IGNORE_CASE)
private val sourcePieceSuffixMarker = Regex("[ \\t]+(pieces?|pcs?)$", RegexOption.IGNORE_CASE)

/** Original spans establish the count role; word order is not quantity meaning. */
internal data class SourcePiecePurchaseReceipt(
    val source: String,
    val amount: IntentLocalAmount,
    val marker: IntRange?,
    val identity: IntRange,
    val form: SourcePieceForm,
) {
    override fun toString(): String = "SourcePiecePurchaseReceipt(<redacted>)"
    val terminalEnd: Int get() = maxOf(identity.last + 1, marker?.let { it.last + 1 } ?: 0, amount.endUtf16)
    fun literalIdentity(): String = com.example.beta.automation.ProductLexicon
        .canonicalizeAliasesPreservingText(source.substring(identity))
}

/** Shared construction only; callers must first validate source ownership. */
internal fun SourcePiecePurchaseReceipt.provenance(quantity: Quantity, query: String) = IntentQuantityProvenance(
    origin = IntentQuantityOrigin.LOCAL, primaryText = source, rawText = source,
    resolvedQuantity = quantity, local = amount, query = query, strictMatchPhrase = query,
    retailPackCount = false, logicalPieceCount = true,
    opaqueNumericLabels = groceryIntentOpaqueNumericLabels(query),
    sourceCountRole = IntentSourceCountRole.REQUESTED_PIECES,
)

/** Every local receipt must retain both instruction boundaries. Prefix and
 * postfix are surface forms, not different levels of source ownership. */
internal fun sourcePieceInstructionIssue(instruction: String, receipt: SourcePiecePurchaseReceipt): String? {
    return "source_piece_context_unproven".takeUnless {
        groceryIntentSourceClauseContextProven(instruction, receipt.source, receipt.terminalEnd)
    }
}

/** One complete amount, explicit piece unit, and one nominal. Both surface
 * orders share the same binder and guards; neither implies a catalogue divisor. */
internal fun sourcePiecePurchaseReceipt(source: String, bareIdentity: String? = null): SourcePiecePurchaseReceipt? {
    if (source.isEmpty() || source.length > 6000) return null
    val start = source.indexOfFirst { it != ' ' && it != '\t' }.takeIf { it >= 0 } ?: return null
    val contentEnd = source.trimEnd(' ', '\t', '.', '!', '?').length
    val matches = mutableListOf<SourcePiecePurchaseReceipt>()
    fun record(amountStart: Int, amountEnd: Int, marker: IntRange?, nominalStart: Int,
               nominalEnd: Int, form: SourcePieceForm) {
        if (nominalStart >= nominalEnd || amountStart >= amountEnd) return
        val atom = source.substring(amountStart, amountEnd)
        if (atom != atom.trim(' ', '\t')) return
        val amount = sourceQuotedLocalAmount(source, atom, "piece") ?: return
        if (amount.startUtf16 != amountStart || amount.endUtf16 != amountEnd ||
            !runCatching { amount.value.intValueExact() in 1..1000 }.getOrDefault(false)) return
        val nominal = source.substring(nominalStart, nominalEnd)
        if (!sourcePieceNominalValid(nominal)) return
        if (bareIdentity != null && (form == SourcePieceForm.BARE_PREFIX && !pieceIdentityEquals(nominal, bareIdentity) ||
                propertyHasUnprovedNegation(nominal) || Regex("[A-Za-z]+").findAll(nominal).any {
                    it.value.lowercase(Locale.ROOT) in highPieceUnsafeWords
                })) return
        val remaining = source.toCharArray()
        (amountStart until amountEnd).forEach { remaining[it] = ' ' }
        if (groceryIntentContainsNumericAtom(String(remaining))) return
        matches += SourcePiecePurchaseReceipt(source, amount, marker, nominalStart until nominalEnd, form)
    }
    for (end in start + 1..minOf(contentEnd, start + 64)) {
        if (end < source.length && source[end] !in " \t") continue
        val atom = source.substring(start, end)
        var nominalStart = end
        var markerRange: IntRange? = null
        if (!sourcePieceDozenSuffix.containsMatchIn(atom)) {
            val marker = sourcePiecePrefixMarker.find(source.substring(end, contentEnd))
            if (marker == null) {
                if (bareIdentity != null) {
                    while (nominalStart < contentEnd && source[nominalStart] in " \t") nominalStart++
                    if (nominalStart > end) record(start, end, null, nominalStart, contentEnd, SourcePieceForm.BARE_PREFIX)
                }
                continue
            }
            markerRange = marker.groups[1]!!.range.let { (end + it.first)..(end + it.last) }
            nominalStart = end + marker.range.last + 1
        }
        nominalStart += sourcePieceOptionalOf
            .find(source.substring(nominalStart, contentEnd))!!.value.length
        record(start, end, markerRange, nominalStart, contentEnd, SourcePieceForm.PREFIX)
    }

    val suffixMarker = sourcePieceSuffixMarker
        .find(source.substring(0, contentEnd))
    val amountEnd = suffixMarker?.range?.first ?: contentEnd
    val markerRange = suffixMarker?.groups?.get(1)?.range
    for (amountStart in maxOf(start + 1, amountEnd - 64) until amountEnd) {
        if (source[amountStart - 1] !in " \t") continue
        val atom = source.substring(amountStart, amountEnd)
        val dozen = sourcePieceDozenSuffix.containsMatchIn(atom)
        if ((suffixMarker == null) != dozen) continue
        var nominalEnd = amountStart
        while (nominalEnd > start && source[nominalEnd - 1] in " \t") nominalEnd--
        if (nominalEnd > start && source[nominalEnd - 1] == ',') {
            nominalEnd--
            while (nominalEnd > start && source[nominalEnd - 1] in " \t") nominalEnd--
        }
        record(amountStart, amountEnd, markerRange, start, nominalEnd, SourcePieceForm.POSTFIX)
    }
    return matches.distinct().singleOrNull()
}

// Fold case and horizontal spacing only. Do not drop modifiers or normalize a SKU, plural, brand or unit away.
internal fun pieceIdentityEquals(a: String, b: String): Boolean {
    fun fold(value: String) = value.trim(' ', '\t').lowercase(Locale.ROOT).replace(Regex("[ \t]+"), " ")
    return fold(a) == fold(b)
}

private val highPieceUnsafeWords by lazy {
    groceryIntentNominalScopeUnsafeWords + forbiddenGenericContainerNameWords + totalRoleStopwords + ("""
        pint pints quart quarts gallon gallons fl floz milligram milligrams microgram micrograms
        tsp tbsp teaspoonful tablespoonful cc cl dl dosage dosages once twice thrice daily nightly weekly hourly monthly
        hours days morning evening bedtime times units metre metres meter meters feet foot yards yard
        gauge gauges grit grade grades codes models skus
    """).trimIndent().split(Regex("\\s+"))
}

private fun sourcePieceNominalValid(nominal: String): Boolean {
    val words = Regex("[A-Za-z]+").findAll(nominal).map { it.value.lowercase(Locale.ROOT) }.toSet()
    return Regex("[A-Za-z]+(?:[ \\t'-][A-Za-z]+)*").matches(nominal) &&
        !groceryIntentContainsNumericAtom(nominal) &&
        !Regex("\\b$discretePackContainers\\b", RegexOption.IGNORE_CASE).containsMatchIn(nominal) &&
        !containerContentsRelationWords.containsMatchIn(nominal) && !nonRetailMeasurePrefix.containsMatchIn(nominal) &&
        !Regex("\\b(?:pieces?|pcs?)\\b", RegexOption.IGNORE_CASE).containsMatchIn(nominal) &&
        words.intersect(groceryIntentNominalClauseUnsafeWords + setOf(
            "wala", "wali", "wale", "size", "x", "cut", "kaat", "kaatna", "tukde", "lagbhag", "se",
            "bhej", "bhejo", "lana", "laao", "aao", "aana", "lao", "chahiye",
        )).isEmpty() && !groceryIntentHasNominalClausePredicate(nominal) &&
        groceryIntentClosedHindiRequestRanges(nominal).isEmpty()
}

/** Compatibility projection of the original-span receipt. */
internal fun sourcePiecePurchaseAmount(source: String, proposedValue: BigDecimal? = null): IntentLocalAmount? =
    sourcePiecePurchaseReceipt(source)?.amount?.takeIf { proposedValue == null || it.value.compareTo(proposedValue) == 0 }

internal fun GroceryIntentItem.legacySourcePieceReceipt(): SourcePiecePurchaseReceipt? {
    if (needsClarification || excluded.isNotEmpty() || packValue != null ||
        quantityUnit !in setOf("count", "piece")) return null
    val receipt = sourcePiecePurchaseReceipt(sourceText) ?: return null
    if (quantity?.compareTo(receipt.amount.value) != 0 ||
        !intentEvidenceTokens(receipt.literalIdentity()).containsAll(
            intentEvidenceTokens(name + " " + required.joinToString(" ") { it.replace('_', ' ') }))) return null
    return receipt
}

internal fun GroceryIntentItem.legacySourcePieceIssue(): String? {
    val amount = sourcePiecePurchaseAmount(sourceText)
    if (amount != null) return "source_piece_purchase_mismatch".takeIf {
        quantityUnit !in setOf("count", "piece") || quantity?.compareTo(amount.value) != 0 || packValue != null
    }
    return "source_piece_purchase_unproven".takeIf {
        packValue == null && groceryIntentContainsNumericAtom(sourceText) &&
            Regex("(?<![A-Za-z])(?:dozens?|pieces?|pcs?)(?![A-Za-z])|(?<=[0-9])(?:dozens?|pieces?|pcs?)",
                RegexOption.IGNORE_CASE).containsMatchIn(sourceText)
    }
}

internal fun ParsedItem.validSourcePieceAmount(): IntentLocalAmount? {
    val proof = intentQuantityProvenance ?: return null
    if (!requiresIntentQuantityProof ||
        proof.origin != com.example.beta.automation.IntentQuantityOrigin.LOCAL || proof.corrected != null ||
        proof.retailContainer != null || proof.unitSize != null || containerContentsProof != null ||
        !logicalPieceCount || retailPackCount || proof.primaryText != rawText || proof.rawText != rawText ||
        proof.query != query || proof.strictMatchPhrase != strictMatchPhrase || proof.resolvedQuantity != quantity ||
        !proof.logicalPieceCount || proof.retailPackCount) return null
    val requested = (quantity as? Quantity.Count)?.n ?: return null
    val receipt = sourcePiecePurchaseReceipt(rawText) ?: return null
    val amount = receipt.amount
    if (amount.value.compareTo(requested.toBigDecimal()) != 0 ||
        query != receipt.literalIdentity() || strictMatchPhrase != query) return null
    return amount.takeIf { it == proof.local }
}

/** Source evidence, not a nullable routing marker, decides whether the strict
 * conversion is mandatory. Reassessment cannot legitimize a downgraded copy. */
internal fun ParsedItem.requiresSourcePieceConversion(): Boolean =
    intentQuantityProvenance?.sourceCountRole == IntentSourceCountRole.REQUESTED_PIECES ||
        sourcePiecePurchaseAmount(rawText) != null

/** Preserve a source-proved outer count even when the producer calls it a
 * generic count/piece. The model enum alone can never create this role.
 * Only used after the complete V1 or V2 row has passed review validation. */
internal data class SourceContainerContentsReceipt(
    val outer: IntentLocalAmount,
    val inner: IntentLocalAmount,
    val container: String,
) {
    fun withQuery(query: String): IntentContainerContentsProof =
        IntentContainerContentsProof(outer, inner, container, query)

    override fun toString(): String = "SourceContainerContentsReceipt(<redacted>)"
}

/** A joint binding needs two explicit source roles, not merely two equal values.
 * Keep ambiguous bare numerals on the existing unique-binding path. This adds
 * no vocabulary, arithmetic conversion, identity proof or cart authority. */
private data class SourceAmountOwners(val quantity: NumericOwner, val pack: NumericOwner)

private fun sourceContainerAmountOwners(
    source: String, quantity: BigDecimal?, quantityUnit: String,
    packValue: BigDecimal?, packUnit: String, literals: List<NumericOwner>,
): SourceAmountOwners? {
    if (quantity == null || packValue == null || packUnit != "piece" ||
        quantityUnit !in setOf("count", "pack", "piece")) return null
    // Reject a third unowned atom before the occurrence binder scans the
    // source again; repeated numeric tokens must not cause quadratic work.
    val atoms = Regex("(?<![\\p{L}\\p{N}_\\p{M}.+/\\-])(?:$numericAmount)(?![\\p{L}\\p{N}_\\p{M}.+/])", RegexOption.IGNORE_CASE)
        .findAll(source).filter { atom -> literals.none { it.start <= atom.range.last && atom.range.first < it.end } }
        .take(3).toList()
    if (atoms.size != 2) return null
    val owners = atoms.asSequence().flatMap { numericAmountCandidates(source, it.value, "piece").asSequence() }
        .filter { owner -> literals.none { it.overlaps(owner) } }.distinct().sortedBy { it.start }.toList()
    if (owners.size != 2) return null
    val (outer, inner) = owners
    if (parseIntentCorrectionAmount(source.substring(outer.start, outer.end))?.compareTo(quantity) != 0 ||
        parseIntentCorrectionAmount(source.substring(inner.start, inner.end))?.compareTo(packValue) != 0 ||
        !Regex("^[ \\t]+(?:pieces?|pcs?)\\b", RegexOption.IGNORE_CASE).containsMatchIn(source.substring(inner.end)) ||
        !sourceContainerContentsRelation(source, outer.start, outer.end, inner.start, inner.end)) return null
    return SourceAmountOwners(outer, inner)
}

private fun GroceryIntentReviewV2ReadyRow.sourceContainerAmountOwners(literals: List<NumericOwner>): SourceAmountOwners? {
    val quantityAtom = item.quantityText ?: return null
    val packAtom = item.packText ?: return null
    val source = evidence.primary.text
    return sourceContainerAmountOwners(source, item.quantity, item.quantityUnit, item.packValue, item.packUnit, literals)
        ?.takeIf { owners -> source.substring(owners.quantity.start, owners.quantity.end) == quantityAtom &&
            source.substring(owners.pack.start, owners.pack.end) == packAtom }
}

/** Bind the source before query rendering. A query can preserve this receipt,
 * but can never create its count, container or inner-contents relationship. */
internal fun GroceryIntentItem.sourceContainerContentsReceipt(): SourceContainerContentsReceipt? {
    if (packUnit != "piece" || quantityUnit !in setOf("count", "pack", "piece")) return null
    val contents = sourceContainerAmountOwners(sourceText, quantity, quantityUnit, packValue, packUnit, emptyList())
    val outer = contents?.quantity ?: quantity?.let { legacyBoundAmount(it, quantityUnit) } ?: return null
    val inner = contents?.pack ?: packValue?.let { legacyBoundAmount(it, packUnit) } ?: return null
    if (!sourceContainerContentsRelation(sourceText, outer.start, outer.end, inner.start, inner.end)) return null
    val containerText = Regex("^[ \\t]+($discretePackContainers)\\b", RegexOption.IGNORE_CASE)
        .find(sourceText.substring(outer.end, inner.start))?.groupValues?.get(1) ?: return null
    val container = groceryIntentRetailContainerKey(containerText) ?: return null
    return SourceContainerContentsReceipt(
        IntentLocalAmount(sourceText, outer.start, outer.end, sourceText.substring(outer.start, outer.end), requireNotNull(quantity), "pack"),
        IntentLocalAmount(sourceText, inner.start, inner.end, sourceText.substring(inner.start, inner.end), requireNotNull(packValue), "piece"),
        container,
    )
}

internal fun GroceryIntentItem.legacyContainerContentsProof(query: String): IntentContainerContentsProof? =
    sourceContainerContentsReceipt()?.withQuery(query)

/** Revalidate immutable source roles at the cart boundary, including edits/copies. */
internal fun ParsedItem.validContainerContentsProof(): IntentContainerContentsProof? {
    val proof = containerContentsProof ?: return null
    if (!retailPackCount || logicalPieceCount || query != proof.query || strictMatchPhrase != query ||
        rawText != proof.outer.source || proof.inner.source != rawText ||
        proof.outer.unit != "pack" || proof.inner.unit != "piece" ||
        (quantity as? Quantity.Count)?.n?.toBigDecimal()?.compareTo(proof.outer.value) != 0) return null
    val pieceMeasures = groceryPackMeasures(query).filter { it.dimension == GroceryPackDimension.PIECES }
    if (pieceMeasures.map { it.baseValue }.distinct().singleOrNull()?.compareTo(proof.inner.value) != 0) return null
    val regenerated = GroceryIntentItem(
        name = "", quantity = proof.outer.value, quantityUnit = "pack", packValue = proof.inner.value,
        packUnit = "piece", required = emptyList(), excluded = emptyList(), sourceText = rawText, needsClarification = false,
    ).legacyContainerContentsProof(query)
    return proof.takeIf { it == regenerated }
}

/** A literal source-owned nominal may contain a container noun (e.g. tea bags).
 * Never mask an arbitrary model name, numeric code, qualifier or relationship. */
internal fun ParsedItem.containerContentsLiteralIdentity(): String? {
    val proof = validContainerContentsProof() ?: return null
    val suffix = " ${proof.inner.value.stripTrailingZeros().toPlainString()} pieces"
    // V1 may append its source-owned specific form after the size; V2 may
    // render it before the size. Interpret those two adapter layouts only.
    val measuredQuery = when {
        query.endsWith(suffix) -> query
        query.endsWith("$suffix ${proof.container}") -> query.removeSuffix(" ${proof.container}")
        else -> return null
    }
    val identity = measuredQuery.removeSuffix(suffix)
    // Remove at most the exact source-owned form from this analysis view, only
    // when the complete remaining nominal matches the original source. Never
    // rewrite query/candidate constraints or delete words inside a product name.
    return listOf(identity, identity.removeSuffix(" ${proof.container}")).distinct().firstOrNull { nominal ->
        Regex("[A-Za-z]+(?:[ \\t'-][A-Za-z]+)*").matches(nominal) &&
            !containerContentsRelationWords.containsMatchIn(nominal) && !groceryIntentContainsNumericAtom(nominal) &&
            sourceContainerContentsRelation(rawText, proof.outer.startUtf16, proof.outer.endUtf16,
                proof.inner.startUtf16, proof.inner.endUtf16, literalIdentity = nominal)
    }
}

private fun discretePackSourceIssue(source: String, quantityStart: Int?, quantityEnd: Int?, packStart: Int, packEnd: Int, requireLeading: Boolean): String? {
    if (quantityStart != null && quantityEnd != null &&
        sourceContainerContentsRelation(source, quantityStart, quantityEnd, packStart, packEnd)) return null
    if (sourceContentsStartsWithRetailContainer(source, packEnd)) return "discrete_pack_role_unproven"
    val before = source.substring(0, packStart)
    val after = source.substring(packEnd)
    if (nonRetailMeasureAfterAmount.containsMatchIn(after)) return "discrete_pack_role_unproven"
    val prefix = Regex("\\b$discretePackContainers$servingSpace+of$servingSpace*$", RegexOption.IGNORE_CASE).find(before)
    val measure = Regex("^$servingSpace*-?$servingSpace*(?:pieces?|pcs|count)\\b", RegexOption.IGNORE_CASE).find(after)
    val tail = after.substring(measure?.value?.length ?: 0)
    val suffix = if (measure != null) Regex("^$servingSpace*(?:(?:ka|ki|ke|wala|wali|wale|tetra)$servingSpace+)?$discretePackContainers\\b", RegexOption.IGNORE_CASE).find(tail) else null
    val total = Regex("\\b(?:total|altogether|kul)$servingSpace*$", RegexOption.IGNORE_CASE).containsMatchIn(before) ||
        Regex("^$servingSpace*(?:in$servingSpace+total|total|altogether|kul)\\b", RegexOption.IGNORE_CASE).containsMatchIn(tail)
    val nutrition = measure != null && Regex("^$servingSpace*(?:protein|fib(?:re|er)|carbs?|carbohydrates?|fat|sugar|sodium|calcium|calories?|kcal)\\b$servingSpace+per$servingSpace+(?:serving|portion|100$servingSpace*(?:g|grams?|ml))\\b", RegexOption.IGNORE_CASE).containsMatchIn(tail)
    if ((prefix == null && suffix == null) || total || nutrition || discretePackBreak.containsMatchIn(source)) return "discrete_pack_role_unproven"
    val phraseStart = prefix?.range?.first ?: packStart
    if (requireLeading && source.substring(0, phraseStart).isNotBlank()) return "discrete_pack_role_unproven"
    if (quantityEnd != null) {
        if (quantityEnd > phraseStart) return "discrete_pack_role_unproven"
        if (prefix != null && Regex("\\b(?:a|an|another|one|ek|single|\\d+)$servingSpace*$", RegexOption.IGNORE_CASE)
                .containsMatchIn(source.substring(quantityEnd, phraseStart))) return "discrete_pack_role_unproven"
    }
    if (prefix != null || suffix != null) {
        // A literal pack marker alone cannot merge two product identities.
        // Model names/role flags, dosage nouns and catalogue bundles do not
        // establish that the original speaker intended one product.
        val product = source.substring(0, phraseStart).let {
            if (quantityStart != null && quantityEnd != null) it.removeRange(quantityStart, quantityEnd) else it
        }
        val contents = if (prefix != null) after else tail.substring(requireNotNull(suffix).value.length)
            .replaceFirst(Regex("^$servingSpace+of$servingSpace+", RegexOption.IGNORE_CASE), "")
        if (!sameDiscreteContentsPhrase(product, contents)) return "pack_contents_scope_unresolved"
    }
    return null
}

/** Mirrors the backend's complete literal-phrase comparison, not a noun heuristic. */
private fun sameDiscreteContentsPhrase(productText: String, contentsText: String): Boolean {
    val product = productText.replace(Regex("[ \\t]+"), " ").trim().lowercase(Locale.ROOT)
    val contents = contentsText.replace(Regex("[ \\t]+"), " ").trim().trimEnd('.', '!', '?')
        .trim().lowercase(Locale.ROOT)
    if (product.isEmpty() || contents.isEmpty() || contents in setOf("piece", "pieces", "pcs", "count")) return true
    val nominal = Regex("[a-z]+(?: [a-z]+)*")
    return nominal.matches(product) && nominal.matches(contents) &&
        (product == contents || product.endsWith(" $contents"))
}

/** Deny-only split boundary. Reuse the existing contents vocabulary; this
 * cannot prove any amount, identity or relationship. A numeric identity is
 * excluded by the parser before invoking it. */
internal fun sourceHasUnresolvedContentsBoundary(prefix: String): Boolean =
    unresolvedContentsBoundary.containsMatchIn(prefix)

/** Positive source-only relation for independently bound outer/inner counts.
 * Mirrors backend source_container_contents_relation. No model name or SKU
 * can supply this proof; exact arithmetic, numeric ownership and identity
 * remain mandatory separate gates. This never authorizes a cart action. */
internal fun sourceContainerContentsRelation(source: String, quantityStart: Int, quantityEnd: Int,
                                            contentsStart: Int, contentsEnd: Int, literalIdentity: String? = null): Boolean {
    if (!(0 <= quantityStart && quantityStart < quantityEnd && quantityEnd < contentsStart &&
            contentsStart < contentsEnd && contentsEnd <= source.length)) return false
    if (source.substring(0, quantityStart).trim(' ', '\t').isNotEmpty()) return false
    val container = Regex("^[ \\t]+($discretePackContainers)\\b(?:[ \\t]+of\\b)?[ \\t]*", RegexOption.IGNORE_CASE)
        .find(source.substring(quantityEnd, contentsStart)) ?: return false
    if (Regex("(?:cups?|sprays?)", RegexOption.IGNORE_CASE).matches(container.groupValues[1])) return false
    val between = source.substring(quantityEnd + container.value.length, contentsStart)
    val tail = source.substring(contentsEnd).trim(' ', '\t').trimEnd('.', '!', '?').trim(' ', '\t')
    if (nonRetailMeasureAfterAmount.containsMatchIn(source.substring(contentsEnd))) return false
    fun nominal(value: String): Boolean =
        Regex("[A-Za-z]+(?:[ \\t'-][A-Za-z]+)*").matches(value) &&
            !sourceNominalHasAmountOrRelation(value)
    // A catalogue mask must own the complete nominal, not a selected word.
    fun ownsIdentity(value: String): Boolean {
        if (literalIdentity == null) return true
        return intentEvidenceTokens(literalIdentity) == intentEvidenceTokens(value)
    }
    // Only the unit after the inner amount is grammar. The pre-comma product
    // below must retain literal unit words such as "piece" in "piece cake".
    // Without a separately retained nominal, a container word after the inner
    // amount could describe nested packs, not generic pieces. A complete
    // repeated nominal below remains independent evidence (e.g. tea bags).
    if (between.trim(' ', '\t').isEmpty()) return !sourceContentsStartsWithRetailContainer(source, contentsEnd) && nominal(tail) && ownsIdentity(
        tail.replaceFirst(Regex("^(?:pieces?|pcs?)\\b[ \\t]*", RegexOption.IGNORE_CASE), ""),
    )
    if (!between.trimEnd(' ', '\t').endsWith(',')) return false
    val product = between.trimEnd(' ', '\t').dropLast(1).trim(' ', '\t')
    return nominal(product) && nominal(tail) && sameDiscreteContentsPhrase(product, tail) && ownsIdentity(product)
}

/** Denial only. Normalization may reveal an amount/relationship but must never
 * manufacture positive source evidence or change the original span offsets. */
internal fun sourceNominalHasAmountOrRelation(value: String): Boolean {
    val denial = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    return containerContentsRelationWords.containsMatchIn(denial) || groceryIntentContainsNumericAtom(denial)
}

private const val sourceRelationOperators =
    "and|aur|with|plus|also|then|or|ya|along|alongside|including|incl|" +
        "containing|contains|extra|bonus|another|additional|separate|each|per|har|" +
        "total|altogether|kul|no|not|without|bina|nahi|nahin|sorry|instead|rather|" +
        "about|around|approx(?:imately)?|roughly|almost|nearly|maybe|perhaps|possibly|" +
        "if|unless|when|otherwise|minimum|maximum|upto|up|to|at|least|most"

/** Relations are controls; ordinary measure nouns may still name a product
 * (such as serving spoons). Keep that distinction for retail identities. */
internal fun sourceNominalHasRelation(value: String): Boolean = sourceRelationWords.containsMatchIn(
    Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT))

private val sourceRelationWords = Regex("\\b(?:$sourceRelationOperators)\\b", RegexOption.IGNORE_CASE)
private val containerContentsRelationWords = Regex(
    "\\b(?:$sourceRelationOperators|" +
        "servings?|portions?|doses?|spoons?|teaspoons?|tablespoons?|scoops?|drops?)\\b",
    RegexOption.IGNORE_CASE,
)

private const val discretePackContainers = "(?:packs?|packets?|bags?|bottles?|cartons?|cups?|cans?|tins?|tubs?|jars?|box(?:es)?|bars?|blocks?|pouch(?:es)?|sachets?|tubes?|loaf|loaves|refills?|rolls?|sprays?|bunch(?:es)?|$groceryIntentGaddiContainerPattern)"
private val unresolvedContentsBoundary = Regex(
    "^[ \\t]*(?:[1-9]\\d?[ \\t]+)?(?:$discretePackContainers)(?:[ \\t]+of)?[ \\t]*$|\\bof[ \\t]*$",
    RegexOption.IGNORE_CASE,
)
private val discretePackBreak = Regex(
    "[,;:/&+()\\[\\]\\r\\n\\u0085\\u2028\\u2029]|\\b(?:and|aur|with|plus|also|then|or|ya|along|alongside|including|incl|containing|extra|bonus)\\b",
    RegexOption.IGNORE_CASE,
)

/** Denial only: normalization must not let a nested container become pieces. */
private fun sourceContentsStartsWithRetailContainer(source: String, amountEnd: Int): Boolean =
    Regex("^\\s*(?:$discretePackContainers)\\b", RegexOption.IGNORE_CASE)
        .containsMatchIn(Normalizer.normalize(source.substring(amountEnd), Normalizer.Form.NFKC))

/** An explicit physical-piece atom; complete row ownership is still checked separately. */
internal fun hasExplicitPieceQuantityAtom(source: String, atom: String): Boolean {
    val bound = numericAmountCandidates(source, atom, "piece").singleOrNull() ?: return false
    return Regex("^[ \\t]*(?:pieces?|pcs)\\b", RegexOption.IGNORE_CASE)
        .containsMatchIn(source.substring(bound.end))
}

/** Reuses the unique source-local binding; called only after the complete row passed. */
internal fun GroceryIntentReviewV2ReadyRow.localQuantityEvidence(): IntentLocalAmount? {
    val atom = item.quantityText ?: return null
    val value = item.quantity ?: return null
    val source = evidence.primary.text
    val literals = numericLiteralRoles(source, item.name, item.required, item.excluded)
    val bound = sourceContainerAmountOwners(literals)?.quantity ?: numericAmountCandidates(source, atom, item.quantityUnit)
        .filter { candidate -> literals.none { it.overlaps(candidate) } }.singleOrNull() ?: return null
    return IntentLocalAmount(source, bound.start, bound.end, atom, value, item.quantityUnit)
}

/** Same unique binding used by numericOwnershipIssue, including explicitly
 * equal '500 ml each' restatements. Call only after numeric ownership passed. */
internal fun GroceryIntentReviewV2ReadyRow.localPackSizeEvidence(): IntentLocalAmount? {
    val atom = item.packText ?: return null
    val value = item.packValue ?: return null
    val source = evidence.primary.text
    val literals = numericLiteralRoles(source, item.name, item.required, item.excluded)
    val candidates = numericAmountCandidates(source, atom, item.packUnit)
        .filter { candidate -> literals.none { it.overlaps(candidate) } }
    val ordinary = candidates.filter { !numericEachAfter(source, it.end, item.packUnit) }
    val bound = sourceContainerAmountOwners(literals)?.pack ?: (ordinary.ifEmpty { candidates }).singleOrNull() ?: return null
    return IntentLocalAmount(source, bound.start, bound.end, atom, value, item.packUnit)
}

/**
 * Only after numericOwnershipIssue passed: mask the actual bound amount spans,
 * never every occurrence of an amount word. Product/nutrition descriptors stay
 * in the identity evidence, including numbers and their units.
 */
internal fun GroceryIntentReviewV2ReadyRow.identitySourceWithoutAmounts(boundAtomsOnly: Boolean = false): String {
    val source = evidence.primary.text
    val remaining = source.toCharArray()
    fun mask(start: Int, end: Int) { for (index in start until end) remaining[index] = ' ' }
    val literals = numericLiteralRoles(source, item.name, item.required, item.excluded)
    val contents = sourceContainerAmountOwners(literals)
    for (isPack in listOf(false, true)) {
        val atom = (if (isPack) item.packText else item.quantityText) ?: continue
        val unit = if (isPack) item.packUnit else item.quantityUnit
        val candidates = numericAmountCandidates(source, atom, unit).filter { candidate ->
            literals.none { it.overlaps(candidate) }
        }
        val ordinary = if (isPack) candidates.filter { !numericEachAfter(source, it.end, unit) } else candidates
        val bound = contents?.let { if (isPack) it.pack else it.quantity }
            ?: (ordinary.ifEmpty { candidates }).singleOrNull() ?: return source
        val packPieceRole = contents != null || Regex("\\b(?:packs?|packets?|box(?:es)?)\\s+of\\s*$", RegexOption.IGNORE_CASE)
            .containsMatchIn(source.substring(0, bound.start)) ||
            Regex("^\\s*-?\\s*(?:pieces?|pcs?)\\s+(?:packs?|packets?|box(?:es)?)\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(source.substring(bound.end))
        val physicalEnd = GroceryIntentPhysicalUnitMatcher.after(source, bound.end, unit)?.end
        val pieceSuffix = if (unit == "piece" && (!isPack || packPieceRole)) {
            Regex("^\\s*-?\\s*(?:pieces?|pcs?)\\b", RegexOption.IGNORE_CASE).find(source.substring(bound.end))
        } else null
        mask(bound.start, physicalEnd ?: (bound.end + (pieceSuffix?.value?.length ?: 0)))
        if (!boundAtomsOnly && isPack && unit in numericPhysicalUnits) {
            // numericPartitionIssue already proved that these repetitions are
            // the SAME pack size, not another requested count or a descriptor.
            val repeated = Regex("(?<![\\p{L}\\p{N}_\\p{M}.+/\\-])($numericAmount)\\s*-?\\s*${numericPhysicalUnits.getValue(unit)}\\s+(?:each|har\\s+ek)\\b", RegexOption.IGNORE_CASE)
            repeated.findAll(source).filter { match ->
                parseIntentCorrectionAmount(match.groupValues[1])?.compareTo(item.packValue) == 0
            }.forEach { mask(it.range.first, it.range.last + 1) }
        }
    }
    // The descriptor preceding this pronoun was independently proved literal;
    // a brand/product named One cannot use this exemption.
    if (!boundAtomsOnly) literals.filter { it.role == "descriptive_one" }.forEach { mask(it.start, it.end) }
    return String(remaining)
}

private data class NumericOwner(val start: Int, val end: Int, val role: String) {
    fun overlaps(other: NumericOwner) = start < other.end && other.start < end
}

// Mirrors the backend's exact adjacent serving/dose grammar. This does not
// reject ordinary product names such as cup noodles or spray bottles, and
// never turns a culinary measure into a guessed retail count or physical size.
private const val servingSpace = "[\\s\\p{Z}\\u0085\\u001c-\\u001f]" // Python Unicode whitespace parity.
private const val servingMeasureContents =
    "(?:cups?|sprays?|spoons?|teaspoons?|tablespoons?|pinch(?:es)?|handfuls?|scoops?|drops?|ladles?|sips?|doses?|servings?|portions?)$servingSpace+of$servingSpace+[\\p{L}\\p{N}_]"
private val nonRetailMeasurePrefix = Regex("^$servingSpace*$servingMeasureContents", RegexOption.IGNORE_CASE)
private val nonRetailMeasureAfterAmount = Regex("^$servingSpace+$servingMeasureContents", RegexOption.IGNORE_CASE)

private fun numericAmountCandidates(source: String, atom: String, unit: String): List<NumericOwner> {
    val found = mutableListOf<NumericOwner>()
    val identityAmounts = ProductLexicon.numericProductIdentities(source).map { it.amountRange }
    var from = 0
    while (from <= source.length - atom.length) {
        val start = source.indexOf(atom, from)
        if (start < 0) break
        val end = start + atom.length
        val gluedUnit = physicalUnitTokenFollowsAt(source, end, unit)
        val boundary = hasQuoteBoundaries(source, atom, start, end) ||
            (atom.lastOrNull()?.isDigit() == true && gluedUnit != null &&
                hasQuoteBoundaries(source, atom + gluedUnit, start, end + gluedUnit.length))
        // A closed product identity owns this ORIGINAL atom before quantity
        // binding. The later partition still requires the identity to survive.
        if (boundary && !isCroppedNativeAmountAtom(source, start, end, atom) &&
            identityAmounts.none { start <= it.last && it.first < end } &&
            physicalUnitFollowsAt(source, start, atom, unit)) found += NumericOwner(start, end, "unassigned")
        from = start + 1
    }
    return found
}

private fun isCroppedNativeAmountAtom(source: String, start: Int, end: Int, atom: String): Boolean {
    if (start !in 0..source.length || end !in start..source.length) return true
    val prefix = source.substring(0, start)
    val suffix = source.substring(end)
    if (Regex("(?:सवा|साढ़े|साढे|पौने|और|सौ|हजार|हज़ार|लाख|करोड़|दर्जन)\\s+$").containsMatchIn(prefix)) return true
    val previousNativeWord = Regex("([\\p{L}\\p{M}]+)\\s+$").find(prefix)?.groupValues?.get(1)
    if (previousNativeWord != null && previousNativeWord.any { it.code in 0x0900..0x097F } &&
        parseIntentCorrectionAmount(previousNativeWord) != null) return true
    if (Regex("^[\\s-]*(?:सौ|हजार|हज़ार|लाख|करोड़|दर्जन|और)(?=$|\\s)").containsMatchIn(suffix)) return true
    return "सौ" in atom.split(Regex("\\s+")) &&
        Regex("^[\\s-]+$numericWord(?=$|\\s)", RegexOption.IGNORE_CASE).containsMatchIn(suffix)
}

internal fun groceryIntentContainsNumericAtom(source: String): Boolean = numericPositions(source).isNotEmpty()

/** A numeral/imperative homograph followed by a SECOND measured
 * amount has two readings (give that amount, or two packs of that amount).
 * Preserve the chosen draft, but require the existing combined review to show
 * the quantity interpretation explicitly. The ORIGINAL instruction owns the
 * boundary: a model cannot hide the homograph by attaching it to a neighbour.
 * This grants no amount/identity proof and never rewrites the chosen draft. */
internal fun groceryIntentQuantityInterpretationReviews(instruction: String, items: List<GroceryIntentItem>): List<String> {
    val imperatives = groceryIntentClosedHindiRequestRanges(instruction) + groceryIntentClosedCartRequestRanges(instruction)
    val ambiguous = numericPhysicalUnits.flatMap { (unit, forms) ->
        Regex("(?<![\\p{L}\\p{N}_])do[ \\t]+($numericAmount)[ \\t]*-?[ \\t]*$forms\\b", RegexOption.IGNORE_CASE)
            .findAll(instruction).filter { match ->
                if (imperatives.any { match.range.first in it && match.range.first + 1 in it }) return@filter false
                val atom = match.groups[1] ?: return@filter false
                sourceQuotedLocalAmount(match.value, atom.value, unit)?.let {
                    it.startUtf16 == atom.range.first - match.range.first &&
                        it.endUtf16 == atom.range.last + 1 - match.range.first
                } == true
            }.toList()
    }.distinctBy { it.range }
    return ambiguous.map { match ->
        val owners = items.filter { item ->
            item.sourceText.isNotEmpty() && Regex(Regex.escape(item.sourceText)).findAll(instruction).any {
                it.range.first <= match.range.last && match.range.first <= it.range.last
            }
        }
        fun BigDecimal.display() = stripTrailingZeros().toPlainString()
        fun understood(item: GroceryIntentItem): String = when {
            item.quantity == null -> "one suggested pack"
            item.quantityUnit in setOf("count", "pack", "piece") && item.packValue != null ->
                "${item.quantity.display()} pack(s), each ${item.packValue.display()} ${item.packUnit}"
            item.quantityUnit in setOf("g", "kg", "ml", "l") ->
                "${item.quantity.display()} ${item.quantityUnit} in total"
            else -> "${item.quantity.display()} ${item.quantityUnit}"
        }
        val choices = owners.joinToString("; ") { "${understood(it)} for ${it.name}" }
        "Quantity to check: ${match.value}\nThe word 'do' can mean 'two' or 'give'. " +
            (if (choices.isEmpty()) "Check the proposed quantities. " else "Beta understood $choices. ") +
            "Check this amount against the basket below; " +
            "edit your list if you meant the other amount."
    }.distinct()
}

internal fun groceryIntentLastNumericAtomEnd(source: String): Int? = numericPositions(source).maxOrNull()?.plus(1)

/** Source offset only; an omitted prefix is never an inferred purchase amount. */
internal fun groceryIntentTrailingNumericAtomStart(source: String): Int? =
    Regex("(?<![\\p{L}\\p{N}_\\p{M}])(?:$numericAmount)(?:[ \\t]+(?:packs?|packets?|pieces?|boxes|box|bottles?|pouches|pouch|jars?|cartons?|bags?|tubs?|tins?|cans?|sachets?|g|kg|keji|ml|l|grams?|litres?|of))*[ \\t]*$", RegexOption.IGNORE_CASE)
        .find(source)?.range?.first

internal fun groceryIntentFragmentIdentityTokens(source: String): Set<String> {
    val remaining = source.toCharArray()
    // Mask an original amount's unit, not every word that can spell a unit.
    // In "500 g gram flour", the second "gram" is still product identity.
    groceryIntentBoundPhysicalUnits(source).forEach { (_, token) ->
        for (index in token.start until token.end) remaining[index] = ' '
    }
    numericPositions(source).forEach { remaining[it] = ' ' }
    return intentEvidenceTokens(String(remaining)) - setOf(
        "pack", "packs", "packet", "packets", "piece", "pieces", "box", "boxes", "bottle", "bottles",
        "pouch", "pouches", "jar", "jars", "carton", "cartons", "bag", "bags", "tub", "tubs", "tin", "tins",
        "can", "cans", "sachet", "sachets",
        "per", "each", "contains", "inside", "in", "of", "with", "and", "wala", "wali", "wale",
    )
}

/** Comparison only: the legacy parser has a smaller unit vocabulary. Feed it
 * the same source-bound units as the numeric binder, without changing original
 * source evidence or exporting this temporary parser's offsets/provenance. */
internal fun groceryIntentPhysicalUnitComparisonView(source: String, view: String = source): String {
    if (view.length != source.length) return view
    var result = view
    groceryIntentBoundPhysicalUnits(source).sortedByDescending { it.second.start }.forEach { (amount, token) ->
        // A property mask must never be restored by normalization.
        if (view.substring(token.start, token.end) == token.token) {
            result = result.replaceRange(token.start, token.end, amount.unit)
        }
    }
    return result
}

/** A complete measured metadata clause, not a product or a second count.
 * Parser-normalized fragments and repeated/ambiguous original quotes cannot
 * be discarded to manufacture a single count witness. */
internal fun groceryIntentMeasuredMetadata(source: String, fragment: String): Boolean {
    val start = source.indexOf(fragment)
    if (fragment.isBlank() || start < 0 || source.indexOf(fragment, start + 1) >= 0) return false
    if (!groceryIntentSourceClauseContextProven(source, fragment, fragment.length)) return false
    val end = start + fragment.length
    val (amount, token) = groceryIntentBoundPhysicalUnits(source).singleOrNull {
        it.first.startUtf16 >= start && it.second.end <= end
    } ?: return false
    return source.substring(start, amount.startUtf16).isBlank() &&
        Regex("\\s*(?:$numericEachMarker)?[\\s.!?]*", RegexOption.IGNORE_CASE)
            .matches(source.substring(token.end, end))
}

private fun groceryIntentBoundPhysicalUnits(source: String): List<Pair<IntentLocalAmount, GroceryIntentPhysicalUnitMatcher.Match>> =
    Regex("(?<![\\p{L}\\p{N}_\\p{M}.+/\\-])(?:$numericAmount|an?)(?:(?![\\p{L}_\\p{M}])|(?<=[0-9]))", RegexOption.IGNORE_CASE)
        .findAll(source).flatMap { atom ->
            GroceryIntentPhysicalUnitMatcher.patterns.keys.asSequence().mapNotNull { unit ->
                val token = GroceryIntentPhysicalUnitMatcher.after(source, atom.range.last + 1, unit) ?: return@mapNotNull null
                val bound = sourceQuotedLocalAmount(source, atom.value, unit) ?: return@mapNotNull null
                if (bound.startUtf16 != atom.range.first || bound.endUtf16 != atom.range.last + 1) return@mapNotNull null
                bound to token
            }
        }.distinct().toList()

private fun numericPositions(source: String): Set<Int> {
    val positions = mutableSetOf<Int>()
    var index = 0
    while (index < source.length) {
        val codePoint = source.codePointAt(index)
        val count = Character.charCount(codePoint)
        if (codePoint in numericLetterCodePoints || Character.getType(codePoint) in setOf(
                Character.DECIMAL_DIGIT_NUMBER.toInt(), Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt(),
            )) positions += index until index + count
        index += count
    }
    Regex("[\\p{L}\\p{N}\\p{M}_]+").findAll(source).forEach { match ->
        val token = Normalizer.normalize(match.value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val postposition = token == "saath" && numericPostpositionSaath.findAll(source).any {
            it.range.last == match.range.last
        }
        if (token in GROUP_NUMERIC_WORDS && !postposition) {
            positions += match.range
        }
    }
    return positions
}

private fun numericLiteral(phrase: String, name: String): Boolean {
    fun normalize(value: String) = value.lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), " ")
    return Regex("(?<![\\p{L}\\p{N}_])" + Regex.escape(normalize(phrase)) + "(?![\\p{L}\\p{N}_])")
        .containsMatchIn(normalize(name))
}

private fun numericLiteralDescriptorRoles(source: String, name: String,
                                         includeLetterIds: Boolean = true,
                                         ingredientRanges: List<IntRange> = emptyList()): List<NumericOwner> {
    val result = mutableListOf<NumericOwner>()
    val identities = ProductLexicon.numericProductIdentities(name).map { it.key }.toSet()
    ProductLexicon.numericProductIdentities(source).forEach { identity ->
        result += NumericOwner(identity.amountRange.first, identity.amountRange.last + 1,
            if (identity.key in identities) "literal_product_identity" else "unpreserved_descriptor")
    }
    val patterns = listOf(numericDescriptor, numericNutrition, numericGrade, numericProductCompound, numericBatterySizeDescriptor) +
        if (includeLetterIds) listOf(numericLetterId) else emptyList()
    for (pattern in patterns) {
        val matches = if (pattern === numericDescriptor || pattern === numericNutrition) numericDescriptorMatches(pattern, source) else pattern.findAll(source)
        matches.forEach { match ->
            // The proven absence owns only its nested generic percent descriptor.
            // Purchase/pack overlaps still fail the ordinary partition check.
            if (pattern === numericDescriptor && ingredientRanges.any {
                    it.first <= match.range.first && match.range.last <= it.last
                }) return@forEach
            if (pattern === numericLetterId && match.groupValues[1].lowercase(Locale.ROOT) in numericUnitPrefixes) return@forEach
            result += NumericOwner(match.range.first, match.range.last + 1,
                if (numericLiteral(match.value, name)) "literal_descriptor" else "unpreserved_descriptor")
        }
    }
    return result
}

// Whole preserved product compounds only, not arbitrary numeric words in a
// model-proposed name. These spans cannot also serve as purchase/pack atoms.
private val numericProductCompound = Regex(
    "\\b(?:(?:pav|paav)[ \\t]+bhaji|do[ \\t]+pyaza|half[ \\t]+and[ \\t]+half)\\b", RegexOption.IGNORE_CASE,
)

// A belongs to this complete literal size phrase, never a free article/count.
// The ordinary partition rejects borrowing the same A as purchase/pack amount.
private val numericBatterySizeDescriptor = Regex(
    "\\b(?:double|triple)(?:[ \\t]+|[ \\t]*-[ \\t]*)a[ \\t]+batter(?:y|ies)\\b", RegexOption.IGNORE_CASE,
)

private fun numericLiteralRoles(source: String, name: String, required: List<String>, excluded: List<String>): List<NumericOwner> {
    val ingredientRanges = intentV2ProvenIngredientRanges(source, name, required, excluded)
    val result = numericLiteralDescriptorRoles(source, name, ingredientRanges = ingredientRanges).toMutableList()
    result += numericDescriptiveOneRoles(source, name, required, excluded)
    result += numericExcludedArticleRoles(source, name, required, excluded)
    numericAuxiliary.findAll(source).forEach { result += NumericOwner(it.range.first, it.range.last + 1, "auxiliary_do") }
    numericImperative.findAll(source).forEach { match ->
        val atom = requireNotNull(match.groups[1])
        result += NumericOwner(atom.range.first, atom.range.last + 1, "imperative_do")
    }
    intentV2ZeroIngredientRanges(source, name, required, excluded).forEach { range ->
        result += NumericOwner(range.first, range.last + 1, "zero_ingredient")
    }
    return result
}

/** Only the article of a complete retained exclusion is non-purchase evidence.
 * This supplies neither property validity nor permission to change any amount. */
private fun numericExcludedArticleRoles(source: String, name: String, required: List<String>,
                                        excluded: List<String>): List<NumericOwner> {
    val positive = name + " " + required.joinToString(" ") { it.replace('_', ' ') }
    return excluded.flatMap { exclusion ->
        // Other Unicode spaces must remain literally equal to the source.
        val phrase = exclusion.trim(' ', '\t').split(Regex("[ \\t]+")).joinToString(" ")
        if (phrase.isEmpty() || numericPositions(phrase).isNotEmpty() || numericLiteral(phrase, positive))
            return@flatMap emptyList()
        val literal = phrase.split(' ').joinToString("[ \\t]+") { Regex.escape(it) }
        Regex("\\bwithout[ \\t]+(an?)[ \\t]+" + literal + "(?=[ \\t]*[.!?]?[ \\t]*$)", RegexOption.IGNORE_CASE)
            .findAll(source).filter { match ->
                !hasQualifiedIngredientMatches(source, sequenceOf(match)) &&
                    !Regex("\\b(?:or|no)[ \\t,:-]*$|[\"'\u201c\u2018][ \\t]*$", RegexOption.IGNORE_CASE)
                        .containsMatchIn(source.substring(0, match.range.first))
            }.map { match ->
                val article = requireNotNull(match.groups[1])
                NumericOwner(article.range.first, article.range.last + 1, "excluded_article")
            }.toList()
    }.distinct()
}

/** No unowned text may follow the descriptor: only complete, retained claims. */
private fun closedDescriptiveOneTail(source: String, end: Int, name: String, required: List<String>,
                                    excluded: List<String>): Boolean {
    if (Regex("[\\s.,;!?]*").matches(source.substring(end))) return true
    val claims = intentV2ProvenIngredientRanges(source, name, required, excluded).filter { it.first >= end }
    if (claims.isEmpty()) return false
    var cursor = end
    for (claim in claims) {
        if (!Regex("[ \\t]*,[ \\t]*").matches(source.substring(cursor, claim.first))) return false
        cursor = claim.last + 1
    }
    return Regex("[ \\t]*[.!?]?[ \\t]*").matches(source.substring(cursor))
}

private fun numericDescriptiveOneRoles(source: String, name: String, required: List<String>,
                                      excluded: List<String>): List<NumericOwner> {
    fun words(value: String) = Regex("[\\p{L}\\p{M}]+").findAll(value.lowercase(Locale.ROOT)).map { it.value }.toSet()
    val preserved = words(name + " " + required.joinToString(" ") { it.replace('_', ' ') })
    val roles = mutableListOf<NumericOwner>()
    val excludedOne = intentV2RetainedExcludedOneMatch(source, name, required, excluded)
    numericDescriptiveOne.findAll(source).forEach { match ->
        if (excludedOne != null && match.groups[2]?.range == excludedOne.groups[2]?.range) {
            val atom = requireNotNull(match.groups[2])
            // Only this retained negative pronoun; purchase overlap still fails.
            roles += NumericOwner(atom.range.first, atom.range.last + 1, "descriptive_one")
            return@forEach
        }
        val descriptor = match.groupValues[1]
        val remaining = descriptor.toCharArray()
        val claims = intentV2ProvenIngredientRanges(descriptor, name, required, excluded)
        claims.forEach { range -> for (index in range) remaining[index] = ' ' }
        val residual = String(remaining)
        val descriptorWords = words(residual) - "and"
        val valid = (descriptorWords.isNotEmpty() || claims.isNotEmpty()) &&
            closedDescriptiveOneTail(source, match.range.last + 1, name, required, excluded) &&
            numericPositions(residual).isEmpty() && !intentV2HasCorrectionCue(residual) &&
            preserved.containsAll(descriptorWords)
        val atom = requireNotNull(match.groups[2])
        roles += NumericOwner(atom.range.first, atom.range.last + 1,
            if (valid) "descriptive_one" else "unpreserved_descriptor")
    }
    numericPronoun.findAll(source).forEach { match ->
        val atom = requireNotNull(match.groups[2])
        if (roles.none { it.start == atom.range.first && it.end == atom.range.last + 1 }) {
            roles += NumericOwner(atom.range.first, atom.range.last + 1,
                if (closedDescriptiveOneTail(source, match.range.last + 1, name, required, excluded) &&
                    numericLiteral(match.groupValues[1], name)) "descriptive_one" else "unpreserved_descriptor")
        }
    }
    return roles
}

/** Flag the new literal-tail proof for independent ORIGINAL item-edge checks. */
internal fun intentHasDescriptiveOneIngredientTail(source: String, name: String, required: List<String>,
                                                  excluded: List<String>): Boolean {
    val claims = intentV2ProvenIngredientRanges(source, name, required, excluded)
    return numericDescriptiveOneRoles(source, name, required, excluded).any { role ->
        role.role == "descriptive_one" && claims.any { it.first >= role.end }
    }
}

/** Internally derived ORIGINAL-source roles, never model-provided ignored ranges.
 * Opaque labels retain their exact spelling and require catalogue-title proof
 * before selection/cart use. This permits discovery, never an inferred count.
 */
internal fun intentProvenNonPurchaseNumericRanges(source: String, name: String, required: List<String>,
                                                excluded: List<String>): List<IntRange> {
    val roles = numericLiteralDescriptorRoles(source, name,
        ingredientRanges = intentV2ProvenIngredientRanges(source, name, required, excluded)) +
        numericDescriptiveOneRoles(source, name, required, excluded) +
        numericExcludedArticleRoles(source, name, required, excluded)
    if (roles.any { it.role == "unpreserved_descriptor" }) return emptyList()
    return roles.map { it.start until it.end }
}

/** Original-source identity requirements, never model-provided exemptions. */
internal fun groceryIntentOpaqueNumericLabels(source: String): Set<String> =
    ProductLexicon.numericProductIdentities(source).map { it.key }.toSet() + numericLetterId.findAll(source).filter {
        it.groupValues[1].lowercase(Locale.ROOT) !in numericUnitPrefixes
    }.map { it.value.lowercase(Locale.ROOT) }.toSet()

private fun numericDescriptorMatches(pattern: Regex, source: String): Sequence<MatchResult> = sequence {
    var end = -1
    for (start in source.indices) {
        val match = pattern.matchAt(source, start) ?: continue
        if (start < end) continue
        val atom = match.groupValues[1]
        if (atom.lowercase(Locale.ROOT) !in setOf("zero", "0") && parseIntentCorrectionAmount(atom) == null) continue
        yield(match)
        end = match.range.last + 1
    }
}

/** Catalogue specs are atomic number/unit pairs, never a bag of digit tokens.
 * This is an additional rejection gate, not proof of product identity, the
 * attribute owning a percentage, or interchangeability of physical dimensions.
 */
internal fun groceryIntentNumericDescriptorClaimsMatch(request: String, offered: String): Boolean {
    fun unit(literal: String) = when (literal.lowercase(Locale.ROOT)) {
        "volt", "volts", "v" -> "volt"
        "watt", "watts", "w" -> "watt"
        "percent", "%" -> "percent"
        "inch", "inches" -> "inch"
        else -> literal.lowercase(Locale.ROOT)
    }
    fun claims(matches: List<MatchResult>): Set<Pair<String, String>> = matches.asSequence()
        .map { match ->
            val amount = parseIntentCorrectionAmount(match.groupValues[1])
                ?.stripTrailingZeros()?.toPlainString() ?: "0"
            unit(match.groupValues[2]) to amount
        }.toSet()
    val wanted = claims(numericDescriptorMatches(numericDescriptor, request).toList())
    if (wanted.isEmpty()) return true
    val offeredMatches = numericDescriptorMatches(numericDescriptor, offered).toList()
    val present = claims(offeredMatches)
    if (!present.containsAll(wanted)) return false
    // Multiple values in one dimension need attribute/role binding, which this
    // closed gate does not establish. A matching title cannot hide a conflict
    // in the variant or subtitle combined into offered by the caller.
    if (wanted.groupBy { it.first }.any { it.value.size != 1 }) return false
    if (present.any { candidate -> wanted.any { it.first == candidate.first && it != candidate } }) return false
    // Do not silently drop an unparsed competing spec, e.g. decimal zero or a
    // number glued to a slash. Every requested-dimension unit must belong to a
    // complete parsed claim, including in the subtitle/variant.
    val unitMention = Regex("(?<![\\p{L}\\p{M}_])(volts?|v|watts?|w|mah|percent|spf|ply|mm|cm|inches?|%)(?![\\p{L}\\p{N}\\p{M}_])", RegexOption.IGNORE_CASE)
    if (unitMention.findAll(offered).any { mention ->
            wanted.any { it.first == unit(mention.value) } && offeredMatches.none {
                mention.range.first >= it.range.first && mention.range.last <= it.range.last
            }
        }) return false
    // Negated, ranged and conditional catalogue claims are not positive proof.
    if (unicodeEvidenceRegex("\\b(?:not|no|without|except|up\\s+to|between|or|approximately|around)\\b|≤|≥")
            .containsMatchIn(offered)) return false
    return offeredMatches.none { match ->
        val tail = offered.substring(match.range.last + 1)
        val dashAmount = Regex("^\\s*[-\u2013\u2014]\\s*[0-9]+(?:\\.[0-9]+)?(?![0-9.])").find(tail)
        val packAfterDash = dashAmount != null && unicodeEvidenceRegex("^\\s*(?:kg|g|grams?|ml|l|litres?|liters?|packs?|pieces?|pcs?|count)\\b")
            .containsMatchIn(tail.substring(dashAmount.range.last + 1))
        unicodeEvidenceRegex("(?:\\bnon[\\s-]*|[+\\-\u2212\uff0b\uff0d\u2013\u2014]\\s*)$")
            .containsMatchIn(offered.substring(0, match.range.first)) ||
            Regex("^\\s*[/+\u00d7]").containsMatchIn(tail) ||
            (dashAmount != null && !packAfterDash)
    }
}

private fun numericPartitionIssue(source: String, bound: List<NumericOwner>, literals: List<NumericOwner>,
                                  packText: String?, packUnit: String): String? {
    if (literals.any { it.role == "unpreserved_descriptor" }) return "numeric_descriptor_not_preserved"
    val positions = numericPositions(source)
    val owners = mutableMapOf<Int, String>()
    fun claim(owner: NumericOwner): Boolean {
        for (position in positions.filter { it >= owner.start && it < owner.end }) {
            if (position in owners) return false
            owners[position] = owner.role
        }
        return true
    }
    for (owner in bound + literals) if (!claim(owner)) return "numeric_atom_multiple_roles"
    val markerNumbers = distributiveEachNumberRanges(source)
    if (bound.any { owner -> owner.role == "quantity" && markerNumbers.any { it.overlaps(owner) } }) {
        return "distributive_each_as_purchase_count"
    }
    numericPhysicalUnits[packUnit]?.let { unit ->
        if (packText != null) {
            val pattern = Regex("(?<![\\p{L}\\p{N}_\\p{M}.+/\\-])($numericAmount)\\s*-?\\s*$unit\\s+(?:each|har\\s+ek)\\b", RegexOption.IGNORE_CASE)
            for (match in pattern.findAll(source)) {
                val atom = requireNotNull(match.groups[1])
                val selected = bound.any { it.role == "pack" && it.start == atom.range.first && it.end == atom.range.last + 1 }
                if (selected) {
                    if (!claim(NumericOwner(atom.range.last + 1, match.range.last + 1, "pack_each_suffix"))) return "numeric_atom_multiple_roles"
                } else if (parseIntentCorrectionAmount(atom.value)?.let { amount ->
                    parseIntentCorrectionAmount(packText)?.compareTo(amount) == 0
                } == true) {
                    if (!claim(NumericOwner(match.range.first, match.range.last + 1, "restated_pack_each"))) return "numeric_atom_multiple_roles"
                }
            }
        }
    }
    return if ((positions - owners.keys).isEmpty()) null else "numeric_atom_unowned"
}

private const val numericEachMarker = "(?:each|har\\s+ek)"
internal val groceryIntentDistributiveMarker = Regex(
    "(?<![\\p{L}\\p{N}_])(?:har\\s+(ek)|हर\\s+(एक)|each\\s+(one))(?![\\p{L}\\p{N}_])",
    RegexOption.IGNORE_CASE,
)
private fun distributiveEachNumberRanges(source: String): List<NumericOwner> = groceryIntentDistributiveMarker.findAll(source).mapNotNull { match ->
    val number = (1..match.groups.size - 1).mapNotNull { match.groups[it] }.firstOrNull() ?: return@mapNotNull null
    NumericOwner(number.range.first, number.range.last + 1, "distributive_each_number")
}.toList()

private fun numericEachAfter(source: String, end: Int, unit: String): Boolean = numericPhysicalUnits[unit]?.let {
    Regex("^\\s*-?\\s*$it\\s+$numericEachMarker\\b", RegexOption.IGNORE_CASE).containsMatchIn(source.substring(end))
} ?: false

private val numericPhysicalUnits: Map<String, String> get() = GroceryIntentPhysicalUnitMatcher.patterns
private val numericWord = "(?:" + (GROUP_NUMERIC_WORDS + setOf("½", "¼", "¾")).sortedWith(compareByDescending<String> { it.length }.thenBy { it }).joinToString("|") { Regex.escape(it) } + ")"
private val numericAmount = "(?:[0-9]+ [0-9]+/[0-9]+|[0-9]+/[0-9]+|[0-9]+(?:\\.[0-9]+)?|$numericWord(?:[ -]+(?:and[ -]+)?$numericWord){0,4})"
/** Shared lexical vocabulary only; ownership must still come from source syntax. */
internal val groceryIntentNumericAmountPattern: String get() = numericAmount
private val numericDescriptor = Regex("(?<![\\p{L}\\p{N}_\\p{M}.+/\\-])($numericAmount)\\s*-?\\s*(volts?|v|watts?|w|mah|percent|spf|ply|mm|cm|inches?|%)(?![\\p{L}\\p{N}_\\p{M}])", RegexOption.IGNORE_CASE)
private val numericNutrition = Regex("(?<![\\p{L}\\p{N}_\\p{M}.+/\\-])($numericAmount)\\s*(?:g|grams?|ml)\\s+(?:protein|fib(?:re|er)|carbs?|fat|sugar|sodium|calcium)\\s+per\\s+(?:serving|portion|100\\s*(?:g|grams?|ml))\\b", RegexOption.IGNORE_CASE)
private val numericLetterId = Regex("\\b([A-Za-z]{1,12})[0-9]{1,6}\\b")
private val numericUnitPrefixes = setOf("g", "gm", "gms", "gram", "grams", "kg", "kgs", "keji", "ml", "l", "ltr", "litre", "liter", "pack", "packet", "packs", "packets", "x", "pk", "pkt", "pkts", "pc", "pcs", "qty", "nos", "dz")
private val numericGrade = Regex("\\b(?:grade|vitamin)\\s+(a)\\b", RegexOption.IGNORE_CASE)
private val numericPronoun = Regex("\\b(?:the\\s+)?(blue|red|green|yellow|white|black|small|large|big|regular)\\s+(one)\\b", RegexOption.IGNORE_CASE)
private val numericDescriptiveOne = Regex("\\bthe\\s+((?:[\\p{L}\\p{N}\\p{M}]+(?:-[\\p{L}\\p{N}\\p{M}]+)*\\s+){1,8})(one)\\b", RegexOption.IGNORE_CASE)
private val numericAuxiliary = Regex("\\bdo(?=\\s+not\\s+substitute\\b)", RegexOption.IGNORE_CASE)
private val numericImperative = Regex("\\b(?:la|de|daal|kar|bhej|mangwa)\\s+(do)\\s*[.!?]*$", RegexOption.IGNORE_CASE)
private val numericPostpositionSaath = Regex("\\b(?:ke|iske|uske)\\s+saath\\b", RegexOption.IGNORE_CASE)

// Unicode numeric letters outside Nd/Nl/No, matching Python isNumeric semantics.
// Kept explicit because Java getNumericValue also treats ordinary A-Z as numbers.
private val numericLetterCodePoints = setOf(
    0x3405, 0x3483, 0x382a, 0x3b4d, 0x4e00, 0x4e03, 0x4e07, 0x4e09, 0x4e5d,
    0x4e8c, 0x4e94, 0x4e96, 0x4ebf, 0x4ec0, 0x4edf, 0x4ee8, 0x4f0d, 0x4f70,
    0x5104, 0x5146, 0x5169, 0x516b, 0x516d, 0x5341, 0x5343, 0x5344, 0x5345,
    0x534c, 0x53c1, 0x53c2, 0x53c3, 0x53c4, 0x56db, 0x58f1, 0x58f9, 0x5e7a,
    0x5efe, 0x5eff, 0x5f0c, 0x5f0d, 0x5f0e, 0x5f10, 0x62fe, 0x634c, 0x67d2,
    0x6f06, 0x7396, 0x767e, 0x8086, 0x842c, 0x8cae, 0x8cb3, 0x8d30, 0x9621,
    0x9646, 0x964c, 0x9678, 0x96f6, 0xf96b, 0xf973, 0xf978, 0xf9b2, 0xf9d1,
    0xf9d3, 0xf9fd, 0x20001, 0x20064, 0x200e2, 0x20121, 0x2092a, 0x20983,
    0x2098c, 0x2099c, 0x20aea, 0x20afd, 0x20b19, 0x22390, 0x22998, 0x23b1b,
    0x2626d, 0x2f890,
)
