package com.example.beta

import java.math.BigDecimal
import java.util.Locale
import com.example.beta.automation.IntentLocalAmount

/** Source-only proof receipt; preparation independently validates final facts and surviving ownership. */
internal data class SameCountRefinementReceipt(
    val rowIndex: Int,
    val source: GroceryIntentReviewV2Quote,
    val previous: GroceryIntentReviewV2Quote,
    val final: GroceryIntentReviewV2Quote,
    val previousAmount: GroceryIntentReviewV2Quote,
    val finalAmount: GroceryIntentReviewV2Quote,
    val quantity: BigDecimal,
    /** Coverage of superseded wording and the cue, never positive product evidence. */
    val superseded: GroceryIntentReviewV2Quote,
    /** Rooted comma-partition component; empty when the source ends at a hard boundary. */
    val scopeMembers: List<Int> = emptyList(),
    /** Conditional ownership of a complete initial editorial envelope. */
    val prefix: GroceryIntentReviewV2Quote? = null,
) {
    val coverageSources: List<GroceryIntentReviewV2Quote>
        get() = listOfNotNull(prefix, superseded)

    override fun toString(): String = "SameCountRefinementReceipt(rowIndex=$rowIndex, source=<redacted>, quantity=<redacted>)"
}

internal class SameCountRefinementError(val code: String) : IllegalArgumentException(code)

private val refinementCue = Regex("[ \\t]*,[ \\t]*(?:sorry(?:[ \\t]*,?[ \\t]+I[ \\t]+mean)?|I[ \\t]+mean|actually)[ \\t]*,?[ \\t]*", RegexOption.IGNORE_CASE)
// Mirrored v1 nominal grammar is ASCII; unsupported scripts stay held.
private val refinementNominal = Regex("[A-Za-z]+(?:['’\\-][A-Za-z]+)*(?:[ \\t]+[A-Za-z]+(?:['’\\-][A-Za-z]+)*)*")
private val refinementWords = Regex("[^\\W\\d_]+|[0-9]+(?:\\.[0-9]+)?")
private val refinementNominalToken = Regex("[\\p{L}\\p{N}]+(?:['’\\-][\\p{L}\\p{N}]+)*")
private val refinementForbidden = "no not nahi nahin never without bina if agar unless or ya instead except only sirf bas all every sab both each same again extra and aur also bhi with contains containing per sorry actually please get buy add order make keep replace change remove previous last usual free when whenever until while once then otherwise either neither jo jab mil available stock alongside besides plus additionally furthermore moreover together saath sath as versus vs cum including includes included comprising comprises consists consisting composed between beside next total altogether kul collectively combined overall cumulative aggregate".split(' ').toSet()
private val refinementForms = "pack packs packet packets bag bags bottle bottles carton cartons cup cups can cans tin tins tub tubs jar jars box boxes bar bars block blocks pouch pouches sachet sachets tube tubes loaf loaves refill refills roll rolls spray sprays bunch bunches piece pieces pc pcs g gm gms gram grams kg kgs kilo kilos kilogram kilograms keji ml millilitre millilitres milliliter milliliters l ltr ltrs litre litres liter liters".split(' ').toSet()
private val refinementRetailForms = buildMap {
    listOf("pack", "packet", "bag", "bottle", "carton", "can", "tin", "tub", "jar",
        "box", "bar", "block", "pouch", "sachet", "tube", "loaf", "roll", "bunch")
        .forEach { put(it, it) }
    listOf("pack", "packet", "bag", "bottle", "carton", "can", "tin", "tub", "jar",
        "bar", "block", "sachet", "tube", "roll")
        .forEach { put("${it}s", it) }
    put("boxes", "box")
    put("pouches", "pouch")
    put("loaves", "loaf")
    put("bunches", "bunch")
}
private val refinementRetailFormPattern = refinementRetailForms.keys.sortedByDescending { it.length }
    .joinToString("|") { Regex.escape(it) }

