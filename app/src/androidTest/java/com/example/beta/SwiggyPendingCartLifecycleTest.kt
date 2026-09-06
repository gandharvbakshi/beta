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
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.CoreMatchers.containsString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File

@RunWith(AndroidJUnit4::class)
class SwiggyPendingCartLifecycleTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val pendingFile get() = File(context.noBackupFilesDir, "swiggy-cart-pending-v1")
    private val backupFile get() = File(pendingFile.path + ".bak")
    private val draftFile get() = File(context.noBackupFilesDir, "swiggy-draft-v1.bin")
    private var originalPrimary: ByteArray? = null
    private var originalBackup: ByteArray? = null
    private var originalDraft: ByteArray? = null
    private var hadPrimary = false
    private var hadBackup = false
    private var hadDraft = false

    @Before
    fun snapshotBaseline() {
        hadPrimary = pendingFile.exists()
        hadBackup = backupFile.exists()
        hadDraft = draftFile.exists()
        originalPrimary = pendingFile.takeIf { it.exists() }?.readBytes()
        originalBackup = backupFile.takeIf { it.exists() }?.readBytes()
        originalDraft = draftFile.takeIf { it.exists() }?.readBytes()
        assertFalse("Do not run while a real Swiggy cart mutation is active", SwiggyCartMutationGuard.isInFlight())
        SwiggyPendingCartStore(context).clear()
        SwiggyDraftStore(context).clear()
    }

    @After
    fun restoreBaseline() {
        SwiggyCartMutationGuard.end()
        restoreFile(pendingFile, hadPrimary, originalPrimary)
        restoreFile(backupFile, hadBackup, originalBackup)
        restoreFile(draftFile, hadDraft, originalDraft)
    }

    @Test
    fun markPendingCreatesOnlyTheOneByteSentinelAndClearRemovesIt() {
        val store = SwiggyPendingCartStore(context)

        assertTrue(store.markPending())
        assertTrue(store.isPending())
        assertTrue(pendingFile.exists())
        assertEquals(1L, pendingFile.length())
        assertArrayEquals(byteArrayOf(1), pendingFile.readBytes())
        assertFalse(backupFile.exists())

        assertTrue(store.clear())
        assertFalse(store.isPending())
        assertFalse(pendingFile.exists())
        assertFalse(backupFile.exists())
    }

    @Test
    fun corruptedPendingFileStillFailsClosedUntilCleared() {
        pendingFile.writeBytes(byteArrayOf(9, 8, 7, 6))

        val store = SwiggyPendingCartStore(context)

        assertTrue(store.isPending())
        assertTrue(store.clear())
        assertFalse(store.isPending())
        assertFalse(pendingFile.exists())
        assertFalse(backupFile.exists())
    }

    @Test
    fun pendingCartWarningSurvivesRecreationAndExplicitReviewClearsWithoutReplay() {
        val store = SwiggyPendingCartStore(context)
        assertTrue(store.markPending())

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            verifyReviewNeededLastCartDialog()
            scenario.onActivity { activity ->
                assertFalse(SwiggyCartMutationGuard.isInFlight())
                assertFalse(coordinatorRunning(activity))
                assertTrue(store.isPending())
            }

            scenario.recreate()

            verifyReviewNeededLastCartDialog()
            scenario.onActivity { activity ->
                assertFalse(SwiggyCartMutationGuard.isInFlight())
                assertFalse(coordinatorRunning(activity))
                assertTrue(store.isPending())
            }

            onView(withId(R.id.swiggyStepSecondary)).inRoot(isDialog())
                .perform(scrollTo(), click())
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            scenario.onActivity { activity ->
                assertFalse(store.isPending())
                assertFalse(SwiggyCartMutationGuard.isInFlight())
                assertFalse(coordinatorRunning(activity))
            }
        }
    }

    private fun restoreFile(file: File, existed: Boolean, bytes: ByteArray?) {
        if (existed) {
            file.writeBytes(bytes ?: byteArrayOf())
        } else {
            file.delete()
        }
    }

    private fun verifyReviewNeededLastCartDialog() {
        onView(withId(R.id.swiggyStepEyebrow)).inRoot(isDialog())
            .check(matches(withText("Review needed")))
        onView(withId(R.id.swiggyStepTitle)).inRoot(isDialog())
            .check(matches(withText("Check your last cart update")))
        onView(withId(R.id.swiggyStepMessage)).inRoot(isDialog())
            .check(matches(withText(containsString("last cart update"))))
        onView(withId(R.id.swiggyStepPrimary)).inRoot(isDialog()).perform(scrollTo())
            .check(matches(withText("Open Swiggy to review"))).check(matches(isDisplayed()))
        onView(withId(R.id.swiggyStepSecondary)).inRoot(isDialog()).perform(scrollTo())
            .check(matches(withText("I have reviewed the cart"))).check(matches(isDisplayed()))
    }

    private fun coordinatorRunning(activity: MainActivity): Boolean {
        val coordinatorField = MainActivity::class.java.getDeclaredField("swiggyOrderCoordinator")
            .apply { isAccessible = true }
        val coordinator = coordinatorField.get(activity)
        val runningField = SwiggyVoiceOrderCoordinator::class.java.getDeclaredField("running")
            .apply { isAccessible = true }
        return runningField.getBoolean(coordinator)
    }
}
