package com.example.beta

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicitly opted-in, read-only cleaner diagnostic.
 *
 * This test only reads saved addresses and a single recommendation batch. It never
 * mutates the cart, never checks out, never applies a plan, and never writes prefs.
 */
@RunWith(AndroidJUnit4::class)
class SwiggyCleanerReadOnlyTest {
    @Test
    fun cleanerReadOnlyProbeUsesExactlyThreeQueriesAndPostsBatchOnly() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Set $FLAG_NAME=true to opt in to the live probe.", arguments.getString(FLAG_NAME) == "true")
        check(AppConfig.backendApiKey.isNotBlank()) {
            "Hosted Swiggy backend key is required for the cleaner read-only probe."
        }
        check(!AppConfig.isLocalBackend) {
            "The cleaner read-only probe must use the hosted backend."
        }
        check(AppConfig.backendBaseUrl.isNotBlank()) {
            "The cleaner read-only probe requires a validated backend base URL."
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installationToken = SwiggyInstallationIdentity.installationToken(context)
        val requestLog = mutableListOf<String>()
        val client = okHttpClient(requestLog)

        val addressBody = fetchBody(
            client = client,
            url = AppConfig.backendBaseUrl + "/swiggy/addresses",
            installationToken = installationToken,
            operationLabel = "saved addresses",
        )
        val addressResponse = SwiggyMcpClient.parseAddressesResponse(addressBody)
        assertTrue("Expected at least one saved address for the cleaner read-only probe.", addressResponse.addresses.isNotEmpty())
        val selectedAddressId = resolveRememberedSwiggyAddress(context, addressResponse.addresses).address.id
        assertTrue("Expected a non-blank remembered saved address id.", selectedAddressId.isNotBlank())

        val batchBody = postBody(
            client = client,
            url = AppConfig.backendBaseUrl + "/swiggy/recommendations/batch",
            installationToken = installationToken,
            body = buildRecommendationBody(selectedAddressId, EXACT_CLEANER_QUERIES),
            operationLabel = "cleaner recommendations",
        )
        assertTrue("Cleaner probe must call the batch endpoint only.", batchBody.isNotBlank())

        val sanitized = summarizeBatchResponse(batchBody)
        assertEquals(EXACT_CLEANER_QUERIES, sanitized.queries)
        assertEquals(EXACT_CLEANER_QUERIES.size, sanitized.candidateDisplayNames.size)
        sanitized.candidateDisplayNames.flatten().forEach { candidate ->
            assertTrue(candidate.isNotBlank())
            assertTrue(!candidate.contains("address", ignoreCase = true))
            assertTrue(!candidate.contains("token", ignoreCase = true))
        }

        savePrivateArtifact(context, "swiggy-cleaner-probe-response.json", batchBody)
        savePrivateArtifact(context, "swiggy-cleaner-probe-summary.json", sanitized.toJson())

        assertEquals(
            listOf("GET /swiggy/addresses", "POST /swiggy/recommendations/batch"),
            requestLog,
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

    private fun buildRecommendationBody(addressId: String, queries: List<String>): String {
        val strictMatchPhrases = List(queries.size) { null as String? }
        return JSONObject()
            .put("addressId", addressId)
            .put("queries", JSONArray().apply { queries.forEach(::put) })
            .put(
                "strictMatchPhrases",
                JSONArray().apply { strictMatchPhrases.forEach { put(it ?: JSONObject.NULL) } },
            )
            .toString()
    }

    private fun summarizeBatchResponse(rawBody: String): CleanerProbeSummary {
        val results = SwiggyMcpClient.parseRecommendationBatch(rawBody)
        val queries = mutableListOf<String>()
        val candidateDisplayNames = mutableListOf<List<String>>()
        results.forEach { result ->
            queries += result.query.orEmpty()
            candidateDisplayNames += result.candidates.map { it.label }
        }
        return CleanerProbeSummary(queries, candidateDisplayNames)
    }

    private fun savePrivateArtifact(context: Context, fileName: String, contents: String) {
        val dir = File(context.filesDir, "swiggy-cleaner-probe").apply { mkdirs() }
        File(dir, fileName).writeText(contents)
    }

    private data class CleanerProbeSummary(
        val queries: List<String>,
        val candidateDisplayNames: List<List<String>>,
    ) {
        fun toJson(): String {
            val root = JSONObject()
                .put("queries", JSONArray(queries))
                .put(
                    "candidateDisplayNames",
                    JSONArray().apply {
                        candidateDisplayNames.forEach { candidates ->
                            put(JSONArray(candidates))
                        }
                    },
                )
            return root.toString(2)
        }
    }

    private companion object {
        private const val FLAG_NAME = "liveCleanerProbe"
        private val EXACT_CLEANER_QUERIES = listOf(
            "harpic toilet cleaner",
            "harpic original toilet cleaner",
            "harpic",
        )
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
