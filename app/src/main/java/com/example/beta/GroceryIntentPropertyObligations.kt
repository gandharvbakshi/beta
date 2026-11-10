package com.example.beta

import com.example.beta.automation.ProductLexicon
import java.util.Locale

/** Original text with only independently bound amounts removed; proposed identity still needs normal grounding. */
internal data class GroceryIntentPropertyRow(
    val rowIndex: Int,
    val primary: GroceryIntentReviewV2Quote,
    val sourceIdentity: String,
    val name: String,
    val required: List<String> = emptyList(),
    val excluded: List<String> = emptyList(),
) {
    override fun toString(): String = "GroceryIntentPropertyRow(rowIndex=$rowIndex, primary=<${primary.startUtf16}..${primary.endUtf16}>, sourceIdentity=<redacted>, name=<redacted>)"
}

internal data class GroceryIntentPropertyObligation(
    val source: GroceryIntentReviewV2Quote,
    val selector: String,
    val memberIndices: List<Int>,
    val required: List<String>,
    val excluded: List<String>,
    val ingredientPredicates: List<IngredientPredicate> = emptyList(),
) {
    override fun toString(): String = "GroceryIntentPropertyObligation(source=<redacted>, selector=<redacted>, memberIndices=$memberIndices, required=$required, excluded=$excluded)"
}

internal data class GroceryIntentPropertyCompilation(
    val obligations: List<GroceryIntentPropertyObligation> = emptyList(),
    val issueCodes: List<String> = emptyList(),
)

private data class PropertySelector(
    val category: String?,
    val target: List<String>,
    val universal: Boolean,
    val originalTarget: List<String> = emptyList(),
)

private data class PropertyCandidate(
    val source: GroceryIntentReviewV2Quote,
    val selector: PropertySelector,
    val required: List<String>,
    val excluded: List<String>,
    val ingredientPredicates: List<IngredientPredicate> = emptyList(),
)

private data class PropertyIdentity(val original: List<String>, val canonical: List<String>)

private val groceryIntentPropertyNames = linkedMapOf(
    "low fat" to "lowfat", "no added sugar" to "noaddedsugar", "sugar free" to "sugarfree",
    "unsweetened" to "unsweetened", "sweetened" to "sweetened", "plain" to "plain",
    "unsalted" to "unsalted", "organic" to "organic", "gluten free" to "glutenfree",
    "lactose free" to "lactosefree", "vegan" to "vegan", "vegetarian" to "vegetarian",
    "ready to eat" to "readytoeat", "ready to drink" to "readytodrink",
    "ready to cook" to "readytocook", "whole" to "whole", "ground" to "ground",
    "powder" to "powder", "full cream" to "fullcream", "full fat" to "fullfat",
    "fresh" to "fresh", "frozen" to "frozen", "dried" to "dried",
)
private val groceryIntentPropertyPatternText = groceryIntentPropertyNames.keys
    .sortedByDescending { it.length }
    .joinToString("|", prefix = "(?:", postfix = ")") { phrase ->
        phrase.split(' ').joinToString("[ \\t_-]+") { Regex.escape(it) }
    }
private val groceryIntentPropertyRegex = Regex(
    "(?i)(?<![\\w-])$groceryIntentPropertyPatternText(?![\\w-])",
)

// Reuse the existing closed property vocabulary. This does not infer an
// opposite ("not frozen" is not "fresh") or authorize a cropped item's scope.
private val terminalExcludedProperty = Regex(
    ",[ \\t]*not[ \\t]+($groceryIntentPropertyPatternText)(?=[ \\t]*[.!?]?[ \\t]*\\z)",
    RegexOption.IGNORE_CASE,
)
internal fun groceryIntentTerminalExcludedPropertyMatch(source: String): MatchResult? =
    // Recognition for denial must be broader than ASCII-only acceptance.
    intentV2ExcludedOneMatch(source) ?: terminalExcludedAttribute.find(source) ?: terminalExcludedProperty.find(source)

/** Positive-identity view only. Original source, offsets and all numeric atoms
 * remain unchanged. Exact signed retention is rechecked at the cart boundary. */
