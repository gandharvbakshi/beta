package com.example.beta

import org.junit.Assert.*
import org.junit.Test

class SwiggyCheckoutModelsTest {
    private val id = "12345678-1234-4234-9234-123456789012"
    private val review = """{"quoteToken":"signed-review","expiresAt":1900000000,"addressId":"home",
        "addressLabel":"Home","addressFull":"602, Sample apartments, Sample Road","amount":"123.45",
        "items":[{"name":"Milk","quantity":2,"variant":"500 ml","unitPrice":"45.00"}],
        "charges":[{"label":"Discount","amount":"-5.00"}],
        "methods":[{"id":"Cash","label":"Cash on delivery","paymentMethod":"Cash"}]}"""

    @Test fun parsesFullFinalReview() {
        val result = SwiggyMcpClient.parseCheckoutReview(review)
        assertEquals("123.45", result.amount)
        assertEquals(2, result.items.single().quantity)
        assertEquals("-5.00", result.charges.single().amount)
        assertEquals("Cash", result.methods.single().paymentMethod)
    }
    @Test fun rejectsUntrustedOrIncompleteFinalReview() {
        listOf(review.replace("123.45", "NaN"), review.replace("123.45", "-1.00"),
            review.replace("123.45", "1.001"), review.replace("\"quantity\":2", "\"quantity\":true"),
            review.replace("\"quantity\":2", "\"quantity\":0"), review.replace("\"expiresAt\":1900000000", "\"expiresAt\":true"),
            review.replace("\"paymentMethod\":\"Cash\"", "\"paymentMethod\":\"Card\""),
            review.replace("\"paymentMethod\":\"Cash\"", "\"paymentMethod\":\"UPI\""),
            review.replace("\"addressFull\":\"602, Sample apartments, Sample Road\"", "\"nested\":{\"addressFull\":\"fake\"}"))
            .forEach { bad -> assertThrows(Exception::class.java) { SwiggyMcpClient.parseCheckoutReview(bad) } }
    }
    @Test fun noneHasNoAttemptIdentity() {
        assertNull(SwiggyMcpClient.parseCheckoutAttempt("""{"state":"none","message":"No order attempt."}""").attemptId)
    }
    @Test fun placementRequiresExplicitStateAndIdentifiers() {
        val placed = """{"state":"placed","attemptId":"$id","message":"Instamart order placed successfully","orderIds":["order-one"]}"""
        assertEquals("placed", SwiggyMcpClient.parseCheckoutAttempt(placed).state)
        listOf(placed.replace("placed\"", "success\""), placed.replace("[\"order-one\"]", "[]"),
            placed.replace(id, "1-1-1-1-1")).forEach { bad ->
                assertThrows(Exception::class.java) { SwiggyMcpClient.parseCheckoutAttempt(bad) }
            }
    }
    @Test fun paymentNeedsTrustedLinkAndPollingHints() {
        val pending = """{"state":"pending_payment","attemptId":"$id","message":"Approve UPI","orderIds":["order-one"],
            "paymentUrl":"https://mcp.swiggy.com/pay/opaque","pollAfterMs":5000,"pollUntilEpochMs":1900000000000}"""
        assertEquals(5000L, SwiggyMcpClient.parseCheckoutAttempt(pending).pollAfterMs)
        listOf(pending.replace("mcp.swiggy.com", "evil.test"), pending.replace("5000", "false"),
            pending.replace("\"pollUntilEpochMs\":1900000000000", "\"other\":1")).forEach { bad ->
                assertThrows(Exception::class.java) { SwiggyMcpClient.parseCheckoutAttempt(bad) }
            }
    }
    @Test fun noRetryOrCartOnlyCopyForOrderMutations() {
        listOf("/swiggy/checkout/place", "/swiggy/checkout/status").forEach { path ->
            assertTrue(SwiggyMcpClient.isCartMutationPath(path))
            assertFalse(SwiggyMcpClient.swiggyRetryable(path, 503))
            assertFalse(SwiggyMcpClient.swiggyRetryable(path, 429))
            assertTrue(SwiggyMcpClient.swiggyNetworkFailureMessage(path).contains("do not place or pay again"))
            assertFalse(SwiggyMcpClient.swiggyUserMessageForHttpCode(path, 502, "{}").contains("Nothing was changed"))
        }
    }
    @Test fun onlyExactPredispatchFlagCanClearAttempt() {
        val path = "/swiggy/checkout/place"
        assertTrue(SwiggyMcpClient.checkoutOrderNotSubmittedFlag(path, 409, """{"detail":{"orderNotSubmitted":true}}"""))
        listOf("""{"detail":{"orderNotSubmitted":"true"}}""", """{"detail":{"orderNotSubmitted":1}}""",
            """{"orderNotSubmitted":true}""", """{"detail":{"nested":{"orderNotSubmitted":true}}}""").forEach {
            assertFalse(SwiggyMcpClient.checkoutOrderNotSubmittedFlag(path, 409, it))
        }
        assertFalse(SwiggyMcpClient.checkoutOrderNotSubmittedFlag(path, 502, """{"detail":{"orderNotSubmitted":true}}"""))
        assertFalse(SwiggyMcpClient.checkoutOrderNotSubmittedFlag("/swiggy/cart/apply", 409, """{"detail":{"orderNotSubmitted":true}}"""))
    }
}
