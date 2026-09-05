package com.example.beta

import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.hasSibling
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers.allOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class SwiggyReadOnlyUiResilienceTest {
    @Test
    fun homeControlsRemainReachableAtCurrentFontScale() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            onView(withId(R.id.swiggyConnectionAction)).perform(scrollTo())
                .check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
            // The existing signed-in device is required for this read-only home check.
            // Do not connect, disconnect, submit, or invoke the microphone in this test.
            val controls = listOf(R.id.orderCommandInput, R.id.orderVoiceInputButton, R.id.orderSubmitButton)
            controls.forEach { id ->
                onView(withId(id)).perform(scrollTo())
                    .check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
            }
            scenario.onActivity { activity ->
                val submit = activity.findViewById<TextView>(R.id.orderSubmitButton)
                val layout = requireNotNull(submit.layout)
                assertTrue("Confirmation button text must fit vertically",
                    layout.height <= submit.height - submit.compoundPaddingTop - submit.compoundPaddingBottom)
                assertTrue("Confirmation label must not be ellipsized",
                    (0 until layout.lineCount).all { layout.getEllipsisCount(it) == 0 })
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun doublePrimaryTapEmitsOnce() {
        val primaryCalls = AtomicInteger(0)
        val (scenario, dialog) = launchDialog(
            stepScreen(
                rows = listOf(
                    SwiggyStepRow(
                        title = "Milk",
                        detail = "1 litre",
                        action = SwiggyStepAction("Change") { },
                    ),
                ),
                primary = SwiggyStepAction("Apply") { primaryCalls.incrementAndGet() },
            ),
        )

        try {
            onView(withId(R.id.swiggyStepPrimary)).inRoot(isDialog()).perform(click(), click())
            assertEquals(1, primaryCalls.get())
        } finally {
            scenario.onActivity { dialog.dismiss() }
            scenario.close()
        }
    }

    @Test
    fun secondaryEditConsumesOldPrimaryAndFreshPrimaryStillWorks() {
        val stalePrimaryCalls = AtomicInteger(0)
        val freshPrimaryCalls = AtomicInteger(0)
        val stalePrimary = AtomicReference<View>()
        lateinit var dialog: SwiggyOrderStepDialog
        val scenario = ActivityScenario.launch(MainActivity::class.java)

        try {
            scenario.onActivity { activity ->
                dialog = SwiggyOrderStepDialog(activity)
                dialog.show(
                    stepScreen(
                        rows = listOf(
                            SwiggyStepRow(
                                title = "Product Alpha",
                                detail = "Original suggestion",
                                action = SwiggyStepAction("Change") { },
                            ),
                        ),
                        primary = SwiggyStepAction("Apply") { stalePrimaryCalls.incrementAndGet() },
                        secondary = SwiggyStepAction("Edit") {
                            dialog.show(
                                stepScreen(
                                    rows = listOf(
                                        SwiggyStepRow(
                                            title = "Product Beta",
                                            detail = "Fresh suggestion",
                                            action = SwiggyStepAction("Change") { },
                                        ),
                                    ),
                                    primary = SwiggyStepAction("Apply") { freshPrimaryCalls.incrementAndGet() },
                                ),
                            )
                        },
                        cancel = { },
                    ),
                )
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            onView(withId(R.id.swiggyStepPrimary)).inRoot(isDialog()).perform(captureView(stalePrimary))
            onView(withId(R.id.swiggyStepSecondary)).inRoot(isDialog()).perform(click())
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            scenario.onActivity { stalePrimary.get().performClick() }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals(0, stalePrimaryCalls.get())
            assertEquals(1, freshPrimaryCalls.get())
        } finally {
            scenario.onActivity { dialog.dismiss() }
            scenario.close()
        }
    }

    @Test
    fun cancelAfterEditUsesOnlyFreshCancellationOnce() {
        val staleCancelCalls = AtomicInteger(0)
        val freshPrimaryCalls = AtomicInteger(0)
        val freshCancelCalls = AtomicInteger(0)
        val staleClose = AtomicReference<View>()
        val freshPrimary = AtomicReference<View>()
        lateinit var dialog: SwiggyOrderStepDialog
        val scenario = ActivityScenario.launch(MainActivity::class.java)

        try {
            scenario.onActivity { activity ->
                dialog = SwiggyOrderStepDialog(activity)
                dialog.show(
                    stepScreen(
                        rows = listOf(
                            SwiggyStepRow(
                                title = "Apples",
                                detail = "Pack of 6",
                                action = SwiggyStepAction("Change") { },
                            ),
                        ),
                        primary = SwiggyStepAction("Apply") { },
                        secondary = SwiggyStepAction("Edit") {
                            dialog.show(
                                stepScreen(
                                    rows = listOf(
                                        SwiggyStepRow(
                                            title = "Bananas",
                                            detail = "1 dozen",
                                            action = SwiggyStepAction("Change") { },
                                        ),
                                    ),
                                    primary = SwiggyStepAction("Apply") { freshPrimaryCalls.incrementAndGet() },
                                    cancel = { freshCancelCalls.incrementAndGet() },
                                ),
                            )
                        },
                        cancel = { staleCancelCalls.incrementAndGet() },
                    ),
                )
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            onView(withId(R.id.swiggyStepClose)).inRoot(isDialog()).perform(captureView(staleClose))
            onView(withId(R.id.swiggyStepSecondary)).inRoot(isDialog()).perform(click())
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            onView(withId(R.id.swiggyStepPrimary)).inRoot(isDialog()).perform(captureView(freshPrimary))

            scenario.onActivity {
                staleClose.get().performClick()
                staleClose.get().performClick()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals(0, staleCancelCalls.get())
            assertEquals(1, freshCancelCalls.get())

            scenario.onActivity { freshPrimary.get().performClick() }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals(0, freshPrimaryCalls.get())
        } finally {
            scenario.onActivity { dialog.dismiss() }
            scenario.close()
        }
    }

    @Test
    fun longSyntheticAddressAndTenRowsRemainReachable() {
        val rowTenClicks = AtomicInteger(0)
        val longAddress = "You have selected your address marked home, which is 8/18 in Example Apartments on Orchard Road"
        val (scenario, dialog) = launchDialog(
            stepScreen(
                rows = (1..10).map { index ->
                    SwiggyStepRow(
                        title = "Product $index",
                        detail = if (index == 10) longAddress else "Line $index",
                        action = SwiggyStepAction("Change") {
                            if (index == 10) rowTenClicks.incrementAndGet()
                        },
                    )
                },
                primary = SwiggyStepAction("Continue") { },
                message = longAddress,
                caption = longAddress,
                cancel = { },
            ),
        )

        try {
            onView(withId(R.id.swiggyStepClose)).inRoot(isDialog())
                .check(androidx.test.espresso.assertion.ViewAssertions.matches(withText(R.string.swiggy_step_stop)))
                .check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
            onView(withId(R.id.swiggyStepPrimary)).inRoot(isDialog())
                .perform(scrollTo())
                .check(androidx.test.espresso.assertion.ViewAssertions.matches(withText("Continue")))
                .check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
            onView(withId(R.id.swiggyStepCaption)).inRoot(isDialog())
                .perform(scrollTo())
                .check(androidx.test.espresso.assertion.ViewAssertions.matches(withContentDescription(longAddress)))
                .check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
            onView(withRowAction("Product 10")).inRoot(isDialog())
                .perform(scrollTo())
                .check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
                .perform(click())
            assertEquals(1, rowTenClicks.get())
        } finally {
            scenario.onActivity { dialog.dismiss() }
            scenario.close()
        }
    }

    private fun launchDialog(screen: SwiggyStepScreen): Pair<ActivityScenario<MainActivity>, SwiggyOrderStepDialog> {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var dialog: SwiggyOrderStepDialog
        scenario.onActivity { activity ->
            dialog = SwiggyOrderStepDialog(activity)
            dialog.show(screen)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        return scenario to dialog
    }

    private fun stepScreen(
        rows: List<SwiggyStepRow>,
        primary: SwiggyStepAction? = null,
        secondary: SwiggyStepAction? = null,
        cancel: (() -> Unit)? = null,
        message: String = "Synthetic dialog proof",
        caption: String = "Cart review",
    ): SwiggyStepScreen {
        return SwiggyStepScreen(
            eyebrow = "Review",
            title = "Review cart suggestions",
            message = message,
            caption = caption,
            rows = rows,
            choices = emptyList(),
            safetyNote = "Test-only dialog",
            primary = primary,
            secondary = secondary,
            tertiary = null,
            cancel = cancel,
        )
    }

    private fun withRowAction(productTitle: String) =
        allOf(
            withId(R.id.swiggyStepRowAction),
            hasSibling(androidx.test.espresso.matcher.ViewMatchers.hasDescendant(withText(productTitle))),
        )

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
}
