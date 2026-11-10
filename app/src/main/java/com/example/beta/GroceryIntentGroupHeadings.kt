package com.example.beta

import java.util.Locale

internal data class GroceryIntentVariantCardinalityHeading(val identity: String, val count: Int)

private const val POSTFIX_WORD = "[A-Za-z]+(?:['-][A-Za-z]+)*"
private const val POSTFIX_IDENTITY = "$POSTFIX_WORD(?:[ \\t]+$POSTFIX_WORD){0,5}"
private val POSTFIX_PATTERNS = listOf(
    "(?i:all[ \\t]+from)[ \\t]+($POSTFIX_IDENTITY)",
    "(?i:sab|sabhi)[ \\t]+($POSTFIX_IDENTITY)[ \\t]+(?i:ke)",
).map(::Regex)
private val POSTFIX_UNSAFE = ("all from sab sabhi ke ki ka and aur or ya except unless if only not no non without " +
    "instead but neither either nahi nahin mat bina bagair sirf agar total each altogether kul").split(" ").toSet()

/** Literal identity only. Callers independently prove complete trailing scope. */
internal fun groceryIntentPostfixIdentityHeading(header: String): String? {
    if (header.length > 180 || header.any { it.code > 127 }) return null
    val bounded = header.trim(' ', '\t').removeSuffix(".").trimEnd(' ', '\t')
    val identity = POSTFIX_PATTERNS.firstNotNullOfOrNull { it.matchEntire(bounded)?.groupValues?.get(1) } ?: return null
    if (postfixIdentityWords(identity).any { it in POSTFIX_UNSAFE } || groceryIntentContainsNumericAtom(identity)) return null
    return identity
}

internal fun postfixIdentityWords(text: String): Set<String> = Regex("[a-z]+")
    .findAll(text.lowercase(Locale.ROOT)).map { it.value }.toSet()

internal data class GroceryIntentPostfixScope(val left: Int, val right: Int, val start: Int, val end: Int, val text: String)

/** Denial-only candidate scopes; never positive evidence or model-chosen scope. */
internal fun groceryIntentPostfixIdentityCandidates(instruction: String): List<GroceryIntentPostfixScope> {
    val result = mutableListOf<GroceryIntentPostfixScope>()
    var left = 0
    val boundaries = Regex("[;!?]|\\.(?=[ \\t\\n\\r\\f\\x0B]|$)").findAll(instruction).map { it.range.first to it.range.last + 1 }.toList() +
        (instruction.length to instruction.length)
    for ((right, next) in boundaries) {
        // Denial survives trailing punctuation, emoji, numbers and newlines.
        Regex("(?<![A-Za-z0-9_])(?:all|sab|sabhi)[ \\t]+$POSTFIX_WORD(?:[ \\t]+$POSTFIX_WORD)*",
            RegexOption.IGNORE_CASE).findAll(instruction.substring(left, right)).forEach { marker ->
            val start = left + marker.range.first
            val text = marker.value
            result += GroceryIntentPostfixScope(left, right, start, start + text.length, text)
        }
        left = next
    }
    return result
}

/** Position/amount preconditions; complete clause and member checks are in validateGroups. */
internal fun GroceryIntentReviewV2Group.postfixIdentity(): String? = header?.takeIf {
    it.startUtf16 > source.startUtf16 && total == null && each == null
}?.let { groceryIntentPostfixIdentityHeading(it.text) }

/** A source count of listed varieties, never a purchase total or a default member quantity. */
internal fun groceryIntentVariantCardinalityHeading(header: String): GroceryIntentVariantCardinalityHeading? {
    val matched = variantCardinalityMatch(header) ?: return null
    val identity = requireNotNull(matched.groups["identity"]).value.trim()
    val amount = requireNotNull(matched.groups["amount"]).value.trim()
    if (identity.isEmpty() || amount.isEmpty() || amount.length > 64 ||
        identity.any { !it.isLetter() && !it.isWhitespace() && it !in "'’.-" } ||
        groceryIntentContainsNumericAtom(identity) || VARIETY_PHYSICAL_UNIT.containsMatchIn(identity) ||
        amount.any { it in "/.,-" }) return null
    val count = parseIntentCorrectionAmount(amount) ?: return null
    if (count.stripTrailingZeros().scale() > 0 || count < 2.toBigDecimal() || count > 20.toBigDecimal()) return null
    return GroceryIntentVariantCardinalityHeading(identity, count.toInt())
}

