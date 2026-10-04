package com.example.beta

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Offline replay of saved envelopes through Android parsing and coordinator review preparation. */
class GroceryIntentSavedReplayTest {
    @Test
    fun savedRowsReachReviewOrReportExplicitHardRejections() {
        val replayPath = System.getenv(REPLAY_ENV)?.trim().orEmpty()
        assumeTrue("Set BETA_INTENT_REPLAY_FILE to opt in to this private offline replay", replayPath.isNotEmpty())
        val file = File(replayPath)
        require(file.isFile) { "Replay input file does not exist" }
        val root = objectAt(JsonParser(file.readText(Charsets.UTF_8)).parseStrict())
        require(stringAt(root["schema"]) == EXPECTED_SCHEMA)
        val expectedRows = numberAt(root["expected_rows"]).also { require(it > 0) }
        val cases = arrayAt(root["cases"])
        require(cases.size == expectedRows) { "Expected $expectedRows preserved replay rows" }
        val caseIds = cases.map { stringAt(objectAt(it)["case_id"]) }
        require(caseIds.all(String::isNotBlank) && caseIds.toSet().size == expectedRows) {
            "Replay rows must have unique, nonblank case IDs"
        }

        val diagnostics = mutableListOf<Diagnostic>()
        var reviewReady = 0
        var readyWithoutExtraAck = 0
        var acknowledgementRequired = 0
        cases.forEach { value ->
            val row = objectAt(value)
            val caseId = stringAt(row["case_id"])
            val instruction = stringAt(row["instruction"])
            if (stringAt(row["preparation_status"]) != "ready") {
                diagnostics += Diagnostic(caseId, "offline_preparation_rejected", "offline_preparation_rejected")
                return@forEach
            }

            var parsedResponse: GroceryIntentResponse? = null
            try {
                val response = GroceryIntentResponse.parse(stringAt(row["response_json"]), instruction)
                parsedResponse = response
                val prepared = response.draft.prepareForReview()
                val validationMessage = swiggyMcpItemValidationMessage("", prepared.items)
                if (validationMessage != null) {
                    diagnostics += Diagnostic(caseId, "app_validation_rejected", "item_validation_rejected")
                    return@forEach
                }

                val gate = GroceryIntentReviewGate()
                gate.prepare(prepared.unresolvedText)
                if (prepared.unresolvedText.isNotEmpty()) {
                    require(!gate.canApply()) { "Unresolved text must block until acknowledged" }
                    acknowledgementRequired++
                    gate.acknowledge(true)
                    require(gate.canApply()) { "Explicit acknowledgement should allow combined review" }
                } else {
                    require(gate.canApply())
                    readyWithoutExtraAck++
                }
                reviewReady++
            } catch (error: Throwable) {
                val category = when (error) {
                    is IntentNeedsReviewException -> "model_or_grounding_hard_rejection"
                    else -> "android_dto_or_preparation_rejected"
                }
                val response = parsedResponse
                val rejectedSources = response?.let {
                    groceryIntentGrounding(it.draft.instruction, it.draft.items).rejected.toSet()
                }.orEmpty()
                val rejectedIndices = response?.draft?.items.orEmpty().mapIndexedNotNull { index, item ->
                    index.takeIf { item.sourceText in rejectedSources }
                }
                val clarificationIndices = response?.draft?.items.orEmpty().mapIndexedNotNull { index, item ->
                    index.takeIf { item.needsClarification }
                }
                diagnostics += Diagnostic(
                    caseId = caseId,
                    category = category,
                    reason = error::class.java.simpleName,
                    rejectedItemIndices = rejectedIndices,
                    clarificationItemIndices = clarificationIndices,
                    modelNeedsClarification = response?.draft?.modelNeedsClarification == true,
                )
            }
        }

        require(readyWithoutExtraAck + acknowledgementRequired == reviewReady)
        val hardRejected = diagnostics.groupingBy { it.category }.eachCount().toSortedMap()
        val report = reportJson(
            total = expectedRows,
            reviewReady = reviewReady,
            readyWithoutExtraAck = readyWithoutExtraAck,
            acknowledgementRequired = acknowledgementRequired,
            hardRejected = hardRejected,
            diagnostics = diagnostics,
        )
        require(root["android_replay_report"] == null) { "Regenerate replay artifact before rerunning this report" }
        val source = file.readText(Charsets.UTF_8).trimEnd()
        require(source.endsWith("}")) { "Replay artifact root must be a JSON object" }
        file.writeText(source.dropLast(1) + ",\"android_replay_report\":$report}\n", Charsets.UTF_8)

        val failedIds = diagnostics.joinToString(",") { it.caseId }
        println(
            "ANDROID_INTENT_REPLAY total=$expectedRows review_ready=$reviewReady " +
                "ready_without_extra_ack=$readyWithoutExtraAck acknowledgement_required=$acknowledgementRequired " +
                "hard_rejected=${diagnostics.size} hard_rejected_categories=$hardRejected hardRejectedCaseIds=$failedIds",
        )
        // Reaching app review is not a semantic-accuracy claim; this only gates hard parsing/review failures.
        assertEquals("Rows failed before combined review: $failedIds", expectedRows, reviewReady)
    }

    private data class Diagnostic(
        val caseId: String,
        val category: String,
        val reason: String,
        val rejectedItemIndices: List<Int> = emptyList(),
        val clarificationItemIndices: List<Int> = emptyList(),
        val modelNeedsClarification: Boolean = false,
    )

    private fun objectAt(value: JsonValue?): Map<String, JsonValue> =
        (value as? JsonValue.Obj)?.members ?: error("Expected JSON object")

    private fun arrayAt(value: JsonValue?): List<JsonValue> =
        (value as? JsonValue.Arr)?.items ?: error("Expected JSON array")

    private fun stringAt(value: JsonValue?): String =
        (value as? JsonValue.Str)?.value ?: error("Expected JSON string")

    private fun numberAt(value: JsonValue?): Int =
        (value as? JsonValue.Num)?.raw?.toIntOrNull() ?: error("Expected integer JSON number")

    private fun reportJson(
        total: Int,
        reviewReady: Int,
        readyWithoutExtraAck: Int,
        acknowledgementRequired: Int,
        hardRejected: Map<String, Int>,
        diagnostics: List<Diagnostic>,
    ): String = "{" +
        "\"total\":$total," +
        "\"review_ready\":$reviewReady," +
        "\"ready_without_extra_ack\":$readyWithoutExtraAck," +
        "\"acknowledgement_required\":$acknowledgementRequired," +
        "\"hard_rejected\":${countMapJson(hardRejected)}," +
        "\"failures\":[" + diagnostics.joinToString(",") { item ->
            "{\"case_id\":${quote(item.caseId)},\"category\":${quote(item.category)},\"reason\":${quote(item.reason)}," +
                "\"rejected_item_indices\":${intArrayJson(item.rejectedItemIndices)}," +
                "\"clarification_item_indices\":${intArrayJson(item.clarificationItemIndices)}," +
                "\"model_needs_clarification\":${item.modelNeedsClarification}}"
        } + "]}"

    private fun countMapJson(counts: Map<String, Int>): String =
        "{" + counts.entries.joinToString(",") { "${quote(it.key)}:${it.value}" } + "}"

    private fun intArrayJson(values: List<Int>): String = "[" + values.joinToString(",") + "]"

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }

    private companion object {
        const val REPLAY_ENV = "BETA_INTENT_REPLAY_FILE"
        const val EXPECTED_SCHEMA = "beta-android-intent-replay-v1"
    }
}
