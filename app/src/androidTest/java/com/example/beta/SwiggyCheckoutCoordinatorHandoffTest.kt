package com.example.beta

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.reflect.Method
import java.util.UUID

@RunWith(AndroidJUnit4::class)
// Run only on an isolated, disconnected test emulator with no real Swiggy
// credentials or pending user attempt. MainActivity also owns normal app work.
class SwiggyCheckoutCoordinatorHandoffTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val storeName = "test-swiggy-checkout-handoff-ui-v1"

    @Test
    fun persisted_handoff_shows_check_existing_order_and_support_dialer_after_recreation() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val attemptId = UUID.randomUUID().toString()
        val attempt = SwiggyCheckoutAttempt(
            attemptId = attemptId,
            state = "pending_payment",
            message = "Payment is pending.",
            orderIds = listOf("order-1"),
            paymentUrl = "https://mcp.swiggy.com/pay",
            pollAfterMs = 5000L,
            pollUntilEpochMs = System.currentTimeMillis() + 60_000L,
        )
        try {
            scenario.onActivity { activity ->
                val store = SwiggyCheckoutStore(activity, storeName)
                assertTrue(store.save(attemptId))
                assertTrue(store.markHandoff(attemptId))
                val coordinator = SwiggyCheckoutCoordinator(activity, announce = {}, onSettled = {}, store = store)
                render(coordinator, attempt)
            }

            onView(withId(R.id.swiggyStepTitle)).inRoot(isDialog())
                .check(matches(withText("Check existing order")))
            onView(withId(R.id.swiggyStepPrimary)).inRoot(isDialog())
                .check(matches(withText("Check existing order")))
            onView(withId(R.id.swiggyStepSecondary)).inRoot(isDialog())
                .check(matches(withText("Open Swiggy support dialer")))

            scenario.recreate()
            scenario.onActivity { activity ->
                val store = SwiggyCheckoutStore(activity, storeName)
                val coordinator = SwiggyCheckoutCoordinator(activity, announce = {}, onSettled = {}, store = store)
                render(coordinator, attempt)
            }

            onView(withId(R.id.swiggyStepPrimary)).inRoot(isDialog())
                .check(matches(withText("Check existing order")))
        } finally {
            scenario.close()
        }
    }

    @Test
    fun failed_and_refund_terminal_states_use_bank_and_refund_acknowledgement_label() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                val store = SwiggyCheckoutStore(activity, storeName)
                val coordinator = SwiggyCheckoutCoordinator(activity, announce = {}, onSettled = {}, store = store)
                val failedAttempt = SwiggyCheckoutAttempt(
                    attemptId = UUID.randomUUID().toString(),
                    state = "failed",
                    message = "Payment did not complete.",
                    orderIds = listOf("order-1"),
                )
                render(coordinator, failedAttempt)
            }

            onView(withId(R.id.swiggyStepPrimary)).inRoot(isDialog())
                .check(matches(withText("I checked my bank and refund status")))
        } finally {
            scenario.close()
        }
    }

    @Test
    fun corrupt_attempt_cannot_adopt_or_acknowledge_an_older_terminal_order() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                File(activity.noBackupFilesDir, storeName).writeText("corrupt")
                val store = SwiggyCheckoutStore(activity, storeName)
                val coordinator = SwiggyCheckoutCoordinator(activity, announce = {}, onSettled = {}, store = store)
                assertTrue(coordinator.onResume())
                assertTrue(store.isPending())
            }
            onView(withId(R.id.swiggyStepTitle)).inRoot(isDialog())
                .check(matches(withText("Please check before continuing")))
            onView(withId(R.id.swiggyStepSecondary)).inRoot(isDialog())
                .check(matches(withText("Open Swiggy support dialer")))
        } finally { scenario.close() }
    }

    @After
    fun clean_up_synthetic_artifacts() {
        val file = File(context.noBackupFilesDir, storeName)
        val handoff = File(context.noBackupFilesDir, "$storeName-opened")
        listOf(file, File(file.path + ".bak"), handoff, File(handoff.path + ".bak")).forEach { it.delete() }
    }

    private fun render(coordinator: SwiggyCheckoutCoordinator, attempt: SwiggyCheckoutAttempt) {
        val method: Method = SwiggyCheckoutCoordinator::class.java.getDeclaredMethod("render", SwiggyCheckoutAttempt::class.java)
            .apply { isAccessible = true }
        method.invoke(coordinator, attempt)
    }
}
