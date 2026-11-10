package com.example.beta

import com.example.beta.automation.ProductLexicon
import java.util.Locale

/** Complete literal relation only; target membership and retained fields need separate proof. */
internal data class IngredientPredicateClause(
    val selector: String,
    val predicates: List<IngredientPredicate>,
    val issueCode: String? = null,
) {
    override fun toString(): String =
        "IngredientPredicateClause(selector=<redacted>, predicates=<redacted>, issueCode=$issueCode)"
}

private const val ingredientSelector = "(?<selector>[A-Za-z][A-Za-z '\\t-]{0,100}?)"
private const val ingredientOperand = "(?<operand>[A-Za-z][A-Za-z \\t-]{0,100}?)"
private const val ingredientSpace = "[ \\t]+"
private val ingredientPredicatePatterns = listOf(
    Triple("no$ingredientSpace$ingredientOperand${ingredientSpace}added${ingredientSpace}to$ingredientSpace(?:the$ingredientSpace)?$ingredientSelector", IngredientPredicateOperator.NO_ADDITION, true),
    Triple("no$ingredientSpace$ingredientOperand${ingredientSpace}in$ingredientSpace(?:the$ingredientSpace)?$ingredientSelector", IngredientPredicateOperator.ABSENT, true),
    Triple("$ingredientSelector${ingredientSpace}without$ingredientSpace$ingredientOperand", IngredientPredicateOperator.ABSENT, false),
    Triple("$ingredientSelector${ingredientSpace}mein$ingredientSpace$ingredientOperand$ingredientSpace(?:nahi|nahin)", IngredientPredicateOperator.ABSENT, true),
).map { (pattern, operator, typedCompositionAllowed) ->
    Triple(Regex(pattern, RegexOption.IGNORE_CASE), operator, typedCompositionAllowed)
}
private val typedIngredientUnsafeOperandWords = setOf(
    "extra", "more", "much", "excess", "high", "too", "very", "little", "fewer", "additional",
    "bag", "bags", "plastic", "packet", "packets", "box", "boxes", "delay", "organic", "frozen", "dried", "fresh",
)

/** Recognize one complete relation, never a bare negated item or substring. */
internal fun ingredientPredicateClause(
    text: String,
    allowTypedIngredientPredicates: Boolean = false,
): IngredientPredicateClause? {
    // Python's source grammar uses re.ASCII: Unicode case-folding cannot supply letters here.
    if (text.any { it.code > 127 }) return null
    for ((pattern, operator, typedCompositionAllowed) in ingredientPredicatePatterns) {
        val match = pattern.matchEntire(text) ?: continue
        val selector = match.groups["selector"]!!.value
        val operands = Regex("[ \\t]+(?:or|ya)[ \\t]+", RegexOption.IGNORE_CASE)
            .split(match.groups["operand"]!!.value)
        val predicates = try {
            operands.map { operand ->
                var literal = operand.lowercase(Locale.ROOT).replace(Regex("[ \\t-]+"), " ").trim()
                // A prefix 'added' belongs only to its own conjunct. The
                // postfix '... added to ...' explicitly scopes the whole list.
                val localOperator = if (operator == IngredientPredicateOperator.ABSENT && literal.startsWith("added ")) {
                    literal = literal.removePrefix("added ")
                    IngredientPredicateOperator.NO_ADDITION
                } else operator
                IngredientPredicate(localOperator, ProductLexicon.canonicalizeIdentityNounsPreservingText(literal))
            }
        } catch (_: IllegalArgumentException) {
            return IngredientPredicateClause(selector, emptyList(), "ingredient_predicate_unproven")
        }
        if (predicates.size !in 1..4 || predicates.distinct().size != predicates.size) {
            return IngredientPredicateClause(selector, emptyList(), "ingredient_predicate_unproven")
        }
        val unsupportedTyped = predicates.any { predicate ->
            if (predicate.legacyExclusion() != null) return@any false
            if (!allowTypedIngredientPredicates) return@any false
            if (!typedCompositionAllowed) return@any true
            val words = predicate.operand.split(' ')
            val selectorWords = Regex("[a-z]+", RegexOption.IGNORE_CASE).findAll(selector.lowercase(Locale.ROOT))
                .map { it.value }.toList()
            words.any { it in typedIngredientUnsafeOperandWords } ||
                selectorWords.isNotEmpty() && words.last() == selectorWords.last()
        }
        if (unsupportedTyped) return IngredientPredicateClause(selector, emptyList(), "ingredient_predicate_unenforced")
        return IngredientPredicateClause(selector, predicates)
    }
    return null
}

/** Exact legacy field retention, not free-text ingredient or identity detection. */
internal fun ingredientExclusionPredicates(
    tags: List<String>,
    allowTyped: Boolean = false,
): Set<IngredientPredicate> = tags.mapNotNull { tag ->
    val phrase = tag.lowercase(Locale.ROOT).replace(Regex("[ \\t_-]+"), " ").trim()
    IngredientPredicate.fromProtectedExclusion(phrase) ?: if (allowTyped) {
        try {
            if (phrase.startsWith("added ")) {
                IngredientPredicate(IngredientPredicateOperator.NO_ADDITION, phrase.removePrefix("added "))
            } else {
                IngredientPredicate(IngredientPredicateOperator.ABSENT, phrase)
            }
        } catch (_: IllegalArgumentException) {
            null
        }
    } else null
}.toSet()

/** Require exact retained operator strength before any source proof is used. */
internal fun requireIngredientPredicateProjection(
    predicates: List<IngredientPredicate>,
    modelExcluded: List<String>,
    allowTyped: Boolean = false,
): List<String> {
    val rendered = predicates.map { if (allowTyped) it.literalExclusion() else it.legacyExclusion() }
    require(rendered.none { it == null }) { "ingredient_predicate_unenforced" }
    require(ingredientExclusionPredicates(modelExcluded, allowTyped).containsAll(predicates)) { "ingredient_predicate_not_retained" }
    return rendered.map { requireNotNull(it) }
}
