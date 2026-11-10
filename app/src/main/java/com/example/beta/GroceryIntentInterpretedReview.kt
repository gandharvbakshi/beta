package com.example.beta

import com.example.beta.automation.IntentLocalAmount
import com.example.beta.automation.IntentQuantityOrigin
import com.example.beta.automation.IntentQuantityProvenance
import com.example.beta.automation.ParsedItem
import com.example.beta.automation.Quantity
import java.math.BigDecimal
import java.util.Collections

/** OFF-default transport. Semantic observations never impersonate source or
 * seller receipts. Only the explicitly enabled interpreted contract selects it. */
internal class GroceryIntentInterpretedReview private constructor(
    private val instruction: String,
    private val fingerprint: String,
    private val wireSnapshot: String,
    private val rows: List<InterpretedRow>,
    private val background: List<GroceryIntentReviewV2Quote>,
    internal val holdReasons: Set<GroceryIntentInterpretedHoldReason>,
) {
    override fun toString() = "GroceryIntentInterpretedReview(<redacted>)"

    fun prepareForReview(): GroceryIntentPreparation {
        // An unsupported row/scope holds the entire original, not the surviving
        // items. The ordinary acknowledgement checkbox cannot waive it.
        if (holdReasons.isNotEmpty() || rows.isEmpty()) throw IntentNeedsReviewException(listOf(instruction), localEditOnly = true)
        val basket = AuditedIntentBasket(instruction, fingerprint, rows.size, wireSnapshot, background)
        val items = rows.map { row ->
            val identity = listOf(row.item.name)
            val form = row.packaging?.takeUnless { it in setOf("pack", "packet") }?.replace('_', ' ')
                ?.takeUnless { intentEvidenceTokens(identity.joinToString(" ")).containsAll(intentEvidenceTokens(it)) }
            val query = (identity + listOfNotNull(form) +
                listOfNotNull(row.pack?.let { normalizePack(it.value, it.unit) }
                    ?.let { "${it.first} ${it.second}" })).distinct().joinToString(" ")
            val exclusions = frozen(row.predicates.map { it.literalExclusion() }.distinct())
            val base = ParsedItem(
                rawText = row.item.sourceText, query = query, strictMatchPhrase = query,
                quantity = interpretedQuantity(row.item.quantity, row.item.quantityUnit),
                avoidPhrases = exclusions, requiresIntentQuantityProof = true,
                retailPackCount = row.role == "retail_units", logicalPieceCount = row.role == "requested_pieces",
            )
            AuditedIntentProvenance.create(basket, row.index, base, row.role, row.packaging,
                requireNotNull(row.merchandise), row.quantity, row.pack, row.predicates, row.sellerRequirement, row.bareHighPieceIdentity,
                row.distribution?.genericOuter == true).project()
        }
        return GroceryIntentPreparation(frozen(items), emptyList(), auditedBasket = basket)
    }

    companion object {
        fun parse(body: String, originalInstruction: String): GroceryIntentInterpretedReview {
            require(body.length <= 600_000)
            return parseValue(JsonParser(body).parseStrict(), originalInstruction, body)
        }

        internal fun parseValue(value: JsonValue, originalInstruction: String, snapshot: String): GroceryIntentInterpretedReview {
            require(snapshot.length <= 700_000)
            require(originalInstruction.isNotBlank() && originalInstruction.codePointCount(0, originalInstruction.length) <= 6000 &&
                originalInstruction.none { it.isISOControl() && it !in "\n\r\t" } &&
                originalInstruction.codePoints().noneMatch { it in 0xD800..0xDFFF })
            val root = value.objectWith(
                "contract_version", "requires_review", "instruction", "source_item_count", "claims", "requirements", "rows", "blocked_rows", "context", "audit")
            require(root.string("contract_version") == "grocery-intent-interpreted-v2" &&
                root["requires_review"] == JsonValue.Bool(true) && root.string("instruction") == originalInstruction)
            val count = root.getValue("source_item_count").integer().also { require(it in 0..50) }
            val claims = root.array("claims", 50).also { require(it.size == count) }
            val requirementClaims = root.array("requirements", 50).also { require(it.size == count) }.map { row ->
                row.array(16).map { value ->
                    val obj = value.objectWith("source_text", "kind", "ingredient_predicates")
                    val text = obj.string("source_text").also { require(it.isNotBlank() && it.codePointCount(0, it.length) <= 320) }
                    LiteralRequirementClaim(text, requirementMeaning(obj))
                }.also {
                    require(it.map { entry -> entry.text }.distinct().size == it.size &&
                        it.flatMap { entry -> entry.meaning.predicates }.distinct().size <= 16)
                }
            }
            require(requirementClaims.sumOf { it.size } <= 64 &&
                requirementClaims.sumOf { row -> row.sumOf { it.meaning.predicates.size } } <= 64 &&
                requirementClaims.sumOf { row -> row.sumOf { it.text.toByteArray(Charsets.UTF_8).size } } <= 48_000)
            val blocked = root.array("blocked_rows", 50).map {
                GroceryIntentReviewV2BlockedRow.parse(it.strictObject(), originalInstruction)
            }
            val contexts = root.array("context", 50).map { value ->
                val obj = value.objectWith("kind", "source")
                val kind = GroceryIntentContextKind.entries.singleOrNull { it.wire == obj.string("kind") }
                    ?: error("Invalid interpreted context kind")
                GroceryIntentContextProposal(kind, quote(obj.getValue("source"), originalInstruction))
            }
            require(contexts.distinct().size == contexts.size)
            val audit = root.getValue("audit").objectWith("version", "request_fingerprint", "rows", "source")
            require(audit.string("version") == "value-audit-v3")
            val fingerprint = audit.string("request_fingerprint").also { require(Regex("[0-9a-f]{64}").matches(it)) }
            val auditRows = audit.array("rows", 50).map { value ->
                val obj = value.objectWith("row_index", "issues", "quantity_role", "package_size_role", "packaging", "requirements")
                val index = obj.getValue("row_index").integer()
                val issues = obj.array("issues", 10).map { it.string() }
                require(issues.distinct().size == issues.size && issues.all { it in auditIssues })
                val role = obj.string("quantity_role").also { require(it in quantityRoles) }
                val packageRole = obj.string("package_size_role").also { require(it in packageSizeRoles) }
                val packaging = obj.getValue("packaging").nullableString().also { require(it == null || it in packagingForms) }
                val requirements = obj.array("requirements", 16).map {
                    val entry = it.objectWith("id", "kind", "ingredient_predicates")
                    entry.string("id") to requirementMeaning(entry)
                }
                require(requirements.map { it.first }.distinct().size == requirements.size)
                RowAudit(index, frozen(issues), role, packageRole, packaging, requirements.toMap())
            }
            require(auditRows.map { it.index }.sorted() == (0 until count).toList())
            val audits = auditRows.associateBy { it.index }
            val holdReasons = linkedSetOf<GroceryIntentInterpretedHoldReason>()
            blocked.forEach { holdReasons += GroceryIntentInterpretedHoldReason(GroceryIntentInterpretedHoldCode.BLOCKED_ROW, it.rowIndex) }
            auditRows.forEach { row ->
                if (row.issues.isNotEmpty()) holdReasons += GroceryIntentInterpretedHoldReason(
                    GroceryIntentInterpretedHoldCode.AUDIT_ISSUE, row.index)
                if (row.role == "unresolved") holdReasons += GroceryIntentInterpretedHoldReason(
                    GroceryIntentInterpretedHoldCode.UNRESOLVED_QUANTITY_ROLE, row.index)
            }
            val sources = audit.array("source", 301).map { value ->
                val obj = value.objectWith("source", "role", "row_indices")
                val source = quote(obj.getValue("source"), originalInstruction, 6000)
                val role = obj.string("role").also { require(it in setOf("item", "background", "unresolved")) }
                val owners = obj.array("row_indices", 50).map { it.integer() }
                require(owners.distinct().size == owners.size && owners.all { it in 0 until count } &&
                    (role == "item") == owners.isNotEmpty())
                SourceAudit(source, role, owners)
            }
            if (sources.any { it.role == "unresolved" }) holdReasons += GroceryIntentInterpretedHoldReason(
                GroceryIntentInterpretedHoldCode.UNRESOLVED_SOURCE)
            if (contexts.any { it.kind != GroceryIntentContextKind.BACKGROUND }) holdReasons += GroceryIntentInterpretedHoldReason(
                GroceryIntentInterpretedHoldCode.UNSUPPORTED_CONTEXT)
            val rows = root.array("rows", 50).map { value ->
                val obj = value.objectWith("row_index", "item", "evidence", "quantity_atom", "pack_atom", "unassigned_numeric", "requirements")
                val index = obj.getValue("row_index").integer().also { require(it in 0 until count) }
                val item = GroceryIntentReviewV2Item.parse(obj.getValue("item").strictObject(), originalInstruction)
                require(item.packaging == null && item.supportingText.isEmpty() && item.required.isEmpty() && item.excluded.isEmpty())
                val evidence = GroceryIntentReviewV2Evidence.parse(obj.getValue("evidence").strictObject(), originalInstruction, item)
                require(uniqueExactQuoteInInstruction(originalInstruction, evidence.primary))
                requireClaimProjection(claims[index], item)
                val amounts = GroceryIntentInterpretedAmounts(evidence.primary.text)
                val quantity = amount(obj.getValue("quantity_atom"), originalInstruction, evidence.primary,
                    item.quantity, item.quantityUnit, item.quantityText, amounts)
                val pack = amount(obj.getValue("pack_atom"), originalInstruction, evidence.primary,
                    item.packValue, item.packUnit, item.packText, amounts)
                require(quantity == null || pack == null || quantity.endUtf16 <= pack.startUtf16 || pack.endUtf16 <= quantity.startUtf16)
                val unassigned = obj.array("unassigned_numeric", 320).map { quote(it, originalInstruction, 64) }
                require(unassigned.all { it.startUtf16 >= evidence.primary.startUtf16 && it.endUtf16 <= evidence.primary.endUtf16 })
                val rowAudit = requireNotNull(audits[index])
                val requirements = obj.array("requirements", 16).map { value ->
                    val entry = value.objectWith("id", "source", "kind", "ingredient_predicates")
                    val source = quote(entry.getValue("source"), originalInstruction)
                    val id = entry.string("id")
                    require(id == "r${source.startUtf16}:${source.endUtf16}" &&
                        source.startUtf16 >= evidence.primary.startUtf16 && source.endUtf16 <= evidence.primary.endUtf16)
                    val local = source.copy(startUtf16 = source.startUtf16 - evidence.primary.startUtf16,
                        endUtf16 = source.endUtf16 - evidence.primary.startUtf16)
                    require(uniqueExactQuoteInInstruction(evidence.primary.text, local))
                    LiteralRequirement(id, source, requirementMeaning(entry))
                }
                require(requirements.map { it.id }.distinct().size == requirements.size &&
                    requirements.none { a -> requirements.any { b -> a !== b && a.source.overlaps(b.source) } })
                require(requirements.map { LiteralRequirementClaim(it.source.text, it.meaning) } == requirementClaims[index])
                require(rowAudit.requirements.keys == requirements.map { it.id }.toSet())
                // Literal alarms can only add a hold. They cannot recover meaning
                // from the source or authorize a claim; both the whole row and
                // each exact requirement must retain any recognized typed claim.
                val rowAlarms = swiggyIngredientClaimAlarms(item.sourceText, includeProcessing = false)
                val requirementAlarms = requirements.associateWith { swiggyIngredientClaimAlarms(it.source.text, includeProcessing = false) }
                val suppliedPredicates = requirements.flatMap { it.meaning.predicates }.toSet()
                if (!processingLabelsAccountedFor(evidence.primary, requirements, item.name) || hasRestrictionOperator(item.sourceText) ||
                    anySourceGap(item.sourceText, evidence.primary.startUtf16, requirements.map { it.source },
                        ::propertyHasUnprovedNegation) ||
                    rowAlarms.any { it !in suppliedPredicates } ||
                    requirementAlarms.any { (requirement, alarms) ->
                        // A loss-prevention alarm isn't positive proof of its
                        // stronger typed meaning, even when both models agree.
                        alarms.any { it !in requirement.meaning.predicates ||
                            !swiggyHasIngredientPredicate(requirement.source.text, it) }
                    }) {
                    holdReasons += GroceryIntentInterpretedHoldReason(
                        GroceryIntentInterpretedHoldCode.UNSUPPORTED_PROPERTY, index)
                }
                val distribution = if (rowAudit.issues.isEmpty() && rowAudit.role == "retail_units" &&
                    rowAudit.packageRole in setOf("per_unit_mass", "per_unit_volume") && unassigned.size == 1)
                    sourceDistributiveSize(item.sourceText, item.name, rowAudit.packaging, quantity, pack)?.takeIf {
                        val reported = unassigned.single()
                        reported.startUtf16 == evidence.primary.startUtf16 + it.markerNumber.first &&
                            reported.endUtf16 == evidence.primary.startUtf16 + it.markerNumber.last + 1 &&
                            requirements.none { req -> req.source.startUtf16 <= evidence.primary.startUtf16 + it.marker.last &&
                                req.source.endUtf16 > evidence.primary.startUtf16 + it.marker.first }
                    } else null
                if (unassigned.isNotEmpty() && distribution == null) holdReasons += GroceryIntentInterpretedHoldReason(
                    GroceryIntentInterpretedHoldCode.UNASSIGNED_NUMERIC, index)
                if (item.needsClarification) holdReasons += GroceryIntentInterpretedHoldReason(
                    GroceryIntentInterpretedHoldCode.ROW_CLARIFICATION, index)
                val sellerSpans = requirements.filter { it.meaning.kind == "seller" }.map { it.source }
                val sellerRequirement = if (sellerSpans.isEmpty()) null else
                    requireNotNull(SellerPropertyRequirement.fromSource(item.name, evidence.primary, sellerSpans))
                val trailingSize = if (rowAudit.issues.isEmpty() && distribution == null)
                    terminalNamedRetailSizeRole(originalInstruction, evidence.primary, item.name, rowAudit.role,
                        rowAudit.packaging, quantity, pack) else null
                if (rowAudit.issues.isEmpty()) {
                    requireRole(item, rowAudit.role, rowAudit.packageRole)
                    if (trailingSize == false || (trailingSize == null &&
                        !ScopedInterpretedCardinal.roleProven(item.sourceText, item.name, rowAudit.role, rowAudit.packaging, quantity, pack,
                            seller = sellerRequirement)))
                        holdReasons += GroceryIntentInterpretedHoldReason(GroceryIntentInterpretedHoldCode.UNRESOLVED_QUANTITY_ROLE, index)
                    // Compare independently bound source mentions, not SKU names
                    // or model labels. Never turn a named outer into inner pieces.
                    if (quantity != null && item.quantityUnit in setOf("count", "pack", "piece") &&
                        pack == null && item.sourceText.contains("of", ignoreCase = true)) {
                        val named = sourceNamedRetailCountReceipt(item.sourceText)
                        if (named != null && named.packaging !in setOf("pack", "packet") && named.qualifier == null &&
                            named.size == null && named.count.startUtf16 == quantity.startUtf16 && named.count.endUtf16 == quantity.endUtf16 &&
                            (rowAudit.role != "retail_units" || rowAudit.packaging != named.packaging))
                            holdReasons += GroceryIntentInterpretedHoldReason(GroceryIntentInterpretedHoldCode.UNRESOLVED_QUANTITY_ROLE, index)
                    }
                    require(requirements.all { it.meaning.kind != "unsupported" &&
                        it.meaning == rowAudit.requirements.getValue(it.id) })
                }
                // Independent amount-coverage check, not a new language grammar:
                // unassigned numeric content stays held rather than disappearing.
                val remainder = item.sourceText.toCharArray()
                listOfNotNull(quantity, pack).forEach { bound ->
                    for (position in bound.startUtf16 until bound.endUtf16) remainder[position] = ' '
                }
                distribution?.markerNumber?.forEach { remainder[it] = ' ' }
                val unbound = String(remainder)
                if (groceryIntentContainsNumericAtom(unbound) || ScopedInterpretedCardinal.hasUnboundWord(unbound)) holdReasons += GroceryIntentInterpretedHoldReason(
                    GroceryIntentInterpretedHoldCode.UNASSIGNED_NUMERIC, index)
                if (requirements.any { it.meaning.kind == "unsupported" }) holdReasons += GroceryIntentInterpretedHoldReason(
                    GroceryIntentInterpretedHoldCode.UNSUPPORTED_PROPERTY, index)
                val predicates = requirements.flatMap { it.meaning.predicates }.distinct()
                val bodyEnd = affirmativeListEndStart(originalInstruction)?.takeIf {
                    evidence.primary.endUtf16 == originalInstruction.length && it > evidence.primary.startUtf16
                }?.minus(evidence.primary.startUtf16) ?: evidence.primary.text.length
                val identityObligations = requirements.mapNotNull { req ->
                    SourceMerchandiseProperty.fromSource(evidence.primary, req.source, req.meaning.kind, req.meaning.predicates, sellerRequirement)
                }
                val merchandise = SourceMerchandise.compile(originalInstruction, evidence.primary, bodyEnd, item.name,
                    rowAudit.role, rowAudit.packaging, quantity, pack, amounts, identityObligations, requirements.map { it.source },
                    sellerRequirement)
                InterpretedRow(index, item, evidence.primary, rowAudit.role,
                    rowAudit.packaging.takeUnless { distribution?.genericOuter == true }, quantity, pack,
                    frozen(predicates), sellerRequirement, distribution = distribution, trailingSizeProven = trailingSize == true,
                    merchandise = merchandise)
            }
            require((rows.map { it.index } + blocked.map { it.rowIndex }).sorted() == (0 until count).toList())
            require(rows.none { row -> rows.any { it.index != row.index && it.primary.overlaps(row.primary) } })
            require(blocked.all { "binding" in requireNotNull(audits[it.rowIndex]).issues && audits.getValue(it.rowIndex).requirements.isEmpty() })
            // Reconstruct the host's ENTIRE-original partition. A plausible row
            // count or fingerprint alone cannot hide an omitted source segment.
            val boundaries = (listOf(0, originalInstruction.length) +
                (rows.map { it.primary } + blocked.mapNotNull { it.source } + contexts.map { it.source })
                    .flatMap { listOf(it.startUtf16, it.endUtf16) }).distinct().sorted()
            val expected = boundaries.zipWithNext().mapNotNull { (start, end) ->
                originalInstruction.substring(start, end).takeIf { it.isNotBlank() }
                    ?.let { GroceryIntentReviewV2Quote(it, start, end) }
            }
            require(sources.map { it.source }.sortedBy { it.startUtf16 } == expected)
            for (row in rows.filter { audits.getValue(it.index).issues.isEmpty() }) {
                require(sources.all { (row.index in it.owners) == it.source.overlaps(row.primary) })
            }
            // Background may be proposed by the producer or found by the audit,
            // but it cannot erase any row or an explicitly unresolved obligation.
            require(contexts.filter { it.kind == GroceryIntentContextKind.BACKGROUND }.none { context ->
                rows.any { it.primary.overlaps(context.source) }
            })
            // Audit atoms can split a word or phrase. Inspect the original contiguous
            // gaps between bound rows, not model-chosen fragments or concatenated rows.
            // A producer chooses the row boundaries. It cannot move an order-level
            // condition, edit or deadline into a product name (or a background
            // span) to make it look enforced. This can only hold, never authorize.
            if (holdReasons.isEmpty() && (groceryIntentExplicitOrderEdit.containsMatchIn(originalInstruction) ||
                    orderScopeOperator.containsMatchIn(originalInstruction) ||
                    anySourceGap(originalInstruction, 0, rows.map { it.primary }, ::detachedConstraintAlarm)))
                holdReasons += GroceryIntentInterpretedHoldReason(GroceryIntentInterpretedHoldCode.UNSUPPORTED_CONTEXT)
            val inSourceOrder = rows.sortedBy { it.primary.startUtf16 }
            val affirmativeEdge = affirmativeListEndStart(originalInstruction)
            val primaries = inSourceOrder.map { it.primary }
            val ownedScope by lazy { groceryIntentCoordinatedItemContextProven(originalInstruction, primaries) }
            val countedScope by lazy {
                ownedScope || affirmativeEdge == null && (detachedShoppingDiscourse(originalInstruction, primaries) ||
                    markedInitialItemScope(originalInstruction, inSourceOrder, primaries))
            }
            if (holdReasons.isEmpty()) affirmativeEdge?.let { edge ->
                val last = inSourceOrder.lastOrNull()
                if (last == null || !last.completeFinalRequest(originalInstruction, edge) || !ownedScope)
                    holdReasons += GroceryIntentInterpretedHoldReason(GroceryIntentInterpretedHoldCode.UNSUPPORTED_CONTEXT)
            }
            inSourceOrder.forEachIndexed { index, row ->
                val next = inSourceOrder.getOrNull(index + 1)?.primary?.startUtf16
                val previous = inSourceOrder.getOrNull(index - 1)?.primary?.endUtf16 ?: 0
                val body = if (next == null && affirmativeEdge != null &&
                    affirmativeEdge in row.primary.startUtf16 until row.primary.endUtf16 &&
                    row.primary.endUtf16 == originalInstruction.length)
                    originalInstruction.substring(row.primary.startUtf16, affirmativeEdge) else row.primary.text
                if (!countedScope ||
                    !row.discreteUnitAccountedFor(originalInstruction, previous, next, body))
                    holdReasons += GroceryIntentInterpretedHoldReason(GroceryIntentInterpretedHoldCode.UNSUPPORTED_CONTEXT, row.index)
            }
            val scopedRows = rows.filter { row -> row.quantity?.atom?.let(ScopedInterpretedCardinal::value) != null }
            if (holdReasons.isEmpty() && scopedRows.isNotEmpty()) {
                val contextOwned = groceryIntentCoordinatedItemContextProven(originalInstruction, rows.map { it.primary }.sortedBy { it.startUtf16 })
                scopedRows.forEach { row ->
                    if (!contextOwned || !groceryIntentSourceClauseContextProven(originalInstruction, row.primary.text, row.primary.text.length))
                        holdReasons += GroceryIntentInterpretedHoldReason(GroceryIntentInterpretedHoldCode.UNSUPPORTED_CONTEXT, row.index)
                }
            }
            if (holdReasons.isEmpty() && inSourceOrder.any { it.distribution != null || it.trailingSizeProven }) {
                val contextOwned = groceryIntentCoordinatedItemContextProven(originalInstruction, inSourceOrder.map { it.primary })
                inSourceOrder.forEachIndexed { index, row ->
                    if (row.distribution == null && !row.trailingSizeProven) return@forEachIndexed
                    val previous = inSourceOrder.getOrNull(index - 1)?.primary
                    // "Two jars of A and two jars of B, each one 500 g" does
                    // not assign the shared size exclusively to the last row.
                    val joinsPrevious = previous != null && previous.text.trimEnd().last() !in ".!;।" &&
                        originalInstruction.substring(previous.endUtf16, row.primary.startUtf16).none { c -> c in ".!;।\r\n" }
                    if (!contextOwned || joinsPrevious || !groceryIntentSourceClauseEndProven(originalInstruction, row.primary.text,
                            row.distribution?.terminalEnd ?: row.primary.text.length))
                        holdReasons += GroceryIntentInterpretedHoldReason(GroceryIntentInterpretedHoldCode.UNSUPPORTED_CONTEXT, row.index)
                }
            }
            if (rows.isEmpty()) holdReasons += GroceryIntentInterpretedHoldReason(GroceryIntentInterpretedHoldCode.EMPTY_ROWS)
            if (holdReasons.isEmpty()) rows.filter { it.merchandise == null }.forEach {
                holdReasons += GroceryIntentInterpretedHoldReason(GroceryIntentInterpretedHoldCode.UNPROVEN_MERCHANDISE, it.index)
            }
            val ordered = rows.sortedBy { it.index }
            val ready = if (holdReasons.isEmpty() && ordered.any { it.highPieceCount() }) {
                val primaries = ordered.map { it.primary }
                require(groceryIntentCoordinatedItemContextProven(originalInstruction, primaries)) { "Unproven high-piece basket scope" }
                ordered.map { row ->
                    if (!row.highPieceCount()) row else {
                        require(row.role == "requested_pieces" && row.packaging == null && row.pack == null)
                        val receipt = requireNotNull(sourcePiecePurchaseReceipt(row.primary.text, bareIdentity = row.item.name))
                        val bound = requireNotNull(row.quantity)
                        require(receipt.amount.source == bound.source && receipt.amount.atom == bound.atom &&
                            receipt.amount.startUtf16 == bound.startUtf16 && receipt.amount.endUtf16 == bound.endUtf16 &&
                            receipt.amount.value.compareTo(bound.value) == 0 && sourcePieceInstructionIssue(originalInstruction, receipt) == null)
                        row.copy(bareHighPieceIdentity = row.primary.text.substring(receipt.identity)
                            .takeIf { receipt.form == SourcePieceForm.BARE_PREFIX })
                    }
                }
            } else ordered
            return GroceryIntentInterpretedReview(originalInstruction, fingerprint, snapshot, frozen(ready),
                frozen(sources.filter { it.role == "background" }.map { it.source }),
                Collections.unmodifiableSet(LinkedHashSet(holdReasons)))
        }
    }
}

