package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.SwiggyMcpClient.RequestedItem
import com.example.beta.automation.ParsedItem
import java.util.Collections

/** Keep wording categories separate: exclusion and acknowledgement are not interchangeable. */
internal data class SwiggyQuantityReviewText(
    val unresolved: List<String> = emptyList(),
    val excluded: List<String> = emptyList(),
    val sharedGroups: List<String> = emptyList(),
    val fulfillment: List<String> = emptyList(),
) {
    internal fun detached(): SwiggyQuantityReviewText = SwiggyQuantityReviewText(
        Collections.unmodifiableList(ArrayList(unresolved)), Collections.unmodifiableList(ArrayList(excluded)),
        Collections.unmodifiableList(ArrayList(sharedGroups)), Collections.unmodifiableList(ArrayList(fulfillment)),
    )
    override fun toString(): String = "SwiggyQuantityReviewText(<redacted>)"
}

/** Complete, immutable review facts. Optional row ticks are deliberately NOT
 * facts: changing a tick requires a fresh plan, but need not repeat an unchanged
 * grouped-quantity acknowledgement. This context alone grants no consent. */
internal class SwiggyQuantityReviewContext private constructor(
    private val instruction: String,
    val address: SwiggyMcpClient.SwiggyAddress,
    private val items: List<ParsedItem>,
    private val candidates: Map<Int, RecommendationCandidate>,
    private val skipped: Set<Int>,
    private val reviewText: SwiggyQuantityReviewText,
    private val existingItems: List<SwiggyMcpClient.CartPlanItem>,
    private val reviewBasis: String,
) {
    internal fun contains(proposal: SwiggyQuantityProposalBinding): Boolean =
        proposal.rowIndex !in skipped && items.getOrNull(proposal.rowIndex) == proposal.evidence.sourceItem &&
            candidates[proposal.rowIndex] == proposal.evidence.candidate

    internal fun binds(snapshot: SwiggyMcpClient.CartPlanSnapshot?): Boolean =
        snapshot != null && snapshot.reviewBasis == reviewBasis && snapshot.existingItems == existingItems

    override fun equals(other: Any?): Boolean = other is SwiggyQuantityReviewContext &&
        instruction == other.instruction && address == other.address && items == other.items &&
        candidates == other.candidates && skipped == other.skipped && reviewText == other.reviewText &&
        existingItems == other.existingItems && reviewBasis == other.reviewBasis
    override fun hashCode(): Int =
        listOf(instruction, address, items, candidates, skipped, reviewText, existingItems, reviewBasis).hashCode()
    override fun toString(): String = "SwiggyQuantityReviewContext(<redacted>)"

    companion object {
        fun create(
            instruction: String,
            address: SwiggyMcpClient.SwiggyAddress,
            items: List<ParsedItem>,
            candidates: Map<Int, RecommendationCandidate>,
            skipped: Set<Int>,
            reviewText: SwiggyQuantityReviewText,
            snapshot: SwiggyMcpClient.CartReviewSnapshot,
        ): SwiggyQuantityReviewContext {
            require(instruction.isNotBlank() && address.id.isNotBlank())
            require(auditedIntentBasketMatches(items, instruction)) { "Interpreted basket changed" }
            require(candidates.keys.all { it in items.indices } && skipped.all { it in items.indices })
            require(items.indices.filterNot { it in skipped }.all { it in candidates })
            require(Regex("cart-basis-v1:[0-9a-f]{64}").matches(snapshot.reviewBasis))
            require(snapshot.existingItems.all { it.spinId.isNotBlank() && it.quantity in 1..100 &&
                (it.skuId == null || it.skuId.isNotBlank()) })
            require(snapshot.existingItems.map { it.spinId }.distinct().size == snapshot.existingItems.size)
            return SwiggyQuantityReviewContext(
                instruction, address, Collections.unmodifiableList(items.map { it.detachedForSwiggyReview() }),
                Collections.unmodifiableMap(LinkedHashMap(candidates)), Collections.unmodifiableSet(HashSet(skipped)),
                reviewText.detached(),
                Collections.unmodifiableList(ArrayList(snapshot.existingItems)), snapshot.reviewBasis,
            )
        }
    }
}

