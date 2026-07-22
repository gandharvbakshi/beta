package com.example.beta

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.ActivityTestRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CommerceProviderSelectionTest {
    @get:Rule
    val activityRule = ActivityTestRule(MainActivity::class.java, true, false)

    @Before
    fun resetProviderSession() {
        CommerceProviderRouter.resetSession()
        activityRule.launchActivity(null)
    }

    @After
    fun cleanUpProviderSession() {
        activityRule.finishActivity()
        CommerceProviderRouter.resetSession()
    }

    @Test
    fun swiggyIsTheVisibleSessionDefault() {
        onView(withId(R.id.providerChoiceGroup)).check(matches(isDisplayed()))
        onView(withId(R.id.providerSwiggy)).check(matches(isChecked()))
        assertEquals(
            CommerceProviderRouter.CommerceProvider.SWIGGY_INSTAMART,
            CommerceProviderRouter.currentSessionProvider()
        )
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

    @Test
    fun swiggyConnectionPanelOnlyShowsForSwiggy() {
        onView(withId(R.id.swiggyConnectionPanel)).check(matches(isDisplayed()))
        onView(withId(R.id.providerBlinkit)).perform(click())
        onView(withId(R.id.swiggyConnectionPanel)).check(
            matches(withEffectiveVisibility(Visibility.GONE))
        )
        onView(withId(R.id.providerSwiggy)).perform(click())
        onView(withId(R.id.swiggyConnectionPanel)).check(matches(isDisplayed()))
    }
}