internal enum class GroceryIntentInterpretedHoldCode(val wireValue: String) {
    BLOCKED_ROW("blocked_row"),
    AUDIT_ISSUE("audit_issue"),
    UNRESOLVED_QUANTITY_ROLE("unresolved_quantity_role"),
    UNRESOLVED_SOURCE("unresolved_source"),
    UNSUPPORTED_CONTEXT("unsupported_context"),
    UNASSIGNED_NUMERIC("unassigned_numeric"),
    ROW_CLARIFICATION("row_clarification"),
    MISSING_DIET_PREDICATE("missing_diet_predicate"),
    UNSUPPORTED_PROPERTY("unsupported_property"),
    UNPROVEN_MERCHANDISE("unproven_merchandise"),
    EMPTY_ROWS("empty_rows"),
}

internal data class GroceryIntentInterpretedHoldReason(
    val code: GroceryIntentInterpretedHoldCode,
    val rowIndex: Int? = null,
)

internal class AuditedIntentBasket(val instruction: String, val fingerprint: String, val rowCount: Int,
    private val wireSnapshot: String, background: List<GroceryIntentReviewV2Quote>) {
    internal val backgroundProposals: List<GroceryIntentContextProposal> = frozen(background.map {
        GroceryIntentContextProposal(GroceryIntentContextKind.BACKGROUND, it)
    })
    internal fun matches(items: List<ParsedItem>, original: String? = null): Boolean =
        items.size == rowCount && rowCount > 0 && (original == null || original == instruction) &&
            items.withIndex().all { (index, item) -> item.auditedIntent()?.let {
                it.rowIndex == index && it.belongsTo(this)
            } == true }
    override fun toString() = "AuditedIntentBasket(<redacted>)"
}

