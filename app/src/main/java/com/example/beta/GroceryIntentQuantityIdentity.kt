package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.SwiggyMcpClient.Recommendations
import com.example.beta.automation.IntentQuantityCandidate
import com.example.beta.automation.IntentLocalAmount
import com.example.beta.automation.IntentQuantityCatalogueCheck
import com.example.beta.automation.IntentQuantityOrigin
import com.example.beta.automation.IntentCartQuantityMode
import com.example.beta.automation.ParsedItem
import com.example.beta.automation.ProductLexicon
import com.example.beta.automation.Quantity
import com.example.beta.automation.backendInputText
import java.math.BigDecimal
import java.util.Locale

private val identityTokens = Regex("[\\p{L}\\p{M}]+|[0-9]+(?:\\.[0-9]+)?")
private val amountMarkers = ("pack packs packet packets bag bags bottle bottles carton cartons cup cups can cans " +
    "tin tins tub tubs jar jars box boxes bar bars block blocks pouch pouches sachet sachets " +
    "tube tubes loaf loaves refill refills roll rolls spray sprays bunch bunches " +
    "piece pieces pc pcs nos number numbers x g gm gms gram grams kg kgs kilo kilos " +
    "kilogram kilograms keji ml millilitre millilitres milliliter milliliters " +
    "l ltr litre litres liter liters").split(" ").toSet()

/** Mirrors the backend: this can only remove readiness, never invent a count. */
internal fun intentQuantityIdentityConflict(source: String, start: Int, end: Int,
                                            value: BigDecimal, unit: String, name: String): Boolean {
    if (unit !in setOf("count", "pack", "piece")) return false
    require(start >= 0 && end > start && end <= source.length)
    require(parseIntentCorrectionAmount(source.substring(start, end))?.compareTo(value) == 0)
    return boundCountConflictsWithTitle(source, end, value, name, interpreted = false)
}

/** The caller proves the original occurrence. This comparison can only veto a
 * purchase when a number belongs to the product name; it never grants units. */
private fun boundCountConflictsWithTitle(source: String, end: Int, value: BigDecimal,
                                         name: String, interpreted: Boolean): Boolean {
    fun titleValue(words: String) = parseIntentCorrectionAmount(words)
        ?: if (interpreted) ScopedInterpretedCardinal.value(words) else null
    val tail = identityTokens.find(source, end) ?: return false
    val word = tail.value.lowercase(Locale.ROOT)
    if (word in amountMarkers || word.first().isDigit() || parseIntentCorrectionAmount(word) != null) return false
    if (source.substring(end, tail.range.first).trim(' ', '\t', '-').isNotEmpty()) return false
    // Title normalization is comparison-only. Source UTF-16 offsets stay intact.
    val title = Regex("\\b([a-zA-Z]+)([A-Z][a-zA-Z]*)\\b").replace(name) {
        if (parseIntentCorrectionAmount(it.groupValues[1]) != null) it.groupValues[1] + " " + it.groupValues[2]
        else it.value
    }
    val tokens = identityTokens.findAll(title).map { it.value.lowercase(Locale.ROOT) }.toList()
    for (index in tokens.indices) {
        if (tokens[index] != word) continue
        for (first in maxOf(0, index - 5) until index) {
            if (titleValue(tokens.subList(first, index).joinToString(" "))?.compareTo(value) == 0) return true
        }
    }
    return false
}

