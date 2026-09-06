package com.example.beta

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.view.View
import com.example.beta.SwiggyMcpClient.SwiggyMcpResult
import org.hamcrest.CoreMatchers.containsString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Offline checkout coverage only: real renderer, fake gateway, no HTTP/provider calls. */
@RunWith(AndroidJUnit4::class)
class SwiggyCheckoutFlowTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val usedTags = listOf(
        "store-preview",
        "full-flow",
        "doubletap",
        "changed-review",
        "concurrent",
        "upi-pending",
        "payment-not-submitted",
        "long-review",
        "restart-pending",
        "terminal-states",
        "timeout",
        "corrupt",
        "placed-ack",
        "partial",
    )

    @Test
    fun capture_store_previews_without_placing_any_order() {
        val gateway = FakeGateway(
            addressesResult = success(addresses(currentCart = true)),
            reviewResults = arrayDequeOf(success(review(
                quoteToken = "synthetic-preview", amount = "193.00", addressId = "example-home",
                addressLabel = "Home", addressFull = "Flat 18, Garden Apartments, Bengaluru",
                items = listOf(SwiggyCheckoutLine("Toned milk", 2, "500 ml", "28.00"),
                    SwiggyCheckoutLine("Whole wheat bread", 1, "400 g", "45.00"),
                    SwiggyCheckoutLine("Whole wheat atta", 1, "1 kg", "75.00")),
                charges = listOf(SwiggyCheckoutCharge("Delivery fee", "15.00"),
                    SwiggyCheckoutCharge("Platform fee", "2.00")),
                methods = listOf(SwiggyCheckoutMethod("upi", "Google Pay", "UPI"),
                    SwiggyCheckoutMethod("cod", "Cash on delivery", "COD")),
            ))),
        )
        launchCoordinator("store-preview", gateway) { scenario, _, coordinator ->
            storeScreenshot("01-home.png")
            scenario.onActivity { coordinator.startFromCart() }
            onView(withId(R.id.swiggyStepTitle)).inRoot(isDialog())
                .check(matches(withText("How would you like to pay?")))
            storeScreenshot("02-payment-choice.png")
            onView(withText(containsString("Google Pay"))).inRoot(isDialog()).perform(scrollTo(), click())
            onView(withId(R.id.swiggyStepTitle)).inRoot(isDialog())
                .perform(scrollTo()).check(matches(withText("Place this order for ₹193.00?")))
            storeScreenshot("03-full-review.png")
            onView(withId(R.id.swiggyStepPrimary)).inRoot(isDialog())
                .perform(scrollTo()).check(matches(isDisplayed()))
            storeScreenshot("04-confirmation.png")
            assertEquals(0, gateway.placeCalls.get())
        }
    }

    private fun storeScreenshot(name: String) {
        waitForIdle()
        val device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5000)
        val folder = File(context.getExternalFilesDir(null), "store-previews").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(folder, name)))
    }

    @Test
    fun start_from_cart_reaches_review_then_places_cod_once_without_sensitive_ids() {
        val gateway = FakeGateway(
            addressesResult = success(addresses(currentCart = true)),
            reviewResults = arrayDequeOf(success(review(
                quoteToken = "quote-token-1",
                amount = "219",
                addressId = "addr-1",
                addressLabel = "Home",
                addressFull = "221B Baker Street, London",
                items = listOf(
                    SwiggyCheckoutLine("Milk", 2, "1 litre", "45"),
                    SwiggyCheckoutLine("Bread", 1, "Brown loaf", "35"),
                ),
                charges = listOf(
                    SwiggyCheckoutCharge("Delivery fee", "15"),
                    SwiggyCheckoutCharge("Platform fee", "6"),
                ),
                methods = listOf(
                    SwiggyCheckoutMethod("cod", "Cash on delivery", "COD"),
                    SwiggyCheckoutMethod("upi", "UPI", "UPI", generateUPIQR = true),
                ),
            ))),
            placeResults = arrayDequeOf(success(attempt(
                attemptId = "attempt-1",
                state = "placed",
                message = "Your order is confirmed.",
                orderIds = listOf("order-placed-1"),
            ))),
            deferFirstPlace = true,
        )
        val settled = AtomicInteger(0)

        launchCoordinator("full-flow", gateway, onSettled = settled) { scenario, _, coordinator ->
            scenario.onActivity { coordinator.startFromCart() }
            waitForIdle()

            onView(withId(R.id.swiggyStepTitle)).inRoot(isDialog())
                .check(matches(withText("How would you like to pay?")))
            onView(withText(containsString("₹219"))).inRoot(isDialog())
                .check(matches(isDisplayed()))
            onView(withText(containsString("Cash on delivery"))).inRoot(isDialog())
                .perform(scrollTo(), click())
            onView(withText(containsString("Milk"))).inRoot(isDialog())
                .perform(scrollTo())
                .check(matches(isDisplayed()))
            onView(withText(containsString("Bread"))).inRoot(isDialog())
                .perform(scrollTo())
                .check(matches(isDisplayed()))
            onView(withText(containsString("Delivery fee"))).inRoot(isDialog())
                .perform(scrollTo())
                .check(matches(isDisplayed()))
            onView(withText(containsString("221B Baker Street"))).inRoot(isDialog())
                .perform(scrollTo())
                .check(matches(isDisplayed()))
            waitForIdle()

            onView(withId(R.id.swiggyStepTitle)).inRoot(isDialog())
                .check(matches(withText("Place this order for ₹219?")))
            onView(withId(R.id.swiggyStepPrimary)).inRoot(isDialog())
                .check(matches(withText("Place order · ₹219 on delivery")))
            onView(withText("quote-token-1")).check(doesNotExist())
            onView(withText("attempt-1")).check(doesNotExist())

            onView(withId(R.id.swiggyStepPrimary)).inRoot(isDialog())
                .perform(scrollTo(), doubleClickWithoutIdle())
            waitForIdle()

            assertEquals(1, gateway.placeCalls.get())
            gateway.releaseDeferredPlace(success(attempt(
                attemptId = "attempt-1",
                state = "placed",
                message = "Your order is confirmed.",
                orderIds = listOf("order-placed-1"),
            )))
            waitForIdle()
            assertEquals(0, settled.get())
            onView(withId(R.id.swiggyStepTitle)).inRoot(isDialog())
                .check(matches(withText("Instamart order confirmed")))

            onView(withId(R.id.swiggyStepPrimary)).inRoot(isDialog())
                .perform(scrollTo(), click())
            waitForIdle()
            assertEquals(1, settled.get())
        }
    }

    @Test
    fun final_confirmation_double_tap_only_places_once() {
        val gateway = FakeGateway(
            addressesResult = success(addresses(currentCart = true)),
            reviewResults = arrayDequeOf(success(review(
                quoteToken = "quote-token-2",
                amount = "149",
                addressId = "addr-2",
                addressLabel = "Work",
                addressFull = "12 Example Road, Bengaluru",
                items = listOf(SwiggyCheckoutLine("Eggs", 12, "free-range", "149")),
                charges = listOf(SwiggyCheckoutCharge("Delivery fee", "0")),
                methods = listOf(SwiggyCheckoutMethod("cod", "Cash on delivery", "COD")),
            ))),
            placeResults = arrayDequeOf(success(attempt(
                attemptId = "attempt-2",
                state = "placed",
                message = "Order placed.",
                orderIds = listOf("order-2"),
            ))),
            deferFirstPlace = true,
        )

        launchCoordinator("doubletap", gateway) { scenario, _, coordinator ->
            scenario.onActivity { coordinator.startFromCart() }
            waitForIdle()

            onView(withText(containsString("Cash on delivery"))).inRoot(isDialog())
                .perform(scrollTo(), click())
            waitForIdle()

            onView(withId(R.id.swiggyStepPrimary)).inRoot(isDialog())
                .perform(scrollTo(), doubleClickWithoutIdle())
            waitForIdle()

            assertEquals(1, gateway.placeCalls.get())
            gateway.releaseDeferredPlace(success(attempt(
                attemptId = "attempt-2",
                state = "placed",
                message = "Order placed.",
                orderIds = listOf("order-2"),
            )))
            waitForIdle()
            onView(withId(R.id.swiggyStepTitle)).inRoot(isDialog())
                .check(matches(withText("Instamart order confirmed")))
        }
    }

    @Test
    fun price_review_change_returns_order_not_submitted_and_keeps_checkout_closed() {
        val gateway = FakeGateway(
            addressesResult = success(addresses(currentCart = true)),
            reviewResults = arrayDequeOf(success(review(
                quoteToken = "quote-token-3",
                amount = "299",
                addressId = "addr-3",
                addressLabel = "Home",
                addressFull = "44 Recovery Lane, Pune",
                items = listOf(SwiggyCheckoutLine("Pasta", 1, "500g", "99")),
                charges = listOf(SwiggyCheckoutCharge("Delivery fee", "10")),
                methods = listOf(SwiggyCheckoutMethod("upi", "UPI", "UPI")),
            ))),
            placeResults = arrayDequeOf(SwiggyMcpResult.Failure(
                userMessage = "The cart changed while we were preparing payment.",
                orderNotSubmitted = true,
            )),
        )

        launchCoordinator("payment-not-submitted", gateway) { scenario, _, coordinator ->
            scenario.onActivity { coordinator.startFromCart() }
            waitForIdle()

            onView(withText(containsString("UPI"))).inRoot(isDialog())
                .perform(scrollTo(), click())
            waitForIdle()
            onView(withId(R.id.swiggyStepPrimary)).inRoot(isDialog())
                .perform(scrollTo(), click())
            waitForIdle()

            onView(withId(R.id.swiggyStepTitle)).inRoot(isDialog())
                .check(matches(withText("Please check before continuing")))
            onView(withId(R.id.swiggyStepMessage)).inRoot(isDialog())
                .check(matches(withText(containsString("cart changed"))))
            assertEquals(1, gateway.placeCalls.get())
            assertEquals(1, gateway.reviewCalls.get())
        }
    }

    @Test
    fun long_ten_item_review_surfaces_full_address_and_total() {
        val gateway = FakeGateway(
            addressesResult = success(addresses(currentCart = true)),
            reviewResults = arrayDequeOf(success(review(
                quoteToken = "quote-token-4",
                amount = "540",
                addressId = "addr-4",
                addressLabel = "Home",
                addressFull = "Flat 18, Tower B, Example Residency, New Delhi",
                items = (1..10).map { index ->
                    SwiggyCheckoutLine("Item $index", index, "Variant $index", "${index * 10}")
                },
                charges = listOf(
                    SwiggyCheckoutCharge("Delivery fee", "20"),
                    SwiggyCheckoutCharge("Platform fee", "10"),
                ),
                methods = listOf(SwiggyCheckoutMethod("cod", "Cash on delivery", "COD")),
            ))),
        )

        launchCoordinator("long-review", gateway) { scenario, _, coordinator ->
            scenario.onActivity { coordinator.startFromCart() }
            waitForIdle()

            onView(withId(R.id.swiggyStepTitle)).inRoot(isDialog())
                .check(matches(withText("How would you like to pay?")))
            onView(withText(containsString("Cash on delivery"))).inRoot(isDialog())
                .perform(scrollTo(), click())
            onView(withText(containsString("Item 10"))).inRoot(isDialog())
                .perform(scrollTo())
                .check(matches(isDisplayed()))
            onView(withText(containsString("Platform fee"))).inRoot(isDialog())
                .perform(scrollTo())
                .check(matches(isDisplayed()))
            onView(withText(containsString("Flat 18, Tower B, Example Residency"))).inRoot(isDialog())
                .perform(scrollTo())
                .check(matches(isDisplayed()))
            onView(withId(R.id.swiggyStepTitle)).inRoot(isDialog())
                .perform(scrollTo())
                .check(matches(withText("Place this order for ₹540?")))
            val imageDir = File(context.getExternalFilesDir(null), "checkout-screenshots").apply { mkdirs() }
            assertTrue(androidx.test.uiautomator.UiDevice.getInstance(
                InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(imageDir, "checkout-review.png")))
            onView(withId(R.id.swiggyStepPrimary)).inRoot(isDialog())
                .perform(scrollTo())
                .check(matches(isDisplayed()))
            assertTrue(androidx.test.uiautomator.UiDevice.getInstance(
                InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(imageDir, "checkout-confirmation.png")))
        }
    }

    @Test
    fun pending_payment_renders_secure_payment_button_but_never_opens_external_link_in_test() {
        val attemptId = UUID.randomUUID().toString()
        val gateway = FakeGateway(
            statusResult = { id ->
                if (id == attemptId) {
                    success(attempt(
                        attemptId = attemptId,
                        state = "pending_payment",
                        message = "Payment is pending in the provider.",
                        orderIds = listOf("order-pending-1"),
                        paymentUrl = "https://mcp.swiggy.com/pay",
                        pollAfterMs = 5_000L,
                        pollUntilEpochMs = System.currentTimeMillis() + 60_000L,
                    ))
                } else {
                    error("Unexpected status attemptId=$id")
                }
            },
        )

        launchCoordinator("upi-pending", gateway, seedPendingAttemptId = attemptId) { _, _, _ ->
            waitForIdle()

            onView(withId(R.id.swiggyStepTitle)).inRoot(isDialog())
                .check(matches(withText("Approve payment, then return")))
            onView(withId(R.id.swiggyStepPrimary)).inRoot(isDialog())
                .check(matches(withText("Open secure UPI payment")))
            onView(withId(R.id.swiggyStepSecondary)).inRoot(isDialog())
                .check(matches(withText("Check payment status")))
            onView(withText("https://mcp.swiggy.com/pay")).check(doesNotExist())
            assertEquals(1, gateway.statusCalls.get())
            assertEquals(0, gateway.placeCalls.get())
        }
    }

    @Test
    fun restart_with_pending_store_reuses_existing_uuid_and_does_not_place_again() {
        val attemptId = UUID.randomUUID().toString()
        val gateway = FakeGateway(
            statusResult = { id ->
                if (id == attemptId) {
                    success(attempt(
                        attemptId = attemptId,
                        state = "pending_payment",
                        message = "Resume the existing attempt.",
                        orderIds = listOf("order-recover-1"),
                        paymentUrl = "https://mcp.swiggy.com/pay",
                        pollAfterMs = 5_000L,
                        pollUntilEpochMs = System.currentTimeMillis() + 60_000L,
                    ))
                } else {
                    error("Unexpected status attemptId=$id")
                }
            },
        )

        launchCoordinator("restart-pending", gateway, seedPendingAttemptId = attemptId) { scenario, _, coordinator ->
            scenario.onActivity { coordinator.onPause(); coordinator.onResume() }
            waitForIdle()

            assertEquals(2, gateway.statusCalls.get())
            assertEquals(0, gateway.reviewCalls.get())
            assertEquals(0, gateway.placeCalls.get())
            onView(withId(R.id.swiggyStepTitle)).inRoot(isDialog())
                .check(matches(withText("Approve payment, then return")))
        }
    }

    @Test
    fun terminal_and_unknown_states_fail_closed_without_false_success() {
        listOf(
            "failed" to "Payment did not complete",
            "cancelled" to "Swiggy reports a cancellation",
            "refund_initiated" to "A refund has started",
            "cart_changed" to "The cart needs a new review",
            "not_submitted" to "No order was sent",
            "weird_state" to "Let us check before you try again",
        ).forEachIndexed { index, (state, expectedTitle) ->
            val attemptId = UUID.randomUUID().toString()
            val gateway = FakeGateway(
                statusResult = { id ->
                    if (id == attemptId) {
                        success(attempt(
                            attemptId = attemptId,
                            state = state,
                            message = "State $state should not be treated as success.",
                            orderIds = listOf("order-terminal-$index"),
                        ))
                    } else {
                        error("Unexpected status attemptId=$id")
                    }
                },
            )

            launchCoordinator("terminal-states", gateway, seedPendingAttemptId = attemptId) { _, _, _ ->
                waitForIdle()

                onView(withId(R.id.swiggyStepTitle)).inRoot(isDialog())
                    .check(matches(withText(expectedTitle)))
                onView(withId(R.id.swiggyStepMessage)).inRoot(isDialog())
                    .check(matches(withText(containsString(state))))
                onView(withText("Open secure UPI payment")).check(doesNotExist())
                assertEquals(1, gateway.statusCalls.get())
                assertEquals(0, gateway.placeCalls.get())
            }
        }
    }

    @Test
    fun stale_quote_blocks_place_until_refreshes_the_cart_review() {
        val gateway = FakeGateway(
            addressesResult = success(addresses(currentCart = true)),
            reviewResults = arrayDequeOf(
                success(review(
                    quoteToken = "quote-token-5",
                    amount = "180",
                    addressId = "addr-5",
                    addressLabel = "Home",
                    addressFull = "10 Old Cart Street, Mumbai",
                    items = listOf(SwiggyCheckoutLine("Rice", 1, "1 kg", "60")),
                    charges = listOf(SwiggyCheckoutCharge("Delivery fee", "10")),
                    methods = listOf(SwiggyCheckoutMethod("cod", "Cash on delivery", "COD")),
                    expiresAt = 0L,
                )),
                success(review(
                    quoteToken = "quote-token-6",
                    amount = "205",
                    addressId = "addr-5",
                    addressLabel = "Home",
                    addressFull = "10 Fresh Cart Street, Mumbai",
                    items = listOf(
                        SwiggyCheckoutLine("Rice", 1, "1 kg", "60"),
                        SwiggyCheckoutLine("Tea", 1, "500 g", "85"),
                    ),
                    charges = listOf(SwiggyCheckoutCharge("Delivery fee", "10")),
                    methods = listOf(SwiggyCheckoutMethod("cod", "Cash on delivery", "COD")),
                )),
            ),
        )

        launchCoordinator("changed-review", gateway) { scenario, _, coordinator ->
            scenario.onActivity { coordinator.startFromCart() }
            waitForIdle()

            onView(withText(containsString("Cash on delivery"))).inRoot(isDialog())
                .perform(scrollTo(), click())
            waitForIdle()

            onView(withId(R.id.swiggyStepPrimary)).inRoot(isDialog())
                .perform(scrollTo(), click())
            waitForIdle()

            assertEquals(0, gateway.placeCalls.get())
            assertEquals(2, gateway.reviewCalls.get())
            onView(withId(R.id.swiggyStepTitle)).inRoot(isDialog())
                .check(matches(withText("How would you like to pay?")))
            onView(withText(containsString("Cash on delivery"))).inRoot(isDialog())
                .perform(scrollTo(), click())
            onView(withText(containsString("Fresh Cart Street"))).inRoot(isDialog())
                .perform(scrollTo())
                .check(matches(isDisplayed()))
        }
    }

    @Test
    fun concurrent_start_requests_only_issue_one_address_lookup() {
        val gateway = FakeGateway(
            addressesResult = success(addresses(currentCart = true)),
            reviewResults = arrayDequeOf(success(review(
                quoteToken = "quote-token-7",
                amount = "99",
                addressId = "addr-7",
                addressLabel = "Home",
                addressFull = "1 Concurrent Street, Chennai",
                items = listOf(SwiggyCheckoutLine("Soap", 1, "1 bar", "25")),
                charges = listOf(SwiggyCheckoutCharge("Delivery fee", "5")),
                methods = listOf(SwiggyCheckoutMethod("cod", "Cash on delivery", "COD")),
            ))),
            deferFirstAddresses = true,
        )

        launchCoordinator("concurrent", gateway) { scenario, _, coordinator ->
            scenario.onActivity {
                coordinator.startFromCart()
                coordinator.startFromCart()
            }
            waitForIdle()

            assertEquals(1, gateway.addressesCalls.get())
            gateway.releaseDeferredAddresses()
            waitForIdle()
            onView(withId(R.id.swiggyStepTitle)).inRoot(isDialog())
                .check(matches(withText("How would you like to pay?")))
        }
    }

    @Test
    fun corrupted_primary_store_blocks_status_and_place_gateway_calls() {
        val gateway = FakeGateway()
        launchCoordinator("corrupt", gateway) { scenario, store, coordinator ->
            scenario.onActivity {
                File(it.noBackupFilesDir, storeFileName("corrupt")).writeBytes(byteArrayOf(0x01, 0x02, 0x03))
            }
            waitForIdle()

            scenario.onActivity { coordinator.startFromCart() }
            waitForIdle()

            assertTrue(store.isPending())
            assertEquals(0, gateway.statusCalls.get())
            assertEquals(0, gateway.placeCalls.get())
            onView(withId(R.id.swiggyStepTitle)).inRoot(isDialog())
                .check(matches(withText("Please check before continuing")))
        }
    }

    @Test
    fun timeout_status_keeps_pending_attempt_and_does_not_start_fresh_checkout() {
        val attemptId = UUID.randomUUID().toString()
        val gateway = FakeGateway(
            statusResult = { _ ->
                SwiggyMcpResult.Failure(
                    userMessage = "Gateway timeout.",
                    retryable = true,
                )
            },
        )

        launchCoordinator("timeout", gateway, seedPendingAttemptId = attemptId) { _, store, _ ->
            waitForIdle()

            assertTrue(store.isPending())
            assertEquals(1, gateway.statusCalls.get())
            assertEquals(0, gateway.reviewCalls.get())
            assertEquals(0, gateway.placeCalls.get())
            onView(withId(R.id.swiggyStepTitle)).inRoot(isDialog())
                .check(matches(withText("Please check before continuing")))
        }
    }

    @Test
    fun mocked_upi_pending_url_is_rendered_but_never_clicked_in_test() {
        val gateway = FakeGateway(
            addressesResult = success(addresses(currentCart = true)),
            reviewResults = arrayDequeOf(success(review(
                quoteToken = "quote-token-8",
                amount = "175",
                addressId = "addr-8",
                addressLabel = "Home",
                addressFull = "88 Secure Payment Road, Hyderabad",
                items = listOf(SwiggyCheckoutLine("Noodles", 1, "2 pack", "75")),
                charges = listOf(SwiggyCheckoutCharge("Delivery fee", "10")),
                methods = listOf(SwiggyCheckoutMethod("upi", "UPI", "UPI", generateUPIQR = true)),
            ))),
            placeResults = arrayDequeOf(success(attempt(
                attemptId = "attempt-8",
                state = "placed",
                message = "Order placed after UPI approval.",
                orderIds = listOf("order-upi-8"),
            ))),
        )

        launchCoordinator("upi-pending", gateway) { scenario, _, coordinator ->
            scenario.onActivity { coordinator.startFromCart() }
            waitForIdle()

            onView(withText(containsString("UPI"))).inRoot(isDialog())
                .perform(scrollTo(), click())
            waitForIdle()

            onView(withId(R.id.swiggyStepPrimary)).inRoot(isDialog())
                .check(matches(withText("Confirm order · Pay ₹175")))
            onView(withId(R.id.swiggyStepSecondary)).inRoot(isDialog())
                .check(matches(withText("Change payment")))
            assertEquals(0, gateway.placeCalls.get())
            onView(withText("https://mcp.swiggy.com/pay")).check(doesNotExist())
        }
    }

    @Test
    fun mocked_cod_success_acknowledges_once_after_double_tap() {
        val gateway = FakeGateway(
            statusResult = { id ->
                success(attempt(
                    attemptId = requireNotNull(id),
                    state = "placed",
                    message = "Your COD order is confirmed.",
                    orderIds = listOf("order-cod-1"),
                ))
            },
        )
        val attemptId = UUID.randomUUID().toString()
        val settled = AtomicInteger(0)

        launchCoordinator("placed-ack", gateway, seedPendingAttemptId = attemptId, onSettled = settled) { scenario, _, _ ->
            waitForIdle()
            val doneButton = AtomicReference<android.view.View>()
            onView(withId(R.id.swiggyStepPrimary)).inRoot(isDialog())
                .check(matches(withText("Done")))
                .perform(captureView(doneButton))
            scenario.onActivity {
                doneButton.get().performClick()
                doneButton.get().performClick()
            }
            waitForIdle()
            assertEquals(1, settled.get())
            assertEquals(1, gateway.statusCalls.get())
            onView(withId(R.id.swiggyStepTitle)).check(doesNotExist())
        }
    }

    @Test
    fun not_submitted_state_is_terminal_and_does_not_false_success() {
        val attemptId = UUID.randomUUID().toString()
        val gateway = FakeGateway(
            statusResult = { id ->
                success(attempt(
                    attemptId = requireNotNull(id),
                    state = "not_submitted",
                    message = "The server proved no order left the cart.",
                    orderIds = listOf("order-safe-1"),
                ))
            },
        )

        launchCoordinator("restart-pending", gateway, seedPendingAttemptId = attemptId) { _, _, _ ->
            waitForIdle()
            onView(withId(R.id.swiggyStepTitle)).inRoot(isDialog())
                .check(matches(withText("No order was sent")))
            onView(withText("Open secure UPI payment")).check(doesNotExist())
            assertEquals(1, gateway.statusCalls.get())
            assertEquals(0, gateway.placeCalls.get())
        }
    }

    @After
    fun cleanUpSyntheticArtifacts() {
        usedTags.forEach { tag ->
            val base = File(context.noBackupFilesDir, storeFileName(tag))
            listOf(
                base,
                File(base.path + ".bak"),
                File(base.path + ".new"),
                File(base.path + "-opened"),
                File(base.path + "-opened.bak"),
                File(base.path + "-opened.new"),
            ).forEach { it.delete() }
        }
    }

    private fun launchCoordinator(
        tag: String,
        gateway: FakeGateway,
        seedPendingAttemptId: String? = null,
        onSettled: AtomicInteger = AtomicInteger(0),
        block: (ActivityScenario<MainActivity>, SwiggyCheckoutStore, SwiggyCheckoutCoordinator) -> Unit,
    ) {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var coordinator: SwiggyCheckoutCoordinator? = null
        lateinit var store: SwiggyCheckoutStore
        try {
            scenario.onActivity { activity ->
                store = SwiggyCheckoutStore(activity, storeFileName(tag))
                seedPendingAttemptId?.let { assertTrue(store.save(it)) }
                coordinator = SwiggyCheckoutCoordinator(
                    activity = activity,
                    announce = {},
                    store = store,
                    gateway = gateway,
                    onSettled = { onSettled.incrementAndGet() },
                )
                coordinator?.onResume()
            }
            waitForIdle()
            block(scenario, store, requireNotNull(coordinator))
        } finally {
            runCatching { coordinator?.let { current -> scenario.onActivity { current.destroy() } } }
            waitForIdle()
            scenario.close()
            SwiggyCheckoutStore(context, storeFileName(tag)).clear()
        }
    }

    private fun waitForIdle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun storeFileName(tag: String): String = "test-swiggy-checkout-$tag-v1"

    private fun addresses(currentCart: Boolean): List<SwiggyMcpClient.SwiggyAddress> {
        return listOf(
            SwiggyMcpClient.SwiggyAddress(
                id = "addr-1",
                label = "Home",
                hasCurrentCart = currentCart,
            )
        )
    }

    private fun review(
        quoteToken: String,
        amount: String,
        addressId: String,
        addressLabel: String,
        addressFull: String,
        items: List<SwiggyCheckoutLine>,
        charges: List<SwiggyCheckoutCharge>,
        methods: List<SwiggyCheckoutMethod>,
        expiresAt: Long = System.currentTimeMillis() / 1000L + 60L,
    ): SwiggyCheckoutReview {
        return SwiggyCheckoutReview(
            quoteToken = quoteToken,
            expiresAt = expiresAt,
            addressId = addressId,
            addressLabel = addressLabel,
            addressFull = addressFull,
            amount = amount,
            items = items,
            charges = charges,
            methods = methods,
        )
    }

    private fun attempt(
        attemptId: String,
        state: String,
        message: String,
        orderIds: List<String>,
        paymentUrl: String? = null,
        pollAfterMs: Long? = null,
        pollUntilEpochMs: Long? = null,
    ): SwiggyCheckoutAttempt {
        return SwiggyCheckoutAttempt(
            attemptId = attemptId,
            state = state,
            message = message,
            orderIds = orderIds,
            paymentUrl = paymentUrl,
            pollAfterMs = pollAfterMs,
            pollUntilEpochMs = pollUntilEpochMs,
        )
    }

    private fun success(value: List<SwiggyMcpClient.SwiggyAddress>): SwiggyMcpResult.Success<List<SwiggyMcpClient.SwiggyAddress>> {
        return SwiggyMcpResult.Success(value)
    }

    private fun success(value: SwiggyCheckoutReview): SwiggyMcpResult.Success<SwiggyCheckoutReview> {
        return SwiggyMcpResult.Success(value)
    }

    private fun success(value: SwiggyCheckoutAttempt): SwiggyMcpResult.Success<SwiggyCheckoutAttempt> {
        return SwiggyMcpResult.Success(value)
    }

    private fun <T> arrayDequeOf(vararg items: T): ArrayDeque<T> {
        return ArrayDeque<T>().apply { addAll(items.toList()) }
    }

    private fun captureView(target: AtomicReference<View>): ViewAction {
        return object : ViewAction {
            override fun getConstraints() = isAssignableFrom(View::class.java)
            override fun getDescription() = "capture view reference"
            override fun perform(uiController: UiController, view: View) {
                target.set(view)
                uiController.loopMainThreadUntilIdle()
            }
        }
    }

    private fun doubleClickWithoutIdle() = object : ViewAction {
        override fun getConstraints() = isDisplayed()
        override fun getDescription() = "deliver two queued taps before provider completion"
        override fun perform(uiController: UiController, view: View) {
            view.performClick()
            view.performClick()
            uiController.loopMainThreadUntilIdle()
        }
    }

    private class FakeGateway(
        addressesResult: SwiggyMcpResult<List<SwiggyMcpClient.SwiggyAddress>> = SwiggyMcpResult.Failure("No address response configured"),
        reviewResults: ArrayDeque<SwiggyMcpResult<SwiggyCheckoutReview>> = ArrayDeque(),
        placeResults: ArrayDeque<SwiggyMcpResult<SwiggyCheckoutAttempt>> = ArrayDeque(),
        private val statusResult: (String?) -> SwiggyMcpResult<SwiggyCheckoutAttempt> = {
            SwiggyMcpResult.Success(
                SwiggyCheckoutAttempt(
                    attemptId = null,
                    state = "none",
                    message = "No prior attempt.",
                )
            )
        },
        private val deferFirstAddresses: Boolean = false,
        private val deferFirstPlace: Boolean = false,
    ) : SwiggyCheckoutGateway {
        val addressesCalls = AtomicInteger(0)
        val reviewCalls = AtomicInteger(0)
        val placeCalls = AtomicInteger(0)
        val statusCalls = AtomicInteger(0)
        private val addressesQueue = ArrayDeque<SwiggyMcpResult<List<SwiggyMcpClient.SwiggyAddress>>>().apply { add(addressesResult) }
        private val reviewQueue = reviewResults
        private val placeQueue = placeResults
        private var deferredAddressesCallback: SwiggyCallback<List<SwiggyMcpClient.SwiggyAddress>>? = null
        private var deferredPlaceCallback: SwiggyCallback<SwiggyCheckoutAttempt>? = null

        override fun addresses(callback: SwiggyCallback<List<SwiggyMcpClient.SwiggyAddress>>) {
            addressesCalls.incrementAndGet()
            if (deferFirstAddresses && deferredAddressesCallback == null) {
                deferredAddressesCallback = callback
                return
            }
            callback(addressesQueue.pollFirst() ?: SwiggyMcpResult.Failure("No address response queued"))
        }

        override fun review(addressId: String, callback: SwiggyCallback<SwiggyCheckoutReview>) {
            reviewCalls.incrementAndGet()
            callback(reviewQueue.pollFirst() ?: SwiggyMcpResult.Failure("No review response queued"))
        }

        override fun place(
            quoteToken: String,
            methodId: String,
            attemptId: String,
            callback: SwiggyCallback<SwiggyCheckoutAttempt>,
        ) {
            placeCalls.incrementAndGet()
            if (deferFirstPlace && deferredPlaceCallback == null) {
                deferredPlaceCallback = callback
                return
            }
            callback(placeQueue.pollFirst() ?: SwiggyMcpResult.Failure("No place response queued"))
        }

        override fun status(attemptId: String?, callback: SwiggyCallback<SwiggyCheckoutAttempt>) {
            statusCalls.incrementAndGet()
            callback(statusResult(attemptId))
        }

        fun releaseDeferredAddresses(result: SwiggyMcpResult<List<SwiggyMcpClient.SwiggyAddress>>? = null) {
            val callback = deferredAddressesCallback ?: error("No deferred address callback is waiting")
            deferredAddressesCallback = null
            callback(result ?: addressesQueue.pollFirst() ?: SwiggyMcpResult.Failure("No address response queued"))
        }

        fun releaseDeferredPlace(result: SwiggyMcpResult<SwiggyCheckoutAttempt>? = null) {
            val callback = deferredPlaceCallback ?: error("No deferred place callback is waiting")
            deferredPlaceCallback = null
            callback(result ?: placeQueue.pollFirst() ?: SwiggyMcpResult.Failure("No place response queued"))
        }
    }
}
