package com.example.beta

import android.app.Activity
import android.os.SystemClock
import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.SwiggyMcpClient.Recommendations
import com.example.beta.SwiggyMcpClient.RequestedItem
import com.example.beta.SwiggyMcpClient.SwiggyAddress
import com.example.beta.SwiggyMcpClient.SwiggyMcpResult
import com.example.beta.automation.InstructionParser
import com.example.beta.automation.ParsedItem
import com.example.beta.automation.Preference
import com.example.beta.automation.PreferenceStore
import com.example.beta.automation.Quantity
import com.example.beta.automation.backendInputText

internal const val MAX_SWIGGY_MCP_ITEMS = 50
internal const val SWIGGY_RECOMMENDATION_BATCH_SIZE = 25
internal const val SWIGGY_ADDRESS_PAGE_SIZE = 8

internal fun prepareSwiggyMcpItems(
    instruction: String,
    lookup: (String) -> Preference?,
): List<ParsedItem> {
    val sanitized = CommerceProviderRouter.sanitizeOrderInstruction(instruction)
    return InstructionParser.applyPreferences(
        InstructionParser.parse(sanitized),
        lookup = lookup,
    )
}

internal fun swiggyMcpItemValidationMessage(
    instruction: String,
    items: List<ParsedItem>,
): String? {
    if (items.size > MAX_SWIGGY_MCP_ITEMS) {
        return "Swiggy supports up to $MAX_SWIGGY_MCP_ITEMS items in one Beta cart run. Please split this list so nothing is skipped."
    }
    val oversizedCountQuery = Regex("\\b([1-9]\\d+)\\s+([A-Za-z][\\w-]*)", RegexOption.IGNORE_CASE)
        .findAll(instruction)
        .mapNotNull { match ->
            val count = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val followingWord = match.groupValues[2].lowercase()
            val isMeasureOrPack = followingWord in setOf(
                "g", "gm", "gms", "gram", "grams", "kg", "kgs",
                "ml", "l", "ltr", "liter", "litre", "liters", "litres",
                "pack", "pk", "pc", "pcs", "piece", "pieces",
            )
            if (count > 20 && !isMeasureOrPack) followingWord else null
        }
        .firstOrNull()
    oversizedCountQuery?.let {
        return "Swiggy supports up to quantity 20 per item. Please reduce the quantity for $it; nothing was changed."
    }
    if (items.any { it.quantitySignal != null }) {
        return "Please write the quantity using digits, for example 2 milk or 500 g rice. Beta could not safely understand a quantity; nothing was changed."
    }
    if (items.any { (it.quantity as? Quantity.Count)?.n?.let { n -> n !in 1..20 } == true }) {
        return "Swiggy supports quantity 1 to 20 per item, including repeated items. Please reduce the quantity; nothing was changed."
    }
    if (items.groupBy { it.query }.any { (_, repeated) -> repeated.size > 1 }) {
        return "One product appears with different quantities or preferences. Please combine it into one clear line; nothing was changed."
    }
    if (Regex("(?:^|[\\s,;])(?:[-−]\\d+(?:\\.\\d+)?|0(?:\\.0+)?)\\s*(?=\\p{L})").containsMatchIn(instruction)) {
        return "Please use a positive quantity, or remove that item from your list. Nothing was changed."
    }
    return null
}

internal fun swiggyNoCandidateMessage(
    item: ParsedItem,
    address: SwiggyAddress,
): String {
    val strictMatchPhrase = item.strictMatchPhrase?.trim().takeIf { !it.isNullOrBlank() }
    return if (strictMatchPhrase != null) {
        "I could not find your preferred exact product, $strictMatchPhrase, at your selected address (${address.shortLabel}). Beta did not substitute it. Nothing was added."
    } else {
        "I could not find ${item.query} on Swiggy Instamart. Nothing was added."
    }
}

internal fun isSafeSwiggyCartPlan(
    plan: SwiggyMcpClient.CartPlan,
    selected: List<RequestedItem>,
): Boolean {
    val requestedQuantities = selected
        .groupBy { it.spinId }
        .mapValues { (_, items) -> items.maxOf { it.quantity } }
    val seen = mutableSetOf<String>()
    val safeKinds = setOf("add", "increase", "change", "update")
    return plan.changes.isNotEmpty() && plan.changes.all { change ->
        val spinId = change.spinId?.trim().orEmpty()
        val kind = change.kind.trim().lowercase()
        val requestedQuantity = requestedQuantities[spinId]
        val from = change.fromQuantity
        val to = change.toQuantity
        spinId.isNotBlank() &&
            kind in safeKinds &&
            seen.add(spinId) &&
            requestedQuantity != null &&
            from != null && from >= 0 &&
            to != null && to > from && to == requestedQuantity
    }
}

internal fun swiggyCartStartsEmpty(plan: SwiggyMcpClient.CartPlan): Boolean =
    plan.existingItemCount == 0

internal fun areSwiggyRecommendationsOrdered(
    recommendations: List<Recommendations>,
    queries: List<String>,
): Boolean {
    return recommendations.size == queries.size &&
        recommendations.zip(queries).all { (recommendation, query) ->
            recommendation.query?.equals(query, ignoreCase = true) == true
        }
}

internal fun rememberedSwiggyAddress(
    addresses: List<SwiggyAddress>,
    rememberedAddressId: String?,
): SwiggyAddress? {
    val remembered = rememberedAddressId
        ?.takeIf { it.isNotBlank() }
        ?.let { id -> addresses.firstOrNull { it.id == id } }
        ?: return null
    val currentCartAddress = addresses.firstOrNull(SwiggyAddress::hasCurrentCart)
    return remembered.takeIf { currentCartAddress == null || currentCartAddress.id == remembered.id }
}

internal fun isRememberedSwiggyAddressFresh(
    selectedAtElapsedRealtime: Long,
    nowElapsedRealtime: Long,
    ttlMillis: Long,
): Boolean = selectedAtElapsedRealtime > 0L &&
    nowElapsedRealtime >= selectedAtElapsedRealtime &&
    nowElapsedRealtime - selectedAtElapsedRealtime <= ttlMillis

internal fun swiggyAddressChoiceLabel(address: SwiggyAddress): String {
    return address.shortLabel.trim().ifBlank { "Saved Swiggy address" }
}

internal fun swiggyAddressChoiceLabels(addresses: List<SwiggyAddress>): List<String> {
    val baseLabels = addresses.map(::swiggyAddressChoiceLabel)
    val counts = baseLabels.groupingBy { it.lowercase() }.eachCount()
    return baseLabels.mapIndexed { index, label ->
        if (counts[label.lowercase()] == 1) label else "$label — saved address ${index + 1}"
    }
}

internal fun swiggySpokenAddressConfirmation(address: SwiggyAddress): String {
    val category = address.categoryLabel.trim().ifBlank { "saved address" }
    val detail = address.confirmationDetail
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.replace(Regex("\\b\\d{6}\\b"), "")
        ?.replace(Regex("\\s+"), " ")
        ?.trim(' ', ',')
        ?.split(' ')
        ?.take(16)
        ?.joinToString(" ")
        ?.takeIf(String::isNotBlank)
    return if (detail == null) "You selected $category." else "You selected $category, $detail."
}

internal fun swiggyAddressSuggestionDetail(
    address: SwiggyAddress,
    index: Int,
    suggestionReasons: Map<String, String>,
): String {
    val reason = suggestionReasons[address.id] ?: return "Saved in Swiggy"
    return if (index == 0) "Suggested · $reason" else reason
}

internal fun swiggyAddressLocationNotice(assessment: SwiggyLocationAssessment): String = when (assessment) {
    SwiggyLocationAssessment.UNKNOWN -> "Location is unavailable or not reliable. You can still use a saved address. Please check the house or flat and area."
    SwiggyLocationAssessment.AREA_MATCH -> "This address matches your current area, not an exact GPS distance. Please check the house or flat."
    SwiggyLocationAssessment.NOT_MATCHED -> "This address could not be matched to your current area. Please confirm this is where you want the groceries, or choose a different address."
}

