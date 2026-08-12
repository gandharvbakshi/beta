package com.example.beta

import android.content.Context
import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

private sealed class JsonValue {
    data object Null : JsonValue()
    data class Bool(val value: Boolean) : JsonValue()
    data class Num(val raw: String) : JsonValue()
    data class Str(val value: String) : JsonValue()
    data class Obj(val members: Map<String, JsonValue>) : JsonValue()
    data class Arr(val items: List<JsonValue>) : JsonValue()
}

private class JsonParser(private val text: String) {
    private var index = 0

    fun parse(): JsonValue? {
        skipWhitespace()
        if (index >= text.length) return null
        val value = parseValue()
        skipWhitespace()
        return value
    }

    private fun parseValue(): JsonValue {
        skipWhitespace()
        return when (peek()) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonValue.Str(parseString())
            't' -> {
                consumeLiteral("true")
                JsonValue.Bool(true)
            }
            'f' -> {
                consumeLiteral("false")
                JsonValue.Bool(false)
            }
            'n' -> {
                consumeLiteral("null")
                JsonValue.Null
            }
            else -> JsonValue.Num(parseNumber())
        }
    }

    private fun parseObject(): JsonValue.Obj {
        expect('{')
        skipWhitespace()
        val members = linkedMapOf<String, JsonValue>()
        if (peek() == '}') {
            index++
            return JsonValue.Obj(members)
        }
        while (index < text.length) {
            skipWhitespace()
            val key = parseString()
            skipWhitespace()
            expect(':')
            skipWhitespace()
            members[key] = parseValue()
            skipWhitespace()
            when (peek()) {
                ',' -> {
                    index++
                    continue
                }
                '}' -> {
                    index++
                    return JsonValue.Obj(members)
                }
                else -> error("Invalid JSON object")
            }
        }
        error("Unterminated JSON object")
    }

    private fun parseArray(): JsonValue.Arr {
        expect('[')
        skipWhitespace()
        val items = mutableListOf<JsonValue>()
        if (peek() == ']') {
            index++
            return JsonValue.Arr(items)
        }
        while (index < text.length) {
            items += parseValue()
            skipWhitespace()
            when (peek()) {
                ',' -> {
                    index++
                    continue
                }
                ']' -> {
                    index++
                    return JsonValue.Arr(items)
                }
                else -> error("Invalid JSON array")
            }
        }
        error("Unterminated JSON array")
    }

    private fun parseString(): String {
        expect('"')
        val out = StringBuilder()
        while (index < text.length) {
            val ch = text[index++]
            when (ch) {
                '"' -> return out.toString()
                '\\' -> {
                    val escaped = text.getOrNull(index++) ?: error("Invalid escape")
                    out.append(
                        when (escaped) {
                            '"', '\\', '/' -> escaped
                            'b' -> '\b'
                            'f' -> '\u000C'
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            'u' -> {
                                val hex = text.substring(index, index + 4)
                                index += 4
                                hex.toInt(16).toChar()
                            }
                            else -> escaped
                        }
                    )
                }
                else -> out.append(ch)
            }
        }
        error("Unterminated JSON string")
    }

    private fun parseNumber(): String {
        val start = index
        while (index < text.length) {
            val ch = text[index]
            if (ch.isDigit() || ch == '-' || ch == '+' || ch == '.' || ch == 'e' || ch == 'E') {
                index++
            } else {
                break
            }
        }
        if (start == index) error("Expected JSON value")
        return text.substring(start, index)
    }

    private fun consumeLiteral(expected: String) {
        if (!text.regionMatches(index, expected, 0, expected.length, ignoreCase = false)) {
            error("Invalid JSON literal")
        }
        index += expected.length
    }

    private fun skipWhitespace() {
        while (index < text.length && text[index].isWhitespace()) {
            index++
        }
    }

    private fun peek(): Char {
        return text.getOrNull(index) ?: error("Unexpected end of JSON")
    }

    private fun expect(ch: Char) {
        if (peek() != ch) error("Expected '$ch'")
        index++
    }
}

