package com.example.beta

import java.util.Collections
import com.example.beta.automation.ProductLexicon

internal const val GROCERY_INTENT_CONTEXT_CONTRACT = "grocery-intent-v1-context"

/** The producer proposes a role; exact binding is not proof that the role is true. */
internal enum class GroceryIntentContextKind(val wire: String) {
    BACKGROUND("background"), FULFILLMENT("fulfillment"), UNRESOLVED("unresolved"),
}

internal data class GroceryIntentContextProposal(
    val kind: GroceryIntentContextKind,
    val source: GroceryIntentReviewV2Quote,
) {
    override fun toString(): String = "GroceryIntentContextProposal(kind=$kind, <redacted>)"
}

/** Negotiated adapter only. Legacy V1 must still reject every context response.
 * Keep context out of the item compiler; it cannot supply numeric, identity or
 * coverage authority. All ordinary V1 envelope/item validations remain shared. */
internal class GroceryIntentContextResponse private constructor(
    private val legacy: GroceryIntentResponse,
    val proposals: List<GroceryIntentContextProposal>,
) {
    fun prepareForReview(): GroceryIntentContextPreparation {
        // Compatibility entry remains genuinely fail-fast. The network context
        // path deliberately uses assessForReview to retain semantic holds.
        return (GroceryIntentContextPreparation.assess(this, collectHolds = false)
            as GroceryIntentContextAssessment.Prepared).preparation
    }

    fun assessForReview(): GroceryIntentContextAssessment = GroceryIntentContextPreparation.assess(this)

    /** A detached view of adapter-validated data, not an alternate input path. */
    internal fun validatedDraftSnapshot(): GroceryIntentDraft = legacy.draft.copy(
        items = frozen(legacy.draft.items.map { it.copy(required = frozen(it.required), excluded = frozen(it.excluded)) }),
    )

    /** Structural source facts are available even when semantic preparation is
     * held. This is not identity equivalence, coverage, quantity provenance or
     * permission to search/apply. Keep the complete original draft alongside it. */
    internal fun boundItemSources(): List<GroceryIntentReviewV2Quote> = itemSources

    internal fun sourceConflictIssues(): List<GroceryIntentContextIssue> = sourceConflicts

    private val itemSources: List<GroceryIntentReviewV2Quote> by lazy {
        frozen(legacy.draft.items.map { item ->
            requireNotNull(bindGroceryIntentProductSource(legacy.draft.instruction, item.sourceText, maxLength = 6000)) {
                "Context item source is not uniquely bound"
            }
        })
    }

    private val sourceConflicts: List<GroceryIntentContextIssue> by lazy {
        frozen(buildList {
            itemSources.forEachIndexed { index, source ->
                if (itemSources.withIndex().any { (otherIndex, other) -> otherIndex != index && source.overlaps(other) })
                    add(GroceryIntentContextIssue(GroceryIntentContextIssueKind.ITEM_SOURCE_OVERLAP, source))
            }
            proposals.forEach { role ->
                if (proposals.any { it.kind != role.kind && it.source.overlaps(role.source) } ||
                    role.kind == GroceryIntentContextKind.BACKGROUND && itemSources.any { it.overlaps(role.source) })
                    add(GroceryIntentContextIssue(GroceryIntentContextIssueKind.CONFLICTING_ROLE, role.source))
            }
        }.distinct())
    }

    override fun toString(): String = "GroceryIntentContextResponse(<redacted>)"

    companion object {
        fun parse(body: String, originalInstruction: String): GroceryIntentContextResponse {
            require(originalInstruction.isNotBlank() && originalInstruction.codePointCount(0, originalInstruction.length) <= 6000 &&
                originalInstruction.none { it.isISOControl() && it !in "\n\r\t" } &&
                originalInstruction.codePoints().noneMatch { it in 0xD800..0xDFFF }) { "Invalid context instruction" }
            val root = JsonParser(body).parseStrict().contextObject("response")
            val draft = root["draft"]?.contextObject("draft") ?: error("Missing context draft")
            require(draft.keys == setOf("contract_version", "requires_review", "instruction", "items",
                "model_needs_clarification", "context")) { "Unexpected context draft fields" }
            require((draft["contract_version"] as? JsonValue.Str)?.value == GROCERY_INTENT_CONTEXT_CONTRACT) {
                "Wrong context contract"
            }
            val entries = (draft["context"] as? JsonValue.Arr)?.items ?: error("Context must be an array")
            require(entries.size <= 50) { "Too many context entries" }
            val proposals = entries.map { entry ->
                val obj = entry.contextObject("context entry")
                require(obj.keys == setOf("kind", "source")) { "Unexpected context fields" }
                val kind = GroceryIntentContextKind.entries.singleOrNull {
                    it.wire == (obj["kind"] as? JsonValue.Str)?.value
                } ?: error("Invalid context kind")
                val source = obj.getValue("source").contextObject("context source")
                require(source.keys == setOf("text", "start_utf16", "end_utf16")) { "Unexpected context source fields" }
                val text = (source["text"] as? JsonValue.Str)?.value ?: error("Invalid context text")
                require(text.codePoints().noneMatch { it in 0xD800..0xDFFF }) { "Invalid context Unicode" }
                val bound = requireNotNull(bindGroceryIntentProductSource(originalInstruction, text, maxLength = 320)) {
                    "Context source is not uniquely bound"
                }
                require(source["start_utf16"].contextInt() == bound.startUtf16 &&
                    source["end_utf16"].contextInt() == bound.endUtf16) { "Context source offsets changed" }
                GroceryIntentContextProposal(kind, bound)
            }
            require(proposals.distinct().size == proposals.size) { "Duplicate context proposal" }
            // Only this explicit version adapter removes the additional field.
            // The old parser remains exact-key strict; no generic ignore-unknown mode.
            val legacyDraft = JsonValue.Obj(draft - "context" + ("contract_version" to JsonValue.Str("grocery-intent-v1")))
            val legacy = GroceryIntentResponse.parseValue(JsonValue.Obj(root + ("draft" to legacyDraft)), originalInstruction)
            require(legacy.draft.modelNeedsClarification == legacy.draft.items.any { it.needsClarification }) {
                "Context clarification summary changed"
            }
            return GroceryIntentContextResponse(legacy, Collections.unmodifiableList(proposals))
        }
    }
}

