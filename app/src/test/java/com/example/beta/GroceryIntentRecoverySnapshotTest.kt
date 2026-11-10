package com.example.beta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentRecoverySnapshotTest {
    @Test fun genericFallbackQuotesExactWholeInstructionAndOmitsUnprovenRows() {
        val instruction = "one BrandA milk, the plain one, no chemicals"

        val display = GroceryIntentRecoverySnapshot.generic(instruction).toDisplay()

        assertEquals(instruction, display.originalInstruction)
        assertEquals("Beta couldn't be sure about your list, so nothing has been added. Change the words below, then check again.", display.body)
        assertEquals(1, display.heldRows.size)
        assertEquals("You said: “$instruction”", display.heldRows.single().title)
        assertEquals("Beta couldn't confirm what to buy from these words.", display.heldRows.single().detail)
        assertTrue(display.understoodRows.isEmpty())
        assertTrue(!display.showJump)
    }

    @Test fun serverIssueCodesAreNotRenderedAsRecoveryReasons() {
        val display = GroceryIntentRecoverySnapshot.generic("two rice").toDisplay()

        assertTrue(display.heldRows.single().detail.contains("couldn't confirm what to buy"))
        assertTrue(display.heldRows.none { it.detail.contains("model_needs_review") })
        assertTrue(display.heldRows.none { it.detail.contains("quote_unresolved") })
    }

    @Test fun preparationDoesNotInferAHoldFromExcludedTextAlone() {
        val preparation = GroceryIntentPreparation(
            items = emptyList(),
            unresolvedText = emptyList(),
            excludedText = listOf("no sugar"),
        )

        assertNull(preparation.recoverySnapshot)
    }

    @Test fun fullyValidatedReadyPreparationHasNoRecoverySnapshot() {
        val instruction = "two milk"
        val preparation = parse(instruction, listOf(readyRow(instruction, 0, "two milk", "milk", 2, "two")))
            .draft.prepareForReview()

        assertEquals(1, preparation.items.size)
        assertEquals(2, (preparation.items.single().quantity as com.example.beta.automation.Quantity.Count).n)
        assertNull(preparation.recoverySnapshot)
    }

    @Test fun validatedBlockedNullSourceAndPartialPreparationsGetGenericFallback() {
        val blocked = "maybe rice"
        assertGenericSnapshot(blocked,
            parse(blocked, emptyList(), listOf(blockedRow(blocked, 0, blocked))).draft.prepareForReview().recoverySnapshot)

        val nullSourceInstruction = "one rice, wait"
        assertGenericSnapshot(nullSourceInstruction,
            parse(nullSourceInstruction, emptyList(), listOf(blockedRow(nullSourceInstruction, 0, null)))
                .draft.prepareForReview().recoverySnapshot)

        val partial = "two milk, maybe rice"
        val partialPreparation = parse(partial,
            listOf(readyRow(partial, 0, "two milk", "milk", 2, "two")),
            listOf(blockedRow(partial, 1, "maybe rice"))).draft.prepareForReview()
        assertGenericSnapshot(partial, partialPreparation.recoverySnapshot)
    }

    @Test fun globalContentsScopeHoldUsesWholeInstructionAndOmitsUnprovenQuantities() {
        val instruction = "one hamper with no added sugar, two tea bags, three biscuits"
        val preparation = parse(instruction, listOf(
            readyRow(instruction, 0, "one hamper with no added sugar", "hamper", 1, "one", "no_added_sugar"),
            readyRow(instruction, 1, "two tea bags", "tea bags", 2, "two"),
            readyRow(instruction, 2, "three biscuits", "biscuits", 3, "three"),
        )).draft.prepareForReview()

        assertTrue(preparation.items.isEmpty())
        assertGenericSnapshot(instruction, preparation.recoverySnapshot)
    }

    @Test fun harmlessExactOpeningGetsDiagnosticOnlyForACompleteFifteenRowList() {
        data class Opening(val prefix: String, val span: String)
        val openings = listOf(
            Opening("Asha here, ", "Asha here,"),
            Opening("I am Asha, ", "I am Asha,"),
            Opening("This is Asha. ", "This is Asha."),
            Opening("My name is Asha. ", "My name is Asha."),
            Opening("Asha here\n", "Asha here\n"),
        )
        openings.forEach { opening ->
            val sources = listOf("milk", "rice", "eggs", "bread", "tea", "coffee", "oats", "flour",
                "apples", "carrots", "dal", "pasta", "biscuits", "chocolate", "cabbage")
                .map { "one $it" }
            val instruction = opening.prefix + sources.joinToString(", ")
            val response = parse(instruction, sources.mapIndexed { index, source ->
                readyRow(instruction, index, source, source.removePrefix("one "), 1, "one")
            })
            assertNull(response.inputRepair)
            val prepared = response.draft.prepareForReview()
            val baselineInstruction = sources.joinToString(", ")
            val baselineResponse = parse(baselineInstruction, sources.mapIndexed { index, source ->
                readyRow(baselineInstruction, index, source, source.removePrefix("one "), 1, "one")
            })
            val baseline = baselineResponse.draft.prepareForReview()
            assertEquals(15, prepared.items.size)
            assertTrue(prepared.items.all { it.quantity == com.example.beta.automation.Quantity.Count(1) })
            assertEquals(baseline.items.map { Triple(it.rawText, it.query, it.quantity) },
                prepared.items.map { Triple(it.rawText, it.query, it.quantity) })
            val primarySpans = response.draft.readyRows.map {
                GroceryIntentCoverageSpan(it.evidence.primary.startUtf16, it.evidence.primary.endUtf16)
            }
            assertEquals(uncoveredGroceryIntentSegmentsBySpan(instruction, primarySpans), prepared.unresolvedText)
            assertTrue(prepared.unresolvedText.isNotEmpty())
            assertTrue(prepared.excludedText.isEmpty())

            val display = requireNotNull(prepared.recoverySnapshot).toDisplay()
            assertEquals(instruction, display.originalInstruction)
            assertEquals(instruction, display.editorText)
            assertEquals("Beta couldn't be sure about your list, so nothing has been added. Change the words below, then check again.", display.body)
            assertEquals("You said: “$instruction”", display.heldRows.single().title)
            assertEquals("Beta isn't sure if “${opening.span}” is part of your shopping list.", display.heldRows.single().detail)
            val spokenOpening = opening.span.trimEnd(' ', '\t', '.', ',', '\r', '\n')
            assertEquals("Not confirmed. You said: $spokenOpening. Beta isn't sure if this is part of your shopping list.",
                display.heldRows.single().spoken)
            assertTrue(display.understoodRows.isEmpty())
            assertTrue(!display.showJump)
        }
    }

    @Test fun meaningfulHealthAndBrandWordsRemainUnresolvedAndInOriginalInstruction() {
        val meaningfulCases = listOf(
            "I am diabetic, one milk, two rice" to listOf("one milk", "two rice"),
            "BrandA here, one milk, two rice" to listOf("one milk", "two rice"),
        )
        meaningfulCases.forEach { (instruction, sources) ->
            val response = parse(instruction, sources.mapIndexed { index, source ->
                val quantityText = source.substringBefore(' ')
                val quantity = if (quantityText.equals("two", ignoreCase = true)) 2 else 1
                readyRow(instruction, index, source, source.substringAfter(' '), quantity, quantityText)
            })
            assertNull(response.inputRepair)
            val prepared = response.draft.prepareForReview()
            val expectedMeaningfulWord = if (instruction.contains("diabetic")) "diabetic" else "BrandA"
            assertTrue(prepared.unresolvedText.any { it.contains(expectedMeaningfulWord, ignoreCase = true) })
            assertEquals(sources.size, prepared.items.size)
            val display = requireNotNull(prepared.recoverySnapshot).toDisplay()
            assertEquals(instruction, display.originalInstruction)
            assertEquals(instruction, display.editorText)
            assertEquals("You said: “$instruction”", display.heldRows.single().title)
            assertTrue(display.understoodRows.isEmpty())
            assertTrue(!display.showJump)
        }
    }

    @Test fun repeatedOpeningUnrelatedSuffixAndBlockedRowsRemainGeneric() {
        val heldCases = listOf(
            "Asha here, one milk, Asha here" to listOf("one milk"),
            "Asha here, one milk, please check this too" to listOf("one milk"),
        )
        heldCases.forEach { (instruction, sources) ->
            val response = parse(instruction, sources.mapIndexed { index, source ->
                val quantityText = source.substringBefore(' ')
                val quantity = quantityText.toIntOrNull() ?: when (quantityText.lowercase()) {
                    "two" -> 2
                    else -> 1
                }
                readyRow(instruction, index, source, source.substringAfter(' '), quantity, quantityText)
            })
            assertNull(response.inputRepair)
            val prepared = response.draft.prepareForReview()
            assertTrue("$instruction", prepared.unresolvedText.isNotEmpty())
            val display = requireNotNull(prepared.recoverySnapshot).toDisplay()
            assertEquals(instruction, display.originalInstruction)
            assertEquals(instruction, display.editorText)
            assertEquals("You said: “$instruction”", display.heldRows.single().title)
            assertEquals("Beta couldn't confirm what to buy from these words.", display.heldRows.single().detail)
            assertTrue(display.understoodRows.isEmpty())
            assertTrue(!display.showJump)
        }

        val blockedInstruction = "My name is Asha, 40. one milk, maybe rice"
        val blockedResponse = parse(blockedInstruction,
            listOf(readyRow(blockedInstruction, 0, "one milk", "milk", 1, "one")),
            listOf(blockedRow(blockedInstruction, 1, "maybe rice")))
        assertNull(blockedResponse.inputRepair)
        val blockedPrepared = blockedResponse.draft.prepareForReview()
        assertGenericSnapshot(blockedInstruction, blockedPrepared.recoverySnapshot)
    }

    @Test fun unicodeOpeningPreservesOriginalTextExactly() {
        val opening = "My name is José."
        val instruction = "$opening one milk, two rice"
        val prepared = parse(instruction, listOf(
            readyRow(instruction, 0, "one milk", "milk", 1, "one"),
            readyRow(instruction, 1, "two rice", "rice", 2, "two"),
        )).draft.prepareForReview()

        assertEquals(2, prepared.items.size)
        assertTrue(prepared.unresolvedText.isNotEmpty())
        val display = requireNotNull(prepared.recoverySnapshot).toDisplay()
        assertEquals(instruction, display.originalInstruction)
        assertEquals(instruction, display.editorText)
        assertEquals("Beta isn't sure if “$opening” is part of your shopping list.", display.heldRows.single().detail)
    }

    private fun assertGenericSnapshot(instruction: String, snapshot: GroceryIntentRecoverySnapshot?) {
        assertNotNull(snapshot)
        val display = requireNotNull(snapshot).toDisplay()
        assertEquals(instruction, display.originalInstruction)
        assertEquals("You said: “$instruction”", display.heldRows.single().title)
        assertEquals("Beta couldn't confirm what to buy from these words.", display.heldRows.single().detail)
        assertTrue(display.understoodRows.isEmpty())
        assertTrue(display.heldRows.none { it.detail.contains("model_needs_review") })
        assertTrue(display.heldRows.none { Regex("\\b[0-9]+\\b").containsMatchIn(it.detail) })
    }

    private fun parse(instruction: String, ready: List<String>, blocked: List<String> = emptyList()): GroceryIntentReviewV2Response {
        val body = """{"accepted":true,"draft":{"contract_version":"grocery-intent-v2","requires_review":true,"instruction":${json(instruction)},"source_item_count":${ready.size + blocked.size},"ready_rows":[${ready.joinToString(",")}],"blocked_rows":[${blocked.joinToString(",")}]},"model":"test","promptVersion":"test","latencyMs":0,"usage":{}}"""
        return GroceryIntentReviewV2Response.parse(body, instruction)
    }

    private fun readyRow(
        instruction: String, index: Int, source: String, name: String, quantity: Int, quantityText: String,
        required: String? = null,
    ): String {
        val start = instruction.indexOf(source)
        require(start >= 0)
        val tags = required?.let { "[${json(it)}]" } ?: "[]"
        return """{"row_index":$index,"item":{"name":${json(name)},"quantity":$quantity,"quantity_unit":"count","pack_value":null,"pack_unit":"unspecified","packaging":null,"required":$tags,"excluded":[],"source_text":${json(source)},"supporting_text":[],"needs_clarification":false,"quantity_text":${json(quantityText)},"pack_text":null},"evidence":{"primary":{"text":${json(source)},"start_utf16":$start,"end_utf16":${start + source.length}},"supporting":[]},"quantity_origin":"stated","amount_role_status":"matched"}"""
    }

    private fun blockedRow(instruction: String, index: Int, source: String?): String {
        val quote = source?.let {
            val start = instruction.indexOf(it)
            require(start >= 0)
            """{"text":${json(it)},"start_utf16":$start,"end_utf16":${start + it.length}}"""
        } ?: "null"
        return """{"row_index":$index,"source":$quote,"issue_codes":["model_needs_review"]}"""
    }

    private fun json(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
}
