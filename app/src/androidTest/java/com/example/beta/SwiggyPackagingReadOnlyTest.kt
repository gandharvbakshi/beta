package com.example.beta

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.beta.automation.InstructionParser
import com.example.beta.automation.ParsedItem
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
 * Explicitly opted-in, read-only packaging diagnostic for the Swiggy Instamart MCP path.
 *
 * This probe only reads saved addresses and asks for one recommendation batch. It never
 * reads or changes the cart, checks out, places an order, applies a plan, or writes addresses.
 * Full recommendation responses are retained only in the app-private files directory.
 */
@RunWith(AndroidJUnit4::class)
class SwiggyPackagingReadOnlyTest {
    @Test
    fun packagingProbeUsesFixedQueriesWithoutMutation() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString(FLAG_NAME) == "true")
        assumeTrue(!AppConfig.isLocalBackend)
        assertTrue(
            "Hosted Swiggy backend key is required for the packaging read-only probe",
            AppConfig.backendApiKey.isNotBlank(),
        )

        val backendBaseUrl = resolveAllowedBackendUrl(arguments.getString(BACKEND_URL_ARGUMENT))
        val everyday = arguments.getString(EVERYDAY_ARGUMENT) == "true"
        val probeItems = if (everyday) everydayProbeItems() else FIXED_QUERIES.mapIndexed { index, query ->
            ParsedItem(
                rawText = query,
                query = query,
                strictMatchPhrase = STRICT_MATCH_PHRASES[index],
            )
        }
        val probeQueries = probeItems.map(::swiggyRecommendationQuery)
        val probeStrictMatchPhrases = probeItems.map { it.strictMatchPhrase }
        val probeRawCommands = if (everyday) EVERYDAY_RAW_COMMANDS else probeItems.map { it.rawText }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installationToken = SwiggyInstallationIdentity.installationToken(context)
        val requestLog = mutableListOf<String>()
        val client = okHttpClient(requestLog)

        val addressBody = fetchBody(
            client = client,
            url = "$backendBaseUrl/swiggy/addresses",
            installationToken = installationToken,
            operationLabel = "saved addresses",
        )
        val addressResponse = SwiggyMcpClient.parseAddressesResponse(addressBody)
        val selectedAddress = resolveRememberedSwiggyAddress(context, addressResponse.addresses).address
        assertTrue("Expected a non-blank last-used saved Swiggy address", selectedAddress.id.isNotBlank())

        val requestBody = buildRecommendationBody(
            addressId = selectedAddress.id,
            queries = probeQueries,
            strictMatchPhrases = probeStrictMatchPhrases,
        )
        assertRequestAlignment(requestBody, probeQueries, probeStrictMatchPhrases)
        val batchBody = postBody(
            client = client,
            url = "$backendBaseUrl/swiggy/recommendations/batch",
            installationToken = installationToken,
            body = requestBody,
            operationLabel = "packaging recommendations",
        )
        val recommendations = SwiggyMcpClient.parseRecommendationBatch(batchBody)
        assertResponseAlignment(recommendations, probeQueries)

        val observations = JSONArray()
        var candidateCountTotal = 0
        var matchedAndroidIdentityCountTotal = 0
        recommendations.forEachIndexed { index, result ->
            val item = probeItems[index]
            val matchedAndroidIdentityCount = result.candidates.count { candidate ->
                isSwiggyCandidateAllowed(item, candidate)
            }
            candidateCountTotal += result.candidates.size
            matchedAndroidIdentityCountTotal += matchedAndroidIdentityCount
            val countCompatibleCandidates = result.candidates.count { candidate ->
                isSwiggyCandidateCountCompatible(item, candidate)
            }
            val observation = JSONObject()
                .put("index", index + 1)
                .put("query", result.query ?: probeQueries[index])
                .put("strictMatchPhrase", item.strictMatchPhrase ?: JSONObject.NULL)
                .put("candidateCount", result.candidates.size)
                .put("matchedAndroidIdentityCount", matchedAndroidIdentityCount)
                .put("candidates", JSONArray().apply {
                    result.candidates.forEach { candidate ->
                        put(
                            JSONObject()
                                .put("spinId", candidate.spinId)
                                .put("skuId", candidate.skuId ?: JSONObject.NULL)
                                .put("label", candidate.label)
                                .put("variant", candidate.variant ?: JSONObject.NULL)
                                .put("subtitle", candidate.subtitle ?: JSONObject.NULL)
                                .put("suggested", candidate.suggested),
                        )
                    }
                })
                .put("suggested", result.suggested?.let(::candidateJson) ?: JSONObject.NULL)
                .put("warnings", JSONArray().apply {
                    if (result.requiresConfirmation) put("requiresConfirmation")
                    if (result.usualProductUnavailable) put("usualProductUnavailable")
                })
            if (everyday) {
                val usableCandidates = result.candidates.filter { candidate ->
                    isSwiggyCandidateAllowed(item, candidate) &&
                        isSwiggyCandidateCountCompatible(item, candidate)
                }
                val finalSuggestion = if (usableCandidates.isEmpty()) {
                    null
                } else {
                    swiggyDefaultSuggestion(item, usableCandidates, preferred = result.suggested)
                }
                observation
                    .put("rawCommand", probeRawCommands[index])
                    .put("requestedQuantity", item.quantity.toString())
                    .put("allowedIdentityCandidateCount", matchedAndroidIdentityCount)
                    .put("countCompatibleCandidateCount", countCompatibleCandidates)
                    .put("finalAcceptableCandidateCount", usableCandidates.size)
                    .put("rawProviderSuggested", result.suggested?.let(::candidateJson) ?: JSONObject.NULL)
                    .put("finalSuggestion", finalSuggestion?.let(::candidateJson) ?: JSONObject.NULL)
            }
            observations.put(observation)

            // Public logging is deliberately limited to indexed aggregate counts.
            Log.i(
                TAG,
                "SWIGGY_PACKAGING index=${index + 1} candidateCount=${result.candidates.size} " +
                    "matchedAndroidIdentityCount=$matchedAndroidIdentityCount",
            )
        }

        val outputFile = if (everyday) OUTPUT_EVERYDAY_FILE else OUTPUT_FILE
        val outputRawFile = if (everyday) OUTPUT_EVERYDAY_RAW_FILE else OUTPUT_RAW_FILE
        val report = JSONObject()
            .put("installationId", opaqueInstallationId(installationToken))
            .put("selectedAddressId", selectedAddress.id)
            .put("queryCount", probeQueries.size)
            .put("observations", observations)
        savePrivateArtifact(context, outputFile, report.toString(2))
        // This is the full provider response, retained privately for schema/warning diagnosis.
        savePrivateArtifact(context, outputRawFile, batchBody)

        recommendations.forEachIndexed { index, result ->
            val item = probeItems[index]
            assertTrue(
                "An incompatible identity candidate survived at query ${index + 1}",
                result.candidates.all { isSwiggyCandidateAllowed(item, it) },
            )
            if (everyday) {
                val usableCandidates = result.candidates.filter { candidate ->
                    isSwiggyCandidateAllowed(item, candidate) &&
                        isSwiggyCandidateCountCompatible(item, candidate)
                }
                val finalSuggestion = if (usableCandidates.isEmpty()) {
                    null
                } else {
                    swiggyDefaultSuggestion(item, usableCandidates, preferred = result.suggested)
                }
                assertTrue(
                    "The Android final suggestion is not count-compatible at query ${index + 1}",
                    finalSuggestion == null || (
                        isSwiggyCandidateAllowed(item, finalSuggestion) &&
                            isSwiggyCandidateCountCompatible(item, finalSuggestion)
                        ),
                )
            }
            // Stock is live, so absence is normally diagnostic, not a test
            // failure. This opt-in gate requires today's reported product
            // families to match, but never requires an unavailable 500 ml SKU.
            if (!everyday && arguments.getString("requireReportedProductMatches") == "true" && index > 0) {
                assertTrue("No current compatible candidate at query ${index + 1}", result.candidates.isNotEmpty())
            }
        }

        Log.i(
            TAG,
            "SWIGGY_PACKAGING_SUMMARY queryCount=${probeQueries.size} " +
                "candidateCountTotal=$candidateCountTotal " +
                "matchedAndroidIdentityCountTotal=$matchedAndroidIdentityCountTotal",
        )

        assertFalse(
            "The packaging probe unexpectedly attempted a cart, checkout, payment, order, or address mutation",
            requestLog.any(::isMutatingPath),
        )
        assertEquals(
            "Expected only the saved-address read and one recommendation batch call",
            listOf("GET /swiggy/addresses", "POST /swiggy/recommendations/batch"),
            requestLog,
        )
    }

    private fun resolveAllowedBackendUrl(argument: String?): String {
        val configuredDefault = AppConfig.backendBaseUrl.trim().trimEnd('/')
        require(configuredDefault in ALLOWED_BACKEND_URLS) {
            "The packaging probe requires the expected hosted Cloud Run backend"
        }
        val requested = argument?.trim()?.trimEnd('/') ?: configuredDefault
        require(requested in ALLOWED_BACKEND_URLS) {
            "backendUrl must be the ordinary hosted backend or the approved same-project canary"
        }
        return requested
    }

    private fun buildRecommendationBody(
        addressId: String,
        queries: List<String>,
        strictMatchPhrases: List<String?>,
    ): String {
        return JSONObject()
            .put("addressId", addressId)
            .put("queries", JSONArray().apply { queries.forEach(::put) })
            .put(
                "strictMatchPhrases",
                JSONArray().apply {
                    strictMatchPhrases.forEach { put(it ?: JSONObject.NULL) }
                },
            )
            .toString()
    }

    private fun assertRequestAlignment(
        body: String,
        expectedQueries: List<String>,
        expectedStrictMatchPhrases: List<String?>,
    ) {
        val root = JSONObject(body)
        val queries = root.optJSONArray("queries")
        val strictMatchPhrases = root.optJSONArray("strictMatchPhrases")
        assertTrue("Recommendation request query list is missing or mis-sized", queries?.length() == expectedQueries.size)
        assertTrue(
            "Recommendation request strict-match list is missing or mis-sized",
            strictMatchPhrases?.length() == expectedStrictMatchPhrases.size,
        )
        for (index in expectedQueries.indices) {
            assertTrue("Recommendation request query alignment failed at index ${index + 1}", queries?.optString(index) == expectedQueries[index])
            val strict = strictMatchPhrases?.opt(index)
            val expected = expectedStrictMatchPhrases[index]
            assertTrue(
                "Recommendation request strict-match alignment failed at index ${index + 1}",
                if (expected == null) strict == JSONObject.NULL || strict == null else strict == expected,
            )
        }
    }

    private fun assertResponseAlignment(recommendations: List<SwiggyMcpClient.Recommendations>, queries: List<String>) {
        assertTrue(
            "Expected one recommendation result per probe query",
            recommendations.size == queries.size,
        )
        recommendations.forEachIndexed { index, result ->
            assertTrue(
                "Recommendation response query alignment failed at index ${index + 1}",
                result.query == queries[index],
            )
        }
    }

    private fun everydayProbeItems(): List<ParsedItem> {
        return EVERYDAY_RAW_COMMANDS.mapIndexed { index, command ->
            val parsed = InstructionParser.parse(command)
            assertEquals("Expected one ParsedItem for everyday command ${index + 1}", 1, parsed.size)
            parsed.single()
        }
    }

    private fun candidateJson(candidate: SwiggyMcpClient.RecommendationCandidate): JSONObject {
        return JSONObject()
            .put("spinId", candidate.spinId)
            .put("skuId", candidate.skuId ?: JSONObject.NULL)
            .put("label", candidate.label)
            .put("variant", candidate.variant ?: JSONObject.NULL)
            .put("subtitle", candidate.subtitle ?: JSONObject.NULL)
            .put("suggested", candidate.suggested)
    }

    private fun savePrivateArtifact(context: Context, fileName: String, contents: String) {
        context.openFileOutput(fileName, Context.MODE_PRIVATE).use { output ->
            output.write(contents.toByteArray(Charsets.UTF_8))
        }
    }

    private fun opaqueInstallationId(token: String): String {
        val digestInput = "beta-installation-v1\u0000".toByteArray(Charsets.UTF_8) + decodeBase64Url(token)
        return MessageDigest.getInstance("SHA-256")
            .digest(digestInput)
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun decodeBase64Url(value: String): ByteArray {
        return android.util.Base64.decode(
            value,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
        )
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
            if (!response.isSuccessful) {
                logHttpError(operationLabel, response.code, body)
            }
            assertTrue(
                "Expected a successful GET for $operationLabel, got HTTP ${response.code} reason=${safeReason(body)}",
                response.isSuccessful,
            )
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
            if (!response.isSuccessful) {
                logHttpError(operationLabel, response.code, responseBody)
            }
            assertTrue(
                "Expected a successful POST for $operationLabel, got HTTP ${response.code} reason=${safeReason(responseBody)}",
                response.isSuccessful,
            )
            assertTrue("Expected a non-empty JSON response for $operationLabel", responseBody.isNotBlank())
            return responseBody
        }
    }

    private fun logHttpError(operationLabel: String, code: Int, body: String) {
        Log.e(TAG, "SWIGGY_PACKAGING_HTTP_ERROR operation=$operationLabel code=$code reason=${safeReason(body)}")
    }

    private fun safeReason(body: String): String {
        val raw = runCatching {
            val root = JSONObject(body)
            val detail = root.optJSONObject("detail")
            detail?.optString("reason")?.takeIf { it.isNotBlank() }
                ?: root.optString("reason").takeIf { it.isNotBlank() }
        }.getOrNull().orEmpty()
        return raw.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9_:-]"), "_")
            .take(64)
            .ifBlank { "unknown" }
    }

    private fun okHttpClient(requestLog: MutableList<String>): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request()
                val signature = "${request.method.uppercase(Locale.US)} ${request.url.encodedPath}"
                check(signature in ALLOWED_REQUESTS) {
                    "Unexpected network request route or method"
                }
                requestLog += signature
                chain.proceed(request)
            })
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    private fun isMutatingPath(request: String): Boolean {
        val signature = request.substringAfter(' ', request)
        return signature != "/swiggy/addresses" &&
            signature != "/swiggy/recommendations/batch" ||
            request.startsWith("POST /swiggy/cart/") ||
            request.contains("/checkout", ignoreCase = true) ||
            request.contains("/payment", ignoreCase = true) ||
            request.contains("/order", ignoreCase = true) ||
            request.contains("/address/", ignoreCase = true)
    }

    private companion object {
        const val FLAG_NAME = "swiggyPackagingProbe"
        const val BACKEND_URL_ARGUMENT = "backendUrl"
        const val EVERYDAY_ARGUMENT = "everyday"
        const val DEFAULT_BACKEND_URL = "https://beta-backend-staging-kvuem5t7mq-el.a.run.app"
        const val CANARY_BACKEND_URL = "https://forms-canary---beta-backend-staging-kvuem5t7mq-el.a.run.app"
        const val OUTPUT_FILE = "swiggy-packaging-read-only-observations.json"
        const val OUTPUT_RAW_FILE = "swiggy-packaging-read-only-recommendations.json"
        const val OUTPUT_EVERYDAY_FILE = "swiggy-packaging-read-only-everyday-observations.json"
        const val OUTPUT_EVERYDAY_RAW_FILE = "swiggy-packaging-read-only-everyday-recommendations.json"
        const val TAG = "BetaAgent"
        val ALLOWED_BACKEND_URLS = setOf(DEFAULT_BACKEND_URL, CANARY_BACKEND_URL)
        val ALLOWED_REQUESTS = setOf("GET /swiggy/addresses", "POST /swiggy/recommendations/batch")
        val FIXED_QUERIES = listOf(
            "500 ml milk tetra pack",
            "milk tetra pack",
            "popcorn butter ready made",
            "butter popcorn ready to eat",
            "dark chocolate",
            "potato chips",
            "keenwaa",
            "sesame paste",
        )
        val STRICT_MATCH_PHRASES = listOf<String?>(
            "500 ml milk tetra pack",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
        )
        val EVERYDAY_RAW_COMMANDS = listOf(
            "Order one samosa",
            "Order one tetra pack milk",
            "I want one lean milk",
            "One milk with less cream",
            "sugar-free ready-made tea",
            "tea without sugar",
            "sugar free",
            "tea no added sugar",
            "one butter popcorn ready made",
            "two dark chocolate",
        )
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()
    }
}
