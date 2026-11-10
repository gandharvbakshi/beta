package com.example.beta

/** A source-bound request note. A list purpose grants neither product evidence nor scheduling authority. */
internal data class GroceryIntentReviewV2Constraint(
    val kind: String,
    val source: GroceryIntentReviewV2Quote,
)

/**
 * Derive only the closed, product-free request grammar mirrored by
 * grocery_intent_constraints.py. Returned quotes are exact UTF-16 source spans.
 */
internal fun deriveFulfillmentConstraints(
    instruction: String,
    primaries: List<GroceryIntentReviewV2Quote>,
    allowListPurpose: Boolean = false,
    allowTrailingFulfillment: Boolean = false,
    allowPurposeGroups: Boolean = false,
): List<GroceryIntentReviewV2Constraint> {
    val notes = deriveLeadingFulfillmentConstraints(instruction, primaries, allowListPurpose, allowPurposeGroups) +
        (if (allowListPurpose && allowPurposeGroups) deriveInteritemHouseholdPurposes(instruction, primaries) else emptyList()) +
        (if (allowTrailingFulfillment) deriveTrailingFulfillmentConstraints(instruction, primaries) else emptyList())
    require(notes.size <= 20) { "Too many review context notes" }
    return notes
}

/** Entire original adjacent-primary gap only; no product or address authority. */
private fun deriveInteritemHouseholdPurposes(
    instruction: String,
    primaries: List<GroceryIntentReviewV2Quote>,
): List<GroceryIntentReviewV2Constraint> {
    if (!originalConstraintPrimaries(instruction, primaries)) return emptyList()
    val ordered = primaries.sortedWith(compareBy<GroceryIntentReviewV2Quote> { it.startUtf16 }.thenBy { it.endUtf16 })
    return ordered.zipWithNext().mapNotNull { (left, right) ->
        val gap = instruction.substring(left.endUtf16, right.startUtf16)
        if (gap.length !in 1..180 || gap.any { it.code > 127 }) return@mapNotNull null
        val match = interitemHouseholdPurposePattern.matchEntire(asciiLower(gap)) ?: return@mapNotNull null
        val captured = match.groups[1] ?: return@mapNotNull null
        val quote = fulfillmentQuote(instruction, left.endUtf16 + captured.range.first,
            left.endUtf16 + captured.range.last + 1)
        if (GroceryIntentConstraintCodec.isValidQuote(quote.text))
            GroceryIntentReviewV2Constraint("list_purpose", quote) else null
    }
}

/** Final original sentence only; never skip an unowned product/correction/condition. */
private fun deriveTrailingFulfillmentConstraints(
    instruction: String,
    primaries: List<GroceryIntentReviewV2Quote>,
): List<GroceryIntentReviewV2Constraint> {
    if (primaries.isEmpty() || primaries.size > 50 || primaries.any {
            it.startUtf16 < 0 || it.startUtf16 >= it.endUtf16 || it.endUtf16 > instruction.length ||
                instruction.substring(it.startUtf16, it.endUtf16) != it.text
        }) return emptyList()
    val ordered = primaries.sortedWith(compareBy<GroceryIntentReviewV2Quote> { it.startUtf16 }.thenBy { it.endUtf16 })
    if (ordered.zipWithNext().any { (left, right) -> left.endUtf16 > right.startUtf16 }) return emptyList()
    val last = ordered.last().endUtf16
    val edge = Regex("[ \\t]*[.;!?][ \\t\\r\\n]*").find(instruction.substring(last))
        ?.takeIf { it.range.first == 0 } ?: return emptyList()
    val quote = fulfillmentQuote(instruction, last + edge.range.last + 1, instruction.length)
    return if (GroceryIntentConstraintCodec.isValidQuote(quote.text) &&
        trailingFulfillmentPattern.matches(asciiLower(quote.text))) {
        listOf(GroceryIntentReviewV2Constraint("fulfillment", quote))
    } else emptyList()
}

