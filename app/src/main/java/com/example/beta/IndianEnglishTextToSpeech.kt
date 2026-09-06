package com.example.beta

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

internal data class TtsVoiceOption(
    val name: String,
    val language: String,
    val country: String,
    val networkRequired: Boolean,
    val quality: Int,
)

internal fun selectPreferredIndianEnglishVoice(
    voices: Collection<TtsVoiceOption>,
): TtsVoiceOption? {
    val indianEnglish = voices.filter {
        it.language.equals("en", ignoreCase = true) &&
            it.country.equals("IN", ignoreCase = true)
    }
    VERIFIED_GOOGLE_EN_IN_MALE_VOICE_IDS.forEach { preferredName ->
        indianEnglish.firstOrNull { it.name.equals(preferredName, ignoreCase = true) }
            ?.let { return it }
    }
    return indianEnglish.minWithOrNull(
        compareByDescending<TtsVoiceOption> { hasMaleVoiceLabel(it.name) }
            .thenBy { it.networkRequired }
            .thenByDescending { it.quality }
            .thenBy { it.name },
    )
}

private val VERIFIED_GOOGLE_EN_IN_MALE_VOICE_IDS = listOf(
    // Google Speech Services Voice III and Voice IV, verified from device-synthesised samples.
    "en-in-x-end-local",
    "en-in-x-ene-local",
    "en-in-x-end-network",
    "en-in-x-ene-network",
)

internal fun isPreferredMaleIndianEnglishVoice(name: String): Boolean {
    return hasMaleVoiceLabel(name) ||
        VERIFIED_GOOGLE_EN_IN_MALE_VOICE_IDS.any { it.equals(name, ignoreCase = true) }
}

internal fun hasMaleVoiceLabel(name: String): Boolean {
    return Regex("(^|[^a-z])male([^a-z]|$)", RegexOption.IGNORE_CASE).containsMatchIn(name)
}

/**
 * Owns the app's spoken feedback. Google Speech Services is requested explicitly,
 * with a device-default fallback when it is unavailable.
 */
internal class IndianEnglishTextToSpeech(context: Context) {
    private val appContext = context.applicationContext
    private var engine: TextToSpeech? = null
    private var requestedEngine: String? = GOOGLE_TTS_ENGINE
    private var ready = false
    private var pendingMessage: String? = null
    private var shutdown = false

    init {
        initialise(GOOGLE_TTS_ENGINE)
    }

    fun speak(message: String) {
        if (shutdown) return
        val normalized = message.trim()
        if (normalized.isBlank()) return
        val activeEngine = engine
        if (!ready || activeEngine == null) {
            pendingMessage = normalized
            return
        }
        splitSpeechForTts(normalized).forEachIndexed { index, chunk ->
            activeEngine.speak(chunk, if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                null, "$UTTERANCE_ID-$index")
        }
    }

    fun stop() {
        pendingMessage = null
        engine?.stop()
    }

    fun shutdown() {
        shutdown = true
        pendingMessage = null
        ready = false
        engine?.shutdown()
        engine = null
    }

    private fun initialise(enginePackage: String?) {
        requestedEngine = enginePackage
        ready = false
        engine = if (enginePackage == null) {
            TextToSpeech(appContext) { status -> onInitialised(status, enginePackage) }
        } else {
            TextToSpeech(appContext, { status -> onInitialised(status, enginePackage) }, enginePackage)
        }
    }

    private fun onInitialised(status: Int, enginePackage: String?) {
        if (shutdown || enginePackage != requestedEngine) return
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TTS_ENGINE_INIT_FAILED engine=${enginePackage ?: "device_default"}")
            engine?.shutdown()
            engine = null
            if (enginePackage == GOOGLE_TTS_ENGINE) initialise(null)
            return
        }

        val activeEngine = engine ?: return
        val localeResult = activeEngine.setLanguage(INDIAN_ENGLISH)
        if (localeResult == TextToSpeech.LANG_MISSING_DATA || localeResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "TTS_EN_IN_UNAVAILABLE engine=${enginePackage ?: "device_default"}")
        }
        activeEngine.setSpeechRate(0.90f)
        activeEngine.setPitch(0.92f)

        val availableVoices = activeEngine.voices.orEmpty()
        val options = availableVoices.map { it.toOption() }
        val selected = selectPreferredIndianEnglishVoice(options)
        val selectedVoice = selected?.let { option ->
            availableVoices.firstOrNull { it.name == option.name }
        }
        if (selectedVoice != null) activeEngine.voice = selectedVoice

        Log.i(
            TAG,
            "TTS_READY engine=${enginePackage ?: "device_default"} locale=en-IN " +
                "voice=${selected?.name ?: "locale_default"} malePreference=${selected?.let { isPreferredMaleIndianEnglishVoice(it.name) } == true}",
        )
        options.filter { it.language.equals("en", true) && it.country.equals("IN", true) }
            .sortedBy { it.name }
            .joinToString(separator = ",") { it.name }
            .takeIf(String::isNotBlank)
            ?.let { Log.i(TAG, "TTS_EN_IN_CANDIDATES names=$it") }

        ready = true
        pendingMessage?.also {
            pendingMessage = null
            speak(it)
        }
    }

    private fun Voice.toOption(): TtsVoiceOption {
        return TtsVoiceOption(
            name = name,
            language = locale.language,
            country = locale.country,
            networkRequired = isNetworkConnectionRequired,
            quality = quality,
        )
    }

    companion object {
        private const val TAG = "BetaAgent"
        private const val GOOGLE_TTS_ENGINE = "com.google.android.tts"
        private const val UTTERANCE_ID = "beta_voice_prompt"
        private val INDIAN_ENGLISH = Locale.forLanguageTag("en-IN")
    }
}

/** Keep long final basket summaries under Android's per-utterance input limit. */
internal fun splitSpeechForTts(message: String, limit: Int = 3500): List<String> {
    require(limit >= 2)
    val chunks = mutableListOf<String>()
    var rest = message.trim()
    while (rest.length > limit) {
        val space = rest.lastIndexOf(' ', limit - 1)
        var end = if (space > 0) space else limit
        if (Character.isHighSurrogate(rest[end - 1])) end--
        chunks += rest.substring(0, end)
        rest = rest.substring(end).trimStart()
    }
    if (rest.isNotBlank()) chunks += rest
    return chunks
}
