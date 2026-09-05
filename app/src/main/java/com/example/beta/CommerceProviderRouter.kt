package com.example.beta

import java.util.Locale

/** Swiggy-only routing retained as a small boundary for launcher and voice parsing tests. */
object CommerceProviderRouter {
    enum class CommerceProvider(
        val appName: String,
        val packageName: String,
        val packageAliases: List<String>,
        val aliases: Set<String>,
    ) {
        SWIGGY_INSTAMART(
            appName = "Swiggy Instamart",
            packageName = "in.swiggy.android",
            packageAliases = listOf("in.swiggy.android", "in.swiggy.android.instamart"),
            aliases = setOf("swiggy", "instamart"),
        ),
    }

    enum class PreferenceSource {
        DEFAULT,
        UI,
        VOICE_OR_TEXT,
    }

    data class LaunchDecision(
        val selectedProvider: CommerceProvider,
        val appName: String,
        val packageName: String,
        val message: String,
        val preferenceSource: PreferenceSource,
        val fallbackUsed: Boolean = false,
        val launchable: Boolean,
    )

    data class InstalledCommerceApp(
        val provider: CommerceProvider,
        val packageName: String,
    )

    data class SessionSelection(
        val provider: CommerceProvider = CommerceProvider.SWIGGY_INSTAMART,
        val source: PreferenceSource = PreferenceSource.DEFAULT,
    )

    fun supportedProviders(): List<CommerceProvider> = listOf(CommerceProvider.SWIGGY_INSTAMART)

    @Suppress("UNUSED_PARAMETER")
    fun unsupportedProviderName(instruction: String?): String? = null

    fun resetSession() = Unit

    fun currentSessionProvider(): CommerceProvider = CommerceProvider.SWIGGY_INSTAMART

    fun currentSessionSelectionSource(): PreferenceSource = PreferenceSource.DEFAULT

    @Suppress("UNUSED_PARAMETER")
    fun selectProviderFromUi(provider: CommerceProvider): SessionSelection = SessionSelection(
        source = PreferenceSource.UI,
    )

    fun selectProviderFromInstruction(instruction: String?): SessionSelection? {
        return if (mentionsSwiggy(instruction)) SessionSelection(source = PreferenceSource.VOICE_OR_TEXT) else null
    }

    fun sanitizeOrderInstruction(instruction: String?): String {
        val trimmed = instruction?.trim()?.replace(Regex("[\\r\\n]+"), ", ").orEmpty()
        if (trimmed.isBlank() || isOpenCommerceAppInstruction(trimmed)) return trimmed

        Regex(
            "^(?:please\\s+)?use\\s+(swiggy|instamart)\\s+for\\s+(.+?)(?:\\s+please)?$",
            RegexOption.IGNORE_CASE,
        ).matchEntire(trimmed)?.let { match ->
            return match.groupValues[2].trim()
        }

        return trimmed
            .replace(
                Regex(
                    "\\b(?:from|on|via|using)\\s+(swiggy|instamart)(?:\\s+please)?[.!]?$",
                    RegexOption.IGNORE_CASE,
                ),
                "",
            )
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun isOpenCommerceAppInstruction(instruction: String?): Boolean {
        val normalized = normalize(instruction)
        if (normalized.isBlank()) return false
        val words = normalized.split(' ')
        val openVerbs = setOf("open", "launch", "start", "use", "switch", "select", "change")
        val orderIntent = words.any { it in setOf("order", "buy", "purchase", "checkout", "pay") } ||
            normalized.contains("add to cart")
        val genericAppRequest = normalized.contains("grocery app") ||
            normalized.contains("shopping app") ||
            normalized.contains("open app")
        val allowedWords = openVerbs + setOf(
            "app", "grocery", "instamart", "my", "now", "please", "shopping", "swiggy", "the", "to",
        )
        return words.any { it in openVerbs } && !orderIntent && words.all { it in allowedWords } &&
            (genericAppRequest || mentionsSwiggy(normalized))
    }

    fun routeLaunch(instruction: String?, installedApps: Set<InstalledCommerceApp>): LaunchDecision {
        val provider = CommerceProvider.SWIGGY_INSTAMART
        val installed = provider.packageAliases.firstNotNullOfOrNull { packageName ->
            installedApps.firstOrNull { it.provider == provider && it.packageName == packageName }
        }
        val source = if (mentionsSwiggy(instruction)) PreferenceSource.VOICE_OR_TEXT else PreferenceSource.DEFAULT
        return LaunchDecision(
            selectedProvider = provider,
            appName = provider.appName,
            packageName = installed?.packageName ?: provider.packageName,
            message = if (installed == null) {
                "Could not open Swiggy. Please open it manually and try again."
            } else {
                "Opening Swiggy Instamart"
            },
            preferenceSource = source,
            launchable = installed != null,
        )
    }

    private fun mentionsSwiggy(instruction: String?): Boolean {
        val normalized = normalize(instruction)
        return CommerceProvider.SWIGGY_INSTAMART.aliases.any { alias ->
            Regex("(^|\\s)${Regex.escape(alias)}(\\s|$)").containsMatchIn(normalized)
        }
    }

    private fun normalize(instruction: String?): String = instruction
        ?.lowercase(Locale.US)
        ?.replace(Regex("[^a-z0-9 ]+"), " ")
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        .orEmpty()
}
