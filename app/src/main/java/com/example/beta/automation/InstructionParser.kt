package com.example.beta.automation

import java.util.Locale
import com.example.beta.sourceHasUnresolvedContentsBoundary
import com.example.beta.withDeterministicSourceEvidence
import com.example.beta.sourcePacketCountSyntax
import com.example.beta.sourceNamedRetailCountReceipt
import com.example.beta.sourceCompositeRetailReceipt
import com.example.beta.sourceArticleMeasure
import com.example.beta.literalQuery
import com.example.beta.groceryIntentParserClauseScopeProven
import com.example.beta.groceryIntentSourceClauseContextProven
import com.example.beta.GROCERY_FIRST_PERSON_REQUEST
import com.example.beta.groceryIntentMeasuredMetadata
import com.example.beta.groceryPackMeasures
import com.example.beta.sourceRetailIdentityConstraint
import com.example.beta.sourceLocalAmount
import com.example.beta.standardGroceryRequestBody

sealed class Quantity {
    data object Default : Quantity()
    data class Count(val n: Int) : Quantity()
    data class Weight(val grams: Int) : Quantity()
    data class Volume(val ml: Int) : Quantity()
}

enum class IntentCartQuantityMode { TARGET, INCREMENT }

data class ParsedItem(
    val rawText: String,
    val query: String,
    val quantity: Quantity = Quantity.Default,
    val quantitySignal: String? = null,
    val parserConfidence: Float = 1.0f,
    val avoidPhrases: List<String> = emptyList(),
    val strictMatchPhrase: String? = null,
    val retailPackCount: Boolean = false,
    // Explicit v2 logical pieces, not a retail-cart count. Requires exact catalogue conversion.
    val logicalPieceCount: Boolean = false,
    // Local, non-serialized source evidence, independent of model/provider flags.
    val requiresIntentQuantityProof: Boolean = false,
    val intentQuantityProvenance: IntentQuantityProvenance? = null,
    val intentQuantityCatalogueCheck: IntentQuantityCatalogueCheck? = null,
    val cartQuantityMode: IntentCartQuantityMode = IntentCartQuantityMode.TARGET,
    val ingredientProvenance: com.example.beta.IntentIngredientProvenance? = null,
    val containerContentsProof: IntentContainerContentsProof? = null,
    // Local source-scope outcome, not a provider flag or a quantity ambiguity.
    val unprovenIdentityScope: Boolean = false,
    // Local category bound to a complete measured source. Adds a seller-offer restriction only.
    val measuredProduceSubject: String? = null,
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

    private const val courtesyWord = "(?:please|kindly)"
    private val leadingCommandRegex = Regex(
        "^(?:\\s*(?:$courtesyWord\\s+)?(?:get\\s+me|pick\\s+up|order|buy|add|get|fetch|bring)\\b[\\s,]*)+",
        RegexOption.IGNORE_CASE
    )
    private val leadingConversationWrapperRegex = Regex(
        "^(?:\\s*(?:$GROCERY_FIRST_PERSON_REQUEST|can\\s+you\\s+please|could\\s+you\\s+please)\\b\\s*)+",
        RegexOption.IGNORE_CASE,
    )
    private val primarySplitterRegex = Regex("\\s*(?:[,;\\r\\n]+)\\s*")
    private val secondarySplitterRegex = Regex(
        "(?:^|\\s+)(?:&|and|aur|plus|और|ಮತ್ತು|ಹಾಗೂ)\\s+",
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
            "\\b(?:one\\s*(?:-\\s*)?and\\s*(?:-\\s*)?a\\s*(?:-\\s*)?half|one\\s*(?:-\\s*)?and\\s*(?:-\\s*)?half)\\s*(kilo|kilograms?|kilogramme|kg|kgs|g|gm|gms|gram|grams|ml|l|ltr|liter|litre|liters|litres)\\b",
            RegexOption.IGNORE_CASE
        ) to "1.5",
        Regex(
            "\\bhalf\\s*(kilo|kilograms?|kilogramme|kg|kgs|g|gm|gms|gram|grams|ml|l|ltr|liter|litre|liters|litres)\\b",
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
    // Only a complete, explicitly separated courtesy/sentence terminator is neutral.
    // A question, dash, combining mark, amount or unit alone cannot name a product
    // and must not silently disappear from an otherwise valid shopping list.
    private val neutralDetached = Regex("(?:$courtesyWord[ \\t]*)?[.!।]|$courtesyWord", RegexOption.IGNORE_CASE)
    private val fragmentCourtesy = Regex("^$courtesyWord(?:[ \\t]+|$)", RegexOption.IGNORE_CASE)
    private fun lacksProductSubject(fragment: String): Boolean {
        val value = fragment.trim().trimEnd('.', '!', '?', '।', ':').trimEnd()
            .replaceFirst(fragmentCourtesy, "").trim()
        return !value.codePoints().anyMatch(Character::isLetter) ||
            standaloneMeasureRegex.matches(value) || standaloneMeasuredValueRegex.matches(value) ||
            normalizeCountToken(value.lowercase(Locale.ROOT)) != null
    }
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

    fun parse(input: String): List<ParsedItem> = parse(input, null)

    /** Denial-analysis only, for an already projected row. OFF lookup never receives
     * this exception: it otherwise omits a detached size from its product query. */
    internal fun parseSourceCountWitness(input: String, row: ParsedItem): List<ParsedItem> {
        val qty = row.quantity as? Quantity.Count ?: return parse(input)
        val comma = input.indexOf(',')
        if (input != row.rawText || row.query != row.strictMatchPhrase || comma <= 0 || comma != input.lastIndexOf(',')) return parse(input)
        val prefix = input.substring(0, comma).trim()
        val suffix = input.substring(comma + 1).trim()
        val owner = sourceRetailIdentityConstraint(prefix)?.literalQuery() ?: sourceNamedRetailCountReceipt(prefix)?.literalQuery()
            ?: return parse(input)
        val size = groceryPackMeasures(suffix).singleOrNull() ?: return parse(input)
        if (sourceLocalAmount(prefix, qty.n.toBigDecimal(), "count")?.startUtf16 != 0 ||
            !groceryIntentMeasuredMetadata(input, suffix) || suffix.endsWith('?') ||
            groceryPackMeasures(row.query) != setOf(size) ||
            !ProductLexicon.tokenize(row.query).containsAll(ProductLexicon.tokenize(owner))) return parse(input)
        return parse(input, suffix)
    }

    private fun parse(input: String, ownedRetailSizeSuffix: String?): List<ParsedItem> {
        val normalized = standardGroceryRequestBody(input.trim()
            .replace(Regex("^mujhe\\s+", RegexOption.IGNORE_CASE), ""))
        if (normalized.isEmpty()) return emptyList()

        val withoutPrefix = stripLeadingCommands(normalized)

        if (withoutPrefix.isEmpty()) return emptyList()
        if (noOpRegex.matches(withoutPrefix.lowercase(Locale.US))) return emptyList()

        val hasNoisySplitters = noisySplitterRegex.containsMatchIn(withoutPrefix)
        val parsedItems = mutableListOf<ParsedItem>()

        val (neutral, literal) = primarySplitterRegex.split(withoutPrefix).partition { neutralDetached.matches(it.trim()) }
        val (subjectless, sourceClauses) = literal
            .flatMap { splitOriginalClauses(it, secondarySplitterRegex) }
            .partition(::lacksProductSubject)
        // A uniquely bound, explicitly separated courtesy owns only its own words.
        // Preserve original scope accounting without inventing a corresponding SKU.
        val courtesies = neutral.map(String::trim).filter { it.codePoints().anyMatch(Character::isLetter) }
        val originalClauses = sourceClauses.map(::stripLeadingCommands).filter(String::isNotBlank) + courtesies
        val representedClauses = courtesies.toMutableList()
        var orphanedExclusion = false
        var hasMeasuredRetailSource = false
        val retailClauseScopeProven by lazy {
            groceryIntentParserClauseScopeProven(input, originalClauses)
        }
        sourceClauses.forEach { sourceClause ->
            // Strip requests for retrieval on original characters. Receipt
            // construction separately proves that prefix; ordinary evidence
            // retains the full clause, including command-like product names.
            val originalSource = stripLeadingCommands(sourceClause)
            val rowStart = parsedItems.size
            val compositeSource = sourceCompositeRetailReceipt(originalSource)
            // Unsized named phrases retain their existing descriptor/ambiguity
            // handling. Only a proven count-plus-size needs this projection.
            val measuredRetailSource = sourceNamedRetailCountReceipt(originalSource)?.takeIf { it.size != null }
            hasMeasuredRetailSource = hasMeasuredRetailSource || compositeSource != null || measuredRetailSource != null
            if ((compositeSource != null || measuredRetailSource != null) && (!retailClauseScopeProven ||
                    !groceryIntentSourceClauseContextProven(input, originalSource, originalSource.length))) {
                // Preserve the whole failed clause. Falling through to implicit
                // splits could erase its factor or governing condition.
                parsedItems += ParsedItem(originalSource, compositeSource?.literalQuery() ?: measuredRetailSource!!.literalQuery(),
                    Quantity.Count((compositeSource?.requestedCount ?: measuredRetailSource!!.count).value.intValueExact()),
                    quantitySignal = "instruction context needs review", retailPackCount = true,
                    requiresIntentQuantityProof = true, unprovenIdentityScope = true)
                representedClauses += originalSource
                return@forEach
            }
            val packInput = originalSource
                .replace(ProductLexicon.maggiMinuteDescriptorRegex, "maggi 2-minute")
                .replace(ProductLexicon.onePlusPhraseRegex, "oneplus")
            val retailPackCount = hasExplicitRetailPackCount(packInput)
            val spoken = normalizeSpokenQuantitySegment(normalizeTrailingSpokenPackCount(packInput))
                .copy(ambiguousPackQuantities = hasConflictingPackCounts(packInput))
                .copy(retailPackCount = retailPackCount, sourceText = sourceClause)
            val quantityInput = normalizeTrailingMeasure(normalizePacketUnitCountSegment(spoken.text))
            val milkTetraNormalization = normalizeMilkTetraPackSegment(quantityInput)
            // The closed original source separates a generic purchase unit
            // from product identity before descriptor preservation. This is an
            // ephemeral parser view; sourceText and amount spans stay original.
            val packetSource = sourcePacketCountSyntax(originalSource)?.takeIf {
                !spoken.ambiguousPackQuantities && spoken.quantitySignal == null &&
                    groceryIntentSourceClauseContextProven(input, originalSource, originalSource.length)
            }
            val namedRetailSource = measuredRetailSource?.takeIf {
                !spoken.ambiguousPackQuantities && spoken.quantitySignal == null &&
                    groceryIntentSourceClauseContextProven(input, originalSource, originalSource.length)
            }
            val articleMeasure = sourceArticleMeasure(originalSource)?.takeIf {
                groceryIntentSourceClauseContextProven(input, originalSource, originalSource.length)
            }
            hasMeasuredRetailSource = hasMeasuredRetailSource || articleMeasure != null
            val quantityText = articleMeasure?.let { (amount, noun) -> "1 ${amount.unit} $noun" } ?: compositeSource?.let {
                // Size/factor have original source owners, not implicit item
                // boundaries. Restore them only after validating this projection.
                "${it.requestedCount.value.stripTrailingZeros().toPlainString()} ${originalSource.substring(it.identity)}"
            } ?: (namedRetailSource ?: packetSource)?.let {
                "${it.count.value.stripTrailingZeros().toPlainString()} ${originalSource.substring(it.identity)}"
            } ?: milkTetraNormalization.normalized ?: quantityInput
            // Quantity normalization may move an explicit purchase count
            // ahead of its pack size. Derive metadata from that same
            // normalized phrase rather than retaining an earlier false flag.
            val normalizedRetailPackCount = compositeSource != null || namedRetailSource != null || retailPackCount || hasExplicitRetailPackCount(quantityText)
            val ambiguousPackQuantities = compositeSource == null && (spoken.ambiguousPackQuantities || milkTetraNormalization.ambiguous)
            // Bind the exact original quote whose neutral prefix was proved,
            // so later clauses retain the same final packet-review guards.
            val sourceBoundSpoken = if (compositeSource != null) spoken.copy(
                sourceText = compositeSource.source, quantitySignal = null, ambiguousPackQuantities = false,
            ) else if (namedRetailSource != null || packetSource != null)
                spoken.copy(sourceText = (namedRetailSource ?: packetSource)!!.source) else spoken
            val implicitParts = splitImplicitQuantityBoundaries(sourceBoundSpoken, quantityText, ambiguousPackQuantities)
            val mixedRetailPackSource = normalizedRetailPackCount && implicitParts.size > 1
            implicitParts.filter { it.text.isNotEmpty() }.forEach implicitPartLoop@{ part ->
                val spokenNormalized = part.copy(
                    ambiguousMilkTetraPack = milkTetraNormalization.ambiguous,
                    ambiguousPackQuantities = ambiguousPackQuantities,
                    // An implicit quantity boundary can otherwise copy a
                    // pack flag onto a later plain item (for example
                    // "1 milk pack 2 samosa"). Fail closed for the whole
                    // mixed source so no later item is treated as packed.
                    quantitySignal = if (mixedRetailPackSource) {
                        "ambiguous mixed retail-pack source"
                    } else {
                        part.quantitySignal
                    },
                    retailPackCount = if (mixedRetailPackSource) false else normalizedRetailPackCount,
                )
                val segment = spokenNormalized.text
                val quantitySignal = if (spokenNormalized.ambiguousPackQuantities) {
                    "conflicting pack quantities"
                } else spokenNormalized.quantitySignal
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
                    if (avoidPhrases.isNotEmpty()) orphanedExclusion = true
                    return@implicitPartLoop
                }

                val expanded = expandKnownProductSequence(cleaned)
                val holdExpandedQuantity = expanded.size > 1 &&
                    (quantity != Quantity.Default || avoidPhrases.isNotEmpty() || quantitySignal != null)
                val outputProducts = if (holdExpandedQuantity) listOf(cleaned) else expanded
                val rowQuantitySignal = if (holdExpandedQuantity) {
                    quantitySignal ?: "ambiguous quantity across multiple products"
                } else quantitySignal
                val confidence = when {
                    rowQuantitySignal != null -> 0.60f
                    expanded.size > 1 -> 0.70f
                    hasNoisySplitters || !cleaned.equals(segment.trim(), ignoreCase = false) -> 0.85f
                    else -> 1.0f
                }

                outputProducts.forEach productLoop@{ item ->
                    val itemQuantity = if (holdExpandedQuantity || expanded.size == 1) quantity else Quantity.Default
                    val exactMeasuredPack = (itemQuantity is Quantity.Count && isMeasuredDescriptorPrefix(item)) ||
                        leadingMeasuredMultipackRegex.matches(item)
                    val productText = if (exactMeasuredPack) normalizeMeasuredPackPrefix(item) else item
                    val query = ProductLexicon.canonicalizeProductText(productText).lowercase(Locale.US)
                    if (query.isBlank()) return@productLoop
                    val strictMatchPhrase = if (exactMeasuredPack) query else null
                    val candidate = ParsedItem(
                        // An unresolved split has no independently owned source
                        // span. Keep the whole clause, not a plausible fragment
                        // whose other amounts were dropped by normalization.
                        rawText = if (rowQuantitySignal != null) sourceClause else item,
                        query = query,
                        quantity = itemQuantity,
                        quantitySignal = rowQuantitySignal,
                        retailPackCount = spokenNormalized.retailPackCount,
                        parserConfidence = if (!query.equals(item, ignoreCase = true)) minOf(confidence, 0.85f) else confidence,
                        avoidPhrases = if (holdExpandedQuantity || expanded.size == 1) avoidPhrases else emptyList(),
                        strictMatchPhrase = strictMatchPhrase,
                    )
                    val sourceText = if (rowQuantitySignal != null) sourceClause else spokenNormalized.sourceText
                    parsedItems.add(if (sourceText == null) {
                        candidate.copy(
                            quantitySignal = rowQuantitySignal ?: "source evidence missing",
                            requiresIntentQuantityProof = true,
                        )
                    } else {
                        candidate.withDeterministicSourceEvidence(
                            sourceText, input,
                            ownsClause = outputProducts.size == 1 && implicitParts.size == 1 &&
                                !holdExpandedQuantity && !mixedRetailPackSource && !ambiguousPackQuantities,
                            receiptSource = stripLeadingCommands(sourceText),
                            compositeScopeProven = compositeSource != null && retailClauseScopeProven,
                        )
                    })
                }
            }
            if (parsedItems.size > rowStart) representedClauses += originalSource
        }

        if (orphanedExclusion || subjectless.any { it != ownedRetailSizeSuffix } ||
            hasMeasuredRetailSource && !groceryIntentParserClauseScopeProven(input, originalClauses, representedClauses)) {
            return parsedItems.map { it.copy(quantitySignal = it.quantitySignal ?: "instruction context needs review",
                requiresIntentQuantityProof = true, unprovenIdentityScope = true, intentQuantityCatalogueCheck = null) }
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

    private fun splitOriginalClauses(text: String, splitter: Regex): List<String> {
        val protectedRanges = mutableListOf<IntRange>()
        fractionalMeasureRegexes.forEach { (pattern, _) ->
            pattern.findAll(text).forEach { protectedRanges += it.range }
        }
        ProductLexicon.onePlusPhraseRegex.findAll(text).forEach { protectedRanges += it.range }

        val clauses = mutableListOf<String>()
        var start = 0
        for (match in splitter.findAll(text)) {
            val overlapsProtected = protectedRanges.any { protected ->
                match.range.first <= protected.last && protected.first <= match.range.last
            }
            if (overlapsProtected) continue
            text.substring(start, match.range.first).trim().takeIf { it.isNotEmpty() }?.let { clauses.add(it) }
            start = match.range.last + 1
        }
        text.substring(start).trim().takeIf { it.isNotEmpty() }?.let { clauses.add(it) }
        return clauses
    }

    private fun splitImplicitQuantityBoundaries(
        spoken: SpokenQuantityNormalization,
        text: String,
        skipSplitting: Boolean,
    ): List<SpokenQuantityNormalization> {
        if (skipSplitting) return listOf(spoken.copy(text = text))

        val parts = mutableListOf<String>()
        var start = 0
        var didSplit = false
        for (boundary in quantityBoundaryRegex.findAll(text)) {
            val rest = text.substring(boundary.range.last + 1)
            // Existing numeric product identities are not contents amounts.
            if (isNumericBrandPrefix(rest)) continue
            val prefixText = text.substring(start, boundary.range.first)
            if (Regex("\\bof[ \\t]*$", RegexOption.IGNORE_CASE).containsMatchIn(prefixText) ||
                !isMeasuredDescriptorPrefix(rest) && sourceHasUnresolvedContentsBoundary(prefixText)) {
                // A boundary through a possible contents relation is not an
                // independent grocery. Keep the whole clause for review; do
                // not guess outer/inner counts, including in a longer list.
                return listOf(spoken.copy(text = text,
                    quantitySignal = spoken.quantitySignal ?: "nested container quantity needs review"))
            }
            val prefix = text.substring(start, boundary.range.first).trim()
            val countedPack = Regex("^[1-9]\\d?$").matches(prefix) &&
                (isMeasuredDescriptorPrefix(rest) || leadingMeasuredMultipackRegex.matches(rest))
            val descriptorPrefix = prefix.replaceFirst(Regex("^[1-9]\\d?\\s+"), "")
            val measuredMultipack = (
                Regex("^[1-9]\\d?\\s*[x×]$", RegexOption.IGNORE_CASE).matches(prefix) ||
                    Regex("^[1-9]\\d?\\s+[1-9]\\d?\\s*[x×]$", RegexOption.IGNORE_CASE).matches(prefix) ||
                    Regex("^[1-9]\\d?\\s*[x×]$", RegexOption.IGNORE_CASE).matches(descriptorPrefix)
                ) && isMeasuredDescriptorPrefix(rest)
            if (countedPack || measuredMultipack) continue

            parts += text.substring(start, boundary.range.first)
            start = boundary.range.last + 1
            didSplit = true
        }
        if (!didSplit) return listOf(spoken.copy(text = text))
        parts += text.substring(start)

        val originalSource = spoken.sourceText
        val sourceMappingIsExact = originalSource != null && text == originalSource
        return parts.map { part ->
            spoken.copy(
                text = part,
                sourceText = if (sourceMappingIsExact) part else originalSource,
                quantitySignal = spoken.quantitySignal ?: when {
                    sourceMappingIsExact -> null
                    originalSource == null -> "source evidence missing"
                    else -> "ambiguous quantity split after normalization"
                },
            )
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
            // Stored test seeds and inference are not user instructions. Keep
            // them inert here; live order history ranks catalogue candidates
            // independently and must not rewrite the source-owned request.
            val preference = lookup(item.query)?.takeIf { it.source == PreferenceSource.USER_OVERRIDE }
            if (preference != null) {
                val avoided = (item.avoidPhrases + preference.avoidPhrases)
                    .map(::cleanAvoidPhrase)
                    .filter { it.isNotBlank() }
                    .distinct()
                // Source-owned identities remain authoritative. History can
                // rank candidates, not rewrite the receipt. Exclusions still
                // survive; changed constraints must pass revalidation.
                val preferred = preference.preferredPhrase.trim().lowercase(Locale.US)
                val sourceLabels = com.example.beta.groceryIntentOpaqueNumericLabels(item.query)
                // A saved refinement cannot replace a source-owned product/model
                // identifier. Keep the original search, not a permanently held rewrite.
                if (item.intentQuantityProvenance?.sourceCountRole != null ||
                    (sourceLabels.isNotEmpty() && sourceLabels != com.example.beta.groceryIntentOpaqueNumericLabels(preferred))) {
                    return@map item.copy(avoidPhrases = avoided, intentQuantityCatalogueCheck = null)
                }
                log("PREFERENCE_APPLIED source=USER_OVERRIDE")
                item.copy(
                    query = preferred,
                    avoidPhrases = avoided,
                    strictMatchPhrase = preferred,
                    intentQuantityProvenance = item.intentQuantityProvenance?.let { proof ->
                        if (proof.origin == IntentQuantityOrigin.SOURCE_NUMERIC) proof.copy(
                            query = preferred, strictMatchPhrase = preferred,
                            opaqueNumericLabels = com.example.beta.groceryIntentOpaqueNumericLabels(preferred),
                        ) else proof
                    },
                    intentQuantityCatalogueCheck = null,
                )
            } else {
                log("PREFERENCE_NONE")
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

    internal fun isNumericBrandPrefix(text: String): Boolean = ProductLexicon.isNumericBrandPrefix(text)

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
    val sourceText: String? = null,
)
