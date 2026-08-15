package com.example.beta

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Debug-only preview entrypoint for representative Swiggy full-screen states.
 *
 * Launch with:
 * adb shell am start -n live.betaapp.android/.SwiggyDesignPreviewActivity --es state searching
 */
class SwiggyDesignPreviewActivity : Activity() {
    private var previewDialog: SwiggyOrderStepDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showPreview(intent?.getStringExtra(EXTRA_STATE))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showPreview(intent.getStringExtra(EXTRA_STATE))
    }

    override fun onDestroy() {
        previewDialog?.dismiss()
        previewDialog = null
        super.onDestroy()
    }

    private fun showPreview(rawState: String?) {
        previewDialog?.dismiss()
        previewDialog = SwiggyOrderStepDialog(this)
        previewDialog?.show(screenFor(rawState))
    }

    private fun screenFor(rawState: String?): SwiggyStepScreen {
        return when (rawState?.trim()?.lowercase()) {
            "address", "address_reassurance", "address-reassurance" -> addressReassuranceScreen()
            "address_suggestions", "smart_address", "smart-address" -> addressSuggestionsScreen()
            "searching", "search" -> searchingScreen()
            "whole_cart_review", "cart_review", "review" -> wholeCartReviewScreen()
            "applying", "apply" -> applyingScreen()
            "verified", "success" -> verifiedScreen()
            "mismatch", "warning" -> mismatchScreen()
            else -> unknownStateScreen(rawState)
        }
    }

    private fun addressSuggestionsScreen(): SwiggyStepScreen {
        val choices = listOf(
            "Home — Bengaluru" to "Suggested · Near you · recently used",
            "Work — Bengaluru" to "Suggested · Recently used",
            "Family — Bengaluru" to "Saved in Swiggy",
            "Saved address 4 — Bengaluru" to "Saved in Swiggy",
            "Saved address 5 — Bengaluru" to "Saved in Swiggy",
            "Saved address 6 — Bengaluru" to "Saved in Swiggy",
            "Saved address 7 — Bengaluru" to "Saved in Swiggy",
            "Saved address 8 — Bengaluru" to "Saved in Swiggy",
        )
        return SwiggyStepScreen(
            eyebrow = "Step 1 of 4 · Delivery address",
            title = "Where should Swiggy deliver?",
            message = "Choose one saved address. Beta shows 8 at a time so the list stays easy to read.",
            caption = "Home is suggested. Choose it or another saved Swiggy delivery address. Showing 1 to 8 of 12.",
            choices = choices.map { (title, detail) ->
                SwiggyStepChoice(title = title, detail = detail) { finish() }
            },
            primary = SwiggyStepAction("Show next 4 addresses") { finish() },
            tertiary = SwiggyStepAction("Cancel this list") { finish() },
            cancel = { finish() },
        )
    }

    private fun addressReassuranceScreen(): SwiggyStepScreen {
        return SwiggyStepScreen(
            eyebrow = "Step 1 of 4 · Delivery address",
            title = "Still delivering to your Home address?",
            message = "All 8 items will be searched at this saved address. Stock, pack sizes, and prices can change when the address changes.",
            caption = "Using Home, the Swiggy address you confirmed earlier in this session.",
            rows = listOf(
                SwiggyStepRow(
                    title = "Home - saved address",
                    detail = getString(R.string.swiggy_step_address_reassure_detail),
                    badge = "Confirmed 4 min ago",
                    tone = SwiggyStepTone.SUCCESS,
                ),
            ),
            safetyNote = "All eight items are searched at this address. Beta re-asks after 15 minutes.",
            primary = SwiggyStepAction("Yes, use this address") { finish() },
            secondary = SwiggyStepAction("Choose a different address") { finish() },
            tertiary = SwiggyStepAction("Cancel this list") { finish() },
            cancel = { finish() },
        )
    }

    private fun searchingScreen(): SwiggyStepScreen {
        val itemNames = verifiedItemNames()
        return SwiggyStepScreen(
            eyebrow = "Step 2 of 4 · Finding options",
            title = "Looking for your 8 items",
            message = "Beta searches a few products at a time so Swiggy stays reliable. Questions stay in the order you said them.",
            caption = "Finding options for all 8 items using your recent Swiggy choices.",
            rows = itemNames.mapIndexed { index, itemName ->
                val searching = index < 2
                SwiggyStepRow(
                    title = itemName,
                    detail = if (searching) {
                        "Searching live stock at Home"
                    } else {
                        "Waiting in your list order"
                    },
                    badge = if (searching) "Searching" else "Queued",
                    tone = if (searching) SwiggyStepTone.AMBER else SwiggyStepTone.NEUTRAL,
                )
            },
            safetyNote = "Beta searches a few products at a time and keeps every result in the order you said it.",
            cancel = { finish() },
        )
    }

    private fun wholeCartReviewScreen(): SwiggyStepScreen {
        val lineItems = verifiedItemNames()
        return SwiggyStepScreen(
            eyebrow = "Step 4 of 4 · Review",
            title = "Please check these cart changes",
            message = "Deliver to Home. Your Swiggy cart is empty right now. After this update it will hold exactly these 8 requested lines.",
            caption = "Reading your current Swiggy cart and preparing the exact changes.",
            rows = lineItems.map { item ->
                SwiggyStepRow(
                    title = item,
                    detail = "Add one verified cart line",
                    badge = "0 to 1",
                )
            },
            safetyNote = "One update, then Beta reads the cart back. Anything you add yourself later stays. Beta stops before checkout and payment.",
            primary = SwiggyStepAction("Add 8 lines to cart") { finish() },
            secondary = SwiggyStepAction("Change address") { finish() },
            tertiary = SwiggyStepAction("Cancel - change nothing") { finish() },
            cancel = { finish() },
        )
    }

    private fun applyingScreen(): SwiggyStepScreen {
        return SwiggyStepScreen(
            eyebrow = "Applying once · Do not close",
            title = "Updating your cart once, then checking it",
            message = "Beta sends one confirmed update, then reads the cart back to be sure it matches what you just approved.",
            caption = "Updating your Swiggy cart once, then checking it.",
            rows = listOf(
                SwiggyStepRow(
                    title = "Update sent",
                    detail = "Reading all 8 requested cart lines back now",
                    badge = "Checking...",
                    tone = SwiggyStepTone.SUCCESS,
                )
            ),
            safetyNote = "Please stay here for a moment. Changing the app or address now would leave the result unverified.",
            cancel = null,
        )
    }

    private fun verifiedScreen(): SwiggyStepScreen {
        val verifiedLines = verifiedItemNames()
        return SwiggyStepScreen(
            eyebrow = "Verified · 8 of 8 lines match",
            title = "Your cart was updated and checked",
            message = "Deliver to Home. Beta read the cart back and verified every requested line.",
            caption = "Your Swiggy cart was updated and checked.",
            rows = buildList {
                verifiedLines.forEach { item ->
                    add(
                        SwiggyStepRow(
                            title = item,
                            detail = "Requested cart quantity 1",
                            badge = "Verified",
                            tone = SwiggyStepTone.SUCCESS,
                        )
                    )
                }
                add(
                    SwiggyStepRow(
                        title = "Added by Swiggy, not by Beta",
                        detail = "Swiggy also included a free gift at no charge.",
                        badge = "Free sample",
                        tone = SwiggyStepTone.AMBER,
                    )
                )
            },
            safetyNote = "Beta has stopped before checkout. Open Swiggy to review the cart and pay yourself.",
            primary = SwiggyStepAction("Open Swiggy to review") { finish() },
            secondary = SwiggyStepAction("Close") { finish() },
            cancel = { finish() },
        )
    }

    private fun mismatchScreen(): SwiggyStepScreen {
        return SwiggyStepScreen(
            eyebrow = "Address mismatch · Nothing added",
            title = "The cart address changed",
            message = "Swiggy's current cart address does not match the selected delivery address. Please choose the right address again.",
            caption = "Swiggy's current cart address does not match the selected delivery address.",
            rows = listOf(
                SwiggyStepRow(
                    title = "Selected for this request",
                    detail = "Home",
                    badge = "Selected",
                    tone = SwiggyStepTone.SUCCESS,
                ),
                SwiggyStepRow(
                    title = "Current Swiggy cart",
                    detail = "Work",
                    badge = "Different",
                    tone = SwiggyStepTone.AMBER,
                ),
            ),
            safetyNote = "Beta refused to move, clear, or overwrite the cart. Choose the right address and it will search every item again.",
            primary = SwiggyStepAction("Choose the right address") { finish() },
            secondary = SwiggyStepAction("Cancel - change nothing") { finish() },
            cancel = { finish() },
        )
    }

    private fun unknownStateScreen(rawState: String?): SwiggyStepScreen {
        return SwiggyStepScreen(
            eyebrow = "Preview mode",
            title = "Unknown state",
            message = "Use one of: address_suggestions, address_reassurance, searching, whole_cart_review, applying, verified, mismatch.",
            caption = "state=${rawState ?: "missing"}",
            rows = listOf(
                SwiggyStepRow(
                    title = "No matching preview state was supplied.",
                    detail = "The dialog is still rendered with safe sample data only.",
                    tone = SwiggyStepTone.AMBER,
                ),
            ),
            primary = SwiggyStepAction("Close") { finish() },
            cancel = { finish() },
        )
    }

    private fun verifiedItemNames(): List<String> = listOf(
        "Amul Taaza Toned Milk - 500 ml",
        "Aashirvaad Whole Wheat Atta - 1 kg",
        "Britannia Brown Bread - 400 g",
        "Farm-fresh eggs - pack of 6",
        "Lay's Classic Salted Chips - 58 g",
        "Tropicana Orange Delight Juice - 1 L",
        "MAGGI 2-Minute Noodles - 280 g",
        "Colgate Strong Teeth Toothpaste - 150 g",
    )

    private companion object {
        const val EXTRA_STATE = "state"
    }
}
