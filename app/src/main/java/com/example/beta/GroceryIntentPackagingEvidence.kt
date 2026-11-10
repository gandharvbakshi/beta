package com.example.beta

import com.example.beta.automation.IntentLocalAmount
import java.text.Normalizer
import java.util.Locale

/**
 * Narrow source-derived packaging evidence for lexicalized household products.
 * This deliberately does not translate generic product words such as "tikki".
 */
internal fun groceryIntentPackagingEvidence(
    sourceText: String,
    proposedName: String,
    packaging: String?,
): Set<String> {
    if (packaging == null) return emptySet()
    val proposed = packagingEvidenceWords(proposedName)

    val matchboxIdentity = proposed == listOf("matchbox") || proposed == listOf("matchboxes")
    if (packaging == "box" && matchboxIdentity && matchesPackagingPhrase(
            sourceText, "matchbox(?:es)?",
        )
    ) return setOf("box")

    val soapIdentity = proposed == listOf("soap") || proposed == listOf("sabun")
    if (packaging == "bar" && soapIdentity && matchesPackagingPhrase(
            sourceText, "(?:sabun|soap)[ ]+ki[ ]+(?:tikki|tikiya)",
        )
    ) return setOf("bar")

    return emptySet()
}

/**
 * V2 omission guard, not a container inference engine. Retain every recognized
 * form in the exact owned fragments, whether represented in name or metadata.
 * This only rejects missing evidence; it never repairs or broadens a query.
 */
internal fun groceryIntentStatedPackagingMissing(
    fragments: List<String>,
    proposedTokens: Set<String>,
): Boolean = fragments.any { fragment ->
    !proposedTokens.containsAll(statedPackagingIdentityTokens(fragment))
}

private fun statedPackagingIdentityTokens(fragment: String): Set<String> {
    // Only this complete leading question is modal. A later "can" (including
    // in "Can you get one can beans") remains evidence of the requested form.
    val withoutModalPrefix = leadingPackagingModal.replaceFirst(fragment, " ")
    return intentEvidenceTokens(withoutModalPrefix).intersect(statedPackagingTokens)
}

/** Source-only lexical obligations, NOT evidence of a container/count role.
 * Reuse V2's omission vocabulary without its quantity grammar: a suffix size
 * cannot erase a form, and "can opener" still means the whole product identity.
 * Null means unresolved polarity, NOT absence of a form. Callers must hold;
 * an empty set means no specific form. Generic pack/packet does not specify a
 * particular catalogue form. A scoped correction must be proven separately. */
internal fun groceryIntentPositiveSourceFormTokens(source: String, positive: String = "", excluded: List<String> = emptyList()): Set<String>? {
    val identity = if (groceryIntentTerminalExcludedPropertyMatch(source) != null)
        groceryIntentRetainedTerminalPropertyView(source, positive, excluded) ?: return null else source
    val forms = groceryIntentSpecificSourceFormTokens(identity)
    return if (forms.isNotEmpty() && intentV2HasCorrectionCue(identity)) null else forms
}

internal fun groceryIntentSpecificSourceFormTokens(source: String): Set<String> =
    statedPackagingIdentityTokens(source) - setOf("pack", "packet")

private val leadingPackagingModal = Regex(
    "^\\s*(?:please\\s+)?can\\s+(?:you|we|i)\\b", RegexOption.IGNORE_CASE,
)
private val statedPackagingTokens = setOf(
    "pack", "packet", "bag", "bottle", "carton", "tetrapack", "cup", "can", "tin", "tub", "jar", "box",
    "bar", "block", "loaf", "bunch", "roll", "refill", "pouch", "sachet", "tube", "spray",
)

private val packagingCountWords = setOf(
    "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
    "a", "an", "ek", "do", "teen", "char", "chaar", "paanch", "panch", "chhe", "cheh", "sat", "saat",
    "ath", "aath", "nau", "nou", "das", "barah",
)

