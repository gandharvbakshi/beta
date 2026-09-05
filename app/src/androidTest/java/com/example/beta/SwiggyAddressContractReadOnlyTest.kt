package com.example.beta

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SwiggyAddressContractReadOnlyTest {
    @Test
    fun captureAddressContractMetadataWithoutMutation() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString(FLAG_NAME) == "true")
        assumeTrue(AppConfig.backendApiKey.isNotBlank())

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installationToken = SwiggyInstallationIdentity.installationToken(context)
        val requestLog = mutableListOf<String>()
        val client = okHttpClient(requestLog)

        val addressesBody = fetchBody(
            client = client,
            url = "${AppConfig.backendBaseUrl}/swiggy/addresses",
            installationToken = installationToken,
            operationLabel = "saved addresses",
        )
        context.openFileOutput(OUTPUT_RAW_ADDRESSES, Context.MODE_PRIVATE).use {
            it.write(addressesBody.toByteArray(Charsets.UTF_8))
        }
        val addressesRoot = JSONObject(addressesBody)
        val addressesArray = extractAddressArray(addressesRoot)
        val savedAddressRecords = (0 until addressesArray.length()).mapNotNull { index ->
            addressesArray.optJSONObject(index)
        }
        val savedAddresses = savedAddressRecords.mapNotNull { record -> parseSavedAddress(record) }

        val rememberedAddress = runCatching {
            resolveRememberedSwiggyAddress(context, savedAddresses)
        }.getOrNull()?.address

        val cartBody = fetchBody(
            client = client,
            url = "${AppConfig.backendBaseUrl}/swiggy/cart",
            installationToken = installationToken,
            operationLabel = "current cart",
        )
        val cartRoot = JSONObject(cartBody)
        val currentCartAddressId = extractCartAddressId(cartRoot)
        val cartItemCount = extractCartItemCount(cartRoot)
        val currentCartEmpty = cartItemCount.count?.let { it == 0 } ?: JSONObject.NULL

        val selectedAddressId = rememberedAddress
            ?.id
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val rememberedSavedRecord = rememberedAddress?.let { address ->
            savedAddressRecords.firstOrNull { parseSavedAddress(it)?.id == address.id }
        }
        val currentCartId = currentCartAddressId?.trim().takeIf { !it.isNullOrBlank() }
        val selectedRecordMatchesCurrentCart = selectedAddressId != null &&
            currentCartId != null &&
            selectedAddressId == currentCartId
        val selectedAddressDetailsId = extractSelectedAddressDetailsId(cartRoot)
        val selectedAddressScalar = firstString(cartRoot, "selectedAddress", "selected_address")
        val selectedAddressScalarMatchesDetailsId = if (selectedAddressScalar != null && selectedAddressDetailsId != null) {
            selectedAddressScalar == selectedAddressDetailsId
        } else {
            JSONObject.NULL
        }
        val selectedRecordContainsCurrentCartId = rememberedSavedRecord?.let { record ->
            currentCartId != null && exactScalarMatch(record, currentCartId)
        } ?: false
        val currentCartMembershipCount = if (currentCartId == null) 0 else {
            savedAddressRecords.count { exactScalarMatch(it, currentCartId) }
        }

        val metadata = JSONObject()
            .put("capturedAtMillis", System.currentTimeMillis())
            .put("currentCartItemsRecognized", cartItemCount.recognized)
            .put("currentCartEmpty", currentCartEmpty)
            .put("currentCartAddressIdLength", currentCartAddressId?.length ?: 0)
            .put("selectedAddressIdLength", selectedAddressId?.length ?: 0)
            .put("selectedAddressIdExactEqualsCurrentCartId", selectedRecordMatchesCurrentCart)
            .put("selectedAddressScalarMatchesSelectedAddressDetailsId", selectedAddressScalarMatchesDetailsId)
            .put("selectedRecordCurrentCartIdAppearsInAnyScalarField", selectedRecordContainsCurrentCartId)
            .put("currentCartMembershipCountAcrossSavedRecords", currentCartMembershipCount)
            .put("savedAddressRecordShapes", JSONArray().apply {
                savedAddressRecords.forEachIndexed { index, address ->
                    put(
                        JSONObject()
                            .put("index", index)
                            .put("shape", shapeOfJson(address)),
                    )
                }
            })
            .put("currentCartIdLikeFieldPaths", JSONArray().apply {
                collectAddressIdLikeFields(cartRoot).forEach { field ->
                    put(
                        JSONObject()
                            .put("path", field.path)
                            .put("type", field.type)
                            .put("length", field.length),
                    )
                }
            })

        context.openFileOutput(OUTPUT_OBSERVATIONS, Context.MODE_PRIVATE).use { output ->
            output.write(metadata.toString(2).toByteArray(Charsets.UTF_8))
        }

        assertTrue("Read-only contract evidence captured", requestLog == listOf("GET /swiggy/addresses", "GET /swiggy/cart"))
    }

    private data class AddressIdField(
        val path: String,
        val type: String,
        val length: Int,
    )

    private fun parseSavedAddress(record: JSONObject): SwiggyMcpClient.SwiggyAddress? {
        val id = firstString(record, "id", "addressId", "address_id", "value", "placeId", "place_id") ?: return null
        val label = firstString(
            record,
            "label",
            "name",
            "title",
            "displayText",
            "display_text",
            "address",
            "formattedAddress",
            "formatted_address",
            "text",
            "addressLine",
        ) ?: id
        val normalizedLabel = label.trim().ifBlank { id }
        val shortLabel = normalizedLabel.substringBefore(" —").trim().ifBlank { normalizedLabel }
        val categoryLabel = shortLabel.substringBefore(" —").trim().ifBlank { shortLabel }
        val confirmationDetail = firstString(record, "confirmationDetail", "confirmation_detail")
        val hasCurrentCart = firstBoolean(record, "hasCurrentCart", "has_current_cart") ?: false
        return SwiggyMcpClient.SwiggyAddress(
            id = id,
            label = label,
            normalizedLabel = normalizedLabel,
            shortLabel = shortLabel,
            categoryLabel = categoryLabel,
            confirmationDetail = confirmationDetail,
            hasCurrentCart = hasCurrentCart,
        )
    }

    private fun extractAddressArray(root: JSONObject): JSONArray {
        root.optJSONObject("data")?.let { data ->
            firstArray(data, "addresses", "items", "results")?.let { return it }
        }
        return firstArray(root, "addresses", "items", "results") ?: JSONArray()
    }

    private fun extractCartAddressId(root: JSONObject): String? {
        root.optJSONObject("selectedAddressDetails")?.let { details ->
            firstString(details, "id", "addressId", "address_id")?.let { return it }
        }
        root.optJSONObject("data")?.optJSONObject("selectedAddressDetails")?.let { details ->
            firstString(details, "id", "addressId", "address_id")?.let { return it }
        }
        return firstString(root, "selectedAddress", "selected_address", "addressId", "address_id")
    }

    private data class CartItemCount(
        val recognized: Boolean,
        val count: Int?,
    )

    private fun extractCartItemCount(root: JSONObject): CartItemCount {
        val items = firstArray(root, "items", "cartItems", "cart_items")
            ?: root.optJSONObject("data")?.let { data -> firstArray(data, "items", "cartItems", "cart_items") }
        return if (items == null) {
            CartItemCount(recognized = false, count = null)
        } else {
            CartItemCount(recognized = true, count = items.length())
        }
    }

    private fun extractSelectedAddressDetailsId(root: JSONObject): String? {
        root.optJSONObject("selectedAddressDetails")?.let { details ->
            firstString(details, "id", "addressId", "address_id")?.let { return it }
        }
        root.optJSONObject("data")?.optJSONObject("selectedAddressDetails")?.let { details ->
            firstString(details, "id", "addressId", "address_id")?.let { return it }
        }
        return null
    }

    private fun collectAddressIdLikeFields(root: JSONObject): List<AddressIdField> {
        val fields = mutableListOf<AddressIdField>()
        walkFields(root, emptyList(), fields)
        return fields
    }

    private fun walkFields(node: Any?, path: List<String>, fields: MutableList<AddressIdField>) {
        when (node) {
            is JSONObject -> {
                node.keys().forEach { key ->
                    val child = node.opt(key)
                    val childPath = path + key
                    if (isIdLikeAddressPath(childPath, child)) {
                        fields += AddressIdField(
                            path = childPath.joinToString("."),
                            type = scalarType(child),
                            length = scalarLength(child),
                        )
                    }
                    walkFields(child, childPath, fields)
                }
            }
            is JSONArray -> {
                for (index in 0 until node.length()) {
                    walkFields(node.opt(index), path + "[$index]", fields)
                }
            }
        }
    }

    private fun isIdLikeAddressPath(path: List<String>, node: Any?): Boolean {
        val key = path.lastOrNull()?.lowercase(Locale.US).orEmpty()
        val joined = path.joinToString(".").lowercase(Locale.US)
        val scalar = node == null || node == JSONObject.NULL || node is String || node is Number || node is Boolean
        if (!scalar) return false
        if (key in setOf("currentcartaddressid", "current_cart_address_id", "selectedaddressid", "selected_address_id", "deliveryaddressid", "delivery_address_id", "addressid", "address_id", "selectedaddress", "selected_address")) return true
        if (key == "id") {
            return joined.contains("selectedaddressdetails") || joined.contains("selected_address_details") ||
                joined.contains("selectedaddress") || joined.contains("selected_address") ||
                joined.contains("deliveryaddress") || joined.contains("delivery_address") ||
                joined.contains("addressdetails")
        }
        return false
    }

    private fun exactScalarMatch(node: Any?, target: String): Boolean {
        if (target.isBlank()) return false
        var matched = false
        fun walk(value: Any?) {
            if (matched) return
            when (value) {
                is JSONObject -> value.keys().forEach { walk(value.opt(it)) }
                is JSONArray -> for (index in 0 until value.length()) walk(value.opt(index))
                is String -> if (value == target) matched = true
                is Number, is Boolean -> if (value.toString() == target) matched = true
            }
        }
        walk(node)
        return matched
    }

    private fun shapeOfJson(node: Any?): Any {
        return when (node) {
            is JSONObject -> JSONObject().apply {
                put("type", "object")
                put("fieldCount", node.length())
                val keys = JSONArray()
                node.keys().forEach { key ->
                    keys.put(
                        JSONObject()
                            .put("key", key)
                            .put("shape", shapeOfJson(node.opt(key))),
                    )
                }
                put("fields", keys)
            }
            is JSONArray -> JSONObject()
                .put("type", "array")
                .put("length", node.length())
                .put(
                    "items",
                    JSONArray().apply {
                        for (index in 0 until node.length()) {
                            put(shapeOfJson(node.opt(index)))
                        }
                    },
                )
            is String -> JSONObject().put("type", "string").put("length", node.length)
            is Number -> JSONObject().put("type", "number").put("length", node.toString().length)
            is Boolean -> JSONObject().put("type", "boolean").put("length", if (node) 4 else 5)
            else -> JSONObject().put("type", "null").put("length", 0)
        }
    }

    private fun scalarType(node: Any?): String = when (node) {
        is String -> "string"
        is Number -> "number"
        is Boolean -> "boolean"
        is JSONArray -> "array"
        is JSONObject -> "object"
        else -> "null"
    }

    private fun scalarLength(node: Any?): Int = when (node) {
        is String -> node.length
        is Number -> node.toString().length
        is Boolean -> if (node) 4 else 5
        is JSONArray -> node.length()
        is JSONObject -> node.length()
        else -> 0
    }

    private fun firstArray(root: JSONObject, vararg keys: String): JSONArray? {
        for (key in keys) {
            root.optJSONArray(key)?.let { return it }
        }
        return null
    }

    private fun firstString(root: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            val value = root.opt(key)
            if (value is String && value.isNotBlank()) return value.trim()
            if (value is Number || value is Boolean) return value.toString().trim()
        }
        return null
    }

    private fun firstBoolean(root: JSONObject, vararg keys: String): Boolean? {
        for (key in keys) {
            val value = root.opt(key)
            if (value is Boolean) return value
            if (value is String) {
                val normalized = value.trim().lowercase(Locale.US)
                if (normalized == "true") return true
                if (normalized == "false") return false
            }
        }
        return null
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

    private fun okHttpClient(requestLog: MutableList<String>): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request()
                val signature = "${request.method.uppercase(Locale.US)} ${request.url.encodedPath}"
                check(signature == "GET /swiggy/addresses" || signature == "GET /swiggy/cart") {
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

    private companion object {
        const val FLAG_NAME = "liveSwiggyAddressContract"
        const val OUTPUT_RAW_ADDRESSES = "swiggy-address-contract-private.json"
        const val OUTPUT_OBSERVATIONS = "swiggy-address-contract-observations.json"
        const val PREFERENCES_NAME = "swiggy_address_intelligence"
    }
}