internal fun groceryIntentRetainedTerminalPropertyView(source: String, positive: String, excluded: List<String>): String? {
    val match = groceryIntentTerminalExcludedPropertyMatch(source) ?: return null
    // Preserve word boundaries exactly as catalogue exclusion enforcement
    // does. Deleting separators could turn an invalid "fro zen" into frozen.
    fun key(value: String) = canonicalPropertyKey(value).trim()
    val property = key(match.groupValues[1])
    val positiveTokens = intentEvidenceTokens(positive)
    val propertyTokens = intentEvidenceTokens(match.groupValues[1])
    if (excluded.map(::key).toSet() != setOf(property) ||
        positiveTokens.containsAll(propertyTokens)) return null
    return source.replaceRange(match.range, " ".repeat(match.value.length))
}

internal fun groceryIntentTerminalPropertyScopeProven(instruction: String, source: String): Boolean {
    val start = instruction.indexOf(source)
    return start >= 0 && groceryIntentTerminalPropertyScopeProven(instruction,
        GroceryIntentReviewV2Quote(source, start, start + source.length))
}
private val groceryIntentPropertyCanonicalValues = groceryIntentPropertyNames.values.toSet()
internal fun groceryIntentPropertyPhrase(key: String): String =
    groceryIntentPropertyNames.entries.single { it.value == key }.key
private val groceryIntentPropertyCanonicalLookup = groceryIntentPropertyNames.mapKeys { canonicalPropertyKey(it.key) }
private val groceryIntentPropertyWordRegex = Regex("(?i)[a-z]+")
private val groceryIntentPropertyClauseRegex = Regex(
    "(?i)(?:the[ \\t]+)?(?<selector>[A-Za-z][A-Za-z '\\t-]{0,100}?)[ \\t]+" +
        "(?:(?:should|must)[ \\t]+(?<beforeNegative>not[ \\t]+)?(?:be|have)|needs?|need)[ \\t]+" +
        "(?<negative>not[ \\t]+)?(?<property>$groceryIntentPropertyPatternText)" +
        "(?:[ \\t]*,[ \\t]*not[ \\t]+(?<contrast>$groceryIntentPropertyPatternText))?",
)
private val groceryIntentPropertyHindiClauseRegex = Regex(
    "(?i)(?<selector>[A-Za-z][A-Za-z '\\t-]{0,100}?)[ \\t]+" +
        "(?<property>$groceryIntentPropertyPatternText)[ \\t]+(?:hi[ \\t]+)?(?:ho|hona(?:[ \\t]+chahiye)?|chahiye)" +
        "(?:[ \\t]*,[ \\t]*(?<contrast>$groceryIntentPropertyPatternText)[ \\t]+(?:nahi|nahin))?",
)
private val groceryIntentPropertyUnsafeSelectorWords = setOf(
    "if", "except", "unless", "only", "instead", "or", "ya", "and", "aur", "not", "no", "without",
    "for", "with", "containing", "mom", "mine", "his", "her", "their",
    "don", "doesn", "never", "nahi", "nahin", "mat", "bina", "bagair",
)
private val groceryIntentPropertyAmountWords = setOf(
    "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
    "half", "quarter", "ek", "do", "teen", "char", "chaar", "paanch", "panch", "chhe", "saat", "aath", "nau",
    "das", "aadha", "adha", "kg", "g", "gm", "gms", "gram", "grams", "kilogram", "kilograms", "ml", "l",
    "litre", "litres", "liter", "liters", "kilo", "keji",
)
private val groceryIntentPropertyContainers = setOf(
    "pack", "packet", "bag", "bottle", "carton", "cup", "can", "tin", "tub", "jar", "box", "pouch", "sachet",
)
private val groceryIntentPropertyPlantTerms = setOf(
    "almond", "almonds", "oat", "oats", "soy", "soya", "coconut", "pea", "cashew", "peanut", "peanuts",
    "rice", "walnut", "hazelnut", "hemp", "cocoa", "cacao", "shea",
)
private val groceryIntentPropertyAmbiguousNouns = setOf("pepper", "coriander", "dhaniya", "turmeric", "haldi", "peas", "cloves")
private val groceryIntentPropertyCompoundHeads = setOf(
    listOf("peanut", "butter"), listOf("cocoa", "butter"), listOf("cacao", "butter"),
    listOf("shea", "butter"), listOf("dairy", "milk"),
)
private val groceryIntentPropertyCategories = mapOf(
    "dairy" to setOf("milk", "butter", "dahi", "curd", "yogurt", "yoghurt", "paneer", "cheese"),
    "pulses" to setOf("dal", "daal", "lentil", "lentils", "pulse", "pulses"),
    "flour" to setOf("flour", "atta", "besan", "maida"),
    "fruits" to setOf("apple", "apples", "banana", "bananas", "orange", "oranges", "pear", "pears", "grape", "grapes", "papaya", "watermelon"),
    "vegetables" to setOf("potato", "potatoes", "tomato", "tomatoes", "onion", "onions", "carrot", "carrots", "peas", "spinach", "broccoli"),
    "spices" to setOf("cumin", "jeera", "turmeric", "haldi", "coriander", "dhaniya", "pepper", "cardamom", "elaichi", "cloves", "cinnamon"),
    "grains" to setOf("rice", "oats", "quinoa"),
    "other" to setOf("bread", "biscuit", "biscuits", "cracker", "crackers", "chocolate", "chocolates", "coffee", "tea", "noodles", "pasta", "oil"),
)

