package com.example.beta

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Explicitly opted-in, read-only diagnostic for Swiggy's live get_cart schema.
 *
 * The raw response stays in the target app's private files directory and is
 * never printed to test output. This test is skipped unless the caller passes
 * `-e liveSwiggySchema true` to the instrumentation runner.
 */
@RunWith(AndroidJUnit4::class)
class SwiggyLiveCartSchemaTest {
    @Test
    fun captureCurrentCartSchemaWithoutMutation() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("liveSwiggySchema") == "true")

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Read-only diagnostic: keep only reviewed line identities in app-private
        // storage, never the signed token, installation identity, or address.
        if (arguments.getString("captureReviewedLines") == "true") {
            SwiggyCartReviewStore(context).load()?.let { token ->
                val payload = org.json.JSONObject(String(android.util.Base64.decode(
                    token.substringBefore('.'), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP,
                ), Charsets.UTF_8))
                val summary = org.json.JSONObject().put("proposedItems", payload.getJSONArray("proposedItems"))
                context.openFileOutput("swiggy-reviewed-lines-diagnostic.json", Context.MODE_PRIVATE).use {
                    it.write(summary.toString().toByteArray(Charsets.UTF_8))
                }
            }
        }
        val snapshotLabel = validateSnapshotLabel(arguments.getString("snapshotLabel"), context)
        val request = Request.Builder()
            .url("${AppConfig.backendBaseUrl}/swiggy/cart")
            .header("x-beta-backend-key", AppConfig.backendApiKey)
            .header("x-beta-installation-token", SwiggyInstallationIdentity.installationToken(context))
            .get()
            .build()
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val receipt = org.json.JSONObject()
                .put("httpStatus", response.code)
                .put("receivedAtMillis", System.currentTimeMillis())
                .put("body", body)
            context.openFileOutput("swiggy-cart-live-response.json", android.content.Context.MODE_PRIVATE).use {
                it.write(receipt.toString().toByteArray(Charsets.UTF_8))
            }
            val reason = runCatching { org.json.JSONObject(body).optString("reason") }.getOrDefault("")
                .takeIf { it.matches(Regex("[a-z_]{1,80}")) }.orEmpty()
            assertTrue("Expected live cart schema response, got HTTP ${response.code} reason=$reason", response.isSuccessful)
            assertTrue("Expected a non-empty cart schema response", body.isNotBlank())
            context.openFileOutput(OUTPUT_FILE, android.content.Context.MODE_PRIVATE).use { output ->
                output.write(body.toByteArray(Charsets.UTF_8))
            }
            snapshotLabel?.let { label ->
                context.openFileOutput(snapshotFileName(label), Context.MODE_PRIVATE).use { output ->
                    output.write(receipt.toString().toByteArray(Charsets.UTF_8))
                }
            }
        }
    }

    private fun validateSnapshotLabel(rawLabel: String?, context: android.content.Context): String? {
        val label = rawLabel?.trim().orEmpty()
        if (label.isBlank()) return null
        require(label.matches(SNAPSHOT_LABEL_REGEX)) {
            "snapshotLabel must match ${SNAPSHOT_LABEL_REGEX.pattern}."
        }
        val snapshotFile = File(context.filesDir, snapshotFileName(label))
        check(!snapshotFile.exists()) {
            "snapshotLabel already exists; refusing to overwrite ${snapshotFile.name}."
        }
        return label
    }

    private fun snapshotFileName(label: String): String = "swiggy-cart-snapshot-$label.json"

    private companion object {
        const val OUTPUT_FILE = "swiggy-cart-live-schema.json"
        val SNAPSHOT_LABEL_REGEX = Regex("[a-z0-9_-]{1,40}")
    }
}