internal fun refinementRetailContract(
    quantityUnit: String,
    packaging: String?,
    packValue: BigDecimal?,
    packUnit: String,
    packText: String?,
): Boolean {
    if (quantityUnit == "count") {
        return packaging == null && packValue == null && packUnit == "unspecified" && packText == null
    }
    if (quantityUnit != "pack" || packaging == null || packaging !in refinementRetailForms.values) return false
    if (packValue == null) return packUnit == "unspecified" && packText == null
    if (packUnit !in setOf("g", "kg", "ml", "l") || packText.isNullOrEmpty() || packText.length > 64 ||
        packValue.signum() <= 0 || packValue > BigDecimal("1000000")) return false
    return parseIntentCorrectionAmount(packText)?.compareTo(packValue) == 0
}

/**
 * Proves a closed adjacent same-count additive refinement. It never merges rows,
 * mutates quantities, or grants readiness. All offsets are Kotlin UTF-16 offsets.
 */
internal fun proveSameCountRefinement(
    instruction: String,
    rowIndex: Int,
    sourceText: String,
    previousText: String,
    finalText: String,
    previousAmountText: String,
    finalAmountText: String,
    quantity: BigDecimal,
    quantityUnit: String,
    finalName: String,
    originalPrimaries: List<GroceryIntentReviewV2Quote>,
    protected: List<GroceryIntentReviewV2Quote> = emptyList(),
    independentPeerIndices: Set<Int> = emptySet(),
    packaging: String? = null,
    packValue: BigDecimal? = null,
    packUnit: String = "unspecified",
    finalPackText: String? = null,
): SameCountRefinementReceipt {
    if (instruction.length !in 1..6000 || !refinementValidText(instruction) || originalPrimaries.size !in 1..50 ||
        rowIndex !in originalPrimaries.indices || !refinementRetailContract(quantityUnit, packaging, packValue, packUnit, finalPackText) || quantity.signum() <= 0 ||
        quantity.stripTrailingZeros().scale() > 0 || quantity > BigDecimal.valueOf(20) ||
        sourceText.length !in 1..320 || previousText.length !in 1..320 || finalText.length !in 1..320 ||
        previousAmountText.length !in 1..64 || finalAmountText.length !in 1..64 || finalName.length !in 1..200) {
        refinementFail("refinement_contract_invalid")
    }
    val source = bindRefinementQuote(instruction, sourceText, "refinement_source_unproven")
    val previous = bindRefinementQuote(instruction, previousText, "refinement_source_unproven")
    val final = bindRefinementQuote(instruction, finalText, "refinement_source_unproven")
    if ((originalPrimaries + protected).any { !validRefinementQuote(instruction, it) }) refinementFail("refinement_source_unproven")
    if (independentPeerIndices.any { it !in originalPrimaries.indices }) refinementFail("refinement_contract_invalid")
    if (originalPrimaries[rowIndex] != source || previous.startUtf16 != source.startUtf16 ||
        final.endUtf16 != source.endUtf16 || previous.endUtf16 >= final.startUtf16 ||
        !refinementCue.matches(instruction.substring(previous.endUtf16, final.startUtf16))) {
        refinementFail("refinement_adjacency_unproven")
    }
    val prefix = initialRefinementEditorialPrefix(instruction, source, rowIndex, originalPrimaries, protected)
    if (prefix != null && !closedRefinementSourceClause(instruction, source, prefix)) {
        refinementFail("refinement_clause_boundary_unproven")
    }
    val scopeMembers = if (closedRefinementSourceClause(instruction, source, prefix)) emptyList() else
        refinementPartitionScope(instruction, source, rowIndex, originalPrimaries, independentPeerIndices, protected)
    val peers = originalPrimaries.filterIndexed { index, _ -> index != rowIndex }
    if ((peers + protected).any(source::overlaps)) refinementFail("refinement_scope_conflict")
    val old = refinementMention(instruction, previous, previousAmountText, quantity,
        quantityUnit, packaging, packValue, packUnit, finalPackText)
    val new = refinementMention(instruction, final, finalAmountText, quantity,
        quantityUnit, packaging, packValue, packUnit, finalPackText)
    val remaining = new.first.iterator()
    if (old.first.last() != new.first.last() || old.first.any { token -> remaining.asSequence().none { it == token } }) {
        refinementFail("refinement_identity_removed_or_replaced")
    }
    if (finalName.lowercase(Locale.ROOT).split(Regex("[ \\t]+")).filter(String::isNotEmpty) != new.first) {
        refinementFail("refinement_final_identity_not_preserved")
    }
    if (peers.any { peer -> refinementNominalToken.findAll(peer.text.lowercase(Locale.ROOT)).any { it.value == old.first.last() } }) {
        refinementFail("refinement_target_ambiguous")
    }
    return SameCountRefinementReceipt(rowIndex, source, previous, final, old.second, new.second, quantity,
        refinementSlice(instruction, source.startUtf16, final.startUtf16), scopeMembers, prefix)
}

