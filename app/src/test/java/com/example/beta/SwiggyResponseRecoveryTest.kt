package com.example.beta

import com.example.beta.SwiggyMcpClient.SwiggyMcpResult

import java.io.IOException
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwiggyResponseRecoveryTest {
    @Test fun unchangedCartIsATypedPlanOnlyOutcomeNotNestedTextOrWriteSuccess() {
        fun failure(path: String, code: Int, body: String) = SwiggyMcpClient.readSwiggyHttpResponse(
            path, response(body.toResponseBody(), code),
        ) { error("An error response must not be parsed as success") } as SwiggyMcpResult.Failure
        val valid = """{"detail":{"reason":"cart_no_changes"}}"""
        for (body in listOf(valid, """{"reason":"cart_no_changes"}""")) {
            val unchanged = failure("/swiggy/cart/plan", 409, body)
            assertTrue(unchanged.cartPlanUnchanged)
            assertNoAlreadyInCartClaim(unchanged.userMessage)
        }
        for (path in listOf("/swiggy/cart/apply", "/swiggy/checkout/place", "/swiggy/cart/check"))
            assertFalse(failure(path, 409, valid).cartPlanUnchanged)
        for (code in listOf(400, 401, 403, 404, 422, 429, 502))
            assertFalse(failure("/swiggy/cart/plan", code, valid).cartPlanUnchanged)
        for (body in listOf(
            """{"detail":{"reason":"cart_changed_replan_required"}}""",
            """{"detail":{"message":"cart_no_changes"}}""",
            """{"metadata":{"reason":"cart_no_changes"}}""",
            """{"reason":"cart_no_changes","detail":{"reason":"other"}}""",
            """{"detail":{"reason":"other","reason":"cart_no_changes"}}""",
            """{"detail":{"reason":"cart_no_changes","reconnectRequired":true}}""",
            valid + "{}",
        )) {
            val malformed = failure("/swiggy/cart/plan", 409, body)
            assertFalse(malformed.cartPlanUnchanged)
            assertNoAlreadyInCartClaim(malformed.userMessage)
        }
    }

    @Test fun truncatedResponseReachesFailureWithoutParsingOrRetrying() {
        for (path in listOf("/swiggy/recommendations/batch", "/swiggy/cart/apply", "/swiggy/intent")) {
            var parses = 0
            var closed = false
            val body = object : ResponseBody() {
                override fun contentType(): MediaType? = null
                override fun contentLength() = -1L
                private val broken = object : ForwardingSource(Buffer()) {
                    override fun read(sink: Buffer, byteCount: Long): Long = throw IOException("synthetic reset")
                    override fun close() { closed = true; super.close() }
                }.buffer()
                override fun source(): BufferedSource = broken
            }
            val result = SwiggyMcpClient.readSwiggyHttpResponse(path, response(body)) { parses++; it }
            assertTrue(result is SwiggyMcpResult.Failure)
            val failure = result as SwiggyMcpResult.Failure
            assertEquals(SwiggyMcpClient.swiggyNetworkFailureMessage(path), failure.userMessage)
            assertEquals(0, parses)
            assertFalse(failure.retryable)
            assertFalse(failure.intentUnavailable)
            assertTrue(closed)
        }
    }

    @Test fun unreadableApplyDoesNotClaimNothingChanged() {
        val result = SwiggyMcpClient.readSwiggyHttpResponse("/swiggy/cart/apply", response("bad-json".toResponseBody())) {
            throw IllegalArgumentException("synthetic malformed response")
        }
        val failure = result as SwiggyMcpResult.Failure
        assertTrue(failure.userMessage.contains("may have changed"))
        assertTrue(failure.userMessage.contains("review the cart"))
        assertFalse(failure.retryable)
    }

    @Test fun actualHttpWrapperKeepsRateLimitAndReconnectDistinct() {
        val limited = SwiggyMcpClient.readSwiggyHttpResponse(
            "/swiggy/recommendations/batch",
            response("""{"detail":{"reason":"swiggy_rate_limited","retryAfterSeconds":70}}""".toResponseBody(), 429),
        ) { it } as SwiggyMcpResult.Failure
        assertTrue(limited.userMessage.contains("2 minutes"))
        assertFalse(limited.reconnectRequired)
        val expired = SwiggyMcpClient.readSwiggyHttpResponse(
            "/swiggy/status", response("""{"detail":{"reason":"swiggy_reconnect_required","reconnectRequired":true}}""".toResponseBody(), 401),
        ) { it } as SwiggyMcpResult.Failure
        assertTrue(expired.reconnectRequired)
    }

    @Test fun intentUnavailableIsOnlyTheExplicitIntentEndpointNotFoundContract() {
        val unavailable = SwiggyMcpClient.readSwiggyHttpResponse(
            "/swiggy/intent",
            response("""{"detail":{"accepted":false,"reason":"intent_unavailable"}}""".toResponseBody(), 404),
        ) { it } as SwiggyMcpResult.Failure
        assertTrue(unavailable.intentUnavailable)
        assertFalse(unavailable.intentNeedsReview)
        assertFalse(unavailable.reconnectRequired)

        val other404 = SwiggyMcpClient.readSwiggyHttpResponse(
            "/swiggy/recommendations/batch",
            response("""{"detail":{"reason":"intent_unavailable"}}""".toResponseBody(), 404),
        ) { it } as SwiggyMcpResult.Failure
        assertFalse(other404.intentUnavailable)

        val unrelated404 = SwiggyMcpClient.readSwiggyHttpResponse(
            "/swiggy/intent",
            response("""{"detail":{"reason":"not_found"}}""".toResponseBody(), 404),
        ) { it } as SwiggyMcpResult.Failure
        assertFalse(unrelated404.intentUnavailable)

        val malformed404 = SwiggyMcpClient.readSwiggyHttpResponse(
            "/swiggy/intent", response("not-json".toResponseBody(), 404),
        ) { it } as SwiggyMcpResult.Failure
        assertFalse(malformed404.intentUnavailable)

        val rejected422 = SwiggyMcpClient.readSwiggyHttpResponse(
            "/swiggy/intent",
            response("""{"detail":{"reason":"intent_unavailable"}}""".toResponseBody(), 422),
        ) { it } as SwiggyMcpResult.Failure
        assertFalse(rejected422.intentUnavailable)
        assertTrue(rejected422.intentNeedsReview)
        assertFalse(rejected422.reconnectRequired)
    }

    @Test fun gatewayErrorOnApplyIsUncertainAndNeverMarkedRetryable() {
        for (code in listOf(500, 502, 503, 504)) {
            var parses = 0
            val result = SwiggyMcpClient.readSwiggyHttpResponse(
                "/swiggy/cart/apply", response("<html>Gateway error</html>".toResponseBody(), code),
            ) { parses++; it } as SwiggyMcpResult.Failure
            assertEquals(0, parses)
            assertEquals(code, result.httpCode)
            assertFalse(result.retryable)
            assertFalse(result.reconnectRequired)
            assertTrue(result.userMessage.contains("cannot confirm whether the cart changed"))
            assertTrue(result.userMessage.contains("review the cart"))
            assertTrue(SwiggyMcpClient.swiggyRetryable("/swiggy/status", code))
        }
    }

    private fun assertNoAlreadyInCartClaim(message: String) {
        assertFalse(message.contains("already has those quantities", ignoreCase = true))
        assertFalse(message.contains("already in your cart", ignoreCase = true))
    }

    private fun response(body: ResponseBody, code: Int = 200) = Response.Builder()
        .request(Request.Builder().url("https://example.invalid/synthetic").build())
        .protocol(Protocol.HTTP_1_1).code(code).message("synthetic").body(body).build()
}
