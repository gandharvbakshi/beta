package com.example.beta

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class SwiggyInjectedSpeechReadOnlyTest {
    @Test
    fun recognizerConsumesAppPrivateWavWithoutMicCapture() {
        assumeTrue(
            "Enable with instrumentation argument liveSwiggyInjectedSpeech=true",
            InstrumentationRegistry.getArguments().getString("liveSwiggyInjectedSpeech") == "true",
        )
        assertTrue("This diagnostic requires Android 36+", Build.VERSION.SDK_INT >= 36)

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val appContext = context.applicationContext

        val audioManager = context.getSystemService(AudioManager::class.java)
        assertNotNull(audioManager)
        assertTrue(
            "Expected the microphone to be muted device-wide before starting; enable microphone privacy first.",
            requireNotNull(audioManager).isMicrophoneMute,
        )
        assertEquals(
            "This diagnostic must run with RECORD_AUDIO denied so a mic fallback cannot be confused with the file-backed path.",
            PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO),
        )

        val fixtureFile = File(context.filesDir, FIXTURE_FILE_NAME)
        assertTrue(
            "Expected the app-private WAV fixture written by SwiggySyntheticSpeechFixtureTest.",
            fixtureFile.exists() && fixtureFile.length() > 0L,
        )
        val wavFixture = parseWavFixture(fixtureFile)
        assertTrue("Expected a mono 16-bit PCM fixture.", wavFixture.channelCount == 1 && wavFixture.bitsPerSample == 16)

        val outputLog = File(context.filesDir, DIAGNOSTIC_LOG_FILE)
        val completedLatch = CountDownLatch(1)
        val recognizedTokens = AtomicReference<List<String>>(emptyList())
        val recognitionError = AtomicInteger(Int.MIN_VALUE)
        val recognizerRef = AtomicReference<SpeechRecognizer?>()
        val sourcePair = ParcelFileDescriptor.createPipe()
        val readSide = sourcePair[0]
        val writeSide = sourcePair[1]
        val writerDone = CountDownLatch(1)
        var writerThread: Thread? = null

        try {
            instrumentation.runOnMainSync {
                recognizerRef.set(createRecognizer(appContext, completedLatch, recognizedTokens, recognitionError))
            }
            val recognizer = requireNotNull(recognizerRef.get())

            val intent = buildRecognizerIntent(readSide, wavFixture)
            instrumentation.runOnMainSync {
                recognizer.startListening(intent)
            }

            writerThread = Thread {
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { output ->
                        output.write(wavFixture.pcmBytes)
                        output.flush()
                    }
                } catch (_: java.io.IOException) {
                    // Expected to stay quiet if the recognizer closes the pipe first.
                } finally {
                    writerDone.countDown()
                }
            }
            writerThread?.name = "swiggy-speech-fixture-writer"
            writerThread?.isDaemon = true
            writerThread?.start()

            assertTrue(
                "Speech recognition did not finish in time.",
                completedLatch.await(30, TimeUnit.SECONDS),
            )
            assertTrue("The injected audio writer did not close cleanly.", writerDone.await(5, TimeUnit.SECONDS))
            assertEquals("Speech recognizer returned an error", 0, recognitionError.get())

            val tokenSet = recognizedTokens.get().toSet()
            val matchedTokenCount = EXPECTED_TOKENS.count { token -> token in tokenSet }
            assertEquals(
                "Expected the recognizer to recover the five-item synthetic grocery list.",
                EXPECTED_TOKENS.size,
                matchedTokenCount,
            )
        } finally {
            try {
                appendDiagnosticRecord(
                    outputLog,
                    timestampMillis = System.currentTimeMillis(),
                    errorCode = recognitionError.get(),
                    matchedTokenCount = EXPECTED_TOKENS.count { token -> token in recognizedTokens.get().toSet() },
                    expectedCount = EXPECTED_TOKENS.size,
                )
            } finally {
                instrumentation.runOnMainSync { recognizerRef.getAndSet(null)?.destroy() }
                runCatching { readSide.close() }
                runCatching { writeSide.close() }
                runCatching { writerThread?.join(2_000) }
            }
        }
    }

    private fun createRecognizer(
        context: Context,
        completedLatch: CountDownLatch,
        recognizedTokens: AtomicReference<List<String>>,
        recognitionError: AtomicInteger,
    ): SpeechRecognizer {
        assertTrue("Speech recognition must be available on this device.", SpeechRecognizer.isRecognitionAvailable(context))
        return SpeechRecognizer.createSpeechRecognizer(context).also { recognizer ->
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit

                override fun onPartialResults(partialResults: Bundle?) = Unit

                override fun onError(error: Int) {
                    recognitionError.set(error)
                    completedLatch.countDown()
                }

                override fun onResults(results: Bundle?) {
                    val tokens = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                        .let { normalizeTokens(it) }
                    recognizedTokens.set(tokens)
                    recognitionError.set(0)
                    completedLatch.countDown()
                }
            })
        }
    }

    private fun buildRecognizerIntent(readSide: ParcelFileDescriptor, fixture: WavFixture): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, INDIA_ENGLISH_TAG)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, INDIA_ENGLISH_TAG)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readSide)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, fixture.channelCount)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, fixture.sampleRateHz)
        }
    }

    private fun parseWavFixture(file: File): WavFixture {
        require(file.length() <= 5L * 1024 * 1024) { "WAV fixture is unexpectedly large." }
        val bytes = file.readBytes()
        require(bytes.size >= 44) { "WAV fixture is too small." }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(readAscii(bytes, 0, 4) == "RIFF") { "WAV fixture missing RIFF header." }
        require(readAscii(bytes, 8, 4) == "WAVE") { "WAV fixture missing WAVE header." }

        var cursor = 12
        var channels = -1
        var sampleRate = -1
        var bitsPerSample = -1
        var pcmStart = -1
        var pcmSize = -1

        while (cursor + 8 <= bytes.size) {
            val chunkId = readAscii(bytes, cursor, 4)
            val chunkSize = buffer.getInt(cursor + 4)
            require(chunkSize >= 0) { "WAV chunk size is invalid." }
            val chunkDataStart = cursor + 8
            if (chunkDataStart + chunkSize > bytes.size) break
            when (chunkId) {
                "fmt " -> {
                    require(chunkSize >= 16) { "WAV fmt chunk is too small." }
                    val audioFormat = buffer.getShort(chunkDataStart).toInt() and 0xffff
                    channels = buffer.getShort(chunkDataStart + 2).toInt() and 0xffff
                    sampleRate = buffer.getInt(chunkDataStart + 4)
                    bitsPerSample = buffer.getShort(chunkDataStart + 14).toInt() and 0xffff
                    assertEquals("Expected PCM audio.", 1, audioFormat)
                }
                "data" -> {
                    pcmStart = chunkDataStart
                    pcmSize = chunkSize
                    break
                }
            }
            cursor = chunkDataStart + chunkSize + (chunkSize % 2)
        }

        require(pcmStart >= 0 && pcmSize > 0) { "WAV fixture missing PCM data." }
        return WavFixture(
            sampleRateHz = sampleRate,
            channelCount = channels,
            bitsPerSample = bitsPerSample,
            pcmBytes = bytes.copyOfRange(pcmStart, pcmStart + pcmSize),
        )
    }

    private fun appendDiagnosticRecord(
        file: File,
        timestampMillis: Long,
        errorCode: Int,
        matchedTokenCount: Int,
        expectedCount: Int,
    ) {
        val record = JSONObject()
            .put("timestampMillis", timestampMillis)
            .put("errorCode", errorCode)
            .put("matchedTokenCount", matchedTokenCount)
            .put("expectedCount", expectedCount)
        file.appendText(record.toString() + "\n")
    }

    private fun normalizeTokens(text: String): List<String> {
        return text
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .map {
                when (it) {
                    "bananas" -> "banana"
                    "apples" -> "apple"
                    "eggs" -> "egg"
                    else -> it
                }
            }
    }

    private fun readAscii(bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, length, Charsets.US_ASCII)

    private data class WavFixture(
        val sampleRateHz: Int,
        val channelCount: Int,
        val bitsPerSample: Int,
        val pcmBytes: ByteArray,
    )

    private companion object {
        private const val INDIA_ENGLISH_TAG = "en-IN"
        private const val DIAGNOSTIC_LOG_FILE = "swiggy_injected_speech_diagnostics.jsonl"
        private const val FIXTURE_FILE_NAME = "swiggy-synthetic-voice.wav"
        private val EXPECTED_TOKENS = listOf("milk", "bread", "egg", "banana", "apple")
    }
}
