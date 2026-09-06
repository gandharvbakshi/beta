package com.example.beta.automation

import java.util.Locale

sealed class Quantity {
    data object Default : Quantity()
    data class Count(val n: Int) : Quantity()
    data class Weight(val grams: Int) : Quantity()
    data class Volume(val ml: Int) : Quantity()
}

data class ParsedItem(
    val rawText: String,
    val query: String,
    val quantity: Quantity = Quantity.Default,
    val quantitySignal: String? = null,
    val parserConfidence: Float = 1.0f,
    val avoidPhrases: List<String> = emptyList(),
    val strictMatchPhrase: String? = null,
)

fun Quantity.requestedCount(): Int = when (this) {
    is Quantity.Count -> n.coerceAtLeast(1)
    else -> 1
}

fun ParsedItem.backendInputText(): String {
    return when (val q = quantity) {
        is Quantity.Count -> if (q.n > 1) "${q.n} $query" else query
        is Quantity.Weight -> "${q.grams} g $query"
        is Quantity.Volume -> "${q.ml} ml $query"
        Quantity.Default -> query
    }
}

object InstructionParser {
    const val PARSER_VERSION = "2026.09.06.2"

    private val leadingCommandRegex = Regex(
        "^(?:\\s*(?:please\\s+|kindly\\s+)?(?:get\\s+me|pick\\s+up|order|buy|add|get|fetch|bring)\\b[\\s,]*)+",
        RegexOption.IGNORE_CASE
    )
    private val primarySplitterRegex = Regex("\\s*(?:[,;\\r\\n]+)\\s*")
    // The numeral belongs to this product name, not an extra two-pack request.
    private val maggiMinuteDescriptorRegex = Regex(
        "\\bmaggi\\s+(?:2|two)[\\s-]+minutes?\\b",
        RegexOption.IGNORE_CASE
    )
    private val secondarySplitterRegex = Regex(
        "\\s*(?:\\s+&\\s+|\\s+and\\s+|\\s+aur\\s+|\\s+plus\\s+|\\s+और\\s+|\\s+ಮತ್ತು\\s+|\\s+ಹಾಗೂ\\s+)\\s*",
        RegexOption.IGNORE_CASE
    )
    private val noisySplitterRegex = Regex("\\s+(?:&|and|aur|plus|और|ಮತ್ತು|ಹಾಗೂ)\\s+", RegexOption.IGNORE_CASE)
    private val leadingNoiseRegex = Regex(
        "^(?:(?:and|then|also|plus|with|some|a|an|the|maybe|perhaps|please|kindly|of|for\\s+me|for|me|\\d+)\\b\\s*)+",
        RegexOption.IGNORE_CASE
    )
    private val trailingNoiseRegex = Regex(
        "(?:\\s*\\b(?:and|then|also|plus|with|some|a|an|the|maybe|perhaps|please|kindly|of|for\\s+me|for|me|\\d+)\\b)+$",
        RegexOption.IGNORE_CASE
    )
    private val trailingCartNoiseRegex = Regex(
        "\\s+\\b(?:to|into|in)\\s+(?:my\\s+|the\\s+)?cart\\b\\s*$",
        RegexOption.IGNORE_CASE
    )
    private val leadingFillerRegex = Regex(
        "^(?:(?:and|then|also|plus|with|some|a|an|the|maybe|perhaps|please|kindly|of|for\\s+me|for|me)\\b\\s*)+",
        RegexOption.IGNORE_CASE
    )
    private val trailingPreferenceNoiseRegex = Regex(
        "(?:\\s+(?:with\\s+my\\s+usual\\s+preference|with\\s+my\\s+usual|my\\s+usual\\s+preference|usual\\s+preference|usual|as\\s*-?is|normally|default))+$",
        RegexOption.IGNORE_CASE
    )
    private val negativePreferenceClauseRegex = Regex(
        "^(.*?)\\s+(?:without|no|not|bina)\\s+(.+)$",
        RegexOption.IGNORE_CASE
    )
    private val avoidPhraseNoiseRegex = Regex(
        "^(?:(?:the|a|an)\\b\\s*)+|(?:\\s*\\b(?:one|ones|type|types|variant|variants|flavor|flavors|flavour|flavours)\\b)+$",
        RegexOption.IGNORE_CASE
    )
    private val fractionalMeasureRegexes = listOf(
        Regex(
            "\\b(?:one\\s*(?:-\\s*)?and\\s*(?:-\\s*)?a\\s*(?:-\\s*)?half|one\\s*(?:-\\s*)?and\\s*(?:-\\s*)?half)\\s*(kg|kgs|g|gm|gms|gram|grams|ml|l|ltr|liter|litre|liters|litres)\\b",
            RegexOption.IGNORE_CASE
        ) to "1.5",
        Regex(
            "\\bhalf\\s*(kg|kgs|g|gm|gms|gram|grams|ml|l|ltr|liter|litre|liters|litres)\\b",
            RegexOption.IGNORE_CASE
        ) to "0.5"
    )
    private val leadingCountRegex = Regex("^([1-9]\\d?)\\s+(.+)$")
    private val leadingMultipackDescriptorRegex = Regex(
        "^[1-9]\\d?\\s*(?:x\\s*)?(?:pack|pk|pc|pcs|piece|pieces)\\b\\s+.+$",
        RegexOption.IGNORE_CASE
    )
    private val numericBrandPrefixTokens = listOf(
        listOf("7", "up"),
        listOf("5", "star"),
        listOf("24", "mantra"),
    )
    private val leadingWeightRegex = Regex("^(\\d+(?:\\.\\d+)?)\\s*(g|gm|gms|gram|grams|kg|kgs)\\b\\s*(.+)$", RegexOption.IGNORE_CASE)
    private val leadingVolumeRegex = Regex("^(\\d+(?:\\.\\d+)?)\\s*(ml|l|ltr|liter|litre|liters|litres)\\b\\s*(.+)$", RegexOption.IGNORE_CASE)
    private val trailingMeasureRegex = Regex(
        "^(.+?)\\s+(\\d+(?:\\.\\d+)?)\\s*(g|gm|gms|gram|grams|kg|kgs|ml|l|ltr|liter|litre|liters|litres)\\s*$",
        RegexOption.IGNORE_CASE
    )
    private val quantityBoundaryRegex = Regex(
        "\\s+(?=\\d+(?:\\.\\d+)?\\s*(?:g|gm|gms|gram|grams|kg|kgs|ml|l|ltr|liter|litre|liters|litres)\\b|[1-9]\\d?\\s+(?!(?:pack|packs|packet|packets|unit|units|pk|pc|pcs|piece|pieces)\\b)\\w)",
        RegexOption.IGNORE_CASE
    )
    // A trailing purchase count is part of this item, never a new grocery line.
    // Keep "6 pack juice" and "tissues 2 packs" descriptors unchanged: those
    // can name one SKU. Explicit packets/units describe purchase counts.
    private val trailingPackCountRegex = Regex(
        "^(.+?)\\s+([1-9]\\d?)\\s*(?:packets?|units?)\\s*$",
        RegexOption.IGNORE_CASE
    )
    private val noOpRegex = Regex("^(?:i\\s+want\\s+)?(?:nothing|none|no\\s+items?)$", RegexOption.IGNORE_CASE)
    private val spokenCountWords = mapOf(
        "one" to 1,
        "two" to 2,
        "three" to 3,
        "four" to 4,
        "five" to 5,
        "six" to 6,
        "seven" to 7,
        "eight" to 8,
        "nine" to 9,
        "ten" to 10,
        "eleven" to 11,
        "twelve" to 12,
        "thirteen" to 13,
        "fourteen" to 14,
        "fifteen" to 15,
        "sixteen" to 16,
        "seventeen" to 17,
        "eighteen" to 18,
        "nineteen" to 19,
        "twenty" to 20,
        "ek" to 1,
        "do" to 2,
        "teen" to 3,
        "chaar" to 4,
        "char" to 4,
        "paanch" to 5,
        "panch" to 5,
        "chhe" to 6,
        "sat" to 7,
        "saat" to 7,
        "aath" to 8,
        "ath" to 8,
        "nau" to 9,
        "nou" to 9,
        "das" to 10,
        "ondu" to 1,
        "eradu" to 2,
        "mooru" to 3,
        "muru" to 3,
        "naalku" to 4,
        "aidu" to 5,
        "aaru" to 6,
        "elu" to 7,
        "entu" to 8,
        "ombattu" to 9,
        "hattu" to 10
    )
    private val spokenCountSignalWords = setOf(
        "zero",
        "thirty",
        "forty",
        "fifty",
        "sixty",
        "seventy",
        "eighty",
        "ninety",
        "hundred"
    )
    private val spokenCountConjunctionWords = setOf("and", "aur", "plus", "&", "और", "ಮತ್ತು", "ಹಾಗೂ")

