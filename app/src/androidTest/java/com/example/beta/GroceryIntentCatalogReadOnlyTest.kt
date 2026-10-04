package com.example.beta

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.beta.automation.ParsedItem
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Explicitly opted-in hosted intent-to-catalog diagnostic. It reads connection/address data and
 * recommendations only; it never reads or changes the cart, checks out, or mutates an address.
 * Results are aggregate-only and saved in app-private storage. A catalog candidate is not proof
 * that the requested product is correct; missing matches are reported as unresolved, not failed.
 */
@RunWith(AndroidJUnit4::class)
class GroceryIntentCatalogReadOnlyTest {
    @Test
    fun checksOptedInIntentThroughReadOnlyCatalogWithoutMutation() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString(FLAG) == "true")
        assertTrue("Use only the configured hosted Beta backend", !AppConfig.isLocalBackend)
        assertTrue("Hosted backend key is required", AppConfig.backendApiKey.isNotBlank())

        val configured = AppConfig.backendBaseUrl.trim().trimEnd('/')
        val baseUrl = arguments.getString("betaIntentBaseUrl")?.trim()?.trimEnd('/') ?: configured
        require(baseUrl == configured || baseUrl == INTENT_CANARY_URL) { "Unexpected intent catalogue backend" }
        require(URI(baseUrl).scheme == "https" && !URI(baseUrl).host.isNullOrBlank()) {
            "Intent catalogue diagnostic requires the configured HTTPS backend"
        }
        val cases = buildList {
            add(ProbeCase("everyday-3", EVERYDAY_INSTRUCTION, everydayExpected()))
            add(ProbeCase("synthetic-15", SYNTHETIC_15_INSTRUCTION, synthetic15Expected()))
            if (arguments.getString(INCLUDE_20_FLAG) == "true") {
                add(ProbeCase("synthetic-20", SYNTHETIC_20_INSTRUCTION, synthetic20Expected()))
            }
        }
        require(cases.size in 2..3)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installationToken = SwiggyInstallationIdentity.installationToken(context)
        val requestLog = mutableListOf<Pair<String, String>>()
        val boundedClient = client(baseUrl, requestLog, 15)
        val catalogueClient = client(baseUrl, requestLog, 90)

        val status = SwiggyMcpClient.parseStatus(
            requestJson(boundedClient, baseUrl, installationToken, "GET", STATUS_PATH),
        )
        assertEquals("Swiggy MCP must already be connected", SwiggyMcpClient.ConnectionState.READY, status.state)

        val addressResponse = SwiggyMcpClient.parseAddressesResponse(
            requestJson(catalogueClient, baseUrl, installationToken, "GET", ADDRESSES_PATH),
        )
        val remembered = resolveRememberedSwiggyAddress(context, addressResponse.addresses)
        assertRememberedAddressMatchesCurrentCart(
            currentCartAddressId = addressResponse.currentCartAddressId,
            rememberedAddressId = remembered.address.id,
        )
        assertTrue("Remembered saved address must be present", remembered.address.id.isNotBlank())

        val privateObservations = JSONArray()
        savePrivate(context, RESULT_FILE, JSONObject().put("cases", privateObservations).toString())
        cases.forEach { probe ->
            val intentStart = System.nanoTime()
            val intentBody = requestJson(
                boundedClient,
                baseUrl,
                installationToken,
                "POST",
                INTENT_PATH,
                JSONObject().put("instruction", probe.instruction),
            )
            val intentLatencyMs = elapsedMs(intentStart)
            val response = GroceryIntentResponse.parse(intentBody, probe.instruction)
            val parsedItems: List<ParsedItem> = response.draft.toParsedItems()
            assertEquals("Typed grocery items must pass the app's exact input validation", null,
                swiggyMcpItemValidationMessage("", parsedItems))
            assertExpectedIntentItems(probe, response.draft.items)

            val coverageResiduals = uncoveredGroceryIntentText(
                probe.instruction,
                response.draft.items.map { it.sourceText },
            )
            val queries = parsedItems.map(::swiggyRecommendationQuery)
            val strictMatchPhrases = parsedItems.map { it.strictMatchPhrase }
            require(queries.size == parsedItems.size && queries.size in 1..20)
            val requestBody = recommendationBody(remembered.address.id, queries, strictMatchPhrases)
            val batchStart = System.nanoTime()
            val batchBody = requestJson(
                catalogueClient,
                baseUrl,
                installationToken,
                "POST",
                RECOMMENDATIONS_PATH,
                requestBody,
            )
            val catalogueLatencyMs = elapsedMs(batchStart)
            val recommendations = SwiggyMcpClient.parseRecommendationBatch(batchBody)
            assertEquals("One catalogue result is required per generated query", queries.size, recommendations.size)

            val unresolvedIndices = mutableListOf<Int>()
            var matchedCount = 0
            var candidateCount = 0
            var identityPassCount = 0
            var quantityCompatibleCount = 0
            var identityRejectedCount = 0
            var quantityRejectedCount = 0
            val quantityRejectReasons = mutableMapOf<String, Int>()
            recommendations.forEachIndexed { index, result ->
                val item = parsedItems[index]
                candidateCount += result.candidates.size
                val identityCandidates = result.candidates.filter { isSwiggyCandidateAllowed(item, it) }
                identityPassCount += identityCandidates.size
                identityRejectedCount += result.candidates.size - identityCandidates.size
                val eligible = identityCandidates.filter { isSwiggyCandidateCountCompatible(item, it) }
                quantityCompatibleCount += eligible.size
                val quantityRejected = identityCandidates.filterNot { isSwiggyCandidateCountCompatible(item, it) }
                quantityRejectedCount += quantityRejected.size
                quantityRejected.forEach { candidate ->
                    val reason = quantityRejectionReason(item, candidate)
                    quantityRejectReasons[reason] = (quantityRejectReasons[reason] ?: 0) + 1
                }
                val chosen = eligible.takeIf { it.isNotEmpty() }?.let { candidates ->
                    swiggyDefaultSuggestion(item, candidates, preferred = result.suggested)
                }
                assertTrue(
                    "Android-selected suggestion must satisfy identity and quantity filters at index ${index + 1}",
                    chosen == null || (
                        eligible.any { it.spinId == chosen.spinId } &&
                            isSwiggyCandidateAllowed(item, chosen) &&
                            isSwiggyCandidateCountCompatible(item, chosen)
                        ),
                )
                if (chosen == null || response.draft.items[index].needsClarification) {
                    unresolvedIndices += index + 1
                } else {
                    matchedCount += 1
                }
            }
            Log.i(
                "BetaAgent",
                "intent_catalog_readonly case=${probe.caseId} items=${parsedItems.size} candidates=$candidateCount " +
                    "identity_pass=$identityPassCount identity_rejected=$identityRejectedCount " +
                    "quantity_compatible=$quantityCompatibleCount quantity_rejected=$quantityRejectedCount " +
                    "quantity_reject_reasons=$quantityRejectReasons unresolved=${unresolvedIndices.size}",
            )

            // Residual conversational text cannot be assigned safely to one item. Flag the whole
            // basket for combined review instead of silently treating the residual as covered.
            if (coverageResiduals.isNotEmpty()) {
                unresolvedIndices.clear()
                unresolvedIndices += parsedItems.indices.map { it + 1 }
            }
            privateObservations.put(
                JSONObject()
                    .put("caseId", probe.caseId)
                    .put("model", response.model)
                    .put("itemCount", parsedItems.size)
                    .put("matchedCount", matchedCount)
                    .put("unresolvedIndices", JSONArray(unresolvedIndices))
                    .put("candidateCount", candidateCount)
                    .put("identityPassCount", identityPassCount)
                    .put("identityRejectedCount", identityRejectedCount)
                    .put("quantityCompatibleCount", quantityCompatibleCount)
                    .put("quantityRejectedCount", quantityRejectedCount)
                    .put("quantityRejectReasons", JSONObject().apply {
                        quantityRejectReasons.forEach { (reason, count) -> put(reason, count) }
                    })
                    .put("coverageResidualCount", coverageResiduals.size)
                    .put("intentLatencyMs", intentLatencyMs)
                    .put("catalogueLatencyMs", catalogueLatencyMs),
            )
            savePrivate(context, RESULT_FILE, JSONObject().put("cases", privateObservations).toString(2))
        }

        savePrivate(context, RESULT_FILE, JSONObject().put("cases", privateObservations).toString(2))
        val expectedCalls = buildList {
            add("GET" to STATUS_PATH)
            add("GET" to ADDRESSES_PATH)
            cases.forEach {
                add("POST" to INTENT_PATH)
                add("POST" to RECOMMENDATIONS_PATH)
            }
        }
        assertEquals("Only bounded status, address, intent, and recommendation calls are allowed", expectedCalls, requestLog)
    }

    private fun assertExpectedIntentItems(probe: ProbeCase, actual: List<GroceryIntentItem>) {
        assertEquals("${probe.caseId} item count", probe.expected.size, actual.size)
        probe.expected.forEach { expected ->
            val item = actual.singleOrNull { it.name.contains(expected.identity, ignoreCase = true) }
            assertNotNull("${probe.caseId} must retain typed ${expected.identity} intent", item)
            requireNotNull(item)
            assertTrue(
                "${probe.caseId} ${expected.identity} quantity",
                item.quantity?.compareTo(BigDecimal(expected.quantity)) == 0,
            )
            assertTrue(
                "${probe.caseId} ${expected.identity} quantity unit must remain count/pack",
                item.quantityUnit in expected.acceptableUnits,
            )
        }
    }

    /** Diagnostic categories only; candidate labels are inspected in memory and never recorded. */
    private fun quantityRejectionReason(item: ParsedItem, candidate: SwiggyMcpClient.RecommendationCandidate): String {
        if (item.quantity is com.example.beta.automation.Quantity.Weight ||
            item.quantity is com.example.beta.automation.Quantity.Volume
        ) return "measured_pack_missing_or_not_exactly_divisible"
        if (item.retailPackCount) return "retail_pack_count_not_compatible"
        val label = swiggyCandidateLabel(candidate)
        if (item.query.contains("samosa", ignoreCase = true)) return "prepared_piece_or_ready_state_not_proven"
        if (Regex("\\b(?:pack\\s+of\\s+\\d+|\\d+\\s*[x×]\\s*\\d+)\\b", RegexOption.IGNORE_CASE).containsMatchIn(label)) {
            return "catalogue_multipack_not_exactly_divisible"
        }
        if (Regex("\\b\\d+\\s*(?:pieces?|pcs?)\\b", RegexOption.IGNORE_CASE).containsMatchIn(label)) {
            return "catalogue_piece_count_not_exactly_divisible"
        }
        return "catalogue_count_unknown_or_incompatible"
    }

    private fun recommendationBody(
        addressId: String,
        queries: List<String>,
        strictMatchPhrases: List<String?>,
    ): JSONObject = JSONObject()
        .put("addressId", addressId)
        .put("queries", JSONArray(queries))
        .put("strictMatchPhrases", JSONArray().apply {
            strictMatchPhrases.forEach { put(it ?: JSONObject.NULL) }
        })

    private fun client(
        baseUrl: String,
        requestLog: MutableList<Pair<String, String>>,
        timeoutSeconds: Long,
    ): OkHttpClient {
        val host = URI(baseUrl).host
        val allowed = setOf(
            "GET" to STATUS_PATH,
            "GET" to ADDRESSES_PATH,
            "POST" to INTENT_PATH,
            "POST" to RECOMMENDATIONS_PATH,
        )
        val guard = Interceptor { chain ->
            val request = chain.request()
            val methodAndPath = request.method to request.url.encodedPath
            require(methodAndPath in allowed) { "Intent catalogue request outside explicit allowlist" }
            require(request.url.isHttps && request.url.host == host) { "Intent catalogue host must be configured HTTPS backend" }
            synchronized(requestLog) { requestLog += methodAndPath }
            chain.proceed(request)
        }
        return OkHttpClient.Builder()
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .addNetworkInterceptor(guard)
            .build()
    }

    private fun requestJson(
        client: OkHttpClient,
        baseUrl: String,
        installationToken: String,
        method: String,
        path: String,
        body: JSONObject? = null,
    ): String {
        val builder = Request.Builder()
            .url("$baseUrl$path")
            .header(HEADER_BACKEND_KEY, AppConfig.backendApiKey)
            .header(HEADER_INSTALLATION_TOKEN, installationToken)
        if (method == "GET") builder.get() else {
            require(body != null)
            builder.post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
        }
        client.newCall(builder.build()).execute().use { response ->
            require(response.isSuccessful) { "Read-only intent catalogue HTTP ${response.code}" }
            return response.body?.string().orEmpty().also { require(it.isNotBlank()) { "Empty read-only response" } }
        }
    }

    private fun elapsedMs(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000L

    private fun savePrivate(context: Context, name: String, contents: String) {
        context.openFileOutput(name, Context.MODE_PRIVATE).use { it.write(contents.toByteArray(Charsets.UTF_8)) }
    }

    private data class ProbeCase(val caseId: String, val instruction: String, val expected: List<ExpectedItem>)
    private data class ExpectedItem(
        val identity: String,
        val quantity: Int,
        val acceptableUnits: Set<String> = setOf("count", "pack"),
    )

    private fun everydayExpected() = listOf(
        ExpectedItem("milk", 1, setOf("pack")),
        ExpectedItem("chocolate", 2, setOf("count")),
        ExpectedItem("popcorn", 1, setOf("count")),
    )

    private fun synthetic15Expected() = listOf(
        ExpectedItem("milk", 1), ExpectedItem("bread", 2), ExpectedItem("egg", 3),
        ExpectedItem("rice", 1), ExpectedItem("banana", 2), ExpectedItem("apple", 1),
        ExpectedItem("tomato", 2), ExpectedItem("onion", 3), ExpectedItem("potato", 1),
        ExpectedItem("biscuit", 2), ExpectedItem("curd", 1), ExpectedItem("orange", 2),
        ExpectedItem("soap", 1), ExpectedItem("tea", 2), ExpectedItem("sugar", 1),
    )

    private fun synthetic20Expected() = synthetic15Expected() + listOf(
        ExpectedItem("oil", 2), ExpectedItem("salt", 1), ExpectedItem("toothpaste", 2),
        ExpectedItem("lentil", 1), ExpectedItem("cucumber", 2),
    )

    private companion object {
        const val FLAG = "betaIntentCatalogReadOnly"
        const val INCLUDE_20_FLAG = "betaIntentCatalog20Item"
        const val RESULT_FILE = "grocery-intent-catalog-readonly-result.json"
        const val INTENT_CANARY_URL = "https://intent-canary---beta-backend-staging-kvuem5t7mq-el.a.run.app"
        const val STATUS_PATH = "/swiggy/status"
        const val ADDRESSES_PATH = "/swiggy/addresses"
        const val INTENT_PATH = "/swiggy/intent"
        const val RECOMMENDATIONS_PATH = "/swiggy/recommendations/batch"
        const val HEADER_BACKEND_KEY = "x-beta-backend-key"
        const val HEADER_INSTALLATION_TOKEN = "x-beta-installation-token"
        const val EVERYDAY_INSTRUCTION = "one tetra pack milk and two dark chocolate and one ready-made butter popcorn"
        const val SYNTHETIC_15_INSTRUCTION =
            "1 milk, 2 bread, 3 eggs, 1 rice, 2 bananas, 1 apple, 2 tomatoes, 3 onions, 1 potato, " +
                "2 biscuits, 1 curd, 2 oranges, 1 soap, 2 tea, 1 sugar"
        const val SYNTHETIC_20_INSTRUCTION =
            "1 milk, 2 bread, 3 eggs, 1 rice, 2 bananas, 1 apple, 2 tomatoes, 3 onions, 1 potato, " +
                "2 biscuits, 1 curd, 2 oranges, 1 soap, 2 tea, 1 sugar, 2 oil, 1 salt, " +
                "2 toothpaste, 1 lentil, 2 cucumber"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