/** Source-only reconstruction; the caller must still validate final facts and surviving ownership. */
internal fun reconstructSameCountRefinement(
    instruction: String,
    rowIndex: Int,
    finalAmountText: String,
    quantity: BigDecimal,
    quantityUnit: String,
    finalName: String,
    originalPrimaries: List<GroceryIntentReviewV2Quote>,
    protected: List<GroceryIntentReviewV2Quote> = emptyList(),
    independentPeerIndices: Set<Int> = emptySet(),
    packaging: String? = null,
    packValue: BigDecimal? = null,
    packUnit: String = "unspecified",
    finalPackText: String? = null,
): SameCountRefinementReceipt {
    if (instruction.length !in 1..6000 || !refinementValidText(instruction) ||
        originalPrimaries.size !in 1..50 || rowIndex !in originalPrimaries.indices ||
        !refinementRetailContract(quantityUnit, packaging, packValue, packUnit, finalPackText) ||
        (originalPrimaries + protected).any { !validRefinementQuote(instruction, it) }) {
        refinementFail("refinement_contract_invalid")
    }
    val final = originalPrimaries[rowIndex]
    var left = refinementClauseStart(instruction, final.startUtf16)
    if (independentPeerIndices.isNotEmpty()) {
        // A peer edge proposes a candidate only; prove the complete original
        // hard-clause partition again after locating the owned comma boundary.
        val priorEnd = originalPrimaries.withIndex()
            .filter { it.index != rowIndex && it.value.endUtf16 in left..final.startUtf16 }
            .maxOfOrNull { it.value.endUtf16 }
        if (priorEnd != null) {
            val delimiter = Regex("[ \\t]*,[ \\t]*").find(instruction.substring(priorEnd, final.startUtf16))
            if (delimiter == null || delimiter.range.first != 0) refinementFail("refinement_partition_gap_unproven")
            left = priorEnd + delimiter.value.length
        }
    }
    while (left < final.startUtf16 && instruction[left] in " \t") left++
    val prefix = instruction.substring(left, final.startUtf16)
    val cue = refinementCue.findAll(prefix).filter { it.range.last + 1 == prefix.length }.singleOrNull()
        ?: refinementFail("refinement_adjacency_unproven")
    var previousEnd = left + cue.range.first
    while (previousEnd > left && instruction[previousEnd - 1] in " \t") previousEnd--
    val starts = mutableListOf(left)
    if (rowIndex == 0 && instruction.substring(0, left).all { it == ' ' || it == '\t' }) {
        Regex("[ \\t]+").findAll(instruction.substring(0, previousEnd)).forEach { whitespace ->
            val candidateStart = whitespace.range.last + 1
            if (instruction.codePointCount(0, candidateStart) in 1..180) starts += candidateStart
        }
    }
    val candidates = starts.distinct().flatMap { start ->
        val candidatePrevious = instruction.substring(start, previousEnd)
        val candidateSource = refinementSlice(instruction, start, final.endUtf16)
        val candidatePrimaries = originalPrimaries.toMutableList().also { it[rowIndex] = candidateSource }
        if (start != left) {
            val editorialPrefix = initialRefinementEditorialPrefix(
                instruction, candidateSource, rowIndex, candidatePrimaries, protected)
            if (editorialPrefix == null) return@flatMap emptyList()
        }
        Regex("[ \\t]+").findAll(candidatePrevious).mapNotNull { space ->
            if (space.range.first !in 1..64) return@mapNotNull null
            try {
                proveSameCountRefinement(instruction, rowIndex, candidateSource.text, candidatePrevious, final.text,
                    candidatePrevious.substring(0, space.range.first), finalAmountText, quantity,
                    quantityUnit, finalName, candidatePrimaries, protected, independentPeerIndices,
                    packaging, packValue, packUnit, finalPackText)
            } catch (_: SameCountRefinementError) {
                null
            }
        }.toList()
    }
    return candidates.singleOrNull() ?: refinementFail("refinement_reconstruction_unproven")
}