private fun intentQuantityProofIssue(item: ParsedItem): String? {
    if (item.hasAuditedIntent()) return if (item.auditedIntent() != null) null else "interpreted_provenance_changed"
    if (item.requiresCompositeRetailOffer() && item.validCompositeRetailReceipt() == null)
        return "composite_retail_provenance_invalid"
    val sourceRetail = sourceNamedRetailCountReceipt(item.rawText)
    if (sourceRetail != null && !sourceRetail.hasRepresentableSize()) return "named_retail_size_not_representable"
    if (item.intentQuantityProvenance?.sourceCountRole == com.example.beta.automation.IntentSourceCountRole.NAMED_RETAIL) {
        if (item.validNamedRetailReceipt() == null) return "named_retail_provenance_invalid"
    } else if (sourceRetail != null) {
        // Existing V2 receipts remain supported. Removing the V1 receipt or
        // changing its role cannot reopen the old model-unit-only fallback.
        val proof = item.intentQuantityProvenance
        if (proof == null || !item.requiresIntentQuantityProof ||
            !groceryIntentSourceRetailReceiptValid(proof) ||
            !intentEvidenceTokens(item.query).containsAll(intentEvidenceTokens(sourceRetail.literalQuery())) ||
            !intentEvidenceTokens(item.strictMatchPhrase.orEmpty()).containsAll(intentEvidenceTokens(sourceRetail.literalQuery())))
            return "named_retail_provenance_missing"
    }
    if (item.requiresGenericContainerOffer() && item.validGenericContainerReceipt() == null)
        return "generic_container_provenance_invalid"
    if (item.requiresSourcePieceConversion() && item.validSourcePieceAmount() == null)
        return "source_piece_provenance_invalid"
    if (item.ingredientProvenance != item.intentQuantityProvenance?.ingredientProvenance ||
        item.ingredientProvenance?.matches(item) == false) return "ingredient_provenance_changed"
    if (!item.requiresIntentQuantityProof) return if (item.intentQuantityProvenance != null ||
        item.cartQuantityMode == IntentCartQuantityMode.INCREMENT) "quantity_provenance_missing" else null
    val proof = item.intentQuantityProvenance ?: return "quantity_provenance_missing"
    if (proof.terminalContext != null && (proof.origin != IntentQuantityOrigin.SOURCE_NUMERIC ||
            proof.terminalContext.spanFor(item.rawText) == null || proof.primaryText != item.rawText))
        return "terminal_context_provenance_changed"
    if (proof.origin == IntentQuantityOrigin.SOURCE_NUMERIC) {
        val sourceQuery = proof.numericSourceQuery ?: return "numeric_source_evidence_missing"
        if (proof.sourceCountRole != null || proof.retailContainer != null || proof.unitSize != null ||
            item.copy(rawText = proof.primaryText, query = sourceQuery, strictMatchPhrase = sourceQuery)
                .untypedSourceNumericIssue() != null) return "numeric_source_evidence_changed"
    } else if (proof.numericSourceQuery != null) return "numeric_source_evidence_changed"
    if (proof.genericContainerReceipt == null && (proof.retailContainer != null || proof.unitSize != null) &&
        !groceryIntentSourceRetailReceiptValid(proof)) return "retail_container_provenance_invalid"
    if (proof.rawText != item.rawText || proof.resolvedQuantity != item.quantity || proof.primaryText.isBlank() ||
        proof.query != item.query || proof.strictMatchPhrase != item.strictMatchPhrase ||
        proof.retailPackCount != item.retailPackCount || proof.logicalPieceCount != item.logicalPieceCount ||
        proof.cartQuantityMode != item.cartQuantityMode) {
        return "quantity_provenance_changed"
    }
    // Matching identity includes independently validated supporting modifiers;
    // primary-only extraction would lose codes quoted in a separate note.
    val codes = groceryIntentOpaqueNumericLabels(item.query)
    if (codes != proof.opaqueNumericLabels || !codes.containsAll(groceryIntentOpaqueNumericLabels(proof.primaryText)) ||
        (!item.strictMatchPhrase.isNullOrBlank() &&
            !groceryIntentOpaqueNumericLabels(item.strictMatchPhrase).containsAll(codes))) {
        return "numeric_product_code_provenance_changed"
    }
    val local = proof.local
    val additiveSource = Regex("\\banother\\b", RegexOption.IGNORE_CASE).containsMatchIn(proof.primaryText)
    if (item.cartQuantityMode == IntentCartQuantityMode.INCREMENT) {
        if (proof.origin != IntentQuantityOrigin.LOCAL || proof.corrected != null ||
            item.quantity != Quantity.Count(1) || local == null || local.source != proof.primaryText ||
            groceryIntentAdditiveCountPrefixRange(local) == null) return "quantity_operation_unproven"
    } else if (additiveSource) return "quantity_operation_unproven"
    if ((proof.origin in setOf(IntentQuantityOrigin.LOCAL, IntentQuantityOrigin.CORRECTION, IntentQuantityOrigin.SOURCE_NUMERIC)) != (local != null)) {
        return "quantity_provenance_invalid"
    }
    val corrected = proof.corrected
    if ((proof.origin == IntentQuantityOrigin.CORRECTION) != (corrected != null) ||
        (local != null && local.source != proof.primaryText)) return "quantity_provenance_invalid"
    for (amount in listOfNotNull(local, corrected)) {
        if (amount.source.isBlank() || amount.startUtf16 < 0 || amount.endUtf16 <= amount.startUtf16 ||
            amount.endUtf16 > amount.source.length || amount.source.substring(amount.startUtf16, amount.endUtf16) != amount.atom ||
            parseIntentCorrectionAmount(amount.atom)?.compareTo(amount.value) != 0 || amount.value.signum() <= 0) {
            return "quantity_provenance_invalid"
        }
        val normalized = runCatching {
            when (amount.unit) {
                "count", "pack", "piece" -> Quantity.Count(amount.value.intValueExact())
                "g" -> Quantity.Weight(amount.value.intValueExact())
                "kg" -> Quantity.Weight(amount.value.multiply(BigDecimal(1000)).intValueExact())
                "ml" -> Quantity.Volume(amount.value.intValueExact())
                "l" -> Quantity.Volume(amount.value.multiply(BigDecimal(1000)).intValueExact())
                else -> error("Unknown local unit")
            }
        }.getOrNull() ?: return "quantity_provenance_invalid"
        if ((proof.origin in setOf(IntentQuantityOrigin.LOCAL, IntentQuantityOrigin.SOURCE_NUMERIC) || amount === corrected) && normalized != item.quantity)
            return "quantity_provenance_changed"
        if (amount === corrected && (item.logicalPieceCount != (amount.unit == "piece") ||
                item.retailPackCount != (amount.unit == "pack"))) return "quantity_provenance_changed"
    }
    return null
}

