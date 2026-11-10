package com.example.beta

import com.example.beta.automation.ParsedItem
import com.example.beta.automation.Quantity

/** A terminal interpreted-policy branch. It uses only existing same-SKU facts
 * and exact arithmetic, never legacy source-role inference. The shared caller
 * still requires the candidate snapshot and applies the seller purchase limit. */
internal fun resolveAuditedSwiggyQuantity(item: ParsedItem, candidate: SwiggyMcpClient.RecommendationCandidate): SwiggyCartQuantityResolution {
    fun held(reason: String) = SwiggyCartQuantityResolution.Held(reason)
    fun exact(n: Int?) = n?.takeIf { it in 1..20 }?.let { SwiggyCartQuantityResolution.Exact(it) }
        ?: held("interpreted_quantity_not_exact")
    val interpreted = item.auditedIntent() ?: return held("interpreted_provenance_changed")
    if (!isSwiggyCandidateAllowed(item, candidate)) return held("interpreted_product_not_proven")
    if (interpreted.bareHighPieceIdentity?.let { !exactHighPieceSkuIdentity(it, candidate) } == true)
        return held("interpreted_piece_identity_not_proven")
    // A household-container request did not name a particular shape. Present
    // an explicitly packed rigid vessel for review, never loose weight or a bar
    // as if it were a vessel. A model-proposed shape is not source evidence.
    if (interpreted.sourceGenericOuter && !isHouseholdContainerOffer(candidate.ownedOuterAndNetSize()?.outer))
        return held("interpreted_container_not_proven")
    val container = interpreted.packaging?.takeUnless { it in setOf("pack", "packet") }
    val describedMeasure = if (container != null && !swiggyCatalogueHasSingleRetailUnit(candidate, container))
        swiggyAuditedDescribedOuterMeasure(candidate, container) ?: return held("interpreted_container_not_proven")
        else null
    val physical = describedMeasure ?: swiggyCatalogueSourcePhysicalMeasure(candidate, container, defaultVariant = true) { outer ->
        interpreted.packaging == null || interpreted.packaging == outer
    } ?: candidate.auditedVariantNetContents(interpreted)?.size
    val pieces = swiggyCatalogueSourcePieceCount(candidate, item.query)
    val pack = interpreted.packAtom?.let { GroceryPackMeasure.of(it.value, it.unit) }
    if (pack != null) {
        val actual = if (pack.dimension == GroceryPackDimension.PIECES) pieces?.let { GroceryPackMeasure.of(it.toBigDecimal(), "pieces") } else physical
        if (actual != pack) return held("interpreted_unit_size_not_proven")
    }
    return when (interpreted.role) {
        "requested_pieces" -> {
            val requested = (item.quantity as? Quantity.Count)?.n ?: return held("interpreted_piece_count_missing")
            if (!swiggyPreparedPieceOfferMatches(item, candidate)) return held("prepared_piece_not_proven")
            exact(pieces?.takeIf { requested % it == 0 }?.let { requested / it })
        }
        "physical_total" -> {
            // Typed metadata must independently prove the same measure used by
            // the existing exact divisor. Display-only size cannot supply it.
            if (physical == null) return held("interpreted_seller_measure_missing")
            val total = when (val amount = item.quantity) {
                is Quantity.Weight -> GroceryPackMeasure.of(amount.grams.toBigDecimal(), "g")
                is Quantity.Volume -> GroceryPackMeasure.of(amount.ml.toBigDecimal(), "ml")
                else -> return held("interpreted_physical_total_missing")
            }
            if (total.dimension != physical.dimension) return held("interpreted_measure_dimension_changed")
            val division = total.baseValue.divideAndRemainder(physical.baseValue)
            if (division[1].signum() != 0) return held("interpreted_quantity_not_exact")
            exact(runCatching { division[0].intValueExact() }.getOrNull())
        }
        "unstated" -> if (item.quantity == Quantity.Default && swiggySingleSkuDefaultAllowed(candidate)) exact(1)
            else held("interpreted_default_bundle_unproven")
        "retail_units" -> {
            val count = (item.quantity as? Quantity.Count)?.n ?: return held("interpreted_retail_count_missing")
            val offer = swiggyProvenRetailOfferContents(candidate, item.query)
                ?: physical?.let { measure -> candidate.catalogueQuantity?.let { evidence ->
                    val unit = if (measure.dimension == GroceryPackDimension.WEIGHT) "g" else "ml"
                    SwiggyRetailOfferContents.Proven(measure, evidence, "${measure.baseValue.stripTrailingZeros().toPlainString()} $unit")
                } } ?: return held("interpreted_retail_contents_missing")
            if (count !in 1..20) held("interpreted_retail_count_limit")
            else SwiggyCartQuantityResolution.RetailProposal(count, offer)
        }
        else -> held("interpreted_quantity_role_unresolved")
    }
}

/** An unmarked high piece count needs a whole literal product identity, not overlapping search terms. */
private fun exactHighPieceSkuIdentity(source: String, candidate: SwiggyMcpClient.RecommendationCandidate): Boolean {
    if (candidate.spinId.isBlank() || candidate.skuId.isNullOrBlank() || candidate.catalogueIngredientsInvalid) return false
    val titles = candidate.catalogueIngredients?.let { it.titlesFor(candidate.spinId, candidate.skuId) ?: return false }.orEmpty()
    val variant = candidate.variant?.takeIf { value -> value.isNotBlank() &&
        candidate.catalogueQuantityFields().orEmpty().any { (key, field) ->
            key in setOf("variantName", "variant", "packSize", "quantityDescription") && field == value } }
    // A clean-looking title must not override a conflicting product name on the same offer.
    return (titles + candidate.label).all { title ->
        pieceIdentityEquals(source, title) || variant != null && title.endsWith(" · $variant") &&
            pieceIdentityEquals(source, title.removeSuffix(" · $variant"))
    }
}
