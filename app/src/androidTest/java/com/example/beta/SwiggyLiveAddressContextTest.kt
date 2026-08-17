package com.example.beta

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Explicitly opted-in, read-only verification of hosted current-cart address context. */
@RunWith(AndroidJUnit4::class)
class SwiggyLiveAddressContextTest {
    @Test
    fun currentCartAddressIsMarkedInSavedAddresses() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString(FLAG_NAME) == "true")

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val request = Request.Builder()
            .url("${AppConfig.backendBaseUrl}/swiggy/addresses")
            .header("x-beta-backend-key", AppConfig.backendApiKey)
            .header("x-beta-installation-token", SwiggyInstallationIdentity.installationToken(context))
            .get()
            .build()
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val safeFailureReason = runCatching {
                val root = JSONObject(body)
                val detail = root.optJSONObject("detail")
                detail?.optString("reason")
                    ?.takeIf { it.matches(Regex("[a-z0-9_]+")) }
                    ?: root.optString("reason").takeIf { it.matches(Regex("[a-z0-9_]+")) }
            }.getOrNull()
            assertTrue(
                "Expected hosted address response, got HTTP ${response.code}" +
                    (safeFailureReason?.let { " reason=$it" } ?: ""),
                response.isSuccessful,
            )
            val data = JSONObject(body).getJSONObject("data")
            val addresses = data.getJSONArray("addresses")
            val currentCartAddressId = data.optString("currentCartAddressId").trim()
            assertTrue("Expected at least one saved Swiggy address", addresses.length() > 0)
            assertTrue("Expected current cart address context for the non-empty live cart", currentCartAddressId.isNotBlank())
            val matches = (0 until addresses.length()).count { index ->
                addresses.optJSONObject(index)?.optString("id") == currentCartAddressId
            }
            assertEquals("Expected exactly one saved address to own the current cart", 1, matches)
        }
    }

    private companion object {
        const val FLAG_NAME = "liveSwiggyAddressContext"
    }
}