private fun RecommendationCandidate.quantityIdentity() = IntentQuantityCandidate(
    spinId, skuId, label, variant, subtitle,
    catalogueQuantityKey = catalogueQuantityIdentityKey(),
    catalogueQuantityInvalid = catalogueQuantityIssue() != null,
    catalogueIngredientKey = catalogueIngredients?.identityKey(),
    catalogueIngredientInvalid = catalogueIngredientsInvalid,
)

/** Inspect all returned titles before shortlist filtering, including a separate suggestion. */
internal fun swiggyIntentQuantityIssue(item: ParsedItem, recommendations: Recommendations): String? {
    intentQuantityProofIssue(item)?.let { return it }
    if (!item.requiresIntentQuantityProof) return null
    val proof = item.intentQuantityProvenance ?: return null
    val amounts = item.auditedIntent()?.amountAtoms() ?: listOfNotNull(proof.local, proof.corrected)
    return swiggyLocalAmountCatalogueIssue(item, recommendations, amounts)
}

/** Pure read-only catalogue comparison. An empty issue supplies no provenance
 * or permission to build/apply a cart; executable callers validate those first. */
internal fun swiggyLocalAmountCatalogueIssue(
    item: ParsedItem, recommendations: Recommendations, amounts: List<IntentLocalAmount>,
): String? {
    val audited = item.auditedIntent()
    if (item.hasAuditedIntent() && (audited == null || audited.amountAtoms() != amounts))
        return "interpreted_provenance_changed"
    if (amounts.isEmpty()) return null
    val container = item.intentQuantityProvenance?.takeIf {
        it.local in amounts && it.primaryText == item.rawText && it.resolvedQuantity == item.quantity &&
            groceryIntentSourceRetailReceiptValid(it)
    }?.retailContainer
    val candidates = recommendations.candidates + listOfNotNull(recommendations.suggested)
    val titles = candidates.flatMap { candidate ->
        // Product titles always retain their numbers. Only a complete, SKU-bound
        // metadata field independently proving the requested single container is
        // quantity rather than a product/model number. The cart still validates
        // the untouched fields, outer count, brand, identity and pack conflicts.
        val fields = if (container == null || swiggyCatalogueQuantityConflict(candidate)) swiggyRawCatalogueFields(candidate)
        else listOfNotNull(candidate.label, candidate.variant, candidate.subtitle) +
            candidate.catalogueQuantityFields().orEmpty().map { (key, field) ->
                if (key != "subTitle" && isSingleNamedUnitField(field, container))
                    field.trim().drop(1).trimStart() else field
            }
        (fields + fields.joinToString(" ")).map { title ->
            swiggyQuantityIdentityTitle(item, candidate, title)
        }
    } + item.query
    return if (amounts.any { amount -> titles.any { title ->
        if (audited == null) intentQuantityIdentityConflict(
            amount.source, amount.startUtf16, amount.endUtf16, amount.value, amount.unit, title)
        else amount.unit in setOf("count", "pack", "piece") &&
            boundCountConflictsWithTitle(amount.source, amount.endUtf16, amount.value, title, interpreted = true)
    } }) "numeric_product_quantity_ambiguous" else null
}

internal fun assessSwiggyIntentQuantity(sourceItem: ParsedItem, recommendations: Recommendations): ParsedItem {
    val item = if (sourceItem.hasAuditedIntent()) sourceItem else sourceItem.withSourceRetailCountReceipt()
    if (!item.requiresIntentQuantityProof) return item
    return item.copy(intentQuantityCatalogueCheck = IntentQuantityCatalogueCheck(
        item.intentQuantityProvenance, item.query, recommendations.candidates.map { it.quantityIdentity() },
        swiggyIntentQuantityIssue(item, recommendations),
    ))
}

