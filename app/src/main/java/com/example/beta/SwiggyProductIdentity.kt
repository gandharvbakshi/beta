package com.example.beta

import com.example.beta.automation.ParsedItem
import com.example.beta.automation.ProductLexicon
import java.util.Locale
import java.text.Normalizer

// Reject-only catalogue evidence; never positive identity aliases or proof of
// freshness. Keep the backend form classes and scope rules in parity.
private val preservationForms = mapOf(
    "dry" to "dried", "dried" to "dried", "dehydrated" to "dried", "frozen" to "frozen",
    "canned" to "canned", "tinned" to "canned", "powder" to "powdered", "powdered" to "powdered",
    "flakes" to "flakes", "instant" to "instant", "pickled" to "pickled",
)
private val formWrappers = setOf("version", "versions", "variant", "variants", "form", "forms", "kind", "kinds", "one", "ones")
private fun productFormWords(text: String): List<String> = Regex("[\\p{L}\\p{N}_]+")
    .findAll(Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)).map { it.value }.toList()

private fun excludedFormClass(phrase: String): String? {
    var words = productFormWords(phrase)
    if (words.firstOrNull() == "the") words = words.drop(1)
    if (words.lastOrNull() in formWrappers) words = words.dropLast(1)
    return words.singleOrNull()?.let(preservationForms::get)
}

internal fun swiggyProductFormsAllowed(requests: List<String>, exclusions: List<String>, fields: List<String>): Boolean {
    val offered = fields.flatMap(::productFormWords).mapNotNull(preservationForms::get).toSet()
    if (offered.intersect(exclusions.mapNotNull(::excludedFormClass).toSet()).isNotEmpty()) return false
    for (request in requests) {
        val words = productFormWords(request)
        val requested = words.mapNotNull(preservationForms::get).toSet()
        for ((index, word) in words.withIndex()) {
            if (word !in preservationForms && word != "fresh") continue
            val before = words.subList(maxOf(0, index - 2), index)
            if (before.lastOrNull() in setOf("not", "no", "non", "without", "never") ||
                (before.size == 2 && before[0] in setOf("not", "no", "without", "never") && before[1] in setOf("the", "a", "any")) ||
                words.getOrNull(index + 1) == "free") return false
        }
        // Evaluate each request independently: the weaker query/strict phrase
        // cannot discharge the other's explicit form constraint.
        if ("fresh" in words && (offered - requested).isNotEmpty()) return false
    }
    return true
}

/** Secondary fields cannot rescue a primary title's claim-only product match. */
internal fun swiggyClaimOnlyIdentityConflict(request: String, title: String): Boolean {
    val wanted = swiggySubjectIdentityTokens(request)
    val original = swiggyIdentityTokens(title)
    val subject = swiggySubjectIdentityTokens(title)
    fun supports(tokens: Set<String>, token: String) = tokens.any { it == token || swiggyIdentityNearToken(token, it) }
    return wanted.any { supports(original, it) && !supports(subject, it) }
}

private fun swiggySubjectIdentityTokens(text: String, fields: List<String> = listOf(text)): Set<String> {
    val normalizers: List<(String) -> String> = listOf(
        { it },
        { swiggyCanonicalIdentityText(it, preserveClaims = true) },
    )
    // Canonical phrase reordering may deny more, never rescue an operand that
    // the original wording negates (e.g. "without milk with less cream").
    val denied = normalizers.flatMap { normalize ->
        val views = fields.map { swiggyClaimIdentityView(normalize(it)) }
        val removed = views.flatMap { view -> view.operandTexts.flatMap { swiggyIdentityTokens(it) } }.toSet()
        val surviving = views.flatMap { swiggyIdentityTokens(it.subjectText) }.toSet()
        removed - surviving
    }.toSet()
    return swiggyIdentityTokens(text) - denied
}

// A positive mention of X as an ingredient/use of Y is not seller evidence that
// this SKU sells X. Independent fields may veto; never join unrelated words
// across boundaries or use this projection to grant identity/dietary claims.
private val sellerRelation = Regex("(?i)\\b(?:made[ \\t]+(?:with|from)|prepared[ \\t]+with|flavou?red[ \\t]+with|" +
    "for[ \\t]+(?:making|preparing|cooking|brewing)|containing|contains|with|ingredients?[ \\t]*:)\\s*\\b")
private val attributiveFlavour = Regex("(?i)\\bflavou?red\\b(?![ \\t]+with\\b)")
private val servingInstruction = Regex("(?i)^(?:serving[ \\t]+suggestions?|serve|serves|recipes?|try|how[ \\t]+to|directions|instructions)\\b")
private val inputForm = Regex("\\b(?:raw|uncooked|dry|plain|flattened|rolled|ground)\\b")
private val affirmativePreparedForm = Regex("\\b(?:ready[\\s-]+(?:made|to[\\s-]+(?:eat|cook|drink))|prepared|instant|premix)\\b")
private val compositeResultHead = Regex("\\b(?:meal|dish|mix|premix|salad|porridge|curry|batter|soup|sauce)\\s*$")
private val nonaffirmativeHead = Regex("\\b(?:not|never|without|non|if|for|use|used|recipe|serve|serving|make|making|prepare|preparing)\\b")
private fun sellerOperandOnly(item: ParsedItem, fields: List<String>): Boolean {
    fun literal(s: String)=Normalizer.normalize(s,Normalizer.Form.NFKC).lowercase(Locale.ROOT).trim()
    val texts=fields.map(::literal)
    if (texts.none { sellerRelation.containsMatchIn(it) || attributiveFlavour.containsMatchIn(it) || affirmativePreparedForm.containsMatchIn(it) }) return false
    fun roles(s:String):Pair<String,List<String>> {
        val r=sellerRelation.find(s)
        val head=if(r==null)s else s.substring(0,r.range.first)
        val operands=mutableListOf<String>()
        if(r!=null)operands+=s.substring(r.range.last+1)
        val f=attributiveFlavour.find(head)?:return head to operands
        val named=head.substring(f.range.last+1).trim(' ','-')
        val flavour=head.substring(0,f.range.first).trim(' ','-')
        if(named.isEmpty() || flavour.isEmpty())return head to operands
        operands+=flavour
        return named to operands
    }
    val requestedHeads=listOfNotNull(item.query,item.strictMatchPhrase,
        item.validNamedRetailReceipt()?.let{it.source.substring(it.identity)},item.containerContentsLiteralIdentity())
        .distinct().map { roles(literal(it)).first }
    val wants=requestedHeads.map(::swiggyIdentityTokens).filter(Set<String>::isNotEmpty).distinct()
    val explicitInput=requestedHeads.any { inputForm.containsMatchIn(it) && !compositeResultHead.containsMatchIn(it) &&
        !nonaffirmativeHead.containsMatchIn(it) }
    val primary=texts.firstOrNull().orEmpty().substringBefore('·').substringBefore('|')
    val primaryTokens=swiggyIdentityTokens(roles(primary).first)
    return texts.any { field -> field.split('·','|',';').any part@{ segment ->
        val s=segment.trim()
        val (claimed,mentioned)=roles(s)
        if(explicitInput && !nonaffirmativeHead.containsMatchIn(claimed) && !servingInstruction.containsMatchIn(s) &&
            compositeResultHead.containsMatchIn(claimed) && affirmativePreparedForm.containsMatchIn(claimed))return@part true
        if(mentioned.isEmpty())return@part false
        val owner=if(servingInstruction.containsMatchIn(s)) primaryTokens else swiggyIdentityTokens(claimed).ifEmpty{primaryTokens}
        owner.isNotEmpty() && mentioned.map(::swiggyIdentityTokens).any { operand ->
            wants.any{wanted->operand.containsAll(wanted) && !owner.containsAll(wanted)}
        }
    } }
}