typealias SwiggyCallback<T> = (SwiggyMcpClient.SwiggyMcpResult<T>) -> Unit

object SwiggyMcpClient {
    sealed class SwiggyMcpResult<out T> {
        data class Success<T>(val value: T) : SwiggyMcpResult<T>()
        data class Failure(
            val userMessage: String,
            val httpCode: Int? = null,
            val reconnectRequired: Boolean = false,
            val retryable: Boolean = false
        ) : SwiggyMcpResult<Nothing>()
    }

    private const val HEADER_BACKEND_KEY = "x-beta-backend-key"
    private const val HEADER_INSTALLATION_TOKEN = "x-beta-installation-token"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
    private val safeRequestClient = provideClient(retryOnConnectionFailure = true)
    private val cartMutationClient = provideClient(retryOnConnectionFailure = false)

    enum class ConnectionState {
        DISCONNECTED,
        READY,
        RECONNECT_REQUIRED
    }

    data class Capabilities(
        val supported: List<String>,
        val reconnectRequired: Boolean = false,
        val message: String? = null
    )

    data class Status(
        val state: ConnectionState,
        val authorizationUrl: String? = null,
        val message: String? = null
    )

    data class ConnectResponse(
        val authorizationUrl: String? = null,
        val message: String? = null,
        val reconnectRequired: Boolean = false
    )

    data class SwiggyAddress(
        val id: String,
        val label: String,
        val normalizedLabel: String = label,
        val shortLabel: String = normalizedLabel,
    )

    data class RecommendationCandidate(
        val spinId: String,
        val label: String,
        val variant: String? = null,
        val subtitle: String? = null,
        val suggested: Boolean = false,
        val skuId: String? = null,
    )

    data class Recommendations(
        val candidates: List<RecommendationCandidate>,
        val suggested: RecommendationCandidate? = null,
        val requiresConfirmation: Boolean = false,
        val query: String? = null,
    )

    data class CartPlanChange(
        val spinId: String? = null,
        val kind: String,
        val displayName: String,
        val fromQuantity: Int? = null,
        val toQuantity: Int? = null,
        val description: String? = null
    )

    data class RequestedItem(
        val spinId: String,
        val quantity: Int,
        val displayName: String,
        val skuId: String? = null,
    )

    data class CartPlan(
        val changes: List<CartPlanChange>,
        val confirmationToken: String? = null,
        val cartMutationEnabled: Boolean = false,
        val message: String? = null
    )

    data class ApplyResult(
        val verified: Boolean,
        val message: String? = null,
        val reconnectRequired: Boolean = false
    )

    fun fetchCapabilities(context: Context, callback: SwiggyCallback<Capabilities>) {
        executeJsonRequest(
            context = context,
            method = "GET",
            path = "/swiggy/capabilities",
            callback = callback,
            parser = ::parseCapabilities
        )
    }

    fun fetchStatus(context: Context, callback: SwiggyCallback<Status>) {
        executeJsonRequest(
            context = context,
            method = "GET",
            path = "/swiggy/status",
            callback = callback,
            parser = ::parseStatus
        )
    }

    fun connect(context: Context, callback: SwiggyCallback<ConnectResponse>) {
        executeJsonRequest(
            context = context,
            method = "POST",
            path = "/swiggy/connect",
            body = JSONObject().toString(),
            callback = callback,
            parser = ::parseConnect
        )
    }

    fun disconnect(context: Context, callback: SwiggyCallback<Status>) {
        executeJsonRequest(
            context = context,
            method = "POST",
            path = "/swiggy/disconnect",
            body = JSONObject().toString(),
            callback = callback,
            parser = ::parseStatus
        )
    }

    fun fetchAddresses(context: Context, callback: SwiggyCallback<List<SwiggyAddress>>) {
        executeJsonRequest(
            context = context,
            method = "GET",
            path = "/swiggy/addresses",
            callback = callback,
            parser = { body ->
                parseAddresses(body).also { parsed ->
                    Log.i(
                        "BetaAgent",
                        "SWIGGY_MCP_ADDRESSES_PARSED count=${parsed.size} ${describeAddressSchema(body)}",
                    )
                }
            }
        )
    }

