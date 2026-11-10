package com.example.beta

import java.math.BigDecimal

// This source alias is ASCII-only even inside a Unicode case-insensitive regex.
internal const val GROCERY_INTENT_KEJI_PATTERN = "(?-i:[kK][eE][jJ][iI])(?=$|[\\s,.;:!?()\\[\\]{}-])"

/** Strict decoder used only when the client explicitly requests the OFF-default v2 contract. */
internal data class GroceryIntentReviewV2Response(
    val draft: GroceryIntentReviewV2,
    val model: String,
    val promptVersion: String,
    val latencyMs: Double,
    val usage: Map<String, Double>,
    val inputRepair: GroceryIntentInputRepair? = null,
    val inputRepairRejected: Boolean = false,
) {
    companion object {
        private val keys = setOf("accepted", "draft", "model", "promptVersion", "latencyMs", "usage")

        fun parse(body: String, originalInstruction: String): GroceryIntentReviewV2Response {
            val root = JsonParser(body).parseStrict().asObject()
            require(root.keys == keys || root.keys == keys + "input_repair")
            require(root.boolean("accepted"))
            val model = root.string("model").bounded("model", 1, 120)
            val promptVersion = root.string("promptVersion").bounded("promptVersion", 1, 80)
            val latencyMs = root.number("latencyMs").finiteDouble("latencyMs").also { require(it >= 0.0) }
            val usageObject = root.objectValue("usage")
            require(usageObject.keys.all { it in USAGE_KEYS })
            val usage = buildMap {
                usageObject.keys.forEach { key ->
                    put(key, usageObject.number(key).finiteDouble("usage.$key").also { require(it in 0.0..100_000_000.0) })
                }
            }
            val draft = GroceryIntentReviewV2.parse(root.objectValue("draft"), originalInstruction)
            val inputRepair = if ("input_repair" in root) {
                // A malformed optional edit suggestion must not discard a valid blocked draft.
                try {
                    GroceryIntentInputRepair.parse(root.getValue("input_repair"), originalInstruction, draft)
                } catch (_: RuntimeException) {
                    null
                }
            } else null
            return GroceryIntentReviewV2Response(
                draft,
                model,
                promptVersion,
                latencyMs,
                usage.toMap(),
                inputRepair,
                "input_repair" in root && inputRepair == null,
            )
        }
    }
}

/** Keep wire decoding with the DTO's existing strict JSON readers. */
internal fun GroceryIntentProductOperation.Companion.parse(
    obj: Map<String, JsonValue>, instruction: String,
): GroceryIntentProductOperation {
    obj.requireKeys(setOf("kind", "target_row_index", "replacement_row_index", "source", "target_mention"))
    val kind = obj.string("kind")
    require(kind == "replace")
    return GroceryIntentProductOperation(
        kind = kind,
        targetRowIndex = obj.integer("target_row_index"),
        replacementRowIndex = obj.integer("replacement_row_index"),
        source = GroceryIntentReviewV2Quote.parse(obj.objectValue("source"), instruction, 6000),
        targetMention = GroceryIntentReviewV2Quote.parse(obj.objectValue("target_mention"), instruction, 100),
        coverageSources = emptyList(),
    )
}

internal data class GroceryIntentReviewV2(
    val contractVersion: String,
    val requiresReview: Boolean,
    val instruction: String,
    val sourceItemCount: Int,
    val readyRows: List<GroceryIntentReviewV2ReadyRow>,
    val blockedRows: List<GroceryIntentReviewV2BlockedRow>,
    val groups: List<GroceryIntentReviewV2Group> = emptyList(),
    val constraints: List<GroceryIntentReviewV2Constraint> = emptyList(),
    val operations: List<GroceryIntentProductOperation> = emptyList(),
    val ingredientObligations: List<GroceryIntentReviewV2IngredientObligation>? = null,
) {
    companion object {
        private val keys = setOf(
            "contract_version", "requires_review", "instruction", "source_item_count", "ready_rows", "blocked_rows",
        )

        fun parse(obj: Map<String, JsonValue>, originalInstruction: String): GroceryIntentReviewV2 {
            val optionalKeys = setOf("groups", "constraints", "operations", "ingredient_obligations")
            require(obj.keys.containsAll(keys) && (obj.keys - keys).all { it in optionalKeys })
            require(obj.string("contract_version") == "grocery-intent-v2")
            require(obj.boolean("requires_review"))
            val instruction = obj.string("instruction").plainText("instruction", 1, 6000)
            require(instruction == originalInstruction)
            val sourceCount = obj.integer("source_item_count").also { require(it in 0..50) }
            val ready = obj.arrayValue("ready_rows")
            val blocked = obj.arrayValue("blocked_rows")
            require(ready.size <= 50 && blocked.size <= 50 && ready.size + blocked.size == sourceCount)
            val readyRows = ready.map { GroceryIntentReviewV2ReadyRow.parse(it.asObject(), instruction) }
            val blockedRows = blocked.map { GroceryIntentReviewV2BlockedRow.parse(it.asObject(), instruction) }
            val indices = (readyRows.map { it.rowIndex } + blockedRows.map { it.rowIndex })
            require(indices.size == sourceCount && indices.toSet().size == sourceCount && indices.sorted() == (0 until sourceCount).toList())
            val primarySpans = readyRows.map { it.evidence.primary } + blockedRows.mapNotNull { it.source }
            validateCorrectionTargets(readyRows, blockedRows, instruction)
            require(primarySpans.indices.all { left ->
                (left + 1 until primarySpans.size).all { right ->
                    primarySpans[left].endUtf16 <= primarySpans[right].startUtf16 ||
                        primarySpans[right].endUtf16 <= primarySpans[left].startUtf16
                }
            })
            val correctionSpans = readyRows.flatMap { row -> row.corrections.map { it.source } }
            require(correctionSpans.all { correction -> primarySpans.none { primary -> correction.overlaps(primary) } })
            require(correctionSpans.indices.all { left ->
                (left + 1 until correctionSpans.size).all { right -> !correctionSpans[left].overlaps(correctionSpans[right]) }
            })
            val groups = if ("groups" in obj) obj.arrayValue("groups") else emptyList()
            require(groups.size <= 15)
            val parsedGroups = groups.map { GroceryIntentReviewV2Group.parse(it.asObject(), instruction) }
            validateGroups(parsedGroups, readyRows, blockedRows, correctionSpans, instruction)
            validateExcludedContexts(readyRows, blockedRows, parsedGroups, sourceCount, instruction)
            val constraintsValue = if ("constraints" in obj) obj.arrayValue("constraints") else emptyList()
            // Bounded leading/inter-item/terminal notes, each independently
            // re-derived below. Duplicates and forged source spans still reject.
            require(constraintsValue.size <= 20)
            val parsedConstraints = constraintsValue.map { value ->
                val constraint = value.asObject()
                constraint.requireKeys(setOf("kind", "source"))
                val kind = constraint.string("kind")
                require(kind in setOf("fulfillment", "list_purpose"))
                GroceryIntentReviewV2Constraint(
                    kind,
                    GroceryIntentReviewV2Quote.parse(constraint.objectValue("source"), instruction, 180),
                )
            }
            val parsedOperations = if ("operations" in obj) {
                val values = obj.arrayValue("operations")
                require(values.isNotEmpty() && values.size <= 25)
                values.map { GroceryIntentProductOperation.parse(it.asObject(), instruction) }
            } else emptyList()
            val parsedIngredientObligations = if ("ingredient_obligations" in obj) {
                val values = obj.arrayValue("ingredient_obligations")
                require(values.size <= 50)
                values.map { GroceryIntentReviewV2IngredientObligation.parse(it, instruction) }
            } else null
            require(parsedIngredientObligations == null || (
                parsedIngredientObligations.distinct().size == parsedIngredientObligations.size &&
                    parsedIngredientObligations.all { receipt -> receipt.memberIndices.all { member ->
                        readyRows.any { it.rowIndex == member }
                    } }
                )) { "Invalid ingredient obligation receipts" }
            if (parsedOperations.isNotEmpty()) {
                // Operations are negotiated only for independent, ordinary rows.
                // All item rows stay in the source partition; the operation is a receipt, not a split.
                require(blockedRows.isEmpty() && groups.isEmpty() && parsedConstraints.isEmpty())
                require(readyRows.size == sourceCount && readyRows.map { it.rowIndex } == (0 until sourceCount).toList())
                require(readyRows.all { it.corrections.isEmpty() && it.evidence.supporting.isEmpty() &&
                    it.item.supportingText.isEmpty() && !it.item.needsClarification })
                val compiled = compileGroceryIntentProductReplacements(instruction, readyRows.map { it.item })
                require(compiled.size == parsedOperations.size && compiled.indices.all { index ->
                    compiled[index].payloadEquals(parsedOperations[index])
                }) { "Invalid product operation receipt" }
            }
            require(parsedConstraints.isEmpty() || readyRows.none { row ->
                row.corrections.any { it.changesWeightPieceRole() }
            }) { "Quantity role replacement cannot reuse fulfillment constraints" }
            val occupied = buildList {
                addAll(primarySpans)
                readyRows.forEach { row ->
                    addAll(row.evidence.supporting.map { it.quote })
                    addAll(row.corrections.map { it.source })
                }
                blockedRows.forEach { row -> addAll(row.excludedContext) }
                parsedGroups.forEach { group ->
                    add(group.source)
                    group.header?.let(::add)
                    group.total?.let { add(it.source) }
                    group.each?.let { add(it.source) }
                }
            }
            validateFulfillmentConstraints(instruction, primarySpans, parsedConstraints, occupied)
            return GroceryIntentReviewV2(
                "grocery-intent-v2", true, instruction, sourceCount,
                readyRows.toList(), blockedRows.toList(), parsedGroups.toList(), parsedConstraints.toList(),
                parsedOperations.toList(), parsedIngredientObligations?.toList(),
            )
        }
    }
}

/** A strict, non-actionable shared-group proof copied from the backend DTO. */
internal data class GroceryIntentReviewV2Group(
    val status: String,
    val amountStatus: String,
    val source: GroceryIntentReviewV2Quote,
    val header: GroceryIntentReviewV2Quote?,
    val memberIndices: List<Int>,
    val members: List<GroceryIntentReviewV2GroupMember>,
    val packaging: String?,
    val total: GroceryIntentReviewV2GroupAmount?,
    val each: GroceryIntentReviewV2GroupAmount?,
) {
    companion object {
        private val keys = setOf(
            "status", "amount_status", "source", "header", "member_indices", "members",
            "packaging", "total", "each",
        )

        fun parse(obj: Map<String, JsonValue>, instruction: String): GroceryIntentReviewV2Group {
            obj.requireKeys(keys)
            val status = obj.string("status")
            require(status == "structurally_verified")
            val amountStatus = obj.string("amount_status")
            require(amountStatus in GROUP_AMOUNT_STATUSES)
            val source = GroceryIntentReviewV2Quote.parse(obj.objectValue("source"), instruction, 1500)
            val header = obj.nullableObject("header")?.let {
                GroceryIntentReviewV2Quote.parse(it, instruction, 180)
            }
            val postfix = header?.takeIf { it.startUtf16 > source.startUtf16 }
                ?.let { groceryIntentPostfixIdentityHeading(it.text) }
            if (header != null) {
                require(source.startUtf16 == header.startUtf16 || postfix != null)
                require(header.endUtf16 <= source.endUtf16)
            }
            val indices = obj.arrayValue("member_indices").map { it.asExactInt() }
            require(indices.size in 2..20 && indices.distinct().size == indices.size)
            require(indices == indices.sorted())
            val members = obj.arrayValue("members").map { GroceryIntentReviewV2GroupMember.parse(it.asObject()) }
            require(members.size == indices.size && members.map { it.rowIndex } == indices)
            val packaging = obj.nullableString("packaging")
            require(packaging == null || packaging in PACKAGING)
            val total = obj.nullableObject("total")?.let {
                GroceryIntentReviewV2GroupAmount.parse(it, instruction, "total")
            }
            val each = obj.nullableObject("each")?.let {
                GroceryIntentReviewV2GroupAmount.parse(it, instruction, "each")
            }
            require((total == null || total.source == header))
            require((each == null) || each.source.text.length <= 100)
            require(amountStatus == deriveAmountStatus(total, each, members))
            require(header != null || (total == null && each != null && packaging == null && amountStatus == "each_verified"))
            require(postfix == null || (total == null && each == null && packaging == null))
            require(packaging == header?.takeIf { postfix == null }?.let { headerPackaging(it.text) })
            val localHeadingIdentity = header?.let { groceryIntentLocalQuantityHeadingIdentity(it.text) }
            require(localHeadingIdentity == null || (
                total == null && each == null && members.isNotEmpty() &&
                    members.all { it.origin == "local" && it.value != null }
            ))
            val variety = header?.let { groceryIntentVariantCardinalityHeading(it.text) }
            if (header != null && groceryIntentHasVariantCardinalityShape(header.text)) {
                // Even a model-supplied total whose arithmetic balances cannot turn varieties into units.
                require(total == null && variety != null && variety.count == members.size)
                require(members.all { it.value != null })
            }
            if (header != null) validateGroupHeaderAmountRole(total, header, packaging, variety?.identity ?: localHeadingIdentity)
            return GroceryIntentReviewV2Group(
                status, amountStatus, source, header, indices.toList(), members.toList(),
                packaging, total, each,
            )
        }
    }
}

internal data class GroceryIntentReviewV2GroupMember(
    val rowIndex: Int,
    val value: BigDecimal?,
    val unit: String,
    val origin: String,
) {
    companion object {
        private val keys = setOf("row_index", "value", "unit", "origin")

        fun parse(obj: Map<String, JsonValue>): GroceryIntentReviewV2GroupMember {
            obj.requireKeys(keys)
            val rowIndex = obj.integer("row_index")
            require(rowIndex in 0..49)
            val value = obj.nullableNumber("value")
            val unit = obj.string("unit")
            require(unit in GROUP_UNITS)
            validateAmount(value, unit, "group member quantity")
            val origin = obj.string("origin")
            require(origin in GROUP_MEMBER_ORIGINS)
            require((origin == "not_stated") == (value == null))
            require((origin == "not_stated") == (unit == "unspecified"))
            return GroceryIntentReviewV2GroupMember(rowIndex, value, unit, origin)
        }
    }
}

