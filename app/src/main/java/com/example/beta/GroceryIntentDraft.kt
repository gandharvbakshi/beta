package com.example.beta

import com.example.beta.automation.ParsedItem
import com.example.beta.automation.ProductLexicon
import com.example.beta.automation.Quantity
import java.math.BigDecimal

/** A strictly validated, review-only response from the optional grocery-intent endpoint. */
data class GroceryIntentResponse(
    val draft: GroceryIntentDraft,
    val model: String,
    val promptVersion: String,
    val latencyMs: Double,
    val usage: Map<String, Double>,
) {
    companion object {
        private val responseKeys = setOf("accepted", "draft", "model", "promptVersion", "latencyMs", "usage")

        fun parse(body: String, originalInstruction: String): GroceryIntentResponse {
            val root = JsonParser(body).parseStrict().asObject("response")
            require(root.keys == responseKeys) { "Unexpected intent response fields" }
            require(root.getValue("accepted").asBoolean("accepted")) { "Intent was not accepted" }
            val draft = GroceryIntentDraft.parse(root.getValue("draft"), originalInstruction)
            val model = root.getValue("model").asString("model").boundedText("model", 1, 120)
            val promptVersion = root.getValue("promptVersion").asString("promptVersion").boundedText("promptVersion", 1, 80)
            val latencyMs = root.getValue("latencyMs").asDouble("latencyMs").also { require(it >= 0 && it.isFinite()) }
            val usageObject = root.getValue("usage").asObject("usage")
            require(usageObject.keys.all { it in setOf("input_tokens", "output_tokens", "total_tokens") }) {
                "Unexpected usage fields"
            }
            val usage = usageObject.mapValues { (key, value) ->
                value.asDouble("usage.$key").also { require(it in 0.0..100_000_000.0 && it.isFinite()) }
            }
            return GroceryIntentResponse(draft, model, promptVersion, latencyMs, usage)
        }
    }
}

data class GroceryIntentDraft(
    val contractVersion: String,
    val requiresReview: Boolean,
    val instruction: String,
    val items: List<GroceryIntentItem>,
    val modelNeedsClarification: Boolean,
) {
    companion object {
        private val draftKeys = setOf(
            "contract_version", "requires_review", "instruction", "items", "model_needs_clarification",
        )

        internal fun parse(value: JsonValue, originalInstruction: String): GroceryIntentDraft {
            val obj = value.asObject("draft")
            require(obj.keys == draftKeys) { "Unexpected draft fields" }
            require(obj.getValue("contract_version").asString("contract_version") == "grocery-intent-v1")
            require(obj.getValue("requires_review").asBoolean("requires_review"))
            val instruction = obj.getValue("instruction").asString("instruction")
            require(instruction == originalInstruction) { "Intent instruction changed" }
            val items = obj.getValue("items").asArray("items").mapIndexed { index, item ->
                GroceryIntentItem.parse(item, originalInstruction, index)
            }
            require(items.size <= 50) { "Too many intent items" }
            return GroceryIntentDraft(
                contractVersion = "grocery-intent-v1",
                requiresReview = true,
                instruction = instruction,
                items = items,
                modelNeedsClarification = obj.getValue("model_needs_clarification").asBoolean("model_needs_clarification"),
            )
        }
    }

    /** Converts only unambiguous entries. Ambiguous requests must go to the caller's review flow. */
    fun toParsedItems(): List<ParsedItem> {
        val uncovered = uncoveredGroceryIntentText(instruction, items.map { it.sourceText })
        if (uncovered.isNotEmpty()) {
            // Do not let a shorter model draft hide an uncited part of a long list.
            throw IntentNeedsReviewException(listOf(uncovered.joinToString(" ")))
        }
        return prepareForReview().items
    }

    /** Review preparation only. Uncited text must remain a blocking acknowledgement at cart review. */
    internal fun prepareForReview(): GroceryIntentPreparation {
        val grounding = groceryIntentGrounding(instruction, items)
        if (grounding.rejected.isNotEmpty()) throw IntentNeedsReviewException(grounding.rejected)
        val flagged = items.filter { it.needsClarification }
        if (modelNeedsClarification || flagged.isNotEmpty()) {
            throw IntentNeedsReviewException(
                (flagged.map { it.sourceText } + if (modelNeedsClarification && flagged.isEmpty()) listOf(instruction) else emptyList()).distinct(),
            )
        }
        return GroceryIntentPreparation(
            items.map(GroceryIntentItem::toParsedItem),
            (uncoveredGroceryIntentSegments(instruction, items.map { it.sourceText }) + grounding.review).distinct(),
        )
    }
}

internal data class GroceryIntentPreparation(val items: List<ParsedItem>, val unresolvedText: List<String>)

