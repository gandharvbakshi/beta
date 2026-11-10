package com.example.beta

import com.example.beta.automation.ParsedItem

/** Local immutable source receipt. Never serialize to analytics or provider tools. */
class IntentIngredientProvenance private constructor(
    private val sourceKey: String,
    private val rawText: String,
    private val query: String,
    private val exclusions: List<String>,
    private val obligations: List<IngredientPredicate>,
) {
    internal fun predicates(): List<IngredientPredicate> = obligations.toList()
    internal fun matches(item: ParsedItem): Boolean = item.requiresIntentQuantityProof &&
        item.rawText == rawText && item.query == query && item.avoidPhrases == exclusions

    override fun equals(other: Any?): Boolean = other is IntentIngredientProvenance &&
        sourceKey == other.sourceKey && rawText == other.rawText && query == other.query &&
        exclusions == other.exclusions && obligations == other.obligations
    override fun hashCode(): Int = listOf(sourceKey, rawText, query, exclusions, obligations).hashCode()
    override fun toString(): String = "IntentIngredientProvenance(<redacted>)"

    companion object {
        internal fun fromProvenObligations(
            instruction: String, rowIndex: Int, item: ParsedItem,
            proofs: List<GroceryIntentPropertyObligation>,
        ): IntentIngredientProvenance? {
            val scoped = proofs.filter { rowIndex in it.memberIndices && it.ingredientPredicates.isNotEmpty() }
            if (scoped.isEmpty()) return null
            require(scoped.all { proof -> proof.source.startUtf16 >= 0 &&
                proof.source.endUtf16 <= instruction.length &&
                instruction.substring(proof.source.startUtf16, proof.source.endUtf16) == proof.source.text })
            val predicates = scoped.flatMap { it.ingredientPredicates }.distinct()
            require(predicates.size in 1..16)
            requireIngredientPredicateProjection(predicates, item.avoidPhrases, allowTyped = true)
            val parts = listOf(instruction, rowIndex.toString()) + scoped.flatMap { proof ->
                listOf(proof.source.startUtf16.toString(), proof.source.endUtf16.toString(), proof.source.text,
                    proof.memberIndices.joinToString(",")) + proof.ingredientPredicates.flatMap {
                    listOf(it.operator.name, it.operand)
                }
            }
            return IntentIngredientProvenance(parts.joinToString("") { "${it.length}:$it" },
                item.rawText, item.query, item.avoidPhrases.toList(), predicates.toList())
        }
    }
}

/** Null means invalid provenance, NOT an empty set of restrictions. One accessor
 * feeds retrieval and final matching without laundering a model observation
 * into fromProvenObligations' source-compiled receipt. */
internal fun validatedIngredientPredicates(item: ParsedItem): List<IngredientPredicate>? {
    if (item.hasAuditedIntent()) return item.auditedIntent()?.predicates()
    val proof = item.ingredientProvenance
    if (proof != item.intentQuantityProvenance?.ingredientProvenance || proof?.matches(item) == false) return null
    return proof?.predicates().orEmpty()
}

internal fun swiggyTypedIngredientsMatch(item: ParsedItem, candidate: SwiggyMcpClient.RecommendationCandidate): Boolean {
    val predicates = validatedIngredientPredicates(item) ?: return false
    if (predicates.isEmpty()) return true
    if (candidate.catalogueIngredientsInvalid) return false
    val fields = candidate.catalogueIngredients?.fieldsFor(candidate.spinId, candidate.skuId) ?: return false
    val allFields = fields + listOfNotNull(candidate.label, candidate.variant, candidate.subtitle)
    if (predicates.any { it.operator == IngredientPredicateOperator.ABSENT && it.operand == "sugar" } &&
        swiggyHasSugarContradiction(allFields.joinToString(" "))) return false
    // Positive proof comes ONLY from complete original variation fields.
    if (!swiggyIngredientPredicatesMatchFields(fields, predicates)) return false
    // Display views may veto but cannot manufacture proof through concatenation.
    return predicates.all { swiggyHasIngredientPredicate(
        allFields.joinToString(" "), it) }
}
