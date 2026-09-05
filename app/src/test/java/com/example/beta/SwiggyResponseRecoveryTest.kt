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
    @Test fun truncatedResponseReachesFailureWithoutParsingOrRetrying() {
        for (path in listOf("/swiggy/recommendations/batch", "/swiggy/cart/apply")) {
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

    private fun response(body: ResponseBody, code: Int = 200) = Response.Builder()
        .request(Request.Builder().url("https://example.invalid/synthetic").build())
        .protocol(Protocol.HTTP_1_1).code(code).message("synthetic").body(body).build()
}
