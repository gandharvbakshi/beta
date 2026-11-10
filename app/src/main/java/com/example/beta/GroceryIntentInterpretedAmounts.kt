package com.example.beta

import com.example.beta.automation.IntentLocalAmount
import com.example.beta.automation.ProductLexicon
import java.math.BigDecimal
import java.util.Locale

/** Purchase-subject authority for this original row. There is no constructor from a model name or verdict. */
internal class SourceMerchandise private constructor(val merchantNoun: String, private val originalRow: String,
    private val properties: List<SourceMerchandiseProperty>) {
    fun matchesSourceName(value: String): Boolean = ProductLexicon.sameQualifiedNoun(merchantNoun, value)
    internal fun belongsTo(source: String, ingredients: List<IngredientPredicate>, seller: SellerPropertyRequirement?) =
        source == originalRow && properties.all { it.enforced(ingredients, seller) }
    override fun toString() = "SourceMerchandise(<redacted>)"

    companion object {
        internal fun compile(instruction: String, primary: GroceryIntentReviewV2Quote, bodyEnd: Int,
            name: String, role: String, form: String?, quantity: IntentLocalAmount?, size: IntentLocalAmount?,
            inventory: GroceryIntentInterpretedAmounts, properties: List<SourceMerchandiseProperty>,
            declaredPropertySources: List<GroceryIntentReviewV2Quote> = emptyList(),
            seller: SellerPropertyRequirement? = null): SourceMerchandise? {
            if (primary.startUtf16 < 0 || primary.endUtf16 > instruction.length || primary.startUtf16 >= primary.endUtf16 ||
                instruction.substring(primary.startUtf16, primary.endUtf16) != primary.text || bodyEnd !in 1..primary.text.length ||
                (bodyEnd != primary.text.length && (primary.endUtf16 != instruction.length ||
                    affirmativeListEndStart(instruction) != primary.startUtf16 + bodyEnd))) return null
            val obligations = properties.map { it.rangeFor(primary) ?: return null }
            for (item in listOfNotNull(quantity, size)) {
                val bound = inventory.bind(item.value, item.unit) ?: return null
                if (item.source != primary.text || bound.value.compareTo(item.value) != 0 ||
                    bound.copy(value = item.value) != item) return null
            }
            val body = primary.text.substring(0, bodyEnd)
            var literal: String? = null
            val remember: (String) -> Unit = { literal = it }
            fun bind(noun: String) = SourceMerchandise(noun, primary.text, properties.toList())
            if (size != null && ScopedInterpretedCardinal.native(size.atom)) return null
            if (quantity != null && ScopedInterpretedCardinal.native(quantity.atom))
                return if (ScopedInterpretedCardinal.roleProven(body, name, role, form, quantity, size, remember, seller))
                    literal?.let(::bind) else null
            sourceDistributiveSize(body, name, form, quantity, size)?.let { return bind(it.merchandise) }
            for (decision in sequenceOf(
                { inventory.bareCountSubject(body, name, role, form, quantity, size, obligations, remember) },
                { sourceCountedRetailSubject(body, name, role, form, quantity, size, remember) },
                { terminalNamedRetailSizeRole(instruction, primary, name, role, form, quantity, size, remember) },
            )) decision()?.let { return if (it) literal?.let(::bind) else null }
            if (quantity != null && ScopedInterpretedCardinal.value(quantity.atom) != null &&
                ScopedInterpretedCardinal.roleProven(body, name, role, form, quantity, size, remember))
                literal?.let { return bind(it) }
            val piece = sourcePiecePurchaseReceipt(body, bareIdentity = name)
            if (piece != null && role == "requested_pieces" && size == null &&
                quantity?.startUtf16 == piece.amount.startUtf16 && quantity.endUtf16 == piece.amount.endUtf16) {
                val noun = body.substring(piece.identity)
                if (ProductLexicon.sameQualifiedNoun(noun, name)) return bind(noun)
            }
            // Remaining standard shapes: only source-bound amounts and independently enforced properties
            // may border a complete noun. An unexplained word is never a removable shopping stopword.
            if ('@' in body || '#' in body) return null
            val edits = mutableListOf<Pair<IntRange, String>>()
            for ((item, marker) in listOf(quantity to "@", size to "#")) if (item != null) {
                if (item.source != primary.text || item.endUtf16 > body.length) return null
                val end = if (item.unit in setOf("g", "kg", "ml", "l"))
                    GroceryIntentPhysicalUnitMatcher.after(body, item.endUtf16, item.unit)?.end ?: return null
                    else item.endUtf16
                edits += (item.startUtf16 until end) to marker
            }
            fun candidate(ranges: List<Pair<IntRange, String>>): SourceMerchandise? {
                val ordered = ranges.sortedByDescending { it.first.first }
                if (ordered.any { it.first.isEmpty() || it.first.first < 0 || it.first.last >= body.length } ||
                    ordered.zipWithNext().any { (a,b) -> b.first.last >= a.first.first }) return null
                var slots = body
                for ((span, replacement) in ordered) slots = slots.replaceRange(span, replacement)
                slots = slots.trim(' ', '\t', '.', '!', '?', '।').replace(horizontalGap, " ")
                val measuredRetail = if (role == "retail_units" && quantity?.unit in setOf("count", "pack") &&
                    size?.unit in setOf("g", "kg", "ml", "l")) {
                    val named = if (quantity?.value?.compareTo(BigDecimal.ONE) == 0)
                        packedMetric.matchEntire(slots) ?: basketCountAndMetric.matchEntire(slots) else null
                    named?.takeIf { form != null && groceryIntentRetailContainerKey(it.groups["form"]!!.value) == form }
                        ?.groups?.get("noun")?.value
                        ?: genericGenitivePack.matchEntire(slots)?.groupValues?.get(1)?.takeIf { form == null || form == "pack" }
                } else null
                val noun = measuredRetail ?: merchandiseCore.matchEntire(slots)?.groups?.get("noun")?.value ?: return null
                return if (ProductLexicon.sameQualifiedNoun(noun, name)) bind(noun) else null
            }
            fun complete(extra: List<Pair<IntRange, String>>) = candidate(edits + extra) ?:
                obligations.takeIf { it.isNotEmpty() }?.let { owned -> candidate(edits + extra + owned.map { it to " " }) }
            complete(emptyList())?.let { return it }
            // The model may quote the request verb with the item or leave it outside. Neither changes
            // the purchase. Remove only an independently recognized, original-start speech act; the
            // remaining original noun, amount, property ranges and whole-basket checks still apply.
            if (size == null && form == null && (role == "unstated" && quantity == null ||
                        role == "physical_total" && quantity?.unit in setOf("g", "kg", "ml", "l"))) {
                val edge = groceryIntentMarkedRequestEnd(instruction) ?: return null
                val local = edge - primary.startUtf16
                if (local in 1 until body.length && declaredPropertySources.none { it.startUtf16 < edge } &&
                    obligations.none { it.first < local } && (primary.startUtf16 == 0 ||
                            instruction[primary.startUtf16 - 1] == ' ' && body.first() != ' ')) {
                    return complete(listOf((0 until local) to " "))
                }
            }
            return null
        }
        // @ and # are positions, never free text. No general article/connector removal.
        private val merchandiseCore = Regex("""(?:(?:@(?: #)?|#) (?:of )?|@, (?:please|you know), )?(?<noun>[\p{L}\p{M}\p{N}]+(?:[ '’-][\p{L}\p{M}\p{N}]+)*?)(?: (?:@|#)| in # packs?)?""", RegexOption.IGNORE_CASE)
        private const val literalNoun = """[\p{L}\p{M}]+(?:[ '’-][\p{L}\p{M}]+)*"""
        private val packedMetric = Regex("""# (?<noun>$literalNoun) packed in @ (?<form>[a-z]+)""", RegexOption.IGNORE_CASE)
        // A basket icon only decorates a completely proven named retail order; other icons can be merchandise.
        private val basketCountAndMetric = Regex("""🧺 @ (?<form>[a-z]+) # (?<noun>$literalNoun)""", RegexOption.IGNORE_CASE)
        private val genericGenitivePack = Regex("""@ # ($literalNoun) का पैक""")
    }
}

