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

internal const val MAX_SWIGGY_MCP_ITEMS = 25

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
    return oversizedCountQuery?.let {
        "Swiggy supports up to quantity 20 per item. Please reduce the quantity for $it; nothing was changed."
    }
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
): SwiggyAddress? = rememberedAddressId
    ?.takeIf { it.isNotBlank() }
    ?.let { id -> addresses.firstOrNull { it.id == id } }

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
    val requested = item.quantity as? Quantity.Count ?: return 1
    return if (isSwiggyCandidateCountCompatible(item, candidate) && candidatePieceCountRegex.containsMatchIn(
            listOfNotNull(candidate.label, candidate.variant, candidate.subtitle).joinToString(" ")
        )
    ) 1 else requested.n
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
) {
    private var running = false
    private var operationGeneration = 0L
    private var mutationInFlight = false
    private val stepDialog = SwiggyOrderStepDialog(activity)
    private var selectedAddressId: String? = null
    private var selectedAddressAtElapsedRealtime = 0L

    fun start(instruction: String) {
        if (SwiggyCartMutationGuard.isInFlight()) {
            announce("Swiggy is still checking the last confirmed cart update. Please wait.")
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
            return
        }
        swiggyMcpItemValidationMessage(instruction, items)?.let { message ->
            announce(message)
            return
        }

        running = true
        val operationId = ++operationGeneration
        val addressCaption = "Checking your saved Swiggy delivery addresses."
        showYourList(operationId, items, addressCaption)
        announce(addressCaption)
        SwiggyMcpClient.fetchAddresses(activity) { result ->
            onUi(operationId) {
                when (result) {
                    is SwiggyMcpResult.Success -> chooseAddress(operationId, result.value, items)
                    is SwiggyMcpResult.Failure -> fail(operationId, result)
                }
            }
        }
    }

    fun cancel(): Boolean {
        if (mutationInFlight) return false
        operationGeneration += 1
        running = false
        dismissActiveDialog()
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

    private fun chooseAddress(operationId: Long, addresses: List<SwiggyAddress>, items: List<ParsedItem>) {
        val usable = addresses.filter { it.id.isNotBlank() }.take(MAX_ADDRESSES)
        if (usable.isEmpty()) {
            finish(operationId, "No saved Swiggy delivery address was found. Add an address in Swiggy, then try again.")
            return
        }
        if (
            isRememberedSwiggyAddressFresh(
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
                        message = "All ${items.size} items will be searched at this saved address. Stock, pack sizes, and prices can change when the address changes.",
                        caption = caption,
                        rows = listOf(
                            SwiggyStepRow(
                                title = "${remembered.shortLabel} - saved address",
                                detail = activity.getString(R.string.swiggy_step_address_reassure_detail),
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
        val caption = "Choose the saved Swiggy delivery address for this cart."
        stepDialog.show(
            SwiggyStepScreen(
                eyebrow = "Step 1 of 4 · Delivery address",
                title = "Where should Swiggy deliver?",
                message = "Choose one saved address. Beta shows only the short name here and searches every item again if you change it.",
                caption = caption,
                choices = swiggyAddressChoiceLabels(usable).mapIndexed { index, label ->
                    SwiggyStepChoice(
                        title = label,
                        detail = "Saved in Swiggy",
                        onClick = {
                            if (isCurrent(operationId)) {
                                rememberAddress(usable[index])
                                collectRecommendations(operationId, usable[index], items)
                            }
                        },
                    )
                },
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
        val queries = items.map(::swiggyRecommendationQuery)
        val caption = "Finding options for all ${items.size} items using your recent Swiggy choices."
        stepDialog.show(
            SwiggyStepScreen(
                eyebrow = "Step 2 of 4 · Searching",
                title = activity.getString(R.string.swiggy_step_progress_title, items.size),
                message = activity.getString(R.string.swiggy_step_progress_detail),
                caption = caption,
                rows = items.mapIndexed { index, item ->
                    val searching = index < 2
                    SwiggyStepRow(
                        title = displayItem(item),
                        detail = if (searching) "Searching live stock at ${address.shortLabel}" else "Waiting in your list order",
                        badge = if (searching) "Searching" else "Queued",
                        tone = if (searching) SwiggyStepTone.AMBER else SwiggyStepTone.NEUTRAL,
                    )
                },
                safetyNote = "At most two searches run together. Beta keeps every result in the order you said it.",
                cancel = { cancelAndDismiss(operationId, "Swiggy cart changes cancelled.") },
            )
        )
        announce(caption)
        SwiggyMcpClient.fetchRecommendationBatch(
            context = activity,
            addressId = address.id,
            queries = queries,
            strictMatchPhrases = items.map { it.strictMatchPhrase },
        ) { result ->
            onUi(operationId) {
                when (result) {
                    is SwiggyMcpResult.Success -> {
                        val recommendations = result.value
                        val orderMatches = areSwiggyRecommendationsOrdered(recommendations, queries)
                        if (!orderMatches) {
                            finish(operationId, "Swiggy returned incomplete or out-of-order choices. Nothing was changed.")
                        } else {
                            announce("Found options for all ${items.size} items. Reviewing them in your list order.")
                            chooseCandidate(
                                operationId,
                                address,
                                items,
                                recommendations,
                                0,
                                mutableListOf(),
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
    ) {
        if (index >= items.size) {
            planCart(operationId, address, items, selected)
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
                index = index,
                selected = selected,
            )
            return
        }

        val preferred = recommendation.suggested?.takeIf { candidate -> usable.any { it.spinId == candidate.spinId } }
        if (!recommendation.requiresConfirmation && preferred != null) {
            selected += preferred.toRequestedItem(item)
            chooseCandidate(operationId, address, items, recommendations, index + 1, selected)
            return
        }

        val needsQuestion = recommendations.mapIndexed { recommendationIndex, candidateSet ->
            val candidateItem = items[recommendationIndex]
            val compatible = usableCandidates(candidateItem, candidateSet)
            val candidatePreferred = candidateSet.suggested?.takeIf { suggestion ->
                compatible.any { it.spinId == suggestion.spinId }
            }
            compatible.isNotEmpty() && (candidateSet.requiresConfirmation || candidatePreferred == null)
        }
        val (questionNumber, questionTotal) = swiggyQuestionPosition(needsQuestion, index) ?: (1 to 1)
        val caption = "Which ${item.query} do you want?"
        stepDialog.show(
            SwiggyStepScreen(
                eyebrow = "Step 3 of 4 · " + activity.getString(
                    R.string.swiggy_step_question_counter,
                    questionNumber,
                    questionTotal,
                ),
                title = caption,
                message = "${items.size - questionTotal} other items matched without a question. Choose one exact pack for this item.",
                caption = caption,
                choices = usable.map { candidate ->
                    val details = listOfNotNull(candidate.variant, candidate.subtitle)
                        .map { it.trim() }
                        .filter { it.isNotBlank() && !it.equals(candidate.label, ignoreCase = true) }
                        .distinct()
                        .joinToString(" · ")
                    SwiggyStepChoice(
                        title = candidate.label,
                        detail = details.ifBlank { null },
                        badge = if (candidate.spinId == preferred?.spinId) "Suggested" else null,
                        onClick = {
                            if (isCurrent(operationId)) {
                                selected += candidate.toRequestedItem(item)
                                chooseCandidate(operationId, address, items, recommendations, index + 1, selected)
                            }
                        },
                    )
                },
                safetyNote = if (item.quantity is Quantity.Count) {
                    "Beta checks pack count separately from cart quantity. One matching multi-piece pack is added as one cart line."
                } else null,
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
        announce("Reading your current Swiggy cart and preparing the exact changes.")
        SwiggyMcpClient.planCart(activity, address.id, selected) { result ->
            onUi(operationId) {
                when (result) {
                    is SwiggyMcpResult.Success -> reviewPlan(operationId, address, items, selected, result.value)
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
        val cartStartsEmpty = plan.changes.all { (it.fromQuantity ?: 0) == 0 }
        val rows = plan.changes.map { change ->
            val from = change.fromQuantity ?: 0
            val to = change.toQuantity ?: from
            SwiggyStepRow(
                title = change.displayName,
                detail = if (from == 0) "Add one verified cart line" else "Keep the existing line and change only this quantity",
                badge = "$from to $to",
            )
        }
        val caption = "Reading your current Swiggy cart and preparing the exact changes."

        if (!plan.cartMutationEnabled) {
            stepDialog.show(
                SwiggyStepScreen(
                    eyebrow = "Step 3 of 4 · Cart preview",
                    title = "Check these cart changes",
                    message = "Deliver to ${address.shortLabel}. Cart updates are not enabled on the Beta backend, so this is a preview only.",
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
            return
        }

        stepDialog.show(
            SwiggyStepScreen(
                eyebrow = "Step 4 of 4 · Review",
                title = "Please check these cart changes",
                message = "Deliver to ${address.shortLabel}. " + activity.getString(
                    if (cartStartsEmpty) R.string.swiggy_step_review_empty else R.string.swiggy_step_review_existing
                ),
                caption = caption,
                rows = rows,
                safetyNote = "One update, then Beta reads the cart back. ${activity.getString(R.string.swiggy_step_no_checkout)}",
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
    }

    private fun restartAddressSelection(
        operationId: Long,
        items: List<ParsedItem>,
    ) {
        if (!isCurrent(operationId)) return
        clearRememberedAddress()
        announce("Choose a different Swiggy address. Beta will search every item again for that address.")
        SwiggyMcpClient.fetchAddresses(activity) { result ->
            onUi(operationId) {
                when (result) {
                    is SwiggyMcpResult.Success -> chooseAddress(operationId, result.value, items)
                    is SwiggyMcpResult.Failure -> fail(operationId, result)
                }
            }
        }
    }

    private fun rememberAddress(address: SwiggyAddress) {
        selectedAddressId = address.id
        selectedAddressAtElapsedRealtime = SystemClock.elapsedRealtime()
        onAddressChanged(address.shortLabel)
    }

    private fun applyPlan(
        operationId: Long,
        token: String,
        address: SwiggyAddress,
        items: List<ParsedItem>,
        selected: List<RequestedItem>,
    ) {
        if (!isCurrent(operationId) || mutationInFlight) return
        setMutationInFlight(true)
        val caption = "Updating your Swiggy cart once, then checking it."
        stepDialog.show(
            SwiggyStepScreen(
                eyebrow = "Applying once · Do not close",
                title = "Updating your cart once, then checking it",
                message = "Beta sends one confirmed update, then reads the cart back to be sure it matches what you just approved.",
                caption = caption,
                rows = listOf(
                    SwiggyStepRow(
                        title = "Update sent",
                        detail = "Reading all ${selected.size} requested cart lines back now",
                        badge = "Checking...",
                        tone = SwiggyStepTone.SUCCESS,
                    )
                ),
                safetyNote = "Please stay here for a moment. Changing the app or address now would leave the result unverified.",
                cancel = null,
            )
        )
        announce(caption)
        SwiggyMcpClient.applyCartPlan(activity, token) { result ->
            onUi(operationId) {
                when (result) {
                    is SwiggyMcpResult.Success -> {
                        if (result.value.verified) {
                            showVerifiedResult(operationId, address, selected, result.value.message)
                        } else {
                            finish(operationId, "Swiggy did not confirm the cart change. Please review your cart before continuing.")
                        }
                    }
                    is SwiggyMcpResult.Failure -> {
                        if (isAddressMismatch(result.userMessage)) {
                            setMutationInFlight(false)
                            showAddressMismatch(operationId, address, items, result.userMessage)
                        } else {
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
    }

    private fun showNoSubstitution(
        operationId: Long,
        address: SwiggyAddress,
        items: List<ParsedItem>,
        recommendations: List<Recommendations>,
        index: Int,
        selected: MutableList<RequestedItem>,
    ) {
        if (!isCurrent(operationId)) return
        val item = items[index]
        val message = swiggyNoCandidateMessage(item, address)
        val otherItemCount = swiggyContinuableItemCount(selected.size, items.size, index)
        stepDialog.show(
            SwiggyStepScreen(
                eyebrow = "No substitution",
                title = "One exact item is not available",
                message = message,
                caption = message,
                rows = listOf(
                    SwiggyStepRow(
                        title = displayItem(item),
                        detail = "No compatible live product was found at ${address.shortLabel}.",
                        badge = "Not added",
                        tone = SwiggyStepTone.AMBER,
                    )
                ),
                safetyNote = if (otherItemCount > 0) {
                    "Beta will not swap the product or pack. Your other $otherItemCount items are still matched or waiting."
                } else {
                    "Beta will not swap the product or pack, and your cart was not changed."
                },
                primary = if (otherItemCount > 0) {
                    SwiggyStepAction("Continue with the other $otherItemCount items") {
                        if (isCurrent(operationId)) {
                            chooseCandidate(operationId, address, items, recommendations, index + 1, selected)
                        }
                    }
                } else {
                    SwiggyStepAction("Try a different address") {
                        if (isCurrent(operationId)) restartAddressSelection(operationId, items)
                    }
                },
                secondary = if (otherItemCount > 0) {
                    SwiggyStepAction("Try a different address") {
                        if (isCurrent(operationId)) restartAddressSelection(operationId, items)
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
    ) {
        if (!isCurrent(operationId)) return
        val providerNote = swiggyProviderAddedMessage(providerMessage)
        val caption = buildString {
            append("Your Swiggy cart was updated and checked.")
            providerNote?.let { append(" ").append(it) }
            append(" Open Swiggy to review it. Beta has stopped before checkout.")
        }
        val completedMutation = mutationInFlight
        running = false
        if (completedMutation) setMutationInFlight(false)
        stepDialog.show(
            SwiggyStepScreen(
                eyebrow = "Verified · ${selected.size} of ${selected.size} lines match",
                title = "Your cart was updated and checked",
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
                safetyNote = activity.getString(R.string.swiggy_step_no_checkout),
                primary = SwiggyStepAction("Open Swiggy to review") {
                    CommerceAppLauncher.launchPreferred(activity, "open swiggy")
                    stepDialog.dismiss()
                },
                secondary = SwiggyStepAction("Done") { stepDialog.dismiss() },
                cancel = { stepDialog.dismiss() },
            )
        )
        announce(caption)
    }

    private fun completeAndDismiss(operationId: Long, message: String) {
        if (!isCurrent(operationId)) return
        val completedMutation = mutationInFlight
        running = false
        stepDialog.dismiss()
        announce(message)
        if (completedMutation) setMutationInFlight(false)
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

    private fun dismissActiveDialog() {
        stepDialog.dismiss()
    }

    private fun usableCandidates(
        item: ParsedItem,
        recommendation: Recommendations,
    ): List<RecommendationCandidate> {
        return recommendation.candidates
            .filter { it.spinId.isNotBlank() && isSwiggyCandidateCountCompatible(item, it) }
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
        return listOfNotNull(candidate.label, candidate.variant, candidate.subtitle)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" — ")
    }

    private companion object {
        const val MAX_ADDRESSES = 50
        const val MAX_CANDIDATES = 5
        const val ADDRESS_CONFIRMATION_TTL_MS = 15 * 60 * 1000L
    }
}
