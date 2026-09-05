package com.example.beta

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class SwiggySyntheticSpeechFixtureTest {
    @Test
    fun synthesizeFixtureSpeechToAppPrivateWavWithoutMicOrRecognizer() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString(FLAG_NAME) == "true")

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val appContext = context.applicationContext
        val outputFile = File(context.filesDir, OUTPUT_FILE)
        if (outputFile.exists()) {
            outputFile.delete()
        }

        val initLatch = CountDownLatch(1)
        val initError = AtomicReference<String?>()
        val engineRef = AtomicReference<TextToSpeech?>()
        val selectedVoiceName = AtomicReference<String?>()
        val initSuccess = AtomicReference(false)

        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val engine = TextToSpeech(appContext, { status ->
                    if (status != TextToSpeech.SUCCESS) {
                        initError.compareAndSet(null, "TextToSpeech init failed with status $status")
                        initLatch.countDown()
                        return@TextToSpeech
                    }

                    val activeEngine = engineRef.get()
                    if (activeEngine == null) {
                        initError.compareAndSet(null, "TextToSpeech engine was not ready after init")
                        initLatch.countDown()
                        return@TextToSpeech
                    }

                    initSuccess.set(true)
                    initLatch.countDown()
                }, GOOGLE_TTS_ENGINE)
                engineRef.set(engine)
            }

            assertTrue("TTS init did not complete in time", initLatch.await(30, TimeUnit.SECONDS))
            assertEquals(null, initError.get())

            val engine = engineRef.get()
            assertNotNull(engine)
            val activeEngine = requireNotNull(engine)

            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val localeResult = activeEngine.setLanguage(INDIAN_ENGLISH)
                if (localeResult == TextToSpeech.LANG_MISSING_DATA || localeResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    initError.compareAndSet(null, "en-IN is not available on the selected TTS engine")
                } else {
                    activeEngine.setSpeechRate(0.9f)
                    val voices = activeEngine.voices.orEmpty()
                        .map {
                            TtsVoiceOption(
                                name = it.name,
                                language = it.locale.language,
                                country = it.locale.country,
                                networkRequired = it.isNetworkConnectionRequired,
                                quality = it.quality,
                            )
                        }
                    val selected = selectPreferredIndianEnglishVoice(voices)
                    val selectedVoice = selected?.let { option ->
                        activeEngine.voices?.firstOrNull { it.name == option.name }
                    }
                    if (selectedVoice != null) {
                        activeEngine.voice = selectedVoice
                    }
                    selectedVoiceName.set(selectedVoice?.name ?: selected?.name ?: "locale_default")
                    Log.i("BetaAgent", "TTS_FIXTURE_SELECTED_VOICE voice=${selectedVoiceName.get()}")
                }
            }

            assertEquals(null, initError.get())

            val synthLatch = CountDownLatch(1)
            val synthError = AtomicReference<String?>()
            activeEngine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    synthLatch.countDown()
                }

                override fun onError(utteranceId: String?) {
                    synthError.compareAndSet(null, "TTS synthesis error for utteranceId=$utteranceId")
                    synthLatch.countDown()
                }
            })

            val speakText = "Milk, bread, eggs, bananas, and apples."
            val result = activeEngine.synthesizeToFile(
                speakText,
                Bundle(),
                outputFile,
                UTTERANCE_ID,
            )
            assertEquals(TextToSpeech.SUCCESS, result)
            assertTrue("TTS synthesis did not finish in time", synthLatch.await(30, TimeUnit.SECONDS))
            assertEquals(null, synthError.get())
            assertTrue("Expected a synthesized WAV file to be written", outputFile.exists() && outputFile.length() > 0L)
            assertTrue("Selected voice should be logged", selectedVoiceName.get().orEmpty().isNotBlank())
            Log.i("BetaAgent", "TTS_FIXTURE_SYNTHESIS_SUCCESS bytes=${outputFile.length()}")
        } finally {
            val engine = engineRef.get()
            if (engine != null) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    engine.stop()
                    engine.shutdown()
                }
            }
        }
    }

    private companion object {
        const val FLAG_NAME = "betaSynthesizeFixture"
        const val OUTPUT_FILE = "swiggy-synthetic-voice.wav"
        const val UTTERANCE_ID = "swiggy_synthetic_fixture"
        const val GOOGLE_TTS_ENGINE = "com.google.android.tts"
        val INDIAN_ENGLISH: Locale = Locale.forLanguageTag("en-IN")
    }
}