    fun fetchRecommendations(
        context: Context,
        addressId: String,
        query: String,
        callback: SwiggyCallback<Recommendations>
    ) {
        val path = buildString {
            append("/swiggy/recommendations?addressId=")
            append(java.net.URLEncoder.encode(addressId, "UTF-8"))
            append("&query=")
            append(java.net.URLEncoder.encode(query, "UTF-8"))
        }
        executeJsonRequest(
            context = context,
            method = "GET",
            path = path,
            callback = callback,
            parser = ::parseRecommendations
        )
    }

    fun fetchRecommendationBatch(
        context: Context,
        addressId: String,
        queries: List<String>,
        strictMatchPhrases: List<String?> = List(queries.size) { null },
        callback: SwiggyCallback<List<Recommendations>>,
    ) {
        require(strictMatchPhrases.size == queries.size) {
            "Strict match phrases must align with recommendation queries."
        }
        val body = JSONObject()
            .put("addressId", addressId)
            .put("queries", JSONArray().apply { queries.forEach(::put) })
            .put(
                "strictMatchPhrases",
                JSONArray().apply {
                    strictMatchPhrases.forEach { phrase -> put(phrase ?: JSONObject.NULL) }
                },
            )
            .toString()
        executeJsonRequest(
            context = context,
            method = "POST",
            path = "/swiggy/recommendations/batch",
            body = body,
            callback = callback,
            parser = ::parseRecommendationBatch,
            timeoutSeconds = 120,
        )
    }

    fun planCart(
        context: Context,
        addressId: String,
        requestedItems: List<RequestedItem>,
        callback: SwiggyCallback<CartPlan>
    ) {
        val body = JSONObject()
            .put("addressId", addressId)
            .put("requestedItems", JSONArray().apply {
                requestedItems.forEach { item ->
                    put(
                        JSONObject()
                            .put("spinId", item.spinId)
                            .apply { item.skuId?.takeIf { it.isNotBlank() }?.let { put("skuId", it) } }
                            .put("quantity", item.quantity)
                            .put("displayName", item.displayName)
                    )
                }
            })
            .toString()
        executeJsonRequest(
            context = context,
            method = "POST",
            path = "/swiggy/cart/plan",
            body = body,
            callback = callback,
            parser = ::parseCartPlan
        )
    }

    fun applyCartPlan(
        context: Context,
        confirmationToken: String,
        callback: SwiggyCallback<ApplyResult>
    ) {
        val body = JSONObject()
            .put("confirmationToken", confirmationToken)
            .toString()
        executeJsonRequest(
            context = context,
            method = "POST",
            path = "/swiggy/cart/apply",
            body = body,
            callback = callback,
            parser = ::parseApplyResult
        )
    }

    internal fun parseCapabilities(body: String): Capabilities {
        val root = parseRoot(body)
        val supported = firstArray(root, "supported", "capabilities", "features", "readOnlyTools", "read_only_tools", "mutatingTools", "mutating_tools")
            ?.let { jsonArrayToStringList(it) }
            ?: emptyList()
        return Capabilities(
            supported = supported,
            reconnectRequired = containsReconnectRequired(root),
            message = firstString(root, "message", "statusMessage", "status_message")
        )
    }

    internal fun parseStatus(body: String): Status {
        val root = parseRoot(body)
        val state = when {
            containsReconnectRequired(root) -> ConnectionState.RECONNECT_REQUIRED
            isTruthy(root, "ready", "isReady", "connected", "isConnected") -> ConnectionState.READY
            else -> parseConnectionState(firstString(root, "state", "status", "connectionState", "connection_state"))
        }
        return Status(
            state = state,
            authorizationUrl = firstString(root, "authorizationUrl", "authorization_url", "connectUrl", "connect_url"),
            message = firstString(root, "message", "statusMessage", "status_message")
        )
    }

