package com.example.beta

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class GroceryIntentInterpretedResponseTest {
    @Test
    fun malformedOrRejectedInterpretedEnvelopeFailsClosed() {
        val source = """{"text":"milk","start_utf16":0,"end_utf16":4}"""
        val claim = """{"name":"milk","quantity":null,"quantity_unit":"unspecified","pack_value":null,"pack_unit":"unspecified","required":[],"excluded":[],"source_text":"milk","needs_clarification":false}"""
        val item = claim.dropLast(1) + """, "packaging":null,"supporting_text":[],"quantity_text":null,"pack_text":null}"""
        val draft = """{"contract_version":"grocery-intent-interpreted-v2","requires_review":true,"instruction":"milk","source_item_count":1,"claims":[$claim],"requirements":[[]],"rows":[{"row_index":0,"item":$item,"evidence":{"primary":$source,"supporting":[]},"quantity_atom":null,"pack_atom":null,"unassigned_numeric":[],"requirements":[]}],"blocked_rows":[],"context":[],"audit":{"version":"value-audit-v3","request_fingerprint":"${"a".repeat(64)}","rows":[{"row_index":0,"issues":[],"quantity_role":"unstated","package_size_role":"unstated","packaging":null,"requirements":[]}],"source":[{"source":$source,"role":"item","row_indices":[0]}]}}"""
        val base = """{"accepted":true,"draft":$draft,"model":"fixture","promptVersion":"test","latencyMs":1,"usage":{}}"""
        assertEquals("milk", GroceryIntentInterpretedResponse.parse(base, "milk").prepareForReview().items.single().query)
        assertTrue(runCatching { GroceryIntentInterpretedResponse.parse(base.replace("\"accepted\":true", "\"accepted\":false"), "milk") }.isFailure)
        assertTrue(runCatching { GroceryIntentInterpretedResponse.parse(base.replace("grocery-intent-interpreted-v2", "grocery-intent-interpreted-v1"), "milk") }.isFailure)
        assertTrue(runCatching { GroceryIntentInterpretedResponse.parse(base, "one milk") }.isFailure)
        assertTrue(runCatching { GroceryIntentInterpretedResponse.parse(base.dropLast(1) + ",\"extra\":true}", "milk") }.isFailure)
        for ((before, after) in listOf(
            "\"latencyMs\":1" to "\"latencyMs\":-1",
            "\"latencyMs\":1" to "\"latencyMs\":true",
            "\"latencyMs\":1" to "\"latencyMs\":1e999",
            "\"usage\":{}" to "\"usage\":{\"secret\":1}",
            "\"usage\":{}" to "\"usage\":{\"input_tokens\":true}",
            "\"model\":\"fixture\"" to "\"model\":\"   \"",
            "\"promptVersion\":\"test\"" to "\"promptVersion\":\" \"",
            "\"accepted\":true" to "\"accepted\":true,\"accepted\":true",
            "\"quantity_role\":\"unstated\"" to "\"quantity_role\":\"unstated\",\"quantity_role\":\"unstated\"",
        )) assertTrue("Must reject $after", runCatching { GroceryIntentInterpretedResponse.parse(base.replace(before, after), "milk") }.isFailure)
    }
}
