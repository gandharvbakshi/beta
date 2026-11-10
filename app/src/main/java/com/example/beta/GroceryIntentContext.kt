package com.example.beta

private const val POLITE = "(?:(?:please|kindly)\\s+)?(?:(?:can|could|would)\\s+you\\s+(?:(?:please|kindly)\\s+)?)?"

// Shared with parser retrieval and closed-request role classification. Keep
// this request-only: expanding it requires the role-boundary safety tests too.
internal const val GROCERY_FIRST_PERSON_REQUEST = "i\\s+(?:want|need|would\\s+like)"

// Denial-only uncertainty vocabulary shared by source scope and catalogue
// claims. These words cannot establish a firm purchase or package relation.
internal const val GROCERY_UNCERTAIN_SCOPE_WORDS =
    "may|might|maybe|perhaps|possibly|shayad|if|unless|whether|when|whenever|later"
internal val groceryIntentUncertainScope = Regex("\\b(?:$GROCERY_UNCERTAIN_SCOPE_WORDS)\\b", RegexOption.IGNORE_CASE)

/** Candidate only: used while proving, or after proving, a complete each-only group. */
internal fun groceryIntentHeadlessSequencePrefix(
    instruction: String,
    primaries: List<GroceryIntentCoverageSpan>,
    scopeStart: Int,
    firstOwnedStart: Int,
): GroceryIntentCoverageSpan? {
    if (scopeStart < 0 || scopeStart >= firstOwnedStart || firstOwnedStart > instruction.length ||
        firstOwnedStart - scopeStart > 64) return null
    val boundary = HEADLESS_SENTENCE.findAll(instruction.substring(0, firstOwnedStart)).lastOrNull() ?: return null
    if (boundary.range.last + 1 != scopeStart || primaries.none {
            it.startUtf16 >= 0 && it.startUtf16 < it.endUtf16 && it.endUtf16 <= scopeStart
        }) return null
    return GroceryIntentCoverageSpan(scopeStart, firstOwnedStart).takeIf {
        HEADLESS_SEQUENCE.matches(instruction.substring(scopeStart, firstOwnedStart))
    }
}

private val HEADLESS_SENTENCE = Regex("[;!?]|\\.(?=\\s|$)")
private val HEADLESS_SEQUENCE = Regex(
    "[ \\t]*(?:then|and[ \\t]+then|(?:aur[ \\t]+)?(?:phir|fir))[ \\t]*,?[ \\t]*", RegexOption.IGNORE_CASE,
)

/** Safe spans prove amounts or their absence; unstated amounts remain visibly suggested at review. */
internal fun groceryIntentCartRequestPrefix(
    instruction: String,
    primarySpans: List<GroceryIntentCoverageSpan>,
    quantitySafePrimarySpans: List<GroceryIntentCoverageSpan>,
    unstatedPrimarySpans: List<GroceryIntentCoverageSpan> = emptyList(),
): GroceryIntentCoverageSpan? {
    if (primarySpans.isEmpty() || primarySpans.size > 50 || primarySpans.toSet() != quantitySafePrimarySpans.toSet() ||
        !primarySpans.containsAll(unstatedPrimarySpans) ||
        primarySpans.any { it.startUtf16 < 0 || it.startUtf16 >= it.endUtf16 || it.endUtf16 > instruction.length }) return null
    val first = primarySpans.minOf { it.startUtf16 }
    val prefix = instruction.substring(0, first)
    if (prefix.length > 180 || prefix.any { it.code > 0x7f } || !CART_REQUEST_PREFIX.matches(prefix)) return null
    // A colon/semicolon may scope an approximate amount over an unquantified list.
    if (unstatedPrimarySpans.isNotEmpty() && !Regex("[.!?]\\s+$").containsMatchIn(prefix)) return null
    return GroceryIntentCoverageSpan(0, first)
}

private val CART_REQUEST_PREFIX = Regex(
    "[ \\t]*(?:(?:okay|ok|(?:namaste|namaskar|hello|hi)(?:\\s+(?:beta|bhaiya|ji))?|haan\\s+ji|haan[ \\t]+beta|" +
        "(?:arre\\s+)?(?:bhai|bhaiya)(?:\\s+ji)?|beta)[ \\t]*,[ \\t]*)?" +
        "(?:(?:(?:(?:thoda|zara|please)[ \\t]+)?" +
        "(?:(?:ye|yeh)[ \\t]+(?:saman|samaan|saamaan|sab|cheezen)[ \\t]+)?" +
        "cart[ \\t]+(?:mein|me)[ \\t]+" +
        "(?:daal[ \\t]+(?:do|dijiye)|dal[ \\t]+(?:do|dijiye)|daalo)" +
        "(?:[ \\t]+(?:na|please))?)|" +
        "$POLITE(?:add[ \\t]+to|put[ \\t]+in(?:to)?)[ \\t]+(?:the|my|our)[ \\t]+cart)" +
        "[ \\t]*[.;:!?]\\s*", RegexOption.IGNORE_CASE,
)
private val INITIAL_CART_DESIRE_PREFIX = Regex(
    "^[ \\t]*(?:haan[ \\t]*,[ \\t]*)?mujhe[ \\t]+cart[ \\t]+(?:mein|me)[ \\t]+" +
        "daalna[ \\t]+hai[ \\t]*[:.!?;][ \\t]*", RegexOption.IGNORE_CASE,
)

/**
 * Finds complete non-product clauses or request shells beside a bound item's edge.
 * Spans use original UTF-16 offsets; complete clauses include their closing separator.
 * This is not a general intent, stopword, quantity, or product parser.
 */
internal fun neutralGroceryIntentContextSpans(
    instruction: String,
    primarySpans: List<GroceryIntentCoverageSpan> = emptyList(),
): List<GroceryIntentCoverageSpan> {
    if (instruction.isEmpty()) return emptyList()

    val spans = mutableListOf<GroceryIntentCoverageSpan>()
    val suffixes = hindiRequestSuffixSpans(instruction, primarySpans)
    var clauseStart = 0
    val boundaries = CLAUSE_BOUNDARY.findAll(instruction).toList()
    boundaries.forEachIndexed { index, boundary ->
        val following = boundaries.getOrNull(index + 1)
        val adjacentPrelude = isAdjacentNeutralPrelude(
            instruction, clauseStart, boundary.range.first, boundary.range.last + 1,
            following?.range?.first, following?.value, primarySpans,
        )
        addNeutralClauseSpan(
            instruction,
            clauseStart,
            boundary.range.first,
            boundary.range.last + 1,
            isHeadingBoundary(boundary.value),
            primarySpans,
            suffixes,
            adjacentPrelude,
            spans,
        )
        clauseStart = boundary.range.last + 1
    }
    addNeutralClauseSpan(instruction, clauseStart, instruction.length, instruction.length, false,
        primarySpans, suffixes, false, spans)
    val wrapped = politeIntroductionSpans(instruction, primarySpans)
    val combined = spans.filterNot { span -> wrapped.any { intro ->
        intro.startUtf16 <= span.startUtf16 && span.endUtf16 <= intro.endUtf16
    } } + wrapped + suffixes + listSequenceSpans(instruction, primarySpans) +
        listTransitionSpans(instruction, primarySpans)
    val initialRequest = initialListRequestPrefix(instruction, primarySpans)?.takeIf { prefix ->
        (prefix.startUtf16 until prefix.endUtf16).any { index ->
            instruction[index] !in " \t" && combined.none { index in it.startUtf16 until it.endUtf16 }
        }
    }
    return (combined + listOfNotNull(initialCartDesirePrefix(instruction, primarySpans)) +
        listOfNotNull(initialRequest) +
        listOfNotNull(editorialPlacementSpan(instruction, primarySpans)) +
        listOfNotNull(editorialSectionOrderSpan(instruction, primarySpans)) +
        listOfNotNull(groceryIntentAssistantInvocation(instruction, primarySpans, combined)) +
        listOfNotNull(leadingSpokenAddressSpan(instruction, primarySpans, combined)) +
        listOfNotNull(selfAgePrefixSpan(instruction, primarySpans, combined)))
        .sortedWith(compareBy<GroceryIntentCoverageSpan> { it.startUtf16 }.thenBy { it.endUtf16 })
}

/** One original request envelope. No missing or overlapping item ownership. */
private fun initialListRequestPrefix(
    instruction: String,
    primaries: List<GroceryIntentCoverageSpan>,
): GroceryIntentCoverageSpan? {
    if (primaries.isEmpty() || primaries.size > 50 || primaries.any {
            it.startUtf16 < 0 || it.startUtf16 >= it.endUtf16 || it.endUtf16 > instruction.length
        }) return null
    val ordered = primaries.sortedBy { it.startUtf16 }
    if (ordered.zipWithNext().any { (left, right) -> left.endUtf16 > right.startUtf16 }) return null
    val first = ordered.first().startUtf16
    val prefix = instruction.substring(0, first)
    if (prefix.length !in 1..MAX_NEUTRAL_CLAUSE_CODE_POINTS ||
        prefix.any { (it != '\t' && it.code < 32) || it.code == 127 }) return null
    // Unicode inventory separators belong only to this complete envelope;
    // existing grammars keep their original ASCII boundary.
    if (prefix.all { it == '\t' || it.code in 32..126 || it in "\u2014\u2013\u2012" } &&
        INITIAL_EDITORIAL_ENVELOPE.matches(prefix.trimStart(' ', '\t'))) {
        return GroceryIntentCoverageSpan(0, first)
    }
    if (prefix.any { it.code > 126 } ||
        !(INITIAL_LIST_REQUEST.matches(prefix.trimStart(' ', '\t')) ||
            INITIAL_LIST_NARRATION.matches(prefix.trimStart(' ', '\t')) ||
            INITIAL_SHOPPING_FRAME.matches(prefix.trimStart(' ', '\t')) ||
            neutralInitialRequestFrame(prefix.trimStart(' ', '\t')))) return null
    return GroceryIntentCoverageSpan(0, first)
}

/** One complete ORIGINAL initial clause. Callers prove its edge and adjacent primary.
 * Time-bearing requests must remain fulfillment notes, never neutral filler.
 */
internal fun groceryIntentInitialRequestClauseKind(clause: String): String? {
    if (clause.length !in 1..180 || clause.any { it.code !in 32..126 }) return null
    if (TIMED_EDITORIAL_FRAME.matches(clause)) return "fulfillment"
    for (pattern in listOf(FRAME_ENGLISH, FRAME_HINDI)) {
        val match = pattern.matchEntire(clause) ?: continue
        val times = listOfNotNull(match.groups["before"], match.groups["after"]) +
            (if (pattern === FRAME_ENGLISH) listOfNotNull(match.groups["shopping"]) else emptyList())
        if (times.size <= 1) return if (times.isEmpty()) "neutral" else "fulfillment"
    }
    return null
}

private fun neutralInitialRequestFrame(prefix: String): Boolean {
    val closed = Regex("(?<clause>.+?)[ ]*[:;.!?][ ]*").matchEntire(prefix) ?: return false
    return groceryIntentInitialRequestClauseKind(closed.groups["clause"]!!.value) == "neutral"
}

/** Complete adjacent request only; no product-bearing, conditional or delivery gap. */
internal fun groceryIntentDirectListRequestGap(text: String): Boolean =
    text.length in 1..90 && text.all { it.code in 32..126 } && DIRECT_LIST_REQUEST.matches(text)

/** A positively marked request at the beginning of the ORIGINAL utterance, independent of model quotes. */
internal fun groceryIntentMarkedRequestEnd(original: String): Int? {
    if (original.substringBefore(' ').lowercase(java.util.Locale.ROOT) !in setOf("please", "kindly", "can", "could", "would")) return null
    val frame = DIRECT_LIST_REQUEST.matchAt(original.take(91), 0)?.value ?: return null
    return frame.length.takeIf { it < original.length && original[it] != ' ' &&
        groceryIntentDirectListRequestGap(frame) && DIRECT_LIST_REQUEST.matchAt(original, it) == null }
}

/** Syntax classification only, not source ownership or semantic permission.
 * Reuse the request-only branches; broader neutral coverage also includes
 * purpose, age, sourcing and timing and must not supply this classification. */
internal fun groceryIntentClosedRequestShell(text: String): Boolean =
    groceryIntentDirectListRequestGap(text) ||
        (text.length in 1..90 && text.all { it.code in 32..126 } &&
            FIRST_PERSON_REQUEST.matches(text.trim(' ')))

/** Classification of a complete independently compiled suffix, never isolated role text.
 * Conditional courtesy and list-writing branches are deliberately not purchase requests. */
internal fun groceryIntentClosedPurchaseSuffix(text: String): Boolean {
    val clause = text.trim(' ', '\t', '\r', '\n')
    val core = if (clause.lastOrNull() in listOf(',', ';', '.', '!', '?'))
        clause.dropLast(1).trimEnd(' ', '\t') else clause
    return core.length in 1..MAX_NEUTRAL_CLAUSE_CODE_POINTS &&
        core.all { it == '\t' || it.code in 32..126 } && CLOSED_HINDI_PURCHASE_SUFFIX.matches(core)
}