internal fun groceryIntentHasVariantCardinalityShape(header: String): Boolean =
    header.length <= 180 && header.none { it in "\r\n;" } && groceryIntentContainsNumericAtom(header) &&
        (variantCardinalityMatch(header) != null ||
            BARE_VARIETY.matches(header.trim().trimEnd(',', ':', '：', '—', '–', '‒', '-').trim()))

private fun variantCardinalityMatch(header: String): MatchResult? {
    if (header.length > 180 || header.any { it in "\r\n;" }) return null
    val bounded = header.trim().trimEnd(',', ':', '：', '—', '–', '‒', '-').trim()
    return VARIETY_PATTERNS.firstNotNullOfOrNull { it.matchEntire(bounded) }
}

/** Used only after the DTO independently binds every member and proves its amount. */
internal fun GroceryIntentReviewV2Group.variantCardinalityIdentity(): String? {
    val proof = header?.let { groceryIntentVariantCardinalityHeading(it.text) } ?: return null
    return proof.identity.takeIf {
        total == null && proof.count == members.size && members.all { member -> member.value != null }
    }
}

internal fun GroceryIntentReviewV2Group.semanticHeader(
    instruction: String? = null,
    primarySources: List<Pair<Int, String>>? = null,
): String? =
    if (header?.let { groceryIntentPurposeGroupHeading(it.text) } != null ||
        hasNeutralNarrationHeading(instruction, primarySources)) null
    else postfixIdentity() ?: variantCardinalityIdentity() ?:
        header?.let {
            if (groceryIntentOrganizationalCategoryHeading(it.text) != null) it.text
            else groceryIntentGroupRequestIdentity(it.text) ?: it.text
        }

/** A postfix literal identity cannot supply category aliases or drop collective nouns. */
internal fun GroceryIntentReviewV2Group.categoryHeader(
    instruction: String? = null,
    primarySources: List<Pair<Int, String>>? = null,
): String? = semanticHeader(instruction, primarySources).takeIf { postfixIdentity() == null }

/**
 * A complete independently recognized list introduction supplies no product facts.
 * Reuse the original-context proof, never strip words from a model-chosen header.
 * Keep the unmodified header for review; this grants no amount or identity evidence.
 */
internal fun GroceryIntentReviewV2Group.hasNeutralNarrationHeading(
    instruction: String?,
    primarySources: List<Pair<Int, String>>?,
): Boolean {
    val original = header ?: return false
    if (instruction == null || primarySources == null || total != null || each != null || packaging != null ||
        status != "structurally_verified" || memberIndices.size !in 2..20 ||
        memberIndices.distinct().size != memberIndices.size || members.map { it.rowIndex } != memberIndices ||
        primarySources.size !in 2..50 || primarySources.map { it.first }.distinct().size != primarySources.size ||
        source.startUtf16 != original.startUtf16 || source.startUtf16 !in 0..instruction.length ||
        source.endUtf16 !in source.startUtf16..instruction.length ||
        original.endUtf16 !in original.startUtf16..source.endUtf16 || source.text.length > 1500 ||
        instruction.substring(source.startUtf16, source.endUtf16) != source.text ||
        instruction.substring(original.startUtf16, original.endUtf16) != original.text ||
        original.text.length !in 1..180) return false
    val spans = primarySources.map { (index, text) ->
        if (text.isEmpty()) return false
        val start = instruction.indexOf(text)
        if (start < 0 || instruction.indexOf(text, start + 1) >= 0) return false
        index to GroceryIntentCoverageSpan(start, start + text.length)
    }
    if (spans.map { it.second }.sortedBy { it.startUtf16 }.zipWithNext().any { (left, right) ->
            left.endUtf16 > right.startUtf16
        }) return false
    val contained = spans.filter { (_, span) ->
        span.startUtf16 >= original.endUtf16 && span.endUtf16 <= source.endUtf16
    }
    if (contained.map { it.first }.toSet() != memberIndices.toSet() || spans.any { (index, span) ->
            span.startUtf16 < source.endUtf16 && source.startUtf16 < span.endUtf16 && index !in memberIndices
        }) return false
    return neutralGroceryIntentContextSpans(instruction, spans.map { it.second }).any { neutral ->
        neutral.startUtf16 < original.endUtf16 && original.startUtf16 < neutral.endUtf16 &&
            instruction.substring(minOf(neutral.startUtf16, original.startUtf16),
                maxOf(neutral.startUtf16, original.startUtf16)).isBlank() &&
            instruction.substring(minOf(neutral.endUtf16, original.endUtf16),
                maxOf(neutral.endUtf16, original.endUtf16)).isBlank()
    }
}

