package com.example.beta

import android.Manifest
import android.content.pm.PackageManager
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
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
    fun blinkitSelectionHidesSwiggyPanelUntilSwiggyIsRestored() {
        clickProvider(R.id.providerBlinkit)
        onView(withId(R.id.providerBlinkit)).check(matches(isChecked()))
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

        clickProvider(R.id.providerSwiggy)

        assertSwiggyMcpUi()
    }

    @Test
    fun mainComposerOffersAccessibleVoiceAndTextWithBlinkitBetaLabel() {
        onView(withId(R.id.orderCommandInput)).check(matches(withHint(R.string.order_input_hint)))
        onView(withId(R.id.orderVoiceInputButton)).check(
            matches(withContentDescription(R.string.order_voice_start))
        )
        onView(withId(R.id.orderSubmitButton)).check(matches(withText(R.string.order_submit)))
        onView(withId(R.id.orderSubmitButton)).check(matches(isEnabled()))
        onView(withId(R.id.providerBlinkit)).perform(scrollTo()).check(
            matches(withText(R.string.works_with_blinkit))
        )
        onView(withText(R.string.blinkit_beta_note)).perform(scrollTo()).check(matches(isDisplayed()))
    }

    @Test
    fun voiceCanStartAndStopWithoutReplacingTypedText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(SpeechRecognizer.isRecognitionAvailable(context))
        assumeTrue(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
        activityRule.runOnUiThread {
            activityRule.activity.findViewById<android.widget.EditText>(R.id.orderCommandInput).setText("milk")
        }

        clickVoiceInput()
        onView(withId(R.id.orderVoiceInputButton)).check(
            matches(withContentDescription(R.string.order_voice_stop))
        )
        clickVoiceInput()

        onView(withId(R.id.orderVoiceInputButton)).check(
            matches(withContentDescription(R.string.order_voice_start))
        )
        onView(withId(R.id.orderCommandInput)).check(matches(withText("milk")))
    }

    @Test
    fun explicitChoiceSurvivesActivityRecreationWithinTheProcessSession() {
        clickProvider(R.id.providerBlinkit)
        onView(withId(R.id.providerBlinkit)).check(matches(isChecked()))
        activityRule.activity.runOnUiThread { activityRule.activity.recreate() }
        onView(withId(R.id.providerBlinkit)).check(matches(isChecked()))
        assertEquals(
            CommerceProviderRouter.CommerceProvider.BLINKIT,
            CommerceProviderRouter.currentSessionProvider()
        )
    }

    private fun assertSwiggyMcpUi() {
        onView(withId(R.id.providerChoiceGroup)).perform(scrollTo()).check(matches(isDisplayed()))
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

    private fun clickVoiceInput() {
        activityRule.runOnUiThread {
            activityRule.activity.findViewById<android.view.View>(R.id.orderVoiceInputButton).performClick()
        }
    }

    private fun clickProvider(providerId: Int) {
        activityRule.runOnUiThread {
            activityRule.activity.findViewById<android.view.View>(providerId).performClick()
        }
    }
}