private fun deriveLeadingFulfillmentConstraints(
    instruction: String,
    primaries: List<GroceryIntentReviewV2Quote>,
    allowListPurpose: Boolean = false,
    allowPurposeGroups: Boolean = false,
): List<GroceryIntentReviewV2Constraint> {
    if (!originalConstraintPrimaries(instruction, primaries)) return emptyList()
    val first = primaries.minOf { it.startUtf16 }
    // Bind the complete original initial prefix, with or without a closing
    // punctuation mark. This retains timing; it cannot hide a product/amount.
    val initialPrefix = instruction.substring(0, first)
    val initialQuote = fulfillmentQuote(instruction, 0, first)
    val initialClause = initialQuote.text.let {
        if (it.lastOrNull() in ":;.!?".toList()) it.dropLast(1).trimEnd(' ') else it
    }
    if (initialPrefix.length in 1..180 && initialPrefix.all { it.code in 32..126 } &&
        Regex("(?:[ ]+|[:;.!?][ ]*)$").containsMatchIn(initialPrefix) &&
        GroceryIntentConstraintCodec.isValidQuote(initialQuote.text) &&
        groceryIntentInitialRequestClauseKind(initialClause) == "fulfillment"
    ) return listOf(GroceryIntentReviewV2Constraint("fulfillment", initialQuote))
    val sentenceEnd = Regex("[;!?\\r\\n]|\\.(?=\\s|$)")
    val introPattern = fulfillmentIntroPattern()
    // A deadline remains a visible fulfillment request, never a scheduling
    // promise or permission to drop product/amount/diet/availability wording.
    for (marker in Regex("[:：;!?]|\\.(?=\\s|$)").findAll(instruction.substring(0, first))) {
        val prior = sentenceEnd.findAll(instruction.substring(0, marker.range.first)).lastOrNull()
        val requestStart = prior?.range?.last?.plus(1) ?: 0
        val requestEnd = marker.range.last + 1
        val quote = fulfillmentQuote(instruction, requestStart, requestEnd)
        val clause = quote.text.dropLast(1).trimEnd(' ')
        val gap = instruction.substring(requestEnd, first)
        if (allowListPurpose && allowPurposeGroups && requestStart == 0 && clause.isNotEmpty() &&
            GroceryIntentConstraintCodec.isValidQuote(quote.text) && storagePurposePattern.matches(asciiLower(clause)) &&
            groceryIntentDirectListRequestGap(gap) &&
            primaries.none { it.startUtf16 < requestEnd }
        ) return listOf(GroceryIntentReviewV2Constraint("list_purpose", quote))
        // Initial closed purpose request only; never skip an unowned obligation
        // to find the first item. A complete closed section heading may intervene.
        if (allowListPurpose && allowPurposeGroups && requestStart == 0 && clause.isNotEmpty() &&
            GroceryIntentConstraintCodec.isValidQuote(quote.text) && groceryIntentPurposeListRequest(clause) &&
            (gap.all { it in " \t\r\n" } || groceryIntentPurposeGroupHeading(gap.trim(' ', '\t', '\r', '\n')) != null) &&
            primaries.none { it.startUtf16 < requestEnd && requestStart < it.endUtf16 }
        ) return listOf(GroceryIntentReviewV2Constraint("list_purpose", quote))
        if (clause.isNotEmpty() && GroceryIntentConstraintCodec.isValidQuote(quote.text) &&
            (temporalRequestPattern.matches(asciiLower(clause)) ||
                requestStart == 0 && groceryIntentInitialRequestClauseKind(clause) == "fulfillment") &&
            instruction.substring(requestEnd, first).all { it in " \t\r\n" } &&
            primaries.none { it.startUtf16 < requestEnd && requestStart < it.endUtf16 }
        ) return listOf(GroceryIntentReviewV2Constraint(
            "fulfillment", quote,
        ))
    }
    for (marker in Regex("[:：]").findAll(instruction)) {
        val priorEnds = sentenceEnd.findAll(instruction.substring(0, marker.range.first)).toList()
        val start = priorEnds.lastOrNull()?.range?.last?.plus(1) ?: 0
        if (start > first) break
        val quote = fulfillmentQuote(instruction, start, marker.range.last + 1)
        val clause = quote.text.dropLast(1).trimEnd(' ')
        if (clause.isNotEmpty() && GroceryIntentConstraintCodec.isValidQuote(quote.text) &&
            introPattern.matches(asciiLower(clause))
        ) {
            return listOf(GroceryIntentReviewV2Constraint(
                "fulfillment", quote,
            ))
        }
        if (allowListPurpose && clause.isNotEmpty() && GroceryIntentConstraintCodec.isValidQuote(quote.text) &&
            listPurposePattern.matches(asciiLower(clause))
        ) {
            return listOf(GroceryIntentReviewV2Constraint(
                "list_purpose", quote,
            ))
        }
    }

    // Paired Hindi shop-origin/request grammar. Only the prefix is owned;
    // item clauses and any extra condition/action remain independently visible.
    val timedOrigin = Regex("(?:aaj|kal)(?:\\s+(?:subah|shaam))?|subah|shaam")
    val timedOriginPattern = Regex("(?:$timedOrigin)\\s+(?:mandi|bazaar|bazar)\\s+se")
    val prefix = instruction.substring(0, first).trim()
    val prefixQuote = fulfillmentQuote(instruction, 0, first)
    if (GroceryIntentConstraintCodec.isValidQuote(prefixQuote.text) &&
        timedOriginPattern.matches(asciiLower(prefix))
    ) {
        val last = primaries.maxOf { it.endUtf16 }
        val suffix = instruction.substring(last)
        val requestEnd = Regex("\\s+le\\s+(?:aana|aao|aaiyega|aaiye)\\s*[.!]?\\s*\\z")
        if (requestEnd.matches(asciiLower(suffix)) &&
            !sentenceEnd.containsMatchIn(instruction.substring(first, last))
        ) {
            return listOf(GroceryIntentReviewV2Constraint("fulfillment", prefixQuote))
        }
    }
    return emptyList()
}