// Named compounds consume their internal head for denial only. The shared fruit
// class does NOT make custard/sugar apple positive synonyms or search aliases.
private val namedCompoundFamilies = mapOf(
    "apple" to mapOf("custard" to "custard_apple", "sugar" to "custard_apple", "wood" to "wood_apple", "ice" to "ice_apple"),
    "potato" to mapOf("sweet" to "sweet_potato"),
)
private data class NamedCompoundFact(val family: String, val literal: String)
private val namedCompoundBoundary = "[\\p{L}\\p{M}\\p{N}_]"
private val namedCompoundHeadWords = mapOf("apple" to "apples?", "potato" to "potato(?:es)?")
private val namedCompoundHeads = namedCompoundHeadWords.mapValues { (_, head) ->
    Regex("(?<!$namedCompoundBoundary)$head(?!$namedCompoundBoundary)")
}
private val namedCompoundPatterns = namedCompoundFamilies.mapValues { (head, modifiers) -> modifiers.map { (modifier, family) ->
    Regex("(?<!$namedCompoundBoundary)$modifier(?:[ \\t]+|[ \\t]*-[ \\t]*)${namedCompoundHeadWords.getValue(head)}(?!$namedCompoundBoundary)") to
        NamedCompoundFact(family, "$modifier $head")
} }
private val namedCompoundAttachedPatterns = namedCompoundFamilies.mapValues { (head, modifiers) ->
    val modifier = modifiers.keys.joinToString("|") { Regex.escape(it) }
    Regex("(?<!$namedCompoundBoundary)${namedCompoundHeadWords.getValue(head)}(?:[ \\t]*\\([ \\t]*(?:$modifier)[ \\t]*\\)|[ \\t]*-[ \\t]*(?:$modifier)(?!$namedCompoundBoundary))")
}
private val namedCompoundNegativePrefix = Regex("\\b(?:not|no|without|never|non|excluding|except)[ \\t-]+(?:(?:a|an|the|any)[ \\t]+)?$")
private val namedCompoundFormat = Regex("\\p{Cf}")
private val namedCompoundWhitespace = Regex("[\\s\\p{Z}\\p{javaWhitespace}\\u0085]+")
private val namedCompoundRepeatedHyphens = Regex("-{2,}")

private fun namedCompoundIdentityText(value: String): String =
    ProductLexicon.canonicalizeAliasesPreservingText(Normalizer.normalize(value, Normalizer.Form.NFKC))
        .lowercase(Locale.ROOT).replace(Regex("[\u2010-\u2015]"), "-")

private fun namedCompoundLayoutShadow(value: String): String = namedCompoundIdentityText(value)
    .replace(namedCompoundFormat, "").replace(namedCompoundWhitespace, " ").replace(namedCompoundRepeatedHyphens, "-").trim()

private fun bareNamedModifierConflict(head: String, fields: List<String>): Boolean {
    val parts = fields.map(::namedCompoundLayoutShadow)
    // Joining owns negative-claim masks ONLY, never positive compound facts.
    val claimSubject = swiggyClaimIdentityView(parts.joinToString(" — ")).subjectText
    var offset = 0
    return parts.mapIndexed { index, text ->
        val positive = claimSubject.substring(offset, offset + text.length)
        offset += text.length + 3
        (index > 0 && text in namedCompoundFamilies.getValue(head) && positive == text) ||
            namedCompoundAttachedPatterns.getValue(head).findAll(text).any { positive.substring(it.range) == text.substring(it.range) }
    }.any { it }
}

private fun namedCompoundFacts(value: String): Map<String, Set<NamedCompoundFact>> {
    val text = namedCompoundIdentityText(value)
    val positive = swiggyClaimIdentityView(text).subjectText
    fun affirmative(range: IntRange) = positive.substring(range) == text.substring(range) &&
        !namedCompoundNegativePrefix.containsMatchIn(text.substring(0, range.first))
    return buildMap {
        namedCompoundFamilies.keys.forEach { head ->
            val facts = mutableSetOf<NamedCompoundFact>()
            val occupied = mutableListOf<IntRange>()
            namedCompoundPatterns.getValue(head).forEach { (pattern, fact) ->
                pattern.findAll(text).forEach { match ->
                    if (positive.substring(match.range) == text.substring(match.range)) {
                        // A negated complete compound cannot donate its head;
                        // a masked adjective claim is not a complete compound.
                        occupied += match.range
                        if (affirmative(match.range)) facts += fact
                    }
                }
            }
            namedCompoundHeads.getValue(head).findAll(text)
                .filter { match -> occupied.none { match.range.first in it } && affirmative(match.range) }
                .forEach { facts += NamedCompoundFact(head, head) }
            if (facts.isNotEmpty()) put(head, facts)
        }
    }
}

/** Bare/named commodity requests need primary identity. Other contexts retain
 * existing matching, but secondary fields never repair a commodity conflict. */
