package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.SwiggyMcpClient.Recommendations
import com.example.beta.automation.Quantity
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentSprayRoleContractTest {
    @Test fun namedSprayDoesNotInferRetailUnitWithoutCatalogueAmount() {
        val source = "one BrandA red cockroach killer spray"
        val prepared = prepareSpray(source, "BrandA red cockroach killer spray", 1, packaging = "spray")
        assertEquals(1, prepared.items.size)
        val item = prepared.items.single()
        assertEquals(Quantity.Count(1), item.quantity)
        assertTrue(item.requiresIntentQuantityProof)

        val exact = candidate("BrandA red cockroach killer spray")
        val assessed = assessSwiggyIntentQuantity(item, Recommendations(listOf(exact)))
        assertTrue(isSwiggyCandidateAllowed(assessed, exact))
        assertUnqualifiedCountNeedsReview(assessed, exact, 1)

        val changedVariant = candidate("BrandA green cockroach killer spray")
        val changedAssessment = assessSwiggyIntentQuantity(item, Recommendations(listOf(changedVariant)))
        assertFalse(isSwiggyCandidateAllowed(changedAssessment, changedVariant))
    }

    @Test fun twoNamedSpraysPreserveTwoButCannotSilentlySelectBundleRole() {
        val source = "two BrandA red cockroach killer sprays"
        val prepared = prepareSpray(source, "BrandA red cockroach killer sprays", 2, packaging = "spray")
        assertEquals(1, prepared.items.size)
        val item = prepared.items.single()
        assertEquals(Quantity.Count(2), item.quantity)

        val single = candidate("BrandA red cockroach killer sprays")
        val singleAssessed = assessSwiggyIntentQuantity(item, Recommendations(listOf(single)))
        assertTrue(isSwiggyCandidateAllowed(singleAssessed, single))
        assertUnqualifiedCountNeedsReview(singleAssessed, single, 2)

        val exactBundle = candidate("BrandA red cockroach killer spray pack of 2")
        val bundleAssessed = assessSwiggyIntentQuantity(item, Recommendations(listOf(exactBundle)))
        assertTrue(isSwiggyCandidateAllowed(bundleAssessed, exactBundle))
        assertUnqualifiedCountNeedsReview(bundleAssessed, exactBundle, 2)

        val oneSource = "one BrandA red cockroach killer spray"
        val one = prepareSpray(oneSource, "BrandA red cockroach killer spray", 1, packaging = "spray").items.single()
        val oneAssessed = assessSwiggyIntentQuantity(one, Recommendations(listOf(exactBundle)))
        assertFalse(isSwiggyCandidateCountCompatible(oneAssessed, exactBundle))
        assertThrows(IllegalArgumentException::class.java) {
            swiggyRequestedCartQuantity(oneAssessed, exactBundle)
        }
    }

    @Test fun applicationSpraysAndDosesCannotBeReinterpretedAsRetailPacks() {
        val nonRetailAmounts = listOf(
            Triple("two sprays of cooking oil", "cooking oil", 2),
            Triple("one dose of cough syrup", "cough syrup", 1),
        )
        nonRetailAmounts.forEach { (source, name, amount) ->
            val preparation = prepareSpray(
                source,
                name,
                amount,
                quantityUnit = "count",
                packaging = if (source.contains("sprays")) "spray" else null,
                amountRoleStatus = "matched", // A claimed match cannot turn a dose into a purchase.
            )
            assertTrue("Application amount must stay held: $source", preparation.items.isEmpty())
            assertTrue("Original application amount must remain for review: $source",
                preparation.unresolvedText.isNotEmpty() || preparation.excludedText.isNotEmpty())
        }
    }

    @Test fun declaredSprayBottlePackagingDoesNotReplaceMissingSourceCountRole() {
        val source = "one BrandA insect repellent spray bottle"
        val prepared = prepareSpray(source, "BrandA insect repellent spray bottle", 1, packaging = "bottle")
        assertEquals(1, prepared.items.size)
        val item = prepared.items.single()
        val exact = candidate("BrandA insect repellent spray bottle")
        val assessed = assessSwiggyIntentQuantity(item, Recommendations(listOf(exact)))
        assertTrue(isSwiggyCandidateAllowed(assessed, exact))
        // This V2 fixture declares count, not pack, and has no bound retail-container receipt.
        // Identity remains eligible but must not silently promote its unresolved count role.
        assertUnqualifiedCountNeedsReview(assessed, exact, 1)
    }
}

private fun prepareSpray(
    instruction: String,
    name: String,
    amount: Int,
    quantityUnit: String = "count",
    packaging: String?,
    amountRoleStatus: String = "unresolved",
) = GroceryIntentReviewV2(
    contractVersion = "grocery-intent-v2",
    requiresReview = true,
    instruction = instruction,
    sourceItemCount = 1,
    readyRows = listOf(GroceryIntentReviewV2ReadyRow(
        rowIndex = 0,
        item = GroceryIntentReviewV2Item(
            name = name,
            quantity = BigDecimal.valueOf(amount.toLong()),
            quantityUnit = quantityUnit,
            packValue = null,
            packUnit = "unspecified",
            packaging = packaging,
            required = emptyList(),
            excluded = emptyList(),
            sourceText = instruction,
            supportingText = emptyList(),
            needsClarification = false,
            quantityText = if (amount == 1) "one" else "two",
            packText = null,
        ),
        evidence = GroceryIntentReviewV2Evidence(
            primary = GroceryIntentReviewV2Quote(instruction, 0, instruction.length),
            supporting = emptyList(),
        ),
        quantityOrigin = "stated",
        amountRoleStatus = amountRoleStatus,
    )),
    blockedRows = emptyList(),
).prepareForReview()

private fun candidate(label: String) = RecommendationCandidate(
    spinId = label.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-'),
    label = label,
)
