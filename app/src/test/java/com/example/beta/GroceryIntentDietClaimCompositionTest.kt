package com.example.beta

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GroceryIntentDietClaimCompositionTest {
    @Test fun exactRetainedNegativeClaimIsScopedToItsExcludedAttribute() {
        val source = "two 250 g tomato ketchup packets without added sugar"
        assertNull(row(source, excluded = listOf("added sugar")).numericOwnershipIssue())
        assertNull(legacyItem(source, excluded = listOf("added sugar")).legacyNumericOwnershipIssue())
        assertEquals("composition_pack_role_unproven",
            legacyItem(source, excluded = listOf("sugar")).legacyNumericOwnershipIssue())
    }

    @Test fun missingOrWrongExcludedAttributeDoesNotClearCompositionRole() {
        val source = "two 250 g tomato ketchup packets without added sugar"
        assertEquals("composition_pack_role_unproven", row(source).numericOwnershipIssue())
        assertEquals("composition_pack_role_unproven",
            row(source, excluded = listOf("sugar")).numericOwnershipIssue())
        assertEquals("composition_pack_role_unproven",
            row(source, excluded = listOf("sugar-free")).numericOwnershipIssue())
    }

    @Test fun matchingRequiredNoAddedSugarPropertyRetainsItsClaim() {
        val source = "two 250 g tomato ketchup packets without added sugar"
        assertNull(row(source, required = listOf("no added sugar")).numericOwnershipIssue())
    }

    @Test fun qualifiedOrScopedNegativeClaimIsNotAnExactRetainedClaim() {
        listOf(
            "almost without added sugar",
            "not without added sugar",
            "without added sugar per serving",
        ).forEach { claim ->
            val source = "two 250 g tomato ketchup packets $claim"
            assertEquals(source, "composition_pack_role_unproven",
                row(source, excluded = listOf("added sugar")).numericOwnershipIssue())
        }
    }

    @Test fun aSecondQualifiedNegativeClaimDoesNotBorrowTheFirstClaim() {
        val source = "two 250 g tomato ketchup packets without added sugar, almost without added salt"
        assertEquals("composition_pack_role_unproven",
            row(source, excluded = listOf("added sugar", "added salt")).numericOwnershipIssue())
    }

    @Test fun negativeClaimDoesNotExemptSeparatePositiveNutrientOrContext() {
        val source = "two 250 g tomato ketchup packets without added sugar, contains sugar"
        assertEquals("composition_pack_role_unproven",
            row(source, excluded = listOf("added sugar")).numericOwnershipIssue())

        val cleanSource = "two 250 g tomato ketchup packets without added sugar"
        assertEquals("composition_pack_role_unproven",
            row(cleanSource, excluded = listOf("added sugar")).numericOwnershipIssue(
                compositionContext = "10 g protein per serving"))
    }

    @Test fun totalAndDenominatorRolesRemainBlockedDespiteDietClaim() {
        val total = "two tomato ketchup packets, total 250 g, without added sugar"
        assertEquals(
            "composition_pack_role_unproven",
            row(total, excluded = listOf("added sugar"), packText = "250").numericOwnershipIssue(),
        )

        val denominator = "two tomato ketchup packets per serving of 10 g without added sugar"
        assertEquals("composition_pack_role_unproven",
            row(denominator, excluded = listOf("added sugar"), packText = "10").numericOwnershipIssue())
    }

    private fun row(
        source: String,
        name: String = "tomato ketchup",
        required: List<String> = emptyList(),
        excluded: List<String> = emptyList(),
        quantityText: String = "two",
        packText: String = "250",
    ): GroceryIntentReviewV2ReadyRow {
        val item = GroceryIntentReviewV2Item(
            name = name,
            quantity = BigDecimal(if (quantityText == "one") "1" else "2"),
            quantityUnit = "count",
            packValue = BigDecimal(packText),
            packUnit = "g",
            packaging = "packet",
            required = required,
            excluded = excluded,
            sourceText = source,
            supportingText = emptyList(),
            needsClarification = false,
            quantityText = quantityText,
            packText = packText,
        )
        return GroceryIntentReviewV2ReadyRow(
            rowIndex = 0,
            item = item,
            evidence = GroceryIntentReviewV2Evidence(
                primary = GroceryIntentReviewV2Quote(source, 0, source.length),
                supporting = emptyList(),
            ),
            quantityOrigin = "stated",
            amountRoleStatus = "matched",
        )
    }

    private fun legacyItem(source: String, excluded: List<String>): GroceryIntentItem = GroceryIntentItem(
        name = "tomato ketchup",
        quantity = BigDecimal("2"),
        quantityUnit = "count",
        packValue = BigDecimal("250"),
        packUnit = "g",
        required = emptyList(),
        excluded = excluded,
        sourceText = source,
        needsClarification = false,
    )
}