/** Closed whole-source head kind; never search for a commodity inside a kit or label. */
internal fun isBareGroceryIntentCommodityHead(text: String): Boolean =
    Regex("[A-Za-z]+").matches(text) && groceryIntentPropertyCategories.values.any {
        text.lowercase(Locale.ROOT) in it
    }
internal fun isMeasuredGrainSubject(text: String): Boolean {
    val noun = text.lowercase(Locale.ROOT).removeSuffix(" seeds").removeSuffix(" grains")
    return noun in groceryIntentPropertyCategories.getValue("grains")
}
private val groceryIntentPropertyOpposites = mapOf(
    "lowfat" to setOf("fullcream", "fullfat"),
    "fullcream" to setOf("lowfat"),
    "fullfat" to setOf("lowfat"),
    "sweetened" to setOf("unsweetened"),
    "unsweetened" to setOf("sweetened"),
    "whole" to setOf("ground", "powder"),
    "ground" to setOf("whole"),
    "powder" to setOf("whole"),
    "fresh" to setOf("frozen", "dried"),
    "frozen" to setOf("fresh"), "dried" to setOf("fresh"),
)

/** Validate only: produces scoped source obligations, never product targets or readiness. */
internal fun hasGroceryIntentPropertyObligationClause(
    instruction: String,
    allowIngredientPredicates: Boolean = false,
    allowTypedIngredientPredicates: Boolean = false,
): Boolean =
    propertySegments(instruction, itemEnd = false).any {
        groceryIntentPropertyClauseRegex.matches(it.text) || groceryIntentPropertyHindiClauseRegex.matches(it.text) ||
            (allowIngredientPredicates && ingredientPredicateClause(it.text, allowTypedIngredientPredicates) != null)
    }

