package com.example.beta

/** A user-edit proposal only. It never creates or modifies a prepared grocery row. */
internal data class GroceryIntentInputRepair(
    val source: GroceryIntentReviewV2Quote,
    val newAmount: GroceryIntentReviewV2Quote,
    val options: List<Option>,
    val originalInstruction: String,
) {
    internal data class Option(
        val rowIndex: Int,
        val primary: GroceryIntentReviewV2Quote,
        val oldAmount: GroceryIntentReviewV2Quote,
        val target: GroceryIntentReviewV2Quote,
    )

    fun editedInstruction(rowIndex: Int): String {
        val option = options.singleOrNull { it.rowIndex == rowIndex } ?: error("Unknown input-repair option")
        require(source.startUtf16 <= newAmount.startUtf16 && newAmount.endUtf16 <= source.endUtf16)
        return originalInstruction.replaceRange(source.startUtf16, newAmount.startUtf16, "make ${option.target.text} ")
    }

    /** No other blocked source/context may be hidden behind this single quantity question. */
    fun isOnlyIssueIn(draft: GroceryIntentReviewV2): Boolean {
        if (draft.instruction != originalInstruction || draft.readyRows.isNotEmpty() ||
            draft.groups.isNotEmpty() || draft.constraints.isNotEmpty() ||
            draft.blockedRows.any { it.excludedContext.isNotEmpty() } ||
            draft.blockedRows.map { it.rowIndex }.toSet() != options.map { it.rowIndex }.toSet()) return false
        val owned = options.map { GroceryIntentCoverageSpan(it.primary.startUtf16, it.primary.endUtf16) } +
            GroceryIntentCoverageSpan(source.startUtf16, source.endUtf16)
        val remaining = originalInstruction.toCharArray()
        val neutral = neutralGroceryIntentContextSpans(originalInstruction, owned)
        (owned + neutral).forEach { span ->
            if (span.startUtf16 < 0 || span.endUtf16 > remaining.size || span.endUtf16 <= span.startUtf16) return false
            for (index in span.startUtf16 until span.endUtf16) remaining[index] = ' '
        }
        return Regex("[ \\t\\r\\n,.!?;:]*").matches(String(remaining))
    }

    companion object {
        private val keys = setOf("kind", "source", "new_amount", "options")
        private val optionKeys = setOf("row_index", "primary", "old_amount", "target")
        private val servingAndMeasure = setOf(
            "serving", "servings", "g", "gm", "gms", "gram", "grams", "kg", "kgs", "kilo", "kilos",
            "kilogram", "kilograms", "keji", "ml", "millilitre", "millilitres", "milliliter", "milliliters",
            "l", "ltr", "litre", "litres", "liter", "liters", "size",
        )
        private val unsafeTargetWords = setOf(
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen",
            "nineteen", "twenty", "dozen", "half", "quarter", "and", "or", "no", "not", "sorry", "actually",
            "make", "change", "set", "instead", "wait", "yes", "each", "per", "size", "glass", "glasses",
            "spoon", "spoons", "tablespoon", "tablespoons", "teaspoon", "teaspoons", "serving", "servings",
            "dose", "doses", "piece", "pieces", "count", "pair", "pairs", "dozens", "sets",
            "gram", "grams", "gm", "gms", "kilogram", "kilograms", "kg", "kgs", "kilo", "kilos", "keji",
            "g", "ml", "millilitre", "millilitres", "milliliter", "milliliters", "litre", "litres", "liter", "liters", "ltr",
        )
        private val ignoredTargetAnchors = setOf(
            "a", "an", "the", "and", "or", "of", "for", "with", "without", "in", "on", "to", "from",
            "please", "get", "buy", "order", "add", "make", "keep", "only", "just", "that", "this", "these", "those",
            "it", "is", "be", "are", "should", "must", "not", "no", "one", "two", "three", "four", "five", "six",
            "seven", "eight", "nine", "ten", "eleven", "twelve", "dozen", "half", "quarter", "pack", "packs",
            "packet", "packets", "bag", "bags", "bottle", "bottles", "carton", "cartons", "cup", "cups", "can", "cans",
            "tin", "tins", "tub", "tubs", "jar", "jars", "box", "boxes", "bar", "bars", "block", "blocks", "loaf",
            "loaves", "bunch", "bunches", "roll", "rolls", "refill", "refills", "pouch", "pouches", "sachet", "sachets",
            "tube", "tubes", "spray", "sprays", "piece", "pieces", "pcs", "count", "small", "large", "medium", "big",
            "little", "plain", "unsalted", "salted", "unsweetened", "sweetened", "organic", "certified", "spicy", "sweet",
            "vegan", "vegetarian", "nonvegetarian", "full", "fat", "free", "fresh", "each", "instead", "sorry",
        )
        private val pluralAliases = mapOf(
            "yoghurts" to "yogurt", "yoghurt" to "yogurt", "yogurts" to "yogurt", "eggs" to "egg",
            "bananas" to "banana", "apples" to "apple", "oranges" to "orange", "tomatoes" to "tomato",
            "onions" to "onion", "potatoes" to "potato", "lemons" to "lemon", "biscuits" to "biscuit",
            "chocolates" to "chocolate", "lentils" to "lentil", "samosas" to "samosa", "strawberries" to "strawberry",
            "blueberries" to "blueberry", "mangoes" to "mango", "pomegranates" to "pomegranate", "chillies" to "chilli",
        )
        private val targetPattern = Regex("[A-Za-z]+(?:[-'][A-Za-z]+)*(?:[ \\t]+[A-Za-z]+(?:[-'][A-Za-z]+)*)*")
        private val countWords = mapOf(
            "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
            "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
            "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15,
            "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19, "twenty" to 20,
        )
        private val cuePattern = Regex(
            "^(?:(?:no|sorry|actually)[ \\t,]+)?(?:make|change)[ \\t]+(?:it|that|this)[ \\t]+(?:to[ \\t]+)?$",
            RegexOption.IGNORE_CASE,
        )
        private val shortCuePattern = Regex("^(?:no|sorry|actually)[, \\t]+$", RegexOption.IGNORE_CASE)
        private val affirmativePattern = Regex("^(?:no|sorry|actually)(?:[ \\t,]+sorry)?[ \\t,]+yes[ \\t,]*$", RegexOption.IGNORE_CASE)
        private val decimalCountPattern = Regex("(?:[1-9]|1[0-9]|20)")

        fun parse(
            value: JsonValue,
            instruction: String,
            draft: GroceryIntentReviewV2,
        ): GroceryIntentInputRepair {
            val obj = value.asObject()
            require(obj.keys == keys)
            require(obj.string("kind") == "quantity_count_target")
            require(draft.readyRows.isEmpty() && draft.blockedRows.isNotEmpty() && draft.groups.isEmpty())

            val source = GroceryIntentReviewV2Quote.parse(obj.objectValue("source"), instruction, 180)
            val newAmount = GroceryIntentReviewV2Quote.parse(obj.objectValue("new_amount"), instruction, 10)
            require(countValue(newAmount.text) != null)
            require(source.startUtf16 <= newAmount.startUtf16 && newAmount.endUtf16 <= source.endUtf16)
            val beforeNewAmount = instruction.substring(source.startUtf16, newAmount.startUtf16)
            require(beforeNewAmount.all { it.code < 128 && (it.isLetter() || it == ' ' || it == '\t' || it == ',') })
            val cue = beforeNewAmount.trim(' ', '\t')
            val suffix = instruction.substring(newAmount.endUtf16, source.endUtf16)
            require(cuePattern.matches("$cue ") || shortCuePattern.matches("$cue ") || affirmativePattern.matches(cue))
            require(Regex("[ \\t.,!?]*").matches(suffix))

            val optionValues = obj.arrayValue("options")
            require(optionValues.size in 1..5)
            val blockedByIndex = draft.blockedRows.associateBy { it.rowIndex }
            val options = optionValues.map { raw ->
                val option = raw.asObject()
                require(option.keys == optionKeys)
                val rowIndex = option.exactInt("row_index")
                val blocked = blockedByIndex[rowIndex] ?: error("Input repair must refer to a blocked row")
                val primary = GroceryIntentReviewV2Quote.parse(option.objectValue("primary"), instruction)
                require(blocked.source == primary)
                require(primary.endUtf16 <= source.startUtf16)
                require(sameHardSentence(instruction, primary.endUtf16, source.startUtf16))

                val oldAmount = GroceryIntentReviewV2Quote.parse(option.objectValue("old_amount"), instruction, 10)
                val target = GroceryIntentReviewV2Quote.parse(option.objectValue("target"), instruction, 100)
                require(countValue(oldAmount.text) != null)
                require(oldAmount.startUtf16 >= primary.startUtf16 && oldAmount.endUtf16 <= primary.endUtf16)
                require(instruction.substring(primary.startUtf16, oldAmount.startUtf16).all { it == ' ' || it == '\t' })
                require(target.startUtf16 > oldAmount.endUtf16 && target.endUtf16 == primary.endUtf16)
                require(instruction.substring(oldAmount.endUtf16, target.startUtf16).isNotEmpty())
                require(instruction.substring(oldAmount.endUtf16, target.startUtf16).all { it == ' ' || it == '\t' })
                validateTarget(target.text)
                require(targetIsUnique(target.text, rowIndex, draft))
                Option(rowIndex, primary, oldAmount, target)
            }
            require(options.map { it.rowIndex }.distinct().size == options.size)
            require(exhaustiveClause(instruction, draft, source, options))

            val occupied = buildList {
                draft.readyRows.forEach { row ->
                    add(row.evidence.primary)
                    addAll(row.evidence.supporting.map { it.quote })
                    addAll(row.corrections.map { it.source })
                }
                draft.blockedRows.forEach { row ->
                    row.source?.let(::add)
                    addAll(row.excludedContext)
                }
                draft.groups.forEach { group ->
                    add(group.source)
                    group.header?.let(::add)
                    group.total?.let { add(it.source) }
                    group.each?.let { add(it.source) }
                }
                draft.constraints.forEach { add(it.source) }
            }
            require(occupied.none { it.overlaps(source) })
            require(options.all { option ->
                occupied.none { it.overlaps(option.primary) && it != option.primary }
            })
            return GroceryIntentInputRepair(source, newAmount, options.toList(), instruction)
        }

        private fun exhaustiveClause(
            instruction: String,
            draft: GroceryIntentReviewV2,
            correction: GroceryIntentReviewV2Quote,
            options: List<Option>,
        ): Boolean {
            if (draft.blockedRows.any { it.source == null }) return false
            val clauseStart = Regex("[;!?\\r\\n]|\\.(?=\\s|$)")
                .findAll(instruction.substring(0, correction.startUtf16)).lastOrNull()?.range?.last?.plus(1) ?: 0
            val preceding = draft.blockedRows.filter { row ->
                row.source!!.startUtf16 >= clauseStart && row.source.startUtf16 < correction.startUtf16
            }
            if (preceding.map { it.rowIndex }.toSet() != options.map { it.rowIndex }.toSet()) return false
            val primaries = draft.blockedRows.map { row ->
                GroceryIntentCoverageSpan(row.source!!.startUtf16, row.source.endUtf16)
            }
            val occupied = primaries + GroceryIntentCoverageSpan(correction.startUtf16, correction.endUtf16)
            val remaining = instruction.toCharArray()
            neutralGroceryIntentContextSpans(instruction, primaries).forEach { span ->
                if (occupied.none { span.startUtf16 < it.endUtf16 && it.startUtf16 < span.endUtf16 }) {
                    for (offset in span.startUtf16 until span.endUtf16) remaining[offset] = ' '
                }
            }
            options.forEach { option ->
                for (offset in option.primary.startUtf16 until option.primary.endUtf16) remaining[offset] = ' '
            }
            return Regex("(?:[ \\t,]|\\band\\b)*", RegexOption.IGNORE_CASE)
                .matches(String(remaining, clauseStart, correction.startUtf16 - clauseStart))
        }

        private fun validateTarget(text: String) {
            require(text.length in 1..100 && text.all { it.code < 128 })
            require(targetPattern.matches(text))
            val words = text.lowercase().split(Regex("[ \\t'-]+"))
            require(words.none { word ->
                word in servingAndMeasure || word in unsafeTargetWords || word.any(Char::isDigit)
            })
        }

        private fun countValue(text: String): Int? = if (decimalCountPattern.matches(text)) text.toIntOrNull()
            else countWords[text.lowercase()]

        private fun targetIsUnique(target: String, rowIndex: Int, draft: GroceryIntentReviewV2): Boolean {
            val anchors = targetAnchors(target)
            if (anchors.isEmpty()) return false
            val matchingRows = draft.blockedRows.filter { row ->
                row.source?.let { source -> anchors.all { it in targetAnchors(source.text) } } == true
            }.map { it.rowIndex }
            return matchingRows == listOf(rowIndex)
        }

        private fun targetAnchors(text: String): Set<String> = Regex("[A-Za-z]+")
            .findAll(text.lowercase())
            .map { pluralAliases[it.value] ?: it.value }
            .filter { it !in ignoredTargetAnchors }
            .toSet()

        private fun sameHardSentence(text: String, start: Int, end: Int): Boolean =
            start <= end && !Regex("[;!?\\r\\n]|\\.(?=\\s|$)").containsMatchIn(text.substring(start, end))

        private fun JsonValue.asObject(): Map<String, JsonValue> =
            (this as? JsonValue.Obj)?.members ?: error("Invalid input-repair object")

        private fun JsonValue.asArray(): List<JsonValue> =
            (this as? JsonValue.Arr)?.items ?: error("Invalid input-repair array")

        private fun Map<String, JsonValue>.string(key: String): String =
            (getValue(key) as? JsonValue.Str)?.value ?: error("Invalid input-repair string")

        private fun Map<String, JsonValue>.objectValue(key: String): Map<String, JsonValue> =
            getValue(key).asObject()

        private fun Map<String, JsonValue>.arrayValue(key: String): List<JsonValue> =
            getValue(key).asArray()

        private fun Map<String, JsonValue>.exactInt(key: String): Int {
            val raw = (getValue(key) as? JsonValue.Num)?.raw ?: error("Invalid input-repair index")
            return raw.toBigDecimalOrNull()?.intValueExactOrNull() ?: error("Invalid input-repair index")
        }

        private fun java.math.BigDecimal.intValueExactOrNull(): Int? = try {
            intValueExact()
        } catch (_: ArithmeticException) {
            null
        }
    }
}