internal data class GroceryIntentReviewV2GroupAmount(
    val source: GroceryIntentReviewV2Quote,
    val amountText: String,
    val value: BigDecimal,
    val unit: String,
) {
    companion object {
        private val keys = setOf("source", "amount_text", "value", "unit")

        fun parse(obj: Map<String, JsonValue>, instruction: String, kind: String): GroceryIntentReviewV2GroupAmount {
            obj.requireKeys(keys)
            val maxSource = if (kind == "total") 180 else 100
            val source = GroceryIntentReviewV2Quote.parse(obj.objectValue("source"), instruction, maxSource)
            val amountText = obj.string("amount_text").singleLineText("group $kind amount", 1, 64)
            val value = obj.number("value")
            val unit = obj.string("unit")
            require(unit in GROUP_AMOUNT_UNITS)
            validateGroupAmount(value, unit, kind)
            require(parseGroupAmountText(amountText)?.compareTo(value) == 0)
            require(uniqueExactQuoteWithin(instruction, source, amountText))
            require(physicalUnitFollows(source.text, amountText, unit))
            return GroceryIntentReviewV2GroupAmount(source, amountText, value, unit)
        }
    }
}

private fun validateGroups(
    groups: List<GroceryIntentReviewV2Group>,
    readyRows: List<GroceryIntentReviewV2ReadyRow>,
    blockedRows: List<GroceryIntentReviewV2BlockedRow>,
    correctionSpans: List<GroceryIntentReviewV2Quote>,
    instruction: String,
) {
    if (groups.isEmpty()) return
    val readyByIndex = readyRows.associateBy { it.rowIndex }
    val allPrimaries = (readyRows.map { it.rowIndex to it.evidence.primary } +
        blockedRows.mapNotNull { row -> row.source?.let { row.rowIndex to it } }).toMap()
    val groupMemberIndices = mutableSetOf<Int>()

    groups.forEachIndexed { groupIndex, group ->
        require(uniqueExactQuoteInInstruction(instruction, group.source))
        require(instruction.substring(group.source.startUtf16, group.source.endUtf16) == group.source.text)
        val header = group.header
        val postfix = group.postfixIdentity()
        require(group.memberIndices.all { it in readyByIndex })
        var coverageInstruction = instruction
        if (header != null) {
            require(uniqueExactQuoteWithin(instruction, group.source, header.text, header))
            if (postfix == null) {
                require(group.source.text.startsWith(header.text))
                require(header.startUtf16 == group.source.startUtf16)
            } else {
                require(!Regex("[:\\u2014\\u2013\\u2012\\uff1a]|(?<=\\s)-(?=\\s)").containsMatchIn(group.source.text))
                require(Regex("[ \\t]*(?:\\.[ \\t]*)?").matches(instruction.substring(header.endUtf16, group.source.endUtf16)))
                val boundaries = Regex("[;!?]|\\.(?=[ \\t\\n\\r\\f\\x0B]|$)")
                val start = boundaries.findAll(instruction.substring(0, group.source.startUtf16)).lastOrNull()
                    ?.range?.last?.plus(1) ?: 0
                coverageInstruction = leadingNeutralGroupContext(instruction, allPrimaries.values.toList(), start,
                    readyByIndex.getValue(group.memberIndices.first()).evidence.primary.startUtf16, header)
                val contentEnd = groupTerminalPeriodOffset(instruction, group.source.endUtf16)
                    ?.takeIf { it >= group.source.startUtf16 } ?: group.source.endUtf16
                val end = boundaries.find(instruction, start)?.range?.first ?: instruction.length
                require(coverageInstruction.substring(start, group.source.startUtf16).isBlank())
                require(contentEnd <= end && instruction.substring(contentEnd, end).trim(' ', '\t', '\r', '\n', ',').isEmpty())
            }
            val purpose = groceryIntentPurposeGroupHeading(header.text) != null
            if (purpose || groceryIntentOrganizationalCategoryHeading(header.text) != null) {
                val left = Regex("[;!?]|\\.(?=\\s|$)").findAll(instruction.substring(0, header.startUtf16))
                    .lastOrNull()?.range?.last?.plus(1) ?: 0
                require(instruction.substring(left, header.startUtf16).isBlank())
                require(group.total == null && group.each == null)
                if (purpose) require(group.packaging == null)
                if (purpose && header.text.trimEnd(' ').lastOrNull() !in listOf(':', '：', '—', '–', '‒')) {
                    val commandEnd = header.endUtf16 - (header.text.length - header.text.trimEnd(' ').length)
                    val firstPrimary = readyByIndex.getValue(group.memberIndices.first()).evidence.primary
                    require(Regex("[ ]+").matches(instruction.substring(commandEnd, firstPrimary.startUtf16)))
                }
                require(!Regex("\\b(?:each|total|altogether|kul)\\b|\\bhar\\s+ek\\b|\\bek\\s+ek\\b", RegexOption.IGNORE_CASE)
                    .containsMatchIn(group.source.text))
            }
            // A shared heading cannot silently stop before another member in
            // the same clause. Independently reject truncated backend proofs.
            val contentEnd = groupTerminalPeriodOffset(instruction, group.source.endUtf16)
                ?.takeIf { it >= group.source.startUtf16 } ?: group.source.endUtf16
            val end = Regex("[;!?]|\\.(?=\\s|$)").find(instruction, contentEnd)?.range?.first ?: instruction.length
            require(instruction.substring(contentEnd, end).trim(' ', '\t', '\r', '\n', ',').isEmpty())
        } else {
            require(group.total == null && group.each != null && group.packaging == null && group.amountStatus == "each_verified")
            require(!Regex("[:\\u2014\\u2013\\u2012\\uff1a]|(?<=\\s)-(?=\\s)").containsMatchIn(group.source.text))
            val boundaries = Regex("[;!?]|\\.(?=\\s|$)")
            val start = boundaries.findAll(instruction.substring(0, group.source.startUtf16)).lastOrNull()
                ?.range?.last?.plus(1) ?: 0
            val leadingEach = requireNotNull(group.each).source
            coverageInstruction = leadingNeutralGroupContext(
                instruction, allPrimaries.values.toList(), start,
                minOf(readyByIndex.getValue(group.memberIndices.first()).evidence.primary.startUtf16,
                    leadingEach.startUtf16), leadingEach,
            )
            val contentEnd = groupTerminalPeriodOffset(instruction, group.source.endUtf16)
                ?.takeIf { it >= group.source.startUtf16 } ?: group.source.endUtf16
            val end = boundaries.find(instruction, contentEnd)?.range?.first ?: instruction.length
            require(coverageInstruction.substring(start, group.source.startUtf16).isBlank() ||
                forwardEachHasOwnedPredecessors(instruction, start, group, readyByIndex, allPrimaries))
            require(instruction.substring(contentEnd, end).trim(' ', '\t', '\r', '\n', ',').isEmpty())
        }
        require(group.source.endUtf16 <= instruction.length)
        val localHeadingEligible = group.hasEligibleLocalQuantityHeading() &&
            header?.let { groceryIntentLocalQuantityHeadingIdentity(it.text) } != null
        require(header == null || !Regex("[\\r\\n]").containsMatchIn(header.text))
        require(header == null || ';' !in header.text || localHeadingEligible)
        require(Regex("[:\\u2014\\u2013\\u2012\\uff1a]|(?<=\\s)-(?=\\s)")
            .findAll(group.source.text).count() <= 1)
        val structural = Regex("[:\\u2014\\u2013\\u2012\\uff1a]|(?<=\\s)-(?=\\s)")
            .find(group.source.text)
        val firstBodyStart = minOf(
            group.memberIndices.map { readyByIndex.getValue(it).evidence.primary.startUtf16 }.minOrNull() ?: Int.MAX_VALUE,
            group.each?.source?.startUtf16 ?: Int.MAX_VALUE,
        )
        require(structural == null || group.source.startUtf16 + structural.range.last < firstBodyStart)
        require(group.memberIndices.all { groupMemberIndices.add(it) })
        require(groups.indices.filter { it != groupIndex }.all { otherIndex ->
            !group.source.overlaps(groups[otherIndex].source)
        })
        val embedded = readyRows.flatMap { row -> row.corrections.filter { it.source.overlaps(group.source) }
            .map { row to it } }
        require(embedded.all { (row, correction) -> group.isEmbeddedPackCorrection(row, correction) })
        require(correctionSpans.filter { it.overlaps(group.source) }.toSet() == embedded.map { it.second.source }.toSet())

        val memberPrimaries = group.memberIndices.map { rowIndex ->
            val row = readyByIndex.getValue(rowIndex)
            require(!row.item.needsClarification)
            require(row.item.supportingText.isEmpty())
            if (group.packaging != null && row.item.packaging != null) {
                require(group.packaging == row.item.packaging)
            }
            row.evidence.primary
        }
        if (group.variantCardinalityIdentity() != null) {
            require(group.memberIndices.map { index ->
                val row = readyByIndex.getValue(index)
                val source = row.evidence.primary.text
                val amount = row.localQuantityEvidence()
                require(row.item.quantity == null || amount != null)
                val identity = if (amount == null) source else
                    source.replaceRange(amount.startUtf16, amount.endUtf16, " ")
                identity.trim().lowercase(java.util.Locale.ROOT)
                .split(Regex("\\s+")).joinToString(" ") }.distinct().size == memberPrimaries.size)
        }
        require(memberPrimaries.all { primary ->
            (if (postfix != null) primary.endUtf16 <= requireNotNull(header).startUtf16
             else primary.startUtf16 >= (header?.endUtf16 ?: group.source.startUtf16)) &&
                primary.startUtf16 >= group.source.startUtf16 && primary.endUtf16 <= group.source.endUtf16
        })
        require(memberPrimaries.zipWithNext().all { (left, right) -> left.endUtf16 <= right.startUtf16 })
        require(allPrimaries.all { (rowIndex, primary) ->
            rowIndex in group.memberIndices || !primary.overlaps(group.source)
        })

        val each = group.each
        if (each != null) {
            require(each.source.startUtf16 >= group.source.startUtf16 && each.source.endUtf16 <= group.source.endUtf16)
            require(uniqueExactQuoteWithin(instruction, group.source, each.source.text, each.source))
            require(header == null || !each.source.overlaps(header))
            require(memberPrimaries.none { it.overlaps(each.source) })
            // An each clause between members cannot assign amounts to later items.
            require(each.source.endUtf16 <= memberPrimaries.first().startUtf16 ||
                each.source.startUtf16 >= memberPrimaries.last().endUtf16)
            require(validEachGrammar(each))
        }
        val total = group.total
        if (total != null) {
            require(header != null && total.source == header)
            validateGroupAmountRole(total, header, group.packaging)
        }
        if (total != null && each != null) {
            require(groupUnitsMatch(total.unit, each.unit, group.packaging))
        }

        group.memberIndices.zip(group.members).forEach { (rowIndex, member) ->
            val item = readyByIndex.getValue(rowIndex).item
            if (item.quantity == null) {
                var remaining = item.sourceText
                item.packText?.let { packText ->
                    val primary = readyByIndex.getValue(rowIndex).evidence.primary
                    val selectedStart = requireNotNull(uniquePackQuoteStartWithin(
                        instruction, readyByIndex.getValue(rowIndex).evidence.primary, packText, item.packUnit,
                    ))
                    val packStart = selectedStart - primary.startUtf16
                    require(physicalUnitFollowsAt(item.sourceText, packStart, packText, item.packUnit))
                    remaining = remaining.replaceRange(packStart, packStart + packText.length, " ".repeat(packText.length))
                }
                val scan = remaining.toCharArray()
                intentProvenNonPurchaseNumericRanges(item.sourceText, item.name, item.required, item.excluded).forEach { range ->
                    for (index in range) scan[index] = ' '
                }
                require(!groceryIntentContainsNumericAtom(String(scan)))
            }
            when (member.origin) {
                "local" -> {
                    require(item.quantity != null && member.value != null)
                    require(item.quantity.compareTo(member.value) == 0 && item.quantityUnit == member.unit)
                    if (each != null) {
                        require(member.value.compareTo(each.value) == 0)
                        require(groupUnitsMatch(member.unit, each.unit, group.packaging))
                    }
                }
                "each" -> {
                    require(item.quantity == null && each != null && member.value != null)
                    require(member.value.compareTo(each.value) == 0)
                    require(groupUnitsMatch(member.unit, each.unit, group.packaging))
                }
                "not_stated" -> {
                    require(item.quantity == null && member.value == null && each == null)
                }
            }
            if (total != null) {
                require(member.value != null && groupUnitsMatch(total.unit, member.unit, group.packaging))
            }
        }
        if (total != null) {
            val sum = group.members.fold(BigDecimal.ZERO) { current, member ->
                current.add(requireNotNull(member.value))
            }
            require(sum.compareTo(total.value) == 0)
        }
        require(group.correctedAmountsIssue(readyByIndex) == null)
        validateGroupCoverage(instruction, coverageInstruction, group, memberPrimaries, embedded)
    }
}

internal fun GroceryIntentReviewV2Group.isEmbeddedPackCorrection(
    row: GroceryIntentReviewV2ReadyRow, correction: GroceryIntentReviewV2Correction,
): Boolean = header != null && header.startUtf16 == source.startUtf16 && total == null && each == null &&
    packaging == null && variantCardinalityIdentity() == null && correction.kind == "pack_size" &&
    row.rowIndex in memberIndices && correction.source.startUtf16 >= source.startUtf16 &&
    correction.source.endUtf16 <= source.endUtf16 && !correction.source.overlaps(header) &&
    !correction.source.overlaps(row.evidence.primary)

/** Do not join original text across a separately proved correction receipt. */
internal fun GroceryIntentReviewV2Group.outsideEmbeddedCorrectionFragments(
    rows: List<GroceryIntentReviewV2ReadyRow>,
): List<String> {
    val embedded = rows.flatMap { row -> row.corrections.filter { isEmbeddedPackCorrection(row, it) } }
        .sortedBy { it.source.startUtf16 }
    var position = 0
    return buildList {
        embedded.forEach { correction ->
            val start = correction.source.startUtf16 - source.startUtf16
            val end = correction.source.endUtf16 - source.startUtf16
            require(start >= position)
            add(source.text.substring(position, start))
            position = end
        }
        add(source.text.substring(position))
    }
}

