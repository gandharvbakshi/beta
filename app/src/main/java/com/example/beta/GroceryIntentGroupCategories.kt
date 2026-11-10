package com.example.beta

import java.text.Normalizer
import java.util.Locale

/** Bounded category-heading proof; this never invents a product identity. */
internal data class GroceryIntentGroupCategoryHeader(
    val identity: String,
    val categories: Set<String>,
    val organizational: Boolean = false,
)

private val groceryCategoryHeads = linkedMapOf(
    "daals" to "pulses",
    "dals" to "pulses",
    "lentils" to "pulses",
    "pulses" to "pulses",
    "flours" to "flour",
    "flour" to "flour",
    "snacks" to "snacks",
    "biscuits" to "biscuits",
    "crackers" to "crackers",
    "dairy" to "dairy",
    "spices" to "spices",
)

private val groceryCategoryNouns = mapOf(
    "pulses" to setOf("dal", "lentil", "pulse"),
    "flour" to setOf("flour", "atta", "besan", "maida"),
    "snacks" to setOf("snack", "namkeen", "bhujia", "biscuit", "cracker"),
    "biscuits" to setOf("biscuit"),
    "crackers" to setOf("cracker"),
    "vegetables" to setOf(
        "tomato", "onion", "potato", "carrot", "cucumber", "capsicum", "drumstick", "brinjal",
        "okra", "cabbage", "cauliflower", "broccoli", "spinach", "coriander", "lettuce", "bean",
        "pea", "pumpkin", "radish", "beetroot", "turnip", "garlic", "ginger", "chilli", "corn",
        "mushroom", "lauki", "karela", "turai",
    ),
    "fruits" to setOf(
        "apple", "apples", "banana", "bananas", "orange", "oranges", "pomegranate", "pomegranates",
        "papaya", "papayas", "watermelon", "watermelons", "pear", "pears", "grape", "grapes",
        "seb", "kela", "kele", "santra", "santre", "anar", "papita", "tarbooz", "nashpati", "angoor",
    ),
    "dairy" to setOf("milk", "butter", "dahi", "curd", "yogurt", "yoghurt", "paneer", "cheese"),
    "spices" to setOf("turmeric", "cumin", "coriander", "pepper", "chilli", "cardamom", "clove",
        "cinnamon", "fennel", "mustard", "carom", "asafoetida", "nutmeg", "mace", "saffron",
        "fenugreek", "anise", "masala"),
)

private val groceryCategorySpiceWords = mapOf(
    "haldi" to "turmeric", "jeera" to "cumin", "jira" to "cumin", "dhaniya" to "coriander", "dhania" to "coriander",
    "elaichi" to "cardamom", "laung" to "clove", "dalchini" to "cinnamon", "saunf" to "fennel", "rai" to "mustard",
    "ajwain" to "carom", "hing" to "asafoetida", "mirch" to "chilli", "chili" to "chilli", "chillies" to "chilli",
    "laal" to "red", "lal" to "red", "kali" to "black", "cloves" to "clove", "seeds" to "seed", "pods" to "pod",
)

private val groceryCategoryProduceWords = mapOf(
    "tomatoes" to "tomato", "onions" to "onion", "potatoes" to "potato", "carrots" to "carrot",
    "cucumbers" to "cucumber", "drumsticks" to "drumstick", "brinjals" to "brinjal",
    "beans" to "bean", "peas" to "pea", "radishes" to "radish", "mushrooms" to "mushroom",
    "chillies" to "chilli", "leaves" to "leaf", "tamatar" to "tomato", "tamaatar" to "tomato",
    "aloo" to "potato", "aaloo" to "potato", "pyaz" to "onion", "pyaaz" to "onion",
    "gajar" to "carrot", "gaajar" to "carrot", "kheera" to "cucumber", "khira" to "cucumber",
    "bhindi" to "okra", "palak" to "spinach", "dhaniya" to "coriander", "dhania" to "coriander",
    "mooli" to "radish", "kela" to "banana", "kele" to "banana", "seb" to "apple",
    "santra" to "orange", "santre" to "orange", "anar" to "pomegranate", "papita" to "papaya",
    "tarbooz" to "watermelon", "nashpati" to "pear", "angoor" to "grape",
)

