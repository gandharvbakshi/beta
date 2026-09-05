package com.example.beta

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Explicitly opted-in, read-only cart-discovery diagnostic.
 *
 * This test only reads the live cart and recommendation batch for a fixed set of
 * synthetic queries. It never mutates the cart, never checks out, never applies a
 * plan, and never touches address-write paths.
 */
@RunWith(AndroidJUnit4::class)
class SwiggyCartDiscoveryReadOnlyTest {
    @Test
    fun compareLiveCartItemsAgainstRecommendationBatchWithoutMutation() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString(FLAG_NAME) == "true")
        assumeTrue(!AppConfig.isLocalBackend)
        assertTrue(
            "Hosted Swiggy backend key is required for the read-only cart discovery check",
            AppConfig.backendApiKey.isNotBlank(),
        )

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installationToken = SwiggyInstallationIdentity.installationToken(context)
        val requestLog = mutableListOf<String>()
        val client = okHttpClient(requestLog)

        val cartBody = fetchBody(
            client = client,
            url = "${AppConfig.backendBaseUrl}/swiggy/cart",
            installationToken = installationToken,
            operationLabel = "live cart",
        )
        val cartSnapshot = parseCartSnapshot(cartBody)

        val addressBody = fetchBody(client, "${AppConfig.backendBaseUrl}/swiggy/addresses", installationToken, "saved address context")
        val addresses = SwiggyMcpClient.parseAddressesResponse(addressBody).addresses
        val latestUsage = context.getSharedPreferences("swiggy_address_intelligence", Context.MODE_PRIVATE)
            .all.entries.filter { it.key.endsWith("_time") && it.value is Long }
            .maxByOrNull { it.value as Long }?.key
        val reviewedAddress = addresses.firstOrNull { address ->
            val digest = MessageDigest.getInstance("SHA-256").digest(address.id.toByteArray())
                .take(12).joinToString("") { "%02x".format(it) }
            "${digest}_time" == latestUsage
        }
        val currentAddress = addresses.firstOrNull { it.id == cartSnapshot.selectedAddressId }
        val cartDetails = JSONObject(cartBody).getJSONObject("selectedAddressDetails")
        fun normalizedAddress(value: String) = value.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
        val reviewedText = normalizedAddress(reviewedAddress?.label.orEmpty())
        val cartAddressText = normalizedAddress(cartDetails.optString("address"))
        val cartFlat = normalizedAddress(cartDetails.optString("flatNo"))
        val addressComparison = JSONObject()
            .put("reviewedAddressFound", reviewedAddress != null)
            .put("currentAddressFound", currentAddress != null)
            .put("currentMatchesReviewedId", reviewedAddress?.id == cartSnapshot.selectedAddressId)
            .put("sameNormalizedLabel", reviewedAddress != null && currentAddress != null && reviewedAddress.normalizedLabel == currentAddress.normalizedLabel)
            .put("reviewedIdLength", reviewedAddress?.id?.length ?: 0)
            .put("reviewedIdNumeric", reviewedAddress?.id?.matches(Regex("\\d+")) == true)
            .put("savedAddressCount", addresses.size)
            .put("reviewedLabelContainsCartStreet", cartAddressText.length >= 20 && reviewedText.contains(cartAddressText))
            .put("reviewedLabelContainsCartFlat", cartFlat.isNotBlank() && (" $reviewedText ").contains(" $cartFlat "))
            .put("reviewedCategory", reviewedAddress?.categoryLabel?.takeIf { it in listOf("Home", "Work", "Other") } ?: "other_label")
            .put("currentCategory", currentAddress?.categoryLabel?.takeIf { it in listOf("Home", "Work", "Other") } ?: "other_label")
        context.openFileOutput("swiggy-cart-address-comparison.json", Context.MODE_PRIVATE).use {
            it.write(addressComparison.toString().toByteArray())
        }

        val queries = listOf("milk", "bread", "eggs", "banana", "coriander leaves")
        val batchBody = postBody(
            client = client,
            url = "${AppConfig.backendBaseUrl}/swiggy/recommendations/batch",
            installationToken = installationToken,
            body = buildRecommendationBody(cartSnapshot.selectedAddressId, queries),
            operationLabel = "cart discovery batch",
        )
        val recommendationBatch = SwiggyMcpClient.parseRecommendationBatch(batchBody)

        assertEquals(
            "Expected one recommendation result per fixed query",
            queries.size,
            recommendationBatch.size,
        )
        recommendationBatch.forEachIndexed { index, result ->
            assertEquals(
                "Expected the recommendation batch to preserve query order at index ${index + 1}",
                queries[index],
                result.query,
            )
        }

        val candidateIndex = linkedMapOf<String, SwiggyMcpClient.RecommendationCandidate>()
        recommendationBatch.forEach { recommendation ->
            recommendation.candidates.forEach { candidate ->
                candidateIndex.putIfAbsent(candidate.spinId, candidate)
            }
        }

        val comparisons = JSONArray()
        cartSnapshot.items.forEach { item ->
            val recommendationCandidate = candidateIndex[item.spinId]
            val cartSkuPresent = !item.skuId.isNullOrBlank()
            val recommendationSkuPresent = !recommendationCandidate?.skuId.isNullOrBlank()
            comparisons.put(
                JSONObject()
                    .put("spinId", item.spinId)
                    .put("cartQty", item.quantity)
                    .put("cartSkuPresent", cartSkuPresent)
                    .put("recommendationSkuPresent", recommendationSkuPresent)
                    .put(
                        "skuEqual",
                        cartSkuPresent &&
                            recommendationSkuPresent &&
                            item.skuId == recommendationCandidate?.skuId,
                    )
                    .put("foundInCandidates", recommendationCandidate != null),
            )
        }

        context.openFileOutput(OUTPUT_FILE, Context.MODE_PRIVATE).use { output ->
            output.write(comparisons.toString(2).toByteArray(Charsets.UTF_8))
        }

        Log.i(
            "BetaAgent",
            "SWIGGY_CART_DISCOVERY itemCount=${cartSnapshot.items.size} " +
                "matchedSpinIds=${candidateIndex.keys.intersect(cartSnapshot.items.map { it.spinId }.toSet()).size} " +
                "queryCount=${queries.size} requestCount=${requestLog.size}",
        )

        assertTrue("Expected the cart snapshot to expose a selected address id", cartSnapshot.selectedAddressId.isNotBlank())
        assertTrue(
            "Expected the live cart schema to expose at least one readable line item",
            cartSnapshot.items.isNotEmpty(),
        )
        assertFalse(
            "The read-only cart discovery test unexpectedly attempted a cart mutation path",
            requestLog.any { isMutatingPath(it) },
        )
        assertEquals(
            "Expected only the live cart fetch plus the recommendation batch call",
            listOf("GET /swiggy/cart", "GET /swiggy/addresses", "POST /swiggy/recommendations/batch"),
            requestLog,
        )
    }

    private fun parseCartSnapshot(body: String): CartSnapshot {
        val root = JSONTokener(body).nextValue() as? JSONObject
            ?: throw IllegalStateException("Swiggy cart response was not JSON object shaped.")

        val selectedAddressId = extractSelectedAddressId(root)
            ?: throw IllegalStateException(
                "Swiggy cart schema did not expose selectedAddressDetails.id or selectedAddressDetails.addressId. " +
                    "Root keys=${root.keysList()}",
            )

        val selectedAddressFromRoot = root.optStringOrNull("selectedAddress")
        if (selectedAddressFromRoot != null) {
            assertEquals(
                "Expected selectedAddress and selectedAddressDetails.id to match in the live cart schema",
                selectedAddressFromRoot,
                selectedAddressId,
            )
        }

        val items = extractCartItems(root)
        if (items.isEmpty()) {
            throw IllegalStateException(
                "Swiggy cart schema did not expose any readable line items with spinId and quantity. " +
                    "Root keys=${root.keysList()}",
            )
        }

        return CartSnapshot(
            selectedAddressId = selectedAddressId,
            items = items,
        )
    }

    private fun extractSelectedAddressId(root: JSONObject): String? {
        root.optJSONObject("selectedAddressDetails")?.let { details ->
            firstString(details, "id", "addressId", "address_id")?.let { return it }
        }
        root.optJSONObject("data")?.optJSONObject("selectedAddressDetails")?.let { details ->
            firstString(details, "id", "addressId", "address_id")?.let { return it }
        }
        return firstString(root, "selectedAddress", "selected_address", "addressId", "address_id")
    }

    private fun extractCartItems(root: JSONObject): List<CartItem> {
        val itemsArray = root.optJSONArray("items")
            ?: root.optJSONObject("data")?.optJSONArray("items")
            ?: throw IllegalStateException(
                "Swiggy cart schema did not expose an items array. Root keys=${root.keysList()}",
            )

        val merged = linkedMapOf<String, CartItem>()
        for (index in 0 until itemsArray.length()) {
            val obj = itemsArray.optJSONObject(index)
                ?: continue
            val spinId = firstString(obj, "spinId", "spin_id")
                ?: throw IllegalStateException("Swiggy cart item at index ${index + 1} is missing spinId.")
            val quantity = firstPositiveInt(obj, "quantity", "qty", "count")
                ?: throw IllegalStateException("Swiggy cart item at index ${index + 1} is missing quantity.")
            val skuId = firstString(obj, "skuId", "sku_id")
                ?: obj.optJSONObject("sku")?.let { sku -> firstString(sku, "id", "skuId", "sku_id") }
            val existing = merged[spinId]
            merged[spinId] = if (existing == null) {
                CartItem(spinId = spinId, quantity = quantity, skuId = skuId)
            } else {
                existing.copy(
                    quantity = existing.quantity + quantity,
                    skuId = existing.skuId ?: skuId,
                )
            }
        }
        return merged.values.toList()
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
                check(signature == "GET /swiggy/cart" || signature == "GET /swiggy/addresses" || signature == "POST /swiggy/recommendations/batch") {
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

    private fun walkJson(node: Any?, visitObject: (JSONObject) -> Unit) {
        when (node) {
            is JSONObject -> {
                visitObject(node)
                val iterator = node.keys()
                while (iterator.hasNext()) {
                    walkJson(node.opt(iterator.next()), visitObject)
                }
            }
            is JSONArray -> {
                for (index in 0 until node.length()) {
                    walkJson(node.opt(index), visitObject)
                }
            }
        }
    }

    private fun JSONObject.keysList(): List<String> {
        val out = mutableListOf<String>()
        val iterator = keys()
        while (iterator.hasNext()) {
            out += iterator.next()
        }
        return out
    }

    private fun JSONObject.optStringOrNull(name: String): String? {
        if (!has(name)) return null
        val value = opt(name)
        return when (value) {
            null, JSONObject.NULL -> null
            is String -> value
            else -> value.toString()
        }?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun firstPositiveInt(obj: JSONObject, vararg keys: String): Int? {
        keys.forEach { key ->
            val value = obj.opt(key)
            when (value) {
                is Number -> value.toInt().takeIf { it > 0 }
                is String -> value.trim().toIntOrNull()?.takeIf { it > 0 }
                else -> null
            }?.let { return it }
        }
        return null
    }

    private fun firstString(obj: JSONObject, vararg keys: String): String? {
        keys.forEach { key ->
            val value = obj.opt(key)
            val text = when (value) {
                null, JSONObject.NULL -> null
                is String -> value
                else -> value.toString()
            }?.trim()
            if (!text.isNullOrBlank()) {
                return text
            }
        }
        return null
    }

    private data class CartSnapshot(
        val selectedAddressId: String,
        val items: List<CartItem>,
    )

    private data class CartItem(
        val spinId: String,
        val quantity: Int,
        val skuId: String?,
    )

    private companion object {
        const val FLAG_NAME = "liveSwiggyCartDiscovery"
        const val OUTPUT_FILE = "swiggy-cart-discovery-comparison.json"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()
    }
}