data class GroceryIntentItem(
    val name: String,
    val quantity: BigDecimal?,
    val quantityUnit: String,
    val packValue: BigDecimal?,
    val packUnit: String,
    val required: List<String>,
    val excluded: List<String>,
    val sourceText: String,
    val needsClarification: Boolean,
) {
    internal fun toParsedItem(): ParsedItem {
        val nameAndTags = (listOf(name) + required.map(::requiredPhrase))
            .map(ProductLexicon::canonicalizeAliasesPreservingText).distinct()
        val pack = packValue?.let { normalizePack(it, packUnit) }
        val query = (nameAndTags + listOfNotNull(pack?.let { "${it.first} ${it.second}" })).joinToString(" ")
        val quantityValue = quantity?.let { normalizeQuantity(it, quantityUnit) }
        val parsedQuantity = when (quantityUnit) {
            "count", "pack", "piece" -> quantityValue?.let { Quantity.Count(it) } ?: Quantity.Default
            "g", "kg" -> quantityValue?.let { Quantity.Weight(it) } ?: Quantity.Default
            "ml", "l" -> quantityValue?.let { Quantity.Volume(it) } ?: Quantity.Default
            "unspecified" -> Quantity.Default
            else -> error("Unsupported quantity unit")
        }
        return ParsedItem(
            rawText = sourceText,
            query = query,
            quantity = parsedQuantity,
            // This legacy field means an UNRESOLVED quantity, not provenance.
            quantitySignal = null,
            parserConfidence = if (required.any { it !in REQUIRED_PHRASES }) 0.7f else 1.0f,
            avoidPhrases = excluded.map(::tagPhrase),
            // Model-supplied constraints require literal catalogue evidence, not fuzzy matching.
            // The language model already performed spelling/category normalization. Never
            // fuzz away a remaining name-only descriptor or silently strip its pack measure.
            strictMatchPhrase = query,
            retailPackCount = quantityUnit == "pack",
        )
    }

    companion object {
        private val itemKeys = setOf(
            "name", "quantity", "quantity_unit", "pack_value", "pack_unit", "required", "excluded",
            "source_text", "needs_clarification",
        )
        private val quantityUnits = setOf("count", "pack", "piece", "g", "kg", "ml", "l", "unspecified")
        private val physicalUnits = setOf("g", "kg", "ml", "l", "piece", "unspecified")
        private val tagPattern = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N} _'-]{0,59}")

        internal fun parse(value: JsonValue, originalInstruction: String, index: Int): GroceryIntentItem {
            val obj = value.asObject("items[$index]")
            require(obj.keys == itemKeys) { "Unexpected item fields" }
            val name = obj.getValue("name").asString("items[$index].name").boundedText("name", 1, 200)
            require(name.none(Char::isISOControl))
            val quantity = obj.getValue("quantity").nullableDecimal("quantity")
            val quantityUnit = obj.getValue("quantity_unit").asString("quantity_unit")
            require(quantityUnit in quantityUnits)
            val packValue = obj.getValue("pack_value").nullableDecimal("pack_value")
            val packUnit = obj.getValue("pack_unit").asString("pack_unit")
            require(packUnit in physicalUnits)
            validateQuantity(quantity, quantityUnit)
            validatePack(packValue, packUnit)
            val required = obj.getValue("required").asArray("required").mapIndexed { tagIndex, tag ->
                tag.asString("required[$tagIndex]").validateTag()
            }
            val excluded = obj.getValue("excluded").asArray("excluded").mapIndexed { tagIndex, tag ->
                tag.asString("excluded[$tagIndex]").validateTag()
            }
            require(required.size <= 16 && excluded.size <= 16) { "Too many item tags" }
            require(required.distinctBy(::normalizeTag).size == required.size) { "Duplicate required tags" }
            require(excluded.distinctBy(::normalizeTag).size == excluded.size) { "Duplicate excluded tags" }
            validateTagConflicts(required, excluded)
            val sourceText = obj.getValue("source_text").asString("source_text").boundedText("source_text", 1, 6000)
            require(sourceText.none(Char::isISOControl))
            require(originalInstruction.contains(sourceText)) { "source_text is not an exact instruction quote" }
            return GroceryIntentItem(
                name, quantity, quantityUnit, packValue, packUnit, required, excluded, sourceText,
                obj.getValue("needs_clarification").asBoolean("needs_clarification"),
            )
        }

        private fun String.validateTag(): String = trim().also {
            require(it.isNotEmpty() && it.length <= 60 && tagPattern.matches(it)) { "Invalid tag" }
        }

        private fun validateQuantity(value: BigDecimal?, unit: String) {
            if (value == null) {
                require(unit == "unspecified") { "A missing quantity must be unspecified" }
                return
            }
            require(value > BigDecimal.ZERO && value.toDouble().isFinite()) { "Quantity must be positive and finite" }
            if (unit in setOf("count", "pack", "piece")) {
                val count = value.exactInt("Discrete quantity")
                require(count in 1..20) { "Discrete quantity is out of range" }
            } else {
                normalizeQuantity(value, unit)
            }
        }

        private fun validatePack(value: BigDecimal?, unit: String) {
            if (value == null) {
                require(unit == "unspecified") { "A missing pack size must be unspecified" }
                return
            }
            if (unit == "piece") {
                require(value > BigDecimal.ZERO && value.toDouble().isFinite())
                require(value.exactInt("Per-pack piece count") in 1..1_000_000) {
                    "Per-pack piece count is out of range"
                }
                return
            }
            require(unit in setOf("g", "kg", "ml", "l")) { "Pack size requires a physical unit" }
            require(value > BigDecimal.ZERO && value.toDouble().isFinite())
            normalizePhysical(value, unit)
        }

        private fun validateTagConflicts(required: List<String>, excluded: List<String>) {
            val req = required.map(::normalizeTag).toSet()
            val exc = excluded.map(::normalizeTag).toSet()
            require(req.intersect(exc).isEmpty()) { "A tag cannot be both required and excluded" }
            val conflicts = listOf(
                "sugar_free" to "contains_sugar", "no_added_sugar" to "added_sugar", "low_fat" to "full_fat",
                "vegetarian" to "non_vegetarian", "vegan" to "dairy", "ready_to_drink" to "powder",
                "ready_to_eat" to "uncooked",
            )
            require(conflicts.none { (a, b) -> (a in req && b in req) || (a in exc && b in exc) }) {
                "Conflicting tags"
            }
        }
    }
}