/** Source-local roles, not new numeric values or permission to choose an offer. */
internal data class SourceDistributiveSize(val marker: IntRange, val markerNumber: IntRange, val terminalEnd: Int,
    val genericOuter: Boolean, val merchandise: String)

// A proposed, explicit rigid retail container can be shown as an offer; it
// must never be recorded as the shopper having named that particular form.
internal fun isHouseholdContainerOffer(form: String?) = form in setOf("box", "tin", "tub", "cup", "jar")

/** An unmeasured counted retail phrase must account for its entire merchandise noun.
 * Null means a different source construction; never grant retailer or packaging authority here. */
internal fun sourceCountedRetailSubject(body: String, subject: String, role: String, packaging: String?,
    count: IntentLocalAmount?, size: IntentLocalAmount?, onSourceNoun: (String) -> Unit = {}): Boolean? {
    if (count == null || count.unit !in setOf("count", "pack", "piece") ||
        count.endUtf16 > body.length || !count.source.startsWith(body)) return null
    val left = body.substring(0, count.startUtf16).trim(' ', '\t')
    val end = body.trimEnd(' ', '\t', '.', '!', '?').length
    if (count.endUtf16 >= end) return null
    val right = body.substring(count.endUtf16, end)
    val forms = retailCountContainerPattern.findAll(right).toList()
    if (forms.isEmpty()) return null
    val first = forms.first()
    val last = forms.last()
    fun matchesNoun(text: String, claimed: String) = (groceryIntentRetailNominalValid(text) && !sourceNominalHasRelation(text) &&
        ProductLexicon.sameQualifiedNoun(text, claimed)).also { if (it) onSourceNoun(text) }
    fun wholeNoun(text: String) = matchesNoun(text, subject)
    if (size != null) {
        if (size.unit !in setOf("g", "kg", "ml", "l") || count.endUtf16 >= size.startUtf16 ||
            size.endUtf16 > end || size.source != count.source) return null
        val physicalEnd = GroceryIntentPhysicalUnitMatcher.after(body, size.endUtf16, size.unit)?.end ?: return false
        val namedStart = count.endUtf16 + first.range.first
        val namedEnd = count.endUtf16 + first.range.last + 1
        val receipt = sourceRetailCountReceipt(body)
        val nominal = when {
            receipt != null -> {
                if (receipt.count.startUtf16 != count.startUtf16 || receipt.count.endUtf16 != count.endUtf16 ||
                    receipt.size?.startUtf16 != size.startUtf16 || receipt.size.endUtf16 != size.endUtf16 ||
                    receipt.qualifier != null || receipt.container != namedStart until namedEnd) return false
                body.substring(receipt.identity)
            }
            // Existing attributive roles: validate the complete merchandise, not just the adjective and unit.
            namedEnd == end && physicalEnd <= namedStart && horizontalGap.matches(body.substring(count.endUtf16, size.startUtf16)) -> {
                val inner = body.substring(physicalEnd, namedStart)
                if (left.isNotEmpty() && inner.isNotBlank()) return false
                if (left.isNotEmpty()) left else inner.trim(' ', '\t')
            }
            namedEnd <= size.startUtf16 && left.isEmpty() && horizontalGap.matches(body.substring(count.endUtf16, namedStart)) &&
                horizontalGap.matches(body.substring(namedEnd, size.startUtf16)) &&
                body.getOrNull(physicalEnd) in setOf(' ', '\t') -> body.substring(physicalEnd, end).trim(' ', '\t')
            else -> return null // detached/total/distributive/other receipt still owns its source-role check
        }
        val form = groceryIntentRetailContainerKey(first.value) ?: return false
        // An already accepted literal Unicode merchandise label is not a translation.
        // Keep its complete spelling/marks; no broadened alias or inferred named package.
        val literalLabel = nominal.any { it.code > 127 } && nominal == subject && literalRetailLabel.matches(nominal) &&
            !sourceNominalHasRelation(nominal) && !groceryIntentHasNominalClausePredicate(nominal) &&
            !groceryIntentUncertainScope.containsMatchIn(nominal)
        return role == "retail_units" && (packaging == form || form == "pack" && packaging == null) && (wholeNoun(nominal) ||
            literalLabel.also { if (it) onSourceNoun(nominal) } ||
            listOf(first.value, form).any { unit -> subject.endsWith(" $unit", true) &&
                matchesNoun(nominal, subject.dropLast(unit.length + 1)) })
    }
    val prefix = right.substring(0, first.range.first)
    val direct = prefix.isNotEmpty() && prefix.all { it in " \t" }
    val final = last.range.last + 1 == right.length
    fun literalObject() = role == "requested_pieces" && packaging == null && left.isEmpty() &&
        wholeNoun(right.trim(' ', '\t'))
    val named = if (direct) first else if (final && left.isEmpty()) last else return literalObject()
    val form = groceryIntentRetailContainerKey(named.value) ?: return false
    val tail = right.substring(named.range.last + 1)
    val nominal = when {
        left.isNotEmpty() && direct && tail.isEmpty() -> left.replace(purchaseGenitiveEnd, "")
        left.isNotEmpty() -> return false
        direct && terminalContentsOf.matches(tail) -> terminalContentsOf.matchEntire(tail)!!.groupValues[1]
        direct && tail.firstOrNull() in setOf(' ', '\t') -> {
            val noun = tail.trim(' ', '\t')
            // Omitted "of" cannot turn a bottle opener into a bottle of openers.
            if (role == "requested_pieces" || packaging == null) return literalObject()
            if (form !in setOf("pack", "packet") || ProductLexicon.completeIdentityNoun(noun) == null) return false
            noun
        }
        final && !direct -> right.substring(0, named.range.first).trim(' ', '\t')
        else -> return false
    }
    val sameForm = packaging == form || (form == "packet" && packaging == "pack")
    return role == "retail_units" && sameForm && (wholeNoun(nominal) ||
        (!direct && final && wholeNoun(right.trim(' ', '\t'))))
}

