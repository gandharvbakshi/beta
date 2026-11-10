package com.example.beta

import android.content.Context

enum class GroceryIntentProvider(val requestValue: String) {
    AUTO("auto"),
    GEMINI("gemini"),
    DEEPSEEK("deepseek"),
    OPENAI("openai"),
}

internal val GroceryIntentProvider.displayName: String get() = when (this) {
    GroceryIntentProvider.GEMINI -> "Google Gemini"
    GroceryIntentProvider.DEEPSEEK -> "DeepSeek"
    GroceryIntentProvider.OPENAI -> "OpenAI"
    GroceryIntentProvider.AUTO -> "Automatic"
}

internal enum class GroceryIntentContract(val requestValue: String) {
    V1("grocery-intent-v1"), V2("grocery-intent-v2"), CONTEXT(GROCERY_INTENT_CONTEXT_CONTRACT),
    INTERPRETED("grocery-intent-interpreted-v2");

    // Preserve the prior V1/V2 policy, but never silently downgrade a negotiated
    // whole-original review contract to the local parser on the next attempt.
    fun disablesProviderOnUnavailable(): Boolean = this == V1 || this == V2
}

internal sealed interface GroceryIntentSelection {
    data object Disabled : GroceryIntentSelection
    data object Unavailable : GroceryIntentSelection
    data class Enabled(
        val mode: GroceryIntentPreferences.Mode,
        val contract: GroceryIntentContract,
        val revision: Long = 0L,
    ) : GroceryIntentSelection {
        init {
            require(mode != GroceryIntentPreferences.Mode.OFF)
            require(contract != GroceryIntentContract.INTERPRETED || mode != GroceryIntentPreferences.Mode.AUTO)
        }
        val provider: GroceryIntentProvider
            get() = requireNotNull(mode.providerOrNull()) { "Enabled selection requires a provider" }
    }
}

/** Process-local guard: SharedPreferences can update memory even when commit() fails. */
internal class GroceryIntentPreferenceAuthority {
    private var revision = 0L
    private var unavailable = false

    @Synchronized
    fun write(value: String, commit: (String) -> Boolean, read: () -> String?): Boolean {
        revision++ // Even a failed write, or a new grant for the same mode, invalidates old callbacks.
        unavailable = true
        try {
            if (commit(value) && read() == value) unavailable = false
        } catch (_: Exception) {
            // Only a subsequent explicit write and matching readback can clear this process's hold.
        }
        return !unavailable
    }

    @Synchronized
    fun selection(read: () -> String?, resolve: (String?) -> GroceryIntentSelection): GroceryIntentSelection {
        if (unavailable) return GroceryIntentSelection.Unavailable
        val raw = try {
            read()
        } catch (_: Exception) {
            unavailable = true
            return GroceryIntentSelection.Unavailable
        }
        val choice = resolve(raw)
        if (choice == GroceryIntentSelection.Unavailable) unavailable = true
        return if (choice is GroceryIntentSelection.Enabled) choice.copy(revision = revision) else choice
    }
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
    private const val INTERPRETED_RECEIPT_PREFIX = "v1|"
    private const val INTERPRETED_DISCLOSURE = "qa-disclosure-nov07-v2"
    private const val INTERPRETED_AUDIENCE = "debug"
    private const val PUBLIC_OPENAI_DISCLOSURE = "disclosure-nov07-v1"
    private const val PUBLIC_OPENAI_AUDIENCE = "public"
    private val authority = GroceryIntentPreferenceAuthority()

    internal fun contractVersion(): GroceryIntentContract = selectContract(
        v2Enabled = BuildConfig.BETA_INTENT_V2_ENABLED,
        contextEnabled = BuildConfig.BETA_INTENT_CONTEXT_ENABLED,
        interpretedEnabled = BuildConfig.BETA_INTENT_INTERPRETED_ENABLED,
    )

    internal fun selectContract(v2Enabled: Boolean, contextEnabled: Boolean, interpretedEnabled: Boolean = false): GroceryIntentContract {
        require(listOf(v2Enabled, contextEnabled, interpretedEnabled).count { it } <= 1) { "Intent contracts are mutually exclusive" }
        return when {
            interpretedEnabled -> GroceryIntentContract.INTERPRETED
            contextEnabled -> GroceryIntentContract.CONTEXT
            v2Enabled -> GroceryIntentContract.V2
            else -> GroceryIntentContract.V1
        }
    }

    internal fun eligiblePublicOptionAudience(
        interpretedEnabled: Boolean = BuildConfig.BETA_INTENT_INTERPRETED_ENABLED,
        publicOpenAiEnabled: Boolean = BuildConfig.BETA_INTENT_PUBLIC_OPENAI_ENABLED,
    ): String? = PUBLIC_OPENAI_AUDIENCE.takeIf { interpretedEnabled && publicOpenAiEnabled }

    internal fun isInterpretedTestAudience(
        debug: Boolean = BuildConfig.DEBUG,
        interpretedEnabled: Boolean = BuildConfig.BETA_INTENT_INTERPRETED_ENABLED,
        publicOpenAiEnabled: Boolean = BuildConfig.BETA_INTENT_PUBLIC_OPENAI_ENABLED,
    ): Boolean = debug && interpretedEnabled && !publicOpenAiEnabled

