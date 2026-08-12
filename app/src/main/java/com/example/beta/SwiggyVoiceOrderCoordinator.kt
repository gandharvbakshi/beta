package com.example.beta

import android.app.Activity
import androidx.appcompat.app.AlertDialog
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
import com.example.beta.automation.requestedCount

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

/** Coordinates the user-visible, no-checkout Swiggy voice flow. */
class SwiggyVoiceOrderCoordinator(
    private val activity: Activity,
    private val announce: (String) -> Unit,
    private val onReconnectRequired: () -> Unit,
) {
    private var running = false
    private var operationGeneration = 0L
    private var mutationInFlight = false
    private var activeDialog: AlertDialog? = null

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
        announce("Checking your saved Swiggy delivery addresses.")
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

    fun isMutationInFlight(): Boolean = mutationInFlight

    private fun chooseAddress(operationId: Long, addresses: List<SwiggyAddress>, items: List<ParsedItem>) {
        val usable = addresses.filter { it.id.isNotBlank() }.take(MAX_ADDRESSES)
        if (usable.isEmpty()) {
            finish(operationId, "No saved Swiggy delivery address was found. Add an address in Swiggy, then try again.")
            return
        }
        if (usable.size == 1) {
            collectRecommendations(operationId, usable.first(), items)
            return
        }

        showTrackedDialog(
            AlertDialog.Builder(activity)
            .setTitle("Where should Swiggy deliver?")
            .setItems(usable.map { it.normalizedLabel }.toTypedArray()) { _, which ->
                if (isCurrent(operationId)) {
                    collectRecommendations(operationId, usable[which], items)
                }
            }
            .setNegativeButton("Cancel") { _, _ -> finish(operationId, "Swiggy cart changes cancelled.") }
            .setOnCancelListener { finish(operationId, "Swiggy cart changes cancelled.") }
        )
    }

    private fun collectRecommendations(
        operationId: Long,
        address: SwiggyAddress,
        items: List<ParsedItem>,
    ) {
        val queries = items.map(::recommendationQuery)
        announce("Finding options for all ${items.size} items using your recent Swiggy choices.")
        SwiggyMcpClient.fetchRecommendationBatch(
            context = activity,
            addressId = address.id,
            queries = queries,
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
            planCart(operationId, address, selected)
            return
        }
        val item = items[index]
        val recommendation = recommendations[index]
        val candidates = recommendation.candidates
        val suggested = recommendation.suggested
        val requiresConfirmation = recommendation.requiresConfirmation
        val usable = candidates.filter { it.spinId.isNotBlank() }.take(MAX_CANDIDATES)
        if (usable.isEmpty()) {
            finish(operationId, "I could not find ${item.query} on Swiggy Instamart. Nothing was added.")
            return
        }

        val preferred = suggested?.takeIf { candidate -> usable.any { it.spinId == candidate.spinId } }
        if (!requiresConfirmation && preferred != null) {
            selected += preferred.toRequestedItem(item)
            chooseCandidate(operationId, address, items, recommendations, index + 1, selected)
            return
        }

        showTrackedDialog(
            AlertDialog.Builder(activity)
            .setTitle("Which ${item.query} do you want?")
            .setItems(usable.map(::candidateLabel).toTypedArray()) { _, which ->
                if (isCurrent(operationId)) {
                    selected += usable[which].toRequestedItem(item)
                    chooseCandidate(operationId, address, items, recommendations, index + 1, selected)
                }
            }
            .setNegativeButton("Cancel") { _, _ -> finish(operationId, "Swiggy cart changes cancelled.") }
            .setOnCancelListener { finish(operationId, "Swiggy cart changes cancelled.") }
        )
    }

    private fun planCart(operationId: Long, address: SwiggyAddress, selected: List<RequestedItem>) {
        announce("Reading your current Swiggy cart and preparing the exact changes.")
        SwiggyMcpClient.planCart(activity, address.id, selected) { result ->
            onUi(operationId) {
                when (result) {
                    is SwiggyMcpResult.Success -> reviewPlan(operationId, selected, result.value)
                    is SwiggyMcpResult.Failure -> fail(operationId, result)
                }
            }
        }
    }

    private fun reviewPlan(
        operationId: Long,
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
        val changeText = plan.changes.joinToString("\n") { change ->
            val from = change.fromQuantity ?: 0
            val to = change.toQuantity ?: from
            if (from == 0) "Add ${change.displayName}, quantity $to" else "${change.displayName}: $from to $to"
        }
        val safetyText = "$changeText\n\nYour other cart items stay. Beta will stop before checkout and payment."

        if (!plan.cartMutationEnabled) {
            showTrackedDialog(
                AlertDialog.Builder(activity)
                .setTitle("Swiggy cart preview")
                .setMessage("$safetyText\n\nCart updates are not enabled on the Beta backend yet, so nothing was changed.")
                .setPositiveButton("Okay") { _, _ -> finish(operationId, "Your Swiggy cart preview is ready. Nothing was changed.") }
                .setOnCancelListener { finish(operationId, "Your Swiggy cart preview is ready. Nothing was changed.") }
            )
            return
        }

        showTrackedDialog(
            AlertDialog.Builder(activity)
            .setTitle("Please check these cart changes")
            .setMessage(safetyText)
            .setPositiveButton("Add to cart") { _, _ ->
                if (isCurrent(operationId)) applyPlan(operationId, token)
            }
            .setNegativeButton("Cancel") { _, _ -> finish(operationId, "Swiggy cart changes cancelled.") }
            .setOnCancelListener { finish(operationId, "Swiggy cart changes cancelled.") }
        )
    }

    private fun applyPlan(operationId: Long, token: String) {
        if (!isCurrent(operationId) || mutationInFlight) return
        setMutationInFlight(true)
        announce("Updating your Swiggy cart once, then checking it.")
        SwiggyMcpClient.applyCartPlan(activity, token) { result ->
            onUi(operationId) {
                when (result) {
                    is SwiggyMcpResult.Success -> {
                        if (result.value.verified) {
                            finish(operationId, "Your Swiggy cart was updated and checked. Open Swiggy to review it. Beta has stopped before checkout.")
                        } else {
                            finish(operationId, "Swiggy did not confirm the cart change. Please review your cart before continuing.")
                        }
                    }
                    is SwiggyMcpResult.Failure -> fail(operationId, result)
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
        dismissActiveDialog()
        announce(message)
        if (completedMutation) setMutationInFlight(false)
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
                activeDialog = null
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

    private fun showTrackedDialog(builder: AlertDialog.Builder) {
        dismissActiveDialog()
        val dialog = builder.create()
        activeDialog = dialog
        dialog.setOnDismissListener {
            if (activeDialog === dialog) activeDialog = null
        }
        dialog.show()
    }

    private fun dismissActiveDialog() {
        activeDialog?.dismiss()
        activeDialog = null
    }

    private fun recommendationQuery(item: ParsedItem): String {
        val quantityAware = when (item.quantity) {
            is Quantity.Weight, is Quantity.Volume -> item.backendInputText()
            else -> item.query
        }
        return if (item.avoidPhrases.isEmpty()) quantityAware else {
            "$quantityAware without ${item.avoidPhrases.joinToString(" or ")}"
        }
    }

    private fun RecommendationCandidate.toRequestedItem(item: ParsedItem): RequestedItem {
        return RequestedItem(
            spinId = spinId,
            quantity = item.quantity.requestedCount(),
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
        const val MAX_ADDRESSES = 8
        const val MAX_CANDIDATES = 5
    }
}
