package com.example.beta

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentServingRoleTest {
    @Test fun ambiguousCupOfCurdRemainsExcludedInsteadOfGuessingARetailSize() {
        val source = "one cup of curd"
        val row = servingRow(source, 0, "curd", source, quantity = "1", quantityUnit = "pack", quantityText = "one")
        assertEquals("non_retail_measure_as_purchase", row.numericOwnershipIssue())
    }

    @Test fun servingContentsCanBeInHindiWithoutBypassingGuard() {
        val explicit = "2 cups of चीनी"
        assertEquals("non_retail_measure_as_purchase", servingRow(explicit, 0, "चीनी", explicit,
            quantity = "2", quantityUnit = "pack", quantityText = "2").numericOwnershipIssue())
        val implicit = "cups of चीनी"
        assertEquals("non_retail_measure_as_purchase", servingRow(implicit, 0, "चीनी", implicit).numericOwnershipIssue())
        for (space in listOf("\u00a0", "\u2003", "\u202f")) {
            val counted = "2 cups of${space}flour"
            assertEquals("non_retail_measure_as_purchase", servingRow(counted, 0, "flour", counted,
                quantity = "2", quantityUnit = "pack", quantityText = "2").numericOwnershipIssue())
            val uncounted = "cups of${space}flour"
            assertEquals("non_retail_measure_as_purchase", servingRow(uncounted, 0, "flour", uncounted).numericOwnershipIssue())
        }
    }

    @Test fun finiteServingAndDoseGrammarIsRejectedForCountPackAndPieceInBothAmountFields() {
        val measures = listOf(
            "cup", "cups", "spray", "sprays", "spoon", "spoons", "teaspoon", "teaspoons",
            "tablespoon", "tablespoons", "pinch", "pinches", "handful", "handfuls", "scoop",
            "scoops", "drop", "drops", "ladle", "ladles", "sip", "sips", "dose", "doses",
            "serving", "servings", "portion", "portions",
        )
        val units = listOf("count", "pack", "piece")
        for (measure in measures) {
            for (unit in units) {
                for (isPack in listOf(false, true)) {
                    val source = "2 $measure of flour"
                    val row = servingRow(
                        instruction = source,
                        index = 0,
                        name = "flour",
                        source = source,
                        quantity = if (isPack) null else "2",
                        quantityUnit = if (isPack) "unspecified" else unit,
                        quantityText = if (isPack) null else "2",
                        packValue = if (isPack) "2" else null,
                        packUnit = if (isPack) unit else "unspecified",
                        packText = if (isPack) "2" else null,
                    )
                    assertEquals("$source unit=$unit isPack=$isPack", "non_retail_measure_as_purchase", row.numericOwnershipIssue())
                }
            }
        }
    }

    @Test fun rejectedServingRowDoesNotBlockValidNeighborDuringReviewPreparation() {
        val instruction = "2 cups of flour; one bread"
        val result = servingReview(instruction, listOf(
            servingRow(instruction, 0, "flour", "2 cups of flour", quantity = "2", quantityText = "2"),
            servingRow(instruction, 1, "bread", "one bread", quantity = "1", quantityText = "one"),
        )).prepareForReview()

        assertEquals(listOf("bread"), result.items.map { it.query })
        assertTrue(result.excludedText.any { it.contains("2 cups of flour") })
    }

    @Test fun unquantifiedCupOfContentIsRejectedWithoutBorrowingNeighborQuantity() {
        val instruction = "cup of flour; one bread"
        val flour = servingRow(instruction, 0, "flour", "cup of flour")
        assertEquals("non_retail_measure_as_purchase", flour.numericOwnershipIssue())

        val result = servingReview(instruction, listOf(
            flour,
            servingRow(instruction, 1, "bread", "one bread", quantity = "1", quantityText = "one"),
        )).prepareForReview()
        assertEquals(listOf("bread"), result.items.map { it.query })
        assertTrue(result.excludedText.any { it.contains("cup of flour") })
    }

    @Test fun productAndRetailNamesRemainEligibleForTheClosedServingGuard() {
        val positives = listOf(
            Triple("one cup noodles", "cup noodles", "one"),
            Triple("one measuring cup", "measuring cup", "one"),
            Triple("one spray bottle", "spray bottle", "one"),
            Triple("one jar of honey", "honey", "one"),
        )
        for ((source, name, amount) in positives) {
            val row = servingRow(source, 0, name, source, quantity = "1", quantityText = amount)
            assertNull("$source", row.numericOwnershipIssue())
        }
    }
}

private fun servingReview(instruction: String, rows: List<GroceryIntentReviewV2ReadyRow>) = GroceryIntentReviewV2(
    contractVersion = "grocery-intent-v2",
    requiresReview = true,
    instruction = instruction,
    sourceItemCount = rows.size,
    readyRows = rows,
    blockedRows = emptyList(),
)

private fun servingRow(
    instruction: String,
    index: Int,
    name: String,
    source: String,
    quantity: String? = null,
    quantityUnit: String = if (quantity == null) "unspecified" else "count",
    quantityText: String? = quantity,
    packValue: String? = null,
    packUnit: String = if (packValue == null) "unspecified" else "count",
    packText: String? = packValue,
) = GroceryIntentReviewV2ReadyRow(
    rowIndex = index,
    item = GroceryIntentReviewV2Item(
        name = name,
        quantity = quantity?.let { BigDecimal(it) },
        quantityUnit = quantityUnit,
        packValue = packValue?.let { BigDecimal(it) },
        packUnit = packUnit,
        packaging = null,
        required = emptyList(),
        excluded = emptyList(),
        sourceText = source,
        supportingText = emptyList(),
        needsClarification = false,
        quantityText = quantityText,
        packText = packText,
    ),
    evidence = GroceryIntentReviewV2Evidence(
        primary = servingQuote(instruction, source),
        supporting = emptyList(),
    ),
    quantityOrigin = if (quantity == null) "not_stated" else "stated",
    amountRoleStatus = if (quantity == null && packValue == null) "not_stated" else "matched",
)

private fun servingQuote(instruction: String, source: String): GroceryIntentReviewV2Quote {
    val start = instruction.indexOf(source)
    require(start >= 0)
    return GroceryIntentReviewV2Quote(source, start, start + source.length)
}