/** Only the proved descriptor's own spelling/plural family, never new flavour identity. */
internal fun GroceryIntentReviewV2Group.variantCardinalityDescriptorWords(): Set<String> {
    if (variantCardinalityIdentity() == null) return emptySet()
    val descriptor = header?.let { variantCardinalityMatch(it.text)?.groups?.get("kind")?.value }
    return when (descriptor?.lowercase(Locale.ROOT)) {
        "flavor", "flavors", "flavour", "flavours" -> setOf("flavor", "flavors", "flavour", "flavours")
        "type", "types" -> setOf("type", "types")
        "variety", "varieties" -> setOf("variety", "varieties")
        "tarah" -> setOf("tarah")
        else -> emptySet()
    }
}

private const val VARIETY_KIND = "(?:flavou?rs?|types?|variet(?:y|ies))"
private val BARE_VARIETY = Regex(".+?\\s+$VARIETY_KIND", RegexOption.IGNORE_CASE)
private val VARIETY_PATTERNS = listOf(
    "(?<identity>.+?)\\s+ke\\s+(?<amount>.+?)\\s+(?<kind>$VARIETY_KIND)",
    "(?<amount>.+?)\\s+(?<kind>$VARIETY_KIND)\\s+of\\s+(?<identity>.+)",
    "(?<amount>.+?)\\s+(?<kind>tarah)\\s+ke\\s+(?<identity>.+)",
    "(?<identity>.+?)\\s+in\\s+(?<amount>.+?)\\s+(?<kind>$VARIETY_KIND)",
).map { Regex(it, RegexOption.IGNORE_CASE) }
private val VARIETY_PHYSICAL_UNIT = Regex(
    "\\b(?:kg|kgs|kilograms?|keji|g|grams?|ml|millilitres?|milliliters?|l|litres?|liters?)\\b", RegexOption.IGNORE_CASE,
)

/**
 * A complete request wrapper is not shared product identity. Call only with
 * the original verified header, before removing any numeric or other evidence.
 * Keep the original header for review; this does not prove amounts or products.
 */
internal fun groceryIntentGroupRequestIdentity(header: String): String? {
    if (header.length > 180 || header.any { it.code > 0x7f || it == '\n' || it == '\r' }) return null
    // One complete grammar match only, never repeated stop-word stripping.
    // Bare "From ..." can be a brand, so require the explicit English request.
    // All numbers, properties and unknown tails remain literal.
    val bounded = header.trim()
    GROUP_REQUEST_HEADERS.firstNotNullOfOrNull { it.matchEntire(bounded) }?.let {
        return it.groupValues[1].trim().takeIf(String::isNotEmpty)
    }
    // Complete lowercase Hindi locative with delimiter, not words removed from
    // an arbitrary product. Chow/Lo Mein remains a literal noodle identity.
    val within = GROUP_WITHIN_HEADER.matchEntire(bounded) ?: return null
    return within.groupValues[1].trim().takeUnless {
        Regex("\\b(?:chow|lo)$", RegexOption.IGNORE_CASE).containsMatchIn(it)
    }
}