internal fun swiggyNamedCompoundIdentityAllowed(requests: List<String>, primary: String, secondary: List<String> = emptyList()): Boolean {
    val offered = namedCompoundFacts(primary)
    val extra = secondary.map(::namedCompoundFacts)
    val requested = requests.map(::namedCompoundFacts)
    val shadowRequests = requests.map { namedCompoundFacts(namedCompoundLayoutShadow(it)) }
    val fields = listOf(primary) + secondary
    val shadowFields = fields.map { namedCompoundFacts(namedCompoundLayoutShadow(it)) }
    val originalFields = listOf(offered) + extra
    val bare = requests.map { swiggyIdentityTokens(swiggyClaimIdentityView(namedCompoundIdentityText(it)).subjectText) }
    for (head in namedCompoundFamilies.keys) {
        if (requested.none { head in it } && shadowRequests.none { head in it }) continue
        if (requested.indices.any { requested[it][head] != shadowRequests[it][head] } ||
            originalFields.indices.any { originalFields[it][head] != shadowFields[it][head] }) return false
        val required = requested.mapNotNull { it[head] }
        if (required.isEmpty()) continue
        val families = required.flatMap { facts -> facts.map { it.family } }.toSet()
        val primaryFacts = offered[head].orEmpty()
        val requirePrimary = bare.any { it == setOf(head) } || required.any { facts -> facts.any { it.literal != head } }
        if (families.size != 1 || (requirePrimary || primaryFacts.isNotEmpty()) &&
            primaryFacts.map { it.family }.toSet() != families) return false
        if (required.any { facts -> facts.any { it.literal != head && it !in primaryFacts } }) return false
        if (extra.any { fieldFacts -> fieldFacts[head]?.map { it.family }?.toSet()?.let { it != families } == true }) return false
        if (families == setOf(head) && bare.any { it == setOf(head) } && bareNamedModifierConflict(head, fields)) return false
    }
    return true
}

/** Candidate text is evidence of identity, not just a bag of overlapping words. */
internal fun swiggyMatchesProductIdentity(item: ParsedItem, label: String, identityFields: List<String> = listOf(label),
    offer: SwiggyMcpClient.RecommendationCandidate? = null): Boolean {
    if (sellerOperandOnly(item,identityFields)) return false
    if (!swiggyNamedCompoundIdentityAllowed(listOf(item.query, item.strictMatchPhrase.orEmpty()),
            identityFields.firstOrNull() ?: label, identityFields.drop(1))) return false
    if (swiggyCatalogueHasUnresolvedBundle(listOf(label))) return false
    if (!swiggyProductFormsAllowed(listOf(item.query, item.strictMatchPhrase.orEmpty()), item.avoidPhrases, listOf(label))) return false
    if (!swiggyBatterySizeClaimsMatch(item.query, label) ||
        !swiggyBatterySizeClaimsMatch(item.strictMatchPhrase.orEmpty(), label)) return false
    if (!swiggyFromRelationMatches(item.query, label) ||
        !swiggyFromRelationMatches(item.strictMatchPhrase.orEmpty(), label)) return false
    if (!groceryIntentNumericDescriptorClaimsMatch(item.query, label) ||
        !groceryIntentNumericDescriptorClaimsMatch(item.strictMatchPhrase.orEmpty(), label)) return false
    if (!nutritionClaimsMatch(item.query, label) ||
        !nutritionClaimsMatch(item.strictMatchPhrase.orEmpty(), label)) return false
    if (listOf(item.query, item.strictMatchPhrase.orEmpty()).any {
            "organic" in swiggyIdentityTokens(it) &&
                (!swiggyHasAffirmativeOrganicClaim(it) || !swiggyHasAffirmativeOrganicClaim(label))
        }) return false
    val strict = !item.strictMatchPhrase.isNullOrBlank()
    if (strict && !swiggyStrictPackMatches(item.strictMatchPhrase.orEmpty(), label, offer)) return false
    val requested = item.strictMatchPhrase?.takeIf { it.isNotBlank() } ?: item.query
    // Pack evidence is not merchandise evidence. A newly recognised sale-unit
    // must not turn any item mentioning the noun into that product.
    val nominal = measuredNominal(requested)
    val paired = if (nominal != null) offer?.ownedOuterAndNetSize() else null
    val containerKey = item.auditedIntent()?.packaging
    val terminalForm = nominal?.let { text -> retailCountContainerPattern.findAll(text).lastOrNull {
        it.range.last == text.lastIndex && it.range.first > 0 && text[it.range.first - 1].isWhitespace()
    } }
    val pluralOuter = terminalForm != null && containerKey != null &&
        groceryIntentRetailContainerKey(terminalForm.value) == containerKey &&
        !terminalForm.value.equals(containerKey.replace('_', ' '), ignoreCase = true)
    if (offer != null && nominal != null && pluralOuter) {
        // The numbered selling form belongs to this exact variation, not
        // to a stemmed merchandise name or a borrowed sibling description.
        if (paired?.outer != containerKey || offer.catalogueIngredientsInvalid) return false
        val name = offer.label.removeSuffix(" · ${offer.variant}")
        val titles = offer.catalogueIngredients?.titlesFor(offer.spinId, offer.skuId).orEmpty()
        if (titles.isEmpty() || titles.any { !it.trim().equals(name, true) }) return false
        val subject = nominal.substring(0, requireNotNull(terminalForm).range.first).trimEnd()
        if (listOf(nominal, subject, "$subject ${requireNotNull(containerKey).replace('_', ' ')}")
                .none { wholeMeasuredTitleMatches(it, name, offer) }) return false
    } else if (offer != null && nominal != null && paired != null) {
        val name = offer.label.removeSuffix(" · ${offer.variant}")
        val container = containerKey?.replace('_', ' ')
        if (!wholeMeasuredTitleMatches(nominal, name, offer) && (container == null || paired.outer != container ||
                !nominal.endsWith(" $container", true) ||
                !wholeMeasuredTitleMatches(nominal.dropLast(container.length + 1), name, offer))) return false
    }
    if (nominal != null && isMeasuredGrainSubject(nominal)) {
        // Plain-size legacy offers have the same role risk. A grain in flour,
        // confectionery or an accessory is not the grain itself.
        val own = identityFields.firstOrNull().orEmpty().substringBefore(" · ").trim()
        val titleNoun = measuredNominal(own) ?: own
        if (!wholeMeasuredTitleMatches(nominal, titleNoun, offer)) return false
    }
    // Compare number only on a complete seller title; never stem a brand,
    // modifier, ingredient list or an arbitrary token within a longer product.
    val title = identityFields.firstOrNull().orEmpty().trim()
    val head = title.substringAfterLast(' ')
    val pluralHead = requested.substringAfterLast(' ')
    val numberEquivalent = requested.equals(title + "s", ignoreCase = true) &&
        isBareGroceryIntentCommodityHead(head) && isBareGroceryIntentCommodityHead(pluralHead) &&
        identityFields.drop(1).all { field ->
            field.isBlank() || (groceryPackMeasurePattern.matches(field.trim()) &&
                groceryPackMeasures(field).singleOrNull()?.dimension in setOf(GroceryPackDimension.WEIGHT, GroceryPackDimension.VOLUME))
        }
    val compared = if (numberEquivalent || exactSupportTitle(requested, title) &&
        exactSupportTitle(item.query, title) && (label == title || identityFields.drop(1).any {
            it.isNotBlank() && label == "$title · $it"
        })) title else requested
    val query = swiggyIdentityTokens(compared)
    val candidate = swiggyIdentityTokens(label)
    if (query.isEmpty()) return false
    // An ingredient mentioned only in an absence/no-addition claim is not the
    // purchased product. This projection can deny identity, never grant it.
    // Field-local evidence cannot grant a noun that the complete display
    // identifies only inside a split absence claim. Joining is denial-only.
    val subject = swiggySubjectIdentityTokens(label, identityFields).intersect(swiggySubjectIdentityTokens(label))
    val wanted = swiggySubjectIdentityTokens(compared)
    if ((wanted.intersect(candidate) - subject).isNotEmpty()) return false
    // Translating an ingredient must not make a derived drink, medicine or
    // personal-care product a match. Explicit compound requests stay eligible.
    if (query.any { it in translatedIngredientTokens } && ingredientDerivativeGroups.any { group ->
        candidate.any { it in group } && query.none { it in group }
    }) return false
    // A staple-only request must not match a prepared dish that merely contains
    // the same ingredient (e.g. potato -> aloo bhujia). Explicit dish requests
    // remain eligible; this deliberately is not a general product classifier.
    if (query.any { it in stapleIdentityTokens } && query.none { it in preparedDishHeadTokens } &&
        candidate.any { it in preparedDishHeadTokens }) return false
    // Form attributes are mandatory evidence, never fuzzy spelling matches.
    val required = query.intersect(setOf("tetrapack", "readytoeat", "readytocook", "readytodrink",
        "sugarfree", "noaddedsugar", "lowfat", "skimmed", "doubletoned"))
    if (!candidate.containsAll(required)) return false
    if ("lowfat" in required && "fullcream" in candidate) return false
    if (required.any { it in setOf("sugarfree", "noaddedsugar") } && swiggyHasSugarContradiction(label)) return false
    if (query == setOf("sugarfree") && candidate.none { it in setOf("sweetener", "sweeteners", "tablet", "tablets", "sachet", "sachets") }) return false
    if ("readytodrink" in required && candidate.any { it in setOf("leaf", "premix", "powder", "instant", "readytocook") }) return false
    if ("samosa" in query && candidate.any { it in setOf("masala", "pastry", "sheet", "sheets") }) return false
    if ("tetrapack" in query && candidate.any { it in setOf("pouch", "pouches", "bottle", "bottles") }) return false
    if ("readytoeat" in query && candidate.any { it in setOf("raw", "kernel", "kernels", "microwave", "readytocook", "frozen") }) return false
    if (query.any { it in setOf("batter", "dough") } && query.none { it in setOf("mix", "powder") } &&
        candidate.any { it in setOf("mix", "powder") }) return false
    if (candidate.containsAll(query)) return true
    if (strict || query.size > 4) return false
    val missing = (query - candidate).sorted()
    val available = subject.intersect(candidate) - query
    fun match(index: Int, unused: Set<String>): Boolean = index == missing.size ||
        unused.any { token ->
            swiggyIdentityNearToken(missing[index], token) && match(index + 1, unused - token)
        }
    return match(0, available)
}

