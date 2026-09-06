package com.example.beta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryPrivacyAcceptanceTest {
    @Test
    fun unknown_event_names_are_dropped_in_release_and_rejected_in_strict_mode() {
        assertFalse(TelemetryPolicy.isEventAllowed("private_text_like_event"))

        val release = TelemetryPolicy.validated(
            name = "totally_new_event",
            params = mapOf(
                "category" to "verified_cart",
                "address" to "home",
            ),
            strict = false,
        )
        assertTrue(release.isEmpty())

        try {
            TelemetryPolicy.validated(
                name = "totally_new_event",
                params = mapOf("category" to "verified_cart"),
                strict = true,
            )
            throw AssertionError("Expected strict telemetry validation to reject unknown event names")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun raw_sensitive_keys_are_dropped_from_release_payloads() {
        val validated = TelemetryPolicy.validated(
            name = "feedback_submitted",
            params = mapOf(
                "address" to "home",
                "token" to "abc123",
                "grocery_item" to "milk",
                "message" to "looks fine",
                "category" to "verified_cart",
            ),
            strict = false,
        )

        assertEquals(setOf("category"), validated.keys)
        assertEquals("verified_cart", validated["category"])
    }

    @Test
    fun numeric_and_boolean_values_are_type_and_range_checked() {
        val validated = TelemetryPolicy.validated(
            name = "cart_update_verified",
            params = mapOf(
                "item_count" to 200,
                "change_count" to 0L,
                "days_since_first_open" to 36500L,
                "activation_age_days" to 42,
                "starts_empty" to true,
                "value" to false,
                "consent_delayed" to true,
                "activation_timestamp_known" to false,
                "selection_reason" to 12.34,
                "outcome" to "success",
            ),
            strict = false,
        )

        assertEquals(200, validated["item_count"])
        assertEquals(0L, validated["change_count"])
        assertEquals(36500L, validated["days_since_first_open"])
        assertEquals(42, validated["activation_age_days"])
        assertEquals(true, validated["starts_empty"])
        assertEquals(false, validated["value"])
        assertEquals(true, validated["consent_delayed"])
        assertEquals(false, validated["activation_timestamp_known"])
        assertFalse("String enum fields must reject numeric payloads", validated.containsKey("selection_reason"))
        assertEquals("success", validated["outcome"])
    }

    @Test
    fun retention_week_one_is_an_allowed_analytics_event() {
        assertTrue(TelemetryPolicy.isEventAllowed("retention_w1"))
    }

    @Test
    fun non_finite_numeric_values_are_dropped() {
        val validated = TelemetryPolicy.validated(
            name = "cart_update_verified",
            params = mapOf(
                "item_count" to Double.NaN,
                "change_count" to Double.POSITIVE_INFINITY,
                "days_since_first_open" to Double.NEGATIVE_INFINITY,
                "outcome" to "success",
            ),
            strict = false,
        )

        assertFalse(validated.containsKey("item_count"))
        assertFalse(validated.containsKey("change_count"))
        assertFalse(validated.containsKey("days_since_first_open"))
        assertEquals("success", validated["outcome"])
    }

    @Test
    fun out_of_range_numeric_counts_are_dropped() {
        val validated = TelemetryPolicy.validated(
            name = "cart_update_verified",
            params = mapOf(
                "item_count" to 201,
                "change_count" to -1,
                "days_since_first_open" to 36501L,
                "outcome" to "success",
            ),
            strict = false,
        )

        assertFalse(validated.containsKey("item_count"))
        assertFalse(validated.containsKey("change_count"))
        assertFalse(validated.containsKey("days_since_first_open"))
        assertEquals("success", validated["outcome"])
    }

    @Test
    fun enum_reason_values_accept_current_and_legacy_rank_labels() {
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
            mapOf("selection_reason" to "same_area_recently_used"),
            TelemetryPolicy.validated(
                "address_selected",
                mapOf("selection_reason" to "Same area · recently used"),
                strict = true,
            ),
        )
        assertEquals(
            mapOf("selection_reason" to "same_area_as_your_location"),
            TelemetryPolicy.validated(
                "address_selected",
                mapOf("selection_reason" to "Same area as your location"),
                strict = true,
            ),
        )
        assertEquals(
            mapOf("selection_reason" to "near_you_recently_used"),
            TelemetryPolicy.validated(
                "address_selected",
                mapOf("selection_reason" to "Near you · recently used"),
                strict = true,
            ),
        )
    }
}