private fun editorialPlacementSpan(
    instruction: String,
    primaries: List<GroceryIntentCoverageSpan>,
): GroceryIntentCoverageSpan? {
    if (primaries.isEmpty() || primaries.size > 50 || primaries.any {
            it.startUtf16 < 0 || it.startUtf16 >= it.endUtf16 || it.endUtf16 > instruction.length
        }) return null
    val ordered = primaries.sortedWith(compareBy<GroceryIntentCoverageSpan> { it.startUtf16 }.thenBy { it.endUtf16 })
    if (ordered.zipWithNext().any { (left, right) -> left.endUtf16 > right.startUtf16 }) return null
    val first = ordered.first().startUtf16
    val prefix = instruction.substring(0, first)
    if (prefix.length !in 1..180 || prefix.any { it.code !in 32..126 } ||
        !EDITORIAL_PLACEMENT_PREFIX.matches(prefix)) return null
    return GroceryIntentCoverageSpan(0, first)
}

/** Past-tense document order can refer to retained ORIGINAL section headings.
 * Never erase a heading/body, infer its meaning, or grant a proposed group.
 * Independent complete group/category validation remains required afterwards.
 */
private fun editorialSectionOrderSpan(
    instruction: String,
    primaries: List<GroceryIntentCoverageSpan>,
): GroceryIntentCoverageSpan? {
    if (primaries.size !in 2..50 || primaries.any {
            it.startUtf16 < 0 || it.startUtf16 >= it.endUtf16 || it.endUtf16 > instruction.length
        }) return null
    val ordered = primaries.sortedWith(compareBy<GroceryIntentCoverageSpan> { it.startUtf16 }.thenBy { it.endUtf16 })
    if (ordered.zipWithNext().any { (left, right) -> left.endUtf16 > right.startUtf16 }) return null
    val intro = EDITORIAL_SECTION_ORDER.find(instruction)?.takeIf { it.range.first == 0 } ?: return null
    val introEnd = intro.range.last + 1
    if (introEnd > 180 || intro.value.any { it.code > 127 } || ordered.first().startUtf16 < introEnd) return null
    val labels = mutableListOf<String>()
    val headers = mutableListOf<GroceryIntentCoverageSpan>()
    val owners = mutableListOf<GroceryIntentCoverageSpan>()
    for (name in listOf("first", "second")) {
        val label = intro.groups[name]!!.value.lowercase(java.util.Locale.ROOT).trim().split(Regex("[ \\t]+")).joinToString(" ")
            .replace(Regex("[ ]+$EDITORIAL_SECTION_NOUN$"), "")
        val tokens = label.split(" ")
        if (tokens.any { it.isEmpty() || it in EDITORIAL_SECTION_UNSAFE || parseIntentCorrectionAmount(it) != null }) return null
        val pattern = Regex("(?:^|[.;!?\\r\\n])[ \\t]*(?<header>" +
            tokens.joinToString("[ \\t]+") { Regex.escape(it) } +
            "(?:[ \\t]+$EDITORIAL_SECTION_NOUN)?[ \\t]*:)[ \\t]*", RegexOption.IGNORE_CASE)
        val matches = pattern.findAll(instruction, introEnd - 1).toList()
        if (matches.size != 1) return null
        val group = matches.single().groups["header"]!!
        if (group.value.any { it.code > 127 }) return null
        val header = GroceryIntentCoverageSpan(group.range.first, group.range.last + 1)
        if (ordered.any { header.startUtf16 < it.endUtf16 && it.startUtf16 < header.endUtf16 }) return null
        val owner = ordered.firstOrNull { it.startUtf16 >= header.endUtf16 } ?: return null
        if (!Regex("[ \\t]+").matches(instruction.substring(header.endUtf16, owner.startUtf16))) return null
        labels += label
        headers += header
        owners += owner
    }
    if (labels[0] == labels[1] || owners[0].endUtf16 > headers[1].startUtf16 || owners[0] != ordered.first() ||
        !Regex("[ \\t]*").matches(instruction.substring(introEnd, headers[0].startUtf16))) return null
    return GroceryIntentCoverageSpan(0, introEnd)
}

/** Complete ORIGINAL list-writing clause or heading, never reconstructed tokens. */
private fun politeIntroductionSpans(
    instruction: String,
    primarySpans: List<GroceryIntentCoverageSpan>,
): List<GroceryIntentCoverageSpan> {
    if (primarySpans.any { it.startUtf16 < 0 || it.startUtf16 >= it.endUtf16 || it.endUtf16 > instruction.length }) {
        return emptyList()
    }
    val spans = mutableListOf<GroceryIntentCoverageSpan>()
    var start = 0
    for (boundary in CLAUSE_BOUNDARY.findAll(instruction)) {
        val heading = isHeadingBoundary(boundary.value)
        if (heading || boundary.value in setOf(".", ";", "!", "?")) {
            val raw = instruction.substring(start, boundary.range.first)
            val end = boundary.range.last + 1
            if (raw.isNotEmpty() && raw.length <= MAX_NEUTRAL_CLAUSE_CODE_POINTS &&
                raw.none { it.code > 0x7f || (it.code < 32 && it != '\t') } &&
                (EDITORIAL_LIST_HEADING.matches(raw.trim(' ', '\t')) ||
                    HINDI_EDITORIAL_LIST_HEADING.matches(raw.trim(' ', '\t')) ||
                    DICTATION_LIST_HEADING.matches(raw.trim(' ', '\t')) ||
                    (heading && (POLITE_INTRODUCTION.matches(raw.trim(' ', '\t')) ||
                        HINDI_HOUSEHOLD_HEADING.matches(raw.trim(' ', '\t')) ||
                        HINDI_DICTATION_LIST_HEADING.matches(raw.trim(' ', '\t'))))) &&
                newHeadingHasList(instruction, raw, end, primarySpans) &&
                primarySpans.none { it.startUtf16 < end && start < it.endUtf16 }
            ) spans += GroceryIntentCoverageSpan(start, end)
        }
        if (boundary.value in setOf(".", ";", "!", "?", "\r", "\n")) {
            start = boundary.range.last + 1
        }
    }
    return spans
}

/** Whole original gaps can continue or close a list, but never supply item facts. */
internal fun listTransitionSpans(
    instruction: String,
    primarySpans: List<GroceryIntentCoverageSpan>,
): List<GroceryIntentCoverageSpan> {
    if (primarySpans.isEmpty() || primarySpans.size > 50 || primarySpans.any {
            it.startUtf16 < 0 || it.startUtf16 >= it.endUtf16 || it.endUtf16 > instruction.length
        }
    ) return emptyList()
    val ordered = primarySpans.sortedWith(compareBy<GroceryIntentCoverageSpan> { it.startUtf16 }.thenBy { it.endUtf16 })
    if (ordered.zipWithNext().any { (left, right) -> left.endUtf16 > right.startUtf16 }) return emptyList()

    val spans = mutableListOf<GroceryIntentCoverageSpan>()
    for ((left, right) in ordered.zipWithNext()) {
        val start = left.endUtf16
        val end = right.startUtf16
        val raw = instruction.substring(start, end)
        if (raw.isNotEmpty() && raw.codePointCount(0, raw.length) <= MAX_NEUTRAL_CLAUSE_CODE_POINTS &&
            raw.all { it.code <= 0x7f } && raw.none { it.code < 32 && it != '\t' } &&
            LIST_RESUMPTION.matches(raw)
        ) {
            // Only trim the leading punctuation after the complete original gap matched.
            val edge = LIST_RESUMPTION_EDGE.find(raw)?.range?.last?.plus(1) ?: 0
            spans += GroceryIntentCoverageSpan(start + edge, end)
        }
    }

    val lastEnd = ordered.last().endUtf16
    // A long-dash closer owns only the ENTIRE original suffix immediately
    // after the last primary. Never search past an omitted product or claim.
    val suffix = instruction.substring(lastEnd)
    // Own only the final request word, never change the item or turn a
    // conditional, missing list member, or negative request into a purchase.
    if (positiveListRequestEnd.matches(suffix) && instruction.substring(0, ordered.first().startUtf16).isBlank() &&
        ordered.zipWithNext().all { (a, b) -> SNACK_MEMBER_GAP.matches(instruction.substring(a.endUtf16, b.startUtf16)) } &&
        ordered.all { item ->
            val text = instruction.substring(item.startUtf16, item.endUtf16)
            affirmativeRequestMember(text)
        }) spans += GroceryIntentCoverageSpan(lastEnd, instruction.length)
    // Same closed list-planning period as introductory wording, never a
    // delivery deadline or a suffix reconstructed past an omitted product.
    if (suffix.length <= MAX_NEUTRAL_CLAUSE_CODE_POINTS &&
        suffix.all { it == '\t' || it.code in 32..126 } &&
        (LIST_PERIOD_ENDING.matches(suffix) || terminalAdditiveSpan(suffix)?.startUtf16 == 0)) {
        spans += GroceryIntentCoverageSpan(lastEnd, instruction.length)
    }
    val dashEdge = DASH_LIST_ENDING_EDGE.find(suffix)?.takeIf { it.range.first == 0 }
    if (dashEdge != null && suffix.length <= MAX_NEUTRAL_CLAUSE_CODE_POINTS) {
        val closing = suffix.substring(dashEdge.range.last + 1)
        if (closing.isNotEmpty() && closing.all { it.code <= 0x7f } &&
            closing.none { it.code < 32 && it != '\t' } &&
            (LIST_ENDING.matches(closing.trim(' ', '\t')) ||
                TERMINAL_HELP_GRATITUDE.matches(closing.trim(' ', '\t')) ||
                ANAPHORIC_CLOSER.matches(closing.trim(' ', '\t').trimEnd('.', '!', '?')))) {
            // Own the separator too; it is not an unproved product heading.
            spans += GroceryIntentCoverageSpan(lastEnd, instruction.length)
        }
    }
    for (boundary in SENTENCE_END.findAll(instruction, lastEnd)) {
        val start = boundary.range.last + 1
        val raw = instruction.substring(start)
        val closingText = raw.trim(' ', '\t').trimEnd('.', '!', '?')
        if (raw.isNotEmpty() && raw.codePointCount(0, raw.length) <= MAX_NEUTRAL_CLAUSE_CODE_POINTS &&
            raw.all { it.code <= 0x7f } && raw.none { it.code < 32 && it != '\t' } &&
            (LIST_ENDING.matches(raw.trim(' ', '\t')) || TERMINAL_HELP_GRATITUDE.matches(raw.trim(' ', '\t')) ||
                ANAPHORIC_CLOSER.matches(closingText))
        ) {
            spans += GroceryIntentCoverageSpan(start, instruction.length)
            break
        }
    }
    return spans
}

/** An original-instruction-bound ending, not a model flag or an item rewrite.
 * Only the existing end-of-list grammar is shared here. Mid-list clauses are
 * deliberately not added: a cut-short quote cannot hide a following condition. */
class GroceryIntentTerminalContext private constructor(
    private val source: String,
    private val span: GroceryIntentCoverageSpan,
) {
    internal fun spanFor(originalSource: String): GroceryIntentCoverageSpan? = span.takeIf { source == originalSource }

    override fun toString(): String = "GroceryIntentTerminalContext(<redacted>)"

    companion object {
        internal fun bind(instruction: String, source: String): GroceryIntentTerminalContext? {
            val quote = bindGroceryIntentProductSource(instruction, source, maxLength = 6000) ?: return null
            // No inferred boundary or skipping across another product, property,
            // negation, number, or request. Only punctuation may follow the quote.
            if (!Regex("[ \\t]*[.!?]?[ \\t]*").matches(instruction.substring(quote.endUtf16))) return null
            val span = terminalAdditiveSpan(source)?.takeIf { it.startUtf16 > 0 } ?: return null
            return GroceryIntentTerminalContext(source, span)
        }
    }
}

private fun terminalAdditiveSpan(text: String): GroceryIntentCoverageSpan? =
    LIST_ADDITIVE_ENDING.findAll(text).lastOrNull { it.range.last + 1 == text.length }
        ?.takeIf { it.value.length <= MAX_NEUTRAL_CLAUSE_CODE_POINTS }
        ?.let { GroceryIntentCoverageSpan(it.range.first, it.range.last + 1) }

private val LIST_RESUMPTION_EDGE = Regex("[ \\t]*[.,;!?]?[ \\t]*")
private val DASH_LIST_ENDING_EDGE = Regex("[ \\t]*[\u2014\u2013\u2012][ \\t]*")

/** Lexical candidates still require original, independently owned following items. */
private fun newHeadingHasList(
    instruction: String,
    raw: String,
    end: Int,
    primaries: List<GroceryIntentCoverageSpan>,
): Boolean {
    if (primaries.size > 50 || primaries.any { it.startUtf16 < 0 || it.startUtf16 >= it.endUtf16 || it.endUtf16 > instruction.length } ||
        raw.any { it.code > 0x7f || (it.code < 32 && it != '\t') } ||
        primaries.any { it.startUtf16 < end && end <= it.endUtf16 }) return false
    if (primaries.sortedBy { it.startUtf16 }.zipWithNext().any { (left, right) -> left.endUtf16 > right.startUtf16 })
        return false
    if (primaries.isNotEmpty()) {
        val following = primaries.filter { end <= it.startUtf16 && it.startUtf16 < it.endUtf16 && it.endUtf16 <= instruction.length }
            .minOfOrNull { it.startUtf16 } ?: return false
        return instruction.substring(end, following).isBlank()
    }
    return instruction.substring(end).trimStart().firstOrNull()?.let { it.isLetterOrDigit() || it == '_' } == true
}