/** Exact whole primary quote only: no quantity interpretation or punctuation token erasure. */
private fun matchesPackagingPhrase(sourceText: String, phrase: String): Boolean {
    if (sourceText.any { Character.isISOControl(it) }) return false
    val normalized = Normalizer.normalize(sourceText, Normalizer.Form.NFKC).trim().lowercase(Locale.ROOT)
    val count = "(?:[1-9][0-9]{0,2}|${packagingCountWords.joinToString("|")})"
    return Regex("^(?:$count[ ]+)?$phrase[,;.!?]?$", RegexOption.IGNORE_CASE).matches(normalized)
}

private fun packagingEvidenceWords(text: String): List<String> =
    Regex("[\\p{L}\\p{N}]+").findAll(Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT))
        .map { it.value }.toList()

/** A model-selected pack unit is actionable only when its bound amount owns a
 * concrete retail container in the same primary source (or a verified group
 * header/each amount). This is a rejection guard; it never changes the unit. */
internal fun GroceryIntentReviewV2ReadyRow.retailPackEvidenceIssue(
    group: GroceryIntentReviewV2Group?,
    instruction: String? = null,
): String? {
    if (group == null && corrections.isEmpty() && item.packaging != null &&
        item.supportingText.isEmpty() && evidence.supporting.isEmpty() && !item.needsClarification) {
        val receipt = sourceRetailCountEvidence()
        if (receipt != null && receipt.packaging != item.packaging) return "retail_container_conflict"
    }
    if (effectiveRetailPackUnit(group) != "pack") return null
    if (group == null && genericContainerRange() != null) return null
    val primary = evidence.primary.text
    val local = if (item.quantity != null) localQuantityEvidence() else null
    val primaryAmount = local?.takeIf { it.source == primary }
    if (primaryAmount != null) {
        if (exactLexicalizedRetailPack(item.packaging, item.name, primary)) return null
        if (group?.packaging != null && !retailContainerRegex.containsMatchIn(primary)) return null
        val packAmount = localPackSizeEvidence()?.let { RetailPackAmount(it.startUtf16, it.endUtf16, it.atom, it.unit) }
        // The same independently bound contents relation also proves the outer
        // role when the producer labels it "pack". Identity/coverage stay separate.
        if (group == null && corrections.isEmpty() && packAmount?.unit == "piece" &&
            sourceContainerContentsRelation(primary, primaryAmount.startUtf16, primaryAmount.endUtf16,
                packAmount.start, packAmount.end)) return null
        val ingredientRanges = intentV2ProvenIngredientRanges(primary, item.name, item.required, item.excluded)
        if (retailContainerAttached(primary, primaryAmount, packAmount, item.name, instruction,
                evidence.primary.startUtf16, ingredientRanges)) return null
        return "retail_pack_unproven"
    }

    // A shared packaging header proves the outer form. Without it, only an
    // explicit container tied to the exact verified `each` amount is enough.
    if (group?.packaging != null) return null
    val each = group?.each ?: return "retail_pack_unproven"
    val eachStart = each.source.text.indexOf(each.amountText)
    if (eachStart < 0 || each.source.text.indexOf(each.amountText, eachStart + 1) >= 0) return "retail_pack_unproven"
    val eachAmount = IntentLocalAmount(
        each.source.text, eachStart, eachStart + each.amountText.length,
        each.amountText, each.value, each.unit,
    )
    return if (retailContainerAttached(each.source.text, eachAmount, null, item.name, allowGroupEach = true)) null
    else "retail_pack_unproven"
}

internal fun GroceryIntentReviewV2ReadyRow.effectiveRetailPackUnit(group: GroceryIntentReviewV2Group?): String? {
    var unit = if (item.quantity != null) item.quantityUnit
        else group?.members?.singleOrNull { it.rowIndex == rowIndex }?.unit
    corrections.firstOrNull { it.kind == "quantity" }?.let { unit = it.newUnit }
    return unit
}

/** A reusable ORIGINAL count/container receipt, independent of model unit or
 * generic default-pack handling. Numeric and identity checks remain mandatory. */