/** Only the checked projection can construct this immutable member. Copies of
 * editable ParsedItem cannot change the audit, quantities, diet, or basket. */
class AuditedIntentProvenance private constructor(
    private val basket: AuditedIntentBasket,
    internal val rowIndex: Int,
    private val expected: ParsedItem,
    internal val role: String,
    internal val packaging: String?,
    internal val merchandise: SourceMerchandise,
    private val quantityAtom: IntentLocalAmount?,
    internal val packAtom: IntentLocalAmount?,
    private val ingredients: List<IngredientPredicate>,
    private val sellerPropertyRequirement: SellerPropertyRequirement?,
    internal val bareHighPieceIdentity: String?,
    internal val sourceGenericOuter: Boolean,
) {
    init { require(merchandise.belongsTo(expected.rawText, ingredients, sellerPropertyRequirement)) }
    internal fun project(): ParsedItem = expected.copy(intentQuantityProvenance = proof())
    internal fun matches(item: ParsedItem): Boolean =
        item.copy(intentQuantityProvenance = null, intentQuantityCatalogueCheck = null) == expected && item.intentQuantityProvenance == proof()
    internal fun amountAtoms(): List<IntentLocalAmount> = listOfNotNull(quantityAtom, packAtom)
    internal fun predicates(): List<IngredientPredicate> = ingredients
    internal fun sellerProperties(): SellerPropertyRequirement? = sellerPropertyRequirement
    internal fun belongsTo(other: AuditedIntentProvenance): Boolean = basket === other.basket
    internal fun belongsTo(expected: AuditedIntentBasket): Boolean = basket === expected
    internal fun bindsInstruction(instruction: String) = basket.instruction == instruction
    internal fun rowCount() = basket.rowCount
    private fun proof() = IntentQuantityProvenance(
        origin = IntentQuantityOrigin.INTERPRETED, primaryText = expected.rawText, rawText = expected.rawText,
        resolvedQuantity = expected.quantity, local = null, query = expected.query, strictMatchPhrase = expected.strictMatchPhrase,
        retailPackCount = expected.retailPackCount, logicalPieceCount = expected.logicalPieceCount,
        opaqueNumericLabels = Collections.unmodifiableSet(groceryIntentOpaqueNumericLabels(expected.query).toSet()), audited = this,
    )
    override fun toString() = "AuditedIntentProvenance(<redacted>)"
    companion object {
        internal fun create(basket: AuditedIntentBasket, rowIndex: Int, expected: ParsedItem, role: String,
            packaging: String?, merchandise: SourceMerchandise, quantity: IntentLocalAmount?, pack: IntentLocalAmount?, ingredients: List<IngredientPredicate>,
            sellerProperties: SellerPropertyRequirement?, bareHighPieceIdentity: String?, sourceGenericOuter: Boolean = false) =
            AuditedIntentProvenance(basket, rowIndex, expected.copy(avoidPhrases = frozen(expected.avoidPhrases)),
                role, packaging, merchandise, quantity, pack, frozen(ingredients), sellerProperties, bareHighPieceIdentity, sourceGenericOuter)
    }
}

