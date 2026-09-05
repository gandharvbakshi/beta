package com.example.beta

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SwiggyDraftLifecycleTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val realFile get() = File(context.noBackupFilesDir, "swiggy-draft-v1.bin")
    private var original: ByteArray? = null

    @Before fun preserveOriginalDraft() {
        check(!realFile.exists() || realFile.isFile && realFile.length() <= 65536)
        original = realFile.takeIf { it.exists() }?.readBytes()
        assertTrue(SwiggyDraftStore(context).clear())
    }

    @After fun restoreOriginalDraft() {
        // ActivityScenario is closed in each test before restoring this file.
        original?.let { realFile.writeBytes(it) } ?: check(realFile.delete() || !realFile.exists())
    }

    @Test
    fun recreationRestoresOnlyComposerText() {
        assertTrue(SwiggyDraftStore(context).save("milk, keenwaa, bread"))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals("milk, keenwaa, bread", textOf(activity))
                assertFalse(SwiggyCartMutationGuard.isInFlight())
                val coordinatorField = MainActivity::class.java.getDeclaredField("swiggyOrderCoordinator").apply { isAccessible = true }
                val coordinator = coordinatorField.get(activity)
                val runningField = SwiggyVoiceOrderCoordinator::class.java.getDeclaredField("running").apply { isAccessible = true }
                assertFalse(runningField.getBoolean(coordinator))
                assertFalse(activity.findViewById<android.widget.EditText>(R.id.orderCommandInput).isSaveEnabled)
            }
        }
    }

    @Test
    fun debouncedDraftSaves_thenPrepareClears_thenRecreateStaysEmpty() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                activity.findViewById<android.widget.EditText>(R.id.orderCommandInput).setText("milk bread")
            }
            waitForDraftDebounce()

            scenario.onActivity { activity ->
                val store = SwiggyDraftStore(activity.applicationContext)
                assertEquals("milk bread", store.load())
                // Queue a new write and clear on the SAME main-loop turn, so
                // this actually exercises cancellation of a pending debounce.
                activity.findViewById<android.widget.EditText>(R.id.orderCommandInput).setText("tea biscuits")
                val prepared = invokePrepareDraftForCartApply(activity)
                assertTrue(prepared)
                assertEquals("", textOf(activity))
                assertNull(store.load())
            }
            waitForDraftDebounce()
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals("", textOf(activity))
                assertNull(SwiggyDraftStore(activity.applicationContext).load())
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun clearFailurePreservesTextAndBlocksPrepare() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val fileName = "swiggy-draft-failure-sentinel.bin"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sentinel = File(context.noBackupFilesDir, fileName)
        try {
            sentinel.deleteRecursively()
            sentinel.mkdirs()
            File(sentinel, "sentinel.txt").writeText("do not replace")
            scenario.onActivity { activity ->
                replaceDraftStore(activity, SwiggyDraftStore(activity.applicationContext, fileName))
                activity.findViewById<android.widget.EditText>(R.id.orderCommandInput).setText("tea biscuits")
            }
            waitForDraftDebounce()

            scenario.onActivity { activity ->
                val prepared = invokePrepareDraftForCartApply(activity)
                assertFalse(prepared)
                assertEquals("tea biscuits", textOf(activity))
            }
        } finally {
            scenario.close()
            sentinel.deleteRecursively()
            File(context.noBackupFilesDir, "$fileName.new").delete()
        }
    }

    private fun waitForDraftDebounce() {
        Thread.sleep(500L)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun textOf(activity: MainActivity): String {
        return activity.findViewById<android.widget.EditText>(R.id.orderCommandInput).text?.toString().orEmpty()
    }

    private fun invokePrepareDraftForCartApply(activity: MainActivity): Boolean {
        val method = MainActivity::class.java.getDeclaredMethod("prepareDraftForCartApply")
        method.isAccessible = true
        return method.invoke(activity) as Boolean
    }

    private fun replaceDraftStore(activity: MainActivity, store: SwiggyDraftStore) {
        setPrivateField(activity, "draftStore", store)
        setPrivateField(activity, "draftPersistenceBlocked", false)
        setPrivateField(activity, "draftRestored", false)
    }

    private fun setPrivateField(target: Any, fieldName: String, value: Any?) {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(target, value)
    }
}