/**
 * The closed continuation "<owned item>, and from <identity>" supplies a request
 * preposition, not a brand word. Standalone/capitalized From stays literal.
 * Inspect original offsets and the complete gap: masking an omitted item or
 * condition first must never manufacture this continuation. Amounts remain local.
 */
internal fun GroceryIntentReviewV2Group.contextualFromRequestIdentity(
    instruction: String?,
    primarySources: List<Pair<Int, String>>?,
): String? {
    val original = header ?: return null
    if (instruction == null || primarySources == null || !hasEligibleLocalQuantityHeading() ||
        status != "structurally_verified" || source.startUtf16 != original.startUtf16 ||
        original.startUtf16 !in 0..instruction.length || original.endUtf16 !in original.startUtf16..instruction.length ||
        instruction.substring(original.startUtf16, original.endUtf16) != original.text ||
        original.text.length > 180 || original.text.any { it.code > 127 }) return null
    val identity = CONTEXTUAL_FROM_HEADER.matchEntire(original.text.trim(' ', '\t'))
        ?.groupValues?.get(1) ?: return null
    if (groceryIntentContainsNumericAtom(identity) || postfixIdentityWords(identity).any { it in POSTFIX_UNSAFE }) return null
    val priorEnds = primarySources.filter { it.first !in memberIndices }.mapNotNull { (_, primary) ->
        if (primary.isEmpty()) return@mapNotNull null
        val start = instruction.indexOf(primary)
        if (start < 0 || instruction.indexOf(primary, start + 1) >= 0) return@mapNotNull null
        (start + primary.length).takeIf { it <= original.startUtf16 }
    }
    val priorEnd = priorEnds.maxOrNull() ?: return null
    return identity.takeIf { CONTEXTUAL_FROM_JOIN.matches(instruction.substring(priorEnd, original.startUtf16)) }
}

private val CONTEXTUAL_FROM_HEADER = Regex("from[ \\t]+($POSTFIX_IDENTITY)[ \\t]*[,:]?")
private val CONTEXTUAL_FROM_JOIN = Regex(",[ \\t]+and[ \\t]+")

internal data class GroceryIntentAffirmedHeading(
    val identity: String,
    val transition: GroceryIntentCoverageSpan,
)

/**
 * A sentence-edge listing transition followed by a complete, comma-delimited
 * affirmative aside is discourse, not another product descriptor. Keep the
 * original header on the group; only its literal identity is used for matching.
 * This is NOT a global stop-word rule or permission to resolve a correction.
 */
internal fun GroceryIntentReviewV2Group.contextualAffirmedHeading(
    instruction: String?,
    primarySources: List<Pair<Int, String>>?,
): GroceryIntentAffirmedHeading? {
    val original = header ?: return null
    if (instruction == null || primarySources == null || !hasEligibleLocalQuantityHeading() ||
        status != "structurally_verified" || memberIndices.size !in 2..20 ||
        memberIndices.distinct().size != memberIndices.size || members.map { it.rowIndex } != memberIndices ||
        primarySources.size !in 2..50 || primarySources.map { it.first }.distinct().size != primarySources.size ||
        source.startUtf16 != original.startUtf16 || source.startUtf16 !in 0..instruction.length ||
        source.endUtf16 !in source.startUtf16..instruction.length ||
        original.endUtf16 !in original.startUtf16..source.endUtf16 || source.text.length > 1500 ||
        instruction.substring(source.startUtf16, source.endUtf16) != source.text ||
        instruction.substring(original.startUtf16, original.endUtf16) != original.text ||
        original.text.length > 180 || original.text.any { it.code > 127 }) return null
    // Lowercase and BOTH commas are intentional: a bare/capitalized Haan may
    // be literal identity. Unknown words, numbers and tails remain unhandled.
    val identity = AFFIRMED_HEADER.matchEntire(original.text)?.groupValues?.get(1) ?: return null
    if (groceryIntentContainsNumericAtom(identity) || intentV2HasCorrectionCue(identity) ||
        postfixIdentityWords(identity).any { it in POSTFIX_UNSAFE }) return null
    val spans = primarySources.map { (index, text) ->
        if (text.isEmpty()) return null
        val start = instruction.indexOf(text)
        if (start < 0 || instruction.indexOf(text, start + 1) >= 0) return null
        Triple(index, start, start + text.length)
    }
    if (spans.indices.any { left -> spans.indices.any { right ->
            left < right && spans[left].second < spans[right].third && spans[right].second < spans[left].third
        } }) return null
    val contained = spans.filter { it.second >= original.endUtf16 && it.third <= source.endUtf16 }
    if (contained.map { it.first }.toSet() != memberIndices.toSet() || spans.any {
            it.second < source.endUtf16 && source.startUtf16 < it.third && it.first !in memberIndices
        }) return null
    val scopeStart = Regex("[;!?\\r\\n]|\\.(?=\\s|$)")
        .findAll(instruction.substring(0, source.startUtf16)).lastOrNull()?.range?.last?.plus(1) ?: 0
    val transition = instruction.substring(scopeStart, source.startUtf16)
    if (transition.length > 64 || !AFFIRMED_TRANSITION.matches(transition)) return null
    return GroceryIntentAffirmedHeading(identity, GroceryIntentCoverageSpan(scopeStart, source.startUtf16))
}

