package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.automation.ParsedItem
import com.example.beta.automation.Quantity
import java.util.Locale

/** One cart unit contains one named outer container and N inner pieces.
 * Never infer this relationship from adjacency ("Box 6 pcs"), history or SKU names.
 */
internal data class SwiggyCatalogueRetailContents(val container: String, val innerPieces: Int)
internal data class SwiggyCatalogueContentsAnalysis(
    val contents: SwiggyCatalogueRetailContents? = null,
    val issue: Boolean = false,
)

private val singularContainer = "(?:tetra[ -]+pack|pack|packet|bag|bottle|carton|can|tin|tub|jar|box|bar|block|loaf|bunch|roll|refill|pouch|sachet|tube)"
private val innerPieces = "(?:pieces?|pcs?)"
private val contentsPatterns = listOf(
    Regex("(?<![a-z0-9.,/+\\-])(?:1 +)?($singularContainer) +of +([1-9][0-9]{0,2}) *$innerPieces\\b", RegexOption.IGNORE_CASE),
    Regex("(?<![a-z0-9.,/+\\-])1 +($singularContainer) *\\( *([1-9][0-9]{0,2}) *$innerPieces *\\)", RegexOption.IGNORE_CASE),
)
private val reverseContentsPatterns = listOf(
    Regex("(?<![a-z0-9.,/+\\-])([1-9][0-9]{0,2}) *$innerPieces +per +(?:1 +)?($singularContainer)\\b", RegexOption.IGNORE_CASE),
    Regex("(?<![a-z0-9.,/+\\-])([1-9][0-9]{0,2}) *$innerPieces +in +(?:a|one|1) +($singularContainer)\\b", RegexOption.IGNORE_CASE),
)
private val barePieces = Regex("(?<![a-z0-9.,/+\\-])([1-9][0-9]{0,2}) *$innerPieces\\b", RegexOption.IGNORE_CASE)
private val oneOuter = Regex("(?<![a-z0-9.,/+\\-])1 +($singularContainer)\\b", RegexOption.IGNORE_CASE)
private val containerMention = Regex("\\b(?:tetra[ -]+packs?|packs?|packets?|bags?|bottles?|cartons?|cups?|cans?|tins?|tubs?|jars?|box(?:es)?|bars?|blocks?|loaf|loaves|bunch(?:es)?|rolls?|refills?|pouch(?:es)?|sachets?|tubes?|sprays?|matchbox(?:es)?)\\b", RegexOption.IGNORE_CASE)
private val unsupportedRelationship = Regex("[×+/÷=]|\\b(?:x|times|each|per|and|or|with|extra|bonus|additional|plus|sets?|bundles?|combos?|multipacks?|sleeves|trays|free|get|buy)\\b", RegexOption.IGNORE_CASE)
private val qualifiedRelationship = Regex(
    "[~<>≤≥≈±]|\\b(?:not|no|non|without|except|excluding|empty|replacement|compatible|fits?|supports?|" +
        "$GROCERY_UNCERTAIN_SCOPE_WORDS|instead|approx(?:imately)?|about|around|estimated|nearly|almost|" +
        "min(?:imum)?|max(?:imum)?|up\\s*to|at\\s+least|at\\s+most|less\\s+than|more\\s+than)\\b",
    RegexOption.IGNORE_CASE,
)
private fun containerKey(text: String) = text.lowercase(Locale.ROOT).replace(Regex("tetra[ -]+pack"), "tetra_pack")
private data class ContentsMatch(val range: IntRange, val contents: SwiggyCatalogueRetailContents)

private fun contentsMatches(text: String): List<ContentsMatch> =
    contentsPatterns.flatMap { pattern -> pattern.findAll(text).map { match ->
        ContentsMatch(match.range, SwiggyCatalogueRetailContents(containerKey(match.groupValues[1]), match.groupValues[2].toInt()))
    }.toList() } + reverseContentsPatterns.flatMap { pattern -> pattern.findAll(text).map { match ->
        ContentsMatch(match.range, SwiggyCatalogueRetailContents(containerKey(match.groupValues[2]), match.groupValues[1].toInt()))
    }.toList() }

