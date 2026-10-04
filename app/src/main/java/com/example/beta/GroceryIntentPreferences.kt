package com.example.beta

import android.content.Context

enum class GroceryIntentProvider(val requestValue: String) {
    AUTO("auto"),
    GEMINI("gemini"),
    DEEPSEEK("deepseek"),
    OPENAI("openai"),
}

/** Opt-in switch for the review-only intent provider. OFF is the conservative rollout default. */
object GroceryIntentPreferences {
    enum class Mode(val storedValue: String) {
        OFF("off"),
        AUTO("auto"),
        GEMINI("gemini"),
        DEEPSEEK("deepseek"),
        OPENAI("openai");

        fun providerOrNull(): GroceryIntentProvider? = when (this) {
            OFF -> null
            AUTO -> GroceryIntentProvider.AUTO
            GEMINI -> GroceryIntentProvider.GEMINI
            DEEPSEEK -> GroceryIntentProvider.DEEPSEEK
            OPENAI -> GroceryIntentProvider.OPENAI
        }
    }

    private const val PREFERENCES_NAME = "grocery_intent_preferences"
    private const val KEY_MODE = "provider_mode"

    // Keep alternative adapters available for development without presenting an
    // unqualified model choice to release users. Widen only after new evaluation.
    fun availableModes(debug: Boolean = BuildConfig.DEBUG): List<Mode> =
        if (debug) Mode.entries else listOf(Mode.OFF, Mode.GEMINI)

    fun getMode(context: Context): Mode {
        val value = context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MODE, Mode.OFF.storedValue)
        return availableModes().firstOrNull { it.storedValue == value } ?: Mode.OFF
    }

    fun setMode(context: Context, mode: Mode) {
        require(mode in availableModes()) { "This provider is not available in this build" }
        context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, mode.storedValue)
            .apply()
    }
}
