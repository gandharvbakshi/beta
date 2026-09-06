package com.example.beta

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.CoreMatchers.containsString
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Offline demo coverage only: visible local UI, no live provider, gateway, or store mutation. */
@RunWith(AndroidJUnit4::class)
class SwiggyOfflineDemoTest {
    @Test
    fun offline_demo_walks_all_steps_and_leaves_draft_state_untouched() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val before = scenario.captureState()

            onView(withId(R.id.swiggyOfflineDemoAction)).perform(scrollTo(), click())
            assertStep("Request preview")
            onView(withId(R.id.swiggyStepPrimary)).inRoot(isDialog()).perform(scrollTo(), click())

            assertStep("Address preview")
            onView(withId(R.id.swiggyStepPrimary)).inRoot(isDialog()).perform(scrollTo(), click())

            assertStep("Cart review")
            onView(withId(R.id.swiggyStepPrimary)).inRoot(isDialog()).perform(scrollTo(), click())

            assertStep("Payment review")
            onView(withId(R.id.swiggyStepPrimary)).inRoot(isDialog()).perform(scrollTo(), click())

            onView(withId(R.id.swiggyStepTitle)).check(doesNotExist())

            val after = scenario.captureState()
            assertEquals(before.first, after.first)
            assertEquals(before.second, after.second)
            assertEquals(before.third, after.third)
        }
    }

    private fun assertStep(expectedTitle: String) {
        onView(withId(R.id.swiggyStepEyebrow)).inRoot(isDialog())
            .check(matches(withText(containsString("DEMO ONLY"))))
        onView(withId(R.id.swiggyStepTitle)).inRoot(isDialog())
            .check(matches(withText(containsString(expectedTitle))))
        onView(withId(R.id.swiggyStepCaption)).inRoot(isDialog())
            .check(matches(withText(containsString("Nothing will be ordered or charged"))))
    }

    private fun ActivityScenario<MainActivity>.captureState(): Triple<String, Boolean, String?> {
        var draftText = ""
        var pending = false
        var checkoutAttempt: String? = null
        onActivity { activity ->
            draftText = activity.findViewById<android.widget.EditText>(R.id.orderCommandInput).text?.toString().orEmpty()
            pending = SwiggyPendingCartStore(activity).isPending()
            checkoutAttempt = SwiggyCheckoutStore(activity).load()
        }
        return Triple(draftText, pending, checkoutAttempt)
    }
}
