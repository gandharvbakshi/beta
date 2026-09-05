package com.example.beta

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBackUnconditionally
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.hasSibling
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers.allOf
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class SwiggyReviewInteractionTest {
    @Test
    fun renderTenRowsAndScrollToChangeAction() {
        val calls = AtomicInteger(0)
        val (scenario, dialog) = launchDialog(
            screen = stepScreen(
                rows = (1..10).map { index ->
                    SwiggyStepRow(
                        title = "Product $index",
                        detail = "Line $index",
                        action = SwiggyStepAction("Change") { calls.incrementAndGet() },
                    )
                },
            ),
        )

        try {
            onView(withRowAction("Product 10")).inRoot(isDialog()).perform(scrollTo(), click())
            onView(withRowAction("Product 10")).inRoot(isDialog()).perform(scrollTo(), click())

            assertEquals(1, calls.get())
        } finally {
            scenario.onActivity { dialog.dismiss() }
            scenario.close()
        }
    }

    @Test
    fun staleRowReferenceDoesNothingAfterNewScreenShowsFreshCallback() {
        val staleCalls = AtomicInteger(0)
        val freshCalls = AtomicInteger(0)
        val staleButton = AtomicReference<View>()
        val (scenario, dialog) = launchDialog(
            screen = stepScreen(
                rows = listOf(
                    SwiggyStepRow(
                        title = "Product Alpha",
                        detail = "Original row",
                        action = SwiggyStepAction("Change") { staleCalls.incrementAndGet() },
                    ),
                ),
                primary = SwiggyStepAction("Apply") { staleCalls.incrementAndGet() },
            ),
        )

        try {
            onView(withRowAction("Product Alpha")).inRoot(isDialog()).perform(scrollTo(), captureView(staleButton))

            scenario.onActivity {
                dialog.show(
                    stepScreen(
                        rows = listOf(
                            SwiggyStepRow(
                                title = "Product Beta",
                                detail = "Fresh row",
                                action = SwiggyStepAction("Change") { freshCalls.incrementAndGet() },
                            ),
                        ),
                        primary = SwiggyStepAction("Apply") { freshCalls.incrementAndGet() },
                    ),
                )
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            scenario.onActivity { staleButton.get().performClick() }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals(0, staleCalls.get())

            onView(withRowAction("Product Beta")).inRoot(isDialog()).perform(scrollTo(), click())
            assertEquals(1, freshCalls.get())
        } finally {
            scenario.onActivity { dialog.dismiss() }
            scenario.close()
        }
    }

    @Test
    fun backAndDismissInvalidateOldApplyCallback() {
        val backApplyCalls = AtomicInteger(0)
        val backCancelCalls = AtomicInteger(0)
        val backApplyButton = AtomicReference<View>()
        val (backScenario, backDialog) = launchDialog(
            screen = stepScreen(
                rows = listOf(
                    SwiggyStepRow(
                        title = "Product Gamma",
                        detail = "Back test",
                        action = SwiggyStepAction("Change") { },
                    ),
                ),
                primary = SwiggyStepAction("Apply") { backApplyCalls.incrementAndGet() },
                cancel = { backCancelCalls.incrementAndGet() },
            ),
        )

        try {
            onView(withId(R.id.swiggyStepPrimary)).inRoot(isDialog()).perform(captureView(backApplyButton))
            pressBackUnconditionally()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            assertEquals(1, backCancelCalls.get())
            backScenario.onActivity { backApplyButton.get().performClick() }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals(0, backApplyCalls.get())
        } finally {
            backScenario.onActivity { backDialog.dismiss() }
            backScenario.close()
        }

        val dismissApplyCalls = AtomicInteger(0)
        val dismissButton = AtomicReference<View>()
        val (dismissScenario, dismissDialog) = launchDialog(
            screen = stepScreen(
                rows = listOf(
                    SwiggyStepRow(
                        title = "Product Delta",
                        detail = "Dismiss test",
                        action = SwiggyStepAction("Change") { },
                    ),
                ),
                primary = SwiggyStepAction("Apply") { dismissApplyCalls.incrementAndGet() },
                cancel = { },
            ),
        )

        try {
            onView(withId(R.id.swiggyStepPrimary)).inRoot(isDialog()).perform(captureView(dismissButton))
            dismissScenario.onActivity { dismissDialog.dismiss() }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            dismissScenario.onActivity { dismissButton.get().performClick() }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals(0, dismissApplyCalls.get())
        } finally {
            dismissScenario.onActivity { dismissDialog.dismiss() }
            dismissScenario.close()
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
        cancel: (() -> Unit)? = null,
    ): SwiggyStepScreen {
        return SwiggyStepScreen(
            eyebrow = "Review",
            title = "Review cart suggestions",
            message = "Synthetic dialog proof",
            caption = "Cart review",
            rows = rows,
            choices = emptyList(),
            safetyNote = "Test-only dialog",
            primary = primary,
            secondary = null,
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