private fun legacyIntroductionHasList(
    instruction: String, start: Int, end: Int, clause: String, heading: Boolean,
    primaries: List<GroceryIntentCoverageSpan>,
): Boolean {
    // Permit a separately validated typed heading before the first item, but
    // never skip another sentence to turn an unfulfilled request into courtesy.
    if (primaries.size > 50 || primaries.any {
            it.startUtf16 < 0 || it.startUtf16 >= it.endUtf16 || it.endUtf16 > instruction.length ||
                (it.startUtf16 < end && end <= it.endUtf16)
        }) return false
    val preceding = primaries.count { it.endUtf16 <= start }
    if (primaries.sortedBy { it.startUtf16 }.zipWithNext().any { (left, right) -> left.endUtf16 > right.startUtf16 })
        return false
    if (preceding > 0 && !heading) return preceding == primaries.size && ANAPHORIC_CLOSER.matches(clause) &&
        instruction.substring(end).all { it.isWhitespace() || it in ".!?" }
    val bodyStart = end + instruction.substring(end).takeWhile(Char::isWhitespace).length
    val bodyEnd = SENTENCE_END.find(instruction, bodyStart)?.range?.first ?: instruction.length
    if (primaries.isNotEmpty()) return primaries.any { bodyStart <= it.startUtf16 && it.endUtf16 <= bodyEnd }
    // Only a lexical candidate without owned primaries; final coverage rechecks.
    return instruction.substring(bodyStart, bodyEnd).any { it.isLetterOrDigit() || it == '_' }
}

/** Exact original opening request; no item quote or trailing clause is consumed. */
private fun initialCartDesirePrefix(
    instruction: String,
    primarySpans: List<GroceryIntentCoverageSpan>,
): GroceryIntentCoverageSpan? {
    val match = INITIAL_CART_DESIRE_PREFIX.find(instruction)?.takeIf { it.range.first == 0 } ?: return null
    val end = match.range.last + 1
    if (end > 180 || match.value.any { it.code > 0x7f } || primarySpans.any {
            it.startUtf16 < end && it.endUtf16 > 0
        }) return null
    return GroceryIntentCoverageSpan(0, end)
}

/** Only a leading app address beside an independently owned shopping request. */
private fun groceryIntentAssistantInvocation(
    instruction: String,
    primaries: List<GroceryIntentCoverageSpan>,
    existingSpans: List<GroceryIntentCoverageSpan>,
): GroceryIntentCoverageSpan? {
    if (primaries.isEmpty() || primaries.size > 50 || primaries.any {
            it.startUtf16 < 0 || it.startUtf16 >= it.endUtf16 || it.endUtf16 > instruction.length
        }) return null
    val primary = primaries.minWith(compareBy<GroceryIntentCoverageSpan> { it.startUtf16 }.thenBy { it.endUtf16 })
    val prefix = instruction.substring(0, primary.startUtf16)
    if (prefix.length > 360 || prefix.any { it.code > 126 || (it.code < 32 && it != '\t') }) return null
    val invocation = ASSISTANT_INVOCATION.find(prefix)?.takeIf { it.range.first == 0 } ?: return null
    val invocationEnd = invocation.range.last + 1
    val request = prefix.substring(invocationEnd).trim(' ', '\t')
    val direct = REQUEST_PREFIX.matches(request) &&
        destinationHomeIsSeparate(request, instruction.substring(primary.startUtf16, primary.endUtf16))
    val introduction = request.lastOrNull() in listOf('.', ';', '!', '?', ':') &&
        LIST_INTRODUCTION.matches(request.dropLast(1).trimEnd(' ', '\t'))
    if (!direct && !introduction) return null
    // Require the existing pass to own every non-whitespace request character.
    if ((invocationEnd until primary.startUtf16).any { index ->
            instruction[index] !in " \t" && existingSpans.none { index >= it.startUtf16 && index < it.endUtf16 }
        }) return null
    return GroceryIntentCoverageSpan(0, invocationEnd)
}

private val ASSISTANT_INVOCATION = Regex("[ \\t]*beta[ \\t]*,", RegexOption.IGNORE_CASE)

/** Only original leading speech address; never item identity, quantity or permission. */
private fun leadingSpokenAddressSpan(
    instruction: String,
    primaries: List<GroceryIntentCoverageSpan>,
    existingSpans: List<GroceryIntentCoverageSpan>,
): GroceryIntentCoverageSpan? {
    if (primaries.isEmpty() || primaries.size > 50 || primaries.any {
            it.startUtf16 < 0 || it.startUtf16 >= it.endUtf16 || it.endUtf16 > instruction.length
        }) return null
    val ordered = primaries.sortedWith(compareBy<GroceryIntentCoverageSpan> { it.startUtf16 }.thenBy { it.endUtf16 })
    if (ordered.zipWithNext().any { (left, right) -> left.endUtf16 > right.startUtf16 }) return null
    val primary = ordered.first()
    val prefix = instruction.substring(0, primary.startUtf16)
    if (prefix.length !in 1..180 || prefix.any { it != '\t' && it.code !in 32..126 }) return null
    val address = LEADING_SPOKEN_ADDRESS.find(prefix)?.takeIf { it.range.first == 0 } ?: return null
    val addressEnd = address.range.last + 1
    val request = prefix.substring(addressEnd).trim(' ', '\t')
    if (request.isNotEmpty()) {
        val direct = REQUEST_PREFIX.matches(request) &&
            destinationHomeIsSeparate(request, instruction.substring(primary.startUtf16, primary.endUtf16))
        val introduction = request.last() in listOf('.', ';', '!', '?', ':') &&
            LIST_INTRODUCTION.matches(request.dropLast(1).trimEnd(' ', '\t'))
        if (!direct && !introduction) return null
        if ((addressEnd until primary.startUtf16).any { index ->
                instruction[index] !in " \t" && existingSpans.none { index >= it.startUtf16 && index < it.endUtf16 }
            }) return null
    }
    return GroceryIntentCoverageSpan(0, addressEnd)
}

private const val SPOKEN_ADDRESS_WORDS =
    "(?:(?:arre|arey)(?:[ \\t]+(?:suno|suniye|yaar))?(?:[ \\t]+(?:beta|ji))?|" +
        "suno(?:[ \\t]+(?:ji|beta))?|sun[ \\t]+na|suniye(?:[ \\t]+(?:beta|ji))?|yaar|didi|orey)"
private val LEADING_SPOKEN_ADDRESS = Regex("[ \\t]*$SPOKEN_ADDRESS_WORDS[ \\t]*,", RegexOption.IGNORE_CASE)
private val SELF_AGE_WORDS = (
    "one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen " +
        "seventeen eighteen nineteen twenty thirty forty fifty sixty seventy eighty ninety hundred and"
    ).split(' ').toSet()
private val SELF_AGE_PREFIX = Regex(
    "[ \\t]*(?:(?:(?:namaste|namaskar|hello|hi)(?:[ \\t]+(?:beta|bhaiya|ji))?|" +
        "haan[ \\t]+ji|(?:arre[ \\t]+)?(?:bhai|bhaiya)(?:[ \\t]+ji)?|$SPOKEN_ADDRESS_WORDS|beta)[ \\t]*,[ \\t]*)?" +
        "(?:(?:i'm|i[ \\t]+am)[ \\t]+(?<english>[a-z0-9]+(?:[ \\t-]+[a-z0-9]+){0,7}?)" +
        "(?:(?:[ \\t]+|-)years?(?:[ \\t]+|-)old)?|(?:main|mai)[ \\t]+" +
        "(?<hindi>[a-z0-9]+(?:[ \\t-]+[a-z0-9]+){0,7}?)[ \\t]+saal[ \\t]+(?:ka|ki)[ \\t]+(?:hoon|hun))" +
        "(?:[ \\t]*[,.;][ \\t]*|[ \\t]+and[ \\t]+(?:i[ \\t]+)?(?:want|need|would[ \\t]+like)[ \\t]+)",
    RegexOption.IGNORE_CASE,
)

/** Original first-person adult age only; never a purchase count or free-text bio. */
private fun selfAgePrefixSpan(
    instruction: String,
    primaries: List<GroceryIntentCoverageSpan>,
    existingSpans: List<GroceryIntentCoverageSpan>,
): GroceryIntentCoverageSpan? {
    if (primaries.isEmpty() || primaries.size > 50 || primaries.any {
            it.startUtf16 < 0 || it.startUtf16 >= it.endUtf16 || it.endUtf16 > instruction.length
        }) return null
    val ordered = primaries.sortedWith(compareBy<GroceryIntentCoverageSpan> { it.startUtf16 }.thenBy { it.endUtf16 })
    if (ordered.zipWithNext().any { (left, right) -> left.endUtf16 > right.startUtf16 }) return null
    fun ageEdgeWord(point: Int): Boolean = Character.isLetterOrDigit(point) || Character.getType(point) in setOf(
        Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt(),
    )
    if (ordered.any { span ->
            val start = span.startUtf16
            val end = span.endUtf16
            instruction[start].isWhitespace() || instruction[end - 1].isWhitespace() ||
                (start > 0 && ageEdgeWord(instruction.codePointBefore(start)) && ageEdgeWord(instruction.codePointAt(start))) ||
                (end < instruction.length && ageEdgeWord(instruction.codePointBefore(end)) && ageEdgeWord(instruction.codePointAt(end))) ||
                (start > 0 && instruction[start - 1].isHighSurrogate() && instruction[start].isLowSurrogate()) ||
                (end < instruction.length && instruction[end - 1].isHighSurrogate() && instruction[end].isLowSurrogate())
        }) return null
    val first = ordered.first().startUtf16
    val prefix = instruction.substring(0, first)
    if (prefix.length !in 1..180 || prefix.any { it != '\t' && it.code !in 32..126 }) return null
    val match = SELF_AGE_PREFIX.find(prefix)?.takeIf { it.range.first == 0 } ?: return null
    val ageText = (match.groups["english"] ?: match.groups["hindi"])?.value ?: return null
    // Amount parsers also accept dozen, fractions and retail units: not age proof.
    if (!(Regex("[1-9][0-9]{1,2}").matches(ageText) || (
            Regex("[a-z]+(?:(?:[ \\t]+|-)[a-z]+)*", RegexOption.IGNORE_CASE).matches(ageText) &&
                Regex("[ \\t-]+").split(ageText.lowercase(java.util.Locale.ROOT)).all { it in SELF_AGE_WORDS }
            ))) return null
    val age = parseIntentCorrectionAmount(ageText) ?: return null
    if (age.stripTrailingZeros().scale() > 0 || age < java.math.BigDecimal(18) || age > java.math.BigDecimal(120)) return null
    val end = match.range.last + 1
    if ((end until first).any { index -> instruction[index] !in " \t" &&
            existingSpans.none { index >= it.startUtf16 && index < it.endUtf16 }
        }) return null
    return GroceryIntentCoverageSpan(0, end)
}

/** A purpose label is not inherited identity. Require a concrete product head in
 * BOTH the independently amount-checked original and the proposed name. This
 * closed witness is not a catalogue classifier; unknown forms remain uncovered.
 */
internal fun groceryIntentHasStandaloneSnackIdentity(sourceWithoutAmounts: String, proposedName: String,
                                                     allowOtherWords: Boolean = true): Boolean {
    fun head(value: String): String? {
        val originalCore = value.substringBefore(',')
        if (originalCore.any { it.code > 0x7f || (it.code < 0x20 && it != '\t') }) return null
        val core = originalCore.trim().replace(Regex("\\([ \\t]*\\)[ \\t]*$"), "").trim()
        if (core.isEmpty() ||
            !Regex("[a-zA-Z \\t'’-]+").matches(core)) return null
        val originalWords = Regex("[a-z]+").findAll(core.lowercase(java.util.Locale.ROOT)).map { it.value }.toList()
        // A small closed seasoning grammar may surround the product noun. It
        // affects this witness ONLY; normal source/property checks must still
        // retain every flavour word. Other conjunctions and new product forms
        // remain ambiguous (e.g. almonds and raisins, almond oil, biscuit mix).
        val seasoningMasked = SNACK_SEASONING.replace(originalWords.joinToString(" "), " ")
        val words = seasoningMasked.split(' ').filter(String::isNotEmpty)
        if (!allowOtherWords && words.size != 1) return null
        if (words.any { it in SNACK_IDENTITY_CONFLICTS }) return null
        val last = words.lastOrNull() ?: return null
        val singular = SNACK_PLURALS[last] ?: last
        if (words.count { (SNACK_PLURALS[it] ?: it) in SNACK_PRODUCT_HEADS + ROASTED_SNACK_HEADS } != 1) return null
        if (singular in ROASTED_SNACK_HEADS) return singular.takeIf { "roasted" in originalWords }
        return singular.takeIf { it in SNACK_PRODUCT_HEADS }
    }
    val original = head(sourceWithoutAmounts) ?: return false
    return original == head(proposedName)
}

