package com.example.beta

// Shared source and catalogue vocabulary: a word match never proves ingredient absence.
internal val literalProcessingLabel = Regex("""(?i)(?<![\p{L}\p{M}\p{N}_])(?:unsalted|unsweetened|unflavou?red)(?![\p{L}\p{M}\p{N}_])""")
internal val qualifiedProcessingLabel = Regex("""(?i)[*†‡?]|\b(?:if|unless|or|ya|either|instead|except|avoid|like|style|flavou?red|contains?|containing|made|with|for|brand|maybe|may|might|preferred|optional)\b""")

internal enum class IngredientPredicateOperator { ABSENT, NO_ADDITION }

/** A literal restriction operand, not a catalogue or medical-safety guarantee. */
internal data class IngredientPredicate(
    val operator: IngredientPredicateOperator,
    val operand: String,
) {
    init {
        require(operand.length <= 60 && Regex("[a-z]+(?: [a-z]+){0,3}").matches(operand) &&
            operand.split(' ').none { it in unsafeOperandWords }) { "Invalid ingredient operand" }
    }

    override fun toString(): String = "IngredientPredicate(operator=$operator, operand=<redacted>)"

    /** Null is not permission for a generic fallback: ingestion must hold until
     * a negotiated end-to-end contract can carry this predicate unchanged. */
    fun legacyExclusion(): String? {
        val rendered = literalExclusion()
        return rendered.takeIf { fromProtectedExclusion(it) == this }
    }

    fun literalExclusion(): String = when (operator) {
        IngredientPredicateOperator.ABSENT -> operand
        IngredientPredicateOperator.NO_ADDITION -> "added $operand"
    }

    fun toPayload(): Map<String, String> = mapOf(
        "operator" to when (operator) {
            IngredientPredicateOperator.ABSENT -> "absent"
            IngredientPredicateOperator.NO_ADDITION -> "no_addition"
        }, "operand" to operand,
    )

    companion object {
        private val unsafeOperandWords = setOf(
            "not", "no", "without", "added", "with", "and", "or", "free", "if", "unless", "except",
            "may", "might", "low", "less", "reduced", "for", "in", "on", "to", "from",
        )
        internal val protectedExclusions = setOf(
            "maida", "palm oil", "salt", "added salt", "added sugar", "chemicals", "preservatives",
            "artificial colours", "artificial flavours",
        )

        fun fromPayload(payload: Map<String, Any?>): IngredientPredicate {
            require(payload.keys == setOf("operator", "operand")) {
                "Invalid ingredient predicate payload"
            }
            val operator = payload["operator"] as? String
                ?: throw IllegalArgumentException("Invalid ingredient predicate payload")
            val operand = payload["operand"] as? String
                ?: throw IllegalArgumentException("Invalid ingredient predicate payload")
            val parsedOperator = when (operator) {
                "absent" -> IngredientPredicateOperator.ABSENT
                "no_addition" -> IngredientPredicateOperator.NO_ADDITION
                else -> throw IllegalArgumentException("Invalid ingredient predicate payload")
            }
            return IngredientPredicate(parsedOperator, operand)
        }

        /** Input is already normalized by the caller; never broaden the closed projection. */
        fun fromProtectedExclusion(phrase: String): IngredientPredicate? {
            if (phrase !in protectedExclusions) return null
            return if (phrase.startsWith("added ")) {
                IngredientPredicate(IngredientPredicateOperator.NO_ADDITION, phrase.removePrefix("added "))
            } else {
                IngredientPredicate(IngredientPredicateOperator.ABSENT, phrase)
            }
        }
    }
}

internal fun swiggyHasIngredientPredicate(label: String, predicate: IngredientPredicate): Boolean =
    // Missing annotation text cannot establish an unconditional literal claim.
    label.none { it in "*\u2020\u2021" } && swiggyHasExplicitIngredientAbsence(label, predicate.literalExclusion())

/** A whole independently-enforced edition qualifier, never an ingredient mention inside a product title. */
internal fun IngredientPredicate.exactSellerQualifier(value: String): Boolean =
    swiggyIngredientAbsencePatterns(literalExclusion()).any { it.matches(value.lowercase(java.util.Locale.ROOT)) } &&
        swiggyHasIngredientPredicate(value, this)

/** Conservative deny-only alarm for familiar literal ingredient claims.
 * This does not establish source meaning or prove a product's ingredients:
 * brand names (for example, "Sugar Free") and scoped wording can false-alarm,
 * while unsupported languages or phrasing can be missed. A hit only holds for
 * explicit typed-claim review; no hit is never permission or admission proof. */
