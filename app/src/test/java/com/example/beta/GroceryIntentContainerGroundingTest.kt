package com.example.beta

import com.example.beta.automation.Quantity
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentContainerGroundingTest {
    @Test
    fun everyPackagingFormNormalizesItsSingularAndPlural(): Unit {
        val pairs = listOf(
            "pack" to "packs",
            "packet" to "packets",
            "bag" to "bags",
            "bottle" to "bottles",
            "carton" to "cartons",
            "tetra pack" to "tetra packs",
            "cup" to "cups",
            "can" to "cans",
            "tin" to "tins",
            "tub" to "tubs",
            "jar" to "jars",
            "box" to "boxes",
            "bar" to "bars",
            "block" to "blocks",
            "loaf" to "loaves",
            "bunch" to "bunches",
            "roll" to "rolls",
            "refill" to "refills",
            "pouch" to "pouches",
            "sachet" to "sachets",
            "tube" to "tubes",
            "spray" to "sprays",
            // Compact spelling is also accepted alongside the schema's spaced enum label.
            "tetrapack" to "tetrapacks",
        )

        pairs.forEach { (singular, plural) ->
            assertEquals("$singular / $plural", intentEvidenceTokens(singular), intentEvidenceTokens(plural))
        }
    }

    @Test
    fun differentContainersAndProductNamesRemainDifferentEvidence(): Unit {
        assertFalse(intentEvidenceTokens("can").containsAll(intentEvidenceTokens("carton")))
        assertFalse(intentEvidenceTokens("carton").containsAll(intentEvidenceTokens("can")))
        assertFalse(intentEvidenceTokens("box").containsAll(intentEvidenceTokens("bag")))
        assertFalse(intentEvidenceTokens("bag").containsAll(intentEvidenceTokens("box")))
        assertFalse(intentEvidenceTokens("milk").containsAll(intentEvidenceTokens("carton")))
        assertFalse(intentEvidenceTokens("carton").containsAll(intentEvidenceTokens("milk")))
    }

    @Test
    fun pluralCartonsGroundV2PackagingAndKeepSearchConstraint(): Unit {
        val result = review(
            instruction = "two cartons milk",
            item = item(
                name = "milk",
                sourceText = "two cartons milk",
                quantity = 2,
                quantityText = "two",
                packaging = "carton",
            ),
        ).prepareForReview()

        val parsed = result.items.single()
        assertEquals(Quantity.Count(2), parsed.quantity)
        assertTrue(parsed.logicalPieceCount.not())
        assertTrue(intentEvidenceTokens(parsed.query).containsAll(setOf("milk", "carton")))
        assertTrue(intentEvidenceTokens(parsed.strictMatchPhrase.orEmpty()).containsAll(setOf("milk", "carton")))
    }

    @Test
    fun tetraAndDietaryFormsArePreservedInV2Search(): Unit {
        val phrase = "two tetra packs of organic milk"
        val result = review(
            phrase,
            item(
                name = "milk",
                sourceText = phrase,
                quantity = 2,
                quantityText = "two",
                packaging = "tetra_pack",
                required = listOf("organic"),
            ).copy(quantityUnit = "pack"), // Source-proven outer count on the canonical wire.
        ).prepareForReview()

        val parsed = result.items.single()
        val tokens = intentEvidenceTokens(parsed.strictMatchPhrase.orEmpty())
        assertTrue(tokens.contains("tetrapack"))
        assertTrue(tokens.contains("organic"))
        assertTrue(tokens.contains("milk"))
    }

    @Test
    fun modelCannotInferUnspokenCartonPackaging(): Unit {
        val result = review(
            instruction = "two milk",
            item = item(
                name = "milk",
                sourceText = "two milk",
                quantity = 2,
                quantityText = "two",
                packaging = "carton",
            ),
        ).prepareForReview()

        assertTrue(result.items.isEmpty())
        assertEquals(listOf("two milk"), result.excludedText)
    }

    private fun review(instruction: String, item: GroceryIntentReviewV2Item) = GroceryIntentReviewV2(
        contractVersion = "grocery-intent-v2",
        requiresReview = true,
        instruction = instruction,
        sourceItemCount = 1,
        readyRows = listOf(
            GroceryIntentReviewV2ReadyRow(
                rowIndex = 0,
                item = item,
                evidence = GroceryIntentReviewV2Evidence(
                    GroceryIntentReviewV2Quote(item.sourceText, 0, item.sourceText.length),
                    emptyList(),
                ),
                quantityOrigin = if (item.quantity == null) "not_stated" else "stated",
                amountRoleStatus = if (item.quantity == null && item.packValue == null) "not_stated" else "matched",
            ),
        ),
        blockedRows = emptyList(),
    )

    private fun item(
        name: String,
        sourceText: String,
        quantity: Int,
        quantityText: String,
        packaging: String,
        required: List<String> = emptyList(),
    ) = GroceryIntentReviewV2Item(
        name = name,
        quantity = BigDecimal.valueOf(quantity.toLong()),
        quantityUnit = "count",
        packValue = null,
        packUnit = "unspecified",
        packaging = packaging,
        required = required,
        excluded = emptyList(),
        sourceText = sourceText,
        supportingText = emptyList(),
        needsClarification = false,
        quantityText = quantityText,
        packText = null,
    )
}
