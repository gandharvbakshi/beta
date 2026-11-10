package com.example.beta

import java.util.Collections
import java.util.Locale

/** Source-owned seller requirements; carrying quotes is not seller evidence or authorization. */
internal class SellerPropertyRequirement private constructor(
    val subject: String,
    val source: GroceryIntentReviewV2Quote,
    requirements: List<GroceryIntentReviewV2Quote>,
) {
    val requirements: List<GroceryIntentReviewV2Quote> = Collections.unmodifiableList(ArrayList(requirements))
    /** Stable span IDs retained for callers that need an ordered property-key list. */
    val properties: List<String> = Collections.unmodifiableList(this.requirements.map(::requirementId))

    fun toPayload(): Map<String, Any> = mapOf(
        "version" to VERSION,
        "subject" to subject,
        "source" to source.toPayload(),
        "requirements" to requirements.map { quote ->
            mapOf("id" to requirementId(quote), "source" to quote.toPayload())
        },
    )

    override fun equals(other: Any?) = other is SellerPropertyRequirement &&
        subject == other.subject && source == other.source && requirements == other.requirements

    override fun hashCode() = 31 * (31 * subject.hashCode() + source.hashCode()) + requirements.hashCode()
    override fun toString() = "SellerPropertyRequirement(<redacted>)"

    companion object {
        private const val VERSION = 2

        /** Normalize only the search subject. Requirement text remains exact source evidence. */
        fun fromSource(
            name: String,
            primary: GroceryIntentReviewV2Quote,
            requirements: List<GroceryIntentReviewV2Quote>,
        ): SellerPropertyRequirement? = runCatching {
            require(name.isWellFormedUnicode() && name.codePoints().noneMatch { Character.isISOControl(it) })
            val subject = normalizeSubject(name)
            val result = SellerPropertyRequirement(subject, primary, requirements)
            require(validRequirement(result.subject, result.source, result.requirements))
            result
        }.getOrNull()

        /** Strict v2-only parser: no legacy canonical-property fallback. */
        fun fromPayload(value: JsonValue): SellerPropertyRequirement {
            val obj = value.sellerObject(setOf("version", "subject", "source", "requirements"))
            require((obj.members["version"] as? JsonValue.Num)?.raw == VERSION.toString()) {
                "Invalid seller requirement version"
            }
            val subject = (obj.members["subject"] as? JsonValue.Str)?.value
                ?: error("Invalid seller requirement subject")
            val source = parseSerializedQuote(obj.members["source"] ?: error("Missing seller requirement source"))
            val rawRequirements = (obj.members["requirements"] as? JsonValue.Arr)?.items
                ?: error("Invalid seller requirements")
            require(rawRequirements.size in 1..MAX_REQUIREMENTS)
            val requirements = rawRequirements.map { raw ->
                val requirement = raw.sellerObject(setOf("id", "source"))
                val quote = parseSerializedQuote(requirement.members["source"] ?: error("Missing seller quote"))
                val id = (requirement.members["id"] as? JsonValue.Str)?.value ?: error("Invalid seller quote ID")
                require(id == requirementId(quote)) { "Changed seller quote ID" }
                quote
            }
            require(validRequirement(subject, source, requirements)) { "Invalid seller requirement evidence" }
            return SellerPropertyRequirement(subject, source, requirements)
        }
    }
}

private const val MAX_PRIMARY_CODEPOINTS = 320
private const val MAX_REQUIREMENT_CODEPOINTS = 320
private const val MAX_SUBJECT_CODEPOINTS = 200
private const val MAX_REQUIREMENTS = 16
private const val MAX_ORIGINAL_UTF16_OFFSET = 12_000

private val subjectSeparators = Regex("[\\s\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\-\\u2010-\\u2015]+")

private fun normalizeSubject(value: String): String = value.lowercase(Locale.ROOT)
    .replace(subjectSeparators, " ").trim()

