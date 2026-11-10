package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import java.util.Locale

/** Typed and immutable SKU-bound catalogue quantity facts. Values stay raw. */
data class CatalogueQuantityFields(
    val variantName: String? = null,
    val variant: String? = null,
    val packSize: String? = null,
    val quantityDescription: String? = null,
    val subTitle: String? = null,
)

data class CatalogueQuantityEvidence(
    val version: Int,
    val source: String,
    val spinId: String,
    val skuId: String,
    val fields: CatalogueQuantityFields,
)

/** Only source-bound, revalidated metadata may enter quantity matching. */
internal fun RecommendationCandidate.catalogueQuantityIssue(): String? {
    if (catalogueQuantityInvalid) return "catalogue_quantity_evidence_invalid"
    val evidence = catalogueQuantity ?: return null
    if (!candidateIdIsBound(spinId) || !candidateIdIsBound(skuId) ||
        evidence.version != 1 || evidence.source != "search_variation" ||
        evidence.spinId != spinId || evidence.skuId != skuId ||
        !validCatalogueQuantityFields(evidence.fields)) return "catalogue_quantity_evidence_invalid"
    return null
}

/** Fixed-order raw strings. Null means absent or invalid, never a partial parse. */
internal fun RecommendationCandidate.catalogueQuantityFields(): List<Pair<String, String>>? {
    if (catalogueQuantityIssue() != null) return null
    val fields = catalogueQuantity?.fields ?: return null
    return buildList {
        fields.variantName?.let { add("variantName" to it) }
        fields.variant?.let { add("variant" to it) }
        fields.packSize?.let { add("packSize" to it) }
        fields.quantityDescription?.let { add("quantityDescription" to it) }
        fields.subTitle?.let { add("subTitle" to it) }
    }.takeIf { it.isNotEmpty() }
}

/** Stable length-prefixed identity including every typed evidence component. */
internal fun RecommendationCandidate.catalogueQuantityIdentityKey(): String? {
    val evidence = catalogueQuantity ?: return null
    val fields = catalogueQuantityFields() ?: return null
    val components = listOf(
        evidence.version.toString(), evidence.source, evidence.spinId, evidence.skuId,
    ) + fields.flatMap { listOf(it.first, it.second) }
    return components.joinToString(separator = "") { component -> "${component.length}:$component" }
}

/** Raw positive/negative quantity text only; no normalization or deduplication. */
internal fun swiggyRawCatalogueFields(candidate: RecommendationCandidate, omitTypedField: String? = null): List<String> =
    listOfNotNull(candidate.label, candidate.variant, candidate.subtitle) +
        candidate.catalogueQuantityFields().orEmpty().mapNotNull { (key, value) -> value.takeUnless { key == omitTypedField } }

/** One SKU must affirm the SAME named unit requested by the source. A weight,
 * a display-only claim, or "1 pack" cannot prove that the SKU contains one bar.
 * Other fields still pass the resolver's independent bundle/conflict checks. */
internal fun swiggyCatalogueHasSingleRetailUnit(candidate: RecommendationCandidate, container: String): Boolean =
    candidate.catalogueQuantityFields().orEmpty().any { (_, field) -> isSingleNamedUnitField(field, container) } ||
        candidate.ownedOuterAndNetSize()?.outer == container

/** One outer and the net contents of ONE SKU, not two interchangeable amounts. */
internal data class SingleOuterNetSize(val outer: String, val size: GroceryPackMeasure)
private val singleOuterForm = Regex("1 +([a-z]+)")
private val singleOuterMetric = Regex("((?:0|[1-9][0-9]{0,6})(?:\\.[0-9]{1,4})?) *(g|gm|kg|ml|l)")

internal fun parseSingleOuterNetSize(field: String): SingleOuterNetSize? {
    if (field.length > 160) return null
    val parts = field.lowercase(Locale.ROOT).split('·')
    if (parts.size != 2) return null
    val outer = singleOuterForm.matchEntire(parts[0].trim())?.groupValues?.get(1) ?: return null
    if (groceryIntentRetailContainerKey(outer) != outer) return null
    val m = singleOuterMetric.matchEntire(parts[1].trim()) ?: return null
    val size = GroceryPackMeasure.of(m.groupValues[1].toBigDecimal(), m.groupValues[2])
    return SingleOuterNetSize(outer, size).takeIf { size.baseValue.signum() > 0 }
}

