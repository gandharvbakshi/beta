package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.SwiggyMcpClient.Recommendations
import com.example.beta.automation.Quantity
import com.example.beta.automation.IntentQuantityOrigin
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwiggyCatalogueRetailContentsTest {
    @Test
    fun explicitSkuBoundContentsAuthorizeOuterBoxUnitsNotInnerPieces() {
        listOf("Box of 6 pcs", "1 box (6 pcs)", "6 pcs per box", "6 pcs in a box").forEachIndexed { index, relation ->
            val prepared = prepared("two boxes of batteries", "batteries", "box", "pack", "2", "two")
            val candidate = candidate(relation)
            val assessed = assessSwiggyIntentQuantity(prepared, Recommendations(listOf(candidate)))
            assertTrue(relation, isSwiggyCandidateAllowed(assessed, candidate))
            assertTrue(relation, isSwiggyCandidateCountCompatible(assessed, candidate))
            assertEquals("relation $index", 2, swiggyRequestedCartQuantity(assessed, candidate))
        }
    }

    @Test
    fun metadataCanProveAbbreviatedTitleButBareContentsStayHeld() {
        val prepared = prepared("one box of batteries", "batteries", "box", "pack")
        val bare = candidate("", title = "Batteries Box 6 pcs")
        val bareAssessed = assessSwiggyIntentQuantity(prepared, Recommendations(listOf(bare)))
        assertFalse(isSwiggyCandidateCountCompatible(bareAssessed, bare))
        assertTrue(runCatching { swiggyRequestedCartQuantity(bareAssessed, bare) }.isFailure)

        val proven = candidate("Box of 6 pcs", title = "Batteries Box 6 pcs")
        val assessed = assessSwiggyIntentQuantity(prepared, Recommendations(listOf(proven)))
        assertTrue(isSwiggyCandidateAllowed(assessed, proven))
        assertEquals(1, swiggyRequestedCartQuantity(assessed, proven))
    }

    @Test
    fun explicitNestedPluralConflictAndOfferEvidenceNeverAuthorize() {
        val prepared = prepared("one box of batteries", "batteries", "box", "pack")
        listOf(
            "2 boxes of 6 pcs", "boxes of 6 pcs", "Packet of 6 pcs", "Box of 6 pcs; 12 pcs",
            "Box of +6 pcs", "Box of 6.5 pcs", "Box of 6 pcs in 2 boxes", "Box of 6 pcs buy one get one free",
        ).forEach { raw ->
            val candidate = candidate(raw)
            val assessed = assessSwiggyIntentQuantity(prepared, Recommendations(listOf(candidate)))
            assertFalse(raw, isSwiggyCandidateCountCompatible(assessed, candidate))
            assertTrue(raw, runCatching { swiggyRequestedCartQuantity(assessed, candidate) }.isFailure)
        }
    }

    @Test
    fun innerPieceGroupAndCorrectionStayHeldAndDefaultRemainsOnePack() {
        val candidate = candidate("Box of 6 pcs")
        val innerPiece = review("two boxes of 6 batteries", "batteries", "box", "pack", "2", "two").prepareForReview()
        assertTrue(innerPiece.items.isEmpty())
        assertTrue((innerPiece.unresolvedText + innerPiece.excludedText).contains("two boxes of 6 batteries"))

        val default = prepared("box of batteries", "batteries", "box", "unspecified", quantity = null, quantityText = null)
        val defaultAssessed = assessSwiggyIntentQuantity(default, Recommendations(listOf(candidate)))
        assertEquals(1, swiggyRequestedCartQuantity(defaultAssessed, candidate))

        val ordinary = prepared("one box of batteries", "batteries", "box", "pack")
        val proof = ordinary.intentQuantityProvenance!!
        val grouped = ordinary.copy(intentQuantityProvenance = proof.copy(
            origin = IntentQuantityOrigin.GROUP, local = null,
        ))
        assertHeld(grouped, candidate)
        val corrected = ordinary.copy(intentQuantityProvenance = proof.copy(corrected = proof.local))
        assertHeld(corrected, candidate)
    }

    @Test
    fun tamperedReceiptOrChangedSkuEvidenceInvalidatesAssessment() {
        val prepared = prepared("one box of batteries", "batteries", "box", "pack")
        val candidate = candidate("Box of 6 pcs")
        val assessed = assessSwiggyIntentQuantity(prepared, Recommendations(listOf(candidate)))
        assertTrue(isSwiggyCandidateCountCompatible(assessed, candidate))

        val tamperedProof = prepared.copy(intentQuantityProvenance =
            prepared.intentQuantityProvenance!!.copy(retailContainer = "packet"))
        assertFalse(isSwiggyCandidateCountCompatible(assessed.copy(
            intentQuantityProvenance = tamperedProof.intentQuantityProvenance,
        ), candidate))
        assertTrue(runCatching { swiggyRequestedCartQuantity(assessed.copy(
            intentQuantityProvenance = tamperedProof.intentQuantityProvenance,
        ), candidate) }.isFailure)

        val changed = candidate.copy(skuId = "sku-changed")
        assertFalse(isSwiggyCandidateCountCompatible(assessed, changed))
        assertTrue(runCatching { swiggyRequestedCartQuantity(assessed, changed) }.isFailure)
    }

    @Test
    fun contradictoryRawFieldsDietAndProductIdentityRemainProtected() {
        val prepared = prepared("one box of organic batteries", "organic batteries", "box", "pack",
            required = listOf("organic"))
        val disagreement = candidate("Box of 6 pcs", variant = "Packet of 6 pcs")
        assertHeld(prepared, disagreement)
        val otherProduct = candidate("Box of 6 pcs", title = "Batteries Box", variantName = "Box of 6 pcs",
            quantityDescription = "Box of 6 pcs", sku = "other-product")
        val assessed = assessSwiggyIntentQuantity(prepared, Recommendations(listOf(otherProduct)))
        assertFalse(isSwiggyCandidateAllowed(assessed, otherProduct))
        assertFalse(isSwiggyCandidateCountCompatible(assessed, otherProduct))
        assertTrue(runCatching { swiggyRequestedCartQuantity(assessed, otherProduct) }.isFailure)

        val wrongDiet = candidate("Box of 6 pcs", title = "Batteries Box", variantName = "Box of 6 pcs",
            quantityDescription = "Box of 6 pcs", subtitle = "non-organic")
        assertHeld(prepared, wrongDiet)
        val correct = candidate("Box of 6 pcs", title = "Organic Batteries Box", variantName = "Box of 6 pcs",
            quantityDescription = "Box of 6 pcs")
        val correctAssessed = assessSwiggyIntentQuantity(prepared, Recommendations(listOf(correct)))
        assertTrue(correctAssessed.query.contains("organic"))
        assertEquals(1, swiggyRequestedCartQuantity(correctAssessed, correct))
    }

    @Test
    fun catalogueFieldPermutationAndGenericContainerVariationAreStable() {
        val prepared = prepared("one box of batteries", "batteries", "box", "pack")
        val fields = CatalogueQuantityFields(variantName = "Box of 6 pcs", quantityDescription = "6 pcs per box")
        val reversed = CatalogueQuantityFields(quantityDescription = "Box of 6 pcs", variantName = "6 pcs per box")
        val first = candidateWithFields(fields)
        val second = candidateWithFields(reversed)
        val firstAssessed = assessSwiggyIntentQuantity(prepared, Recommendations(listOf(first)))
        val secondAssessed = assessSwiggyIntentQuantity(prepared, Recommendations(listOf(second)))
        assertEquals(swiggyRequestedCartQuantity(firstAssessed, first), swiggyRequestedCartQuantity(secondAssessed, second))

        listOf("packet", "bottle", "carton", "bag").forEach { container ->
            val source = "one $container of batteries"
            val row = prepared(source, "batteries", container, "pack")
            val metadata = "6 pcs per $container"
            val itemCandidate = candidate(metadata, title = "Batteries ${container.replaceFirstChar { it.uppercase() }}")
            val assessed = assessSwiggyIntentQuantity(row, Recommendations(listOf(itemCandidate)))
            if (container == "packet") assertReviewedRetailProposal(assessed, itemCandidate, 1)
            else assertEquals(container, 1, swiggyRequestedCartQuantity(assessed, itemCandidate))
        }
    }

    @Test
    fun unprovenPackageCountNeverFallsBackToInnerPieceDivision() {
        for (count in listOf(1, 2, 6, 12)) {
            val item = prepared("$count boxes of batteries", "batteries", "box", "pack", "$count", "$count")
            for (text in listOf("Box 6 pcs", "boxes of 6 pcs", "2 boxes of 6 pcs")) {
                assertHeld(item, candidate(text))
                assertHeld(item, candidate(text).copy(variant = text, catalogueQuantity = null))
            }
        }
    }

    @Test
    fun sameDimensionConflictsAreHeldBeforeAnyLegacyArithmetic() {
        for ((first, second) in listOf("500 ml" to "1 L", "6 pcs" to "12 pcs", "500 g" to "250 g",
                "10 g protein per serving" to "20 g protein per serving")) {
            val candidate = candidateWithFields(CatalogueQuantityFields(variantName = first, packSize = second))
            assertTrue("$first / $second", swiggyCatalogueQuantityConflict(candidate))
        }
        val same = candidateWithFields(CatalogueQuantityFields(variantName = "500 g", packSize = "0.5 kg"))
        assertFalse(swiggyCatalogueQuantityConflict(same))
        val independent = candidateWithFields(CatalogueQuantityFields(variantName = "500 g", packSize = "6 pcs"))
        assertFalse(swiggyCatalogueQuantityConflict(independent))
    }

    @Test
    fun unownedAmountsAndUnstatedAdditionalContainersNeverCompleteAProof() {
        val item = prepared("one box of batteries", "batteries", "box", "pack")
        for (extra in listOf("-6 pcs per box", "Box of -6 pcs", "1.5 box of 6 pcs", "6 pcs x 2",
                "Box of 6 pcs and box", "Box of 6 pcs with box", "Box of 6 pcs + box", "Box of 6 pcs; 12")) {
            val candidate = candidateWithFields(CatalogueQuantityFields(variantName = "Box of 6 pcs", subTitle = extra))
            assertHeld(item, candidate)
        }
    }

    @Test
    fun negatedAndQualifiedRelationshipsCannotEstablishOrCorroborateContents() {
        val item = prepared("one box of batteries", "batteries", "box", "pack")
        for (claim in listOf("not Box of 6 pcs", "without Box of 6 pcs", "replacement for Box of 6 pcs", "fits Box of 6 pcs")) {
            assertHeld(item, candidateWithFields(CatalogueQuantityFields(variantName = claim)))
            assertHeld(item, candidateWithFields(CatalogueQuantityFields(variantName = "Box of 6 pcs", subTitle = claim)))
        }
    }

    @Test
    fun defaultPackPreservedInLegacyAndV2ButExplicitCountsStayHeldWithoutReceipt() {
        val candidate = candidate("Box of 6 pcs")
        val legacy = com.example.beta.automation.ParsedItem("batteries", "batteries", Quantity.Default)
        assertTrue(isSwiggyCandidateCountCompatible(legacy, candidate))
        assertEquals(1, swiggyRequestedCartQuantity(legacy, candidate))
        assertHeld(legacy.copy(quantity = Quantity.Count(12), logicalPieceCount = true), candidate)
        assertHeld(legacy.copy(quantity = Quantity.Count(2)), candidate)
        val v2 = prepared("box of batteries", "batteries", "box", "unspecified", quantity = null, quantityText = null)
        val checked = assessSwiggyIntentQuantity(v2, Recommendations(listOf(candidate)))
        assertEquals(1, swiggyRequestedCartQuantity(checked, candidate))
    }

    @Test
    fun directDtoReceiptStillRequiresExactSourceAndSameSku() {
        val row = prepared("one box of batteries", "batteries", "box", "pack")
        val good = candidate("Box of 6 pcs")
        assertTrue(groceryIntentSourceRetailReceiptValid(row.intentQuantityProvenance!!))
        assertTrue(isSwiggyCandidateCountCompatible(assessSwiggyIntentQuantity(row, Recommendations(listOf(good))), good))
        val mismatch = good.copy(catalogueQuantity = good.catalogueQuantity!!.copy(skuId = "different"))
        assertFalse(isSwiggyCandidateCountCompatible(assessSwiggyIntentQuantity(row, Recommendations(listOf(mismatch))), mismatch))
    }

    private fun assertHeld(item: com.example.beta.automation.ParsedItem, candidate: RecommendationCandidate) {
        val assessed = assessSwiggyIntentQuantity(item, Recommendations(listOf(candidate)))
        assertFalse(isSwiggyCandidateCountCompatible(assessed, candidate))
        assertTrue(runCatching { swiggyRequestedCartQuantity(assessed, candidate) }.isFailure)
    }

    private fun candidate(
        relation: String,
        title: String = "Batteries Box",
        variant: String? = null,
        variantName: String = relation,
        quantityDescription: String? = null,
        subtitle: String? = null,
        sku: String = "sku-1",
    ) = candidateWithFields(CatalogueQuantityFields(
        variantName = variantName.takeIf(String::isNotEmpty),
        quantityDescription = quantityDescription,
    ), title, variant, subtitle, sku)

    private fun candidateWithFields(
        fields: CatalogueQuantityFields,
        title: String = "Batteries Box",
        variant: String? = null,
        subtitle: String? = null,
        sku: String = "sku-1",
    ): RecommendationCandidate {
        val evidence = CatalogueQuantityEvidence(1, "search_variation", "spin-1", sku, fields)
        return RecommendationCandidate("spin-1", title, variant, subtitle, skuId = sku, catalogueQuantity = evidence)
    }

    private fun prepared(
        instruction: String,
        name: String,
        packaging: String,
        unit: String,
        quantity: String? = "1",
        quantityText: String? = "one",
        required: List<String> = emptyList(),
    ) = review(instruction, name, packaging, unit, quantity, quantityText, required).prepareForReview().items.single()

    private fun review(
        instruction: String,
        name: String,
        packaging: String,
        unit: String,
        quantity: String? = "1",
        quantityText: String? = "one",
        required: List<String> = emptyList(),
    ): GroceryIntentReviewV2 {
        val item = GroceryIntentReviewV2Item(
            name = name, quantity = quantity?.let(::BigDecimal), quantityUnit = unit,
            packValue = null, packUnit = "unspecified", packaging = packaging, required = required,
            excluded = emptyList(), sourceText = instruction, supportingText = emptyList(),
            needsClarification = false, quantityText = quantityText, packText = null,
        )
        val quote = GroceryIntentReviewV2Quote(instruction, 0, instruction.length)
        return GroceryIntentReviewV2(
            "grocery-intent-v2", true, instruction, 1,
            listOf(GroceryIntentReviewV2ReadyRow(0, item, GroceryIntentReviewV2Evidence(quote, emptyList()), "stated", "matched")),
            emptyList(),
        )
    }

}