/** Original group proof is immutable; numeric edits must preserve its meaning. */
internal fun GroceryIntentReviewV2Group.correctedAmountsIssue(
    readyByIndex: Map<Int, GroceryIntentReviewV2ReadyRow>,
): String? {
    fun canonical(value: BigDecimal, unit: String): Pair<String, BigDecimal> {
        var family = when (unit) { "g", "kg" -> "weight"; "ml", "l" -> "volume"; else -> unit }
        if (packaging != null && family in setOf("count", "pack")) family = "retail"
        return family to value.multiply(if (unit in setOf("kg", "l")) BigDecimal("1000") else BigDecimal.ONE)
    }
    val final = members.map { member ->
        val row = readyByIndex[member.rowIndex] ?: return "group_member_unproven"
        var value = member.value
        var unit = member.unit
        for (correction in row.corrections) {
            if (correction.source.overlaps(source) && !isEmbeddedPackCorrection(row, correction)) return "group_correction_overlap"
            if (correction.kind != "quantity") continue
            if (value == null || canonical(value, unit).first != canonical(correction.newValue, correction.newUnit).first)
                return "group_correction_unit_conflict"
            value = correction.newValue
            unit = correction.newUnit
        }
        val amount = value?.let { canonical(it, unit) }
        if (each != null) {
            val expected = canonical(each.value, each.unit)
            if (amount == null || amount.first != expected.first || amount.second.compareTo(expected.second) != 0)
                return "group_correction_each_conflict"
        }
        amount
    }
    total?.let { total ->
        val expected = canonical(total.value, total.unit)
        if (final.any { it == null || it.first != expected.first } ||
            final.fold(BigDecimal.ZERO) { sum, amount -> sum.add(requireNotNull(amount).second) }.compareTo(expected.second) != 0)
            return "group_correction_total_conflict"
    }
    return null
}

/** A new forward-only distribution may follow fully owned, locally quantified
 * siblings. This does not erase left context or give an each amount to them. */
private fun forwardEachHasOwnedPredecessors(
    instruction: String,
    scopeStart: Int,
    group: GroceryIntentReviewV2Group,
    readyByIndex: Map<Int, GroceryIntentReviewV2ReadyRow>,
    allPrimaries: Map<Int, GroceryIntentReviewV2Quote>,
): Boolean {
    val each = group.each?.source ?: return false
    val first = readyByIndex[group.memberIndices.first()]?.evidence?.primary ?: return false
    if (each.startUtf16 != group.source.startUtf16 || each.endUtf16 >= first.startUtf16 ||
        !Regex(".+\\s+each", RegexOption.IGNORE_CASE).matches(each.text.trim()) ||
        !Regex("[ \\t]+(?i:of)(?:[ \\t]+the)?[ \\t]+").matches(instruction.substring(each.endUtf16, first.startUtf16))) return false
    val prior = allPrimaries.entries.filter { (_, source) ->
        source.startUtf16 >= scopeStart && source.startUtf16 < group.source.startUtf16
    }.sortedBy { it.value.startUtf16 }
    if (prior.isEmpty()) return false
    var position = scopeStart
    for ((index, source) in prior) {
        val row = readyByIndex[index] ?: return false
        val quantity = row.localQuantityEvidence() ?: return false
        // Leading identity/request words may scope over the entire list.
        if (!Regex("[ \\t]*").matches(source.text.substring(0, quantity.startUtf16))) return false
        if (source.endUtf16 > group.source.startUtf16 || source.startUtf16 < position ||
            row.item.quantity == null || row.item.needsClarification || row.item.supportingText.isNotEmpty() ||
            row.evidence.supporting.isNotEmpty() || row.corrections.isNotEmpty() ||
            Regex("[:\\u2014\\u2013\\u2012\\uff1a]|(?<=\\s)-(?=\\s)|[;\\r\\n]").containsMatchIn(source.text) ||
            intentV2HasCorrectionCue(source.text) ||
            Regex("\\b(?:each|total|altogether|kul)\\b|\\bhar\\s+ek\\b|\\bek\\s+ek\\b", RegexOption.IGNORE_CASE).containsMatchIn(source.text) ||
            !groupGapIsSeparator(instruction.substring(position, source.startUtf16)) ||
            row.groundingIssue(instruction = instruction) != null) return false
        position = source.endUtf16
    }
    return Regex("[ \\t,]*(?i:and|aur)[ \\t]+").matches(instruction.substring(position, group.source.startUtf16))
}

private fun validateGroupCoverage(
    instruction: String,
    coverageInstruction: String,
    group: GroceryIntentReviewV2Group,
    memberPrimaries: List<GroceryIntentReviewV2Quote>,
    embedded: List<Pair<GroceryIntentReviewV2ReadyRow, GroceryIntentReviewV2Correction>>,
) {
    val owned = (listOfNotNull(group.header) + memberPrimaries + listOfNotNull(group.each?.source) + embedded.map { it.second.source })
        .sortedBy { it.startUtf16 }
    require(memberPrimaries.none { Regex("[:\\u2014\\u2013\\u2012\\uff1a]|(?<=\\s)-(?=\\s)|[;\\r\\n]").containsMatchIn(it.text) })
    var position = group.source.startUtf16
    var previous: GroceryIntentReviewV2Quote? = null
    owned.forEach { span ->
        require(span.startUtf16 >= position)
        val gap = coverageInstruction.substring(position, span.startUtf16)
        val inlineSizeGap = embedded.any { (row, correction) -> span == correction.source && previous == row.evidence.primary &&
            Regex("[ \\t,?\\-\\u2014\\u2013\\u2012]*").matches(gap) }
        require(inlineSizeGap || groupGapIsOwned(gap, previous, span, group, memberPrimaries))
        position = span.endUtf16
        previous = span
    }
    require(groupTailIsSeparator(instruction, position, group.source.endUtf16))
}

/** Closed connectors at independently validated quote edges, not stopword erasure. */
private fun groupGapIsOwned(
    text: String,
    previous: GroceryIntentReviewV2Quote?,
    following: GroceryIntentReviewV2Quote,
    group: GroceryIntentReviewV2Group,
    members: List<GroceryIntentReviewV2Quote>,
): Boolean {
    if (groupGapIsSeparator(text)) return true
    if (following !in members) return false
    val each = group.each
    if (each != null && previous == each.source && following == members.first() &&
        Regex(".+\\s+each", RegexOption.IGNORE_CASE).matches(each.source.text.trim()) &&
        Regex("[ \\t]+(?i:of)(?:[ \\t]+the)?[ \\t]+").matches(text)
    ) return true
    if (group.header != null && previous == group.header && following == members.first() &&
        (each != null || group.members.all { it.origin == "local" && it.value != null }) &&
        Regex("[ \\t,:]*(?:(?i:i|we)[ \\t]+(?i:want|need|would[ \\t]+like)|" +
            "(?i:mujhe[ \\t]+chahiye))[ \\t,]+").matches(text)
    ) return true
    // Literal lowercase 'the' only. Capital The may be a brand; a/an/ek
    // are quantity atoms and must never disappear into a connector.
    val article = Regex("([\\s\\S]*)\\bthe[ \\t]+").matchEntire(text)
    if (previous in members && article != null) {
        val prefix = article.groupValues[1]
        return groupGapIsSeparator(prefix) &&
            (Regex("(?i)\\b(?:and|aur|bhi)\\b").containsMatchIn(prefix) || ',' in prefix || '&' in prefix)
    }
    return false
}

private const val GROUP_ASCII_WHITESPACE = " \t\r\n\u000c\u000b"

private fun groupTerminalPeriodOffset(instruction: String, end: Int): Int? {
    var position = end - 1
    while (position >= 0 && instruction[position] in GROUP_ASCII_WHITESPACE) position--
    return position.takeIf {
        it >= 0 && instruction[it] == '.' &&
            (it + 1 == instruction.length || instruction[it + 1] in GROUP_ASCII_WHITESPACE)
    }
}

private fun groupTailIsSeparator(instruction: String, start: Int, end: Int): Boolean {
    if (groupGapIsSeparator(instruction.substring(start, end))) return true
    val period = groupTerminalPeriodOffset(instruction, end) ?: return false
    return period in start until end && instruction.substring(start, period).all {
        it in GROUP_ASCII_WHITESPACE || it == ','
    }
}

/** Call only for groups already bound and validated against their complete source. */
internal fun GroceryIntentReviewV2Group.sequenceContextSpan(
    instruction: String,
    primaries: List<GroceryIntentReviewV2Quote>,
): GroceryIntentCoverageSpan? {
    val scopeStart = Regex("[;!?]|\\.(?=\\s|$)")
        .findAll(instruction.substring(0, source.startUtf16)).lastOrNull()?.range?.last?.plus(1) ?: 0
    if (header != null) {
        // The group already owns its complete heading and all members. Only
        // an original sentence-edge transition can sit outside that proof;
        // lowercase 'the' is deliberate (capital The may start a brand).
        if (header.startUtf16 != source.startUtf16 || scopeStart == 0 ||
            primaries.none { it.startUtf16 < it.endUtf16 && it.endUtf16 <= scopeStart } ||
            primaries.any { it.startUtf16 < source.startUtf16 && scopeStart < it.endUtf16 }) return null
        val prefix = instruction.substring(scopeStart, source.startUtf16)
        if (prefix.length > 64 || !Regex(
                "[ \\t]*(?i:then|and[ \\t]+then|next|last|lastly|finally)[ \\t]+the[ \\t]+",
            ).matches(prefix)) return null
        return GroceryIntentCoverageSpan(scopeStart, source.startUtf16)
    }
    if (each == null || total != null) return null
    val firstMember = primaries.filter { it.startUtf16 >= source.startUtf16 && it.endUtf16 <= source.endUtf16 }
        .minOfOrNull { it.startUtf16 } ?: return null
    return groceryIntentHeadlessSequencePrefix(
        instruction, primaries.map { GroceryIntentCoverageSpan(it.startUtf16, it.endUtf16) },
        scopeStart, minOf(firstMember, each.source.startUtf16),
    )
}

private fun leadingNeutralGroupContext(
    instruction: String,
    primaries: List<GroceryIntentReviewV2Quote>,
    scopeStart: Int,
    firstOwnedStart: Int,
    each: GroceryIntentReviewV2Quote,
): String {
    val spans = primaries.map { GroceryIntentCoverageSpan(it.startUtf16, it.endUtf16) }
    val protected = spans + GroceryIntentCoverageSpan(each.startUtf16, each.endUtf16)
    val characters = instruction.toCharArray()
    val sequence = groceryIntentHeadlessSequencePrefix(instruction, spans, scopeStart, firstOwnedStart)
    (neutralGroceryIntentContextSpans(instruction, spans) + listOfNotNull(sequence)).forEach { context ->
        if (context.startUtf16 >= scopeStart && context.endUtf16 <= firstOwnedStart && protected.none {
                context.startUtf16 < it.endUtf16 && it.startUtf16 < context.endUtf16
            }
        ) {
            for (index in context.startUtf16 until context.endUtf16) characters[index] = ' '
        }
    }
    return String(characters)
}

private fun groupGapIsSeparator(text: String): Boolean {
    val tokens = Regex("[\\p{L}\\p{N}]+")
        .findAll(text.lowercase())
        .map { it.value }
        .toList()
    if (tokens.any { it !in GROUP_GAP_WORDS }) return false
    val residue = Regex("\\b(?:and|aur|bhi)\\b", RegexOption.IGNORE_CASE).replace(text, "")
    return Regex("[\\s,:\\u2014\\u2013\\u2012\\uff1a&\\-]*").matches(residue)
}

private fun validateGroupAmountRole(
    amount: GroceryIntentReviewV2GroupAmount,
    header: GroceryIntentReviewV2Quote,
    packaging: String?,
) {
    val unit = amount.unit
    if (unit in GROUP_PHYSICAL_UNITS && packaging != null) {
        // A physical value in a container header is per-container evidence, not a shared total.
        error("Unsupported shared physical pack amount")
    }
    val start = header.text.indexOf(amount.amountText)
    require(start >= 0)
    val remaining = header.text.removeRange(start, start + amount.amountText.length)
    require(!groceryIntentContainsNumericAtom(remaining))
}

private fun validateGroupHeaderAmountRole(
    total: GroceryIntentReviewV2GroupAmount?,
    header: GroceryIntentReviewV2Quote,
    packaging: String?,
    localHeadingIdentity: String? = null,
) {
    if (total != null) validateGroupAmountRole(total, header, packaging)
    var remaining = header.text
    if (total != null) {
        val occurrences = Regex(Regex.escape(total.amountText), RegexOption.IGNORE_CASE).findAll(remaining).toList()
        require(occurrences.size == 1)
        val match = occurrences.single()
        remaining = remaining.removeRange(match.range.first, match.range.last + 1)
    }
    val amountRoleText = if (localHeadingIdentity != null && total == null) localHeadingIdentity else remaining
    require(!groceryIntentContainsNumericAtom(amountRoleText))
}

private fun validateGroupAmount(value: BigDecimal, unit: String, kind: String) {
    require(value > BigDecimal.ZERO && value <= BigDecimal("1000000"))
    when (unit) {
        "count", "pack" -> {
            require(value.stripTrailingZeros().scale() <= 0)
            if (kind == "each") require(value <= BigDecimal(20))
        }
        "piece" -> {
            require(value.stripTrailingZeros().scale() <= 0)
            if (kind == "each") require(value <= BigDecimal(1000))
        }
        "g", "kg", "ml", "l" -> Unit
        else -> error("Invalid group amount unit")
    }
}

private fun validEachGrammar(each: GroceryIntentReviewV2GroupAmount): Boolean {
    val text = each.source.text.trim()
    val match = Regex("^(.+?)\\s+each$", RegexOption.IGNORE_CASE).matchEntire(text)
    if (match != null) {
        val words = match.groupValues[1].trim().split(Regex("\\s+"))
        val suffixUnits = mapOf(
            "piece" to "piece", "pieces" to "piece", "pack" to "pack", "packs" to "pack",
            "packet" to "pack", "packets" to "pack", "g" to "g", "gram" to "g", "grams" to "g",
            "kg" to "kg", "kilo" to "kg", "keji" to "kg", "ml" to "ml", "l" to "l", "litre" to "l", "liter" to "l",
        )
        if (words.lastOrNull()?.lowercase() == "keji" && words.last().any { it.code > 127 }) return false
        val explicitUnit = words.lastOrNull()?.lowercase()?.let(suffixUnits::get)
        val amountWords = if (explicitUnit == null) words else words.dropLast(1)
        if (explicitUnit != null && !groupUnitsMatch(explicitUnit, each.unit, null)) return false
        return amountWords.joinToString(" ").equals(each.amountText, ignoreCase = true)
    }
    val normalized = text.lowercase().split(Regex("\\s+"))
    return normalized.size == 2 && normalized[0] == normalized[1] &&
        normalized[0] in HINDI_REDUPLICATED_NUMBERS && each.unit in setOf("count", "pack", "piece") &&
        each.amountText.equals(text, ignoreCase = true)
}

private fun parseGroupAmountText(text: String): BigDecimal? {
    val words = text.trim().split(Regex("\\s+"))
    if (words.size == 2 && words[0].equals(words[1], ignoreCase = true) &&
        words[0].lowercase() in HINDI_REDUPLICATED_NUMBERS
    ) {
        return parseIntentCorrectionAmount(words[0])
    }
    return parseIntentCorrectionAmount(text)
}