/** Final coverage only, AFTER ordinary row validation. Never used to make a row
 * ready, prove corrections, discard restrictions, or manufacture a group.
 * Every primary in the complete original sentence must independently qualify.
 */
internal fun groceryIntentSnackSectionSpans(
    instruction: String,
    primarySpans: List<GroceryIntentCoverageSpan>,
    eligiblePrimarySpans: List<GroceryIntentCoverageSpan>,
    protectedSpans: List<GroceryIntentCoverageSpan> = emptyList(),
): List<GroceryIntentCoverageSpan> {
    if (primarySpans.isEmpty() || primarySpans.size > 50 ||
        primarySpans.distinct().size != primarySpans.size ||
        !primarySpans.containsAll(eligiblePrimarySpans) ||
        (primarySpans + protectedSpans).any { it.startUtf16 < 0 || it.startUtf16 >= it.endUtf16 ||
            it.endUtf16 > instruction.length }) return emptyList()
    val ordered = primarySpans.sortedBy { it.startUtf16 }
    if (ordered.zipWithNext().any { (left, right) -> left.endUtf16 > right.startUtf16 }) return emptyList()
    val result = mutableListOf<GroceryIntentCoverageSpan>()
    val boundaries = SNACK_SENTENCE.findAll(instruction).toList()
    for (index in 0..boundaries.size) {
        val start = if (index == 0) 0 else boundaries[index - 1].range.last + 1
        val end = boundaries.getOrNull(index)?.range?.first ?: instruction.length
        if (end - start > 1500 || protectedSpans.any { it.startUtf16 < end && start < it.endUtf16 }) continue
        val members = ordered.filter { it.startUtf16 < end && start < it.endUtf16 }
        if (members.size !in 2..20 || members.any { it.startUtf16 < start || it.endUtf16 > end ||
                it !in eligiblePrimarySpans }) continue
        val first = members.first().startUtf16
        val intro = instruction.substring(start, first)
        if (intro.length > 64 || intro.any { it.code > 0x7f } || !SNACK_SECTION.matches(intro)) continue
        if (members.zipWithNext().any { (left, right) ->
                !SNACK_MEMBER_GAP.matches(instruction.substring(left.endUtf16, right.startUtf16))
            } || instruction.substring(members.last().endUtf16, end).any { it !in " \t" }) continue
        result += GroceryIntentCoverageSpan(start, first)
    }
    return result
}

private val SNACK_SENTENCE = Regex("[;!?\\r\\n]|\\.(?=\\s|$)")
private val SNACK_SECTION = Regex("[ \\t]*snacks?[ \\t]+(?:mein|me|main)[ \\t]+", RegexOption.IGNORE_CASE)
private val SNACK_MEMBER_GAP = Regex(
    "(?:[ \\t]*,[ \\t]*(?:(?:and|aur)[ \\t]+)?|[ \\t]+(?:and|aur)[ \\t]+)", RegexOption.IGNORE_CASE,
)
private val SNACK_PRODUCT_HEADS = setOf(
    "almond", "cashew", "peanut", "walnut", "pistachio", "raisin", "biscuit", "cracker",
    "bhujia", "namkeen", "popcorn", "chip",
)
private val ROASTED_SNACK_HEADS = setOf("seed", "chickpea", "gram", "chana", "foxnut", "makhana")
private val SNACK_PLURALS = (SNACK_PRODUCT_HEADS + ROASTED_SNACK_HEADS).associateBy { it + "s" }
private val SNACK_SEASONING = Regex(
    "\\b(?:roasted and (?:salted|unsalted)|(?:salted|unsalted) and roasted|" +
        "salt and pepper|salt and vinegar|cream and onion|cheese and herbs|" +
        "peri peri|tangy tomato|spicy pudina|roasted|salted|unsalted|plain|masala)\\b",
)
private val SNACK_IDENTITY_CONFLICTS = setOf(
    "no", "not", "without", "except", "unless", "only", "instead", "or", "ya", "and", "aur",
    "with", "containing", "contains", "coated", "coating", "flavored", "flavoured", "flavour", "flavor", "style",
)

/** Original list edges only; an unnumbered brand-like prefix is never removed. */
private fun listSequenceSpans(
    instruction: String,
    primarySpans: List<GroceryIntentCoverageSpan>,
): List<GroceryIntentCoverageSpan> {
    val boundaries = CLAUSE_BOUNDARY.findAll(instruction).toList()
    val spans = mutableListOf<GroceryIntentCoverageSpan>()
    for (index in 0..boundaries.size) {
        val start = if (index == 0) 0 else boundaries[index - 1].range.last + 1
        val boundary = boundaries.getOrNull(index)
        val end = boundary?.range?.first ?: instruction.length
        if (primarySpans.none { it.startUtf16 >= 0 && it.startUtf16 < it.endUtf16 && it.endUtf16 <= start }) continue
        val clause = instruction.substring(start, end).trim()
        if (boundary?.value == "," && LIST_SEQUENCE.matches(clause)) {
            val afterComma = boundary.range.last + 1
            val nextEnd = boundaries.getOrNull(index + 1)?.range?.first ?: instruction.length
            val first = primarySpans.filter {
                it.startUtf16 >= afterComma && it.startUtf16 < nextEnd &&
                    it.startUtf16 < it.endUtf16 && it.endUtf16 <= instruction.length
            }.minOfOrNull { it.startUtf16 }
            if (first != null && instruction.substring(afterComma, first).isBlank()) {
                spans += GroceryIntentCoverageSpan(start, first)
            }
        } else if (boundary == null || !isHeadingBoundary(boundary.value)) {
            val primary = primarySpans.filter {
                it.startUtf16 >= start && it.startUtf16 < end &&
                    it.startUtf16 < it.endUtf16 && it.endUtf16 <= instruction.length
            }.minWithOrNull(compareBy<GroceryIntentCoverageSpan> { it.startUtf16 }.thenBy { it.endUtf16 })
            val prefix = primary?.let { instruction.substring(start, it.startUtf16) }
            // Match the ORIGINAL hard-edge resumption, never a bare brand-like
            // word or a comma-separated missing product. Amounts stay local.
            val resumption = prefix != null && prefix.length <= 64 && index > 0 &&
                boundaries[index - 1].value in setOf(".", ";", "!", "?", "\r", "\n") &&
                Regex("[ \\t]*(?i:arre)[ \\t]+and[ \\t]+").matches(prefix)
            if (primary != null && (LIST_SEQUENCE.matches(requireNotNull(prefix).trim()) || resumption)) {
                val token = instruction.substring(primary.startUtf16, primary.endUtf16).trim()
                    .split(Regex("\\s+"), limit = 2).first()
                if (parseIntentCorrectionAmount(token)?.signum() == 1) {
                    spans += GroceryIntentCoverageSpan(start, primary.startUtf16)
                }
            }
        }
    }
    return spans
}

private fun isAdjacentNeutralPrelude(
    instruction: String,
    clauseStartUtf16: Int,
    clauseEndUtf16: Int,
    boundaryEndUtf16: Int,
    followingClauseEndUtf16: Int?,
    followingBoundary: String?,
    primarySpans: List<GroceryIntentCoverageSpan>,
): Boolean {
    val boundary = instruction.substring(clauseEndUtf16, boundaryEndUtf16)
    if (isHeadingBoundary(boundary)) return false
    val rawPrelude = instruction.substring(clauseStartUtf16, clauseEndUtf16)
    val prelude = rawPrelude.trim()
    val horizontalPrelude = rawPrelude.trim(' ', '\t')
    val dayGreeting = clauseStartUtf16 == 0 && horizontalPrelude.all { it.code <= 0x7f } &&
        DAY_GREETING.matches(horizontalPrelude) &&
        primarySpans.none { it.startUtf16 < clauseEndUtf16 && clauseStartUtf16 < it.endUtf16 }
    if (dayGreeting && boundary in setOf("\r", "\n")) return false
    if (prelude.isEmpty() || !prelude.all { it.code <= 0x7f } ||
        prelude.codePointCount(0, prelude.length) > MAX_NEUTRAL_CLAUSE_CODE_POINTS ||
        !(SOCIAL_PRELUDE.matches(prelude) || HOUSEHOLD_PRELUDE.matches(prelude) || dayGreeting)
    ) return false

    val followingEnd = followingClauseEndUtf16 ?: instruction.length
    val nextHeading = followingBoundary != null && isHeadingBoundary(followingBoundary)
    if (nextHeading) {
        val rawFollowing = instruction.substring(boundaryEndUtf16, followingEnd)
        val following = if (dayGreeting) rawFollowing.trim(' ', '\t') else rawFollowing.trim()
        if (dayGreeting && rawFollowing.any { it.code > 0x7f || (it.code < 0x20 && it != '\t') }) return false
        return following.isNotEmpty() && following.codePointCount(0, following.length) <= MAX_NEUTRAL_CLAUSE_CODE_POINTS &&
            LIST_INTRODUCTION.matches(following)
    }

    val candidates = primarySpans.filter {
        it.startUtf16 >= boundaryEndUtf16 && it.startUtf16 < it.endUtf16 && it.endUtf16 <= followingEnd
    }
    val firstPrimaryStart = candidates.minOfOrNull { it.startUtf16 } ?: return false
    val rawPrefix = instruction.substring(boundaryEndUtf16, firstPrimaryStart)
    if (dayGreeting && rawPrefix.any { it.code > 0x7f || (it.code < 0x20 && it != '\t') }) return false
    val prefix = if (dayGreeting) rawPrefix.trim(' ', '\t') else rawPrefix.trim()
    return prefix.isNotEmpty() && prefix.codePointCount(0, prefix.length) <= MAX_NEUTRAL_CLAUSE_CODE_POINTS &&
        REQUEST_PREFIX.matches(prefix) &&
        destinationHomeIsSeparate(prefix, instruction.substring(firstPrimaryStart, followingEnd))
}

/** Context next to independently verified correction evidence, never a raw model proposal. */
internal fun correctionGroceryIntentDiscourseSpans(
    instruction: String,
    verifiedCorrectionSpans: List<GroceryIntentCoverageSpan>,
    primarySpans: List<GroceryIntentCoverageSpan>,
): List<GroceryIntentCoverageSpan> {
    val allSpans = (primarySpans + verifiedCorrectionSpans).sortedBy { it.startUtf16 }
    if (primarySpans.isEmpty() || primarySpans.size > 50 || verifiedCorrectionSpans.size > 50 ||
        allSpans.any { it.startUtf16 < 0 || it.startUtf16 >= it.endUtf16 || it.endUtf16 > instruction.length } ||
        allSpans.zipWithNext().any { (left, right) -> left.endUtf16 > right.startUtf16 }
    ) return emptyList()
    val context = mutableListOf<GroceryIntentCoverageSpan>()
    val first = primarySpans.minBy { it.startUtf16 }
    val prelude = instruction.substring(0, first.startUtf16)
    val announcement = if (first.startUtf16 in 1..MAX_NEUTRAL_CLAUSE_CODE_POINTS && prelude.all { it.code <= 0x7f })
        CORRECTION_ANNOUNCEMENT.matchEntire(prelude) else null
    if (announcement != null && verifiedCorrectionSpans.isNotEmpty() &&
        parseIntentCorrectionAmount(announcement.groups["count"]!!.value)?.compareTo(
            java.math.BigDecimal(verifiedCorrectionSpans.size)) == 0 &&
        verifiedCorrectionSpans.all { first.endUtf16 <= it.startUtf16 }
    ) context += GroceryIntentCoverageSpan(0, first.startUtf16)
    for (correction in verifiedCorrectionSpans) {
        val start = correction.startUtf16
        val end = correction.endUtf16
        if (start < 0 || start >= end || end > instruction.length || primarySpans.any { primary ->
                start < primary.endUtf16 && primary.startUtf16 < end
            }
        ) continue

        // A separator between proved source spans cannot carry a group scope.
        // Unknown intervening words and raw correction proposals never qualify.
        val previousEnd = allSpans.filter { it.endUtf16 <= start }.maxOfOrNull { it.endUtf16 }
        if (previousEnd != null && Regex("(?:[ \\t]*[\\u2014\\u2013\\u2012][ \\t]*|[ \\t]+-[ \\t]+)")
                .matches(instruction.substring(previousEnd, start))) {
            context += GroceryIntentCoverageSpan(previousEnd, start)
        }
        val preceding = instruction.substring(0, start)
        val headingMatch = CORRECTION_HEADING.find(preceding)
        headingMatch?.groups?.get(1)?.range?.let { range ->
            context += GroceryIntentCoverageSpan(range.first, range.last + 1)
        }

        if (primarySpans.all { it.endUtf16 <= start } && CORRECTION_UNCHANGED_TAIL.matches(instruction.substring(end))) {
            context += GroceryIntentCoverageSpan(end, instruction.length)
        }
    }
    verifiedCorrectionSpans.sortedBy { it.startUtf16 }.zipWithNext().forEach { (left, right) ->
        // Conjunction between two closed proven operations, never 'aur' as more.
        if (primarySpans.all { it.endUtf16 <= left.startUtf16 } &&
            CORRECTION_CONNECTIVE.matches(instruction.substring(left.endUtf16, right.startUtf16)) &&
            namedCorrectionStarts(instruction.substring(right.startUtf16, right.endUtf16), instruction, primarySpans) &&
            CORRECTION_CLOSED_TAIL.containsMatchIn(instruction.substring(left.startUtf16, left.endUtf16))
        ) context += GroceryIntentCoverageSpan(left.endUtf16, right.startUtf16)
    }
    return context.sortedWith(compareBy<GroceryIntentCoverageSpan> { it.startUtf16 }.thenBy { it.endUtf16 })
}

