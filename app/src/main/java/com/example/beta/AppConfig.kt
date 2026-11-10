package com.example.beta

import android.content.Context
import java.net.URI

object AppConfig {
    @Volatile private var endpoint: BackendEndpointConfig? = null
    private var installed = false

    // Called before Application.onCreate/content providers. Invalid assets never choose a fallback host.
    @Synchronized internal fun install(context: Context) {
        if (installed) return
        endpoint = runCatching {
            context.assets.open(BackendEndpointConfig.ASSET_NAME).use(BackendEndpointConfig::read)
        }.getOrNull()
        installed = true
    }

    val backendBaseUrl: String
        get() = endpoint?.baseUrl ?: throw BackendConfigurationUnavailableException()
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
        get() = URI(backendBaseUrl).host in setOf("10.0.2.2", "localhost", "127.0.0.1")
}