/** Derive target membership from original spans, not proposed names. */
internal fun compileGroceryIntentPropertyObligations(
    instruction: String,
    rows: List<GroceryIntentPropertyRow>,
    coveredContext: List<GroceryIntentReviewV2Quote> = emptyList(),
    allowIngredientPredicates: Boolean = false,
    allowTypedIngredientPredicates: Boolean = false,
): GroceryIntentPropertyCompilation {
    fun reject(code: String) = GroceryIntentPropertyCompilation(issueCodes = listOf(code))
    if (allowTypedIngredientPredicates && !allowIngredientPredicates) return reject("ingredient_predicate_unenforced")
    if (rows.isEmpty() || rows.size > 50 || instruction.codePointCount(0, instruction.length) > 6000) return reject("property_invalid_rows")
    val primaries = rows.map { it.primary }
    if (rows.map { it.rowIndex }.toSet().size != rows.size || rows.any { it.rowIndex < 0 } ||
        (primaries + coveredContext).any { !validPropertyQuote(instruction, it) } ||
        primaries.indices.any { left -> (left + 1 until primaries.size).any { right -> primaries[left].overlaps(primaries[right]) } }
    ) return reject("property_invalid_evidence")

    val itemSegments = propertySegments(instruction, itemEnd = true)
    // Closed list separator between two source items, never a product-bearing
    // prefix or a conjunction borrowed across an omitted source member.
    val connectors = primaries.sortedBy { it.startUtf16 }.zipWithNext().mapNotNull { (left, right) ->
        val gap = instruction.substring(left.endUtf16, right.startUtf16)
        if (!Regex("[ \\t]*,[ \\t]*(?:and|aur)[ \\t]+", RegexOption.IGNORE_CASE).matches(gap)) null
        else GroceryIntentCoverageSpan(left.endUtf16, right.startUtf16)
    }
    // Preserve one original item segment; only an independently proven request
    // prefix may precede it. No suffix crop or joining across product gaps.
    // coveredContext may also carry notes/corrections, so derive neutral wording
    // independently instead of letting those receipts crop the primary.
    val neutralPrefixes = if (primaries.any { it !in itemSegments }) {
        neutralGroceryIntentContextSpans(instruction, primaries.map {
            GroceryIntentCoverageSpan(it.startUtf16, it.endUtf16)
        })
    } else emptyList()
    val intactPrefixes = neutralPrefixes + connectors
    if (primaries.any { primary -> itemSegments.none { segment ->
            segment.startUtf16 <= primary.startUtf16 && primary.endUtf16 == segment.endUtf16 &&
                (segment.startUtf16 until primary.startUtf16).all { index ->
                    instruction[index].isWhitespace() || intactPrefixes.any { context ->
                        context.startUtf16 <= index && index < context.endUtf16 && context.endUtf16 <= primary.startUtf16
                    }
                }
        } }) return reject("property_cropped_primary")
    if (rows.any { !propertyIdentityRetainsSource(it) }) return reject("property_source_identity_unproven")

    val candidates = mutableListOf<PropertyCandidate>()
    for (source in propertySegments(instruction, itemEnd = false)) {
        val predicateClause = if (allowIngredientPredicates) {
            ingredientPredicateClause(source.text, allowTypedIngredientPredicates)
        } else null
        if (predicateClause != null) {
            if ((primaries + coveredContext).any(source::overlaps)) return reject("property_overlapping_context")
            predicateClause.issueCode?.let { return reject(it) }
            val selector = parsePropertySelector(predicateClause.selector) ?: return reject("property_selector_unproven")
            if (predicateClause.predicates.any { it.legacyExclusion() == null } && !allowTypedIngredientPredicates) {
                return reject("ingredient_predicate_unenforced")
            }
            candidates += PropertyCandidate(source, selector, emptyList(), emptyList(), predicateClause.predicates)
            continue
        }
        val englishMatch = groceryIntentPropertyClauseRegex.matchEntire(source.text)
        val hindiMatch = if (englishMatch == null) groceryIntentPropertyHindiClauseRegex.matchEntire(source.text) else null
        val match = englishMatch ?: hindiMatch ?: continue
        if ((primaries + coveredContext).any(source::overlaps)) return reject("property_overlapping_context")
        val selectorText = match.groups["selector"]?.value ?: return reject("property_selector_unproven")
        val selector = parsePropertySelector(selectorText) ?: return reject("property_selector_unproven")
        val property = canonicalProperty(match.groups["property"]?.value ?: return reject("property_selector_unproven"))
        val beforeNegative = englishMatch?.groups?.get("beforeNegative") != null
        val afterNegative = englishMatch?.groups?.get("negative") != null
        if (beforeNegative && afterNegative) return reject("property_conflicting_polarity")
        val negative = beforeNegative || afterNegative
        val contrast = match.groups["contrast"]?.value?.let(::canonicalProperty)
        val required = if (negative) emptyList() else listOf(property)
        val excluded = (if (negative) listOf(property) else emptyList()) + listOfNotNull(contrast)
        if (required.toSet().intersect(excluded.toSet()).isNotEmpty() || excluded.toSet().size != excluded.size) {
            return reject("property_conflicting_polarity")
        }
        candidates += PropertyCandidate(source, selector, required, excluded)
    }
    if (candidates.isEmpty()) return GroceryIntentPropertyCompilation()
    if (rows.any { row -> (listOf(row.sourceIdentity, row.name) + row.required + row.excluded).any(::propertyHasUnprovedNegation) }) {
        return reject("property_source_identity_unproven")
    }

    val remaining = instruction.toCharArray()
    (primaries + coveredContext + candidates.map { it.source }).forEach { quote ->
        for (index in quote.startUtf16 until quote.endUtf16) remaining[index] = ' '
    }
    connectors.forEach { span ->
        for (index in span.startUtf16 until span.endUtf16) remaining[index] = ' '
    }
    if (String(remaining).codePoints().anyMatch { point ->
            point == '_'.code || Character.isLetter(point) || Character.getType(point) in setOf(
                Character.DECIMAL_DIGIT_NUMBER.toInt(), Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt(),
            )
        }) return reject("property_incomplete_source_coverage")

    // Request-local only: normalize once per source, not per source/obligation.
    // No process cache retains the user's product text after this call.
    val identities = rows.associate { row -> row.rowIndex to PropertyIdentity(
        productWords(row.sourceIdentity),
        productWords(ProductLexicon.canonicalizeIdentityNounsPreservingText(row.sourceIdentity.lowercase(Locale.ROOT))),
    ) }
    val obligations = mutableListOf<GroceryIntentPropertyObligation>()
    for (candidate in candidates) {
        val membership = rows.associate { it.rowIndex to propertyMembership(candidate.selector, identities.getValue(it.rowIndex)) }
        if (membership.values.any { it == null }) return reject("property_scope_unknown")
        val members = membership.filterValues { it == true }.keys.sorted()
        if (members.isEmpty() || (!candidate.selector.universal && members.size != 1)) {
            return reject("property_target_unproven")
        }
        obligations += GroceryIntentPropertyObligation(
            source = candidate.source,
            selector = candidate.selector.category ?: candidate.selector.target.joinToString(" "),
            memberIndices = members,
            required = candidate.required,
            excluded = candidate.excluded,
            ingredientPredicates = candidate.ingredientPredicates,
        )
    }

    val allRequired = obligations.flatMap { it.required }.toSet()
    val allExcluded = obligations.flatMap { it.excluded }.toSet()
    val allPredicates = obligations.flatMap { it.ingredientPredicates }.toSet()
    for (row in rows) {
        val scoped = obligations.filter { row.rowIndex in it.memberIndices }
        val required = scoped.flatMap { it.required }.toSet()
        val excluded = scoped.flatMap { it.excluded }.toSet()
        val positive = propertiesIn(row.name) + row.required.flatMap(::propertiesIn)
        val negative = row.excluded.flatMap(::propertiesIn).toSet()
        val predicates = scoped.flatMap { it.ingredientPredicates }
        try {
            requireIngredientPredicateProjection(predicates, row.excluded, allowTypedIngredientPredicates)
        } catch (error: IllegalArgumentException) {
            return reject(error.message ?: "ingredient_predicate_unproven")
        }
        if ((allPredicates - predicates.toSet()).intersect(
                ingredientExclusionPredicates(row.excluded, allowTypedIngredientPredicates),
            ).isNotEmpty()) {
            return reject("ingredient_predicate_scope_mismatch")
        }
        if (allPredicates.isNotEmpty()) {
            // Scoped evidence cannot shelter an additional model restriction.
            // An empty legacy property set is not proof of field ownership.
            val projected = predicates.map {
                if (allowTypedIngredientPredicates) it.literalExclusion() else it.legacyExclusion()
            }.toSet()
            for (tag in row.excluded) {
                val phrase = tag.lowercase(Locale.ROOT).replace(Regex("[ \\t_-]+"), " ").trim()
                val localPredicate = IngredientPredicate.fromProtectedExclusion(phrase)
                val taggedProperties = propertiesIn(tag)
                if (phrase !in projected &&
                    !(taggedProperties.isNotEmpty() && excluded.containsAll(taggedProperties)) &&
                    !(localPredicate != null && swiggyHasIngredientPredicate(row.sourceIdentity, localPredicate))
                ) return reject("ingredient_predicate_extra_exclusion")
            }
        }
        val local = propertiesIn(row.sourceIdentity)
        // No producer-invented form facts: only this original primary and
        // independently scoped obligations can own the new form vocabulary.
        val forms = setOf("fresh", "frozen", "dried")
        if ((positive - local - required).intersect(forms).isNotEmpty() ||
            (negative - excluded).intersect(forms).isNotEmpty()
        ) return reject("property_unproven_form")
        if (required.intersect(excluded).isNotEmpty() || !positive.containsAll(required) || !negative.containsAll(excluded) ||
            required.any { it in negative } || excluded.any { it in positive }
        ) return reject("property_scope_mismatch")
        if (excluded.any { it in local } ||
            required.any { requiredProperty -> groceryIntentPropertyOpposites[requiredProperty].orEmpty().any { it in local } }
        ) return reject("property_source_conflict")
        val identityWords = identities.getValue(row.rowIndex).canonical
        if (identityWords.lastOrNull() == "milk" && identityWords.none { it in groceryIntentPropertyPlantTerms } &&
            (("whole" in required && "lowfat" in local) || ("lowfat" in required && "whole" in local))
        ) return reject("property_source_conflict")
        if ("plain" in required && "plain" !in local &&
            productWords(groceryIntentPropertyRegex.replace(row.sourceIdentity, " ")).size > 1
        ) return reject("property_scope_unknown")
        // Compare unions across the complete obligation set so independent
        // same-property clauses can coexist without becoming cross-row leakage.
        if (allRequired.subtract(required).any { it in positive - local } ||
            allExcluded.subtract(excluded).any { it in negative }
        ) return reject("property_scope_mismatch")
    }
    return GroceryIntentPropertyCompilation(obligations)
}