internal fun ParsedItem.hasAuditedIntent(): Boolean = intentQuantityProvenance?.let {
    it.origin == IntentQuantityOrigin.INTERPRETED || it.audited != null
} == true

internal fun ParsedItem.auditedIntent(): AuditedIntentProvenance? = intentQuantityProvenance?.audited?.takeIf { it.matches(this) }

/** Invalid audited evidence is a hard failure, never an empty requirement. */
internal fun validatedSellerPropertyRequirement(item: ParsedItem): SellerPropertyRequirement? {
    if (!item.hasAuditedIntent()) return null
    val proof = item.auditedIntent() ?: throw IllegalArgumentException("Seller-property provenance changed")
    return proof.sellerProperties()
}

internal fun auditedIntentBasketMatches(items: List<ParsedItem>, instruction: String? = null, expected: AuditedIntentBasket? = null): Boolean {
    if (expected != null) return expected.matches(items, instruction)
    if (items.none { it.hasAuditedIntent() }) return true
    val proofs = items.map { it.auditedIntent() ?: return false }
    val first = proofs.first()
    return items.size == first.rowCount() && proofs.withIndex().all { (index, proof) ->
        proof.rowIndex == index && proof.belongsTo(first) && (instruction == null || proof.bindsInstruction(instruction))
    }
}

