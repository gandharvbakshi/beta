package com.example.beta

object AppConfig {
    val backendBaseUrl: String = BuildConfig.BETA_BACKEND_BASE_URL.trim().trimEnd('/')
    val backendApiKey: String = BuildConfig.BETA_BACKEND_API_KEY.trim()

    private fun backendPath(path: String): String = "$backendBaseUrl/${path.trimStart('/')}"

    val feedbackUrl: String
        get() = backendPath("feedback")

    val swiggyConnectUrl: String
        get() = backendPath("swiggy/connect")

    val swiggyDisconnectUrl: String
        get() = backendPath("swiggy/disconnect")

    val swiggyStatusUrl: String
        get() = backendPath("swiggy/status")

    val swiggyCapabilitiesUrl: String
        get() = backendPath("swiggy/capabilities")

    val swiggyAddressesUrl: String
        get() = backendPath("swiggy/addresses")

    val swiggyRecommendationsUrl: String
        get() = backendPath("swiggy/recommendations")

    val swiggyCartPlanUrl: String
        get() = backendPath("swiggy/cart/plan")

    val swiggyCartApplyUrl: String
        get() = backendPath("swiggy/cart/apply")

    val isLocalBackend: Boolean
        get() = backendBaseUrl.contains("10.0.2.2") ||
            backendBaseUrl.contains("localhost") ||
            backendBaseUrl.contains("127.0.0.1")
}
