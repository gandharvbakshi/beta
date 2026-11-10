package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.SwiggyMcpClient.Recommendations
import com.example.beta.SwiggyMcpClient.RequestedItem
import com.example.beta.automation.ParsedItem
import java.util.Collections

/** One immutable interpretation and catalogue revision. It can propose a plan,
 * never manufacture source provenance or authorize an apply. Identity equality
 * is intentional: rebuilding the interpretation/catalogue revokes old consent. */
internal class GroceryIntentContextBasket private constructor(
    val preparation: GroceryIntentContextPreparation,
    val items: List<ParsedItem>,
    private val recommendations: List<Recommendations>,
    private val pending: Map<Int, GroceryIntentPendingCandidateAssessment>,
) {
    fun usable(index: Int): List<RecommendationCandidate> {
        val item = items.getOrNull(index) ?: return emptyList()
        val catalogue = recommendations[index]
        val assessment = pending[index] ?: return swiggyUsableCandidates(item, catalogue)
        // The whole response is scanned for conflicts, but suggested-only SKUs
        // do not become selectable merely because this is the context route.
        return catalogue.candidates.filter { assessment.offerFor(it) != null }.take(SWIGGY_MAX_CANDIDATES)
    }

    fun resolution(index: Int, candidate: RecommendationCandidate): SwiggyCartQuantityResolution {
        if (candidate !in usable(index)) return SwiggyCartQuantityResolution.Held("context_candidate_not_current")
        val assessment = pending[index] ?: return resolveSwiggyCartQuantity(items[index], candidate)
        val offer = assessment.offerFor(candidate) ?: return SwiggyCartQuantityResolution.Held("context_offer_unproven")
        if (!offer.matches(preparation.instruction, index, items[index], recommendations[index]))
            return SwiggyCartQuantityResolution.Held("context_source_changed")
        return SwiggyCartQuantityResolution.RetailProposal(offer.requestedCount, offer.contents)
    }

    /** Plan payload only. It must remain owned by a current review request and
     * pass the existing address/plan/one-shot consent gates before mutation. */
    fun preview(index: Int, candidate: RecommendationCandidate): RequestedItem? {
        val count = when (val value = resolution(index, candidate)) {
            is SwiggyCartQuantityResolution.Exact -> value.cartUnits
            is SwiggyCartQuantityResolution.RetailProposal -> value.cartUnits
            is SwiggyCartQuantityResolution.Held -> return null
        }
        return RequestedItem(candidate.spinId, count, swiggyCandidateLabel(candidate), candidate.skuId,
            items[index].cartQuantityMode, candidate.cataloguePurchaseLimit)
    }

    fun binding(index: Int, candidate: RecommendationCandidate): SwiggyQuantityProposalBinding? {
        if (index !in pending) return items.getOrNull(index)?.let { swiggyQuantityProposalBinding(index, it, candidate) }
        val proposal = resolution(index, candidate) as? SwiggyCartQuantityResolution.RetailProposal ?: return null
        val item = items[index]
        return SwiggyQuantityProposalBinding(index, item.rawText, proposal.cartUnits, candidate.spinId, candidate.skuId,
            SwiggyQuantityProposalEvidence.snapshot(item, candidate, "context-offer-v1", proposal.packSize, proposal.contents))
    }

    override fun toString(): String = "GroceryIntentContextBasket(<redacted>)"

    companion object {
        fun create(preparation: GroceryIntentContextPreparation, recommendations: List<Recommendations>): GroceryIntentContextBasket? {
            if (preparation.blockingIssues.isNotEmpty()) return null
            val original = preparation.legacyPreparation.items
            if (!areSwiggyRecommendationsOrdered(recommendations, original.map(::swiggyRecommendationQuery))) return null
            val catalogue = Collections.unmodifiableList(recommendations.map {
                it.copy(candidates = Collections.unmodifiableList(it.candidates.toList()))
            })
            val pending = preparation.pendingCounts.mapValues { (index, witness) ->
                GroceryIntentPendingCandidateAssessment.assess(preparation.instruction, index, original[index], witness, catalogue[index])
            }
            // Assessing a pending item would change the exact witness snapshot.
            // Ordinary failures never fall back into the pending-count path.
            val items = original.mapIndexed { index, item ->
                (if (index in pending) item else assessSwiggyIntentQuantity(item, catalogue[index])).detachedForSwiggyReview()
            }
            return GroceryIntentContextBasket(preparation, Collections.unmodifiableList(items), catalogue,
                Collections.unmodifiableMap(pending))
        }
    }
}