/** A recoverable semantic hold is not a malformed response or an executable
 * preparation. In particular Held contains no ParsedItem, quantity witness,
 * fulfillment permission or conversion API. Original rows are never filtered. */
internal sealed interface GroceryIntentContextAssessment {
    class Prepared(val preparation: GroceryIntentContextPreparation) : GroceryIntentContextAssessment {
        override fun toString(): String = "GroceryIntentContextAssessment.Prepared(<redacted>)"
    }

    class Held private constructor(
        val draft: GroceryIntentDraft,
        val itemSources: List<GroceryIntentReviewV2Quote>,
        /** Raw model proposals retained for diagnosis, NOT effective review roles.
         * Unlike Prepared, Held is non-executable and must never be rendered as
         * an accepted interpretation; blockingIssues describes the assessment. */
        val proposals: List<GroceryIntentContextProposal>,
        val draftHolds: List<GroceryIntentDraftHold>,
        val groundingIssues: List<GroceryIntentGroundingIssue>,
        val unevaluatedPropertyRows: Set<Int>,
        val unownedRanges: List<GroceryIntentCoverageSpan>,
        val blockingIssues: List<GroceryIntentContextIssue>,
    ) : GroceryIntentContextAssessment {
        // Conversion, source-evidence, pending-witness and catalogue checks are
        // deliberately not reached for ANY row, including apparently good rows.
        val unevaluatedItemRows: Set<Int> = Collections.unmodifiableSet(draft.items.indices.toSet())
        init { require(draftHolds.isNotEmpty() && blockingIssues.isNotEmpty()) }
        override fun toString(): String = "GroceryIntentContextAssessment.Held(<redacted>)"

        companion object {
            internal fun retain(
                response: GroceryIntentContextResponse, assessment: GroceryIntentDraftAssessment,
                unowned: List<GroceryIntentCoverageSpan>, issues: List<GroceryIntentContextIssue>,
            ): Held {
                require(assessment.preparation == null)
                return Held(response.validatedDraftSnapshot(), response.boundItemSources(), frozen(response.proposals),
                    frozen(assessment.holds.map { it.copy(affectedSourceText = frozen(it.affectedSourceText)) }),
                    frozen(assessment.grounding.issues),
                    Collections.unmodifiableSet(assessment.grounding.unevaluatedPropertyRows.toSet()),
                    frozen(unowned), frozen(issues))
            }
        }
    }
}

/** In-memory, pending interpretation; deliberately not accepted by existing
 * cart/discovery/apply APIs. The legacy preparation keeps every original hold.
 * A later preview/resolution integration must bind consent to this entire
 * instruction and interpretation, not merely to the proposed item list. */
