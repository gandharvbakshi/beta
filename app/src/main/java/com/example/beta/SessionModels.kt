package com.example.beta

import org.json.JSONObject
import java.util.*

// Session state enum matching backend specification
enum class SessionState(val value: String) {
    EXECUTING_SEARCH("ExecutingSearch"),
    EVALUATING_RESULTS("EvaluatingResults"),
    AWAITING_USER_CLARIFICATION("AwaitingUserClarification"),
    ADDING_TO_CART("AddingToCart"),
    VERIFYING_CHECKOUT("VerifyingCheckout"),
    SUMMARY_AND_EDIT("SummaryAndEdit"),
    COMPLETED("Completed"),
    ERROR("Error");

    companion object {
        fun fromString(value: String): SessionState? {
            return values().find { it.value == value }
        }
    }
}

// Policy object from backend response
data class Policy(
    val minConfidence: Double
)

// Progress object from backend response
data class Progress(
    val itemsTotal: Int,
    val itemsDone: Int,
    val itemsPending: List<String> = emptyList()
)

// Debug object from backend response
data class DebugInfo(
    val requestId: String,
    val notes: String? = null
)

// Error object from backend response
data class ErrorResponse(
    val reason: String,
    val details: String
)

// Verification status object from backend response (API v1.1)
data class VerificationStatus(
    val isVerificationStep: Boolean = false,
    val targetItem: String? = null,
    val itemFoundInCart: Boolean? = null,
    val verificationDetails: String? = null,
    val verificationFailed: Boolean = false,
    val retryAction: String? = null
)

// ActionResult for reporting back to backend
data class ActionResult(
    val actionId: String,
    val status: String, // "success" or "fail" or "fail_low_confidence"
    val notes: String
)

// Session context to track current session
data class SessionContext(
    val sessionId: String = UUID.randomUUID().toString(),
    val isActive: Boolean = true,
    var lastActionResult: ActionResult? = null,
    val actionHistory: MutableList<JSONObject> = mutableListOf()
)

// Helper extension for JSON conversion
fun ActionResult.toJson(): JSONObject {
    val json = JSONObject()
    json.put("action_id", actionId)
    json.put("status", status)
    json.put("notes", notes)
    return json
}