internal fun GroceryIntentReviewV2Group.contextualAffirmedHeadingIdentity(
    instruction: String?,
    primarySources: List<Pair<Int, String>>?,
): String? = contextualAffirmedHeading(instruction, primarySources)?.identity

private val AFFIRMED_HEADER = Regex("[ \\t]*($POSTFIX_IDENTITY)[ \\t]*,[ \\t]+haan[ \\t]*,[ \\t]*")
private val AFFIRMED_TRANSITION = Regex("[ \\t]*(?i:then|phir|fir)[ \\t]+")

/** A closed organizational produce heading; it confers category scope, not identity or amount. */
internal fun groceryIntentOrganizationalCategoryHeading(header: String): String? {
    if (header.length > 180) return null
    val asciiLower = buildString(header.length) {
        header.forEach { char -> append(if (char in 'A'..'Z') char + 32 else char) }
    }
    val category = ORGANIZATIONAL_CATEGORY_HEADING.matchEntire(asciiLower)?.groupValues?.get(1) ?: return null
    return when (category) {
        "fruit", "fruits", "phal" -> "fruits"
        "spices", "masale" -> "spices"
        else -> "vegetables"
    }
}

private const val PURPOSE_ROOM = "(?:kitchen|pantry|cupboard|bathroom|washroom|sink(?:[ ]+area)?|bedroom|living[ ]+room)"
private const val PURPOSE_ACTIVITY = "(?:breakfast|lunch|dinner|(?:(?:morning|evening)[ ]+)?tea|snacks|cleaning|bathing|laundry)"
private const val PURPOSE_LABEL =
    "[ ]*(?:(?:and|aur|phir)[ ]+)?(?:" +
        "for[ ]+(?:the[ ]+)?" + PURPOSE_ACTIVITY +
        "|(?:for|in|by|near)[ ]+(?:the[ ]+)?" + PURPOSE_ROOM +
        "|" + PURPOSE_ROOM + "(?:[ ]+(?:side|section))?" +
        "|(?:nashta|safai|nahane|kapde[ ]+dhone)[ ]+(?:ke[ ]+liye|ka[ ]+samaan)" +
        "|(?:rasoi|kitchen|pantry|bathroom|washroom)[ ]+(?:ke[ ]+liye|ka[ ]+samaan)" +
        ")"
