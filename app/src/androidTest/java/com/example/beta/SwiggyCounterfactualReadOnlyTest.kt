package com.example.beta

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Explicitly opted-in, read-only counterfactual diagnostic.
 *
 * This test only reads saved addresses and a single recommendation batch. It never
 * mutates the cart, never checks out, never applies a plan, and never writes addresses.
 */
@RunWith(AndroidJUnit4::class)
class SwiggyCounterfactualReadOnlyTest {
    @Test
    fun inspectAlternativeBasketQueriesWithoutMutation() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString(FLAG_NAME) == "true")
        assumeTrue(!AppConfig.isLocalBackend)
        assertTrue("Hosted Swiggy backend key is required for the counterfactual check", AppConfig.backendApiKey.isNotBlank())

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installationToken = SwiggyInstallationIdentity.installationToken(context)
        val requestLog = mutableListOf<String>()
        val client = okHttpClient(requestLog)

        val addressBody = fetchBody(
            client = client,
            url = "${AppConfig.backendBaseUrl}/swiggy/addresses",
            installationToken = installationToken,
            operationLabel = "saved addresses",
        )
        val addressResponse = SwiggyMcpClient.parseAddressesResponse(addressBody)
        val selectedAddress = selectLastUsedAddress(context, addressResponse.addresses)
        assertTrue("Expected a last-selected saved address in Swiggy address intelligence prefs", selectedAddress != null)

        val queries = listOf("tahini", "sesame paste", "keenwaa", "edamame", "soybeans", "parchment", "baking paper", "butter paper")
        val batchBody = postBody(
            client = client,
            url = "${AppConfig.backendBaseUrl}/swiggy/recommendations/batch",
            installationToken = installationToken,
            body = buildRecommendationBody(requireNotNull(selectedAddress).id, queries),
            operationLabel = "counterfactual recommendations",
        )
        val recommendations = SwiggyMcpClient.parseRecommendationBatch(batchBody)

        assertEquals("Expected one recommendation result per fixed query", queries.size, recommendations.size)
        recommendations.forEachIndexed { index, result ->
            assertEquals("Expected query order to be preserved at index ${index + 1}", queries[index], result.query)
            if (queries[index] in setOf("sesame paste", "keenwaa")) {
                assertTrue("Unrelated candidate for ${queries[index]}", result.candidates.all {
                    isSwiggyCandidateAllowed(com.example.beta.automation.ParsedItem(rawText = queries[index], query = queries[index]), it)
                })
            }
        }

        val payload = JSONArray()
        recommendations.forEachIndexed { index, result ->
            val candidateArray = JSONArray()
            result.candidates.forEach { candidate ->
                candidateArray.put(
                    JSONObject()
                        .put("spinId", candidate.spinId)
                        .put("label", candidate.label)
                        .put("skuId", candidate.skuId ?: JSONObject.NULL),
                )
            }
            payload.put(
                JSONObject()
                    .put("query", result.query ?: queries[index])
                    .put("candidateCount", result.candidates.size)
                    .put("candidates", candidateArray)
                    .put(
                        "warnings",
                        if (result.requiresConfirmation) JSONArray().put("requiresConfirmation") else JSONArray(),
                    ),
            )
        }

        context.openFileOutput(OUTPUT_FILE, Context.MODE_PRIVATE).use { output ->
            output.write(payload.toString(2).toByteArray(Charsets.UTF_8))
        }

        val candidateCountTotal = recommendations.sumOf { it.candidates.size }
        val confirmationCount = recommendations.count { it.requiresConfirmation }
        val zeroCandidateQueries = recommendations.count { it.candidates.isEmpty() }
        assertFalse("The counterfactual test unexpectedly attempted a cart mutation path", requestLog.any { isMutatingPath(it) })
        assertEquals(
            "Expected only the saved-address read plus one recommendation batch call",
            listOf("GET /swiggy/addresses", "POST /swiggy/recommendations/batch"),
            requestLog,
        )
        assertTrue(
            "Expected the batch response to stay aligned with the fixed query list",
            recommendations.size == queries.size,
        )

        // Public logging only: aggregate counts and timing are okay; no raw addresses or candidate text.
        android.util.Log.i(
            "BetaAgent",
            "SWIGGY_COUNTERFACTUAL queryCount=${queries.size} candidateCountTotal=$candidateCountTotal " +
                "zeroCandidateQueries=$zeroCandidateQueries confirmationCount=$confirmationCount",
        )
    }

    private fun selectLastUsedAddress(context: Context, addresses: List<SwiggyMcpClient.SwiggyAddress>): SwiggyMcpClient.SwiggyAddress? {
        if (addresses.isEmpty()) return null
        val prefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        return addresses.mapNotNull { address ->
            val key = usageKey(address.id)
            val time = prefs.getLong("${key}_time", 0L)
            if (time > 0L) address to time else null
        }.maxByOrNull { it.second }?.first
    }

    private fun usageKey(addressId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(addressId.toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun buildRecommendationBody(addressId: String, queries: List<String>): String {
        return JSONObject()
            .put("addressId", addressId)
            .put("queries", JSONArray().apply { queries.forEach { put(it) } })
            .put("strictMatchPhrases", JSONArray().apply { queries.forEach { put(JSONObject.NULL) } })
            .toString()
    }

    private fun fetchBody(
        client: OkHttpClient,
        url: String,
        installationToken: String,
        operationLabel: String,
    ): String {
        val request = Request.Builder()
            .url(url)
            .header("x-beta-backend-key", AppConfig.backendApiKey)
            .header("x-beta-installation-token", installationToken)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            assertTrue("Expected a successful GET for $operationLabel, got HTTP ${response.code}", response.isSuccessful)
            assertTrue("Expected a non-empty JSON response for $operationLabel", body.isNotBlank())
            return body
        }
    }

    private fun postBody(
        client: OkHttpClient,
        url: String,
        installationToken: String,
        body: String,
        operationLabel: String,
    ): String {
        val request = Request.Builder()
            .url(url)
            .header("x-beta-backend-key", AppConfig.backendApiKey)
            .header("x-beta-installation-token", installationToken)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            assertTrue("Expected a successful POST for $operationLabel, got HTTP ${response.code}", response.isSuccessful)
            assertTrue("Expected a non-empty JSON response for $operationLabel", responseBody.isNotBlank())
            return responseBody
        }
    }

    private fun okHttpClient(requestLog: MutableList<String>): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request()
                val signature = "${request.method.uppercase(Locale.US)} ${request.url.encodedPath}"
                check(signature == "GET /swiggy/addresses" || signature == "POST /swiggy/recommendations/batch") {
                    "Unexpected network request: $signature"
                }
                requestLog += signature
                chain.proceed(request)
            })
            .retryOnConnectionFailure(false)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private fun isMutatingPath(request: String): Boolean {
        val path = request.substringAfter(' ', request)
        return path == "/swiggy/cart/apply" ||
            path.startsWith("/swiggy/cart/") ||
            path.contains("/checkout") ||
            path.contains("/payment") ||
            path.contains("/address/", ignoreCase = true)
    }

    private companion object {
        const val FLAG_NAME = "liveSwiggyCounterfactual"
        const val PREFERENCES_NAME = "swiggy_address_intelligence"
        const val OUTPUT_FILE = "swiggy-counterfactual-observations.json"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()
    }
}