internal fun GroceryIntentReviewV2ReadyRow.localRetailContainerReceipt(): Boolean {
    if (item.quantityUnit !in setOf("count", "pack")) return false
    val count = localQuantityEvidence() ?: return false
    val size = localPackSizeEvidence()?.let { RetailPackAmount(it.startUtf16, it.endUtf16, it.atom, it.unit) }
    return retailContainerAttached(evidence.primary.text, count, size, item.name,
        ingredientRanges = intentV2ProvenIngredientRanges(evidence.primary.text, item.name, item.required, item.excluded))
}

/** Only an independently validated group may be passed here. No not-applicable
 * validator result or final unit label is positive evidence of a container. */
internal fun groupRetailContainerReceipt(group: GroceryIntentReviewV2Group?): Boolean {
    if (group == null) return false
    if (group.packaging != null) return true
    val each = group.each ?: return false
    val start = each.source.text.indexOf(each.amountText)
    if (start < 0 || each.source.text.indexOf(each.amountText, start + 1) >= 0) return false
    val amount = IntentLocalAmount(each.source.text, start, start + each.amountText.length,
        each.amountText, each.value, each.unit)
    return retailContainerAttached(each.source.text, amount, null, "", allowGroupEach = true)
}

// Original source only; ASCII case variants do not admit Unicode look-alikes.
internal const val groceryIntentGaddiContainerPattern =
    "(?<![^\\s\\p{Z}\\u0085\\u001c-\\u001f,.;:!?()\\[\\]{}])(?-i:[gG][aA][dD][dD][iI])(?=$|[\\s\\p{Z}\\u0085\\u001c-\\u001f,.;:!?()\\[\\]{}])"

private val retailContainerRegex = Regex(
    "(?<![\\p{L}\\p{N}_\\p{M}])(?:tetra[\\s-]*packs?|packs?|packets?|bags?|bottles?|cartons?|cups?|cans?|tins?|tubs?|jars?|" +
        "box(?:es)?|bars?|blocks?|pouch(?:es)?|sachets?|tubes?|loaf|loaves|refills?|rolls?|sprays?|bunch(?:es)?)(?![\\p{L}\\p{N}_\\p{M}])|" +
        groceryIntentGaddiContainerPattern,
    RegexOption.IGNORE_CASE,
)
private const val retailPackWhitespace = "[\\s\\p{Z}\\u0085\\u001c-\\u001f]"

/** Original lexical container boundary, without product-token normalization. */
internal fun groceryIntentContainsRetailContainer(source: String): Boolean =
    retailContainerRegex.containsMatchIn(source)

private fun exactLexicalizedRetailPack(packaging: String?, name: String, source: String): Boolean {
    if (packaging == null || source.any { Character.isISOControl(it) }) return false
    val proposed = packagingEvidenceWords(name)
    val normalized = Normalizer.normalize(source, Normalizer.Form.NFKC).trim().lowercase(Locale.ROOT)
    val count = "(?:[1-9][0-9]{0,2}|${packagingCountWords.joinToString("|")})"
    fun exact(phrase: String) = Regex("^(?:$count[ ]+)?$phrase[,;.!?]?$").matches(normalized)
    return (packaging == "box" && proposed in listOf(listOf("matchbox"), listOf("matchboxes")) &&
        exact("matchbox(?:es)?")) ||
        (packaging == "bar" && proposed in listOf(listOf("soap"), listOf("sabun")) &&
            exact("(?:sabun|soap)[ ]+ki[ ]+(?:tikki|tikiya)"))
}

private data class RetailPackAmount(val start: Int, val end: Int, val atom: String, val unit: String)

private const val positiveRetailClauseSuffix = "[ \\t]*" +
        "(?:(?:and|aur|then|also|phir|plus)[ \\t]+)?" +
        "(?:(?:please[ \\t]+)?(?:(?:can|could|would)[ \\t]+you[ \\t]+(?:please[ \\t]+)?)?" +
        "(?:add|get|buy|order|bring)[ \\t]+)?\\z"
private val positiveRetailClauseStart = Regex(
    "(?:\\A|[,;:.!?])" + positiveRetailClauseSuffix,
    RegexOption.IGNORE_CASE,
)
private val positiveRetailLineStart = Regex(
    "(?:\\A|[,;:.!?\\r\\n])" + positiveRetailClauseSuffix,
    RegexOption.IGNORE_CASE,
)

