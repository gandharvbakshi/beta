package com.example.beta

import com.example.beta.automation.ProductLexicon

/**
 * Bounded evidence checks, not a semantic proof. Names may use known translations/plurals but
 * cannot introduce arbitrary words absent from the item quote or its bounded shared clause.
 * This does NOT prove which neighbouring item a shared modifier belongs to inside a quote.
 */
internal data class GroceryIntentGroundingResult(val rejected: List<String>, val review: List<String>)

internal fun groceryIntentGroundingConcerns(instruction: String, items: List<GroceryIntentItem>): List<String> =
    groceryIntentGrounding(instruction, items).rejected

internal fun groceryIntentGrounding(instruction: String, items: List<GroceryIntentItem>): GroceryIntentGroundingResult {
    val rejected = mutableListOf<String>()
    val review = mutableListOf<String>()
    items.forEach { item ->
        val literalSource = intentEvidenceTokens(item.sourceText)
        // "Ready-made" food ordinarily requests prepared food, but an already-mixed
        // ingredient is not evidence it can be eaten without cooking.
        val equivalentForms = if ("readymade" in literalSource && literalSource.none {
            it in setOf("batter", "dough", "mix", "premix", "flour", "paste")
        }) setOf("readytoeat") else emptySet()
        val source = literalSource
        val proposed = intentEvidenceTokens(item.name + " " + item.required.joinToString(" ") { it.replace('_', ' ') })
        // Shared names may occur earlier within punctuation-bounded context.
        // This bounded lexical context is not proof of modifier scope. Dietary/form evidence
        // below must come from the item's own quote, not this shared prefix.
        val start = instruction.indexOf(item.sourceText)
        val uniqueQuote = start >= 0 && instruction.indexOf(item.sourceText, start + 1) < 0
        val prefix = if (start > 0 && uniqueQuote) instruction.substring(0, start)
            .split(Regex("[,;\\n]|[.!?](?=\\s)")).last().takeLast(160) else ""
        // A colon explicitly introduces a shared list header ("Brand yogurt: mango,
        // strawberry"). Carry only that short header, never earlier item bodies, across
        // commas. Sentence/semicolon boundaries end the group. It still needs review.
        val sentencePrefix = if (start > 0 && uniqueQuote) instruction.substring(0, start)
            .split(Regex("[;\\n]|[.!?](?=\\s)")).last() else ""
        val header = sentencePrefix.takeIf { it.count { char -> char == ':' } == 1 &&
            Regex("(?<=\\p{L})\\s*:\\s").containsMatchIn(it) }
            ?.substringBefore(':')?.trim()?.takeIf { it.length in 1..100 && ',' !in it }
            ?.takeUnless { candidate -> items.any { other -> candidate.contains(other.sourceText) } }.orEmpty()
        val localScope = source + equivalentForms + intentEvidenceTokens(prefix)
        val scoped = localScope + intentEvidenceTokens(header)
        val proposedGrounded = scoped.containsAll(proposed)
        // Bidirectional checks: preserve stated properties AND reject model-added dietary
        // or form restrictions. Ordinary product knowledge is not user evidence.
        val protectedForms = setOf("sugarfree", "noaddedsugar", "lowfat", "tetrapack", "readytoeat", "readytodrink", "readytocook",
            "organic", "certified", "unsalted", "plain", "highfibre", "highprotein", "lowcalorie", "lowsugar",
            "glutenfree", "lactosefree", "vegan", "vegetarian")
        val missingProperties = (source.intersect(protectedForms) - proposed).toMutableSet()
        if ("readytoeat" in equivalentForms && proposed.none { it in setOf("readymade", "readytoeat") }) {
            missingProperties += "readymade"
        }
        val addedProperties = proposed.intersect(protectedForms) - source - equivalentForms
        val headerMissing = intentEvidenceTokens(header).intersect(protectedForms) - proposed
        val overlappingQuote = items.any { other -> other !== item && other.sourceText != item.sourceText &&
            item.sourceText.contains(other.sourceText) }
        val correction = Regex("\\b(?:sorry|instead|keep the first|rehne do|nahi\\s+nahi)\\b|\\b(?:no|nahi)\\s*(?:,|—|--|\\s-\\s)", RegexOption.IGNORE_CASE)
            .containsMatchIn(item.sourceText)
        when {
            !proposedGrounded || addedProperties.isNotEmpty() || groceryIntentPackEvidenceConflict(item) -> rejected += item.sourceText
            missingProperties.isNotEmpty() && !overlappingQuote && !correction -> rejected += item.sourceText
            missingProperties.isNotEmpty() || headerMissing.isNotEmpty() -> {
                val labels = (missingProperties + headerMissing).sorted().joinToString(", ") { property ->
                    mapOf("sugarfree" to "sugar-free", "noaddedsugar" to "no added sugar", "lowfat" to "low fat",
                        "tetrapack" to "tetra pack", "readytoeat" to "ready to eat", "readytodrink" to "ready to drink",
                        "readytocook" to "ready to cook", "readymade" to "ready-made", "highfibre" to "high fibre",
                        "highprotein" to "high protein", "lowcalorie" to "low calorie", "lowsugar" to "low sugar",
                        "glutenfree" to "gluten-free", "lactosefree" to "lactose-free")[property] ?: property
                }
                val evidence = if (headerMissing.isNotEmpty()) "$header: ${item.sourceText}" else item.sourceText
                review += "$evidence\nYour words mention $labels, but this proposed item does not require it. Check whether you meant to remove that requirement."
            }
            !localScope.containsAll(proposed) -> review +=
                "Shared wording: $header\nFor ${item.sourceText}, Beta understood ${item.name}. Check this in your basket."
        }
    }
    return GroceryIntentGroundingResult(rejected.distinct(), review.distinct())
}