    fun parse(input: String): List<ParsedItem> {
        val normalized = input.trim()
            .replace(Regex("^mujhe\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+(?:chahiye|chaahiye|mangao|lao|le\\s+aao)\\s*$", RegexOption.IGNORE_CASE), "")
        if (normalized.isEmpty()) return emptyList()

        val withoutPrefix = stripLeadingCommands(normalized)

        if (withoutPrefix.isEmpty()) return emptyList()
        if (noOpRegex.matches(withoutPrefix.lowercase(Locale.US))) return emptyList()

        val hasNoisySplitters = noisySplitterRegex.containsMatchIn(withoutPrefix)
        val parsedItems = mutableListOf<ParsedItem>()

        primarySplitterRegex.split(withoutPrefix)
            .flatMap { explicitSegment ->
                // Preserve fractional "and" and the OnePlus brand before
                // splitting the list. Each item's trailing measure stays local.
                secondarySplitterRegex.split(normalizeFractionalMeasures(explicitSegment)
                    .replace(Regex("\\bone\\s+plus\\b", RegexOption.IGNORE_CASE), "oneplus"))
            }
            .flatMap { segment ->
                val productSegment = segment.replace(maggiMinuteDescriptorRegex, "maggi 2-minute")
                val spoken = normalizeSpokenQuantitySegment(stripLeadingCommands(productSegment))
                val quantityText = normalizeTrailingMeasure(normalizePacketUnitCountSegment(spoken.text))
                var segmentStart = 0
                val splitSegment = quantityText.replace(quantityBoundaryRegex) { boundary ->
                    val rest = quantityText.substring(boundary.range.last + 1)
                    val prefix = quantityText.substring(segmentStart, boundary.range.first).trim()
                    val countedPack = Regex("^[1-9]\\d?$").matches(prefix) && isMeasuredDescriptorPrefix(rest)
                    if (isNumericBrandPrefix(rest) || countedPack) boundary.value else {
                        segmentStart = boundary.range.last + 1
                        ","
                    }
                }
                primarySplitterRegex.split(splitSegment).map { spoken.copy(text = it) }
            }
            .filter { it.text.isNotEmpty() }
            .forEach { spokenNormalized ->
                val segment = spokenNormalized.text
                val quantitySignal = spokenNormalized.quantitySignal
                val normalizedSegment = normalizeTrailingMeasure(normalizeTrailingPackCount(segment))
                val (withoutQuantity, quantity) = extractQuantityPrefix(normalizedSegment)
                val (withoutModifiers, avoidPhrases) = extractPreferenceModifiers(withoutQuantity)
                val cleaned = cleanSegment(
                    withoutModifiers,
                    preserveLeadingNumber = shouldPreserveLeadingNumber(withoutModifiers)
                )
                if (cleaned.isEmpty() || noOpRegex.matches(cleaned.lowercase(Locale.US))) {
                    return@forEach
                }

                val expanded = expandKnownProductSequence(cleaned)
                val confidence = when {
                    quantitySignal != null -> 0.60f
                    expanded.size > 1 -> 0.70f
                    hasNoisySplitters || !cleaned.equals(segment.trim(), ignoreCase = false) -> 0.85f
                    else -> 1.0f
                }

                expanded.forEach { item ->
                    val itemQuantity = if (expanded.size == 1) quantity else Quantity.Default
                    val exactMeasuredPack = itemQuantity is Quantity.Count && isMeasuredDescriptorPrefix(item)
                    val productText = if (exactMeasuredPack) normalizeMeasuredPackPrefix(item) else item
                    val query = ProductLexicon.canonicalizeProductText(productText).lowercase(Locale.US)
                    if (query.isBlank()) {
                        return@forEach
                    }
                    val strictMatchPhrase = if (exactMeasuredPack) query else null
                    val candidate = ParsedItem(
                        rawText = item,
                        query = query,
                        quantity = itemQuantity,
                        quantitySignal = quantitySignal,
                        parserConfidence = if (!query.equals(item, ignoreCase = true)) minOf(confidence, 0.85f) else confidence,
                        avoidPhrases = if (expanded.size == 1) avoidPhrases else emptyList(),
                        strictMatchPhrase = strictMatchPhrase,
                    )
                    addParsedItem(parsedItems, candidate)
                }
            }

        return parsedItems
    }