private fun canonicalPropertyKey(text: String): String = text.lowercase(Locale.ROOT).replace(Regex("[ \\t_-]+"), " ")

private fun canonicalProperty(text: String): String = groceryIntentPropertyCanonicalLookup[canonicalPropertyKey(text)]
    ?: error("unsupported_property")

private fun propertiesIn(text: String): Set<String> {
    if (text in groceryIntentPropertyCanonicalValues) return setOf(text)
    return groceryIntentPropertyRegex.findAll(text).map { canonicalProperty(it.value) }.toSet()
}

/** Closed property vocabulary shared by independent equality-only receipts. */
internal fun groceryIntentPropertiesIn(text: String): Set<String> = propertiesIn(text)

internal fun groceryIntentWholeProperty(text: String): String? =
    groceryIntentPropertyRegex.matchEntire(text)?.let { canonicalProperty(it.value) }

internal fun propertyHasUnprovedNegation(text: String): Boolean {
    val normalized = Regex("[\\t_-]+").replace(text, " ")
    val positive = Regex("(?i)\\bno[ ]+added[ ]+sugar\\b").replace(normalized, " ")
    return Regex("(?i)\\b(?:not|no|non|nonorganic|nonvegetarian|nonveg|uncertified|without|bina|bagair|nahi|nahin|mat|never|don't|doesn't)\\b").containsMatchIn(positive)
}