    internal fun parseConnect(body: String): ConnectResponse {
        val root = parseRoot(body)
        return ConnectResponse(
            authorizationUrl = firstString(root, "authorizationUrl", "authorization_url", "connectUrl", "connect_url"),
            message = firstString(root, "message", "statusMessage", "status_message"),
            reconnectRequired = containsReconnectRequired(root)
        )
    }

    internal fun parseAddresses(body: String): List<SwiggyAddress> {
        val root = parseRoot(body)
        val array = firstArray(root, "addresses", "data", "items", "results")
            ?: JsonValue.Arr(emptyList())
        return array.items.mapIndexed { index, value ->
            when (value) {
                is JsonValue.Obj -> parseAddressObject(value, index)
                else -> null
            }
        }.filterNotNull().filter { it.id.isNotBlank() && it.normalizedLabel.isNotBlank() }
    }

    internal fun parseRecommendations(body: String): Recommendations {
        return parseRecommendationsValue(parseRoot(body))
    }

    internal fun parseRecommendationBatch(body: String): List<Recommendations> {
        val root = parseRoot(body)
        val results = firstArray(root, "results")
            ?: throw IllegalArgumentException("Swiggy recommendation batch has no results")
        return results.items.map(::parseRecommendationsValue)
    }

    private fun parseRecommendationsValue(root: JsonValue?): Recommendations {
        val array = firstArray(root, "candidates", "recommendations", "items", "results")
            ?: JsonValue.Arr(emptyList())
        val candidates = array.items.mapIndexedNotNull { index, value ->
            when (value) {
                is JsonValue.Obj -> {
                    val spinId = firstString(value, "spinId", "spin_id", "id", "productId", "product_id")
                    val label = firstString(value, "label", "name", "title", "productName", "product_name")
                    if (spinId.isNullOrBlank() || label.isNullOrBlank()) null
                    else parseRecommendationCandidate(value, index)
                }
                else -> null
            }
        }
        val suggested = firstObject(root, "suggested", "recommended", "default", "primaryCandidate")?.let {
            val spinId = firstString(it, "spinId", "spin_id", "id", "productId", "product_id")
            val label = firstString(it, "label", "name", "title", "productName", "product_name")
            if (spinId.isNullOrBlank() || label.isNullOrBlank()) null else parseRecommendationCandidate(it, -1)
        } ?: candidates.firstOrNull { it.suggested }
        val confirmationValue = findField(
            root,
            "requiresConfirmation",
            "requires_confirmation",
            "confirmationRequired",
        )
        return Recommendations(
            candidates = candidates,
            suggested = suggested,
            requiresConfirmation = confirmationValue == null || isTruthy(
                root,
                "requiresConfirmation",
                "requires_confirmation",
                "confirmationRequired",
            ),
            query = firstString(root, "query"),
        )
    }

    internal fun parseCartPlan(body: String): CartPlan {
        val root = parseRoot(body)
        val array = firstArray(root, "changes", "cartChanges", "cart_changes", "operations")
            ?: JsonValue.Arr(emptyList())
        val changes = array.items.mapIndexed { index, value ->
            when (value) {
                is JsonValue.Obj -> parseCartPlanChange(value, index)
                is JsonValue.Str -> CartPlanChange(
                    kind = "note",
                    displayName = normalizeLabel(value.value),
                    description = normalizeLabel(value.value)
                )
                else -> CartPlanChange(
                    kind = "note",
                    displayName = "Change ${index + 1}",
                    description = "Change ${index + 1}"
                )
            }
        }
        return CartPlan(
            changes = changes,
            confirmationToken = firstString(root, "confirmationToken", "confirmation_token", "token"),
            cartMutationEnabled = isTruthy(root, "cartMutationEnabled", "cart_mutation_enabled", "mutationEnabled"),
            message = firstString(root, "message", "statusMessage", "status_message")
        )
    }

