package com.example.beta

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwiggyCheckoutUrlTest {
    @Test fun opensOnlyExplicitTrustedHttpsHosts() {
        assertTrue(isSafeCheckoutPaymentUrl("https://mcp.swiggy.com/payment/opaque-id"))
        assertTrue(isSafeCheckoutPaymentUrl("https://www.swiggy.com:443/payment?opaque=value"))
        listOf("http://swiggy.com/pay", "javascript:alert(1)", "intent://pay",
            "upi://pay", "https://swiggy.com.evil.test/pay", "https://evil.test/?swiggy.com",
            "https://user@swiggy.com/pay", "https://swiggy.com:444/pay", "https://localhost/pay",
            "https://127.0.0.1/pay", "https://[::1]/pay", "https://swiggy.com\\@evil.test/pay",
            "https://swiggy.com/\n", "https://mcp.swiggy.com/" + "a".repeat(4096)
        ).forEach { assertFalse(it, isSafeCheckoutPaymentUrl(it)) }
    }
}
