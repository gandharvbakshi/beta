package com.example.beta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentInputRepairTest {
    @Test
    fun validProposalIsOnlyAnInputEditAndPreservesUneditedSourceExactly() {
        val instruction = "2 milk, actually make it 3.  Keep this suffix unchanged."
        val response = parse(instruction)
        val repair = response.inputRepair ?: error("Expected a validated input-repair proposal")

        assertEquals("actually make it 3.", repair.source.text)
        assertEquals("3", repair.newAmount.text)
        assertEquals(listOf(0), repair.options.map { it.rowIndex })
        assertEquals("milk", repair.options.single().target.text)
        assertEquals("2", repair.options.single().oldAmount.text)
        assertEquals("2 milk, make milk 3.  Keep this suffix unchanged.", repair.editedInstruction(0))
        assertThrows(IllegalStateException::class.java) { repair.editedInstruction(1) }

        val prepared = response.draft.prepareForReview()
        assertEquals(0, prepared.items.size)
        assertTrue(prepared.unresolvedText.isNotEmpty() || prepared.excludedText.isNotEmpty())
    }

    @Test
    fun sameCountAndChangedCountAreBothPermittedAsInputSuggestions() {
        assertEquals("2", parse("2 milk, actually make it 2.", newAmount = "2").inputRepair?.newAmount?.text)
        assertEquals("3", parse("2 milk, actually make it 3.").inputRepair?.newAmount?.text)
        assertEquals(
            "two",
            parse("2 milk, no sorry, yes two.", newAmount = "two", sourceText = "no sorry, yes two.").inputRepair?.newAmount?.text,
        )
    }

    @Test
    fun shortCorrectionCuesDoNotLoseTheirAmountSeparator() {
        listOf("no", "sorry", "actually", "no,", "NO").forEach { cue ->
            val instruction = "2 milk, $cue two."
            val repair = parse(instruction, newAmount = "two", sourceText = "$cue two.").inputRepair!!
            assertEquals("2 milk, make milk two.", repair.editedInstruction(0))
        }
    }

    @Test
    fun commaAfterAffirmationMatchesBackendAndEditPreservesSuffix() {
        val instruction = "2 milk, no sorry, yes, two!!"
        val repair = parse(instruction, newAmount = "two", sourceText = "no sorry, yes, two!!").inputRepair!!
        assertEquals("2 milk, make milk two!!", repair.editedInstruction(0))
    }

    @Test
    fun incompleteChoiceListsAreDiscardedButOriginalDraftStaysBlocked() {
        for ((instruction, extraSources) in listOf(
            "2 milk, eggs, no sorry, yes two" to listOf("eggs"),
            "2 milk, rice 500 g, no sorry, yes two" to listOf("rice 500 g"),
            "2 milk, bread, no sorry, yes two" to emptyList(),
            "bread, 2 milk, no sorry, yes two" to emptyList(),
        )) {
            val suggestion = repairJson(instruction, newAmount = "two", sourceText = "no sorry, yes two")
            val body = responseJson(instruction, suggestion, extraSources = extraSources, primaryText = "2 milk")
            val response = GroceryIntentReviewV2Response.parse(body, instruction)
            assertNull(response.inputRepair)
            assertTrue(response.inputRepairRejected)
            assertTrue(response.draft.readyRows.isEmpty())
            assertTrue(response.draft.prepareForReview().items.isEmpty())
        }
    }

    @Test
    fun allEnglishCountSpellingsOneThroughTwentyBindAsExactAmounts() {
        val counts = listOf(
            "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty",
        )
        counts.forEach { amount ->
            val instruction = "$amount milk, actually make it $amount."
            val repair = parse(
                instruction, primary = "$amount milk", target = "milk", newAmount = amount,
                oldAmount = amount, sourceText = "actually make it $amount.",
            ).inputRepair
            assertEquals(amount, repair?.options?.single()?.oldAmount?.text)
            assertEquals(amount, repair?.newAmount?.text)
        }
    }

    @Test
    fun sourceLiteralPackagingCanBePreservedForFullRevalidation() {
        val instruction = "2 milk packets, actually make it 3."
        val repair = parse(instruction, primary = "2 milk packets", target = "milk packets").inputRepair!!
        assertEquals("2 milk packets, make milk packets 3.", repair.editedInstruction(0))
    }

    @Test
    fun syntheticMultiOptionEnvelopeOffersExactEditsWithoutChangingTheDraft() {
        val instruction = "one rice, two milk, no sorry, yes two"
        val response = GroceryIntentReviewV2Response.parse(twoOptionResponse(instruction), instruction)
        val repair = response.inputRepair ?: error("Expected input-repair proposal")
        assertEquals(listOf(0, 1), repair.options.map { it.rowIndex })
        assertEquals("one rice, two milk, make rice two", repair.editedInstruction(0))
        assertEquals("one rice, two milk, make milk two", repair.editedInstruction(1))
        val preparation = response.draft.prepareForReview().copy(inputRepair = repair)
        assertEquals(0, preparation.items.size)
        assertTrue(preparation.unresolvedText.isNotEmpty() || preparation.excludedText.isNotEmpty())
    }

    @Test
    fun inputRepairIsOnlyIssueWhenEveryBlockedRowAndMeaningfulWordIsExplained() {
        val soleInstruction = "2 milk, actually make it 3."
        val sole = parse(soleInstruction)
        assertTrue(sole.inputRepair!!.isOnlyIssueIn(sole.draft))

        val extraBlockedInstruction = "2 milk, actually make it 3., eggs"
        val extraBlockedBody = responseJson(
            extraBlockedInstruction,
            repairJson(extraBlockedInstruction),
            extraSources = listOf("eggs"),
        )
        val extraBlocked = GroceryIntentReviewV2Response.parse(extraBlockedBody, extraBlockedInstruction)
        assertTrue(extraBlocked.inputRepair != null)
        assertTrue(!extraBlocked.inputRepair!!.isOnlyIssueIn(extraBlocked.draft))

        val extraSuffixInstruction = "2 milk, actually make it 3., buy bread"
        val extraSuffix = parse(extraSuffixInstruction)
        assertTrue(extraSuffix.inputRepair != null)
        assertTrue(!extraSuffix.inputRepair!!.isOnlyIssueIn(extraSuffix.draft))

        val neutralInstruction = "Hello! 2 milk, actually make it 3.!"
        val neutralBody = responseJson(
            neutralInstruction,
            repairJson(neutralInstruction),
            primaryText = "2 milk",
        )
        val neutral = GroceryIntentReviewV2Response.parse(neutralBody, neutralInstruction)
        assertTrue(neutral.inputRepair != null)
        assertTrue(neutral.inputRepair!!.isOnlyIssueIn(neutral.draft))
    }

    @Test
    fun targetMustUniquelyAnchorOneBlockedPrimary() {
        val instruction = "one milk, one milk, no sorry, yes two"
        val first = quote(instruction, "one milk")
        val second = quote(instruction, "one milk", first.endIndex)
        val source = quote(instruction, "no sorry, yes two")
        val amount = quote(instruction, "two", source.startIndex)
        val old = quote(instruction, "one")
        val target = quote(instruction, "milk", old.endIndex)
        val option = "{\"row_index\":0,\"primary\":${first.json},\"old_amount\":${old.json},\"target\":${target.json}}"
        val repair = "{\"kind\":\"quantity_count_target\",\"source\":${source.json},\"new_amount\":${amount.json},\"options\":[$option]}"
        val draft = "{\"contract_version\":\"grocery-intent-v2\",\"requires_review\":true," +
            "\"instruction\":${json(instruction)},\"source_item_count\":2,\"ready_rows\":[],\"blocked_rows\":[" +
            "{\"row_index\":0,\"source\":${first.json},\"issue_codes\":[\"quantity_omission_suspected\"]}," +
            "{\"row_index\":1,\"source\":${second.json},\"issue_codes\":[\"quantity_omission_suspected\"]}]}"
        val body = "{\"accepted\":true,\"draft\":$draft,\"input_repair\":$repair," +
            "\"model\":\"test\",\"promptVersion\":\"test\",\"latencyMs\":0,\"usage\":{}}"
        assertNull(GroceryIntentReviewV2Response.parse(body, instruction).inputRepair)
    }

    @Test
    fun strictRootAndOptionShapesRejectUnknownNullDuplicateAndNonActionableDrafts() {
        val instruction = "2 milk, actually make it 3."
        val valid = responseJson(instruction)
        assertNull(GroceryIntentReviewV2Response.parse(valid, instruction).inputRepair)
        assertThrows(IllegalArgumentException::class.java) {
            GroceryIntentReviewV2Response.parse(valid.replace("\"usage\":{}", "\"usage\":{},\"extra\":true"), instruction)
        }
        val optionalNull = GroceryIntentReviewV2Response.parse(
            valid.replace("\"usage\":{}", "\"usage\":{},\"input_repair\":null"), instruction,
        )
        assertNull(optionalNull.inputRepair)
        assertTrue(optionalNull.inputRepairRejected)
        assertEquals(GroceryIntentReviewV2Response.parse(valid, instruction).draft, optionalNull.draft)
        assertRejected(instruction, repairJson(instruction, kind = "other"))
        assertRejected(instruction, repairJson(instruction, duplicateOptions = true))
        assertRejected(instruction, repairJson(instruction, rowIndex = 1))
        assertRejected(instruction, repairJson(instruction).replace("{\"kind\":", "{\"extra\":true,\"kind\":"))
        assertRejected(instruction, repairJson(instruction).replace("{\"row_index\":0", "{\"row_index\":0,\"extra\":true"))
        assertRejected(instruction, repairJson(instruction).replace("\"new_amount\":{\"text\":\"3\",\"start_utf16\":25,\"end_utf16\":26}",
            "\"new_amount\":{\"text\":\"2\",\"start_utf16\":0,\"end_utf16\":1}"))
        val noBlockedRows = responseJson(instruction, repairJson(instruction), blockedRow = false)
        assertNull(GroceryIntentReviewV2Response.parse(noBlockedRows, instruction).inputRepair)
    }

    @Test
    fun quoteBindingOverlapHardSentenceAndOldAmountPositionAreEnforced() {
        val instruction = "2 milk, actually make it 3."
        assertRejected(instruction, repairJson(instruction, sourceText = "2 milk"))
        assertRejected("2 milk; actually make it 3.", repairJson("2 milk; actually make it 3."))
        assertRejected(instruction, repairJson(instruction, primaryText = "milk"))
        assertRejected("2 milk, actually make it 21.", repairJson("2 milk, actually make it 21.", newAmount = "21"))
        assertRejected("2 milk, actually make it 3.0.", repairJson("2 milk, actually make it 3.0.", newAmount = "3.0"))
        assertRejected("2 milk, actually make it 3 bottles.", repairJson(
            "2 milk, actually make it 3 bottles.", sourceText = "actually make it 3 bottles.",
        ))
        assertRejected("2 milk, actually\nmake it 3.", repairJson(
            "2 milk, actually\nmake it 3.", sourceText = "actually\nmake it 3.",
        ))
        assertRejected("2 milk, actually\u00a0make it 3.", repairJson(
            "2 milk, actually\u00a0make it 3.", sourceText = "actually\u00a0make it 3.",
        ))
    }

    @Test
    fun correctionCuesAndTargetSemanticsAreClosedAndAsciiBounded() {
        val instruction = "2 milk, actually make it 3."
        assertRejected("2 milk, choose 3.", repairJson("2 milk, choose 3.", sourceText = "choose 3."))
        assertRejected("2 two milk, actually make it 3.", repairJson(
            "2 two milk, actually make it 3.", primaryText = "2 two milk", target = "two milk",
        ))
        assertRejected("2 milk per serving, actually make it 3.", repairJson(
            "2 milk per serving, actually make it 3.", primaryText = "2 milk per serving", target = "milk per serving",
        ))
        assertRejected("2 milk g, actually make it 3.", repairJson(
            "2 milk g, actually make it 3.", primaryText = "2 milk g", target = "milk g",
        ))
        assertRejected("2 milk instead, actually make it 3.", repairJson(
            "2 milk instead, actually make it 3.", primaryText = "2 milk instead", target = "milk instead",
        ))
        assertRejected("2 mïlk, actually make it 3.", repairJson(
            "2 mïlk, actually make it 3.", primaryText = "2 mïlk", target = "mïlk",
        ))
    }

    @Test
    fun changedInstructionAndSourceRangeTamperingAreRejected() {
        val instruction = "2 milk, actually make it 3."
        val body = responseJson(instruction, repairJson(instruction))
        assertThrows(RuntimeException::class.java) {
            GroceryIntentReviewV2Response.parse(body, "2 milk, actually make 4.")
        }
        val tampered = body.replace("\"end_utf16\":6", "\"end_utf16\":5")
        assertThrows(RuntimeException::class.java) { GroceryIntentReviewV2Response.parse(tampered, instruction) }
    }

    private fun parse(
        instruction: String,
        primary: String = "2 milk",
        target: String = "milk",
        newAmount: String = "3",
        oldAmount: String = "2",
        sourceText: String = "actually make it $newAmount.",
    ): GroceryIntentReviewV2Response = GroceryIntentReviewV2Response.parse(
        responseJson(instruction, repairJson(instruction, primaryText = primary, target = target, newAmount = newAmount, oldAmount = oldAmount, sourceText = sourceText)),
        instruction,
    )

    private fun assertRejected(instruction: String, repair: String) {
        val response = GroceryIntentReviewV2Response.parse(responseJson(instruction, repair), instruction)
        assertNull(response.inputRepair)
        assertTrue(response.inputRepairRejected)
    }

    private fun responseJson(instruction: String, repair: String? = null, blockedRow: Boolean = true,
                             extraSources: List<String> = emptyList(), primaryText: String? = null): String {
        val primary = primaryText ?: instruction.substringBefore(',').substringBefore(';').trimEnd()
        val sources = listOf(primary) + extraSources
        val blockedRows = sources.mapIndexed { index, text ->
            "{\"row_index\":$index,\"source\":${quote(instruction, text).json},\"issue_codes\":[\"quantity_omission_suspected\"]}"
        }.joinToString(",")
        val rows = if (blockedRow) "\"source_item_count\":${sources.size},\"ready_rows\":[],\"blocked_rows\":[$blockedRows]"
        else "\"source_item_count\":0,\"ready_rows\":[],\"blocked_rows\":[]"
        val fields = "\"accepted\":true,\"draft\":{\"contract_version\":\"grocery-intent-v2\",\"requires_review\":true," +
            "\"instruction\":${json(instruction)},$rows}," +
            "\"model\":\"test\",\"promptVersion\":\"test\",\"latencyMs\":1,\"usage\":{}"
        return "{" + fields + (repair?.let { ",\"input_repair\":$it" } ?: "") + "}"
    }

    private fun twoOptionResponse(instruction: String): String {
        val rice = quote(instruction, "one rice")
        val milk = quote(instruction, "two milk")
        val source = quote(instruction, "no sorry, yes two")
        val amount = quote(instruction, "two", source.startIndex)
        fun option(index: Int, primary: BoundQuote, old: String, target: String): String {
            val oldQuote = quote(instruction, old, primary.startIndex)
            val targetQuote = quote(instruction, target, oldQuote.endIndex)
            return "{\"row_index\":$index,\"primary\":${primary.json},\"old_amount\":${oldQuote.json},\"target\":${targetQuote.json}}"
        }
        val repair = "{\"kind\":\"quantity_count_target\",\"source\":${source.json},\"new_amount\":${amount.json}," +
            "\"options\":[${option(0, rice, "one", "rice")},${option(1, milk, "two", "milk")}] }"
        val draft = "{\"contract_version\":\"grocery-intent-v2\",\"requires_review\":true," +
            "\"instruction\":${json(instruction)},\"source_item_count\":2,\"ready_rows\":[],\"blocked_rows\":[" +
            "{\"row_index\":0,\"source\":${rice.json},\"issue_codes\":[\"unclaimed_correction_requires_review\"]}," +
            "{\"row_index\":1,\"source\":${milk.json},\"issue_codes\":[\"unclaimed_correction_requires_review\"]}]}"
        return "{\"accepted\":true,\"draft\":$draft,\"input_repair\":$repair," +
            "\"model\":\"synthetic\",\"promptVersion\":\"synthetic\",\"latencyMs\":0,\"usage\":{}}"
    }

    private fun repairJson(
        instruction: String,
        kind: String = "quantity_count_target",
        newAmount: String = "3",
        sourceText: String = "actually make it $newAmount.",
        oldAmount: String = "2",
        primaryText: String = "2 milk",
        target: String = "milk",
        rowIndex: Int = 0,
        duplicateOptions: Boolean = false,
    ): String {
        val primary = quote(instruction, primaryText)
        val old = quote(instruction, oldAmount, instruction.indexOf(oldAmount))
        val targetQuote = quote(instruction, target, old.endIndex)
        val source = quote(instruction, sourceText)
        val amount = quote(instruction, newAmount, source.startIndex)
        val option = "{\"row_index\":$rowIndex,\"primary\":${primary.json},\"old_amount\":${old.json},\"target\":${targetQuote.json}}"
        val options = if (duplicateOptions) "[$option,$option]" else "[$option]"
        return "{\"kind\":${json(kind)},\"source\":${source.json},\"new_amount\":${amount.json},\"options\":$options}"
    }

    private data class BoundQuote(val json: String, val startIndex: Int, val endIndex: Int)

    private fun quote(instruction: String, text: String, from: Int = 0): BoundQuote {
        val start = instruction.indexOf(text, from)
        require(start >= 0)
        return quoteRange(instruction, start, start + text.length)
    }

    private fun quoteRange(instruction: String, start: Int, end: Int): BoundQuote {
        val text = instruction.substring(start, end)
        return BoundQuote(
            "{\"text\":${json(text)},\"start_utf16\":$start,\"end_utf16\":$end}",
            start,
            end,
        )
    }

    private fun json(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
}