private fun validRequirement(
    subject: String,
    primary: GroceryIntentReviewV2Quote,
    requirements: List<GroceryIntentReviewV2Quote>,
): Boolean {
    if (!subject.isWellFormedUnicode() || subject.isEmpty() || subject != normalizeSubject(subject) ||
        subject.codePointCount(0, subject.length) > MAX_SUBJECT_CODEPOINTS ||
        subject.codePoints().anyMatch { Character.isISOControl(it) }) return false
    if (!validQuoteText(primary.text, MAX_PRIMARY_CODEPOINTS) || primary.startUtf16 < 0 ||
        primary.endUtf16 <= primary.startUtf16 || primary.endUtf16 > MAX_ORIGINAL_UTF16_OFFSET ||
        primary.endUtf16 - primary.startUtf16 != primary.text.length) return false
    if (requirements.size !in 1..MAX_REQUIREMENTS) return false

    val ids = HashSet<String>()
    val localRanges = ArrayList<IntRange>(requirements.size)
    for (quote in requirements) {
        if (!validQuoteText(quote.text, MAX_REQUIREMENT_CODEPOINTS) || quote.startUtf16 < primary.startUtf16 ||
            quote.endUtf16 <= quote.startUtf16 || quote.endUtf16 > primary.endUtf16 ||
            quote.endUtf16 > MAX_ORIGINAL_UTF16_OFFSET || quote.endUtf16 - quote.startUtf16 != quote.text.length) return false
        val localStart = quote.startUtf16 - primary.startUtf16
        val localEnd = quote.endUtf16 - primary.startUtf16
        if (splitsSurrogate(primary.text, localStart) || splitsSurrogate(primary.text, localEnd) ||
            primary.text.substring(localStart, localEnd) != quote.text ||
            !hasQuoteBoundaries(primary.text, quote.text, localStart, localEnd) ||
            !uniqueExactQuoteInInstruction(primary.text, GroceryIntentReviewV2Quote(quote.text, localStart, localEnd))) return false
        if (!ids.add(requirementId(quote))) return false
        localRanges += localStart until localEnd
    }
    val ordered = localRanges.sortedBy { it.first }
    if (ordered.zipWithNext().any { (left, right) -> left.last >= right.first }) return false
    return true
}

private fun parseSerializedQuote(value: JsonValue): GroceryIntentReviewV2Quote {
    val obj = value.sellerObject(setOf("text", "start_utf16", "end_utf16"))
    val text = (obj.members["text"] as? JsonValue.Str)?.value ?: error("Invalid seller quote text")
    val start = obj.members["start_utf16"].sellerInt() ?: error("Invalid seller quote start")
    val end = obj.members["end_utf16"].sellerInt() ?: error("Invalid seller quote end")
    require(validQuoteText(text, MAX_PRIMARY_CODEPOINTS) && start >= 0 && end > start &&
        end <= MAX_ORIGINAL_UTF16_OFFSET && end - start == text.length) { "Invalid seller quote span" }
    return GroceryIntentReviewV2Quote(text, start, end)
}

private fun validQuoteText(value: String, maxCodepoints: Int): Boolean =
    value.isNotBlank() && value.isWellFormedUnicode() &&
        value.codePointCount(0, value.length) <= maxCodepoints &&
        value.codePoints().noneMatch { Character.isISOControl(it) && it !in setOf(9, 10, 13) }

private fun String.isWellFormedUnicode(): Boolean {
    var index = 0
    while (index < length) {
        val char = this[index]
        when {
            char.isHighSurrogate() -> {
                if (index + 1 >= length || !this[index + 1].isLowSurrogate()) return false
                index += 2
            }
            char.isLowSurrogate() -> return false
            else -> index++
        }
    }
    return true
}

private fun splitsSurrogate(text: String, index: Int): Boolean =
    index in 1 until text.length && text[index - 1].isHighSurrogate() && text[index].isLowSurrogate()

private fun requirementId(quote: GroceryIntentReviewV2Quote): String = "r${quote.startUtf16}:${quote.endUtf16}"

private fun GroceryIntentReviewV2Quote.toPayload(): Map<String, Any> = mapOf(
    "text" to text, "start_utf16" to startUtf16, "end_utf16" to endUtf16,
)

private fun JsonValue.sellerObject(expectedKeys: Set<String>): JsonValue.Obj =
    (this as? JsonValue.Obj)?.also { require(it.duplicateKeys.isEmpty() && it.members.keys == expectedKeys) }
        ?: error("Invalid seller requirement object")

private fun JsonValue?.sellerInt(): Int? {
    val raw = (this as? JsonValue.Num)?.raw ?: return null
    if (!Regex("(?:0|[1-9][0-9]*)").matches(raw)) return null
    return raw.toIntOrNull()
}