internal fun uniqueExactQuoteInInstruction(
    instruction: String,
    expected: GroceryIntentReviewV2Quote,
): Boolean = exactQuoteOccurrences(instruction, expected.text, 0, instruction.length).let { matches ->
    matches.size == 1 && matches.single() == expected
}

private fun uniqueExactQuoteWithin(
    instruction: String,
    scope: GroceryIntentReviewV2Quote,
    text: String,
    expected: GroceryIntentReviewV2Quote? = null,
): Boolean = exactQuoteOccurrences(instruction, text, scope.startUtf16, scope.endUtf16).let { matches ->
    matches.size == 1 && (expected == null || matches.single() == expected)
}

private fun exactQuoteOccurrences(
    instruction: String,
    quote: String,
    scopeStart: Int,
    scopeEnd: Int,
): List<GroceryIntentReviewV2Quote> {
    if (quote.isEmpty() || scopeStart !in 0..instruction.length || scopeEnd !in scopeStart..instruction.length) return emptyList()
    val result = mutableListOf<GroceryIntentReviewV2Quote>()
    var search = scopeStart
    while (search <= scopeEnd - quote.length) {
        val start = instruction.indexOf(quote, search)
        if (start < 0 || start + quote.length > scopeEnd) break
        val end = start + quote.length
        if (hasQuoteBoundaries(instruction, quote, start, end)) {
            result += GroceryIntentReviewV2Quote(quote, start, end)
        }
        search = start + 1
    }
    return result
}

internal fun hasQuoteBoundaries(instruction: String, quote: String, start: Int, end: Int): Boolean {
    fun isWord(char: Char): Boolean {
        val type = Character.getType(char)
        return char == '_' || Character.isLetterOrDigit(char) || type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt()
    }
    if (quote.firstOrNull()?.let(::isWord) == true && start > 0 && isWord(instruction[start - 1])) return false
    if (quote.lastOrNull()?.let(::isWord) == true && end < instruction.length && isWord(instruction[end])) return false
    val numericSeparators = setOf('.', ',', '/', '\u066b', '\u066c', '\uff0e', '\uff0c', '\u2044', '\u2215')
    if (quote.firstOrNull()?.isDigit() == true && start > 0) {
        val previous = instruction[start - 1]
        if (previous in setOf('.', '\u066b', '\uff0e', '+', '-', '\u2212', '\uff0b', '\uff0d')) return false
        if (previous in numericSeparators && start > 1 && instruction[start - 2].isDigit()) return false
    }
    if (quote.lastOrNull()?.isDigit() == true && end + 1 < instruction.length &&
        instruction[end] in numericSeparators && instruction[end + 1].isDigit()
    ) return false
    return true
}

private fun physicalUnitFollows(text: String, amountText: String, unit: String): Boolean {
    val start = text.indexOf(amountText)
    require(start >= 0)
    return physicalUnitFollowsAt(text, start, amountText, unit)
}

internal fun physicalUnitFollowsAt(text: String, start: Int, amountText: String, unit: String): Boolean {
    if (start !in 0..text.length - amountText.length) return false
    val end = start + amountText.length
    return if (unit in GROUP_PHYSICAL_UNITS) {
        GroceryIntentPhysicalUnitMatcher.after(text, end, unit) != null
    } else {
        GROUP_PHYSICAL_UNITS.none { GroceryIntentPhysicalUnitMatcher.after(text, end, it) != null }
    }
}

/** One alias table and Unicode-safe matcher shared by amount binding and review proofs. */
internal object GroceryIntentPhysicalUnitMatcher {
    private const val END = "(?!['’][\\p{L}])(?=$|[\\s,.;:!?()\\[\\]{}/'’\\-])"
    internal val patterns: Map<String, String> = mapOf(
        // Literal CLDR 46 long-unit forms; no case inflections, join-control stripping or transliteration.
        "g" to "(?:grams?|gms|gm|g|ग्राम|கிராம்|கிராம்கள்|ಗ್ರಾಂ|ಗ್ರಾಂಗಳು)$END",
        "kg" to "(?:kilograms?|kilos?|kgs|kg|$GROCERY_INTENT_KEJI_PATTERN|किलोग्राम|किलो|கிலோகிராம்|கிலோகிராம்கள்|கிலோ|ಕಿಲೋಗ್ರಾಂ|ಕಿಲೋಗ್ರಾಂಗಳು|ಕಿಲೋ)$END",
        "ml" to "(?:millilitres?|milliliters?|ml|मिलीलीटर|मिलिलीटर|मिलिलिटर|मिली|மில்லிலிட்டர்|மில்லிலிட்டர்கள்|ಮಿಲಿಲೀಟರ್)$END",
        "l" to "(?:litres?|liters?|ltr|l|लीटर|लिटर|லிட்டர்|லிட்டர்கள்|ಲೀಟರ್)$END",
    )

    internal data class Match(val token: String, val start: Int, val end: Int)

    internal fun after(text: String, start: Int, unit: String): Match? {
        val aliases = patterns[unit] ?: return null
        if (start !in 0..text.length) return null
        val tail = text.substring(start)
        val separator = unicodeEvidenceRegex("^\\s*-?\\s*(?:a\\s+)?").find(tail)?.value ?: return null
        val tokenStart = start + separator.length
        val token = unicodeEvidenceRegex("^(?:$aliases)").find(text.substring(tokenStart))?.value ?: return null
        val tokenEnd = tokenStart + token.length
        if (tokenEnd < text.length) {
            val next = text.codePointAt(tokenEnd)
            val type = Character.getType(next)
            if (next == '_'.code || Character.isLetterOrDigit(next) ||
                type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt() ||
                type == Character.ENCLOSING_MARK.toInt()) return null
            // Keep the English brand guard: L'Oreal is not a litre suffix.
            if (text[tokenEnd] in setOf('\'', '\u2019') && tokenEnd + 1 < text.length &&
                Character.isLetter(text.codePointAt(tokenEnd + 1))) return null
        }
        return Match(token, tokenStart, tokenEnd)
    }

    internal fun immediatelyAfter(text: String, end: Int, unit: String): String? {
        val match = after(text, end, unit) ?: return null
        return match.token.takeIf { match.start == end }
    }
}

private fun uniquePackQuoteStartWithin(
    instruction: String,
    primary: GroceryIntentReviewV2Quote,
    quote: String,
    unit: String,
    requirePhysicalUnit: Boolean = false,
): Int? {
    val found = mutableListOf<Int>()
    var search = primary.startUtf16
    while (search <= primary.endUtf16 - quote.length) {
        val start = instruction.indexOf(quote, search)
        if (start < 0 || start + quote.length > primary.endUtf16) break
        val end = start + quote.length
        val regularBoundary = hasQuoteBoundaries(instruction, quote, start, end)
        val gluedUnit = physicalUnitTokenFollowsAt(instruction, end, unit)
        val gluedMeasure = quote.lastOrNull()?.isDigit() == true && gluedUnit != null &&
            hasQuoteBoundaries(instruction, quote + gluedUnit, start, end + gluedUnit.length)
        if ((regularBoundary || gluedMeasure) &&
            (!requirePhysicalUnit || physicalUnitFollowsAt(instruction, start, quote, unit))) found += start
        search = start + 1
    }
    return found.singleOrNull()
}

/** Null means genuinely unstated, never permission to lose a spoken count.
 * Unknown numeric descriptors remain for review; closed unit-bound specs and
 * grade letters need source proof and literal preservation. Model fields,
 * later corrections and group defaults cannot provide missing amount proof.
 */
internal fun GroceryIntentReviewV2ReadyRow.quantityOmissionIssue(): String? {
    if (item.quantity != null) return null
    var remaining = evidence.primary.text
    val packText = item.packText
    if (packText != null && item.packUnit in GROUP_PHYSICAL_UNITS) {
        val localPrimary = GroceryIntentReviewV2Quote(remaining, 0, remaining.length)
        val start = uniquePackQuoteStartWithin(remaining, localPrimary, packText, item.packUnit)
        if (start == null || item.packValue == null ||
            parseIntentCorrectionAmount(packText)?.compareTo(item.packValue) != 0 ||
            !physicalUnitFollowsAt(remaining, start, packText, item.packUnit)
        ) return "quantity_omission_suspected"
        remaining = remaining.replaceRange(start, start + packText.length, " ".repeat(packText.length))
    }
    if (packText != null && item.packUnit == "piece") {
        // Only the positively proven inner count may be masked. Any other
        // number remains an omitted purchase amount; null never becomes 1.
        val pack = localPackSizeEvidence()
        if (pack == null || parseIntentCorrectionAmount(packText)?.compareTo(pack.value) != 0 ||
            discretePackRoleIssue(requireLeading = true) != null) return "quantity_omission_suspected"
        remaining = remaining.replaceRange(pack.startUtf16, pack.endUtf16, " ".repeat(packText.length))
    }
    // Supporting notes are considered covered later, so scan them independently
    // too. No pack atom can own a number outside the primary source.
    val fragments = listOf(evidence.primary.text to remaining) + evidence.supporting.map { it.quote.text to it.quote.text }
    return if (fragments.any { (original, fragment) ->
        // Classify against the original words, before pack masking can create a
        // new descriptor template. This never supplies a default count as proof.
        val scan = fragment.toCharArray()
        intentProvenNonPurchaseNumericRanges(original, item.name, item.required, item.excluded).forEach { range ->
            for (index in range) scan[index] = ' '
        }
        val normalized = java.text.Normalizer.normalize(String(scan), java.text.Normalizer.Form.NFKC)
            .lowercase(java.util.Locale.ROOT)
            // Existing polarity/target checks validate the whole clause first.
            // Only its English auxiliary is not the Hinglish number two.
            .replace(Regex("\\bdo(?=\\s+not\\s+substitute\\b)"), "  ")
        groceryIntentContainsNumericAtom(normalized)
    }) "quantity_omission_suspected" else null
}

internal fun physicalUnitTokenFollowsAt(text: String, end: Int, unit: String): String? {
    if (unit !in GROUP_PHYSICAL_UNITS || end !in 0..text.length) return null
    return GroceryIntentPhysicalUnitMatcher.immediatelyAfter(text, end, unit)
}

private fun groupUnitsMatch(left: String, right: String, packaging: String?): Boolean =
    left == right || (packaging != null && left in setOf("count", "pack") && right in setOf("count", "pack"))

private fun headerPackaging(header: String): String? {
    val forms = GROUP_PACKAGING_PATTERNS.filterValues { pattern -> pattern.containsMatchIn(header) }.keys
    require(forms.size <= 1)
    return forms.singleOrNull()
}

private fun deriveAmountStatus(
    total: GroceryIntentReviewV2GroupAmount?,
    each: GroceryIntentReviewV2GroupAmount?,
    members: List<GroceryIntentReviewV2GroupMember>,
): String = when {
    total != null -> "total_verified"
    each != null -> "each_verified"
    members.all { it.value != null } -> "local_amounts_verified"
    else -> "amounts_unspecified"
}

internal data class GroceryIntentReviewV2ReadyRow(
    val rowIndex: Int,
    val item: GroceryIntentReviewV2Item,
    val evidence: GroceryIntentReviewV2Evidence,
    val quantityOrigin: String,
    val amountRoleStatus: String,
    val corrections: List<GroceryIntentReviewV2Correction> = emptyList(),
) {
    companion object {
        private val keys = setOf("row_index", "item", "evidence", "quantity_origin", "amount_role_status")

        fun parse(obj: Map<String, JsonValue>, instruction: String): GroceryIntentReviewV2ReadyRow {
            require(obj.keys == keys || obj.keys == keys + "corrections")
            val index = obj.integer("row_index")
            val item = GroceryIntentReviewV2Item.parse(obj.objectValue("item"), instruction)
            require(!item.needsClarification && item.supportingText.none { it.kind == "correction" })
            val evidence = GroceryIntentReviewV2Evidence.parse(obj.objectValue("evidence"), instruction, item)
            val quantityOrigin = obj.string("quantity_origin")
            require(quantityOrigin == if (item.quantity == null) "not_stated" else "stated")
            val amountRoleStatus = obj.string("amount_role_status")
            require(amountRoleStatus in AMOUNT_ROLE_STATUSES)
            require((amountRoleStatus == "not_stated") == (item.quantity == null && item.packValue == null))
            val corrections = if ("corrections" in obj) obj.arrayValue("corrections").let { entries ->
                require(entries.size in 1..2)
                entries.map { GroceryIntentReviewV2Correction.parse(it.asObject(), instruction, item, evidence.primary) }
            } else emptyList()
            require(corrections.map { it.kind }.distinct().size == corrections.size)
            require(!(corrections.any { it.kind == "pack_size" } && corrections.any {
                it.kind == "quantity" && (it.retainsPackSize || it.singlePackSelection != null)
            })) { "Unchanged-size reference conflicts with a size correction" }
            return GroceryIntentReviewV2ReadyRow(index, item, evidence, quantityOrigin, amountRoleStatus, corrections.toList())
        }
    }
}