internal fun sourceDistributiveSize(source: String, subject: String, auditedOuter: String?,
    count: IntentLocalAmount?, size: IntentLocalAmount?): SourceDistributiveSize? {
    if (count == null || size == null || count.source != source || size.source != source ||
        count.unit !in setOf("pack", "count") || count.value !in BigDecimal(2)..BigDecimal(20) ||
        count.value.stripTrailingZeros().scale() > 0 || size.value.signum() <= 0 ||
        source.any(Char::isISOControl) || intentV2HasCorrectionCue(source)) return null
    val marker = groceryIntentDistributiveMarker.findAll(source).singleOrNull() ?: return null
    val markerNumber = marker.groups.drop(1).filterNotNull().single().range
    val unit = GroceryIntentPhysicalUnitMatcher.after(source, size.endUtf16, size.unit) ?: return null
    val comma = source.indexOf(',')
    if (comma <= count.endUtf16 || comma != source.lastIndexOf(',') || minOf(size.startUtf16, marker.range.first) <= comma) return null
    val physical = size.startUtf16 until unit.end
    val (left, right) = listOf(marker.range, physical).sortedBy { it.first }
    fun blanks(text: String) = text.all { it == ' ' || it == '\t' }
    if (left.last >= right.first || !blanks(source.substring(comma + 1, left.first)) ||
        !blanks(source.substring(left.last + 1, right.first)) ||
        !Regex("[ \\t]*[.!;]?[ \\t]*").matches(source.substring(right.last + 1))) return null
    val head = source.substring(0, comma)
    if ((count.startUtf16 > 0 && !head[count.startUtf16 - 1].isWhitespace()) ||
        !head[count.endUtf16].isWhitespace()) return null
    val prefix = head.substring(0, count.startUtf16).trim()
    val tail = head.substring(count.endUtf16).trim()
    val (noun, form) = if (prefix.isNotEmpty()) {
        // Product-first: the complete nominal owns the adjacent purchase count;
        // a recipient, an extra number or another container may not hide here.
        prefix.replace(purchaseGenitiveEnd, "") to tail
    } else {
        val receipt = sourceNamedRetailCountReceipt(head) ?: return null
        if (receipt.count.startUtf16 != count.startUtf16 || receipt.count.endUtf16 != count.endUtf16 ||
            receipt.qualifier != null || receipt.size != null) return null
        head.substring(receipt.identity) to head.substring(receipt.container)
    }
    if (!groceryIntentRetailNominalValid(noun) || sourceHasGenericContainer(noun) ||
        retailCountContainerPattern.containsMatchIn(noun)) return null
    val outer = groceryIntentRetailContainerKey(form)
    val generic = form.lowercase(Locale.ROOT) in groceryIntentGenericContainerWords
    if (!generic && (outer == null || outer != auditedOuter)) return null
    if (generic && auditedOuter != null && !isHouseholdContainerOffer(auditedOuter)) return null
    if (!ProductLexicon.sameQualifiedNoun(noun, subject) && (outer == null || !subject.endsWith(" $outer", true) ||
            !ProductLexicon.sameQualifiedNoun(noun, subject.dropLast(outer.length + 1)))) return null
    return SourceDistributiveSize(marker.range, markerNumber, right.last + 1, generic, noun)
}

/** Per-outer size only when the shopper's complete source construction
 * binds the subject, selling form, count and terminal measure. Null = outside
 * this family; false = a claimed relationship which may not fall back to model
 * agreement. Call after the separate, independently proved each-one receipt. */
