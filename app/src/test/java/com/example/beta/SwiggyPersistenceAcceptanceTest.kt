package com.example.beta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwiggyPersistenceAcceptanceTest {
    @Test
    fun parseApplyResult_onlyTreatsLiteralBooleanTrueAsPersistenceVerified() {
        val cases = listOf(
            """{"verified":true,"persistenceVerified":true}""" to true,
            """{"verified":true,"persistenceVerified":false}""" to false,
            """{"verified":true,"persistenceVerified":"true"}""" to false,
            """{"verified":true,"persistenceVerified":1}""" to false,
            """{"verified":true}""" to false,
        )

        cases.forEach { (body, expected) ->
            assertEquals(expected, SwiggyMcpClient.parseApplyResult(body).persistenceVerified)
        }
    }

    @Test
    fun parseApplyResult_keepsVerifiedAndPersistenceVerifiedIndependent() {
        val parsed = SwiggyMcpClient.parseApplyResult(
            """{"verified":true,"persistenceVerified":false,"message":"Applied","reconnectRequired":false}"""
        )

        assertTrue(parsed.verified)
        assertFalse(parsed.persistenceVerified)
        assertEquals("Applied", parsed.message)
        assertFalse(parsed.reconnectRequired)
    }
}