private val groceryCategorySingulars = mapOf(
    "daal" to "dal",
    "daals" to "dal",
    "dals" to "dal",
    "lentils" to "lentil",
    "pulses" to "pulse",
    "flours" to "flour",
    "snacks" to "snack",
    "biscuits" to "biscuit",
    "crackers" to "cracker",
    "packs" to "pack", "packets" to "packet", "bags" to "bag", "bottles" to "bottle",
    "cartons" to "carton", "cups" to "cup", "cans" to "can", "tins" to "tin",
    "tubs" to "tub", "jars" to "jar", "boxes" to "box", "bars" to "bar",
    "blocks" to "block", "loaves" to "loaf", "bunches" to "bunch", "rolls" to "roll",
    "refills" to "refill", "pouches" to "pouch", "sachets" to "sachet", "tubes" to "tube", "sprays" to "spray",
    "slices" to "slice",
)

// These forms are projected only to prove dairy product membership. They are
// still retained as required identity words and must survive elsewhere.
private val groceryDairyContainers = setOf(
    "pack", "packet", "bag", "bottle", "carton", "cup", "can", "tin", "tub", "jar", "box", "pouch", "sachet", "tube",
)
// An enclosing package is narrower than a sale form (e.g. 'bar' or 'refill').
internal fun isLiteralContentContainer(raw: String): Boolean =
    groceryIntentRetailContainerKey(raw)?.let { it == "tetra_pack" || it in groceryDairyContainers } == true

private val namedPlantIngredients = setOf(
    "oat", "oats", "almond", "almonds", "soy", "soya", "coconut", "pea", "cashew", "peanut", "peanuts",
    "cocoa", "cacao", "rice", "walnut", "walnuts", "hazelnut", "hazelnuts", "flax", "flaxseed", "sunflower",
    "sesame", "hemp", "nut", "nuts", "seed", "seeds",
)
internal fun isLiteralPlantIngredient(word: String): Boolean = word in namedPlantIngredients
private val groceryDairyConflicts = setOf(
    "no", "not", "non", "without", "vegan", "plant", "shea", "margarine", "nondairy", "dairyfree", "milkfree", "plantbased",
) + namedPlantIngredients

private val groceryUnsafePrefixWords = setOf(
    "no", "not", "without", "non", "un", "except", "unless", "only", "instead", "or", "ya",
    "and", "aur", "for", "with", "containing", "contains", "coated", "coating", "flavored",
    "flavoured", "flavour", "flavor", "style",
)

private val groceryCategoryHeadPattern = groceryCategoryHeads.keys
    .sortedByDescending { it.length }
    .joinToString("|", prefix = "(?:", postfix = ")") { Regex.escape(it) }
private val groceryCategorySuffixRegex = Regex(
    "^(.+?)\\s+($groceryCategoryHeadPattern(?:\\s*(?:\\band\\b|\\baur\\b|&)\\s*$groceryCategoryHeadPattern){0,2})$",
    RegexOption.IGNORE_CASE,
)
private val groceryCategoryJoinerRegex = Regex("\\s*(?:\\band\\b|\\baur\\b|&)\\s*", RegexOption.IGNORE_CASE)
private val groceryUnicodeWordsRegex = Regex("[\\p{L}\\p{N}]+")
private val groceryIdentityCharacterRegex = Regex("[\\p{L}\\p{N}_\\s'’.-]+")
private const val GROCERY_LITERAL_AMOUNT = "[0-9]+(?:\\.[0-9]+)?"
private val groceryLeadingAmount = Regex(
    "^\\s*$GROCERY_LITERAL_AMOUNT\\s+",
)
private val groceryTrailingPack = Regex(
    "\\s*\\(\\s*$GROCERY_LITERAL_AMOUNT\\s*(?:kg|keji|g|gm|gms|grams?|ml|l|litres?|liters?|ltr)\\s*(?:each\\s*)?\\)\\s*$",
    RegexOption.IGNORE_CASE,
)