internal fun terminalNamedRetailSizeRole(instruction: String, primary: GroceryIntentReviewV2Quote, subject: String,
    role: String, packaging: String?, count: IntentLocalAmount?, size: IntentLocalAmount?, onSourceNoun: (String) -> Unit = {}): Boolean? {
    if (count == null || size == null) return null
    // Native retail syntax is compiled by the same scoped source witness as its number, never by this legacy shape.
    if (ScopedInterpretedCardinal.native(count.atom)) return null
    val source = primary.text
    if (count.source != source || size.source != source) return false
    val (first, last) = listOf(count, size).sortedBy { it.startUtf16 }
    if (first.endUtf16 >= last.startUtf16) return false
    val forms = retailCountContainerPattern.findAll(source).toList()
    if (forms.isEmpty()) return null
    val physicalUnits = setOf("g", "kg", "ml", "l")
    if (count.unit !in setOf("pack", "count", "piece") || size.unit !in physicalUnits) {
        val comma = source.indexOf(',', first.endUtf16)
        return if (size === first && size.unit in setOf("pack", "count", "piece") && count.unit in physicalUnits &&
            comma in first.endUtf16 until last.startUtf16 && forms.any { it.range.first < comma }) false else null
    }
    val plural = count.value > BigDecimal.ONE
    val physical = GroceryIntentPhysicalUnitMatcher.after(source, size.endUtf16, size.unit) ?: return false
    // "<size>, two bottles" cannot tell us whether size is shared or per
    // bottle. Preserve existing singular measured constructions independently.
    if (size === first) return if (plural) false else null
    val beforeSize = source.substring(0, size.startUtf16)
    val headWithSeparator = beforeSize.trimEnd(' ', '\t')
    if (forms.none { it.range.first < size.startUtf16 }) {
        // "two 200g blocks of paneer" and "two 200g milk cartons": the
        // measure directly modifies the named selling unit; keep existing
        // independent validators in charge. Nothing may be discarded.
        if (!plural) return null
        val outer = forms.first()
        if (physical.end > outer.range.first || !horizontalGap.matches(source.substring(count.endUtf16, size.startUtf16))) return false
        val between = source.substring(physical.end, outer.range.first)
        return if (horizontalGap.matches(between) || ScopedInterpretedCardinal.completeNounMatches(between.trim(' ', '\t'), subject)) null else false
    }
    // Do not change singular legacy constructions like "1 bottle 1 litre milk".
    if (!plural && !headWithSeparator.endsWith(',')) return null
    if (role != "retail_units" || count.unit == "piece" ||
        count.value.stripTrailingZeros().scale() > 0 || count.value !in BigDecimal.ONE..BigDecimal(20) ||
        size.value.signum() <= 0 || forms.count { it.range.first < size.startUtf16 } != 1 ||
        source.any { it.isISOControl() && it != '\t' } ||
        !groceryIntentSourceClauseContextProven(instruction, source, source.length)) return false
    val head = headWithSeparator.removeSuffix(",").trimEnd(' ', '\t')
    val outer = retailCountContainerPattern.find(head, count.endUtf16) ?: return false
    val form = groceryIntentRetailContainerKey(outer.value) ?: return false
    if (form != packaging) return false
    val middle = head.substring(count.endUtf16, outer.range.first)
    val left = head.substring(0, count.startUtf16)
    val right = head.substring(outer.range.last + 1)
    val nominal = when {
        left.all { it in " \t" } && horizontalGap.matches(middle) -> terminalContentsOf.matchEntire(right)?.groupValues?.get(1) ?: return false
        left.all { it in " \t" } && right.isEmpty() -> middle.trim(' ', '\t')
        right.isEmpty() && horizontalGap.matches(middle) && left.lastOrNull() in setOf(' ', '\t') -> left.trim(' ', '\t')
        else -> return false
    }
    val names = listOf(subject) + listOf(form.replace('_', ' '), outer.value).mapNotNull { unit ->
        subject.takeIf { it.endsWith(" $unit", ignoreCase = true) }?.dropLast(unit.length + 1)
    }
    if (names.none { ScopedInterpretedCardinal.completeNounMatches(nominal, it) } ||
        sourceRetailCompositionIssue(source, count, size) != null) return false
    if (!source.substring(size.endUtf16, physical.start).all { it in " \t" }) return false
    val tail = source.substring(physical.end).trimEnd(' ', '\t').removeSuffix(".").trimEnd(' ', '\t')
    if (tail.isEmpty()) return (count.value.compareTo(BigDecimal.ONE) == 0).also { if (it) onSourceNoun(nominal) }
    if (tail.first() !in " \t") return false
    val marker = tail.trim(' ', '\t').lowercase(Locale.ROOT).replace(horizontalGap, " ")
    return (marker == "each" || marker == "per " + form.replace('_', ' ')).also { if (it) onSourceNoun(nominal) }
}

private val purchaseGenitiveEnd = Regex("[ \\t]+(?:ka|ke|ki)$", RegexOption.IGNORE_CASE)
private val literalRetailLabel = Regex("""[\p{L}][\p{L}\p{M}]*(?:[ '\t-]+[\p{L}][\p{L}\p{M}]*)*""")
private val horizontalGap = Regex("[ \\t]+")
private val terminalContentsOf = Regex("[ \\t]+of[ \\t]+([^,]+?)[ \\t]*", RegexOption.IGNORE_CASE)

/** Source-only occurrence inventory for INTERPRETED rows, never retail-role proof.
 * Maximal amounts are found before a model value can select a smaller subspan.
 * Legacy quantity and sourceQuotedLocalAmount callers remain unchanged.
 */
internal class GroceryIntentInterpretedAmounts(private val source: String) {
    private data class Atom(val start: Int, val end: Int, val text: String, val value: BigDecimal, val unit: String)
    private val physical = setOf("g", "kg", "ml", "l")
    private val atoms: List<Atom>
    private val eligible: Set<Pair<Int, Int>>