    fun normalizeSpokenQuantities(input: String): String {
        return normalizeSpokenQuantitySegment(input).text
    }

    fun normalizeSpokenQuantitySegment(segment: String): SpokenQuantityNormalization {
        val trimmed = segment.trim()
        if (trimmed.isEmpty()) {
            return SpokenQuantityNormalization(trimmed)
        }

        val fractionalNormalized = normalizeFractionalMeasures(trimmed)
        return normalizeLeadingSpokenCount(fractionalNormalized) ?: SpokenQuantityNormalization(fractionalNormalized)
    }

    private fun addParsedItem(items: MutableList<ParsedItem>, candidate: ParsedItem) {
        val lastIndex = items.indexOfLast { it.query == candidate.query }
        if (lastIndex < 0) {
            items.add(candidate)
            return
        }

        val existing = items[lastIndex]
        val merged = mergeParsedItems(existing, candidate)
        if (merged != null) {
            items[lastIndex] = merged
            return
        }

        items.add(candidate)
    }

    private fun mergeParsedItems(existing: ParsedItem, candidate: ParsedItem): ParsedItem? {
        if (existing.query != candidate.query) return null
        if (existing.quantitySignal != null || candidate.quantitySignal != null) return null
        if (existing.avoidPhrases != candidate.avoidPhrases) return null

        return when {
            existing.quantity is Quantity.Count && candidate.quantity is Quantity.Count -> {
                existing.copy(
                    quantity = Quantity.Count(existing.quantity.n + candidate.quantity.n),
                    parserConfidence = minOf(existing.parserConfidence, candidate.parserConfidence)
                )
            }
            existing.quantity is Quantity.Default && candidate.quantity is Quantity.Default -> existing
            else -> null
        }
    }

