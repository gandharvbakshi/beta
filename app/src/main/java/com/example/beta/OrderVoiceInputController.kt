package com.example.beta

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Per-session boundary; tests can deliver late callbacks without opening a microphone. */
internal interface OrderSpeechSession {
    fun start(intent: Intent)
    fun cancel()
    fun destroy()
}

private class AndroidOrderSpeechSession(context: Context, listener: RecognitionListener) : OrderSpeechSession {
    private val recognizer = SpeechRecognizer.createSpeechRecognizer(context).also {
        it.setRecognitionListener(listener)
    }
    override fun start(intent: Intent) = recognizer.startListening(intent)
    override fun cancel() = recognizer.cancel()
    override fun destroy() = recognizer.destroy()
}

internal class OrderVoiceInputController(
    context: Context,
    private val onStateChanged: (State) -> Unit,
    private val onPartialResult: (String) -> Unit,
    private val onFinalResult: (String) -> Unit,
    private val onRecognitionError: (Error) -> Unit,
    private val recognitionAvailable: () -> Boolean = { SpeechRecognizer.isRecognitionAvailable(context.applicationContext) },
    private val sessionFactory: (RecognitionListener) -> OrderSpeechSession = {
        AndroidOrderSpeechSession(context.applicationContext, it)
    },
) {
    enum class State { IDLE, LISTENING, PROCESSING }
    enum class Error { UNAVAILABLE, NO_MATCH, BUSY, NETWORK, PERMISSION, OTHER }

    private var session: OrderSpeechSession? = null
    private var state = State.IDLE
    private var epoch = 0L
    private var active = false
    private var destroyed = false

    val isActive: Boolean get() = active

    fun toggle() {
        if (active) cancel() else start()
    }

    fun start() {
        if (destroyed || active) return
        if (!runCatching(recognitionAvailable).getOrDefault(false)) {
            onRecognitionError(Error.UNAVAILABLE)
            return
        }
        val capturedEpoch = ++epoch
        active = true
        try {
            session = sessionFactory(listener(capturedEpoch))
            updateState(State.LISTENING)
            session?.start(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-IN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            })
        } catch (_: Exception) {
            if (isCurrent(capturedEpoch)) {
                finish(cancelFirst = true)
                onRecognitionError(Error.OTHER)
            }
        }
    }

    fun cancel() = finish(cancelFirst = true)

    fun destroy() {
        destroyed = true
        finish(cancelFirst = true)
    }

    private fun isCurrent(capturedEpoch: Long) = !destroyed && active && capturedEpoch == epoch

    private fun listener(capturedEpoch: Long) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (isCurrent(capturedEpoch)) updateState(State.LISTENING)
        }
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
        override fun onEndOfSpeech() {
            if (isCurrent(capturedEpoch)) updateState(State.PROCESSING)
        }
        override fun onError(error: Int) {
            if (!isCurrent(capturedEpoch)) return
            finish(cancelFirst = false)
            onRecognitionError(when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Error.NO_MATCH
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> Error.BUSY
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> Error.NETWORK
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> Error.PERMISSION
                else -> Error.OTHER
            })
        }
        override fun onResults(results: Bundle?) {
            if (!isCurrent(capturedEpoch)) return
            val result = results.bestResult()
            finish(cancelFirst = false)
            if (result.isBlank()) onRecognitionError(Error.NO_MATCH) else onFinalResult(result)
        }
        override fun onPartialResults(partialResults: Bundle?) {
            if (isCurrent(capturedEpoch)) {
                partialResults.bestResult().takeIf(String::isNotBlank)?.let(onPartialResult)
            }
        }
    }

    private fun finish(cancelFirst: Boolean) {
        // Invalidate first: cancel/destroy can still dispatch queued callbacks to the old listener.
        active = false
        ++epoch
        val oldSession = session
        session = null
        if (cancelFirst) runCatching { oldSession?.cancel() }
        runCatching { oldSession?.destroy() }
        updateState(State.IDLE)
    }

    private fun updateState(next: State) {
        if (state == next) return
        state = next
        onStateChanged(next)
    }

    private fun Bundle?.bestResult(): String = this
        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull()?.trim().orEmpty()
}