private fun hasUnownedContentsConnector(text: String, owned: List<IntRange>): Boolean =
    Regex("\\b(?:of|in)\\b", RegexOption.IGNORE_CASE).findAll(text).any { connector ->
        owned.none { it.first <= connector.range.first && it.last >= connector.range.last }
    }

/** Deny conflicting or unsupported relations; never complete missing proof. */
internal fun swiggyCatalogueContainerRelationConflicts(
    candidate: RecommendationCandidate, container: String, literalIdentity: String,
): Boolean = swiggyRawCatalogueFields(candidate).withIndex().any { (index, field) ->
    val relations = contentsMatches(field)
    if (relations.any { it.contents.container != container }) return@any true
    val remainder = field.toCharArray()
    relations.forEach { relation -> relation.range.forEach { remainder[it] = ' ' } }
    val text = String(remainder)
    val owned = if (index == 0) Regex(
        "(?<![A-Za-z0-9_])${Regex.escape(literalIdentity)}(?![A-Za-z0-9_])", RegexOption.IGNORE_CASE,
    ).findAll(text).map { it.range }.toList() else emptyList()
    hasUnownedContentsConnector(text, owned)
}

/** Positive relationships must exist wholly within ONE provider-owned field.
 * Every other raw field may corroborate or veto, never complete half a proof.
 */
internal fun swiggyCatalogueContents(candidate: RecommendationCandidate, literalSourceIdentity: String? = null): SwiggyCatalogueContentsAnalysis {
    if (candidate.catalogueQuantityIssue() != null) return SwiggyCatalogueContentsAnalysis(issue = true)
    val metadata = candidate.catalogueQuantityFields() ?: return SwiggyCatalogueContentsAnalysis()
    val proofs = metadata.flatMap { (_, field) ->
        contentsMatches(field).filter { match ->
            // The quantity field must state the relationship, not mention it
            // inside a negation, comparison, accessory name or conditional.
            field.removeRange(match.range).trim(' ', '.', '(', ')').isEmpty()
        }
    }
    if (proofs.isEmpty()) return SwiggyCatalogueContentsAnalysis()
    val contents = proofs.map { it.contents }.distinct().singleOrNull()
        ?: return SwiggyCatalogueContentsAnalysis(issue = true)
    val fields = swiggyRawCatalogueFields(candidate)
    if (swiggyCatalogueHasUnresolvedBundle(fields)) return SwiggyCatalogueContentsAnalysis(issue = true)
    for ((index, field) in fields.withIndex()) {
        // Unsupported numeric spellings/units must not be removed as punctuation.
        if (field.any { it.code > 127 && it != '·' || it.isISOControl() }) return SwiggyCatalogueContentsAnalysis(issue = true)
        val matches = contentsMatches(field)
        if (matches.size > 1 || matches.any { it.contents != contents }) return SwiggyCatalogueContentsAnalysis(issue = true)
        var remainder = field
        if (matches.isNotEmpty()) remainder = remainder.replaceRange(matches.single().range, " ")
        val pieces = barePieces.findAll(remainder).toList()
        if (pieces.size > 1 || pieces.any { it.groupValues[1].toInt() != contents.innerPieces } ||
            matches.isNotEmpty() && pieces.isNotEmpty()) return SwiggyCatalogueContentsAnalysis(issue = true)
        remainder = barePieces.replace(remainder, " ")
        val outers = oneOuter.findAll(remainder).toList()
        if (outers.size > 1 || outers.any { containerKey(it.groupValues[1]) != contents.container } ||
            matches.isNotEmpty() && outers.isNotEmpty()) return SwiggyCatalogueContentsAnalysis(issue = true)
        remainder = oneOuter.replace(remainder, " ")
        // A source-grounded product nominal in the title is identity, not an
        // additional outer container. Metadata and explicit relationships
        // above are never masked; neither are numbers/qualifiers below.
        val identityPattern = literalSourceIdentity?.let { Regex(
            "(?<![A-Za-z0-9_])${Regex.escape(literalSourceIdentity)}(?![A-Za-z0-9_])", RegexOption.IGNORE_CASE,
        ) }
        val originalIdentity = if (index == 0) identityPattern?.findAll(field)?.toList()?.singleOrNull() else null
        // Bare-ness is checked before removing any quantity text. A title
        // such as "Tea Bags of 25 Pieces" must not lose its competing relation
        // when the generic piece count is removed from the remainder.
        val bareIdentity = originalIdentity != null && !Regex(
            "^[ \\t]*(?:[(:/×+0-9]|(?:of|per|in|each|x)\\b)", RegexOption.IGNORE_CASE,
        ).containsMatchIn(field.substring(originalIdentity.range.last + 1))
        val identityRanges = if (bareIdentity) requireNotNull(identityPattern).findAll(remainder)
            .map { it.range }.toList() else emptyList()
        // A connector surviving the complete relationship parser is not a
        // bare identity, on either side of it and regardless of punctuation.
        // Literal product nominals can own their own words (cream of wheat),
        // but cannot own a separate "- of 25 pieces" / "25 pieces in" clause.
        val unownedConnector = hasUnownedContentsConnector(remainder, identityRanges)
        if (groceryIntentContainsNumericAtom(remainder) || unsupportedRelationship.containsMatchIn(remainder) ||
            qualifiedRelationship.containsMatchIn(remainder) || unownedConnector ||
            containerMention.findAll(remainder).any { mention -> containerKey(mention.value) != contents.container &&
                identityRanges.none { it.first <= mention.range.first && it.last >= mention.range.last } }) {
            return SwiggyCatalogueContentsAnalysis(issue = true)
        }
    }
    return SwiggyCatalogueContentsAnalysis(contents)
}