    private fun normalizeTrailingMeasure(segment: String): String {
        val match = trailingMeasureRegex.matchEntire(segment.trim()) ?: return segment
        val product = match.groupValues[1].trim()
        val amount = match.groupValues[2]
        val unit = match.groupValues[3]
        return "$amount $unit $product"
    }

    private fun normalizeTrailingPackCount(segment: String): String {
        val match = trailingPackCountRegex.matchEntire(segment.trim()) ?: return segment
        return "${match.groupValues[2]} ${match.groupValues[1].trim()}"
    }

    private fun normalizeFractionalMeasures(segment: String): String {
        var text = segment.replace(Regex("\\b(?:kilo|kilograms?|kilogramme)\\b", RegexOption.IGNORE_CASE), "kg")
            .replace(Regex("\\b(?:aadha|adha|aadhaa)\\s+(kg|g|ml|l|litre|liter)\\b", RegexOption.IGNORE_CASE), "0.5 $1")
            .replace(Regex("\\b(?:dedh|derh)\\s+(kg|g|ml|l|litre|liter)\\b", RegexOption.IGNORE_CASE), "1.5 $1")
        fractionalMeasureRegexes.forEach { (pattern, replacement) ->
            text = pattern.replace(text) { matchResult ->
                val unit = matchResult.groupValues[1]
                "$replacement $unit"
            }
        }
        return text
    }

    private fun normalizeLeadingSpokenCount(segment: String): SpokenQuantityNormalization? {
        val trimmed = segment.trim().trim(',', ';', '&').trim()
        val tokens = ProductLexicon.tokenize(trimmed)
        if (tokens.isEmpty()) return null

        val first = tokens.first()
        if (first in spokenCountSignalWords) {
            val signal = tokens.take(1).joinToString(" ")
            val rest = stripLeadingTokenPhrase(trimmed, signal)
            return if (rest.isNotEmpty()) {
                SpokenQuantityNormalization(rest, signal)
            } else {
                SpokenQuantityNormalization(trimmed, signal)
            }
        }

        val firstCount = spokenCountWords[first] ?: return null
        if (tokens.size == 1) {
            return SpokenQuantityNormalization(trimmed, tokens.take(1).joinToString(" "))
        }

        val second = tokens[1]
        if (second in spokenCountConjunctionWords) {
            return null
        }
        if (second in spokenCountWords || second in spokenCountSignalWords) {
            val signal = tokens.take(2).joinToString(" ")
            val rest = stripLeadingTokenPhrase(trimmed, signal)
            return if (rest.isNotEmpty()) {
                SpokenQuantityNormalization(rest, signal)
            } else {
                SpokenQuantityNormalization(trimmed, signal)
            }
        }

        if (firstCount > 20) {
            return SpokenQuantityNormalization(trimmed, tokens.take(1).joinToString(" "))
        }

        val rest = stripLeadingTokenPhrase(trimmed, tokens.first())
        if (rest.isEmpty()) {
            return SpokenQuantityNormalization(trimmed, tokens.take(1).joinToString(" "))
        }

        return SpokenQuantityNormalization("$firstCount $rest")
    }

