package com.example.beta

import com.example.beta.automation.ProductLexicon
import java.util.Collections
import java.util.Locale

/** Reasons are evidence about a failed comparison, never an exception/waiver
 * policy. Keep simultaneous failures: a lexical difference cannot hide a lost
 * property, borrowed scope or an unproved quantity role. */
internal enum class GroceryIntentGroundingIssueKind {
    LEXICAL_UNVERIFIED, SOURCE_SCOPE, SOURCE_FORM_UNPROVEN, PROPERTY_ADDED,
    PROPERTY_MISSING, EXCLUSION_ADDED, PACK_EVIDENCE, COUNT_ROLE,
}

internal data class GroceryIntentGroundingIssue(
    val rowIndex: Int,
    val kind: GroceryIntentGroundingIssueKind,
    val sourceText: String,
) {
    override fun toString(): String = "GroceryIntentGroundingIssue(rowIndex=$rowIndex, kind=$kind, <redacted>)"
}

/**
 * Bounded evidence checks, not a semantic proof. Names may use known translations/plurals but
 * cannot introduce arbitrary words absent from the item quote or its bounded shared clause.
 * This does NOT prove which neighbouring item a shared modifier belongs to inside a quote.
 */
internal data class GroceryIntentGroundingResult(
    val rejected: List<String>,
    val review: List<String>,
    val unprovenIdentityScope: Set<String>,
    /** Hard failures only. Review notes and unproven local scope remain distinct. */
    val issues: List<GroceryIntentGroundingIssue>,
    /** Failed terminal-polarity scope prevents the dependent positive view from
     * being evaluated. Absence of other issue kinds is not a passing check. */
    val unevaluatedPropertyRows: Set<Int>,
)

internal fun groceryIntentGroundingConcerns(instruction: String, items: List<GroceryIntentItem>): List<String> =
    groceryIntentGrounding(instruction, items).rejected

/** Denial only: these original local operators have no affirmative source
 * receipt yet. Absence is NOT identity/polarity proof; proposed names, typed
 * exclusions and surrounding request text cannot waive the hold. */
private val uncompiledNativeSourceOperators = setOf(
    "नहीं", "नही", "नहीँ", "मत", "बिना", "अगर", "यदि", "वरना", "बजाय", "अलावा", "छोड़कर",
)

internal fun groceryIntentHasUncompiledNativeSourceScope(source: String): Boolean {
    fun isNative(codePoint: Int): Boolean = codePoint in 0x0900..0x097f ||
        codePoint in 0xa8e0..0xa8ff || codePoint in 0x11b00..0x11b5f
    var containsNative = false
    var nativeOffset = 0
    while (nativeOffset < source.length && !containsNative) {
        val codePoint = source.codePointAt(nativeOffset)
        containsNative = isNative(codePoint)
        nativeOffset += Character.charCount(codePoint)
    }
    if (!containsNative) return false
    fun hasOperator(text: String): Boolean = groceryIdentityWords(text).withIndex().any { (index, word) ->
        word in uncompiledNativeSourceOperators || word == "तो" && index > 0
    }
    // Always check original words first: removing a separator must not hide
    // an operator that was independently visible in the unmodified source.
    if (hasOperator(source)) return true
    fun shadowOnlyRemoval(codePoint: Int): Boolean {
        val type = Character.getType(codePoint)
        return type == Character.FORMAT.toInt() ||
            !isNative(codePoint) && (type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt())
    }
    // Denial-only shadow. Never bind identity, amounts or offsets from this.
    val shadow = StringBuilder(source.length)
    var removed = false
    var offset = 0
    while (offset < source.length) {
        val codePoint = source.codePointAt(offset)
        if (shadowOnlyRemoval(codePoint)) removed = true else shadow.appendCodePoint(codePoint)
        offset += Character.charCount(codePoint)
    }
    return removed && hasOperator(shadow.toString())
}