private fun originalConstraintPrimaries(instruction: String, primaries: List<GroceryIntentReviewV2Quote>): Boolean {
    if (primaries.isEmpty() || primaries.size > 50 || primaries.any {
            it.startUtf16 < 0 || it.startUtf16 >= it.endUtf16 || it.endUtf16 > instruction.length ||
                instruction.substring(it.startUtf16, it.endUtf16) != it.text
        }) return false
    val ordered = primaries.sortedWith(compareBy<GroceryIntentReviewV2Quote> { it.startUtf16 }.thenBy { it.endUtf16 })
    return ordered.zipWithNext().none { (left, right) -> left.endUtf16 > right.startUtf16 }
}

/** Validate both model-declared and object-constructed ownership before masking any text. */
internal fun validateFulfillmentConstraints(
    instruction: String,
    primaries: List<GroceryIntentReviewV2Quote>,
    declared: List<GroceryIntentReviewV2Constraint>,
    occupied: List<GroceryIntentReviewV2Quote>,
): List<GroceryIntentReviewV2Constraint> {
    // Older responses do not advertise the new kind. A declared purpose is
    // independently re-derived, never trusted as a model neutral-text label.
    val expected = deriveFulfillmentConstraints(instruction, primaries,
        allowListPurpose = declared.any { it.kind == "list_purpose" },
        allowPurposeGroups = declared.any { it.kind == "list_purpose" },
        allowTrailingFulfillment = primaries.isNotEmpty() && declared.any {
            it.kind == "fulfillment" && it.source.startUtf16 >= primaries.maxOf { primary -> primary.endUtf16 }
        })
    require(declared == expected) { "Fulfillment constraints differ from independently derived source" }
    require(expected.none { constraint -> occupied.any { constraint.source.overlaps(it) } }) {
        "Fulfillment constraint overlaps already-owned evidence"
    }
    return expected
}

private fun fulfillmentQuote(instruction: String, rawStart: Int, rawEnd: Int): GroceryIntentReviewV2Quote {
    var start = rawStart
    var end = rawEnd
    while (start < end && instruction[start].isWhitespace()) start++
    while (end > start && instruction[end - 1].isWhitespace()) end--
    return GroceryIntentReviewV2Quote(instruction.substring(start, end), start, end)
}

/** Python's re.I | re.ASCII equivalent: fold ASCII letters only. */
private fun asciiLower(value: String): String = buildString(value.length) {
    value.forEach { char -> append(if (char in 'A'..'Z') char + ('a' - 'A') else char) }
}

private val trailingFulfillmentPattern = run {
    val courtesy = "(?:(?:please|kindly)[ ]+)?(?:(?:can|could|would)[ ]+you[ ]+(?:(?:please|kindly)[ ]+)?)?"
    val time = "(?:today|tomorrow(?:\\s+(?:morning|evening))?|this\\s+(?:morning|evening)|tonight)"
    Regex(courtesy + "(?:bring|get|deliver|send)[ ]+" +
        "(?:(?:these|those)(?:[ ]+on[ ]+(?:this|the|my|our)[ ]+list)?|" +
        "everything[ ]+on[ ]+(?:this|the|my|our)[ ]+list)[ ]+" +
        "(?:when[ ]+you[ ]+(?:come(?:[ ]+over)?|can)|$time|(?:by|before)[ ]+(?:the[ ]+weekend|$time))[.!?]?")
}