private fun swiggyNormalizeBatterySizeForms(value: String): String {
    var normalized = Regex(
        "\\b(double|triple)(?:[ \\t]+|[ \\t]*-[ \\t]*)a([ \\t]+batter(?:y|ies))\\b", RegexOption.IGNORE_CASE,
    ).replace(value) { match ->
        (if (match.groupValues[1].equals("double", ignoreCase = true)) "aa" else "aaa") + match.groupValues[2]
    }
    if (Regex("\\bbatter(?:y|ies)\\b", RegexOption.IGNORE_CASE).containsMatchIn(normalized)) {
        normalized = Regex("(?<![\\p{L}\\p{N}_\\p{M}.+/\\-])([0-9]+(?:\\.[0-9]+)?)[ \\t]*(?:volts?|v)\\b", RegexOption.IGNORE_CASE)
            .replace(normalized) { match -> match.groupValues[1] + " v" }
    }
    return normalized
}

private fun swiggyBatterySizeClaimsMatch(request: String, label: String): Boolean {
    fun claims(text: String): Set<String> {
        val normalized = swiggyNormalizeBatterySizeForms(text).lowercase(Locale.ROOT)
        if (!Regex("\\bbatter(?:y|ies)\\b").containsMatchIn(normalized)) return emptySet()
        return Regex("\\b(?:aa|aaa|aaaa|c|d)\\b").findAll(normalized).map { it.value }.toSet()
    }
    val required = claims(request)
    if (required.isEmpty()) return true
    val qualifier = Regex("\\b(?:not|no|without|excluding|excluded|excludes?|may|might|compatible|compatibility|fits?|supports?)\\b|n['’]t\\b", RegexOption.IGNORE_CASE)
    if (qualifier.containsMatchIn(request) || qualifier.containsMatchIn(label)) return false
    val nonSize = Regex("\\bnon[\\s\\-\u2010-\u2015\u2212]*(?:aa|aaa|aaaa|c|d)\\b|\\bbatter(?:y|ies)[\\s\\-\u2010-\u2015]*(?:free|less)\\b", RegexOption.IGNORE_CASE)
    if (nonSize.containsMatchIn(swiggyNormalizeBatterySizeForms(request)) ||
        nonSize.containsMatchIn(swiggyNormalizeBatterySizeForms(label))) return false
    val accessories = setOf("holder", "holders", "charger", "chargers", "adapter", "adapters", "adaptor", "adaptors",
        "converter", "converters", "case", "cases", "cover", "covers", "tester", "testers")
    fun words(value: String) = Regex("\\b[a-z]+\\b").findAll(value.lowercase(Locale.ROOT)).map { it.value }.toSet()
    if ((words(label).intersect(accessories) - words(request)).isNotEmpty()) return false
    val mixed = listOf(
        "\\b(?:aa|aaa|aaaa|c|d)(?:[ \\t]+batter(?:y|ies))?[ \\t]*(?:and|or|plus|[/&+])[ \\t]*([a-z0-9]+)\\b",
        "\\b([a-z0-9]+)(?:[ \\t]+batter(?:y|ies))?[ \\t]*(?:and|or|plus|[/&+])[ \\t]*(?:aa|aaa|aaaa|c|d)\\b",
    )
    if (mixed.any { pattern -> Regex(pattern, RegexOption.IGNORE_CASE).findAll(swiggyNormalizeBatterySizeForms(label))
            .any { it.groupValues[1].lowercase(Locale.ROOT) !in required } }) return false
    return required == claims(label)
}