private fun refinementClauseStart(instruction: String, start: Int): Int =
    instruction.substring(0, start).indexOfLast { it in ";\r\n" } + 1

private fun refinementPartitionScope(
    instruction: String,
    source: GroceryIntentReviewV2Quote,
    rowIndex: Int,
    primaries: List<GroceryIntentReviewV2Quote>,
    independentPeerIndices: Set<Int>,
    protected: List<GroceryIntentReviewV2Quote>,
): List<Int> {
    val left = refinementClauseStart(instruction, source.startUtf16)
    val next = instruction.indexOfAny(charArrayOf(';', '\r', '\n'), source.endUtf16)
    val right = if (next < 0) instruction.length else next
    val members = primaries.withIndex()
        .filter { it.value.startUtf16 < right && left < it.value.endUtf16 }
        .sortedBy { it.value.startUtf16 }
    if (members.size < 2 || members.none { it.index == rowIndex } ||
        members.any { it.value.startUtf16 < left || it.value.endUtf16 > right } ||
        protected.any { it.startUtf16 < right && left < it.endUtf16 } ||
        instruction.substring(left, members.first().value.startUtf16).trim(' ', '\t').isNotEmpty() ||
        !Regex("[ \\t]*[.!?]?[ \\t]*").matches(instruction.substring(members.last().value.endUtf16, right))) {
        refinementFail("refinement_clause_boundary_unproven")
    }
    if (members.any { member ->
            member.index != rowIndex && (member.index !in independentPeerIndices ||
                intentV2HasCorrectionCue(member.value.text, allowPropertyContrast = false))
        }) refinementFail("refinement_partition_member_unproven")
    members.zipWithNext().forEach { (before, after) ->
        if (before.value.endUtf16 >= after.value.startUtf16 ||
            !Regex("[ \\t]*,[ \\t]*").matches(instruction.substring(before.value.endUtf16, after.value.startUtf16))) {
            refinementFail("refinement_partition_gap_unproven")
        }
    }
    return members.map { it.index }
}

private fun initialRefinementEditorialPrefix(
    instruction: String,
    source: GroceryIntentReviewV2Quote,
    rowIndex: Int,
    primaries: List<GroceryIntentReviewV2Quote>,
    protected: List<GroceryIntentReviewV2Quote>,
): GroceryIntentReviewV2Quote? {
    if (rowIndex != 0 || instruction.codePointCount(0, source.startUtf16) !in 1..180 ||
        instruction.substring(0, source.startUtf16).any { it in ";\r\n" } ||
        primaries.withIndex().any { (index, span) -> index != rowIndex && span.startUtf16 < source.startUtf16 } ||
        protected.any { it.startUtf16 < source.startUtf16 }) return null
    val sourceSpan = GroceryIntentCoverageSpan(source.startUtf16, source.endUtf16)
    val neutral = neutralGroceryIntentContextSpans(instruction, listOf(sourceSpan))
        .filter { it.startUtf16 < source.startUtf16 && it.endUtf16 <= source.startUtf16 }
    val covered = (0 until source.startUtf16).all { index ->
        instruction[index] in " \t" || neutral.any { index in it.startUtf16 until it.endUtf16 }
    }
    return refinementSlice(instruction, 0, source.startUtf16).takeIf { covered && neutral.isNotEmpty() }
}