// Retained purpose only: storage location never supplies a frozen/product form.
private val storagePurposePattern: Regex = run {
    val noun = "(?:freezer|fridge|refrigerator|pantry|cupboard)"
    val place = "(?:the|my|our)[ ]+$noun"
    Regex("(?:i|we)[ ]+(?:need|want)[ ]+to[ ]+(?:stock|restock)[ ]+$place" +
        "(?:[ ]+and[ ]+(?:(?:(?:the|my|our)[ ]+)?$noun|a[ ]+few[ ]+(?:pantry|household|kitchen)[ ]+items))?")
}

private val householdPurposeNotePattern = Regex(
    "(?:for[ ]+(?:the|my|our)[ ]+(?:house|home|household)|ghar[ ]+ke[ ]+liye)[ ]*[;.!?]",
)
private val interitemHouseholdPurposePattern = Regex(
    "[ \\t]+(${householdPurposeNotePattern.pattern})[ \\t]*" +
        "(?:(?:then|and[ \\t]+then|(?:aur[ \\t]+)?(?:phir|fir))[ \\t]+)?",
)

// Positive complete scaffolds only; unknown text is never neutral merely
// because it lacks a known danger word. No amounts or product/diet slots.
private val listPurposePattern: Regex = run {
    val hindi = "(?:chai[ -]nashta|nashta|safai|nahane|roz[ ]+ke[ ]+khane)"
    val english = "(?:tea|snacks|breakfast|lunch|dinner|cleaning|bathing|bathroom|laundry)"
    val englishSequence = "$english(?:(?:[ ]+and[ ]+|[ ]*,[ ]*(?:and[ ]+)?)$english){0,3}"
    val hindiSequence = "$hindi(?:(?:[ ]+aur[ ]+|[ ]*,[ ]*(?:aur[ ]+)?)$hindi){0,3}"
    val englishScaffold = "(?:(?:please|kindly)[ ]+)?(?:write(?:[ ]+down)?|prepare|make)[ ]+" +
        "(?:(?:my|our|the|a)[ ]+)?(?:(?:grocery|shopping)[ ]+)?list[ ]+" +
        "(?:for[ ]+$englishSequence|of[ ]+$englishSequence[ ]+items)(?:[ ]*,[ ]*(?:please|kindly))?"
    Regex("(?:(?:sun|suno|suniye)[ ]*,[ ]*)?(?:(?:bazaar|bazar|mandi)[ ]+se[ ]+)?" +
        "(?:ghar[ ]+ke[ ]+liye[ ]+)?" + hindiSequence + "[ ]+(?:ka|ki)[ ]+(?:list|samaan|maal)" +
        "|$englishScaffold")
}

private val leadingListPurposeIdentityNames = setOf(
    "patti", "pattis", "masala", "masalas", "powder", "powders", "liquid", "liquids",
    "brush", "brushes", "cloth", "cloths", "towel", "towels", "roll", "rolls", "soap", "soaps",
    "cleaner", "cleaners", "leaf", "leaves", "mix", "mixes",
)
private val listPurposePackagingWords = setOf(
    "a", "an", "of", "pack", "packs", "packet", "packets", "box", "boxes", "bottle", "bottles",
    "pouch", "pouches", "bag", "bags", "jar", "jars", "tub", "tubs", "tin", "tins", "carton",
    "cartons", "sachet", "sachets", "tube", "tubes", "piece", "pieces", "pc", "pcs",
    "ka", "ki", "ke", "wala", "wali", "wale", "small", "big", "large", "bada", "badi",
    "chhota", "chhoti",
)

