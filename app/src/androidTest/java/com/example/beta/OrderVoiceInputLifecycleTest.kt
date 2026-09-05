package com.example.beta

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class OrderVoiceInputLifecycleTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val draftFile get() = File(context.noBackupFilesDir, "swiggy-draft-v1.bin")
    private var originalDraftBytes: ByteArray? = null

    @Before
    fun preserveDraftText() {
        originalDraftBytes = draftFile.takeIf { it.exists() }?.readBytes()
        assertTrue(SwiggyDraftStore(context).clear())
    }

    @After
    fun restoreDraftText() {
        originalDraftBytes?.let { draftFile.writeBytes(it) } ?: draftFile.delete()
    }

    @Test
    fun cancelAndStaleCallbacksAreIgnoredAcrossSessions() {
        val partialCalls = AtomicInteger(0)
        val finalCalls = AtomicInteger(0)
        val errorCalls = AtomicInteger(0)
        val harness = newControllerHarness(
            onPartial = { partialCalls.incrementAndGet() },
            onFinal = { finalCalls.incrementAndGet() },
            onError = { errorCalls.incrementAndGet() },
        )

        runOnMain {
            harness.controller.start()
            assertTrue(harness.controller.isActive)
        }

        val firstSession = harness.sessions.single()

        runOnMain {
            harness.controller.cancel()
            firstSession.emitPartial("stale partial")
            firstSession.emitFinal("stale final")
            firstSession.emitError(SpeechRecognizer.ERROR_NETWORK)
        }
        instrumentation.waitForIdleSync()

        assertEquals(0, partialCalls.get())
        assertEquals(0, finalCalls.get())
        assertEquals(0, errorCalls.get())

        runOnMain {
            harness.controller.start()
            assertTrue(harness.controller.isActive)
        }
        val secondSession = harness.sessions[1]

        runOnMain {
            firstSession.emitPartial("still stale")
            firstSession.emitFinal("still stale")
            firstSession.emitError(SpeechRecognizer.ERROR_NO_MATCH)
            secondSession.emitPartial("fresh partial")
            secondSession.emitFinal("fresh final")
        }
        instrumentation.waitForIdleSync()

        assertEquals(1, partialCalls.get())
        assertEquals(1, finalCalls.get())
        assertEquals(0, errorCalls.get())
    }

    @Test
    fun duplicateResultsAndErrorsAreConsumedOnce() {
        val finalCalls = AtomicInteger(0)
        val errorCalls = AtomicInteger(0)
        val harness = newControllerHarness(
            onFinal = { finalCalls.incrementAndGet() },
            onError = { errorCalls.incrementAndGet() },
        )

        runOnMain { harness.controller.start() }
        val firstSession = harness.sessions.single()

        runOnMain {
            firstSession.emitFinal("milk")
            firstSession.emitFinal("bread")
            firstSession.emitError(SpeechRecognizer.ERROR_NETWORK)
        }
        instrumentation.waitForIdleSync()

        assertEquals(1, finalCalls.get())
        assertEquals(0, errorCalls.get())

        runOnMain { harness.controller.start() }
        val secondSession = harness.sessions[1]

        runOnMain {
            secondSession.emitError(SpeechRecognizer.ERROR_NO_MATCH)
            secondSession.emitError(SpeechRecognizer.ERROR_SERVER)
        }
        instrumentation.waitForIdleSync()

        assertEquals(1, errorCalls.get())
    }

    @Test
    fun startFailureAndRecognitionErrorsRecoverWithFreshSessions() {
        val errorCalls = AtomicInteger(0)
        val harness = newControllerHarness(
            configureSession = { index, session ->
                if (index == 0) {
                    session.startException = IllegalStateException("boom")
                }
            },
            onError = { errorCalls.incrementAndGet() },
        )

        runOnMain { harness.controller.start() }
        assertEquals(1, harness.sessions.size)
        assertEquals(1, errorCalls.get())
        assertEquals(1, harness.sessions[0].cancelCalls)
        assertEquals(1, harness.sessions[0].destroyCalls)

        runOnMain { harness.controller.start() }
        assertEquals(2, harness.sessions.size)

        val secondSession = harness.sessions[1]
        runOnMain {
            secondSession.emitError(SpeechRecognizer.ERROR_NETWORK)
        }
        instrumentation.waitForIdleSync()

        assertEquals(2, harness.sessions.size)
        assertEquals(2, errorCalls.get())
        assertFalse(harness.controller.isActive)

        runOnMain { harness.controller.start() }
        val thirdSession = harness.sessions[2]
        runOnMain {
            thirdSession.emitError(SpeechRecognizer.ERROR_NO_MATCH)
        }
        instrumentation.waitForIdleSync()

        assertEquals(3, harness.sessions.size)
        assertEquals(3, errorCalls.get())
    }

    @Test
    fun destroyPreventsRestart() {
        val harness = newControllerHarness()

        runOnMain {
            harness.controller.start()
            harness.controller.destroy()
            harness.controller.start()
        }

        assertEquals(1, harness.sessions.size)
        assertEquals(1, harness.sessions[0].destroyCalls)
        assertEquals(1, harness.sessions[0].cancelCalls)
        assertFalse(harness.controller.isActive)
    }

    @Test
    fun unavailableDoesNotCreateRecognizer() {
        val unavailableCalls = AtomicInteger(0)
        val harness = newControllerHarness(
            recognitionAvailable = { false },
            onError = { error ->
                if (error == OrderVoiceInputController.Error.UNAVAILABLE) unavailableCalls.incrementAndGet()
            },
        )

        runOnMain { harness.controller.start() }

        assertEquals(0, harness.sessions.size)
        assertEquals(1, unavailableCalls.get())
        assertFalse(harness.controller.isActive)
    }

    @Test
    fun privateVoiceErrorHandlerUpdatesStatusWithoutEditingComposer() {
        val harness = launchActivityHarness()
        val composerText = "milk, bread, bananas"

        try {
            harness.scenario.onActivity { activity ->
                activity.findViewById<EditText>(R.id.orderCommandInput).setText(composerText)
            }

            val cases = listOf(
                OrderVoiceInputController.Error.NETWORK to R.string.voice_network_error,
                OrderVoiceInputController.Error.PERMISSION to R.string.voice_microphone_required,
                OrderVoiceInputController.Error.NO_MATCH to R.string.voice_no_order_heard,
            )
            cases.forEach { (error, expectedStatus) ->
                harness.scenario.onActivity { activity ->
                    invokePrivateMethod(activity, "handleVoiceInputError", error)
                    assertEquals(
                        activity.getString(expectedStatus),
                        activity.findViewById<TextView>(R.id.orderInputStatus).text.toString(),
                    )
                    assertEquals(composerText, activity.findViewById<EditText>(R.id.orderCommandInput).text.toString())
                    assertTrue(activity.findViewById<EditText>(R.id.orderCommandInput).isEnabled)
                }
            }
        } finally {
            harness.scenario.close()
        }
    }

    @Test
    fun editingComposerCancelsInjectedVoiceSession() {
        val harness = launchActivityHarness()

        try {
            runOnMain { harness.controller.start() }
            val session = harness.sessions.single()

            harness.scenario.onActivity { activity ->
                activity.findViewById<EditText>(R.id.orderCommandInput).setText("fresh groceries")
            }
            instrumentation.waitForIdleSync()

            assertEquals(1, session.cancelCalls)
            assertFalse(harness.controller.isActive)
        } finally {
            harness.scenario.close()
        }
    }

    @Test
    fun reflectedPartialUpdateDoesNotCancelInjectedVoiceSession() {
        val harness = launchActivityHarness()

        try {
            runOnMain { harness.controller.start() }
            val session = harness.sessions.single()

            harness.scenario.onActivity { activity ->
                invokePrivateMethod(activity, "setRecognizedSpeechText", "milk and bread")
            }
            instrumentation.waitForIdleSync()

            assertEquals(0, session.cancelCalls)
            assertTrue(harness.controller.isActive)
            harness.scenario.onActivity { activity ->
                assertEquals("milk and bread", activity.findViewById<EditText>(R.id.orderCommandInput).text.toString())
            }
        } finally {
            harness.scenario.close()
        }
    }

    @Test
    fun moveToCreatedCancelsInjectedVoiceSession() {
        val harness = launchActivityHarness()

        try {
            runOnMain { harness.controller.start() }
            val session = harness.sessions.single()

            harness.scenario.moveToState(Lifecycle.State.CREATED)
            instrumentation.waitForIdleSync()

            assertEquals(1, session.cancelCalls)
            assertFalse(harness.controller.isActive)
        } finally {
            harness.scenario.close()
        }
    }

    @Test
    fun staleFinalAfterPauseIsIgnored() {
        val finalCalls = AtomicInteger(0)
        val harness = launchActivityHarness(
            finalHandler = { finalCalls.incrementAndGet() },
        )

        try {
            runOnMain { harness.controller.start() }
            val session = harness.sessions.single()

            harness.scenario.moveToState(Lifecycle.State.CREATED)
            instrumentation.waitForIdleSync()

            runOnMain { session.emitFinal("stale final") }
            instrumentation.waitForIdleSync()

            assertEquals(0, finalCalls.get())
            assertEquals(1, session.cancelCalls)
            assertFalse(harness.controller.isActive)
        } finally {
            harness.scenario.close()
        }
    }

    private fun newControllerHarness(
        recognitionAvailable: () -> Boolean = { true },
        configureSession: (index: Int, session: FakeSpeechSession) -> Unit = { _, _ -> },
        onPartial: (String) -> Unit = {},
        onFinal: (String) -> Unit = {},
        onError: (OrderVoiceInputController.Error) -> Unit = {},
    ): ControllerHarness {
        val sessions = CapturingSessionFactory(configureSession)
        val controller = OrderVoiceInputController(
            context = context,
            onStateChanged = { },
            onPartialResult = onPartial,
            onFinalResult = onFinal,
            onRecognitionError = onError,
            recognitionAvailable = recognitionAvailable,
            sessionFactory = sessions,
        )
        return ControllerHarness(controller, sessions.sessions)
    }

    private fun launchActivityHarness(
        recognitionAvailable: () -> Boolean = { true },
        configureSession: (index: Int, session: FakeSpeechSession) -> Unit = { _, _ -> },
        finalHandler: ((String) -> Unit)? = null,
        errorHandler: ((OrderVoiceInputController.Error) -> Unit)? = null,
    ): ActivityHarness {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var controller: OrderVoiceInputController
        val sessions = CapturingSessionFactory(configureSession)
        val finalSink: (String) -> Unit = finalHandler ?: { _: String -> }
        val errorSink: (OrderVoiceInputController.Error) -> Unit = errorHandler ?: { _: OrderVoiceInputController.Error -> }

        scenario.onActivity { launched ->
            val originalField = MainActivity::class.java.getDeclaredField("voiceInputController").apply { isAccessible = true }
            val original = originalField.get(launched) as OrderVoiceInputController
            original.destroy()
            controller = OrderVoiceInputController(
                context = launched,
                onStateChanged = { },
                onPartialResult = { text -> invokePrivateMethod(launched, "setRecognizedSpeechText", text) },
                onFinalResult = finalSink,
                onRecognitionError = errorSink,
                recognitionAvailable = recognitionAvailable,
                sessionFactory = sessions,
            )
            originalField.set(launched, controller)
        }
        instrumentation.waitForIdleSync()
        return ActivityHarness(scenario, controller, sessions.sessions)
    }

    private fun runOnMain(block: () -> Unit) {
        instrumentation.runOnMainSync(block)
    }

    private fun invokePrivateMethod(target: Any, methodName: String, vararg args: Any?) {
        val method = target.javaClass.declaredMethods.firstOrNull {
            it.name == methodName && it.parameterTypes.size == args.size
        } ?: error("No method named $methodName with ${args.size} args on ${target.javaClass.name}")
        method.isAccessible = true
        method.invoke(target, *args)
    }

    private data class ControllerHarness(
        val controller: OrderVoiceInputController,
        val sessions: MutableList<FakeSpeechSession>,
    )

    private data class ActivityHarness(
        val scenario: ActivityScenario<MainActivity>,
        val controller: OrderVoiceInputController,
        val sessions: MutableList<FakeSpeechSession>,
    )

    private class CapturingSessionFactory(
        private val configure: (index: Int, session: FakeSpeechSession) -> Unit,
    ) : (RecognitionListener) -> OrderSpeechSession {
        val sessions = mutableListOf<FakeSpeechSession>()

        override fun invoke(listener: RecognitionListener): OrderSpeechSession {
            val session = FakeSpeechSession(listener).also { configure(sessions.size, it) }
            sessions += session
            return session
        }
    }

    private class FakeSpeechSession(
        private val listener: RecognitionListener,
    ) : OrderSpeechSession {
        var startException: RuntimeException? = null
        var startCalls = 0
        var cancelCalls = 0
        var destroyCalls = 0

        override fun start(intent: Intent) {
            startCalls++
            startException?.let { throw it }
        }

        override fun cancel() {
            cancelCalls++
        }

        override fun destroy() {
            destroyCalls++
        }

        fun emitPartial(text: String) {
            listener.onPartialResults(resultBundle(text))
        }

        fun emitFinal(text: String) {
            listener.onResults(resultBundle(text))
        }

        fun emitError(error: Int) {
            listener.onError(error)
        }

        private fun resultBundle(text: String): Bundle = Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
        }
    }
}
