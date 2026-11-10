package com.example.beta

import java.util.ArrayList
import java.util.Collections
import java.util.HashSet
import com.example.beta.automation.IntentQuantityProvenance
import com.example.beta.automation.ParsedItem

/** Immutable identity for one grouped-quantity proposal shown for explicit review. */
internal data class SwiggyQuantityProposalBinding(
    val rowIndex: Int,
    val source: String,
    val quantity: Int,
    val spinId: String,
    val skuId: String?,
    val evidence: SwiggyQuantityProposalEvidence,
) {
    // These values can contain user text or provider/catalogue identifiers.
    override fun toString(): String =
        "SwiggyQuantityProposalBinding(rowIndex=$rowIndex, quantity=$quantity, source=<redacted>, spinId=<redacted>, skuId=<redacted>, evidence=<redacted>)"
}

/** Structured, immutable evidence binding the interpreted source to its catalogue candidate. */
internal data class SwiggyQuantityProposalEvidence private constructor(
    val sourceItem: ParsedItem,
    val candidate: SwiggyMcpClient.RecommendationCandidate,
    val resolverVersion: String,
    val packSize: String,
    val contents: SwiggyRetailOfferContents,
) {
    // Source text and catalogue identifiers must not leak through data-class diagnostics.
    override fun toString(): String =
        "SwiggyQuantityProposalEvidence(sourceItem=<redacted>, candidate=<redacted>, resolverVersion=<redacted>, packSize=<redacted>)"

    companion object {
        fun snapshot(
            sourceItem: ParsedItem,
            candidate: SwiggyMcpClient.RecommendationCandidate,
            resolverVersion: String,
            packSize: String,
            contents: SwiggyRetailOfferContents = SwiggyRetailOfferContents.DisplayOnly(packSize),
        ): SwiggyQuantityProposalEvidence {
            require(resolverVersion.isNotBlank()) { "resolver version must be nonblank" }
            require(packSize.isNotBlank()) { "pack size must be nonblank" }
            require(contents.display == packSize) { "display must match the offer contents" }
            return SwiggyQuantityProposalEvidence(
                sourceItem = sourceItem.detachedForSwiggyReview(),
                candidate = candidate,
                resolverVersion = resolverVersion,
                packSize = packSize,
                contents = contents,
            )
        }
    }
}

internal fun ParsedItem.detachedForSwiggyReview(): ParsedItem = copy(
    avoidPhrases = immutableList(avoidPhrases),
    intentQuantityProvenance = intentQuantityProvenance?.detachedForQuantityProposal(),
    intentQuantityCatalogueCheck = intentQuantityCatalogueCheck?.let { check ->
        check.copy(
            provenance = check.provenance?.detachedForQuantityProposal(),
            candidates = immutableList(check.candidates),
        )
    },
)

private fun IntentQuantityProvenance.detachedForQuantityProposal(): IntentQuantityProvenance =
    copy(opaqueNumericLabels = immutableSet(opaqueNumericLabels))