    init {
        require(source.length <= 640) // 320 Unicode code points on the host.
        val ranges = (unicodeEvidenceRegex("[\\p{L}\\p{N}\\p{M}_]+").findAll(source)
            .filter { it.value.lowercase(Locale.ROOT) in GROUP_NUMERIC_WORDS || ScopedInterpretedCardinal.value(it.value) != null }.map { it.range }.toList() +
            unicodeEvidenceRegex("\\p{Nd}+(?:[.,/]\\p{Nd}+)*|[½¼¾]").findAll(source).map { it.range }.toList()).distinct()
        val parsed = ranges.flatMap { left -> ranges.mapNotNull { right ->
            val start = left.first
            val end = right.last + 1
            if (end <= start || end - start > 64) return@mapNotNull null
            val text = source.substring(start, end)
            val scoped = ScopedInterpretedCardinal.value(text)
            val value = scoped ?: parseIntentCorrectionAmount(text) ?: return@mapNotNull null
            val unit = if (scoped != null) {
                if (ScopedInterpretedCardinal.native(text) &&
                    !ScopedInterpretedCardinal.nativeOccurrenceBound(source,start,end,text)) return@mapNotNull null
                physical.singleOrNull { ScopedInterpretedCardinal.metricEnd(source, end, it) != null }
                    ?: "count".takeIf { ScopedInterpretedCardinal.retail(source, end, text) != null }
                    ?: return@mapNotNull null
            } else physical.singleOrNull { GroceryIntentPhysicalUnitMatcher.after(source, end, it) != null } ?: "count"
            Atom(start, end, text, value, unit)
        } }.distinctBy { it.start to it.end }
        val maximal = parsed.filter { atom -> parsed.none { other ->
            other != atom && other.start <= atom.start && atom.end <= other.end
        } }
        val identities = ProductLexicon.numericProductIdentities(source).map { it.amountRange }
        atoms = maximal
        eligible = maximal.filter { atom ->
            val preceding = maximal.singleOrNull { it.end < atom.start && source.substring(it.end, atom.start).isBlank() }
            val separateCount = preceding?.let { wordCountBeforeMeasure(it, atom) } == true
            // Eligibility validates this code-enumerated occurrence. Ownership
            // is the single value+dimension match in bind(), not literal-text
            // uniqueness: "1 bottle 1 litre" has two different dimensions.
            validBoundary(atom) && identities.none { atom.start <= it.last && it.first < atom.end } &&
                !partialExpression(atom, separateCount)
        }.map { it.start to it.end }.toSet()
    }

    fun bind(value: BigDecimal, unit: String): IntentLocalAmount? {
        fun measure(number: BigDecimal, u: String): Pair<String, BigDecimal> = when (u) {
            "kg" -> "mass" to number.movePointRight(3)
            "g" -> "mass" to number
            "l" -> "volume" to number.movePointRight(3)
            "ml" -> "volume" to number
            "count", "pack", "piece" -> "discrete" to number
            else -> error("Invalid interpreted amount unit")
        }
        val target = measure(value, unit)
        val atom = atoms.singleOrNull {
            val measured = measure(it.value, it.unit)
            measured.first == target.first && measured.second.compareTo(target.second) == 0
        } ?: return null
        // Invalid contextual competitors cannot disappear and manufacture
        // unique ownership for a different equivalent amount occurrence.
        if (atom.start to atom.end !in eligible) return null
        return IntentLocalAmount(source, atom.start, atom.end, atom.text, atom.value,
            if (atom.unit == "count") unit else atom.unit)
    }

    /** A single ordinary count constrains the complete purchase subject, not how the seller sells it.
     * Decide applicability from the source inventory even if both readers put the count in another slot. */
    fun bareCountSubject(body: String, name: String, role: String, form: String?, quantity: IntentLocalAmount?,
                         size: IntentLocalAmount?, obligations: List<IntRange>, onSourceNoun: (String) -> Unit = {}): Boolean? {
        val atom = atoms.singleOrNull()?.takeIf { it.unit == "count" && it.value in BigDecimal.ONE..BigDecimal(20) &&
            it.value.stripTrailingZeros().scale() <= 0 && it.start to it.end in eligible } ?: return null
        if (atom.end > body.length || retailCountContainerPattern.find(body, atom.end) != null) return null
        val plain = body.trimEnd(' ', '\t', '.', '!', '?')
        if (atom.end > plain.length) return false
        if (quantity?.startUtf16 != atom.start || quantity.endUtf16 != atom.end || size != null ||
            role !in setOf("retail_units", "requested_pieces") || form != null) return false
        val nominal = sourcePiecePurchaseReceipt(plain)?.let { receipt ->
            if (receipt.amount.startUtf16 != atom.start || receipt.amount.endUtf16 != atom.end) return false
            receipt.identity
        } ?: run {
            val left = plain.substring(0, atom.start)
            val right = plain.substring(atom.end)
            when {
                left.isBlank() && right.firstOrNull() in setOf(' ', '\t') -> atom.end until plain.length
                (right.isBlank() || countCourtesy.matches(right.trim(' ', '\t'))) &&
                    left.lastOrNull() in setOf(' ', '\t') -> 0 until atom.start
                else -> return null // the original boundary/role guards still apply to a different construction
            }
        }
        var full = plain.substring(nominal).trim(' ', '\t')
        if (!ProductLexicon.sameQualifiedNoun(full, name) && obligations.isNotEmpty()) {
            // Only a separately enforced, exact property span is owned outside the search noun.
            val sourceWords = plain.substring(nominal).toCharArray()
            for (span in obligations) {
                if (span.first < nominal.first || span.last > nominal.last) return false
                sourceWords.fill(' ', span.first - nominal.first, span.last - nominal.first + 1)
            }
            full = String(sourceWords).trim(' ', '\t').replace(horizontalGap, " ")
        }
        return ScopedInterpretedCardinal.completeNounMatches(full, name).also { if (it) onSourceNoun(full) }
    }

    /** The two models can agree on the same cropped quote. Validate its edges
     * against the shopper's original words, not against either model's summary. */
    fun coversOriginalAmount(original: String, rowStart: Int, local: IntentLocalAmount): Boolean {
        val rowEnd = rowStart + source.length
        if (rowStart == 0 && rowEnd == original.length) return true
        if (ScopedInterpretedCardinal.native(local.atom) && rowStart > 0) {
            // The second word of a compound (or conditional) is not a new clause. An
            // English list conjunction still needs the ordinary independent multi-row scope check.
            val lead = original.substring(0, rowStart).trimEnd(' ', '\t')
            if (lead.isNotEmpty() && lead.last() !in ",;.!?\n" && !lead.endsWith(" and", true)) return false
        }
        val start = rowStart + local.startUtf16
        val end = rowStart + local.endUtf16
        fun word(cp: Int) = cp == '_'.code || Character.isLetterOrDigit(cp) || Character.getType(cp) in markCategories
        if (start == rowStart && start > 0 && word(original.codePointBefore(start)) && word(original.codePointAt(start))) return false
        if (end == rowEnd && end < original.length && word(original.codePointBefore(end)) && word(original.codePointAt(end))) return false
        if (rowStart > 0 && strongPrefix.find(original.substring(0, start))?.let { it.range.first < rowStart } == true) return false
        if (rowEnd == original.length) return true
        val tail = original.substring(end)
        fun crosses(pattern: Regex) = pattern.find(tail)?.let { end + it.range.last + 1 > rowEnd } == true
        if (crosses(compoundSuffix) || crosses(strongNativeSuffix) || crosses(symbolicContinuation)) return false
        if (physical.any { (GroceryIntentPhysicalUnitMatcher.after(original, end, it)?.end ?: end) > rowEnd }) return false
        val next = end + tail.takeWhile { it == ' ' || it == '\t' }.length
        return retailCountContainerPattern.matchAt(original, next)?.let { it.range.last < rowEnd } != false
    }