/** Extra adjacency proof; complete numeric target proof remains the caller's job. */
private fun namedCorrectionStarts(text: String, instruction: String, primaries: List<GroceryIntentCoverageSpan>): Boolean {
    val token = Regex("^([a-z]+)(?=[ \\t])", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)
        ?.lowercase(java.util.Locale.ROOT) ?: return false
    if (token in setOf("aur", "and", "or", "bhi", "extra", "zyada", "more", "it", "this", "that",
            "isko", "usko", "yeh", "ye", "woh", "wo", "no", "not", "nahi", "nahin", "actually",
            "make", "set", "change", "please", "the", "a", "an") || parseIntentCorrectionAmount(token) != null
    ) return false
    val anchor = Regex("\\b${Regex.escape(token)}\\b", RegexOption.IGNORE_CASE)
    return primaries.count { anchor.containsMatchIn(instruction.substring(it.startUtf16, it.endUtf16)) } == 1
}

private fun hindiRequestSuffixSpans(
    instruction: String,
    primarySpans: List<GroceryIntentCoverageSpan>,
): List<GroceryIntentCoverageSpan> {
    val suffixes = mutableListOf<GroceryIntentCoverageSpan>()
    var clauseStart = 0
    val boundaries = CLAUSE_BOUNDARY.findAll(instruction).iterator()
    while (boundaries.hasNext()) {
        val boundary = boundaries.next()
        val clauseEnd = boundary.range.first
        val itemEnds = primarySpans.asSequence()
            .filter {
                it.startUtf16 >= clauseStart && it.startUtf16 < it.endUtf16 &&
                    it.endUtf16 <= clauseEnd
            }
            .map { it.endUtf16 }
            .toList()
        if (itemEnds.isNotEmpty() && !isHeadingBoundary(boundary.value)) {
            addHindiRequestSuffix(instruction, itemEnds.maxOrNull()!!, clauseEnd, boundary.range.last + 1, suffixes)
        }
        clauseStart = boundary.range.last + 1
    }
    val finalItemEnds = primarySpans.asSequence()
        .filter {
            it.startUtf16 >= clauseStart && it.startUtf16 < it.endUtf16 &&
                it.endUtf16 <= instruction.length
        }
        .map { it.endUtf16 }
        .toList()
    if (finalItemEnds.isNotEmpty()) {
        addHindiRequestSuffix(instruction, finalItemEnds.maxOrNull()!!, instruction.length, instruction.length, suffixes)
    }
    return suffixes
}

/** Exact imperative "do" offsets, derived only from an already-proven closed Hindi suffix. */
internal fun neutralHindiImperativeDoSpans(
    instruction: String,
    neutralSpans: List<GroceryIntentCoverageSpan>,
): List<GroceryIntentCoverageSpan> {
    val trimChars = charArrayOf(' ', '\t', '\r', '\n', '.', ',', ';', '!', '?')
    val doToken = Regex("\\bdo\\b", RegexOption.IGNORE_CASE)
    return neutralSpans.filter { it.startUtf16 >= 0 && it.endUtf16 > it.startUtf16 && it.endUtf16 <= instruction.length }
        .flatMap { span ->
            val raw = instruction.substring(span.startUtf16, span.endUtf16)
            val leading = raw.indexOfFirst { it !in trimChars }.takeIf { it >= 0 } ?: return@flatMap emptyList()
            val trailing = raw.indexOfLast { it !in trimChars } + 1
            val trimmed = raw.substring(leading, trailing)
            if (!HINDI_SUFFIX.matches(trimmed)) return@flatMap emptyList()
            doToken.findAll(trimmed).map { match ->
                GroceryIntentCoverageSpan(span.startUtf16 + leading + match.range.first,
                    span.startUtf16 + leading + match.range.last + 1)
            }.toList()
        }.distinct().sortedBy { it.startUtf16 }
}

/** Late, non-actionable coverage; a rejected final row cannot own this request. */
internal fun groceryIntentTerminalCommaCartRequest(
    instruction: String,
    primarySpans: List<GroceryIntentCoverageSpan>,
    survivingSpans: List<GroceryIntentCoverageSpan>,
): GroceryIntentCoverageSpan? {
    if (primarySpans.isEmpty() || primarySpans.size > 50 || primarySpans.any {
            it.startUtf16 < 0 || it.startUtf16 >= it.endUtf16 || it.endUtf16 > instruction.length
        }) return null
    val ordered = primarySpans.sortedBy { it.startUtf16 }
    if (ordered.zipWithNext().any { (left, right) -> left.endUtf16 > right.startUtf16 }) return null
    val owner = ordered.last()
    if (owner !in survivingSpans) return null
    val tail = instruction.substring(owner.endUtf16)
    if (tail.length > 180 || tail.any { it.code > 0x7f } || !TERMINAL_COMMA_CART_REQUEST.matches(tail)) return null
    val sentenceStart = Regex("[;!?\\r\\n]|\\.(?=\\s|$)")
        .findAll(instruction.substring(0, owner.endUtf16)).lastOrNull()?.range?.last?.plus(1) ?: 0
    if (owner.startUtf16 < sentenceStart) return null
    val sentence = instruction.substring(sentenceStart, owner.endUtf16)
    // Original text only, never assembled after masking a quote or lost item.
    if (!Regex("[A-Za-z0-9 \\t,.-]+").matches(sentence) ||
        instruction.any { it in "\"'`“”‘’()[]{}" } ||
        CART_REPORTED_CONTEXT.containsMatchIn(instruction)) return null
    return GroceryIntentCoverageSpan(owner.endUtf16, instruction.length)
}

private val CART_REPORTED_CONTEXT = Regex(
    "\\b(?:say|says|said|saying|read|reads|reading|write|writes|writing|written|" +
        "print|prints|printing|printed|label|labels|labelled|labeled|caption|captions|" +
        "message|messages|text|texts|inscription|inscribe|inscribed|inscribing|" +
        "engrave|engraved|engraving|emboss|embossed|embossing|stamp|stamped|stamping|" +
        "display|displays|displayed|displaying|show|shows|shown|showing|quote|quotes|quoted|quotation|" +
        "bear|bears|bearing|bore|borne|slogan|slogans|motto|mottos|mottoes|lettering|wording|" +
        "phrase|phrases|with|whose|which|that|carrying|carry|carries|carried|" +
        "card|cards|sign|signs|poster|posters|called|named|title|titles|word|words|" +
        "likha|likhi|likhe|likhna|likho|kehna|kaha|bola|bolna|if|unless|agar)\\b", RegexOption.IGNORE_CASE,
)

private fun addHindiRequestSuffix(
    instruction: String,
    lastItemEndUtf16: Int,
    clauseEndUtf16: Int,
    coveredEndUtf16: Int,
    suffixes: MutableList<GroceryIntentCoverageSpan>,
) {
    val suffix = instruction.substring(lastItemEndUtf16, clauseEndUtf16).trim()
    if (suffix.isNotEmpty() && suffix.codePointCount(0, suffix.length) <= MAX_NEUTRAL_CLAUSE_CODE_POINTS &&
        HINDI_SUFFIX.matches(suffix)
    ) {
        suffixes += GroceryIntentCoverageSpan(lastItemEndUtf16, coveredEndUtf16)
    }
}

private fun addNeutralClauseSpan(
    instruction: String,
    startUtf16: Int,
    endUtf16: Int,
    coveredEndUtf16: Int,
    headingBoundary: Boolean,
    primarySpans: List<GroceryIntentCoverageSpan>,
    suffixes: List<GroceryIntentCoverageSpan>,
    socialPrelude: Boolean,
    spans: MutableList<GroceryIntentCoverageSpan>,
) {
    val clause = instruction.substring(startUtf16, endUtf16).trim()
    if (clause.isEmpty()) return
    val nominalEdgeSafe = !NOMINAL_RESTOCK_CLAUSE.matches(clause) || startUtf16 == 0 ||
        Regex("[.;!?\\r\\n][ \\t]*$").containsMatchIn(instruction.substring(0, startUtf16))
    val addedHeadingSafe = !headingBoundary || LEGACY_LIST_INTRODUCTION.matches(clause) ||
        newHeadingHasList(instruction, instruction.substring(startUtf16, endUtf16), coveredEndUtf16, primarySpans)
    val legacyBodySafe = !LEGACY_LIST_INTRODUCTION.matches(clause) ||
        legacyIntroductionHasList(instruction, startUtf16, coveredEndUtf16, clause, headingBoundary, primarySpans)
    if (clause.codePointCount(0, clause.length) <= MAX_NEUTRAL_CLAUSE_CODE_POINTS &&
        nominalEdgeSafe && addedHeadingSafe && legacyBodySafe &&
        ((if (headingBoundary) LIST_INTRODUCTION else NEUTRAL_CLAUSE).matches(clause) || socialPrelude)
    ) {
        spans += GroceryIntentCoverageSpan(startUtf16, coveredEndUtf16)
        return
    }
    val firstPrimaryStart = primarySpans.asSequence()
        .filter { it.startUtf16 >= startUtf16 && it.startUtf16 < it.endUtf16 && it.endUtf16 <= instruction.length }
        .filter { it.startUtf16 < endUtf16 }
        .minOfOrNull { it.startUtf16 }
    if (firstPrimaryStart != null) {
        val prefix = instruction.substring(startUtf16, firstPrimaryStart).trim()
        val englishRequest = REQUEST_PREFIX.matches(prefix) &&
            destinationHomeIsSeparate(prefix, instruction.substring(firstPrimaryStart, endUtf16))
        val hindiRequest = HINDI_PREFIX.matches(prefix) && suffixes.any { suffix ->
            firstPrimaryStart < suffix.startUtf16 &&
                !SENTENCE_END.containsMatchIn(instruction.substring(firstPrimaryStart, suffix.startUtf16))
        }
        if (prefix.isNotEmpty() && prefix.codePointCount(0, prefix.length) <= MAX_NEUTRAL_CLAUSE_CODE_POINTS &&
            (englishRequest || hindiRequest)
        ) {
            spans += GroceryIntentCoverageSpan(startUtf16, firstPrimaryStart)
        }
    }
}

private fun destinationHomeIsSeparate(prefix: String, primary: String): Boolean {
    // Do not erase 'home' from model-split home made/style/grown modifiers.
    // An explicit leading number separates destination wording from identity;
    // this never validates, infers or changes the item's purchased amount.
    if (!Regex("\\bhome$", RegexOption.IGNORE_CASE).containsMatchIn(prefix)) return true
    return parseIntentCorrectionAmount(primary.trim().split(Regex("\\s+"), limit = 2).first()) != null
}

private fun isHeadingBoundary(boundary: String): Boolean =
    boundary in setOf(":", "—", "–", "‒", "：", "-")

private const val MAX_NEUTRAL_CLAUSE_CODE_POINTS = 180
private val LIST_SEQUENCE = Regex("(?:(?:and\\s+)?(?:next|last|lastly|finally|then|phir|fir)|aur[ \\t]+(?:phir|fir))", RegexOption.IGNORE_CASE)

private val CLAUSE_BOUNDARY = Regex(
    "[,;:!?\\r\\n\\u2014\\u2013\\u2012\\uff1a]|(?<=\\s)-(?=\\s)|\\.(?=\\s|$)",
)

private const val WHEN_MOMENT = "when\\s+you\\s+(?:have|get)\\s+a\\s+moment"
private const val REQUEST = "(?:get|bring|buy|order|add|arrange|send|pick\\s+up|put\\s+in)"
private const val HOUSEHOLD_OWNER =
    "(?:(?:my|our|the)\\s+)?(?:(?:(?:parents|grandparents|children|kids)'|" +
        "(?:mother|father|mom|dad|wife|husband|son|daughter|family)'s)\\s+)?"
private const val MEAL_PURPOSE =
    "(?:(?:morning|afternoon|evening)\\s+)?(?:tea|coffee|breakfast|lunch|dinner)"