/** Whole original prefix only, never a suffix verb that hides a condition or
 * amount. This is local count-edge proof, not global coverage or action consent. */
private fun hasPositiveRetailClauseStart(before: String, allowLineBoundary: Boolean = false): Boolean {
    // Preserve the caller's previous newline policy and Python's ASCII casing.
    val pattern = if (allowLineBoundary) positiveRetailLineStart else positiveRetailClauseStart
    val match = pattern.find(before) ?: return false
    return match.value.all { it.code <= 127 }
}

/** Exact identity ownership only, never a general `of` stop-word exemption. */
private fun identityOwnsInternalOf(
    source: String,
    amount: IntentLocalAmount,
    container: MatchResult,
    productName: String,
    gap: String,
    instruction: String?,
    primaryStart: Int?,
): Boolean {
    val phrase = Regex("[ \\t]+([A-Za-z]+(?:[ \\t]+[A-Za-z]+){2,3})[ \\t]+").matchEntire(gap) ?: return false
    val rawTokens = phrase.groupValues[1].split(Regex("[ \\t]+"))
    val tokens = rawTokens.map { it.lowercase(Locale.ROOT) }
    if (tokens.count { it == "of" } != 1 || tokens.first() == "of" || tokens.last() == "of" || "and" in tokens) return false
    // Speech casing cannot prove a brand or quantity role. Preserve the whole
    // exact nominal phrase, but reject copied clauses in any capitalization.
    if (tokens.any { it != "of" && it in groceryIntentNominalScopeUnsafeWords } ||
        groceryIntentHasNominalClausePredicate(phrase.groupValues[1])) return false
    val measures = ("glass glasses spoon spoons teaspoon teaspoons tablespoon tablespoons " +
        "pinch pinches handful handfuls scoop scoops drop drops ladle ladles sip sips " +
        "dose doses serving servings portion portions slice slices piece pieces pc pcs " +
        "set sets pair pairs chunk chunks dollop dollops lump lumps bit bits " +
        "case cases crate crates tray trays strip strips sheet sheets bowl bowls stick sticks " +
        "head heads bundle bundles ounce ounces oz pound pounds lb lbs couple couples few " +
        "g gm gms gram grams kg kilo kilos kilogram kilograms keji ml millilitre millilitres " +
        "milliliter milliliters l litre litres liter liters ltr").split(' ').toSet()
    if (tokens.any { it in measures } || groceryIntentContainsNumericAtom(phrase.groupValues[1]) ||
        retailContainerRegex.containsMatchIn(gap)) return false
    val containerEnd = container.range.last + 1
    if (!Regex("[ \\t]*").matches(source.substring(0, amount.startUtf16)) ||
        !Regex("[ \\t]*[,;.!?]?[ \\t]*").matches(source.substring(containerEnd))) return false
    if (instruction == null || primaryStart == null || primaryStart < 0 ||
        primaryStart > instruction.length - source.length ||
        instruction.substring(primaryStart, primaryStart + source.length) != source) return false
    if (!hasPositiveRetailClauseStart(instruction.substring(0, primaryStart))) return false
    if (source.trimEnd(' ', '\t').last() !in ",;.!?" &&
        !Regex("^[ \\t]*(?:[,;.!?]|\\z)").containsMatchIn(instruction.substring(primaryStart + source.length))) return false
    if (productName.isEmpty()) return false
    val names = Regex(Regex.escape(productName)).findAll(source).filter {
        hasQuoteBoundaries(source, productName, it.range.first, it.range.last + 1)
    }.toList()
    val name = names.singleOrNull() ?: return false
    val start = amount.endUtf16 + gap.length - gap.trimStart(' ', '\t').length
    val end = container.range.first - (gap.length - gap.trimEnd(' ', '\t').length)
    return name.range.first == start && name.range.last + 1 in setOf(end, containerEnd)
}

