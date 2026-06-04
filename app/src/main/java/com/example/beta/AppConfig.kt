package com.example.beta

object AppConfig {
    val backendBaseUrl: String = BuildConfig.BETA_BACKEND_BASE_URL.trim().trimEnd('/')
    val backendApiKey: String = BuildConfig.BETA_BACKEND_API_KEY.trim()

    val analyzeScreenshotUrl: String
        get() = "$backendBaseUrl/analyze-screenshot"

    val feedbackUrl: String
        get() = "$backendBaseUrl/feedback"

    val isLocalBackend: Boolean
        get() = backendBaseUrl.contains("10.0.2.2") ||
            backendBaseUrl.contains("localhost") ||
            backendBaseUrl.contains("127.0.0.1")
}