/** Preserve the two sides of an explicit relation before bag-of-words matching. */
private fun swiggyFromRelationMatches(request: String, label: String): Boolean {
    val marker = Regex("\\bfrom\\b", RegexOption.IGNORE_CASE)
    val requested = marker.findAll(request).toList()
    if (requested.isEmpty()) return true
    val offered = marker.findAll(label).toList()
    if (requested.size != 1 || offered.size != 1) return false
    val left = swiggyIdentityTokens(request.substring(0, requested.single().range.first))
    val right = swiggyIdentityTokenSequence(request.substring(requested.single().range.last + 1))
    val offeredLeft = swiggyIdentityTokens(label.substring(0, offered.single().range.first))
    val offeredRight = swiggyIdentityTokenSequence(label.substring(offered.single().range.last + 1))
    // This does not classify the right side as a brand, ingredient or origin.
    // Extra descriptors on the product side are fine; another right-hand
    // identity is not evidence for the requested relation. Never repair text.
    return left.isNotEmpty() && right.isNotEmpty() &&
        offeredLeft.containsAll(left) && offeredRight == right
}

private fun measuredNominal(text: String): String? {
    val match = groceryPackMeasurePattern.findAll(text).singleOrNull() ?: return null
    if (match.range.first != 0 && match.range.last != text.lastIndex) return null
    if (groceryPackMeasures(match.value).singleOrNull()?.dimension !in
        setOf(GroceryPackDimension.WEIGHT, GroceryPackDimension.VOLUME)) return null
    return text.removeRange(match.range).trim().takeIf(String::isNotEmpty)
}

/** Positive interpreted-offer identity. Search similarity stays discovery-only. A closed current-SKU
 * primary title must name the complete source merchandise; display/variant contradictions still veto. */
internal fun swiggyOwnsMerchandise(proof: AuditedIntentProvenance, offer: SwiggyMcpClient.RecommendationCandidate): Boolean {
    if (offer.catalogueIngredientsInvalid || offer.catalogueQuantityIssue() != null) return false
    val evidence = offer.catalogueIngredients ?: return false
    val titles = evidence.titlesFor(offer.spinId, offer.skuId)?.takeIf { it.isNotEmpty() } ?: return false
    val variants = evidence.variantsFor(offer.spinId, offer.skuId).orEmpty() + offer.catalogueQuantityFields().orEmpty()
        .filter { it.first == "variantName" || it.first == "variant" }.map { it.second }
    val selectedVariant = offer.variant
    val netContents = offer.auditedVariantNetContents(proof)
    if (selectedVariant != null && selectedVariant !in variants && netContents == null &&
        (proof.packaging == null || offer.ownedOuterAndNetSize()?.outer != proof.packaging)) return false
    val maker = offer.catalogueBrand?.let { b ->
        if (b.source != "search_variation" || b.spinId != offer.spinId || b.skuId != offer.skuId ||
            b.name.isBlank() || propertyHasUnprovedNegation(b.name)) return false
        b.name
    }
    val verifiedProperties = proof.sellerProperties()?.takeIf { offer.sellerAssessment?.matches(it, offer) == true }
        ?.requirements.orEmpty().map { it.text }
    fun qualifier(v: String) = verifiedProperties.any { it.equals(v, true) } || proof.predicates().any { it.exactSellerQualifier(v) }
    // Counted merchandise words (tablets, batteries, etc.) are not neutral units for a different subject.
    fun isAmount(v: String): Boolean {
        val unit = groceryPackMeasurePattern.matchEntire(v)?.groupValues?.get(2)?.lowercase(Locale.ROOT)
        if (unit != null) return unit in setOf("pc", "pcs", "piece", "pieces", "count", "pack", "packs") ||
            groceryPackMeasures(v).singleOrNull()?.dimension in setOf(GroceryPackDimension.WEIGHT, GroceryPackDimension.VOLUME)
        val sourceForm = proof.packaging
        val joined = parseSingleOuterNetSize(v)
        if (joined != null) return offer.ownedOuterAndNetSize() == joined &&
            (joined.outer == sourceForm || sourceForm == null && proof.packAtom != null)
        return sourceForm != null && v.startsWith("1 ") && groceryIntentRetailContainerKey(v.substring(2)) == sourceForm
    }
    fun nounOf(value: String) = proof.merchandise.matchesSourceName(value) ||
        proof.merchandise.merchantNoun.equals(value + "s", true) && isBareGroceryIntentCommodityHead(value.substringAfterLast(' ')) &&
        isBareGroceryIntentCommodityHead(proof.merchandise.merchantNoun.substringAfterLast(' '))
    fun literal(value: String): Boolean {
        if (nounOf(value)) return true
        if (verifiedProperties.isEmpty() && proof.predicates().isEmpty()) return false
        // Only split at a literal boundary. One side must be the entire source noun and the other a
        // separately bound property; words inside an accessory, recipe or different product cannot qualify.
        return value.indices.any { i -> value[i] == ' ' && (
            proof.merchandise.matchesSourceName(value.substring(i + 1)) && qualifier(value.substring(0, i)) ||
            proof.merchandise.matchesSourceName(value.substring(0, i)) && qualifier(value.substring(i + 1))) }
    }
    val usedEditions = mutableSetOf<String>()
    fun namesMerchandise(raw: String, variantField: Boolean = false): Boolean {
        var title = raw
        // This is the only recognized display join. Both components still have to prove their own role.
        selectedVariant?.let { title = title.removeSuffix(" · $it") }
        variants.filter(::isAmount).firstOrNull { title.endsWith(" $it", true) }?.let { title = title.dropLast(it.length + 1) }
        if (title.equals(maker, ignoreCase = true)) return false
        // This shortcut depends on the net-contents receipt verifying the whole
        // requested edition AND every same-SKU primary title, not just its size.
        if (!variantField && title == netContents?.primaryTitle) return true
        // A maker is not a product modifier: a brand called "Green" cannot prove the apples are green.
        val noun = if (maker != null && title.startsWith("$maker ", true)) title.drop(maker.length + 1) else title
        fun owned(value: String): Boolean {
            if (literal(value)) return true
            val form = value.substringAfterLast(' ', "")
            val key = groceryIntentRetailContainerKey(form) ?: return false
            val sourceForm = proof.packaging
            val fits = key == sourceForm || key in setOf("pack","packet") && (sourceForm in setOf("pack","packet") ||
                variantField && proof.role == "requested_pieces" && variants.any { isAmount(it) })
            return fits && literal(value.dropLast(form.length + 1))
        }
        if (owned(noun)) return true
        val edition = selectedVariant ?: return false
        if (variantField || edition.equals(maker, true) || edition.equals("standard", true) || isAmount(edition)) return false
        if (!literal("$edition $noun")) return false
        usedEditions.add(edition)
        return true
    }
    val measuredVariant = selectedVariant?.takeIf { netContents != null }
    if (!(titles + offer.label).all { namesMerchandise(it) } || !variants.all {
            it == measuredVariant || it in usedEditions || it.equals("standard", true) || isAmount(it) || qualifier(it) || namesMerchandise(it, variantField = true)
        }) return false
    // A receipt may carry a conflicting sales name only in subTitle/quantityDescription. Such a
    // secondary field can veto, never rescue, the independently checked primary and variant above.
    val secondary = evidence.fieldsFor(offer.spinId, offer.skuId).orEmpty() +
        offer.catalogueQuantityFields().orEmpty().map { it.second } + listOfNotNull(offer.subtitle)
    fun provedOuterDetail(value: String): Boolean {
        if (offer.catalogueQuantity?.fields?.quantityDescription != value) return false
        proof.packaging?.let { if (swiggyAuditedDescribedOuterMeasure(offer, it) != null) return true }
        if (proof.role != "physical_total" || !value.startsWith("1 ")) return false
        val outer = groceryIntentRetailContainerKey(value.substring(2)) ?: return false
        return swiggyCatalogueSourcePhysicalMeasure(offer, defaultVariant = true) { it == outer } != null
    }
    return secondary.distinct().all { value ->
        value in titles || value in variants || value == measuredVariant || isAmount(value) || qualifier(value) || namesMerchandise(value) || provedOuterDetail(value) ||
            neutralStorageNote.matches(value) || corroboratedSellerDetail.matchEntire(value)?.let { match ->
                val kind = match.groupValues[1].lowercase(Locale.ROOT)
                val fact = match.groupValues[2]
                if (kind == "brand" || kind == "manufacturer") fact.equals(maker, true)
                else isAmount(fact) && variants.any { it.equals(fact, true) }
            } == true
    }
}