internal class GroceryIntentContextPreparation private constructor(
    val instruction: String,
    val legacyPreparation: GroceryIntentPreparation,
    val itemSources: List<GroceryIntentReviewV2Quote>,
    /** Effective review roles; the response retains its unchanged raw proposals. */
    val proposals: List<GroceryIntentContextProposal>,
    val pendingCounts: Map<Int, GroceryIntentPendingCountWitness>,
    val unownedRanges: List<GroceryIntentCoverageSpan>,
    val blockingIssues: List<GroceryIntentContextIssue>,
    val fulfillmentConstraints: List<String>,
    val disputedProposals: Set<Int>,
) {
    override fun toString(): String = "GroceryIntentContextPreparation(<redacted>)"

    /** The exact quote stays owned; a click cannot turn a requirement into
     * background. Editing must create a newly validated whole interpretation. */
    fun disputeBackground(index: Int): GroceryIntentContextPreparation {
        require(proposals.getOrNull(index)?.kind == GroceryIntentContextKind.BACKGROUND)
        if (index in disputedProposals) return this
        return GroceryIntentContextPreparation(instruction, legacyPreparation, itemSources, proposals,
            pendingCounts, unownedRanges,
            frozen(blockingIssues + GroceryIntentContextIssue(GroceryIntentContextIssueKind.DISPUTED_BACKGROUND,
                source = proposals[index].source)), fulfillmentConstraints,
            Collections.unmodifiableSet(disputedProposals + index))
    }

    companion object {
        internal fun assess(response: GroceryIntentContextResponse, collectHolds: Boolean = true): GroceryIntentContextAssessment {
            // Response has a private constructor: only the strict negotiated
            // adapter can enter, never a manually constructed unchecked draft.
            val draft = response.validatedDraftSnapshot()
            val proposals = response.proposals
            // Source ownership is independent of lexical/arithmetic approval.
            // The same strict response owns these facts for future read-only
            // interpretation work; do not reconstruct a second parser here.
            val sources = response.boundItemSources()
            val structuralIssues = response.sourceConflictIssues()
            // Run the whole-list validators once. Never recompute grounding per
            // row, infer scope from warning strings or remove a legacy warning.
            val assessment = draft.assessForContextReview(collectHolds)
            val prepared = assessment.preparation?.let { value -> value.copy(
                items = frozen(value.items.map { it.detachedForSwiggyReview() }),
                unresolvedText = frozen(value.unresolvedText), excludedText = frozen(value.excludedText),
                sharedGroupNotes = frozen(value.sharedGroupNotes), fulfillmentConstraints = frozen(value.fulfillmentConstraints),
                itemReviewConcerns = frozen(value.itemReviewConcerns),
            ) }
            val roles = frozen(proposals)
            // Defend this internal factory too; manually constructed proposals
            // must not become authority through a source span from another input.
            require(roles.size <= 50 && roles.distinct().size == roles.size && roles.all {
                bindGroceryIntentProductSource(draft.instruction, it.source.text, maxLength = 320) == it.source
            }) { "Context source changed" }
            val pending = prepared?.items?.mapIndexedNotNull { index, item ->
                if (!item.requiresIntentQuantityProof || item.intentQuantityProvenance != null) null
                else GroceryIntentPendingCountWitness.compile(draft.instruction, index, item)?.let { index to it }
            }?.toMap().orEmpty()
            val covered = (sources + roles.map { it.source }).map { GroceryIntentCoverageSpan(it.startUtf16, it.endUtf16) }
            val neutral = neutralGroceryIntentCoverageSpans(draft.instruction, sources.map { it.text })
            // Coverage alone is not a semantic verdict. Only the existing
            // closed request grammar may override a model's context label.
            val closedRequests = neutral.filter { span ->
                val text = draft.instruction.substring(span.startUtf16, span.endUtf16)
                (sources.any { it.startUtf16 == span.endUtf16 } && groceryIntentClosedRequestShell(text)) ||
                    (sources.any { it.endUtf16 == span.startUtf16 } && groceryIntentClosedPurchaseSuffix(text))
            }
            val closedRequestRoles = roles.indices.filter { index ->
                val role = roles[index]
                role.kind != GroceryIntentContextKind.UNRESOLVED &&
                    structuralIssues.none { it.kind == GroceryIntentContextIssueKind.CONFLICTING_ROLE && it.source == role.source } &&
                    closedRequests.any { it.startUtf16 <= role.source.startUtf16 && it.endUtf16 >= role.source.endUtf16 }
            }.toSet()
            // Preserve the raw response; effective background keeps the exact
            // original quote visible in the existing combined-review UI.
            val interpretedRoles = frozen(roles.mapIndexed { index, role ->
                if (index in closedRequestRoles) role.copy(kind = GroceryIntentContextKind.BACKGROUND) else role
            })
            val unowned = frozen(uncoveredGroceryIntentRangesBySpan(draft.instruction, covered + neutral))
            val issues = mutableListOf<GroceryIntentContextIssue>()
            unowned.forEach { span -> issues += GroceryIntentContextIssue(GroceryIntentContextIssueKind.UNOWNED_SOURCE,
                source = GroceryIntentReviewV2Quote(draft.instruction.substring(span.startUtf16, span.endUtf16), span.startUtf16, span.endUtf16)) }
            val concerns = prepared?.itemReviewConcerns ?: (
                assessment.grounding.review.map { GroceryIntentItemReviewConcern(GroceryIntentItemReviewKind.IDENTITY_OR_PROPERTY, it) } +
                    assessment.quantityReviews.map { GroceryIntentItemReviewConcern(GroceryIntentItemReviewKind.QUANTITY_INTERPRETATION, it) })
            concerns.forEach { concern ->
                issues += GroceryIntentContextIssue(GroceryIntentContextIssueKind.ITEM_REVIEW, concern = concern)
            }
            assessment.holds.forEach { hold ->
                val kind = when (hold.check) {
                    GroceryIntentDraftCheck.COMPOSITE -> GroceryIntentContextIssueKind.ITEM_COMPOSITE
                    GroceryIntentDraftCheck.GROUNDING -> GroceryIntentContextIssueKind.ITEM_GROUNDING
                    GroceryIntentDraftCheck.NUMERIC_OWNERSHIP -> GroceryIntentContextIssueKind.ITEM_NUMERIC
                    GroceryIntentDraftCheck.MODEL_CLARIFICATION -> GroceryIntentContextIssueKind.ITEM_CLARIFICATION
                }
                hold.affectedSourceText.forEach { text ->
                    val source = sources.firstOrNull { it.text == text }
                        ?: requireNotNull(bindGroceryIntentProductSource(draft.instruction, text, maxLength = 6000))
                    issues += GroceryIntentContextIssue(kind, source)
                }
            }
            sources.forEachIndexed { index, source ->
                structuralIssues.filterTo(issues) {
                    it.kind == GroceryIntentContextIssueKind.ITEM_SOURCE_OVERLAP && it.source == source
                }
                val item = prepared?.items?.get(index)
                if (item?.unprovenIdentityScope == true || prepared == null && source.text in assessment.grounding.unprovenIdentityScope)
                    issues += GroceryIntentContextIssue(GroceryIntentContextIssueKind.ITEM_IDENTITY, source)
                if (item != null && item.requiresIntentQuantityProof && item.intentQuantityProvenance == null && index !in pending)
                    issues += GroceryIntentContextIssue(GroceryIntentContextIssueKind.ITEM_QUANTITY, source)
            }
            val fulfillment = mutableListOf<String>()
            // Only the existing independent grammar can classify a fulfillment
            // limitation. A model label alone cannot discard a product/condition.
            val knownFulfillment = deriveFulfillmentConstraints(draft.instruction, sources,
                allowListPurpose = true, allowTrailingFulfillment = true, allowPurposeGroups = true)
                .filter { it.kind == "fulfillment" }.map { it.source }.toSet()
            interpretedRoles.forEachIndexed { index, role ->
                structuralIssues.filterTo(issues) {
                    it.kind == GroceryIntentContextIssueKind.CONFLICTING_ROLE && it.source == role.source
                }
                when (role.kind) {
                    GroceryIntentContextKind.BACKGROUND -> if (index !in closedRequestRoles && contextBackgroundNeedsReview(role.source.text))
                        issues += GroceryIntentContextIssue(GroceryIntentContextIssueKind.ACTIONABLE_BACKGROUND, role.source)
                    GroceryIntentContextKind.UNRESOLVED -> issues += GroceryIntentContextIssue(
                        GroceryIntentContextIssueKind.UNRESOLVED_CONTEXT, role.source)
                    GroceryIntentContextKind.FULFILLMENT -> if (role.source in knownFulfillment &&
                        GroceryIntentConstraintCodec.isValidQuote(role.source.text)) fulfillment += role.source.text
                    else issues += GroceryIntentContextIssue(GroceryIntentContextIssueKind.UNSUPPORTED_FULFILLMENT, role.source)
                }
            }
            if (prepared == null) return GroceryIntentContextAssessment.Held.retain(response, assessment, unowned, issues.distinct())
            return GroceryIntentContextAssessment.Prepared(GroceryIntentContextPreparation(draft.instruction, prepared, sources, interpretedRoles,
                Collections.unmodifiableMap(pending), unowned, frozen(issues.distinct()), frozen(fulfillment.distinct()), emptySet()))
        }
    }
}