/**
 * Read a source-piece count only from one complete provider-owned quantity
 * field. Raw display fields can corroborate that number or veto it, but never
 * establish it. This is intentionally separate from the V2 piece resolver.
 */
internal fun swiggyCatalogueSourcePieceCount(candidate: RecommendationCandidate, literalSourceIdentity: String? = null): Int? {
    if (candidate.catalogueQuantityIssue() != null) return null
    val metadata = candidate.catalogueQuantityFields() ?: return null
    val rawFields = swiggyRawCatalogueFields(candidate)
    if (swiggyCatalogueHasUnresolvedBundle(rawFields)) return null

    val directCounts = metadata.mapNotNull { (_, field) -> exactWholePieceCount(field) }
    val contents = swiggyCatalogueContents(candidate, literalSourceIdentity)
    if (contents.issue) return null
    val authoritative = (directCounts + listOfNotNull(contents.contents?.innerPieces)).distinct().singleOrNull() ?: return null

    // The contents analyzer performs field-by-field relation validation,
    // including unknown-number and qualified-claim rejection. For direct
    // measures, require every raw numeric atom to be that exact piece amount;
    // a title/SKU number can never be silently ignored as a product code.
    if (contents.contents == null && !rawFields.all {
            rawFieldConfirmsOnlyMeasure(it, GroceryPackMeasure.of(authoritative.toBigDecimal(), "pieces"), authoritative)
        }) return null
    if (swiggyCatalogueQuantityConflict(candidate)) return null
    return authoritative
}

private fun exactWholePieceCount(field: String): Int? {
    val match = groceryPackMeasurePattern.matchEntire(field.trim()) ?: return null
    val amountText = match.groups[1]?.value ?: return null
    val unit = match.groups[2]?.value ?: return null
    if (!amountText.matches(Regex("[1-9][0-9]{0,2}"))) return null
    val measure = runCatching { GroceryPackMeasure.of(amountText.toBigDecimal(), unit) }
        .getOrNull() ?: return null
    if (measure.dimension != GroceryPackDimension.PIECES || measure.baseValue.stripTrailingZeros().scale() > 0) return null
    return measure.baseValue.intValueExact().takeIf { it in 1..999 }
}

/** A generic source wrapper needs one complete SKU-bound physical measure.
 * Display fields may veto it, but cannot establish or complete the measure. */
internal fun swiggyCatalogueSourcePhysicalMeasure(candidate: RecommendationCandidate, singleNamedUnit: String? = null,
    defaultVariant: Boolean = false, acceptsIndependentOuter: ((String) -> Boolean)? = null): GroceryPackMeasure? =
    sourcePhysicalMeasure(candidate, singleNamedUnit, defaultVariant, acceptsIndependentOuter)

