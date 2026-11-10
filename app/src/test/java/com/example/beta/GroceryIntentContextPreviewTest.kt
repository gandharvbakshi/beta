package com.example.beta

import com.example.beta.SwiggyMcpClient.RecommendationCandidate
import com.example.beta.SwiggyMcpClient.Recommendations
import com.example.beta.automation.ParsedItem
import com.example.beta.automation.Quantity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryIntentContextPreviewTest {
    @Test
    fun compatibleProductsCanBePreviewedButHeldRowRemainsNonExecutable() {
        val instruction = "2 milk"
        val item = heldItem(instruction)
        val witness = witness(instruction, item)
        val milk = candidate("Amul Milk", "spin-milk", "sku-milk")
        val catalogue = recommendations(milk)
        val preview = GroceryIntentPendingCandidateAssessment.assess(instruction, 0, item, witness, catalogue)

        assertEquals(listOf(milk), preview.candidates)
        assertNull(preview.holdReason)
        assertTrue(preview.matches(instruction, 0, item, catalogue))
        assertTrue(item.requiresIntentQuantityProof)
        assertNull(item.intentQuantityProvenance)
        assertFalse(isSwiggyCandidateAllowed(item, milk))
        assertFalse(swiggyIntentQuantityCandidateChecked(item, milk))
        assertFalse(isSwiggyCandidateCountCompatible(item, milk))
        assertThrows(IllegalArgumentException::class.java) { swiggyRequestedCartQuantity(item, milk) }
        assertThrows(IllegalArgumentException::class.java) { swiggyRequestedItemFromParsedItem(milk, item) }
        assertThrows(IllegalStateException::class.java) { swiggyPreviewItemFromParsedItem(milk, item) }
    }

    @Test
    fun sugarFreeLowFatAndTetraPackRequirementsRejectMismatchedCandidates() {
        val cases = listOf(
            "sugar free milk" to candidate("Amul Milk", "sugar-free-mismatch"),
            "low fat milk" to candidate("Amul Full Cream Milk", "low-fat-mismatch"),
            "tetra pack milk" to candidate("Amul Pouch Milk", "tetra-pack-mismatch"),
        )
        cases.forEach { (query, wrongCandidate) ->
            val instruction = "2 milk"
            val item = heldItem(instruction, query = query)
            val assessment = GroceryIntentPendingCandidateAssessment.assess(
                instruction, 0, item, witness(instruction, item), recommendations(wrongCandidate),
            )
            assertTrue(query, assessment.candidates.isEmpty())
            assertEquals(query, "no_compatible_candidate", assessment.holdReason)
        }
    }

    @Test
    fun numericAmountAmbiguityScansEveryCandidateAndSeparateSuggestionBeforeFiltering() {
        val instruction = "2 milk"
        val item = heldItem(instruction)
        val safe = candidate("Amul Milk", "safe-milk")
        val ambiguous = candidate("Stage 2 Milk Powder", "ambiguous-milk")
        val catalogues = listOf(
            recommendations(safe, extra = listOf(ambiguous)),
            Recommendations(candidates = listOf(safe), suggested = ambiguous, query = "milk"),
        )

        catalogues.forEach { catalogue ->
            val assessment = GroceryIntentPendingCandidateAssessment.assess(
                instruction, 0, item, witness(instruction, item), catalogue,
            )
            assertTrue(assessment.candidates.isEmpty())
            assertEquals("numeric_product_quantity_ambiguous", assessment.holdReason)
        }
    }

    @Test
    fun invalidTypedQuantityMetadataCannotMakeCandidatePreviewCompatible() {
        val instruction = "2 milk"
        val item = heldItem(instruction)
        val invalid = candidate("Amul Milk", "invalid-typed", "invalid-sku").copy(
            catalogueQuantity = CatalogueQuantityEvidence(
                version = 2, source = "untrusted", spinId = "invalid-typed", skuId = "invalid-sku",
                fields = CatalogueQuantityFields(packSize = "500 ml"),
            ),
        )
        val assessment = GroceryIntentPendingCandidateAssessment.assess(
            instruction, 0, item, witness(instruction, item), recommendations(invalid),
        )

        assertTrue(assessment.candidates.isEmpty())
        assertEquals("no_compatible_candidate", assessment.holdReason)
    }

    @Test
    fun numericProductCodeNeedsTitleEvidenceAndRejectsConflictingFields() {
        val instruction = "2 CR2025 battery"
        val item = heldItem(instruction, query = "CR2025 battery")
        val titleEvidence = candidate("CR2025 Lithium Battery", "cr2025-title")
        val subtitleOnly = candidate("Lithium Battery", "cr2025-subtitle").copy(subtitle = "CR2025")
        val conflicting = titleEvidence.copy(spinId = "conflicting-code", skuId = "conflicting-code", variant = "CR2032")
        val assessment = GroceryIntentPendingCandidateAssessment.assess(
            instruction, 0, item, witness(instruction, item),
            Recommendations(candidates = listOf(titleEvidence, subtitleOnly, conflicting)),
        )

        assertEquals(listOf(titleEvidence), assessment.candidates)
        assertNull(assessment.holdReason)
    }

    @Test
    fun previewSnapshotBindsInstructionItemRowCandidateMetadataAndSuggestion() {
        val instruction = "2 milk"
        val item = heldItem(instruction)
        val witness = witness(instruction, item)
        val milk = candidate("Amul Milk", "snapshot-milk")
        val catalogue = recommendations(milk)
        val preview = GroceryIntentPendingCandidateAssessment.assess(instruction, 0, item, witness, catalogue)

        assertFalse(preview.matches("2 milk please", 0, item, catalogue))
        assertFalse(preview.matches(instruction, 1, item, catalogue))
        assertFalse(preview.matches(instruction, 0, item.copy(quantity = Quantity.Count(1)), catalogue))
        assertFalse(preview.matches(instruction, 0, item, recommendations(milk.copy(label = "Other Milk"))))
        assertFalse(preview.matches(instruction, 0, item, catalogue.copy(query = "different query")))
        assertFalse(preview.matches(instruction, 0, item, catalogue.copy(requiresConfirmation = true)))
        assertFalse(preview.matches(instruction, 0, item,
            catalogue.copy(suggested = milk.copy(subtitle = "changed suggestion metadata"))))
    }

    @Test
    fun assessmentDetachesMutableCandidateListAndSameSkuConflictsAreHeld() {
        val instruction = "2 milk"
        val item = heldItem(instruction)
        val witness = witness(instruction, item)
        val milk = candidate("Amul Milk", "same-spin", "same-sku")
        val mutableCandidates = mutableListOf(milk)
        val mutableCatalogue = Recommendations(candidates = mutableCandidates, suggested = milk)
        val assessment = GroceryIntentPendingCandidateAssessment.assess(
            instruction, 0, item, witness, mutableCatalogue,
        )
        mutableCandidates += candidate("Another Milk", "other-spin", "other-sku")
        assertEquals(listOf(milk), assessment.candidates)
        assertFalse(assessment.matches(instruction, 0, item, mutableCatalogue))

        val conflicting = GroceryIntentPendingCandidateAssessment.assess(
            instruction, 0, item, witness,
            Recommendations(candidates = listOf(milk), suggested = milk.copy(label = "Different Milk")),
        )
        assertTrue(conflicting.candidates.isEmpty())
        assertEquals("catalogue_candidate_conflict", conflicting.holdReason)
    }

    @Test
    fun suggestedFlagAloneIsNotAConflictingDescription() {
        val instruction = "2 milk"
        val item = heldItem(instruction)
        val ordinary = candidate("Amul Milk", "same-spin", "same-sku")
        val suggested = ordinary.copy(suggested = true)
        val assessment = GroceryIntentPendingCandidateAssessment.assess(
            instruction, 0, item, witness(instruction, item),
            Recommendations(candidates = listOf(ordinary), suggested = suggested),
        )

        assertEquals(listOf(ordinary), assessment.candidates)
        assertNull(assessment.holdReason)
    }

    @Test
    fun previewAssessmentStringIsRedacted() {
        val instruction = "PRIVATE 2 milk"
        val item = heldItem(instruction, source = "2 milk")
        val assessment = GroceryIntentPendingCandidateAssessment.assess(
            instruction, 0, item, witness(instruction, item), recommendations(candidate("Amul Milk", "redacted")),
        )

        assertFalse(assessment.toString().contains("PRIVATE"))
        assertFalse(assessment.toString().contains(instruction))
        assertNotNull(assessment.candidates)
    }

    @Test
    fun concreteRetailOfferPreservesRequestedCountAndNeverEnablesLegacyAction() {
        val instruction = "For the meeting get 2 milk"
        val item = heldItem(instruction, source = "2 milk")
        val milk = candidate("Amul Milk 500 ml", "milk-offer")
        val catalogue = recommendations(milk)
        val assessment = GroceryIntentPendingCandidateAssessment.assess(instruction, 0, item,
            witness(instruction, item), catalogue)
        val offer = requireNotNull(assessment.offerFor(milk))
        assertEquals(2, offer.requestedCount)
        assertEquals(milk, offer.candidate)
        assertTrue(offer.contents.display.contains("500"))
        assertTrue(offer.matches(instruction, 0, item, catalogue))
        assertFalse(offer.matches("$instruction, if available", 0, item, catalogue))
        assertFalse(offer.matches(instruction, 1, item, catalogue))
        assertFalse(offer.matches(instruction, 0, item, catalogue.copy(suggested = milk.copy(variant = "1 L"))))
        assertFalse(offer.toString().contains("milk"))
        assertNull(assessment.offerFor(milk.copy(variant = "1 L")))
        assertNull(item.intentQuantityProvenance)
        assertThrows(IllegalArgumentException::class.java) { swiggyRequestedCartQuantity(item, milk) }
    }

    @Test
    fun unknownOrBundledContentsCannotAcquireAnOfferOrImpliedDivisor() {
        val instruction = "2 milk"
        val item = heldItem(instruction)
        listOf("Amul Milk", "Amul Milk 2 x 500 ml", "Amul Milk about 500 ml").forEach { label ->
            val milk = candidate(label, "no-offer")
            val assessment = GroceryIntentPendingCandidateAssessment.assess(instruction, 0, item,
                witness(instruction, item), recommendations(milk))
            assertNull(label, assessment.offerFor(milk))
        }
    }

    @Test
    fun pendingRetailPackFlagCannotStandInForSourceRoleProof() {
        val instruction = "2 milk"
        val item = heldItem(instruction).copy(retailPackCount = true)
        val milk = candidate("Amul Milk 500 ml", "pack-flag")
        val assessment = GroceryIntentPendingCandidateAssessment.assess(instruction, 0, item,
            witness(instruction, item), recommendations(milk))
        assertEquals(listOf(milk), assessment.candidates)
        assertNull(assessment.offerFor(milk))
    }

    @Test
    fun genericHealthRequestRetainsExactProductBoundaryBeforePreview() {
        val instruction = "2 cough medicine"
        val item = heldItem(instruction, query = "cough medicine")
        assertTrue(swiggyNeedsExactHealthProduct(item))
        val medicine = candidate("Cough Medicine Syrup 100 ml", "health")
        val assessment = GroceryIntentPendingCandidateAssessment.assess(instruction, 0, item,
            witness(instruction, item), recommendations(medicine))
        assertEquals("exact_health_product_required", assessment.holdReason)
        assertTrue(assessment.candidates.isEmpty())
        assertNull(assessment.offerFor(medicine))
    }

    private fun heldItem(
        instruction: String,
        source: String = instruction,
        query: String = "milk",
        count: Int = 2,
    ) = ParsedItem(
        rawText = source,
        query = query,
        quantity = Quantity.Count(count),
        strictMatchPhrase = query,
        retailPackCount = false,
        requiresIntentQuantityProof = true,
        intentQuantityProvenance = null,
    )

    private fun witness(instruction: String, item: ParsedItem) =
        requireNotNull(GroceryIntentPendingCountWitness.compile(instruction, 0, item)) {
            "Synthetic source count must compile into a pending witness"
        }

    private fun candidate(label: String, spinId: String, skuId: String = spinId) =
        RecommendationCandidate(spinId = spinId, skuId = skuId, label = label)

    private fun recommendations(
        candidate: RecommendationCandidate,
        extra: List<RecommendationCandidate> = emptyList(),
    ) = Recommendations(candidates = listOf(candidate) + extra, suggested = candidate, query = "milk")
}