/** Conservative lexical signals, not proof of a requested or omitted item.
 * Relation words include ordinary coordination; nominal shape is not meaning.
 * Absence of signals still grants no authority to act on an interpretation. */
internal enum class GroceryIntentContextBackgroundSignal {
    NUMBER_OR_RELATION, PURCHASE_SCOPE, CORRECTION, UNCERTAIN_SCOPE, PROPERTY_OR_NEGATION,
    NOMINAL_PREDICATE, RETAIL_NOMINAL_SHAPE, HINDI_REQUEST_SHAPE, KNOWN_PRODUCT_TOKENS,
}

/** Lexical hold signals for diagnostics; these are not semantic proof. */
internal fun groceryIntentContextBackgroundSignals(text: String): Set<GroceryIntentContextBackgroundSignal> {
    return contextBackgroundSignalSequence(text).toSet()
}

/** Production checks the first signal only, preserving short-circuit predicate work. */
private fun contextBackgroundSignalSequence(text: String): Sequence<GroceryIntentContextBackgroundSignal> = sequence {
    val words = ProductLexicon.tokenize(text)
    if (sourceNominalHasAmountOrRelation(text)) yield(GroceryIntentContextBackgroundSignal.NUMBER_OR_RELATION)
    if (groceryIntentPurchaseScopeContextRanges(text).isNotEmpty()) yield(GroceryIntentContextBackgroundSignal.PURCHASE_SCOPE)
    if (intentV2HasCorrectionCue(text)) yield(GroceryIntentContextBackgroundSignal.CORRECTION)
    if (groceryIntentUncertainScope.containsMatchIn(text)) yield(GroceryIntentContextBackgroundSignal.UNCERTAIN_SCOPE)
    if (groceryIntentPropertiesIn(text).isNotEmpty() || propertyHasUnprovedNegation(text))
        yield(GroceryIntentContextBackgroundSignal.PROPERTY_OR_NEGATION)
    if (groceryIntentHasNominalClausePredicate(text)) yield(GroceryIntentContextBackgroundSignal.NOMINAL_PREDICATE)
    if (groceryIntentRetailNominalValid(text)) yield(GroceryIntentContextBackgroundSignal.RETAIL_NOMINAL_SHAPE)
    if (groceryIntentClosedHindiRequestRanges(text).isNotEmpty()) yield(GroceryIntentContextBackgroundSignal.HINDI_REQUEST_SHAPE)
    if (ProductLexicon.knownProductTokens.any { (_, tokens) -> tokens.isNotEmpty() && words.containsAll(tokens) })
        yield(GroceryIntentContextBackgroundSignal.KNOWN_PRODUCT_TOKENS)
}

