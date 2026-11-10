package com.example.beta

import java.math.BigDecimal
import com.example.beta.automation.Quantity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for property claims that the original wording does not entail. */
class GroceryIntentPropertyAssertionsTest {
    @Test fun unsupportedDietaryAssertionsAreHeldByClientPreparation() {
        val cases = listOf(
            "one biscuit with no sugar added" to listOf("sugar_free"),
            "one biscuit with no sugar added" to listOf("no_added_sugar"),
            "one biscuit with no sugar has been added" to listOf("no_added_sugar"),
            "one probably organic milk" to listOf("organic"),
            "one maybe organic milk" to listOf("organic"),
            "one unsweetened sweetened drink" to listOf("unsweetened", "sweetened"),
            "one un-sweetened sweetened drink" to listOf("unsweetened", "sweetened"),
            "one non-sweetened sweetened drink" to listOf("unsweetened", "sweetened"),
            "one snack with no salt added" to listOf("unsalted"),
            "one low fat full cream milk" to listOf("low_fat"),
            "one low fat full cream milk" to emptyList(),
        )

        cases.forEach { (instruction, required) ->
            val result = propertyAssertionDraft(instruction, required).prepareForReview()

            assertTrue("Unsupported assertion was made actionable: $instruction", result.items.isEmpty())
            assertTrue(
                "Original wording must remain reviewable: $instruction",
                result.unresolvedText.isNotEmpty() || result.excludedText.isNotEmpty(),
            )
        }
    }

    @Test fun literalUnqualifiedPropertiesAndExactNegativesRemainSupported() {
        val cases = listOf(
            "one organic milk" to listOf("organic"),
            "one biscuit with no added sugar" to listOf("no_added_sugar"),
            "one drink with zero sugar" to listOf("sugar_free"),
            "one non-sweetened drink" to listOf("unsweetened"),
            "one unsalted snack" to listOf("unsalted"),
        )

        cases.forEach { (instruction, required) ->
            val result = propertyAssertionDraft(instruction, required).prepareForReview()

            assertEquals("$instruction should retain its explicitly supported claim", 1, result.items.size)
            assertEquals("$instruction must preserve the source quantity", Quantity.Count(1), result.items.single().quantity)
            assertTrue("$instruction left no supported property unresolved", result.unresolvedText.isEmpty())
            assertTrue("$instruction was unexpectedly excluded", result.excludedText.isEmpty())
        }
    }

    @Test fun aSeparatelyOwnedMaybeModifierStillBlocksOrganic() {
        val instruction = "one organic milk; milk probably"
        val primaryText = "one organic milk"
        val modifier = "milk probably"
        val item = GroceryIntentReviewV2Item(
            name = "organic milk probably",
            quantity = BigDecimal.ONE,
            quantityUnit = "count",
            packValue = null,
            packUnit = "unspecified",
            packaging = null,
            required = listOf("organic"),
            excluded = emptyList(),
            sourceText = primaryText,
            supportingText = listOf(GroceryIntentReviewV2SupportText("modifier", modifier)),
            needsClarification = false,
            quantityText = "one",
            packText = null,
        )
        val primaryStart = instruction.indexOf(primaryText)
        val modifierStart = instruction.indexOf(modifier)
        val row = GroceryIntentReviewV2ReadyRow(
            rowIndex = 0,
            item = item,
            evidence = GroceryIntentReviewV2Evidence(
                primary = GroceryIntentReviewV2Quote(primaryText, primaryStart, primaryStart + primaryText.length),
                supporting = listOf(GroceryIntentReviewV2SupportQuote(
                    "modifier", GroceryIntentReviewV2Quote(modifier, modifierStart, modifierStart + modifier.length),
                )),
            ),
            quantityOrigin = "stated",
            amountRoleStatus = "matched",
        )
        val prepared = propertyAssertionDraft(instruction, emptyList()).copy(readyRows = listOf(row))
            .prepareForReview()

        assertTrue(prepared.items.isEmpty())
        assertTrue(prepared.unresolvedText.isNotEmpty() || prepared.excludedText.isNotEmpty())
    }
}

private fun propertyAssertionDraft(
    instruction: String,
    required: List<String>,
): GroceryIntentReviewV2 {
    val item = GroceryIntentReviewV2Item(
        name = instruction.substringAfter(' ').trim(),
        quantity = BigDecimal.ONE,
        quantityUnit = "count",
        packValue = null,
        packUnit = "unspecified",
        packaging = null,
        required = required,
        excluded = emptyList(),
        sourceText = instruction,
        supportingText = emptyList(),
        needsClarification = false,
        quantityText = "one",
        packText = null,
    )
    val primary = GroceryIntentReviewV2Quote(instruction, 0, instruction.length)
    val ready = GroceryIntentReviewV2ReadyRow(
        rowIndex = 0,
        item = item,
        evidence = GroceryIntentReviewV2Evidence(primary = primary, supporting = emptyList()),
        quantityOrigin = "stated",
        amountRoleStatus = "matched",
    )
    return GroceryIntentReviewV2(
        contractVersion = "grocery-intent-v2",
        requiresReview = true,
        instruction = instruction,
        sourceItemCount = 1,
        readyRows = listOf(ready),
        blockedRows = emptyList(),
    )
}
