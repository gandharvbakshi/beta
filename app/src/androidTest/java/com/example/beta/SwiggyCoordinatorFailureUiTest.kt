package com.example.beta

import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.beta.SwiggyMcpClient.SwiggyMcpResult
import org.hamcrest.CoreMatchers.containsString
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Injects failures into the real renderer; never sends a cart, reconnect or microphone request. */
@RunWith(AndroidJUnit4::class)
class SwiggyCoordinatorFailureUiTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val draftFile get() = File(context.noBackupFilesDir, "swiggy-draft-v1.bin")
    private var originalDraft: ByteArray? = null
    private var draftCaptured = false

    @Before fun preserveDraft() {
        originalDraft = draftFile.takeIf { it.exists() }?.readBytes()
        draftCaptured = true
        assertFalse("Do not run while a real mutation is active", SwiggyCartMutationGuard.isInFlight())
    }

    @After fun restoreDraft() {
        if (!draftCaptured) return
        originalDraft?.let { draftFile.writeBytes(it) } ?: draftFile.delete()
    }

    @Test fun rateLimitShowsWaitAndPreservesComposer() = checkFailure(
        SwiggyMcpResult.Failure(
            userMessage = SwiggyMcpClient.swiggyUserMessageForHttpCode(
                "/swiggy/recommendations/batch", 429,
                """{"detail":{"reason":"swiggy_rate_limited","retryAfterSeconds":70}}""",
            ),
            httpCode = 429,
        ),
        expectedMessagePart = "2 minutes",
    )

    @Test fun expiredConnectionNotifiesReconnectOnce() = checkFailure(
        SwiggyMcpResult.Failure("Swiggy connection needs to be reconnected.", 401, reconnectRequired = true),
        expectedMessagePart = "reconnected",
    )

    @Test fun uncertainApplyRequiresCartReviewAndDoesNotRetry() = checkFailure(
        SwiggyMcpResult.Failure(SwiggyMcpClient.swiggyNetworkFailureMessage("/swiggy/cart/apply")),
        expectedMessagePart = "review the cart",
        afterMutation = true,
    )

    private fun checkFailure(failure: SwiggyMcpResult.Failure, expectedMessagePart: String, afterMutation: Boolean = false) {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var coordinator: SwiggyVoiceOrderCoordinator? = null
        var terminalCalls = 0
        var reconnectCalls = 0
        val announcements = mutableListOf<String>()
        try {
            scenario.onActivity { activity ->
                activity.findViewById<EditText>(R.id.orderCommandInput).setText("Synthetic grocery list")
                val owner = SwiggyVoiceOrderCoordinator(
                    activity = activity, announce = { announcements += it },
                    onReconnectRequired = { reconnectCalls++ }, onTerminal = { terminalCalls++ },
                )
                coordinator = owner
                field("running").setBoolean(owner, true)
                // Simulates the callback state only; no apply or global begin() is invoked.
                field("mutationInFlight").setBoolean(owner, afterMutation)
                val fail = owner.javaClass.getDeclaredMethod("fail", Long::class.javaPrimitiveType, SwiggyMcpResult.Failure::class.java)
                    .apply { isAccessible = true }
                fail.invoke(owner, 0L, failure)
                fail.invoke(owner, 0L, failure)
                assertEquals(1, terminalCalls)
                assertEquals(if (failure.reconnectRequired) 1 else 0, reconnectCalls)
                assertEquals(listOf(failure.userMessage), announcements)
                assertFalse(field("running").getBoolean(owner))
                assertFalse(field("mutationInFlight").getBoolean(owner))
                val composer = activity.findViewById<EditText>(R.id.orderCommandInput)
                assertEquals("Synthetic grocery list", composer.text.toString())
                assertTrue(composer.isEnabled)
            }
            onView(withId(R.id.swiggyStepTitle)).inRoot(isDialog())
                .check(matches(withText(if (afterMutation) "Please check your Swiggy cart" else "Beta stopped safely")))
            onView(withId(R.id.swiggyStepMessage)).inRoot(isDialog())
                .check(matches(withText(containsString(expectedMessagePart))))
            if (afterMutation) {
                onView(withId(R.id.swiggyStepSafetyNote)).inRoot(isDialog())
                    .check(matches(withText(containsString("will not retry"))))
                onView(withId(R.id.swiggyStepSecondary)).inRoot(isDialog()).perform(scrollTo())
                    .check(matches(withText("Close"))).check(matches(isDisplayed()))
            } else {
                onView(withId(R.id.swiggyStepPrimary)).inRoot(isDialog()).perform(scrollTo())
                    .check(matches(withText("Done"))).check(matches(isDisplayed()))
            }
        } finally {
            try {
                scenario.onActivity {
                    coordinator?.let { owner -> (field("stepDialog").get(owner) as SwiggyOrderStepDialog).dismiss() }
                }
            } finally {
                scenario.close()
            }
        }
    }

    private fun field(name: String) = SwiggyVoiceOrderCoordinator::class.java.getDeclaredField(name)
        .apply { isAccessible = true }
}