    private fun stripLeadingTokenPhrase(text: String, phrase: String): String {
        val tokenPattern = phrase.split(' ').joinToString("[\\s-]+") { Regex.escape(it) }
        val match = Regex("^\\s*$tokenPattern\\b", RegexOption.IGNORE_CASE).find(text)
            ?: return ""
        return text.removeRange(match.range).trimStart()
    }

    fun applyPreferences(
        items: List<ParsedItem>,
        lookup: (String) -> Preference?,
        log: (String) -> Unit = {}
    ): List<ParsedItem> {
        return items.map { item ->
            val preference = lookup(item.query)
            if (preference != null) {
                val preferred = preference.preferredPhrase.trim().lowercase(Locale.US)
                log("PREFERENCE_APPLIED token=\"${item.query}\" -> \"$preferred\" conf=${"%.2f".format(Locale.US, preference.confidence)}")
                item.copy(
                    query = preferred,
                    avoidPhrases = (item.avoidPhrases + preference.avoidPhrases)
                        .map(::cleanAvoidPhrase)
                        .filter { it.isNotBlank() }
                        .distinct(),
                    strictMatchPhrase = preferred,
                )
            } else {
                log("PREFERENCE_NONE token=\"${item.query}\"")
                item
            }
        }
    }

    private fun stripLeadingCommands(input: String): String {
        var text = input.trim().trimStart(',', ';').trim()
        while (true) {
            val before = text
            text = text
                .replace(leadingCommandRegex, "")
                .trim()
                .trimStart(',', ';')
                .trim()
            if (text == before) return text
        }
    }

    private fun cleanSegment(segment: String, preserveLeadingNumber: Boolean = false): String {
        var text = segment.trim().trim(',', ';', '&').trim()
        while (true) {
            val before = text
            text = text
                .replace(if (preserveLeadingNumber) leadingFillerRegex else leadingNoiseRegex, "")
                .replace(trailingCartNoiseRegex, "")
                .replace(trailingNoiseRegex, "")
                .trim()
                .trim(',', ';', '&')
                .trim()
            if (text == before) return text
        }
    }

    private fun extractPreferenceModifiers(segment: String): Pair<String, List<String>> {
        var text = segment.trim().trim(',', ';', '&').trim()
        text = text.replace(trailingPreferenceNoiseRegex, "").trim()

        val match = negativePreferenceClauseRegex.find(text) ?: return text to emptyList()
        val core = match.groupValues[1].trim().trim(',', ';', '&').trim()
        val avoid = cleanAvoidPhrase(match.groupValues[2])
        return core to listOf(avoid).filter { it.isNotBlank() }
    }

    private fun cleanAvoidPhrase(value: String): String {
        var text = ProductLexicon.canonicalizeProductText(value)
            .trim()
            .trim(',', ';', '&')
            .trim()
        while (true) {
            val before = text
            text = text
                .replace(avoidPhraseNoiseRegex, "")
                .trim()
                .trim(',', ';', '&')
                .trim()
            if (text == before) return text.lowercase(Locale.US)
        }
    }

    internal fun isNumericBrandPrefix(text: String): Boolean {
        val tokens = ProductLexicon.tokenize(text.trim())
        return numericBrandPrefixTokens.any { prefix ->
            tokens.size >= prefix.size && tokens.subList(0, prefix.size) == prefix
        }
    }

    private fun isMeasuredDescriptorPrefix(text: String): Boolean {
        val normalized = text.trim()
        return leadingWeightRegex.matches(normalized) || leadingVolumeRegex.matches(normalized)
    }