private val corroboratedSellerDetail = Regex("(brand|manufacturer|net weight|net volume|net quantity|pack size):[ \\t]*(.+)", RegexOption.IGNORE_CASE)
// A complete storage instruction, not arbitrary prose after a "storage"/"recipe" heading.
private val neutralStorageNote = Regex("(?:(?:storage|storage instructions):[ \\t]*)?(?:keep|store) (?:refrigerated|in a (?:cool,? dry|dry|cool) place)\\.?", RegexOption.IGNORE_CASE)

private fun wholeMeasuredTitleMatches(nominal: String, title: String,
    offer: SwiggyMcpClient.RecommendationCandidate?): Boolean {
    if (title.equals(nominal, true)) return true
    val maker = offer?.catalogueBrand?.takeIf { it.source == "search_variation" &&
        it.spinId == offer.spinId && it.skuId == offer.skuId }?.name ?: return false
    if (!title.startsWith("$maker ", ignoreCase = true)) return false
    val sellerNoun = title.substring(maker.length + 1)
    val sourceNoun = if (nominal.startsWith("$maker ", ignoreCase = true)) nominal.substring(maker.length + 1) else nominal
    return ProductLexicon.sameQualifiedNoun(sourceNoun, sellerNoun)
}

internal fun swiggyStrictPackMatches(request: String, label: String,
    offer: SwiggyMcpClient.RecommendationCandidate? = null): Boolean {
    val measure = groceryPackMeasurePattern
    val required = groceryPackMeasures(request)
    // Generic pieces are a numeric dimension, not interchangeable contents.
    // A title can contain the requested noun and still explicitly advertise
    // a different measured form ("lozenges ... 24 tablets"). Preserve that
    // relation even when bag-of-words identity and the count both match.
    if (required.any { it.dimension == GroceryPackDimension.PIECES }) {
        val typedNouns = setOf("lozenge", "tablet", "capsule", "sachet", "pellet", "egg", "battery")
        val requestedNouns = swiggyIdentityTokens(request).intersect(typedNouns)
        val offeredNouns = measure.findAll(label).map { swiggyIdentityTokens(it.groupValues[2]) }
            .flatMap { it.asSequence() }.filter { it in typedNouns }.toSet()
        if (requestedNouns.isNotEmpty() && !requestedNouns.containsAll(offeredNouns)) return false
    }
    val multipack = Regex(
        "\\b(?:pack\\s+of\\s+(\\d+)|(\\d+)\\s*[x×](?=\\s*\\d))",
        RegexOption.IGNORE_CASE,
    )
    val suffixMultiplier = Regex("^\\s*[x×]\\s*(\\d+)\\b", RegexOption.IGNORE_CASE)
    fun multipliers(text: String): Set<Int?> {
        val physical = nutritionMeasureEvidence(text).physicalText
        val measures = measure.findAll(physical).toList()
        val measuredAmounts = measures.mapNotNull { it.groups[1]?.range }.toSet()
        // One numeric span cannot mean both measured inner contents and an
        // extra outer multiplier. Bare "pack of N" and explicit xN remain.
        val prefixes = multipack.findAll(physical).filterNot { it.groups[1]?.range in measuredAmounts }.map { match ->
            match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.toIntOrNull()
        }.toSet()
        // One canonical measure vocabulary owns suffixes too, including typed
        // contents. Do not maintain another unit list that can silently drift.
        val suffixes = measures.mapNotNull { suffixMultiplier.find(physical.substring(it.range.last + 1)) }
            .map { it.groupValues[1].toIntOrNull() }.toSet()
        return prefixes + suffixes
    }
    val offered = groceryPackMeasures(label)
    val matches = required.isEmpty() || offered == required || (required.size == 1 && offer != null &&
        label == swiggyCandidateLabel(offer) && offered == required + GroceryPackMeasure.of(java.math.BigDecimal.ONE, "pack") &&
        offer.ownedOuterAndNetSize()?.let { it.outer in setOf("pack", "packet") && it.size in required } == true)
    return matches &&
        (required.isEmpty() && multipliers(request).isEmpty() || multipliers(request) == multipliers(label))
}

internal fun swiggyIdentityTokens(value: String): Set<String> = swiggyIdentityTokenSequence(value).toSet()