private val PURPOSE_HEADING = Regex(
    PURPOSE_LABEL + "(?:[ ]*[:：—–‒][ ]*|[ ]*,[ ]*(?:(?:please|kindly)[ ]+)?(?:get|add|bring|buy|include)[ ]*)",
)
private const val PURPOSE_SLOT = "(?:the[ ]+)?(?:$PURPOSE_ACTIVITY|$PURPOSE_ROOM)"
private const val PURPOSE_SEQUENCE = "$PURPOSE_SLOT(?:(?:[ ]+and[ ]+|[ ]*,[ ]*(?:and[ ]+)?)$PURPOSE_SLOT){0,3}"
private const val PURPOSE_COURTESY = "(?:(?:please|kindly)[ ]+)?(?:(?:can|could|would)[ ]+you[ ]+(?:(?:please|kindly)[ ]+)?)?"
private const val PURPOSE_ITEMS = "(?:(?:the|these|those|my|our)[ ]+|a[ ]+few[ ]+)?(?:things|items|groceries|list)"
private const val PURPOSE_ROOM_SLOT = "(?:the[ ]+)?$PURPOSE_ROOM"
private const val PURPOSE_ROOM_SEQUENCE = "$PURPOSE_ROOM_SLOT(?:(?:[ ]+and[ ]+|[ ]*,[ ]*(?:and[ ]+)?)$PURPOSE_ROOM_SLOT){0,3}"
// Retained restocking-list purpose only, never quantity/product/substitution evidence.
// No numeric, dietary, brand or alternative slot is accepted here.
private const val PURPOSE_NOMINAL =
    "(?:(?:a[ ]+few|some|these|the|my|our)[ ]+)?" +
        "(?:(?:groceries|items|supplies)[ ]+for[ ]+$PURPOSE_SEQUENCE|" +
        // 'Replacements for tea' may be a substitution, so require a room.
        "replacements[ ]+for[ ]+$PURPOSE_ROOM_SEQUENCE)(?:[ ]*,[ ]*(?:please|kindly))?"
// Word order does not change proof: retain the complete note, never infer items.
private val PURPOSE_LIST_REQUEST = Regex(
    "(?:(?:beta|namaste|hello|hi|bhaiya|ji)[ ]*,[ ]*)?" +
        "(?:$PURPOSE_COURTESY(?:get|add|bring|buy|order|include)[ ]+$PURPOSE_ITEMS[ ]+for[ ]+$PURPOSE_SEQUENCE|" +
        "for[ ]+$PURPOSE_SEQUENCE[ ]*,[ ]*$PURPOSE_COURTESY" +
        "(?:get|add|bring|buy|order|include|put[ ]+down|write[ ]+down|note[ ]+down)(?:[ ]+$PURPOSE_ITEMS)?|" +
        "$PURPOSE_COURTESY" + "keep[ ]+$PURPOSE_SEQUENCE[ ]+lists[ ]+together|$PURPOSE_NOMINAL)",
)

/** Retained section context, never a product/category/amount inference. DTO proves scope independently. */
internal fun groceryIntentPurposeGroupHeading(header: String): String? {
    if (header.length > 180 || header.any { it.isISOControl() }) return null
    val lower = buildString(header.length) {
        header.forEach { char -> append(if (char in 'A'..'Z') char + 32 else char) }
    }
    return header.trim(' ').trimEnd(':', '：', '—', '–', '‒').trimEnd(' ')
        .takeIf { PURPOSE_HEADING.matches(lower) }
}

/** Role only: the caller must retain the original clause and prove its complete scope. */
internal fun groceryIntentPurposeListRequest(clause: String): Boolean {
    if (clause.length > 180) return false
    val lower = buildString(clause.length) {
        clause.forEach { char -> append(if (char in 'A'..'Z') char + 32 else char) }
    }
    return PURPOSE_LIST_REQUEST.matches(lower)
}

/** Closed listing discourse. This returns identity only; callers prove local amounts separately. */
internal fun groceryIntentLocalQuantityHeadingIdentity(header: String): String? {
    if ('\n' in header || '\r' in header) return null
    val bounded = header.trim().trimEnd(':', '：', '—', '–', '‒', '-').trim()
    if (bounded.any { it.code > 0x7f }) return null

    val local = LOCAL_QUANTITY_HEADER.matchEntire(bounded)
    if (local != null) {
        var identity = SHELF_PREFIX.replaceFirst(local.groupValues[1], "").trim()
        identity = Regex("\\s+(?:wale|waale)$", RegexOption.IGNORE_CASE).replace(identity, "").trim()
        return identity.takeIf(String::isNotEmpty)
    }

    val section = LOCAL_QUANTITY_SECTION.matchEntire(bounded) ?: return null
    val identity = section.groupValues[1].trim()
    val repeated = section.groupValues[2].trim()
    return identity.takeIf { normalizedLabel(it) == normalizedLabel(repeated) }
}