    private fun normalizeMeasuredPackPrefix(text: String): String {
        val match = leadingWeightRegex.find(text) ?: leadingVolumeRegex.find(text) ?: return text
        val unit = match.groupValues[2].lowercase(Locale.US)
        val weight = unit in setOf("kg", "kgs", "g", "gm", "gms", "gram", "grams")
        val factor = if (unit in setOf("kg", "kgs", "l", "ltr", "liter", "litre", "liters", "litres")) 1000 else 1
        val amount = match.groupValues[1].toBigDecimal().multiply(factor.toBigDecimal()).stripTrailingZeros().toPlainString()
        return "$amount ${if (weight) "g" else "ml"} ${match.groupValues[3]}"
    }

    private fun shouldPreserveLeadingNumber(segment: String): Boolean {
        val normalized = segment.trim()
        return leadingMultipackDescriptorRegex.matches(normalized) ||
            isNumericBrandPrefix(normalized) ||
            isMeasuredDescriptorPrefix(normalized)
    }

    private fun normalizePacketUnitCountSegment(segment: String): String {
        val trimmed = segment.trim().trim(',', ';', '&').trim()
        // Keep the untouched product suffix/prefix: token reconstruction loses
        // decimal pack measures before exact pack validation can see them.
        Regex("^(\\S+)\\s+(?:packets?|units?)\\s+(.+)$", RegexOption.IGNORE_CASE)
            .matchEntire(trimmed)?.let { match ->
                normalizeCountToken(match.groupValues[1].lowercase(Locale.US))?.let { count ->
                    return "$count ${match.groupValues[2]}"
                }
            }
        Regex("^(.+?)\\s+(\\S+)\\s+(?:packets?|units?)$", RegexOption.IGNORE_CASE)
            .matchEntire(trimmed)?.let { match ->
                normalizeCountToken(match.groupValues[2].lowercase(Locale.US))?.let { count ->
                    return "$count ${match.groupValues[1]}"
                }
            }
        return trimmed
    }

    private fun normalizeCountToken(token: String): Int? {
        token.toIntOrNull()?.let { count ->
            if (count > 0) return count
        }
        return spokenCountWords[token]
    }

    private fun extractQuantityPrefix(segment: String): Pair<String, Quantity> {
        val normalized = segment
            .trim()
            .trim(',', ';', '&')
            .trim()
            .replace(leadingFillerRegex, "")
            .trim()
        leadingWeightRegex.find(normalized)?.let { match ->
            val amount = match.groupValues[1].toDoubleOrNull() ?: return segment to Quantity.Default
            val unit = match.groupValues[2].lowercase(Locale.US)
            val grams = when (unit) {
                "kg", "kgs" -> (amount * 1000).toInt()
                else -> amount.toInt()
            }
            if (grams > 0) return match.groupValues[3].trim() to Quantity.Weight(grams)
        }
        leadingVolumeRegex.find(normalized)?.let { match ->
            val amount = match.groupValues[1].toDoubleOrNull() ?: return segment to Quantity.Default
            val unit = match.groupValues[2].lowercase(Locale.US)
            val ml = when (unit) {
                "l", "ltr", "liter", "litre", "liters", "litres" -> (amount * 1000).toInt()
                else -> amount.toInt()
            }
            if (ml > 0) return match.groupValues[3].trim() to Quantity.Volume(ml)
        }
        if (shouldPreserveLeadingNumber(normalized)) {
            return normalized to Quantity.Default
        }
        val match = leadingCountRegex.find(normalized) ?: return segment to Quantity.Default
        val count = match.groupValues[1].toIntOrNull() ?: return segment to Quantity.Default
        // Preserve the requested number. Provider-specific validation rejects
        // counts above its limit; never silently turn 21 packets into one.
        if (count <= 0) return segment to Quantity.Default
        return match.groupValues[2].trim() to Quantity.Count(count)
    }

    private fun expandKnownProductSequence(segment: String): List<String> {
        val tokens = ProductLexicon.tokenize(segment)
        if (tokens.size < 2) return listOf(segment)

        val matches = mutableListOf<String>()
        var index = 0
        while (index < tokens.size) {
            val match = ProductLexicon.knownProductTokens.firstOrNull { (_, productTokens) ->
                productTokens.isNotEmpty() &&
                    index + productTokens.size <= tokens.size &&
                    tokens.subList(index, index + productTokens.size) == productTokens
            } ?: return listOf(segment)

            matches.add(match.first)
            index += match.second.size
        }

        return if (matches.size > 1) matches else listOf(segment)
    }
}

data class SpokenQuantityNormalization(
    val text: String,
    val quantitySignal: String? = null
)