// Whole prefix immediately before an independently owned first item. Period
// modifies "list", not delivery; "for dinner," is purpose, never "by dinner".
private val INITIAL_SHOPPING_FRAME = Regex(
    "(?:can|could|may)[ \\t]+(?:I|we)[ \\t]+(?:please[ \\t]+)?(?:have|get|buy|order)[ \\t]+|" +
        "(?:(?:my|our|the)[ \\t]+)?(?:(?:weekly|monthly)[ \\t]+)?(?:(?:grocery|shopping)[ \\t]+)?list(?:[ \\t]+please)?[ \\t]*:[ \\t]*|" +
        "for[ \\t]+$MEAL_PURPOSE[ \\t]*[,:][ \\t]*", RegexOption.IGNORE_CASE,
)
private const val SHOPPING_PERIOD = "for\\s+(?:the|this)\\s+(?:week|month)"
private const val LIST_DESTINATION = "(?:to\\s+(?:the|my|our)\\s+list|(?:to|in)\\s+(?:the|my|our)\\s+cart)"
private const val SHOPPING_LIST = "(?:(?:household\\s+)?(?:grocery|shopping|shop)\\s+list)"
private const val RESTOCK = "(?:(?:weekly|monthly)\\s+(?:(?:household|grocery)\\s+)?restock)"
private const val LIST =
    "(?:(?:me|us)\\s+)?(?:(?:my|our|the|these)\\s+)?" +
        "(?:following(?:\\s+(?:items|groceries|things))?|" +
        "(?:(?:weekly|monthly|regular)\\s+)?(?:groceries|shopping|basics|essentials|items|list|(?:household\\s+)?supplies))"
private const val HEADING_LIST = "(?:$LIST|(?:(?:my|our|the|these)\\s+)?(?:" +
    "$SHOPPING_LIST|$RESTOCK|(?:pantry|household)\\s+(?:items|things|supplies)))"
private const val LIST_REFERENCE = "(?:$LIST|these|everything\\s+on\\s+(?:this|the|my|our)\\s+list)"
private const val LIST_SCAFFOLD =
    "(?:$WHEN_MOMENT\\s+)?$POLITE(?:$REQUEST\\s+$LIST_REFERENCE|put\\s+$LIST_REFERENCE\\s+$LIST_DESTINATION)" +
        "(?:\\s+(?:$SHOPPING_PERIOD|$LIST_DESTINATION))?(?:\\s+too)?"
// No arbitrary object/quantity/diet/deadline slot in a list desire or restock.
private const val LIST_DESIRE = "(?:i|we)\\s+(?:need|want)\\s+(?:a\\s+)?$HEADING_LIST"
private const val NOMINAL_LIST = "(?:(?:my|our|the)\\s+)?(?:$SHOPPING_LIST|$RESTOCK)"
private val NOMINAL_RESTOCK_CLAUSE = Regex("(?:(?:my|our|the)\\s+)?$RESTOCK", RegexOption.IGNORE_CASE)
private const val HEADING_REQUEST = "$POLITE$REQUEST\\s+$HEADING_LIST"
private const val NEW_HEADINGS = "(?:$LIST_DESIRE|$NOMINAL_LIST|$HEADING_REQUEST)"
private const val PURPOSE_TARGET =
    "(?:$HOUSEHOLD_OWNER(?:home|house|household|kitchen|bathroom|pantry)|" +
        "(?:(?:my|our|the)\\s+)?(?:$MEAL_PURPOSE|basics|essentials))"
private const val LIST_PURPOSE = "for\\s+$PURPOSE_TARGET(?:\\s+and\\s+$PURPOSE_TARGET)?"
private const val POLITE_REQUEST_LIST =
    "(?:$LIST_SCAFFOLD(?:\\s+$LIST_PURPOSE)?)"
private const val ORDINARY_POLITE_REQUEST_LIST =
    "$LIST_SCAFFOLD" +
        "(?:\\s+for\\s+(?:home|(?:my|our|the)\\s+(?:home|house|household)))?"

private const val HINDI_COURTESY = "(?:(?:zara|please|jab\\s+aasani\\s+ho)\\s+)?"
private const val HINDI_DESTINATION = "(?:ghar\\s+ke\\s+liye\\s+)?"
private const val HINDI_ORIGIN = "(?:bazaar|bazar|mandi)\\s+se"
private const val HINDI_BRING_REQUEST = "(?:le\\s+(?:aana|aao|aaiyega|aaiye)|la\\s+do|mangwa\\s+(?:do|dijiye))"
private const val HINDI_REQUEST = "(?:$HINDI_BRING_REQUEST|likh\\s+(?:lo|lijiye))"
/** Original-span request evidence for receipt compilers, never a text rewrite. */
internal fun groceryIntentClosedHindiRequestRanges(source: String): List<IntRange> =
    Regex("(?<![A-Za-z0-9_])$HINDI_REQUEST(?![A-Za-z0-9_])")
        .findAll(source.map { if (it in 'A'..'Z') it + ('a' - 'A') else it }.joinToString(""))
        .map { it.range }.toList()
private const val HINDI_CART_REQUEST =
    "(?:cart[ \\t]+(?:mein|me)[ \\t]+(?:daal|dal)[ \\t]+(?:do|dijiye)|(?:daal|dal)[ \\t]+(?:do|dijiye)[ \\t]+cart[ \\t]+(?:mein|me))"
private val CLOSED_HINDI_PURCHASE_SUFFIX = Regex("(?:$HINDI_BRING_REQUEST|$HINDI_CART_REQUEST)", RegexOption.IGNORE_CASE)
/** Reuse the closed cart request grammar without changing receipt eligibility. */
internal fun groceryIntentClosedCartRequestRanges(source: String): List<IntRange> =
    Regex("(?<![A-Za-z0-9_])$HINDI_CART_REQUEST(?![A-Za-z0-9_])", RegexOption.IGNORE_CASE)
        .findAll(source).map { it.range }.toList()
private val TERMINAL_COMMA_CART_REQUEST = Regex(
    "[ \\t]*,[ \\t]*(?:(?:zara|please)[ \\t]+)?$HINDI_CART_REQUEST" +
        "(?:[ \\t]+please)?[ \\t]*[.!?]?[ \\t]*", RegexOption.IGNORE_CASE,
)
private const val HINDI_LIST = "(?:(?:yeh|ye)\\s+(?:sab|samaan|saamaan|cheezen)|(?:samaan|saamaan))"
private const val HINDI_INTRODUCTION =
    HINDI_COURTESY + HINDI_DESTINATION + "(?:$HINDI_ORIGIN\\s+)?" + HINDI_LIST + "\\s+" + HINDI_REQUEST
// Explicit list-writing speech, independent of speaker gender. Today qualifies
// only the present writing act; no delivery date, product, diet or amount slot.
private const val HINDI_WRITING_OBJECT =
    "(?:(?:ghar\\s+(?:ka|ki)|ghar\\s+ke\\s+liye)\\s+)?" +
        "(?:samaan|saamaan|saman|(?:(?:shopping|grocery)\\s+)?list)"
private const val HINDI_WRITER =
    "(?:(?:main|mai|hum)(?:\\s+aaj)?\\s+|aaj(?:\\s+(?:main|mai|hum))?\\s+)?"
private const val HINDI_WRITTEN_INTRODUCTION =
    HINDI_WRITER + HINDI_WRITING_OBJECT + "\\s+likh\\s+" +
        "(?:(?:raha|rahi)\\s+(?:hoon|hun)|rahe\\s+(?:hain|hai))"
private const val HINDI_WRITING_REQUEST =
    HINDI_COURTESY + "(?:$HINDI_WRITING_OBJECT|list\\s+(?:mein|me))" +
        "\\s+likh\\s+(?:lo|lijiye|lena|do)"
private const val EDITORIAL_DOCUMENT =
    "(?:(?:my|our|the|this|a|one)[ \\t]+)?(?:following[ \\t]+)?" +
        "(?:(?:weekly|monthly)[ \\t]+)?(?:(?:short|long|mixed)[ \\t]+)?" +
        "(?:(?:shopping|grocery|household|kitchen)[ \\t]+)?(?:list|note)"
// 'One' above counts the explicit document, never a grocery quantity. Additional
// spoken addresses belong only to a complete editorial clause, never a product.
private const val SPOKEN_LIST_GREETING =
    "(?:(?:(?:namaste|namaskar|hello|hi)(?:\\s+(?:beta|bhaiya|ji))?|" +
        "haan\\s+ji|(?:arre\\s+)?(?:bhai|bhaiya)(?:\\s+ji)?|" +
        "suno(?:[ \\t]+ji)?|suniye|didi|arre)[ \\t]*,[ \\t]*)?"

private val standardTerminalRequest = Regex("\\s+(?:chahiye|chaahiye|mangao|lao|le\\s+aao)[ \\t]*[.!।]?$", RegexOption.IGNORE_CASE)
private val positiveListRequestEnd = Regex("[ \\t]+(?:chahiye|chaahiye)[ \\t]*[.!।]?[ \\t]*$", RegexOption.IGNORE_CASE)
internal fun affirmativeListEndStart(instruction: String) = positiveListRequestEnd.find(instruction)?.range?.first
private val listEndScopeOperator = unicodeEvidenceRegex("[,;:!?।]|(?<![\\p{L}\\p{M}\\p{N}_])(?:and|aur|or|ya|if|unless|only|agar|और|या|अगर|नहीं|मत)(?![\\p{L}\\p{M}\\p{N}_])")
internal fun affirmativeRequestMember(text: String) = !listEndScopeOperator.containsMatchIn(text) &&
    !propertyHasUnprovedNegation(text) && !negatedTerminalRequest.containsMatchIn(text) && !intentV2HasCorrectionCue(text)
private val detachedGreetingBeforeAmount = Regex("^$SPOKEN_LIST_GREETING(?=[1-9][0-9]*[ \\t])", RegexOption.IGNORE_CASE)
private val negatedTerminalRequest = Regex("(?:^|\\s)(?:nahi[n]?|nhi|nai|naa?|mat|no|not|never|don['’]t)$", RegexOption.IGNORE_CASE)

/** A detached greeting is background only inside a complete measured shopping request. */
internal fun standardGroceryRequestBody(text: String): String {
    val suffix = standardTerminalRequest.find(text) ?: return text
    val body = text.substring(0, suffix.range.first)
    if (negatedTerminalRequest.containsMatchIn(body)) return text
    return body.replaceFirst(detachedGreetingBeforeAmount, "")
}

private const val LIST_WRITING_MANNER = "(?:slowly|clearly|carefully)"
private const val LIST_WRITING_MANNERS = "$LIST_WRITING_MANNER(?:[ \\t]+and[ \\t]+$LIST_WRITING_MANNER)?"
private const val LIST_WRITING_CIRCUMSTANCE =
    "while[ \\t]+(?:it[ \\t]+is[ \\t]+quiet|(?:i|we)[ \\t]+have[ \\t]+time)"
private const val EDITORIAL_ITEMS = "(?:these(?:[ \\t]+items)?|the[ \\t]+following(?:[ \\t]+items)?)"
private const val EDITORIAL_ACTION =
    "(?:(?:make|prepare|write(?:[ \\t]+down)?|note(?:[ \\t]+down)?)[ \\t]+$EDITORIAL_DOCUMENT|" +
        "(?:add|put|write(?:[ \\t]+down)?|note(?:[ \\t]+down)?)[ \\t]+$EDITORIAL_ITEMS" +
        "[ \\t]+(?:in|on|to|into)[ \\t]+$EDITORIAL_DOCUMENT)"
// The closed memory aside qualifies list writing, never a product, medical
// requirement or delivery time. Match it inside the original complete heading.
private const val LIST_MEMORY_ASIDE =
    "(?:before[ \\t]+(?:i|we)[ \\t]+forget[ \\t]*,[ \\t]*|" +
        "(?:after[ \\t]+the[ \\t]+(?:clinic|appointment|walk|meeting)[ \\t]+)?" +
        "(?:i|we)[ \\t]+(?:may|might)[ \\t]+forget[ \\t]*,[ \\t]*so[ \\t]+)"
private val EDITORIAL_LIST_HEADING = Regex(
    SPOKEN_LIST_GREETING + "(?:$LIST_MEMORY_ASIDE)?$POLITE" +
        "(?:$LIST_WRITING_MANNER[ \\t]+)?$EDITORIAL_ACTION(?:[ \\t]+$LIST_WRITING_MANNER)?" +
        "(?:[ \\t]+(?:$SHOPPING_PERIOD|for\\s+(?:home|(?:my|our|the)\\s+(?:home|house|household))))?" +
        "(?:[ \\t]+$LIST_WRITING_CIRCUMSTANCE)?" +
        "(?:[ \\t]*,[ \\t]*(?:please|kindly))?" +
        "(?:[ \\t]*,[ \\t]*in[ \\t]+whichever[ \\t]+order[ \\t]+is[ \\t]+easier)?",
    RegexOption.IGNORE_CASE,
)
private val HINDI_EDITORIAL_LIST_HEADING = Regex(
    SPOKEN_LIST_GREETING + HINDI_COURTESY + "(?:" +
        "(?:(?:grocery|shopping)[ \\t]+)?list[ \\t]+(?:mein|me)[ \\t]+(?:yeh|ye)(?:[ \\t]+sab)?[ \\t]+" +
        "likh[ \\t]+(?:lo|lijiye|lena|do)|" +
        "(?:ek[ \\t]+)?(?:(?:short|long|mixed)[ \\t]+)?(?:(?:grocery|shopping)[ \\t]+)?" +
        "(?:list|note)[ \\t]+bana[ \\t]+(?:lo|do|lijiye))",
    RegexOption.IGNORE_CASE,
)
// These closed origins describe the act of dictating, never product/group facts.
private const val DICTATION_ORIGIN =
    "(?:$EDITORIAL_DOCUMENT|(?:the|my|our)[ ]+(?:(?:kitchen|pantry)[ ]+)?(?:shelf|shelves|cupboard|cupboards))"