internal fun legacyNumericSourceCollisions(instruction: String, items: List<GroceryIntentItem>): List<String> {
    // Legacy rows cannot reuse quantitative source evidence without explicit group
    // ownership. Qualitative overlap keeps its existing acknowledgement/final gates.
    // Compare original UTF-16 ranges, not model quantities or repeated clause words.
    val boundSources = items.map { bindGroceryIntentProductSource(instruction, it.sourceText, maxLength = 6000) }
    val collidingRows = mutableSetOf<Int>()
    // An ambiguous quote cannot be assigned to its first occurrence or used to
    // evade overlap checks. The shared binder ignores word-interior near matches.
    if (items.size >= 2) boundSources.forEachIndexed { index, span ->
        if (span == null && groceryIntentContainsNumericAtom(items[index].sourceText)) collidingRows += index
    }
    for (left in items.indices) for (right in left + 1 until items.size) {
        val a = boundSources[left]
        val b = boundSources[right]
        val sharedNumericSource = if (a != null && b != null &&
            a.startUtf16 < b.endUtf16 && b.startUtf16 < a.endUtf16) {
            groceryIntentContainsNumericAtom(instruction.substring(maxOf(a.startUtf16, b.startUtf16),
                minOf(a.endUtf16, b.endUtf16)))
        } else items[left].sourceText == items[right].sourceText &&
            groceryIntentContainsNumericAtom(items[left].sourceText)
        if (sharedNumericSource) {
            collidingRows += left
            collidingRows += right
        }
    }
    return items.indices.filter { it in collidingRows }.map { items[it].sourceText }.distinct()
}

