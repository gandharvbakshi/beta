package com.example.beta

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.TimeUnit
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

/** Explicit opt-in: three read-only requests, no plan/apply/checkout or preference changes. */
@RunWith(AndroidJUnit4::class)
class SwiggyHistoryPreferenceReadOnlyTest {
    @Test fun compareHistoryPreferenceAgainstLiveRecommendationsWithoutMutation() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("liveSwiggyHistoryPreference") == "true")
        assumeTrue(!AppConfig.isLocalBackend)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = args.getString("historyInput").orEmpty().ifBlank { "2 amul dark chocolate" }
        val items = prepareSwiggyMcpItems(input, lookup = { null })
        require(swiggyMcpItemValidationMessage(input, items) == null)
        val queries = items.map(::swiggyRecommendationQuery)
        require(queries.size in 1..10 && queries.all { it.length in 1..200 })
        val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS).build()
        val installationToken = SwiggyInstallationIdentity.installationToken(context)
        fun request(path: String, body: JSONObject? = null): JSONObject {
            require(path in setOf("/swiggy/cart", "/swiggy/orders", "/swiggy/recommendations/batch"))
            val builder = Request.Builder().url("${AppConfig.backendBaseUrl}$path")
                .header("x-beta-backend-key", AppConfig.backendApiKey)
                .header("x-beta-installation-token", installationToken)
            if (body != null) builder.post(body.toString().toRequestBody("application/json".toMediaType()))
            return client.newCall(builder.build()).execute().use { response ->
                assertTrue("Read-only HTTP ${response.code}", response.isSuccessful)
                JSONObject(response.body!!.string())
            }
        }
        fun data(root: JSONObject) = root.optJSONObject("data") ?: root
        val cart = data(request("/swiggy/cart"))
        val addressId = cart.optString("selectedAddress").ifBlank { cart.optString("selectedAddressId") }
            .ifBlank { cart.optJSONObject("selectedAddressDetails")?.optString("id").orEmpty() }
        require(addressId.isNotBlank()) { "No current cart address" }
        val orders = data(request("/swiggy/orders")).getJSONArray("orders")
        val history = JSONArray()
        val filter = args.getString("historyFilter").orEmpty().ifBlank { "chocolate" }
        for (i in 0 until orders.length()) {
            val items = orders.getJSONObject(i).optJSONArray("items") ?: continue
            for (j in 0 until items.length()) {
                val item = items.getJSONObject(j)
                val name = item.optString("name").ifBlank { item.optString("productName") }
                if (name.contains(filter, ignoreCase = true)) history.put(JSONObject()
                    .put("name", name).put("quantity", item.optInt("quantity")))
            }
        }
        val batchRequest = JSONObject().put("addressId", addressId).put("queries", JSONArray(queries))
            .put("strictMatchPhrases", JSONArray(items.map { it.strictMatchPhrase }))
        args.getString("historyStrictPhrase")?.takeIf { it.isNotBlank() }?.let { phrase ->
            require(queries.size == 1 && phrase.length <= 200)
            batchRequest.put("strictMatchPhrases", JSONArray(listOf(phrase)))
        }
        val batch = data(request("/swiggy/recommendations/batch", batchRequest))
        val results = batch.getJSONArray("results")
        val parsedResults = SwiggyMcpClient.parseRecommendationBatch(batch.toString())
        assertEquals(queries.size, results.length())
        val sanitized = JSONArray()
        for (i in queries.indices) {
            val result = results.getJSONObject(i)
            assertEquals(queries[i], result.getString("query"))
            val suggested = result.optJSONObject("suggested")
            val parsed = parsedResults[i]
            val compatible = parsed.candidates.filter {
                isSwiggyCandidateAllowed(items[i], it) && isSwiggyCandidateCountCompatible(items[i], it)
            }
            val planned = compatible.takeIf { it.isNotEmpty() }?.let {
                swiggyDefaultSuggestion(items[i], it, parsed.suggested)
            }
            val candidates = result.getJSONArray("candidates")
            val candidateLabels = JSONArray()
            for (j in 0 until candidates.length()) {
                candidateLabels.put(candidates.getJSONObject(j).optString("label"))
            }
            sanitized.put(JSONObject().put("query", queries[i])
                .put("suggestedName", suggested?.optString("name") ?: JSONObject.NULL)
                .put("suggestedLabel", suggested?.optString("label") ?: JSONObject.NULL)
                .put("sources", suggested?.optJSONArray("sources") ?: JSONArray())
                .put("candidateCount", result.getJSONArray("candidates").length())
                .put("candidateLabels", candidateLabels)
                .put("compatibleCandidateCount", compatible.size)
                .put("plannedLabel", planned?.label ?: JSONObject.NULL)
                .put("plannedCartQuantity", planned?.let { swiggyRequestedCartQuantity(items[i], it) } ?: JSONObject.NULL)
                .put("warnings", result.optJSONArray("warnings") ?: JSONArray()))
            args.getString("rejectSuggestedText")?.takeIf { it.isNotBlank() }?.let { rejected ->
                assertTrue("Unexpected substituted product at $i",
                    suggested?.optString("name")?.contains(rejected, ignoreCase = true) != true)
            }
            if (args.getString("expectedUnavailableQuery") == queries[i]) {
                assertTrue("Expected unavailable-usual safety at $i", parsed.usualProductUnavailable)
                assertTrue("Unavailable usual must not silently substitute", planned == null)
            }
        }
        // Never export address/order IDs, bearer tokens, or signed cart plans.
        context.openFileOutput("swiggy-history-preference-private.json", Context.MODE_PRIVATE).use {
            it.write(JSONObject().put("ordersChecked", orders.length()).put("history", history)
                .put("results", sanitized).toString().toByteArray(Charsets.UTF_8))
        }
        if (args.getString("expectHistoryFirst") == "true") {
            val expected = args.getString("preferredHistoryTitle").orEmpty().trim()
            require(expected.isNotBlank())
            assertTrue("Expected matching history", (0 until history.length()).any {
                history.getJSONObject(it).getString("name").equals(expected, ignoreCase = true)
            })
            for (i in queries.indices) {
                val suggested = results.getJSONObject(i).optJSONObject("suggested")
                assertTrue("Expected history identity at result $i",
                    suggested?.optString("name")?.equals(expected, ignoreCase = true) == true)
                val sources = suggested!!.getJSONArray("sources")
                assertTrue("Expected order history signal at result $i",
                    (0 until sources.length()).any { sources.optString(it) == "orders" })
            }
        }
        Log.i("BetaAgent", "SWIGGY_HISTORY_PREFERENCE orders=${orders.length()} queries=${queries.size} matches=${history.length()}")
    }
}
