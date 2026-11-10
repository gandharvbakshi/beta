package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.SwiggyMcpClient.Recommendations
import com.example.beta.automation.ParsedItem
import java.util.Collections

/** Read-only product compatibility, NOT a quantity offer or an executable row.
 * There is deliberately no cart-unit count, RequestedItem, provenance, consent
 * or conversion API. Context remains pending even if products can be displayed. */
internal class GroceryIntentPendingCandidateAssessment private constructor(
    private val witness: GroceryIntentPendingCountWitness,
    private val catalogue: Recommendations,
    val candidates: List<RecommendationCandidate>,
    val holdReason: String?,
) {
    fun matches(instruction: String, rowIndex: Int, item: ParsedItem, recommendations: Recommendations): Boolean =
        witness.matches(instruction, rowIndex, item) && catalogue == recommendations

    /** Display proposal only: it has no cart conversion, provenance or consent. */
    fun offerFor(candidate: RecommendationCandidate): GroceryIntentPendingRetailOffer? =
        GroceryIntentPendingRetailOffer.create(this, candidate)

    internal fun offerFactsFor(candidate: RecommendationCandidate): Pair<Int, SwiggyRetailOfferContents>? {
        if (holdReason != null || candidate !in candidates || candidate.purchaseLimitHold(witness.requestedCount) != null) return null
        val contents = witness.ordinaryOfferContents(candidate) ?: return null
        return witness.requestedCount to contents
    }

    override fun toString(): String = "GroceryIntentPendingCandidateAssessment(<redacted>)"

    companion object {
        fun assess(
            instruction: String, rowIndex: Int, item: ParsedItem,
            witness: GroceryIntentPendingCountWitness, recommendations: Recommendations,
        ): GroceryIntentPendingCandidateAssessment {
            // Candidate values contain immutable strings/typed immutable fields;
            // only the external list needs detaching. Include suggested and all
            // response flags in the bound snapshot, not merely selected IDs.
            val snapshot = recommendations.copy(candidates = Collections.unmodifiableList(recommendations.candidates.toList()))
            fun result(candidates: List<RecommendationCandidate>, reason: String? = null) =
                GroceryIntentPendingCandidateAssessment(witness, snapshot, Collections.unmodifiableList(candidates.toList()), reason)
            witness.catalogueIssue(instruction, rowIndex, item, snapshot)?.let { return result(emptyList(), it) }
            if (swiggyNeedsExactHealthProduct(item)) return result(emptyList(), "exact_health_product_required")
            if (snapshot.catalogueMatchUnverified) return result(emptyList(), "catalogue_match_unverified")
            val all = snapshot.candidates + listOfNotNull(snapshot.suggested)
            // Two contradictory descriptions of the same SKU cannot be silently
            // resolved by list order. A suggestion badge alone is not a conflict.
            if (all.groupBy { it.spinId to it.skuId }.values.any { duplicates ->
                    duplicates.map { it.copy(suggested = false) }.distinct().size > 1
                }) return result(emptyList(), "catalogue_candidate_conflict")
            val compatible = all.distinctBy { it.spinId to it.skuId }.filter { candidate ->
                candidate.spinId.isNotBlank() && swiggyCatalogueContentMatches(item, candidate) &&
                    swiggyCatalogueNumericCodesMatch(item.query, item.rawText, emptySet(), candidate)
            }
            return result(compatible, if (compatible.isEmpty()) "no_compatible_candidate" else null)
        }
    }
}

/** A suggested N times this concrete offer, never an Exact source count role.
 * Only an owning context review can later bind interpretation and user consent. */
internal class GroceryIntentPendingRetailOffer private constructor(
    private val assessment: GroceryIntentPendingCandidateAssessment,
    val requestedCount: Int,
    val candidate: RecommendationCandidate,
    val contents: SwiggyRetailOfferContents,
) {
    fun matches(instruction: String, rowIndex: Int, item: ParsedItem, recommendations: Recommendations): Boolean =
        assessment.matches(instruction, rowIndex, item, recommendations)

    override fun toString(): String = "GroceryIntentPendingRetailOffer(<redacted>)"

    companion object {
        internal fun create(assessment: GroceryIntentPendingCandidateAssessment,
                            candidate: RecommendationCandidate): GroceryIntentPendingRetailOffer? {
            val (count, contents) = assessment.offerFactsFor(candidate) ?: return null
            if (count !in 1..20) return null
            return GroceryIntentPendingRetailOffer(assessment, count, candidate, contents)
        }
    }
}