private fun hasUnownedNominalControl(gap: String, startOffset: Int, ingredientRanges: List<IntRange>): Boolean {
    val tokens = Regex("[a-z]+", RegexOption.IGNORE_CASE).findAll(gap)
    return tokens.any { token -> token.value.lowercase(Locale.ROOT) in groceryIntentNominalScopeUnsafeWords &&
        ingredientRanges.none { range -> startOffset + token.range.first >= range.first &&
            startOffset + token.range.last <= range.last } } ||
        groceryIntentContainsNumericAtom(gap) || groceryIntentHasNominalClausePredicate(gap)
}

/** Literal count + whole nominal identity + container, optionally followed by
 * one closed property. Not a larger generic word gap; all other checks apply. */
private fun identityOwnsLongContainerGap(
    source: String,
    amount: IntentLocalAmount,
    container: MatchResult,
    productName: String,
    gap: String,
    instruction: String?,
    primaryStart: Int?,
    ingredientRanges: List<IntRange>,
    minWords: Int = 5,
): Boolean {
    val word = "[A-Za-z]+(?:[-'\u2019][A-Za-z]+)*"
    val phrase = Regex("[ \\t]+($word(?:[ \\t]+$word){${minWords - 1},11})[ \\t]+").matchEntire(gap) ?: return false
    if (productName.isEmpty() || instruction == null || primaryStart == null) return false
    if (hasUnownedNominalControl(gap, amount.endUtf16, ingredientRanges)) return false
    val containerEnd = container.range.last + 1
    // Recognize one complete comma-delimited property, never a free clause.
    // This is syntax proof only; the original source and all dietary checks
    // remain intact. No name/source/amount is rewritten here.
    val tail = source.substring(containerEnd)
    val propertyTail = Regex("[ \\t]*,[ \\t]*([^,;.!?\\r\\n]+?)[ \\t]*[.!?]?[ \\t]*").matchEntire(tail)
    val closedProperty = propertyTail != null && groceryIntentIsClosedNominalProperty(propertyTail.groupValues[1])
    // The sole caller proved one owned container after excluding mentions
    // inside the unique exact name. Recounting raw words would reject a
    // 'room spray bottle' even though its outer container is unambiguous.
    if (!Regex("[ \\t]*").matches(source.substring(0, amount.startUtf16)) ||
        !(Regex("[ \\t]*[,;.!?]?[ \\t]*").matches(tail) || closedProperty) ||
        primaryStart < 0 ||
        primaryStart > instruction.length - source.length ||
        instruction.substring(primaryStart, primaryStart + source.length) != source) return false
    val nameStart = amount.endUtf16 + phrase.groups[1]!!.range.first
    val nameEnd = amount.endUtf16 + phrase.groups[1]!!.range.last + 1
    val names = setOf(source.substring(nameStart, nameEnd), source.substring(nameStart, containerEnd))
    val retainedNames = if (closedProperty) names + names.map { it + tail.trimEnd(' ', '\t', '.', '!', '?') } else names
    if (productName !in retainedNames) {
        // Typed claims can live in required/excluded. Cover the ORIGINAL gap
        // with one contiguous literal name plus proved claim spans. Do not
        // erase or rejoin source words to manufacture product identity.
        val matches = Regex(Regex.escape(productName)).findAll(source).filter {
            hasQuoteBoundaries(source, productName, it.range.first, it.range.last + 1)
        }.toList()
        val name = matches.singleOrNull()?.range ?: return false
        if (name.first < nameStart || name.last >= nameEnd) return false
        val owned = listOf(name) + ingredientRanges.filter { it.first >= nameStart && it.last < nameEnd }
        if ((nameStart until nameEnd).any { offset -> !source[offset].isWhitespace() &&
                owned.none { offset in it } }) return false
    }
    if (!hasPositiveRetailClauseStart(instruction.substring(0, primaryStart), allowLineBoundary = true)) return false
    return source.trimEnd(' ', '\t').last() in ",;.!?" ||
        Regex("^[ \\t]*(?:[,;.!?\\r\\n]|\\z)").containsMatchIn(instruction.substring(primaryStart + source.length))
}