private val repeatedSalesName = Regex("""[\p{L}\p{M}]+(?:[ -][\p{L}\p{M}]+)*""")
private val metricContainer = Regex("(.+?)[ \\t]+($singularContainer)", RegexOption.IGNORE_CASE)
private val singularSellingContainer = Regex(singularContainer, RegexOption.IGNORE_CASE)
private val foodHeads = setOf("juice", "drink", "water", "seeds", "beans", "nuts")
private val foodModifiers = setOf("fresh", "plain", "toned", "skimmed", "pasteurised", "pasteurized",
    "rolled", "whole", "ground", "raw", "roasted", "salted", "unsalted", "unsweetened")

// A capacity stamped on a vessel is not a volume of groceries. A compound merchandising
// field is only net-content evidence for a positively recognised, complete contents noun.
// None of these modifiers is optional for identity or evidence of a dietary property.
private fun namesPackedContents(value: String, dimension: GroceryPackDimension): Boolean {
    if (dimension == GroceryPackDimension.WEIGHT && isDocumentedWeighedStaple(value)) return true
    val literal = value.lowercase(Locale.ROOT).split(' ')
    // Cut forms are not pieces-per-pack evidence; the caller accepts physical totals only.
    val words = if (literal.last() in groceryCutForms) literal.dropLast(1) else literal
    fun ingredient(word: String) = isBareGroceryIntentCommodityHead(word) || isLiteralProduceNoun(word) || isLiteralPlantIngredient(word)
    return words.isNotEmpty() && (ingredient(words.last()) || words.last() in foodHeads) &&
        words.dropLast(1).all { ingredient(it) || it in foodModifiers }
}

internal class AuditedSkuNetContents(val primaryTitle: String, val size: GroceryPackMeasure)

/** A complete sale name followed by a net measure, or a metric followed by an enclosing package.
 * Repeated projections of one seller field are not independent facts. The source must explicitly
 * name the whole edition; no inferred adjective, container count or dietary claim is granted. */
internal fun RecommendationCandidate.auditedVariantNetContents(proof: AuditedIntentProvenance): AuditedSkuNetContents? {
    if (proof.role != "physical_total" || proof.packaging != null || proof.packAtom != null) return null
    val raw = variant ?: return null
    val fieldsOfName = if (',' in raw) raw.split(',').takeIf { it.size == 2 } ?: return null else null
    val plain = groceryPackMeasurePattern.matches(raw)
    val measureThenOuter = metricContainer.matchEntire(raw)?.takeIf { isLiteralContentContainer(it.groupValues[2]) }
    val size = raw.takeIf { plain } ?: fieldsOfName?.last()?.trim() ?: measureThenOuter?.groupValues?.get(1) ?: return null
    val exact = groceryPackMeasurePattern.matchEntire(size) ?: return null
    if (catalogueIngredientsInvalid || swiggyCatalogueQuantityConflict(this)) return null
    val names = catalogueIngredients?.titlesFor(spinId, skuId)?.takeIf { it.isNotEmpty() } ?: return null
    val name = names.first()
    // "bag, 1 kg" and "1 kg bag" state the same net size, not the
    // number of bags. Only an entire singular enclosure can take this role;
    // a sales edition (including "empty bag") still has to match the source.
    val prefix = fieldsOfName?.first()?.trim()
    val outerThenMeasure = prefix != null && singularSellingContainer.matches(prefix) && isLiteralContentContainer(prefix)
    val edition = if (outerThenMeasure) name else prefix ?: name
    val measure = GroceryPackMeasure.of(exact.groupValues[1].toBigDecimal(), exact.groupValues[2])
    if (measure.dimension !in setOf(GroceryPackDimension.WEIGHT, GroceryPackDimension.VOLUME) ||
        measure.baseValue.signum() <= 0) return null
    if (!repeatedSalesName.matches(name) || !namesPackedContents(name, measure.dimension) || !repeatedSalesName.matches(edition) ||
        !namesPackedContents(edition, measure.dimension) || !proof.merchandise.matchesSourceName(edition) ||
        names.any { !it.equals(name, true) } || names.none { label == it || label == "$it · $raw" }) return null
    if (!edition.equals(name, true) && (!edition.endsWith(" $name", true) || catalogueBrand != null)) return null
    val quantity = catalogueQuantityFields() ?: return null
    // Instamart's direct variation can supply only quantityDescription. Only a
    // measured enclosure (not an edition, title or subtitle) may use this route.
    if (plain || (outerThenMeasure || measureThenOuter != null) && quantity.first().first == "quantityDescription") {
        if (!catalogueIngredients.agreesWithSizeProjection(spinId, skuId, raw, quantity)) return null
    } else if (quantity.none { (key, value) -> key in setOf("variant", "variantName") && value == raw } ||
        raw !in catalogueIngredients.variantsFor(spinId, skuId).orEmpty()) return null
    val fields = quantity.map { it.second } + catalogueIngredients.fieldsFor(spinId, skuId).orEmpty() + listOfNotNull(subtitle)
    if (fields.any { it != raw && it != size && it !in names } ||
        quantity.any { (key, value) -> key == "packSize" && value != size } ||
        swiggyCatalogueHasUnresolvedBundle(fields)) return null
    return AuditedSkuNetContents(name, measure)
}

