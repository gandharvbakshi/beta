package com.example.beta

import android.os.SystemClock
import android.widget.Button
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.Visibility.GONE
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.ActivityTestRule
import org.hamcrest.Matchers.containsString
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BetaAppSmokeTest {

    @get:Rule
    val activityRule = ActivityTestRule(MainActivity::class.java)

    @Test
    fun homeShowsOnlyTheExpectedSwiggyPathForCurrentConnectionState() {
        onView(withId(R.id.swiggyConnectionAction))
            .check(matches(isDisplayed()))

        val actionText = waitForSettledConnectionAction()
        val connectText = activityRule.activity.getString(R.string.swiggy_connection_action)
        val reconnectText = activityRule.activity.getString(R.string.swiggy_connection_reconnect_action)
        val disconnectText = activityRule.activity.getString(R.string.swiggy_connection_disconnect)
        assertTrue(
            "Unexpected Swiggy connection action: $actionText",
            actionText == connectText || actionText == reconnectText || actionText == disconnectText,
        )

        if (actionText == disconnectText) {
            onView(withId(R.id.orderComposerCard)).check(matches(isDisplayed()))
        } else {
            onView(withId(R.id.orderComposerCard)).check(matches(withEffectiveVisibility(GONE)))
        }

        onView(withText(containsString("Blinkit"))).check(doesNotExist())
        onView(withText(containsString("AccessibilityService"))).check(doesNotExist())
        onView(withText(containsString("screen capture"))).check(doesNotExist())
    }

    private fun waitForSettledConnectionAction(): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var actionText = ""
        repeat(100) {
            instrumentation.runOnMainSync {
                actionText = activityRule.activity
                    .findViewById<Button>(R.id.swiggyConnectionAction)
                    .text
                    .toString()
            }
            if (
                actionText == activityRule.activity.getString(R.string.swiggy_connection_action) ||
                actionText == activityRule.activity.getString(R.string.swiggy_connection_reconnect_action) ||
                actionText == activityRule.activity.getString(R.string.swiggy_connection_disconnect)
            ) {
                return actionText
            }
            SystemClock.sleep(100)
        }
        return actionText
    }
}