// Apply the SAME existing noun aliases before identity or claim-role analysis.
// Otherwise a multiword operand can leave a synthetic product token behind.
private val swiggyProductNounAliases = listOf(
    Regex("\\bsesame\\s+paste\\b", RegexOption.IGNORE_CASE) to "tahini",
    Regex("\\bkeen\\s*waa?\\b", RegexOption.IGNORE_CASE) to "quinoa",
)
private fun swiggyCanonicalProductNouns(value: String): String =
    swiggyProductNounAliases.fold(value) { text, (pattern, canonical) -> pattern.replace(text, canonical) }

/** Share positive phrase interpretation with claim analysis. Claim operators
 * remain literal in the denial view; original fields still own dietary proof. */
private fun swiggyCanonicalIdentityText(value: String, preserveClaims: Boolean = false): String {
    var text = ProductLexicon.canonicalizeAliasesPreservingText(
        swiggyNormalizeBatterySizeForms(if (preserveClaims) value else swiggyNormalizeZeroPercentIngredients(value))).lowercase(Locale.ROOT)
        .replace(Regex("\\blean\\s+milk\\b|\\bmilk\\s+(?:with\\s+)?less\\s+cream\\b|\\bless\\s+cream\\s+milk\\b"), "low fat milk")
    if (Regex("\\b(?:tea|coffee)\\b").containsMatchIn(text)) {
        text = text.replace(Regex("\\bready[\\s-]*made\\b"), "ready to drink")
    }
    if (Regex("\\b(?:batter|dough)\\b").containsMatchIn(text)) {
        // Prepared ingredient, not an already cooked meal. Its named form still has
        // to match (and dry mixes are rejected above); do not infer ready-to-eat.
        text = text.replace(Regex("\\bready[\\s-]*made\\b"), " ")
    }
    // Exact provider shorthand, not a preferred brand or inferred UHT pack.
    // Keep this aligned with backend _normalize_product_identity_forms.
    if (Regex("\\s*amul\\s+taaza\\s+tetra(?:\\s*(?:[·|,-]\\s*)?\\d+(?:\\.\\d+)?\\s*(?:ml|l|ltr|litres?))?\\s*").matches(text)) {
        text = text.replace(Regex("\\btaaza\\b"), "taaza milk")
    }
    if (Regex("\\bmilk\\b").containsMatchIn(text)) {
        text = text.replace(Regex("\\btetra\\b(?![\\s-]*packs?\\b)"), "tetrapack")
    }
    text = swiggyCanonicalProductNouns(ProductLexicon.normalizeNumericProductIdentities(text))
    if (!preserveClaims) {
        text = text.replace(Regex("\\b(?:with\\s+)?(?:no|without)[\\s-]+added[\\s-]+sugar\\b"), "noaddedsugar")
            .replace(Regex("\\b(?:sugar[\\s-]*free|zero[\\s-]+sugar)\\b"), "sugarfree")
    }
    return text.replace(Regex("\\blow[\\s-]*fat\\b"), "lowfat")
        .replace(Regex("\\bdouble[\\s-]*toned\\b"), "doubletoned")
        .replace(Regex("\\bfull[\\s-]*cream\\b"), "fullcream")
        .replace(Regex("\\bready[\\s-]*to[\\s-]*drink\\b"), "readytodrink")
        .replace(Regex("\\b(?:tetra[\\s-]*packs?|tetrapaks?|tetrapacks?|tetta[\\s-]*packs?)\\b"), "tetrapack")
        .replace(Regex("\\bready[\\s-]*(?:made|to[\\s-]*eat)\\b"), "readytoeat")
        .replace(Regex("\\bready[\\s-]*to[\\s-]*cook\\b"), "readytocook")
}

private fun swiggyIdentityTokenSequence(value: String): List<String> {
    var text = swiggyCanonicalIdentityText(value)
    // Remove quantities, never bracketed flavours, model codes or identity words
    // following a measure. Exact pack arithmetic is checked separately.
    // Strip a complete measured amount before a bare pack multiplier: otherwise
    // "Pack of 3 Pieces" loses its form and leaves an orphan "pieces" token.
    text = text.replace(Regex("\\b\\d+(?:\\.\\d+)?\\s*[x×]\\s*(?=\\d)"), " ")
        .replace(Regex("\\b\\d+(?:\\.\\d+)?\\s*(?:kg|kgs|g|gm|gms|grams?|ml|l|ltr|lit(?:er|re)s?|pcs?|pieces?|count|packs?)\\b"), " ")
        .replace(Regex("\\bpack\\s+of\\s+\\d+\\b"), " ")
    val plurals = mapOf("eggs" to "egg", "bananas" to "banana", "tomatoes" to "tomato",
        "onions" to "onion", "potatoes" to "potato", "leaves" to "leaf", "samosas" to "samosa",
        "apples" to "apple", "oranges" to "orange", "carrots" to "carrot", "cucumbers" to "cucumber",
        "lemons" to "lemon", "pears" to "pear", "mangoes" to "mango", "guavas" to "guava",
        "kiwis" to "kiwi", "plums" to "plum", "peaches" to "peach", "grapes" to "grape",
        "cauliflowers" to "cauliflower", "cabbages" to "cabbage", "berries" to "berry",
        "cockroaches" to "cockroach", "mosquitoes" to "mosquito", "flies" to "fly", "ants" to "ant",
        "insects" to "insect", "spiders" to "spider", "termites" to "termite",
        "strawberries" to "strawberry", "blueberries" to "blueberry", "chillies" to "chilli",
        // Preserve retail form identity across catalogue singular/plural labels.
        // Counts and bundle divisibility are independently validated later.
        "cups" to "cup", "jars" to "jar", "bags" to "bag", "tins" to "tin",
        "tubs" to "tub", "pouches" to "pouch", "packets" to "packet", "sachets" to "sachet",
        "cans" to "can", "refills" to "refill", "bottles" to "bottle", "cartons" to "carton",
        "boxes" to "box", "bars" to "bar", "blocks" to "block", "tubes" to "tube",
        "loaves" to "loaf", "rolls" to "roll", "sprays" to "spray", "bunches" to "bunch",
        "matchboxes" to "matchbox",
        // These are contents identities, not interchangeable generic pieces.
        "lozenges" to "lozenge", "tablets" to "tablet", "capsules" to "capsule",
        "pellets" to "pellet", "batteries" to "battery")
    val tokens = groceryIdentityWords(text)
        .filter { it !in setOf("fresh", "and", "the", "of") }
        .map { plurals[it] ?: it }.toMutableList()
    if ("milk" in tokens && tokens.any { it in setOf("skimmed", "doubletoned") } && "lowfat" !in tokens) {
        val affirmative = swiggyClaimIdentityView(text).subjectText
        if (Regex("\\bmilk\\b").containsMatchIn(affirmative) &&
            Regex("\\b(?:skimmed|doubletoned)\\b").containsMatchIn(affirmative)) tokens += "lowfat"
    }
    // Bounded complete catalogue titles, not a blanket brand/category guess.
    if (Regex(
        "\\s*sugar[\\s-]*free\\s+(?:gold\\+?|green(?:\\s+stevia|\\s+100%\\s+natural\\s+made\\s+from\\s+stevia)?|natura(?:\\s+low\\s+calorie\\s+sweet(?:e)?ner)?)" +
            "(?:\\s*(?:[·|—-]\\s*)?\\d+(?:\\.\\d+)?\\s*(?:g|gm|kg|pcs?|pieces?|tablets?|pellets?))?\\s*",
        RegexOption.IGNORE_CASE,
    ).matches(value) && "sweetener" !in tokens) tokens += "sweetener"
    return tokens
}

