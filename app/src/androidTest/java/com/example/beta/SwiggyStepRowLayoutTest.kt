package com.example.beta

import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.ViewAssertion
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SwiggyStepRowLayoutTest {
    @Test
    fun longBadgeKeepsAddressTitleWideAndAllCopyVisible() {
        val title = "Home — Bengaluru"
        val detail = "Flat 204, Block B, Maple Court, Orchard Lane, Bengaluru"
        val badge = "Suggested · Same area · recently used · verified for delivery"
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var dialog: SwiggyOrderStepDialog

        try {
            scenario.onActivity { activity ->
                dialog = SwiggyOrderStepDialog(activity)
                dialog.show(
                    SwiggyStepScreen(
                        eyebrow = "Address",
                        title = "Choose delivery address",
                        message = "Synthetic address layout regression",
                        caption = "Test-only content",
                        rows = listOf(SwiggyStepRow(title, detail, badge)),
                        safetyNote = "Synthetic test data",
                    ),
                )
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            onView(withId(R.id.swiggyStepItems)).inRoot(isDialog()).check(ViewAssertion { view, _ ->
                val items = view as LinearLayout
                val card = items.getChildAt(0) as LinearLayout
                val heading = card.getChildAt(0) as LinearLayout
                val titleView = heading.getChildAt(0) as TextView
                val badgeView = heading.getChildAt(1) as TextView
                val detailView = card.getChildAt(1) as TextView

                assertTrue("title should occupy the full card width", titleView.width >= card.width - card.paddingLeft - card.paddingRight)
                assertTrue("title should have measurable height", titleView.height > 0)
                assertEquals(title, titleView.text.toString())
                assertEquals(badge, badgeView.text.toString())
                assertEquals(detail, detailView.text.toString())
                assertTrue("title should be visible", titleView.isShown)
                assertTrue("badge should be visible", badgeView.isShown)
                assertTrue("address detail should be visible", detailView.isShown)
                assertTrue("title should span the heading width", titleView.width >= heading.width)
                assertTrue("badge should span the heading width", badgeView.width >= heading.width)
                assertTrue("badge should sit below the title", titleView.bottom <= badgeView.top)
                assertTrue("badge should not be ellipsized", (0 until badgeView.lineCount).all { badgeView.layout?.getEllipsisCount(it) == 0 })
                assertTrue("detail should not be ellipsized", (0 until detailView.lineCount).all { detailView.layout?.getEllipsisCount(it) == 0 })
            })
            onView(withText(badge)).inRoot(isDialog())
                .perform(scrollTo())
                .check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
        } finally {
            scenario.onActivity { dialog.dismiss() }
            scenario.close()
        }
    }
}
