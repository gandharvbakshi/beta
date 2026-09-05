package com.example.beta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TelemetryPolicyTest {
    @Test
    fun acceptsOnlyTypedAllowlistedGrowthParameters() {
        assertEquals(
            mapOf("item_count" to 8, "source" to "voice"),
            TelemetryPolicy.validated(
                "order_request_submitted",
                mapOf("item_count" to 8, "source" to "voice"),
                strict = true,
            ),
        )
    }

    @Test
    fun rejectsOrderTextAddressAndPinLikeValues() {
        assertThrows(IllegalArgumentException::class.java) {
            TelemetryPolicy.validated(
                "order_request_submitted",
                mapOf("order_text" to "milk", "reason" to "home_560047"),
                strict = true,
            )
        }
    }

    @Test
    fun rejectsAddressLikeTextEvenWhenThePropertyKeyIsAllowed() {
        assertThrows(IllegalArgumentException::class.java) {
            TelemetryPolicy.validated(
                "address_selected",
                mapOf("selection_reason" to "8 18 Lynwood Avenue"),
                strict = true,
            )
        }
    }

    @Test
    fun acceptsOnlyTheEnumeratedProductionLabels() {
        assertEquals(
            mapOf(
                "selection_reason" to "near_you_recently_used",
                "outcome" to "success",
            ),
            TelemetryPolicy.validated(
                "address_selected",
                mapOf(
                    "selection_reason" to "Near you · recently used",
                    "outcome" to "success",
                ),
                strict = true,
            ),
        )
        assertEquals(
            mapOf("selection_reason" to "same_area_recently_used"),
            TelemetryPolicy.validated(
                "address_selected",
                mapOf("selection_reason" to "same_area_recently_used"),
                strict = true,
            ),
        )
        assertEquals(
            mapOf("selection_reason" to "same_area_as_your_location"),
            TelemetryPolicy.validated(
                "address_selected",
                mapOf("selection_reason" to "same_area_as_your_location"),
                strict = true,
            ),
        )
        assertEquals(
            mapOf("selection_reason" to "current_cart_address"),
            TelemetryPolicy.validated(
                "address_selected",
                mapOf("selection_reason" to "Current cart address"),
                strict = true,
            ),
        )
        assertEquals(
            mapOf("selection_reason" to "recently_used"),
            TelemetryPolicy.validated(
                "address_selected",
                mapOf("selection_reason" to "Recently used"),
                strict = true,
            ),
        )
    }
}