internal fun swiggyIngredientClaimAlarms(label: String, includeProcessing: Boolean = true): Set<IngredientPredicate> {
    val operands = IngredientPredicate.protectedExclusions.map { it.removePrefix("added ") }
        .toSet() + setOf("sugar", "gluten", "lactose")
    return buildSet {
        for (operand in operands) for (operator in IngredientPredicateOperator.values()) {
            val predicate = IngredientPredicate(operator, operand)
            if (swiggyHasIngredientPredicate(label, predicate) ||
                includeProcessing && predicate == IngredientPredicate(IngredientPredicateOperator.ABSENT, "salt") &&
                label.none { it in "*\u2020\u2021" } &&
                swiggyIngredientAbsence(label, "salt", retainUnsaltedAlarm = true)) add(predicate)
        }
    }
}

// V1 can carry these already-supported dietary forms in the product name,
// without typed ingredient provenance. Reuse the same absence semantics at
// selection/final review; do not interpret the Sugar Free brand as an allergen.
private val dietaryAbsenceForm = Regex("(?<![\\p{L}\\p{M}\\p{N}_])(lactose|gluten)[ \\t_\\-\\u2010-\\u2015]*free(?![\\p{L}\\p{M}\\p{N}_])",
    RegexOption.IGNORE_CASE)
private fun normalizedDietaryForm(field: String) = dietaryAbsenceForm.replace(field) { it.groupValues[1] + " free" }

internal fun swiggyDietaryFormsAgree(item: com.example.beta.automation.ParsedItem,
    candidate: SwiggyMcpClient.RecommendationCandidate, primary: String): Boolean {
    val operands = mutableSetOf<String>()
    for (source in listOfNotNull(item.rawText, item.intentQuantityProvenance?.primaryText, item.query, item.strictMatchPhrase).distinct()) {
        for (claim in dietaryAbsenceForm.findAll(source)) {
            val term = claim.groupValues[1].lowercase(java.util.Locale.ROOT)
            val predicate = IngredientPredicate(IngredientPredicateOperator.ABSENT, term)
            if (!swiggyHasIngredientPredicate(normalizedDietaryForm(source), predicate)) return false
            operands += term
        }
    }
    val predicates = linkedSetOf<IngredientPredicate>()
    operands.forEach { predicates += IngredientPredicate(IngredientPredicateOperator.ABSENT, it) }
    item.avoidPhrases.map { it.trim().lowercase(java.util.Locale.ROOT) }
        .filter { it in setOf("sugar", "gluten", "lactose") }
        .forEach { predicates += IngredientPredicate(IngredientPredicateOperator.ABSENT, it) }
    if (predicates.isEmpty()) return true
    if (candidate.catalogueIngredientsInvalid) return false
    val skuFields = candidate.catalogueIngredients?.let { it.fieldsFor(candidate.spinId, candidate.skuId) ?: return false }.orEmpty()
    val fields = (listOf(primary) + swiggyRawCatalogueFields(candidate).drop(1) + skuFields)
        .map(::normalizedDietaryForm).distinct()
    if (predicates.any { it.operand == "sugar" } && fields.any(::swiggyHasSugarContradiction)) return false
    return swiggyIngredientPredicatesMatchFields(fields, predicates.toList())
}

/** Same-SKU catalogue evidence only: individual fields prove, their join only vetoes. */
internal fun swiggyIngredientPredicatesMatchFields(
    fields: List<*>,
    predicates: List<*>,
): Boolean {
    if (fields.isEmpty() || fields.size > 16 || predicates.isEmpty() || predicates.size > 16 ||
        fields.any { it !is String || it.codePointCount(0, it.length) > 500 || it.any(Char::isISOControl) } ||
        predicates.any { it !is IngredientPredicate } || predicates.distinct().size != predicates.size) return false
    val textFields = fields.filterIsInstance<String>()
    val typedPredicates = predicates.filterIsInstance<IngredientPredicate>()
    val joined = textFields.joinToString(" ")
    return typedPredicates.all { predicate ->
        val opposite = IngredientPredicate(
            if (predicate.operator == IngredientPredicateOperator.ABSENT) IngredientPredicateOperator.NO_ADDITION
            else IngredientPredicateOperator.ABSENT,
            predicate.operand,
        )
        textFields.any { swiggyHasIngredientPredicate(it, predicate) } &&
            textFields.none { swiggyHasIngredientPredicate(it, opposite) } &&
            swiggyHasIngredientPredicate(joined, predicate)
    }
}