    private fun wordCountBeforeMeasure(count: Atom, amount: Atom): Boolean =
        amount.unit in physical && unicodeEvidenceRegex("\\p{Nd}+(?:\\.\\p{Nd}+)?").matches(amount.text) &&
            count.unit == "count" && count.value.stripTrailingZeros().scale() <= 0 &&
            count.value >= BigDecimal.ONE && count.value <= BigDecimal(20) &&
            count.text.codePoints().noneMatch { Character.getType(it) in numberCategories } &&
            sourceQuotedLocalAmount(source, count.text, "count")?.let {
                it.startUtf16 == count.start && it.endUtf16 == count.end
            } == true && !partialExpression(count, false)

    /** Mirrors the host's complete-amount guard family, not a product grammar.
     * Parseability alone is insufficient for unsupported multipliers, ranges
     * and operators. Legacy entrypoints are deliberately unchanged.
     */
    private fun partialExpression(atom: Atom, separateCount: Boolean): Boolean {
        val prefix = source.substring(0, atom.start)
        val suffix = source.substring(atom.end)
        if (strongPrefix.containsMatchIn(prefix)) return true
        if (nativePrefix.containsMatchIn(prefix) || nativeSuffix.containsMatchIn(suffix) || strongNativeSuffix.containsMatchIn(suffix) ||
            compoundSuffix.containsMatchIn(suffix)) return true
        if (expressionSuffix.containsMatchIn(suffix) ||
            unicodeEvidenceRegex("\\band(?:\\s+a)?\\s*$").containsMatchIn(prefix)) return true
        if (atom.text.lowercase(Locale.ROOT).split(unicodeEvidenceRegex("[ -]+")).any { it in setOf("hundred", "sau", "सौ") } &&
            unicodeEvidenceRegex("^[\\s-]*(?:and[\\s-]+)?$numberStart").containsMatchIn(suffix)) return true
        val previous = unicodeEvidenceRegex("(?:^|[\\s-])([^\\s-]+)[\\s-]+$").find(prefix)
        if (previous != null && parseIntentCorrectionAmount(previous.groupValues[1]) != null &&
            !separateCount && !legacyMeasuredContainer(atom, previous)) return true
        if (unicodeEvidenceRegex("^\\s*[-–—]\\s*\\p{Nd}").containsMatchIn(suffix)) return true
        return false
    }

    /** Preserve the existing explicit count + measured-container cases. This
     * is NOT used by the new word-count/decimal proof and does not infer roles.
     */
    private fun legacyMeasuredContainer(atom: Atom, previous: MatchResult): Boolean {
        if (atom.unit !in physical) return false
        val suffix = source.substring(atom.end)
        val digit = unicodeEvidenceRegex("\\p{Nd}+(?:\\.\\p{Nd}+)?").matches(atom.text)
        if (!digit && !suffix.startsWith("-")) return false
        val measure = GroceryIntentPhysicalUnitMatcher.after(source, atom.end, atom.unit) ?: return false
        val remainder = source.substring(measure.end)
        if (remainder.isEmpty() || !remainder.first().isWhitespace()) return false
        val tail = remainder.trimStart()
        if (unicodeEvidenceRegex("^(?:protein|fib(?:re|er)|carbs?|carbohydrates?|fat|sodium|calcium|calories?)\\b")
                .containsMatchIn(tail)) return false
        val container = retailCountContainerPattern.find(tail)?.let {
            unicodeEvidenceRegex("(?:[\\p{L}]+(?:-[\\p{L}]+)*\\s+){0,6}").matches(tail.substring(0, it.range.first))
        } == true
        val nominal = digit && unicodeEvidenceRegex("(?:[\\p{L}]+(?:-[\\p{L}]+)*[ \\t]+){0,7}[\\p{L}]+(?:-[\\p{L}]+)*[.!?]?").matches(tail) &&
            !unicodeEvidenceRegex("\\b(?:and|aur|with|or|ya|if|per|total|altogether|kul|another|instead)\\b").containsMatchIn(tail)
        val text = previous.groupValues[1]
        if (!container && !(nominal && unicodeEvidenceRegex("[A-Za-z]+").matches(text))) return false
        val count = parseIntentCorrectionAmount(text) ?: return false
        if (count.stripTrailingZeros().scale() > 0 || count < BigDecimal.ONE || count > BigDecimal(20)) return false
        val bound = sourceQuotedLocalAmount(source, text, "count") ?: return false
        val previousStart = previous.groups[1]!!.range.first
        return bound.startUtf16 == previousStart && !partialExpression(
            Atom(bound.startUtf16, bound.endUtf16, text, count, "count"), false)
    }

    private fun validBoundary(atom: Atom): Boolean {
        fun word(cp: Int) = cp == '_'.code || Character.isLetter(cp) || Character.getType(cp) in numberCategories + markCategories
        val unitEnd = if (atom.unit in physical) GroceryIntentPhysicalUnitMatcher.after(source, atom.end, atom.unit)
            ?.takeIf { it.start == atom.end }?.end else null
        val end = if (atom.end < source.length && word(source.codePointAt(atom.end))) unitEnd ?: return false else atom.end
        if (atom.start > 0 && word(source.codePointBefore(atom.start)) && word(source.codePointAt(atom.start))) return false
        if (end < source.length && word(source.codePointBefore(end)) && word(source.codePointAt(end))) return false
        // Keep decimal/grouping/range boundaries as well as supplementary-digit
        // word boundaries. Never fold compatibility numerals into quantities.
        if (Character.isDigit(source.codePointAt(atom.start)) && atom.start > 0) {
            val previous = source[atom.start - 1]
            if (previous in ".٫．+-−＋－" || previous in ",/٬，⁄∕" && atom.start > 1 &&
                Character.isDigit(source.codePointBefore(atom.start - 1))) return false
        }
        if (Character.isDigit(source.codePointBefore(atom.end)) && atom.end + 1 < source.length &&
            source[atom.end] in ".,/٫٬．，⁄∕" && Character.isDigit(source.codePointAt(atom.end + 1))) return false
        return true
    }