private fun contextBackgroundNeedsReview(text: String): Boolean =
    contextBackgroundSignalSequence(text).any()

internal enum class GroceryIntentContextIssueKind {
    UNOWNED_SOURCE, ITEM_REVIEW, ITEM_SOURCE_OVERLAP, ITEM_IDENTITY, ITEM_QUANTITY,
    ITEM_COMPOSITE, ITEM_GROUNDING, ITEM_NUMERIC, ITEM_CLARIFICATION,
    CONFLICTING_ROLE, ACTIONABLE_BACKGROUND, UNRESOLVED_CONTEXT, UNSUPPORTED_FULFILLMENT, DISPUTED_BACKGROUND,
}
internal data class GroceryIntentContextIssue(
    val kind: GroceryIntentContextIssueKind,
    val source: GroceryIntentReviewV2Quote? = null,
    val concern: GroceryIntentItemReviewConcern? = null,
) {
    override fun toString(): String = "GroceryIntentContextIssue(kind=$kind, <redacted>)"
}

private fun <T> frozen(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))

private fun JsonValue.contextObject(label: String): Map<String, JsonValue> {
    val obj = this as? JsonValue.Obj ?: error("$label must be an object")
    require(obj.duplicateKeys.isEmpty()) { "Duplicate context object fields" }
    return obj.members
}

private fun JsonValue?.contextInt(): Int =
    (this as? JsonValue.Num)?.raw?.toIntOrNull() ?: error("Context offset must be an integer")