// A condition/material on a seller's outer container is not its contents or size.
// Only discharge this complete, typed field when the bound primary titles also
// identify this singular selling form. All other fields retain their vetoes.
private val describedOuter = Regex("1[ \\t]+(?:sealed|glass|plastic|metal|paper|cardboard)[ \\t]+($singularContainer)", RegexOption.IGNORE_CASE)
internal fun swiggyAuditedDescribedOuterMeasure(candidate: RecommendationCandidate, container: String): GroceryPackMeasure? {
    val metadata = candidate.catalogueQuantityFields() ?: return null
    val description = metadata.singleOrNull { it.first == "quantityDescription" }?.second ?: return null
    val form = describedOuter.matchEntire(description.trim())?.groupValues?.get(1) ?: return null
    if (groceryIntentRetailContainerKey(form) != container || candidate.catalogueIngredientsInvalid ||
        swiggyCatalogueQuantityConflict(candidate) || swiggyCatalogueHasUnresolvedBundle(swiggyRawCatalogueFields(candidate))) return null
    val names = candidate.catalogueIngredients?.titlesFor(candidate.spinId, candidate.skuId) ?: return null
    val primary = candidate.variant?.let { candidate.label.removeSuffix(" · $it") } ?: candidate.label
    if (names.isEmpty() || names.any { !it.equals(primary, ignoreCase = true) } ||
        !primary.endsWith(" $form", ignoreCase = true) || containerMention.findAll(primary).count() != 1 ||
        unsupportedRelationship.containsMatchIn(primary) || qualifiedRelationship.containsMatchIn(primary) ||
        Regex("\\b(?:for|of|in)\\b", RegexOption.IGNORE_CASE).containsMatchIn(primary)) return null
    val sizes = metadata.filter { (k, v) -> k in setOf("variantName", "variant", "packSize") &&
        groceryPackMeasurePattern.matches(v.trim()) }.map { it.second }
    if (sizes.isEmpty()) return null
    // A second bound observation of this SKU may contradict the quantity
    // snapshot even with matching names and IDs. Unknown fields cannot vote it away.
    val observed = candidate.catalogueIngredients.fieldsFor(candidate.spinId, candidate.skuId) ?: return null
    if (observed.any { it !in sizes && it != description && !it.equals(primary, true) }) return null
    return sourcePhysicalMeasure(candidate, container, false, null, describedOuterProven = true)
}