private fun <T> immutableList(values: List<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

private fun <T> immutableSet(values: Set<T>): Set<T> =
    Collections.unmodifiableSet(HashSet(values))

/**
 * In-memory review-bound acknowledgement gate for grouped quantity proposals.
 * Matching proposals can be re-acknowledged until invalidated or consumed for apply.
 * No persistence or logging is performed here.
 */
internal class SwiggyQuantityProposalGate {
    private data class Snapshot(
        val operationId: Long,
        val addressId: String,
        val planToken: String,
        val proposals: List<SwiggyQuantityProposalBinding>,
    )

    private var prepared: Snapshot? = null
    private var acknowledged = false
    private var reviewContext: SwiggyQuantityReviewContext? = null
    private data class Carry(
        val snapshot: Snapshot, val context: SwiggyQuantityReviewContext, val seenTokens: Set<String>,
    )
    private var carry: Carry? = null
    private var seenTokens: Set<String> = emptySet()

    /** A fresh prepare always resets acknowledgement, including identical inputs. */
    @Synchronized
    fun prepare(
        operationId: Long,
        addressId: String,
        planToken: String,
        proposals: List<SwiggyQuantityProposalBinding>,
    ) {
        clear()
        prepared = snapshot(operationId, addressId, planToken, proposals)
        seenTokens = setOf(planToken)
    }

    /** The caller must have independently validated and rendered the full plan.
     * Legacy preparation without full context can never preserve consent. */
    @Synchronized
    fun prepareWithContext(
        operationId: Long, addressId: String, plan: SwiggyMcpClient.CartPlan,
        proposals: List<SwiggyQuantityProposalBinding>, context: SwiggyQuantityReviewContext,
    ) {
        clear()
        val planToken = requireNotNull(plan.confirmationToken) { "Plan token is required" }
        prepare(operationId, addressId, planToken, proposals)
        if (context.address.id != addressId || !context.binds(plan.snapshot) || proposals.any { !context.contains(it) }) {
            clear()
            throw IllegalArgumentException("Review context does not bind plan and proposals")
        }
        reviewContext = context
    }

    /** Synchronously revoke the token BEFORE constructing new context or starting
     * a refresh. Pass the last verified context; null discards any carry. Retained
     * acknowledgement cannot authorize Apply while a read is pending. */
    @Synchronized
    fun beginRefresh(context: SwiggyQuantityReviewContext?) {
        val previous = prepared
        carry = when {
            context == null -> null
            previous != null && acknowledged && reviewContext == context -> Carry(previous, context, seenTokens)
            previous == null && carry?.context == context -> carry
            else -> null
        }
        prepared = null
        acknowledged = false
        reviewContext = null
    }

    /** Atomic rebind, never a saved checkbox boolean. A fresh accepted plan may
     * carry acknowledgement only across identical facts and the entire proposal
     * set. Failure/ordinary preparation/lifecycle clear cannot revive old consent. */
    @Synchronized
    fun prepareAfterRefresh(
        operationId: Long, addressId: String, plan: SwiggyMcpClient.CartPlan,
        proposals: List<SwiggyQuantityProposalBinding>, context: SwiggyQuantityReviewContext,
    ): Boolean {
        val previous = carry
        prepareWithContext(operationId, addressId, plan, proposals, context)
        val current = requireNotNull(prepared)
        // Different from just the preceding token is insufficient: T0 -> T1 ->
        // T0 is still reuse. Bound the in-memory lineage; exhaustion drops carry.
        if (previous != null && current.planToken in previous.seenTokens) {
            clear()
            return false
        }
        acknowledged = previous != null && previous.context == context &&
            previous.snapshot.operationId == current.operationId && previous.snapshot.addressId == current.addressId &&
            previous.seenTokens.size < MAX_CARRY_TOKENS && previous.snapshot.proposals == current.proposals
        if (acknowledged) seenTokens = requireNotNull(previous).seenTokens + current.planToken
        return acknowledged
    }

    /**
     * Acknowledge only the exact current ordered proposal set. A stale or malformed
     * acknowledgement invalidates the prepared state so it cannot later be reused.
     * Returns true only when the supplied binding matched the prepared snapshot.
     */
    @Synchronized
    fun acknowledge(
        operationId: Long,
        addressId: String,
        planToken: String,
        currentProposals: List<SwiggyQuantityProposalBinding>,
        checked: Boolean,
    ): Boolean {
        val expected = prepared
        val current = try {
            snapshot(operationId, addressId, planToken, currentProposals)
        } catch (_: IllegalArgumentException) {
            clear()
            return false
        }
        if (expected == null || current != expected) {
            clear()
            return false
        }
        acknowledged = checked
        return true
    }

    /** Empty proposal sets need no acknowledgement; nonempty sets need a current checked acknowledgement. */
    @Synchronized
    fun canApply(
        operationId: Long,
        addressId: String,
        planToken: String,
        currentProposals: List<SwiggyQuantityProposalBinding>,
    ): Boolean {
        // A read in flight has no prepared authority. Merely rendering a disabled
        // action must not consume the tentative carry or resurrect its old token.
        val expected = prepared ?: return false
        val current = try {
            snapshot(operationId, addressId, planToken, currentProposals)
        } catch (_: IllegalArgumentException) {
            clear()
            return false
        }
        if (current != expected) {
            clear()
            return false
        }
        return current.proposals.isEmpty() || acknowledged
    }

    @Synchronized
    fun consumeForApply(
        operationId: Long,
        addressId: String,
        planToken: String,
        currentProposals: List<SwiggyQuantityProposalBinding>,
    ): Boolean {
        val allowed = canApply(operationId, addressId, planToken, currentProposals)
        clear()
        return allowed
    }

    @Synchronized
    fun clear() {
        prepared = null
        acknowledged = false
        reviewContext = null
        carry = null
        seenTokens = emptySet()
    }

    private fun snapshot(
        operationId: Long,
        addressId: String,
        planToken: String,
        proposals: List<SwiggyQuantityProposalBinding>,
    ): Snapshot {
        require(operationId > 0L) { "operation ID must be positive" }
        require(addressId.isNotBlank()) { "address ID must be nonblank" }
        require(planToken.isNotBlank()) { "plan token must be nonblank" }
        val copied = ArrayList(proposals.map { proposal ->
            proposal.copy(evidence = SwiggyQuantityProposalEvidence.snapshot(
                proposal.evidence.sourceItem, proposal.evidence.candidate,
                proposal.evidence.resolverVersion, proposal.evidence.packSize, proposal.evidence.contents,
            ))
        })
        require(copied.map { it.rowIndex }.toSet().size == copied.size) { "proposal row indices must be unique" }
        copied.forEach { proposal ->
            require(proposal.rowIndex >= 0) { "proposal row index must be nonnegative" }
            require(proposal.source.isNotBlank()) { "proposal source must be nonblank" }
            require(proposal.quantity in 1..MAX_PROPOSAL_QUANTITY) { "proposal quantity is out of range" }
            require(proposal.spinId.isNotBlank()) { "proposal spin ID must be nonblank" }
            require(proposal.skuId == null || proposal.skuId.isNotBlank()) { "proposal SKU ID must be nonblank when present" }
        }
        return Snapshot(operationId, addressId, planToken, Collections.unmodifiableList(copied))
    }

    private companion object {
        const val MAX_PROPOSAL_QUANTITY = 20
        const val MAX_CARRY_TOKENS = 128
    }
}