/** Complete original count + refill form + literal contents, not arbitrary
 * container merging. Only refill may move inside the retained product name. */
private fun refillCompoundAttached(
    source: String,
    amount: IntentLocalAmount,
    pack: RetailPackAmount?,
    productName: String,
    instruction: String?,
    primaryStart: Int?,
): Boolean {
    val containers = retailContainerRegex.findAll(source).toList()
    if (containers.size != 2 || instruction == null || primaryStart == null) return false
    val (modifier, outer) = containers
    val refill = Regex("[rR][eE][fF][iI][lL][lL]")
    if (outer.value.any { it.code > 127 } || !refill.matches(modifier.value) || Regex("[rR][eE][fF][iI][lL][lL][sS]?").matches(outer.value) ||
        !Regex("(?:[ \\t]+|[ \\t]*-[ \\t]*)").matches(source.substring(modifier.range.last + 1, outer.range.first))) return false
    val words = productName.trim(' ', '\t').split(Regex("[ \\t]+"))
    if (words.count { refill.matches(it) } != 1) return false
    val core = words.filterNot { refill.matches(it) }.joinToString(" ")
    val word = "[A-Za-z]+(?:[-'\u2019][A-Za-z]+)*"
    val contextControls = inlineParticleSelectors + setOf("every", "daily", "weekly", "monthly", "nightly",
        "today", "tomorrow", "tonight", "yesterday", "as", "before", "after", "ago", "again")
    if (!Regex("$word(?: $word){0,11}").matches(core) || hasUnownedNominalControl(core, 0, emptyList()) ||
        core.lowercase(Locale.ROOT).split(' ').any { it in contextControls }) return false
    if (primaryStart < 0 || primaryStart > instruction.length - source.length ||
        instruction.substring(primaryStart, primaryStart + source.length) != source ||
        !hasPositiveRetailClauseStart(instruction.substring(0, primaryStart), allowLineBoundary = true) ||
        !Regex("^[ \\t]*(?:[,;.!?\\r\\n]|\\z)").containsMatchIn(instruction.substring(primaryStart + source.length))) return false
    val count = Regex.escape(source.substring(amount.startUtf16, amount.endUtf16))
    val compound = Regex.escape(source.substring(modifier.range.first, outer.range.last + 1))
    val contents = "[ \\t]+(?:of[ \\t]+)?" + core.split(' ').joinToString("[ \\t]+") { Regex.escape(it) }
    val body = compound + contents
    var variants = listOf(count + "[ \\t]+" + body)
    if (pack != null) {
        val unit = retailPackUnitRegex(pack.unit) ?: return false
        val suffix = Regex("^[ \\t]*-?[ \\t]*$unit\\b", RegexOption.IGNORE_CASE).find(source.substring(pack.end)) ?: return false
        val size = Regex.escape(source.substring(pack.start, pack.end + suffix.value.length))
        variants = listOf(count + "[ \\t]+" + size + "[ \\t]+" + body,
            count + "[ \\t]+" + body + "[ \\t]+" + size + "(?:[ \\t]+each)?")
    }
    return variants.any { Regex("(?i)[ \\t]*$it[ \\t]*[,;.!?]?[ \\t]*").matches(source) }
}

/** Deny copied subject/distribution clauses before all container-order paths.
 * Bound size-each is not a per-person total. Never rewrite the retained source. */
private val purchaseScopeContext = Regex(
    "\\b(?:per|each|every|apiece|daily|weekly|monthly|nightly|today|tomorrow|tonight|" +
        "usual|regular|previous|again|as[ \\t]+(?:before|usual))\\b", RegexOption.IGNORE_CASE,
)
internal fun groceryIntentPurchaseScopeContextRanges(text: String): List<IntRange> =
    purchaseScopeContext.findAll(text).map { it.range }.toList()

private val retailFinitePredicates = (
    "needs wants prefers requests requires chooses selects recommends asks says wishes demands " +
        "receives shares holds contains costs weighs"
).split(' ').toSet()
private val retailPredicateSubject = Regex(
    "\\b(?:i|you|we|they|he|she|it|someone|anyone|person|people|child|children|" +
        "adult|adults|parent|parents|guest|guests|family|families|can|could|would|" +
        "should|must|will|shall|may|might)\\b", RegexOption.IGNORE_CASE,
)