private fun sourcePhysicalMeasure(candidate: RecommendationCandidate, singleNamedUnit: String?,
    defaultVariant: Boolean, acceptsIndependentOuter: ((String) -> Boolean)?, describedOuterProven: Boolean = false): GroceryPackMeasure? {
    candidate.ownedOuterAndNetSize()?.let { fact ->
        return fact.size.takeIf { (singleNamedUnit == null || singleNamedUnit == fact.outer) &&
            (acceptsIndependentOuter == null || acceptsIndependentOuter(fact.outer)) }
    }
    val metadata = candidate.catalogueQuantityFields() ?: return null
    val authoritative = metadata.filterNot { (_, field) ->
        singleNamedUnit != null && isSingleNamedUnitField(field, singleNamedUnit)
    }.mapNotNull { (_, field) ->
        groceryPackMeasurePattern.matchEntire(field.trim())?.let { match ->
            runCatching { GroceryPackMeasure.of(match.groupValues[1].toBigDecimal(), match.groupValues[2]) }.getOrNull()
        }
    }.distinct().singleOrNull() ?: return null
    if (authoritative.dimension !in setOf(GroceryPackDimension.WEIGHT, GroceryPackDimension.VOLUME) ||
        authoritative.baseValue.signum() <= 0) return null
    val fields = swiggyRawCatalogueFields(candidate, if (describedOuterProven) "quantityDescription" else null)
    val dietaryView = defaultVariant && fields.none { promotionContext.containsMatchIn(it) }
    fun compatible(field: String): Boolean =
        rawFieldConfirmsOnlyMeasure(field, authoritative, dietaryView = dietaryView) ||
            singleNamedUnit != null && namedUnitAndMeasureAgree(field, singleNamedUnit, authoritative)
    if (fields.all(::compatible)) return authoritative
    // A variation can state its selling unit and net size separately. Discharge only
    // the typed, singular outer count; title, variant and subtitle keep their veto.
    if (acceptsIndependentOuter == null || singleNamedUnit != null ||
        fields.any { promotionContext.containsMatchIn(it) } || swiggyCatalogueQuantityConflict(candidate)) return null
    val outer = metadata.singleOrNull { it.first == "quantityDescription" }?.second
        ?.let { singleOuterSellingUnit.matchEntire(it.trim())?.groupValues?.get(1) }
        ?.lowercase(Locale.ROOT)?.replace(Regex("[ -]+"), "_") ?: return null
    if ((outer !in measuredNamedForms && outer !in setOf("pack", "packet", "tetra_pack")) || !acceptsIndependentOuter(outer)) return null
    if (metadata.none { (key, value) -> key in setOf("packSize", "variantName", "variant") &&
            groceryPackMeasurePattern.matches(value.trim()) }) return null
    val remaining = listOfNotNull(candidate.label, candidate.variant, candidate.subtitle) +
        metadata.filterNot { it.first == "quantityDescription" }.map { it.second }
    return authoritative.takeIf { remaining.all { field -> compatible(field) &&
        containerMention.findAll(field).all { groceryIntentRetailContainerKey(it.value) == outer }
    } }
}

private val singleOuterSellingUnit = Regex("1 +([a-z]+(?:[ -]pack)?)", RegexOption.IGNORE_CASE)

// Only called AFTER the selected SKU independently declared a complete "1 <unit>"
// and a complete physical measure. Ignore that same count once in a display field;
// leave every other number, negation, bundle cue and possible conflicting size intact.
private val singleRetailMention = Regex("(?<![^ \\t])1[ \\t]+[A-Za-z]+(?=[ \\t]|$)")
private fun namedUnitAndMeasureAgree(field: String, container: String, expected: GroceryPackMeasure): Boolean {
    val claim = singleRetailMention.findAll(field).singleOrNull { isSingleNamedUnitField(it.value, container) } ?: return false
    val previous = field.substring(0, claim.range.first).trimEnd(' ', '\t').lastOrNull()
    if (previous != null && !previous.isLetter() && previous != '·') return false
    return rawFieldConfirmsOnlyMeasure(field.replaceRange(claim.range, " "), expected)
}
/** A seller's typed "1 bottle" proves a container count, never its physical contents. */
internal fun swiggyNamedRetailSizeConfirmed(item: ParsedItem, candidate: RecommendationCandidate): Boolean {
    val proof = item.intentQuantityProvenance ?: return true
    val container = proof.retailContainer ?: return true
    val size = proof.unitSize ?: return true
    return swiggyCatalogueHasSingleRetailUnit(candidate, container) &&
        swiggyCatalogueSourcePhysicalMeasure(candidate, container) == GroceryPackMeasure.of(size.value, size.unit)
}

/** Display-only legacy offers cannot supply the proof needed by a generic
 * wrapper. Proven offers retain their typed dimension and original SKU facts. */
internal sealed interface SwiggyRetailOfferContents {
    val display: String

    data class DisplayOnly(override val display: String) : SwiggyRetailOfferContents {
        override fun toString(): String = "SwiggyRetailOfferContents.DisplayOnly(<redacted>)"
    }

