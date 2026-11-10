package com.example.beta

import android.content.Context
import android.util.Log
import com.example.beta.automation.IntentCartQuantityMode
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.io.IOException
import java.util.concurrent.TimeUnit

internal sealed class JsonValue {
    data object Null : JsonValue()
    data class Bool(val value: Boolean) : JsonValue()
    data class Num(val raw: String) : JsonValue()
    data class Str(val value: String) : JsonValue()
    data class Obj(val members: Map<String, JsonValue>, val duplicateKeys: Set<String> = emptySet()) : JsonValue()
    data class Arr(val items: List<JsonValue>) : JsonValue()
}

internal class JsonParser(private val text: String) {
    private var index = 0
    private var strict = false

    fun parse(): JsonValue? {
        skipWhitespace()
        if (index >= text.length) return null
        val value = parseValue()
        skipWhitespace()
        return value
    }

    fun parseStrict(): JsonValue {
        strict = true
        val value = parse() ?: error("Empty JSON")
        skipWhitespace()
        require(index == text.length) { "Trailing JSON content" }
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
        val duplicateKeys = mutableSetOf<String>()
        if (peek() == '}') {
            index++
            return JsonValue.Obj(members, duplicateKeys)
        }
        while (index < text.length) {
            skipWhitespace()
            val key = parseString()
            skipWhitespace()
            expect(':')
            skipWhitespace()
            if (key in members) {
                require(!strict) { "Duplicate JSON field" }
                duplicateKeys += key
            }
            members[key] = parseValue()
            skipWhitespace()
            when (peek()) {
                ',' -> {
                    index++
                    continue
                }
                '}' -> {
                    index++
                    return JsonValue.Obj(members, duplicateKeys)
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
                            else -> {
                                require(!strict) { "Invalid JSON escape" }
                                escaped
                            }
                        }
                    )
                }
                else -> {
                    require(!strict || ch.code >= 0x20) { "Unescaped JSON control" }
                    out.append(ch)
                }
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
        return text.substring(start, index).also {
            require(!strict || Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?").matches(it)) {
                "Invalid JSON number"
            }
        }
    }

    private fun consumeLiteral(expected: String) {
        if (!text.regionMatches(index, expected, 0, expected.length, ignoreCase = false)) {
            error("Invalid JSON literal")
        }
        index += expected.length
    }

    private fun skipWhitespace() {
        while (index < text.length && if (strict) text[index] in " \t\r\n" else text[index].isWhitespace()) {
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
            val retryable: Boolean = false,
            val orderNotSubmitted: Boolean = false,
            val intentNeedsReview: Boolean = false,
            val intentUnavailable: Boolean = false,
            // A plan-only outcome, not evidence of a purchase or verified cart success.
            val cartPlanUnchanged: Boolean = false,
            // Edit-only offsets. No rejected draft, inferred product or raw source travels here.
            val intentEditRanges: List<IntRange> = emptyList(),
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
        val categoryLabel: String = shortLabel.substringBefore(" —").trim(),
        val confirmationDetail: String? = null,
        val hasCurrentCart: Boolean = false,
    )

    data class CatalogueBrandEvidence(
        val name: String,
        val spinId: String,
        val skuId: String,
        val source: String,
    )

    /** Negative evidence only, expressed in sellable units of this exact live variation. */
    data class CataloguePurchaseLimit(val spinId: String, val skuId: String, val maxUnits: Int) {
        fun validFor(spin: String, sku: String?) = spinId == spin && skuId == sku && maxUnits in 1..999 &&
            opaqueId.matches(spinId) && opaqueId.matches(skuId)
        private companion object { val opaqueId = Regex("[A-Za-z0-9_-]{1,128}") }
    }

    data class RecommendationCandidate(
        val spinId: String,
        val label: String,
        val variant: String? = null,
        val subtitle: String? = null,
        val suggested: Boolean = false,
        val skuId: String? = null,
        val catalogueBrand: CatalogueBrandEvidence? = null,
        val catalogueQuantity: CatalogueQuantityEvidence? = null,
        val catalogueQuantityInvalid: Boolean = false,
        val catalogueIngredients: CatalogueIngredientEvidence? = null,
        val catalogueIngredientsInvalid: Boolean = false,
        val cataloguePurchaseLimit: CataloguePurchaseLimit? = null,
        val cataloguePurchaseLimitInvalid: Boolean = false,
        // Derived locally from another observation of the same sellable SKU; deny only.
        val catalogueVesselRole: String? = null,
        val sellerAssessment: SwiggySellerAssessment? = null,
    )

    data class Recommendations(
        val candidates: List<RecommendationCandidate>,
        val suggested: RecommendationCandidate? = null,
        val requiresConfirmation: Boolean = false,
        val query: String? = null,
        val usualProductUnavailable: Boolean = false,
        val catalogueMatchUnverified: Boolean = false,
        val searchProductsUnavailable: Boolean = false,
    ) {
        /** Diagnostic only: absence of extractable offers is NOT proof about seller inventory. */
        val emptyCatalogueDisposition: String
            get() = when {
                candidates.isNotEmpty() -> "not_empty"
                catalogueMatchUnverified && searchProductsUnavailable -> "server_match_filtered_and_upstream_search_unavailable"
                catalogueMatchUnverified -> "server_match_filtered"
                searchProductsUnavailable -> "upstream_search_unavailable"
                else -> "no_extractable_or_unclassified"
            }
    }

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
        val quantityMode: IntentCartQuantityMode = IntentCartQuantityMode.TARGET,
        // Local veto for merged rows / incremental plans. Never trust or send it as cart authority.
        val cataloguePurchaseLimit: CataloguePurchaseLimit? = null,
    )

    data class CartPlan(
        val changes: List<CartPlanChange>,
        val existingItemCount: Int? = null,
        val confirmationToken: String? = null,
        val cartMutationEnabled: Boolean = false,
        val message: String? = null,
        val snapshot: CartPlanSnapshot? = null,
    ) {
        override fun toString(): String = "CartPlan(changes=<redacted>, token=<redacted>, snapshot=<redacted>)"
    }

    data class CartPlanItem(val spinId: String, val quantity: Int, val skuId: String? = null) {
        override fun toString(): String = "CartPlanItem(<redacted>)"
    }

    data class CartPlanSnapshot(
        val existingItems: List<CartPlanItem>,
        val proposedItems: List<CartPlanItem>,
        // Equality only; this receipt is never an apply token or source proof.
        val reviewBasis: String? = null,
    ) {
        override fun toString(): String = "CartPlanSnapshot(<redacted>)"
    }

    /** Equality-only baseline. Deliberately has no token or mutation capability. */
    data class CartReviewSnapshot(val existingItems: List<CartPlanItem>, val reviewBasis: String) {
        override fun toString(): String = "CartReviewSnapshot(<redacted>)"
    }