private data class InterpretedRow(val index: Int, val item: GroceryIntentReviewV2Item, val primary: GroceryIntentReviewV2Quote,
    val role: String, val packaging: String?, val quantity: IntentLocalAmount?, val pack: IntentLocalAmount?,
    val predicates: List<IngredientPredicate>, val sellerRequirement: SellerPropertyRequirement?, val bareHighPieceIdentity: String? = null,
    val distribution: SourceDistributiveSize? = null, val trailingSizeProven: Boolean = false, val merchandise: SourceMerchandise? = null)

private fun markedInitialItemScope(original: String, ordered: List<InterpretedRow>, primaries: List<GroceryIntentReviewV2Quote>): Boolean {
    val first = ordered.firstOrNull() ?: return false
    if (first.merchandise == null || first.pack != null || first.packaging != null || first.role !in setOf("unstated", "physical_total")) return false
    val edge = groceryIntentMarkedRequestEnd(original) ?: return false
    val quote = first.primary
    if (quote.startUtf16 !in 1..edge || quote.endUtf16 <= edge || original[quote.startUtf16 - 1] != ' ' ||
        original[quote.startUtf16] == ' ') return false
    // The newly covered prefix is strictly part of the originally verified command, never a sibling
    // or a condition. Recheck every subsequent gap on the original, preserving all item quote offsets.
    val framed = GroceryIntentReviewV2Quote(original.substring(0, quote.endUtf16), 0, quote.endUtf16)
    return groceryIntentCoordinatedItemContextProven(original, listOf(framed) + primaries.drop(1))
}
private fun InterpretedRow.highPieceCount() = item.quantityUnit == "piece" && (item.quantity ?: BigDecimal.ZERO) > BigDecimal(20)

/** Identical purchase authority if a model attached the speech ending to its
 * last quote or left it in background. Never rewrite the wire or its offsets. */
private fun InterpretedRow.completeFinalRequest(instruction: String, edge: Int): Boolean {
    if (edge <= primary.startUtf16 || primary.endUtf16 !in setOf(edge, instruction.length)) return false
    val body = instruction.substring(primary.startUtf16, edge)
    return merchandise != null && affirmativeRequestMember(body)
}