    data class Proven(
        val measure: GroceryPackMeasure,
        val catalogue: CatalogueQuantityEvidence,
        override val display: String,
    ) : SwiggyRetailOfferContents {
        override fun toString(): String = "SwiggyRetailOfferContents.Proven(<redacted>)"
    }

    data class ProvenComposite(
        val shape: CompositeRetailShape,
        val catalogue: CatalogueQuantityEvidence,
    ) : SwiggyRetailOfferContents {
        override val display: String get() = shape.display
        override fun toString(): String = "SwiggyRetailOfferContents.ProvenComposite(<redacted>)"
    }
}

/** A complete typed provider field owns BOTH the multiplier and inner size.
 * Other raw fields can veto, never complete a split proof or imply a total.
 * This does not prove a named retail container or source purchase quantity. */
internal fun swiggyCatalogueCompositeShape(candidate: RecommendationCandidate): SwiggyRetailOfferContents.ProvenComposite? {
    val catalogue = candidate.catalogueQuantity ?: return null
    val metadata = candidate.catalogueQuantityFields() ?: return null
    val shape = metadata.mapNotNull { (_, field) -> exactCompositeRetailShape(field) }.distinct().singleOrNull()
        ?: return null
    val remainders = mutableListOf<String>()
    for (field in swiggyRawCatalogueFields(candidate)) {
        if (field.any { it.code > 127 && it !in setOf('×', '·') || it.isISOControl() } ||
            qualifiedRelationship.containsMatchIn(field)) return null
        val matches = compositeRetailShapeMatches(field)
        if (matches.size > 1 || matches.any { it.shape != shape }) return null
        val match = matches.singleOrNull()
        if (match != null && field.substring(0, match.range.first).trimEnd().lastOrNull() in
            setOf('+', '-', '.', ',', '/', '~', '<', '>')) return null
        val remainder = match?.let { field.replaceRange(it.range, " ") } ?: field
        if (groceryIntentContainsNumericAtom(remainder) || unsupportedRelationship.containsMatchIn(remainder)) return null
        remainders += remainder
    }
    if (swiggyCatalogueHasUnresolvedBundle(remainders)) return null
    return SwiggyRetailOfferContents.ProvenComposite(shape, catalogue)
}

internal fun swiggyProvenRetailOfferContents(
    candidate: RecommendationCandidate, literalSourceIdentity: String?,
): SwiggyRetailOfferContents.Proven? {
    val catalogue = candidate.catalogueQuantity ?: return null
    if (candidate.catalogueQuantityIssue() != null ||
        swiggyCatalogueHasUnresolvedBundle(swiggyRawCatalogueFields(candidate)) ||
        swiggyCatalogueQuantityConflict(candidate)) return null
    val physical = swiggyCatalogueSourcePhysicalMeasure(candidate)
    val pieces = swiggyCatalogueSourcePieceCount(candidate, literalSourceIdentity)
    // Conflicting dimensions never become a default package choice.
    if ((physical == null) == (pieces == null)) return null
    val measure = physical ?: GroceryPackMeasure.of(requireNotNull(pieces).toBigDecimal(), "pieces")
    val unit = when (measure.dimension) {
        GroceryPackDimension.WEIGHT -> "g"
        GroceryPackDimension.VOLUME -> "ml"
        GroceryPackDimension.PIECES -> "pieces"
        else -> return null
    }
    val display = measure.baseValue.stripTrailingZeros().toPlainString() + " " + unit
    return SwiggyRetailOfferContents.Proven(measure, catalogue, display)
}

// Disambiguate only a recognized dietary adjective in the packaging-operator
// scan. No replacement in the raw number, quantity, identity or dietary checks.
private val dietaryFreeAdjective = Regex("\\b(sugar|gluten|lactose)[ \\t-]+free\\b", RegexOption.IGNORE_CASE)
private val promotionContext = Regex("\\b(?:gift|sample|trial|promo(?:tion)?|complimentary|buy|get|bonus|extra|offer|freebie)s?\\b", RegexOption.IGNORE_CASE)

private fun ambiguousRetailRelationship(field: String, dietaryView: Boolean): Boolean {
    val operatorText = if (dietaryView) dietaryFreeAdjective.replace(field) { it.groupValues[1] + "free" } else field
    return qualifiedRelationship.containsMatchIn(field) || unsupportedRelationship.containsMatchIn(operatorText) ||
        swiggyCatalogueHasUnresolvedBundle(listOf(field))
}