private fun groceryNfkc(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)

private fun groceryWords(value: String): List<String> =
    groceryUnicodeWordsRegex.findAll(groceryNfkc(value).lowercase(Locale.ROOT)).map { it.value }.toList()

private fun grocerySemanticWords(value: String): List<String> {
    // Runtime callers already proved numeric roles. Only these bounded decimal
    // scaffolds are removable; never erase 'saat' in saat anaj or 'bar' in a
    // snack bar. Unit/container words must survive as typed fields/properties.
    // Written quantities need future exact-span support.
    val bounded = groceryTrailingPack.replaceFirst(groceryLeadingAmount.replaceFirst(value, ""), " ")
    return groceryWords(bounded).map { groceryCategorySingulars[it] ?: it }
}

private fun groceryCategoryWords(proof: GroceryIntentGroupCategoryHeader, value: String): List<String> {
    val words = grocerySemanticWords(value)
    if (proof.categories == setOf("spices")) return words.map { groceryCategorySpiceWords[it] ?: it }
    return if (proof.organizational) words.map { groceryCategoryProduceWords[it] ?: it } else words
}

internal fun groceryIntentGroupCategoryHeader(
    header: String,
    localQuantities: Boolean = false,
): GroceryIntentGroupCategoryHeader? {
    val organizational = groceryIntentOrganizationalCategoryHeading(header)
    if (organizational != null) {
        return GroceryIntentGroupCategoryHeader("", setOf(organizational), organizational = true)
    }
    val localIdentity = if (localQuantities) groceryIntentLocalQuantityHeadingIdentity(header) else null
    val hasLocalHeading = localIdentity != null
    if (localIdentity != null && localIdentity.lowercase(Locale.ROOT) in setOf("fruit", "fruits", "phal")) {
        return GroceryIntentGroupCategoryHeader("", setOf("fruits"))
    }
    val categoryHeader = localIdentity ?: header
    val bounded = groceryNfkc(categoryHeader).trim().trimEnd(':', '：', '—', '–', '‒', '-').trim()
    val match = groceryCategorySuffixRegex.matchEntire(bounded) ?: return null
    val identity = match.groupValues[1].trim()
    val prefixWords = groceryWords(identity).toSet()
    val childNouns = groceryCategoryNouns.values.flatten().toSet()
    if (!groceryIdentityCharacterRegex.matches(identity) ||
        prefixWords.any { it in groceryUnsafePrefixWords || it in groceryCategoryHeads } ||
        grocerySemanticWords(identity).any { it in childNouns }
    ) return null

    val heads = groceryCategoryJoinerRegex.split(match.groupValues[2].lowercase(Locale.ROOT))
    val categories = heads.map { groceryCategoryHeads[it.trim()] ?: return null }
    // A dairy suffix can also be a brand name (for example, Mother Dairy).
    // Only a complete verified per-item quantity heading establishes category scope.
    if ("dairy" in categories && !hasLocalHeading) return null
    if (categories.distinct().size != categories.size) return null
    // Singular "flour" can be a product or brand head; collective flour is plural or mixed.
    if (categories.size == 1 && heads.first() == "flour") return null
    if (categories.indices.any { left ->
            (left + 1 until categories.size).any { right ->
                groceryCategoryNouns.getValue(categories[left]).any { it in groceryCategoryNouns.getValue(categories[right]) }
            }
        }
    ) return null

    return GroceryIntentGroupCategoryHeader(identity, categories.toSet())
}

private fun groceryDairyConflict(words: List<String>): Boolean =
    words.any { it in groceryDairyConflicts } || words.zipWithNext().any { (left, right) ->
        (left == "dairy" || left == "milk") && right == "free"
    }