/** Server-proven numeric changes only. Their text is NEVER product-name evidence. */
internal data class GroceryIntentReviewV2Correction(
    val kind: String,
    val source: GroceryIntentReviewV2Quote,
    val newAmount: GroceryIntentReviewV2Quote,
    val oldValue: BigDecimal,
    val oldUnit: String,
    val newValue: BigDecimal,
    val newUnit: String,
    // Derived proof only, not a server-supplied JSON field.
    val retainsPackSize: Boolean = false,
    // Recomputed from the complete original correction, never a JSON claim.
    val singlePackSelection: String? = null,
) {
    fun changesWeightPieceRole(): Boolean =
        (oldUnit in setOf("g", "kg") && newUnit == "piece") ||
            (oldUnit == "piece" && newUnit in setOf("g", "kg"))

    /** Derived from the same closed source proof, never from the model name. */
    fun namedPieceProduceCategory(item: GroceryIntentReviewV2Item, primary: GroceryIntentReviewV2Quote): IntentProduceCategory? {
        if (kind != "quantity" || oldUnit !in setOf("g", "kg") || newUnit != "piece") return null
        val noun = namedPiecePrimaryNoun(primary.text, item.quantityText ?: return null, oldUnit) ?: return null
        val suffix = source.text.substring(newAmount.endUtf16 - source.startUtf16)
            .trim().trimEnd { it.isWhitespace() || it in ",.!?" }.trim()
        return namedPieceNouns[noun]?.second.takeIf { namedPieceNoun(trimHindiCorrectionAction(suffix)) == noun }
    }

    companion object {
        fun parse(
            obj: Map<String, JsonValue>, instruction: String, item: GroceryIntentReviewV2Item,
            primary: GroceryIntentReviewV2Quote,
        ): GroceryIntentReviewV2Correction {
            obj.requireKeys(setOf("kind", "source", "new_amount", "old_value", "old_unit", "new_value", "new_unit"))
            val kind = obj.string("kind")
            require(kind in setOf("quantity", "pack_size"))
            val source = GroceryIntentReviewV2Quote.parse(obj.objectValue("source"), instruction)
            val amount = GroceryIntentReviewV2Quote.parse(obj.objectValue("new_amount"), instruction)
            require(source.text.length <= 180 && amount.text.length <= 64)
            require(source.startUtf16 >= primary.endUtf16)
            require(amount.startUtf16 >= source.startUtf16 && amount.endUtf16 <= source.endUtf16)
            val oldValue = obj.number("old_value")
            val oldUnit = obj.string("old_unit")
            val baseValue = if (kind == "quantity") item.quantity else item.packValue
            val baseUnit = if (kind == "quantity") item.quantityUnit else item.packUnit
            require(baseValue != null && oldValue.compareTo(baseValue) == 0 && oldUnit == baseUnit)
            val newValue = obj.number("new_value")
            val newUnit = obj.string("new_unit")
            val quotedValue = parseIntentCorrectionAmount(amount.text)
            require(quotedValue != null && quotedValue.compareTo(newValue) == 0)
            if (kind == "quantity") validateAmount(newValue, newUnit, "corrected quantity")
            else {
                require(newUnit in setOf("piece", "g", "kg", "ml", "l"))
                validatePack(newValue, newUnit)
            }
            val roleChanged = unitFamily(oldUnit) != unitFamily(newUnit)
            if (roleChanged) {
                require(kind == "quantity" && setOf(unitFamily(oldUnit), unitFamily(newUnit)) == setOf("weight", "piece"))
                require(item.packValue == null && item.packaging == null &&
                    !CORRECTION_CONTAINERS.containsMatchIn(primary.text + " " + source.text))
                if (oldUnit == "piece") {
                    require(hasExplicitPieceQuantityAtom(primary.text, requireNotNull(item.quantityText)))
                }
            }
            val physicalUnit = when (newUnit) {
                "g" -> "(?:g|gm|gms|grams?)"
                "kg" -> "(?:kg|kgs|kilos?|kilograms?|$GROCERY_INTENT_KEJI_PATTERN)"
                "ml" -> "(?:ml|millilitres?|milliliters?)"
                "l" -> "(?:l|ltr|litres?|liters?)"
                else -> null
            }
            if (physicalUnit != null) {
                val tail = source.text.substring(amount.endUtf16 - source.startUtf16)
                require(Regex("^\\s*-?\\s*$physicalUnit\\b", RegexOption.IGNORE_CASE).containsMatchIn(tail))
            }
            val newTail = when (newUnit) {
                "g" -> "(?:g|gm|gms|grams?)"
                "kg" -> "(?:kg|kgs|kilos?|kilograms?|$GROCERY_INTENT_KEJI_PATTERN)"
                "ml" -> "(?:ml|millilitres?|milliliters?)"
                "l" -> "(?:l|ltr|litres?|liters?)"
                "piece" -> "(?:pieces?|pcs|eggs?)?"
                "pack", "count" -> "(?:packs?|packets?|bags?|bottles?|cartons?|cups?|cans?|tins?|tubs?|jars?|box(?:es)?|bars?|blocks?|pouch(?:es)?|sachets?|tubes?|loaf|loaves|refills?|rolls?|sprays?|bunch(?:es)?)?"
                else -> error("Invalid correction unit")
            }
            val suffix = source.text.substring(amount.endUtf16 - source.startUtf16)
                .trim().trimEnd { it.isWhitespace() || it in ",.!?" }.trim()
            val hindiTarget = hindiNumericCorrectionTarget(
                source.text.substring(0, amount.startUtf16 - source.startUtf16).trimStart(),
                suffix, kind, item, primary.text, source.text, oldValue, oldUnit, newUnit, newTail, roleChanged, newValue,
            )
            require(!roleChanged || hindiTarget != null) { "Role replacement needs explicit named contrast" }
            val sameSize = validateSameSizeQuantityTail(suffix, newTail, kind, newUnit, item, primary)
            val repeatedSize = validateRepeatedSizeQuantityTail(suffix, kind, newUnit, item, primary)
            val singlePack = validateSinglePackSelection(source, amount, kind, newValue, newUnit, item, primary)
            if (hindiTarget == null && !sameSize && !repeatedSize && singlePack == null && !Regex("\\s*$newTail\\s*", RegexOption.IGNORE_CASE).matches(suffix)) {
                requireRecalledCorrectionAmount(source.text, amount.endUtf16 - source.startUtf16, oldValue, oldUnit, newUnit)
            }
            if (kind == "quantity" && singlePack == null) requireUnchangedCorrectionContainer(item.packaging, primary.text,
                if (sameSize) SAME_SIZE_NOTE.replace(trimCorrectionTail(source.text), "") else source.text)
            return GroceryIntentReviewV2Correction(kind, source, amount, oldValue, oldUnit, newValue, newUnit,
                retainsPackSize = sameSize || repeatedSize, singlePackSelection = singlePack)
        }

        private fun unitFamily(unit: String): String = when (unit) {
            "g", "kg" -> "weight"
            "ml", "l" -> "volume"
            "count", "pack", "piece" -> unit
            else -> error("Invalid correction unit")
        }
    }
}

private val CORRECTION_CONTAINERS = Regex(
    "\\b(?:tetra[ \\t-]*packs?|packs?|packets?|bags?|bottles?|cartons?|cups?|cans?|tins?|tubs?|jars?|" +
        "box(?:es)?|bars?|blocks?|pouch(?:es)?|sachets?|tubes?|loaf|loaves|refills?|rolls?|sprays?|bunch(?:es)?)\\b",
    RegexOption.IGNORE_CASE,
)
private val SAME_SIZE_NOTE = Regex(",[ \\t]*same(?:[ \\t]+pack)?[ \\t]+size$", RegexOption.IGNORE_CASE)
private fun trimCorrectionTail(text: String): String = text.trim().trimEnd { it.isWhitespace() || it in ",.!?" }.trim()

/** Exact total plus an explicitly selected SINGLE pack, not an inferred count.
 * Both occurrences are role-bound on original text. A second number, unknown
 * suffix, changed existing container or unequal measure can never be discarded.
 */
private fun validateSinglePackSelection(
    source: GroceryIntentReviewV2Quote, amount: GroceryIntentReviewV2Quote,
    kind: String, newValue: BigDecimal, newUnit: String,
    item: GroceryIntentReviewV2Item, primary: GroceryIntentReviewV2Quote,
): String? {
    val unit = physicalCorrectionUnits[newUnit] ?: return null
    val families = mapOf("g" to "weight", "kg" to "weight", "ml" to "volume", "l" to "volume")
    if (kind != "quantity" || item.quantityUnit !in families || families[item.quantityUnit] != families[newUnit] ||
        item.packValue != null || item.packaging != null || CORRECTION_CONTAINERS.containsMatchIn(primary.text) ||
        source.text.any { it == '\n' || it == '\r' }) return null
    val measures = physicalCorrectionUnits.values.joinToString("|")
    val pattern = Regex(
        "(?<left>(?:(?:no|sorry|actually)[, \\t]+){0,2}(?:make|change|set)[ \\t]+(?:the[ \\t]+)?" +
            "[^,;:\\r\\n!?]+?[ \\t]+(?:to[ \\t]+)?(?<total>${Regex.escape(amount.text)})[ \\t]*$unit)" +
            ",[ \\t]*(?<count>one|ek|1(?:\\.0+)?)[ \\t]+(?<size>[^,;:\\r\\n!?]+?)[ \\t]*-?[ \\t]*" +
            "(?<unit>$measures)[ \\t]+(?<form>${CORRECTION_CONTAINERS.pattern})",
        RegexOption.IGNORE_CASE,
    )
    val match = pattern.matchEntire(trimCorrectionTail(source.text)) ?: return null
    val total = requireNotNull(match.groups["total"])
    val leading = source.text.length - source.text.trimStart().length
    require(source.startUtf16 + leading + total.range.first == amount.startUtf16 &&
        source.startUtf16 + leading + total.range.last + 1 == amount.endUtf16) {
        "Corrected total must bind the first role, not the repeated pack size"
    }
    val packUnit = physicalCorrectionUnits.entries.single {
        Regex(it.value, RegexOption.IGNORE_CASE).matches(requireNotNull(match.groups["unit"]).value)
    }.key
    val size = requireNotNull(parseIntentCorrectionAmount(requireNotNull(match.groups["size"]).value.trim()))
    fun canonical(value: BigDecimal, code: String) = value.multiply(
        if (code in setOf("kg", "l")) BigDecimal("1000") else BigDecimal.ONE)
    require(parseIntentCorrectionAmount(requireNotNull(match.groups["count"]).value)?.compareTo(BigDecimal.ONE) == 0 &&
        size.signum() > 0 && families[packUnit] == families[newUnit] &&
        canonical(size, packUnit).compareTo(canonical(newValue, newUnit)) == 0) {
        "Single pack must exactly equal the corrected physical total"
    }
    val form = requireNotNull(match.groups["form"]).value
    return if (Regex("packets?", RegexOption.IGNORE_CASE).matches(form)) "packet" else correctionContainerFamily(form)
}

/** An unchanged-size reference is not evidence of an unstated size or a new product. */
private fun validateSameSizeQuantityTail(
    suffix: String, unitTail: String, kind: String, newUnit: String,
    item: GroceryIntentReviewV2Item, primary: GroceryIntentReviewV2Quote,
): Boolean {
    val note = SAME_SIZE_NOTE.find(suffix) ?: return false
    require(kind == "quantity" && newUnit in setOf("pack", "count") && item.quantityUnit == newUnit)
    require(item.packaging != null && item.packValue != null && item.packUnit in setOf("g", "kg", "ml", "l"))
    require(Regex("\\s*$unitTail\\s*", RegexOption.IGNORE_CASE).matches(suffix.substring(0, note.range.first)))
    requireOriginalPhysicalPack(item, primary)
    return true
}

/** An exact repeated physical size is a retained pack assertion, never another edit. */
private fun validateRepeatedSizeQuantityTail(
    suffix: String, kind: String, newUnit: String,
    item: GroceryIntentReviewV2Item, primary: GroceryIntentReviewV2Quote,
): Boolean {
    if (item.packUnit !in setOf("g", "kg", "ml", "l")) return false
    val unit = correctionUnitTail(item.packUnit)
    val match = Regex("[ \\t]*(?<amount>[^\\r\\n;,:!?]+?)[ \\t]+$unit[ \\t]+" +
        "(?<container>${CORRECTION_CONTAINERS.pattern})[ \\t]*", RegexOption.IGNORE_CASE).matchEntire(suffix) ?: return false
    require(kind == "quantity" && newUnit in setOf("pack", "count") && item.quantityUnit == newUnit)
    require(item.packaging != null && item.packValue != null)
    require(correctionContainerFamily(requireNotNull(match.groups["container"]).value) == correctionContainerFamily(item.packaging))
    requireOriginalPhysicalPack(item, primary)
    require(parseIntentCorrectionAmount(requireNotNull(match.groups["amount"]).value.trim())?.compareTo(item.packValue) == 0) {
        "Repeated per-pack size changed or unproven"
    }
    return true
}

/** Common source proof for verbal and numeric unchanged-size assertions. */
private fun requireOriginalPhysicalPack(item: GroceryIntentReviewV2Item, primary: GroceryIntentReviewV2Quote) {
    require(item.packaging != null && item.packValue != null && item.packUnit in setOf("g", "kg", "ml", "l"))
    val packText = requireNotNull(item.packText)
    require(parseIntentCorrectionAmount(packText)?.compareTo(item.packValue) == 0)
    val localPrimary = GroceryIntentReviewV2Quote(primary.text, 0, primary.text.length)
    val start = requireNotNull(uniquePackQuoteStartWithin(primary.text, localPrimary, packText, item.packUnit,
        requirePhysicalUnit = true))
    require(physicalUnitFollowsAt(primary.text, start, packText, item.packUnit))
    val unit = correctionUnitTail(item.packUnit)
    val after = primary.text.substring(start + packText.length)
    val measure = requireNotNull(Regex("^\\s*-?\\s*$unit\\b", RegexOption.IGNORE_CASE).find(after))
    val tail = after.substring(measure.range.last + 1)
    val before = primary.text.substring(0, start)
    val container = CORRECTION_CONTAINERS.pattern.removePrefix("\\b").removeSuffix("\\b")
    val packAfter = Regex("^\\s*(?:(?:ka|ki|ke|wala|wali|wale|tetra)\\s+)?$container\\b", RegexOption.IGNORE_CASE).containsMatchIn(tail)
    val packBefore = Regex("\\b$container\\s+of\\s*$", RegexOption.IGNORE_CASE).containsMatchIn(before)
    require(packAfter || packBefore) { "Original per-pack size role is unproven" }
    require(!Regex("\\b(?:total|altogether|kul)\\s*$", RegexOption.IGNORE_CASE).containsMatchIn(before) &&
        !Regex("^\\s*(?:in\\s+total|total|altogether|kul)\\b", RegexOption.IGNORE_CASE).containsMatchIn(tail))
    val forms = CORRECTION_CONTAINERS.findAll(primary.text).map { correctionContainerFamily(it.value) }.toSet()
    require(forms == setOf(correctionContainerFamily(item.packaging)))
}

private fun correctionContainerFamily(word: String): String {
    val normalized = word.lowercase(java.util.Locale.ROOT).replace(Regex("[ \\t-]"), "")
    if (normalized in setOf("tetrapack", "tetrapacks", "tetra_pack")) return "tetra_pack"
    val singular = when (normalized) {
        "boxes" -> "box"
        "pouches" -> "pouch"
        "bunches" -> "bunch"
        "loaves" -> "loaf"
        else -> normalized.removeSuffix("s")
    }
    return if (singular == "packet") "pack" else singular
}

/** Denial-only: a quantity override may repeat a proven form, never add/change it. */
private fun requireUnchangedCorrectionContainer(packaging: String?, primary: String, correction: String) {
    fun forms(text: String) = CORRECTION_CONTAINERS.findAll(text).map { correctionContainerFamily(it.value) }.toSet()
    val repeated = forms(correction)
    if (repeated.isEmpty()) return
    require(packaging != null && repeated == setOf(correctionContainerFamily(packaging)) && forms(primary) == repeated) {
        "Correction container changed or unproven"
    }
}