    private companion object {
        val strongPrefix = unicodeEvidenceRegex("(?:(?<![\\p{L}\\p{M}\\p{N}_])(?:plus|minus|times|divided\\s+by|paune|pauney|sawa|saadhe|sadhe|सवा|साढ़े|साढे|पौने|हजार|हज़ार|लाख|करोड़|दर्जन)|[+*/×÷−＋－–—⁄∕-])\\s*$")
        val nativePrefix = unicodeEvidenceRegex("(?:^|\\s)और\\s+$")
        val strongNativeSuffix = unicodeEvidenceRegex("^[\\s-]*(?:सौ|हजार|हज़ार|लाख|करोड़|दर्जन)(?=$|\\s)")
        val nativeSuffix = unicodeEvidenceRegex("^[\\s-]*(?:और)(?=$|\\s)")
        val compoundSuffix = unicodeEvidenceRegex("^[\\s-]*(?:dozens?\\b|hundred\\b|thousand\\b|lakhs?\\b|crores?\\b|sau\\b|haza+rs?\\b|quarters?\\b|halves\\b|and[\\s-]+(?:a[\\s-]+)?(?:half|quarter)\\b|\\p{Nd}+/\\p{Nd}+\\b)")
        val numberStart = "(?:\\p{N}|(?:" + GROUP_NUMERIC_WORDS.sortedByDescending { it.length }
            .joinToString("|") { Regex.escape(it) } + ")(?![\\p{L}\\p{N}\\p{M}_]))"
        val symbolicContinuation = unicodeEvidenceRegex("^\\s*(?:[+*/×÷−＋－–—⁄∕-]|(?:plus|minus|times|divided\\s+by)\\b)\\s*" + numberStart)
        val expressionSuffix = unicodeEvidenceRegex("^[\\s-]*(?:(?:and|plus|minus|times|divided\\s+by)(?![\\p{L}\\p{N}\\p{M}_])|[+*/×÷])[\\s-]*" + numberStart)
        val numberCategories = setOf(Character.DECIMAL_DIGIT_NUMBER.toInt(), Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt())
        val markCategories = setOf(Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt())
    }
}

/** These words are possible numbers, not general synonyms for digits. They
 * gain purchasing meaning only in a complete local item + amount construction. */
internal object ScopedInterpretedCardinal {
    private val kannada = mapOf("ond" to 1, "ondu" to 1, "eradu" to 2)
    private val words = kannada + mapOf("bees" to 20, "tees" to 30, "chalis" to 40, "chaalis" to 40,
        "pachas" to 50, "pachaas" to 50, "assi" to 80, "nabbe" to 90)
    // Literal cardinals only; no normalization, suffix stripping, text replacement or general number parser.
    private val nativeWords = mapOf("ஒரு" to 1, "ஒன்று" to 1, "இரண்டு" to 2, "மூன்று" to 3, "நான்கு" to 4,
        "ஐந்து" to 5, "ஆறு" to 6, "ஏழு" to 7, "எட்டு" to 8, "ஒன்பது" to 9, "பத்து" to 10,
        "ಒಂದು" to 1, "ಎರಡು" to 2, "ಮೂರು" to 3, "ನಾಲ್ಕು" to 4, "ಐದು" to 5, "ಆರು" to 6, "ಏಳು" to 7,
        "ಎಂಟು" to 8, "ಒಂಬತ್ತು" to 9, "ಹತ್ತು" to 10)
    private val nativeForms = mapOf("ಪ್ಯಾಕ್" to "pack", "ಪೌಚ್" to "pouch", "ಚೀಲ" to "bag", "ಕಪ್" to "cup",
        "பாக்கெட்" to "packet", "பை" to "bag")
    private val privateForm = Regex(nativeForms.keys.joinToString("|", transform=Regex::escape))
    fun native(word: String) = word in nativeWords
    fun sourceNativeForm(marker: String, atom: String): String? = if (native(atom)) nativeForms[marker] else null
    private val tokens = unicodeEvidenceRegex("[\\p{L}\\p{M}\\p{N}_]+")
    fun value(word: String) = (nativeWords[word] ?: words[word.lowercase(Locale.ROOT)])?.toBigDecimal()
    fun hasUnboundWord(text: String) = tokens.findAll(text).any { value(it.value) != null }

    private val gap = Regex("[ \\t]+")
    fun metricEnd(source: String, end: Int, unit: String): Int? =
        GroceryIntentPhysicalUnitMatcher.after(source, end, unit)?.takeIf { gap.matches(source.substring(end, it.start)) }?.end

    private fun exactBound(source: String, start: Int, end: Int): Boolean {
        fun delimiter(ch: Char) = ch in " \t\r\n,.;:!?()[]{}"
        return (start == 0 || delimiter(source[start-1])) && (end == source.length || delimiter(source[end]))
    }
    fun retail(source: String, end: Int, word: String): MatchResult? {
        if (!native(word)) return if (word.lowercase(Locale.ROOT) !in kannada) null else
            retailCountContainerPattern.find(source, end)?.takeIf { gap.matches(source.substring(end, it.range.first)) }
        val start = end - word.length
        if (start < 0 || !exactBound(source,start,end)) return null
        val forms = (privateForm.findAll(source) + retailCountContainerPattern.findAll(source))
            .filter { exactBound(source,it.range.first,it.range.last+1) }
        return forms.filter { f ->
            f.range.first > end && gap.matches(source.substring(end,f.range.first)) ||
                f.range.last+1 < start && gap.matches(source.substring(f.range.last+1,start))
        }.singleOrNull()
    }
    private val metricValue = Regex("(?:^|[ \\t])([0-9]+(?:\\.[0-9]+)?)[ \\t]*([^ \\t\\d.,;!?]+)$")
    fun nativeOccurrenceBound(source: String,start: Int,end: Int,word: String): Boolean {
        if (!exactBound(source,start,end)) return false
        val f = retail(source,end,word)
        if (f != null && f.range.last < start) {
            val tail = source.substring(end).trimStart(' ','\t')
            return tail.isEmpty() || tail.first() in ",;.!?\r\n"
        }
        val prefix = source.substring(0,start).substringAfterLast(',').substringAfterLast(';').trim(' ','\t')
        if (prefix.isEmpty() || completeNounMatches(prefix,prefix)) return true
        // Only an actual numeric atom followed by an entire recognised physical unit,
        // not an arbitrary native word, a fraction suffix or an abbreviated compound number.
        val prior = metricValue.find(prefix) ?: return false
        val amount = prior.groups[1]!!
        if (parseIntentCorrectionAmount(amount.value) == null) return false
        return listOf("g","kg","ml","l").any {
            GroceryIntentPhysicalUnitMatcher.after(prefix,amount.range.last+1,it)?.end == prefix.length
        }
    }