/** A discourse identity may be used only with locally evidenced member amounts and no shared amount. */
internal fun GroceryIntentReviewV2Group.hasEligibleLocalQuantityHeading(): Boolean =
    header != null && total == null && each == null && members.isNotEmpty() &&
        members.all { it.origin == "local" && it.value != null }

private fun normalizedLabel(value: String): String = value.trim().split(Regex("[\\t ]+")).joinToString(" ")
    .lowercase(Locale.ROOT)

private const val LOCAL_HEADING_IDENTITY = "[A-Za-z0-9](?:[A-Za-z0-9 '\\-]*[A-Za-z0-9])?"
private val GROUP_REQUEST_HEADERS = listOf(
    "for[\\t ]+($LOCAL_HEADING_IDENTITY),[\\t ]+I[\\t ]+(?:want|need)[\\t ]*[,:]?",
    "($LOCAL_HEADING_IDENTITY)[\\t ]+bhi(?:[\\t ]+(?:chahiye|chaahiye|daalna))?[\\t ]*[,:]?",
    "from[\\t ]+($LOCAL_HEADING_IDENTITY)[\\t ]+I[\\t ]+(?:need|want)[\\t ]*[,:]?",
    "then[\\t ]+($LOCAL_HEADING_IDENTITY)[\\t ]*[,:]?",
).map { Regex(it, RegexOption.IGNORE_CASE) }
private val GROUP_WITHIN_HEADER = Regex("($LOCAL_HEADING_IDENTITY)[\\t ]+mein[\\t ]*[,:]")
private val ORGANIZATIONAL_CATEGORY_HEADING = Regex(
    "^[ \\t]*(?:(?:and|aur|phir)[ \\t]+)?(fruits?|phal|vegetables?|sabzi|sabziyan|spices|masale)" +
        "(?:(?:[ \\t]+(?:side|section))?[ \\t]*[:：—–‒]|[ \\t]+ke[ \\t]+liye[ \\t]*[,:]|[ \\t]+mein[ \\t]*[,:])[ \\t]*$",
)
private val LOCAL_QUANTITY_HEADER = Regex(
    "($LOCAL_HEADING_IDENTITY)\\s+(?:(?:wale|waale)\\s+)?items" +
        "(?:\\s+likh\\s+(?:raha|rahi)\\s+hoon)?\\s*,\\s*(?:" +
        "har\\s+naam\\s+ke\\s+saamne\\s+(?:jitne\\s+likhe\\s+hain\\s+utne\\s+hi|" +
        "jo\\s+quantity\\s+hai\\s+utni\\s+hi)(?:\\s+lena)?|" +
        "(?:please\\s+)?take\\s+(?:the\\s+)?quantity\\s+written\\s+(?:beside|next\\s+to)\\s+each\\s+item|" +
        "(?:please\\s+)?take\\s+(?:the\\s+)?stated\\s+quantity\\s+of\\s+each\\s+named\\s+item)",
    RegexOption.IGNORE_CASE,
)
private val LOCAL_QUANTITY_SECTION = Regex(
    "($LOCAL_HEADING_IDENTITY)\\s+ke\\s+liye\\s+alag\\s+section\\s+bana\\s+do;\\s*" +
        "har\\s+($LOCAL_HEADING_IDENTITY)\\s+ki\\s+apni\\s+ginti\\s+hai",
    RegexOption.IGNORE_CASE,
)
private val SHELF_PREFIX = Regex(
    "^(?:(?:pantry|kitchen)\\s+shelf\\s+ke\\s+liye\\s+|for\\s+(?:the\\s+)?(?:pantry|kitchen)\\s+shelf\\s+)",
    RegexOption.IGNORE_CASE,
)
