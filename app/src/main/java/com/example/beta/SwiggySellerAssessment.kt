package com.example.beta

/** Immutable server assessment bound to one exact normalized catalogue candidate. */
class SwiggySellerAssessment private constructor(
    private val policy: String,
    private val snapshotFingerprint: String,
    private val requirement: SellerPropertyRequirement,
    private val spinId: String,
    private val skuId: String,
    private val label: String,
    private val variant: String?,
    private val subtitle: String?,
    private val catalogueIngredientsIdentity: String,
    private val catalogueBrand: SwiggyMcpClient.CatalogueBrandEvidence?,
    private val properties: List<String>,
) {
    /** True only while every identity/text/evidence binding remains unchanged. */
    internal fun matches(
        expectedRequirement: SellerPropertyRequirement,
        candidate: SwiggyMcpClient.RecommendationCandidate,
    ): Boolean = policy == policyFor(expectedRequirement) &&
        snapshotFingerprint.matches(FINGERPRINT) &&
        requirement == expectedRequirement &&
        properties.size == expectedRequirement.properties.size &&
        properties.toSet() == expectedRequirement.properties.toSet() &&
        candidate.spinId == spinId && candidate.skuId == skuId &&
        candidate.label == label && candidate.variant == variant && candidate.subtitle == subtitle &&
        !candidate.catalogueIngredientsInvalid &&
        candidate.catalogueIngredients?.identityKey() == catalogueIngredientsIdentity &&
        candidate.catalogueBrand == catalogueBrand &&
        candidate.catalogueIngredients.supportsProcessingLabels(expectedRequirement, candidate)

    override fun toString(): String = "SwiggySellerAssessment(<redacted>)"

    internal companion object {
        const val POLICY = "seller-assessment-v3"
        const val LITERAL_POLICY = "seller-assessment-v4-literal"
        fun policyFor(requirement: SellerPropertyRequirement?): String =
            if (requirement != null && literalProcessingLabel.containsMatchIn(requirement.source.text)) LITERAL_POLICY else POLICY
        fun batchPolicy(requirements: List<SellerPropertyRequirement?>): String =
            if (requirements.any { policyFor(it) == LITERAL_POLICY }) LITERAL_POLICY else POLICY
        private val FINGERPRINT = Regex("[0-9a-f]{64}")

        /** Validate exact wire shape and raw candidate binding before caller normalizes text. */
        fun parse(raw: JsonValue?, rawCandidate: JsonValue.Obj, expected: SellerPropertyRequirement,
                  normalizedCandidate: SwiggyMcpClient.RecommendationCandidate): SwiggySellerAssessment {
            val assessment = raw as? JsonValue.Obj ?: error("Missing seller assessment")
            require(assessment.duplicateKeys.isEmpty() && assessment.members.keys ==
                setOf("policy", "snapshotFingerprint", "requirement", "candidate", "properties"))
            val expectedPolicy = policyFor(expected)
            require((assessment.members["policy"] as? JsonValue.Str)?.value == expectedPolicy)
            val fingerprint = (assessment.members["snapshotFingerprint"] as? JsonValue.Str)?.value
                ?: error("Invalid seller assessment fingerprint")
            require(FINGERPRINT.matches(fingerprint))
            val requirement = SellerPropertyRequirement.fromPayload(
                assessment.members["requirement"] ?: error("Missing seller assessment requirement"))
            require(requirement == expected) { "Changed seller assessment requirement" }

            val candidateMirror = assessment.members["candidate"] as? JsonValue.Obj
                ?: error("Missing seller assessment candidate")
            val mirrorKeys = setOf("spinId", "skuId", "label", "variant", "subtitle", "catalogueIngredients")
            require(candidateMirror.duplicateKeys.isEmpty() && candidateMirror.members.keys == mirrorKeys)
            require(rawCandidate.duplicateKeys.isEmpty())
            val rawSpin = (rawCandidate.members["spinId"] as? JsonValue.Str)?.value
                ?: error("Invalid assessed candidate spin ID")
            val rawSku = (rawCandidate.members["skuId"] as? JsonValue.Str)?.value
                ?: error("Invalid assessed candidate SKU ID")
            val rawLabel = (rawCandidate.members["label"] as? JsonValue.Str)?.value
                ?: error("Invalid assessed candidate label")
            require(rawSpin.isNotBlank() && rawSku.isNotBlank() && rawLabel.isNotBlank())
            require((candidateMirror.members["spinId"] as? JsonValue.Str)?.value == rawSpin)
            require((candidateMirror.members["skuId"] as? JsonValue.Str)?.value == rawSku)
            require((candidateMirror.members["label"] as? JsonValue.Str)?.value == rawLabel)

            fun nullableText(key: String): String? {
                val rawValue = rawCandidate.members[key]
                val mirrorValue = candidateMirror.members[key] ?: error("Missing candidate mirror field")
                // The backend canonicalizes missing optional text to JSON null.
                val parsed = when (rawValue) {
                    null, JsonValue.Null -> null
                    is JsonValue.Str -> rawValue.value
                    else -> error("Invalid assessed candidate text")
                }
                val mirrored = when (mirrorValue) {
                    JsonValue.Null -> null
                    is JsonValue.Str -> mirrorValue.value
                    else -> error("Invalid seller assessment mirror text")
                }
                require(parsed == mirrored)
                return parsed
            }
            val variant = nullableText("variant")
            val subtitle = nullableText("subtitle")

            val rawIngredients = rawCandidate.members["catalogueIngredients"] ?: JsonValue.Null
            val mirroredIngredients = candidateMirror.members["catalogueIngredients"]
                ?: error("Missing candidate mirror ingredients")
            require(rawIngredients == mirroredIngredients) { "Changed seller assessment catalogue evidence" }
            val evidence = CatalogueIngredientEvidence.parse(rawIngredients, rawSpin, rawSku, emptySet())
                ?: error("Missing or invalid assessed catalogue evidence")
            require(normalizedCandidate.spinId == rawSpin && normalizedCandidate.skuId == rawSku &&
                normalizedCandidate.variant == variant && normalizedCandidate.subtitle == subtitle &&
                normalizedCandidate.catalogueIngredients == evidence &&
                !normalizedCandidate.catalogueIngredientsInvalid)

            val propertiesRaw = assessment.members["properties"] as? JsonValue.Arr
                ?: error("Invalid seller assessment properties")
            val properties = propertiesRaw.items.map { value ->
                (value as? JsonValue.Str)?.value ?: error("Invalid seller assessment property ID")
            }
            require(properties.size == properties.distinct().size &&
                properties.size == expected.properties.size && properties.toSet() == expected.properties.toSet()) {
                "Incomplete seller assessment property coverage"
            }
            return SwiggySellerAssessment(expectedPolicy, fingerprint, requirement, rawSpin, rawSku,
                normalizedCandidate.label, variant, subtitle, evidence.identityKey(), normalizedCandidate.catalogueBrand,
                properties.toList())
        }
    }
}