    private val unsafeNominal = groceryIntentNominalScopeUnsafeWords + setOf(
        "under", "over", "below", "above", "minimum", "maximum", "least", "most", "less", "more", "only",
        "protein", "fibre", "fiber", "carb", "carbs", "carbohydrate", "carbohydrates", "fat",
        "sodium", "calcium", "calorie", "calories", "kcal", "nutrient", "nutrients", "nutrition",
    )
    fun roleProven(source: String, subject: String, role: String, form: String?,
        quantity: IntentLocalAmount?, size: IntentLocalAmount?, onSourceNoun: (String) -> Unit = {},
        seller: SellerPropertyRequirement? = null): Boolean {
        // This lane never licenses an inner amount or infers an outer from a size.
        if (size != null && value(size.atom) != null) return false
        if (quantity == null || value(quantity.atom) == null) return true
        if (source.any { it.isISOControl() && it != '\t' }) return false
        val localNative = native(quantity.atom)
        if (localNative && !nativeOccurrenceBound(source,quantity.startUtf16,quantity.endUtf16,quantity.atom)) return false
        val physicalEnd = metricEnd(source, quantity.endUtf16, quantity.unit)
        val named = retail(source, quantity.endUtf16, quantity.atom)
        val postfix = named != null && named.range.last < quantity.startUtf16
        val end = if (physicalEnd != null) {
            if (role != "physical_total" || size != null || form != null || (localNative && named != null)) return false
            physicalEnd
        } else {
            if (role != "retail_units" || named == null || (nativeForms[named.value] ?: groceryIntentRetailContainerKey(named.value)) != form) return false
            if (postfix) quantity.endUtf16 else named.range.last + 1
        }
        var start = if (postfix) named!!.range.first else quantity.startUtf16
        var compositionIssue = false
        if (size != null) {
            // Product, explicit per-pack measure, then the named retail count.
            val measureEnd = GroceryIntentPhysicalUnitMatcher.after(source, size.endUtf16, size.unit)?.end ?: return false
            if (measureEnd >= start || !gap.matches(source.substring(measureEnd, start)) ||
                localNative && quantity.value.compareTo(BigDecimal.ONE) != 0) return false
            compositionIssue = sourceRetailCompositionIssue(source, quantity, size) != null
            start = size.startUtf16
        }
        val before = source.substring(0, start)
        val after = source.substring(end).trimEnd(' ', '\t').removeSuffix(".")
        val nominal = when {
            before.isBlank() && size == null && after.firstOrNull() in setOf(' ', '\t') -> {
                if (postfix) return false
                val tail = after.trim(' ', '\t')
                // "ondu bottle opener" names an object, not a bottle of openers.
                if (named != null && !tail.startsWith("of ", true)) return false
                tail.replaceFirst(Regex("^of[ \\t]+", RegexOption.IGNORE_CASE), "")
            }
            after.isBlank() && before.lastOrNull() in setOf(' ', '\t') -> before.trim(' ', '\t')
            else -> return false
        }
        // Only the fully consumed "<whole canonical goods> <metric> <one named selling unit>"
        // frame can distinguish a nutrient sold in its own right from nutrition information.
        // Never discharge an extra diet/serving word, unbound size, product suffix or substitute name.
        val packedWholeGoods = localNative && size != null && named != null &&
            ProductLexicon.completeIdentityNoun(nominal)?.let { it.equals(nominal,true) && it.equals(subject,true) } == true
        val packedCut = localNative && named != null && size?.unit in setOf("g", "kg") &&
            quantity.value.compareTo(BigDecimal.ONE) == 0 && nominal.equals(subject, true) &&
            requiredFoodCut(source, nominal, seller)
        return (packedWholeGoods || !compositionIssue && (completeNounMatches(nominal, subject) || packedCut)).also {
            if (it) onSourceNoun(nominal)
        }
    }

    // The role geometry above accounts for every character. A cut form inside a
    // two-word product name is not a count: preserve both words for the seller
    // and require the cut itself as a separately enforceable exact requirement.
    private fun requiredFoodCut(source: String, nominal: String, seller: SellerPropertyRequirement?): Boolean {
        if (seller == null || seller.source.text != source) return false
        val words = nominal.lowercase(Locale.ROOT).split(' ')
        if (words.size != 2 || words.last() !in groceryCutForms ||
            !(isBareGroceryIntentCommodityHead(words.first()) || isLiteralProduceNoun(words.first()))) return false
        val nounStart = source.indexOfFirst { it != ' ' && it != '\t' }
        if (nounStart < 0 || !source.startsWith(nominal, nounStart)) return false
        val end = seller.source.startUtf16 + nounStart + nominal.length
        return seller.requirements.any { it.endUtf16 == end && it.startUtf16 == end - words.last().length &&
            it.text.equals(words.last(), ignoreCase = true) }
    }

    fun completeNounMatches(nominal: String, subject: String): Boolean {
        val parts = tokens.findAll(nominal.lowercase(Locale.ROOT)).map { it.value }.toSet()
        return groceryIntentRetailNominalValid(nominal) && parts.none { it in unsafeNominal || it in GROUP_NUMERIC_WORDS || it in words } &&
            !sourceHasGenericContainer(nominal) && !retailCountContainerPattern.containsMatchIn(nominal) &&
            ProductLexicon.sameQualifiedNoun(nominal, subject)
    }
}