// A merchant could sell two things, but that doesn't make the customer's
// "two <unit>" a request for pieces. Check the unit's position, not a
// dictionary of every unsupported dimension in every language.
private fun InterpretedRow.discreteUnitAccountedFor(original: String, previous: Int, next: Int?, body: String): Boolean {
    val n = quantity ?: return true
    fun clean(s: String) = s.trim { it.isWhitespace() || it in ".,;!?।" }
    fun phrase(s: String, name: String) = s.equals(name, true)
    fun endsIn(s: String, name: String) = phrase(s, name) ||
        (s.length > name.length && s.endsWith(name, true) && s[s.length - name.length - 1].isWhitespace())
    fun startsWithName(s: String, name: String) = s.length > name.length &&
        s.startsWith(name, true) && s[name.length].isWhitespace()
    fun unitFits(marker: String): Boolean {
        val form = groceryIntentRetailContainerKey(marker) ?: ScopedInterpretedCardinal.sourceNativeForm(marker,n.atom)
        return when {
            form != null -> role == "retail_units" && (packaging == form ||
                (form == "packet" && packaging == "pack") ||
                (form == "pack" && packaging == null && pack != null))
            pieceUnit.matches(marker) -> role == "requested_pieces"
            else -> false
        }
    }
    if (n.endUtf16 > body.length) return false
    val before = clean(body.substring(0, n.startUtf16))
    val after = clean(body.substring(n.endUtf16))
    val name = item.name.trim()
    // A qualifier immediately before a leading amount cannot be abandoned
    // as background. Reuse the source-checked request/courtesy vocabulary.
    if (before.isEmpty() || (pack == null && name.isNotEmpty() && (phrase(after, name) || startsWithName(after, name)))) {
        if (!amountPrefixNeutral(original.substring(0, primary.endUtf16), primary.startUtf16 + n.startUtf16, previous)) return false
    }
    if (n.unit !in setOf("count", "piece", "pack")) return true
    // A number at the right edge can only end here or lead into a real,
    // separately represented item. Punctuation alone cannot erase a unit.
    if (after.isEmpty() || countCourtesy.matches(after)) {
        val gap = original.substring(primary.endUtf16, next ?: original.length)
        if (next != null && gap.isBlank()) return false
        val rest = clean(gap)
        if (rest.isNotEmpty() && !countCourtesy.matches(rest) &&
            !(next != null && countJoin.matches(rest))) return false
    }
    if (distribution != null || trailingSizeProven) return true // complete source-owned construction
    // Remove only the exact inner measure, never all words after the count.
    val residual = if (pack != null && pack.startUtf16 >= n.endUtf16) {
        val measureEnd = GroceryIntentPhysicalUnitMatcher.after(primary.text, pack.endUtf16, pack.unit)?.end
        if (measureEnd == null) after else clean(primary.text.substring(n.endUtf16, pack.startUtf16) + " " + primary.text.substring(measureEnd))
    } else after
    if (name.isEmpty() || residual.isEmpty() || countCourtesy.matches(residual)) return true
    if (endsIn(before, name)) return unitFits(residual)
    // The product may be translated ("doodh" -> "milk"). An unknown unit
    // cannot become a piece just because the claim used a different noun.
    if (before.isNotEmpty() && pack == null && !amountPrefixNeutral(primary.text, n.startUtf16))
        return unitFits(residual)
    if (startsWithName(residual, name)) {
        val tail = residual.substring(name.length).trim()
        if (groceryIntentRetailContainerKey(tail) != null || pieceUnit.matches(tail)) return unitFits(tail)
    }
    if (endsIn(residual, name) && !phrase(residual, name)) {
        val marker = residual.dropLast(name.length).trim()
        if (marker.endsWith(" of", true)) return unitFits(marker.dropLast(3).trim())
    }
    return true
}

private val pieceUnit = Regex("pieces?|pcs?", RegexOption.IGNORE_CASE)
internal val countCourtesy = Regex("please|kindly|thanks|thank[ \\t]+you", RegexOption.IGNORE_CASE)
private val countJoin = Regex("and|aur|और", RegexOption.IGNORE_CASE)
private val countSentenceEnd = Regex("[.!?।](?=[ \\t\\r\\n])|[\\r\\n]")
private fun amountPrefixNeutral(text: String, start: Int, from: Int = 0): Boolean {
    if (start == from) return true
    // Completed background sentences are allowed; decimal points are not stops.
    val boundary = from + (countSentenceEnd.findAll(text.substring(from, start)).lastOrNull()?.range?.last?.plus(1) ?: 0)
    val gap = text.substring(boundary, start).trim { it.isWhitespace() || it in ".,;!?।" }
    if (gap.isEmpty() || countJoin.matches(gap)) return true
    // Keep original offsets: a secondary row cannot pretend to start a list.
    val owned = listOfNotNull(if (boundary > 0) GroceryIntentReviewV2Quote(text.substring(0, boundary), 0, boundary) else null,
        GroceryIntentReviewV2Quote(text.substring(start), start, text.length))
    return groceryIntentOwnedContextComplete(text, owned, owned)
}
private data class RowAudit(val index: Int, val issues: List<String>, val role: String, val packageRole: String,
    val packaging: String?, val requirements: Map<String, RequirementMeaning>)
private data class RequirementMeaning(val kind: String, val predicates: Set<IngredientPredicate>)
private data class LiteralRequirementClaim(val text: String, val meaning: RequirementMeaning)
private data class LiteralRequirement(val id: String, val source: GroceryIntentReviewV2Quote, val meaning: RequirementMeaning)

/** A proposed requirement cannot launder a missing product word out of the subject. */
internal class SourceMerchandiseProperty private constructor(private val primary: GroceryIntentReviewV2Quote,
    private val quote: GroceryIntentReviewV2Quote, private val predicates: Set<IngredientPredicate>) {
    internal fun rangeFor(row: GroceryIntentReviewV2Quote) = if (primary != row) null else
        (quote.startUtf16 - row.startUtf16) until (quote.endUtf16 - row.startUtf16)
    internal fun enforced(ingredients: List<IngredientPredicate>, seller: SellerPropertyRequirement?) =
        if (predicates.isNotEmpty()) ingredients.containsAll(predicates) else seller?.source == primary && quote in seller.requirements

    companion object {
        internal fun fromSource(primary: GroceryIntentReviewV2Quote, quote: GroceryIntentReviewV2Quote, kind: String,
            predicates: Set<IngredientPredicate>, seller: SellerPropertyRequirement?): SourceMerchandiseProperty? {
            val start = quote.startUtf16 - primary.startUtf16
            val end = quote.endUtf16 - primary.startUtf16
            if (start < 0 || end <= start || end > primary.text.length || primary.text.substring(start, end) != quote.text) return null
            val text = quote.text
            val key = text.lowercase(java.util.Locale.ROOT).replace(Regex("[ \\t-]+"), "")
            val supported = when (kind) {
                "ingredient" -> predicates.isNotEmpty() && predicates.all { swiggyHasIngredientPredicate(text, it) } &&
                    swiggyClaimIdentityView(text).subjectText.isBlank()
                "seller" -> predicates.isEmpty() && seller?.source == primary && quote in seller.requirements &&
                    (literalProcessingLabel.matches(text) || key in detachedDietForms && groceryIntentPropertiesIn(text) == setOf(key))
                else -> false
            }
            return if (supported) SourceMerchandiseProperty(primary, quote, predicates.toSet()) else null
        }
    }
}
private data class SourceAudit(val source: GroceryIntentReviewV2Quote, val role: String, val owners: List<Int>)