private fun propertyWords(text: String): List<String> = groceryIntentPropertyWordRegex.findAll(text.lowercase(Locale.ROOT))
    .map { it.value }.toList()

private fun propertyIdentityRetainsSource(row: GroceryIntentPropertyRow): Boolean {
    if ((row.primary.text + row.sourceIdentity).codePoints().anyMatch { point ->
            Character.isLetter(point) && point !in 'A'.code..'Z'.code && point !in 'a'.code..'z'.code
        }) return false
    fun meaningfulWords(text: String) = propertyWords(text).filterNot { it in groceryIntentPropertyAmountWords }
    return meaningfulWords(row.primary.text) == meaningfulWords(row.sourceIdentity)
}

private fun productWords(identity: String): List<String> {
    val words = propertyWords(identity).toMutableList()
    if (words.lastOrNull()?.removeSuffix("s")?.let { it in groceryIntentPropertyContainers } == true) words.removeAt(words.lastIndex)
    if (words.takeLast(2) == listOf("cheese", "slices")) words.removeAt(words.lastIndex)
    return words
}

private fun parsePropertySelector(text: String): PropertySelector? {
    val words = propertyWords(text).toMutableList()
    val universal = words.firstOrNull() == "all" || words.firstOrNull() == "every"
    if (universal) words.removeAt(0)
    if (words.firstOrNull() == "the") words.removeAt(0)
    if (words.isEmpty() || words.any { it in groceryIntentPropertyUnsafeSelectorWords }) return null
    val category = words.firstOrNull()?.takeIf { it in groceryIntentPropertyCategories && it != "other" }
    if (category != null && words.drop(1) in listOf(emptyList(), listOf("items"), listOf("products"))) {
        return PropertySelector(category, emptyList(), true)
    }
    // Source-language equality only, before tokenization: punctuation must not
    // manufacture a phrase alias. Category scope and search recall stay separate.
    val namedWords = propertyWords(ProductLexicon.canonicalizeIdentityNounsPreservingText(text.lowercase(Locale.ROOT)))
        .toMutableList()
    if (universal) namedWords.removeAt(0)
    if (namedWords.firstOrNull() == "the") namedWords.removeAt(0)
    if (namedWords.isEmpty() || namedWords.any { it in groceryIntentPropertyUnsafeSelectorWords }) return null
    return PropertySelector(null, namedWords, universal, words)
}