private const val DICTATION_NOTE =
    "(?:please[ ]+|kindly[ ]+)?(?:note[ ]+(?:this|it)(?:[ ]+down)?|write[ ]+(?:this|it)[ ]+down)"
private val DICTATION_LIST_HEADING = Regex(
    // Preserve the existing dictation no-tab boundary despite shared document slots.
    "(?!.*\\t)" + SPOKEN_LIST_GREETING + "(?:i[ ]+am|i'm|we[ ]+are|we're)[ ]+" +
        "(?:(?:dictating|reading(?:[ ]+out)?|writing(?:[ ]+down)?|preparing|making|" +
        "going[ ]+to[ ]+(?:say|dictate|read(?:[ ]+out)?))[ ]+" +
        "(?:this|$EDITORIAL_DOCUMENT)|" +
        "(?:reading(?:[ ]+out)?|dictating|speaking)[ ]+from[ ]+$DICTATION_ORIGIN)" +
        "(?:(?:[ ]+|[ ]*,[ ]*)$LIST_WRITING_MANNERS)?" +
        "(?:(?:[ ]+|[ ]*,[ ]*)(?:so[ ]+(?:i|we)[ ]+(?:do[ ]+not|don't)[ ]+miss[ ]+anything|" +
        "in[ ]+groups|one[ ]+item[ ]+at[ ]+a[ ]+time))?" +
        "(?:[ ]+$LIST_WRITING_CIRCUMSTANCE)?" +
        "(?:[ ]*,[ ]*$DICTATION_NOTE)?",
    RegexOption.IGNORE_CASE,
)
private val HINDI_DICTATION_LIST_HEADING = Regex(
    "(?:(?:bhaiya|beta|suno[ ]+ji|suniye)[ ]*,[ ]*)?" +
        "(?:" +
        "(?:thoda[ ]+)?dhyan[ ]+se[ ]+(?:sunna|suno|suniye)[ ]*,[ ]*(?:ye|yeh)[ ]+sab[ ]+chahiye|" +
        "(?:(?:meri|hamari)[ ]+(?:beti|beta)[ ]+ne[ ]+bola[ ]+)?list[ ]+(?:saaf[ ]+)?likh[ ]+(?:dena|do|lena)[ ]*,[ ]*(?:suno|suniye)|" +
        "(?:chh?oti[ ]+si[ ]+)?(?:lambi[ ]+)?list[ ]+hai" +
        ")",
    RegexOption.IGNORE_CASE,
)
// Complete original first-primary prefix, never independently removed words.
// Closed document/action slots cannot consume product, amount, diet, deadline
// or history constraints. 'Thoda' here modifies writing, not an item amount.
private val INITIAL_LIST_REQUEST = Regex(
    "(?:(?:(?:namaste|namaskar|hello|hi)(?:\\s+(?:beta|bhaiya|ji))?|haan\\s+ji|" +
        "(?:arre\\s+)?(?:bhai|bhaiya)(?:\\s+ji)?|arre[ \\t]+beta|beta|suno(?:[ \\t]+ji)?|suniye|didi|arre)[ \\t]*,[ \\t]*)?" +
        "(?:$POLITE(?:pick|put[ \\t]+down|(?:write|note)[ \\t]+down|" +
        "(?:add|put|(?:write|note)(?:[ \\t]+down)?)[ \\t]+(?:to|in|on|into)[ \\t]+" +
        "(?:the|my|our|this)[ \\t]+(?:(?:shopping|grocery)[ \\t]+)?list)|" +
        "$HINDI_COURTESY(?:(?:thoda[ \\t]+)?dhyan[ \\t]+se[ \\t]+likh[ \\t]+(?:lo|lijiye|lena|do)|" +
        "(?:bazaar|bazar|mandi|dukaan)[ \\t]+se[ \\t]+" +
        "(?:le[ \\t]+(?:aana|aao|aaiyega|aaiye)|la[ \\t]+do|mangwa[ \\t]+(?:do|dijiye))|" +
        "list[ \\t]+(?:mein|me)))" +
        "(?:[ \\t]*[:;.!?][ \\t]*|[ \\t]+)", RegexOption.IGNORE_CASE,
)
// Complete original editorial actions, not removable words. An inventory
// prelude requires a complete action. No product, amount, property, deadline
// or history slot exists in either language.
private const val EDITORIAL_ENVELOPE_ENGLISH =
    POLITE + "(?:(?:note|write|jot)(?:[ \\t]+down)?" +
        "(?:[ \\t]+$EDITORIAL_ITEMS(?:[ \\t]+down)?)?|" +
        "put[ \\t]+down(?:[ \\t]+$EDITORIAL_ITEMS)?|" +
        "include(?:[ \\t]+$EDITORIAL_ITEMS)?)" +
        "(?:[ \\t]+for[ \\t]+(?:me|us))?"
private const val EDITORIAL_ENVELOPE_HINDI =
    "(?:(?:zara|please|bas)[ \\t]+)?(?:(?:mere|hamare)[ \\t]+liye[ \\t]+)?(?:" +
        "likh[ \\t]+(?:lo|lijiye|lena|do|dena)|" +
        "(?:(?:grocery|shopping)[ \\t]+)?(?:list|note)[ \\t]+bana[ \\t]+(?:do|lo|lijiye)|" +
        "list[ \\t]+(?:mein|me)[ \\t]+likh[ \\t]+(?:lo|lijiye|lena|do|dena))"
private const val EDITORIAL_ENVELOPE_INVENTORY =
    "(?:pantry|kitchen|household|grocery|shopping)[ \\t]+" +
        "(?:check|stock[ \\t]+check|inventory(?:[ \\t]+check)?)" +
        "[ \\t]*[,;:\\u2014\\u2013\\u2012][ \\t]*"
private val INITIAL_EDITORIAL_ENVELOPE = Regex(
    SPOKEN_LIST_GREETING + "(?:(?:$EDITORIAL_ENVELOPE_INVENTORY)?$EDITORIAL_ENVELOPE_ENGLISH|" +
        "$EDITORIAL_ENVELOPE_HINDI)(?:[ \\t]*[:;.!?][ \\t]*|[ \\t]+)", RegexOption.IGNORE_CASE,
)
// Calendar wording must survive as a fulfillment receipt, not neutral filler
// or a promise that a delivery can be scheduled.
private val TIMED_EDITORIAL_FRAME = Regex(
    SPOKEN_LIST_GREETING + "(?:for[ ]+(?:today|tomorrow)'s[ ]+(?:shopping|restock)[ ]*,[ ]*" +
        EDITORIAL_ENVELOPE_ENGLISH + "|(?:aaj|kal)[ ]+ki[ ]+(?:(?:grocery|shopping)[ ]+)?" +
        "list[ ]+(?:mein|me)(?:[ ]+likh[ ]+(?:lo|lijiye))?)", RegexOption.IGNORE_CASE,
)
// Closed editorial narration, bound to the original first-primary prefix.
// A separator is mandatory; the object/style cannot contain grocery facts,
// history preferences or delivery conditions. Never apply to masked leftovers.
private const val EDITORIAL_REFERENCE =
    "(?:it|this|these|them|(?:the|my|our|this|these)[ \\t]+(?:list|items|groceries))"
private val INITIAL_LIST_NARRATION = Regex(
    "(?:" +
        "(?:(?:i|we)[ \\t]+have[ \\t]+(?:written|noted|listed|jotted)|" +
        "(?:i|we)[ \\t]+(?:wrote|noted|listed|jotted))[ \\t]+" +
        "(?:$EDITORIAL_REFERENCE(?:[ \\t]+down)?|down[ \\t]+$EDITORIAL_REFERENCE)|" +
        "(?:(?:let's|let[ \\t]+us|we[ \\t]+can|please|kindly)[ \\t]+)?keep[ \\t]+" +
        "(?:the|my|our|this)[ \\t]+list[ \\t]+(?:simple|clear|straightforward)|" +
        "(?:let's|let[ \\t]+us)[ \\t]+keep[ \\t]+(?:it|this)[ \\t]+(?:simple|straightforward)" +
        ")[ \\t]*[:;.!?][ \\t]*", RegexOption.IGNORE_CASE,
)
// Closed grammatical slots compose a complete request, not removable words.
// No product/diet/amount/history slot; all timing survives as a review receipt.
private const val FRAME_LIST_NOUN =
    "(?:(?:household|grocery|shopping)[ ]+)?(?:list|items|things|groceries|basics|essentials|supplies)"
private const val FRAME_LIST_REFERENCE =
    "(?:these(?:[ ]+$FRAME_LIST_NOUN)?|" +
        "(?:the[ ]+)?following(?:[ ]+$FRAME_LIST_NOUN)?|" +
        "(?:(?:my|our|the|this)[ ]+|a[ ]+few[ ]+)?$FRAME_LIST_NOUN)"
private const val FRAME_ENGLISH_ACTION =
    "(?:$REQUEST[ ]+(?:(?:me|us)[ ]+)?$FRAME_LIST_REFERENCE|" +
        "(?:i|we)[ ]+(?:need|want)[ ]+$FRAME_LIST_REFERENCE|" +
        "(?:note|write)(?:[ ]+down)?[ ]+$FRAME_LIST_REFERENCE(?:[ ]+down)?|" +
        "(?:note|write)(?:[ ]+down)?)"
private const val FRAME_ENGLISH_TIME =
    "(?:today|tomorrow(?:[ ]+(?:morning|evening))?|this[ ]+(?:morning|evening)|tonight)"
private val FRAME_ENGLISH = Regex(
    SPOKEN_LIST_GREETING + "(?:for[ ]+(?:(?:the|our)[ ]+shopping[ ]+run|" +
        "(?<shopping>today|tomorrow|this[ ]+(?:week|month)|next[ ]+(?:week|month))'s[ ]+shopping)[ ]*,[ ]*)?" +
        "(?:(?<before>$FRAME_ENGLISH_TIME)[ ]*,?[ ]+)?$POLITE$FRAME_ENGLISH_ACTION" +
        "(?:[ ]+exactly[ ]+as[ ]+listed)?" +
        "(?:[ ]+for[ ]+(?:me|us|home|(?:my|our|the)[ ]+(?:home|house|household)))?" +
        "(?:[ ]+from[ ]+(?:the[ ]+)?(?:shop|store|market))?" +
        "(?:[ ]+(?<after>$FRAME_ENGLISH_TIME))?" +
        "(?:[ ]*,?[ ]+(?:please|kindly))?", RegexOption.IGNORE_CASE,
)
private const val FRAME_HINDI_LIST =
    "(?:(?:ye|yeh)[ ]+(?:sab(?:[ ]+(?:items|cheezen|cheezein))?|saman|samaan|saamaan|items|cheezen|cheezein)|" +
        "(?:(?:meri|hamari)[ ]+)?(?:(?:shopping|grocery)[ ]+)?list|saman|samaan|saamaan)"
private const val FRAME_HINDI_EDIT =
    "(?:(?:note[ ]+kar|add[ ]+kar)[ ]+(?:lo|lijiye|do|dena|dijiye)|likh[ ]+(?:lo|lijiye|do|dena|lena)|daal?[ ]+(?:do|dena|dijiye))"
private const val FRAME_HINDI_ACTION = "(?:$HINDI_REQUEST|$FRAME_HINDI_EDIT|chahiye|lena[ ]+hai)"
private const val FRAME_HINDI_TIME = "(?:(?:aaj|kal)(?:[ ]+(?:subah|shaam))?|subah|shaam)"
private val FRAME_HINDI = Regex(
    SPOKEN_LIST_GREETING + "(?:(?<before>$FRAME_HINDI_TIME)[ ]+)?" + HINDI_COURTESY + HINDI_DESTINATION +
        "(?:(?:bazaar|bazar|mandi|dukaan|dukan)[ ]+se[ ]+)?$HINDI_COURTESY" +
        "(?:list[ ]+(?:mein|me)[ ]+)?$FRAME_HINDI_LIST[ ]+$FRAME_HINDI_ACTION" +
        "(?:[ ]+(?<after>$FRAME_HINDI_TIME))?" +
        "(?:[ ]*,[ ]*(?:(?:please|zara)[ ]+)?(?:$FRAME_HINDI_EDIT|" +
        "(?:add|note)[ ]+(?:it|these)(?:[ ]+down)?))?" +
        "(?:[ ]*,?[ ]+(?:please|ji|na))?", RegexOption.IGNORE_CASE,
)
// Also used for closed-request role classification; no timing, source, product,
// quantity or conditional slots may be added without reviewing that boundary.
private const val DIRECT_LIST_REQUEST_GAP =
    "[ ]*(?:(?:please|kindly)[ ]+)?" +
        "(?:(?:can|could|would)[ ]+you[ ]+(?:(?:please|kindly)[ ]+)?)?" +
        "(?:get|bring|buy|order|add|pick[ ]+up)[ ]+"
