package com.example.beta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class GroceryIntentExcludedContextTest {
    @Test
    fun oldBlockedRowsRemainCompatibleAndValidReceiptStaysVisibleOnlyAsExcludedContext() {
        val oldInstruction = "one bread; 1 packet garbage bags"
        val old = GroceryIntentReviewV2Response.parse(response(
            oldInstruction, ready = listOf(readyRow(oldInstruction, "one bread")),
            blocked = listOf(blockedRow(oldInstruction, "1 packet garbage bags", 1, receipt = null)),
        ), oldInstruction).draft
        assertTrue(old.blockedRows.single().excludedContext.isEmpty())

        val instruction = "one bread; 1 packet garbage bags; please choose organic garbage bags if available."
        val draft = parseValid(instruction)
        val preparation = draft.prepareForReview()

        assertEquals(1, preparation.items.size)
        assertEquals("bread", draft.readyRows.single().item.name)
        assertTrue(preparation.excludedText.single().contains("1 packet garbage bags"))
        assertTrue(preparation.excludedText.single().contains("please choose organic garbage bags if available."))
        assertTrue(preparation.unresolvedText.isEmpty())

        val gate = GroceryIntentReviewGate()
        val itemsBeforeConfirmation = preparation.items.toList()
        gate.prepare(preparation.unresolvedText, preparation.excludedText, preparation.sharedGroupNotes)
        gate.acknowledge(true)
        assertFalse(gate.canApply())
        assertEquals(listOf("bread"), itemsBeforeConfirmation.map { it.query })
        gate.confirmExclusions()
        assertTrue(gate.canApply())
        assertEquals(itemsBeforeConfirmation, preparation.items)

        val multiline = "😀 one bread;\n1 packet garbage bags;\nplease choose organic garbage bags if available."
        assertEquals(1, parseValid(multiline).prepareForReview().items.size)
    }

    @Test
    fun targetOffsetsPrefixSuffixScopeAndUniquenessAreIndependentlyProven() {
        val validInstruction = "one bread; 1 packet garbage bags; please choose organic garbage bags if available."
        val valid = responseWithReceipt(validInstruction)
        val badOffset = valid.replace("\"start_utf16\":${validInstruction.indexOf("please choose")}", "\"start_utf16\":0")
        assertRejected(validInstruction, badOffset)
        val wrongTargetQuote = valid.replace(
            "\"text\":${json("please choose organic garbage bags if available.")}",
            "\"text\":${json("please choose organic other bags if available.")}",
        )
        assertRejected(validInstruction, wrongTargetQuote)
        val wrongTarget = "one bread; 1 packet garbage bags; please choose organic bread if available."
        assertRejected(wrongTarget, responseWithReceipt(wrongTarget, receiptText = "please choose organic bread if available."))
        val milkCompound = "one bread; 1 packet milk chocolate; please choose organic milk if available."
        assertRejected(milkCompound, responseWithReceipt(
            milkCompound, blockedSource = "1 packet milk chocolate", receiptText = "please choose organic milk if available.",
        ))
        val coconutCompound = "one bread; 1 packet coconut water; please choose organic coconut if available."
        assertRejected(coconutCompound, responseWithReceipt(
            coconutCompound, blockedSource = "1 packet coconut water", receiptText = "please choose organic coconut if available.",
        ))

        val prefix = "one bread; 1 packet garbage bags; if diabetic, please choose organic garbage bags if available."
        assertRejected(prefix, responseWithReceipt(prefix))
        val suffix = "one bread; 1 packet garbage bags; please choose organic garbage bags if available for everyone."
        assertRejected(suffix, responseWithReceipt(suffix, receiptText = "please choose organic garbage bags if available for everyone."))
        val global = "one bread; 1 packet garbage bags; please choose organic garbage bags for everyone if available."
        assertRejected(global, responseWithReceipt(global, receiptText = "please choose organic garbage bags for everyone if available."))

        val duplicateTargets = "one bread; 1 packet garbage bags; 2 packets garbage bags; please choose organic garbage bags if available."
        assertRejected(duplicateTargets, responseWithReceipt(duplicateTargets, duplicateTarget = true))
    }

    @Test
    fun receiptsFailClosedForMissingPrimaryOverlapHeaderScopeAndReadyRows() {
        val missing = "one bread; 1 packet garbage bags; unknown item; please choose organic garbage bags if available."
        assertRejected(missing, responseWithReceipt(missing, missingPrimary = true))

        val overlap = "one bread; 1 packet garbage bags; please choose organic garbage bags if available."
        assertRejected(overlap, responseWithReceipt(overlap, receiptInPrimary = true))

        val header = "one bread: 1 packet garbage bags; please choose organic garbage bags if available."
        assertRejected(header, responseWithReceipt(header))

        val readyReceipt = "one bread; 1 packet garbage bags; please choose organic garbage bags if available."
        val ready = readyRow(readyReceipt, "one bread").dropLast(1) +
            ",\"excluded_context\":[${quote(readyReceipt, "please choose organic garbage bags if available.")}] }"
        assertRejected(readyReceipt, response(
            readyReceipt, listOf(ready), listOf(blockedRow(readyReceipt, "1 packet garbage bags", 1)),
        ))

        val support = readyRow(readyReceipt, "one bread", support = "please choose organic garbage bags if available.")
        assertRejected(readyReceipt, response(
            readyReceipt, listOf(support), listOf(blockedRow(readyReceipt, "1 packet garbage bags", 1,
                "please choose organic garbage bags if available.")),
        ))
    }

    @Test
    fun receiptNeverSwallowsLaterGlobalConditionOrResurrectsBlockedTarget() {
        listOf("No sugar.", "Make everything two.", "For diabetics.", "My father has diabetes.",
            "For someone with an allergy.", "Friday morning delivery.").forEach { global ->
            val instruction = "one bread; 1 packet garbage bags; please choose organic garbage bags if available. $global"
            assertRejected(instruction, responseWithReceipt(instruction))
        }
    }

    @Test
    fun compoundNamesCannotHideAnOmittedNounEvenWhenItLooksLikePackaging() {
        listOf("protein bar" to "protein", "garbage bags" to "garbage", "Amul milk" to "milk").forEach { (name, target) ->
            val primary = "1 packet $name"
            val receipt = "please choose organic $target if available."
            val instruction = "one bread; $primary; $receipt"
            assertRejected(instruction, responseWithReceipt(instruction, blockedSource = primary, receiptText = receipt))
        }
        listOf("milk chocolate", "coconut water", "protein bar", "garbage bags").forEach { name ->
            val primary = "1 packet $name"
            val receipt = "please choose organic $name if available."
            val instruction = "one bread; $primary; $receipt"
            val draft = GroceryIntentReviewV2Response.parse(
                responseWithReceipt(instruction, blockedSource = primary, receiptText = receipt), instruction,
            ).draft
            assertEquals(1, draft.prepareForReview().items.size)
            assertTrue(draft.prepareForReview().excludedText.single().contains(receipt))
        }
    }

    private fun parseValid(instruction: String): GroceryIntentReviewV2 =
        GroceryIntentReviewV2Response.parse(responseWithReceipt(instruction), instruction).draft

    private fun responseWithReceipt(
        instruction: String,
        duplicateTarget: Boolean = false,
        missingPrimary: Boolean = false,
        receiptInPrimary: Boolean = false,
        receiptText: String = "please choose organic garbage bags if available.",
        blockedSource: String = "1 packet garbage bags",
    ): String {
        val ready = readyRow(instruction, "one bread")
        val firstBlocked = if (receiptInPrimary) {
            blockedRow(instruction, "$blockedSource; $receiptText", 1, receiptText)
        } else blockedRow(instruction, blockedSource, 1, receiptText)
        val blocked = mutableListOf(firstBlocked)
        if (duplicateTarget) blocked += blockedRow(instruction, "2 packets garbage bags", 2)
        if (missingPrimary) blocked += """{"row_index":${if (duplicateTarget) 3 else 2},"source":null,"issue_codes":["quote_unresolved"]}"""
        return response(instruction, listOf(ready), blocked)
    }

    private fun response(instruction: String, ready: List<String>, blocked: List<String>): String =
        """{"accepted":true,"draft":{"contract_version":"grocery-intent-v2","requires_review":true,"instruction":${json(instruction)},"source_item_count":${ready.size + blocked.size},"ready_rows":[${ready.joinToString(",")}],"blocked_rows":[${blocked.joinToString(",")}]},"model":"test","promptVersion":"v2-test","latencyMs":1,"usage":{}}"""

    private fun readyRow(instruction: String, source: String, support: String? = null): String {
        val start = instruction.indexOf(source).also { require(it >= 0) }
        val end = start + source.length
        val supportText = support?.let { "\"kind\":\"modifier\",\"text\":${json(it)}" } ?: ""
        val supportQuote = support?.let { quote(instruction, it) } ?: ""
        return """{"row_index":0,"item":{"name":"bread","quantity":1,"quantity_unit":"count","pack_value":null,"pack_unit":"unspecified","packaging":null,"required":[],"excluded":[],"source_text":${json(source)},"supporting_text":[${if (support == null) "" else "{$supportText}"}],"needs_clarification":false,"quantity_text":"one","pack_text":null},"evidence":{"primary":{"text":${json(source)},"start_utf16":$start,"end_utf16":$end},"supporting":[${if (support == null) "" else "{\"kind\":\"modifier\",\"quote\":$supportQuote}"}]},"quantity_origin":"stated","amount_role_status":"matched"}"""
    }

    private fun blockedRow(instruction: String, source: String, index: Int, receipt: String? = null): String {
        val start = instruction.indexOf(source).also { require(it >= 0) }
        val end = start + source.length
        val context = receipt?.let { ",\"excluded_context\":[${quote(instruction, it)}]" } ?: ""
        return """{"row_index":$index,"source":{"text":${json(source)},"start_utf16":$start,"end_utf16":$end},"issue_codes":["item_contains_correction_or_alternative"]$context}"""
    }

    private fun quote(instruction: String, text: String): String {
        val start = instruction.indexOf(text).also { require(it >= 0) }
        return """{"text":${json(text)},"start_utf16":$start,"end_utf16":${start + text.length}}"""
    }

    private fun assertRejected(instruction: String, body: String) {
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(body, instruction)
        }
    }

    private fun json(value: String): String = "\"${buildString {
        value.forEach { char ->
            append(when (char) {
                '\\' -> "\\\\"
                '"' -> "\\\""
                '\n' -> "\\n"
                '\r' -> "\\r"
                '\t' -> "\\t"
                else -> char
            })
        }
    }}\""
}