/** Retain an explicit single retail container while changing only its size.
 * No singular-noun default or multi-pack role inference; the whole original
 * primary, nearby named clause and both original amounts must independently bind.
 * Target uniqueness, complete suffix and group/source coverage still follow.
 */
private fun singleContainerSizeTarget(
    prefix: String, instruction: String, item: GroceryIntentReviewV2Item,
    primary: GroceryIntentReviewV2Quote, correction: GroceryIntentReviewV2Correction,
): String? {
    val match = Regex("(?:no|sorry|actually)[, \\t]+(?:the[ \\t]+)?(.+?)[ \\t]+is[ \\t]+", RegexOption.IGNORE_CASE)
        .matchEntire(prefix) ?: return null
    require(item.quantity?.compareTo(BigDecimal.ONE) == 0 && item.quantityUnit == "pack")
    require(item.packUnit in physicalCorrectionUnits && item.packaging !in setOf(null, "cup", "spray"))
    require((primary.text + correction.source.text).none { it == '\r' || it == '\n' })
    require(Regex("[ \\t,?\\-\\u2014\\u2013\\u2012]*")
        .matches(instruction.substring(primary.endUtf16, correction.source.startUtf16)))
    val count = requireNotNull(item.quantityText)
    val size = requireNotNull(item.packText)
    require(parseIntentCorrectionAmount(count)?.compareTo(BigDecimal.ONE) == 0)
    require(item.packValue != null && parseIntentCorrectionAmount(size)?.compareTo(item.packValue) == 0)
    require(uniqueExactQuoteWithin(instruction, primary, count))
    require(uniquePackQuoteStartWithin(instruction, primary, size, item.packUnit, requirePhysicalUnit = true) != null)
    val containers = CORRECTION_CONTAINERS.pattern.removePrefix("\\b").removeSuffix("\\b")
    val source = requireNotNull(Regex(
        Regex.escape(count) + "[ \\t]+" + Regex.escape(size) + "[ \\t]*" + physicalCorrectionUnits.getValue(item.packUnit) +
            "[ \\t]+(?<container>$containers)[ \\t]+of[ \\t]+\\S(?:.*\\S)?", RegexOption.IGNORE_CASE,
    ).matchEntire(primary.text))
    require(CORRECTION_CONTAINERS.findAll(primary.text).count() == 1)
    requireUnchangedCorrectionContainer(item.packaging, primary.text, requireNotNull(source.groups["container"]).value)
    return match.groupValues[1]
}

private fun validateCorrectionTargets(
    readyRows: List<GroceryIntentReviewV2ReadyRow>,
    blockedRows: List<GroceryIntentReviewV2BlockedRow>,
    instruction: String,
) {
    val primaries = readyRows.map { it.rowIndex to it.evidence.primary } +
        blockedRows.mapNotNull { row -> row.source?.let { row.rowIndex to it } }
    readyRows.forEach { row -> row.corrections.forEach { correction ->
        val primary = row.evidence.primary
        val prefix = correction.source.text.substring(0, correction.newAmount.startUtf16 - correction.source.startUtf16).trimStart()
        val local = Regex("(?:(?:no|sorry|actually)[,\\s]+){0,2}(?:make|change)\\s+(?:it|that|this)\\s+(?:to\\s+)?", RegexOption.IGNORE_CASE).matches(prefix) ||
            Regex("(?:no|sorry|actually)[,\\s]+", RegexOption.IGNORE_CASE).matches(prefix)
        val namedPattern = if (correction.kind == "pack_size") {
            Regex("(?:make|change|set)\\s+(?:(?:the|its)\\s+)?(?:(.+?)\\s+)?(?:pack\\s+size|size)\\s+(?:to\\s+)?", RegexOption.IGNORE_CASE)
        } else Regex("(?:(?:no|sorry|actually)[,\\s]+){0,2}(?:make|change|set)\\s+(?:the\\s+)?(.+?)\\s+(?:to\\s+)?", RegexOption.IGNORE_CASE)
        val named = namedPattern.matchEntire(prefix)
        val target: String
        val useLocal: Boolean
        val suffix = correction.source.text.substring(correction.newAmount.endUtf16 - correction.source.startUtf16)
            .trim().trimEnd { it.isWhitespace() || it in ",.!?" }.trim()
        val hindiTarget = hindiNumericCorrectionTarget(
            prefix, suffix, correction.kind, row.item, primary.text, correction.source.text,
            correction.oldValue, correction.oldUnit, correction.newUnit,
            correctionUnitTail(correction.newUnit), correction.changesWeightPieceRole(), correction.newValue,
        )
        if (hindiTarget != null) {
            target = hindiTarget
            useLocal = false
        } else if (correction.kind == "pack_size") {
            target = if (named != null) named.groupValues.getOrNull(1).orEmpty().trim()
                else requireNotNull(singleContainerSizeTarget(prefix, instruction, row.item, primary, correction)) {
                    "Unsupported pack-size correction prefix"
                }
            useLocal = target.isEmpty()
        } else if (local) {
            target = ""
            useLocal = true
        } else {
            require(named != null) { "Unsupported correction prefix" }
            target = named.groupValues.getOrNull(1).orEmpty().trim()
            require(!Regex("\\b(?:size|each|per)\\b", RegexOption.IGNORE_CASE).containsMatchIn(target))
            useLocal = false
        }
        if (useLocal) {
            require(localCorrectionTarget(instruction, correction, primary, primaries))
        } else {
            val anchors = correctionAnchors(target)
            require(anchors.isNotEmpty())
            val matches = primaries.filter { (_, span) -> anchors.all { it in correctionAnchors(span.text) } }
            require(matches.size == 1 && matches.single().first == row.rowIndex)
            val words = correctionWords(target)
            require(words.isNotEmpty() && words.all { it in correctionWords(primary.text) })
            fun tokens(text: String) = Regex("[\\p{L}\\p{N}\\p{M}]+")
                .findAll(java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
                    .lowercase(java.util.Locale.ROOT)).map { it.value }.toSet()
            if (tokens(target).any { it.any(Char::isDigit) || parseIntentCorrectionAmount(it) != null }) {
                fun normalized(text: String) = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
                    .lowercase(java.util.Locale.ROOT).trim().replace(Regex("\\s+"), " ")
                // A substring boundary can crop 1.5% to 5% or split a product
                // code. Numeric targets must retain one complete identity field.
                require((listOf(row.item.name) + row.item.required).any { normalized(target) == normalized(it) }) {
                    "Numeric target must preserve its complete original identity expression"
                }
            }
        }
    } }
}

private fun localCorrectionTarget(
    instruction: String,
    correction: GroceryIntentReviewV2Correction,
    primary: GroceryIntentReviewV2Quote,
    primaries: List<Pair<Int, GroceryIntentReviewV2Quote>>,
): Boolean {
    if (primary.endUtf16 > correction.source.startUtf16) return false
    val gap = instruction.substring(primary.endUtf16, correction.source.startUtf16)
    if (!Regex("[\\s,\\-\\u2014\\u2013\\u2012]*").matches(gap)) return false
    val boundary = Regex("[;!?\\r\\n]|\\.(?=\\s|$)").findAll(instruction.substring(0, correction.source.startUtf16)).lastOrNull()?.range?.last?.plus(1) ?: 0
    val antecedents = primaries.filter { (_, span) -> span.startUtf16 >= boundary && span.endUtf16 <= correction.source.startUtf16 }
    return antecedents.size == 1 && antecedents.single().first == primaries.firstOrNull { it.second == primary }?.first
}

private val CORRECTION_IGNORED_ANCHORS = setOf(
    "a", "an", "the", "and", "or", "of", "for", "with", "without", "in", "on", "to", "from", "please", "get", "buy", "order", "add",
    "make", "keep", "only", "just", "that", "this", "these", "those", "it", "is", "be", "are", "should", "must", "not", "no",
    "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "dozen", "half", "quarter",
    "ek", "do", "teen", "char", "chaar", "paanch", "panch", "chhe", "saat", "aath", "nau", "das", "dedh", "dhai", "aadha", "adha",
    "paav", "kilo", "kg", "keji", "g", "gm", "gram", "grams", "ml", "litre", "litres", "liter", "liters", "l", "pack", "packs", "packet", "packets",
    "bag", "bags", "bottle", "bottles", "carton", "cartons", "cup", "cups", "can", "cans", "tin", "tins", "tub", "tubs", "jar", "jars",
    "box", "boxes", "bar", "bars", "block", "blocks", "loaf", "loaves", "bunch", "bunches", "roll", "rolls", "refill", "refills",
    "pouch", "pouches", "sachet", "sachets", "tube", "tubes", "spray", "sprays", "piece", "pieces", "pcs", "count", "small", "large",
    "medium", "big", "little", "plain", "unsalted", "salted", "unsweetened", "sweetened", "organic", "certified", "spicy", "sweet", "vegan",
    "vegetarian", "nonvegetarian", "full", "fat", "free", "fresh", "each", "instead", "sorry",
)
private val CORRECTION_PLURALS = mapOf(
    "yoghurts" to "yogurt", "yoghurt" to "yogurt", "yogurts" to "yogurt", "eggs" to "egg", "bananas" to "banana", "apples" to "apple",
    "oranges" to "orange", "tomatoes" to "tomato", "onions" to "onion", "potatoes" to "potato", "lemons" to "lemon",
    "biscuits" to "biscuit", "chocolates" to "chocolate", "lentils" to "lentil", "samosas" to "samosa", "strawberries" to "strawberry",
    "blueberries" to "blueberry", "mangoes" to "mango",
)
private fun correctionWords(text: String): Set<String> = Regex("[\\p{L}]+")
    .findAll(java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC).lowercase(java.util.Locale.ROOT))
    .map { CORRECTION_PLURALS[it.value] ?: it.value }.filter { it != "the" }.toSet()
private val CORRECTION_PROTECTED_PHRASES = Regex(
    "\\b(?:no[\\s-]+added[\\s-]+(?:sugar|salt)|sugar[\\s-]*free|zero[\\s-]+sugar|" +
        "low[\\s-]+(?:fat|sugar)|high[\\s-]+(?:fibre|fiber|protein)|" +
        "gluten[\\s-]*free|lactose[\\s-]*free|pulp[\\s-]*free|" +
        "ready[\\s-]+(?:made|to[\\s-]+(?:eat|drink|cook)))\\b", RegexOption.IGNORE_CASE,
)
internal fun correctionAnchors(text: String): Set<String> = correctionWords(CORRECTION_PROTECTED_PHRASES.replace(
    java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC), " "),
).filterNot { it in CORRECTION_IGNORED_ANCHORS }.toSet()

private fun requireRecalledCorrectionAmount(
    sourceText: String,
    newAmountEnd: Int,
    oldValue: BigDecimal,
    oldUnit: String,
    newUnit: String,
) {
    val newTail = when (newUnit) {
        "g" -> "(?:g|gm|gms|grams?)"
        "kg" -> "(?:kg|kgs|kilos?|kilograms?|$GROCERY_INTENT_KEJI_PATTERN)"
        "ml" -> "(?:ml|millilitres?|milliliters?)"
        "l" -> "(?:l|ltr|litres?|liters?)"
        "count", "pack" -> "(?:packs?|packets?|bags?|bottles?|cartons?|cups?|cans?|tins?|tubs?|jars?|box(?:es)?|bars?|blocks?|pouch(?:es)?|sachets?|tubes?|loaf|loaves|refills?|rolls?|sprays?|bunch(?:es)?)?"
        "piece" -> "(?:pieces?|pcs|eggs?)?"
        else -> error("Invalid correction unit")
    }
    val oldTail = when (oldUnit) {
        "g" -> "(?:g|gm|gms|grams?)"
        "kg" -> "(?:kg|kgs|kilos?|kilograms?|$GROCERY_INTENT_KEJI_PATTERN)"
        "ml" -> "(?:ml|millilitres?|milliliters?)"
        "l" -> "(?:l|ltr|litres?|liters?)"
        "count" -> ""
        "pack" -> "(?:packs?|packets?)?"
        "piece" -> "(?:pieces?|pcs)?"
        else -> error("Invalid correction unit")
    }
    val suffix = sourceText.substring(newAmountEnd).trim().trimEnd { it.isWhitespace() || it in ",.!?" }.trim()
    val match = requireNotNull(Regex(
        "(.*?)\\binstead\\s+of\\s+(.+?)\\s*$oldTail\\s*",
        RegexOption.IGNORE_CASE,
    ).matchEntire(suffix)) { "Invalid recalled correction amount" }
    require(Regex("\\s*$newTail\\s*", RegexOption.IGNORE_CASE).matches(match.groupValues[1]))
    val recalled = parseIntentCorrectionAmount(match.groupValues[2])
    require(recalled != null && recalled.compareTo(oldValue) == 0)
}

// Reviewed whole-produce nouns only; explicit plurals/spellings, not translations.
// Local tooling compares both literal tables to the Python runtime tables.
internal const val NAMED_PIECE_FRUITS = "apple apples|banana bananas|orange oranges|mango mangoes mangos|pomegranate pomegranates|lemon lemons|lime limes|pear pears|guava guavas|kiwi kiwis|coconut coconuts|papaya papayas|watermelon watermelons|seb|kela kele|santra santre|aam|anaar anar|nimbu neembu|nariyal|papita|tarbooz|amrood"
internal const val NAMED_PIECE_VEGETABLES = "onion onions|potato potatoes|tomato tomatoes|carrot carrots|cucumber cucumbers|capsicum capsicums|cabbage cabbages|cauliflower cauliflowers|pyaz pyaaz|aloo aaloo|tamatar tamaatar|gajar gaajar|kheera khira"
private val namedPieceNouns = listOf(NAMED_PIECE_FRUITS to IntentProduceCategory.FRUITS,
    NAMED_PIECE_VEGETABLES to IntentProduceCategory.VEGETABLES).flatMap { (lexicon, category) ->
    lexicon.split('|').flatMap { group ->
        val words = group.split(' ')
        words.map { it to (words.first() to category) }
    }
}.toMap()

private fun namedPieceAsciiLower(text: String): String = text.map { if (it in 'A'..'Z') it + ('a' - 'A') else it }.joinToString("")
private fun namedPieceNoun(text: String): String? = namedPieceNouns[namedPieceAsciiLower(text)]?.first