// Keep these bounded kind exclusions aligned with backend rank_candidates.
private val translatedIngredientTokens = setOf("chilli", "fenugreek", "pepper", "orange", "tea")
private val ingredientDerivativeGroups = listOf(
    setOf("sauce", "sauces"), setOf("achaar", "pickle", "pickles"), setOf("juice", "juices"),
    setOf("syrup", "syrups"), setOf("tablet", "tablets"), setOf("capsule", "capsules"),
    setOf("shampoo", "shampoos"), setOf("soap", "soaps"),
    setOf("conditioner", "conditioners"), setOf("balm", "balms"),
)
private val stapleIdentityTokens = setOf("milk", "potato", "mango", "curd", "carrot", "onion")
private val preparedDishHeadTokens = setOf(
    "bhujia", "namkeen", "peda", "barfi", "ladoo", "laddoo", "mithai", "halwa",
    "vada", "bhalla", "chaat", "papad", "kachori", "tikki", "paratha", "achaar", "pickle", "pickles",
)

/** The complete unbranded merchandise phrase, never a fuzzy match or a stripped
 * leading token. Existing independently classified nouns only. */
internal fun swiggyKnownUnbrandedNoun(text: String): Boolean =
    ProductLexicon.completeIdentityNoun(text) != null ||
        groceryIntentHasStandaloneSnackIdentity(text, text, allowOtherWords = false) ||
        text.split(' ').all { word -> ProductLexicon.completeIdentityNoun(word) != null ||
            groceryIntentHasStandaloneSnackIdentity(word, word, allowOtherWords = false) ||
            word in stapleIdentityTokens || word in preparedDishHeadTokens || ingredientDerivativeGroups.any { word in it } }

internal fun swiggyHasSugarContradiction(label: String): Boolean {
    var text = label.replace(Regex("\\b(?:no|without)[\\s-]+added[\\s-]+sugar\\b", RegexOption.IGNORE_CASE), " ")
    val percentClaim = swiggyZeroPercentIngredientPattern("added sugar")
    if (percentClaim.containsMatchIn(label) && swiggyHasExplicitIngredientAbsence(label, "added sugar")) {
        text = percentClaim.replace(text, " ")
    }
    return Regex("\\b(?:with|contains|added)\\s+sugar\\b|\\bsweetened\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
}

/** Literal catalogue evidence, not independent certification of the product. */
private fun swiggyHasAffirmativeOrganicClaim(value: String): Boolean {
    val text = value.lowercase(Locale.ROOT)
    val words = text.replace(Regex("[^\\p{L}\\p{N}]+"), " ")
    if (!Regex("\\borganic\\b").containsMatchIn(words)) return false
    // A negated/qualified occurrence cannot supply a positive property. Check
    // the entire original label so a later contradiction cannot be hidden by
    // an earlier 'Organic' brand/title. Mirror _has_affirmative_organic_claim.
    if (Regex("\\b(?:non\\s*organic|inorganic)\\b|\\b(?:no|without|zero)(?:\\s+[\\p{L}\\p{N}]+){0,3}\\s+organic\\b|" +
            "\\borganic\\s+(?:free|style|like|type|alternative|substitute)\\b").containsMatchIn(words)) return false
    // Inspect original numeric tokens: splitting 100.0% would invent '0 organic'.
    if (Regex("(?<![\\p{L}\\p{N}_.+-])0(?:\\.0+)?[\\s-]+organic\\b").containsMatchIn(text)) return false
    if (Regex("(?<![\\p{L}\\p{N}_.])([+-]?[0-9]+(?:\\.[0-9]+)?)\\s*(?:%|percent)[\\s-]*organic\\b")
            .findAll(text).any { it.groupValues[1].toBigDecimal().compareTo(100.toBigDecimal()) != 0 }) return false
    return !Regex("\\b(?:not|never|cannot|isnt|arent|wasnt|werent|cant|uncertified|may|might|could|" +
        "possibly|almost|nearly|partly|partially|semi|mostly|largely|mainly|maybe|perhaps|potentially|apparently|supposedly|allegedly|sometimes|" +
        "alternatives?|substitutes?|substitutions?|or)\\b|n['’]t\\b").containsMatchIn(text)
}

private val exactIdentityWords = setOf(
    "milk", "silk", "rice", "salt", "sugar", "soap", "soda", "butter", "batter", "flour", "flower",
    "corn", "curd", "tea", "coffee", "salted", "unsalted", "sweetened", "unsweetened", "sugarfree",
    "free", "decaf", "regular", "taped", "pants", "adult", "baby", "almond", "coconut", "vanilla", "chocolate", "ginger", "finger",
    "lowfat", "skimmed", "doubletoned", "fullcream", "noaddedsugar", "readytodrink",
)

private fun swiggyIdentityNearToken(left: String, right: String): Boolean {
    // Keep this observed misspelling, without allowing real words such as
    // bitter -> butter or soup -> soap through the general fuzzy fallback.
    if (left == "buttr" && right == "butter") return true
    if (left in exactIdentityWords || right in exactIdentityWords || minOf(left.length, right.length) < 4 ||
        !left.all { it in 'a'..'z' } || !right.all { it in 'a'..'z' }) return false
    val limit = if (minOf(left.length, right.length) >= 7) 2 else 1
    if (kotlin.math.abs(left.length - right.length) > limit) return false
    var previous = IntArray(right.length + 1) { it }
    left.forEachIndexed { index, char ->
        val current = IntArray(right.length + 1)
        current[0] = index + 1
        right.forEachIndexed { j, other ->
            current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + if (char == other) 0 else 1)
        }
        previous = current
    }
    return previous.last() <= limit
}
