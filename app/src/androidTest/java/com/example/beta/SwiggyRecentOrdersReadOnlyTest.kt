package com.example.beta

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Explicitly opted-in, read-only diagnostic for recent Swiggy Instamart orders.
 *
 * The test never touches checkout or payment. It only reads order history and
 * validates that each recent order detail exposes at least one usable item.
 * It can optionally preview a cart plan in read-only mode when the order detail
 * contains enough information to do so safely.
 */
@RunWith(AndroidJUnit4::class)
class SwiggyRecentOrdersReadOnlyTest {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    @Test
    fun validateRecentOrdersWithoutMutation() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString(FLAG_NAME) == "true")
        assumeTrue(!AppConfig.isLocalBackend)
        assertTrue("Hosted Swiggy backend key is required for read-only validation", AppConfig.backendApiKey.isNotBlank())

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installationToken = SwiggyInstallationIdentity.installationToken(context)

        val recentOrdersRoot = getJson(
            path = "/swiggy/orders",
            installationToken = installationToken,
            operationLabel = "recent orders",
        )
        val recentOrders = extractRecentOrders(recentOrdersRoot)
        val hasMore = extractHasMore(recentOrdersRoot)
        val addressRoot = getJson(
            path = "/swiggy/addresses",
            installationToken = installationToken,
            operationLabel = "saved addresses",
        )
        val currentCartAddressId = extractCurrentCartAddressId(addressRoot)
        val activeAddressId = currentCartAddressId ?: extractFirstSavedAddressId(addressRoot)
        assertTrue(
            "Expected either the current cart address or at least one saved delivery address",
            !activeAddressId.isNullOrBlank(),
        )
        val selectedAddressId = requireNotNull(activeAddressId)
        Log.i(
            "BetaAgent",
            "SWIGGY_RECENT_ORDERS_LIST count=${recentOrders.size} hasMore=$hasMore schemaKeys=${describeSchemaKeys(recentOrdersRoot)}",
        )
        assertTrue("Expected at least one recent Swiggy Instamart order", recentOrders.isNotEmpty())
        assertTrue("Expected the recent-orders response to report whether more orders exist", hasMore != null)
        assertFalse("Backend indicated that additional recent orders were not validated", hasMore == true)

        var totalItems = 0
        var maxItems = 0
        var detailsUnavailable = 0
        var plansPreviewed = 0
        var itemsUnavailableNow = 0
        recentOrders.forEachIndexed { index, recentOrder ->
            val detailRoot = getJson(
                path = "/swiggy/orders/${URLEncoder.encode(recentOrder.orderId, Charsets.UTF_8.name())}",
                installationToken = installationToken,
                operationLabel = "recent order detail #${index + 1}",
            )
            val detailUnavailable = containsError(detailRoot)
            if (detailUnavailable) detailsUnavailable += 1
            val validationRoot = if (detailUnavailable) recentOrder.summary else detailRoot
            val items = extractOrderItems(validationRoot)
            totalItems += items.size
            maxItems = maxOf(maxItems, items.size)
            Log.i(
                "BetaAgent",
                "SWIGGY_RECENT_ORDER_DETAIL index=${index + 1} itemCount=${items.size} detailUnavailable=$detailUnavailable schemaKeys=${describeSchemaKeys(validationRoot)} addressFields=${describeAddressFieldAvailability(validationRoot)}",
            )

            assertTrue(
                "Expected at least one usable item in recent order #${index + 1}",
                items.isNotEmpty(),
            )
            items.forEach { item ->
                assertTrue("Expected a sane quantity in recent order #${index + 1}", item.quantity in 1..999)
                assertTrue(
                    "Expected a usable item identifier or name in recent order #${index + 1}",
                    !item.usableLabel.isBlank(),
                )
            }

            assertTrue(
                "Recent order #${index + 1} is too large for one safe cart preview",
                items.size <= MAX_CART_PLAN_ITEMS,
            )
            val discoveryRoot = discoverRecommendations(
                addressId = selectedAddressId,
                requestedItems = items,
                installationToken = installationToken,
            )
            val previewableItems = extractSuggestedItems(discoveryRoot, items)
            val unavailableCount = items.size - previewableItems.size
            itemsUnavailableNow += unavailableCount
            assertTrue(
                "Expected at least one currently available item to exercise a safe cart preview for recent order #${index + 1}",
                previewableItems.isNotEmpty(),
            )
            val previewRoot = previewPlan(
                addressId = selectedAddressId,
                requestedItems = previewableItems,
                installationToken = installationToken,
            )
            plansPreviewed += 1
            Log.i(
                "BetaAgent",
                "SWIGGY_CART_PLAN_PREVIEW index=${index + 1} itemCount=${previewableItems.size} " +
                    "unavailableCount=$unavailableCount schemaKeys=${describeSchemaKeys(previewRoot)}",
            )
        }
        Log.i(
            "BetaAgent",
            "SWIGGY_RECENT_ORDERS_VALIDATED orders=${recentOrders.size} totalItems=$totalItems " +
                "maxItems=$maxItems detailUnavailable=$detailsUnavailable plansPreviewed=$plansPreviewed " +
                "itemsUnavailableNow=$itemsUnavailableNow",
        )
    }

    private fun extractHasMore(root: Any?): Boolean? {
        var hasMore: Boolean? = null
        walkJson(root) { obj ->
            if (hasMore != null) return@walkJson
            listOf("hasMore", "has_more").forEach { key ->
                if (!obj.has(key)) return@forEach
                when (val value = obj.opt(key)) {
                    is Boolean -> hasMore = value
                    is String -> value.trim().lowercase(Locale.US)
                        .takeIf { it == "true" || it == "false" }
                        ?.let { hasMore = it.toBoolean() }
                }
            }
        }
        return hasMore
    }

    private fun extractRecentOrders(root: Any?): List<RecentOrder> {
        val orders = mutableListOf<RecentOrder>()
        walkJson(root) { obj ->
            val array = obj.optJSONArray("orders") ?: return@walkJson
            for (index in 0 until array.length()) {
                val summary = array.optJSONObject(index) ?: continue
                val orderId = firstString(summary, "orderId", "order_id", "orderNo", "order_no")
                    ?: continue
                if (orders.none { it.orderId == orderId }) orders += RecentOrder(orderId, summary)
            }
        }
        if (orders.isNotEmpty()) return orders
        return extractOrderIds(root).map { RecentOrder(it, JSONObject()) }
    }

    private fun containsError(root: Any?): Boolean {
        var found = false
        walkJson(root) { obj ->
            if (obj.has("error")) found = true
        }
        return found
    }

    private fun getJson(
        path: String,
        installationToken: String,
        operationLabel: String,
    ): Any? {
        val request = Request.Builder()
            .url("${AppConfig.backendBaseUrl}$path")
            .header("x-beta-backend-key", AppConfig.backendApiKey)
            .header("x-beta-installation-token", installationToken)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            assertTrue("Expected successful GET for $operationLabel, got HTTP ${response.code}", response.isSuccessful)
            assertFalse("Expected a non-empty JSON response for $operationLabel", body.isBlank())
            return JSONTokener(body).nextValue()
        }
    }

    private fun previewPlan(
        addressId: String,
        requestedItems: List<RequestedItemPreview>,
        installationToken: String,
    ): Any? {
        val body = JSONObject()
            .put("addressId", addressId)
            .put(
                "requestedItems",
                JSONArray().apply {
                    requestedItems.forEach { item ->
                        put(
                            JSONObject()
                                .put("spinId", item.spinId)
                                .put("quantity", item.quantity)
                                .put("displayName", item.usableLabel)
                                .apply { item.skuId?.takeIf { it.isNotBlank() }?.let { put("skuId", it) } }
                        )
                    }
                },
            )
            .toString()

        val request = Request.Builder()
            .url(AppConfig.swiggyCartPlanUrl)
            .header("x-beta-backend-key", AppConfig.backendApiKey)
            .header("x-beta-installation-token", installationToken)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            val parsed = runCatching { JSONTokener(responseBody).nextValue() }.getOrNull()
            val noChangeAccepted = extractReason(parsed) == "cart_no_changes" ||
                responseBody.lowercase(Locale.US).contains("no change") ||
                responseBody.lowercase(Locale.US).contains("no-op") ||
                responseBody.lowercase(Locale.US).contains("nothing to do") ||
                responseBody.lowercase(Locale.US).contains("already up to date")

            if (!response.isSuccessful && !noChangeAccepted) {
                assertTrue(
                    "Expected a successful or explicitly no-change cart-plan preview, got HTTP ${response.code}",
                    false,
                )
            }
            assertTrue(
                "Expected the preview response to contain JSON or a readable no-change message",
                parsed != null || noChangeAccepted,
            )
            return parsed
        }
    }

    private fun discoverRecommendations(
        addressId: String,
        requestedItems: List<RequestedItemPreview>,
        installationToken: String,
    ): Any? {
        val body = JSONObject()
            .put("addressId", addressId)
            .put("queries", JSONArray().apply { requestedItems.forEach { put(it.usableLabel) } })
            .put("strictMatchPhrases", JSONArray().apply { requestedItems.forEach { put(JSONObject.NULL) } })
            .toString()
        val request = Request.Builder()
            .url("${AppConfig.backendBaseUrl}/swiggy/recommendations/batch")
            .header("x-beta-backend-key", AppConfig.backendApiKey)
            .header("x-beta-installation-token", installationToken)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            assertTrue(
                "Expected successful recommendation discovery, got HTTP ${response.code}",
                response.isSuccessful,
            )
            assertFalse("Expected recommendation discovery JSON", responseBody.isBlank())
            return JSONTokener(responseBody).nextValue()
        }
    }

    private fun extractSuggestedItems(
        root: Any?,
        requestedItems: List<RequestedItemPreview>,
    ): List<RequestedItemPreview> {
        val results = (root as? JSONObject)?.optJSONArray("results") ?: return emptyList()
        if (results.length() != requestedItems.size) return emptyList()
        val candidateCounts = (0 until results.length()).map { index ->
            results.optJSONObject(index)?.optJSONArray("candidates")?.length() ?: 0
        }
        val warningCodes = (0 until results.length()).map { index ->
            val warnings = results.optJSONObject(index)?.optJSONArray("warnings")
            (0 until (warnings?.length() ?: 0)).mapNotNull { warningIndex ->
                warnings?.optString(warningIndex)?.takeIf { it.matches(Regex("[a-z0-9_]+")) }
            }
        }
        Log.i(
            "BetaAgent",
            "SWIGGY_RECENT_ORDER_DISCOVERY requested=${requestedItems.size} " +
                "candidateCounts=$candidateCounts warningCodes=$warningCodes",
        )
        return (0 until results.length()).mapNotNull { index ->
            val result = results.optJSONObject(index) ?: return@mapNotNull null
            val suggested = result.optJSONObject("suggested")
                ?: result.optJSONArray("candidates")?.optJSONObject(0)
                ?: return@mapNotNull null
            val spinId = firstString(suggested, "spinId", "spin_id") ?: return@mapNotNull null
            RequestedItemPreview(
                spinId = spinId,
                usableLabel = firstString(suggested, "label", "name", "productName")
                    ?: requestedItems[index].usableLabel,
                quantity = requestedItems[index].quantity,
                skuId = firstString(suggested, "skuId", "sku_id"),
            )
        }
    }

    private fun extractCurrentCartAddressId(root: Any?): String? {
        var currentCartAddressId: String? = null
        walkJson(root) { obj ->
            if (currentCartAddressId == null) {
                firstString(obj, "currentCartAddressId", "current_cart_address_id")
                    ?.let { currentCartAddressId = it }
            }
        }
        return currentCartAddressId
    }

    private fun extractFirstSavedAddressId(root: Any?): String? {
        var addressId: String? = null
        walkJson(root) { obj ->
            if (addressId != null) return@walkJson
            val addresses = obj.optJSONArray("addresses") ?: return@walkJson
            val firstAddress = addresses.optJSONObject(0) ?: return@walkJson
            firstString(firstAddress, "id", "addressId", "address_id")
                ?.let { addressId = it }
        }
        return addressId
    }

    private fun extractReason(root: Any?): String? {
        var reason: String? = null
        walkJson(root) { obj ->
            if (reason == null) {
                firstString(obj, "reason")?.let { reason = it }
            }
        }
        return reason
    }

    private fun extractOrderIds(root: Any?): List<String> {
        val collected = linkedSetOf<String>()
        collectIdsFromNamedArrays(root, collected)
        if (collected.isNotEmpty()) return collected.toList()
        walkJson(root) { obj ->
            val keys = obj.keySet()
            val hasOrderContext = keys.any { it.matches(ORDER_CONTEXT_KEYS) }
            if (!hasOrderContext) return@walkJson
            keys.forEach { key ->
                if (key.matches(ORDER_ID_KEYS)) {
                    obj.optStringOrNull(key)?.takeIf { it.isNotBlank() }?.let(collected::add)
                }
            }
        }
        return collected.toList()
    }

    private fun collectIdsFromNamedArrays(root: Any?, collected: MutableSet<String>) {
        walkJson(root) { obj ->
            ORDER_ARRAY_KEYS.forEach { arrayKey ->
                val array = obj.optJSONArray(arrayKey) ?: return@forEach
                for (index in 0 until array.length()) {
                    val candidate = array.optJSONObject(index) ?: continue
                    ORDER_ID_KEYS.forEach { idKey ->
                        candidate.optStringOrNull(idKey)?.takeIf { it.isNotBlank() }?.let(collected::add)
                    }
                }
            }
        }
    }

    private fun extractOrderItems(root: Any?): List<RequestedItemPreview> {
        val collected = linkedMapOf<String, RequestedItemPreview>()
        walkJson(root) { obj ->
            val candidate = obj.toRequestedItemPreview()
            if (candidate != null) {
                collected.putIfAbsent(candidate.dedupeKey(), candidate)
            }
        }
        return collected.values.toList()
    }

    private fun extractAddressId(root: Any?): String? {
        var addressId: String? = null
        walkJson(root) { obj ->
            if (addressId != null) return@walkJson
            obj.optJSONObject("deliveryAddress")?.let { deliveryAddress ->
                firstString(deliveryAddress, "addressId", "address_id", "id")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { addressId = it }
            }
            obj.keySet().forEach { key ->
                if (key.matches(ADDRESS_ID_KEYS)) {
                    obj.optStringOrNull(key)?.takeIf { it.isNotBlank() }?.let {
                        addressId = it
                    }
                }
            }
        }
        return addressId
    }

    private fun describeAddressFieldAvailability(root: Any?): String {
        return "addressIdKeys=${collectMatchingKeys(root, ADDRESS_ID_KEYS)} addressTextKeys=${collectMatchingKeys(root, ADDRESS_TEXT_KEYS)}"
    }

    private fun collectMatchingKeys(root: Any?, targets: Set<String>): List<String> {
        val keys = linkedSetOf<String>()
        walkJson(root) { obj ->
            obj.keySet().forEach { key ->
                if (key.matches(targets) && keys.size < 20) {
                    keys.add(key)
                }
            }
        }
        return keys.toList()
    }

    private fun describeSchemaKeys(root: Any?): List<String> {
        val keys = linkedSetOf<String>()
        walkJson(root) { obj ->
            obj.keySet().forEach { key ->
                if (keys.size < 30) {
                    keys.add(key)
                }
            }
        }
        return keys.toList()
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

    private fun JSONObject.keySet(): List<String> {
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

    private fun JSONObject.toRequestedItemPreview(): RequestedItemPreview? {
        val quantity = firstPositiveInt(
            this,
            "quantity",
            "qty",
            "count",
            "itemQuantity",
            "orderedQuantity",
        ) ?: return null
        val spinId = firstString(
            this,
            "spinId",
            "spin_id",
            "productId",
            "product_id",
            "skuId",
            "sku_id",
            "id",
        ) ?: nestedValueString(
            this,
            nestedKeys = arrayOf("product", "item", "catalogItem", "catalog_item"),
            valueKeys = arrayOf("spinId", "spin_id", "productId", "product_id", "skuId", "sku_id", "id"),
        )
        val label = firstString(
            this,
            "displayName",
            "itemName",
            "name",
            "productName",
            "title",
            "label",
        ) ?: nestedValueString(
            this,
            nestedKeys = arrayOf("product", "item", "catalogItem", "catalog_item"),
            valueKeys = arrayOf("displayName", "itemName", "name", "productName", "title", "label"),
        )
        if (spinId.isNullOrBlank() && label.isNullOrBlank()) return null
        return RequestedItemPreview(
            spinId = spinId?.trim().orEmpty(),
            usableLabel = label?.trim().orEmpty().ifBlank { spinId.orEmpty() },
            quantity = quantity,
            skuId = firstString(this, "skuId", "sku_id"),
        )
    }

    private fun RequestedItemPreview.dedupeKey(): String {
        return listOf(spinId, usableLabel, quantity.toString(), skuId.orEmpty()).joinToString("|")
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

    private fun nestedValueString(
        obj: JSONObject,
        nestedKeys: Array<out String>,
        valueKeys: Array<out String>,
    ): String? {
        nestedKeys.forEach { key ->
            val nested = obj.optJSONObject(key)
            if (nested != null) {
                firstString(nested, *valueKeys)?.let { return it }
            }
        }
        return null
    }

    private fun String.matches(options: Set<String>): Boolean {
        val normalized = trim().lowercase(Locale.US)
        return options.contains(normalized)
    }

    private data class RequestedItemPreview(
        val spinId: String,
        val usableLabel: String,
        val quantity: Int,
        val skuId: String? = null,
    ) {
        fun toRequestedItem(): RequestedItemPreview = this
    }

    private data class RecentOrder(
        val orderId: String,
        val summary: JSONObject,
    )

    private companion object {
        const val FLAG_NAME = "liveSwiggyRecentOrders"
        const val MAX_CART_PLAN_ITEMS = 50
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()
        val ORDER_ID_KEYS = setOf("id", "orderId", "order_id", "orderNo", "order_no", "orderNumber", "order_number")
        val ADDRESS_ID_KEYS = setOf("addressId", "address_id", "deliveryAddressId", "delivery_address_id", "selectedAddressId")
        val ADDRESS_TEXT_KEYS = setOf("address", "addressText", "address_text", "deliveryAddress", "delivery_address", "formattedAddress", "formatted_address", "label", "shortAddress", "short_address")
        val ORDER_CONTEXT_KEYS = setOf("items", "lineItems", "line_items", "orderItems", "order_items", "products", "status", "createdAt", "created_at", "placedAt", "placed_at", "total", "totalAmount", "total_amount")
        val ORDER_ARRAY_KEYS = setOf("orders", "data", "items", "results")
    }
}
