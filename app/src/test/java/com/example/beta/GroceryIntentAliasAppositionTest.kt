package com.example.beta

import com.example.beta.automation.Quantity
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentAliasAppositionTest {
    @Test fun literalKnownAliasAfterItsOwnProductHeadPreservesIdentityAndCount() {
        val cases = listOf(
            Triple("one banana, jisko baalehannu bolte hain", "banana", 1),
            Triple("two ginger, also known as shunti", "ginger", 2),
            Triple("three fenugreek, also known as methi", "fenugreek", 3),
            Triple("four cardamom, also known as elaichi", "cardamom", 4),
            Triple("three elaichi, also known as cardamom", "cardamom", 3),
        )
        cases.forEach { (instruction, identity, count) ->
            val draft = review(instruction, listOf(
                readyRow(instruction, item(identity, instruction, count, countWord(count))),
            ))
            val prepared = draft.prepareForReview()
            assertEquals("Original instruction must remain verbatim", instruction, draft.instruction)
            assertEquals(1, prepared.items.size)
            val parsed = prepared.items.single()
            assertTrue(parsed.query.contains(identity, ignoreCase = true))
            assertEquals(Quantity.Count(count), parsed.quantity)
            assertEquals(instruction, parsed.intentQuantityProvenance?.primaryText)
            assertTrue(prepared.unresolvedText.isEmpty())
            assertTrue(prepared.excludedText.isEmpty())
        }
    }

    @Test fun unrelatedSemicolonNeighborRemainsIndependent() {
        val instruction = "one banana, jisko baalehannu bolte hain; two rice"
        val draft = review(instruction, listOf(
            readyRow(instruction, item("banana", "one banana, jisko baalehannu bolte hain", 1, "one"), 0),
            readyRow(instruction, item("rice", "two rice", 2, "two"), 1),
        ))
        val prepared = draft.prepareForReview()
        assertEquals(listOf(Quantity.Count(1), Quantity.Count(2)), prepared.items.map { it.quantity })
        assertTrue(prepared.items.first().query.contains("banana", ignoreCase = true))
        assertTrue(prepared.items.last().query.contains("rice", ignoreCase = true))
        assertTrue(prepared.unresolvedText.isEmpty())
    }

    @Test fun mismatchedUnknownOrNonproductAliasPhrasesStayHeld() {
        listOf(
            "one milk, jisko baalehannu bolte hain" to item("banana", "one milk, jisko baalehannu bolte hain", 1, "one"),
            "one banana, jisko unknownfruit bolte hain" to item("banana", "one banana, jisko unknownfruit bolte hain", 1, "one"),
            "one banana, called baalehannu" to item("banana", "one banana, called baalehannu", 1, "one"),
            "one banana elaichi, also known as cardamom" to item("banana elaichi", "one banana elaichi, also known as cardamom", 1, "one"),
            "one banana elaichi, jisko cardamom bolte hain" to item("banana elaichi", "one banana elaichi, jisko cardamom bolte hain", 1, "one"),
            "one banana, which is called baalehannu" to item("banana", "one banana, which is called baalehannu", 1, "one"),
            "banana, jisko baalehannu bolte hain" to item("banana", "banana, jisko baalehannu bolte hain", null, null),
            "this is banana, jisko baalehannu bolte hain" to item("banana", "this is banana, jisko baalehannu bolte hain", 1, "one"),
            "one banana, jisko baalehannu bolte hain, organic" to item("organic banana", "one banana, jisko baalehannu bolte hain, organic", 1, "one", required = listOf("organic")),
            "one banana, jisko baalehannu bolte hain, 2 kg" to item("banana", "one banana, jisko baalehannu bolte hain, 2 kg", 1, "one", packValue = BigDecimal("2"), packUnit = "kg"),
            "one banana, jisko baalehannu bolte hain if available" to item("banana", "one banana, jisko baalehannu bolte hain if available", 1, "one"),
            "one banana, jisko baalehannu bolte hain or three apples" to item("banana", "one banana, jisko baalehannu bolte hain or three apples", 1, "one"),
            "one banana, jisko baalehannu bolte hain 2-4" to item("banana", "one banana, jisko baalehannu bolte hain 2-4", 1, "one"),
        ).forEach { (instruction, candidate) -> assertHeld(instruction, candidate) }
    }

    @Test fun croppedAliasPrimaryCannotDropOriginalPropertyOrConditionTail() {
        val instruction = "one banana, jisko baalehannu bolte hain, organic if available"
        val cropped = "one banana, jisko baalehannu bolte hain"
        assertHeld(instruction, item("banana", cropped, 1, "one"))
    }

    @Test fun copyingAnUnprovedAliasRelationIntoNameDoesNotProveIt() {
        listOf("also known as cardamom", "jisko cardamom bolte hain").forEach { clause ->
            val source = "one banana elaichi, $clause"
            assertHeld(source, item("banana elaichi, $clause", source, 1, "one"))
        }
    }

    @Test fun unclaimedIndependentRequestStillRequiresWholeInstructionRecovery() {
        val source = "one banana, jisko baalehannu bolte hain"
        val instruction = "$source; then buy two rice"
        val prepared = review(instruction, listOf(readyRow(instruction, item("banana", source, 1, "one"))))
            .prepareForReview()
        assertEquals(listOf(Quantity.Count(1)), prepared.items.map { it.quantity })
        assertTrue(prepared.unresolvedText.isNotEmpty())
        assertEquals(instruction, prepared.recoverySnapshot?.toDisplay()?.originalInstruction)
    }

    @Test fun supportGroupPackAndCorrectionReceiptsDoNotQualifyAliasSourceProof() {
        val supportInstruction = "one banana, jisko baalehannu bolte hain; from last time"
        val supportItem = item("banana", "one banana, jisko baalehannu bolte hain", 1, "one",
            supporting = listOf("from last time"))
        val supportRow = readyRow(supportInstruction, supportItem, supporting = listOf("from last time"))
        assertHeld(supportInstruction, listOf(supportRow))

        val grouped = "for breakfast: one banana, jisko baalehannu bolte hain; two rice"
        val banana = item("banana", "one banana, jisko baalehannu bolte hain", 1, "one")
        val rice = item("rice", "two rice", 2, "two")
        val group = GroceryIntentReviewV2Group(
            status = "structurally_verified", amountStatus = "local_amounts_verified",
            source = quote(grouped, grouped), header = quote(grouped, "for breakfast:"),
            memberIndices = listOf(0, 1),
            members = listOf(
                GroceryIntentReviewV2GroupMember(0, BigDecimal.ONE, "count", "local"),
                GroceryIntentReviewV2GroupMember(1, BigDecimal("2"), "count", "local"),
            ),
            packaging = null, total = null, each = null,
        )
        assertHeld(grouped, listOf(readyRow(grouped, banana, 0), readyRow(grouped, rice, 1)), listOf(group))

        val packed = "one banana, jisko baalehannu bolte hain, 500 g"
        assertHeld(packed, item("banana", packed, 1, "one", packValue = BigDecimal("500"), packUnit = "g"))

        val corrected = "one banana, jisko baalehannu bolte hain; actually make it two"
        val correctedRow = readyRow(corrected,
            item("banana", "one banana, jisko baalehannu bolte hain", 1, "one"),
            corrections = listOf(GroceryIntentReviewV2Correction(
                kind = "quantity", source = quote(corrected, "actually make it two"), newAmount = quote(corrected, "two"),
                oldValue = BigDecimal.ONE, oldUnit = "count", newValue = BigDecimal("2"), newUnit = "count",
            )),
        )
        assertHeld(corrected, listOf(correctedRow))
    }

    private fun assertHeld(instruction: String, candidate: GroceryIntentReviewV2Item) =
        assertHeld(instruction, listOf(readyRow(instruction, candidate)))

    private fun assertHeld(
        instruction: String,
        rows: List<GroceryIntentReviewV2ReadyRow>,
        groups: List<GroceryIntentReviewV2Group> = emptyList(),
    ) {
        val result = review(instruction, rows, groups).prepareForReview()
        assertTrue("Alias proposal must remain non-actionable: $instruction", result.items.isEmpty())
        assertEquals(instruction, result.recoverySnapshot?.toDisplay()?.originalInstruction)
    }

    private fun review(
        instruction: String,
        rows: List<GroceryIntentReviewV2ReadyRow>,
        groups: List<GroceryIntentReviewV2Group> = emptyList(),
    ) = GroceryIntentReviewV2(
        contractVersion = "grocery-intent-v2", requiresReview = true, instruction = instruction,
        sourceItemCount = rows.size, readyRows = rows, blockedRows = emptyList(), groups = groups,
    )

    private fun item(
        name: String,
        source: String,
        quantity: Int?,
        quantityText: String?,
        required: List<String> = emptyList(),
        supporting: List<String> = emptyList(),
        packValue: BigDecimal? = null,
        packUnit: String = if (packValue == null) "unspecified" else "g",
    ) = GroceryIntentReviewV2Item(
        name = name,
        quantity = quantity?.let { BigDecimal.valueOf(it.toLong()) },
        quantityUnit = "count",
        packValue = packValue,
        packUnit = packUnit,
        packaging = null,
        required = required,
        excluded = emptyList(),
        sourceText = source,
        supportingText = supporting.map { GroceryIntentReviewV2SupportText("modifier", it) },
        needsClarification = false,
        quantityText = quantityText,
        packText = packValue?.toPlainString(),
    )

    private fun readyRow(
        instruction: String,
        item: GroceryIntentReviewV2Item,
        index: Int = 0,
        supporting: List<String> = emptyList(),
        corrections: List<GroceryIntentReviewV2Correction> = emptyList(),
    ) = GroceryIntentReviewV2ReadyRow(
        rowIndex = index,
        item = item,
        evidence = GroceryIntentReviewV2Evidence(
            primary = quote(instruction, item.sourceText),
            supporting = supporting.map { GroceryIntentReviewV2SupportQuote("modifier", quote(instruction, it)) },
        ),
        quantityOrigin = if (item.quantity == null) "not_stated" else "stated",
        amountRoleStatus = if (item.quantity == null && item.packValue == null) "not_stated" else "matched",
        corrections = corrections,
    )

    private fun quote(instruction: String, text: String): GroceryIntentReviewV2Quote {
        val start = instruction.indexOf(text)
        require(start >= 0)
        return GroceryIntentReviewV2Quote(text, start, start + text.length)
    }

    private fun countWord(value: Int) = when (value) {
        1 -> "one"
        2 -> "two"
        3 -> "three"
        else -> "four"
    }
}
