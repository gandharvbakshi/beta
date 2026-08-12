package com.example.beta

import java.util.Locale

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
        BLINKIT(
            appName = "Blinkit",
            packageName = "com.grofers.customerapp",
            packageAliases = listOf("com.grofers.customerapp"),
            aliases = setOf("blinkit", "grofers"),
        ),
        ZEPTO(
            appName = "Zepto",
            packageName = "com.zeptoconsumerapp",
            packageAliases = listOf("com.zeptoconsumerapp"),
            aliases = setOf("zepto"),
        ),
    }

    enum class PreferenceSource {
        DEFAULT,
        UI,
        VOICE_OR_TEXT,
        FALLBACK_FROM_DEFAULT,
    }

    data class LaunchDecision(
        val selectedProvider: CommerceProvider,
        val appName: String,
        val packageName: String,
        val message: String,
        val preferenceSource: PreferenceSource,
        val fallbackUsed: Boolean,
        val launchable: Boolean,
    )

    data class InstalledCommerceApp(
        val provider: CommerceProvider,
        val packageName: String,
    )

    data class SessionSelection(
        val provider: CommerceProvider,
        val source: PreferenceSource,
    )

    private val supportedProvidersInFallbackOrder = listOf(
        CommerceProvider.SWIGGY_INSTAMART,
        CommerceProvider.BLINKIT,
        CommerceProvider.ZEPTO,
    )

    private var sessionSelection: SessionSelection = SessionSelection(
        provider = CommerceProvider.SWIGGY_INSTAMART,
        source = PreferenceSource.DEFAULT,
    )

    fun supportedProviders(): List<CommerceProvider> = supportedProvidersInFallbackOrder

    @Synchronized
    fun resetSession() {
        sessionSelection = SessionSelection(
            provider = CommerceProvider.SWIGGY_INSTAMART,
            source = PreferenceSource.DEFAULT,
        )
    }

    @Synchronized
    fun currentSessionProvider(): CommerceProvider = sessionSelection.provider

    @Synchronized
    fun currentSessionSelectionSource(): PreferenceSource = sessionSelection.source

    @Synchronized
    fun selectProviderFromUi(provider: CommerceProvider): SessionSelection {
        if (SwiggyCartMutationGuard.isInFlight()) return sessionSelection
        sessionSelection = SessionSelection(provider = provider, source = PreferenceSource.UI)
        return sessionSelection
    }

    @Synchronized
    fun selectProviderFromInstruction(instruction: String?): SessionSelection? {
        if (SwiggyCartMutationGuard.isInFlight()) return null
        val explicitProvider = parseExplicitProvider(instruction) ?: return null
        sessionSelection = SessionSelection(provider = explicitProvider, source = PreferenceSource.VOICE_OR_TEXT)
        return sessionSelection
    }

    fun sanitizeOrderInstruction(instruction: String?): String {
        val trimmed = instruction?.trim().orEmpty()
        if (trimmed.isBlank() || isOpenCommerceAppInstruction(trimmed)) return trimmed

        val safeUseForPattern = Regex(
            "^(?:please\\s+)?use\\s+(swiggy|instamart|blinkit|grofers|zepto)\\s+for\\s+(.+?)(?:\\s+please)?$",
            RegexOption.IGNORE_CASE,
        )
        safeUseForPattern.matchEntire(trimmed)?.let { match ->
            return match.groupValues[2].trim()
        }

        return trimmed
            .replace(
                Regex(
                    "\\b(?:from|on|via|using)\\s+(swiggy|instamart|blinkit|grofers|zepto)(?:\\s+please)?[.!]?$",
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

        val words = normalized.split(" ")
        val openVerbs = setOf("open", "launch", "start", "use", "switch", "select", "change")
        val openVerb = words.any { it in openVerbs }
        val orderIntent = words.any { it in setOf("order", "buy", "purchase", "checkout", "pay") } ||
            normalized.contains("add to cart")
        val genericCommerceRequest = normalized.contains("grocery app") ||
            normalized.contains("commerce app") ||
            normalized.contains("shopping app") ||
            normalized.contains("open app") ||
            normalized.contains("grocery") && normalized.contains("app")
        val commerceProviderMentioned = parseExplicitProvider(normalized) != null
        val openCommandWords = openVerbs + setOf(
            "app", "commerce", "grocery", "my", "now", "please", "provider", "shopping", "the", "to",
        ) + supportedProvidersInFallbackOrder.flatMap { it.aliases }
        val containsOnlyOpenCommandWords = words.all { it in openCommandWords }
        return openVerb && !orderIntent && containsOnlyOpenCommandWords &&
            (genericCommerceRequest || commerceProviderMentioned)
    }

    @Synchronized
    fun routeLaunch(instruction: String?, installedApps: Set<InstalledCommerceApp>): LaunchDecision {
        if (SwiggyCartMutationGuard.isInFlight()) {
            return decisionForProvider(
                provider = sessionSelection.provider,
                source = sessionSelection.source,
                installedApps = installedApps,
                fallbackUsed = false,
            )
        }
        val explicitProvider = parseExplicitProvider(instruction)
        if (explicitProvider != null) {
            sessionSelection = SessionSelection(provider = explicitProvider, source = PreferenceSource.VOICE_OR_TEXT)
            return decisionForProvider(
                provider = explicitProvider,
                source = PreferenceSource.VOICE_OR_TEXT,
                installedApps = installedApps,
                fallbackUsed = false,
            )
        }

        val currentSelection = sessionSelection
        return if (currentSelection.source == PreferenceSource.DEFAULT) {
            resolveDefaultLaunch(installedApps)
        } else {
            decisionForProvider(
                provider = currentSelection.provider,
                source = currentSelection.source,
                installedApps = installedApps,
                fallbackUsed = false,
            )
        }
    }

    private fun resolveDefaultLaunch(installedApps: Set<InstalledCommerceApp>): LaunchDecision {
        val swiggyInstalled = installedAppFor(CommerceProvider.SWIGGY_INSTAMART, installedApps)
        if (swiggyInstalled != null) {
            return decisionForProvider(
                provider = CommerceProvider.SWIGGY_INSTAMART,
                source = PreferenceSource.DEFAULT,
                installedApps = installedApps,
                fallbackUsed = false,
            )
        }

        val fallbackProvider = supportedProvidersInFallbackOrder.drop(1).firstOrNull { installedAppFor(it, installedApps) != null }
        return if (fallbackProvider != null) {
            val fallbackApp = installedAppFor(fallbackProvider, installedApps)
            decisionForProvider(
                provider = fallbackProvider,
                source = PreferenceSource.FALLBACK_FROM_DEFAULT,
                installedApps = installedApps,
                fallbackUsed = true,
            ).copy(
                packageName = fallbackApp?.packageName ?: fallbackProvider.packageName,
                message = "Swiggy Instamart was unavailable. Opening ${fallbackProvider.appName}.",
            )
        } else {
            LaunchDecision(
                selectedProvider = CommerceProvider.SWIGGY_INSTAMART,
                appName = CommerceProvider.SWIGGY_INSTAMART.appName,
                packageName = CommerceProvider.SWIGGY_INSTAMART.packageName,
                message = "Swiggy Instamart is unavailable. Install Swiggy Instamart, Blinkit, or Zepto to use Beta grocery automation.",
                preferenceSource = PreferenceSource.DEFAULT,
                fallbackUsed = false,
                launchable = false,
            )
        }
    }

    private fun decisionForProvider(
        provider: CommerceProvider,
        source: PreferenceSource,
        installedApps: Set<InstalledCommerceApp>,
        fallbackUsed: Boolean,
    ): LaunchDecision {
        val installedApp = installedAppFor(provider, installedApps)
        val launchable = installedApp != null
        val message = when {
            fallbackUsed -> "Swiggy Instamart was unavailable. Opening ${provider.appName}."
            launchable -> "Opening ${provider.appName}"
            else -> "Could not open ${provider.appName}. Please open it manually and try again."
        }
        return LaunchDecision(
            selectedProvider = provider,
            appName = provider.appName,
            packageName = installedApp?.packageName ?: provider.packageName,
            message = message,
            preferenceSource = source,
            fallbackUsed = fallbackUsed,
            launchable = launchable,
        )
    }

    private fun installedAppFor(
        provider: CommerceProvider,
        installedApps: Set<InstalledCommerceApp>,
    ): InstalledCommerceApp? {
        return provider.packageAliases.firstNotNullOfOrNull { packageName ->
            installedApps.firstOrNull { it.provider == provider && it.packageName == packageName }
        }
    }

    private fun parseExplicitProvider(instruction: String?): CommerceProvider? {
        val normalized = normalize(instruction)
        if (normalized.isBlank()) return null

        return supportedProvidersInFallbackOrder.firstOrNull { provider ->
            provider.aliases.any { alias -> normalized.containsWord(alias) }
        }
    }

    private fun normalize(instruction: String?): String {
        return instruction
            ?.lowercase(Locale.US)
            ?.replace(Regex("[^a-z0-9 ]+"), " ")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()
    }

    private fun String.containsWord(word: String): Boolean {
        return Regex("(^|\\s)${Regex.escape(word)}(\\s|$)").containsMatchIn(this)
    }
}