private fun closedRefinementSourceClause(
    instruction: String,
    source: GroceryIntentReviewV2Quote,
    prefix: GroceryIntentReviewV2Quote? = null,
): Boolean {
    // A peer (including a blocked row) never authorizes cropping containing text.
    val left = refinementClauseStart(instruction, source.startUtf16)
    val next = instruction.indexOfAny(charArrayOf(';', '\r', '\n'), source.endUtf16)
    val right = if (next < 0) instruction.length else next
    val emptyPrefix = instruction.substring(left, source.startUtf16).trim(' ', '\t').isEmpty()
    val provenPrefix = prefix != null && left == 0 && prefix.startUtf16 == 0 && prefix.endUtf16 == source.startUtf16
    return (emptyPrefix || provenPrefix) &&
        Regex("[ \\t]*[.!?]?[ \\t]*").matches(instruction.substring(source.endUtf16, right))
}

private fun refinementMention(
    instruction: String,
    quote: GroceryIntentReviewV2Quote,
    amountText: String,
    quantity: BigDecimal,
    quantityUnit: String,
    packaging: String?,
    packValue: BigDecimal?,
    packUnit: String,
    packText: String?,
): Pair<List<String>, GroceryIntentReviewV2Quote> {
    val amount = parseIntentCorrectionAmount(amountText)
    if (amount == null || amount.compareTo(quantity) != 0 || amount.stripTrailingZeros().scale() > 0 ||
        amount < BigDecimal.ONE || amount > BigDecimal.valueOf(20)) refinementFail("refinement_count_unproven")
    val matches = refinementAmountCandidates(quote.text, amountText)
    val local = matches.singleOrNull() ?: refinementFail("refinement_count_unproven")
    if (local.first != 0) refinementFail("refinement_count_unproven")
    val tail = quote.text.substring(local.second)
    if (tail.isEmpty() || tail[0] !in " \t") refinementFail("refinement_nominal_unproven")
    var nominal = tail.trim(' ', '\t')
    if (quantityUnit == "pack") {
        val form = Regex("^([A-Za-z]+)[ \\t]+(?:of[ \\t]+)?", RegexOption.IGNORE_CASE).find(nominal)
            ?: refinementFail("refinement_retail_form_unproven")
        if (refinementRetailForms[form.groupValues[1].lowercase(Locale.ROOT)] != packaging) {
            refinementFail("refinement_retail_form_unproven")
        }
        nominal = nominal.substring(form.range.last + 1)
        if (packValue != null) {
            val sizeText = requireNotNull(packText)
            val size = Regex("^${Regex.escape(sizeText)}[ \\t]*${Regex.escape(packUnit)}[ \\t]+", RegexOption.IGNORE_CASE)
                .find(nominal) ?: refinementFail("refinement_retail_size_unproven")
            if (parseIntentCorrectionAmount(sizeText)?.compareTo(packValue) != 0 ||
                !physicalUnitFollowsAt(nominal, 0, sizeText, packUnit)) {
                refinementFail("refinement_retail_size_unproven")
            }
            nominal = nominal.substring(size.range.last + 1)
        }
    }
    if (!refinementNominal.matches(nominal) || groceryIntentContainsNumericAtom(nominal)) refinementFail("refinement_nominal_unproven")
    val tokens = nominal.lowercase(Locale.ROOT).split(Regex("[ \\t]+")).filter(String::isNotEmpty)
    if (tokens.isEmpty()) refinementFail("refinement_nominal_unproven")
    val lexicalTokens = refinementWords.findAll(nominal.lowercase(Locale.ROOT)).map { it.value }.toList()
    if (lexicalTokens.any { it in refinementForbidden || it in refinementForms || it in groceryIntentNominalScopeUnsafeWords } ||
        groceryIntentHasNominalClausePredicate(nominal) ||
        intentV2HasCorrectionCue(nominal, allowPropertyContrast = false)) refinementFail("refinement_role_or_polarity_unproven")
    val amountQuote = refinementSlice(instruction, quote.startUtf16, quote.startUtf16 + local.second)
    if (quantityUnit == "pack" && !refinementRetailMentionRoleIsProven(
            quote, amountQuote, amountText, quantity, quantityUnit,
            packaging, packValue, packUnit, packText, nominal)) {
        refinementFail("refinement_retail_role_unproven")
    }
    return tokens to amountQuote
}

