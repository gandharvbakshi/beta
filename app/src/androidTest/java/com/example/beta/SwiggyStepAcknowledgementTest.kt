package com.example.beta

import android.view.View
import android.widget.CheckBox
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.isNotEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class SwiggyStepAcknowledgementTest {
    @Test
    fun primaryRequiresAcknowledgementAndUncheckRevokesIt() {
        val primaryCalls = AtomicInteger(0)
        val checkChanges = mutableListOf<Boolean>()
        val primaryView = AtomicReference<View>()
        val (scenario, dialog) = launchDialog(
            acknowledgedScreen(
                onCheck = { checkChanges += it },
                onPrimary = { primaryCalls.incrementAndGet() },
            ),
        )
        try {
            onView(withId(R.id.swiggyStepAcknowledgement)).inRoot(isDialog())
                .perform(scrollTo())
                .check(matches(isDisplayed()))
            onView(withId(R.id.swiggyStepPrimary)).inRoot(isDialog())
                .perform(scrollTo())
                .check(matches(isNotEnabled()))
            onView(withId(R.id.swiggyStepPrimary)).inRoot(isDialog()).perform(captureView(primaryView))
            scenario.onActivity { primaryView.get().performClick() }
            assertEquals(0, primaryCalls.get())

            onView(withId(R.id.swiggyStepAcknowledgement)).inRoot(isDialog()).perform(scrollTo(), click())
            onView(withId(R.id.swiggyStepPrimary)).inRoot(isDialog()).check(matches(isEnabled()))
            assertTrue(checkChanges.last())

            onView(withId(R.id.swiggyStepAcknowledgement)).inRoot(isDialog()).perform(scrollTo(), click())
            onView(withId(R.id.swiggyStepPrimary)).inRoot(isDialog()).check(matches(isNotEnabled()))
            assertFalse(checkChanges.last())

            onView(withId(R.id.swiggyStepAcknowledgement)).inRoot(isDialog()).perform(scrollTo(), click())
            onView(withId(R.id.swiggyStepPrimary)).inRoot(isDialog()).perform(scrollTo(), click())
            assertEquals(1, primaryCalls.get())
        } finally {
            scenario.onActivity { dialog.dismiss() }
            scenario.close()
        }
    }

    @Test
    fun reshowResetsAndDetachedOldCheckboxCannotAcknowledgeFreshScreen() {
        val staleChanges = mutableListOf<Boolean>()
        val freshChanges = mutableListOf<Boolean>()
        val primaryCalls = AtomicInteger(0)
        val staleCheckbox = AtomicReference<CheckBox>()
        val freshCheckbox = AtomicReference<CheckBox>()
        lateinit var dialog: SwiggyOrderStepDialog
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                dialog = SwiggyOrderStepDialog(activity)
                dialog.show(acknowledgedScreen({ staleChanges += it }, {}))
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            onView(withId(R.id.swiggyStepAcknowledgement)).inRoot(isDialog()).perform(
                captureCheckBox(staleCheckbox),
            )
            assertFalse(staleCheckbox.get().isChecked)
            onView(withId(R.id.swiggyStepAcknowledgement)).inRoot(isDialog()).perform(scrollTo(), click())
            assertTrue(staleCheckbox.get().isChecked)

            scenario.onActivity {
                dialog.show(acknowledgedScreen({ freshChanges += it }, { primaryCalls.incrementAndGet() }))
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals(listOf(false), freshChanges)
            onView(withId(R.id.swiggyStepAcknowledgement)).inRoot(isDialog()).perform(
                captureCheckBox(freshCheckbox),
            )
            assertFalse("Every new presentation starts unchecked", freshCheckbox.get().isChecked)
            scenario.onActivity {
                staleCheckbox.get().isChecked = false
                staleCheckbox.get().performClick()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            onView(withId(R.id.swiggyStepPrimary)).inRoot(isDialog()).check(matches(isNotEnabled()))
            assertEquals(listOf(false), freshChanges)
            onView(withId(R.id.swiggyStepAcknowledgement)).inRoot(isDialog()).perform(scrollTo(), click())
            onView(withId(R.id.swiggyStepPrimary)).inRoot(isDialog()).perform(scrollTo(), click())
            assertEquals(1, primaryCalls.get())
            assertEquals(listOf(false, true), staleChanges)
        } finally {
            scenario.onActivity { dialog.dismiss() }
            scenario.close()
        }
    }

    @Test
    fun secondaryTertiaryAndCancelRemainAvailableBeforeAcknowledgement() {
        val primaryCalls = AtomicInteger(0)
        val cancelCalls = AtomicInteger(0)
        val checkChanges = mutableListOf<Boolean>()
        val (scenario, dialog) = launchDialog(
            acknowledgedScreen(
                onCheck = { checkChanges += it },
                onPrimary = { primaryCalls.incrementAndGet() },
            ).copy(
                secondary = SwiggyStepAction("Edit") { },
                tertiary = SwiggyStepAction("More options") { },
                cancel = { cancelCalls.incrementAndGet() },
            ),
        )
        try {
            onView(withId(R.id.swiggyStepPrimary)).inRoot(isDialog()).perform(scrollTo()).check(matches(isNotEnabled()))
            onView(withId(R.id.swiggyStepSecondary)).inRoot(isDialog()).perform(scrollTo()).check(matches(isEnabled()))
            onView(withId(R.id.swiggyStepTertiary)).inRoot(isDialog()).perform(scrollTo()).check(matches(isEnabled()))
            onView(withId(R.id.swiggyStepClose)).inRoot(isDialog()).perform(click())

            assertEquals(1, cancelCalls.get())
            assertEquals(0, primaryCalls.get())
            assertEquals("Cancel must not acknowledge the review", listOf(false), checkChanges)
        } finally {
            scenario.onActivity { dialog.dismiss() }
            scenario.close()
        }
    }

    @Test
    fun screenWithoutAcknowledgementKeepsPrimaryActionEnabled() {
        val primaryCalls = AtomicInteger(0)
        val (scenario, dialog) = launchDialog(
            SwiggyStepScreen(
                eyebrow = "Status",
                title = "Connected",
                message = "No acknowledgement required",
                caption = "",
                primary = SwiggyStepAction("Continue") { primaryCalls.incrementAndGet() },
            ),
        )
        try {
            onView(withId(R.id.swiggyStepPrimary)).inRoot(isDialog())
                .perform(scrollTo())
                .check(matches(isEnabled()))
            onView(withId(R.id.swiggyStepAcknowledgement)).inRoot(isDialog()).check(
                androidx.test.espresso.assertion.ViewAssertions.doesNotExist(),
            )
            onView(withId(R.id.swiggyStepPrimary)).inRoot(isDialog()).perform(scrollTo(), click())
            assertEquals(1, primaryCalls.get())
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

    private fun acknowledgedScreen(
        onCheck: (Boolean) -> Unit,
        onPrimary: () -> Unit,
    ) = SwiggyStepScreen(
        eyebrow = "Review",
        title = "Confirm the step",
        message = "Review the information first.",
        caption = "",
        rows = listOf(SwiggyStepRow("Reviewed item")),
        primary = SwiggyStepAction("Continue", onPrimary),
        acknowledgement = SwiggyStepAcknowledgement("I have reviewed this information", onCheck),
    )

    private fun captureCheckBox(target: AtomicReference<CheckBox>): androidx.test.espresso.ViewAction =
        object : androidx.test.espresso.ViewAction {
            override fun getConstraints() = androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(CheckBox::class.java)
            override fun getDescription() = "capture acknowledgement checkbox"
            override fun perform(uiController: androidx.test.espresso.UiController, view: View) {
                target.set(view as CheckBox)
                uiController.loopMainThreadUntilIdle()
            }
        }

    private fun captureView(target: AtomicReference<View>): androidx.test.espresso.ViewAction =
        object : androidx.test.espresso.ViewAction {
            override fun getConstraints() = androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(View::class.java)
            override fun getDescription() = "capture primary button"
            override fun perform(uiController: androidx.test.espresso.UiController, view: View) {
                target.set(view)
                uiController.loopMainThreadUntilIdle()
            }
        }
}