private fun groceryCategoryWitnesses(proof: GroceryIntentGroupCategoryHeader, primary: String): Set<String> {
    val words = groceryCategoryWords(proof, primary)
    if (words.isEmpty()) return emptySet()
    val terminalNoun = words.last()
    val result = proof.categories.filterTo(mutableSetOf()) { category ->
        category != "dairy" && terminalNoun in groceryCategoryNouns.getValue(category)
    }
    if (proof.organizational && "vegetables" in proof.categories &&
        words.takeLast(2) in listOf(listOf("coriander", "leaf"), listOf("spinach", "leaf"))
    ) result += "vegetables"
    // Only the product head proves membership, not an ingredient elsewhere.
    if ("spices" in proof.categories && words.size >= 2 && terminalNoun in setOf("powder", "seed", "pod", "whole", "ground") &&
        words[words.lastIndex - 1] in groceryCategoryNouns.getValue("spices")) result += "spices"
    if ("dairy" in proof.categories && !groceryDairyConflict(words)) {
        val product = if (terminalNoun in groceryDairyContainers) words.dropLast(1) else words
        if (product.isNotEmpty() &&
            (product.last() in groceryCategoryNouns.getValue("dairy") || product.takeLast(2) == listOf("cheese", "slice"))
        ) result += "dairy"
    }
    return result
}

internal fun groceryIntentGroupCategoryIssue(
    header: String, primary: String, proposed: String, requirements: List<String> = emptyList(),
    localQuantities: Boolean = false,
): String? {
    val proof = groceryIntentGroupCategoryHeader(header, localQuantities) ?: return null
    // Dairy is common in brand names; do not inherit the heading's brand for
    // a source item that did not itself state that identity.
    if ("dairy" in proof.categories &&
        !grocerySemanticWords(primary).toSet().containsAll(grocerySemanticWords(proof.identity).toSet())
    ) return "group_category_shared_identity_unproven"
    if ("dairy" in proof.categories && requirements.any { groceryDairyConflict(grocerySemanticWords(it)) }) {
        return "group_category_membership_unproven"
    }
    val own = groceryCategoryWitnesses(proof, primary)
    if (own.size != 1 || own != groceryCategoryWitnesses(proof, proposed)) {
        return "group_category_membership_unproven"
    }
    val identity = groceryCategoryWords(proof, proposed + " " + requirements.joinToString(" ")).toSet()
    if (!identity.containsAll(groceryCategoryWords(proof, primary).toSet())) {
        return "group_category_identity_lost"
    }
    if (!identity.containsAll(groceryCategoryWords(proof, proof.identity).toSet())) {
        return "group_category_shared_identity_lost"
    }
    return null
}

internal fun groceryIntentGroupCategoryCoverageIssue(
    header: String,
    members: List<Pair<String, String>>,
    requirements: List<List<String>> = List(members.size) { emptyList() },
    localQuantities: Boolean = false,
): String? {
    val proof = groceryIntentGroupCategoryHeader(header, localQuantities) ?: return null
    require(requirements.size == members.size)
    val witnessed = mutableSetOf<String>()
    for ((index, member) in members.withIndex()) {
        val (primary, proposed) = member
        val issue = groceryIntentGroupCategoryIssue(header, primary, proposed, requirements[index], localQuantities)
        if (issue != null) return issue
        witnessed += groceryCategoryWitnesses(proof, primary)
    }
    return if (witnessed == proof.categories) null else "group_category_missing_member"
}

internal fun groceryIntentGroupCategoryGroundingHeader(
    header: String, @Suppress("UNUSED_PARAMETER") primary: String, localQuantities: Boolean = false,
): String {
    val proof = groceryIntentGroupCategoryHeader(header, localQuantities)
    return proof?.identity ?: if (localQuantities) groceryIntentLocalQuantityHeadingIdentity(header) ?: header else header
}

internal fun groceryIntentGroupCategoryAliases(
    header: String, primary: String, localQuantities: Boolean = false,
): List<String> {
    val proof = groceryIntentGroupCategoryHeader(header, localQuantities) ?: return emptyList()
    val own = groceryCategoryWitnesses(proof, primary)
    if (own.size != 1) return emptyList()
    return when (own.single()) {
        "pulses" -> listOf("dal", "lentil", "pulse")
        "flour" -> listOf("flour")
        "snacks" -> listOf("snack")
        "biscuits" -> listOf("biscuit")
        "crackers" -> listOf("cracker")
        else -> emptyList()
    }
}
