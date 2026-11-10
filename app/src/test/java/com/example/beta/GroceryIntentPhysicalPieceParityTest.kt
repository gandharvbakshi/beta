package com.example.beta

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

class GroceryIntentPhysicalPieceParityTest {
    @Test
    fun sourceExplicitPhysicalPieceSizeRemainsHeldAcrossQuantityUnitEnums() {
        val cases = listOf(
            "two 100 g chocolate pieces" to "chocolate",
            "two 100 g soap pieces" to "soap",
            "two 100 g cheese pieces" to "cheese",
            "two 100 g samosa pieces" to "samosa",
        )
        for ((source, name) in cases) {
            for (unit in listOf("count", "pack", "piece")) {
                val issue = legacyItem(source, name, unit, quantity = 2, packValue = "100")
                    .legacyNumericOwnershipIssue()
                assertEquals("$source / $unit", "physical_piece_size_ambiguous", issue)
            }
        }
    }

    @Test
    fun sourceEachEvidenceAndValidUnitRolesRemainAccepted() {
        val sourceEach = "two cheese pieces 100 g each"
        assertEquals(null, legacyItem(sourceEach, "cheese", "piece", 2, "100").legacyNumericOwnershipIssue())
        assertEquals(null, legacyItem(sourceEach, "cheese", "count", 2, "100").legacyNumericOwnershipIssue())

        val namedRetail = "two 100 g chocolate bars"
        for (unit in listOf("count", "pack")) {
            assertEquals(
                "$namedRetail / $unit",
                null,
                legacyItem(namedRetail, "chocolate bars", unit, 2, "100").legacyNumericOwnershipIssue(),
            )
        }
        val containerFirst = "one bar of 100 g chocolate"
        for (unit in listOf("count", "pack", "piece")) {
            assertEquals("$containerFirst / $unit", null,
                legacyItem(containerFirst, "chocolate", unit, 1, "100").legacyNumericOwnershipIssue())
        }
    }

    private fun legacyItem(
        source: String,
        name: String,
        quantityUnit: String,
        quantity: Int,
        packValue: String,
    ) = GroceryIntentItem(
        name = name,
        quantity = BigDecimal(quantity),
        quantityUnit = quantityUnit,
        packValue = BigDecimal(packValue),
        packUnit = "g",
        required = emptyList(),
        excluded = emptyList(),
        sourceText = source,
        needsClarification = false,
    )
}