private fun labelTouchesSubject(text: String, occurrence: MatchResult, subject: String): Boolean {
    val words = subject.lowercase(java.util.Locale.ROOT).trim().split(Regex("""\s+""")).filter(String::isNotEmpty)
    val at = words.indexOf(occurrence.value.lowercase(java.util.Locale.ROOT))
    val after = if (at < 0) words else words.drop(at + 1)
    val before = if (at < 0) words else words.take(at)
    fun phrase(parts: List<String>) = parts.joinToString("""[\s-]+""", transform = Regex::escape)
    if (after.isNotEmpty() && Regex("""(?i)^[\s-]+""" + phrase(after) + """(?![\p{L}\p{M}\p{N}_])""")
        .containsMatchIn(text.substring(occurrence.range.last + 1))) return true
    return before.isNotEmpty() && (at < 0 || after.isEmpty()) &&
        Regex("""(?i)(?<![\p{L}\p{M}\p{N}_])""" + phrase(before) + """[\s-]+$""")
            .containsMatchIn(text.substring(0, occurrence.range.first))
}

private fun processingLabelsAccountedFor(primary: GroceryIntentReviewV2Quote, requirements: List<LiteralRequirement>, subject: String): Boolean {
    val labels = literalProcessingLabel.findAll(primary.text).toList()
    if (labels.isEmpty()) return true
    val sellerRanges = requirements.filter { it.meaning.kind == "seller" }.map {
        (it.source.startUtf16 - primary.startUtf16) until (it.source.endUtf16 - primary.startUtf16)
    }
    if (labels.any { it.range !in sellerRanges || !labelTouchesSubject(primary.text, it, subject) }) return false
    val remainder = primary.text.toCharArray()
    for (r in requirements) {
        if (r.meaning.kind == "ingredient" && r.meaning.predicates.isNotEmpty() &&
            r.meaning.predicates.all { swiggyHasIngredientPredicate(r.source.text, it) }) {
            remainder.fill('|', r.source.startUtf16 - primary.startUtf16, r.source.endUtf16 - primary.startUtf16)
        }
    }
    val scope = String(remainder)
    return !propertyHasUnprovedNegation(scope) && !qualifiedProcessingLabel.containsMatchIn(scope)
}

// A disagreement with either model can only veto. Never project an object, a
// shopping row or a dietary predicate from background onto a nearby purchase.
private fun anySourceGap(text: String, offset: Int, owned: List<GroceryIntentReviewV2Quote>, alarm: (String) -> Boolean): Boolean {
    var start = 0
    for (span in owned.sortedBy { it.startUtf16 }) {
        val end = span.startUtf16 - offset
        if (start < end && alarm(text.substring(start, end))) return true
        start = span.endUtf16 - offset
    }
    return start < text.length && alarm(text.substring(start))
}
private val explicitRestriction = Regex("""(?i)(?<![\p{L}\p{M}\p{N}_])(?:avoid(?:s|ed|ing)?|skip(?:s|ped|ping)?|exclud(?:e|es|ed|ing)|except|leave\s+out|allerg(?:y|ies|ic|ens?)|intoleran(?:t|ce)|cannot|dont|cant|wont|नहीं|नही|बिना|मत)(?![\p{L}\p{M}\p{N}_])|n['’ʼ]t\b""")
private fun hasRestrictionOperator(text: String): Boolean = explicitRestriction.containsMatchIn(text)
// Relations, not catalogue adjectives or standalone words such as "only",
// "morning" or "agar". Search identities are never exceptions to this check.
private val orderScopeOperator = Regex(
    """(?ix)(?<![\p{L}\p{M}\p{N}_])(?:
        if | unless | otherwise | rather\s+than | provided\s+that |
        (?:agar|अगर|यदि)\s+[^.;!?।\r\n]{1,120}?\s+(?:toh?|तो|तभी|ho|mile|mila|हो|मिले|मिला) |
        (?:before|after|by)\s+(?:breakfast|lunch|dinner|noon|midnight|tonight|tomorrow) |
        (?:today|tomorrow|this)\s+(?:morning|afternoon|evening|night)
    )(?![\p{L}\p{M}\p{N}_])""",
)
private val detachedDietForms = setOf("lowfat", "noaddedsugar", "sugarfree", "unsweetened", "unsalted",
    "glutenfree", "lactosefree", "vegetarian", "vegan")
private val standaloneCourtesy = Regex("""(?i)[\s.,;!?]*no\s+(?:worries|problem)[\s.,;!?]*""")
private val singleSpeechAct = Regex("""(?i)(?:just[ \t]+(?:chatting|talking)(?:[ \t]+today)?|(?:thanks|thank[ \t]+you)[ \t]+for[ \t]+(?:your[ \t]+)?help)""")
/** Only entire independently bound courtesy/asides at either edge; a period alone grants nothing. */
private fun detachedShoppingDiscourse(text: String, rows: List<GroceryIntentReviewV2Quote>): Boolean {
    if (rows.isEmpty()) return false
    val edges = listOf(0 until rows.first().startUtf16, rows.last().endUtf16 until text.length)
    val discourse = edges.filterNot { it.isEmpty() }.mapNotNull { edge ->
        val gap = text.substring(edge)
        val nearItem = if (edge.first == 0) gap.trimEnd().lastOrNull() else gap.trimStart().firstOrNull()
        if (nearItem !in setOf('.', '!', '?', ',', '।')) return@mapNotNull null
        val acts = gap.trim(' ', '\t', '.', '!', '?', ',', '।').split(Regex("[.!?।]+")).map(String::trim)
        if (acts.none { standaloneCourtesy.matches(it) || singleSpeechAct.matches(it) } ||
            acts.any { !standaloneCourtesy.matches(it) && !singleSpeechAct.matches(it) && !countCourtesy.matches(it) })
            return@mapNotNull null
        GroceryIntentReviewV2Quote(gap, edge.first, edge.last + 1)
    }
    return discourse.isNotEmpty() && groceryIntentOwnedContextComplete(text, rows + discourse, rows)
}
private fun detachedConstraintAlarm(text: String): Boolean {
    // A gap containing only a number (including a signed decimal) still
    // carries a quantity. It must not disappear as punctuation/background.
    if (text.codePoints().anyMatch { Character.isDigit(it) } || groceryIntentContainsNumericAtom(text)) return true
    if (text.none(Char::isLetter) || standaloneCourtesy.matches(text)) return false
    return hasRestrictionOperator(text) || propertyHasUnprovedNegation(text) ||
        ScopedInterpretedCardinal.hasUnboundWord(text) ||
        groceryIntentPropertiesIn(text).any { it in detachedDietForms } || swiggyBackgroundIngredientAlarm(text)
}
private val auditIssues = setOf("product", "quantity", "size", "diet", "condition", "timing", "shared_allocation", "correction", "binding", "unknown")
private val quantityRoles = setOf("requested_pieces", "retail_units", "physical_total", "unstated", "unresolved")
private val packageSizeRoles = setOf("unstated", "per_unit_mass", "per_unit_volume", "per_unit_pieces", "unresolved")
private val packagingForms = setOf("pack", "packet", "bag", "bottle", "carton", "tetra_pack", "cup", "can", "tin", "tub", "jar", "box", "bar", "block", "loaf", "bunch", "roll", "refill", "pouch", "sachet", "tube", "spray")

