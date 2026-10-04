package com.example.beta

import android.content.Context
import android.util.Base64
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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Opt-in hosted intent diagnostic. It checks status and draft generation only; no cart APIs. */
@RunWith(AndroidJUnit4::class)
class GroceryIntentReadOnlyTest {
    @Test
    fun validatesSyntheticIntentDraftsWithoutCartMutation() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString(READ_ONLY_ARGUMENT) == "true")

        val backendBaseUrl = resolveBackendBaseUrl(arguments.getString(BASE_URL_ARGUMENT))
        val providerAlias = arguments.getString(PROVIDER_ARGUMENT)?.trim()?.takeIf { it.isNotEmpty() }
        require(providerAlias == null || GroceryIntentProvider.entries.any { it.requestValue == providerAlias }) {
            "Unsupported grocery-intent provider alias"
        }
        assertTrue("Hosted backend key is required for the opted-in intent pilot", AppConfig.backendApiKey.isNotBlank())

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installationToken = SwiggyInstallationIdentity.installationToken(context)
        val recordedRequests = mutableListOf<Pair<String, String>>()
        val client = readOnlyClient(backendBaseUrl, recordedRequests)
        val observations = JSONArray()
        val failedCaseIds = mutableListOf<String>()
        var completedIntentRequests = 0

        try {
            val statusBody = requestJson(
                client = client,
                baseUrl = backendBaseUrl,
                installationToken = installationToken,
                method = "GET",
                path = STATUS_PATH,
            )
            val status = SwiggyMcpClient.parseStatus(statusBody)
            assertEquals("The opted-in canary must already be connected", SwiggyMcpClient.ConnectionState.READY, status.state)

            syntheticCases().forEach { case ->
                val startNanos = System.nanoTime()
                val requestBody = JSONObject().put("instruction", case.instruction)
                providerAlias?.let { requestBody.put("provider", it) }
                val responseBody = requestJson(
                    client = client,
                    baseUrl = backendBaseUrl,
                    installationToken = installationToken,
                    method = "POST",
                    path = INTENT_PATH,
                    body = requestBody,
                )
                completedIntentRequests += 1
                val wallLatencyMs = (System.nanoTime() - startNanos) / 1_000_000.0

                var response: GroceryIntentResponse? = null
                var parsedItems: List<ParsedItem> = emptyList()
                val caseFailures = mutableListOf<String>()
                try {
                    response = GroceryIntentResponse.parse(responseBody, case.instruction)
                    parsedItems = response.draft.toParsedItems()
                    caseFailures += validateCase(case.id, response.draft.items)
                    if (swiggyMcpItemValidationMessage("", parsedItems) != null) {
                        caseFailures += "app_item_validation"
                    }
                } catch (_: Exception) {
                    caseFailures += "typed_response_or_item_conversion"
                }
                val passed = caseFailures.isEmpty()
                if (!passed) failedCaseIds += case.id
                observations.put(caseObservation(case, response, wallLatencyMs, passed, caseFailures))
            }

            assertEquals("Expected exactly one bounded intent request per synthetic utterance", 6, completedIntentRequests)
            assertTrue("Synthetic intent cases failed: ${failedCaseIds.joinToString()}", failedCaseIds.isEmpty())
        } finally {
            savePrivateArtifact(context, RESULT_FILE, JSONObject().put("cases", observations).toString())
        }
        assertBoundedCalls(recordedRequests, completedIntentRequests)
    }

    @Test
    fun exportsOnlyDerivedInstallationIdentityWhenExplicitlyOptedIn() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString(IDENTITY_EXPORT_ARGUMENT) == "true")

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val token = SwiggyInstallationIdentity.installationToken(context)
        val identity = MessageDigest.getInstance("SHA-256")
            .digest(INSTALLATION_ID_PREFIX.toByteArray(Charsets.UTF_8) + decodeBase64Url(token))
            .joinToString("") { byte -> "%02x".format(byte) }
        savePrivateArtifact(context, IDENTITY_FILE, identity)
        assertEquals(64, identity.length)
        assertTrue(identity.all { it in '0'..'9' || it in 'a'..'f' })
    }

    private fun validateCase(caseId: String, items: List<GroceryIntentItem>): List<String> {
        val failures = mutableListOf<String>()
        fun expect(label: String, condition: Boolean) {
            if (!condition) failures += "$caseId:$label"
        }
        fun hasQuantity(item: GroceryIntentItem?, value: Int, unit: String): Boolean =
            item != null && item.quantity?.compareTo(BigDecimal(value)) == 0 && item.quantityUnit == unit
        fun oneItem(): GroceryIntentItem? {
            expect("item_count", items.size == 1)
            return items.singleOrNull()
        }

        when (caseId) {
            "one_samosa" -> {
                val item = oneItem()
                expect("samosa_identity", item?.name?.contains("samosa", ignoreCase = true) == true)
                expect("quantity_one", hasQuantity(item, 1, "count"))
            }
            "one_tetra_pack_milk" -> {
                val item = oneItem()
                expect("milk_identity", item?.name?.contains("milk", ignoreCase = true) == true)
                expect("quantity_one_pack", hasQuantity(item, 1, "pack"))
                expect("tetra_pack_constraint", item?.required?.contains("tetra_pack") == true)
            }
            "one_lean_milk" -> {
                val item = oneItem()
                expect("milk_identity", item?.name?.contains("milk", ignoreCase = true) == true)
                expect("quantity_one", hasQuantity(item, 1, "count"))
                expect("low_fat_constraint", item?.required?.contains("low_fat") == true)
            }
            "milk_less_cream" -> {
                val item = oneItem()
                expect("milk_identity", item?.name?.contains("milk", ignoreCase = true) == true)
                expect("quantity_unspecified", item != null && item.quantity == null && item.quantityUnit == "unspecified")
                expect("low_fat_constraint", item?.required?.contains("low_fat") == true)
            }
            "sugar_free_ready_made_tea" -> {
                val item = oneItem()
                expect("tea_identity", item?.name?.contains("tea", ignoreCase = true) == true)
                expect("quantity_unspecified", item != null && item.quantity == null && item.quantityUnit == "unspecified")
                expect("sugar_free_constraint", item?.required?.contains("sugar_free") == true)
                // Evidence in the enforced name is equivalent to a duplicated tag.
                // Conversion and catalogue-safety regression tests cover both representations.
                expect("ready_to_drink_constraint", item != null &&
                    "readytodrink" in swiggyIdentityTokens(item.toParsedItem().strictMatchPhrase.orEmpty()))
            }
            "milk_tetta_and_dark_chocolate" -> {
                expect("item_count", items.size == 2)
                val milk = items.singleOrNull { it.name.contains("milk", ignoreCase = true) }
                val chocolate = items.singleOrNull { it.name.contains("chocolate", ignoreCase = true) }
                expect("milk_quantity_five_packs", hasQuantity(milk, 5, "pack"))
                expect("tetra_pack_constraint", milk?.required?.contains("tetra_pack") == true)
                expect("chocolate_quantity_two", hasQuantity(chocolate, 2, "count"))
            }
            else -> failures += "$caseId:unknown_case"
        }
        return failures
    }

    private fun caseObservation(
        case: SyntheticCase,
        response: GroceryIntentResponse?,
        wallLatencyMs: Double,
        passed: Boolean,
        failureCategories: List<String>,
    ): JSONObject = JSONObject()
        .put("caseId", case.id)
        .put("latencyMs", wallLatencyMs)
        .put("passed", passed)
        .put("failureCategories", JSONArray(failureCategories))
        .put("model", response?.model ?: JSONObject.NULL)
        .put("promptVersion", response?.promptVersion ?: JSONObject.NULL)
        .put("items", JSONArray().apply {
            response?.draft?.items.orEmpty().forEach { item ->
                put(
                    JSONObject()
                        .put("name", item.name)
                        .put("quantity", item.quantity?.toDouble() ?: JSONObject.NULL)
                        .put("quantityUnit", item.quantityUnit)
                        .put("packValue", item.packValue?.toDouble() ?: JSONObject.NULL)
                        .put("packUnit", item.packUnit)
                        .put("required", JSONArray(item.required))
                        .put("excluded", JSONArray(item.excluded))
                        .put("needsClarification", item.needsClarification),
                )
            }
        })

    private fun resolveBackendBaseUrl(requested: String?): String {
        val configured = AppConfig.backendBaseUrl.trim().trimEnd('/')
        val resolved = requested?.trim()?.trimEnd('/') ?: configured
        require(resolved == configured || resolved == INTENT_CANARY_BASE_URL) {
            "Intent pilot backend must be the configured backend or the fixed intent canary"
        }
        require(URI(resolved).scheme == "https") { "Intent pilot backend must use HTTPS" }
        return resolved
    }

    private fun readOnlyClient(baseUrl: String, recordedRequests: MutableList<Pair<String, String>>): OkHttpClient {
        val configuredHost = URI(baseUrl).host
        val canaryHost = URI(INTENT_CANARY_BASE_URL).host
        val guard = Interceptor { chain ->
            val request = chain.request()
            val methodAndPath = request.method to request.url.encodedPath
            require(
                methodAndPath == ("GET" to STATUS_PATH) || methodAndPath == ("POST" to INTENT_PATH),
            ) { "Intent pilot request is outside the read-only allowlist" }
            require(request.url.isHttps && request.url.host in setOf(configuredHost, canaryHost)) {
                "Intent pilot request host is not allowed"
            }
            synchronized(recordedRequests) { recordedRequests += methodAndPath }
            chain.proceed(request)
        }
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
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
        require((method == "GET" && path == STATUS_PATH) || (method == "POST" && path == INTENT_PATH))
        val builder = Request.Builder()
            .url("$baseUrl$path")
            .header(HEADER_BACKEND_KEY, AppConfig.backendApiKey)
            .header(HEADER_INSTALLATION_TOKEN, installationToken)
        if (method == "GET") {
            builder.get()
        } else {
            require(body != null)
            builder.post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
        }
        client.newCall(builder.build()).execute().use { response ->
            require(response.isSuccessful) { "Intent pilot HTTP ${response.code}" }
            return response.body?.string().orEmpty()
        }
    }

    private fun assertBoundedCalls(requests: List<Pair<String, String>>, completedIntentRequests: Int) {
        val intentCalls = requests.count { it == ("POST" to INTENT_PATH) }
        val statusCalls = requests.count { it == ("GET" to STATUS_PATH) }
        assertTrue("Intent requests must not exceed the six synthetic cases", intentCalls in 0..6)
        assertEquals("Connected status must be read exactly once", 1, statusCalls)
        assertEquals(completedIntentRequests, intentCalls)
        assertEquals(requests.size, intentCalls + statusCalls)
    }

    private fun savePrivateArtifact(context: Context, fileName: String, contents: String) {
        context.openFileOutput(fileName, Context.MODE_PRIVATE).use { output ->
            output.write(contents.toByteArray(Charsets.UTF_8))
        }
    }

    private fun decodeBase64Url(value: String): ByteArray = Base64.decode(
        value,
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
    )

    private fun syntheticCases() = listOf(
        SyntheticCase("one_samosa", "one samosa"),
        SyntheticCase("one_tetra_pack_milk", "one tetra pack milk"),
        SyntheticCase("one_lean_milk", "one lean milk"),
        SyntheticCase("milk_less_cream", "milk with less cream"),
        SyntheticCase("sugar_free_ready_made_tea", "sugar-free ready-made tea"),
        SyntheticCase("milk_tetta_and_dark_chocolate", "5 milk tetta packs and 2 dark chocolate"),
    )

    private data class SyntheticCase(val id: String, val instruction: String)

    private companion object {
        const val READ_ONLY_ARGUMENT = "betaIntentReadOnly"
        const val IDENTITY_EXPORT_ARGUMENT = "betaIntentIdentityExport"
        const val BASE_URL_ARGUMENT = "betaIntentBaseUrl"
        const val PROVIDER_ARGUMENT = "betaIntentProvider"
        const val RESULT_FILE = "grocery-intent-readonly-result.json"
        const val IDENTITY_FILE = "grocery-intent-pilot-id.txt"
        const val INTENT_CANARY_BASE_URL = "https://intent-canary---beta-backend-staging-kvuem5t7mq-el.a.run.app"
        const val STATUS_PATH = "/swiggy/status"
        const val INTENT_PATH = "/swiggy/intent"
        const val HEADER_BACKEND_KEY = "x-beta-backend-key"
        const val HEADER_INSTALLATION_TOKEN = "x-beta-installation-token"
        const val INSTALLATION_ID_PREFIX = "beta-installation-v1\u0000"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
