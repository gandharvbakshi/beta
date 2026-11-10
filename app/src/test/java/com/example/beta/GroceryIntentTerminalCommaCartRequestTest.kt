package com.example.beta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentTerminalCommaCartRequestTest {
    @Test fun terminalCommaCartRequestIsReceiptedOnlyAfterTheLastSurvivingPrimary() {
        val instruction = "Beta, ek fresh apple, sebu, cart mein daal do."
        val primary = spanOf(instruction, "ek fresh apple, sebu")

        assertEquals(
            GroceryIntentCoverageSpan(primary.endUtf16, instruction.length),
            groceryIntentTerminalCommaCartRequest(instruction, listOf(primary), listOf(primary)),
        )
        assertNull(groceryIntentTerminalCommaCartRequest(instruction, listOf(primary), emptyList()))
        assertNull(groceryIntentTerminalCommaCartRequest(
            instruction, listOf(GroceryIntentCoverageSpan(-1, primary.endUtf16)), listOf(primary),
        ))
        assertNull(groceryIntentTerminalCommaCartRequest(
            instruction, listOf(GroceryIntentCoverageSpan(primary.startUtf16, instruction.length + 1)), listOf(primary),
        ))
        assertNull(groceryIntentTerminalCommaCartRequest(
            instruction,
            listOf(primary, GroceryIntentCoverageSpan(primary.startUtf16 + 1, primary.endUtf16)),
            listOf(primary),
        ))
    }

    @Test fun validHindiTerminalCommaRequestsPrepareWithExactSourcesAndCounts() {
        for ((instruction, row) in listOf(
            "Beta, ek fresh apple, sebu, cart mein daal do." to
                RowSpec("ek fresh apple, sebu", "fresh apple", 1, "ek"),
            "do apples, cart mein daal do." to
                RowSpec("do apples", "apples", 2, "do"),
        )) {
            val prepared = prepare(instruction, listOf(row))

            assertEquals(1, prepared.items.size)
            assertEquals(row.source, prepared.items.single().rawText)
            assertEquals(row.quantity, (prepared.items.single().quantity as com.example.beta.automation.Quantity.Count).n)
            assertTrue(prepared.unresolvedText.isEmpty())
            assertTrue(prepared.excludedText.isEmpty())
        }
    }

    @Test fun quotedReportedOrIncompleteCartRequestsRemainUnresolved() {
        val cases = listOf(
            "The card says, \"ek fresh apple, sebu, cart mein daal do.\"",
            "The printed sign reads: ek fresh apple, sebu, cart mein daal do.",
            "The card says, \"ek fresh apple, sebu, cart mein daal do.",
            "Beta, ek fresh apple, sebu, cart mein daal do tomorrow.",
            "Beta, ek fresh apple, sebu, no sugar, cart mein daal do.",
            "Beta, ek fresh apple, sebu, two more, cart mein daal do.",
        )
        cases.forEach { instruction ->
            val source = "ek fresh apple, sebu"
            val prepared = prepare(instruction, listOf(RowSpec(source, "fresh apple", 1, "ek")))

            assertTrue("$instruction must not silently close over a cart request",
                prepared.unresolvedText.isNotEmpty() || prepared.excludedText.isNotEmpty())
        }
    }

    @Test fun missingBoundSoapCannotBeHiddenByRequestSuffix() {
        val instruction = "one soap, ek fresh apple, sebu, cart mein daal do."
        val prepared = prepare(instruction, listOf(RowSpec("ek fresh apple, sebu", "fresh apple", 1, "ek")))

        assertTrue(prepared.unresolvedText.any { "one soap" in it })
    }

    @Test fun extraGapBeforeTheCartRequestHasNoDirectCoverageReceipt() {
        val instruction = "ek fresh apple, sebu, plus another product, cart mein daal do."
        val primary = spanOf(instruction, "ek fresh apple, sebu")

        assertNull(groceryIntentTerminalCommaCartRequest(instruction, listOf(primary), listOf(primary)))
    }

    @Test fun directReceiptRejectsNewlinesUnicodeCartWordsAndQuotesOpenedEarlier() {
        val cases = listOf(
            "Beta, ek fresh apple, sebu,\ncart mein daal do.",
            "Beta, ek fresh apple, sebu, cart meİn daal do.",
            "A sign says \"not a request. ek fresh apple, sebu, cart mein daal do.",
        )
        cases.forEach { instruction ->
            val primary = spanOf(instruction, "ek fresh apple, sebu")
            assertNull(instruction, groceryIntentTerminalCommaCartRequest(
                instruction, listOf(primary), listOf(primary),
            ))
        }
    }

    @Test fun sourceContainedEngravingAndInscriptionAreNotPurchasableDescriptions() {
        for ((source, name) in listOf(
            "one mug engraved" to "mug engraved",
            "one plaque inscribed" to "plaque inscribed",
            "one mug bearing" to "mug bearing",
            "one mug with slogan" to "mug with slogan",
        )) {
            val instruction = "$source, cart mein daal do."
            val prepared = prepare(instruction, listOf(RowSpec(source, name, 1, "one")))

            assertTrue("$source must remain held", prepared.items.isEmpty())
            assertTrue(prepared.recoverySnapshot != null || prepared.unresolvedText.isNotEmpty())
        }
    }

    @Test fun failedFinalOwnerIdentityRevokesReceiptAndKeepsRecovery() {
        val instruction = "one rice, one organic apple, cart mein daal do."
        val prepared = prepare(instruction, listOf(
            RowSpec("one rice", "rice", 1, "one"),
            RowSpec("one organic apple", "apple", 1, "one"),
        ))

        assertTrue(prepared.recoverySnapshot?.toDisplay()?.originalInstruction == instruction)
        assertTrue(prepared.excludedText.any { "one organic apple" in it })
        assertTrue(prepared.items.none { "apple" in it.query })
        val primaries = listOf(spanOf(instruction, "one rice"), spanOf(instruction, "one organic apple"))
        val surviving = primaries.filter { span ->
            prepared.items.any { it.rawText == instruction.substring(span.startUtf16, span.endUtf16) }
        }
        assertNull(groceryIntentTerminalCommaCartRequest(instruction, primaries, surviving))
        // The legacy lexical coverage aid may omit familiar courtesy wording;
        // it is not a receipt or permission to release the failed owning row.
    }

    private fun prepare(instruction: String, rows: List<RowSpec>): GroceryIntentPreparation {
        val rowJson = rows.mapIndexed { index, row ->
            val sourceSpan = spanOf(instruction, row.source)
            val required = row.required.joinToString(prefix = "[", postfix = "]", transform = ::jsonString)
            val excluded = row.excluded.joinToString(prefix = "[", postfix = "]", transform = ::jsonString)
            """{"row_index":$index,"item":{"name":${jsonString(row.name)},"quantity":${row.quantity},"quantity_unit":"count","pack_value":null,"pack_unit":"unspecified","packaging":null,"required":$required,"excluded":$excluded,"source_text":${jsonString(row.source)},"supporting_text":[],"needs_clarification":false,"quantity_text":${jsonString(row.quantityText)},"pack_text":null},"evidence":{"primary":{"text":${jsonString(row.source)},"start_utf16":${sourceSpan.startUtf16},"end_utf16":${sourceSpan.endUtf16}},"supporting":[]},"quantity_origin":"stated","amount_role_status":"matched"}"""
        }
        val body = """{"accepted":true,"draft":{"contract_version":"grocery-intent-v2","requires_review":true,"instruction":${jsonString(instruction)},"source_item_count":${rows.size},"ready_rows":[${rowJson.joinToString(",")}],"blocked_rows":[],"groups":[]},"model":"test","promptVersion":"terminal-comma-request-test","latencyMs":1,"usage":{}}"""
        return GroceryIntentReviewV2Response.parse(body, instruction).draft.prepareForReview()
    }

    private fun spanOf(instruction: String, source: String): GroceryIntentCoverageSpan {
        val start = instruction.indexOf(source)
        require(start >= 0 && start == instruction.lastIndexOf(source))
        return GroceryIntentCoverageSpan(start, start + source.length)
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

    private data class RowSpec(
        val source: String,
        val name: String,
        val quantity: Int,
        val quantityText: String,
        val required: List<String> = emptyList(),
        val excluded: List<String> = emptyList(),
    )
}