private val DIRECT_LIST_REQUEST = Regex(DIRECT_LIST_REQUEST_GAP, RegexOption.IGNORE_CASE)
// Present-tense narration about the owner's document, never delivery placement.
private val EDITORIAL_PLACEMENT_PREFIX = Regex(
    "[ ]*(?:i[ ]+am|i'm|we[ ]+are|we're)[ ]+(?:leaving|placing|putting)[ ]+" +
        EDITORIAL_DOCUMENT + "[ ]+on[ ]+(?:the|my|our)[ ]+" +
        "(?:(?:kitchen|dining|study)[ ]+)?(?:table|desk)[ ]*[.;!?][ ]+" +
        DIRECT_LIST_REQUEST_GAP,
    RegexOption.IGNORE_CASE,
)
private val EDITORIAL_SECTION_ORDER = Regex(
    "[ \\t]*(?:i|we)[ \\t]+(?:wrote|listed|noted(?:[ \\t]+down)?|" +
        "have[ \\t]+(?:written|listed|noted(?:[ \\t]+down)?))[ \\t]+(?:the[ \\t]+)?" +
        "(?<first>[A-Za-z]+(?:[ \\t]+[A-Za-z]+){0,5}?)[ \\t]+first[ \\t]+" +
        "(?:and[ \\t]+)?then[ \\t]+(?:the[ \\t]+)?" +
        "(?<second>[A-Za-z]+(?:[ \\t]+[A-Za-z]+){0,5}?)[ \\t]*[.!]", RegexOption.IGNORE_CASE,
)
private const val EDITORIAL_SECTION_NOUN = "(?:goods|items|things|products|section)"
private val EDITORIAL_SECTION_UNSAFE = ("no not without except unless if only instead and or but each total altogether " +
    "first then tomorrow today yesterday before after").split(" ").toSet()
private const val LIST_GRATITUDE = "(?:(?:thank[ \\t]+you|thanks)(?:[ \\t]+very[ \\t]+much)?|" +
    "dhanyavaad|shukriya|appreciate[ \\t]+it|much[ \\t]+appreciated)"
private const val LIST_COMPLETION = "(?:that(?:'s|[ \\t]+is)[ \\t]+(?:all|everything)(?:[ \\t]+for[ \\t]+today)?|" +
    "that[ \\t]+(?:should|will)[ \\t]+cover[ \\t]+(?:this|the)[ \\t]+(?:week|month)|" +
    "(?:theek|thik)[ \\t]+hai[ \\t]*,[ \\t]*bas|" +
    "bas(?:[ \\t]+(?:itna|itni|itne|yahi|yehi)(?:[ \\t]+hi)?)?)"
private val LIST_ENDING = Regex(
    "(?:$LIST_COMPLETION(?:[ \\t]*[,.!?][ \\t]*$LIST_GRATITUDE)?|" +
        "$LIST_GRATITUDE(?:[ \\t]*[,.!?][ \\t]*(?:$LIST_COMPLETION|bas))?)" +
        "(?:[ \\t]*,[ \\t]*(?:beta|bhaiya|ji))?[ \\t]*[.!?]*[ \\t]*",
    RegexOption.IGNORE_CASE,
)
private val TERMINAL_HELP_GRATITUDE = Regex(
    "(?:thanks|thank[ \\t]+you)(?:[ \\t]+very[ \\t]+much)?[ \\t]+for[ \\t]+" +
        "(?:helping(?:[ \\t]+(?:me|us))?|(?:sorting|arranging|noting)[ \\t]+" +
        "(?:it|this|(?:the|my|our)[ \\t]+list))[ \\t]*[.!?]*[ \\t]*", RegexOption.IGNORE_CASE,
)
private val LIST_PERIOD_ENDING = Regex("[ \\t]+$SHOPPING_PERIOD[ \\t]*[.!?]?[ \\t]*", RegexOption.IGNORE_CASE)
// Entire original terminal suffix only, never stopwords inside product/condition text.
private val LIST_ADDITIVE_ENDING = Regex("(?:[ \\t]+|[ \\t]*,[ \\t]*)(?:as[ \\t]+well|too|bhi)[ \\t]*[.!?]?[ \\t]*", RegexOption.IGNORE_CASE)
private val LIST_RESUMPTION = Regex(
    "[ \\t]*[.,;!?]?[ \\t]*(?:$LIST_GRATITUDE[ \\t]*[.,;!?]?[ \\t]+)?" +
        "(?:and[ \\t]+)?(?:$POLITE(?:also[ \\t]+)?(?:add|include|buy|get|bring|pick[ \\t]+up)|" +
        "(?:to[ \\t]+)?(?:round[ \\t]+(?:it|(?:the|this|my|our)[ \\t]+list)[ \\t]+off[ \\t]+with|" +
        "finish[ \\t]+(?:it|(?:the|this|my|our)[ \\t]+list)[ \\t]+with|" +
        "complete[ \\t]+(?:the|this|my|our)[ \\t]+list[ \\t]+with)|followed[ \\t]+by)[ \\t]+",
    RegexOption.IGNORE_CASE,
)
private val HINDI_PREFIX = Regex(HINDI_COURTESY + HINDI_DESTINATION + HINDI_ORIGIN, RegexOption.IGNORE_CASE)
private val HINDI_SUFFIX = Regex(
    "(?:$HINDI_COURTESY$HINDI_REQUEST|(?:(?:zara|please)[ \\t]+)?$HINDI_CART_REQUEST)(?:\\s+please)?",
    RegexOption.IGNORE_CASE,
)
private val SOCIAL_PRELUDE = Regex(
    "(?:(?:(?:breakfast|lunch|dinner)\\s+)?(?:guests|mehman|mehmaan)\\s+aa\\s+rahe\\s+hain|" +
        "(?:(?:my|our|the)\\s+)?guests\\s+are\\s+coming(?:\\s+for\\s+(?:breakfast|lunch|dinner))?)",
    RegexOption.IGNORE_CASE,
)
private val HOUSEHOLD_PRELUDE = Regex(
    "for\\s+$HOUSEHOLD_OWNER(?:home|house|household|kitchen|bathroom|pantry)",
    RegexOption.IGNORE_CASE,
)
private val DAY_GREETING = Regex("good[ \\t]+(?:morning|afternoon|evening)", RegexOption.IGNORE_CASE)
private val SENTENCE_END = Regex("[;!?\\r\\n]|\\.(?=\\s|$)")
// Closed document/origin slots plus a count backed by independently verified
// later corrections. No arbitrary prefix, product facts or raw-proposal grant.
private const val ATTRIBUTED_LIST =
    "(?:(?:my|our|the)[ \\t]+)?(?:(?:mother|father|mom|mum|mummy|dad|daddy|wife|husband|son|daughter|family)'s|" +
        "(?:parents|grandparents|children|kids)')[ \\t]+(?:(?:grocery|shopping)[ \\t]+)?(?:list|note)"
private val CORRECTION_ANNOUNCEMENT = Regex(
    "[ \\t]*(?:(?:$EDITORIAL_DOCUMENT|$ATTRIBUTED_LIST)" +
        "(?:[ \\t]+from[ \\t]+(?:the|my|our)[ \\t]+(?:phone|message|notebook))?[ \\t]*,[ \\t]*)?" +
        "(?:with[ \\t]+)?(?<count>one|two|three|four|five|six|seven|eight|nine|ten|[1-9][0-9]?)" +
        "[ \\t]+corrections?[ \\t]+(?:at[ \\t]+the[ \\t]+end|below)[ \\t]*[:;.!?][ \\t]*", RegexOption.IGNORE_CASE,
)
private val CORRECTION_HEADING = Regex(
    "(?:^|[;!?\\r\\n]|\\.(?=\\s))(\\s*corrections?\\s*[:：]\\s*)$",
    RegexOption.IGNORE_CASE,
)
private val CORRECTION_UNCHANGED_TAIL = Regex(
    "\\s*;\\s*(?:everything\\s+else\\s+stays\\s+(?:as\\s+first\\s+listed|unchanged)|" +
        "leave\\s+everything\\s+else\\s+unchanged|" +
        "(?:baaki|baki)\\s+(?:sab\\s+)?(?:same(?:\\s+hai)?|" +
        "waise\\s+hi\\s+(?:rehne\\s+do|rakhna|rakh\\s+do)|" +
        "jaisa\\s+likha\\s+hai\\s+waisa\\s+hi\\s+rehne\\s+do))\\s*[.!]?\\s*",
    RegexOption.IGNORE_CASE,
)

private const val OTHER_LIST_INTRODUCTIONS =
    "(?:my\\s+|our\\s+|the\\s+)?(?:grocery|shopping)\\s+list|" +
        HINDI_INTRODUCTION + "|" +
        "(?:zara\\s+)?meri\\s+baat\\s+dhyan\\s+se\\s+likh\\s+lo|" +
        HINDI_WRITTEN_INTRODUCTION + "|" + HINDI_WRITING_REQUEST

private val LEGACY_LIST_INTRODUCTION = Regex(
    "(?:$POLITE_REQUEST_LIST|$OTHER_LIST_INTRODUCTIONS)",
    RegexOption.IGNORE_CASE,
)
private val LIST_INTRODUCTION = Regex(
    "(?:$POLITE_REQUEST_LIST|$OTHER_LIST_INTRODUCTIONS|$NEW_HEADINGS)", RegexOption.IGNORE_CASE,
)
private val POLITE_INTRODUCTION = Regex(
    "(?:$POLITE_REQUEST_LIST|$OTHER_LIST_INTRODUCTIONS|$NEW_HEADINGS)[ \\t]*,[ \\t]*(?:please|kindly)",
    RegexOption.IGNORE_CASE,
)
// No meal/room/brand/diet/deadline slots: those headings need semantic ownership.
private val HINDI_HOUSEHOLD_HEADING = Regex(
    "(?:(?:sun|suno|suniye)[ \\t]*,[ \\t]*)?(?:(?:bazaar|bazar|mandi)[ \\t]+se[ \\t]+)?" +
        "(?:(?:ghar|mahine|hafte)[ \\t]+(?:ka|ki)[ \\t]+list|" +
        "(?:ghar[ \\t]+ka[ \\t]+)?(?:saman|samaan|saamaan)[ \\t]+ki[ \\t]+list)",
    RegexOption.IGNORE_CASE,
)
private val CORRECTION_CONNECTIVE = Regex("(?:[ \\t]+[,;]?[ \\t]*|[,;][ \\t]*)(?:aur|and)[ \\t]+", RegexOption.IGNORE_CASE)
private val CORRECTION_CLOSED_TAIL = Regex(
    "(?:\\b(?:g|kg|grams?|kilograms?|kilos?|ml|l|litres?|liters?|" +
        "packs?|packets?|tubs?|bottles?|cartons?|cups?|jars?|boxes|box|bars?|pieces?|pcs|" +
        "hi|(?:kar|rakh)[ \\t]+do|dena|karna|rakhna|rakho|lena|lo|chahiye)|(?<=[0-9])(?:g|kg|ml|l))[ \\t]*$",
    RegexOption.IGNORE_CASE,
)
private val ANAPHORIC_CLOSER = Regex(
    "$POLITE$REQUEST\\s+(?:(?:these|those)|everything\\s+on\\s+(?:this|the|my|our)\\s+list)" +
        "(?:\\s+$LIST_DESTINATION)?|" +
        "$HINDI_COURTESY$HINDI_DESTINATION(?:yeh|ye)\\s+sab\\s+$HINDI_REQUEST",
    RegexOption.IGNORE_CASE,
)

private val NEUTRAL_CLAUSE = Regex(
    "(?:" +
        "(?:(?:namaste|namaskar|hello|hi)(?:\\s+(?:beta|bhaiya|ji))?|haan\\s+ji|(?:arre\\s+)?(?:bhai|bhaiya)(?:\\s+ji)?)|" +
        "$ORDINARY_POLITE_REQUEST_LIST|" +
        "$OTHER_LIST_INTRODUCTIONS|" +
        "for\\s+(?:home|(?:my|our|the)\\s+(?:home|house|household))|" +
        "$WHEN_MOMENT|no\\s+rush|" +
        "(?:thank\\s+you|thanks|bahut\\s+dhanyavaad)" +
        ")",
    RegexOption.IGNORE_CASE,
)

private val REQUEST_PREFIX = Regex(
    "$GROCERY_FIRST_PERSON_REQUEST|(?:so[ \\t]+)?(?:$WHEN_MOMENT\\s+)?$POLITE$REQUEST(?:\\s+(?:me|us|home))?",
    RegexOption.IGNORE_CASE,
)
private val FIRST_PERSON_REQUEST = Regex(GROCERY_FIRST_PERSON_REQUEST, RegexOption.IGNORE_CASE)