private fun intentEvidenceTokens(text: String): Set<String> {
    // These are category synonyms, not inferred brands, variants or medical substitutions.
    val canonical = ProductLexicon.canonicalizeProductText(text)
        .replace(Regex("\\bhigh[\\s-]+fib(?:re|er)\\b"), "highfibre")
        .replace(Regex("\\bhigh[\\s-]+protein\\b"), "highprotein")
        .replace(Regex("\\blow[\\s-]+cal(?:orie|ories)?\\b"), "lowcalorie")
        .replace(Regex("\\blow[\\s-]+sugar\\b"), "lowsugar")
        .replace(Regex("\\bgluten[\\s-]+free\\b"), "glutenfree")
        .replace(Regex("\\blactose[\\s-]+free\\b"), "lactosefree")
        .replace(Regex("\\byoghurts?\\b"), "yogurt")
        .replace(Regex("\\byogurts\\b"), "yogurt")
        .replace(Regex("\\b(?:patti|leaves)\\b"), "leaf")
        .replace(Regex("\\b(?:dahi|curds)\\b"), "curd")
        .replace(Regex("\\b(?:aaloo|aloo)\\b"), "potato")
        .replace(Regex("\\b(?:pyaaz|pyaz)\\b"), "onion")
        .replace(Regex("\\b(?:gajar|gaajar)\\b"), "carrot")
        .replace(Regex("\\b(?:kheera|khira)\\b"), "cucumber")
        .replace(Regex("\\b(?:dal|daal)\\b"), "lentil")
        .replace(Regex("\\b(?:nimbu|neembu)\\b"), "lemon")
        .replace(Regex("\\b(?:kela|kele)\\b"), "banana")
        .replace(Regex("\\b(?:tamatar|tamaatar)\\b"), "tomato")
        .replace(Regex("\\bnariyal\\s+(?:pani|paani)\\b"), "coconut water")
        .replace(Regex("\\b(?:kothmir|kothimbir)\\b"), "coriander leaf")
        .replace(Regex("\\b(?:dhaniya|dhania)\\b"), "coriander")
        .replace(Regex("\\b(?:jeera|jira|cumin seeds?)\\b"), "cumin")
        .replace(Regex("\\b(?:murmura|mamra|muri)\\b"), "puffed rice")
        .replace(Regex("\\blaal\\b"), "red")
        .replace(Regex("\\b(?:kachcha|kachche|kaccha|kacche)\\b"), "raw")
        .replace(Regex("\\baam\\b"), "mango")
        .replace(Regex("\\bsabun\\b"), "soap")
        .replace(Regex("\\b(?:fox\\s*nuts?|lotus\\s+seeds?)\\b"), "makhana")
        // Literal request evidence: no catalogue assumptions, no stripping nutrition amounts.
        .replace(Regex("\\b(?:no|without)[\\s-]+added[\\s-]+sugar\\b"), "noaddedsugar")
        .replace(Regex("\\b(?:sugar[\\s-]*free|zero[\\s-]+sugar)\\b"), "sugarfree")
        .replace(Regex("\\blow[\\s-]*fat\\b"), "lowfat")
        .replace(Regex("\\b(?:tetra|tetta|tetera)[\\s-]*(?:packs?|paks?)\\b"), "tetrapack")
        .replace(Regex("\\bready[\\s-]*made\\b"), "readymade")
        .replace(Regex("\\bready[\\s-]*to[\\s-]*eat\\b"), "readytoeat")
        .replace(Regex("\\bready[\\s-]*to[\\s-]*drink\\b"), "readytodrink")
        .replace(Regex("\\bready[\\s-]*to[\\s-]*cook\\b"), "readytocook")
        .replace(Regex("(\\d)([a-z])"), "$1 $2")
        .replace(Regex("\\b(?:gm|gms|grams?)\\b"), "g")
        .replace(Regex("\\b(?:litres?|liters?|ltr)\\b"), "l")
    val plurals = mapOf("apples" to "apple", "carrots" to "carrot", "cucumbers" to "cucumber",
        "lemons" to "lemon", "oranges" to "orange", "lentils" to "lentil", "biscuits" to "biscuit",
        "cups" to "cup", "bottles" to "bottle", "packets" to "packet", "drops" to "drop",
        "chocolates" to "chocolate", "bars" to "bar", "berries" to "berry", "batteries" to "battery",
        "lozenges" to "lozenge", "tablets" to "tablet", "capsules" to "capsule",
        "eggs" to "egg", "bananas" to "banana", "tomatoes" to "tomato", "onions" to "onion",
        "potatoes" to "potato", "samosas" to "samosa", "mangoes" to "mango")
    return Regex("[\\p{L}\\p{N}]+").findAll(canonical).map { it.value }
        .filter { it !in setOf("and", "the", "of") }.map { plurals[it] ?: it }.toSet()
}