/** Literal text evidence only; this is not an allergy or ingredient-safety guarantee. */
internal fun swiggyZeroPercentIngredientPattern(ingredient: String): Regex {
    val term = ingredient.split(" ").joinToString("[\\s-]+") { Regex.escape(it) }
    return Regex("(?<![\\p{L}\\p{N}_.+/−-])(?:zero|0)(?:[\\s-]+percent[\\s-]+|\\s*%[\\s-]*)(" + term + ")(?![\\p{L}\\p{N}_-])", RegexOption.IGNORE_CASE)
}

internal fun swiggyNormalizeZeroPercentIngredients(label: String): String {
    if ('%' !in label && !label.contains("percent", ignoreCase = true)) return label
    var result = label
    for ((ingredient, property) in listOf("sugar" to "sugarfree", "added sugar" to "noaddedsugar",
            "salt" to "unsalted", "added salt" to "noaddedsalt", "maida" to "zeromaida", "palm oil" to "zeropalmoil")) {
        val pattern = swiggyZeroPercentIngredientPattern(ingredient)
        // Check the ORIGINAL whole title, not a string changed by earlier matches.
        if (pattern.containsMatchIn(label) && swiggyHasExplicitIngredientAbsence(label, ingredient)) {
            result = pattern.replace(result, property)
        }
    }
    return result
}

private fun swiggyIngredientAbsencePatterns(ingredient: String): List<Regex> {
    val term = ingredient.split(" ").joinToString("[\\s-]+") { Regex.escape(it) }
    val joiner = "[\\s-]+"
    return listOf(
        Regex("(?<![\\p{L}\\p{N}_.+/−-])(?:no|without|zero)$joiner($term)(?![\\p{L}\\p{N}_-])"),
        Regex("(?<![\\p{L}\\p{N}_.+/−-])0$joiner($term)(?![\\p{L}\\p{N}_-])"),
        Regex("(?<![\\p{L}\\p{N}_])($term)$joiner" + "free(?![\\p{L}\\p{N}_-])"),
        swiggyZeroPercentIngredientPattern(ingredient),
    )
}

/** Denial-only identity view, never proof of a dietary claim. Even a qualified
 * absence claim cannot supply the product noun. Keep original fields for all
 * safety/quantity checks and display; a separate positive occurrence survives. */
internal data class SwiggyClaimIdentityView(val subjectText: String, val operandTexts: List<String>)

internal fun swiggyClaimIdentityView(label: String): SwiggyClaimIdentityView {
    val text = label.lowercase(java.util.Locale.ROOT)
    if (!ingredientIdentityClaimCue.containsMatchIn(text)) return SwiggyClaimIdentityView(text, emptyList())
    val subject = text.toCharArray()
    val operands = mutableListOf<String>()
    for (pattern in ingredientIdentityClaimPatterns) {
        pattern.findAll(text).forEach { match ->
            operands += ingredientIdentityOperand.findAll(match.value).map { it.value }
            match.range.forEach { subject[it] = ' ' }
        }
    }
    return SwiggyClaimIdentityView(String(subject), operands)
}

// Operators/modifiers are never operands. Capturing literal operands separately
// prevents removal of derived identity tokens such as sweetener or unsalted.
private const val identityClaimGap = "[\\s\\-·–—/]+"
private const val identityClaimControls = "no|without|zero|free|from|of|and|or|any|added|refined|cane|percent|g|gram|grams"
private val ingredientIdentityOperand = Regex(
    "(?<![\\p{L}\\p{N}_])(?:" + IngredientPredicate.protectedExclusions
        .filter { ' ' in it && !it.startsWith("added ") }.sortedByDescending { it.length }
        .joinToString("|") { it.split(' ').joinToString(identityClaimGap) { part -> Regex.escape(part) } } +
        "|(?!($identityClaimControls)(?![\\p{L}\\p{N}_]))[\\p{L}\\p{M}]+)(?![\\p{L}\\p{N}_])",
)
private val ingredientIdentityClaimPatterns: List<Regex> = run {
    val gap = identityClaimGap
    val modifier = "(?:(?:any|added|refined|cane)$gap)*"
    val negative = "(?:no|without|(?:zero|0(?:\\.0+)?)(?:\\s*(?:%|percent|g|grams?))?|free$gap(?:from|of))"
    val term = ingredientIdentityOperand.pattern
    // And/& may join separate product claims; or under a negator excludes
    // both operands. "low fat & lactose free" is not fat-free.
    val form = "(?:lowfat|fullcream|doubletoned|skimmed|readytoeat|readytocook|readytodrink|tetrapack)"
    val ingredient = "(?!$form(?![\\p{L}\\p{N}_]))$term"
    val coordinated = "$term(?:$gap(?:(?:and|&)$gap(?:$negative$gap$modifier$term|$modifier$ingredient)|" +
        "or$gap(?:$negative$gap)?$modifier$term))*"
    val postfix = "(?:$ingredient$gap(?:and|or|&)$gap)*$term"
    listOf(
        Regex("(?<![\\p{L}\\p{N}_.])$negative$gap$modifier$coordinated"),
        // Field separators cannot turn 'Butter — Free ...' into butter-free.
        // Free from/of and free-flow/range describe something other than absence.
        Regex("$postfix(?:\\s+|-)free(?![\\p{L}\\p{N}_]|[\\s-]+(?:from|of|range|flow|flowing|run|gift)\\b)"),
    )
}
private val ingredientIdentityClaimCue = Regex(
    "(?<![\\p{L}\\p{N}_.])(?:no|without|zero|0(?:\\.0+)?(?:\\s*(?:%|percent|g|grams?))?|free)(?![\\p{L}\\p{N}_])",
)

