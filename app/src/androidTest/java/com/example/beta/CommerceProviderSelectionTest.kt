package com.example.beta

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.ActivityTestRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.hamcrest.Matchers.not

@RunWith(AndroidJUnit4::class)
class CommerceProviderSelectionTest {
    @get:Rule
    val activityRule = ActivityTestRule(MainActivity::class.java, true, false)

    @Before
    fun resetProviderSession() {
        SwiggyExecutionMode.resetSession()
        CommerceProviderRouter.resetSession()
        activityRule.launchActivity(null)
    }

    @After
    fun cleanUpProviderSession() {
        activityRule.finishActivity()
        CommerceProviderRouter.resetSession()
        SwiggyExecutionMode.resetSession()
    }

    @Test
    fun swiggyDefaultsToMcpUiAndCanToggleScreenAssistedMode() {
        assertSwiggyMcpUi()

        onView(withId(R.id.swiggyExecutionModeAction)).check(
            matches(withText(R.string.swiggy_use_screen_assisted))
        )
        clickExecutionModeAction()

        assertSwiggyScreenAssistedUi()

        onView(withId(R.id.providerSwiggy)).check(matches(isChecked()))
        onView(withId(R.id.swiggyExecutionModeAction)).check(
            matches(withText(R.string.swiggy_use_mcp))
        )
        clickExecutionModeAction()

        assertSwiggyMcpUi()
        onView(withId(R.id.providerSwiggy)).check(matches(isChecked()))
    }

    @Test
    fun blinkitAndZeptoSelectionHideSwiggyPanelUntilSwiggyIsRestored() {
        listOf(R.id.providerBlinkit, R.id.providerZepto).forEach { providerId ->
            onView(withId(providerId)).perform(click())
            onView(withId(providerId)).check(matches(isChecked()))
            onView(withId(R.id.setupPermissionsCard)).check(
                matches(withEffectiveVisibility(Visibility.VISIBLE))
            )
            onView(withId(R.id.swiggyConnectionPanel)).check(
                matches(withEffectiveVisibility(Visibility.GONE))
            )
            assertTrue(
                CommerceProviderRouter.currentSessionProvider() !=
                    CommerceProviderRouter.CommerceProvider.SWIGGY_INSTAMART
            )
        }

        onView(withId(R.id.providerSwiggy)).perform(click())

        assertSwiggyMcpUi()
    }

    @Test
    fun explicitChoiceSurvivesActivityRecreationWithinTheProcessSession() {
        onView(withId(R.id.providerBlinkit)).perform(click()).check(matches(isChecked()))
        activityRule.activity.runOnUiThread { activityRule.activity.recreate() }
        onView(withId(R.id.providerBlinkit)).check(matches(isChecked()))
        assertEquals(
            CommerceProviderRouter.CommerceProvider.BLINKIT,
            CommerceProviderRouter.currentSessionProvider()
        )
    }

    private fun assertSwiggyMcpUi() {
        onView(withId(R.id.providerChoiceGroup)).check(matches(isDisplayed()))
        onView(withId(R.id.providerSwiggy)).check(matches(isChecked()))
        onView(withId(R.id.swiggyConnectionPanel)).check(
            matches(withEffectiveVisibility(Visibility.VISIBLE))
        )
        onView(withId(R.id.setupPermissionsCard)).check(
            matches(withEffectiveVisibility(Visibility.GONE))
        )
        onView(withId(R.id.swiggyConnectionAction)).check(
            matches(withEffectiveVisibility(Visibility.VISIBLE))
        )
        onView(withId(R.id.swiggyExecutionModeAction)).check(
            matches(withEffectiveVisibility(Visibility.VISIBLE))
        )
        onView(withId(R.id.swiggyExecutionModeAction)).check(
            matches(withText(R.string.swiggy_use_screen_assisted))
        )
        onView(withId(R.id.swiggyConnectionStatus)).check(
            matches(not(withText("")))
        )
        onView(withId(R.id.swiggyConnectionDetail)).check(
            matches(not(withText("")))
        )
        assertEquals(
            CommerceProviderRouter.CommerceProvider.SWIGGY_INSTAMART,
            CommerceProviderRouter.currentSessionProvider()
        )
    }

    private fun assertSwiggyScreenAssistedUi() {
        onView(withId(R.id.providerSwiggy)).check(matches(isChecked()))
        onView(withId(R.id.swiggyConnectionPanel)).check(
            matches(withEffectiveVisibility(Visibility.VISIBLE))
        )
        onView(withId(R.id.setupPermissionsCard)).check(
            matches(withEffectiveVisibility(Visibility.VISIBLE))
        )
        onView(withId(R.id.swiggyConnectionAction)).check(
            matches(withEffectiveVisibility(Visibility.GONE))
        )
        onView(withId(R.id.swiggyExecutionModeAction)).check(
            matches(withEffectiveVisibility(Visibility.VISIBLE))
        )
        onView(withId(R.id.swiggyExecutionModeAction)).check(
            matches(withText(R.string.swiggy_use_mcp))
        )
        onView(withId(R.id.swiggyConnectionStatus)).check(
            matches(withText(R.string.swiggy_screen_assisted_status))
        )
        onView(withId(R.id.swiggyConnectionDetail)).check(
            matches(withText(R.string.swiggy_screen_assisted_detail))
        )
        assertEquals(
            CommerceProviderRouter.CommerceProvider.SWIGGY_INSTAMART,
            CommerceProviderRouter.currentSessionProvider()
        )
    }

    private fun clickExecutionModeAction() {
        activityRule.runOnUiThread {
            activityRule.activity.findViewById<android.view.View>(R.id.swiggyExecutionModeAction).performClick()
        }
    }
}