private fun retailCountScopeUnproven(
    source: String,
    amount: IntentLocalAmount,
    containers: List<MatchResult>,
    pack: RetailPackAmount?,
    allowGroupEach: Boolean,
): Boolean {
    val sizeRange = pack?.let { size ->
        retailPackUnitRegex(size.unit)?.let { unit ->
            Regex("^[ \\t]*-?[ \\t]*$unit\\b", RegexOption.IGNORE_CASE).find(source.substring(size.end))
                ?.let { size.start until size.end + it.value.length }
        }
    }
    val repeatedSizes = sizeRange?.let { range ->
        Regex(Regex.escape(source.substring(range)), RegexOption.IGNORE_CASE).findAll(source).map { it.range }.toList()
    }.orEmpty()
    for (marker in purchaseScopeContext.findAll(source)) {
        if (allowGroupEach && marker.value.equals("each", ignoreCase = true) &&
            Regex("[ \\t]*[,;.!?]?[ \\t]*").matches(source.substring(marker.range.last + 1))) continue
        if (marker.value.equals("each", ignoreCase = true) && sizeRange != null) {
            val terminal = Regex("[ \\t]*[)\\]]?[ \\t]*[,;.!?]?[ \\t]*")
            if (repeatedSizes.any { size ->
                    val beforeSize = marker.range.last + 1 <= size.first &&
                        Regex("[ \\t]+").matches(source.substring(marker.range.last + 1, size.first)) &&
                        terminal.matches(source.substring(size.last + 1)) &&
                        !Regex("\\bfor[ \\t]*\\z", RegexOption.IGNORE_CASE).containsMatchIn(source.substring(0, marker.range.first))
                    val afterSize = size.last + 1 <= marker.range.first &&
                        Regex("[ \\t]+").matches(source.substring(size.last + 1, marker.range.first)) &&
                        terminal.matches(source.substring(marker.range.last + 1))
                    beforeSize || afterSize
                }) continue
        }
        return true
    }
    val owned = listOf(amount.startUtf16 until amount.endUtf16) + containers.map { it.range } + listOfNotNull(sizeRange)
    var nominal = source.mapIndexed { index, char -> if (owned.any { index in it }) ' ' else char }.joinToString("")
    nominal = Regex("^\\s*(?:please\\s+)?(?:(?:can\\s+(?:you|we|i)\\s+)?(?:get|buy|order|add))\\s+",
        RegexOption.IGNORE_CASE).replaceFirst(nominal, "")
    nominal = Regex("\\bready[\\s-]*to[\\s-]*(?:eat|drink|cook)\\b", RegexOption.IGNORE_CASE).replace(nominal, "prepared")
    // Nominal drink/drinking/eating and leading brands are not finite clauses.
    return Regex("[a-z]+", RegexOption.IGNORE_CASE).findAll(nominal).any {
        it.value.lowercase(Locale.ROOT) in retailFinitePredicates
    } || retailPredicateSubject.findAll(nominal).any {
        groceryIntentHasNominalClausePredicate(nominal.substring(it.range.last + 1))
    }
}

