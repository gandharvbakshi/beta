package com.example.beta

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.net.URI
import java.util.UUID
import com.example.beta.SwiggyMcpClient.SwiggyMcpResult

/** Separate user-confirmed checkout: grocery voice recognition cannot invoke place(). */
internal class SwiggyCheckoutCoordinator(
    private val activity: Activity,
    private val announce: (String) -> Unit,
    private val store: SwiggyCheckoutStore = SwiggyCheckoutStore(activity),
    private val onSettled: () -> Unit,
) {
    private val screen = SwiggyOrderStepDialog(activity)
    private val handler = Handler(Looper.getMainLooper())
    private var resumed = false
    private var busy = false
    private var showing = false
    private var epoch = 0L
    private var lastAttempt: SwiggyCheckoutAttempt? = null
    private var paymentOpened = false
    private val poll = Runnable { if (resumed && store.isPending()) recover() }

    fun isActive(): Boolean = showing || busy || store.isPending()

    fun onResume(): Boolean {
        resumed = true
        if (store.isPending()) {
            if (!busy) recover()
            return true
        }
        return showing || busy
    }

    fun onPause() {
        resumed = false
        handler.removeCallbacks(poll)
        // A UPI handoff is expected; an unconfirmed review is invalidated.
        if (!store.isPending()) { epoch++; busy = false; showing = false; screen.dismiss() }
    }

    fun destroy() { resumed = false; handler.removeCallbacks(poll); screen.dismiss() }

    fun startFromCart() {
        if (!BuildConfig.BETA_SWIGGY_CHECKOUT_ENABLED || !resumed || busy) return
        if (store.isPending()) { recover(); return }
        val generation = ++epoch
        progress("Finding your cart address", "The full delivery address will appear in the final review.")
        SwiggyMcpClient.fetchAddresses(activity) { result -> ui(generation) {
            busy = false
            if (result is SwiggyMcpResult.Success) {
                val address = result.value.singleOrNull { it.hasCurrentCart }
                if (address != null) start(address.id)
                else problem("Beta could not identify the address for this cart. Build or review your cart and select the delivery address first.")
            } else problem("Beta could not verify the cart address. No order was sent.")
        } }
    }

    fun start(addressId: String) {
        if (!BuildConfig.BETA_SWIGGY_CHECKOUT_ENABLED || !resumed || busy) return
        if (store.isPending()) { recover(); return }
        val generation = ++epoch
        progress("Checking for an earlier order", "Beta checks existing attempts before preparing a new order.")
        SwiggyMcpClient.checkoutStatus(activity, null) { result -> ui(generation) {
            busy = false
            if (result is SwiggyMcpResult.Success) {
                val attempt = result.value
                if (attempt.state != "none" && attempt.state !in terminalStates) {
                    if (attempt.attemptId != null) store.save(attempt.attemptId)
                    render(attempt)
                } else review(addressId)
            } else problem("Beta could not check earlier order attempts. No new order was sent. Please try checking again.")
        } }
    }

    private fun review(addressId: String) {
        val generation = ++epoch
        progress("Checking your whole cart", "Checking the current items, address, fees and payment options. Nothing is ordered yet.")
        SwiggyMcpClient.reviewCheckout(activity, addressId) { result -> ui(generation) {
            busy = false
            when (result) {
                is SwiggyMcpResult.Success -> choosePayment(result.value)
                is SwiggyMcpResult.Failure -> problem(result.userMessage)
            }
        } }
    }

    private fun choosePayment(review: SwiggyCheckoutReview) {
        showing = true
        screen.show(SwiggyStepScreen(
            eyebrow = "Payment choice", title = "How would you like to pay?",
            message = "Total ₹${review.amount}. Choose one option offered by Swiggy.",
            caption = "No order is placed by choosing a payment method.",
            choices = review.methods.map { method -> SwiggyStepChoice(method.label,
                when {
                    method.generateUPIQR -> "Scan this QR from a second device. Beta never asks for a PIN."
                    method.paymentMethod == "UPI" -> "You approve in your UPI app. Beta never asks for a PIN."
                    else -> "Pay when delivered. The final button places a real order."
                },
                onClick = { finalReview(review, method) }) },
            cancel = ::dismiss,
        ))
        announce("The total is ${review.amount} rupees. Choose how you want to pay. You will review everything before placing the order.")
    }

    private fun finalReview(review: SwiggyCheckoutReview, method: SwiggyCheckoutMethod) {
        if (!resumed) return
        val warning = "Check the flat or house and area even if location is off or you are ordering for someone elsewhere. Swiggy may split items into separate store orders. Keep Swiggy closed during this process."
        screen.show(SwiggyStepScreen(
            eyebrow = "Final order confirmation", title = "Place this order for ₹${review.amount}?",
            message = "Deliver to ${review.addressLabel}: ${review.addressFull}",
            caption = "Payment: ${method.label}. This includes ALL items in the cart, including any added earlier.",
            rows = review.items.map { SwiggyStepRow(it.name, "${it.variant} · Quantity ${it.quantity} · Unit price ₹${it.unitPrice}") } +
                review.charges.map { SwiggyStepRow(it.label, "₹${it.amount}") } +
                SwiggyStepRow("Total payable", "₹${review.amount}", "Final total"),
            safetyNote = warning,
            primary = SwiggyStepAction(if (method.paymentMethod == "UPI") "Confirm order · Pay ₹${review.amount}" else "Place order · ₹${review.amount} on delivery") {
                place(review, method)
            },
            secondary = SwiggyStepAction("Change payment") { choosePayment(review) },
            tertiary = SwiggyStepAction("Read the summary again") { finalReview(review, method) },
            cancel = ::dismiss,
        ))
        speakReview(review, method)
    }

    private fun speakReview(review: SwiggyCheckoutReview, method: SwiggyCheckoutMethod) {
        val short = review.addressFull.split(' ').take(20).joinToString(" ")
        val items = review.items.joinToString(". ") { "${it.quantity} ${it.name} ${it.variant}" }
        announce("You selected the address marked ${review.addressLabel}, $short. The total including fees is ${review.amount} rupees. Payment: ${method.label}. $items. Check the full address and items on screen. Tap the final confirmation only if these are correct.")
    }

    private fun place(review: SwiggyCheckoutReview, method: SwiggyCheckoutMethod) {
        if (!resumed || busy || store.isPending()) return
        if (System.currentTimeMillis() / 1000 >= review.expiresAt) { this.review(review.addressId); return }
        val id = UUID.randomUUID().toString()
        if (!store.save(id)) { problem("Beta could not save a safe recovery record. No order was sent."); return }
        val generation = ++epoch
        progress("Sending your confirmed order", "Please wait. If interrupted, Beta checks this attempt and never sends it again automatically.")
        SwiggyMcpClient.placeCheckout(activity, review.quoteToken, method.id, id) { result -> ui(generation) {
            busy = false
            if (result is SwiggyMcpResult.Success) render(result.value)
            else if (result is SwiggyMcpResult.Failure && result.orderNotSubmitted && store.clear()) {
                problem(result.userMessage + " No order was sent. Review the current cart before confirming again.")
            }
            else uncertain()
        } }
    }

    private fun recover() {
        if (busy || !resumed) return
        val id = store.load()
        // Without this UUID, a server's latest *older terminal* cannot resolve
        // this marker. Do not poll/auto-confirm or acknowledge an unrelated order.
        if (id == null) { uncertain(); return }
        val generation = ++epoch
        progress("Checking your existing order", "Do not place or pay again while the result is unclear.")
        SwiggyMcpClient.checkoutStatus(activity, id) { result -> ui(generation) {
            busy = false
            if (result is SwiggyMcpResult.Success && result.value.state != "none") render(result.value)
            else uncertain()
        } }
    }

    private fun render(attempt: SwiggyCheckoutAttempt) {
        lastAttempt = attempt
        showing = true
        handler.removeCallbacks(poll)
        // A corrupt/mismatched handoff file is uncertainty, not permission to pay again.
        paymentOpened = store.hasHandoffRecord()
        val terminal = attempt.state in terminalStates
        val paid = attempt.state == "placed"
        val canPay = BuildConfig.BETA_SWIGGY_CHECKOUT_ENABLED && attempt.state == "pending_payment" && attempt.paymentUrl != null && !paymentOpened &&
            (attempt.pollUntilEpochMs ?: 0) > System.currentTimeMillis()
        val title = when (attempt.state) {
            "placed" -> "Instamart order confirmed"
            "pending_payment" -> if (paymentOpened) "Check existing order" else "Approve payment, then return"
            "failed" -> "Payment did not complete"
            "cancelled" -> "Swiggy reports a cancellation"
            "refund_initiated" -> "A refund has started"
            "cart_changed" -> "The cart needs a new review"
            "partial" -> "Review each separate store order"
            else -> "Let us check before you try again"
        }
        screen.show(SwiggyStepScreen(
            eyebrow = if (paid) "Confirmed by Swiggy" else "Existing order attempt", title = title,
            message = attempt.message, caption = if (paid) "For changes or cancellation, contact Swiggy support." else "An unclear result does not mean that payment or the order failed. Do not pay twice.",
            rows = attempt.orderIds.map { SwiggyStepRow("Swiggy order", it, if (paid) "Confirmed" else "Check status") },
            primary = if (canPay) SwiggyStepAction("Open secure UPI payment") { openPayment(attempt) }
                else if (terminal) SwiggyStepAction(
                    when (attempt.state) {
                        "partial" -> "I reviewed the separate orders"
                        "failed", "refund_initiated" -> "I checked my bank and refund status"
                        else -> "Done"
                    }
                ) { acknowledge() }
                else SwiggyStepAction("Check existing order") { recover() },
            secondary = if (canPay) SwiggyStepAction("Check payment status") { recover() } else SwiggyStepAction("Open Swiggy support dialer") { dialSupport() },
            cancel = { screen.dismiss(); showing = false; handler.removeCallbacks(poll) },
        ))
        announce("$title. ${attempt.message}")
        if (paymentOpened && attempt.state == "pending_payment" && (attempt.pollUntilEpochMs ?: 0) > System.currentTimeMillis()) {
            handler.postDelayed(poll, (attempt.pollAfterMs ?: 5000L).coerceAtLeast(1000L))
        }
    }

    private fun openPayment(attempt: SwiggyCheckoutAttempt) {
        val url = attempt.paymentUrl ?: run { uncertain(); return }
        val attemptId = attempt.attemptId ?: run { uncertain(); return }
        if (!BuildConfig.BETA_SWIGGY_CHECKOUT_ENABLED) { recover(); return }
        if (!resumed || !isSafeCheckoutPaymentUrl(url)) { uncertain(); return }
        if (store.hasHandoffRecord()) { recover(); return }
        if (!store.markHandoff(attemptId)) {
            problem("Beta could not save a safe recovery marker. Do not tap pay again. Check the existing order instead.")
            return
        }
        paymentOpened = true
        // Exact server-returned HTTPS URL only; no WebView, intent:// parser, PIN or UPI-ID input.
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)) }
            .onFailure {
                paymentOpened = false
                if (!store.clearHandoff()) {
                    problem("No browser could open payment and the handoff marker could not be cleared. Check the existing order instead.")
                    return@onFailure
                }
                problem("No browser could open payment. Your order attempt is saved. Do not place another order; check its status.")
            }
    }

    private fun dialSupport() {
        runCatching {
            activity.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:080-67466729")))
        }.onFailure {
            problem("Beta could not open the Swiggy support dialer. You can call 080-67466729 manually.")
        }
    }

    private fun acknowledge() {
        if (lastAttempt?.state !in terminalStates) return
        // Clear cart receipt before attempt marker so recovery wins across a crash.
        if (!SwiggyCartReviewStore(activity).clear() || !SwiggyPendingCartStore(activity).clear() || !store.clear()) {
            uncertain(); return
        }
        dismiss()
        onSettled()
    }

    private fun progress(title: String, message: String) {
        busy = true; showing = true
        screen.show(SwiggyStepScreen("Please wait", title, message, "No repeat order will be sent automatically."))
    }

    private fun uncertain() = problem("Beta cannot yet confirm this order. Do not place or pay again. Check the existing attempt or contact Swiggy support.")

    private fun problem(message: String) {
        busy = false; showing = true
        screen.show(SwiggyStepScreen("Review needed", "Please check before continuing", message, "No order is repeated automatically.",
            primary = if (store.isPending()) SwiggyStepAction("Check existing order") { recover() } else SwiggyStepAction("Back") { dismiss() },
            secondary = if (store.isPending()) SwiggyStepAction("Open Swiggy support dialer") { dialSupport() } else null,
            cancel = { screen.dismiss(); showing = false }))
        announce(message)
    }

    private fun dismiss() { epoch++; busy = false; showing = false; paymentOpened = false; screen.dismiss(); handler.removeCallbacks(poll) }

    private fun ui(generation: Long, action: () -> Unit) {
        activity.runOnUiThread {
            if (epoch != generation) return@runOnUiThread
            busy = false
            if (activity.isDestroyed || activity.isFinishing || !resumed) return@runOnUiThread
            action()
        }
    }

    private companion object { val terminalStates = setOf("placed", "failed", "cancelled", "cart_changed", "refund_initiated", "partial") }
}

internal fun isSafeCheckoutPaymentUrl(url: String): Boolean = runCatching {
    val uri = URI(url)
    url.length <= 4096 && url.none(Char::isWhitespace) && '\\' !in url && uri.scheme == "https" &&
        uri.userInfo == null && uri.host != null && uri.port in setOf(-1, 443) &&
        uri.host.lowercase() in setOf("mcp.swiggy.com", "swiggy.com", "www.swiggy.com")
}.getOrDefault(false)