    internal fun parseApplyResult(body: String): ApplyResult {
        val root = parseRoot(body)
        return ApplyResult(
            verified = isTruthy(root, "verified", "applied", "success", "cartVerified", "cart_verified"),
            message = firstString(root, "message", "statusMessage", "status_message"),
            reconnectRequired = containsReconnectRequired(root)
        )
    }

    internal fun normalizeLabel(value: String): String {
        return value
            .replace(Regex("\\s+"), " ")
            .replace(Regex("\\s*,\\s*"), ", ")
            .trim()
    }

    private fun provideClient(retryOnConnectionFailure: Boolean): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(retryOnConnectionFailure)

        return builder.build()
    }

    private fun <T> executeJsonRequest(
        context: Context,
        method: String,
        path: String,
        body: String? = null,
        callback: SwiggyCallback<T>,
        parser: (String) -> T,
        timeoutSeconds: Long? = null,
    ) {
        val installationToken = runCatching {
            SwiggyInstallationIdentity.installationToken(context)
        }.getOrElse {
            callback(
                SwiggyMcpResult.Failure(
                    userMessage = "Beta could not open secure storage for the Swiggy connection.",
                )
            )
            return
        }
        val requestBuilder = Request.Builder()
            .url("${AppConfig.backendBaseUrl}$path")
            .header(HEADER_BACKEND_KEY, AppConfig.backendApiKey)
            .header(HEADER_INSTALLATION_TOKEN, installationToken)

        val request = when (method) {
            "POST" -> requestBuilder
                .post((body ?: JSONObject().toString()).toRequestBody(jsonMediaType))
                .build()
            else -> requestBuilder.build()
        }

        val baseClient = if (isCartMutationPath(path)) cartMutationClient else safeRequestClient
        val requestClient = timeoutSeconds?.let { seconds ->
            baseClient.newBuilder().readTimeout(seconds, TimeUnit.SECONDS).build()
        } ?: baseClient
        val call = requestClient.newCall(request)
        timeoutSeconds?.let { call.timeout().timeout(it, TimeUnit.SECONDS) }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(SwiggyMcpResult.Failure(userMessage = swiggyNetworkFailureMessage(path)))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val responseBody = it.body?.string().orEmpty()
                    if (it.isSuccessful) {
                        try {
                            callback(SwiggyMcpResult.Success(parser(responseBody)))
                        } catch (e: Exception) {
                            callback(SwiggyMcpResult.Failure(userMessage = "Swiggy backend returned an unreadable response."))
                        }
                    } else {
                        callback(SwiggyMcpResult.Failure(
                            userMessage = userMessageForHttpCode(it.code, responseBody),
                            httpCode = it.code,
                            reconnectRequired = isReconnectRequired(responseBody),
                            retryable = it.code >= 500
                        ))
                    }
                }
            }
        })
    }

    internal fun describeAddressSchema(body: String): String {
        val root = parseRoot(body)
        val rootKeys = (root as? JsonValue.Obj)?.members?.keys.orEmpty().take(12)
        val addressArray = firstArray(root, "addresses", "items", "results")
        val firstAddressKeys = (addressArray?.items?.firstOrNull() as? JsonValue.Obj)
            ?.members
            ?.keys
            .orEmpty()
            .take(16)
        return "rootKeys=${rootKeys.joinToString("|")} addressEntries=${addressArray?.items?.size ?: -1} firstAddressKeys=${firstAddressKeys.joinToString("|")}"
    }

    internal fun swiggyNetworkFailureMessage(path: String): String {
        return if (path == "/swiggy/cart/apply") {
            "Swiggy may have changed the cart, but Beta lost the connection before it could verify it. Please open Swiggy and review the cart before trying again."
        } else {
            "Unable to reach Swiggy backend right now."
        }
    }

    internal fun isCartMutationPath(path: String): Boolean = path == "/swiggy/cart/apply"

    private fun userMessageForHttpCode(code: Int, body: String): String {
        val reason = firstString(parseRoot(body), "reason")
        return when {
            isReconnectRequired(body) -> "Swiggy connection needs to be reconnected."
            reason == "cart_changed_replan_required" -> "Your Swiggy cart changed. Please repeat the voice order so Beta can check it again."
            reason == "cart_confirmation_already_used" -> "That cart confirmation was already used. Please repeat the voice order."
            reason == "cart_confirmation_expired" -> "That cart review expired. Please repeat the voice order so Beta can check the cart again."
            reason == "cart_update_in_progress" -> "Another Swiggy cart update is still finishing. Please wait a moment, then repeat the voice order."
            reason == "cart_update_outcome_unknown" -> "Swiggy may have changed the cart, but Beta could not verify it. Please open Swiggy and review the cart before trying again."
            reason == "cart_update_not_verified" -> "Swiggy did not match the reviewed cart change. Please open Swiggy and review the cart before trying again."
            reason == "cart_address_unverified" -> "Beta could not verify the current Swiggy delivery address for this cart. Please reopen Swiggy and choose the right address again."
            reason == "cart_address_mismatch" -> "Swiggy's current cart address does not match the selected delivery address. Please choose the right address again."
            reason == "cart_schema_unrecognized" -> "Beta could not safely read this Swiggy cart. Nothing was changed."
            reason == "cart_mutation_disabled" -> "Swiggy cart updates are not enabled yet. Nothing was changed."
            reason == "cart_no_changes" -> "Your Swiggy cart already has those quantities."
            code == 401 || code == 403 -> "Swiggy authorization is no longer valid."
            code == 404 -> "Swiggy setup is not ready yet. Please try again later."
            code in 500..599 -> "Swiggy backend is temporarily unavailable."
            else -> "Swiggy backend request failed."
        }
    }

    private fun isReconnectRequired(body: String): Boolean {
        return containsReconnectRequired(parseRoot(body))
    }

    private fun parseConnectionState(raw: String?): ConnectionState {
        val normalized = raw?.trim()?.lowercase().orEmpty()
        return when {
            normalized.contains("reconnect") || normalized.contains("reauth") -> ConnectionState.RECONNECT_REQUIRED
            normalized in setOf("ready", "connected", "active") -> ConnectionState.READY
            else -> ConnectionState.DISCONNECTED
        }
    }

    private fun parseAddressObject(value: JsonValue.Obj, index: Int): SwiggyAddress {
        val label = normalizeLabel(
            firstString(
                value,
                "label",
                "name",
                "title",
                "displayText",
                "display_text",
                "address",
                "formattedAddress",
                "formatted_address",
                "text",
                "addressLine"
            ) ?: buildAddressLabel(value)
        )
        val providerId = firstString(value, "id", "addressId", "address_id", "value", "placeId", "place_id")
            ?.trim()
            .orEmpty()
        return SwiggyAddress(
            id = providerId,
            label = label,
            normalizedLabel = label,
            shortLabel = conciseAddressLabel(
                fullLabel = label,
                category = firstString(value, "addressCategory", "address_category", "category", "type"),
                tag = firstString(value, "addressTag", "address_tag", "tag"),
                index = index,
            ),
        )
    }

    internal fun conciseAddressLabel(
        fullLabel: String,
        category: String?,
        tag: String?,
        index: Int,
    ): String {
        val categoryLabel = readableAddressName(category).takeIf(::isUsefulAddressName)
        val tagLabel = readableAddressName(tag).takeIf(::isUsefulAddressName)
        val name = categoryLabel ?: tagLabel ?: "Saved address ${index + 1}"
        val locality = deriveAddressLocality(fullLabel)
            ?.takeUnless { it.equals(name, ignoreCase = true) }
        return listOfNotNull(name, locality).joinToString(" — ").take(64)
    }

    private fun isUsefulAddressName(value: String): Boolean {
        return value.isNotBlank() &&
            value.length <= 32 &&
            value.lowercase() !in setOf("address", "saved address", "other")
    }

    private fun readableAddressName(value: String?): String {
        val normalized = normalizeLabel(value.orEmpty())
        return if (normalized.length > 1 && normalized.all { !it.isLetter() || it.isUpperCase() }) {
            normalized.lowercase().replaceFirstChar { it.uppercase() }
        } else {
            normalized
        }
    }

    private fun deriveAddressLocality(fullLabel: String): String? {
        val segments = normalizeLabel(fullLabel)
            .split(',')
            .map(String::trim)
            .filter(String::isNotBlank)
            .toMutableList()
        if (segments.size < 2) return null
        while (segments.isNotEmpty()) {
            val last = segments.last()
            val normalized = last.lowercase()
            val isCountry = normalized in setOf("india", "bharat")
            val isStateOrPostcode = Regex("\\b\\d{6}\\b").containsMatchIn(last) ||
                normalized in INDIAN_STATE_NAMES
            if (!isCountry && !isStateOrPostcode) break
            segments.removeAt(segments.lastIndex)
        }
        return segments.lastOrNull()
            ?.takeIf { it.length in 2..40 && !Regex("\\b\\d{6}\\b").containsMatchIn(it) }
    }

    private val INDIAN_STATE_NAMES = setOf(
        "andhra pradesh", "assam", "bihar", "chhattisgarh", "delhi", "goa", "gujarat",
        "haryana", "himachal pradesh", "jharkhand", "karnataka", "kerala", "madhya pradesh",
        "maharashtra", "odisha", "punjab", "rajasthan", "tamil nadu", "telangana",
        "uttar pradesh", "uttarakhand", "west bengal",
    )

    private fun buildAddressLabel(value: JsonValue.Obj): String {
        val parts = listOf(
            firstString(value, "line1", "addressLine1", "address_line_1"),
            firstString(value, "addressLine"),
            firstString(value, "line2", "addressLine2", "address_line_2"),
            firstString(value, "area", "locality", "neighborhood"),
            firstString(value, "city", "town"),
            firstString(value, "state"),
            firstString(value, "pincode", "pinCode", "postalCode")
        ).filterNotNull().map { normalizeLabel(it) }.filter { it.isNotBlank() }
        return parts.joinToString(", ")
    }

    private fun parseRecommendationCandidate(value: JsonValue.Obj, index: Int): RecommendationCandidate {
        val label = normalizeLabel(
            firstString(
                value,
                "label",
                "name",
                "title",
                "displayText",
                "display_text",
                "query",
                "text"
            ) ?: "Suggestion ${index + 1}"
        )
        return RecommendationCandidate(
            spinId = firstString(value, "spinId", "spin_id", "id", "candidateId", "candidate_id", "value", "slug")
                ?: label.ifBlank { index.toString() },
            skuId = firstString(value, "skuId", "sku_id"),
            label = label,
            variant = firstString(value, "variant", "pack", "packSize", "pack_size", "size", "quantityLabel"),
            subtitle = firstString(value, "subtitle", "description", "details"),
            suggested = isTruthy(value, "suggested", "isSuggested", "is_suggested", "recommended")
        )
    }

    private fun parseCartPlanChange(value: JsonValue.Obj, index: Int): CartPlanChange {
        val kind = firstString(value, "kind", "type", "action", "operation") ?: "change"
        val label = normalizeLabel(
            firstString(value, "displayName", "display_name", "label", "name", "title", "item", "description")
                ?: "Change ${index + 1}"
        )
        return CartPlanChange(
            spinId = firstString(value, "spinId", "spin_id", "productId", "product_id"),
            kind = kind,
            displayName = label,
            fromQuantity = firstInt(value, "fromQuantity", "from_quantity", "quantityBefore", "quantity_before"),
            toQuantity = firstInt(value, "toQuantity", "to_quantity", "quantityAfter", "quantity_after", "quantity"),
            description = firstString(value, "description", "details", "notes")
        )
    }

    private fun parseRoot(body: String): JsonValue? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        return runCatching { JsonParser(trimmed).parse() }.getOrNull()
    }

    private fun containsReconnectRequired(root: JsonValue?): Boolean {
        return when (root) {
            is JsonValue.Obj -> isTruthy(root, "reconnectRequired", "reconnect_required", "reauthRequired", "reauth_required") ||
                firstString(root, "reason", "error", "code", "detail")?.equals("swiggy_reconnect_required", ignoreCase = true) == true
            is JsonValue.Arr -> root.items.any { containsReconnectRequired(it) }
            else -> false
        }
    }

    private fun isTruthy(root: JsonValue?, vararg keys: String): Boolean {
        return when (root) {
            is JsonValue.Obj -> keys.any { key ->
                when (val value = findField(root, key)) {
                    is JsonValue.Bool -> value.value
                    is JsonValue.Num -> value.raw.toDoubleOrNull()?.let { it != 0.0 } ?: false
                    is JsonValue.Str -> value.value.equals("true", ignoreCase = true) ||
                        value.value.equals("yes", ignoreCase = true) ||
                        value.value.equals("1", ignoreCase = true) ||
                        value.value.equals("ready", ignoreCase = true) ||
                        value.value.equals("connected", ignoreCase = true)
                    else -> false
                }
            }
            else -> false
        }
    }

    private fun firstString(root: JsonValue?, vararg keys: String): String? {
        return when (val value = findField(root, *keys)) {
            is JsonValue.Str -> value.value.takeIf { it.isNotBlank() }
            is JsonValue.Num -> value.raw.takeIf { it.isNotBlank() }
            is JsonValue.Bool -> value.value.toString()
            else -> null
        }
    }

    private fun firstString(root: JsonValue.Obj): String? {
        return firstString(root, "value", "label", "name", "title", "text", "message")
    }

    private fun firstInt(root: JsonValue?, vararg keys: String): Int? {
        return when (val value = findField(root, *keys)) {
            is JsonValue.Num -> value.raw.toIntOrNull()
            is JsonValue.Str -> value.value.toIntOrNull()
            is JsonValue.Bool -> if (value.value) 1 else 0
            else -> null
        }
    }

    private fun firstArray(root: JsonValue?, vararg keys: String): JsonValue.Arr? {
        return when (val value = findField(root, *keys)) {
            is JsonValue.Arr -> value
            else -> null
        }
    }

    private fun firstObject(root: JsonValue?, vararg keys: String): JsonValue.Obj? {
        return when (val value = findField(root, *keys)) {
            is JsonValue.Obj -> value
            else -> null
        }
    }

    private fun jsonArrayToStringList(array: JsonValue.Arr): List<String> {
        return array.items.mapNotNull { value ->
            when (value) {
                is JsonValue.Str -> value.value.takeIf { it.isNotBlank() }?.let(::normalizeLabel)
                is JsonValue.Num -> value.raw.takeIf { it.isNotBlank() }?.let(::normalizeLabel)
                is JsonValue.Bool -> value.value.toString()
                is JsonValue.Obj -> firstString(value) ?: firstString(value, "value", "id", "key")
                else -> null
            }
        }
    }

    private fun findField(root: JsonValue?, vararg keys: String): JsonValue? {
        // Preserve caller priority. A hash set made response parsing depend on
        // iteration order (for example, selecting `data` before `addresses`).
        for (key in keys) {
            val value = findFieldRecursive(root, setOf(key))
            if (value != null) return value
        }
        return null
    }

    private fun findFieldRecursive(root: JsonValue?, keys: Set<String>): JsonValue? {
        return when (root) {
            is JsonValue.Obj -> {
                for (key in keys) {
                    root.members[key]?.let { return it }
                }
                for (child in root.members.values) {
                    val nested = findFieldRecursive(child, keys)
                    if (nested != null) return nested
                }
                null
            }
            is JsonValue.Arr -> {
                for (child in root.items) {
                    val nested = findFieldRecursive(child, keys)
                    if (nested != null) return nested
                }
                null
            }
            else -> null
        }
    }
}