class IntentNeedsReviewException(val affectedSourceText: List<String>) : IllegalArgumentException(
    "Intent requires user clarification before product matching",
)

private val REQUIRED_PHRASES = mapOf(
    "sugar_free" to "sugar free", "no_added_sugar" to "no added sugar", "ready_to_drink" to "ready to drink",
    "ready_to_eat" to "ready to eat", "low_fat" to "low fat", "tetra_pack" to "tetra pack",
    "unsalted" to "unsalted", "organic" to "organic", "plain" to "plain", "whole_wheat" to "whole wheat",
    "gluten_free" to "gluten free", "lactose_free" to "lactose free", "vegan" to "vegan",
    "vegetarian" to "vegetarian", "non_vegetarian" to "non vegetarian", "full_fat" to "full fat",
    "contains_sugar" to "contains sugar", "added_sugar" to "added sugar", "powder" to "powder",
    "uncooked" to "uncooked", "dairy" to "dairy",
)

private fun requiredPhrase(tag: String): String = REQUIRED_PHRASES[tag] ?: tagPhrase(tag)
private fun tagPhrase(tag: String): String = tag.replace('_', ' ').trim()
private fun normalizeTag(tag: String): String = tag.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_')

private fun normalizeQuantity(value: BigDecimal, unit: String): Int = when (unit) {
    "count", "pack", "piece", "g", "ml" -> value.exactInt("Quantity")
    "kg" -> value.multiply(BigDecimal("1000")).exactInt("Quantity")
    "l" -> value.multiply(BigDecimal("1000")).exactInt("Quantity")
    else -> error("Unsupported quantity unit")
}.also { require(it > 0) { "Quantity is not representable exactly" } }

private fun normalizePhysical(value: BigDecimal, unit: String): Pair<Int, String> {
    val canonicalUnit = if (unit == "kg" || unit == "l") if (unit == "kg") "g" else "ml" else unit
    val canonicalValue = if (unit == "kg" || unit == "l") value.multiply(BigDecimal("1000")) else value
    return canonicalValue.exactInt("Pack size").also { require(it > 0) { "Pack size is not representable exactly" } } to canonicalUnit
}

private fun normalizePack(value: BigDecimal, unit: String): Pair<Int, String> =
    if (unit == "piece") {
        value.exactInt("Per-pack piece count").also { require(it in 1..1_000_000) } to "pieces"
    } else {
        normalizePhysical(value, unit)
    }

private fun BigDecimal.exactInt(label: String): Int = try {
    intValueExact()
} catch (_: ArithmeticException) {
    throw IllegalArgumentException("$label is not representable exactly")
}

private fun JsonValue.asObject(label: String): Map<String, JsonValue> =
    (this as? JsonValue.Obj)?.members ?: error("$label must be an object")

private fun JsonValue.asArray(label: String): List<JsonValue> =
    (this as? JsonValue.Arr)?.items ?: error("$label must be an array")

private fun JsonValue.asString(label: String): String =
    (this as? JsonValue.Str)?.value ?: error("$label must be a string")

private fun JsonValue.asBoolean(label: String): Boolean =
    (this as? JsonValue.Bool)?.value ?: error("$label must be a boolean")

private fun JsonValue.nullableDecimal(label: String): BigDecimal? = when (this) {
    JsonValue.Null -> null
    is JsonValue.Num -> raw.toBigDecimalOrNull() ?: error("$label must be a finite number")
    else -> error("$label must be a number or null")
}

private fun JsonValue.asDouble(label: String): Double {
    val raw = (this as? JsonValue.Num)?.raw ?: error("$label must be a number")
    return raw.toDoubleOrNull()?.also { require(it.isFinite()) } ?: error("$label must be a finite number")
}

private fun String.boundedText(label: String, min: Int, max: Int): String = trim().also {
    require(this == it && it.length in min..max && it.none(Char::isISOControl)) { "$label has invalid length or characters" }
}
