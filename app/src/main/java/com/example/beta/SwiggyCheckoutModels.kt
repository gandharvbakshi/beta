package com.example.beta

data class SwiggyCheckoutReview(val quoteToken: String, val expiresAt: Long,
    val addressId: String, val addressLabel: String, val addressFull: String,
    val amount: String, val items: List<SwiggyCheckoutLine>,
    val charges: List<SwiggyCheckoutCharge>, val methods: List<SwiggyCheckoutMethod>)
data class SwiggyCheckoutLine(val name: String, val quantity: Int, val variant: String, val unitPrice: String)
data class SwiggyCheckoutCharge(val label: String, val amount: String)
data class SwiggyCheckoutMethod(val id: String, val label: String, val paymentMethod: String,
    val intentApp: String? = null, val generateUPIQR: Boolean = false)
data class SwiggyCheckoutAttempt(val attemptId: String?, val state: String, val message: String,
    val orderIds: List<String> = emptyList(), val paymentUrl: String? = null,
    val pollAfterMs: Long? = null, val pollUntilEpochMs: Long? = null)
