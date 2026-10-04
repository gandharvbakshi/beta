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
    val retailPackCount: Boolean = false,
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
    const val PARSER_VERSION = "2026.09.16.1"

    private val leadingCommandRegex = Regex(
        "^(?:\\s*(?:please\\s+|kindly\\s+)?(?:get\\s+me|pick\\s+up|order|buy|add|get|fetch|bring)\\b[\\s,]*)+",
        RegexOption.IGNORE_CASE
    )
    private val leadingConversationWrapperRegex = Regex(
        "^(?:\\s*(?:i\\s+(?:want|need)|i\\s+would\\s+like|can\\s+you\\s+please|could\\s+you\\s+please)\\b\\s*)+",
        RegexOption.IGNORE_CASE,
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
    private val leadingBinaPreferenceRegex = Regex(
        "^bina\\s+(.+?)\\s+(chai|tea|coffee|doodh|milk)$",
        RegexOption.IGNORE_CASE,
    )
    private val positiveAddedSugarPhraseRegex = Regex(
        "(?:\\bwith\\s+)?\\b(?:without|no)\\s+added\\s+sugar\\b",
        RegexOption.IGNORE_CASE,
    )
    private const val positiveAddedSugarMarker = "__positive_added_sugar__"
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
    private val leadingMeasuredMultipackRegex = Regex(
        "^[1-9]\\d?\\s*[x×]\\s*\\d+(?:\\.\\d+)?\\s*(?:kg|kgs|g|gm|gms|grams?|ml|l|ltr|liters?|litres?)\\b\\s+.+$",
        RegexOption.IGNORE_CASE,
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
    private val standaloneMeasureRegex = Regex(
        "^(?:ml|l|ltr|liter|litre|liters|litres|g|gm|gms|gram|grams|kg|kgs)$",
        RegexOption.IGNORE_CASE,
    )
    private val standaloneMeasuredValueRegex = Regex(
        "^\\d+(?:\\.\\d+)?\\s*(?:ml|l|ltr|liter|litre|liters|litres|g|gm|gms|gram|grams|kg|kgs)$",
        RegexOption.IGNORE_CASE,
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
                val packInput = stripLeadingCommands(productSegment)
                val retailPackCount = hasExplicitRetailPackCount(packInput)
                val spoken = normalizeSpokenQuantitySegment(normalizeTrailingSpokenPackCount(packInput))
                    .copy(ambiguousPackQuantities = hasConflictingPackCounts(packInput))
                    .copy(retailPackCount = retailPackCount)
                val quantityInput = normalizeTrailingMeasure(normalizePacketUnitCountSegment(spoken.text))
                val milkTetraNormalization = normalizeMilkTetraPackSegment(quantityInput)
                val quantityText = milkTetraNormalization.normalized ?: quantityInput
                val ambiguousPackQuantities = spoken.ambiguousPackQuantities || milkTetraNormalization.ambiguous
                var segmentStart = 0
                val splitSegment = if (ambiguousPackQuantities) {
                    quantityText
                } else {
                    quantityText.replace(quantityBoundaryRegex) { boundary ->
                        val rest = quantityText.substring(boundary.range.last + 1)
                        val prefix = quantityText.substring(segmentStart, boundary.range.first).trim()
                        val countedPack = Regex("^[1-9]\\d?$").matches(prefix) &&
                            (isMeasuredDescriptorPrefix(rest) || leadingMeasuredMultipackRegex.matches(rest))
                        val descriptorPrefix = prefix.replaceFirst(Regex("^[1-9]\\d?\\s+"), "")
                        val measuredMultipack = (
                            Regex("^[1-9]\\d?\\s*[x×]$", RegexOption.IGNORE_CASE).matches(prefix) ||
                                Regex("^[1-9]\\d?\\s+[1-9]\\d?\\s*[x×]$", RegexOption.IGNORE_CASE).matches(prefix) ||
                                Regex("^[1-9]\\d?\\s*[x×]$", RegexOption.IGNORE_CASE).matches(descriptorPrefix)
                            ) &&
                            isMeasuredDescriptorPrefix(rest)
                        if (isNumericBrandPrefix(rest) || countedPack || measuredMultipack) boundary.value else {
                            segmentStart = boundary.range.last + 1
                            ","
                        }
                    }
                }
                val mixedRetailPackSource = retailPackCount && splitSegment.contains(",")
                primarySplitterRegex.split(splitSegment).map {
                    spoken.copy(
                        text = it,
                        ambiguousMilkTetraPack = milkTetraNormalization.ambiguous,
                        ambiguousPackQuantities = ambiguousPackQuantities,
                        // An implicit quantity boundary can otherwise copy a
                        // pack flag onto a later plain item (for example
                        // "1 milk pack 2 samosa"). Fail closed for the whole
                        // mixed source so no later item is treated as packed.
                        quantitySignal = if (mixedRetailPackSource) {
                            "ambiguous mixed retail-pack source"
                        } else {
                            spoken.quantitySignal
                        },
                        retailPackCount = if (mixedRetailPackSource) false else retailPackCount,
                    )
                }
            }
            .filter { it.text.isNotEmpty() }
            .forEach { spokenNormalized ->
                val segment = spokenNormalized.text
                val quantitySignal = if (spokenNormalized.ambiguousPackQuantities) "conflicting pack quantities" else spokenNormalized.quantitySignal
                val ambiguousPackQuantities = spokenNormalized.ambiguousPackQuantities
                val normalizedSegment = normalizeTrailingMeasure(normalizeTrailingPackCount(segment))
                val (withoutQuantity, quantity) = if (ambiguousPackQuantities) {
                    normalizedSegment to Quantity.Default
                } else {
                    extractQuantityPrefix(normalizedSegment)
                }
                val (withoutModifiers, avoidPhrases) = extractPreferenceModifiers(withoutQuantity)
                val cleaned = if (ambiguousPackQuantities) {
                    withoutModifiers.trim()
                } else {
                    cleanSegment(
                        withoutModifiers,
                        preserveLeadingNumber = shouldPreserveLeadingNumber(withoutModifiers)
                    )
                }
                if (cleaned.isEmpty() ||
                    standaloneMeasureRegex.matches(cleaned) ||
                    standaloneMeasuredValueRegex.matches(cleaned) ||
                    Regex("^(?:\\d+\\s+)?(?:packs?|packets?|units?)$", RegexOption.IGNORE_CASE).matches(cleaned) ||
                    noOpRegex.matches(cleaned.lowercase(Locale.US))
                ) {
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
                    val exactMeasuredPack = (itemQuantity is Quantity.Count && isMeasuredDescriptorPrefix(item)) ||
                        leadingMeasuredMultipackRegex.matches(item)
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
                        retailPackCount = spokenNormalized.retailPackCount,
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
        if (existing.retailPackCount != candidate.retailPackCount) return null
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

    /**
     * Retain provenance for an explicit purchase-unit request. Ordinary
     * leading counts ("2 eggs") are not retail-pack counts, while a count
     * attached to packs/packets/pouches/units is. Numeric brand prefixes and
     * descriptor-only "24 pack paper towels" remain catalogue identity.
     */
    private fun hasExplicitRetailPackCount(segment: String): Boolean {
        val trimmed = segment.trim()
        val tokens = ProductLexicon.tokenize(trimmed)
        if (tokens.isEmpty()) return false
        val packNounIndices = tokens.withIndex()
            .filter { (_, token) -> token.matches(Regex("tetrapouches?|tetrapackets?|tetrapacks?|pouches?|packets?|packs?|units?", RegexOption.IGNORE_CASE)) }
            .map { it.index }
        if (packNounIndices.isEmpty()) return false

        // A count immediately following the package noun is unambiguous,
        // including the spoken Hindi/Hinglish forms handled by the parser.
        if (packNounIndices.any { index ->
                index + 1 < tokens.size && normalizeCountToken(tokens[index + 1]) != null
            }) return true

        val firstCount = normalizeCountToken(tokens.first())
        if (firstCount == null) return false

        // A numeric brand such as "5 star" or "24 mantra" owns its number;
        // only an additional outer count before that brand is retail syntax.
        if (isNumericBrandPrefix(trimmed)) return false
        if (tokens.size > 1 && isNumericBrandPrefix(tokens.drop(1).joinToString(" "))) return true

        // Measured prefixes own their number. They become retail-pack syntax
        // only when an explicit trailing count was found above.
        if (isMeasuredDescriptorPrefix(trimmed)) return false

        // A singular high-number "24 pack ..." is a common catalogue
        // descriptor; do not label it as a requested quantity.
        if (firstCount > 20 && packNounIndices.firstOrNull() == 1 && tokens[1] == "pack") return false
        return true
    }

    private fun normalizeTrailingPackCount(segment: String): String {
        val match = trailingPackCountRegex.matchEntire(segment.trim()) ?: return segment
        return "${match.groupValues[2]} ${match.groupValues[1].trim()}"
    }

    /**
     * Keep a common spoken milk request together before the generic quantity
     * splitter runs.  Inputs such as "500 ml 5 milk tetta packs" contain two
     * quantities in one product phrase; treating the volume as its own item
     * produces the unsafe `ml` product and loses the requested pack count.
     *
     * The correction is intentionally narrow: `tetta` is only normalised when
     * milk, tetra and pack language are all present. Brands and packaging stay
     * in the product identity. Conflicting measures/counts fail validation.
     */
    private data class MilkTetraPackNormalization(
        val normalized: String?,
        val ambiguous: Boolean,
    )

    private fun normalizeMilkTetraPackSegment(segment: String): MilkTetraPackNormalization {
        val trimmed = segment.trim()
        val typoNormalized = trimmed.replace(
            Regex("\\btetta\\b", RegexOption.IGNORE_CASE),
            "tetra",
        )
        val tokens = ProductLexicon.tokenize(typoNormalized)
        val milkTokens = setOf("milk", "doodh", "दूध", "haalu", "ಹಾಲು")
        val hasMilk = tokens.any { it in milkTokens }
        val hasTetra = tokens.contains("tetra")
        val hasPack = tokens.any { it in setOf("pack", "packs", "packet", "packets") }
        if (!hasMilk || !hasTetra || !hasPack) return MilkTetraPackNormalization(null, false)
        if (Regex("\\b(?:24\\s*mantra|7\\s*up|5\\s*star)\\b", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)) {
            return MilkTetraPackNormalization(null, false)
        }

        val measure = Regex("\\b(\\d+(?:\\.\\d+)?)\\s*(ml|l|ltr|liters?|litres?)\\b", RegexOption.IGNORE_CASE)
        val volumes = measure.findAll(typoNormalized).toList()
        val withoutVolume = measure.replace(typoNormalized, " ")
        val countPattern = Regex("\\b\\d+\\b")
        val counts = countPattern.findAll(withoutVolume).toList()
        val productWords = countPattern.replace(withoutVolume, " ").replace(Regex("\\s+"), " ").trim()
        // Keep explicit letter-only brand/variant prefixes. Numeric brands use
        // the existing parser, never a guessed pack count.
        val productPattern = Regex("^(?:[\\p{L}\\p{M}']+\\s+)*(?:milk|doodh|दूध|haalu|ಹಾಲು)\\s+tetra\\s+(?:packs?|packets?)$", RegexOption.IGNORE_CASE)
        if (!productPattern.matches(productWords)) return MilkTetraPackNormalization(null, false)
        if (volumes.size > 1 || counts.size > 1) return MilkTetraPackNormalization(null, true)
        val volume = volumes.singleOrNull()?.let { "${it.groupValues[1]} ${it.groupValues[2]} " } ?: ""
        val count = counts.singleOrNull()?.value?.toIntOrNull()
        if (counts.isNotEmpty() && (count == null || count !in 1..20)) return MilkTetraPackNormalization(null, true)
        val product = volume + productWords
            .replace(Regex("\\b(?:doodh|दूध|haalu|ಹಾಲು)\\b", RegexOption.IGNORE_CASE), "milk")
            .replace(Regex("\\b(?:packs?|packets?)$", RegexOption.IGNORE_CASE), "pack")
        return MilkTetraPackNormalization(
            normalized = if (count == null) product else "$count $product",
            ambiguous = false,
        )
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
                .replace(leadingConversationWrapperRegex, "")
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

        // Protect the positive phrase before generic negative extraction. The
        // connector ("with"/"without") is not a catalogue token here; the
        // marker is restored as human-readable "no added sugar" on return.
        text = positiveAddedSugarPhraseRegex.replace(text, positiveAddedSugarMarker)
        fun restorePositiveAddedSugar(value: String): String =
            value.replace(positiveAddedSugarMarker, "no added sugar")

        leadingBinaPreferenceRegex.matchEntire(text)?.let { match ->
            val core = match.groupValues[2].trim()
            val avoid = cleanAvoidPhrase(match.groupValues[1])
            return restorePositiveAddedSugar(core) to listOf(avoid).filter { it.isNotBlank() }
        }
        val match = negativePreferenceClauseRegex.find(text) ?: return restorePositiveAddedSugar(text) to emptyList()
        val core = match.groupValues[1].trim().trim(',', ';', '&').trim()
        val avoid = cleanAvoidPhrase(match.groupValues[2])
        return restorePositiveAddedSugar(core) to listOf(avoid).filter { it.isNotBlank() }
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
        val outerCountBeforeNumericBrand = leadingCountRegex.find(normalized)
            ?.let { isNumericBrandPrefix(it.groupValues[2].trim()) }
            ?: false
        if (outerCountBeforeNumericBrand) return false
        return leadingMultipackDescriptorRegex.matches(normalized) ||
            leadingMeasuredMultipackRegex.matches(normalized) ||
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

    private fun normalizeTrailingSpokenPackCount(segment: String): String {
        // A count after an explicit package noun is unambiguous. Do not strip
        // arbitrary trailing words ("milk two", product/model names, etc.).
        val match = Regex(
            "^(.+\\b(?:packs?|packets?|pouch(?:es)?|units?))\\s+([1-9]\\d?|[\\p{L}]+)$",
            RegexOption.IGNORE_CASE,
        ).matchEntire(segment.trim()) ?: return segment
        val trailingToken = match.groupValues[2].lowercase(Locale.US)
        val trailingCount = normalizeCountToken(trailingToken) ?: return segment

        val prefix = match.groupValues[1].trim()
        // A leading measure, measured multipack, or numeric brand is part of
        // the product descriptor, not a purchase count. Keep its number when
        // normalizing a trailing count (for example, "1 l milk packs 5" or
        // "5 star chocolate packs 2").
        if (isMeasuredDescriptorPrefix(prefix) ||
            leadingMeasuredMultipackRegex.matches(prefix) ||
            isNumericBrandPrefix(prefix)
        ) {
            return "$trailingCount ${normalizeTrailingPackProduct(prefix)}"
        }
        val leadingNumericCount = Regex("^([1-9]\\d*)\\s+", RegexOption.IGNORE_CASE)
            .find(prefix)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        if (leadingNumericCount != null && leadingNumericCount > 20) return segment
        val leading = Regex("^([1-9]\\d?|[\\p{L}]+)\\s+(.+)$", RegexOption.IGNORE_CASE)
            .matchEntire(prefix)
        val leadingCount = leading?.let { normalizeCountToken(it.groupValues[1].lowercase(Locale.US)) }
        if (leadingCount != null) {
            // Matching duplicate counts are safe to canonicalize once. Any
            // disagreement is left untouched and marked by the caller so the
            // item cannot silently choose one quantity or split into phantoms.
            if (leadingCount != trailingCount) return segment
            return "$leadingCount ${normalizeTrailingPackProduct(leading.groupValues[2].trim())}"
        }
        return "$trailingCount ${normalizeTrailingPackProduct(prefix)}"
    }

    private fun normalizeTrailingPackProduct(prefix: String): String {
        val trimmed = prefix.trim()
        // Keep tetra-pack and pouch form evidence; generic purchase nouns are
        // not catalogue identity tokens ("milk packs" -> "milk").
        if (Regex("\\b(?:tetra|tetta)\\s+(?:packs?|packets?)$", RegexOption.IGNORE_CASE).containsMatchIn(trimmed) ||
            Regex("\\bpouch(?:es)?$", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)
        ) {
            return trimmed
        }
        return trimmed.replace(Regex("\\s+(?:packs?|packets?|units?)$", RegexOption.IGNORE_CASE), "").trim()
    }

    private fun hasConflictingPackCounts(segment: String): Boolean {
        val trimmed = segment.trim()
        val leadingNumber = Regex("^([1-9]\\d*)\\s+", RegexOption.IGNORE_CASE)
            .find(trimmed)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        val trailingPackCount = Regex(
            "(?:packs?|packets?|pouch(?:es)?|units?)(?:\\s*([1-9]\\d*)|\\s+([\\p{L}]+))$",
            RegexOption.IGNORE_CASE,
        ).find(trimmed)?.let { match ->
            match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }
                ?.let { normalizeCountToken(it.lowercase(Locale.US)) }
        }
        if (leadingNumber != null && leadingNumber > 20 &&
            !isMeasuredDescriptorPrefix(trimmed) &&
            !leadingMeasuredMultipackRegex.matches(trimmed) &&
            !isNumericBrandPrefix(trimmed) &&
            trailingPackCount != null
        ) {
            // Preserve an out-of-range leading count so downstream validation
            // can reject it; cleanSegment must not silently drop the number.
            return true
        }
        val match = Regex(
            "^([1-9]\\d*|[\\p{L}]+)\\s+(.+\\b(?:packs?|packets?|pouch(?:es)?|units?))\\s*([1-9]\\d*|[\\p{L}]+)$",
            RegexOption.IGNORE_CASE,
        ).matchEntire(segment.trim()) ?: return false
        val leadingCount = normalizeCountToken(match.groupValues[1].lowercase(Locale.US)) ?: return false
        val trailingCount = normalizeCountToken(match.groupValues[3].lowercase(Locale.US)) ?: return false
        val descriptorPrefix = "${match.groupValues[1]} ${match.groupValues[2]}"
        if (isMeasuredDescriptorPrefix(descriptorPrefix) ||
            leadingMeasuredMultipackRegex.matches(descriptorPrefix) ||
            isNumericBrandPrefix(descriptorPrefix)
        ) {
            return false
        }
        return leadingCount != trailingCount
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
        // A normalized outer count may precede a numeric brand (for example,
        // "2 24 mantra atta packs"). Treat that outer number as purchase
        // quantity before descriptor-preservation checks inspect the suffix.
        Regex("^([1-9]\\d?)\\s+((?:7\\s+up|5\\s+star|24\\s+mantra)\\b.*)$", RegexOption.IGNORE_CASE)
            .matchEntire(normalized)?.let { match ->
                val count = match.groupValues[1].toIntOrNull()
                if (count != null && count > 0) return match.groupValues[2].trim() to Quantity.Count(count)
            }
        leadingCountRegex.find(normalized)?.let { match ->
            val suffix = match.groupValues[2].trim()
            if (isNumericBrandPrefix(suffix)) {
                val count = match.groupValues[1].toIntOrNull()
                if (count != null && count > 0) return suffix to Quantity.Count(count)
            }
        }
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
    val quantitySignal: String? = null,
    val ambiguousMilkTetraPack: Boolean = false,
    val ambiguousPackQuantities: Boolean = false,
    val retailPackCount: Boolean = false,
)