internal fun swiggyMatchedWithoutQuestionMessage(matchedCount: Int): String? = when (matchedCount) {
    0 -> null
    1 -> "1 other item matched without a question. Choose one exact pack for this item."
    else -> "$matchedCount other items matched without a question. Choose one exact pack for this item."
}

internal fun swiggyCandidateDetail(candidate: RecommendationCandidate): String? {
    val label = candidate.label.trim()
    return listOfNotNull(candidate.variant, candidate.subtitle)
        .map(String::trim)
        .filter { it.isNotBlank() && !label.contains(it, ignoreCase = true) }
        .distinctBy(String::lowercase)
        .joinToString(" · ")
        .ifBlank { null }
}

internal fun swiggyCandidateLabel(candidate: RecommendationCandidate): String {
    val label = candidate.label.trim()
    val extras = listOfNotNull(candidate.variant, candidate.subtitle)
        .map(String::trim)
        .filter { it.isNotBlank() && !label.contains(it, ignoreCase = true) }
        .distinctBy(String::lowercase)
    return (listOf(label) + extras).filter(String::isNotBlank).joinToString(" — ")
}

internal fun swiggyRecommendationQuery(item: ParsedItem): String {
    val quantityAware = when (item.quantity) {
        is Quantity.Count, is Quantity.Weight, is Quantity.Volume -> item.backendInputText()
        Quantity.Default -> item.query
    }
    return if (item.avoidPhrases.isEmpty()) quantityAware else {
        "$quantityAware without ${item.avoidPhrases.joinToString(" or ")}"
    }
}

private val candidatePieceCountRegex = Regex(
    "\\b([1-9]\\d?)\\s*(?:pieces?|pcs?|eggs?)\\b",
    RegexOption.IGNORE_CASE,
)

internal fun isSwiggyCandidateCountCompatible(
    item: ParsedItem,
    candidate: RecommendationCandidate,
): Boolean {
    if (item.quantity is Quantity.Weight || item.quantity is Quantity.Volume) {
        return swiggyMeasuredPackQuantity(item, candidate) != null
    }
    val requested = item.quantity as? Quantity.Count ?: return true
    val explicitPackCount = candidatePieceCountRegex
        .find(listOfNotNull(candidate.label, candidate.variant, candidate.subtitle).joinToString(" "))
        ?.groupValues
        ?.get(1)
        ?.toIntOrNull()
    return explicitPackCount == null || explicitPackCount == requested.n
}

internal fun swiggyRequestedCartQuantity(
    item: ParsedItem,
    candidate: RecommendationCandidate,
): Int {
    if (item.quantity is Quantity.Weight || item.quantity is Quantity.Volume) {
        return requireNotNull(swiggyMeasuredPackQuantity(item, candidate)) { "Unverified pack measure" }
    }
    val requested = item.quantity as? Quantity.Count ?: return 1
    return if (isSwiggyCandidateCountCompatible(item, candidate) && candidatePieceCountRegex.containsMatchIn(
            listOfNotNull(candidate.label, candidate.variant, candidate.subtitle).joinToString(" ")
        )
    ) 1 else requested.n
}

