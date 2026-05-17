package com.example.beta.automation

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

enum class PreferenceSource {
    SEEDED,
    USER_OVERRIDE,
    INFERRED
}

data class Preference(
    val token: String,
    val preferredPhrase: String,
    val avoidPhrases: List<String> = emptyList(),
    val source: PreferenceSource = PreferenceSource.SEEDED,
    val confidence: Float
)

object PreferenceStore {
    const val ACTION_SEED_PREFERENCE = "com.example.beta.SEED_PREFERENCE"
    const val ACTION_CLEAR_PREFERENCES = "com.example.beta.CLEAR_PREFERENCES"
    private const val MIN_CONFIDENCE = 0.6f
    private const val PREFS_NAME = "beta_user_preferences"
    private const val KEY_PREFIX = "pref_"

    private val preferences = linkedMapOf<String, Preference>()

    @Synchronized
    fun upsert(preference: Preference) {
        val token = normalize(preference.token)
        if (token.isBlank()) return
        preferences[token] = preference.copy(
            token = token,
            preferredPhrase = normalize(preference.preferredPhrase),
            avoidPhrases = preference.avoidPhrases.map(::normalize).filter { it.isNotBlank() }
        )
    }

    @Synchronized
    fun upsert(context: Context, preference: Preference) {
        upsert(preference)
        val stored = preferences[normalize(preference.token)] ?: return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PREFIX + stored.token, stored.toJson().toString())
            .apply()
    }

    @Synchronized
    fun lookup(token: String): Preference? {
        val preference = preferences[normalize(token)] ?: return null
        return preference.takeIf { it.confidence >= MIN_CONFIDENCE }
    }

    @Synchronized
    fun lookup(context: Context, token: String): Preference? {
        lookup(token)?.let { return it }
        val normalized = normalize(token)
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PREFIX + normalized, null)
            ?: return null
        val preference = parse(raw) ?: return null
        preferences[normalized] = preference
        return preference.takeIf { it.confidence >= MIN_CONFIDENCE }
    }

    @Synchronized
    fun forgetAll() {
        preferences.clear()
    }

    @Synchronized
    fun forgetAll(context: Context) {
        preferences.clear()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    @Synchronized
    fun snapshot(): List<Preference> = preferences.values.toList()

    fun normalize(value: String): String {
        return ProductLexicon.canonicalizeProductText(value)
            .trim()
            .replace(Regex("\\s+"), " ")
            .lowercase(Locale.US)
    }

    private fun Preference.toJson(): JSONObject {
        return JSONObject()
            .put("token", token)
            .put("preferredPhrase", preferredPhrase)
            .put("avoidPhrases", JSONArray(avoidPhrases))
            .put("source", source.name)
            .put("confidence", confidence)
    }

    private fun parse(raw: String): Preference? {
        return try {
            val json = JSONObject(raw)
            val avoids = json.optJSONArray("avoidPhrases")
            val avoidPhrases = mutableListOf<String>()
            if (avoids != null) {
                for (i in 0 until avoids.length()) {
                    avoidPhrases.add(avoids.optString(i))
                }
            }
            Preference(
                token = json.optString("token"),
                preferredPhrase = json.optString("preferredPhrase"),
                avoidPhrases = avoidPhrases,
                source = runCatching {
                    PreferenceSource.valueOf(json.optString("source", PreferenceSource.SEEDED.name))
                }.getOrDefault(PreferenceSource.SEEDED),
                confidence = json.optDouble("confidence", 0.0).toFloat()
            )
        } catch (_: Exception) {
            null
        }
    }
}