/** Apply the existing amount-role and composition guards to each quoted mention independently. */
private fun refinementRetailMentionRoleIsProven(
    quote: GroceryIntentReviewV2Quote,
    amountQuote: GroceryIntentReviewV2Quote,
    amountText: String,
    quantity: BigDecimal,
    quantityUnit: String,
    packaging: String?,
    packValue: BigDecimal?,
    packUnit: String,
    packText: String?,
    sourceNominalName: String,
): Boolean {
    val localItem = GroceryIntentReviewV2Item(
        name = sourceNominalName,
        quantity = quantity,
        quantityUnit = quantityUnit,
        packValue = packValue,
        packUnit = packUnit,
        packaging = packaging,
        required = emptyList(),
        excluded = emptyList(),
        sourceText = quote.text,
        supportingText = emptyList(),
        needsClarification = false,
        quantityText = amountText,
        packText = packText,
    )
    val row = GroceryIntentReviewV2ReadyRow(
        rowIndex = 0,
        item = localItem,
        evidence = GroceryIntentReviewV2Evidence(quote, emptyList()),
        quantityOrigin = "stated",
        amountRoleStatus = "matched",
    )
    val packEvidence = if (packValue == null) true else row.localPackSizeEvidence()?.let { evidence ->
        refinementRetailPackRoleIsClosed(quote.text, evidence, requireNotNull(packText), packUnit)
    } == true
    return amountQuote.text == amountText && amountQuote.startUtf16 == quote.startUtf16 &&
        row.numericOwnershipIssue() == null && row.quantityOmissionIssue() == null &&
        row.localQuantityEvidence()?.startUtf16 == 0 &&
        packEvidence
}

private fun refinementRetailPackRoleIsClosed(
    source: String,
    pack: IntentLocalAmount,
    packText: String,
    packUnit: String,
): Boolean {
    if (pack.source != source || pack.atom != packText || pack.unit != packUnit ||
        pack.startUtf16 < 0 || pack.endUtf16 != pack.startUtf16 + packText.length || pack.endUtf16 > source.length ||
        source.substring(pack.startUtf16, pack.endUtf16) != packText ||
        !physicalUnitFollowsAt(source, pack.startUtf16, packText, packUnit)) return false
    val unitMatches = Regex("(?<![A-Za-z0-9_])${Regex.escape(packText)}[ \\t]*${Regex.escape(packUnit)}\\b",
        RegexOption.IGNORE_CASE).findAll(source).toList()
    if (unitMatches.singleOrNull()?.range?.first != pack.startUtf16) return false
    if (pack.startUtf16 <= 0 || parseIntentCorrectionAmount(packText)?.compareTo(pack.value) != 0) return false
    val before = source.substring(0, pack.startUtf16)
    if (!Regex("\\b(?:$refinementRetailFormPattern)[ \\t]+of[ \\t]*$", RegexOption.IGNORE_CASE).containsMatchIn(before)) {
        return false
    }
    val sizeUnit = Regex("^${Regex.escape(packText)}[ \\t]*${Regex.escape(packUnit)}\\b", RegexOption.IGNORE_CASE)
        .find(source.substring(pack.startUtf16)) ?: return false
    val afterMeasure = source.substring(pack.startUtf16 + sizeUnit.range.last + 1)
    val totalBefore = Regex("\\b(?:total|altogether|kul)[ \\t]*$", RegexOption.IGNORE_CASE).containsMatchIn(before)
    val totalAfter = Regex("^[ \\t]*(?:(?:in)[ \\t]+)?(?:total|altogether|kul)\\b", RegexOption.IGNORE_CASE)
        .containsMatchIn(afterMeasure)
    return !totalBefore && !totalAfter
}