/** A net size cannot establish the contents of an offer that also advertises another item. */
internal fun swiggyOuterSizeTitleHasPromotionOrRelationship(title: String): Boolean =
    promotionContext.containsMatchIn(title) || ambiguousRetailRelationship(title, dietaryView = true)

private fun rawFieldConfirmsOnlyMeasure(field: String, expected: GroceryPackMeasure, wholePieceLexeme: Int? = null,
    dietaryView: Boolean = false): Boolean {
    if (field.any { it.code > 127 && it != '·' || it.isISOControl() } ||
        ambiguousRetailRelationship(field, dietaryView)) return false

    val measures = groceryPackMeasurePattern.findAll(field).toList()
    // Repeated amounts in one field are not corroboration; they may encode a
    // range or competing offer. Separate provider fields may corroborate.
    if (measures.size > 1) return false
    val remainder = field.toCharArray()
    measures.singleOrNull()?.let { match ->
        val amount = match.groups[1] ?: return false
        val unit = match.groups[2]?.value ?: return false
        val amountText = amount.value
        val measure = runCatching { GroceryPackMeasure.of(amountText.toBigDecimal(), unit) }.getOrNull() ?: return false
        if (wholePieceLexeme != null && amountText != wholePieceLexeme.toString()) return false
        if (measure.dimension != expected.dimension || measure.baseValue.compareTo(expected.baseValue) != 0) return false
        // Leave signs/range/decimal punctuation in place for the numeric scan.
        // Otherwise matching the positive suffix of "-6", "5-6", or "6.5"
        // could erase the evidence that makes the raw field ambiguous.
        val prefix = field.substring(0, amount.range.first)
        val previous = prefix.trimEnd().lastOrNull()
        // A comma after a complete word and before whitespace separates a
        // label from its typed measure; it is not a decimal/grouping comma.
        // Signs (including spaced hyphens) remain ambiguous. The unmasked
        // remainder must still contain no other numeric atom below.
        val labelComma = previous == ',' && prefix.count { it == ',' } == 1 &&
            Regex("[A-Za-z][ \\t]*,[ \\t]+$").containsMatchIn(prefix)
        if (previous in setOf('+', '-', '−', '~', '<', '>', '≤', '≥', '≈', '±', '.', ',') && !labelComma) return false
        for (index in match.range) remainder[index] = ' '
    }
    return !groceryIntentContainsNumericAtom(String(remainder))
}

/** Null leaves the existing conservative arithmetic in charge. Zero is a hold.
 * The only new conversion is a source-proven count of the SAME outer container.
 * Defaults are resolved independently by the shared final quantity resolver.
 * Logical pieces, corrections and physical totals are not widened.
 */
internal fun swiggyCatalogueRetailCartQuantity(item: ParsedItem, candidate: RecommendationCandidate): Int? {
    val literal = item.containerContentsLiteralIdentity()
    // A provider's exact contents relation proves quantity, not a shortened
    // product identity. Apply the same complete source nominal gate as offers.
    if (item.containerContentsProof != null && literal == null) return 0
    val analysis = swiggyCatalogueContents(candidate, literal)
    if (analysis.issue) return 0
    val contents = analysis.contents ?: return if (item.containerContentsProof != null) 0 else null
    if (!swiggyNamedRetailSizeConfirmed(item, candidate)) return 0
    if (item.containerContentsProof != null) {
        val source = item.validContainerContentsProof() ?: return 0
        if (source.container != contents.container || source.inner.value.compareTo(contents.innerPieces.toBigDecimal()) != 0 ||
            !isSwiggyCandidateAllowed(item, candidate)) return 0
        return (item.quantity as? Quantity.Count)?.n?.takeIf { it in 1..20 } ?: 0
    }
    val proof = item.intentQuantityProvenance ?: return 0
    if (!item.requiresIntentQuantityProof || !groceryIntentSourceRetailReceiptValid(proof) ||
        proof.retailContainer != contents.container || !isSwiggyCandidateAllowed(item, candidate)) return 0
    return (item.quantity as? Quantity.Count)?.n?.takeIf { it in 1..20 } ?: 0
}
