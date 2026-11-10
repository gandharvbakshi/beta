package com.example.beta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentCommaContainerSafetyTest {
    @Test fun mergedProductNamesCannotEraseCommaBoundContainerScope() {
        val cases = listOf(
            Case(
                source = "one room spray, rose fragrance bottle",
                name = "room spray rose fragrance",
                packaging = "bottle",
            ),
            Case(
                source = "one bottle brush, red colour bottle",
                name = "bottle brush red colour bottle",
                packaging = "bottle",
            ),
            Case(
                source = "one bottle brush, red colour bottle",
                name = "bottle brush red colour",
                packaging = "bottle",
            ),
        )

        cases.forEach { case ->
            val prepared = prepare(case)

            assertTrue("${case.source} must not produce a ready item", prepared.items.isEmpty())
            assertFalse("${case.source} must remain visibly unresolved",
                prepared.unresolvedText.isEmpty() && prepared.excludedText.isEmpty())
            assertNotNull(prepared.recoverySnapshot)
            assertEquals(case.source, prepared.recoverySnapshot!!.toDisplay().originalInstruction)
        }
    }

    @Test fun supportedLiteralRoomSprayBottleRemainsAReadyPack() {
        val source = "one Acme Valley gentle lavender room spray bottle"
        val prepared = prepare(Case(
            source = source,
            name = "Acme Valley gentle lavender room spray",
            packaging = "bottle",
        ))

        assertEquals(1, prepared.items.size)
        assertTrue(prepared.items.single().query.contains("Acme Valley gentle lavender room spray"))
        assertTrue(prepared.items.single().retailPackCount)
        assertEquals(1, (prepared.items.single().quantity as com.example.beta.automation.Quantity.Count).n)
        assertTrue(prepared.unresolvedText.isEmpty())
        assertTrue(prepared.excludedText.isEmpty())
    }

    private fun prepare(case: Case): GroceryIntentPreparation {
        val instruction = case.source
        val start = instruction.indexOf(case.source)
        require(start >= 0)
        val row = """{"row_index":0,"item":{"name":${jsonString(case.name)},"quantity":1,"quantity_unit":"pack","pack_value":null,"pack_unit":"unspecified","packaging":${jsonString(case.packaging)},"required":[],"excluded":[],"source_text":${jsonString(case.source)},"supporting_text":[],"needs_clarification":false,"quantity_text":"one","pack_text":null},"evidence":{"primary":{"text":${jsonString(case.source)},"start_utf16":$start,"end_utf16":${start + case.source.length}},"supporting":[]},"quantity_origin":"stated","amount_role_status":"matched"}"""
        val body = """{"accepted":true,"draft":{"contract_version":"grocery-intent-v2","requires_review":true,"instruction":${jsonString(instruction)},"source_item_count":1,"ready_rows":[$row],"blocked_rows":[],"groups":[]},"model":"test","promptVersion":"comma-container-test","latencyMs":1,"usage":{}}"""
        return GroceryIntentReviewV2Response.parse(body, instruction).draft.prepareForReview()
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }

    private data class Case(val source: String, val name: String, val packaging: String)
}