/** Exact, divisible measures only. An unknown or mixed multipack is not guessed. */
internal fun swiggyMeasuredPackQuantity(item: ParsedItem, candidate: RecommendationCandidate): Int? {
    val text = listOfNotNull(candidate.label, candidate.variant, candidate.subtitle).joinToString(" ")
    val trueMultipackPrefix = Regex("\\b\\d+(?:\\.\\d+)?\\s*[x×]\\s*(?=\\d)", RegexOption.IGNORE_CASE)
    val trueMultipackSuffix = Regex(
        "\\b\\d+(?:\\.\\d+)?\\s*(?:kg|kgs|g|gm|gms|grams?|ml|l|ltr|litres?|liters?)\\s*[x×]\\s*\\d+\\b",
        RegexOption.IGNORE_CASE,
    )
    if (trueMultipackPrefix.containsMatchIn(text) || trueMultipackSuffix.containsMatchIn(text) ||
        Regex("\\bpack\\s+of\\s+(?:[2-9]|[1-9]\\d+)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
    ) return null
    val measureRegex = Regex("\\b(\\d+(?:\\.\\d+)?)\\s*(kg|kgs|g|gm|gms|grams?|ml|l|ltr|litres?|liters?)\\b", RegexOption.IGNORE_CASE)
    val weight = item.quantity is Quantity.Weight
    val packSizes = measureRegex.findAll(text).mapNotNull { match ->
        val unit = match.groupValues[2].lowercase()
        val isWeight = unit in setOf("kg", "kgs", "g", "gm", "gms", "gram", "grams")
        if (isWeight != weight) return@mapNotNull null
        val factor = if (unit in setOf("kg", "kgs", "l", "ltr", "litre", "litres", "liter", "liters")) 1000 else 1
        val amount = match.groupValues[1].toDouble() * factor
        amount.toInt().takeIf { it > 0 && it.toDouble() == amount }
    }.distinct().toList()
    if (packSizes.size != 1) return null
    val requested = when (val quantity = item.quantity) {
        is Quantity.Weight -> quantity.grams
        is Quantity.Volume -> quantity.ml
        else -> return null
    }
    val packSize = packSizes.single()
    return (requested / packSize).takeIf { requested % packSize == 0 && it in 1..20 }
}

internal fun swiggySuggestionNeedsReview(item: ParsedItem, recommendation: Recommendations, hasPreferred: Boolean): Boolean =
    recommendation.requiresConfirmation || !hasPreferred || item.parserConfidence < 1f ||
        item.quantity is Quantity.Weight || item.quantity is Quantity.Volume || item.avoidPhrases.isNotEmpty() ||
        (item.strictMatchPhrase.isNullOrBlank() && item.query.trim().split(Regex("\\s+")).size == 1)

internal fun swiggyNeedsExactHealthProduct(item: ParsedItem): Boolean {
    val words = item.query.lowercase().split(Regex("[^\\p{L}]+" )).toSet()
    return words.any { it in setOf("cough", "khansi", "khaansi", "fever", "headache", "pain") } &&
        words.any { it in setOf("woh", "wo", "wali", "waali", "wala", "goli", "medicine", "dawai", "dawa", "something") }
}

internal fun consolidateSwiggyRequestedItems(selected: List<RequestedItem>): List<RequestedItem> =
    selected.groupBy { it.spinId }.values.map { sameProduct ->
        sameProduct.first().copy(quantity = sameProduct.sumOf { it.quantity })
    }

internal fun swiggyDefaultSuggestion(
    item: ParsedItem,
    usable: List<RecommendationCandidate>,
    preferred: RecommendationCandidate?,
): RecommendationCandidate {
    // Generic milk means ordinary drinking milk by default. Keep every compatible
    // variant available under Change, but don't let a flavoured purchase outrank it.
    val ordinaryMilk = if (item.query.trim().equals("milk", ignoreCase = true) && item.strictMatchPhrase.isNullOrBlank()) {
        val specialVariant = Regex("\\b(chocolate|strawberry|vanilla|rose|badam|kesar|flavou?red|shake|powder|condensed|coconut|almond|oat|soy|soya)\\b", RegexOption.IGNORE_CASE)
        usable.filter { !specialVariant.containsMatchIn(swiggyCandidateLabel(it)) }
    } else emptyList()
    val defaults = ordinaryMilk.ifEmpty { usable }
    return defaults.firstOrNull { it.spinId == preferred?.spinId } ?: defaults.first()
}

internal fun isSwiggyCandidateAllowed(item: ParsedItem, candidate: RecommendationCandidate): Boolean {
    if (!swiggyMatchesProductIdentity(item, swiggyCandidateLabel(candidate))) return false
    val label = swiggyCandidateLabel(candidate).lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ")
    return item.avoidPhrases.none { phrase ->
        val words = phrase.lowercase().trim().replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        // Explicit "lactose free" / "sugar free" is not the excluded ingredient.
        // Do not infer ingredient safety from an otherwise silent catalog label.
        val unnegated = label.replace(Regex("\\b${Regex.escape(words)} free\\b"), " ")
        words.isNotBlank() && Regex("(?:^| )${Regex.escape(words)}(?: |$)").containsMatchIn(unnegated)
    }
}

internal fun swiggyQuestionPosition(
    needsQuestion: List<Boolean>,
    itemIndex: Int,
): Pair<Int, Int>? {
    if (itemIndex !in needsQuestion.indices || !needsQuestion[itemIndex]) return null
    val total = needsQuestion.count { it }
    val current = needsQuestion.take(itemIndex + 1).count { it }
    return current to total
}

internal fun swiggyContinuableItemCount(
    selectedCount: Int,
    itemCount: Int,
    unavailableIndex: Int,
): Int = selectedCount.coerceAtLeast(0) + (itemCount - unavailableIndex - 1).coerceAtLeast(0)

internal fun swiggyProviderAddedMessage(message: String?): String? {
    val normalized = message?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val describesFreeProviderItem = normalized.contains("free", ignoreCase = true) &&
        (normalized.contains("gift", ignoreCase = true) || normalized.contains("sample", ignoreCase = true))
    return normalized.takeIf { describesFreeProviderItem }
}

/** Coordinates the user-visible, no-checkout Swiggy voice flow. */
class SwiggyVoiceOrderCoordinator(
    private val activity: Activity,
    private val announce: (String) -> Unit,
    private val onReconnectRequired: () -> Unit,
    private val onAddressChanged: (String?) -> Unit = {},
    private val onTerminal: () -> Unit = {},
    private val onVerified: (Int) -> Unit = {},
    private val onEditRequest: () -> Unit = {},
    private val beforeApply: () -> Boolean = { true },
    private val onCheckoutRequested: (String) -> Unit = {},
    private val isCheckoutActive: () -> Boolean = { false },
    private val checkReviewedCart: (String, (SwiggyMcpResult<Boolean>) -> Unit) -> Unit = { token, callback ->
        SwiggyMcpClient.checkCartPlan(activity, token, callback)
    },
) {
    private var running = false
    private var operationGeneration = 0L
    private var mutationInFlight = false
    private val pendingCartStore = SwiggyPendingCartStore(activity.applicationContext)
    private val cartReviewStore = SwiggyCartReviewStore(activity.applicationContext)
    private var hostResumed = false
    private var interruptedDuringRequest = false
    private var preparationInterrupted = false
    private var waitingForExistingRequest = false
    private val stepDialog = SwiggyOrderStepDialog(activity)
    private val addressIntelligence = SwiggyAddressIntelligence(activity.applicationContext)
    private var addressSuggestionReasons: Map<String, String> = emptyMap()
    private var addressLocationAssessments: Map<String, SwiggyLocationAssessment> = emptyMap()
    private var selectedAddressId: String? = null
    private var selectedAddressAtElapsedRealtime = 0L
    private val draftCandidates = mutableMapOf<Int, RecommendationCandidate>()
    private val draftSkipped = mutableSetOf<Int>()
    private var draftRecommendations: List<Recommendations> = emptyList()

    private fun clearDraft() {
        draftCandidates.clear()
        draftSkipped.clear()
        draftRecommendations = emptyList()
    }

    fun start(instruction: String) {
        if (isCheckoutActive()) {
            announce("Please finish checking your existing order before starting another cart.")
            return
        }
        if (restorePendingCartWarning()) return
        if (SwiggyCartMutationGuard.isInFlight()) {
            announce("Swiggy is still checking the last confirmed cart update. Please wait.")
            notifyTerminal()
            return
        }
        if (running) {
            announce("Beta is already working on your Swiggy cart.")
            return
        }
        val items = prepareSwiggyMcpItems(
            instruction = instruction,
            lookup = { PreferenceStore.lookup(activity, it) },
        )
        if (items.isEmpty()) {
            announce("I could not find a grocery item in that request. Please try again.")
            notifyTerminal()
            return
        }
        swiggyMcpItemValidationMessage(instruction, items)?.let { message ->
            announce(message)
            notifyTerminal()
            return
        }

        running = true
        clearDraft()
        val operationId = ++operationGeneration
        val addressCaption = "Checking your saved Swiggy delivery addresses."
        showYourList(operationId, items, addressCaption)
        announce(addressCaption)
        SwiggyMcpClient.fetchAddresses(activity) { result ->
            onUi(operationId) {
                when (result) {
                    is SwiggyMcpResult.Success -> rankAndChooseAddress(operationId, result.value, items)
                    is SwiggyMcpResult.Failure -> fail(operationId, result)
                }
            }
        }
    }

    fun cancel(): Boolean {
        if (mutationInFlight) return false
        operationGeneration += 1
        running = false
        clearDraft()
        dismissActiveDialog()
        notifyTerminal()
        return true
    }

    fun clearRememberedAddress(): Boolean {
        if (mutationInFlight) return false
        selectedAddressId = null
        selectedAddressAtElapsedRealtime = 0L
        onAddressChanged(null)
        return true
    }

    fun isMutationInFlight(): Boolean = mutationInFlight

    /** Detects Beta leaving the foreground, not which other app the user opened. */
    fun onHostPaused() {
        hostResumed = false
        if (mutationInFlight) {
            interruptedDuringRequest = true
        } else if (running) {
            preparationInterrupted = true
            cancel() // Invalidate the unconfirmed plan; the composer draft is preserved.
        }
    }

    fun onHostResumed(suppressCartReview: Boolean = false): Boolean {
        hostResumed = true
        if (suppressCartReview || isCheckoutActive()) return true
        if (SwiggyCartMutationGuard.isInFlight()) {
            waitingForExistingRequest = !mutationInFlight
            return true
        }
        if (running) return true
        if (pendingCartStore.isPending()) {
            checkPendingCartOnReturn()
            return true
        }
        if (preparationInterrupted) {
            preparationInterrupted = false
            announce(activity.getString(R.string.swiggy_preparation_interrupted))
            return true
        }
        return false
    }

    fun onSharedRequestSettled() {
        if (hostResumed && waitingForExistingRequest && !SwiggyCartMutationGuard.isInFlight()) {
            waitingForExistingRequest = false
            onHostResumed()
        }
    }

    private fun clearPendingCartReview(): Boolean = cartReviewStore.clear() && pendingCartStore.clear()

    private fun checkPendingCartOnReturn() {
        if (!hostResumed || running || SwiggyCartMutationGuard.isInFlight()) return
        val token = cartReviewStore.load()
        if (token == null) {
            restorePendingCartWarning()
            return
        }
        running = true
        interruptedDuringRequest = false
        val operationId = ++operationGeneration
        setMutationInFlight(true) // Block new writes while this read-only check is unresolved.
        val message = activity.getString(R.string.swiggy_return_check_message)
        stepDialog.show(SwiggyStepScreen(
            eyebrow = "Read-only check",
            title = "Checking your cart and address",
            message = message,
            caption = message,
            safetyNote = activity.getString(R.string.swiggy_keep_closed_warning),
        ))
        announce(message)
        checkReviewedCart(token) { result ->
            onUi(operationId) {
                running = false
                setMutationInFlight(false)
                if (!hostResumed || interruptedDuringRequest) {
                    stepDialog.dismiss()
                    if (hostResumed) restorePendingCartWarning()
                    return@onUi
                }
                if (result is SwiggyMcpResult.Success && result.value && clearPendingCartReview()) {
                    val checked = activity.getString(R.string.swiggy_return_check_matches)
                    stepDialog.show(SwiggyStepScreen(
                        eyebrow = "Checked now",
                        title = "Your cart and address still match",
                        message = checked,
                        caption = checked,
                        safetyNote = activity.getString(R.string.swiggy_handoff_warning),
                        primary = SwiggyStepAction("Done") { stepDialog.dismiss() },
                        cancel = { stepDialog.dismiss() },
                    ))
                    // This is not another addition or activation event.
                    announce(checked)
                    notifyTerminal()
                } else {
                    val failure = result as? SwiggyMcpResult.Failure
                    if (failure?.reconnectRequired == true) onReconnectRequired()
                    val warning = if (failure?.httpCode == 409) {
                        activity.getString(R.string.swiggy_return_check_changed)
                    } else activity.getString(R.string.swiggy_return_check_unknown)
                    restorePendingCartWarning(warning)
                }
            }
        }
    }

    fun restorePendingCartWarning(message: String? = null): Boolean {
        if (running || SwiggyCartMutationGuard.isInFlight() || !pendingCartStore.isPending()) return false
        val warning = message ?: activity.getString(R.string.swiggy_return_check_unknown)
        stepDialog.show(SwiggyStepScreen(
            eyebrow = "Review needed",
            title = "Check your last cart update",
            message = warning,
            caption = warning,
            safetyNote = activity.getString(R.string.swiggy_handoff_warning),
            primary = SwiggyStepAction("Open Swiggy to review") {
                CommerceAppLauncher.launchPreferred(activity, "open swiggy")
                stepDialog.dismiss()
            },
            secondary = SwiggyStepAction("I have reviewed the cart") {
                if (clearPendingCartReview()) {
                    stepDialog.dismiss()
                    announce("You can now start a new list. Beta has not changed the cart.")
                    notifyTerminal()
                } else announce("Beta could not save that confirmation. Please try again.")
            },
            tertiary = cartReviewStore.load()?.let {
                SwiggyStepAction("Check again without adding") { checkPendingCartOnReturn() }
            },
            cancel = { stepDialog.dismiss() },
        ))
        announce(warning)
        notifyTerminal()
        return true
    }

    private fun rankAndChooseAddress(
        operationId: Long,
        addresses: List<SwiggyAddress>,
        items: List<ParsedItem>,
    ) {
        SwiggyMcpClient.fetchRecentAddressIds(activity) { result ->
            onUi(operationId) {
                val recentAddressIds = (result as? SwiggyMcpResult.Success)?.value.orEmpty()
                addressIntelligence.rank(addresses, recentAddressIds) { ranked ->
                    onUi(operationId) {
                        addressSuggestionReasons = ranked
                            .mapNotNull { entry -> entry.reason?.let { entry.address.id to it } }
                            .toMap()
                        addressLocationAssessments = ranked.associate { it.address.id to it.locationAssessment }
                        chooseAddress(operationId, ranked.map(RankedSwiggyAddress::address), items)
                    }
                }
            }
        }
    }

    private fun showYourList(operationId: Long, items: List<ParsedItem>, caption: String) {
        stepDialog.show(
            SwiggyStepScreen(
                eyebrow = "Your list · ${items.size} items",
                title = "Here is what Beta understood",
                message = "Beta will confirm your saved delivery address, search live Instamart stock, and show every cart change before adding anything.",
                caption = caption,
                rows = items.mapIndexed { index, item ->
                    SwiggyStepRow(
                        title = displayItem(item),
                        detail = "Item ${index + 1} of ${items.size}",
                        badge = "Ready",
                        tone = SwiggyStepTone.SUCCESS,
                    )
                },
                safetyNote = activity.getString(R.string.swiggy_step_no_checkout),
                cancel = { cancelAndDismiss(operationId, "Swiggy cart changes cancelled.") },
            )
        )
    }

    private fun chooseAddress(
        operationId: Long,
        addresses: List<SwiggyAddress>,
        items: List<ParsedItem>,
        pageIndex: Int = 0,
        allowRememberedAddress: Boolean = true,
    ) {
        val usable = addresses.filter { it.id.isNotBlank() }.distinctBy { it.id }.take(MAX_ADDRESSES)
        if (usable.isEmpty()) {
            finish(operationId, "No saved Swiggy delivery address was found. Add an address in Swiggy, then try again.")
            return
        }
        if (
            allowRememberedAddress && isRememberedSwiggyAddressFresh(
                selectedAtElapsedRealtime = selectedAddressAtElapsedRealtime,
                nowElapsedRealtime = SystemClock.elapsedRealtime(),
                ttlMillis = ADDRESS_CONFIRMATION_TTL_MS,
            )
        ) {
            rememberedSwiggyAddress(usable, selectedAddressId)?.let { remembered ->
                onAddressChanged(remembered.shortLabel)
                val ageMinutes = ((SystemClock.elapsedRealtime() - selectedAddressAtElapsedRealtime) / 60_000L)
                    .coerceAtLeast(0L)
                val caption = "Using ${remembered.shortLabel}, the Swiggy address you confirmed earlier in this session."
                stepDialog.show(
                    SwiggyStepScreen(
                        eyebrow = "Step 1 of 4 · Delivery address",
                        title = activity.getString(
                            R.string.swiggy_step_address_reassure_title,
                            remembered.shortLabel,
                        ),
                        message = "All ${items.size} items will be searched at this saved address. ${addressLocationNotice(remembered)}",
                        caption = caption,
                        rows = listOf(
                            SwiggyStepRow(
                                title = "${remembered.shortLabel} - saved address",
                                detail = remembered.label,
                                badge = "Confirmed ${ageMinutes} min ago",
                                tone = SwiggyStepTone.SUCCESS,
                            )
                        ),
                        primary = SwiggyStepAction("Yes, use this address") {
                            if (isCurrent(operationId)) collectRecommendations(operationId, remembered, items)
                        },
                        secondary = SwiggyStepAction("Choose a different address") {
                            if (isCurrent(operationId)) restartAddressSelection(operationId, items)
                        },
                        tertiary = SwiggyStepAction(activity.getString(R.string.swiggy_step_cancel_list)) {
                            cancelAndDismiss(operationId, "Swiggy cart changes cancelled.")
                        },
                        cancel = { cancelAndDismiss(operationId, "Swiggy cart changes cancelled.") },
                    )
                )
                announce(caption)
                return
            }
        }
        clearRememberedAddress()
        val pageCount = (usable.size + SWIGGY_ADDRESS_PAGE_SIZE - 1) / SWIGGY_ADDRESS_PAGE_SIZE
        val safePageIndex = pageIndex.coerceIn(0, pageCount - 1)
        val pageStart = safePageIndex * SWIGGY_ADDRESS_PAGE_SIZE
        val pageEnd = minOf(pageStart + SWIGGY_ADDRESS_PAGE_SIZE, usable.size)
        val labels = swiggyAddressChoiceLabels(usable)
        val suggested = usable.firstOrNull()?.takeIf { addressSuggestionReasons.containsKey(it.id) }
        val caption = suggested?.let {
            "${it.shortLabel} is suggested. Choose it or another saved Swiggy delivery address. Showing ${pageStart + 1} to $pageEnd of ${usable.size}."
        } ?: "Choose the saved Swiggy delivery address for this cart. Showing ${pageStart + 1} to $pageEnd of ${usable.size}."
        stepDialog.show(
            SwiggyStepScreen(
                eyebrow = "Step 1 of 4 · Delivery address",
                title = "Where should Swiggy deliver?",
                message = "Choose one saved address. Location is only a suggestion; you may choose an address for someone else. " +
                    if (addressLocationAssessments.values.all { it == SwiggyLocationAssessment.UNKNOWN }) "Location is unavailable. Check the house or flat and area." else "Check the house or flat and area before choosing.",
                caption = caption,
                choices = (pageStart until pageEnd).map { index ->
                    SwiggyStepChoice(
                        title = labels[index],
                        detail = swiggyAddressSuggestionDetail(usable[index], index, addressSuggestionReasons) +
                            if (addressLocationAssessments[usable[index].id] == SwiggyLocationAssessment.NOT_MATCHED) " · Not matched to your current area" else "",
                        onClick = {
                            if (isCurrent(operationId)) {
                                rememberAddress(usable[index])
                                collectRecommendations(operationId, usable[index], items)
                            }
                        },
                    )
                },
                primary = if (pageEnd < usable.size) {
                    SwiggyStepAction("Show next ${minOf(SWIGGY_ADDRESS_PAGE_SIZE, usable.size - pageEnd)} addresses") {
                        if (isCurrent(operationId)) {
                            chooseAddress(
                                operationId,
                                usable,
                                items,
                                pageIndex = safePageIndex + 1,
                                allowRememberedAddress = false,
                            )
                        }
                    }
                } else null,
                secondary = if (safePageIndex > 0) {
                    SwiggyStepAction("Show previous addresses") {
                        if (isCurrent(operationId)) {
                            chooseAddress(
                                operationId,
                                usable,
                                items,
                                pageIndex = safePageIndex - 1,
                                allowRememberedAddress = false,
                            )
                        }
                    }
                } else null,
                tertiary = SwiggyStepAction(activity.getString(R.string.swiggy_step_cancel_list)) {
                    cancelAndDismiss(operationId, "Swiggy cart changes cancelled.")
                },
                cancel = { cancelAndDismiss(operationId, "Swiggy cart changes cancelled.") },
            )
        )
        announce(caption)
    }

    private fun collectRecommendations(
        operationId: Long,
        address: SwiggyAddress,
        items: List<ParsedItem>,
    ) {
        clearDraft()
        val queries = items.map(::swiggyRecommendationQuery)
        val strictMatchPhrases = items.map { it.strictMatchPhrase }
        showRecommendationProgress(operationId, address, items, completedCount = 0)
        fetchRecommendationChunk(
            operationId = operationId,
            address = address,
            items = items,
            queries = queries,
            strictMatchPhrases = strictMatchPhrases,
            offset = 0,
            accumulated = mutableListOf(),
        )
    }

    private fun showRecommendationProgress(
        operationId: Long,
        address: SwiggyAddress,
        items: List<ParsedItem>,
        completedCount: Int,
    ) {
        if (!isCurrent(operationId)) return
        val batchEnd = minOf(completedCount + SWIGGY_RECOMMENDATION_BATCH_SIZE, items.size)
        val caption = if (items.size <= SWIGGY_RECOMMENDATION_BATCH_SIZE) {
            "Finding options for all ${items.size} items using your recent Swiggy choices."
        } else {
            "Finding options for items ${completedCount + 1} to $batchEnd of ${items.size}."
        }
        stepDialog.show(
            SwiggyStepScreen(
                eyebrow = "Step 2 of 4 · Searching",
                title = activity.getString(R.string.swiggy_step_progress_title, items.size),
                message = activity.getString(R.string.swiggy_step_progress_detail),
                caption = caption,
                rows = items.mapIndexed { index, item ->
                    val completed = index < completedCount
                    val searching = index in completedCount until minOf(completedCount + 4, batchEnd)
                    val queuedInBatch = index in completedCount until batchEnd
                    SwiggyStepRow(
                        title = displayItem(item),
                        detail = when {
                            completed -> "Options found in your list order"
                            searching -> "Searching live stock at ${address.shortLabel}"
                            queuedInBatch -> "Queued in this search group"
                            else -> "Waiting in your list order"
                        },
                        badge = when {
                            completed -> "Found"
                            searching -> "Searching"
                            else -> "Queued"
                        },
                        tone = when {
                            completed -> SwiggyStepTone.SUCCESS
                            searching -> SwiggyStepTone.AMBER
                            else -> SwiggyStepTone.NEUTRAL
                        },
                    )
                },
                safetyNote = "Beta searches a few products at a time and keeps every result in the order you said it.",
                cancel = { cancelAndDismiss(operationId, "Swiggy cart changes cancelled.") },
            )
        )
        announce(caption)
    }

    private fun fetchRecommendationChunk(
        operationId: Long,
        address: SwiggyAddress,
        items: List<ParsedItem>,
        queries: List<String>,
        strictMatchPhrases: List<String?>,
        offset: Int,
        accumulated: MutableList<Recommendations>,
    ) {
        if (!isCurrent(operationId)) return
        if (offset >= queries.size) {
            if (!areSwiggyRecommendationsOrdered(accumulated, queries)) {
                finish(operationId, "Swiggy returned incomplete or out-of-order choices. Nothing was changed.")
                return
            }
            draftRecommendations = accumulated.toList()
            announce("Preparing one suggested basket for you to review.")
            chooseCandidate(operationId, address, items, accumulated, 0, mutableListOf())
            return
        }
        if (offset > 0) {
            showRecommendationProgress(operationId, address, items, completedCount = offset)
        }
        val end = minOf(offset + SWIGGY_RECOMMENDATION_BATCH_SIZE, queries.size)
        val batchQueries = queries.subList(offset, end)
        SwiggyMcpClient.fetchRecommendationBatch(
            context = activity,
            addressId = address.id,
            queries = batchQueries,
            strictMatchPhrases = strictMatchPhrases.subList(offset, end),
        ) { result ->
            onUi(operationId) {
                when (result) {
                    is SwiggyMcpResult.Success -> {
                        if (!areSwiggyRecommendationsOrdered(result.value, batchQueries)) {
                            finish(operationId, "Swiggy returned incomplete or out-of-order choices. Nothing was changed.")
                        } else {
                            accumulated += result.value
                            fetchRecommendationChunk(
                                operationId,
                                address,
                                items,
                                queries,
                                strictMatchPhrases,
                                offset = end,
                                accumulated = accumulated,
                            )
                        }
                    }
                    is SwiggyMcpResult.Failure -> fail(operationId, result)
                }
            }
        }
    }

    private fun chooseCandidate(
        operationId: Long,
        address: SwiggyAddress,
        items: List<ParsedItem>,
        recommendations: List<Recommendations>,
        index: Int,
        selected: MutableList<RequestedItem>,
        forceQuestion: Boolean = false,
    ) {
        if (!isCurrent(operationId)) return
        if (index >= items.size) {
            planCart(operationId, address, items, selected)
            return
        }
        if (index in draftSkipped) {
            chooseCandidate(operationId, address, items, recommendations, index + 1, selected)
            return
        }
        val item = items[index]
        val recommendation = recommendations[index]
        val usable = usableCandidates(item, recommendation)
        if (usable.isEmpty()) {
            showNoSubstitution(
                operationId = operationId,
                address = address,
                items = items,
                recommendations = recommendations,
            )
            return
        }

        val preferred = recommendation.suggested?.takeIf { candidate -> usable.any { it.spinId == candidate.spinId } }
        if (!forceQuestion) {
            val suggestion = draftCandidates[index]?.takeIf { saved -> usable.any { it.spinId == saved.spinId } }
                ?: swiggyDefaultSuggestion(item, usable, preferred)
            draftCandidates[index] = suggestion
            selected += suggestion.toRequestedItem(item)
            chooseCandidate(operationId, address, items, recommendations, index + 1, selected)
            return
        }

        val caption = "Which ${item.query} do you want?"
        stepDialog.show(
            SwiggyStepScreen(
                eyebrow = "Change one item",
                title = caption,
                message = "Choose a replacement, or keep the suggestion. Your other items stay the same; nothing is added until you confirm the whole basket.",
                caption = caption,
                choices = usable.map { candidate ->
                    SwiggyStepChoice(
                        title = candidate.label,
                        detail = listOfNotNull(swiggyCandidateDetail(candidate), if (item.quantity is Quantity.Weight || item.quantity is Quantity.Volume) "${swiggyRequestedCartQuantity(item, candidate)} pack(s) for your requested amount" else null).joinToString(" · ").ifBlank { null },
                        badge = if (candidate.spinId == preferred?.spinId) "Suggested" else null,
                        onClick = {
                            if (isCurrent(operationId)) {
                                draftCandidates[index] = candidate
                                chooseCandidate(++operationGeneration, address, items, recommendations, 0, mutableListOf())
                            }
                        },
                    )
                },
                safetyNote = if (item.quantity is Quantity.Count) {
                    "Beta checks pack count separately from cart quantity. One matching multi-piece pack is added as one cart line."
                } else null,
                secondary = SwiggyStepAction("Keep the suggestion") {
                    if (isCurrent(operationId)) {
                        chooseCandidate(++operationGeneration, address, items, recommendations, 0, mutableListOf())
                    }
                },
                tertiary = SwiggyStepAction(activity.getString(R.string.swiggy_step_cancel_list)) {
                    cancelAndDismiss(operationId, "Swiggy cart changes cancelled.")
                },
                cancel = { cancelAndDismiss(operationId, "Swiggy cart changes cancelled.") },
            )
        )
        announce(caption)
    }

    private fun planCart(
        operationId: Long,
        address: SwiggyAddress,
        items: List<ParsedItem>,
        selected: List<RequestedItem>,
    ) {
        val combined = consolidateSwiggyRequestedItems(selected)
        if (combined.isEmpty() || combined.any { it.quantity !in 1..20 }) {
            finish(operationId, "Please reduce or clarify the combined quantity for repeated products. Nothing was changed.")
            return
        }
        BetaTelemetry.instance?.logEvent(
            "product_discovery_completed",
            mapOf("item_count" to selected.size),
        )
        announce("Reading your current Swiggy cart and preparing the exact changes.")
        stepDialog.show(SwiggyStepScreen(
            eyebrow = "Preparing your review",
            title = "Checking the exact cart changes",
            message = "Your suggested basket is ready. Beta is checking the existing cart before showing one final confirmation.",
            caption = "Nothing has been added yet.",
            cancel = { cancelAndDismiss(operationId, "Swiggy cart changes cancelled.") },
        ))
        SwiggyMcpClient.planCart(activity, address.id, combined) { result ->
            onUi(operationId) {
                when (result) {
                    is SwiggyMcpResult.Success -> reviewPlan(operationId, address, items, combined, result.value)
                    is SwiggyMcpResult.Failure -> {
                        if (isAddressMismatch(result.userMessage)) {
                            showAddressMismatch(operationId, address, items, result.userMessage)
                        } else {
                            fail(operationId, result)
                        }
                    }
                }
            }
        }
    }

    private fun reviewPlan(
        operationId: Long,
        address: SwiggyAddress,
        items: List<ParsedItem>,
        selected: List<RequestedItem>,
        plan: SwiggyMcpClient.CartPlan,
    ) {
        val token = plan.confirmationToken
        if (plan.changes.isEmpty()) {
            finish(operationId, "No Swiggy cart change is needed.")
            return
        }
        if (token.isNullOrBlank() || !isSafeSwiggyCartPlan(plan, selected)) {
            finish(operationId, "Beta could not verify that the Swiggy cart plan only adds your selected items. Nothing was changed.")
            return
        }
        val cartStartsEmpty = swiggyCartStartsEmpty(plan)
        BetaTelemetry.instance?.logEvent(
            "cart_review_viewed",
            mapOf(
                "change_count" to plan.changes.size,
                "starts_empty" to cartStartsEmpty,
            ),
        )
        val rows = listOf(
            SwiggyStepRow(
                title = "Delivery address · ${address.categoryLabel}",
                detail = address.label,
                badge = "Please check",
                tone = SwiggyStepTone.AMBER,
            )
        ) + draftSkipped.sorted().map { skipped ->
            SwiggyStepRow(title = displayItem(items[skipped]), detail = "You chose to leave this item out.", badge = "Not added", tone = SwiggyStepTone.AMBER)
        } + plan.changes.map { change ->
            val from = change.fromQuantity ?: 0
            val to = change.toQuantity ?: from
            val draftIndex = draftCandidates.entries.firstOrNull { it.value.spinId == change.spinId }?.key
            val suggestedNote = draftIndex?.let { index ->
                if (swiggySuggestionNeedsReview(items[index], draftRecommendations[index], true)) "Suggested pack · " else ""
            }.orEmpty()
            SwiggyStepRow(
                title = change.displayName,
                detail = suggestedNote + if (from == 0) "Add $to pack(s) of this exact product" else "Keep the existing line and change only this quantity",
                badge = "$from → $to packs",
                action = draftIndex?.let { index ->
                    SwiggyStepAction("Change ${items[index].query}") {
                        if (isCurrent(operationId) && !mutationInFlight) {
                            chooseCandidate(++operationGeneration, address, items, draftRecommendations, index, mutableListOf(), forceQuestion = true)
                        }
                    }
                },
            )
        }
        val caption = "Review everything together. Nothing has been added yet."
        val addressConfirmation = swiggySpokenAddressConfirmation(address)

        if (!plan.cartMutationEnabled) {
            stepDialog.show(
                SwiggyStepScreen(
                    eyebrow = "Step 3 of 4 · Cart preview",
                    title = "Check these cart changes",
                    message = "$addressConfirmation Cart updates are not enabled on the Beta backend, so this is a preview only.",
                    caption = caption,
                    rows = rows,
                    safetyNote = activity.getString(R.string.swiggy_step_no_checkout),
                    primary = SwiggyStepAction("Done") {
                        completeAndDismiss(operationId, "Your Swiggy cart preview is ready. Nothing was changed.")
                    },
                    secondary = SwiggyStepAction("Change address") {
                        if (isCurrent(operationId)) restartAddressSelection(operationId, items)
                    },
                    cancel = { completeAndDismiss(operationId, "Your Swiggy cart preview is ready. Nothing was changed.") },
                )
            )
            announce(addressConfirmation)
            return
        }

        stepDialog.show(
            SwiggyStepScreen(
                eyebrow = "Step 4 of 4 · Review",
                title = "Your suggested basket",
                message = "$addressConfirmation ${addressLocationNotice(address)} " + activity.getString(
                    if (cartStartsEmpty) R.string.swiggy_step_review_empty else R.string.swiggy_step_review_existing
                ),
                caption = caption,
                rows = rows,
                safetyNote = "${activity.getString(R.string.swiggy_keep_closed_warning)} Check the suggested brands and packs together. Use Change only if needed. ${activity.getString(R.string.swiggy_step_no_checkout)}",
                primary = SwiggyStepAction(
                    if (cartStartsEmpty) "Add ${plan.changes.size} lines to cart" else "Apply ${plan.changes.size} cart changes"
                ) {
                    if (isCurrent(operationId)) applyPlan(operationId, token, address, items, selected)
                },
                secondary = SwiggyStepAction("Change address") {
                    if (isCurrent(operationId)) restartAddressSelection(operationId, items)
                },
                tertiary = SwiggyStepAction("Cancel - change nothing") {
                    cancelAndDismiss(operationId, "Swiggy cart changes cancelled.")
                },
                cancel = { cancelAndDismiss(operationId, "Swiggy cart changes cancelled.") },
            )
        )
        val skippedNotice = if (draftSkipped.isEmpty()) "" else " ${draftSkipped.size} items you chose to skip are not included."
        announce("$addressConfirmation ${addressLocationNotice(address)}$skippedNotice Please review the suggested brands and pack quantities together. ${activity.getString(R.string.swiggy_keep_closed_warning)} Nothing is added until you confirm. No order will be placed.")
    }

    private fun restartAddressSelection(
        operationId: Long,
        items: List<ParsedItem>,
    ) {
        if (!isCurrent(operationId)) return
        val nextOperationId = ++operationGeneration
        clearDraft()
        clearRememberedAddress()
        announce("Choose a different Swiggy address. Beta will search every item again for that address.")
        SwiggyMcpClient.fetchAddresses(activity) { result ->
            onUi(nextOperationId) {
                when (result) {
                    is SwiggyMcpResult.Success -> rankAndChooseAddress(nextOperationId, result.value, items)
                    is SwiggyMcpResult.Failure -> fail(nextOperationId, result)
                }
            }
        }
    }

    private fun rememberAddress(address: SwiggyAddress) {
        selectedAddressId = address.id
        selectedAddressAtElapsedRealtime = SystemClock.elapsedRealtime()
        addressIntelligence.recordSelection(address.id)
        onAddressChanged(address.shortLabel)
        BetaTelemetry.instance?.logEvent(
            "address_selected",
            mapOf("selection_reason" to (addressSuggestionReasons[address.id] ?: "saved_address")),
        )
    }

    private fun addressLocationNotice(address: SwiggyAddress): String = swiggyAddressLocationNotice(
        addressLocationAssessments[address.id] ?: SwiggyLocationAssessment.UNKNOWN,
    )

    private fun applyPlan(
        operationId: Long,
        token: String,
        address: SwiggyAddress,
        items: List<ParsedItem>,
        selected: List<RequestedItem>,
    ) {
        if (!isCurrent(operationId) || mutationInFlight || !hostResumed) return
        if (!pendingCartStore.markPending() || !cartReviewStore.save(token)) {
            finish(operationId, "Beta could not safely save the cart-check state. Nothing was added. Please try again.")
            return
        }
        if (!beforeApply()) {
            clearPendingCartReview()
            finish(operationId, "Beta could not safely save the change of state on this phone. Nothing was added. Please try your list again.")
            return
        }
        BetaTelemetry.instance?.logEvent("cart_apply_started", mapOf("item_count" to selected.size))
        interruptedDuringRequest = false
        setMutationInFlight(true)
        val caption = "Updating your Swiggy cart once, then checking that the changes stay. This takes about a minute."
        stepDialog.show(
            SwiggyStepScreen(
                eyebrow = "Applying once · Do not close",
                title = "Updating your cart once, then checking it",
                message = "Beta sends one confirmed update, checks every line, then checks again after 45 seconds before showing success.",
                caption = caption,
                rows = listOf(
                    SwiggyStepRow(
                        title = "Update sent",
                        detail = "Reading all ${selected.size} requested cart lines back now",
                        badge = "Checking...",
                        tone = SwiggyStepTone.AMBER,
                    )
                ),
                safetyNote = activity.getString(R.string.swiggy_keep_closed_warning),
                cancel = null,
            )
        )
        announce(caption)
        SwiggyMcpClient.applyCartPlan(activity, token) { result ->
            onUi(operationId) {
                if (!hostResumed || interruptedDuringRequest) {
                    // Never replay a write or trust a result that completed across an app switch.
                    running = false
                    setMutationInFlight(false)
                    stepDialog.dismiss()
                    if (hostResumed) checkPendingCartOnReturn()
                    return@onUi
                }
                when (result) {
                    is SwiggyMcpResult.Success -> {
                        if (result.value.verified && result.value.persistenceVerified) {
                            showVerifiedResult(operationId, address, selected, result.value.message, token)
                        } else {
                            BetaTelemetry.instance?.logEvent(
                                "cart_update_failed",
                                mapOf("reason" to "provider_unverified"),
                            )
                            finish(operationId, "Swiggy did not confirm the cart change. Please review your cart before continuing.")
                        }
                    }
                    is SwiggyMcpResult.Failure -> {
                        if (isAddressMismatch(result.userMessage)) {
                            setMutationInFlight(false)
                            showAddressMismatch(operationId, address, items, result.userMessage)
                        } else {
                            BetaTelemetry.instance?.logEvent(
                                "cart_update_failed",
                                mapOf("reason" to if (result.reconnectRequired) "reconnect_required" else "backend_failure"),
                            )
                            fail(operationId, result)
                        }
                    }
                }
            }
        }
    }

    private fun fail(operationId: Long, failure: SwiggyMcpResult.Failure) {
        if (!isCurrent(operationId)) return
        if (failure.reconnectRequired) onReconnectRequired()
        finish(operationId, failure.userMessage)
    }

    private fun finish(operationId: Long, message: String) {
        if (!isCurrent(operationId)) return
        val completedMutation = mutationInFlight
        running = false
        stepDialog.show(
            SwiggyStepScreen(
                eyebrow = if (completedMutation) "Review needed" else "Nothing added",
                title = if (completedMutation) "Please check your Swiggy cart" else "Beta stopped safely",
                message = message,
                caption = message,
                safetyNote = if (completedMutation) {
                    "Beta has stopped and will not retry this update. Open Swiggy and review the cart before starting another list."
                } else {
                    activity.getString(R.string.swiggy_step_no_checkout)
                },
                primary = if (completedMutation) {
                    SwiggyStepAction("Open Swiggy to review") {
                        CommerceAppLauncher.launchPreferred(activity, "open swiggy")
                        stepDialog.dismiss()
                    }
                } else {
                    SwiggyStepAction("Done") { stepDialog.dismiss() }
                },
                secondary = if (completedMutation) {
                    SwiggyStepAction("Close") { stepDialog.dismiss() }
                } else null,
                cancel = { stepDialog.dismiss() },
            )
        )
        announce(message)
        if (completedMutation) setMutationInFlight(false)
        notifyTerminal()
    }

    private fun showNoSubstitution(
        operationId: Long,
        address: SwiggyAddress,
        items: List<ParsedItem>,
        recommendations: List<Recommendations>,
    ) {
        if (!isCurrent(operationId)) return
        val unresolved = items.indices.filter { it !in draftSkipped && usableCandidates(items[it], recommendations[it]).isEmpty() }
        val otherItemCount = items.size - draftSkipped.size - unresolved.size
        val message = "Beta could not safely choose ${unresolved.size} item(s). Check the names or pack sizes, or leave these items out. Nothing has been added."
        stepDialog.show(
            SwiggyStepScreen(
                eyebrow = "No substitution",
                title = if (unresolved.size == 1) "One item needs your help" else "${unresolved.size} items need your help",
                message = message,
                caption = message,
                rows = unresolved.map { missingIndex ->
                    val item = items[missingIndex]
                    SwiggyStepRow(
                        title = displayItem(item),
                        detail = if (swiggyNeedsExactHealthProduct(item)) "Please name the exact product or brand. Beta will not guess a medicine from a symptom." else swiggyNoCandidateMessage(item, address),
                        badge = "Not added",
                        tone = SwiggyStepTone.AMBER,
                    )
                },
                safetyNote = if (otherItemCount > 0) {
                    "Beta will not swap the product or pack. Your other $otherItemCount items are still matched or waiting."
                } else {
                    "Beta will not swap the product or pack, and your cart was not changed."
                },
                primary = if (otherItemCount > 0) {
                    SwiggyStepAction("Continue with $otherItemCount matched items") {
                        if (isCurrent(operationId)) {
                            draftSkipped.addAll(unresolved)
                            chooseCandidate(++operationGeneration, address, items, recommendations, 0, mutableListOf())
                        }
                    }
                } else {
                    SwiggyStepAction("Edit your list") {
                        if (isCurrent(operationId)) {
                            cancelAndDismiss(operationId, "Your list is ready to edit. Nothing was changed.")
                            onEditRequest()
                        }
                    }
                },
                secondary = if (otherItemCount > 0) {
                    SwiggyStepAction("Edit your list") {
                        if (isCurrent(operationId)) {
                            cancelAndDismiss(operationId, "Your list is ready to edit. Nothing was changed.")
                            onEditRequest()
                        }
                    }
                } else null,
                tertiary = SwiggyStepAction("Cancel - change nothing") {
                    cancelAndDismiss(operationId, "Swiggy cart changes cancelled.")
                },
                cancel = { cancelAndDismiss(operationId, "Swiggy cart changes cancelled.") },
            )
        )
        announce(message)
    }

    private fun showAddressMismatch(
        operationId: Long,
        address: SwiggyAddress,
        items: List<ParsedItem>,
        message: String,
    ) {
        if (!isCurrent(operationId)) return
        stepDialog.show(
            SwiggyStepScreen(
                eyebrow = "Address mismatch · Nothing added",
                title = "The cart address changed",
                message = message,
                caption = message,
                rows = listOf(
                    SwiggyStepRow(
                        title = "Selected for this request",
                        detail = address.shortLabel,
                        badge = "Selected",
                        tone = SwiggyStepTone.SUCCESS,
                    ),
                    SwiggyStepRow(
                        title = "Current Swiggy cart",
                        detail = "The provider did not confirm that this cart uses the same saved address.",
                        badge = "Different",
                        tone = SwiggyStepTone.AMBER,
                    ),
                ),
                safetyNote = "Beta refused to move, clear, or overwrite the cart. Choose the right address and it will search every item again.",
                primary = SwiggyStepAction("Choose the right address") {
                    if (isCurrent(operationId)) restartAddressSelection(operationId, items)
                },
                secondary = SwiggyStepAction("Cancel - change nothing") {
                    cancelAndDismiss(operationId, "Swiggy cart changes cancelled.")
                },
                cancel = { cancelAndDismiss(operationId, "Swiggy cart changes cancelled.") },
            )
        )
        announce(message)
    }

    private fun showVerifiedResult(
        operationId: Long,
        address: SwiggyAddress,
        selected: List<RequestedItem>,
        providerMessage: String?,
        confirmationToken: String,
    ) {
        if (!isCurrent(operationId)) return
        val providerNote = swiggyProviderAddedMessage(providerMessage)
        val caption = buildString {
            append("Your cart was updated and checked in Beta.")
            providerNote?.let { append(" ").append(it) }
            append(" ").append(activity.getString(R.string.swiggy_handoff_warning))
        }
        val completedMutation = mutationInFlight
        running = false
        if (completedMutation) setMutationInFlight(false)
        onVerified(selected.size)
        stepDialog.show(
            SwiggyStepScreen(
                eyebrow = "Checked in Beta · ${selected.size} of ${selected.size} lines match",
                title = "Your cart is checked in Beta",
                message = "Deliver to ${address.shortLabel}. ${activity.getString(R.string.swiggy_step_readback_verified)}",
                caption = caption,
                rows = buildList {
                    selected.forEach {
                        add(
                            SwiggyStepRow(
                                title = it.displayName,
                                detail = "Requested cart quantity ${it.quantity}",
                                badge = "Verified",
                                tone = SwiggyStepTone.SUCCESS,
                            )
                        )
                    }
                    providerNote?.let {
                        add(
                            SwiggyStepRow(
                                title = activity.getString(R.string.swiggy_step_provider_added_note),
                                detail = it,
                                badge = if (it.contains("sample", ignoreCase = true)) "Free sample" else "Free gift",
                                tone = SwiggyStepTone.AMBER,
                            )
                        )
                    }
                },
                safetyNote = activity.getString(R.string.swiggy_handoff_warning),
                primary = if (BuildConfig.BETA_SWIGGY_CHECKOUT_ENABLED) SwiggyStepAction("Review and pay in Beta") {
                    stepDialog.dismiss()
                    onCheckoutRequested(address.id)
                } else SwiggyStepAction("Open Swiggy to review") {
                    // Another client may have changed the cart since the delayed
                    // check. Recheck read-only immediately before this handoff.
                    running = true
                    interruptedDuringRequest = false
                    setMutationInFlight(true)
                    announce("Checking your cart once more before opening Swiggy.")
                    checkReviewedCart(confirmationToken) { result ->
                        onUi(operationId) {
                            if (!hostResumed || interruptedDuringRequest) {
                                running = false
                                setMutationInFlight(false)
                                stepDialog.dismiss()
                                if (hostResumed) restorePendingCartWarning()
                            } else if (result is SwiggyMcpResult.Success && result.value) {
                                if (!CommerceAppLauncher.launchPreferred(activity).launched) {
                                    finish(operationId, "Beta could not open Swiggy. Please open it and review your cart. Beta has not repeated the update.")
                                    return@onUi
                                }
                                running = false
                                setMutationInFlight(false)
                                stepDialog.dismiss()
                                notifyTerminal()
                            } else {
                                finish(operationId, "The cart may have changed since Beta checked it. Please review the current Swiggy cart. Beta will not add anything again.")
                            }
                        }
                    }
                },
                secondary = SwiggyStepAction("Done") { stepDialog.dismiss() },
                cancel = { stepDialog.dismiss() },
            )
        )
        announce(caption)
        notifyTerminal()
    }

    private fun completeAndDismiss(operationId: Long, message: String) {
        if (!isCurrent(operationId)) return
        val completedMutation = mutationInFlight
        running = false
        clearDraft()
        stepDialog.dismiss()
        announce(message)
        if (completedMutation) setMutationInFlight(false)
        notifyTerminal()
    }

    private fun cancelAndDismiss(operationId: Long, message: String) {
        completeAndDismiss(operationId, message)
    }

    private fun onUi(operationId: Long, block: () -> Unit) {
        activity.runOnUiThread {
            if (!activity.isFinishing && !activity.isDestroyed && isCurrent(operationId)) {
                block()
            } else if (operationGeneration == operationId) {
                val abandonedMutation = mutationInFlight
                mutationInFlight = false
                running = false
                operationGeneration += 1
                stepDialog.dismiss()
                if (abandonedMutation) {
                    SwiggyCartMutationGuard.end(
                        "Swiggy finished the cart request while Beta was reopening. Please review your Swiggy cart before continuing.",
                    )
                }
                notifyTerminal()
            }
        }
    }

    private fun isCurrent(operationId: Long): Boolean {
        return running && operationGeneration == operationId
    }

    private fun setMutationInFlight(inFlight: Boolean) {
        if (mutationInFlight == inFlight) return
        mutationInFlight = inFlight
        if (inFlight) SwiggyCartMutationGuard.begin() else SwiggyCartMutationGuard.end()
    }

    private fun notifyTerminal() {
        if (!mutationInFlight) {
            onTerminal()
        }
    }

    private fun dismissActiveDialog() {
        stepDialog.dismiss()
    }

    private fun usableCandidates(
        item: ParsedItem,
        recommendation: Recommendations,
    ): List<RecommendationCandidate> {
        if (swiggyNeedsExactHealthProduct(item)) return emptyList()
        return recommendation.candidates
            .filter { it.spinId.isNotBlank() && isSwiggyCandidateCountCompatible(item, it) && isSwiggyCandidateAllowed(item, it) }
            .take(MAX_CANDIDATES)
    }

    private fun displayItem(item: ParsedItem): String {
        return item.backendInputText().trim().replaceFirstChar { first ->
            if (first.isLowerCase()) first.titlecase() else first.toString()
        }
    }

    private fun isAddressMismatch(message: String): Boolean {
        return message.contains("does not match the selected delivery address", ignoreCase = true)
    }

    private fun RecommendationCandidate.toRequestedItem(item: ParsedItem): RequestedItem {
        return RequestedItem(
            spinId = spinId,
            skuId = skuId,
            quantity = swiggyRequestedCartQuantity(item, this),
            displayName = candidateLabel(this),
        )
    }

    private fun candidateLabel(candidate: RecommendationCandidate): String {
        return swiggyCandidateLabel(candidate)
    }

    private companion object {
        const val MAX_ADDRESSES = 50
        const val MAX_CANDIDATES = 5
        const val ADDRESS_CONFIRMATION_TTL_MS = 15 * 60 * 1000L
    }
}
