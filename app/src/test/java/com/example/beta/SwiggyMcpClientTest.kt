package com.example.beta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwiggyMcpClientTest {
    @Test
    fun parseStatusDetectsReconnectRequiredAndAuthorizationUrl() {
        val parsed = SwiggyMcpClient.parseStatus(
            """
            {
              "success": true,
              "data": {
                "state": "reconnect_required",
                "authorization_url": "https://auth.example/swiggy",
                "message": "Please reconnect"
              }
            }
            """.trimIndent()
        )

        assertEquals(SwiggyMcpClient.ConnectionState.RECONNECT_REQUIRED, parsed.state)
        assertEquals("https://auth.example/swiggy", parsed.authorizationUrl)
        assertEquals("Please reconnect", parsed.message)
    }

    @Test
    fun parseAddressesNormalizesLabelsFromMixedShapes() {
        val parsed = SwiggyMcpClient.parseAddresses(
            """
            {
              "success": true,
              "data": {
                "addresses": [
                  {
                    "addressId": "home-1",
                    "line1": "  12,  Main Street  ",
                    "line2": "  Apt 4B ",
                    "city": "Bengaluru",
                    "pincode": " 560001 "
                  },
                  "Work Address"
                ]
              }
            }
            """.trimIndent()
        )

        assertEquals(2, parsed.size)
        assertEquals("home-1", parsed[0].id)
        assertEquals("12, Main Street, Apt 4B, Bengaluru, 560001", parsed[0].normalizedLabel)
        assertEquals("Work Address", parsed[1].id)
        assertEquals("Work Address", parsed[1].normalizedLabel)
    }

    @Test
    fun parseRecommendationsChoosesSuggestedCandidateAndConfirmationFlag() {
        val parsed = SwiggyMcpClient.parseRecommendations(
            """
            {
              "success": true,
              "data": {
                "requires_confirmation": true,
                "candidates": [
                  {"spinId": "a", "label": "Fresh Milk", "variant": "1L"},
                  {"spinId": "b", "title": "Toned Milk", "suggested": true}
                ]
              }
            }
            """.trimIndent()
        )

        assertTrue(parsed.requiresConfirmation)
        assertEquals(2, parsed.candidates.size)
        assertEquals("a", parsed.candidates[0].spinId)
        assertEquals("1L", parsed.candidates[0].variant)
        assertEquals("b", parsed.suggested?.spinId)
        assertEquals("Toned Milk", parsed.suggested?.label)
    }

    @Test
    fun parseCartPlanAndApplyResultReadsMutationAndVerificationFlags() {
        val plan = SwiggyMcpClient.parseCartPlan(
            """
            {
              "success": true,
              "data": {
                "cart_mutation_enabled": true,
                "confirmation_token": "confirm-123",
                "changes": [
                  {
                    "type": "add",
                    "displayName": "Pencil",
                    "fromQuantity": 0,
                    "toQuantity": 2
                  }
                ]
              }
            }
            """.trimIndent()
        )

        val apply = SwiggyMcpClient.parseApplyResult(
            """
            {
              "verified": true,
              "message": "Applied",
              "reconnectRequired": false
            }
            """.trimIndent()
        )

        assertTrue(plan.cartMutationEnabled)
        assertEquals("confirm-123", plan.confirmationToken)
        assertEquals(1, plan.changes.size)
        assertEquals("add", plan.changes[0].kind)
        assertEquals("Pencil", plan.changes[0].displayName)
        assertEquals(0, plan.changes[0].fromQuantity)
        assertEquals(2, plan.changes[0].toQuantity)
        assertTrue(apply.verified)
        assertFalse(apply.reconnectRequired)
        assertEquals("Applied", apply.message)
    }

    @Test
    fun parseCapabilitiesRecognizesReadOnlyAndMutatingTools() {
        val parsed = SwiggyMcpClient.parseCapabilities(
            """
            {
              "success": true,
              "data": {
                "readOnlyTools": ["status", "addresses"],
                "mutatingTools": ["connect", "cartPlan"]
              }
            }
            """.trimIndent()
        )

        assertEquals(listOf("status", "addresses"), parsed.supported)
    }
}