    data class ApplyResult(
        val verified: Boolean,
        val message: String? = null,
        val reconnectRequired: Boolean = false,
        val persistenceVerified: Boolean = false,
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

    fun fetchRecentAddressIds(context: Context, callback: SwiggyCallback<List<String>>) {
        executeJsonRequest(
            context = context,
            method = "GET",
            path = "/swiggy/orders",
            callback = callback,
            parser = { body ->
                parseRecentAddressIds(body).also { ids ->
                    Log.i("BetaAgent", "SWIGGY_MCP_RECENT_ADDRESS_IDS_PARSED count=${ids.size}")
                }
            },
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
        ingredientProofs: List<IntentIngredientProvenance?> = List(queries.size) { null },
        sourceItems: List<com.example.beta.automation.ParsedItem>? = null,
        sellerAssessmentProvider: GroceryIntentProvider? = null,
        sourceOffset: Int? = null,
        callback: SwiggyCallback<List<Recommendations>>,
    ) {
        val wire = recommendationBatchWire(addressId, queries, strictMatchPhrases, ingredientProofs, sourceItems,
            sellerAssessmentProvider, sourceOffset)
        executeJsonRequest(
            context = context,
            method = "POST",
            path = "/swiggy/recommendations/batch",
            postWire = wire,
            callback = callback,
            parser = wire::parse,
            timeoutSeconds = 120,
        )
    }

    /** Keep the expected ingredient evidence paired with the exact request that asked for it. */
    internal class RecommendationBatchFields(
        val body: Map<String, Any?>,
        private val expectedPredicates: List<List<IngredientPredicate>>?,
        private val expectedSellerProperties: List<SellerPropertyRequirement?>?,
        private val expectMerchandiseEvidence: Boolean,
    ) {
        fun parse(responseBody: String): List<Recommendations> =
            parseRecommendationBatch(responseBody, expectedPredicates, expectedSellerProperties,
                expectMerchandiseEvidence = expectMerchandiseEvidence)
    }

    internal class RecommendationBatchWire(private val projection: RecommendationBatchFields) :
        ProfiledPost("/swiggy/recommendations/batch", JSONObject(projection.body).toString(), "X-Beta-Catalog-Profile" to "validated-window-1") {
        fun parse(responseBody: String) = projection.parse(responseBody)
    }

    internal fun recommendationBatchWire(
        addressId: String,
        queries: List<String>,
        strictMatchPhrases: List<String?>,
        ingredientProofs: List<IntentIngredientProvenance?>,
        sourceItems: List<com.example.beta.automation.ParsedItem>? = null,
        sellerAssessmentProvider: GroceryIntentProvider? = null,
        sourceOffset: Int? = null,
    ) = RecommendationBatchWire(recommendationBatchFields(addressId, queries, strictMatchPhrases, ingredientProofs,
        sourceItems, sellerAssessmentProvider, sourceOffset))

    internal fun recommendationBatchFields(addressId: String, queries: List<String>, strictMatchPhrases: List<String?>,
        ingredientProofs: List<IntentIngredientProvenance?>, sourceItems: List<com.example.beta.automation.ParsedItem>? = null,
        sellerAssessmentProvider: GroceryIntentProvider? = null, sourceOffset: Int? = null): RecommendationBatchFields {
        // A window may borrow authority only from its complete unchanged source basket, not other rows or a mode flag.
        val interpreted = sourceItems?.any { it.hasAuditedIntent() } == true
        require(!interpreted || auditedIntentBasketMatches(requireNotNull(sourceItems))) { "Incomplete interpreted basket" }
        val window = if (sourceOffset == null) sourceItems else {
            require(interpreted && sourceItems != null && queries.isNotEmpty() && sourceOffset in 0..(sourceItems.size - queries.size))
            sourceItems.subList(sourceOffset, sourceOffset + queries.size)
        }
        val predicates = recommendationPredicates(queries, strictMatchPhrases, ingredientProofs, window)
        val sellers = recommendationSellerProperties(queries, strictMatchPhrases, window)?.takeIf { rows -> rows.any { it != null } }
        val fields = linkedMapOf<String, Any?>("addressId" to addressId, "queries" to queries.toList(),
            "strictMatchPhrases" to strictMatchPhrases.toList())
        predicates?.let { fields["ingredientPredicates"] = it.map { row -> row.map(IngredientPredicate::toPayload) } }
        sellers?.let { fields["sellerProperties"] = it.map { row -> row?.toPayload() }
            sellerAssessmentProvider?.let { provider -> fields["sellerAssessmentProvider"] = provider.requestValue } }
        window?.let(::auditedPhysicalTotals)?.let { fields["auditedPhysicalTotals"] =
            it.map { total -> total?.let { (amount, unit) -> mapOf("amount" to amount, "unit" to unit) } } }
        if (interpreted) fields["includeMerchandiseEvidence"] = true
        return RecommendationBatchFields(fields, predicates, sellers, interpreted)
    }

    /** Optional read-only evidence for the backend: source-audited physical totals only.
     * A null row means this aligned basket row is not independently eligible for this contract. */
    internal fun auditedPhysicalTotals(items: List<com.example.beta.automation.ParsedItem>): List<Pair<Int, String>?>? {
        if (items.none { it.hasAuditedIntent() } || !auditedIntentBasketMatches(items)) return null
        return items.map { item ->
            val proof = item.auditedIntent() ?: return@map null
            val atom = proof.amountAtoms().singleOrNull() ?: return@map null
            if (proof.role != "physical_total" || proof.packAtom != null ||
                atom.source != item.rawText || atom.source.length !in 1..640) return@map null
            val (amount, unit) = when (val q = item.quantity) {
                is com.example.beta.automation.Quantity.Weight -> q.grams to "g"
                is com.example.beta.automation.Quantity.Volume -> q.ml to "ml"
                else -> return@map null
            }
            if (amount !in 1..999_999_999 ||
                GroceryIntentInterpretedAmounts(atom.source).bind(amount.toBigDecimal(), unit) != atom) null
            else amount to unit
        }.takeIf { rows -> rows.any { it != null } }
    }

    /** Same pure mapping for production requests and offline contract tests. */
    internal fun recommendationPredicates(
        queries: List<String>, strictMatchPhrases: List<String?>,
        ingredientProofs: List<IntentIngredientProvenance?>,
        sourceItems: List<com.example.beta.automation.ParsedItem>? = null,
    ): List<List<IngredientPredicate>>? {
        require(strictMatchPhrases.size == queries.size) {
            "Strict match phrases must align with recommendation queries."
        }
        require(ingredientProofs.size == queries.size)
        return if (sourceItems != null) {
            require(sourceItems.size == queries.size && sourceItems.map(::swiggyRecommendationQuery) == queries &&
                sourceItems.map { it.strictMatchPhrase } == strictMatchPhrases &&
                sourceItems.map { it.ingredientProvenance } == ingredientProofs)
            sourceItems.map { requireNotNull(validatedIngredientPredicates(it)) { "Ingredient provenance changed" } }
        } else ingredientProofs.takeIf { it.any { proof -> proof != null } }?.map { it?.predicates().orEmpty() }
    }

    /** Source-bound seller qualifiers share the exact query/source alignment. */
    internal fun recommendationSellerProperties(
        queries: List<String>, strictMatchPhrases: List<String?>,
        sourceItems: List<com.example.beta.automation.ParsedItem>? = null,
    ): List<SellerPropertyRequirement?>? {
        require(strictMatchPhrases.size == queries.size) {
            "Strict match phrases must align with recommendation queries."
        }
        return sourceItems?.also { items ->
            require(items.size == queries.size && items.map(::swiggyRecommendationQuery) == queries &&
                items.map { it.strictMatchPhrase } == strictMatchPhrases)
        }?.map(::validatedSellerPropertyRequirement)
    }

    fun fetchCartReviewSnapshot(context: Context, addressId: String, callback: SwiggyCallback<CartReviewSnapshot>) {
        executeJsonRequest(
            context = context,
            method = "POST",
            path = "/swiggy/cart/snapshot",
            body = JSONObject().put("addressId", addressId).toString(),
            callback = callback,
            parser = ::parseCartReviewSnapshot,
            timeoutSeconds = 30L,
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
            .put("requestedItems", requestedItemsJson(requestedItems))
            .toString()
        executeJsonRequest(
            context = context,
            method = "POST",
            path = "/swiggy/cart/plan",
            body = body,
            callback = callback,
            parser = ::parseCartPlan,
            // Bound the whole read, including retries/body consumption. Its
            // terminal callback releases the single-flight review scheduler.
            timeoutSeconds = 30L,
        )
    }

    internal fun requestedItemsJson(requestedItems: List<RequestedItem>): JSONArray = JSONArray().apply {
        requestedItems.forEach { item ->
            put(JSONObject(requestedItemPayload(item)))
        }
    }

    internal fun requestedItemPayload(item: RequestedItem): Map<String, Any> = linkedMapOf<String, Any>(
        "spinId" to item.spinId,
    ).apply {
        item.skuId?.takeIf { it.isNotBlank() }?.let { put("skuId", it) }
        put("quantity", item.quantity)
        put("displayName", item.displayName)
        if (item.quantityMode == IntentCartQuantityMode.INCREMENT) put("quantityMode", "increment")
    }

    fun applyCartPlan(
        context: Context,
        confirmationToken: String,
        callback: SwiggyCallback<ApplyResult>
    ) {
        val body = JSONObject()
            .put("confirmationToken", confirmationToken)
            .put("persistenceCheck", true)
            .toString()
        executeJsonRequest(
            context = context,
            method = "POST",
            path = "/swiggy/cart/apply",
            body = body,
            callback = callback,
            parser = ::parseApplyResult,
            timeoutSeconds = 120L,
        )
    }

    fun checkCartPlan(context: Context, confirmationToken: String, callback: SwiggyCallback<Boolean>) {
        executeJsonRequest(
            context = context,
            method = "POST",
            path = "/swiggy/cart/check",
            body = JSONObject().put("confirmationToken", confirmationToken).toString(),
            callback = callback,
            parser = { body ->
                val root = parseRoot(body) as? JsonValue.Obj
                root?.members?.get("verified") == JsonValue.Bool(true)
            },
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
        return parseAddressesResponse(body).addresses
    }

    fun reviewCheckout(context: Context, addressId: String, callback: SwiggyCallback<SwiggyCheckoutReview>) {
        executeJsonRequest(context, "POST", "/swiggy/checkout/review",
            JSONObject().put("addressId", addressId).toString(), callback, ::parseCheckoutReview, 60)
    }

    fun placeCheckout(context: Context, quoteToken: String, methodId: String, attemptId: String,
                      callback: SwiggyCallback<SwiggyCheckoutAttempt>) {
        executeJsonRequest(context, "POST", "/swiggy/checkout/place",
            JSONObject().put("quoteToken", quoteToken).put("methodId", methodId)
                .put("attemptId", attemptId).put("confirmed", true).toString(), callback, ::parseCheckoutAttempt, 110)
    }

    fun checkoutStatus(context: Context, attemptId: String?, callback: SwiggyCallback<SwiggyCheckoutAttempt>) {
        executeJsonRequest(context, "POST", "/swiggy/checkout/status",
            JSONObject().apply { if (attemptId != null) put("attemptId", attemptId) }.toString(),
            callback, ::parseCheckoutAttempt, 60)
    }

    private fun checkoutObject(value: JsonValue?): JsonValue.Obj = value as? JsonValue.Obj ?: error("Invalid checkout object")
    private fun checkoutText(root: JsonValue.Obj, key: String, max: Int = 2000): String =
        (root.members[key] as? JsonValue.Str)?.value?.takeIf { it.isNotBlank() && it.length <= max }
            ?: error("Invalid checkout field")
    private fun checkoutLong(root: JsonValue.Obj, key: String): Long =
        (root.members[key] as? JsonValue.Num)?.raw?.toLongOrNull()?.takeIf { it > 0 }
            ?: error("Invalid checkout number")
    private fun checkoutMoney(root: JsonValue.Obj, key: String, signed: Boolean = false): String {
        val amount = checkoutText(root, key, 20)
        require(amount.matches(Regex(if (signed) "-?[0-9]+\\.[0-9]{2}" else "[0-9]+\\.[0-9]{2}")))
        require(java.math.BigDecimal(amount).abs() <= java.math.BigDecimal("100000"))
        return amount
    }
    private fun checkoutArray(root: JsonValue.Obj, key: String): List<JsonValue> =
        (root.members[key] as? JsonValue.Arr)?.items ?: error("Missing checkout list")

    internal fun parseCheckoutReview(body: String): SwiggyCheckoutReview {
        val root = checkoutObject(JsonParser(body).parse())
        val items = checkoutArray(root, "items").map {
            val item = checkoutObject(it)
            val quantity = checkoutLong(item, "quantity").also { n -> require(n <= 100) }.toInt()
            SwiggyCheckoutLine(checkoutText(item, "name", 500), quantity,
                (item.members["variant"] as? JsonValue.Str)?.value.orEmpty(), checkoutMoney(item, "unitPrice"))
        }.also { require(it.size in 1..100) }
        val charges = checkoutArray(root, "charges").map {
            val charge = checkoutObject(it)
            SwiggyCheckoutCharge(checkoutText(charge, "label", 200), checkoutMoney(charge, "amount", signed = true))
        }.also { require(it.size <= 100) }
        val methods = checkoutArray(root, "methods").map {
            val method = checkoutObject(it)
            val type = checkoutText(method, "paymentMethod", 10).also { t -> require(t in setOf("UPI", "Cash", "COD")) }
            val intent = (method.members["intentApp"] as? JsonValue.Str)?.value
            val qr = method.members["generateUPIQR"] == JsonValue.Bool(true)
            if (type == "UPI") require(!intent.isNullOrBlank() xor qr) else require(intent == null && !qr)
            val id = checkoutText(method, "id", 200)
            if (intent != null) require(intent == id)
            if (type != "UPI") require(id == type)
            SwiggyCheckoutMethod(id, checkoutText(method, "label", 200), type, intent, qr)
        }.also { list -> require(list.size in 1..30 && list.map { it.id }.distinct().size == list.size) }
        val amount = checkoutMoney(root, "amount").also { require(java.math.BigDecimal(it).signum() > 0) }
        return SwiggyCheckoutReview(checkoutText(root, "quoteToken", 4096), checkoutLong(root, "expiresAt"),
            checkoutText(root, "addressId", 128), checkoutText(root, "addressLabel", 200),
            checkoutText(root, "addressFull", 2500), amount, items, charges, methods)
    }

    internal fun parseCheckoutAttempt(body: String): SwiggyCheckoutAttempt {
        val root = checkoutObject(JsonParser(body).parse())
        val state = checkoutText(root, "state", 40).also { require(it in setOf("none", "not_submitted", "dispatching", "pending_payment", "confirming",
            "placed", "failed", "cancelled", "cart_changed", "refund_initiated", "partial", "unknown")) }
        if (state == "none") {
            require(root.members["attemptId"] == null && root.members["paymentUrl"] == null)
            return SwiggyCheckoutAttempt(null, state, checkoutText(root, "message"))
        }
        val id = checkoutText(root, "attemptId", 36).also { require(java.util.UUID.fromString(it).toString() == it) }
        val ids = checkoutArray(root, "orderIds").map { (it as? JsonValue.Str)?.value?.takeIf { s -> s.isNotBlank() && s.length <= 200 } ?: error("Invalid order id") }
        require(ids.size <= 100 && ids.distinct().size == ids.size)
        if (state == "placed" || state == "pending_payment") require(ids.isNotEmpty())
        val link = (root.members["paymentUrl"] as? JsonValue.Str)?.value
        if (link != null) require(state == "pending_payment" && isSafeCheckoutPaymentUrl(link))
        val interval = if (root.members["pollAfterMs"] != null) checkoutLong(root, "pollAfterMs").also { require(it <= 120000) } else null
        val deadline = if (root.members["pollUntilEpochMs"] != null) checkoutLong(root, "pollUntilEpochMs") else null
        if (state == "pending_payment") require(interval != null && deadline != null)
        return SwiggyCheckoutAttempt(id, state, checkoutText(root, "message", 12000), ids, link, interval, deadline)
    }

    internal fun checkoutOrderNotSubmittedFlag(path: String, code: Int, body: String): Boolean {
        if (path != "/swiggy/checkout/place" || code !in setOf(400, 403, 409, 422)) return false
        val root = JsonParser(body).parse() as? JsonValue.Obj ?: return false
        val detail = root.members["detail"] as? JsonValue.Obj ?: return false
        return detail.members["orderNotSubmitted"] == JsonValue.Bool(true)
    }

    internal fun parseAddressesResponse(body: String): AddressResponse {
        val root = parseRoot(body)
        val array = firstArray(root, "addresses", "data", "items", "results")
            ?: JsonValue.Arr(emptyList())
        val currentCartAddressId = firstString(root, "currentCartAddressId", "current_cart_address_id")
        val addresses = array.items.mapIndexed { index, value ->
            when (value) {
                is JsonValue.Obj -> parseAddressObject(value, index, currentCartAddressId)
                else -> null
            }
        }.filterNotNull().filter { it.id.isNotBlank() && it.normalizedLabel.isNotBlank() }
        return AddressResponse(addresses = addresses, currentCartAddressId = currentCartAddressId)
    }

    internal fun parseRecommendations(body: String): Recommendations {
        return parseRecommendationsValue(parseRoot(body))
    }

    internal fun parseRecentAddressIds(body: String): List<String> {
        val root = parseRoot(body)
        val orders = firstArray(root, "orders", "results") ?: return emptyList()
        return orders.items.mapNotNull { value ->
            val order = value as? JsonValue.Obj ?: return@mapNotNull null
            firstString(order, "deliveryAddressId", "delivery_address_id")
                ?: firstObject(order, "deliveryAddress", "delivery_address")?.let { address ->
                    firstString(address, "addressId", "address_id", "id")
                }
        }.filter(String::isNotBlank).distinct()
    }

    internal fun parseRecommendationBatch(body: String,
        expectedIngredientPredicates: List<List<IngredientPredicate>>? = null,
        expectedSellerProperties: List<SellerPropertyRequirement?>? = null,
        expectedBrands: List<String?>? = null,
    ): List<Recommendations> = parseRecommendationBatch(body, expectedIngredientPredicates, expectedSellerProperties, expectedBrands, false)

    internal fun parseRecommendationBatch(body: String,
        expectedIngredientPredicates: List<List<IngredientPredicate>>? = null,
        expectedSellerProperties: List<SellerPropertyRequirement?>? = null,
        expectedBrands: List<String?>? = null,
        expectMerchandiseEvidence: Boolean,
    ): List<Recommendations> {
        val root = if (expectMerchandiseEvidence || expectedSellerProperties != null || expectedBrands != null)
            JsonParser(body).parseStrict() else parseRoot(body)
        val results = firstArray(root, "results")
            ?: throw IllegalArgumentException("Swiggy recommendation batch has no results")
        if (expectMerchandiseEvidence) {
            require(root is JsonValue.Obj && root.members["results"] === results &&
                (root.members["merchandiseEvidenceVersion"] as? JsonValue.Num)?.raw == "1") { "Missing merchandise evidence version" }
        }
        if (expectedBrands != null) {
            val envelope = root as? JsonValue.Obj ?: error("Missing brand receipt")
            require(envelope.duplicateKeys.isEmpty() && envelope.members["results"] === results &&
                (envelope.members["requiredBrandVersion"] as? JsonValue.Num)?.raw == "1" &&
                results.items.size == expectedBrands.size)
            results.items.forEachIndexed { i, value ->
                val row = value as? JsonValue.Obj ?: error("Missing brand receipt")
                val expected = expectedBrands[i]
                require(row.duplicateKeys.isEmpty() && row.members["candidates"] is JsonValue.Arr &&
                    row.members["requiredBrand"] == (expected?.let(JsonValue::Str) ?: JsonValue.Null))
                if (expected != null) {
                    require(expected.isNotBlank() && expected.length <= 120 && expected.none(Char::isISOControl))
                    val offers = (row.members.getValue("candidates") as JsonValue.Arr).items
                    val preferred = row.members["suggested"]
                    require(preferred == null || preferred == JsonValue.Null || preferred in offers)
                    require(offers.all { offer -> offer is JsonValue.Obj &&
                        parseCatalogueBrandEvidence(offer)?.let { sameCatalogueMaker(it.name, expected) } == true })
                }
            }
        }
        if (expectedIngredientPredicates != null) {
            val envelope = root as? JsonValue.Obj ?: error("Missing ingredient contract")
            require(envelope.duplicateKeys.isEmpty() &&
                (envelope.members["ingredientPredicateVersion"] as? JsonValue.Num)?.raw == "1" &&
                envelope.members["results"] === results && results.items.size == expectedIngredientPredicates.size)
            results.items.forEachIndexed { index, result ->
                val row = result as? JsonValue.Obj ?: error("Missing ingredient receipt")
                require(row.duplicateKeys.isEmpty())
                val predicates = row.members["ingredientPredicates"] as? JsonValue.Arr ?: error("Missing ingredient receipt")
                require(predicates.items.size <= 16)
                val parsed = predicates.items.map { value ->
                    val predicate = value as? JsonValue.Obj ?: error("Invalid ingredient receipt")
                    require(predicate.duplicateKeys.isEmpty() && predicate.members.keys == setOf("operator", "operand"))
                    IngredientPredicate.fromPayload(mapOf("operator" to
                        ((predicate.members["operator"] as? JsonValue.Str)?.value ?: error("Invalid operator")),
                        "operand" to ((predicate.members["operand"] as? JsonValue.Str)?.value ?: error("Invalid operand"))))
                }
                require(parsed == expectedIngredientPredicates[index]) { "Changed ingredient receipt" }
            }
        }
        if (expectedSellerProperties != null) {
            val envelope = root as? JsonValue.Obj ?: error("Missing seller-property contract")
            require(envelope.duplicateKeys.isEmpty() &&
                (envelope.members["sellerPropertyVersion"] as? JsonValue.Num)?.raw == "2" &&
                (envelope.members["sellerAssessmentPolicy"] as? JsonValue.Str)?.value == SwiggySellerAssessment.batchPolicy(expectedSellerProperties) &&
                envelope.members["results"] === results && results.items.size == expectedSellerProperties.size)
            results.items.forEachIndexed { index, result ->
                val row = result as? JsonValue.Obj ?: error("Missing seller-property receipt")
                require(row.duplicateKeys.isEmpty())
                val raw = row.members["sellerProperties"] ?: error("Missing seller-property receipt")
                val parsed = when (raw) {
                    JsonValue.Null -> null
                    else -> SellerPropertyRequirement.fromPayload(raw)
                }
                require(parsed == expectedSellerProperties[index]) { "Changed seller-property receipt" }
            }
        }
        val decoded = reconcileCatalogueVetoes(results.items.mapIndexed { index, value ->
            decodeRecommendations(value, expectedSellerProperties?.getOrNull(index))
        })
        if (expectedBrands != null) {
            val all = decoded.flatMap { it.candidates + listOfNotNull(it.suggested) }.groupBy { it.spinId to it.skuId }
            decoded.forEachIndexed { i, row -> expectedBrands[i]?.let { expected ->
                for (offer in row.candidates + listOfNotNull(row.suggested))
                    require(all.getValue(offer.spinId to offer.skuId).all { other ->
                        other.catalogueBrand?.let { it.spinId == other.spinId && it.skuId == other.skuId &&
                            it.source == "search_variation" && sameCatalogueMaker(it.name, expected) } == true
                    }) { "Changed catalogue maker evidence" }
            } }
        }
        return decoded
    }

    // Do not remove punctuation, accents or product words. ASCII case folding
    // is intentionally no more permissive than the backend.
    private fun sameCatalogueMaker(a: String, b: String): Boolean {
        fun key(name: String) = name.trim().split(Regex("[ \\t]+")).joinToString(" ").map {
            if (it in 'A'..'Z') it.lowercaseChar() else it
        }.joinToString("")
        return key(a) == key(b)
    }

    private fun hasRecommendationWarning(root: JsonValue?, expected: String): Boolean =
        firstArray(root, "warnings")?.items?.any { (it as? JsonValue.Str)?.value == expected } == true

    internal fun parseRecommendationsValue(root: JsonValue?): Recommendations =
        reconcileCatalogueVetoes(listOf(decodeRecommendations(root))).single()

    // A preferred copy or a second query can repeat the same SKU with a looser
    // or missing limit. A previously observed veto cannot be erased by list order.
    internal fun reconcileCatalogueVetoes(rows: List<Recommendations>): List<Recommendations> {
        val observations = rows.flatMap { it.candidates + listOfNotNull(it.suggested) }
        // A second description may reveal that the matching product words refer
        // to intended contents of empty hardware. Keep it across query/batch boundaries.
        val vesselRoles = observations.filter { !it.skuId.isNullOrBlank() }.mapNotNull { candidate ->
            sellerVesselRole(candidate)?.let { (candidate.spinId to candidate.skuId) to it }
        }.groupBy({ it.first }, { it.second }).mapValues { (_, roles) -> roles.distinct().singleOrNull() ?: "conflict" }
        // A second query cannot launder a clean copy of a SKU when another
        // observation carries invalid or conflicting variation evidence.
        val bySku = observations.filter { !it.skuId.isNullOrBlank() }.groupBy { it.spinId to it.skuId }
        val sellerPropertyConflicts = bySku.mapValues { (_, group) ->
                group.any { it.catalogueIngredientsInvalid } ||
                    group.mapNotNull { it.catalogueIngredients?.identityKey() }.distinct().size > 1 ||
                    group.map { listOf(it.label, it.variant, it.subtitle) }.distinct().size > 1
            }
        val sizeConflicts = bySku.filterValues { group -> group.size > 1 &&
            (group.any { it.catalogueQuantityIssue() != null || swiggyCatalogueQuantityConflict(it) } ||
                group.mapNotNull { it.catalogueQuantityIdentityKey() }.distinct().size > 1 ||
                group.any { it.variant?.let(::parseSingleOuterNetSize) != null } &&
                group.map { Triple(it.label, it.variant, it.catalogueQuantityIdentityKey()) }.distinct().size > 1)
        }.keys
        val evidence = observations
            .filter { it.cataloguePurchaseLimit != null || it.cataloguePurchaseLimitInvalid }
            .groupBy { it.spinId to it.skuId }
            .mapValues { (_, group) -> group.any { it.cataloguePurchaseLimitInvalid } to
                group.mapNotNull { it.cataloguePurchaseLimit }.minByOrNull { it.maxUnits } }
        if (evidence.isEmpty() && vesselRoles.isEmpty() && sellerPropertyConflicts.values.none { it } && sizeConflicts.isEmpty()) return rows
        fun bound(candidate: RecommendationCandidate): RecommendationCandidate {
            val id = candidate.spinId to candidate.skuId
            val observation = evidence[id]
            val sellerConflict = sellerPropertyConflicts[id] == true
            if (observation == null && id !in vesselRoles && !sellerConflict && id !in sizeConflicts) return candidate
            return candidate.copy(cataloguePurchaseLimit = observation?.second,
                catalogueQuantityInvalid = candidate.catalogueQuantityInvalid || id in sizeConflicts,
                cataloguePurchaseLimitInvalid = observation?.first == true, catalogueVesselRole = vesselRoles[id],
                catalogueIngredientsInvalid = candidate.catalogueIngredientsInvalid || sellerConflict)
        }
        return rows.map { it.copy(candidates = it.candidates.map(::bound), suggested = it.suggested?.let(::bound)) }
    }

    private fun decodeRecommendations(
        root: JsonValue?,
        requiredSellerAssessment: SellerPropertyRequirement? = null,
    ): Recommendations {
        if (requiredSellerAssessment != null) {
            val strictRoot = root as? JsonValue.Obj ?: error("Missing seller-assessed recommendation row")
            require(strictRoot.duplicateKeys.isEmpty())
            require(listOf("recommendations", "items", "results").none { it in strictRoot.members })
            val strictArray = strictRoot.members["candidates"] as? JsonValue.Arr
                ?: error("Missing seller-assessed candidate list")
            val strictCandidates = strictArray.items.mapIndexed { index, item ->
                val candidate = item as? JsonValue.Obj ?: error("Malformed seller-assessed candidate")
                parseRecommendationCandidate(candidate, index, requiredSellerAssessment)
            }
            require(listOf("recommended", "default", "primaryCandidate").none { it in strictRoot.members })
            val strictSuggested = when (val raw = strictRoot.members["suggested"]) {
                null, JsonValue.Null -> null
                is JsonValue.Obj -> parseRecommendationCandidate(raw, -1, requiredSellerAssessment)
                else -> error("Malformed seller-assessed suggestion")
            } ?: strictCandidates.firstOrNull { it.suggested }
            val confirmationValue = findField(strictRoot, "requiresConfirmation", "requires_confirmation", "confirmationRequired")
            return Recommendations(
                candidates = strictCandidates,
                suggested = strictSuggested,
                requiresConfirmation = confirmationValue == null || isTruthy(
                    strictRoot, "requiresConfirmation", "requires_confirmation", "confirmationRequired"),
                query = firstString(strictRoot, "query"),
                usualProductUnavailable = hasRecommendationWarning(strictRoot, "usual_product_unavailable"),
                catalogueMatchUnverified = hasRecommendationWarning(strictRoot, "catalogue_match_unverified"),
                searchProductsUnavailable = hasRecommendationWarning(strictRoot, "search_products_unavailable"),
            )
        }
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
            usualProductUnavailable = hasRecommendationWarning(root, "usual_product_unavailable"),
            catalogueMatchUnverified = hasRecommendationWarning(root, "catalogue_match_unverified"),
            searchProductsUnavailable = hasRecommendationWarning(root, "search_products_unavailable"),
        )
    }

    internal fun parseCartPlan(body: String): CartPlan {
        val envelope = JsonParser(body.trim()).parseStrict() as? JsonValue.Obj
            ?: error("Invalid cart plan response")
        val changeKeys = arrayOf("changes", "cartChanges", "cart_changes", "operations")
        val root = if ("data" in envelope.members) {
            require(envelope.members.keys.none { it in changeKeys || it in setOf(
                "confirmationToken", "confirmation_token", "token", "existingItems", "existing_items",
                "proposedItems", "reviewBasis", "cartMutationEnabled", "cart_mutation_enabled", "mutationEnabled",
            ) }) { "Conflicting cart plan envelopes" }
            envelope.members["data"] as? JsonValue.Obj ?: error("Invalid cart plan envelope")
        } else envelope
        val snapshot = parseCartPlanSnapshot(root)
        val array = if (snapshot != null) {
            directCartPlanField(root, *changeKeys) as? JsonValue.Arr ?: error("Invalid cart plan changes")
        } else firstArray(root, *changeKeys) ?: JsonValue.Arr(emptyList())
        val changes = array.items.mapIndexed { index, value ->
            if (snapshot != null) require(value is JsonValue.Obj) { "Invalid cart plan change" }
            when (value) {
                is JsonValue.Obj -> if (snapshot == null) parseCartPlanChange(value, index)
                    else CartPlanChange(
                        spinId = cartSnapshotId(directCartPlanField(value, "spinId", "spin_id", "productId", "product_id")),
                        kind = directCartPlanString(value, "kind", "type", "action", "operation") ?: "change",
                        displayName = normalizeLabel(directCartPlanString(value,
                            "displayName", "display_name", "label", "name", "title", "item")
                            ?: directCartPlanString(value, "description") ?: "Change ${index + 1}"),
                        fromQuantity = cartSnapshotQuantity(directCartPlanField(value,
                            "fromQuantity", "from_quantity", "quantityBefore", "quantity_before"), allowZero = true),
                        toQuantity = cartSnapshotQuantity(directCartPlanField(value,
                            "toQuantity", "to_quantity", "quantityAfter", "quantity_after", "quantity"), allowZero = true),
                        description = directCartPlanString(value, "description", "details", "notes"),
                    )
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
            existingItemCount = snapshot?.existingItems?.size ?: firstArray(root, "existingItems", "existing_items")?.items?.size,
            confirmationToken = if (snapshot != null) directCartPlanString(root, "confirmationToken", "confirmation_token", "token")
                else firstString(root, "confirmationToken", "confirmation_token", "token"),
            cartMutationEnabled = if (snapshot != null) {
                optionalDirectCartPlanField(root, "cartMutationEnabled", "cart_mutation_enabled", "mutationEnabled")?.let {
                    (it as? JsonValue.Bool)?.value ?: error("Invalid cart mutation flag")
                } ?: false
            } else isTruthy(root, "cartMutationEnabled", "cart_mutation_enabled", "mutationEnabled"),
            message = if (snapshot != null) directCartPlanString(root, "message", "statusMessage", "status_message")
                else firstString(root, "message", "statusMessage", "status_message"),
            snapshot = snapshot,
        )
    }

    private fun directCartPlanField(root: JsonValue.Obj, vararg keys: String): JsonValue =
        optionalDirectCartPlanField(root, *keys) ?: error("Missing cart plan field")

    private fun optionalDirectCartPlanField(root: JsonValue.Obj, vararg keys: String): JsonValue? {
        val present = keys.filter { it in root.members }
        require(present.size <= 1) { "Ambiguous cart plan field" }
        return present.singleOrNull()?.let { root.members.getValue(it) }
    }

    private fun directCartPlanString(root: JsonValue.Obj, vararg keys: String): String? {
        val value = optionalDirectCartPlanField(root, *keys) ?: return null
        return (value as? JsonValue.Str)?.value ?: error("Invalid cart plan text")
    }

    /** Decode one complete server snapshot; malformed evidence never downgrades to legacy. */
    private fun parseCartPlanSnapshot(root: JsonValue.Obj): CartPlanSnapshot? {
        if ("proposedItems" !in root.members && "reviewBasis" !in root.members) return null
        val basis = if ("reviewBasis" in root.members) cartReviewBasis(root.members["reviewBasis"]) else null
        return CartPlanSnapshot(
            cartSnapshotItems(directCartPlanField(root, "existingItems", "existing_items")),
            cartSnapshotItems(directCartPlanField(root, "proposedItems")), basis,
        )
    }

    internal fun parseCartReviewSnapshot(body: String): CartReviewSnapshot {
        val root = JsonParser(body.trim()).parseStrict() as? JsonValue.Obj ?: error("Invalid cart review snapshot")
        require(root.members.keys == setOf("existingItems", "reviewBasis")) { "Invalid cart review fields" }
        val items = root.members["existingItems"] as? JsonValue.Arr ?: error("Invalid cart review items")
        require(items.items.all { item -> item is JsonValue.Obj &&
            item.members.keys.all { it in setOf("spinId", "skuId", "quantity") }
        }) { "Invalid cart review item fields" }
        return CartReviewSnapshot(cartSnapshotItems(items), cartReviewBasis(root.members["reviewBasis"]))
    }

    private fun cartReviewBasis(value: JsonValue?): String {
        val basis = (value as? JsonValue.Str)?.value
        require(basis != null && Regex("cart-basis-v1:[0-9a-f]{64}").matches(basis)) { "Invalid cart review basis" }
        return basis
    }

    private fun cartSnapshotItems(value: JsonValue?): List<CartPlanItem> {
        val array = value as? JsonValue.Arr ?: error("Incomplete cart snapshot")
        val rows = array.items.map { item ->
            val row = item as? JsonValue.Obj ?: error("Invalid cart snapshot item")
            require(row.members.keys.all { it in setOf("spinId", "skuId", "quantity", "providerManagedFree") }) {
                "Unknown cart snapshot fields"
            }
            if ("providerManagedFree" in row.members) require(row.members["providerManagedFree"] == JsonValue.Bool(true))
            CartPlanItem(
                spinId = cartSnapshotId(row.members["spinId"]),
                quantity = cartSnapshotQuantity(row.members["quantity"]),
                skuId = if ("skuId" in row.members) cartSnapshotId(row.members["skuId"]) else null,
            )
        }
        require(rows.map { it.spinId }.distinct().size == rows.size) { "Duplicate cart snapshot item" }
        return java.util.Collections.unmodifiableList(rows)
    }

    private fun cartSnapshotId(value: JsonValue?): String {
        val id = (value as? JsonValue.Str)?.value
        require(id != null && id.isNotBlank() && id == id.trim()) { "Invalid cart snapshot identity" }
        return id
    }

    private fun cartSnapshotQuantity(value: JsonValue?, allowZero: Boolean = false): Int {
        val raw = (value as? JsonValue.Num)?.raw
        val quantity = raw?.takeIf { it.matches(Regex("0|[1-9][0-9]*")) }?.toIntOrNull()
        // Match the backend's existing checkout/readback contract; user edits
        // remain subject to the separate, stricter 20-unit planning limit.
        require(quantity != null && quantity in (if (allowZero) 0 else 1)..100) { "Invalid cart snapshot quantity" }
        return quantity
    }

    internal fun parseApplyResult(body: String): ApplyResult {
        val root = parseRoot(body)
        return ApplyResult(
            verified = isTruthy(root, "verified", "applied", "success", "cartVerified", "cart_verified"),
            message = firstString(root, "message", "statusMessage", "status_message"),
            reconnectRequired = containsReconnectRequired(root),
            persistenceVerified = (root as? JsonValue.Obj)?.members?.get("persistenceVerified") == JsonValue.Bool(true),
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
            .followRedirects(false)
            .followSslRedirects(false)

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
        postWire: ProfiledPost? = null,
        editHintInstruction: String? = null,
    ) {
        require((postWire != null) == (path == "/swiggy/intent" || path == "/swiggy/recommendations/batch"))
        require(postWire == null || (method == "POST" && body == null && path == postWire.path))
        val backendBaseUrl = try {
            AppConfig.backendBaseUrl
        } catch (_: BackendConfigurationUnavailableException) {
            callback(SwiggyMcpResult.Failure(userMessage =
                "This Beta build could not connect to its service. Please update the app. Nothing was sent."))
            return
        }
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
            .url("$backendBaseUrl$path")
            .header(HEADER_BACKEND_KEY, AppConfig.backendApiKey)
            .header(HEADER_INSTALLATION_TOKEN, installationToken)
        if (editHintInstruction != null) {
            require(path == "/swiggy/intent" && postWire is IntentPreparationWire)
            requestBuilder.header("X-Beta-Intent-Edit-Hints", "1")
        }

        val request = postWire?.applyTo(requestBuilder)?.build() ?: when (method) {
            "POST" -> requestBuilder
                .post((body ?: JSONObject().toString()).toRequestBody(jsonMediaType))
                .build()
            else -> requestBuilder.build()
        }

        // Intent inference is read-only but billable; never replay it automatically.
        val baseClient = if (permitsConnectionRetry(path)) safeRequestClient else cartMutationClient
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
                // Consume/read failures must also reach the owner, especially after an apply.
                // Keep the owner's callback outside parser catches to prevent double delivery.
                callback(readSwiggyHttpResponse(path, response, editHintInstruction, parser))
            }
        })
    }

    internal fun <T> readSwiggyHttpResponse(
        path: String,
        response: Response,
        parser: (String) -> T,
    ): SwiggyMcpResult<T> = readSwiggyHttpResponse(path, response, null, parser)

    private fun <T> readSwiggyHttpResponse(
        path: String,
        response: Response,
        editHintInstruction: String?,
        parser: (String) -> T,
    ): SwiggyMcpResult<T> = response.use {
        val body = try {
            it.body?.string().orEmpty()
        } catch (_: IOException) {
            return SwiggyMcpResult.Failure(userMessage = swiggyNetworkFailureMessage(path))
        }
        if (!it.isSuccessful) {
            return SwiggyMcpResult.Failure(
                userMessage = swiggyUserMessageForHttpCode(path, it.code, body),
                httpCode = it.code,
                reconnectRequired = swiggyReconnectRequired(body),
                retryable = swiggyRetryable(path, it.code),
                orderNotSubmitted = checkoutOrderNotSubmittedFlag(path, it.code, body),
                intentNeedsReview = path == "/swiggy/intent" && it.code == 422,
                intentEditRanges = if (path == "/swiggy/intent" && it.code == 422 && editHintInstruction != null)
                    GroceryIntentEditHints.fromRejection(editHintInstruction, body).map { q -> q.startUtf16 until q.endUtf16 }
                    else emptyList(),
                intentUnavailable = path == "/swiggy/intent" && it.code == 404 &&
                    swiggyHttpErrorReason(body) in setOf("intent_unavailable", "intent_version_unavailable"),
                cartPlanUnchanged = isCartPlanUnchanged(path, it.code, body),
            )
        }
        val parsed = try {
            parser(body)
        } catch (hold: GroceryLocalEditHold) {
            return SwiggyMcpResult.Failure(userMessage = "Beta needs help with your wording.",
                intentNeedsReview = path == "/swiggy/intent",
                intentEditRanges = if (path == "/swiggy/intent" && editHintInstruction != null) hold.ranges else emptyList())
        } catch (_: Exception) {
            return SwiggyMcpResult.Failure(intentNeedsReview = path == "/swiggy/intent", userMessage = if (path.startsWith("/swiggy/checkout/")) {
                "Beta could not read the order result. Check the existing attempt; do not place or pay again."
            } else if (isCartMutationPath(path)) {
                "Swiggy may have changed the cart, but Beta could not read the result. Open Swiggy and review the cart before trying again."
            } else {
                "Swiggy backend returned an unreadable response."
            })
        }
        SwiggyMcpResult.Success(parsed)
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
        if (path in setOf("/swiggy/checkout/place", "/swiggy/checkout/status"))
            return "The order outcome is not confirmed. Check the existing attempt; do not place or pay again."
        return if (path == "/swiggy/cart/apply") {
            "Swiggy may have changed the cart, but Beta lost the connection before it could verify it. Please open Swiggy and review the cart before trying again."
        } else {
            "Unable to reach Swiggy backend right now."
        }
    }

    internal fun isCartMutationPath(path: String): Boolean = path in setOf(
        "/swiggy/cart/apply", "/swiggy/checkout/place", "/swiggy/checkout/status")

    // Recommendation batches can contain paid assessment work. A transport
    // retry cannot know whether the first request already consumed its quota.
    internal fun permitsConnectionRetry(path: String): Boolean =
        !isCartMutationPath(path) && path !in setOf("/swiggy/intent", "/swiggy/recommendations/batch")

    internal fun swiggyHttpErrorReason(body: String): String? {
        val root = parseRoot(body)
        return firstString(root, "reason")
            ?: firstObject(root, "detail")?.let { detail ->
                firstString(detail, "reason", "error", "message", "detail")
            }
    }

    private fun isCartPlanUnchanged(path: String, code: Int, body: String): Boolean {
        if (path != "/swiggy/cart/plan" || code != 409) return false
        val envelope = runCatching { JsonParser(body).parseStrict() }.getOrNull() as? JsonValue.Obj ?: return false
        val root = if ("detail" in envelope.members) {
            if ("reason" in envelope.members) return false
            envelope.members["detail"] as? JsonValue.Obj ?: return false
        } else envelope
        return root.members["reason"] == JsonValue.Str("cart_no_changes") && !containsReconnectRequired(envelope)
    }

    internal fun swiggyReconnectRequired(body: String): Boolean {
        return containsReconnectRequired(parseRoot(body))
    }

    internal fun swiggyRetryable(path: String, code: Int): Boolean {
        return when {
            code == 429 -> !isCartMutationPath(path)
            code in 500..599 -> !isCartMutationPath(path)
            else -> false
        }
    }

    internal fun swiggyUserMessageForHttpCode(path: String, code: Int, body: String): String {
        val reason = swiggyHttpErrorReason(body)
        if (path.startsWith("/swiggy/checkout/")) {
            if (path == "/swiggy/checkout/review") return when (reason) {
                "checkout_disabled" -> "Checkout in Beta is not enabled yet. No order was sent."
                "checkout_unresolved" -> "An earlier order needs checking before you can order again."
                else -> "Beta could not verify the cart, address, total or payment options. No order was sent."
            }
            return if (checkoutOrderNotSubmittedFlag(path, code, body)) {
                "The review changed or could not be verified. Please review the current cart again."
            } else "The order outcome is not confirmed. Check the existing attempt; do not place or pay again."
        }
        val waitMinutes = swiggyRateLimitWaitMinutes(body)
        return when {
            swiggyReconnectRequired(body) -> "Swiggy connection needs to be reconnected."
            code == 429 || reason == "swiggy_rate_limited" -> {
                val waitText = waitMinutes?.let { minute ->
                    if (minute <= 1) "a minute" else "$minute minutes"
                } ?: "a minute"
                "Swiggy needs a short pause. Please wait $waitText, then try again."
            }
            reason == "cart_changed_replan_required" -> "Your Swiggy cart changed. Please repeat the voice order so Beta can check it again."
            reason == "cart_confirmation_already_used" -> "That cart confirmation was already used. Please repeat the voice order."
            reason == "cart_confirmation_expired" -> "That cart review expired. Please repeat the voice order so Beta can check the cart again."
            reason == "cart_update_in_progress" -> "Another Swiggy cart update is still finishing. Please wait a moment, then repeat the voice order."
            reason == "cart_update_outcome_unknown" -> "Swiggy may have changed the cart, but Beta could not verify it. Please open Swiggy and review the cart before trying again."
            reason == "cart_update_not_verified" -> "Swiggy did not match the reviewed cart change. Please open Swiggy and review the cart before trying again."
            reason == "cart_changed_after_update" -> "The cart changed after Beta's update. Please review your Swiggy cart. Beta will not add anything again automatically."
            reason == "cart_persistence_unknown" -> "Beta could not finish the later cart check. Please review your Swiggy cart. Beta will not repeat the update."
            reason == "cart_address_unverified" -> "Beta could not verify the current Swiggy delivery address for this cart. Please reopen Swiggy and choose the right address again."
            reason == "cart_address_mismatch" -> "Swiggy's current cart address does not match the selected delivery address. Please choose the right address again."
            reason == "cart_schema_unrecognized" -> "Beta could not safely read this Swiggy cart. Nothing was changed."
            reason == "cart_mutation_disabled" -> "Swiggy cart updates are not enabled yet. Nothing was changed."
            reason == "cart_no_changes" -> "Beta could not verify this cart review. Please review your list again."
            code == 401 || code == 403 -> "Swiggy authorization is no longer valid."
            code == 404 -> "Swiggy setup is not ready yet. Please try again later."
            code in 500..599 -> if (isCartMutationPath(path)) {
                "Swiggy returned an error after the cart request. Beta cannot confirm whether the cart changed. Open Swiggy and review the cart before trying again."
            } else "Swiggy backend is temporarily unavailable."
            else -> "Swiggy backend request failed."
        }
    }

    internal fun swiggyRateLimitWaitMinutes(body: String): Int? {
        val retryAfterSeconds = swiggyRetryAfterSeconds(body) ?: return null
        return ((retryAfterSeconds.toLong() + 59) / 60).toInt()
    }

    internal fun swiggyRetryAfterSeconds(body: String): Int? {
        val root = parseRoot(body)
        val candidates = sequenceOf(
            firstString(root, "retryAfterSeconds", "retry_after_seconds"),
            firstObject(root, "detail")?.let { detail ->
                firstString(detail, "retryAfterSeconds", "retry_after_seconds")
            },
        )
        return candidates.firstNotNullOfOrNull { value ->
            value?.toLongOrNull()?.takeIf { it in 1L..1_000_000L }?.toInt()
        }
    }

    private fun parseConnectionState(raw: String?): ConnectionState {
        val normalized = raw?.trim()?.lowercase().orEmpty()
        return when {
            normalized.contains("reconnect") || normalized.contains("reauth") -> ConnectionState.RECONNECT_REQUIRED
            normalized in setOf("ready", "connected", "active") -> ConnectionState.READY
            else -> ConnectionState.DISCONNECTED
        }
    }

    private fun parseAddressObject(value: JsonValue.Obj, index: Int, currentCartAddressId: String?): SwiggyAddress {
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
        val category = firstString(value, "addressCategory", "address_category", "category", "type")
        val tag = firstString(value, "addressTag", "address_tag", "tag")
        val categoryLabel = conciseAddressCategory(category, tag, index)
        return SwiggyAddress(
            id = providerId,
            label = label,
            normalizedLabel = label,
            shortLabel = conciseAddressLabel(
                fullLabel = label,
                category = category,
                tag = tag,
                index = index,
            ),
            categoryLabel = categoryLabel,
            confirmationDetail = conciseAddressConfirmationDetail(label, categoryLabel),
            hasCurrentCart = currentCartAddressId?.isNotBlank() == true && currentCartAddressId == providerId,
        )
    }

    internal data class AddressResponse(
        val addresses: List<SwiggyAddress>,
        val currentCartAddressId: String?,
    )

    internal fun conciseAddressLabel(
        fullLabel: String,
        category: String?,
        tag: String?,
        index: Int,
    ): String {
        val name = conciseAddressCategory(category, tag, index)
        val locality = deriveAddressLocality(fullLabel)
            ?.takeUnless { it.equals(name, ignoreCase = true) }
        return listOfNotNull(name, locality).joinToString(" — ").take(64)
    }

    private fun conciseAddressCategory(category: String?, tag: String?, index: Int): String {
        val categoryLabel = readableAddressName(category).takeIf(::isUsefulAddressName)
        val tagLabel = readableAddressName(tag).takeIf(::isUsefulAddressName)
        return categoryLabel ?: tagLabel ?: "Saved address ${index + 1}"
    }

    internal fun conciseAddressConfirmationDetail(
        fullLabel: String,
        categoryLabel: String,
    ): String? {
        val segments = normalizeLabel(fullLabel)
            .split(',')
            .map(String::trim)
            .filter(String::isNotBlank)
            .filterNot { it.equals(categoryLabel, ignoreCase = true) }
            .filterNot { segment ->
                val normalized = segment.lowercase()
                normalized in setOf("india", "bharat") ||
                    normalized in INDIAN_STATE_NAMES ||
                    Regex("^\\d{6}$").matches(normalized)
            }
        if (segments.isEmpty()) return null

        val first = segments[0].take(32)
        if (segments.size == 1) return first
        val second = segments[1].take(40)
        val structuralSecond = Regex(
            "\\b(block|floor|wing|tower|building|apartment|flat)\\b",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(second)
        val detail = if (structuralSecond && segments.size >= 3) {
            "$first, $second in ${segments[2].take(48)}"
        } else {
            "$first in $second"
        }
        return detail.take(96)
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

    fun fetchIntentDraft(
        context: Context,
        instruction: String,
        provider: GroceryIntentProvider,
        callback: SwiggyCallback<GroceryIntentResponse>,
    ) {
        require(instruction.isNotBlank()) { "Instruction must not be blank" }
        executeJsonRequest(
            context = context,
            method = "POST",
            path = "/swiggy/intent",
            postWire = intentPreparationWire(instruction, provider, GroceryIntentContract.V1),
            callback = callback,
            parser = { body -> GroceryIntentResponse.parse(body, instruction) },
            timeoutSeconds = 15,
        )
    }

    /** Version is captured by the coordinator at operation start; no retry or downgrade. */
    internal fun fetchIntentPreparation(
        context: Context,
        instruction: String,
        provider: GroceryIntentProvider,
        contract: GroceryIntentContract,
        callback: SwiggyCallback<GroceryIntentPreparation>,
    ) {
        require(contract != GroceryIntentContract.CONTEXT) {
            "The context contract requires fetchIntentContextPreparation"
        }
        require(instruction.isNotBlank()) { "Instruction must not be blank" }
        executeJsonRequest(
            context = context,
            method = "POST",
            path = "/swiggy/intent",
            postWire = intentPreparationWire(instruction, provider, contract),
            editHintInstruction = instruction.takeIf { contract == GroceryIntentContract.V1 },
            callback = callback,
            parser = { body ->
                when (contract) {
                    GroceryIntentContract.V1 -> prepareV1WithEditHints(body, instruction)
                    GroceryIntentContract.V2 -> GroceryIntentReviewV2Response.parse(body, instruction).let { response ->
                        if (response.inputRepairRejected) Log.w("BetaAgent", "INTENT_INPUT_REPAIR_REJECTED")
                        response.draft.prepareForReview(allowPropertyObligations = true, allowIngredientPredicates = true,
                            allowTypedIngredientPredicates = true)
                            .copy(inputRepair = response.inputRepair?.takeIf { it.isOnlyIssueIn(response.draft) })
                    }
                    GroceryIntentContract.CONTEXT -> error("The context contract requires fetchIntentContextPreparation")
                    GroceryIntentContract.INTERPRETED -> GroceryIntentInterpretedResponse.parse(body, instruction)
                        .prepareForReview()
                }
            },
            timeoutSeconds = 15,
        )
    }

    /** Separate strict adapter. Context responses never enter the V1/V2 callback path. */
    internal fun fetchIntentContextPreparation(
        context: Context,
        instruction: String,
        provider: GroceryIntentProvider,
        callback: SwiggyCallback<GroceryIntentContextAssessment>,
    ) {
        require(instruction.isNotBlank()) { "Instruction must not be blank" }
        executeJsonRequest(
            context = context,
            method = "POST",
            path = "/swiggy/intent",
            postWire = intentPreparationWire(instruction, provider, GroceryIntentContract.CONTEXT),
            callback = callback,
            parser = { body -> GroceryIntentContextResponse.parse(body, instruction).assessForReview() },
            timeoutSeconds = 15,
        )
    }

    internal fun prepareV1WithEditHints(body: String, instruction: String): GroceryIntentPreparation {
        val draft = GroceryIntentResponse.parse(body, instruction).draft
        try { return draft.prepareForReview() } catch (hold: IntentNeedsReviewException) {
            if (!hold.localEditOnly) throw hold
            val hints = GroceryIntentEditHints.fromLocal(instruction, draft.items, hold.affectedSourceText)
            if (hints.isEmpty()) throw hold
            throw GroceryLocalEditHold(hints.map { it.startUtf16 until it.endUtf16 })
        }
    }

    internal sealed class ProfiledPost(val path: String, val body: String, private val header: Pair<String, String>?) {
        fun applyTo(builder: Request.Builder): Request.Builder {
            header?.let { (name, value) -> builder.header(name, value) }
            return builder.post(body.toRequestBody(jsonMediaType))
        }
    }

    internal class IntentPreparationWire(body: String, profile: String?) :
        ProfiledPost("/swiggy/intent", body, profile?.let { "X-Beta-Intent-Profile" to it })

    internal fun intentPreparationWire(
        instruction: String,
        provider: GroceryIntentProvider,
        contract: GroceryIntentContract,
    ): IntentPreparationWire {
        // Strict legacy V1 servers accept just these two keys. An additive header, unlike
        // unknown JSON fields, can cross a rolling deployment without a second inference.
        val body = JSONObject().put("instruction", instruction).put("provider", provider.requestValue)
        if (contract == GroceryIntentContract.V1) return IntentPreparationWire(body.toString(), "v1-source-offers-1")
        body.put("contract_version", contract.requestValue)
        intentPreparationCapabilities(contract).forEach { (name, enabled) -> body.put(name, enabled) }
        return IntentPreparationWire(body.toString(), null)
    }

    internal fun intentPreparationCapabilities(contract: GroceryIntentContract): Map<String, Boolean> = when (contract) {
        GroceryIntentContract.V2 -> mapOf(
                "supports_headless_groups" to true,
                "supports_variant_cardinality" to true,
                "supports_postfix_identity" to true,
                "supports_input_repair" to true,
                "supports_property_obligations" to true,
                "supports_ingredient_predicates" to true,
                "supports_typed_ingredient_predicates" to true,
                "supports_group_corrections" to true,
                "supports_list_purpose" to true,
                "supports_purpose_groups" to true,
                "supports_named_piece_corrections" to true,
                "supports_restatement_receipts" to true,
                "supports_quantity_exclusion_receipts" to true,
                "supports_trailing_fulfillment" to true,
                "supports_same_count_refinements" to true,
                "supports_product_operations" to true,
            )
        GroceryIntentContract.INTERPRETED -> emptyMap()
        GroceryIntentContract.V1, GroceryIntentContract.CONTEXT ->
            mapOf("supports_source_piece_counts" to true, "supports_generic_container_offers" to true)
    }

    internal val INDIAN_STATE_NAMES = setOf(
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

    private fun parseRecommendationCandidate(
        value: JsonValue.Obj,
        index: Int,
        requiredSellerAssessment: SellerPropertyRequirement? = null,
    ): RecommendationCandidate {
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
        val catalogueQuantity = parseCatalogueQuantityEvidence(
            raw = value.members["catalogueQuantity"],
            candidateSpinId = (value.members["spinId"] as? JsonValue.Str)?.value,
            candidateSkuId = (value.members["skuId"] as? JsonValue.Str)?.value,
            duplicatedCandidateKeys = value.duplicateKeys,
        )
        val catalogueIngredients = CatalogueIngredientEvidence.parse(value.members["catalogueIngredients"],
            (value.members["spinId"] as? JsonValue.Str)?.value,
            (value.members["skuId"] as? JsonValue.Str)?.value, value.duplicateKeys)
        val limit = parseCataloguePurchaseLimit(value)
        val candidate = RecommendationCandidate(
            spinId = firstString(value, "spinId", "spin_id", "id", "candidateId", "candidate_id", "value", "slug")
                ?: label.ifBlank { index.toString() },
            skuId = firstString(value, "skuId", "sku_id"),
            label = label,
            variant = firstString(value, "variant", "pack", "packSize", "pack_size", "size", "quantityLabel"),
            subtitle = firstString(value, "subtitle", "description", "details"),
            suggested = isTruthy(value, "suggested", "isSuggested", "is_suggested", "recommended"),
            catalogueBrand = parseCatalogueBrandEvidence(value),
            catalogueQuantity = catalogueQuantity,
            catalogueQuantityInvalid = "catalogueQuantity" in value.members && catalogueQuantity == null,
            catalogueIngredients = catalogueIngredients,
            catalogueIngredientsInvalid = "catalogueIngredients" in value.members && catalogueIngredients == null,
            cataloguePurchaseLimit = limit,
            cataloguePurchaseLimitInvalid = "cataloguePurchaseLimit" in value.members && limit == null,
        )
        if (requiredSellerAssessment == null) return candidate
        require(value.duplicateKeys.isEmpty())
        require(value.members.keys.containsAll(setOf("spinId", "skuId", "label")))
        require(value.members.keys.none { it in setOf(
            "spin_id", "id", "candidateId", "candidate_id", "productId", "product_id", "name", "title",
            "value", "slug", "displayText", "display_text", "query", "text",
            "sku_id", "pack", "packSize", "pack_size", "size", "quantityLabel",
            "description", "details",
        ) })
        return candidate.copy(sellerAssessment = SwiggySellerAssessment.parse(
            raw = value.members["sellerAssessment"],
            rawCandidate = value,
            expected = requiredSellerAssessment,
            normalizedCandidate = candidate,
        ))
    }

    private fun parseCataloguePurchaseLimit(candidate: JsonValue.Obj): CataloguePurchaseLimit? {
        val obj = candidate.members["cataloguePurchaseLimit"] as? JsonValue.Obj ?: return null
        if (candidate.duplicateKeys.any { it in setOf("cataloguePurchaseLimit", "spinId", "skuId") } ||
            obj.duplicateKeys.isNotEmpty() || obj.members.keys != setOf("source", "spinId", "skuId", "maxUnits")) return null
        fun text(row: JsonValue.Obj, key: String) = (row.members[key] as? JsonValue.Str)?.value
        if (text(obj, "source") != "live_search_variation") return null
        val units = (obj.members["maxUnits"] as? JsonValue.Num)?.raw?.toIntOrNull() ?: return null
        val limit = CataloguePurchaseLimit(text(obj, "spinId") ?: return null, text(obj, "skuId") ?: return null, units)
        return limit.takeIf { it.validFor(text(candidate, "spinId") ?: return null, text(candidate, "skuId")) }
    }

    private fun parseCatalogueBrandEvidence(candidate: JsonValue.Obj): CatalogueBrandEvidence? {
        val evidence = candidate.members["catalogueBrand"] as? JsonValue.Obj ?: return null
        val fields = evidence.members
        if (fields.keys != setOf("name", "spinId", "skuId", "source")) return null
        val name = (fields["name"] as? JsonValue.Str)?.value ?: return null
        val spinId = (fields["spinId"] as? JsonValue.Str)?.value ?: return null
        val skuId = (fields["skuId"] as? JsonValue.Str)?.value ?: return null
        val source = (fields["source"] as? JsonValue.Str)?.value ?: return null
        // Brand evidence is bound only to explicit backend IDs, never to the
        // parser's synthetic label-based fallback or a loose alias field.
        val suppliedSpinId = (candidate.members["spinId"] as? JsonValue.Str)?.value ?: return null
        val suppliedSkuId = (candidate.members["skuId"] as? JsonValue.Str)?.value ?: return null
        if (name.isBlank() || name.length > 120 || name != name.trim() || name.any(Char::isISOControl)) return null
        if (spinId.isBlank() || spinId != spinId.trim() || spinId != suppliedSpinId) return null
        if (skuId.isBlank() || skuId != skuId.trim() || skuId != suppliedSkuId) return null
        if (source != "search_variation") return null
        return CatalogueBrandEvidence(name, spinId, skuId, source)
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
                for ((field, child) in root.members) {
                    // Optional evidence has its own strict decoder. Its name,
                    // IDs or unexpected keys cannot fill ordinary response
                    // fields, even when the evidence is rejected as malformed.
                    if (field in setOf("catalogueBrand", "catalogueQuantity", "catalogueIngredients", "cataloguePurchaseLimit")) continue
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