internal fun groceryIntentGrounding(instruction: String, items: List<GroceryIntentItem>): GroceryIntentGroundingResult {
    val rejected = mutableListOf<String>()
    val review = mutableListOf<String>()
    val unprovenIdentityScope = mutableSetOf<String>()
    val issues = mutableListOf<GroceryIntentGroundingIssue>()
    val unevaluatedPropertyRows = mutableSetOf<Int>()
    val instructionTokens = intentEvidenceTokens(instruction)
    items.forEachIndexed { rowIndex, item ->
        val positive = item.name + " " + item.required.joinToString(" ")
        val terminalProperty = groceryIntentTerminalExcludedPropertyMatch(item.sourceText)
        val propertyView = if (terminalProperty != null && items.size == 1 &&
            groceryIntentTerminalPropertyScopeProven(instruction, item.sourceText))
            groceryIntentRetainedTerminalPropertyView(item.sourceText, positive, item.excluded) else null
        if (terminalProperty != null && propertyView == null) {
            rejected += item.sourceText
            issues += GroceryIntentGroundingIssue(rowIndex, GroceryIntentGroundingIssueKind.SOURCE_SCOPE, item.sourceText)
            unevaluatedPropertyRows += rowIndex
            return@forEachIndexed
        }
        val literalSource = intentEvidenceTokens(propertyView ?: item.sourceText)
        // "Ready-made" food ordinarily requests prepared food, but an already-mixed
        // ingredient is not evidence it can be eaten without cooking.
        val equivalentForms = equivalentGroceryIntentIdentityTokens(literalSource) - literalSource
        val source = literalSource
        val namedRetail = item.legacyRetailCountReceipt()
        val sourcePieces = item.legacySourcePieceReceipt()
        val genericWrapper = item.legacyGenericContainerReceipt()
        val composite = item.legacyCompositeRetailReceipt()
        val proposed = intentEvidenceTokens(composite?.let { it.source.substring(it.identity) }
            ?: namedRetail?.literalIdentity() ?: sourcePieces?.literalIdentity()
            ?: genericWrapper?.literalIdentity()
            ?: (item.name + " " + item.required.joinToString(" ") { it.replace('_', ' ') }))
        // Shared names may occur earlier within punctuation-bounded context.
        // This bounded lexical context is not proof of modifier scope. Dietary/form evidence
        // below must come from the item's own quote, not this shared prefix.
        val start = instruction.indexOf(item.sourceText)
        val uniqueQuote = start >= 0 && instruction.indexOf(item.sourceText, start + 1) < 0
        val prefix = if (start > 0 && uniqueQuote) instruction.substring(0, start)
            .split(Regex("[,;\\n]|[.!?](?=\\s)")).last().takeLast(160) else ""
        // A colon explicitly introduces a shared list header ("Brand yogurt: mango,
        // strawberry"). Carry only that short header, never earlier item bodies, across
        // commas. Sentence/semicolon boundaries end the group. It still needs review.
        val sentencePrefix = if (start > 0 && uniqueQuote) instruction.substring(0, start)
            .split(Regex("[;\\n]|[.!?](?=\\s)")).last() else ""
        val header = sentencePrefix.takeIf { it.count { char -> char == ':' } == 1 &&
            Regex("(?<=\\p{L})\\s*:\\s").containsMatchIn(it) }
            ?.substringBefore(':')?.trim()?.takeIf { it.length in 1..100 && ',' !in it }
            ?.takeUnless { candidate -> items.any { other -> candidate.contains(other.sourceText) } }.orEmpty()
        val localScope = source + equivalentForms + intentEvidenceTokens(prefix)
        val scoped = localScope + intentEvidenceTokens(header)
        val proposedGrounded = scoped.containsAll(proposed)
        // Lexical presence in a neighbouring clause/header is not ownership.
        // V1 has no scoped supporting-quote receipt. Keep the proposed row for
        // review, but do not let a valid local amount authorize that identity.
        if (!(source + equivalentForms).containsAll(proposed)) {
            unprovenIdentityScope += item.sourceText
        }
        // Bidirectional checks: preserve stated properties AND reject model-added dietary
        // or form restrictions. Ordinary product knowledge is not user evidence.
        val protectedForms = setOf("sugarfree", "noaddedsugar", "lowfat", "tetrapack", "readytoeat", "readytodrink", "readytocook",
            "organic", "certified", "unsalted", "plain", "highfibre", "highprotein", "lowcalorie", "lowsugar",
            "glutenfree", "lactosefree", "vegan", "vegetarian",
            // A category translation cannot erase an explicitly stated variant.
            // V2 checks every retained source token independently; V1 must also
            // preserve these common identity/form qualifiers in its local quote.
            "green", "black", "white", "brown", "red", "whole", "powder", "powdered", "fresh", "dry", "dried")
        val missingProperties = (source.intersect(protectedForms) - proposed).toMutableSet()
        if ("readytoeat" in equivalentForms && proposed.none { it in setOf("readymade", "readytoeat") }) {
            missingProperties += "readymade"
        }
        val addedProperties = proposed.intersect(protectedForms) - source - equivalentForms
        // V1 has no supporting-quote ownership for exclusions either. A model
        // cannot invent a restriction or borrow it from another source row.
        val addedExclusions = item.excluded.flatMap(::groceryIntentPropertiesIn).toSet() -
            groceryIntentPropertiesIn(item.sourceText)
        val headerMissing = intentEvidenceTokens(header).intersect(protectedForms) - proposed
        val overlappingQuote = items.any { other -> other !== item && other.sourceText != item.sourceText &&
            item.sourceText.contains(other.sourceText) }
        val correction = Regex("\\b(?:sorry|instead|keep the first|rehne do|nahi\\s+nahi)\\b|\\b(?:no|nahi)\\s*(?:,|—|--|\\s-\\s)", RegexOption.IGNORE_CASE)
            .containsMatchIn(item.sourceText)
        // V1 has no bound amount atoms: at minimum, its model-selected pack
        // unit must cite an actual container in THIS item's source. A shared
        // heading or model name cannot turn bare 'one samosa' into a bag.
        // V2 independently checks the exact count/container relationship.
        val unprovenRetailPack = genericWrapper == null && item.quantityUnit == "pack" && !Regex(
            "\\b(?:tetra[\\s-]*packs?|packs?|packets?|bags?|bottles?|cartons?|cups?|cans?|tins?|tubs?|jars?|" +
                "box(?:es)?|bars?|blocks?|pouch(?:es)?|sachets?|tubes?|loaf|loaves|refills?|rolls?|sprays?|bunch(?:es)?)\\b",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(item.sourceText.replaceFirst(
            Regex("^\\s*(?:please\\s+)?can\\s+(?:you|we|i)\\b", RegexOption.IGNORE_CASE), " ",
        ))
        val reasons = linkedSetOf<GroceryIntentGroundingIssueKind>()
        if (groceryIntentHasUncompiledNativeSourceScope(item.sourceText))
            reasons += GroceryIntentGroundingIssueKind.SOURCE_SCOPE
        if (groceryIntentPositiveSourceFormTokens(item.sourceText, positive, item.excluded) == null)
            reasons += GroceryIntentGroundingIssueKind.SOURCE_FORM_UNPROVEN
        if (sourceRetailIdentityConstraint(item.sourceText)?.hasRepresentableSize() == false)
            reasons += GroceryIntentGroundingIssueKind.PACK_EVIDENCE
        if (genericWrapper != null && !genericWrapper.instructionContextProven(instruction, items.map { it.sourceText }) ||
            sourcePieces?.let { sourcePieceInstructionIssue(instruction, it) } != null)
            reasons += GroceryIntentGroundingIssueKind.SOURCE_SCOPE
        if (requiresProductFirstRetailProof(item.sourceText)) {
            if (namedRetail == null) reasons += GroceryIntentGroundingIssueKind.COUNT_ROLE
            if (!groceryIntentClosedRetailContext(instruction, items.map { it.sourceText }, item.sourceText, item.sourceText.length))
                reasons += GroceryIntentGroundingIssueKind.SOURCE_SCOPE
        }
        if (sourcePiecePurchaseReceipt(item.sourceText) != null && sourcePieces == null ||
            sourceRetailCountReceipt(item.sourceText) != null && item.legacySourceRetailCountReceipt() == null ||
            sourceHasGenericContainer(item.sourceText) && genericWrapper == null ||
            item.legacyDiscretePackRoleIssue() != null || item.legacySourcePieceIssue() != null || unprovenRetailPack)
            reasons += GroceryIntentGroundingIssueKind.COUNT_ROLE
        if (!proposedGrounded) {
            val unbound = proposed - scoped
            if (unbound.any { it in instructionTokens }) reasons += GroceryIntentGroundingIssueKind.SOURCE_SCOPE
            if (unbound.any { it !in instructionTokens }) reasons += GroceryIntentGroundingIssueKind.LEXICAL_UNVERIFIED
        }
        if (addedProperties.isNotEmpty()) reasons += GroceryIntentGroundingIssueKind.PROPERTY_ADDED
        if (addedExclusions.isNotEmpty()) reasons += GroceryIntentGroundingIssueKind.EXCLUSION_ADDED
        if (composite == null && namedRetail == null && groceryIntentPackEvidenceConflict(item))
            reasons += GroceryIntentGroundingIssueKind.PACK_EVIDENCE
        if (missingProperties.isNotEmpty() && !overlappingQuote && !correction)
            reasons += GroceryIntentGroundingIssueKind.PROPERTY_MISSING
        when {
            reasons.isNotEmpty() -> {
                rejected += item.sourceText
                issues += reasons.map { GroceryIntentGroundingIssue(rowIndex, it, item.sourceText) }
            }
            missingProperties.isNotEmpty() || headerMissing.isNotEmpty() -> {
                val labels = (missingProperties + headerMissing).sorted().joinToString(", ") { property ->
                    mapOf("sugarfree" to "sugar-free", "noaddedsugar" to "no added sugar", "lowfat" to "low fat",
                        "tetrapack" to "tetra pack", "readytoeat" to "ready to eat", "readytodrink" to "ready to drink",
                        "readytocook" to "ready to cook", "readymade" to "ready-made", "highfibre" to "high fibre",
                        "highprotein" to "high protein", "lowcalorie" to "low calorie", "lowsugar" to "low sugar",
                        "glutenfree" to "gluten-free", "lactosefree" to "lactose-free")[property] ?: property
                }
                val evidence = if (headerMissing.isNotEmpty()) "$header: ${item.sourceText}" else item.sourceText
                review += "$evidence\nYour words mention $labels, but this proposed item does not require it. Check whether you meant to remove that requirement."
            }
            !localScope.containsAll(proposed) -> review +=
                "Shared wording: $header\nFor ${item.sourceText}, Beta understood ${item.name}. Check this in your basket."
        }
    }
    return GroceryIntentGroundingResult(rejected.distinct(), review.distinct(), unprovenIdentityScope,
        Collections.unmodifiableList(issues), Collections.unmodifiableSet(unevaluatedPropertyRows))
}

/** Comparison-only vocabulary shared by grounding and source conservation.
 * A prepared ingredient is not evidence that it can be eaten without cooking. */
internal fun equivalentGroceryIntentIdentityTokens(tokens: Set<String>): Set<String> =
    if ("readymade" in tokens && tokens.none {
        it in setOf("batter", "dough", "mix", "premix", "flour", "paste")
    }) tokens - "readymade" + "readytoeat" else tokens

internal fun intentEvidenceTokens(text: String): Set<String> {
    // These are category synonyms, not inferred brands, variants or medical substitutions.
    // Match aliases before tokenization so punctuation cannot create a phrase.
    val canonical = ProductLexicon.canonicalizeAliasesPreservingText(
        ProductLexicon.normalizeNumericProductIdentities(text)).lowercase(Locale.ROOT)
        .replace(Regex("\\bhigh[\\s-]+fib(?:re|er)\\b"), "highfibre")
        .replace(Regex("\\bhigh[\\s-]+protein\\b"), "highprotein")
        .replace(Regex("\\blow[\\s-]+cal(?:orie|ories)?\\b"), "lowcalorie")
        .replace(Regex("\\blow[\\s-]+sugar\\b"), "lowsugar")
        .replace(Regex("\\bgluten[\\s-]+free\\b"), "glutenfree")
        .replace(Regex("\\blactose[\\s-]+free\\b"), "lactosefree")
        .replace(Regex("\\byoghurts?\\b"), "yogurt")
        .replace(Regex("\\byogurts\\b"), "yogurt")
        .replace(Regex("\\b(?:patti|leaves)\\b"), "leaf")
        .replace(Regex("\\bgaddi\\b"), "bunch")
        .replace(Regex("\\b(?:dahi|curds)\\b"), "curd")
        .replace(Regex("\\b(?:aaloo|aloo)\\b"), "potato")
        .replace(Regex("\\b(?:pyaaz|pyaz)\\b"), "onion")
        .replace(Regex("\\b(?:gajar|gaajar)\\b"), "carrot")
        .replace(Regex("\\b(?:kheera|khira)\\b"), "cucumber")
        .replace(Regex("\\b(?:dal|daal)\\b"), "lentil")
        .replace(Regex("\\b(?:nimbu|neembu)\\b"), "lemon")
        .replace(Regex("\\b(?:kela|kele)\\b"), "banana")
        .replace(Regex("\\b(?:tamatar|tamaatar)\\b"), "tomato")
        .replace(Regex("\\bnariyal\\s+(?:pani|paani)\\b"), "coconut water")
        .replace(Regex("\\b(?:kothmir|kothimbir)\\b"), "coriander leaf")
        .replace(Regex("\\b(?:dhaniya|dhania)\\b"), "coriander")
        .replace(Regex("\\b(?:jeera|jira|cumin seeds?)\\b"), "cumin")
        .replace(Regex("\\b(?:murmura|mamra|muri)\\b"), "puffed rice")
        .replace(Regex("\\blaal\\b"), "red")
        .replace(Regex("\\b(?:kachcha|kachche|kaccha|kacche)\\b"), "raw")
        .replace(Regex("\\baam\\b"), "mango")
        .replace(Regex("\\bsabun\\b"), "soap")
        .replace(Regex("\\b(?:fox\\s*nuts?|lotus\\s+seeds?)\\b"), "makhana")
        // Literal request evidence: no catalogue assumptions, no stripping nutrition amounts.
        .replace(Regex("\\b(?:no|without)[\\s-]+added[\\s-]+sugar\\b"), "noaddedsugar")
        .replace(Regex("\\b(?:sugar[\\s-]*free|zero[\\s-]+sugar)\\b"), "sugarfree")
        .replace(Regex("\\blow[\\s-]*fat\\b"), "lowfat")
        .replace(Regex("\\b(?:tetra|tetta|tetera)[\\s-]*(?:packs?|paks?)\\b"), "tetrapack")
        .replace(Regex("\\bready[\\s-]*made\\b"), "readymade")
        .replace(Regex("\\bready[\\s-]*to[\\s-]*eat\\b"), "readytoeat")
        .replace(Regex("\\bready[\\s-]*to[\\s-]*drink\\b"), "readytodrink")
        .replace(Regex("\\bready[\\s-]*to[\\s-]*cook\\b"), "readytocook")
        .replace(Regex("(\\d)([a-z])"), "$1 $2")
        .replace(Regex("\\b(?:gm|gms|grams?)\\b"), "g")
        .replace(Regex("\\b(?:litres?|liters?|ltr)\\b"), "l")
    val plurals = mapOf("apples" to "apple", "carrots" to "carrot", "cucumbers" to "cucumber",
        "lemons" to "lemon", "oranges" to "orange", "lentils" to "lentil", "biscuits" to "biscuit",
        "cups" to "cup", "bottles" to "bottle", "packets" to "packet", "drops" to "drop",
        "packs" to "pack", "bags" to "bag", "cartons" to "carton", "cans" to "can", "tins" to "tin",
        "tubs" to "tub", "jars" to "jar", "boxes" to "box", "blocks" to "block", "loaves" to "loaf",
        "bunches" to "bunch", "rolls" to "roll", "refills" to "refill", "pouches" to "pouch",
        "sachets" to "sachet", "tubes" to "tube", "sprays" to "spray", "matchboxes" to "matchbox",
        "chocolates" to "chocolate", "bars" to "bar", "berries" to "berry", "batteries" to "battery",
        "lozenges" to "lozenge", "tablets" to "tablet", "capsules" to "capsule",
        "eggs" to "egg", "bananas" to "banana", "tomatoes" to "tomato", "onions" to "onion",
        "cockroaches" to "cockroach", "mosquitoes" to "mosquito", "flies" to "fly", "ants" to "ant",
        "insects" to "insect", "spiders" to "spider", "termites" to "termite",
        "potatoes" to "potato", "samosas" to "samosa", "mangoes" to "mango",
        "pomegranates" to "pomegranate", "chillies" to "chilli")
    return groceryIdentityWords(canonical)
        .filter { it !in setOf("and", "the", "of") }.map { plurals[it] ?: it }.toSet()
}
