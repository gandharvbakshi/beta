package com.example.beta

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.SwiggyMcpClient.RequestedItem
import com.example.beta.SwiggyMcpClient.SwiggyAddress
import com.example.beta.SwiggyMcpClient.SwiggyMcpResult
import com.example.beta.automation.InstructionParser
import com.example.beta.automation.ParsedItem
import com.example.beta.automation.PreferenceStore
import com.example.beta.automation.Quantity
import com.example.beta.automation.backendInputText
import com.example.beta.automation.requestedCount

/** Coordinates the user-visible, no-checkout Swiggy voice flow. */
class SwiggyVoiceOrderCoordinator(
    private val activity: Activity,
    private val announce: (String) -> Unit,
    private val onReconnectRequired: () -> Unit,
) {
    private var running = false

    fun start(instruction: String) {
        if (running) {
            announce("Beta is already working on your Swiggy cart.")
            return
        }
        val sanitized = CommerceProviderRouter.sanitizeOrderInstruction(instruction)
        val items = InstructionParser.applyPreferences(
            InstructionParser.parse(sanitized),
            lookup = { PreferenceStore.lookup(activity, it) },
        ).take(MAX_ITEMS)
        if (items.isEmpty()) {
            announce("I could not find a grocery item in that request. Please try again.")
            return
        }

        running = true
        announce("Checking your saved Swiggy delivery addresses.")
        SwiggyMcpClient.fetchAddresses(activity) { result ->
            onUi {
                when (result) {
                    is SwiggyMcpResult.Success -> chooseAddress(result.value, items)
                    is SwiggyMcpResult.Failure -> fail(result)
                }
            }
        }
    }

    private fun chooseAddress(addresses: List<SwiggyAddress>, items: List<ParsedItem>) {
        val usable = addresses.filter { it.id.isNotBlank() }.take(MAX_ADDRESSES)
        if (usable.isEmpty()) {
            finish("No saved Swiggy delivery address was found. Add an address in Swiggy, then try again.")
            return
        }
        if (usable.size == 1) {
            collectRecommendations(usable.first(), items, 0, mutableListOf())
            return
        }

        AlertDialog.Builder(activity)
            .setTitle("Where should Swiggy deliver?")
            .setItems(usable.map { it.normalizedLabel }.toTypedArray()) { _, which ->
                collectRecommendations(usable[which], items, 0, mutableListOf())
            }
            .setNegativeButton("Cancel") { _, _ -> finish("Swiggy cart changes cancelled.") }
            .setOnCancelListener { finish("Swiggy cart changes cancelled.") }
            .show()
    }

    private fun collectRecommendations(
        address: SwiggyAddress,
        items: List<ParsedItem>,
        index: Int,
        selected: MutableList<RequestedItem>,
    ) {
        if (index >= items.size) {
            planCart(address, selected)
            return
        }
        val item = items[index]
        announce("Finding ${item.query} using your recent Swiggy choices.")
        SwiggyMcpClient.fetchRecommendations(
            context = activity,
            addressId = address.id,
            query = recommendationQuery(item),
        ) { result ->
            onUi {
                when (result) {
                    is SwiggyMcpResult.Success -> chooseCandidate(
                        address,
                        items,
                        index,
                        selected,
                        result.value.candidates,
                        result.value.suggested,
                        result.value.requiresConfirmation,
                    )
                    is SwiggyMcpResult.Failure -> fail(result)
                }
            }
        }
    }

    private fun chooseCandidate(
        address: SwiggyAddress,
        items: List<ParsedItem>,
        index: Int,
        selected: MutableList<RequestedItem>,
        candidates: List<RecommendationCandidate>,
        suggested: RecommendationCandidate?,
        requiresConfirmation: Boolean,
    ) {
        val item = items[index]
        val usable = candidates.filter { it.spinId.isNotBlank() }.take(MAX_CANDIDATES)
        if (usable.isEmpty()) {
            finish("I could not find ${item.query} on Swiggy Instamart. Nothing was added.")
            return
        }

        val preferred = suggested?.takeIf { candidate -> usable.any { it.spinId == candidate.spinId } }
        if (!requiresConfirmation && preferred != null) {
            selected += preferred.toRequestedItem(item)
            collectRecommendations(address, items, index + 1, selected)
            return
        }

        AlertDialog.Builder(activity)
            .setTitle("Which ${item.query} do you want?")
            .setItems(usable.map(::candidateLabel).toTypedArray()) { _, which ->
                selected += usable[which].toRequestedItem(item)
                collectRecommendations(address, items, index + 1, selected)
            }
            .setNegativeButton("Cancel") { _, _ -> finish("Swiggy cart changes cancelled.") }
            .setOnCancelListener { finish("Swiggy cart changes cancelled.") }
            .show()
    }

    private fun planCart(address: SwiggyAddress, selected: List<RequestedItem>) {
        announce("Reading your current Swiggy cart and preparing the exact changes.")
        SwiggyMcpClient.planCart(activity, address.id, selected) { result ->
            onUi {
                when (result) {
                    is SwiggyMcpResult.Success -> reviewPlan(result.value)
                    is SwiggyMcpResult.Failure -> fail(result)
                }
            }
        }
    }

    private fun reviewPlan(plan: SwiggyMcpClient.CartPlan) {
        val token = plan.confirmationToken
        if (token.isNullOrBlank() || plan.changes.isEmpty()) {
            finish("No Swiggy cart change is needed.")
            return
        }
        val changeText = plan.changes.joinToString("\n") { change ->
            val from = change.fromQuantity ?: 0
            val to = change.toQuantity ?: from
            if (from == 0) "Add ${change.displayName}, quantity $to" else "${change.displayName}: $from to $to"
        }
        val safetyText = "$changeText\n\nYour other cart items stay. Beta will stop before checkout and payment."

        if (!plan.cartMutationEnabled) {
            AlertDialog.Builder(activity)
                .setTitle("Swiggy cart preview")
                .setMessage("$safetyText\n\nCart updates are not enabled on the Beta backend yet, so nothing was changed.")
                .setPositiveButton("Okay") { _, _ -> finish("Your Swiggy cart preview is ready. Nothing was changed.") }
                .setOnCancelListener { finish("Your Swiggy cart preview is ready. Nothing was changed.") }
                .show()
            return
        }

        AlertDialog.Builder(activity)
            .setTitle("Please check these cart changes")
            .setMessage(safetyText)
            .setPositiveButton("Add to cart") { _, _ -> applyPlan(token) }
            .setNegativeButton("Cancel") { _, _ -> finish("Swiggy cart changes cancelled.") }
            .setOnCancelListener { finish("Swiggy cart changes cancelled.") }
            .show()
    }

    private fun applyPlan(token: String) {
        announce("Updating your Swiggy cart once, then checking it.")
        SwiggyMcpClient.applyCartPlan(activity, token) { result ->
            onUi {
                when (result) {
                    is SwiggyMcpResult.Success -> {
                        if (result.value.verified) {
                            finish("Your Swiggy cart was updated and checked. Open Swiggy to review it. Beta has stopped before checkout.")
                        } else {
                            finish("Swiggy did not confirm the cart change. Please review your cart before continuing.")
                        }
                    }
                    is SwiggyMcpResult.Failure -> fail(result)
                }
            }
        }
    }

    private fun fail(failure: SwiggyMcpResult.Failure) {
        if (failure.reconnectRequired) onReconnectRequired()
        finish(failure.userMessage)
    }

    private fun finish(message: String) {
        running = false
        announce(message)
    }

    private fun onUi(block: () -> Unit) {
        activity.runOnUiThread {
            if (!activity.isFinishing && !activity.isDestroyed) block()
        }
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
            quantity = item.quantity.requestedCount().coerceIn(1, 20),
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
        const val MAX_ITEMS = 10
        const val MAX_ADDRESSES = 8
        const val MAX_CANDIDATES = 5
    }
}