private fun refinementAmountCandidates(text: String, amountText: String): List<Pair<Int, Int>> =
    Regex(Regex.escape(amountText)).findAll(text).filter { match ->
        val before = text.getOrNull(match.range.first - 1)
        val after = text.getOrNull(match.range.last + 1)
        (before == null || !(before.isLetterOrDigit() || before == '_' || Character.getType(before) == Character.NON_SPACING_MARK.toInt())) &&
            (after == null || !(after.isLetterOrDigit() || after == '_' || Character.getType(after) == Character.NON_SPACING_MARK.toInt())) &&
            parseIntentCorrectionAmount(match.value)?.compareTo(parseIntentCorrectionAmount(amountText)) == 0
    }.map { it.range.first to (it.range.last + 1) }.toList()

private fun bindRefinementQuote(instruction: String, text: String, error: String): GroceryIntentReviewV2Quote {
    val start = instruction.indexOf(text)
    if (start < 0 || instruction.indexOf(text, start + 1) >= 0) refinementFail(error)
    val end = start + text.length
    fun word(char: Char) = char.isLetterOrDigit() || char == '_' || Character.getType(char) in setOf(
        Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt())
    if (word(text.first()) && start > 0 && word(instruction[start - 1]) ||
        word(text.last()) && end < instruction.length && word(instruction[end])) refinementFail(error)
    val numericSeparators = ".,/٫٬．，⁄∕"
    if (text.first().isDigit() && start > 0 &&
        (instruction[start - 1] in ".٫．+-−＋－" ||
            instruction[start - 1] in numericSeparators && start > 1 && instruction[start - 2].isDigit())) refinementFail(error)
    if (text.last().isDigit() && end + 1 < instruction.length &&
        instruction[end] in numericSeparators && instruction[end + 1].isDigit()) refinementFail(error)
    return GroceryIntentReviewV2Quote(text, start, start + text.length)
}

private fun refinementValidText(text: String): Boolean = text.indices.all { index ->
    val char = text[index]
    when {
        char.isHighSurrogate() -> text.getOrNull(index + 1)?.isLowSurrogate() == true
        char.isLowSurrogate() -> text.getOrNull(index - 1)?.isHighSurrogate() == true
        Character.isISOControl(char) -> char in "\n\r\t"
        else -> true
    }
}

private fun validRefinementQuote(instruction: String, quote: GroceryIntentReviewV2Quote): Boolean =
    quote.startUtf16 >= 0 && quote.endUtf16 > quote.startUtf16 && quote.endUtf16 <= instruction.length &&
        !(quote.startUtf16 in 1 until instruction.length && instruction[quote.startUtf16 - 1].isHighSurrogate() && instruction[quote.startUtf16].isLowSurrogate()) &&
        !(quote.endUtf16 in 1 until instruction.length && instruction[quote.endUtf16 - 1].isHighSurrogate() && instruction[quote.endUtf16].isLowSurrogate()) &&
        instruction.substring(quote.startUtf16, quote.endUtf16) == quote.text

private fun refinementSlice(instruction: String, start: Int, end: Int) =
    GroceryIntentReviewV2Quote(instruction.substring(start, end), start, end)

private fun refinementFail(code: String): Nothing = throw SameCountRefinementError(code)