/** One owned request snapshot for planning, review, and the final pre-write check.
 * Empty selection is valid but does not authorize a request or a cart mutation. */
internal class SwiggyCartReviewRequest private constructor(
    val sourceItems: List<ParsedItem>,
    private val candidates: Map<Int, RecommendationCandidate>,
    private val skipped: Set<Int>,
    val requestedItems: List<RequestedItem>,
    val quantityProposals: List<SwiggyQuantityProposalBinding>,
    private val contextBasket: GroceryIntentContextBasket? = null,
) {
    override fun equals(other: Any?): Boolean = other is SwiggyCartReviewRequest &&
        sourceItems == other.sourceItems && candidates == other.candidates && skipped == other.skipped &&
        requestedItems == other.requestedItems && quantityProposals == other.quantityProposals && contextBasket === other.contextBasket

    override fun hashCode(): Int = listOf(sourceItems, candidates, skipped, requestedItems, quantityProposals, contextBasket).hashCode()
    override fun toString(): String = "SwiggyCartReviewRequest(<redacted>)"

    companion object {
        fun build(
            items: List<ParsedItem>,
            candidates: Map<Int, RecommendationCandidate>,
            skipped: Set<Int>,
            auditedBasket: AuditedIntentBasket? = null,
        ): SwiggyCartReviewRequest? = if (!auditedIntentBasketMatches(items, expected = auditedBasket)) null
        else buildOwned(items, candidates, skipped, null) { index, item, candidate ->
            if (!isSwiggyCandidateAllowed(item, candidate)) null else {
                val requested = runCatching { swiggyPreviewItemFromParsedItem(candidate, item) }.getOrNull()
                requested?.let { it to swiggyQuantityProposalBinding(index, item, candidate) }
            }
        }

        fun buildContext(
            basket: GroceryIntentContextBasket,
            candidates: Map<Int, RecommendationCandidate>,
            skipped: Set<Int>,
        ): SwiggyCartReviewRequest? = buildOwned(basket.items, candidates, skipped, basket) { index, _, candidate ->
            basket.preview(index, candidate)?.let { it to basket.binding(index, candidate) }
        }

        private fun buildOwned(
            items: List<ParsedItem>, candidates: Map<Int, RecommendationCandidate>, skipped: Set<Int>,
            contextBasket: GroceryIntentContextBasket?,
            resolve: (Int, ParsedItem, RecommendationCandidate) -> Pair<RequestedItem, SwiggyQuantityProposalBinding?>?,
        ): SwiggyCartReviewRequest? {
            if (!auditedIntentBasketMatches(items)) return null
            if (candidates.keys.any { it !in items.indices } || skipped.any { it !in items.indices }) return null
            val source = items.map { it.detachedForSwiggyReview() }
            val candidateCopy = LinkedHashMap(candidates)
            val skippedCopy = HashSet(skipped)
            val requests = mutableListOf<RequestedItem>()
            val proposals = mutableListOf<SwiggyQuantityProposalBinding>()
            for (index in source.indices) {
                if (index in skippedCopy) continue
                val item = source[index]
                val candidate = candidateCopy[index] ?: return null
                val (requested, proposal) = resolve(index, item, candidate) ?: return null
                requests += requested
                proposal?.let(proposals::add)
            }
            val combined = consolidateSwiggyRequestedItems(requests)
            if (requests.isNotEmpty() && combined.isEmpty()) return null
            return SwiggyCartReviewRequest(
                Collections.unmodifiableList(source), Collections.unmodifiableMap(candidateCopy),
                Collections.unmodifiableSet(skippedCopy), Collections.unmodifiableList(combined),
                Collections.unmodifiableList(proposals), contextBasket,
            )
        }
    }
}