/** Final cart-plan boundary: checks are tied to this exact item and catalogue identity. */
internal fun swiggyIntentQuantityCandidateChecked(item: ParsedItem, candidate: RecommendationCandidate): Boolean {
    if (item.unprovenIdentityScope || item.quantitySignal != null) return false
    if (!swiggyTypedIngredientsMatch(item, candidate) || !swiggyIntentNumericCodeMatches(item, candidate)) return false
    if (swiggyCatalogueQuantityConflict(candidate)) return false
    if (intentQuantityProofIssue(item) != null) return false
    if (!item.requiresIntentQuantityProof) return item.untypedSourceNumericIssue() == null &&
        item.intentQuantityProvenance?.produceCategory == null && item.intentQuantityProvenance?.opaqueNumericLabels.isNullOrEmpty()
    val check = item.intentQuantityCatalogueCheck ?: return false
    return check.issue == null && check.provenance == item.intentQuantityProvenance && check.query == item.query &&
        candidate.quantityIdentity() in check.candidates && swiggyIntentProduceConstraintMatches(item, candidate)
}

/** Raw catalogue TITLE evidence only. History, a suggestion, variant and subtitle
 * cannot supply missing identity. No fuzzy match or code/quantity conversion. */
internal fun swiggyIntentNumericCodeMatches(item: ParsedItem, candidate: RecommendationCandidate): Boolean {
    if (intentQuantityProofIssue(item) != null) return false
    return swiggyCatalogueNumericCodesMatch(item.query, item.intentQuantityProvenance?.primaryText ?: item.rawText,
        item.intentQuantityProvenance?.opaqueNumericLabels.orEmpty(), candidate)
}

/** Catalogue facts only, shared with non-executable previews. */
internal fun swiggyCatalogueNumericCodesMatch(
    query: String, source: String, additionalCodes: Set<String>, candidate: RecommendationCandidate,
): Boolean {
    val queryCodes = groceryIntentOpaqueNumericLabels(query)
    val sourceCodes = groceryIntentOpaqueNumericLabels(source)
    if (!queryCodes.containsAll(sourceCodes)) return false
    val required = queryCodes + additionalCodes
    if (required.isEmpty()) return true
    val present = groceryIntentOpaqueNumericLabels(candidate.label)
    if (!present.containsAll(required)) return false
    // Other fields cannot add positive evidence, but conflicting metadata must
    // not be hidden just because the title contains the requested code.
    val fields = swiggyRawCatalogueFields(candidate).map(ProductLexicon::normalizeNumericProductIdentities)
    val allCodes = fields.flatMap(::groceryIntentOpaqueNumericLabels).toSet()
    fun family(code: String) = code.takeWhile { it in 'a'..'z' }
    // An A1+A2 or CR2025+CR2032 mixed title is not proof of the requested code.
    if (allCodes.any { code -> code !in required && required.any { family(it) == family(code) } }) return false
    return required.none { code ->
        val literal = "(?<![\\p{L}\\p{N}\\p{M}_])${Regex.escape(code)}(?![\\p{L}\\p{N}\\p{M}_])"
        val negated = Regex("(?<![\\p{L}\\p{N}_])(?:not|non|without|no|minus|free[\\s-]+from)[\\s-]+$literal|" +
            "$literal[\\s-]+free(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)
        fields.any(negated::containsMatchIn)
    }
}

/** Additional AND gate, never an alternative to exact identity/pack checks. */
internal fun swiggyIntentProduceConstraintMatches(item: ParsedItem, candidate: RecommendationCandidate): Boolean {
    val category = item.intentQuantityProvenance?.produceCategory ?: return true
    if (!item.requiresIntentQuantityProof || intentQuantityProofIssue(item) != null) return false
    return swiggyIntentProduceCandidateMatches(category, candidate)
}

internal fun swiggyIntentQuantitySource(item: ParsedItem): String? =
    item.intentQuantityProvenance?.primaryText?.takeIf { item.requiresIntentQuantityProof }

internal fun swiggyDisplayItem(item: ParsedItem): String {
    if (item.requiresIntentQuantityProof) return when (val amount = item.quantity) {
        is Quantity.Count -> "${amount.n} × ${item.query}"
        is Quantity.Weight -> "${amount.grams} g — ${item.query}"
        is Quantity.Volume -> "${amount.ml} ml — ${item.query}"
        else -> "1 suggested pack × ${item.query}"
    }
    return item.backendInputText().trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}