private fun amount(value: JsonValue, original: String, primary: GroceryIntentReviewV2Quote, number: BigDecimal?, unit: String, text: String?, amounts: GroceryIntentInterpretedAmounts): IntentLocalAmount? {
    if (number == null) { require(value == JsonValue.Null && text == null); return null }
    val atom = quote(value, original, 64)
    require(atom.text == text && atom.startUtf16 >= primary.startUtf16 && atom.endUtf16 <= primary.endUtf16)
    val local = requireNotNull(amounts.bind(number, unit)) { "Invalid interpreted numeric atom" }
    require(amounts.coversOriginalAmount(original, primary.startUtf16, local)) { "Item quote cuts an original amount" }
    require(local.startUtf16 + primary.startUtf16 == atom.startUtf16 && local.endUtf16 + primary.startUtf16 == atom.endUtf16 &&
        local.value.compareTo(number) == 0 && physicalUnitFollowsAt(primary.text, local.startUtf16, atom.text, unit))
    return local
}

private fun requireRole(item: GroceryIntentReviewV2Item, role: String, packageRole: String) {
    require((item.quantity == null) == (role == "unstated"))
    require((item.quantityUnit in setOf("g", "kg", "ml", "l")) == (role == "physical_total"))
    require(item.quantityUnit != "piece" || role == "requested_pieces")
    require(item.quantityUnit != "pack" || role == "retail_units")
    require(role != "unresolved")
    val expected = when {
        item.packValue == null -> "unstated"
        item.packUnit in setOf("g", "kg") -> "per_unit_mass"
        item.packUnit in setOf("ml", "l") -> "per_unit_volume"
        else -> "per_unit_pieces"
    }
    require(packageRole == expected)
}

private fun interpretedQuantity(value: BigDecimal?, unit: String): Quantity {
    if (value == null) return Quantity.Default
    val n = if (unit in setOf("kg", "l")) value.multiply(BigDecimal(1000)).intValueExact() else value.intValueExact()
    require(n > 0)
    return when (unit) {
        "count", "pack", "piece" -> Quantity.Count(n)
        "g", "kg" -> Quantity.Weight(n)
        "ml", "l" -> Quantity.Volume(n)
        else -> error("Unsupported interpreted quantity")
    }
}

private fun requireClaimProjection(value: JsonValue, item: GroceryIntentReviewV2Item) {
    val obj = value.objectWith("name", "quantity", "quantity_unit", "pack_value", "pack_unit", "required", "excluded", "source_text", "needs_clarification")
    require(obj.string("name") == item.name && obj.string("source_text") == item.sourceText &&
        obj["needs_clarification"] == JsonValue.Bool(item.needsClarification) &&
        obj.array("required", 16).map { it.string() } == item.required && obj.array("excluded", 16).map { it.string() } == item.excluded)
    fun measureMatches(key: String, unitKey: String, amount: BigDecimal?, unit: String): Boolean {
        val claim = obj.getValue(key)
        if (claim == JsonValue.Null) return amount == null && obj.string(unitKey) == "unspecified"
        val number = (claim as? JsonValue.Num)?.raw?.toBigDecimal() ?: return false
        if (amount == null || number.signum() <= 0) return false
        val claimUnit = obj.string(unitKey)
        if (unit in setOf("count", "pack", "piece")) return claimUnit == unit && number.compareTo(amount) == 0
        return runCatching { GroceryPackMeasure.of(number, claimUnit) == GroceryPackMeasure.of(amount, unit) }.getOrDefault(false)
    }
    require(measureMatches("quantity", "quantity_unit", item.quantity, item.quantityUnit) &&
        measureMatches("pack_value", "pack_unit", item.packValue, item.packUnit))
}

private fun requirementMeaning(obj: Map<String, JsonValue>): RequirementMeaning {
    val kind = obj.string("kind").also { require(it in setOf("seller", "ingredient", "unsupported")) }
    val predicates = obj.array("ingredient_predicates", 16).map {
        val entry = it.objectWith("operator", "operand")
        IngredientPredicate.fromPayload(mapOf("operator" to entry.string("operator"), "operand" to entry.string("operand")))
    }
    require(predicates.distinct().size == predicates.size && (kind == "ingredient") == predicates.isNotEmpty())
    return RequirementMeaning(kind, Collections.unmodifiableSet(LinkedHashSet(predicates)))
}

private fun quote(value: JsonValue, original: String, max: Int = 320) = GroceryIntentReviewV2Quote.parse(value.strictObject(), original, max)
private fun JsonValue.strictObject(): Map<String, JsonValue> = (this as? JsonValue.Obj)?.also { require(it.duplicateKeys.isEmpty()) }?.members ?: error("Expected object")
private fun JsonValue.objectWith(vararg keys: String): Map<String, JsonValue> = strictObject().also { require(it.keys == keys.toSet()) }
private fun JsonValue.string(): String = (this as? JsonValue.Str)?.value ?: error("Expected string")
private fun JsonValue.nullableString(): String? = if (this == JsonValue.Null) null else string()
private fun JsonValue.integer(): Int = (this as? JsonValue.Num)?.raw?.takeIf { Regex("-?(?:0|[1-9][0-9]*)").matches(it) }?.toIntOrNull() ?: error("Expected integer")
private fun JsonValue.array(max: Int): List<JsonValue> = ((this as? JsonValue.Arr)?.items ?: error("Expected array")).also { require(it.size <= max) }
private fun Map<String, JsonValue>.string(key: String) = getValue(key).string()
private fun Map<String, JsonValue>.array(key: String, max: Int): List<JsonValue> =
    ((getValue(key) as? JsonValue.Arr)?.items ?: error("Expected array")).also { require(it.size <= max) }
private fun <T> frozen(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
