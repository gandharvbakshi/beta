package com.example.beta

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.beta.SwiggyMcpClient.SwiggyAddress
import org.hamcrest.CoreMatchers.containsString
import org.hamcrest.CoreMatchers.not
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Exercises the real coordinator address presentation without calling MCP.
 * No address is selected and no provider/cart request is started.
 */
@RunWith(AndroidJUnit4::class)
class SwiggyAddressSuggestionFlowTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun suggestedAddressCanBeReviewedBeforeFullSavedAddressList() {
        val first = address(
            id = "home-one",
            confirmationDetail = "Unit 12 in Maple Court, Orchard Lane 560047",
            label = "Unit 12, Maple Court, Orchard Lane, Bengaluru, 560047, India",
        )
        val second = address(
            id = "home-two",
            confirmationDetail = "Flat 204, Block B in Maple Court, Orchard Lane 560041",
            label = "Flat 204, Block B, Maple Court, Orchard Lane, 7th Block, Bengaluru, 560041, India",
        )
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var coordinator: SwiggyVoiceOrderCoordinator? = null

        try {
            scenario.onActivity { activity ->
                val owner = SwiggyVoiceOrderCoordinator(
                    activity = activity,
                    announce = {},
                    onReconnectRequired = {},
                )
                coordinator = owner
                field("running").setBoolean(owner, true)
                field("operationGeneration").setLong(owner, 1L)
                chooseAddress(owner).invoke(owner, 1L, listOf(first, second), emptyList<Any>(), 0, true, false)
            }
            waitForIdle()

            onView(withId(R.id.swiggyStepPrimary)).inRoot(isDialog())
                .check(matches(withText("Deliver here")))
            onView(withId(R.id.swiggyStepSecondary)).inRoot(isDialog())
                .check(matches(withText("Change address")))
            saveSyntheticScreenshot("swiggy-address-suggestion-initial.png")

            // Only reveal the list. Do not select an address or start a network flow.
            onView(withId(R.id.swiggyStepSecondary)).inRoot(isDialog()).perform(scrollTo(), click())
            waitForIdle()

            onView(withText(containsString("Unit 12 in Maple Court"))).inRoot(isDialog())
                .perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText(containsString("Flat 204, Block B in Maple Court"))).inRoot(isDialog())
                .perform(scrollTo()).check(matches(isDisplayed()))
            onView(withId(R.id.swiggyStepPrimary)).inRoot(isDialog())
                .check(matches(not(isDisplayed())))

            saveSyntheticScreenshot("swiggy-address-suggestion-list.png")
        } finally {
            scenario.onActivity {
                coordinator?.let { owner ->
                    val dialog = field("stepDialog").get(owner) as SwiggyOrderStepDialog
                    dialog.dismiss()
                }
            }
            scenario.close()
        }
    }

    private fun address(id: String, confirmationDetail: String, label: String) = SwiggyAddress(
        id = id,
        label = label,
        normalizedLabel = label,
        shortLabel = "Home — Bengaluru",
        categoryLabel = "Home",
        confirmationDetail = confirmationDetail,
    )

    private fun chooseAddress(owner: SwiggyVoiceOrderCoordinator) =
        owner.javaClass.getDeclaredMethod(
            "chooseAddress",
            Long::class.javaPrimitiveType,
            List::class.java,
            List::class.java,
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }

    private fun field(name: String) = SwiggyVoiceOrderCoordinator::class.java
        .getDeclaredField(name).apply { isAccessible = true }

    private fun waitForIdle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun saveSyntheticScreenshot(name: String) {
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        assertNotNull("Synthetic address flow screenshot should be available", screenshot)
        val file = File(context.cacheDir, name)
        val written = file.outputStream().use { output ->
            screenshot!!.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
        }
        assertTrue("Synthetic screenshot should save in app cache", written)
        assertTrue(file.exists() && file.length() > 0L)
    }
}
