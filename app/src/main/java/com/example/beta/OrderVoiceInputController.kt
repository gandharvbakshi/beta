package com.example.beta

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

internal class OrderVoiceInputController(
    context: Context,
    private val onStateChanged: (State) -> Unit,
    private val onPartialResult: (String) -> Unit,
    private val onFinalResult: (String) -> Unit,
    private val onRecognitionError: (Error) -> Unit,
) : RecognitionListener {
    enum class State { IDLE, LISTENING, PROCESSING }

    enum class Error { UNAVAILABLE, NO_MATCH, BUSY, NETWORK, PERMISSION, OTHER }

    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private var state = State.IDLE

    fun toggle() {
        if (state == State.IDLE) start() else cancel()
    }

    fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            onRecognitionError(Error.UNAVAILABLE)
            return
        }
        if (recognizer == null) {
            recognizer = runCatching {
                SpeechRecognizer.createSpeechRecognizer(appContext).also {
                    it.setRecognitionListener(this)
                }
            }.getOrElse {
                onRecognitionError(Error.UNAVAILABLE)
                return
            }
        }
        updateState(State.LISTENING)
        runCatching {
            recognizer?.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-IN")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                },
            )
        }.onFailure {
            updateState(State.IDLE)
            onRecognitionError(Error.OTHER)
        }
    }

    fun cancel() {
        if (state != State.IDLE) {
            releaseRecognizer(cancelFirst = true)
        }
        updateState(State.IDLE)
    }

    fun destroy() {
        releaseRecognizer(cancelFirst = false)
        updateState(State.IDLE)
    }

    override fun onReadyForSpeech(params: Bundle?) = updateState(State.LISTENING)
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = updateState(State.PROCESSING)

    override fun onError(error: Int) {
        updateState(State.IDLE)
        releaseRecognizer(cancelFirst = false)
        onRecognitionError(
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Error.NO_MATCH
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> Error.BUSY
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> Error.NETWORK
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> Error.PERMISSION
                else -> Error.OTHER
            },
        )
    }

    override fun onResults(results: Bundle?) {
        updateState(State.IDLE)
        val result = results.bestResult()
        if (result.isBlank()) onRecognitionError(Error.NO_MATCH) else onFinalResult(result)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        partialResults.bestResult().takeIf(String::isNotBlank)?.let(onPartialResult)
    }

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    private fun updateState(next: State) {
        if (state == next) return
        state = next
        onStateChanged(next)
    }

    private fun releaseRecognizer(cancelFirst: Boolean) {
        val activeRecognizer = recognizer ?: return
        recognizer = null
        activeRecognizer.setRecognitionListener(null)
        if (cancelFirst) activeRecognizer.cancel()
        activeRecognizer.destroy()
    }

    private fun Bundle?.bestResult(): String {
        return this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
    }
}
