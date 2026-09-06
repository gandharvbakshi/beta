package com.example.beta

import android.content.Context

/** Typed boundary allows the complete UI flow to be tested without a provider call. */
internal interface SwiggyCheckoutGateway {
    fun addresses(callback: SwiggyCallback<List<SwiggyMcpClient.SwiggyAddress>>)
    fun review(addressId: String, callback: SwiggyCallback<SwiggyCheckoutReview>)
    fun place(quoteToken: String, methodId: String, attemptId: String, callback: SwiggyCallback<SwiggyCheckoutAttempt>)
    fun status(attemptId: String?, callback: SwiggyCallback<SwiggyCheckoutAttempt>)
}

internal class LiveSwiggyCheckoutGateway(private val context: Context) : SwiggyCheckoutGateway {
    override fun addresses(callback: SwiggyCallback<List<SwiggyMcpClient.SwiggyAddress>>) =
        SwiggyMcpClient.fetchAddresses(context, callback)
    override fun review(addressId: String, callback: SwiggyCallback<SwiggyCheckoutReview>) =
        SwiggyMcpClient.reviewCheckout(context, addressId, callback)
    override fun place(quoteToken: String, methodId: String, attemptId: String, callback: SwiggyCallback<SwiggyCheckoutAttempt>) =
        SwiggyMcpClient.placeCheckout(context, quoteToken, methodId, attemptId, callback)
    override fun status(attemptId: String?, callback: SwiggyCallback<SwiggyCheckoutAttempt>) =
        SwiggyMcpClient.checkoutStatus(context, attemptId, callback)
}
