package com.example.beta

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.beta.automation.Quantity
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
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

/**
 * Explicitly opted-in, read-only diagnostic for elderly-style Swiggy Instamart baskets.
 *
 * This test only reads saved addresses and recommendation batches. It never mutates the cart,
 * never checks out, and never writes addresses. It records only sanitized aggregates to the
 * app-private files directory for later review.
 */
@RunWith(AndroidJUnit4::class)
class SwiggyElderlyReadOnlyTest {
    @Test
    fun validateElderlyBasketsWithoutMutation() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString(FLAG_NAME) == "true")
        assumeTrue(!AppConfig.isLocalBackend)
        assertTrue(
            "Hosted Swiggy backend key is required for the read-only elderly basket check",
            AppConfig.backendApiKey.isNotBlank(),
        )

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val backendBaseUrl = arguments.getString("betaReadOnlyBaseUrl") ?: AppConfig.backendBaseUrl
        require(backendBaseUrl == AppConfig.backendBaseUrl || backendBaseUrl == "https://elderly-canary---beta-backend-staging-kvuem5t7mq-el.a.run.app")
        val installationToken = SwiggyInstallationIdentity.installationToken(context)
        val requestLog = mutableListOf<String>()
        val client = okHttpClient(requestLog)
        val cases = elderlyBasketCases()
        val observations = mutableListOf<JSONObject>()
        val issues = mutableListOf<String>()

        val addressBody = fetchBody(
            client = client,
            url = "$backendBaseUrl/swiggy/addresses",
            installationToken = installationToken,
            operationLabel = "saved Swiggy addresses",
        )
        val addressResponse = SwiggyMcpClient.parseAddressesResponse(addressBody)
        val activeAddressId = addressResponse.currentCartAddressId
            ?.takeIf { it.isNotBlank() }
            ?: addressResponse.addresses.firstOrNull()?.id
        assertTrue(
            "Expected either the current cart address or at least one saved Swiggy address",
            !activeAddressId.isNullOrBlank(),
        )

        val addressId = requireNotNull(activeAddressId)

        cases.forEach { case ->
            val preparedItems = prepareSwiggyMcpItems(case.instruction, lookup = { null })
            assertListEquals(
                message = "Case ${case.caseId} should parse the expected elderly instruction in order",
                expected = case.expectedItems.map { it.query },
                actual = preparedItems.map { it.query },
                issues = issues,
            )
            assertListEquals(
                message = "Case ${case.caseId} should preserve the expected quantities",
                expected = case.expectedItems.map { it.quantity },
                actual = preparedItems.map { it.quantity },
                issues = issues,
            )

            val queries = preparedItems.map(::swiggyRecommendationQuery)
            val caseStart = System.nanoTime()
            val responseBody = postBody(
                client = client,
                url = "$backendBaseUrl/swiggy/recommendations/batch",
                installationToken = installationToken,
                body = buildRecommendationBody(addressId, queries),
                operationLabel = "recommendation batch ${case.caseId}",
            )
            val parsed = SwiggyMcpClient.parseRecommendationBatch(responseBody)
            val elapsedMs = ((System.nanoTime() - caseStart) / 1_000_000.0).roundToLong()

            if (parsed.size != queries.size) {
                issues += "Case ${case.caseId} returned ${parsed.size} results for ${queries.size} queries."
            }
            if (preparedItems.size != case.expectedItems.size) {
                issues += "Case ${case.caseId} parsed ${preparedItems.size} items for ${case.expectedItems.size} expected items."
            }

            var topMatchCount = 0
            var candidateSetMatchCount = 0
            var clarificationCount = 0

            parsed.forEachIndexed { index, recommendation ->
                val expected = case.expectedItems.getOrNull(index)
                val requestQuery = queries.getOrNull(index)
                if (expected == null) {
                    issues += "Case ${case.caseId} has more recommendation results than expected items at index ${index + 1}."
                    return@forEachIndexed
                }
                if (requestQuery == null) {
                    issues += "Case ${case.caseId} has more recommendation results than queries at index ${index + 1}."
                    return@forEachIndexed
                }
                val preparedItem = preparedItems.getOrNull(index)
                if (preparedItem == null) {
                    issues += "Case ${case.caseId} has more recommendation results than prepared items at index ${index + 1}."
                    return@forEachIndexed
                }
                val responseQuery = recommendation.query?.takeIf { it.isNotBlank() }
                if (responseQuery == null) {
                    issues += "Case ${case.caseId} query ${index + 1} returned no echoed query."
                } else if (responseQuery != requestQuery) {
                    issues += "Case ${case.caseId} query ${index + 1} changed from \"$requestQuery\" to \"$responseQuery\"."
                }

                if (recommendation.candidates.isEmpty() && !recommendation.requiresConfirmation) {
                    issues += "Case ${case.caseId} query ${index + 1} had no candidates but did not require confirmation."
                }

                val topSuggestion = recommendation.candidates.firstOrNull()
                val topSuggestionMatches = topSuggestion?.let {
                    matchesTokenGroups(it.label, expected.expectedTokenGroups)
                } == true
                val candidateSetHasExpected = recommendation.candidates.any {
                    matchesTokenGroups(it.label, expected.expectedTokenGroups)
                }
                val needsQuestion = swiggySuggestionNeedsReview(preparedItem, recommendation, recommendation.suggested != null)

                if (!topSuggestionMatches && !needsQuestion) {
                    issues += "Case ${case.caseId} query ${index + 1} had a wrong top suggestion but did not require confirmation."
                }

                if (expected.requiresClarification) {
                    if (!needsQuestion) {
                        issues += "Case ${case.caseId} query ${index + 1} should require clarification."
                    }
                }

                if (expected.expectedTokenGroups.isNotEmpty() && !candidateSetHasExpected) {
                    issues += "Case ${case.caseId} query ${index + 1} produced no candidate matching the expected token groups."
                }
                if (expected.maxParserConfidenceExclusive != null &&
                    preparedItem.parserConfidence >= expected.maxParserConfidenceExclusive
                ) {
                    issues += "Case ${case.caseId} query ${index + 1} parser confidence ${preparedItem.parserConfidence} was not below ${expected.maxParserConfidenceExclusive}."
                }

                if (topSuggestionMatches) topMatchCount += 1
                if (candidateSetHasExpected) candidateSetMatchCount += 1
                if (recommendation.requiresConfirmation) clarificationCount += 1

                Log.i(
                    "BetaAgent",
                    "SWIGGY_ELDERLY_CASE caseId=${case.caseId} index=${index + 1} " +
                        "topSuggestionMatches=$topSuggestionMatches candidateSetHasExpected=$candidateSetHasExpected " +
                        "requiresConfirmation=${recommendation.requiresConfirmation} candidateCount=${recommendation.candidates.size}",
                )
            }

            val candidateCounts = parsed.map { it.candidates.size }
            observations += JSONObject()
                .put("caseId", case.caseId)
                .put("queryCount", queries.size)
                .put("resultCount", parsed.size)
                .put("candidateCountMin", candidateCounts.minOrNull() ?: 0)
                .put("candidateCountMax", candidateCounts.maxOrNull() ?: 0)
                .put("candidateCountMean", if (candidateCounts.isNotEmpty()) candidateCounts.average() else 0.0)
                .put("requiresConfirmationCount", clarificationCount)
                .put("topSuggestionMatchCount", topMatchCount)
                .put("candidateSetHasExpectedCount", candidateSetMatchCount)
                .put("latencyMs", elapsedMs)

            Log.i(
                "BetaAgent",
                "SWIGGY_ELDERLY_CASE_SUMMARY caseId=${case.caseId} queries=${queries.size} " +
                    "results=${parsed.size} candidateCountMin=${candidateCounts.minOrNull() ?: 0} " +
                    "candidateCountMax=${candidateCounts.maxOrNull() ?: 0} requiresConfirmationCount=$clarificationCount " +
                    "topSuggestionMatchCount=$topMatchCount candidateSetHasExpectedCount=$candidateSetMatchCount latencyMs=$elapsedMs",
            )
        }

        val payload = JSONArray()
        observations.forEach { payload.put(it) }
        context.openFileOutput(OUTPUT_FILE, Context.MODE_PRIVATE).use { output ->
            output.write(payload.toString(2).toByteArray(Charsets.UTF_8))
        }

        assertFalse("The live read-only test unexpectedly attempted a cart mutation path", requestLog.any { isMutatingPath(it) })
        assertTrue(
            "Expected one saved-address read plus one recommendation batch per case",
            requestLog.count { it == "GET /swiggy/addresses" } == 1 &&
                requestLog.count { it == "POST /swiggy/recommendations/batch" } == cases.size,
        )
        assertTrue(issues.joinToString(separator = "\n").ifBlank { "All elderly read-only cases passed." }, issues.isEmpty())
    }

    private fun elderlyBasketCases(): List<ElderlyBasketCase> {
        return listOf(
            ElderlyBasketCase(
                caseId = "basket-5-regular",
                instruction = "milk, bread, eggs, banana, coriander leaves",
                expectedItems = listOf(
                    ExpectedItem("milk", Quantity.Default, listOf(listOf("milk"))),
                    ExpectedItem("bread", Quantity.Default, listOf(listOf("bread"))),
                    ExpectedItem("eggs", Quantity.Default, listOf(listOf("egg", "eggs"))),
                    ExpectedItem("banana", Quantity.Default, listOf(listOf("banana"))),
                    ExpectedItem("coriander leaves", Quantity.Default, listOf(listOf("coriander", "dhaniya"))),
                ),
            ),
            ElderlyBasketCase(
                caseId = "basket-7-brand-generic",
                instruction = "2 milk, amul buttr, aashirvad aata, mozrella, keen waa, dabur honey, woh cough wali goli",
                expectedItems = listOf(
                    ExpectedItem("milk", Quantity.Count(2), listOf(listOf("milk"))),
                    ExpectedItem("amul buttr", Quantity.Default, listOf(listOf("amul"), listOf("butter", "buttr"))),
                    ExpectedItem("aashirvad atta", Quantity.Default, listOf(listOf("aashirvad", "aashirvaad"), listOf("atta", "aata", "flour"))),
                    ExpectedItem("mozrella", Quantity.Default, listOf(listOf("mozzarella", "mozrella", "cheese"))),
                    ExpectedItem("quinoa", Quantity.Default, listOf(listOf("quinoa")), requiresClarification = true, maxParserConfidenceExclusive = 1.0f),
                    ExpectedItem("dabur honey", Quantity.Default, listOf(listOf("dabur"), listOf("honey"))),
                    ExpectedItem("woh cough wali goli", Quantity.Default, emptyList(), requiresClarification = true),
                ),
            ),
            ElderlyBasketCase(
                caseId = "basket-10-unusual",
                instruction = "keen waa, tahini, edamame, agar agar, parchment, paper napkin, bhujia, curd, soap, orange",
                expectedItems = listOf(
                    ExpectedItem("quinoa", Quantity.Default, listOf(listOf("quinoa")), requiresClarification = true, maxParserConfidenceExclusive = 1.0f),
                    ExpectedItem("tahini", Quantity.Default, listOf(listOf("tahini"))),
                    ExpectedItem("edamame", Quantity.Default, listOf(listOf("edamame"))),
                    ExpectedItem("agar agar", Quantity.Default, listOf(listOf("agar", "agar agar"))),
                    ExpectedItem("parchment", Quantity.Default, listOf(listOf("parchment"))),
                    ExpectedItem("paper napkin", Quantity.Default, listOf(listOf("paper", "napkin", "tissue"))),
                    ExpectedItem("bhujia", Quantity.Default, listOf(listOf("bhujia", "namkeen"))),
                    ExpectedItem("curd", Quantity.Default, listOf(listOf("curd", "yogurt"))),
                    ExpectedItem("soap", Quantity.Default, listOf(listOf("soap"))),
                    ExpectedItem("orange", Quantity.Default, listOf(listOf("orange"))),
                ),
            ),
        )
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
            assertTrue(
                "Expected a successful POST for $operationLabel, got HTTP ${response.code}",
                response.isSuccessful,
            )
            assertTrue("Expected a non-empty JSON response for $operationLabel", responseBody.isNotBlank())
            return responseBody
        }
    }

    private fun buildRecommendationBody(addressId: String, queries: List<String>): String {
        return JSONObject()
            .put("addressId", addressId)
            .put("queries", JSONArray().apply { queries.forEach { put(it) } })
            .put("strictMatchPhrases", JSONArray().apply { queries.forEach { put(JSONObject.NULL) } })
            .toString()
    }

    private fun matchesTokenGroups(label: String, tokenGroups: List<List<String>>): Boolean {
        if (tokenGroups.isEmpty()) return false
        val normalizedLabel = normalizeForComparison(label)
        return tokenGroups.all { group ->
            group.asSequence()
                .map { normalizeForComparison(it) }
                .filter(String::isNotBlank)
                .any { normalizedLabel.contains(it) }
        }
    }

    private fun normalizeForComparison(value: String): String {
        return value
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun isMutatingPath(request: String): Boolean {
        val path = request.substringAfter(' ', request)
        return path == "/swiggy/cart/apply" ||
            path.startsWith("/swiggy/cart/") ||
            path.contains("/checkout") ||
            path.contains("/payment") ||
            path.contains("/address/", ignoreCase = true)
    }

    private fun <T> assertListEquals(
        message: String,
        expected: List<T>,
        actual: List<T>,
        issues: MutableList<String>,
    ) {
        if (expected != actual) {
            issues += "$message Expected=$expected Actual=$actual"
        }
    }

    private data class ElderlyBasketCase(
        val caseId: String,
        val instruction: String,
        val expectedItems: List<ExpectedItem>,
    )

    private data class ExpectedItem(
        val query: String,
        val quantity: Quantity,
        val expectedTokenGroups: List<List<String>>,
        val requiresClarification: Boolean = false,
        val maxParserConfidenceExclusive: Float? = null,
    )

    private companion object {
        const val FLAG_NAME = "liveSwiggyElderlyBaskets"
        const val OUTPUT_FILE = "swiggy-elderly-read-only-observations.json"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()
    }
}
