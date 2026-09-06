package com.example.beta

import android.app.Activity

/**
 * Local onboarding demo that reuses the checkout step renderer without touching
 * any live provider, gateway, store, or analytics path.
 */
internal class SwiggyOfflineDemo(private val activity: Activity) {
    private val screen = SwiggyOrderStepDialog(activity)
    private var stepIndex = 0
    private var showing = false

    fun start() {
        stepIndex = 0
        showing = true
        render()
    }

    fun onPause() {
        dismiss()
    }

    fun destroy() {
        dismiss()
    }

    private fun render() {
        if (!showing || activity.isFinishing || activity.isDestroyed) return
        screen.show(currentStep())
    }

    private fun currentStep(): SwiggyStepScreen {
        return when (stepIndex) {
            0 -> SwiggyStepScreen(
                eyebrow = activity.getString(R.string.swiggy_offline_demo_eyebrow),
                title = activity.getString(R.string.swiggy_offline_demo_step_request_title),
                message = "Demo request: 2 toned milk, 1 whole wheat bread, and 6 bananas.",
                caption = activity.getString(R.string.swiggy_offline_demo_step_caption),
                rows = listOf(
                    SwiggyStepRow("Request", "2 toned milk, 1 whole wheat bread, and 6 bananas"),
                    SwiggyStepRow("Try it safely", "These are example groceries. Your own list stays unchanged."),
                ),
                primary = SwiggyStepAction("Next") {
                    stepIndex = 1
                    render()
                },
                cancel = ::dismiss,
            )
            1 -> SwiggyStepScreen(
                eyebrow = activity.getString(R.string.swiggy_offline_demo_eyebrow),
                title = activity.getString(R.string.swiggy_offline_demo_step_address_title),
                message = "Check the house number and area before you continue. This is an example address.",
                caption = activity.getString(R.string.swiggy_offline_demo_step_caption),
                rows = listOf(
                    SwiggyStepRow("Saved address", "Demo Home, 12 Sample Street, Bengaluru 560001", "Example"),
                    SwiggyStepRow("Your address", "Your saved addresses will not change."),
                ),
                primary = SwiggyStepAction("Next") {
                    stepIndex = 2
                    render()
                },
                secondary = SwiggyStepAction("Back") {
                    stepIndex = 0
                    render()
                },
                cancel = ::dismiss,
            )
            2 -> SwiggyStepScreen(
                eyebrow = activity.getString(R.string.swiggy_offline_demo_eyebrow),
                title = activity.getString(R.string.swiggy_offline_demo_step_cart_title),
                message = "The cart review is still local. Nothing is written to Swiggy.",
                caption = activity.getString(R.string.swiggy_offline_demo_step_caption),
                rows = listOf(
                    SwiggyStepRow("Toned milk", "Qty 2 · ₹28 each", "Cart"),
                    SwiggyStepRow("Whole wheat bread", "Qty 1 · ₹45", "Cart"),
                    SwiggyStepRow("Bananas", "1 pack of 6 bananas · ₹32", "Cart"),
                    SwiggyStepRow("Example total", "₹133.00 · no charge in this demo", "Demo", SwiggyStepTone.SUCCESS),
                ),
                primary = SwiggyStepAction("Next") {
                    stepIndex = 3
                    render()
                },
                secondary = SwiggyStepAction("Back") {
                    stepIndex = 1
                    render()
                },
                cancel = ::dismiss,
            )
            else -> SwiggyStepScreen(
                eyebrow = activity.getString(R.string.swiggy_offline_demo_eyebrow),
                title = activity.getString(R.string.swiggy_offline_demo_step_payment_title),
                message = "Review the payment summary in demo mode. This does not open any payment app.",
                caption = activity.getString(R.string.swiggy_offline_demo_step_caption),
                rows = listOf(
                    SwiggyStepRow("Deliver to", "Demo Home, 12 Sample Street, Bengaluru 560001"),
                    SwiggyStepRow("Items", "2 milk, 1 bread, 1 pack of 6 bananas"),
                    SwiggyStepRow("Example total", "₹133.00, including ₹0 example fees"),
                    SwiggyStepRow("Payment method", "Example UPI — no payment app will open", "Demo"),
                    SwiggyStepRow("Final reminder", "Finish demo closes only. No real order or payment.", "Safe", SwiggyStepTone.AMBER),
                ),
                primary = SwiggyStepAction(activity.getString(R.string.swiggy_offline_demo_finish)) {
                    dismiss()
                },
                secondary = SwiggyStepAction("Back") {
                    stepIndex = 2
                    render()
                },
                cancel = ::dismiss,
            )
        }
    }

    private fun dismiss() {
        showing = false
        stepIndex = 0
        screen.dismiss()
    }
}