    internal fun resolveSelection(
        storedValue: String?, debug: Boolean, v2Enabled: Boolean, contextEnabled: Boolean,
        interpretedEnabled: Boolean, publicOpenAiEnabled: Boolean = false,
    ): GroceryIntentSelection {
        // Keep the build-level exclusivity invariant even when a saved choice is invalid.
        selectContract(v2Enabled, contextEnabled, interpretedEnabled)
        if (storedValue == null || storedValue == Mode.OFF.storedValue) return GroceryIntentSelection.Disabled

        if (storedValue == encodePublicOpenAiReceipt()) {
            if (eligiblePublicOptionAudience(interpretedEnabled, publicOpenAiEnabled) != PUBLIC_OPENAI_AUDIENCE) {
                return GroceryIntentSelection.Unavailable
            }
            return GroceryIntentSelection.Enabled(Mode.OPENAI, GroceryIntentContract.INTERPRETED)
        }

        if (storedValue.startsWith(INTERPRETED_RECEIPT_PREFIX)) {
            val fields = storedValue.split('|')
            if (fields.size != 5 || fields[0] != "v1" || fields[2] != GroceryIntentContract.INTERPRETED.requestValue ||
                fields[3] != INTERPRETED_DISCLOSURE || fields[4] != INTERPRETED_AUDIENCE ||
                !isInterpretedTestAudience(debug, interpretedEnabled, publicOpenAiEnabled)
            ) return GroceryIntentSelection.Unavailable
            val mode = Mode.entries.firstOrNull { it.storedValue == fields[1] && it != Mode.OFF && it != Mode.AUTO }
                ?: return GroceryIntentSelection.Unavailable
            return GroceryIntentSelection.Enabled(mode, GroceryIntentContract.INTERPRETED)
        }

        val mode = Mode.entries.firstOrNull { it.storedValue == storedValue } ?: return GroceryIntentSelection.Unavailable
        if (mode == Mode.OFF) return GroceryIntentSelection.Disabled
        // A named test build can send a draft and seller listings. A legacy alias (including Auto)
        // gives no permission for that audience; never run a different contract behind its picker.
        if (isInterpretedTestAudience(debug, interpretedEnabled, publicOpenAiEnabled)) return GroceryIntentSelection.Unavailable
        if (publicOpenAiEnabled && mode != Mode.GEMINI) return GroceryIntentSelection.Unavailable
        if (debug) {
            if (mode == Mode.GEMINI || mode == Mode.AUTO || mode == Mode.DEEPSEEK || mode == Mode.OPENAI) {
                val contract = selectContract(v2Enabled, contextEnabled, false)
                return GroceryIntentSelection.Enabled(mode, contract)
            }
        } else if (mode == Mode.GEMINI) {
            return GroceryIntentSelection.Enabled(mode, GroceryIntentContract.V1)
        }
        return GroceryIntentSelection.Unavailable
    }

    internal fun encodeInterpretedDevelopmentChoice(mode: Mode): String {
        require(mode != Mode.OFF && mode != Mode.AUTO) { "A concrete provider is required" }
        return "v1|${mode.storedValue}|${GroceryIntentContract.INTERPRETED.requestValue}|$INTERPRETED_DISCLOSURE|$INTERPRETED_AUDIENCE"
    }

    internal fun encodePublicOpenAiReceipt(): String =
        "v1|openai|${GroceryIntentContract.INTERPRETED.requestValue}|$PUBLIC_OPENAI_DISCLOSURE|$PUBLIC_OPENAI_AUDIENCE"

    internal fun savePublicOpenAiConsent(context: Context): Boolean {
        if (eligiblePublicOptionAudience() != PUBLIC_OPENAI_AUDIENCE) return false
        return write(context, encodePublicOpenAiReceipt())
    }

    internal fun setInterpretedDevelopmentMode(context: Context, mode: Mode): Boolean {
        if (!isInterpretedTestAudience()) return false
        if (mode == Mode.OFF || mode == Mode.AUTO) return false
        return write(context, encodeInterpretedDevelopmentChoice(mode))
    }

    // Keep alternative adapters available for development without presenting an
    // unqualified model choice to release users. Widen only after new evaluation.
    fun availableModes(debug: Boolean = BuildConfig.DEBUG): List<Mode> =
        if (debug) Mode.entries else listOf(Mode.OFF, Mode.GEMINI)

    fun getMode(context: Context): Mode {
        return (getSelection(context) as? GroceryIntentSelection.Enabled)?.mode ?: Mode.OFF
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    internal fun getSelection(context: Context): GroceryIntentSelection =
        authority.selection({ preferences(context).getString(KEY_MODE, null) }) { value ->
            resolveSelection(
                value, BuildConfig.DEBUG, BuildConfig.BETA_INTENT_V2_ENABLED,
                BuildConfig.BETA_INTENT_CONTEXT_ENABLED, BuildConfig.BETA_INTENT_INTERPRETED_ENABLED,
                BuildConfig.BETA_INTENT_PUBLIC_OPENAI_ENABLED,
            )
        }

    private fun write(context: Context, value: String): Boolean =
        authority.write(value, { preferences(context).edit().putString(KEY_MODE, it).commit() },
            { preferences(context).getString(KEY_MODE, null) })

    fun setMode(context: Context, mode: Mode): Boolean {
        require(mode in availableModes()) { "This provider is not available in this build" }
        return write(context, mode.storedValue)
    }
}