/** null means membership is unknown; unknown must never be treated as outside the scope. */
private fun propertyMembership(selector: PropertySelector, identity: PropertyIdentity): Boolean? {
    // Expand the entire original identity, retaining compound qualifiers such
    // as elaichi banana. Do not rewrite the source, proposed name or any amount.
    val words = if (selector.category == null) identity.canonical else identity.original
    if (words.isEmpty()) return null
    if (selector.category == null) {
        if (selector.target.isEmpty()) return false
        if (selector.target.size < 2 && words.takeLast(2) in groceryIntentPropertyCompoundHeads &&
            selector.target.last() == words.last()) return null
        if (words.takeLast(selector.target.size) == selector.target) return true
        // An embedded reference may be an ingredient/variety; an exact match
        // elsewhere does not prove this compound is outside the user's scope.
        if ((0 until words.size - selector.target.size).any { index ->
                words.subList(index, index + selector.target.size) == selector.target
            }) return null
        val originalWords = identity.original
        if (selector.originalTarget.isNotEmpty() &&
            (0..originalWords.size - selector.originalTarget.size).any { index ->
                originalWords.subList(index, index + selector.originalTarget.size) == selector.originalTarget
            }) return null
        // Missing descriptor on a known shared product head is unknown, not
        // proven nonmembership (e.g. mango yogurt vs every flavoured yogurt).
        if (selector.universal && selector.target.size > 1 && words.last() == selector.target.last()) return null
        return false
    }
    if ("dairy" in words || words.any { it in setOf("non", "without", "not", "no") }) return null
    if (words.last() in groceryIntentPropertyAmbiguousNouns) return null
    val plant = words.any { it in groceryIntentPropertyPlantTerms }
    val last = words.last()
    val dairyNouns = groceryIntentPropertyCategories.getValue("dairy")
    if (last in dairyNouns) {
        if (plant) return if (selector.category == "dairy" && last == "milk") false else null
        return selector.category == "dairy"
    }
    val matching = groceryIntentPropertyCategories.filterValues { last in it }.keys
    if (matching.size != 1) return null
    return selector.category in matching
}

private fun validPropertyQuote(instruction: String, quote: GroceryIntentReviewV2Quote): Boolean {
    val start = quote.startUtf16
    val end = quote.endUtf16
    if (start < 0 || start >= end || end > instruction.length) return false
    if (splitsPropertySurrogate(instruction, start) || splitsPropertySurrogate(instruction, end)) return false
    if (instruction.substring(start, end) != quote.text) return false
    return quote.text.firstOrNull()?.isWhitespace() != true && quote.text.lastOrNull()?.isWhitespace() != true
}

private fun splitsPropertySurrogate(text: String, index: Int): Boolean =
    index in 1 until text.length && text[index - 1].isHighSurrogate() && text[index].isLowSurrogate()

private fun propertySegments(instruction: String, itemEnd: Boolean): List<GroceryIntentReviewV2Quote> {
    val result = mutableListOf<GroceryIntentReviewV2Quote>()
    var start = 0
    fun add(end: Int) {
        var trimmedStart = start
        var trimmedEnd = end
        while (trimmedStart < trimmedEnd && instruction[trimmedStart].isWhitespace()) trimmedStart++
        while (trimmedEnd > trimmedStart && instruction[trimmedEnd - 1].isWhitespace()) trimmedEnd--
        if (trimmedStart < trimmedEnd) {
            result += GroceryIntentReviewV2Quote(instruction.substring(trimmedStart, trimmedEnd), trimmedStart, trimmedEnd)
        }
    }
    for (index in instruction.indices) {
        val char = instruction[index]
        val punctuation = if (itemEnd) {
            char in ",;:!?\r\n"
        } else {
            char in ";!?\r\n"
        }
        val delimiter = punctuation || (char == '.' && (index + 1 == instruction.length || instruction[index + 1].isWhitespace()))
        if (delimiter) {
            add(index)
            start = index + 1
        }
    }
    add(instruction.length)
    return result
}