/** Direct variation supplies both roles. All other fields may veto, never complete the pair. */
internal fun RecommendationCandidate.ownedOuterAndNetSize(): SingleOuterNetSize? {
    val variantText = variant ?: return null
    val fact = parseSingleOuterNetSize(variantText) ?: return null
    val owned = catalogueQuantityFields() ?: return null
    val primary = label.removeSuffix(" · $variantText")
    val copies = owned.filter { it.first == "variant" || it.first == "variantName" }.ifEmpty {
        // Some variations publish only quantityDescription. It must be the selected
        // field in both raw views, not an equivalent-looking title or subtitle.
        val evidence = catalogueIngredients ?: return null
        if (owned.first().first != "quantityDescription" ||
            !evidence.agreesWithSizeProjection(spinId, skuId, variantText, owned) ||
            evidence.titlesFor(spinId, skuId)?.distinct() != listOf(primary)) return null
        listOf(owned.first())
    }
    if (copies.isEmpty() || copies.any { it.second != variantText } || catalogueIngredientsInvalid ||
        swiggyCatalogueQuantityConflict(this)) return null
    if (primary.isBlank() || '·' in primary || sourceNominalHasAmountOrRelation(primary) ||
        swiggyOuterSizeTitleHasPromotionOrRelationship(primary)) return null
    fun sameNet(text: String) = groceryPackMeasurePattern.matches(text.trim()) && groceryPackMeasures(text) == setOf(fact.size)
    if (owned.any { it !in copies && !sameNet(it.second) } || subtitle?.let { !sameNet(it) } == true) return null
    catalogueIngredients?.let { evidence ->
        val fields = evidence.fieldsFor(spinId, skuId) ?: return null
        if (fields.any { it != primary && it != variantText && !sameNet(it) }) return null
    }
    return fact
}

internal fun isSingleNamedUnitField(field: String, container: String): Boolean =
    Regex("1[ \\t]+(.+)").matchEntire(field.trim())?.let {
        groceryIntentRetailContainerKey(it.groupValues[1]) == container
    } == true

/** Extra provider fields can veto a conflicting amount, never silently replace it. */
internal fun swiggyCatalogueQuantityConflict(candidate: RecommendationCandidate): Boolean {
    if (candidate.catalogueQuantityIssue() != null) return true
    if (candidate.catalogueQuantity == null) return false // Legacy wire shape.
    val fields = swiggyRawCatalogueFields(candidate)
    val nutrition = fields.map(::nutritionMeasureEvidence)
    if (nutrition.any { it.unsupported } || nutritionClaimsConflict(nutrition.flatMap { it.claims }.toSet())) return true
    return fields.flatMap(::groceryPackMeasures).groupBy { it.dimension }
        .values.any { amounts -> amounts.map { it.baseValue }.distinct().size > 1 }
}

internal fun parseCatalogueQuantityEvidence(
    raw: JsonValue?,
    candidateSpinId: String?,
    candidateSkuId: String?,
    duplicatedCandidateKeys: Set<String> = emptySet(),
): CatalogueQuantityEvidence? {
    val obj = raw as? JsonValue.Obj ?: return null
    if (obj.duplicateKeys.isNotEmpty() ||
        duplicatedCandidateKeys.any { it in setOf("spinId", "skuId", "catalogueQuantity") }) return null
    val members = obj.members
    if (members.keys != setOf("version", "source", "spinId", "skuId", "fields")) return null
    val version = (members["version"] as? JsonValue.Num)?.raw?.takeIf { it == "1" }?.toIntOrNull() ?: return null
    val source = (members["source"] as? JsonValue.Str)?.value ?: return null
    val spinId = (members["spinId"] as? JsonValue.Str)?.value ?: return null
    val skuId = (members["skuId"] as? JsonValue.Str)?.value ?: return null
    if (source != "search_variation" || !candidateIdIsBound(candidateSpinId) ||
        !candidateIdIsBound(candidateSkuId) || spinId != candidateSpinId || skuId != candidateSkuId) return null
    val fieldsObj = members["fields"] as? JsonValue.Obj ?: return null
    if (fieldsObj.duplicateKeys.isNotEmpty()) return null
    val fieldMembers = fieldsObj.members
    val allowed = setOf("variantName", "variant", "packSize", "quantityDescription", "subTitle")
    if (fieldMembers.isEmpty() || fieldMembers.size > 5 || fieldMembers.keys.any { it !in allowed }) return null
    if (fieldMembers.values.any { value ->
            val text = (value as? JsonValue.Str)?.value
            text == null || !validCatalogueQuantityText(text)
        }) return null
    fun text(key: String): String? = (fieldMembers[key] as? JsonValue.Str)?.value
    val variantName = text("variantName")
    val variant = text("variant")
    val packSize = text("packSize")
    val quantityDescription = text("quantityDescription")
    val subTitle = text("subTitle")
    return CatalogueQuantityEvidence(
        version = version,
        source = source,
        spinId = spinId,
        skuId = skuId,
        fields = CatalogueQuantityFields(variantName, variant, packSize, quantityDescription, subTitle),
    ).takeIf { validCatalogueQuantityFields(it.fields) }
}

private fun candidateIdIsBound(value: String?): Boolean =
    value != null && value.isNotBlank() && value == value.trim()

private fun validCatalogueQuantityText(value: String): Boolean =
    value.isNotBlank() && value.length in 1..160 && value.none(Char::isISOControl)

private fun validCatalogueQuantityFields(fields: CatalogueQuantityFields): Boolean {
    val values = listOfNotNull(
        fields.variantName, fields.variant, fields.packSize, fields.quantityDescription, fields.subTitle,
    )
    return values.isNotEmpty() && values.size <= 5 && values.all(::validCatalogueQuantityText)
}
