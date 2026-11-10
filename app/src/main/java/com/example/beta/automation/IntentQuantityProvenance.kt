package com.example.beta.automation

import java.math.BigDecimal
import com.example.beta.IntentProduceCategory

/** Local-only evidence. Never log or add to analytics/advertising payloads. */
data class IntentLocalAmount(
    val source: String,
    val startUtf16: Int,
    val endUtf16: Int,
    val atom: String,
    val value: BigDecimal,
    val unit: String,
)

/** Local immutable source receipt; never a model role or a cart authorization. */
data class IntentContainerContentsProof(
    val outer: IntentLocalAmount,
    val inner: IntentLocalAmount,
    val container: String,
    val query: String,
) {
    override fun toString(): String = "IntentContainerContentsProof(<redacted>)"
}

enum class IntentQuantityOrigin { LOCAL, GROUP, CORRECTION, UNSTATED, SOURCE_NUMERIC, INTERPRETED }
enum class IntentSourceCountRole { REQUESTED_PIECES, GENERIC_WRAPPERS, NAMED_RETAIL, COMPOSITE_RETAIL_OFFER }

data class IntentQuantityProvenance(
    val origin: IntentQuantityOrigin,
    val primaryText: String,
    val rawText: String,
    val resolvedQuantity: Quantity,
    val local: IntentLocalAmount?,
    val query: String,
    val strictMatchPhrase: String?,
    val retailPackCount: Boolean,
    val logicalPieceCount: Boolean,
    // Derived only after source/group validation. Candidate snapshots bind this
    // constraint too, so editing or removing it invalidates a prior assessment.
    val produceCategory: IntentProduceCategory? = null,
    // Derived from the source-validated matching identity (including modifiers).
    // A title must confirm these codes before provisional discovery can be selected.
    val opaqueNumericLabels: Set<String> = emptySet(),
    // A corrected amount belongs to its own validated operation, not to the
    // obsolete original amount. Recheck it at the final catalogue boundary.
    val corrected: IntentLocalAmount? = null,
    // The source-owned action survives discovery and must not change into a total.
    val cartQuantityMode: IntentCartQuantityMode = IntentCartQuantityMode.TARGET,
    // Local receipt for an independently source-proved outer retail container.
    val retailContainer: String? = null,
    // Optional local physical size bound within that retail-container receipt.
    val unitSize: IntentLocalAmount? = null,
    val ingredientProvenance: com.example.beta.IntentIngredientProvenance? = null,
    // Derived from the complete source, never from the producer's unit enum.
    val sourceCountRole: IntentSourceCountRole? = null,
    // A generic wrapper is an offer only, never a particular retail package.
    val genericContainerReceipt: com.example.beta.GenericContainerReceipt? = null,
    // Numeric validation is not purchase-role authority. Keep the validated
    // identity separate from a later, preference-enriched retrieval query.
    val numericSourceQuery: String? = null,
    // Three independently owned source amounts; always an offer, never Exact.
    val compositeRetailReceipt: com.example.beta.CompositeRetailReceipt? = null,
    // Non-serialized original-instruction proof; cannot supply identity or amounts.
    val terminalContext: com.example.beta.GroceryIntentTerminalContext? = null,
    // A model audit is not source grammar or seller proof. Its own immutable
    // basket binding must survive through discovery and the final review.
    val audited: com.example.beta.AuditedIntentProvenance? = null,
)

data class IntentQuantityCandidate(
    val spinId: String,
    val skuId: String?,
    val label: String,
    val variant: String?,
    val subtitle: String?,
    val catalogueQuantityKey: String? = null,
    val catalogueQuantityInvalid: Boolean = false,
    val catalogueIngredientKey: String? = null,
    val catalogueIngredientInvalid: Boolean = false,
)

/** Binds the full-list check to the source, interpreted amount, and exact candidates. */
data class IntentQuantityCatalogueCheck(
    val provenance: IntentQuantityProvenance?,
    val query: String,
    val candidates: List<IntentQuantityCandidate>,
    val issue: String?,
)