/** Consume the complete independently bound original amount + weight + noun. */
private fun namedPiecePrimaryNoun(primary: String, oldText: String, oldUnit: String): String? {
    val unit = when (oldUnit) {
        "g" -> "(?:g|gm|gms|grams?)"
        "kg" -> "(?:kg|kgs|kilos?|kilograms?|$GROCERY_INTENT_KEJI_PATTERN)"
        else -> return null
    }
    val amount = Regex.escape(namedPieceAsciiLower(oldText))
    val match = Regex("[ \\t]*(?:$amount[ \\t]*$unit[ \\t]+([a-z]+)|([a-z]+)[ \\t]+$amount[ \\t]*$unit)[ \\t]*")
        .matchEntire(namedPieceAsciiLower(primary)) ?: return null
    return namedPieceNoun(match.groupValues[1].ifEmpty { match.groupValues[2] })
}

private fun trimHindiCorrectionAction(after: String): String {
    val action = "(?:kar[ \\t]+(?:do|dena)|karna|rakh[ \\t]+do|rakhna|rakho|lena|le[ \\t]+lo|chahiye)"
    var suffix = after.trim(' ', '\t')
    Regex("(?:^|[ \\t])$action$", RegexOption.IGNORE_CASE).find(suffix)?.let {
        suffix = suffix.substring(0, it.range.first).trimEnd(' ', '\t')
    }
    Regex("(?:^|[ \\t])hi$", RegexOption.IGNORE_CASE).find(suffix)?.let {
        suffix = suffix.substring(0, it.range.first).trimEnd(' ', '\t')
    }
    return suffix
}

/** Named old-N-not-new-M contrast only; all normal target, form and amount checks remain. */
private fun hindiRecalledCorrectionTarget(
    before: String, after: String, oldValue: BigDecimal, oldUnit: String, newUnit: String,
    namedNoun: String? = null, newValue: BigDecimal? = null,
): String? {
    val contrast = Regex(
        "(?:(?:actually|sorry)[ \\t]+)?(.+?)[ \\t]+(?:nahi|nahin)[ \\t]*,?[ \\t]*",
        RegexOption.IGNORE_CASE,
    ).matchEntire(before) ?: return null
    require('\n' !in before + after && '\r' !in before + after)
    val containers = "(?:packs?|packets?|bags?|bottles?|cartons?|cups?|cans?|tins?|tubs?|jars?|" +
        "box(?:es)?|bars?|blocks?|pouch(?:es)?|sachets?|tubes?|loaf|loaves|refills?|rolls?|sprays?|bunch(?:es)?)"
    fun unitTail(unit: String, recalled: Boolean): String = when (unit) {
        "g" -> "(?:g|gm|gms|grams?)"
        "kg" -> "(?:kg|kgs|kilos?|kilograms?|$GROCERY_INTENT_KEJI_PATTERN)"
        "ml" -> "(?:ml|millilitres?|milliliters?)"
        "l" -> "(?:l|ltr|litres?|liters?)"
        "count" -> if (recalled) "" else "$containers?"
        "pack" -> "$containers?"
        "piece" -> if (!recalled && oldUnit in setOf("g", "kg")) "(?:pieces?|pcs)" else "(?:pieces?|pcs|eggs?)?"
        else -> error("Invalid correction unit")
    }
    val unitSuffix = trimHindiCorrectionAction(after)
    val namedPiece = !Regex(unitTail(newUnit, false), RegexOption.IGNORE_CASE).matches(unitSuffix)
    require(!namedPiece || (namedNoun != null && namedPieceNoun(unitSuffix) == namedNoun &&
        newValue != null && newValue >= BigDecimal.ONE && newValue <= BigDecimal(24))) {
        "Unsupported Hindi correction suffix"
    }
    val recalledClause = contrast.groupValues[1]
    val candidates = Regex("[ \\t]+").findAll(recalledClause).mapNotNull { gap ->
        val target = recalledClause.substring(0, gap.range.first)
        if (!Regex("[\\p{L}]+(?:[ '\\-]+[\\p{L}]+)*").matches(target)) return@mapNotNull null
        val recalled = Regex("(.+?)[ \\t]*" + unitTail(oldUnit, true), RegexOption.IGNORE_CASE)
            .matchEntire(recalledClause.substring(gap.range.last + 1)) ?: return@mapNotNull null
        val value = parseIntentCorrectionAmount(recalled.groupValues[1]) ?: return@mapNotNull null
        target.takeIf { value.compareTo(oldValue) == 0 }
    }.toList()
    require(candidates.size == 1) { "Unproven recalled Hindi correction amount" }
    require(!namedPiece || namedPieceNoun(candidates.single()) == namedNoun) { "Unproven named piece target" }
    return candidates.single()
}

private val physicalCorrectionUnits = mapOf(
    "g" to "(?:g|gm|gms|grams?)", "kg" to "(?:kg|kgs|kilos?|kilograms?|$GROCERY_INTENT_KEJI_PATTERN)",
    "ml" to "(?:ml|millilitres?|milliliters?)", "l" to "(?:l|ltr|litres?|liters?)",
)
private const val HINDI_NUMERIC_CHANGE = "(?:kar[ \\t]+(?:do|dena)|karna|rakh[ \\t]+do|rakhna|rakho)"

private fun correctionUnitTail(unit: String): String = physicalCorrectionUnits[unit] ?: when (unit) {
    "piece" -> "(?:pieces?|pcs|eggs?)?"
    "count", "pack" -> "(?:${CORRECTION_CONTAINERS.pattern.removePrefix("\\b").removeSuffix("\\b")})?"
    else -> error("Invalid correction unit")
}

/** One clause parser for wire and target checks. Numeric edits cannot infer a new retail form. */
private fun hindiNumericCorrectionTarget(
    before: String, after: String, kind: String, item: GroceryIntentReviewV2Item,
    primary: String, correction: String, oldValue: BigDecimal, oldUnit: String, newUnit: String,
    newTail: String, roleChanged: Boolean, newValue: BigDecimal,
): String? {
    if (kind == "quantity") {
        val namedNoun = if (roleChanged && newUnit == "piece")
            namedPiecePrimaryNoun(primary, item.quantityText ?: "", oldUnit) else null
        hindiRecalledCorrectionTarget(before, after, oldValue, oldUnit, newUnit,
            namedNoun, newValue)?.let { return it }
        if (roleChanged) return null
        val named = Regex("(.+?)[ \\t]+ko[ \\t]+", RegexOption.IGNORE_CASE).matchEntire(before) ?: return null
        require(item.packValue == null && item.packaging == null) { "Unproven correction quantity role" }
        val action = requireNotNull(Regex("(?:^|[ \\t])$HINDI_NUMERIC_CHANGE$", RegexOption.IGNORE_CASE).find(after))
        val unitSuffix = after.substring(0, action.range.first).trimEnd(' ', '\t')
            .replace(Regex("(?:^|[ \\t])hi$", RegexOption.IGNORE_CASE), "").trimEnd(' ', '\t')
        require(Regex(newTail, RegexOption.IGNORE_CASE).matches(unitSuffix)) { "Unsupported Hindi correction suffix" }
        return named.groupValues[1]
    }
    val contrast = Regex(
        "(?:(?:actually|sorry)[ \\t]+)?(.+?)[ \\t]+(?:nahi|nahin|ki[ \\t]+jagah|ke[ \\t]+bajay)[ \\t]*,?[ \\t]*",
        RegexOption.IGNORE_CASE,
    ).matchEntire(before) ?: return null
    require('\n' !in before + after && '\r' !in before + after)
    require(oldUnit in physicalCorrectionUnits && newUnit in physicalCorrectionUnits)
    require(item.quantity == null || item.quantityUnit != oldUnit || item.quantity.compareTo(oldValue) != 0) {
        "Ambiguous quantity and pack-size correction"
    }
    var suffix = after.trim(' ', '\t')
    Regex("(?:^|[ \\t])(?:$HINDI_NUMERIC_CHANGE|lena|le[ \\t]+lo|chahiye)$", RegexOption.IGNORE_CASE).find(suffix)?.let {
        suffix = suffix.substring(0, it.range.first).trimEnd(' ', '\t')
    }
    suffix = suffix.replace(Regex("(?:^|[ \\t])hi$", RegexOption.IGNORE_CASE), "").trimEnd(' ', '\t')
    val containers = CORRECTION_CONTAINERS.pattern.removePrefix("\\b").removeSuffix("\\b")
    val tail = requireNotNull(Regex(
        physicalCorrectionUnits.getValue(newUnit) + "[ \\t]+(?:(?:ka|ke)[ \\t]+)?(?:(?<count>.+?)[ \\t]+)?$containers",
        RegexOption.IGNORE_CASE,
    ).matchEntire(suffix)) { "Unsupported Hindi pack-size suffix" }
    requireUnchangedCorrectionContainer(item.packaging, primary, correction)
    require(item.quantity != null && item.quantityUnit in setOf("pack", "count")) {
        "Unproven original pack count"
    }
    require(tail.groups["count"] != null || item.quantity.compareTo(BigDecimal.ONE) == 0) {
        "Multi-pack size correction needs a repeated count"
    }
    tail.groups["count"]?.value?.let { repeated ->
        require(item.quantity != null && item.quantityUnit in setOf("pack", "count"))
        val count = requireNotNull(parseIntentCorrectionAmount(repeated))
        require(count.compareTo(item.quantity) == 0) { "Repeated correction count differs" }
        val quantityText = requireNotNull(item.quantityText)
        val originalCount = requireNotNull(parseIntentCorrectionAmount(quantityText))
        require(originalCount.compareTo(count) == 0)
        // Full original numeric ownership is independently checked before review.
        require(quantityText in primary)
    }
    val recalledClause = contrast.groupValues[1]
    val candidates = Regex("[ \\t]+").findAll(recalledClause).mapNotNull { gap ->
        val target = recalledClause.substring(0, gap.range.first)
        if (!Regex("[\\p{L}]+(?:[ '\\-]+[\\p{L}]+)*").matches(target)) return@mapNotNull null
        val recalled = recalledClause.substring(gap.range.last + 1)
        val explicit = Regex("(.+?)[ \\t]*" + physicalCorrectionUnits.getValue(oldUnit), RegexOption.IGNORE_CASE)
            .matchEntire(recalled)
        val amount = parseIntentCorrectionAmount(explicit?.groupValues?.get(1) ?: recalled) ?: return@mapNotNull null
        if (amount.compareTo(oldValue) != 0) return@mapNotNull null
        if (explicit == null && (item.quantity == null || item.quantity.compareTo(amount) == 0)) return@mapNotNull null
        target
    }.toList()
    require(candidates.size == 1) { "Unproven recalled Hindi pack-size amount" }
    return candidates.single()
}

internal data class GroceryIntentReviewV2Item(
    val name: String,
    val quantity: BigDecimal?,
    val quantityUnit: String,
    val packValue: BigDecimal?,
    val packUnit: String,
    val packaging: String?,
    val required: List<String>,
    val excluded: List<String>,
    val sourceText: String,
    val supportingText: List<GroceryIntentReviewV2SupportText>,
    val needsClarification: Boolean,
    val quantityText: String?,
    val packText: String?,
) {
    companion object {
        private val keys = setOf(
            "name", "quantity", "quantity_unit", "pack_value", "pack_unit", "packaging", "required", "excluded",
            "source_text", "supporting_text", "needs_clarification", "quantity_text", "pack_text",
        )
        private val quantityUnits = setOf("count", "pack", "piece", "g", "kg", "ml", "l", "unspecified")
        private val packUnits = setOf("piece", "g", "kg", "ml", "l", "unspecified")
        fun parse(obj: Map<String, JsonValue>, instruction: String): GroceryIntentReviewV2Item {
            obj.requireKeys(keys)
            val name = obj.string("name").singleLineText("name", 1, 200)
            val quantity = obj.nullableNumber("quantity")
            val quantityUnit = obj.string("quantity_unit")
            require(quantityUnit in quantityUnits)
            validateAmount(quantity, quantityUnit, "quantity")
            val packValue = obj.nullableNumber("pack_value")
            val packUnit = obj.string("pack_unit")
            require(packUnit in packUnits)
            validatePack(packValue, packUnit)
            val packaging = obj.nullableString("packaging")
            require(packaging == null || packaging in PACKAGING)
            val required = obj.stringArray("required").map(::validatedTag)
            val excluded = obj.stringArray("excluded").map(::validatedTag)
            require(required.size <= 16 && excluded.size <= 16)
            require(required.map(::normalizeTag).toSet().size == required.size)
            require(excluded.map(::normalizeTag).toSet().size == excluded.size)
            val requiredSet = required.map(::normalizeTag).toSet()
            val excludedSet = excluded.map(::normalizeTag).toSet()
            require(requiredSet.intersect(excludedSet).isEmpty())
            val sourceText = obj.string("source_text").plainText("source_text", 1, 320)
            val supportingText = obj.arrayValue("supporting_text").let { array ->
                require(array.size <= 4)
                array.map { GroceryIntentReviewV2SupportText.parse(it.asObject()) }
            }
            val clarification = obj.boolean("needs_clarification")
            val quantityText = obj.nullableString("quantity_text")?.singleLineText("quantity_text", 1, 64)
            val packText = obj.nullableString("pack_text")?.singleLineText("pack_text", 1, 64)
            require((quantity == null) == (quantityText == null))
            require((packValue == null) == (packText == null))
            require(quantityText == null || sourceText.contains(quantityText))
            require(packText == null || sourceText.contains(packText))
            require(instruction.contains(sourceText))
            return GroceryIntentReviewV2Item(
                name, quantity, quantityUnit, packValue, packUnit, packaging, required, excluded,
                sourceText, supportingText, clarification, quantityText, packText,
            )
        }

        private fun validatedTag(value: String): String = value.also {
            it.singleLineText("modifier", 1, 80)
        }
        private fun normalizeTag(value: String) = value.trim().lowercase()
    }
}

internal data class GroceryIntentReviewV2SupportText(val kind: String, val text: String) {
    companion object {
        fun parse(obj: Map<String, JsonValue>): GroceryIntentReviewV2SupportText {
            obj.requireKeys(setOf("kind", "text"))
            val kind = obj.string("kind")
            require(kind in SUPPORT_KINDS)
            val text = obj.string("text").plainText("support text", 1, 180)
            return GroceryIntentReviewV2SupportText(kind, text)
        }
    }
}