private fun retailContainerAttached(
    source: String,
    amount: IntentLocalAmount,
    pack: RetailPackAmount?,
    productName: String,
    instruction: String? = null,
    primaryStart: Int? = null,
    ingredientRanges: List<IntRange> = emptyList(),
    allowGroupEach: Boolean = false,
): Boolean {
    var containers = retailContainerRegex.findAll(source).toList()
    // Unicode-aware edges must expose, not silently skip, a case-folded
    // look-alike. Only the literal ASCII container lexicon grants this role;
    // unrelated Unicode product names and whitespace remain unchanged.
    if (containers.any { match -> match.value.any { it.isLetter() && it.code > 127 } }) return false
    if (containers.size > 1) {
        val nameOccurrences = Regex(Regex.escape(productName)).findAll(source).filter {
            hasQuoteBoundaries(source, productName, it.range.first, it.range.last + 1)
        }.toList()
        if (nameOccurrences.size == 1) {
            val nameRange = nameOccurrences.single().range
            if (!(nameRange.first < amount.endUtf16 && amount.startUtf16 <= nameRange.last)) {
                val filtered = containers.filterNot { match ->
                    match.range.first >= nameRange.first && match.range.last <= nameRange.last
                }
                if (filtered.isNotEmpty()) containers = filtered
            }
        }
    }
    // Retain container words inside the identity as possible clause subjects.
    // Only the independently selected outer form belongs to the pack count.
    if (retailCountScopeUnproven(source, amount, containers, pack, allowGroupEach)) return false
    if (containers.size != 1) return refillCompoundAttached(source, amount, pack, productName, instruction, primaryStart)
    val container = containers.single()
    val containerEnd = container.range.last + 1
    if (containerEnd <= amount.startUtf16) {
        return Regex("(?:$retailPackWhitespace|[,;.!?])*").matches(source.substring(amount.endUtf16)) &&
            Regex("${retailPackWhitespace}*,?${retailPackWhitespace}*").matches(source.substring(containerEnd, amount.startUtf16))
    }
    if (container.range.first < amount.endUtf16) return false
    var gap = source.substring(amount.endUtf16, container.range.first)
    // Relations are checked before any size masking. Only original, preserved,
    // unqualified ingredient-absence claims own their internal `no` token.
    val controls = Regex("(?<![\\p{L}\\p{N}_\\p{M}])(?:with|if|or|ya|not|no|nahi|nahin|without|instead)(?![\\p{L}\\p{N}_\\p{M}])",
        RegexOption.IGNORE_CASE).findAll(gap).toList()
    if (controls.any { control -> ingredientRanges.none { range ->
            amount.endUtf16 + control.range.first >= range.first &&
                amount.endUtf16 + control.range.last <= range.last } }) return false
    // New exemptions require COMPLETE nominal boundaries, not just safe gap
    // words. Keep before-count and after-container clauses such as 'per child'
    // out of this exact-count path even when copied into the model's name.
    if (controls.isNotEmpty()) return pack == null && identityOwnsLongContainerGap(
        source, amount, container, productName, gap, instruction, primaryStart, ingredientRanges, minWords = 1,
    )
    if (pack != null && pack.start >= amount.endUtf16 && pack.end <= container.range.first) {
        val unit = retailPackUnitRegex(pack.unit) ?: return false
        val suffix = Regex("^\\s*-?\\s*$unit\\b", RegexOption.IGNORE_CASE)
            .find(source.substring(pack.end)) ?: return false
        val from = pack.start - amount.endUtf16
        val to = pack.end + suffix.value.length - amount.endUtf16
        if (to > gap.length) return false
        gap = gap.substring(0, from) + " " + gap.substring(to)
    }
    if (Regex("\\bof\\b", RegexOption.IGNORE_CASE).containsMatchIn(gap) &&
        (pack != null || !identityOwnsInternalOf(source, amount, container, productName, gap, instruction, primaryStart))) return false
    // Keep word-internal brand apostrophes literal; do not erase separators,
    // leading/trailing quotes or extra amounts to manufacture a retail count.
    val word = "[\\p{L}]+(?:[-'\u2019][\\p{L}]+)*"
    return Regex("${retailPackWhitespace}*(?:$word$retailPackWhitespace+){0,4}").matches(gap) ||
        (pack == null && identityOwnsLongContainerGap(source, amount, container, productName, gap, instruction, primaryStart,
            ingredientRanges))
}

private fun retailPackUnitRegex(unit: String): String? = when (unit) {
    "piece" -> "(?:pieces?|pcs?)"
    "g" -> "(?:g|gm|gms|grams?)"
    "kg" -> "(?:kg|kgs|kilos?|kilograms?|keji)"
    "ml" -> "(?:ml|millilitres?|milliliters?)"
    "l" -> "(?:l|ltr|litres?|liters?)"
    else -> null
}