/** Deny only: purpose notes never establish generic identities, even before an inter-item household note. */
internal fun listPurposeIdentityIssue(
    primary: GroceryIntentReviewV2Quote,
    sourceWithoutAmounts: String,
    constraints: List<GroceryIntentReviewV2Constraint>,
): String? {
    val applicablePurpose = constraints.any { it.kind == "list_purpose" &&
        (it.source.endUtf16 <= primary.startUtf16 || householdPurposeNotePattern.matches(asciiLower(it.source.text))) }
    if (!applicablePurpose) return null
    // Stock-condition grammar cannot make a generic head more specific. Keep
    // properties, mask only original verified grammar at unchanged offsets.
    val grammar = intentV2LocalAvailabilityGrammarRanges(primary.text)
    if (grammar.isNotEmpty() && sourceWithoutAmounts.length != primary.text.length)
        return "list_purpose_requires_item_identity"
    val projected = sourceWithoutAmounts.toCharArray()
    grammar.forEach { range -> for (index in range) projected[index] = ' ' }
    // Denial-only projection; Unicode spacing/symbols never exempt a generic
    // English head, and no original source evidence is normalized or removed.
    val tokens = Regex("[a-z]+")
        .findAll(asciiLower(String(projected)))
        .map { it.value }
        .toList()
    if (tokens.isEmpty() || tokens.any { it !in leadingListPurposeIdentityNames && it !in listPurposePackagingWords }) return null
    return "list_purpose_requires_item_identity".takeIf { tokens.any { it in leadingListPurposeIdentityNames } }
}

private fun fulfillmentIntroPattern(): Regex {
    val time = "(?:today|tomorrow(?:\\s+(?:morning|evening))?|this\\s+(?:morning|evening)|tonight)"
    val hindiTime = "(?:(?:aaj|kal)(?:\\s+(?:subah|shaam))?|subah|shaam)"
    val polite = "(?:(?:please|kindly)\\s+)?"
    val list = "(?:(?:my|our|the|these|this)\\s+)?(?:(?:grocery|shopping)\\s+)?(?:list|groceries|items)"
    val beta = "(?:beta\\s*,?\\s*)?"
    val expressions = listOf(
        polite + "(?:get|bring|buy|order|deliver|send)\\s+" + list + "\\s+" + time + "(?:\\s+if\\s+you\\s+can)?",
        "(?:for\\s+)?" + time + "\\s+" + polite + "(?:write|prepare|make)\\s+" + list,
        beta + hindiTime + "\\s+ke\\s+liye\\s+list\\s+likh\\s+(?:lo|lijiye)",
        "(?:(?:beta[ ]*,?[ ]*|(?:please|zara)[ ]+))?aaj[ ]+" +
            "(?:(?:bazaar|bazar|mandi)[ ]+(?:ki|ka)[ ]+)?list[ ]+" +
            "(?:bana[ ]+(?:do|dijiye)|likh[ ]+(?:lo|lijiye))",
        beta + hindiTime + "\\s+ko\\s+aa\\s+(?:sako|sakte\\s+ho)\\s+to\\s+le\\s+(?:aana|aaiyega)",
        "ghar\\s+ka\\s+(?:rashan|ration)\\s+likh\\s+raha\\s+hoon\\s*,\\s*(?:kirane|dukan)\\s+par\\s+jo\\s+mil\\s+(?:jaaye|jaye)",
        polite + "(?:get|bring|buy)\\s+(?:these|the\\s+following)\\s+(?:groceries|items)\\s+(?:if\\s+available|whatever\\s+is\\s+available)",
    )
    return Regex("(?:(?:namaste|hello|hi|arre)[ ]*,[ ]*)?(?:${expressions.joinToString("|")})")
}

private val temporalRequestPattern: Regex = run {
    val time = "(?:today|tomorrow(?:\\s+(?:morning|evening))?|this\\s+(?:morning|evening)|tonight)"
    val list = "(?:(?:(?:my|our|the|these|this)[ ]+)?(?:(?:grocery|shopping)[ ]+)?(?:list|groceries|items|things)|" +
        "a[ ]+few[ ]+(?:(?:grocery|shopping)[ ]+)?(?:groceries|items|things))"
    val courtesy = "(?:(?:please|kindly)[ ]+)?(?:(?:can|could|would)[ ]+you[ ]+(?:(?:please|kindly)[ ]+)?)?"
    Regex("(?:(?:namaste|hello|hi|arre|beta)[ ]*,[ ]*)?" + courtesy +
        "(?:get|bring|buy|order|deliver|send)[ ]+" + list +
        "[ ]+(?:" + time + "|(?:by|before)[ ]+(?:the[ ]+weekend|" + time + "))" +
        "(?:(?:[ ]+|[ ]*,[ ]*)if[ ]+you[ ]+can)?")
}