internal data class GroceryIntentReviewV2Evidence(val primary: GroceryIntentReviewV2Quote, val supporting: List<GroceryIntentReviewV2SupportQuote>) {
    companion object {
        fun parse(obj: Map<String, JsonValue>, instruction: String, item: GroceryIntentReviewV2Item): GroceryIntentReviewV2Evidence {
            obj.requireKeys(setOf("primary", "supporting"))
            val primary = GroceryIntentReviewV2Quote.parse(obj.objectValue("primary"), instruction)
            require(primary.text == item.sourceText)
            val supports = obj.arrayValue("supporting")
            require(supports.size == item.supportingText.size)
            val parsed = supports.mapIndexed { index, supportValue ->
                val support = supportValue.asObject()
                support.requireKeys(setOf("kind", "quote"))
                val kind = support.string("kind")
                require(kind == item.supportingText[index].kind)
                val quote = GroceryIntentReviewV2Quote.parse(support.objectValue("quote"), instruction)
                require(quote.text == item.supportingText[index].text)
                GroceryIntentReviewV2SupportQuote(kind, quote)
            }
            return GroceryIntentReviewV2Evidence(primary, parsed.toList())
        }
    }
}

internal data class GroceryIntentReviewV2SupportQuote(val kind: String, val quote: GroceryIntentReviewV2Quote)

internal data class GroceryIntentReviewV2Quote(val text: String, val startUtf16: Int, val endUtf16: Int) {
    fun overlaps(other: GroceryIntentReviewV2Quote): Boolean = startUtf16 < other.endUtf16 && other.startUtf16 < endUtf16

    companion object {
        fun parse(obj: Map<String, JsonValue>, instruction: String, maxLength: Int = 320): GroceryIntentReviewV2Quote {
            obj.requireKeys(setOf("text", "start_utf16", "end_utf16"))
            val text = obj.string("text").plainText("quote", 1, maxLength)
            val start = obj.integer("start_utf16")
            val end = obj.integer("end_utf16")
            require(start >= 0 && end > start && end <= instruction.length)
            require(!splitsSurrogate(instruction, start) && !splitsSurrogate(instruction, end))
            require(instruction.substring(start, end) == text)
            return GroceryIntentReviewV2Quote(text, start, end)
        }

        private fun splitsSurrogate(text: String, index: Int): Boolean =
            index in 1 until text.length && text[index - 1].isHighSurrogate() && text[index].isLowSurrogate()
    }
}

/** Optional negotiated receipt for independently compiled typed ingredient scope. */
internal data class GroceryIntentReviewV2IngredientObligation(
    val source: GroceryIntentReviewV2Quote,
    val memberIndices: List<Int>,
    val predicates: List<IngredientPredicate>,
) {
    override fun toString(): String =
        "GroceryIntentReviewV2IngredientObligation(source=<redacted>, memberIndices=$memberIndices, predicates=<redacted>)"

    companion object {
        fun parse(value: JsonValue, instruction: String): GroceryIntentReviewV2IngredientObligation {
            val obj = value.asObject()
            obj.requireKeys(setOf("source", "member_indices", "predicates"))
            val source = GroceryIntentReviewV2Quote.parse(obj.objectValue("source"), instruction, 6000)
            val memberIndices = obj.arrayValue("member_indices").map { it.asExactInt() }
            require(memberIndices.isNotEmpty() && memberIndices.size <= 50 &&
                memberIndices.distinct().size == memberIndices.size && memberIndices == memberIndices.sorted())
            val predicateValues = obj.arrayValue("predicates")
            require(predicateValues.size in 1..16)
            val predicates = predicateValues.map { raw ->
                val predicate = raw.asObject()
                predicate.requireKeys(setOf("operator", "operand"))
                val operator = when (predicate.string("operator")) {
                    "absent" -> IngredientPredicateOperator.ABSENT
                    "no_addition" -> IngredientPredicateOperator.NO_ADDITION
                    else -> throw IllegalArgumentException("Invalid ingredient predicate operator")
                }
                IngredientPredicate(operator, predicate.string("operand"))
            }
            require(predicates.distinct().size == predicates.size)
            return GroceryIntentReviewV2IngredientObligation(source, memberIndices.toList(), predicates.toList())
        }
    }
}

internal data class GroceryIntentReviewV2BlockedRow(
    val rowIndex: Int,
    val source: GroceryIntentReviewV2Quote?,
    val issueCodes: List<String>,
    val excludedContext: List<GroceryIntentReviewV2Quote> = emptyList(),
) {
    companion object {
        private val codePattern = Regex("[a-z][a-z0-9_]{0,63}")
        fun parse(obj: Map<String, JsonValue>, instruction: String): GroceryIntentReviewV2BlockedRow {
            val keys = setOf("row_index", "source", "issue_codes")
            require(obj.keys == keys || obj.keys == keys + "excluded_context")
            val index = obj.integer("row_index")
            val source = obj.nullableObject("source")?.let { GroceryIntentReviewV2Quote.parse(it, instruction) }
            val codes = obj.stringArray("issue_codes")
            require(codes.isNotEmpty() && codes.size <= 8 && codes.all { codePattern.matches(it) })
            require(codes.toSet().size == codes.size)
            val excludedContext = if ("excluded_context" in obj) obj.arrayValue("excluded_context").let { entries ->
                require(entries.size <= 4)
                entries.map { GroceryIntentReviewV2Quote.parse(it.asObject(), instruction, 180) }
            } else emptyList()
            return GroceryIntentReviewV2BlockedRow(index, source, codes.toList(), excludedContext.toList())
        }
    }
}

private val USAGE_KEYS = setOf("input_tokens", "output_tokens", "total_tokens")
private val SUPPORT_KINDS = setOf("header", "modifier", "correction")
private val AMOUNT_ROLE_STATUSES = setOf("matched", "unresolved", "not_stated")
private val PACKAGING = setOf(
    "pack", "packet", "bag", "bottle", "carton", "tetra_pack", "cup", "can", "tin", "tub", "jar", "box",
    "bar", "block", "loaf", "bunch", "roll", "refill", "pouch", "sachet", "tube", "spray",
)
private val GROUP_AMOUNT_STATUSES = setOf("total_verified", "each_verified", "local_amounts_verified", "amounts_unspecified")
private val GROUP_UNITS = setOf("unspecified", "count", "pack", "piece", "g", "kg", "ml", "l")
private val GROUP_AMOUNT_UNITS = GROUP_UNITS - "unspecified"
private val GROUP_MEMBER_ORIGINS = setOf("local", "each", "not_stated")
private val GROUP_PHYSICAL_UNITS = setOf("g", "kg", "ml", "l")
private val GROUP_GAP_WORDS = setOf("and", "aur", "bhi")
internal val GROUP_NUMERIC_WORDS = setOf(
    "a", "an", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
    "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety", "dozen", "dozens",
    "half", "quarter", "ek", "do", "teen", "char", "chaar", "paanch", "panch", "chhe", "cheh", "saat",
    "aath", "nau", "das", "barah", "dedh", "dhai", "aadha", "adha", "sau", "hazar", "lakh", "crore",
    "paav", "pav", "zero", "hundred", "thousand",
    // Detection only; unsupported amounts are never converted or defaulted.
    "couple", "couples", "pair", "pairs", "few", "several",
    "gyarah", "gyaarah", "terah", "chaudah", "pandrah", "solah", "satrah",
    "atharah", "athara", "unnis", "unnees", "bees", "bis", "tees", "tis",
    "chaalis", "chalis", "pachas", "pachaas", "saath", "sattar", "assi", "nabbe",
    "shunya", "hazaar", "hajaar", "hajar",
    "शून्य", "एक", "दो", "तीन", "चार", "पांच", "पाँच", "छह", "छः", "सात", "आठ", "नौ", "दस",
    "ग्यारह", "बारह", "तेरह", "चौदह", "पंद्रह", "पन्द्रह", "सोलह", "सत्रह", "अठारह", "उन्नीस",
    "बीस", "तीस", "चालीस", "पचास", "साठ", "सत्तर", "अस्सी", "नब्बे", "सौ", "हजार", "हज़ार",
    "लाख", "करोड़", "आधा", "आधी", "पाव", "डेढ़", "ढाई", "दर्जन", "जोड़ी",
    // Recognize, but never calculate, unsupported compound Hindi quantity modifiers.
    "सवा", "साढ़े", "साढे", "पौने",
)
private val HINDI_REDUPLICATED_NUMBERS = setOf("ek", "do", "teen", "char", "chaar", "paanch", "panch")
private val GROUP_PACKAGING_PATTERNS = mapOf(
    "pack" to Regex("\\bpacks?\\b", RegexOption.IGNORE_CASE),
    "packet" to Regex("\\bpackets?\\b", RegexOption.IGNORE_CASE),
    "bag" to Regex("\\bbags?\\b", RegexOption.IGNORE_CASE),
    "bottle" to Regex("\\bbottles?\\b", RegexOption.IGNORE_CASE),
    "carton" to Regex("\\bcartons?\\b", RegexOption.IGNORE_CASE),
    "tetra_pack" to Regex("\\btetra(?:\\s*[- ]?\\s*)packs?\\b", RegexOption.IGNORE_CASE),
    "cup" to Regex("\\bcups?\\b", RegexOption.IGNORE_CASE),
    "can" to Regex("\\bcans?\\b", RegexOption.IGNORE_CASE),
    "tin" to Regex("\\btins?\\b", RegexOption.IGNORE_CASE),
    "tub" to Regex("\\btubs?\\b", RegexOption.IGNORE_CASE),
    "jar" to Regex("\\bjars?\\b", RegexOption.IGNORE_CASE),
    "box" to Regex("\\bbox(?:es)?\\b", RegexOption.IGNORE_CASE),
    "bar" to Regex("\\bbars?\\b", RegexOption.IGNORE_CASE),
    "block" to Regex("\\bblocks?\\b", RegexOption.IGNORE_CASE),
    "loaf" to Regex("\\bloaves?\\b", RegexOption.IGNORE_CASE),
    "bunch" to Regex("\\bbunch(?:es)?\\b", RegexOption.IGNORE_CASE),
    "roll" to Regex("\\brolls?\\b", RegexOption.IGNORE_CASE),
    "refill" to Regex("\\brefills?\\b", RegexOption.IGNORE_CASE),
    "pouch" to Regex("\\bpouches?\\b", RegexOption.IGNORE_CASE),
    "sachet" to Regex("\\bsachets?\\b", RegexOption.IGNORE_CASE),
    "tube" to Regex("\\btubes?\\b", RegexOption.IGNORE_CASE),
    "spray" to Regex("\\bsprays?\\b", RegexOption.IGNORE_CASE),
)

private fun Map<String, JsonValue>.requireKeys(expected: Set<String>) = require(keys == expected)
private fun Map<String, JsonValue>.value(key: String): JsonValue = getValue(key)
private fun Map<String, JsonValue>.string(key: String): String = value(key).asString()
private fun Map<String, JsonValue>.boolean(key: String): Boolean = value(key).asBoolean()
private fun Map<String, JsonValue>.objectValue(key: String): Map<String, JsonValue> = value(key).asObject()
private fun Map<String, JsonValue>.arrayValue(key: String): List<JsonValue> = value(key).asArray()
private fun JsonValue.asObject(): Map<String, JsonValue> = (this as? JsonValue.Obj)?.members ?: error("Invalid object")
private fun JsonValue.asArray(): List<JsonValue> = (this as? JsonValue.Arr)?.items ?: error("Invalid array")
private fun JsonValue.asString(): String = (this as? JsonValue.Str)?.value ?: error("Invalid string")
private fun JsonValue.asBoolean(): Boolean = (this as? JsonValue.Bool)?.value ?: error("Invalid boolean")
private fun Map<String, JsonValue>.stringArray(key: String): List<String> = arrayValue(key).map { it.asString() }
private fun Map<String, JsonValue>.number(key: String): BigDecimal = value(key).asDecimal()
private fun Map<String, JsonValue>.nullableNumber(key: String): BigDecimal? = value(key).nullableDecimal()
private fun Map<String, JsonValue>.nullableString(key: String): String? = value(key).nullableString()
private fun Map<String, JsonValue>.nullableObject(key: String): Map<String, JsonValue>? = value(key).nullableObject()
private fun JsonValue.nullableDecimal(): BigDecimal? = if (this == JsonValue.Null) null else asDecimal()
private fun JsonValue.nullableString(): String? = if (this == JsonValue.Null) null else asString()
private fun JsonValue.nullableObject(): Map<String, JsonValue>? = if (this == JsonValue.Null) null else asObject()
private fun JsonValue.asDecimal(): BigDecimal = (this as? JsonValue.Num)?.raw?.toBigDecimalOrNull() ?: error("Invalid number")
private fun Map<String, JsonValue>.integer(key: String): Int {
    val value = number(key)
    return try { value.intValueExact() } catch (_: ArithmeticException) { error("Invalid integer field") }
}
private fun JsonValue.asExactInt(): Int = try {
    asDecimal().intValueExact()
} catch (_: ArithmeticException) {
    error("Invalid integer")
}
private fun BigDecimal.finiteDouble(label: String): Double = toDouble().also { require(it.isFinite()) { "Invalid $label" } }

private fun validateAmount(value: BigDecimal?, unit: String, field: String) {
    if (value == null) {
        require(unit == "unspecified") { "Missing $field must be unspecified" }
        return
    }
    require(unit != "unspecified" && value > BigDecimal.ZERO && value <= BigDecimal("1000000"))
    when (unit) {
        "count", "pack" -> require(value.stripTrailingZeros().scale() <= 0 && value <= BigDecimal(20))
        "piece" -> require(value.stripTrailingZeros().scale() <= 0 && value <= BigDecimal(1000))
        "g", "kg", "ml", "l" -> Unit
        else -> error("Invalid amount unit")
    }
}

private fun validatePack(value: BigDecimal?, unit: String) {
    if (value == null) {
        require(unit == "unspecified") { "Missing pack size must be unspecified" }
        return
    }
    require(unit != "unspecified" && value > BigDecimal.ZERO && value <= BigDecimal("1000000"))
    if (unit == "piece") require(value.stripTrailingZeros().scale() <= 0)
}

private fun String.bounded(label: String, min: Int, max: Int): String = singleLineText(label, min, max)

private fun String.plainText(label: String, min: Int, max: Int): String = also {
    require(it.isNotBlank() && it.length in min..max && it.none { char ->
        char.isISOControl() && char !in "\n\r\t"
    } && !it.hasUnpairedSurrogate()) { "Invalid $label" }
}

private fun String.singleLineText(label: String, min: Int, max: Int): String = also {
    require(it.isNotBlank() && it.length in min..max && it.none(Char::isISOControl) && !it.hasUnpairedSurrogate()) {
        "Invalid $label"
    }
}

private fun String.hasUnpairedSurrogate(): Boolean {
    var index = 0
    while (index < length) {
        val char = this[index]
        if (char.isHighSurrogate()) {
            if (index + 1 >= length || !this[index + 1].isLowSurrogate()) return true
            index += 2
        } else {
            if (char.isLowSurrogate()) return true
            index++
        }
    }
    return false
}
