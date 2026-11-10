package com.example.beta

/** Bounded raw current-SKU fields. Private construction prevents partial proof. */
class CatalogueIngredientEvidence private constructor(
    private val spinId: String, private val skuId: String, private val fields: List<Pair<String, String>>,
) {
    internal fun fieldsFor(spin: String, sku: String?): List<String>? =
        fields.map { it.second }.takeIf { spin == spinId && sku == skuId }
    internal fun titlesFor(spin: String, sku: String?): List<String>? =
        if (spin != spinId || sku != skuId) null else fields.filter { it.first in titleKeys }.map { it.second }
    internal fun variantsFor(spin: String, sku: String?): List<String>? =
        if (spin != spinId || sku != skuId) null else fields.filter { it.first in variantKeys }.map { it.second }

    /** The provider projects the first present key in this order. A display echo, even from
     * this SKU, is not evidence from a different source field. */
    internal fun agreesWithSizeProjection(spin: String, sku: String?, raw: String,
                                          quantity: List<Pair<String, String>>): Boolean {
        if (spin != spinId || sku != skuId || quantity.firstOrNull()?.first !in setOf("packSize", "quantityDescription")) return false
        return quantity.first().second == raw && quantity == fields.filter { it.first in projectionKeys }
    }

    /** An interpreted processing word describes the item sold, not an ingredient or a
     * promise from the assessor. Only the bound variation can supply this evidence;
     * display, pack and secondary fields may disqualify it, never establish it. */
    internal fun supportsProcessingLabels(requirement: SellerPropertyRequirement,
                                         candidate: SwiggyMcpClient.RecommendationCandidate): Boolean {
        val labels = requirement.requirements.map { it.text.lowercase(java.util.Locale.ROOT) }
            .filter(literalProcessingLabel::matches)
        if (literalProcessingLabel.findAll(requirement.source.text).map { it.value.lowercase(java.util.Locale.ROOT) }
                .toList().sorted() != labels.sorted()) return false
        if (labels.isEmpty()) return true
        if (candidate.spinId != spinId || candidate.skuId != skuId) return false
        val names = titlesFor(spinId, skuId).orEmpty()
        val variants = fields.filter { it.first == "variant" || it.first == "variantName" }.map { it.second }
        val brand = candidate.catalogueBrand?.takeIf {
            it.spinId == spinId && it.skuId == skuId && it.source == "search_variation"
        }?.name
        fun words(text: String) = text.lowercase(java.util.Locale.ROOT).trim().replace(whitespace, " ")
        // Compare complete noun phrases. Do not discover a noun by deleting
        // arbitrary words from a related product, instruction or alternative.
        fun plainIdentity(value: String) = plainWords.matches(value) && !qualifiedProcessingLabel.containsMatchIn(value) &&
            !propertyHasUnprovedNegation(value) && !literalProcessingLabel.containsMatchIn(value) &&
            value.split(' ').none { it in misleadingWords }
        val maker = brand?.let(::words)
        if (maker != null && !plainIdentity(maker)) return false
        val displayName = candidate.variant?.let { candidate.label.removeSuffix(" · $it") } ?: candidate.label
        fun neutralPack(value: String): Boolean {
            val parts = value.lowercase(java.util.Locale.ROOT).split('·').map { it.trim() }
            if (parts.size == 1) return plainMeasure.matches(parts.single())
            return parseSingleOuterNetSize(value) != null
        }
        val visible = listOfNotNull(displayName, candidate.variant, candidate.subtitle) +
            candidate.catalogueQuantityFields().orEmpty().map { it.second } + fields.map { it.second }
        return labels.all { label ->
            val noun = words(requirement.subject).removePrefix("$label ")
                .removePrefix(maker?.let { "$it " } ?: "").removePrefix("$label ")
            if (!plainIdentity(noun) || noun == maker || !swiggyKnownUnbrandedNoun(noun.substringAfterLast(' '))) return@all false
            fun shapes(base: String) = listOfNotNull(maker?.let { "$it $base" }, base)
            val makerNames = listOfNotNull(maker?.let { "$it $label $noun" }, maker?.let { "$label $it $noun" })
            val labelled = makerNames + "$label $noun"
            val titles = shapes(noun) + labelled
            val nominal = Regex("(?<![\\p{L}\\p{N}_])(?:" + labelled.joinToString("|") { Regex.escape(it) } +
                ")(?![\\p{L}\\p{N}_])").find(words(requirement.source.text)) ?: return@all false
            // An unclassified prefix may be a different maker. Never let the
            // shopper's words echoed in a title overrule a structured brand.
            if (!swiggyKnownUnbrandedNoun(noun) && (nominal.value !in makerNames ||
                    (names + variants).none { words(it) in makerNames })) return@all false
            val outside = words(requirement.source.text).removeRange(nominal.range)
            if (sourceToken.findAll(outside).any { m -> val w = m.value
                w !in GROUP_NUMERIC_WORDS && w !in setOf("of", "each", ",", ".") && w.toIntOrNull() == null &&
                    groceryIntentRetailContainerKey(w) == null && !groceryPackMeasurePattern.matches("1 $w") &&
                    !groceryPackMeasurePattern.matches(w)
            }) return@all false
            visible.all { words(it) in titles || words(it) == label || neutralPack(it) } &&
                (names.any { words(it) in labelled } || variants.any { words(it) in labelled ||
                    words(it) == label && names.any { n -> words(n) in titles } })
        }
    }
    internal fun identityKey(): String = (listOf(spinId, skuId) + fields.flatMap { listOf(it.first, it.second) })
        .joinToString("") { "${it.length}:$it" }
    override fun equals(other: Any?): Boolean = other is CatalogueIngredientEvidence && identityKey() == other.identityKey()
    override fun hashCode(): Int = identityKey().hashCode()
    override fun toString(): String = "CatalogueIngredientEvidence(<redacted>)"

    companion object {
        private val whitespace = Regex("\\s+")
        private val plainWords = Regex("[a-z]+(?: [a-z]+)*")
        private val misleadingWords = setOf("salted", "sweetened", "preferred", "alternative", "substitute")
        private val sourceToken = Regex("[\\p{L}\\p{M}\\p{N}_]+|[^\\s]")
        private val plainMeasure = Regex("(?=[0-9.]*[1-9])[0-9]+(?:\\.[0-9]+)?\\s*(?:g|gm|kg|ml|l|pc|pcs)")
        private val titleKeys = setOf("productName", "displayName", "itemName", "name", "title")
        private val variantKeys = setOf("variant", "variantName")
        private val projectionKeys = setOf("variantName", "variant", "packSize", "quantityDescription", "subTitle")
        internal val fieldKeys = listOf("productName", "displayName", "itemName", "name", "title", "variantName",
            "variant", "packSize", "quantityDescription", "subTitle", "subtitle")
        internal fun parse(raw: JsonValue?, spinId: String?, skuId: String?, duplicates: Set<String>): CatalogueIngredientEvidence? {
            val obj = raw as? JsonValue.Obj ?: return null
            if (obj.duplicateKeys.isNotEmpty() || duplicates.any { it in setOf("spinId", "skuId", "catalogueIngredients") }) return null
            val m = obj.members
            if (m.keys != setOf("version", "source", "spinId", "skuId", "fields") ||
                (m["version"] as? JsonValue.Num)?.raw != "1" ||
                (m["source"] as? JsonValue.Str)?.value != "search_variation" ||
                spinId.isNullOrBlank() || skuId.isNullOrBlank() || spinId != spinId.trim() || skuId != skuId.trim() ||
                (m["spinId"] as? JsonValue.Str)?.value != spinId ||
                (m["skuId"] as? JsonValue.Str)?.value != skuId) return null
            val rawFields = m["fields"] as? JsonValue.Obj ?: return null
            if (rawFields.duplicateKeys.isNotEmpty() || rawFields.members.isEmpty() ||
                rawFields.members.keys.any { it !in fieldKeys }) return null
            val fields = fieldKeys.filter { it in rawFields.members }.map { key ->
                val text = (rawFields.members[key] as? JsonValue.Str)?.value ?: return null
                if (text.isBlank() || text.codePointCount(0, text.length) > 500 || text.any(Char::isISOControl)) return null
                key to text
            }
            return CatalogueIngredientEvidence(spinId, skuId, fields)
        }
    }
}