// Background commonly consists of short conjunctions. Only run the full
// operand-by-operand alarm when the source has an ingredient-claim marker.
internal fun swiggyBackgroundIngredientAlarm(text: String): Boolean =
    ingredientIdentityClaimCue.containsMatchIn(text.lowercase(java.util.Locale.ROOT)) &&
        swiggyIngredientClaimAlarms(text).isNotEmpty()

internal fun swiggyHasExplicitIngredientAbsence(label: String, ingredient: String): Boolean {
    return swiggyIngredientAbsence(label, ingredient, retainUnsaltedAlarm = false)
}

/** Preserve unsalted as a coarse deny-only interpreted alarm, never as absence proof. */
private fun swiggyIngredientAbsence(label: String, ingredient: String, retainUnsaltedAlarm: Boolean): Boolean {
    val text = label.lowercase()
    if (ingredient == "salt" && Regex("\\bsalted\\b").containsMatchIn(text)) return false
    val term = ingredient.split(" ").joinToString("[\\s-]+") { Regex.escape(it) }
    val boundary = "(?<![\\p{L}\\p{N}_])(?:$term)(?![\\p{L}\\p{N}_])"
    val occurrence = Regex(boundary)
    val joiner = "[\\s-]+"
    val patterns = swiggyIngredientAbsencePatterns(ingredient)
    // Reject qualified/negated claims across the title; punctuation or adverbs
    // must not turn a non-claim into evidence of ingredient absence.
    if (Regex("\\b(?:not|never|cannot|isnt|arent|cant|may|might|could|possibly|almost|nearly|" +
            "maybe|perhaps|potentially|apparently|roughly|approximately|around|supposedly|allegedly|sometimes|" +
            "alternatives?|substitutes?|substitutions?|or)\\b|n['’]t\\b").containsMatchIn(text)) return false
    val negativeClaims = listOf(
        Regex("\\bnot$joiner(?:(?:no|without|zero|0)$joiner)?$term\\b"),
        Regex("\\bnot$joiner$term$joiner" + "free\\b"),
        Regex("\\bno$joiner$term$joiner" + "free\\b"),
    )
    if (negativeClaims.any { it.containsMatchIn(text) }) return false
    val qualifier = Regex("\\b(?:may|might|possibly|low|less|reduced|alternatives?)\\b")
    if (occurrence.findAll(text).any { match ->
            val context = text.substring((match.range.first - 30).coerceAtLeast(0), (match.range.last + 31).coerceAtMost(text.length))
            qualifier.containsMatchIn(context)
        }) return false
    val claims = mutableListOf<IntRange>()
    for (pattern in patterns) {
        for (match in pattern.findAll(text)) {
            // A qualified claim such as "no preservatives added" is not absence.
            if (Regex("^[\\t ,:-]*(?:added|used|mixed|sprinkled|coated|has|have|had|is|was|were|being|" +
                    "per|in|on|to|from|for|syrup|coating|cubes?)\\b")
                    .containsMatchIn(text.substring(match.range.last + 1))) return false
            val termRange = match.groups[1]!!.range
            claims += termRange
        }
    }
    val mentions = occurrence.findAll(text).map { it.range }.toList()
    if (retainUnsaltedAlarm && ingredient == "salt" && mentions.isEmpty() &&
        Regex("(?<![\\p{L}\\p{N}_])unsalted(?![\\p{L}\\p{N}_-])").containsMatchIn(text)) {
        return !Regex("\\b(?:salted|low|less|reduced)\\b").containsMatchIn(text)
    }
    if (mentions.isEmpty() || claims.isEmpty()) return false
    return mentions.all { mention ->
        claims.any { claim -> mention.first >= claim.first && mention.last <= claim.last } ||
            (ingredient == "salt" && claims.isNotEmpty() &&
                Regex("\\bno\\s+added\\s+$").containsMatchIn(text.substring(0, mention.first)))
    }
}
